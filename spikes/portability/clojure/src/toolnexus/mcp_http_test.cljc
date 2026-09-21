;; toolnexus.mcp — the streamable-HTTP suite: response-header casing (§2 session
;; identity) and the §10 elicitation bridge over HTTP. ONE `.cljc`, run unchanged
;; on Clojure (JVM) and on cljgo.
;;
;; Everything here drives a REAL `koine.server` on 127.0.0.1:0 speaking the
;; streamable-HTTP MCP shape. No network, no key, no model.
;;
;; Two things are measured that `mcp_test.cljc` cannot:
;;
;;   1. THE CASING REGRESSION. The two hosts' HTTP clients disagreed about the
;;      case of RESPONSE header names — java.net.http lowercases, Go's
;;      http.Header canonicalises — so `(get (:headers res) "Mcp-Session-Id")`
;;      found the value on cljgo and nil on the JVM, and the lowercase spelling
;;      did the reverse. It failed SILENTLY: a missing header and a mis-cased one
;;      are both nil, so the client simply stopped echoing `Mcp-Session-Id` and
;;      the server saw a new session on every request. The test therefore asserts
;;      what the SERVER SAW on the second request, for a server that spelled the
;;      header `Mcp-Session-Id` AND for one that spelled it `mcp-session-id` —
;;      the happy path alone would pass on one host with the bug present.
;;
;;   2. THE ELICITATION BRIDGE OVER HTTP. A server request arrives as an SSE
;;      event mid-`tools/call`, is mapped onto the ONE §10 waitFor, and the
;;      Answer goes back on its own POST carrying the session id — while the
;;      `tools/call` response body is still being read. The in-flight call then
;;      resumes; the tool is not re-executed. Mapping expectations come from
;;      SPEC §2's "Elicitation bridge" and js/src/mcp.ts, never from this port's
;;      own output.
;;
;; HONEST LIMIT, stated rather than papered over: `koine.server` returns a whole
;; response body at once — it cannot withhold bytes — so the peer here cannot
;; BLOCK on the client's answer before emitting the result frame. What is real:
;; the client parses the reverse request, calls waitFor, and completes a second
;; HTTP POST all while the first response body is still open and unconsumed, and
;; only then reads the result. What is not covered on this transport is a server
;; that stalls the stream until the answer lands — that needs a streaming test
;; peer, which koine has no portable primitive for.
(ns toolnexus.mcp-http-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [koine.http :as http]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.mcp :as mcp]))

;; ---------------------------------------------------------------------------
;; the peer
;; ---------------------------------------------------------------------------

(def ^:private tool-defs
  [{:name "ask" :description "asks a question mid-call" :inputSchema {:type "object"}}])

(def ^:private form-params
  "SPEC §2 form mode — no `mode`, a `requestedSchema` that must ride along in
  `data.schema`."
  {:message         "What is your name?"
   :requestedSchema {:type "object" :properties {:name {:type "string"}}
                     :required ["name"]}})

(def ^:private url-params
  "SPEC §2 URL mode — a credential is never collected through a form, so this
  must become `kind:\"authorization\"` carrying the url and NO schema."
  {:mode    "url"
   :message "Authorize toolnexus"
   :url     "https://example.invalid/authorize"})

(def ^:private observed
  "What the peer SAW. Header VALUES that could carry a credential are never
  stored — `mcp-session-id` is not one, and it is the whole subject here."
  (atom {:requests [] :replies []}))

(defn- reset-observed! [] (reset! observed {:requests [] :replies []}) nil)

(defn- sse-frame [m] (str "data: " (json/write-str m) "\n\n"))

(defn- rpc-result
  "The peer's answer to one client request. `elicit` is the params of the
  reverse request to interleave before a `tools/call` result, or nil."
  [msg elicit]
  (let [id (:id msg)]
    (cond
      (= "initialize" (:method msg))
      [{:jsonrpc "2.0" :id id
        :result  {:protocolVersion "2024-11-05"
                  :capabilities    {:tools {}}
                  :serverInfo      {:name "toolnexus-http-peer" :version "1.0.0"}}}]

      (= "tools/list" (:method msg))
      [{:jsonrpc "2.0" :id id :result {:tools tool-defs}}]

      (= "tools/call" (:method msg))
      (cond-> []
        elicit (conj {:jsonrpc "2.0" :id "srv-77" :method "elicitation/create"
                      :params elicit})
        true   (conj {:jsonrpc "2.0" :id id
                      :result {:content [{:type "text" :text "asked and answered"}]}}))

      :else
      [{:jsonrpc "2.0" :id id :error {:code -32601 :message "Method not found"}}])))

