;; toolnexus.mcp — the suite. ONE `.cljc`, run unchanged on Clojure (JVM) and
;; on cljgo (ADR 0009 Decision 3/4).
;;
;; What it actually drives, all of it real:
;;
;;   - streamable-HTTP against a koine.server MCP peer on 127.0.0.1:0, in BOTH
;;     response modes (application/json and text/event-stream), including
;;     pagination, a non-2xx, a 200 that is not JSON, and a dead port;
;;   - stdio against the REAL `npx -y @modelcontextprotocol/server-everything`;
;;   - CONCURRENT writers over one stdio child — the test the reader loop
;;     exists for, and the one the spikes could not pass;
;;   - a HUNG peer, abandoned by `disconnect` inside its budget.
;;
;; Hermetic: the only network is 127.0.0.1 plus the one blessed npx child. No
;; `java.*`, no `Thread/sleep`, no reader conditional. Header VALUES are never
;; asserted on, printed or returned — only the shape (§0.3).
(ns toolnexus.mcp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [koine.host :as host]
            [koine.json :as json]
            [koine.server :as server]
            [koine.time :as ktime]
            [toolnexus.mcp :as mcp]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; the fake remote MCP server
;; ---------------------------------------------------------------------------
;;
;; Four tools, advertised on the wire in SCRAMBLED order (so the client's sort is
;; a measurement, not a coincidence), one per §0.4 branch plus an echo. Served
;; over two paths so the JSON and the SSE response modes are the same session
;; logic, and three deliberately broken paths.

(def ^:private advertised
  [{:name "zebra note"  :description "two text parts"  :inputSchema {:type "object"}}
   {:name "alpha/stats" :description "structuredContent wins over text"
    :inputSchema {:type "object"}}
   {:name "mid.boom"    :description "isError"         :inputSchema {:type "object"}}
   {:name "echo"        :description "echoes a message"
    :inputSchema {:type "object" :properties {:message {:type "string"}}
                  :required ["message"]}}])

(defn- call-result [tool-name args]
  (cond
    (= tool-name "zebra note")
    {:content [{:type "text" :text "line one"} {:type "text" :text "line two"}]}

    (= tool-name "alpha/stats")
    {:content           [{:type "text" :text "ignored when structured is present"}]
     :structuredContent {:zulu 1 :alpha 2 :nested {:b false :a true}}}

    (= tool-name "mid.boom")
    {:content [{:type "text" :text "boom: the tool failed"}] :isError true}

    (= tool-name "echo")
    {:content [{:type "text" :text (str "Echo: " (:message args))}]}

    :else
    {:content [{:type "text" :text (str "unknown tool: " tool-name)}] :isError true}))

(def ^:private seen-capabilities
  "The `capabilities` object the client advertised on its LAST initialize.
  §10's elicitation bridge is only allowed to promise what it can satisfy, and
  the only place that promise is visible is on the wire."
  (atom ::never))

(defn- rpc-response
  "`paged?` splits tools/list across two pages via nextCursor, so the client's
  pagination is exercised rather than asserted."
  [body paged?]
  (let [msg    (try (json/read-str body) (catch Throwable _ nil))
        id     (:id msg)
        method (:method msg)
        params (:params msg)]
    (when (= method "initialize")
      (reset! seen-capabilities (:capabilities params)))
    (cond
      (= method "initialize")
      {:jsonrpc "2.0" :id id
       :result {:protocolVersion "2024-11-05"
                :capabilities    {:tools {}}
                :serverInfo      {:name "toolnexus-fake-remote" :version "1.0.0"}}}

      (= method "tools/list")
      (if paged?
        (if (= "page2" (:cursor params))
          {:jsonrpc "2.0" :id id :result {:tools (vec (drop 2 advertised))}}
          {:jsonrpc "2.0" :id id :result {:tools (vec (take 2 advertised))
                                          :nextCursor "page2"}})
        {:jsonrpc "2.0" :id id :result {:tools advertised}})

      (= method "tools/call")
      {:jsonrpc "2.0" :id id :result (call-result (:name params) (:arguments params))}

      :else
      {:jsonrpc "2.0" :id id :error {:code -32601 :message "Method not found"}})))

(def ^:private seen-headers
  "What the server SAW, reduced to shape the instant it is observed. The header
  VALUE is never stored — the ${ENV} questions are answered here, in-process,
  and only their booleans escape. Only a request that CARRIED the header is
  recorded, so unrelated traffic on the same path cannot erase the observation
  (clojure.test does not promise an order between deftests)."
  (atom nil))

(defn- observe! [req]
  (let [hs   (:headers req)
        auth (some (fn [e] (when (= "authorization" (str/lower-case (str (key e)))) (val e))) hs)]
    (when auth
      (reset! seen-headers
              {:authorization  true
               :still-template (str/includes? (str auth) "${")
               :non-empty      (not= "Bearer " (str auth))}))))

(defn- start-fake! []
  (server/serve
   (fn [req]
     (let [path (:path req)]
       (cond
         (= path "/mcp")
         (do (observe! req)
             {:status 200 :headers {"content-type" "application/json"
                                    "mcp-session-id" "sess-1"}
              :body (json/write-str (rpc-response (:body req) false))})

         (= path "/mcp-paged")
         {:status 200 :headers {"content-type" "application/json"}
          :body (json/write-str (rpc-response (:body req) true))}

         ;; The other legal streamable-HTTP response mode: the JSON-RPC message
         ;; arrives in `data:` frames, behind a comment/keep-alive frame the
         ;; client must skip.
         (= path "/mcp-sse")
         {:status 200 :headers {"content-type" "text/event-stream"}
          :body (str ": keep-alive\n\n"
                     "event: message\n"
                     "data: " (json/write-str (rpc-response (:body req) false)) "\n\n")}

         (= path "/badstatus")
         {:status 503 :headers {"content-type" "text/plain"} :body "service unavailable"}

         (= path "/garbage")
         {:status 200 :headers {"content-type" "application/json"}
          :body "<html>not json at all</html>"}

         :else {:status 404 :headers {"content-type" "text/plain"} :body "Not Found"})))
   {:port 0}))

(defn- dead-url
  "A URL nothing is listening on — bound, read back, closed. Asking the OS for a
  free port and releasing it is the only portable way to be SURE it is dead; a
  hardcoded number is a coin flip."
  []
  (let [h (server/serve (fn [_req] {:status 200 :body "ok"}) {:port 0})
        p (server/port h)]
    (server/stop! h)
    (str "http://127.0.0.1:" p "/mcp")))

;; ---------------------------------------------------------------------------
;; fixture
;; ---------------------------------------------------------------------------

(def ^:private ctx (atom {}))

(def ^:private everything-command
  ["npx" "-y" "@modelcontextprotocol/server-everything"])

(defn- with-world [f]
  (let [handle (start-fake!)
        base   (str "http://127.0.0.1:" (server/port handle))
        remote (mcp/connect {:name "remote api" :kind "remote" :enabled true
                             :url (str base "/mcp")
                             :headers (mcp/expand-headers {"Authorization" "Bearer ${PATH}"
                                                           "X-TN-Static"   "plain"})
                             :raw-headers {"Authorization" "Bearer ${PATH}"
                                           "X-TN-Static"   "plain"}
                             :timeout 5000})
        ;; The stdio leg is a REAL MCP server. It is connected once for the whole
        ;; suite because spawning npx is the slowest thing here by two orders of
        ;; magnitude. A host without `spawn`, or an npx that cannot start, leaves
        ;; :stdio nil and the stdio tests report themselves as skipped rather
        ;; than passing silently.
        stdio  (when (host/supports? :process/spawn)
                 (let [c (mcp/connect {:name "everything" :kind "local" :enabled true
                                       :command everything-command :timeout 30000})]
                   (when (= "connected" (:status c)) c)))]
    (reset! ctx {:handle handle :base base :remote remote :stdio stdio})
    (try (f)
         (finally
           (mcp/disconnect remote)
           (when stdio (mcp/disconnect stdio))
           (server/stop! handle)))))

(use-fixtures :once with-world)

(defn- base [] (:base @ctx))
(defn- remote-conn [] (:remote @ctx))
(defn- stdio-conn [] (:stdio @ctx))

(defn- run-tool [conn tool-name args]
  (tool/execute (tool/toolkit (mcp/tools conn)) tool-name args))

;; ---------------------------------------------------------------------------
;; §0.2  naming
;; ---------------------------------------------------------------------------

(deftest tool-naming
  (is (= "remote_api_zebra_note" (mcp/mcp-tool-name "remote api" "zebra note")))
  (is (= "remote_api_alpha_stats" (mcp/mcp-tool-name "remote api" "alpha/stats")))
  (is (= "a-b_c-d" (mcp/mcp-tool-name "a-b" "c-d")))
  (testing "sanitize is toolnexus.tool's, not a second copy"
    (is (= (str (tool/sanitize "x y") "_" (tool/sanitize "p.q"))
           (mcp/mcp-tool-name "x y" "p.q")))))

;; ---------------------------------------------------------------------------
;; §0.3  config
;; ---------------------------------------------------------------------------

(deftest config-wrappers
  (let [entry {:command ["a"]}]
    (doseq [wrapper [:mcpServers :servers :mcp]]
      (let [parsed (mcp/parse-config {wrapper {:one entry}})]
        (is (= 1 (count parsed)) (str "wrapper " wrapper))
        (is (= "one" (:name (first parsed))))))))

(deftest config-kind-inference
  (let [parsed (mcp/parse-config
                (json/write-str {:mcpServers {"loc"  {:command ["x" "y"]}
                                              "rem"  {:url "http://127.0.0.1:1/mcp"}
                                              "huh"  {:description "neither"}
                                              "over" {:type "remote" :url "http://127.0.0.1:1/mcp"}}}))
        by-name (reduce (fn [acc s] (assoc acc (:name s) s)) {} parsed)]
    (is (= ["huh" "loc" "over" "rem"] (mapv :name parsed)) "sorted by name")
    (is (= "local"   (:kind (get by-name "loc"))))
    (is (= "remote"  (:kind (get by-name "rem"))))
    (is (= "remote"  (:kind (get by-name "over"))))
    (is (= "unknown" (:kind (get by-name "huh"))))
    (is (= ["x" "y"] (:command (get by-name "loc"))))))

(deftest config-enabled-and-timeout
  (let [parsed (mcp/parse-config {:mcpServers {"a" {:command ["x"] :disabled true}
                                               "b" {:command ["x"] :enabled false}
                                               "c" {:command ["x"]}
                                               "d" {:command ["x"] :timeout 1234}}})
        by-name (reduce (fn [acc s] (assoc acc (:name s) s)) {} parsed)]
    (is (false? (:enabled (get by-name "a"))) "disabled:true skips")
    (is (false? (:enabled (get by-name "b"))) "enabled:false skips")
    (is (true?  (:enabled (get by-name "c"))))
    (is (= 30000 (:timeout (get by-name "c"))) "SPEC §0.3 default")
    (is (= 1234  (:timeout (get by-name "d"))))))

(deftest config-reserved-sibling-keys
  (testing "with no wrapper the object IS the server map, minus §2's reserved keys"
    (let [parsed (mcp/parse-config {:builtins {:tools {:bash false}}
                                    :agents   {}
                                    :a2a      {:name "x"}
                                    :mcpServer {:name "y"}
                                    :real     {:command ["x"]}})]
      (is (= ["real"] (mapv :name parsed))))))

(deftest env-expansion-in-headers
  ;; §0.3 / BRIEF rule 5 — assert only that expansion HAPPENED. No value is
  ;; compared, printed or returned. PATH is used because it is set on every host
  ;; this suite runs on and is not a secret.
  (let [raw      {"Authorization" "Bearer ${PATH}"
                  "X-TN-Static"   "plain"
                  "X-TN-Missing"  "${TN_DEFINITELY_UNSET_VARIABLE}"}
        expanded (mcp/expand-headers raw)
        shape    (mcp/header-shape raw expanded)]
    (is (= ["Authorization" "X-TN-Missing" "X-TN-Static"] (:keys shape)))
    (is (true? (:expanded shape)) "at least one value changed")
    (is (not (str/includes? (get expanded "Authorization") "${"))
        "the template is gone")
    (is (= "plain" (get expanded "X-TN-Static")) "a value with no template is untouched")
    (is (= "" (get expanded "X-TN-Missing")) "an unset variable expands to empty")))

(deftest env-expansion-reached-the-wire
  ;; The same question, end to end: what the SERVER received, as booleans only.
  (let [c   (mcp/connect {:name "hdr" :kind "remote" :enabled true
                          :url (str (base) "/mcp") :timeout 5000
                          :headers (mcp/expand-headers {"Authorization" "Bearer ${PATH}"})})
        _   (mcp/disconnect c)
        saw @seen-headers]
    (is (= "connected" (:status c)))
    (is (true? (:authorization saw)) "the header arrived")
    (is (false? (:still-template saw)) "it was expanded before it was sent")
    (is (true? (:non-empty saw)))))

;; ---------------------------------------------------------------------------
;; §2  streamable-HTTP
;; ---------------------------------------------------------------------------

(deftest remote-connects-and-lists
  (let [c (remote-conn)]
    (is (= "connected" (:status c)))
    (is (= "toolnexus-fake-remote" (get-in c [:server-info :name])))
    (is (= ["remote_api_alpha_stats" "remote_api_echo" "remote_api_mid_boom"
            "remote_api_zebra_note"]
           (mapv :name (mcp/tools c)))
        "sorted, and the wire order was scrambled")
    (is (= ["alpha/stats" "echo" "mid.boom" "zebra note"]
           (mapv :remote-name (mcp/tools c))))
    (is (= #{"mcp"} (set (map :source (mcp/tools c)))))
    (is (= {:type "object" :properties {:message {:type "string"}} :required ["message"]}
           (:input-schema (first (filter #(= "remote_api_echo" (:name %)) (mcp/tools c))))))))

(deftest remote-result-branches
  (testing "§0.4 — all three, over HTTP"
    (let [c (remote-conn)]
      (testing "joined text parts"
        (let [r (run-tool c "remote_api_zebra_note" {})]
          (is (false? (:isError r)))
          (is (= "line one\nline two" (:output r)))))
      (testing "structuredContent wins over text, JSON-encoded with sorted keys"
        (let [r (run-tool c "remote_api_alpha_stats" {})]
          (is (false? (:isError r)))
          (is (= "{\"alpha\":2,\"nested\":{\"a\":true,\"b\":false},\"zulu\":1}" (:output r)))))
      (testing "isError ⇒ error result carrying the joined text"
        (let [r (run-tool c "remote_api_mid_boom" {})]
          (is (true? (:isError r)))
          (is (= "boom: the tool failed" (:output r)))))
      (testing "arguments reach the server"
        (is (= "Echo: over-http" (:output (run-tool c "remote_api_echo" {:message "over-http"}))))))))

(deftest remote-sse-response-mode
  (let [c (mcp/connect {:name "sse" :kind "remote" :enabled true
                        :url (str (base) "/mcp-sse") :timeout 5000})]
    (try
      (is (= "connected" (:status c)) "a text/event-stream answer is a valid response")
      (is (= 4 (count (mcp/tools c))))
      (is (= "Echo: over-sse"
             (:output (run-tool c "sse_echo" {:message "over-sse"}))))
      (finally (mcp/disconnect c)))))

(deftest remote-pagination
  (let [c (mcp/connect {:name "paged" :kind "remote" :enabled true
                        :url (str (base) "/mcp-paged") :timeout 5000})]
    (try
      (is (= "connected" (:status c)))
      (is (= 4 (count (mcp/tools c))) "both pages were followed via nextCursor")
      (finally (mcp/disconnect c)))))

(deftest remote-degradations
  (testing "a non-2xx answer is a named failure, not a throw"
    (let [c (mcp/connect {:name "bad" :kind "remote" :enabled true
                          :url (str (base) "/badstatus") :timeout 5000})]
      (is (= "failed" (:status c)))
      (is (= "initialize" (:phase c)))
      (is (= "http-status" (get-in c [:error :error])))
      (is (= 503 (get-in c [:error :status])))))
  (testing "a 200 whose body is not JSON is a named failure"
    (let [c (mcp/connect {:name "garbage" :kind "remote" :enabled true
                          :url (str (base) "/garbage") :timeout 5000})]
      (is (= "failed" (:status c)))
      (is (= "malformed-body" (get-in c [:error :error])))))
  (testing "a server with neither command nor url never reaches a transport"
    (let [c (mcp/connect {:name "empty" :kind "unknown" :enabled true})]
      (is (= "failed" (:status c)))
      (is (= "config" (:phase c))))))

;; ---------------------------------------------------------------------------
;; §0.3  failure isolation
;; ---------------------------------------------------------------------------

(deftest failure-isolation
  (let [result (mcp/from-config
                {:mcpServers {"live" {:url (str (base) "/mcp") :timeout 5000}
                              "dead" {:url (dead-url) :timeout 2000}
                              "off"  {:url (str (base) "/mcp") :enabled false}}})]
    (try
      (is (= {"live" "connected" "dead" "failed" "off" "disabled"} (:statuses result)))
      (is (= 4 (count (:tools result))) "the live server's tools are all there")
      (is (= ["live_alpha_stats" "live_echo" "live_mid_boom" "live_zebra_note"]
             (mapv :name (:tools result))))
      (testing "the toolkit still works with a dead server in the config"
        (let [tk (tool/toolkit (:tools result))]
          (is (= "Echo: isolated" (:output (tool/execute tk "live_echo" {:message "isolated"}))))))
      (testing "the failure is reported, with a phase and a stable error name"
        (is (str/includes? (get (:errors result) "dead") "dead"))
        (is (= "transport" (get-in (first (filter #(= "dead" (:name %)) (:connections result)))
                                   [:error :error]))))
      (testing "a disabled server is never contacted"
        (is (empty? (:tools (first (filter #(= "off" (:name %)) (:connections result)))))))
      (finally (mcp/disconnect-all result)))))

(deftest unknown-tool-is-a-result-not-a-throw
  (let [tk (tool/toolkit (mcp/tools (remote-conn)))]
    (is (= (tool/failure "unknown tool: nope") (tool/execute tk "nope" {})))))

(deftest transport-failure-becomes-an-error-result
  ;; A tool whose server dies AFTER listing must hand the model an error
  ;; ToolResult (§0.1), never an exception.
  (let [h (server/serve (fn [req]
                          {:status 200 :headers {"content-type" "application/json"}
                           :body (json/write-str (rpc-response (:body req) false))})
                        {:port 0})
        c (mcp/connect {:name "vanishing" :kind "remote" :enabled true
                        :url (str "http://127.0.0.1:" (server/port h) "/mcp")
                        :timeout 2000})]
    (is (= "connected" (:status c)))
    (server/stop! h)
    (let [r (run-tool c "vanishing_echo" {:message "x"})]
      (is (true? (:isError r)))
      (is (str/includes? (:output r) "vanishing"))
      (is (str/includes? (:output r) "tools/call")))))

;; ---------------------------------------------------------------------------
;; §2  stdio — against the real @modelcontextprotocol/server-everything
;; ---------------------------------------------------------------------------

(deftest stdio-connects-to-a-real-mcp-server
  (if-let [c (stdio-conn)]
    (do
      (is (= "connected" (:status c)))
      (is (some? (get-in c [:server-info :name])))
      (is (pos? (count (mcp/tools c))))
      (is (contains? (set (map :name (mcp/tools c))) "everything_echo"))
      (is (= (sort (map :name (mcp/tools c))) (map :name (mcp/tools c)))
          "sorted, on both hosts")
      (testing "a real tools/call over stdio"
        (let [r (run-tool c "everything_echo" {:message "toolnexus"})]
          (is (false? (:isError r)))
          (is (str/includes? (:output r) "toolnexus")))))
    (is false "stdio server did not start — this test measured NOTHING")))

(deftest stdio-concurrent-writers
  ;; THE test the dedicated reader loop exists for. Twelve threads each send
  ;; their own tools/call over ONE child, concurrently. Under the spikes'
  ;; caller-side id matching every caller but one would see the others' replies
  ;; as noise, drop them, and time out; and unserialized writes would tear a
  ;; frame in half. Each caller must get its OWN answer.
  (if-let [c (stdio-conn)]
    (let [tk      (tool/toolkit (mcp/tools c))
          n       12
          ;; The future body is wrapped so a throw becomes DATA. `mapv deref`
          ;; RETHROWS whatever a future threw, which aborts the whole deftest as
          ;; an ERROR — and an error carries no assertion, so the run reports a
          ;; short count and never says WHICH caller died. That is precisely the
          ;; shape of the intermittent short-count seen on the interpreted leg
          ;; (704 and 700 of 708, error:1). Whether or not this test is that
          ;; flake, an unattributable error is the wrong failure mode for a
          ;; twelve-way concurrency test: now a caller that blows up is reported
          ;; by NUMBER, as a failing assertion, on the next line.
          results (mapv deref
                        (mapv (fn [i]
                                (future
                                  [i (try (tool/execute tk "everything_echo"
                                                        {:message (str "concurrent-" i)})
                                          (catch Throwable e
                                            (tool/failure (str "caller " i " threw: "
                                                               (or (ex-message e) e)))))]))
                              (range n)))]
      (is (= n (count results)))
      (is (every? (fn [pair] (false? (:isError (second pair)))) results)
          (str "no caller timed out or was handed a torn frame; failures: "
               (pr-str (->> results
                            (filter (fn [pair] (:isError (second pair))))
                            (mapv (fn [pair] [(first pair) (:output (second pair))]))))))
      (is (every? (fn [pair]
                    (str/includes? (:output (second pair)) (str "concurrent-" (first pair))))
                  results)
          "every caller got ITS OWN answer, not another caller's")
      (testing "and the session is still healthy afterwards"
        (is (false? (:isError (tool/execute tk "everything_echo" {:message "after"}))))))
    (is false "stdio server did not start — this test measured NOTHING")))

(deftest stdio-hung-peer-is-abandoned
  ;; `sleep` accepts stdin and never writes a byte, so the reader parks inside
  ;; read-line! forever — the failure koine's `kill!` was added for. The budget
  ;; must expire, the server must be `failed`, and the transport must be closed
  ;; rather than leaking a parked reader.
  (if (host/supports? :process/spawn)
    (let [start (ktime/mono-ms)
          c     (mcp/connect {:name "hung" :kind "local" :enabled true
                              :command ["sleep" "60"] :timeout 1200})
          took  (ktime/elapsed-ms start)]
      (is (= "failed" (:status c)))
      (is (= "initialize" (:phase c)))
      (is (= "timeout" (get-in c [:error :error])))
      (is (< took 10000) (str "gave up in " took "ms, not after the child's 60s"))
      (testing "connect closed the transport on the way out, so nothing is left parked"
        ;; A second connect proves the mechanism is repeatable and that the first
        ;; child was really killed rather than left to hold the process open.
        (let [c2 (mcp/connect {:name "hung2" :kind "local" :enabled true
                               :command ["sleep" "60"] :timeout 800})]
          (is (= "failed" (:status c2))))))
    (is false "no :process/spawn on this host — this test measured NOTHING")))

(deftest stdio-dead-command-is-isolated
  ;; A command that exits immediately: EOF before any response.
  ;;
  ;; This test USED to accept any of #{peer-eof closed timeout}, because the port
  ;; genuinely could not tell a dead peer from a quiet one and a set of three
  ;; strings was the honest assertion. koine 0.8.0's exit-code ended that, so the
  ;; assertion is now specific: the child exits with status 3 and the port must
  ;; SAY 3. A vaguer assertion here would pass whether or not the capability
  ;; works, which is what made the old one unable to catch anything.
  (if (host/supports? :process/spawn)
    (let [c (mcp/connect {:name "gone" :kind "local" :enabled true
                          :command ["sh" "-c" "echo boom-on-stderr >&2; exit 3"]
                          :timeout 3000})]
      (is (= "failed" (:status c)))
      (is (= "initialize" (:phase c)))
      (is (or (= "peer-exited (status 3)" (get-in c [:error :error]))
              ;; a spawn that fails before the child ever runs is a different
              ;; leg and legitimately reports `transport`
              (= "transport" (get-in c [:error :error])))
          (str "expected the exit status to be observed, got: "
               (get-in c [:error :error])))
      (testing "koine's stderr ring is pulled into the message — the point of it existing"
        (is (str/includes? (:message c) "boom-on-stderr"))))
    (is false "no :process/spawn on this host — this test measured NOTHING")))

(deftest stdio-nonexistent-command-is-isolated
  (if (host/supports? :process/spawn)
    (let [c (mcp/connect {:name "nope" :kind "local" :enabled true
                          :command ["tn-definitely-not-a-real-binary"] :timeout 2000})]
      (is (= "failed" (:status c)) "a spawn that throws is still just a failed server")
      (is (empty? (mcp/tools c))))
    (is false "no :process/spawn on this host — this test measured NOTHING")))

;; ---------------------------------------------------------------------------
;; §0.4  shaping, directly
;; ---------------------------------------------------------------------------

(deftest shape-result-branches
  (is (= (tool/success "a\nb")
         (mcp/shape-result {:content [{:type "text" :text "a"} {:type "text" :text "b"}]})))
  (is (= (tool/failure "bad")
         (mcp/shape-result {:content [{:type "text" :text "bad"}] :isError true})))
  (is (= (tool/success "{\"a\":1,\"b\":2}")
         (mcp/shape-result {:content [{:type "text" :text "ignored"}]
                            :structuredContent {:b 2 :a 1}})))
  (testing "non-text content parts are skipped, not stringified"
    (is (= (tool/success "only")
           (mcp/shape-result {:content [{:type "image" :data "…"} {:type "text" :text "only"}]}))))
  (testing "an empty content list is an empty output, not nil"
    (is (= (tool/success "") (mcp/shape-result {:content []})))))

;; ---------------------------------------------------------------------------
;; §10 — the MCP elicitation bridge onto the ONE waitFor
;; ---------------------------------------------------------------------------
;;
;; The two mappings are pure and are the byte-parity surface. Expected values
;; are read off js/src/mcp.ts (`elicitationToRequest` / `answerToElicitResult`)
;; and the suspension spec's "MCP server elicitation is bridged" requirement —
;; not from this port's output.

(deftest elicitation-form-mode-becomes-an-input-request
  (let [r (mcp/elicitation->request
           {:message "What is your name?"
            :requestedSchema {:type "object" :properties {:name {:type "string"}}}})]
    (is (= "input" (:kind r)))
    (is (= "What is your name?" (:prompt r)))
    (testing "requestedSchema rides on data.schema (R2), never as a graft"
      (is (= {:type "object" :properties {:name {:type "string"}}}
             (get-in r [:data :schema]))))
    (is (nil? (:url r)))
    (is (str/starts-with? (str (:id r)) "elc-"))
    (testing "ids are unique — two elicitations in flight must not collide"
      (is (not= (:id r) (:id (mcp/elicitation->request {:message "again"})))))))

(deftest elicitation-url-mode-becomes-an-authorization-request
  (let [r (mcp/elicitation->request
           {:mode "url" :message "Authorize us" :url "https://example.test/oauth"
            :requestedSchema {:type "object"}})]
    (is (= "authorization" (:kind r)))
    (is (= "Authorize us" (:prompt r)))
    (is (= "https://example.test/oauth" (:url r)))
    (testing "URL mode carries no schema — the credential never comes through a form"
      (is (nil? (get-in r [:data :schema]))))))

(deftest elicitation-with-no-message-still-yields-a-request
  (is (= "" (:prompt (mcp/elicitation->request {})))))

(deftest answers-map-back-onto-the-three-mcp-actions
  (is (= {:action "accept" :content {:name "muthu"}}
         (mcp/answer->elicit-result {:ok true :data {:name "muthu"}})))
  (testing "ok with no data accepts with an empty content object"
    (is (= {:action "accept" :content {}} (mcp/answer->elicit-result {:ok true}))))
  (testing "reason 'declined' is decline; anything else is cancel (R1)"
    (is (= {:action "decline"} (mcp/answer->elicit-result {:ok false :reason "declined"})))
    (is (= {:action "cancel"}  (mcp/answer->elicit-result {:ok false :reason "cancelled"})))
    (is (= {:action "cancel"}  (mcp/answer->elicit-result {:ok false})))))

(deftest a-server-request-is-answered-inline-by-wait-for
  (let [seen  (atom nil)
        reply (mcp/server-request-response
               (fn [request] (reset! seen request) {:ok true :data {:name "muthu"}})
               {:jsonrpc "2.0" :id 77 :method "elicitation/create"
                :params {:message "Who are you?"}})]
    (is (= {:jsonrpc "2.0" :id 77 :result {:action "accept" :content {:name "muthu"}}} reply))
    (testing "the SAME §10 Request the host would have seen from the client loop"
      (is (= "input" (:kind @seen)))
      (is (= "Who are you?" (:prompt @seen))))))

(deftest a-declining-wait-for-does-not-crash-the-session
  (is (= {:jsonrpc "2.0" :id 3 :result {:action "decline"}}
         (mcp/server-request-response (fn [_] {:ok false :reason "declined"})
                                      {:id 3 :method "elicitation/create" :params {}}))))

(deftest a-throwing-wait-for-becomes-a-cancel
  ;; §0.3's isolation rule at the reverse-request boundary: a host callback that
  ;; blows up must not take the connection down with it.
  (is (= "cancel" (get-in (mcp/server-request-response
                           (fn [_] (throw (ex-info "boom" {})))
                           {:id 4 :method "elicitation/create" :params {}})
                          [:result :action]))))

(deftest an-unknown-server-request-is-a-method-not-found
  (is (= {:jsonrpc "2.0" :id 9 :error {:code -32601 :message "Method not found"}}
         (mcp/server-request-response (fn [_] {:ok true}) {:id 9 :method "sampling/createMessage"}))))

(deftest with-no-wait-for-there-is-nothing-to-answer-with
  ;; The capability is not advertised, so a spec-compliant server never asks;
  ;; if one asks anyway it gets a clean error rather than a hang.
  (is (= -32601 (get-in (mcp/server-request-response nil {:id 1 :method "elicitation/create"})
                        [:error :code]))))

(deftest elicitation-capability-is-advertised-only-with-a-wait-for
  (let [url (str (base) "/mcp")]
    (testing "no waitFor ⇒ capabilities is bare"
      (reset! seen-capabilities ::never)
      (let [c (mcp/connect {:name "cap-off" :kind "remote" :enabled true :url url :timeout 5000})]
        (is (= "connected" (:status c)))
        (is (= {} @seen-capabilities))
        (mcp/disconnect c)))
    (testing "a waitFor ⇒ capabilities.elicitation is promised"
      (reset! seen-capabilities ::never)
      (let [c (mcp/connect {:name "cap-on" :kind "remote" :enabled true :url url :timeout 5000}
                           {:wait-for (fn [_] {:ok true})})]
        (is (= "connected" (:status c)))
        (is (= {:elicitation {}} @seen-capabilities))
        (mcp/disconnect c)))))

(deftest from-config-threads-wait-for-to-every-server
  (reset! seen-capabilities ::never)
  (let [res (mcp/from-config {:mcpServers {"one" {:url (str (base) "/mcp") :timeout 5000}}}
                             {:wait-for (fn [_] {:ok true})})]
    (is (= {"one" "connected"} (:statuses res)))
    (is (= {:elicitation {}} @seen-capabilities))
    (mcp/disconnect-all res)))
