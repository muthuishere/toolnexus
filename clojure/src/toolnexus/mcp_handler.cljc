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
            [koine.time :as ktime]
            [toolnexus.serve :as serve]
            [toolnexus.tool :as tool]))

(def implemented-versions
  "MCP protocol versions whose wire this handler implements (ADR 0039)."
  ["2025-11-25" "2025-06-18"])

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

(defn- handle-initialize [{:keys [cfg ids versions] :as h} ctx msg]
  (let [p       (when (map? (:params msg)) (:params msg))
        version (if (some #{(:protocolVersion p)} versions) (:protocolVersion p) (first versions))
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

(defn- ring-handler [{:keys [cfg] :as h}]
  (fn [req]
    (let [ctx    {:request req :session-id ""}
          method (keyword (str/lower-case (name (or (:request-method req) (:method req) :get))))
          auth   (when-let [f (:authenticate cfg)]
                   (try [:ok (f req)] (catch Throwable e [:err e])))]
      (if (= :err (first auth))
        (let [www (when-let [f (:www-authenticate cfg)]
                    (try (f req (second auth)) (catch Throwable _ nil)))]
          (cond-> (text-response 401 "Unauthorized")
            (seq www) (assoc-in [:headers "WWW-Authenticate"] www)))
        (let [ctx (cond-> ctx auth (assoc :identity (second auth)))]
          (case method
            :post   (handle-post h ctx req)
            :get    (handle-get h ctx req)
            :delete (handle-delete h ctx req)
            (assoc-in (text-response 405 "Method Not Allowed") [:headers "Allow"] "GET, POST, DELETE")))))))

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
                        dropped; default [\"2025-06-18\"]
    :authenticate (fn [ring-request] identity)  — a THROW ⇒ 401
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
         h   {:tk       tk
              :cfg      cfg
              :ids      (or (:session-ids cfg) (memory-session-ids))
              :versions (normalize-versions (:protocol-versions cfg))
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