(def ^:private routes
  "path -> {:ct … :session-header {name value} :elicit params-or-nil}.

  `:session-header` is the point of the first pair: the SAME session id, issued
  in two different spellings of the same header name. A client that reads it
  with a literal `get` finds exactly one of them, on exactly one host."
  {"/cased"       {:ct "application/json"  :session-header {"Mcp-Session-Id" "sess-cased-9"}}
   "/lower"       {:ct "application/json"  :session-header {"mcp-session-id" "sess-lower-9"}}
   "/ct-cased"    {:ct "text/event-stream" :session-header {"MCP-SESSION-ID" "sess-shout-9"}}
   "/elicit"      {:ct "text/event-stream" :session-header {"Mcp-Session-Id" "sess-elicit-1"}
                   :elicit form-params}
   "/elicit-url"  {:ct "text/event-stream" :session-header {"Mcp-Session-Id" "sess-elicit-2"}
                   :elicit url-params}})

(defn- handler [req]
  (let [path  (:path req)
        hs    (:headers req)                  ; koine.server lowercases these
        route (get routes path)
        msg   (try (json/read-str (:body req)) (catch Throwable _ nil))]
    (cond
      (nil? route)
      {:status 404 :headers {"content-type" "text/plain"} :body "Not Found"}

      ;; A client -> server RESPONSE: an `id` and no `method`. MCP streamable-HTTP
      ;; answers it 202 with no body — this is the channel the elicitation Answer
      ;; travels on.
      (and (map? msg) (some? (:id msg)) (nil? (:method msg)))
      (do (swap! observed update :replies conj
                 {:path    path
                  :id      (:id msg)
                  :result  (:result msg)
                  :error   (:error msg)
                  :session (get hs "mcp-session-id")})
          {:status 202 :headers {} :body ""})

      ;; a notification — no id, nothing to answer
      (and (map? msg) (nil? (:id msg)))
      {:status 202 :headers {} :body ""}

      :else
      (let [_    (swap! observed update :requests conj
                        {:path         path
                         :method       (:method msg)
                         :session      (get hs "mcp-session-id")
                         :capabilities (when (= "initialize" (:method msg))
                                         (get-in msg [:params :capabilities]))})
            out  (rpc-result msg (:elicit route))
            sse? (= "text/event-stream" (:ct route))]
        {:status  200
         :headers (cond-> {"content-type" (:ct route)}
                    ;; issued ONCE, on initialize — exactly as a real peer does,
                    ;; so a later request can only carry it if the client read it
                    ;; back off that one response.
                    (= "initialize" (:method msg)) (merge (:session-header route)))
         :body    (if sse?
                    (apply str ": keep-alive\n\n" (map sse-frame out))
                    (json/write-str (first out)))}))))

;; ---------------------------------------------------------------------------
;; fixture
;; ---------------------------------------------------------------------------

(def ^:private ctx (atom {}))

(defn- with-peer [f]
  (let [h (server/serve handler {:port 0})]
    (reset! ctx {:handle h :base (str "http://127.0.0.1:" (server/port h))})
    (try (f) (finally (server/stop! h)))))

(use-fixtures :once with-peer)

(defn- base [] (:base @ctx))

(defn- remote [path opts]
  (mcp/connect {:name "remote peer" :kind "remote" :enabled true
                :url (str (base) path) :headers {} :timeout 5000}
               opts))

(defn- requests-to [path method]
  (->> (:requests @observed)
       (filter (fn [r] (and (= path (:path r)) (= method (:method r)))))
       vec))

;; ---------------------------------------------------------------------------
;; §2 — response header casing, the silent cross-host bug
;; ---------------------------------------------------------------------------

(deftest a-response-header-is-readable-in-whatever-case-the-server-sent-it
  ;; The contract this port now depends on, asserted directly against a real
  ;; server rather than assumed: koine lowercases response header names on every
  ;; host, and `koine.http/header` finds one whatever case the CALLER spells.
  ;; Before koine 0.10.0 no portable spelling existed at all.
  (let [res (http/request {:method :post :url (str (base) "/ct-cased")
                           :headers {"content-type" "application/json"}
                           :body (json/write-str {:jsonrpc "2.0" :id 1 :method "initialize"})})]
    (is (= 200 (:status res)))
    (testing "the normal form is lowercase, whatever the server shouted"
      (is (contains? (:headers res) "mcp-session-id")
          "koine.http normalizes response header names to lower case on every host"))
    (testing "and `header` reads it under any spelling the caller uses"
      (is (= "sess-shout-9" (http/header res "MCP-SESSION-ID")))
      (is (= "sess-shout-9" (http/header res "Mcp-Session-Id")))
      (is (= "sess-shout-9" (http/header res "mcp-session-id"))))))

(deftest the-session-id-is-echoed-whatever-case-the-server-spelled-the-header
  ;; THE REGRESSION TEST.
  ;;
  ;; It asserts what the SERVER SAW, not what the client parsed — the bug was
  ;; invisible from the client's side, since a header it failed to read is nil
  ;; and nil is also what an absent header looks like. And it runs the SAME
  ;; assertion against a peer spelling the header `Mcp-Session-Id` and one
  ;; spelling it `mcp-session-id`, because either spelling ALONE is a state where
  ;; the correct and the broken implementation coincide on one of the two hosts.
  (doseq [[path expected] [["/cased" "sess-cased-9"] ["/lower" "sess-lower-9"]]]
    (reset-observed!)
    (let [conn (remote path nil)]
      (is (= "connected" (:status conn)) (str path " must connect"))
      (let [t (first (mcp/tools conn))]
        (is (some? t) (str path " must expose its tool"))
        ((:execute t) {}))
      (mcp/disconnect conn)
      (let [call (last (requests-to path "tools/call"))]
        (is (= expected (:session call))
            (str path ": the session id issued on the initialize RESPONSE must be echoed "
                 "on every later request, whatever case the server spelled the header in"))))))

