;; ACP (Agent Client Protocol) model source — issue #96, ADR 0025,
;; openspec/changes/add-acp-model-source. Hermetic: no network, no real
;; devin/gemini/zed agent. The "fake ACP agent" is THIS PORT ITSELF,
;; re-invoked as a subprocess with ACP_FAKE_SERVER=1 — the classic
;; re-invoke-the-test-binary trick golang/acp_test.go uses
;; (GO_WANT_ACP_HELPER + `go test -run TestACPHelperProcess`), generalised to
;; this port's two hosts: `clojure -M -m toolnexus.test-main` on the JVM,
;; `cljgo run src/run_tests.cljc` on cljgo — both already how
;; `toolnexus.test-main/-main` is invoked by every mode `all-modes-check.sh`
;; runs, so no new build artifact and no jvm-only-check.sh violation (see
;; `fake-server-command`). `toolnexus.test-main/-main` checks
;; ACP_FAKE_SERVER BEFORE its usual TN_EXAMPLES gate and, when set, calls
;; `run-fake-server!` below instead of the suite.
;;
;; Scripted per ACP_SCENARIO to exercise exactly the ADR 0025 gate items /
;; spec scenarios this file covers, reporting what it observed by rewriting
;; ACP_OUT_FILE (newline-delimited JSON) after each event, for the parent
;; test to read back once the child is closed.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.acp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.host :as host]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.acp :as acp]
            [toolnexus.client :as client]
            [toolnexus.core :as toolnexus]))

;; ===========================================================================
;; the fake ACP agent — see toolnexus.test-main/-main for how it is reached
;; ===========================================================================

;; Matches toolnexus.acp's own (private) marker byte-for-byte — see that
;; namespace's `supersedes-marker`, and elixir/test/acp_test.exs, which
;; hardcodes the same literal for the same reason: a test fixture keys off
;; the WIRE TEXT, not an internal implementation detail the production
;; namespace has no reason to export.
(def ^:private supersedes-marker "SUPERSEDES-ALL-PRIOR:")

(defn- fake-send! [ctx msg]
  ((:with-write-lock ctx)
   (fn [] (println (json/write-str msg)) (flush))))

(defn- fake-reply! [ctx id result]
  (fake-send! ctx {:jsonrpc "2.0" :id id :result result}))

(defn- fake-reply-err! [ctx id code message]
  (fake-send! ctx {:jsonrpc "2.0" :id id :error {:code code :message message}}))

(defn- fake-notify! [ctx method params]
  (fake-send! ctx {:jsonrpc "2.0" :method method :params params}))

(defn- fake-request!
  "Send a server-initiated request and return [id promise] — the promise is
  delivered by the main read loop once a matching id-only reply arrives (see
  run-fake-server!)."
  [ctx method params]
  (let [id (str "srv-" (swap! (:server-id ctx) inc))
        p  (promise)]
    (swap! (:pending ctx) assoc id p)
    (fake-send! ctx {:jsonrpc "2.0" :id id :method method :params params})
    [id p]))

(defn- fake-emit!
  "Append one observation to ACP_OUT_FILE, if set, for the parent test to
  read back after the client closes this process. Rewrites the whole file —
  koine.fs has no append, and these fixtures write a handful of lines."
  [ctx ev]
  (when-let [out-file (:out-file ctx)]
    ((:with-events-lock ctx)
     (fn []
       (swap! (:events ctx) conj ev)
       (fs/write-file out-file (str (str/join "\n" (map json/write-str @(:events ctx))) "\n"))))))

(defn- fake-handle-warm! [ctx id sid text]
  (fake-notify! ctx "session/update"
                {:sessionId sid
                 :update {:sessionUpdate "agent_message_chunk"
                          :content       {:type "text" :text (str "echo:" text)}}})
  (fake-reply! ctx id {:stopReason "end_turn"}))

(defn- fake-handle-stale!
  "Mirrors golang/acp_test.go's acpHandleStaleForTest: a naive stateful agent
  answers the FIRST history entry that is a substring of the current prompt,
  UNLESS the current prompt carries the supersedes marker, in which case it
  answers only the text after the marker."
  [ctx id sid text]
  (swap! (:history ctx) conj text)
  (let [snapshot   @(:history ctx)
        marker-idx (str/index-of text supersedes-marker)
        answer     (if marker-idx
                     (str "FRESH-ANSWER-TO:"
                          (str/trim (subs text (+ marker-idx (count supersedes-marker)))))
                     (let [matched (or (some (fn [h] (when (str/includes? text h) h)) snapshot)
                                       (first snapshot))]
                       (str "STALE-ANSWER-TO:" matched)))]
    (fake-notify! ctx "session/update"
                  {:sessionId sid
                   :update {:sessionUpdate "agent_message_chunk"
                            :content       {:type "text" :text answer}}})
    (fake-reply! ctx id {:stopReason "end_turn"})))

