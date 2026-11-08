;; SPEC §7C.1 / ADR 0039 — the EMBEDDABLE MCP server: a Ring handler.
;;
;;   (def h (mcp-handler tk {:authenticate … :list-tools … :before-call …}))
;;   ((:handler h) ring-request)  ;=> ring-response
;;   (notify-tools-changed! h)
;;
;; `serve` (§7C) owns a listener and one static tool list. `mcp-handler` is what
;; a host mounts on its own Ring stack, behind its own TLS, CORS and OAuth. Every
;; policy is a hook; toolnexus knows nothing of tenants, users or storage.
;;
;; The transport is plain JSON-RPC streamable HTTP (MCP 2025-06-18) written by
;; hand, mirroring golang/mcphandler.go: status codes, error codes, message
;; strings and omit-empty rules are the Go reference's.
;;
;; STREAMING. A Ring response body may be a seq; adapters write each element in
;; turn. Two responses use that:
;;   * GET (Accept: text/event-stream) — a lazy seq of SSE events that blocks for
;;     the next `notifications/tools/list_changed`; it ends when the session is
;;     DELETEd. (Ring cannot see a client disconnect, so a dead stream only stops
;;     receiving when its bounded queue is full — see `notify-tools-changed!`.)
;;   * a POST tools/call whose `:before-call` says :confirm, from a session that
;;     declared the elicitation capability — the response upgrades to SSE: first
;;     the `elicitation/create` request, then (after the client POSTs its answer)
;;     the call's JSON-RPC response.
;; Everything else is a plain string body.
;;
;; Portability: no reader conditional, no java.* — koine + toolnexus only.
;; Threads are koine.process/run-async!, waits are atom polls with
;; koine.time/sleep! (the blocking 3-arity deref is not in a cljgo release).
(ns toolnexus.mcp-handler
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.codec :as codec]
            [koine.fs :as fs]
            [koine.time :as ktime]
            [toolnexus.frontmatter :as frontmatter]
            [toolnexus.mcp-crypto :as crypto]
            [toolnexus.serve :as serve]
            [toolnexus.tool :as tool]))

(def protocol-2026
  "The MCP 2026-07-28 wire (SPEC §7C.2) — opt-in only, never a default."
  "2026-07-28")

(def implemented-versions
  "MCP protocol versions whose wire this handler implements (ADR 0039/0040).
  2026-07-28 is the stateless wire of SPEC §7C.2 and is served only when listed
  in `:protocol-versions`."
  ["2026-07-28" "2025-11-25" "2025-06-18"])

(def ^:private default-versions ["2025-06-18"])
(def ^:private default-name "toolnexus")
(def ^:private default-version "0.1.0")

;; ---------------------------------------------------------------------------
;; ctx accessors — every hook and every :context? tool receives this map.
;; ---------------------------------------------------------------------------

(defn mcp-identity     "What :authenticate returned (nil without it)." [ctx] (:identity ctx))
(defn mcp-request      "The inbound Ring request." [ctx] (:request ctx))
(defn mcp-session-id   "The Mcp-Session-Id of the request (\"\" if none)." [ctx] (or (:session-id ctx) ""))
(defn mcp-request-meta "The JSON-RPC request's `params._meta` (nil when absent)." [ctx] (:meta ctx))

;; ---------------------------------------------------------------------------
;; MCPAuthError — an :authenticate failure with an explicit status + challenge
;; ---------------------------------------------------------------------------

(defn mcp-auth-error
  "SPEC §7C.2 `MCPAuthError`: THROW this from any `:authenticate` hook to answer
  `status` (401|403; nil ⇒ 401) with `WWW-Authenticate: challenge` (a non-empty
  `:www-authenticate` result wins) and the body `{\"error\":\"unauthorized\"}`,
  on both wires. `reason` is the exception message and never reaches the wire."
  ([status challenge] (mcp-auth-error status challenge nil))
  ([status challenge reason]
   (ex-info (or (not-empty reason) "unauthorized")
            {:toolnexus/mcp-auth-error true :status status :challenge challenge})))

(defn mcp-auth-error?
  "True when `e` is an `mcp-auth-error`."
  [e]
  (true? (:toolnexus/mcp-auth-error (ex-data e))))

;; ---------------------------------------------------------------------------
;; small helpers
;; ---------------------------------------------------------------------------

(defn- utf8-count [s]
  (reduce (fn [n cp] (+ n (cond (< cp 0x80) 1 (< cp 0x800) 2 (< cp 0x10000) 3 :else 4)))
          0 (tool/code-points s)))

(defn- header [req k] (get (:headers req) k))

(defn- omit-empty [m]
  (into {} (remove (fn [[_ v]] (or (nil? v) (= "" v))) m)))

(defn- text-response [status msg]
  {:status  status
   :headers {"Content-Type" "text/plain; charset=utf-8" "X-Content-Type-Options" "nosniff"}
   :body    (str msg "\n")})

(defn- json-response [status m]
  {:status status :headers {"Content-Type" "application/json"} :body (str (json/write-str m) "\n")})

(defn- rpc-error-response
  "A transport-level JSON-RPC error: HTTP 400, id null unless given."
  [id code message]
  {:status  400
   :headers {"Content-Type" "application/json"}
   :body    (str (json/write-str {:jsonrpc "2.0" :id id :error {:code code :message message}}) "\n")})

(defn- rpc-msg [id result rerr]
  (if rerr
    {:jsonrpc "2.0" :id id :error rerr}
    {:jsonrpc "2.0" :id id :result result}))

(defn- sse-event [m] (str "event: message\ndata: " (json/write-str m) "\n\n"))

(defn- msg-of [t] (or (ex-message t) (str t)))

(defn- await-value
  "Poll `f` until it returns non-nil or `ms` elapses (nil)."
  [f ms]
  (let [deadline (+ (ktime/mono-ms) ms)]
    (loop []
      (or (f)
          (when (< (ktime/mono-ms) deadline)
            (ktime/sleep! 2)
            (recur))))))

;; ---------------------------------------------------------------------------
;; default session ids — an in-memory set
;; ---------------------------------------------------------------------------

