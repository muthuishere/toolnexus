;; SPEC §2 (2026-07-28) + §2A.1 — the discover-first client, hermetic. Peers are
;; koine.server fixtures (toolnexus.mcp2026-fixture-test) and `node -e` stdio
;; scripts; the only network is 127.0.0.1. Expectations come from
;; examples/mcp-2026/expected.json and SPEC, not from this port's own output.
(ns toolnexus.mcp2026-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.process :as proc]
            [toolnexus.core :as core]
            [toolnexus.mcp :as mcp]
            [toolnexus.mcp-auth :as auth]
            [toolnexus.mcp2026 :as m26]
            [toolnexus.mcp2026-fixture-test :as fx]
            [toolnexus.shared-examples-test :refer [examples-dir]]
            [toolnexus.skill :as skill]
            [toolnexus.tool :as tool]))

(defn- expected [] (json/read-str (fs/read-file (str (examples-dir) "/mcp-2026/expected.json")) {:key-fn str}))

(defn- remote [url & {:as extra}]
  (merge {:name "fx" :kind "remote" :enabled true :url url :headers {} :timeout 10000} extra))

(defn- run [conn nm args & [ctx]]
  (let [t (first (filter #(= nm (:name %)) (:tools conn)))]
    (if ctx ((:execute t) args ctx) ((:execute t) args))))

(defn- with-server [opts f]
  (let [s (fx/start-server opts)] (try (f s) (finally ((:stop s))))))

;; ---------------------------------------------------------------------------
;; units
;; ---------------------------------------------------------------------------

(deftest wire-constants-and-header-sentinel
  (is (= "2026-07-28" m26/protocol-version (get (expected) "protocolVersion")))
  (is (= "plain" (m26/encode-header "plain")))
  (doseq [v [" lead" "trail " "héllo" "=?base64?eA==?="]]
    (is (str/starts-with? (m26/encode-header v) "=?base64?") v))
  (is (= {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
          "io.modelcontextprotocol/clientCapabilities" {"elicitation" {"form" {}}}}
         (select-keys (m26/envelope) ["io.modelcontextprotocol/protocolVersion"
                                      "io.modelcontextprotocol/clientCapabilities"]))))

(deftest discover-outcome-classification
  (is (= :2026 (m26/discover-outcome {:ok {:result {:supportedVersions ["2026-07-28"]}}})))
  (is (= :fallback (m26/discover-outcome {:ok {:result {:supportedVersions ["2025-11-25"]}}})))
  (is (= :fallback (m26/discover-outcome {:error "rpc-error" :code -32601})))
  (doseq [s [400 404 405]] (is (= :fallback (m26/discover-outcome {:error "http-status" :status s}))))
  (is (= :fallback (m26/discover-outcome {:error "malformed-body" :status 200})))
  (is (= :fallback (m26/discover-outcome {:error "http-status" :status 500})) "amended §2.2a: 5xx falls back")
  (is (= :failed (m26/discover-outcome {:error "http-status" :status 401})))
  (is (= :failed (m26/discover-outcome {:error "transport"})))
  (is (= :failed (m26/discover-outcome {:error "auth" :message "x"}))))

(deftest skill-uri-containment
  (doseq [u (get (expected) "skillUriRejected")]
    (is (thrown? Throwable (m26/parse-skill-uri u)) u))
  (is (= ["hello-world" "scripts/greet.sh"] (m26/parse-skill-uri "skill://hello-world/scripts/greet.sh"))))

(deftest sse-reply-uses-the-final-data-frame
  (is (= {:a 1} (:result (m26/parse-body "text/event-stream"
                                         ": ka\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"x\"}\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"a\":1}}\n\n")))))

;; ---------------------------------------------------------------------------
;; HTTP — the 2026 path
;; ---------------------------------------------------------------------------

(deftest http-discover-first-then-tools
  (with-server {}
    (fn [s]
      (let [c (mcp/connect (remote (:url s)))]
        (is (= "connected" (:status c)))
        (is (= "2026-07-28" (:protocol c)))
        (is (= "toolnexus-spike" (:name (:server-info c))) "serverInfo read from result _meta")
        (is (= ["fx_echo" "fx_greet"] (mapv :name (:tools c))))
        (is (= "server/discover" (first (fx/methods-seen s))) "discover is the first request")
        (is (not (some #{"initialize"} (fx/methods-seen s))) "no initialize on the 2026 path")
        (let [r (run c "fx_echo" {:text "hi toolnexus"})]
          (is (not (:isError r)))
          (is (= {"text" "hi toolnexus"} (json/read-str (:output r) {:key-fn str})) "structuredContent branch"))
        (let [call (last (:requests @(:state s)))]
          (is (= {"mcp-protocol-version" "2026-07-28" "mcp-method" "tools/call" "mcp-name" "echo"} (:headers call)))
          (is (= "toolnexus" (get-in call [:meta "io.modelcontextprotocol/clientInfo" "name"]))))
        (testing "SSE replies are read the same way"
          (swap! (:state s) assoc :sse true)
          (is (str/includes? (:output (run c "fx_echo" {:text "sse"})) "sse")))))))

(deftest mrtr-with-wait-for-asks-once
  (with-server {}
    (fn [s]
      (let [asked (atom [])
            c (mcp/connect (remote (:url s))
                           {:wait-for (fn [req] (swap! asked conj req)
                                        {:id (:id req) :ok true :data {:name "toolnexus"}})})
            r (run c "fx_greet" {})]
        (is (= (str "{\"greeting\":\"Hello, toolnexus!\"}") (:output r)))
        (is (= 1 (count @asked)))
        (is (= "input" (:kind (first @asked))))
        (is (= "What is your name?" (:prompt (first @asked))))
        (is (= ["name"] (get-in (first @asked) [:data :schema :required])))
        (is (= 1 (:executions @(:state s))))
        (testing "round 2 echoes requestState and carries inputResponses"
          (let [p (:params (last (:requests @(:state s))))]
            (is (= "rs-0" (get p "requestState")))
            (is (= "accept" (get-in p ["inputResponses" "name" "action"])))))))))

(deftest mrtr-without-wait-for-is-pending-then-resumes
  (with-server {}
    (fn [s]
      (let [c   (mcp/connect (remote (:url s)))
            p   (run c "fx_greet" {})
            req (get-in p [:metadata :pending])]
        (is (= "input" (:kind req)) "a §10 pending, not a hang")
        (is (str/starts-with? (:id req) "mrtr-"))
        (is (= 0 (:executions @(:state s))))
        (let [r (run c "fx_greet" {} {:answer {"id" (:id req) "ok" true "data" {"name" "Ada"}}})]
          (is (str/includes? (:output r) "Hello, Ada!")))
        (is (= 1 (:executions @(:state s))))
        (is (= 1 (:asked @(:state s))) "the earlier round is not re-asked")))))

(deftest no-hidden-retry
  (with-server {}
    (fn [s]
      (let [c (mcp/connect (remote (:url s)))]
        (testing "HTTP 502 after the tool ran"
          (swap! (:state s) assoc :fail-after-exec 502)
          (let [r (run c "fx_echo" {:text "x"})]
            (is (:isError r))
            (is (= 1 (:executions @(:state s))))
            (is (= 1 (count (filter #{"tools/call"} (fx/methods-seen s)))))))
        (testing "a timed-out call is not re-sent"
          (swap! (:state s) assoc :fail-after-exec nil :echo-delay-ms 1500)
          (let [c2 (mcp/connect (remote (:url s) :timeout 300))
                n  (count (filter #{"tools/call"} (fx/methods-seen s)))]
            (is (:isError (run c2 "fx_echo" {:text "slow"})))
            (proc/run-async! (fn [] nil))
            (is (= (inc n) (count (filter #{"tools/call"} (fx/methods-seen s)))))))))))

(deftest http-fallback-to-the-frozen-path
  (doseq [st [nil 404 405 400 500]]
    (let [old (fx/start-legacy-server {:discover-status st})]
      (try
        (let [c (mcp/connect (remote (:url old) :name "old"))]
          (is (= "connected" (:status c)) (str "discover status " st))
          (is (nil? (:protocol c)))
          (is (= ["server/discover" "initialize"] (take 2 (map :method @(:seen old)))))
          (is (= "hello from the old era" (:output (run c "old_hello" {})))))
        (finally ((:stop old)))))))

(deftest http-load-failures-are-isolated
  (let [c (mcp/connect (remote "http://127.0.0.1:1/mcp"))]
    (is (= "failed" (:status c)))
    (is (= "server/discover" (:phase c)))))

;; ---------------------------------------------------------------------------
;; stdio
;; ---------------------------------------------------------------------------

(defn- local [mode & {:as extra}]
  (merge {:name "sx" :kind "local" :enabled true :command (fx/stdio-command mode) :timeout 10000} extra))

(deftest stdio-2026-path
  (let [c (mcp/connect (local "2026") {:wait-for (fn [req] {:id (:id req) :ok true :data {:name "stdio"}})})]
    (try
      (is (= "connected" (:status c)))
      (is (= "2026-07-28" (:protocol c)))
      (is (= "stdio-fixture" (:name (:server-info c))))
      (is (= "{\"text\":\"via stdio\"}" (:output (run c "sx_echo" {:text "via stdio"}))))
      (is (= "Hello, stdio!" (:output (run c "sx_greet" {}))))
      (testing "concurrent calls are demultiplexed by id"
        (let [fs (mapv (fn [i] (future (:output (run c "sx_echo" {:text (str "n" i)})))) (range 8))]
          (is (= (mapv (fn [i] (str "{\"text\":\"n" i "\"}")) (range 8)) (mapv deref fs)))))
      (finally (mcp/disconnect c)))))

(deftest stdio-fallback
  (doseq [[mode timeout] [["legacy" 10000] ["silent" 800]]]
    (let [c (mcp/connect (local mode :timeout timeout))]
      (try
        (is (= "connected" (:status c)) mode)
        (is (nil? (:protocol c)) mode)
        (is (= "stdio-legacy" (:name (:server-info c))) mode)
        (finally (mcp/disconnect c))))))

(deftest stdio-discover-probe-child-reuse
  ;; §2.2a (amended), shared fixture examples/mcp-2026/legacy-stdio: a fast
  ;; discover error keeps the probe child (1 start); silence respawns (2).
  (let [script (str (examples-dir) "/mcp-2026/legacy-stdio/server.mjs")]
    (doseq [[mode timeout starts] [["error" 10000 1] ["silent" 1500 2]]]
      (let [d (fs/temp-dir! "tn-spawn")
            f (str d "/starts.txt")
            c (mcp/connect {:name "legacy" :kind "local" :enabled true :timeout timeout
                            :command ["node" script]
                            :environment {"SPAWN_COUNT_FILE" f "DISCOVER_MODE" mode}})]
        (try
          (is (= "connected" (:status c)) mode)
          (is (= "echo:hi" (:output (run c "legacy_echo" {:text "hi"}))) mode)
          (is (= starts (count (remove str/blank? (str/split-lines (fs/read-file f))))) mode)
          (finally (mcp/disconnect c) (fs/delete-tree! d)))))))

;; ---------------------------------------------------------------------------
;; §2.7 skills, §2.8 tool search
;; ---------------------------------------------------------------------------

(deftest remote-skills-join-the-skill-inventory
  (with-server {}
    (fn [s]
      (let [tk (core/build {:mcp {:mcpServers {"fx" {:url (:url s)}}}})
            local (skill/load-skills (str (examples-dir) "/skills"))
            remote-out (:output (core/execute tk "skill" {:name "hello-world"}))]
        (try
          (is (contains? (:tools tk) "skill"))
          (is (= (str/trim (:content (first (:skills local))))
                 (str/trim (:content (first (:skills (:skills tk))))))
              "a remote skill's body is byte-identical to the local copy")
          (is (str/includes? remote-out "Base directory for this skill: skill://hello-world/"))
          (is (str/includes? remote-out "<file>scripts/greet.sh</file>"))
          (testing "download-skill! verifies digests and writes the files"
            (let [dir (fs/temp-dir! "tn-skill")
                  conn (first (:connections tk))]
              (mcp/download-skill! conn "skill://hello-world/SKILL.md" dir)
              (is (= (fs/read-file (str (examples-dir) "/skills/hello-world/scripts/greet.sh"))
                     (fs/read-file (str dir "/hello-world/scripts/greet.sh"))))
              (fs/delete-tree! dir)))
          (finally (core/shutdown! tk)))))))

(deftest tool-search-exposes-two-meta-tools
  (with-server {}
    (fn [s]
      (let [c (mcp/connect (remote (:url s) :tool-search true :description "the fixture"))]
        (is (= ["fx_search_tools" "fx_call_tool"] (mapv :name (:tools c))) "direct names hidden")
        (is (= "Search the tools of the fixture by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool."
               (:description (first (:tools c)))))
        (let [hits (json/read-str (:output (run c "fx_search_tools" {:query "echo text"})) {:key-fn str})]
          (is (= "echo" (get (first hits) "name")))
          (is (str/starts-with? (:output (run c "fx_search_tools" {:query "echo"}))
                                "[{\"name\":\"echo\",\"description\":") "pinned key order")
          (is (= (set (get-in (expected) ["toolSearch" "hitKeys"])) (set (keys (first hits)))))
          (is (= "the fixture" (get (first hits) "server"))))
        (is (= "[]" (:output (run c "fx_search_tools" {:query "zzz"}))))
        (is (= "{\"text\":\"found\"}" (:output (run c "fx_call_tool" {:name "echo" :arguments {:text "found"}}))))
        (is (= "unknown tool \"nope\" on fx" (:output (run c "fx_call_tool" {:name "nope"}))))
        (testing "disable-tools applies before search"
          (let [d (mcp/connect (remote (:url s) :tool-search true) {:disable-tools ["fx_echo"]})]
            (is (= "[]" (:output (run d "fx_search_tools" {:query "echo"}))))
            (is (= "unknown tool \"echo\" on fx" (:output (run d "fx_call_tool" {:name "echo"}))))))))))