(defn- fake-handle-permission! [ctx id sid _text]
  (let [[req-id p] (fake-request! ctx "session/request_permission"
                                   {:sessionId sid
                                    :options [{:optionId "reject" :kind "reject_once" :name "Reject"}
                                              {:optionId "allow-once" :kind "allow_once" :name "Allow"}]})
        reply-msg  @p
        outcome    (get-in reply-msg [:result :outcome])]
    (swap! (:pending ctx) dissoc req-id)
    (fake-emit! ctx {:type "permission-answer"
                     :data {:outcome (:outcome outcome) :optionId (:optionId outcome)}})
    (fake-reply! ctx id {:stopReason "end_turn"})))

(defn- fake-handle-noisy! [ctx id sid _text]
  (doseq [c [{:sessionUpdate "agent_thought_chunk"
              :content {:type "text" :text "Let me think about this... "}}
             {:sessionUpdate "tool_call" :toolCallId "t1" :title "reading files" :status "in_progress"}
             {:sessionUpdate "agent_message_chunk" :content {:type "text" :text "{\"answer\":"}}
             {:sessionUpdate "tool_call_update" :toolCallId "t1" :status "completed"}
             {:sessionUpdate "agent_thought_chunk" :content {:type "text" :text "now double-checking... "}}
             {:sessionUpdate "agent_message_chunk" :content {:type "text" :text "true}"}}]]
    (fake-notify! ctx "session/update" {:sessionId sid :update c}))
  (fake-reply! ctx id {:stopReason "end_turn"}))

(defn- fake-handle-serialize!
  "Held long enough (150ms) that a client which failed to serialise turns
  would land a second session/prompt on top of this one and trip the
  compare-and-set! guard below — mirrors golang/acp_test.go's `busy` flag."
  [ctx id sid text]
  (if-not (compare-and-set! (:busy ctx) false true)
    (do (fake-emit! ctx {:type "violation"})
        (fake-reply-err! ctx id -32000 "reentrant session/prompt"))
    (do
      (ktime/sleep! 150)
      (fake-notify! ctx "session/update"
                    {:sessionId sid
                     :update {:sessionUpdate "agent_message_chunk"
                              :content       {:type "text" :text (str "ok:" text)}}})
      (fake-reply! ctx id {:stopReason "end_turn"})
      (reset! (:busy ctx) false))))

(defn- fake-handle-prompt! [scenario ctx id sid text]
  (case scenario
    "stale"      (fake-handle-stale! ctx id sid text)
    "permission" (fake-handle-permission! ctx id sid text)
    "noisy"      (fake-handle-noisy! ctx id sid text)
    "serialize"  (fake-handle-serialize! ctx id sid text)
    (fake-handle-warm! ctx id sid text)))

(defn- fake-lock []
  (let [lock (atom false)]
    (fn [f]
      (loop []
        (if (compare-and-set! lock false true)
          (try (f) (finally (reset! lock false)))
          (do (ktime/sleep! 1) (recur)))))))

(defn- new-fake-ctx [out-file]
  {:out-file          out-file
   :events            (atom [])
   :with-events-lock  (fake-lock)
   :with-write-lock   (fake-lock)
   :history           (atom [])
   :pending           (atom {})
   :server-id         (atom 0)
   :busy              (atom false)})

