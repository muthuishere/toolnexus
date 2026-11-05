;; SPEC §7C.1 / ADR 0039 — `mcp-handler`, mirroring golang/mcphandler_test.go
;; test for test. The Go suite drives an mcp-go client over httptest; here every
;; request is a Ring request map handed straight to the handler (the port's
;; native in-process request), including a raw elicitation round trip over the
;; POST's SSE body and a list_changed notification over a GET stream.
(ns toolnexus.mcp-handler-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [koine.http :as http]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.mcp-handler :as mh]
            [toolnexus.serve :as serve]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- req
  ([method body] (req method body {}))
  ([method body headers]
   {:request-method method
    :headers (merge (when (= :post method)
                      {"content-type" "application/json"
                       "accept"       "application/json, text/event-stream"})
                    (into {} (map (fn [[k v]] [(str/lower-case k) v]) headers)))
    :body    (or body "")}))

(defn- hdr [res k]
  (some (fn [[hk v]] (when (= (str/lower-case (name hk)) (str/lower-case k)) v)) (:headers res)))

(defn- body-str [res] (let [b (:body res)] (if (string? b) b (apply str b))))
(defn- body-json [res] (json/read-str (body-str res)))

(defn- test-tool
  "Echoes `<name>:<identity>`, counting executions in `ran`."
  ([nm] (test-tool nm nil))
  ([nm ran]
   (tool/tool {:name nm :description (str nm " tool") :source "native"
               :input-schema {:type "object" :properties {:id {:type "string"}}}
               :context? true
               :execute (fn [_args ctx]
                          (when ran (swap! ran inc))
                          (tool/success (str nm ":" (or (mh/mcp-identity ctx) ""))))})))