(defn memory-session-ids
  "The default `:session-ids`: {:generate :validate :terminate} over an atom.
  A host plugs its own (signed ids, a DB) so any replica validates any session."
  []
  (let [live (atom #{})]
    {:generate  (fn [_ctx] (let [id (str (random-uuid))
                                 id (str/replace id "-" "")]
                             (swap! live conj id) id))
     :validate  (fn [_ctx id] (contains? @live id))
     :terminate (fn [_ctx id] (swap! live disj id) nil)}))

;; ---------------------------------------------------------------------------
;; wire shapes
;; ---------------------------------------------------------------------------

(defn wire-tool
  "The tools/list entry: name verbatim, description, inputSchema, outputSchema
  when set, annotations (`:annotations` verbatim; else derived from `:hints`;
  else omitted)."
  [t]
  (let [ann (cond
              (:annotations t)
              (let [a (:annotations t)]
                (cond-> {}
                  (seq (str (or (:title a) ""))) (assoc :title (:title a))
                  (boolean? (:readOnlyHint a))    (assoc :readOnlyHint (:readOnlyHint a))
                  (boolean? (:destructiveHint a)) (assoc :destructiveHint (:destructiveHint a))
                  (boolean? (:idempotentHint a))  (assoc :idempotentHint (:idempotentHint a))
                  (boolean? (:openWorldHint a))   (assoc :openWorldHint (:openWorldHint a))))

              (:hints t)
              (let [h (:hints t)]
                {:readOnlyHint    (true? (:read-only h))
                 :destructiveHint (true? (:destructive h))
                 :openWorldHint   (true? (:open-world h))})

              :else {})]
    (cond-> {:name        (:name t)
             :description (or (:description t) "")
             :inputSchema (or (:input-schema t) {:type "object"})}
      (:output-schema t) (assoc :outputSchema (:output-schema t))
      (seq ann)          (assoc :annotations ann))))

;; ---------------------------------------------------------------------------
;; the handler state
;; ---------------------------------------------------------------------------

(defn- normalize-versions [vs]
  (let [ok (reduce (fn [acc v]
                     (if (and (some #{v} implemented-versions) (not (some #{v} acc)))
                       (conj acc v) acc))
                   [] vs)]
    (if (seq ok) ok default-versions)))

(defn- check-session
  "[:ok ctx sid] or [:fail ring-response] — MCP-Protocol-Version, then (stateful)
  Mcp-Session-Id presence (400) and validity (404)."
  [{:keys [cfg ids versions]} ctx req]
  (let [v (header req "mcp-protocol-version")]
    (cond
      (and (seq v) (not (some #{v} versions)))
      [:fail (text-response 400 "Bad Request: unsupported MCP-Protocol-Version")]

      (:stateless cfg) [:ok ctx ""]

      :else
      (let [sid (header req "mcp-session-id")]
        (cond
          (empty? sid) [:fail (text-response 400 "Bad Request: missing Mcp-Session-Id")]
          (not (try (true? ((:validate ids) ctx sid)) (catch Throwable _ false)))
          [:fail (text-response 404 "Session not found")]
          :else [:ok (assoc ctx :session-id sid) sid])))))

(defn- list-tools [{:keys [cfg tk]} ctx]
  (if-let [f (:list-tools cfg)]
    (vec (f ctx))
    (tool/all-tools tk)))

(defn- can-elicit? [h sid]
  (and (seq sid) (true? (get @(:caps h) sid))))

(defn- elicit-confirm!
  "Send elicitation/create on this POST's SSE stream (`emit!`), wait for the
  answer POSTed back (delivered via `deliver!`). [:ok bool] or [:error msg]."
  [h emit! sid message]
  (let [id      (str "toolnexus-confirm-" (swap! (:seq h) inc))
        k       [sid id]
        slot    (atom nil)
        message (if (seq message) message "Confirm this action?")]
    (swap! (:pending h) assoc k slot)
    (try
      (emit! {:jsonrpc "2.0" :id id :method "elicitation/create"
              :params  {:message         message
                        :requestedSchema {:type       "object"
                                          :properties {:confirm {:type "boolean" :title "Confirm"
                                                                 :description message}}}}})
      (if-let [m (await-value (fn [] @slot) (get-in h [:cfg :confirm-timeout-ms]))]
        (cond
          (some? (:error m)) [:error "client returned an error"]
          (not (map? (:result m))) [:error "invalid elicitation result"]
          (not= "accept" (get-in m [:result :action])) [:ok false]
          (false? (get-in m [:result :content :confirm])) [:ok false]
          :else [:ok true])
        [:error "context deadline exceeded"])
      (finally (swap! (:pending h) dissoc k)))))

(defn- run-tool [t args ctx]
  (try
    (let [f (:execute t)
          r (if (:context? t) (f args ctx) (f args))]
      [(or r {:output ""}) nil])
    (catch Throwable e
      [{:output (msg-of e) :isError true} e])))

(defn- decision-kind [k]
  (cond (nil? k) :allow (keyword? k) k :else (keyword (str k))))

(defn- call-tool [h ctx emit! sid msg]
  (let [{:keys [cfg]} h
        has?   (contains? msg :params)
        params (:params msg)]
    (cond
      (or (not has?) (and (some? params) (not (map? params)))
          (and (map? params) (some? (:name params)) (not (string? (:name params)))))
      [nil {:code -32602 :message "invalid params"}]

      :else
      (let [nm    (or (:name params) "")
            tools (try [:ok (list-tools h ctx)] (catch Throwable e [:err (msg-of e)]))]
        (if (= :err (first tools))
          [nil {:code -32603 :message (second tools)}]
          (let [t    (first (filter #(= nm (:name %)) (second tools)))
                args (:arguments params)]
            (cond
              (or (nil? t) (not (fn? (:execute t))))
              [nil {:code -32602 :message (str "tool not found: " nm)}]

              (and (some? args) (not (map? args)))
              (if (= "2025-11-25" (header (:request ctx) "mcp-protocol-version"))
                [(serve/tool-result->mcp {:output "invalid arguments: must be an object" :isError true}) nil]
                [nil {:code -32602 :message "arguments must be an object"}])

              :else
              (let [args    (or args {})
                    started (ktime/mono-ms)
                    ev      (atom {:name (:name t) :source (:source t) :decision :allow
                                   :identity (:identity ctx) :session-id sid})
                    finish  (fn [res err]
                              (let [wire (serve/tool-result->mcp res)
                                    e    (swap! ev assoc
                                                :ms (- (ktime/mono-ms) started)
                                                :is-error (true? (:isError res))
                                                :err err
                                                :result-bytes (utf8-count (json/write-str wire)))]
                                (when-let [f (:on-call cfg)]
                                  (try (f e) (catch Throwable _ nil)))
                                [wire nil]))
                    run     (fn [] (let [[r e] (run-tool t args ctx)] (finish r e)))]
                (if-let [bc (:before-call cfg)]
                  (let [d    (try (or (bc ctx t args) {:kind :allow})
                                  (catch Throwable e {:kind :deny :message (msg-of e)}))
                        kind (decision-kind (:kind d))]
                    (swap! ev assoc :decision kind)
                    (case kind
                      :deny
                      (if (:result d)
                        (finish (:result d) nil)
                        (finish {:output (if (seq (:message d)) (:message d) "denied") :isError true} nil))

                      :confirm
                      (if-not (can-elicit? h sid)
                        (if (:result d)
                          (finish (:result d) nil)
                          (finish {:output (str "confirmation required: " (or (:message d) "")) :isError true} nil))
                        (let [[tag v] (try (elicit-confirm! h emit! sid (:message d))
                                           (catch Throwable e [:error (msg-of e)]))]
                          (cond
                            (= tag :error) (finish {:output (str "confirmation failed: " v) :isError true} nil)
                            (not v)        (finish {:output "declined" :isError true} nil)
                            :else          (run))))

                      (run)))
                  (run))))))))))

(defn- dispatch
  "[result rpc-error] for one JSON-RPC request."
  [{:keys [cfg] :as h} ctx emit! sid msg]
  (let [m      (:method msg)
        params (:params msg)
        nf     [nil {:code -32601 :message (str "method not found: " m)}]
        hook   (fn [f code] (try [:ok (f)] (catch Throwable e [:err [nil {:code code :message (msg-of e)}]])))]
    (case m
      "ping" [{} nil]

      "tools/list"
      (let [[tag v] (hook #(list-tools h ctx) -32603)]
        (if (= tag :err) v [{:tools (mapv wire-tool v)} nil]))

      "tools/call" (call-tool h ctx emit! sid msg)

      "resources/list"
      (if-not (:list-resources cfg) nf
        (let [[tag v] (hook #((:list-resources cfg) ctx) -32603)]
          (if (= tag :err) v
            [{:resources (mapv (fn [r] (omit-empty (select-keys r [:uri :name :title :description :mimeType]))) v)} nil])))

      "resources/read"
      (if-not (and (:list-resources cfg) (:read-resource cfg)) nf
        (let [[tag v] (hook #((:read-resource cfg) ctx (str (or (:uri params) ""))) -32002)]
          (if (= tag :err) v
            [{:contents (mapv (fn [c]
                                (cond-> (omit-empty (select-keys c [:uri :mimeType :blob]))
                                  (empty? (:blob c)) (assoc :text (str (or (:text c) "")))))
                              v)} nil])))

      "prompts/list"
      (if-not (:list-prompts cfg) nf
        (let [[tag v] (hook #((:list-prompts cfg) ctx) -32603)]
          (if (= tag :err) v
            [{:prompts (mapv (fn [p]
                               (cond-> (omit-empty (select-keys p [:name :title :description]))
                                 (seq (:arguments p))
                                 (assoc :arguments
                                        (mapv (fn [a] (cond-> (omit-empty (select-keys a [:name :description]))
                                                        (true? (:required a)) (assoc :required true)))
                                              (:arguments p)))))
                             v)} nil])))

      "prompts/get"
      (if-not (and (:list-prompts cfg) (:get-prompt cfg)) nf
        (let [args (:arguments params)]
          (if (or (not (map? params)) (and (some? args) (not (map? args)))
                  (and (map? args) (not-every? string? (vals args))))
            [nil {:code -32602 :message "invalid params"}]
            (let [[tag v] (hook #((:get-prompt cfg) ctx (str (or (:name params) "")) (or args {})) -32602)]
              (if (= tag :err) v
                [(omit-empty {:description (:description v)
                              :messages    (mapv (fn [x] {:role (:role x) :content {:type "text" :text (str (:text x))}})
                                                 (:messages v))})
                 nil])))))

      nf)))

;; ---------------------------------------------------------------------------
;; HTTP
;; ---------------------------------------------------------------------------

(defn- body-string [req]
  (let [b (:body req)]
    (cond (nil? b) "" (string? b) b :else (slurp b))))

(defn- media-type [ct]
  (str/lower-case (str/trim (first (str/split (str (or ct "")) #";")))))

(defn- handle-initialize [{:keys [cfg ids legacy] :as h} ctx msg]
  (let [p       (when (map? (:params msg)) (:params msg))
        ;; initialize NEVER negotiates 2026-07-28 (§7C.2): legacy versions only
        version (if (some #{(:protocolVersion p)} legacy)
                  (:protocolVersion p)
                  (or (first legacy) (first default-versions)))
        gen     (when-not (:stateless cfg)
                  (try [:ok ((:generate ids) ctx)] (catch Throwable _ [:err])))]
    (if (= :err (first gen))
      (rpc-error-response (:id msg) -32603 "cannot create session")
      (let [sid  (if (:stateless cfg) (or (:stateless-session-id cfg) "") (second gen))
            ctx  (cond-> ctx (not (:stateless cfg)) (assoc :session-id sid))
            _    (when-not (:stateless cfg)
                   (swap! (:caps h) assoc sid (some? (get-in p [:capabilities :elicitation]))))
            info (if-let [f (:server-info cfg)]
                   (f ctx)
                   (select-keys cfg [:name :title :version :instructions]))
            caps (cond-> {:tools {:listChanged true}}
                   (:list-resources cfg) (assoc :resources {})
                   (:list-prompts cfg)   (assoc :prompts {}))
            res  (cond-> {:protocolVersion version
                          :capabilities    caps
                          :serverInfo      (omit-empty {:name    (if (seq (:name info)) (:name info) default-name)
                                                        :title   (:title info)
                                                        :version (if (seq (:version info)) (:version info) default-version)})}
                   (seq (:instructions info)) (assoc :instructions (:instructions info)))]
        (cond-> (json-response 200 (rpc-msg (:id msg) res nil))
          (seq sid) (assoc-in [:headers "Mcp-Session-Id"] sid))))))

(defn- sse-headers []
  {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"})

(defn- queue-seq
  "A lazy seq over `q` (an atom of a vector of strings): each element blocks
  until something is queued; ends when `done?` is true and the queue is empty."
  [q done?]
  (lazy-seq
    (let [x (await-value (fn []
                           (let [old @q]
                             (cond (and (seq old) (compare-and-set! q old (subvec old 1))) [:item (first old)]
                                   (seq old) nil
                                   (done?)   [:end]
                                   :else     nil)))
                         3600000)]
      (cond
        (nil? x)          (queue-seq q done?)
        (= :end (first x)) nil
        :else             (cons (second x) (queue-seq q done?))))))

(defn- handle-post [{:keys [cfg] :as h} ctx req]
  (if (not= "application/json" (media-type (header req "content-type")))
    (text-response 415 "Unsupported Media Type: must be application/json")
    (let [body (body-string req)]
      (cond
        (> (utf8-count body) (:max-body-bytes cfg))
        (text-response 413 "Request Entity Too Large")

        (str/starts-with? (str/trim body) "[")
        (rpc-error-response nil -32600 "batch requests are not supported")

        :else
        (let [msg (try (json/read-str body) (catch Throwable _ nil))]
          (if-not (and (map? msg) (= "2.0" (:jsonrpc msg)))
            (rpc-error-response nil -32700 "parse error")
            (if (= "initialize" (:method msg))
              (handle-initialize h ctx msg)
              (let [[tag a b] (check-session h ctx req)]
                (if (= tag :fail)
                  a
                  (let [ctx a sid b]
                    (cond
                      ;; a client RESPONSE — the answer to our elicitation
                      (nil? (:method msg))
                      (do (when (contains? msg :id)
                            (when-let [slot (get @(:pending h) [sid (:id msg)])]
                              (compare-and-set! slot nil msg)))
                          {:status 202 :headers {} :body ""})

                      ;; a notification
                      (nil? (:id msg)) {:status 202 :headers {} :body ""}

                      :else
                      (let [meta (get-in msg [:params :_meta])
                            ctx  (cond-> ctx (map? meta) (assoc :meta meta))
                            id   (:id msg)]
                        (if (and (= "tools/call" (:method msg)) (:before-call cfg) (can-elicit? h sid))
                          ;; may upgrade to SSE: run on another thread, answer
                          ;; with whichever comes first — an event or the result.
                          (let [q     (atom [])
                                done  (atom nil)
                                emit! (fn [m] (swap! q conj (sse-event m)) nil)]
                            (proc/run-async!
                              (fn []
                                (reset! done (try (dispatch h ctx emit! sid msg)
                                                  (catch Throwable e [nil {:code -32603 :message (msg-of e)}])))))
                            (await-value (fn [] (or (seq @q) @done)) 3600000)
                            (if (and (empty? @q) @done)
                              (let [[r e] @done] (json-response 200 (rpc-msg id r e)))
                              (let [final (atom false)]
                                {:status 200 :headers (sse-headers)
                                 :body   (queue-seq q (fn []
                                                        (when (and @done (not @final))
                                                          (reset! final true)
                                                          (let [[r e] @done]
                                                            (swap! q conj (sse-event (rpc-msg id r e)))))
                                                        (and @final (empty? @q))))})))
                          (let [[r e] (try (dispatch h ctx (fn [_] (throw (ex-info "no stream" {}))) sid msg)
                                           (catch Throwable e [nil {:code -32603 :message (msg-of e)}]))]
                            (json-response 200 (rpc-msg id r e))))))))))))))))

(defn- handle-get [h ctx req]
  (if-not (str/includes? (str (header req "accept")) "text/event-stream")
    (text-response 405 "Method Not Allowed")
    (let [[tag a b] (check-session h ctx req)]
      (if (= tag :fail)
        a
        (let [stream {:q (atom []) :sid b :open (atom true)}]
          (swap! (:streams h) conj stream)
          {:status 200 :headers (sse-headers)
           :body   (queue-seq (:q stream) (fn [] (not @(:open stream))))})))))

(defn- handle-delete [{:keys [cfg ids] :as h} ctx req]
  (if (:stateless cfg)
    (text-response 405 "Method Not Allowed")
    (let [[tag a sid] (check-session h ctx req)]
      (if (= tag :fail)
        a
        (if-not (try ((:terminate ids) a sid) true (catch Throwable _ false))
          (text-response 500 "cannot terminate session")
          (do (swap! (:caps h) dissoc sid)
              ;; Ring cannot see a disconnect: end this session's GET streams here.
              (doseq [s @(:streams h) :when (= sid (:sid s))] (reset! (:open s) false))
              (swap! (:streams h) (fn [ss] (set (remove #(= sid (:sid %)) ss))))
              {:status 200 :headers {} :body ""}))))))

(defn- write-auth-failure
  "Answer an :authenticate failure (both wires): an `mcp-auth-error` with its
  status + challenge and a JSON body; anything else the §7C.1 plain 401."
  [cfg req e]
  (let [www (when-let [f (:www-authenticate cfg)]
              (try (f req e) (catch Throwable _ nil)))]
    (if (mcp-auth-error? e)
      (let [{:keys [status challenge]} (ex-data e)
            ch (if (seq www) www challenge)]
        {:status  (if (pos? (or status 0)) status 401)
         :headers (cond-> {"Content-Type" "application/json"}
                    (seq ch) (assoc "WWW-Authenticate" ch))
         :body    "{\"error\":\"unauthorized\"}"})
      (cond-> (text-response 401 "Unauthorized")
        (seq www) (assoc-in [:headers "WWW-Authenticate"] www)))))

(declare handle-post-2026 only-2026?)

(defn- ring-handler [{:keys [cfg] :as h}]
  (fn [req]
    (let [ctx    {:request req :session-id ""}
          method (keyword (str/lower-case (name (or (:request-method req) (:method req) :get))))
          ;; the 2026 profile reads the body first; hand the string on
          req    (if (and (= :post method) (some #{protocol-2026} (:versions h)) (not (string? (:body req))))
                   (assoc req :body (body-string req))
                   req)
          r2026  (when (and (= :post method) (some #{protocol-2026} (:versions h)))
                   (handle-post-2026 h ctx req))]
      (cond
        r2026 r2026

        (and (only-2026? h) (not= :post method))
        (assoc-in (text-response 405 "Method Not Allowed") [:headers "Allow"] "POST")

        :else
        (let [auth (when-let [f (:authenticate cfg)]
                     (try [:ok (f req)] (catch Throwable e [:err e])))]
          (if (= :err (first auth))
            (write-auth-failure cfg req (second auth))
            (let [ctx (cond-> ctx auth (assoc :identity (second auth)))]
              (case method
                :post   (handle-post h ctx req)
                :get    (handle-get h ctx req)
                :delete (handle-delete h ctx req)
                (assoc-in (text-response 405 "Method Not Allowed") [:headers "Allow"] "GET, POST, DELETE")))))))))

;; ---------------------------------------------------------------------------
;; MCP 2026-07-28 (SPEC §7C.2, ADR 0040) — the stateless wire, opt-in
;; ---------------------------------------------------------------------------
;;
;; Mirrors golang/mcphandler2026.go. No initialize, no Mcp-Session-Id, no
;; server→client request, no GET stream. A confirm decision and a §10 tool
;; suspension leave as an MRTR `input_required` result; the client's retry
;; carries `inputResponses` and the echoed `requestState`.

(def ^:private meta-version     (keyword "io.modelcontextprotocol/protocolVersion"))
(def ^:private meta-client-caps (keyword "io.modelcontextprotocol/clientCapabilities"))
(def ^:private meta-server-info (keyword "io.modelcontextprotocol/serverInfo"))

(def skills-extension
  "The SEP-2640 skills extension id advertised by server/discover."
  "io.modelcontextprotocol/skills")

(defn- only-2026? [h] (= [protocol-2026] (:versions h)))

(defn- kname
  "A JSON object key back to its string (keywords keep a namespace part)."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- route-2026?
  "Is this POST served on the 2026 profile? (§7C.2 Routing, pinned.)"
  [h req method meta]
  (cond
    (not (some #{protocol-2026} (:versions h))) false
    (empty? (:legacy h)) true
    (= "initialize" method) false
    :else
    (let [hv (str (or (header req "mcp-protocol-version") ""))]
      (cond
        (and (seq hv) (some #{hv} (:legacy h))) false
        :else (or (= "server/discover" method)
                  (seq (header req "mcp-method"))
                  (and (map? meta) (contains? meta meta-version)))))))

(defn decode-header-value
  "An Mcp-Name header value with the `=?base64?<std-base64>?=` sentinel decoded
  (SPEC §2.3). [value ok?] — an absent / empty header is [\"\" false]."
  [v]
  (let [v (str (or v ""))]
    (if (and (str/starts-with? v "=?base64?") (str/ends-with? v "?=") (>= (count v) 11))
      (if-let [d (crypto/b64-std-decode (subs v 9 (- (count v) 2)))]
        [d true]
        ["" false])
      [v (not= "" v)])))

(defn- err26
  ([code msg] (err26 code msg nil nil))
  ([code msg status] (err26 code msg status nil))
  ([code msg status data] {:code code :message msg :status status :data data}))

(defn- validate-2026
  "§7C.2 header validation, in order; nil when the request passes."
  [req method params meta]
  (let [mismatch (fn [m] (err26 -32020 (str "Header mismatch: " m) 400))
        hv       (str (or (header req "mcp-protocol-version") ""))
        hm       (str (or (header req "mcp-method") ""))
        mv       (let [x (when (map? meta) (get meta meta-version))] (if (string? x) x ""))
        name-err (when (#{"tools/call" "prompts/get" "resources/read"} method)
                   (let [k        (if (= "resources/read" method) :uri :name)
                         want     (let [x (when (map? params) (get params k))] (if (string? x) x ""))
                         [got ok] (decode-header-value (header req "mcp-name"))]
                     (when (or (not ok) (not= got want))
                       (mismatch (str "Mcp-Name header value '" got "' does not match body value '" want "'")))))]
    (cond
      (= "" hv) (mismatch "MCP-Protocol-Version header missing")

      (not= hm method)
      (mismatch (str "Mcp-Method header value '" hm "' does not match body value '" method "'"))

      name-err name-err

      (not= mv hv)
      (mismatch (str "MCP-Protocol-Version header value '" hv "' does not match _meta value '" mv "'"))

      (not= mv protocol-2026)
      (err26 -32022 "Unsupported protocol version" 400 {:supported [protocol-2026] :requested mv})

      :else nil)))

(defn- info-2026 [{:keys [cfg]} ctx]
  (let [info (if-let [f (:server-info cfg)]
               (f ctx)
               (select-keys cfg [:name :title :version :instructions]))]
    (assoc info
           :name (if (seq (:name info)) (:name info) default-name)
           :version (if (seq (:version info)) (:version info) default-version))))

(defn- complete-26
  "Stamp `resultType` and `_meta[serverInfo]` (merged into any `_meta`)."
  [h ctx m result-type]
  (let [info (info-2026 h ctx)
        meta (if (map? (:_meta m)) (:_meta m) {})]
    (assoc m
           :_meta (assoc meta meta-server-info (omit-empty (select-keys info [:name :title :version])))
           :resultType result-type)))

(defn- cache-scope [{:keys [cfg]}] (if (:authenticate cfg) "private" "public"))

(defn- skills-26 [{:keys [cfg]} ctx]
  (vec (sort-by :name tool/compare-strings ((:list-skills cfg) ctx))))

(defn- skill-frontmatter [s]
  (if (some? (:frontmatter s)) (:frontmatter s) {:name (:name s)}))

(defn- skill-uri [s] (str "skill://" (:name s) "/SKILL.md"))

(defn- skill-entry [s]
  {:uri         (skill-uri s)
   :frontmatter (skill-frontmatter s)
   :resources   (mapv (fn [f]
                        (let [vs (crypto/utf8-vals (:text f))]
                          {:uri    (str "skill://" (:name s) "/" (:path f))
                           :digest (str "sha256:" (crypto/hex (crypto/sha256 vs)))
                           :size   (count vs)}))
                      (sort-by :path tool/compare-strings (:files s)))})

(defn parse-skill-uri
  "SPEC §2.7 containment for `skill://<name>/<path>`: [name rel] or throws
  ex-info whose message is the §7C.2 reason."
  [uri]
  (let [uri (str uri)]
    (when-not (str/starts-with? uri "skill://")
      (throw (ex-info "not a skill:// URI" {})))
    (let [dec (try (crypto/url-decode (subs uri 8))
                   (catch Throwable _ (throw (ex-info "bad percent-encoding" {}))))]
      (when (some (fn [c] (or (= c \%) (= c \\) (= (int c) 0))) dec)
        (throw (ex-info "skill URI has encoded, backslash or NUL characters" {})))
      (let [segs (str/split dec #"/" -1)]
        (when (< (count segs) 2)
          (throw (ex-info "skill URI needs skill://<name>/<path>" {})))
        (when (some (fn [s] (or (= "" s) (= "." s) (= ".." s) (str/includes? s ":"))) segs)
          (throw (ex-info "skill URI path escapes the skill (empty, '.', '..' or ':' segment)" {})))
        [(first segs) (str/join "/" (rest segs))]))))

;; requestState — base64url-nopad(compact JSON {"tool":…[,"confirmed":true]})

(defn- encode-state [tool-name confirmed?]
  (crypto/b64url (str "{\"tool\":" (json/write-str tool-name)
                      (when confirmed? ",\"confirmed\":true") "}")))

(defn- decode-state [v]
  (when (string? v)
    (try
      (let [m (json/read-str (crypto/vals->utf8 (crypto/b64url-decode-vals v)))]
        (when (map? m)
          {:tool (let [t (:tool m)] (if (string? t) t ""))
           :confirmed (true? (:confirmed m))}))
      (catch Throwable _ nil))))

(defn- input-response
  "One MRTR input response -> a §10 Answer, or nil."
  [k v]
  (when (map? v)
    (let [action (:action v)
          ok     (= "accept" action)]
      (cond-> {:id (kname k) :ok ok}
        (map? (:content v)) (assoc :data (:content v))
        (not ok)            (assoc :reason (if (= "decline" action) "declined" "cancelled"))))))

(defn- can-elicit-26? [meta]
  (let [caps (when (map? meta) (get meta meta-client-caps))]
    (and (map? caps) (contains? caps :elicitation))))

(defn- input-request
  "A §10 Request -> an MRTR input request (URL-mode for authorization + url)."
  [r]
  (if (and (= "authorization" (:kind r)) (seq (:url r)))
    {:method "elicitation/create"
     :params {:mode "url" :message (str (or (:prompt r) "")) :url (:url r) :elicitationId (:id r)}}
    {:method "elicitation/create"
     :params {:mode "form" :message (str (or (:prompt r) ""))
              :requestedSchema (let [s (get-in r [:data :schema])]
                                 (if (map? s) s {:type "object" :properties {}}))}}))

(defn- call-tool-2026 [h ctx params meta]
  (let [{:keys [cfg]} h
        nm    (let [x (when (map? params) (:name params))] (if (string? x) x ""))
        tools (try [:ok (list-tools h ctx)] (catch Throwable e [:err (msg-of e)]))]
    (if (= :err (first tools))
      [nil (err26 -32603 (second tools))]
      (let [t (first (filter #(= nm (:name %)) (second tools)))]
        (if (or (nil? t) (not (fn? (:execute t))))
          [nil (err26 -32602 (str "Unknown tool: " nm))]
          (let [started (ktime/mono-ms)
                ev      (atom {:name (:name t) :source (:source t) :decision :allow
                               :identity (:identity ctx) :session-id ""})
                finish  (fn [m is-err err]
                          (let [e (swap! ev assoc :ms (- (ktime/mono-ms) started) :is-error (true? is-err)
                                         :err err :result-bytes (utf8-count (json/write-str m)))]
                            (when-let [f (:on-call cfg)]
                              (try (f e) (catch Throwable _ nil)))
                            [m nil]))
                result  (fn [res err]
                          (finish (complete-26 h ctx (serve/tool-result->mcp res) "complete")
                                  (:isError res) err))
                has-args (and (map? params) (contains? params :arguments) (some? (:arguments params)))
                args    (:arguments params)]
            (if (and has-args (not (map? args)))
              (result {:output "invalid arguments: must be an object" :isError true} nil)
              (let [args      (or args {})
                    st        (decode-state (:requestState params))
                    st        (when (and st (= nm (:tool st))) st)
                    responses (let [r (:inputResponses params)] (when (map? r) r))
                    ;; [:return v] ends the call; [:run confirmed? responses] runs it
                    gate      (if-let [bc (:before-call cfg)]
                                (let [d    (try (or (bc ctx t args) {:kind :allow})
                                                (catch Throwable e {:kind :deny :message (msg-of e)}))
                                      kind (decision-kind (:kind d))]
                                  (swap! ev assoc :decision kind)
                                  (case kind
                                    :deny
                                    [:return (if (:result d)
                                               (result (:result d) nil)
                                               (result {:output (if (seq (:message d)) (:message d) "denied")
                                                        :isError true} nil))]

                                    :confirm
                                    (let [ans (when st (input-response :confirm (:confirm responses)))]
                                      (cond
                                        (and st (:confirmed st)) [:run true responses]

                                        ans
                                        (if (or (not (:ok ans)) (false? (get-in ans [:data :confirm])))
                                          [:return (result {:output "declined" :isError true} nil)]
                                          [:run true nil]) ; the confirm round is consumed

                                        (not (can-elicit-26? meta))
                                        [:return (if (:result d)
                                                   (result (:result d) nil)
                                                   (result {:output (str "confirmation required: " (or (:message d) ""))
                                                            :isError true} nil))]

                                        :else
                                        (let [msg (if (seq (:message d)) (:message d) "Confirm this action?")]
                                          [:return (finish (complete-26 h ctx
                                                                        {:inputRequests
                                                                         {:confirm {:method "elicitation/create"
                                                                                    :params {:mode "form" :message msg
                                                                                             :requestedSchema
                                                                                             {:type "object"
                                                                                              :properties {:confirm {:type "boolean" :title "Confirm"
                                                                                                                     :description msg}}}}}}
                                                                         :requestState (encode-state nm false)}
                                                                        "input_required")
                                                           false nil)])))

                                    [:run (boolean (and st (:confirmed st))) responses]))
                                [:run (boolean (and st (:confirmed st))) responses])]
                (if (= :return (first gate))
                  (second gate)
                  (let [[_ confirmed? responses] gate
                        answer (when (and st (= 1 (count responses)))
                                 (let [[k v] (first responses)] (input-response k v)))
                        tctx   (cond-> ctx answer (assoc :answer answer))
                        [res err] (run-tool t args tctx)
                        pending (when-not err (get-in res [:metadata :pending]))]
                    (if pending
                      (finish (complete-26 h ctx {:inputRequests {(keyword (str (:id pending))) (input-request pending)}
                                                  :requestState (encode-state nm confirmed?)}
                                           "input_required")
                              false nil)
                      (result res err))))))))))))

(defn- dispatch-2026 [{:keys [cfg] :as h} ctx method params meta]
  (let [internal (fn [e] [nil (err26 -32603 (msg-of e))])
        invalid  (fn [m] [nil (err26 -32602 m)])
        nf       [nil (err26 -32601 (str "Method not found: " method) 404)]
        ok       (fn [m] [(complete-26 h ctx m "complete") nil])
        uri      (let [x (when (map? params) (:uri params))] (if (string? x) x ""))]
    (case method
      "server/discover"
      (let [info (info-2026 h ctx)
            caps (cond-> {:tools {}}
                   (or (:list-resources cfg) (:list-skills cfg)) (assoc :resources {})
                   (:list-prompts cfg) (assoc :prompts {})
                   (:list-skills cfg)  (assoc :extensions {(keyword skills-extension) {}}))]
        (ok (cond-> {:supportedVersions [protocol-2026] :capabilities caps
                     :ttlMs 3600000 :cacheScope "public"}
              (seq (:instructions info)) (assoc :instructions (:instructions info)))))

      "ping" (ok {})

      "tools/list"
      (try (ok {:tools (mapv wire-tool (sort-by :name tool/compare-strings (list-tools h ctx)))
                :ttlMs 60000 :cacheScope (cache-scope h)})
           (catch Throwable e (internal e)))

      "tools/call" (call-tool-2026 h ctx params meta)

      "skills/list"
      (if-not (:list-skills cfg) nf
        (try (ok {:skills (mapv (fn [s] {:uri (skill-uri s) :frontmatter (skill-frontmatter s)}) (skills-26 h ctx))
                  :ttlMs 60000 :cacheScope (cache-scope h)})
             (catch Throwable e (internal e))))

      "skills/get"
      (if-not (:list-skills cfg) nf
        (let [bad (try (parse-skill-uri uri) nil (catch Throwable e (msg-of e)))]
          (if bad
            (invalid (str "Invalid skill URI: " bad))
            (let [ss (try [:ok (skills-26 h ctx)] (catch Throwable e [:err e]))]
              (if (= :err (first ss))
                (internal (second ss))
                (if-let [s (first (filter #(= uri (skill-uri %)) (second ss)))]
                  (ok {:skill (skill-entry s)})
                  (invalid (str "Unknown skill: " uri))))))))

      "resources/list"
      (if-not (:list-resources cfg) nf
        (try (ok {:resources (mapv (fn [r] (omit-empty (select-keys r [:uri :name :title :description :mimeType])))
                                   ((:list-resources cfg) ctx))})
             (catch Throwable e (internal e))))

      "resources/read"
      (if (and (str/starts-with? uri "skill://") (:list-skills cfg))
        (let [parsed (try [:ok (parse-skill-uri uri)] (catch Throwable e [:err (msg-of e)]))]
          (if (= :err (first parsed))
            (invalid (str "Invalid resource URI: " (second parsed)))
            (let [[sname rel] (second parsed)
                  ss (try [:ok (skills-26 h ctx)] (catch Throwable e [:err e]))]
              (if (= :err (first ss))
                (internal (second ss))
                (if-let [f (some (fn [s] (when (= sname (:name s))
                                           (first (filter #(= rel (:path %)) (:files s)))))
                                 (second ss))]
                  (ok {:contents [{:uri uri :mimeType (if (str/ends-with? rel ".md") "text/markdown" "text/plain")
                                   :text (str (:text f))}]
                       :ttlMs 60000 :cacheScope (cache-scope h)})
                  (invalid (str "Resource not found: " uri)))))))
        (if-not (and (:list-resources cfg) (:read-resource cfg)) nf
          (try (ok {:contents (mapv (fn [c]
                                      (cond-> (omit-empty (select-keys c [:uri :mimeType :blob]))
                                        (empty? (:blob c)) (assoc :text (str (or (:text c) "")))))
                                    ((:read-resource cfg) ctx uri))})
               (catch Throwable e [nil (err26 -32002 (msg-of e))]))))

      ("prompts/list" "prompts/get")
      (if-not (:list-prompts cfg) nf
        ;; same wire as the legacy profile; only the envelope differs
        (let [msg (cond-> {:jsonrpc "2.0" :id 0 :method method}
                    (some? params) (assoc :params params))
              [r e] (try (dispatch h ctx (fn [_] (throw (ex-info "no stream" {}))) "" msg)
                         (catch Throwable e [nil {:code -32603 :message (msg-of e)}]))]
          (if e
            [nil (err26 (:code e) (:message e))]
            (ok (if (map? r) r {})))))

      nf)))

(defn- json-26 [status id result e]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (str (json/write-str (if e
                                   {:jsonrpc "2.0" :id id
                                    :error (cond-> {:code (:code e) :message (:message e)}
                                             (some? (:data e)) (assoc :data (:data e)))}
                                   {:jsonrpc "2.0" :id id :result result}))
                 "\n")})

(defn- serve-2026 [{:keys [cfg] :as h} ctx req msg meta]
  (let [method (:method msg)]
    (if (or (not (string? method)) (= "" method) (nil? (:id msg)))
      {:status 202 :headers {} :body ""} ; a notification (or a stray response)
      (let [params (:params msg)
            id     (:id msg)]
        (if-let [e (validate-2026 req method params meta)]
          (json-26 (:status e) id nil e)
          (let [auth (when-let [f (:authenticate cfg)]
                       (try [:ok (f req)] (catch Throwable e [:err e])))]
            (if (= :err (first auth))
              (write-auth-failure cfg req (second auth))
              (let [ctx   (cond-> ctx
                            auth        (assoc :identity (second auth))
                            (map? meta) (assoc :meta meta))
                    [r e] (try (dispatch-2026 h ctx method params meta)
                               (catch Throwable x [nil (err26 -32603 (msg-of x))]))]
                (json-26 (if e (or (:status e) 200) 200) id r e)))))))))

(defn- handle-post-2026
  "Answer a POST on the 2026 profile, or nil when it belongs to the legacy wire
  (or to the legacy path's 415 / 413 answers)."
  [{:keys [cfg] :as h} ctx req]
  (when (= "application/json" (media-type (header req "content-type")))
    (let [body (:body req)]
      (when-not (> (utf8-count body) (:max-body-bytes cfg))
        (let [trimmed (str/trim body)
              msg     (when-not (str/starts-with? trimmed "[")
                        (try (json/read-str body) (catch Throwable _ nil)))]
          (if-not (and (map? msg) (= "2.0" (:jsonrpc msg)))
            (when (only-2026? h)
              (if (str/starts-with? trimmed "[")
                (json-26 400 nil nil (err26 -32600 "batch requests are not supported"))
                (json-26 400 nil nil (err26 -32700 "Parse error"))))
            (let [meta (let [p (:params msg)] (when (map? p) (let [m (:_meta p)] (when (map? m) m))))]
              (when (route-2026? h req (:method msg) meta)
                (serve-2026 h ctx req msg meta)))))))))

;; ---------------------------------------------------------------------------
;; load-mcp-skills
;; ---------------------------------------------------------------------------

(defn- file-vals [path]
  (let [b (fs/read-bytes path)]
    (if (zero? (count b)) [] (vec (codec/b64-decode-vals (codec/encode b))))))

(defn load-mcp-skills
  "SPEC §7C.2 `loadMcpSkills(dir)` — every skill under `dir` (`**/SKILL.md`, any
  depth) as `:list-skills` entries {:name :frontmatter {:name :description}
  :files [{:path :text}]}. Frontmatter is parsed as in §3 (trimmed strings); a
  skill without a `name` is skipped. Files are every regular file under the
  skill directory that is valid UTF-8 (binary files are skipped — the wire is
  text), `/`-separated paths sorted by code point. A path reached THROUGH a
  symlink is skipped (koine's walk follows links; this compares each path with
  its real path, so a linked file or directory is never published)."
  [dir]
  (let [root  (str/replace (str dir) #"/+$" "")
        real  (fs/real-path root)
        rel   (fn [p] (let [r (subs (str p) (count root))] (str/replace r #"^/+" "")))
        plain? (fn [p] (try (= (fs/real-path p) (str real "/" (rel p))) (catch Throwable _ false)))
        all   (->> (fs/list-tree root) (map str) (filter #(str/starts-with? % (str root "/"))) vec)
        mds   (filter (fn [p] (and (= "SKILL.md" (last (str/split p #"/")))
                                   (not (fs/directory? p)) (plain? p)))
                      all)]
    (->> mds
         (keep (fn [p]
                 (let [fm   (frontmatter/read-frontmatter (fs/read-file p))
                       nm   (str/trim (str (or (get-in fm [:data :name]) "")))
                       desc (str/trim (str (or (get-in fm [:data :description]) "")))
                       sdir (subs p 0 (- (count p) (count "/SKILL.md")))]
                   (when (and (seq nm) (not= "malformed-frontmatter" (:reason fm)))
                     {:name        nm
                      :frontmatter {:name nm :description desc}
                      :files       (->> all
                                        (filter #(str/starts-with? % (str sdir "/")))
                                        (remove fs/directory?)
                                        (filter plain?)
                                        (keep (fn [f]
                                                (let [vs (file-vals f)]
                                                  (when (crypto/valid-utf8? vs)
                                                    {:path (subs f (inc (count sdir)))
                                                     :text (crypto/vals->utf8 vs)}))))
                                        (sort-by :path tool/compare-strings)
                                        vec)}))))
         (sort-by :name tool/compare-strings)
         vec)))

;; ---------------------------------------------------------------------------
;; public API
;; ---------------------------------------------------------------------------

(defn mcp-handler
  "SPEC §7C.1 `toolkit.mcpHandler(cfg)` — an embeddable streamable-HTTP MCP
  server. Returns `{:handler (fn [ring-request] ring-response) …}`; mount
  `(:handler h)` on any Ring stack and call `(notify-tools-changed! h)` when the
  tool set moves. Every cfg key is optional:

    :name :title :version :instructions   static serverInfo (defaults
                                          \"toolnexus\" / \"0.1.0\"; empty title omitted)
    :server-info (fn [ctx] {:name :title :version :instructions})   wins when set
    :protocol-versions  newest first; entries outside `implemented-versions`
                        dropped; default [\"2025-06-18\"]. Listing \"2026-07-28\"
                        enables the stateless 2026 wire (SPEC §7C.2) — alone,
                        or beside legacy versions on one mount
    :list-skills (fn [ctx] skills)  — SEP-2640 skills extension on the 2026
                        wire; skills are {:name :frontmatter? :files [{:path :text}]}
                        (`load-mcp-skills` builds them); nil ⇒ not advertised
    :authenticate (fn [ring-request] identity)  — a THROW ⇒ 401; a thrown
                        `mcp-auth-error` ⇒ its status + challenge (both wires).
                        `mcp-server-auth` builds one (toolnexus.mcp-server-auth)
    :www-authenticate (fn [ring-request ex] header-string)
    :list-tools (fn [ctx] tools)  — per request, for tools/list AND tools/call
                                  (a name outside it ⇒ -32602, never executed)
    :before-call (fn [ctx tool args] {:kind :allow|:deny|:confirm :message :result})
                  nil ⇒ allow; a throw ⇒ deny with its message
    :confirm-timeout-ms   elicitation round trip bound (default 300000)
    :on-call (fn [event])  {:name :source :ms :is-error :err :result-bytes
                            :decision :identity :session-id}; throws isolated
    :list-resources (fn [ctx]) / :read-resource (fn [ctx uri])
    :list-prompts (fn [ctx]) / :get-prompt (fn [ctx name args])
    :session-ids {:generate (fn [ctx]) :validate (fn [ctx id]) :terminate (fn [ctx id])}
    :stateless true  — no session ids issued or checked; no elicitation
    :stateless-session-id  returned on initialize in stateless mode, never checked
    :max-body-bytes  POST cap (default 4 MiB ⇒ 413)

  ctx (every hook, and every tool built with `:context? true`) is
  {:identity :request :session-id :meta} — see `mcp-identity` & co."
  ([tk] (mcp-handler tk {}))
  ([tk cfg]
   (let [cfg (cond-> cfg
               (not (pos? (or (:confirm-timeout-ms cfg) 0))) (assoc :confirm-timeout-ms 300000)
               (not (pos? (or (:max-body-bytes cfg) 0)))     (assoc :max-body-bytes (* 4 1024 1024)))
         vs  (normalize-versions (:protocol-versions cfg))
         h   {:tk       tk
              :cfg      cfg
              :ids      (or (:session-ids cfg) (memory-session-ids))
              :versions vs
              :legacy   (vec (remove #{protocol-2026} vs))
              :caps     (atom {})
              :streams  (atom #{})
              :pending  (atom {})
              :seq      (atom 0)}]
     (assoc h :handler (ring-handler h)))))

(defn notify-tools-changed!
  "Send notifications/tools/list_changed on every open GET stream. A stream
  whose queue already holds 16 events drops it rather than grow (Go parity:
  never block the host on a slow — or silently dead — stream)."
  [h]
  (let [ev (sse-event {:jsonrpc "2.0" :method "notifications/tools/list_changed"})]
    (doseq [s @(:streams h)]
      (swap! (:q s) (fn [v] (if (< (count v) 16) (conj v ev) v))))
    nil))