(defn run-fake-server!
  "The fake ACP agent's whole read loop — reads JSON-RPC lines from its OWN
  stdin (`read-line`/`println`/`flush`, plain clojure.core, present on both
  hosts — cljgo embeds its own clojure.core, see test_main.cljc's own use of
  `println` for its verdict line) until EOF, dispatching each `session/prompt`
  onto a `koine.process/run-async!` background thread so the read loop stays
  free to observe a second, concurrent prompt (needed for the
  turn-serialisation gate) and to relay a permission answer back to whichever
  handler is waiting on it."
  []
  (let [scenario (env/get-env "ACP_SCENARIO" "warm")
        out-file (env/get-env "ACP_OUT_FILE")
        ctx      (new-fake-ctx out-file)]
    (loop []
      (when-let [line (read-line)]
        (when-not (str/blank? line)
          (let [msg (try (json/read-str line) (catch Throwable _ ::bad))]
            (when (and (not= ::bad msg) (map? msg))
              (cond
                (and (nil? (:method msg)) (some? (:id msg)))
                (when-let [p (get @(:pending ctx) (:id msg))]
                  (deliver p msg))

                (= "initialize" (:method msg))
                (fake-reply! ctx (:id msg) {:protocolVersion 1 :agentCapabilities {:loadSession false}})

                (= "session/new" (:method msg))
                (do (fake-emit! ctx {:type "session/new" :data (:params msg)})
                    (fake-reply! ctx (:id msg) {:sessionId "sess-1"}))

                (= "session/set_mode" (:method msg))
                (fake-reply! ctx (:id msg) {})

                (= "session/prompt" (:method msg))
                (let [params (:params msg)
                      sid    (:sessionId params)
                      text   (str/join "" (map :text (:prompt params)))
                      id     (:id msg)]
                  (fake-emit! ctx {:type "session/prompt" :data {:text text}})
                  (proc/run-async! (fn [] (fake-handle-prompt! scenario ctx id sid text))))

                (= "session/cancel" (:method msg))
                (fake-reply! ctx (:id msg) {})

                (some? (:id msg))
                (fake-reply! ctx (:id msg) {})

                :else nil))))
        (recur)))))

;; ===========================================================================
;; parent-side test helpers
;; ===========================================================================

(defn- fake-server-command
  "How to reach THIS namespace's own `run-fake-server!` as a subprocess, on
  whichever host is running the current test — never `cljgo` from a JVM host
  (jvm-only-check.sh poisons it), and never a build artifact that does not
  already exist on every mode (all-modes-check.sh's five legs already run
  exactly these two commands)."
  []
  (if (= :jvm host/id)
    ["clojure" "-M" "-m" "toolnexus.test-main"]
    ["cljgo" "run" "src/run_tests.cljc"]))

(defn- connect!
  [scenario & {:keys [out-file opts]}]
  (acp/connect (fake-server-command)
               (merge {:environment (cond-> {"ACP_FAKE_SERVER" "1" "ACP_SCENARIO" scenario}
                                      out-file (assoc "ACP_OUT_FILE" out-file))
                       :timeout 20000
                       :permission-timeout 5000}
                      opts)))

(defn- read-events [path]
  (->> (str/split-lines (fs/read-file path))
       (map str/trim)
       (remove str/blank?)
       (map json/read-str)))

;; ===========================================================================
;; tests
;; ===========================================================================

;; 1. warm session reuse ------------------------------------------------------