;; ---------------------------------------------------------------------------
;; §10 — the elicitation bridge over streamable-HTTP
;; ---------------------------------------------------------------------------

(deftest http-elicitation-form-mode-is-bridged-onto-wait-for-and-satisfied-inline
  (reset-observed!)
  (let [seen     (atom [])
        wait-for (fn [request] (swap! seen conj request) {:ok true :data {:name "muthu"}})
        conn     (remote "/elicit" {:wait-for wait-for})]
    (is (= "connected" (:status conn)))
    (let [t   (first (mcp/tools conn))
          res ((:execute t) {})]
      (testing "the in-flight tools/call resumes and returns its result"
        (is (false? (:isError res)))
        (is (= "asked and answered" (:output res))))
      (testing "the tool is NOT re-executed — one call, one elicitation"
        (is (= 1 (count (requests-to "/elicit" "tools/call"))))
        (is (= 1 (count @seen))))
      (testing "SPEC §2: form mode becomes kind:\"input\" carrying requestedSchema in data.schema"
        (let [r (first @seen)]
          (is (= "input" (:kind r)))
          (is (= "What is your name?" (:prompt r)))
          (is (= (:requestedSchema form-params) (get-in r [:data :schema])))
          (is (nil? (:url r)) "a form-mode request has no url")
          (is (some? (:id r)) "a Request is identified")))
      (testing "SPEC §2: ok ⇒ accept, with answer.data as the content"
        (let [reply (first (:replies @observed))]
          (is (= "srv-77" (:id reply)) "answered on the server's own request id")
          (is (= {:action "accept" :content {:name "muthu"}} (:result reply)))
          (testing "and the answer POST carries the session id, or the peer cannot match it"
            (is (= "sess-elicit-1" (:session reply)))))))
    (mcp/disconnect conn)))

(deftest http-elicitation-url-mode-becomes-an-authorization-request-and-a-decline-declines
  (reset-observed!)
  (let [seen     (atom [])
        wait-for (fn [request] (swap! seen conj request) {:ok false :reason "declined"})
        conn     (remote "/elicit-url" {:wait-for wait-for})]
    (is (= "connected" (:status conn)))
    (let [t   (first (mcp/tools conn))
          res ((:execute t) {})]
      (testing "a declined elicitation still lets the in-flight call finish"
        (is (false? (:isError res))))
      (testing "SPEC §2: URL mode becomes kind:\"authorization\" with url and NO schema"
        (let [r (first @seen)]
          (is (= "authorization" (:kind r)))
          (is (= "https://example.invalid/authorize" (:url r)))
          (is (nil? (:data r)) "a credential is never collected through a form")))
      (testing "SPEC §2: ok==false with reason \"declined\" ⇒ decline"
        (is (= {:action "decline"} (:result (first (:replies @observed)))))
        (is (= "sess-elicit-2" (:session (first (:replies @observed)))))))
    (mcp/disconnect conn)))

(deftest over-http-the-elicitation-capability-is-advertised-only-with-a-wait-for
  (reset-observed!)
  (testing "no waitFor ⇒ nothing is promised"
    (let [conn (remote "/elicit" nil)]
      (is (= "connected" (:status conn)))
      (is (= {} (:capabilities (first (requests-to "/elicit" "initialize")))))
      (mcp/disconnect conn)))
  (reset-observed!)
  (testing "a waitFor ⇒ capabilities.elicitation is promised on the HTTP leg too"
    (let [conn (remote "/elicit" {:wait-for (fn [_] {:ok true})})]
      (is (= "connected" (:status conn)))
      (is (= {:elicitation {}} (:capabilities (first (requests-to "/elicit" "initialize")))))
      (mcp/disconnect conn))))

(deftest an-unpromised-server-request-is-refused-rather-than-hung-on
  ;; With no waitFor the client never advertised `elicitation`, so a server that
  ;; asks anyway gets a clean JSON-RPC refusal — not silence, which would leave a
  ;; real peer blocked until its own timeout.
  (reset-observed!)
  (let [conn (remote "/elicit" nil)
        t    (first (mcp/tools conn))
        res  ((:execute t) {})]
    (is (false? (:isError res)))
    (is (= "asked and answered" (:output res)))
    (let [reply (first (:replies @observed))]
      (is (= "srv-77" (:id reply)))
      (is (nil? (:result reply)))
      (is (= -32601 (:code (:error reply))) "Method not found — the honest answer"))
    (mcp/disconnect conn)))