(defn- bearer-auth [r]
  (let [tok (str/replace (str (get-in r [:headers "authorization"])) #"^Bearer " "")]
    (if (#{"alice" "bob"} tok) tok (throw (ex-info "bad token" {})))))

(defn- init!
  "initialize (optionally declaring elicitation); returns the session id."
  ([h token] (init! h token false))
  ([h token elicit?]
   (let [res ((:handler h) (req :post (json/write-str {:jsonrpc "2.0" :id 0 :method "initialize"
                                                       :params {:protocolVersion "2025-06-18"
                                                                :capabilities (if elicit? {:elicitation {}} {})
                                                                :clientInfo {:name "test" :version "1"}}})
                                (cond-> {} token (assoc "Authorization" (str "Bearer " token)))))]
     (hdr res "Mcp-Session-Id"))))

(defn- rpc
  "POST one JSON-RPC request within session `sid`; returns the Ring response."
  [h sid token m]
  ((:handler h) (req :post (json/write-str (merge {:jsonrpc "2.0"} m))
                     (cond-> {"MCP-Protocol-Version" "2025-06-18"}
                       (seq sid) (assoc "Mcp-Session-Id" sid)
                       token     (assoc "Authorization" (str "Bearer " token))))))

(defn- call [h sid token nm args]
  (body-json (rpc h sid token {:id 7 :method "tools/call" :params (cond-> {:name nm} args (assoc :arguments args))})))

(defn- text-of [m] (get-in m [:result :content 0 :text]))
(defn- names [h sid token]
  (mapv :name (get-in (body-json (rpc h sid token {:id 1 :method "tools/list"})) [:result :tools])))

(defn- tk [] (tool/toolkit []))

;; ---------------------------------------------------------------------------
;; tests (golang/mcphandler_test.go order)
;; ---------------------------------------------------------------------------

(deftest auth-rejects-with-401-and-www-authenticate
  (let [h   (mh/mcp-handler (tk) {:authenticate bearer-auth
                                  :www-authenticate (fn [_ _] "Bearer resource_metadata=\"https://example.test/.well-known/oauth-protected-resource\"")})
        res ((:handler h) (req :post "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))]
    (is (= 401 (:status res)))
    (is (str/includes? (str (hdr res "WWW-Authenticate")) "resource_metadata="))))

(deftest per-identity-tools-and-not-found-never-executes
  (let [ran   (atom 0)
        h     (mh/mcp-handler (tk) {:authenticate bearer-auth
                                    :list-tools (fn [ctx]
                                                  (cond-> [(test-tool "search")]
                                                    (= "alice" (mh/mcp-identity ctx)) (conj (test-tool "admin_reset" ran))))})
        alice (init! h "alice")
        bob   (init! h "bob")]
    (is (= ["search" "admin_reset"] (names h alice "alice")))
    (is (= ["search"] (names h bob "bob")))
    (is (= "search:bob" (text-of (call h bob "bob" "search" nil))))
    (let [r (call h bob "bob" "admin_reset" nil)]
      (is (= {:code -32602 :message "tool not found: admin_reset"} (:error r))))
    (is (zero? @ran))
    (is (= "admin_reset:alice" (text-of (call h alice "alice" "admin_reset" nil))))))

(defn- gated-handler [fallback ran]
  (let [del (assoc (test-tool "delete_record" ran)
                   :annotations {:title "Delete" :destructiveHint true :readOnlyHint false
                                 :idempotentHint true :openWorldHint false})]
    (mh/mcp-handler (tk) {:authenticate bearer-auth
                          :list-tools (fn [_] [del (test-tool "read")])
                          :before-call (fn [_ctx t args]
                                         (cond
                                           (get-in t [:annotations :destructiveHint])
                                           (if (= "nonce-ok" (:nonce args))
                                             {:kind :allow}
                                             (cond-> {:kind :confirm :message "Delete the record?"}
                                               fallback (assoc :result fallback)))
                                           (= "forbidden" (:id args)) {:kind :deny :message "not allowed"}
                                           :else nil))})))

(defn- sse-data [event]
  (json/read-str (second (re-find #"data: (.*)\n" event))))

(defn- elicit-round-trip
  "tools/call delete_record over raw HTTP shapes; answer the elicitation with `action`."
  [h sid action]
  (let [res    (rpc h sid "alice" {:id 9 :method "tools/call" :params {:name "delete_record" :arguments {:id "1"}}})
        _      (is (= "text/event-stream" (hdr res "Content-Type")))
        events (:body res)
        ask    (sse-data (first events))]
    (is (= "elicitation/create" (:method ask)))
    (is (= "Delete the record?" (get-in ask [:params :message])))
    (is (= "boolean" (get-in ask [:params :requestedSchema :properties :confirm :type])))
    (let [ack (rpc h sid "alice" {:id (:id ask) :result {:action action :content {:confirm (= "accept" action)}}})]
      (is (= 202 (:status ack))))
    (let [final (sse-data (second events))]
      (is (= 9 (:id final)))
      (is (nil? (nth (vec events) 2 nil)) "stream ends after the response")
      final)))

(deftest confirm-via-elicitation
  (let [ran (atom 0)
        h   (gated-handler nil ran)
        sid (init! h "alice" true)
        yes (elicit-round-trip h sid "accept")]
    (is (= "delete_record:alice" (text-of yes)))
    (is (nil? (get-in yes [:result :isError])))
    (is (= 1 @ran))
    (let [no (elicit-round-trip h (init! h "alice" true) "decline")]
      (is (= "declined" (text-of no)))
      (is (true? (get-in no [:result :isError])))
      (is (= 1 @ran)))))

(deftest confirm-timeout-is-a-shaped-failure
  (let [ran (atom 0)
        del (test-tool "delete_record" ran)
        h   (mh/mcp-handler (tk) {:list-tools (fn [_] [del]) :confirm-timeout-ms 50
                                  :before-call (fn [_ _ _] {:kind :confirm})})
        sid (init! h nil true)
        res (rpc h sid nil {:id 3 :method "tools/call" :params {:name "delete_record"}})
        evs (vec (:body res))]
    (is (= "Confirm this action?" (get-in (sse-data (first evs)) [:params :message])))
    (is (= "confirmation failed: context deadline exceeded" (text-of (sse-data (second evs)))))
    (is (zero? @ran))))

(deftest confirm-without-elicitation
  (let [ran (atom 0)
        h   (gated-handler nil ran)
        sid (init! h "alice")
        r   (call h sid "alice" "delete_record" {:id "1"})]
    (is (= "confirmation required: Delete the record?" (text-of r)))
    (is (true? (get-in r [:result :isError])))
    (is (zero? @ran)))
  (let [ran (atom 0)
        h   (gated-handler {:output "re-call with {\"nonce\":\"nonce-ok\"}"} ran)
        sid (init! h "alice")
        r   (call h sid "alice" "delete_record" {:id "1"})]
    (is (nil? (get-in r [:result :isError])))
    (is (str/includes? (text-of r) "nonce-ok"))
    (is (zero? @ran))
    (is (= "delete_record:alice" (text-of (call h sid "alice" "delete_record" {:id "1" :nonce "nonce-ok"}))))
    (is (= 1 @ran))))

(deftest deny-and-annotations
  (let [h   (gated-handler nil nil)
        sid (init! h "alice")
        r   (call h sid "alice" "read" {:id "forbidden"})]
    (is (true? (get-in r [:result :isError])))
    (is (= "not allowed" (text-of r)))
    (is (= {:title "Delete" :destructiveHint true :readOnlyHint false :idempotentHint true :openWorldHint false}
           (get-in (body-json (rpc h sid "alice" {:id 1 :method "tools/list"})) [:result :tools 0 :annotations])))))

(deftest on-call-event
  (let [evs  (atom [])
        boom (tool/tool {:name "boom" :source "native"
                         :execute (fn [_] (ktime/sleep! 5) (throw (ex-info "kaput" {})))})
        h    (mh/mcp-handler (tk) {:authenticate bearer-auth
                                   :list-tools (fn [_] [boom])
                                   :on-call (fn [ev] (swap! evs conj ev) (throw (ex-info "isolated" {})))})
        sid  (init! h "bob")
        r    (call h sid "bob" "boom" nil)]
    (is (= "kaput" (text-of r)))
    (is (true? (get-in r [:result :isError])))
    (is (= 1 (count @evs)))
    (let [ev (first @evs)]
      (is (= "boom" (:name ev)))
      (is (true? (:is-error ev)))
      (is (some? (:err ev)))
      (is (pos? (:result-bytes ev)))
      (is (>= (:ms ev) 5))
      (is (= "bob" (:identity ev)))
      (is (seq (:session-id ev)))
      (is (= :allow (:decision ev))))))

(deftest list-changed-notification
  (let [h   (mh/mcp-handler (tk) {:list-tools (fn [_] [(test-tool "a")])})
        sid (init! h nil)
        get-res ((:handler h) {:request-method :get
                               :headers {"accept" "text/event-stream" "mcp-session-id" sid}})
        got (atom nil)]
    (is (= 200 (:status get-res)))
    (is (= "text/event-stream" (hdr get-res "Content-Type")))
    (proc/run-async! (fn [] (reset! got (first (:body get-res)))))
    (mh/notify-tools-changed! h)
    (let [deadline (+ (ktime/mono-ms) 5000)]
      (loop [] (when (and (nil? @got) (< (ktime/mono-ms) deadline)) (ktime/sleep! 5) (recur))))
    (is (= "notifications/tools/list_changed" (:method (sse-data (str @got)))))
    ;; DELETE ends the session's streams
    ((:handler h) {:request-method :delete :headers {"mcp-session-id" sid}})
    (is (= 1 (count (vec (:body get-res)))))))

(deftest server-info-resources-prompts
  (let [h   (mh/mcp-handler (tk) {:authenticate bearer-auth
                                  :server-info (fn [ctx] {:name (str "srv-" (get-in (mh/mcp-request ctx) [:headers "host"]) "-" (mh/mcp-identity ctx))
                                                          :version "9.9.9" :instructions "be careful"})
                                  :list-resources (fn [_] [{:uri "docs://api" :name "api" :mimeType "text/markdown"}])
                                  :read-resource (fn [ctx uri] [{:uri uri :mimeType "text/markdown" :text (str "# API for " (mh/mcp-identity ctx))}])
                                  :list-prompts (fn [_] [{:name "greet" :arguments [{:name "who" :required true}]}])
                                  :get-prompt (fn [_ _ args] {:messages [{:role "user" :text (str "hello " (:who args))}]})})
        init (body-json ((:handler h) (req :post (json/write-str {:jsonrpc "2.0" :id 1 :method "initialize"
                                                                 :params {:protocolVersion "2025-06-18"}})
                                           {"Authorization" "Bearer alice" "Host" "127.0.0.1"})))
        sid  (init! h "alice")]
    (is (= {:name "srv-127.0.0.1-alice" :version "9.9.9"} (get-in init [:result :serverInfo])))
    (is (= "be careful" (get-in init [:result :instructions])))
    (is (= "2025-06-18" (get-in init [:result :protocolVersion])))
    (is (= {} (get-in init [:result :capabilities :resources])))
    (is (= {} (get-in init [:result :capabilities :prompts])))
    (is (= [{:uri "docs://api" :name "api" :mimeType "text/markdown"}]
           (get-in (body-json (rpc h sid "alice" {:id 2 :method "resources/list"})) [:result :resources])))
    (is (= "# API for alice"
           (get-in (body-json (rpc h sid "alice" {:id 3 :method "resources/read" :params {:uri "docs://api"}})) [:result :contents 0 :text])))
    (is (= "hello world"
           (get-in (body-json (rpc h sid "alice" {:id 4 :method "prompts/get" :params {:name "greet" :arguments {:who "world"}}}))
                   [:result :messages 0 :content :text])))
    (is (= [{:name "who" :required true}]
           (get-in (body-json (rpc h sid "alice" {:id 5 :method "prompts/list"})) [:result :prompts 0 :arguments])))))

(deftest protocol-conformance
  (let [h   (mh/mcp-handler (tk) {:list-tools (fn [_] [])})
        raw (fn [sid body hdrs] ((:handler h) (req :post body (cond-> hdrs (seq sid) (assoc "Mcp-Session-Id" sid)))))
        res (raw "" "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"x\",\"version\":\"1\"}}}" {})
        sid (hdr res "Mcp-Session-Id")
        v   {"MCP-Protocol-Version" "2025-06-18"}]
    (is (= 200 (:status res)))
    (is (seq sid))
    (is (str/includes? (body-str res) "\"protocolVersion\":\"2025-06-18\""))
    (is (str/includes? (body-str res) "\"listChanged\":true"))
    (is (str/includes? (body-str (raw "" "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}" {}))
                       "\"protocolVersion\":\"2025-06-18\""))
    (is (= 202 (:status (raw sid "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}" v))))
    (is (= 400 (:status (raw "" "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}" v))))
    (is (= 404 (:status (raw "nope" "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}" v))))
    (is (= 400 (:status (raw sid "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}" {"MCP-Protocol-Version" "2099-01-01"}))))
    (is (str/includes? (body-str (raw sid "[{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}]" v)) "-32600"))
    (is (str/includes? (body-str (raw sid "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}" v)) "\"result\":{}"))
    (is (str/includes? (body-str (raw sid "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}" v)) "-32601"))
    (is (= 400 (:status (raw sid "not json" v))))
    (is (= 415 (:status ((:handler h) {:request-method :post :headers {"content-type" "text/plain"} :body "{}"}))))
    (is (= 405 (:status ((:handler h) {:request-method :get :headers {}}))))
    (let [put ((:handler h) {:request-method :put :headers {}})]
      (is (= 405 (:status put)))
      (is (= "GET, POST, DELETE" (hdr put "Allow"))))
    (is (= 200 (:status ((:handler h) {:request-method :delete :headers {"mcp-session-id" sid}}))))
    (is (= 404 (:status (raw sid "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"ping\"}" v))))))

(deftest max-body-bytes
  (let [h (mh/mcp-handler (tk) {:stateless true :max-body-bytes 10})]
    (is (= 413 (:status ((:handler h) (req :post "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")))))))

(deftest custom-session-ids-and-stateless
  (let [cnt (atom {:gen 0 :val 0})
        ids {:generate  (fn [_] (swap! cnt update :gen inc) "host-session-1")
             :validate  (fn [_ id] (swap! cnt update :val inc) (= id "host-session-1"))
             :terminate (fn [_ _] nil)}
        h   (mh/mcp-handler (tk) {:session-ids ids :list-tools (fn [_] [(test-tool "a")])})
        sid (init! h nil)]
    (is (= "host-session-1" sid))
    (is (= "a:" (text-of (call h sid nil "a" nil))))
    (is (= 1 (:gen @cnt)))
    (is (pos? (:val @cnt))))
  (let [h   (mh/mcp-handler (tk) {:stateless true :list-tools (fn [_] [(test-tool "a")])})
        res ((:handler h) (req :post "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"))]
    (is (nil? (hdr res "Mcp-Session-Id")))
    (is (str/includes? (body-str ((:handler h) (req :post "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"a\"}}")))
                       "a:"))))

(deftest defaults-to-toolkit-tools
  (let [h   (mh/mcp-handler (tool/toolkit [(test-tool "registered")]))
        sid (init! h nil)]
    (is (some #{"registered"} (names h sid nil)))))

(deftest structured-content-and-output-schema
  (let [st    (tool/tool {:name "stats" :source "native"
                          :output-schema {:type "object" :properties {:count {:type "integer"}}}
                          :execute (fn [_] (tool/with-structured {:output ""} {:count 3}))})
        plain (test-tool "plain")
        h     (mh/mcp-handler (tk) {:list-tools (fn [_] [st plain])})
        sid   (init! h nil)
        lt    (body-json (rpc h sid nil {:id 1 :method "tools/list"}))
        r     (call h sid nil "stats" nil)]
    (is (= "object" (get-in lt [:result :tools 0 :outputSchema :type])))
    (is (= {:count 3} (get-in r [:result :structuredContent])))
    (is (= "{\"count\":3}" (text-of r)))
    (let [raw (json/write-str (serve/tool-result->mcp {:output "x"}))]
      (is (not (str/includes? raw "structuredContent")))
      (is (not (str/includes? raw "_meta"))))
    (is (not (contains? (mh/wire-tool plain) :outputSchema)))))

(deftest meta-passthrough
  (let [seen (atom nil) seen-hook (atom nil)
        t    (tool/tool {:name "m" :source "native" :context? true
                         :execute (fn [_ ctx] (reset! seen (mh/mcp-request-meta ctx))
                                    (tool/with-structured (tool/success "ok") nil {:usage {:units 2}}))})
        h    (mh/mcp-handler (tk) {:stateless true :list-tools (fn [_] [t])
                                   :before-call (fn [ctx _ _] (reset! seen-hook (mh/mcp-request-meta ctx)) nil)})
        b    (body-str ((:handler h) (req :post "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"m\",\"_meta\":{\"clientCaps\":{\"elicitation\":{}}}}}")))]
    (is (str/includes? b "\"_meta\":{\"usage\":{\"units\":2}}"))
    (is (contains? @seen :clientCaps))
    (is (contains? @seen-hook :clientCaps))))

(deftest protocol-versions-per-handler
  (let [init-body (fn [v] (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" v "\"}}"))
        dflt  (:handler (mh/mcp-handler (tk) {:stateless true}))
        newer (:handler (mh/mcp-handler (tk) {:stateless true
                                              :protocol-versions ["2026-07-28" "2025-11-25" "2025-06-18" "2025-03-26"]
                                              :list-tools (fn [_] [(test-tool "a")])}))
        bad   "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"a\",\"arguments\":[1]}}"]
    (is (str/includes? (body-str (dflt (req :post (init-body "2025-11-25")))) "\"protocolVersion\":\"2025-06-18\""))
    (is (= 400 (:status (dflt (req :post "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}" {"MCP-Protocol-Version" "2025-11-25"})))))
    (is (str/includes? (body-str (newer (req :post (init-body "2025-11-25")))) "\"protocolVersion\":\"2025-11-25\""))
    (is (str/includes? (body-str (newer (req :post (init-body "2026-07-28")))) "\"protocolVersion\":\"2025-11-25\""))
    (is (= 400 (:status (newer (req :post "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}" {"MCP-Protocol-Version" "2025-03-26"})))))
    (let [b (body-str (newer (req :post bad {"MCP-Protocol-Version" "2025-11-25"})))]
      (is (str/includes? b "\"isError\":true"))
      (is (not (str/includes? b "-32602"))))
    (is (str/includes? (body-str (newer (req :post bad {"MCP-Protocol-Version" "2025-06-18"}))) "-32602"))))

(deftest stateless-session-id-and-title
  (let [h (:handler (mh/mcp-handler (tk) {:stateless true :stateless-session-id "stateless" :title "Records API"}))
        r (h (req :post "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}"))]
    (is (= "stateless" (hdr r "Mcp-Session-Id")))
    (is (str/includes? (body-str r) "\"title\":\"Records API\""))
    (is (str/includes? (body-str (h (req :post "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}" {"Mcp-Session-Id" "whatever"})))
                       "\"result\":{}")))
  (let [h (:handler (mh/mcp-handler (tk) {:stateless true}))]
    (is (not (str/includes? (body-str (h (req :post "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")))
                            "\"title\"")))))

(deftest serve-structured-result-via-mcp-server
  ;; the §7C `serve` path maps structured/meta/outputSchema too, and is
  ;; byte-identical when they are absent.
  (let [tk  (tool/toolkit [(tool/tool {:name "x" :execute (fn [_] (tool/success "plain"))})
                           (tool/tool {:name "y" :output-schema {:type "object"}
                                       :execute (fn [_] (tool/with-structured (tool/success "hi") {:a 1} {:k "v"}))})])
        h   (serve/serve tk {:port 0 :mcp {}})
        post (fn [m] (json/read-str (:body (http/request {:method :post :url (str (:url h) "/mcp")
                                                                 :headers {"content-type" "application/json"}
                                                                 :body (json/write-str m)}))))]
    (try
      (let [tools (get-in (post {:jsonrpc "2.0" :id 1 :method "tools/list"}) [:result :tools])]
        (is (= {:name "x" :description "" :inputSchema {:type "object"}} (first tools)))
        (is (= {:type "object"} (:outputSchema (second tools)))))
      (is (= {:content [{:type "text" :text "plain"}] :isError false}
             (:result (post {:jsonrpc "2.0" :id 2 :method "tools/call" :params {:name "x"}}))))
      (is (= {:content [{:type "text" :text "hi"}] :isError false :structuredContent {:a 1} :_meta {:k "v"}}
             (:result (post {:jsonrpc "2.0" :id 3 :method "tools/call" :params {:name "y"}}))))
      (finally (serve/stop! h)))))