(deftest a-warm-session-serves-many-turns-from-one-process-one-session-new
  (let [dir      (fs/temp-dir! "acp")
        out-file (str dir "/events.ndjson")
        c        (connect! "warm" :out-file out-file)]
    (try
      (dotimes [i 3]
        (let [res (acp/prompt c (str "turn " i))]
          (is (nil? (:error res)))
          (is (str/includes? (:ok res) "echo:"))))
      (finally (acp/close c)))
    (let [events (read-events out-file)]
      (is (= 1 (count (filter #(= "session/new" (:type %)) events)))
          "exactly one session/new — the process and session were opened once")
      (is (= 3 (count (filter #(= "session/prompt" (:type %)) events)))
          "one session/prompt per turn"))))

(deftest session-new-carries-an-absolute-cwd-and-an-mcp-servers-array
  (let [dir      (fs/temp-dir! "acp")
        out-file (str dir "/events.ndjson")
        c        (connect! "warm" :out-file out-file)]
    (acp/prompt c "hi")
    (acp/close c)
    (let [ev  (first (filter #(= "session/new" (:type %)) (read-events out-file)))
          cwd (get-in ev [:data :cwd])]
      (is (some? ev))
      (is (str/starts-with? (str cwd) "/")
          "real devin acp rejects session/new with -32602 without an absolute cwd")
      (is (contains? (get-in ev [:data]) :mcpServers)))))

(deftest generate-drives-the-in-process-client-end-to-end
  (let [c        (connect! "warm")
        generate (acp/generate c)
        tk       (toolnexus/build {:builtins false})
        client   (client/create-in-process-client {:model "acp-fake" :generate generate})
        result   (client/run client "hello" {:toolkit tk})]
    (acp/close c)
    (is (= "done" (:status result)))
    (is (str/includes? (:text result) "echo:"))))

;; 2. thought/tool narration filtered -----------------------------------------

(deftest only-agent-message-chunk-forms-the-reply
  (let [c   (connect! "noisy")
        res (acp/prompt c "what is the answer")]
    (acp/close c)
    (is (nil? (:error res)))
    (is (not (str/includes? (:ok res) "Let me think")))
    (is (not (str/includes? (:ok res) "double-checking")))
    (let [parsed (json/read-str (:ok res))]
      (is (true? (:answer parsed))))))

;; 3. permission answered inline, never awaited by the caller -----------------

(deftest a-permission-request-is-answered-with-the-first-allow-kind-option-inline
  (let [dir      (fs/temp-dir! "acp")
        out-file (str dir "/events.ndjson")
        c        (connect! "permission" :out-file out-file)
        start    (ktime/mono-ms)
        res      (acp/prompt c "delete the db")
        elapsed  (- (ktime/mono-ms) start)]
    (acp/close c)
    (is (nil? (:error res)))
    (is (< elapsed 1000)
        "answered inline from the reader thread, not routed through the caller — should complete in well under a second")
    (let [ev (first (filter #(= "permission-answer" (:type %)) (read-events out-file)))]
      (is (some? ev) "the fake server never observed a permission answer")
      (is (= "selected" (get-in ev [:data :outcome])))
      (is (= "allow-once" (get-in ev [:data :optionId]))))))

;; 4. the supersedes marker prevents a stale answer ----------------------------

(deftest a-stateful-session-answers-a-near-duplicate-with-a-stale-answer-unless-marked
  (let [c (connect! "stale")]
    (let [a (:ok (acp/prompt c "What is the capital of France?"))]
      (is (str/starts-with? a "STALE-ANSWER-TO:")))
    ;; The SECOND prompt contains the first, verbatim, with no marker — the
    ;; fake agent matches the EARLIEST remembered question and answers stale.
    (let [b (:ok (acp/prompt c "What is the capital of France?\nWhat is the capital of Japan?"))]
      (is (= "STALE-ANSWER-TO:What is the capital of France?" b)))
    ;; The library's own supersedes marker fixes it.
    (let [c2 (:ok (acp/prompt c (str "What is the capital of France?\nWhat is the capital of Japan?\n"
                                     supersedes-marker " What is the capital of Japan?")))]
      (is (= "FRESH-ANSWER-TO:What is the capital of Japan?" c2)))
    (acp/close c)))

(deftest generate-assembles-the-supersedes-marker-itself
  (let [c        (connect! "stale")
        generate (acp/generate c)
        r1       (generate {:messages [{:role "user" :content "What is the capital of France?"}]})]
    (is (str/includes? (:content r1) "FRESH-ANSWER-TO:What is the capital of France?"))
    (let [r2 (generate {:messages [{:role "user" :content "What is the capital of France?"}
                                    {:role "assistant" :content "STALE-ANSWER-TO:What is the capital of France?"}
                                    {:role "user" :content "What is the capital of Japan?"}]})]
      (is (str/includes? (:content r2) "FRESH-ANSWER-TO:What is the capital of Japan?")))
    (acp/close c)))

;; 5. turns on one session are serialised --------------------------------------

(deftest turns-on-one-session-are-serialised-never-sent-concurrently
  (let [dir      (fs/temp-dir! "acp")
        out-file (str dir "/events.ndjson")
        c        (connect! "serialize" :out-file out-file)
        results  (atom [])
        threads  (mapv (fn [n]
                          (proc/run-async!
                           (fn [] (swap! results conj (acp/prompt c (str "t" n))))))
                        [1 2 3])]
    ;; A generous settle — three TRUE-serial 150ms turns take >= ~450ms; poll
    ;; rather than a fixed sleep so a slow CI box still gets a fair result.
    (let [deadline (+ (ktime/mono-ms) 10000)]
      (while (and (< (count @results) 3) (< (ktime/mono-ms) deadline))
        (ktime/sleep! 5)))
    (acp/close c)
    (is (= 3 (count @results)))
    (doseq [r @results] (is (nil? (:error r))))
    (let [texts (set (map :ok @results))]
      (is (contains? texts "ok:t1"))
      (is (contains? texts "ok:t2"))
      (is (contains? texts "ok:t3")))
    (is (empty? (filter #(= "violation" (:type %)) (read-events out-file)))
        "the fake server observed a reentrant session/prompt — turns were not serialised")))

;; 6. process lifetime + idempotent close --------------------------------------

(deftest close-is-idempotent
  (let [c (connect! "warm")]
    (acp/prompt c "one")
    (is (nil? (acp/close c)))
    (is (nil? (acp/close c)))
    (is (nil? (acp/close c)))))

(deftest a-turns-own-failure-does-not-take-down-the-process
  (let [c (connect! "warm")]
    (let [r1 (acp/prompt c "first")]
      (is (nil? (:error r1))))
    (let [r2 (acp/prompt c "second")]
      (is (nil? (:error r2)))
      (is (str/includes? (:ok r2) "second")))
    (acp/close c)))
