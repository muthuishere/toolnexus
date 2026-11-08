;; ACP (Agent Client Protocol) model source — issue #96, ADR 0031,
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
;; Scripted per ACP_SCENARIO to exercise exactly the ADR 0031 gate items /
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
            [koine.http :as http]
            [toolnexus.acp :as acp]
            [toolnexus.client :as client]
            [toolnexus.core :as toolnexus]
            [toolnexus.native :as native]
            [toolnexus.serve :as serve]
            [toolnexus.shared-examples-test :as te]))

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

(defn- fake-chunk! [ctx sid kind text]
  (fake-notify! ctx "session/update"
                {:sessionId sid :update {:sessionUpdate kind :content {:type "text" :text text}}}))

(defn- fake-handle-drip!
  "conformance/acp-client stream.acpDrip: one thought, then each chunk gapMs
  apart — a buffered stream would show ~0ms lead before done."
  [ctx id sid]
  (fake-chunk! ctx sid "agent_thought_chunk" "pondering")
  (fake-chunk! ctx sid "agent_message_chunk" "drip-1 ")
  (ktime/sleep! 200)
  (fake-chunk! ctx sid "agent_message_chunk" "drip-2 ")
  (ktime/sleep! 200)
  (fake-chunk! ctx sid "agent_message_chunk" "drip-3")
  (fake-reply! ctx id {:stopReason "end_turn"}))

(defn- fake-handle-tool-update!
  "conformance/acp-client stream.acpToolUpdate (#188): the case's updates in
  order (the final agent_message_chunk included), then end_turn."
  [ctx id sid]
  (doseq [u [{:sessionUpdate "tool_call" :toolCallId "t1" :title "Write" :kind "edit" :status "pending"}
             {:sessionUpdate "tool_call_update" :toolCallId "t1" :status "in_progress"
              :title "Write /tmp/a.txt" :rawInput {:file_path "/tmp/a.txt" :content "hi"}}
             {:sessionUpdate "tool_call_update" :toolCallId "t1" :status "in_progress"}
             {:sessionUpdate "tool_call_update" :toolCallId "t1" :status "completed"
              :content [{:type "content" :content {:type "text" :text "wrote"}}]}]]
    (fake-notify! ctx "session/update" {:sessionId sid :update u}))
  (fake-chunk! ctx sid "agent_message_chunk" "done")
  (fake-reply! ctx id {:stopReason "end_turn"}))

(defn- fake-handle-fs-request!
  "conformance/acp-client unknownRequest: ask the client for something it does
  not implement, record the answer, then finish the turn with \"ok\"."
  [ctx id sid]
  (let [[req-id p] (fake-request! ctx "fs/read_text_file" {:sessionId sid :path "/etc/hosts"})
        reply      @p]
    (swap! (:pending ctx) dissoc req-id)
    (fake-emit! ctx {:type "fs-reply" :data reply})
    (fake-chunk! ctx sid "agent_message_chunk" "ok")
    (fake-reply! ctx id {:stopReason "end_turn"})))

(defn- fake-handle-prompt! [scenario ctx id sid text]
  (cond
    (= "1" (env/get-env "TN_FAKE_DRIP"))       (fake-handle-drip! ctx id sid)
    (= "1" (env/get-env "TN_FAKE_TOOLUPDATE")) (fake-handle-tool-update! ctx id sid)
    (= "1" (env/get-env "TN_FAKE_FS_REQUEST")) (fake-handle-fs-request! ctx id sid)
    :else
  (case scenario
    "stale"      (fake-handle-stale! ctx id sid text)
    "permission" (fake-handle-permission! ctx id sid text)
    "noisy"      (fake-handle-noisy! ctx id sid text)
    "serialize"  (fake-handle-serialize! ctx id sid text)
    (fake-handle-warm! ctx id sid text))))

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
        ctx      (new-fake-ctx out-file)
        stop     (atom false)]
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
                (do (fake-emit! ctx {:type "initialize" :data (:params msg)})
                    (fake-reply! ctx (:id msg) {:protocolVersion 1 :agentCapabilities {:loadSession false}}))

                (= "session/set_config_option" (:method msg))
                (do (fake-emit! ctx {:type "session/set_config_option" :data (:params msg)})
                    (if (= "1" (env/get-env "TN_FAKE_NO_CONFIG_OPTION"))
                      (fake-reply-err! ctx (:id msg) -32601 "method not found")
                      (fake-reply! ctx (:id msg) {})))

                (= "session/set_model" (:method msg))
                (do (fake-emit! ctx {:type "session/set_model" :data (:params msg)})
                    (fake-reply! ctx (:id msg) {}))

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
                  (if (= "1" (env/get-env "TN_FAKE_EXIT_ON_PROMPT"))
                    ;; conformance agentExit: never reply — just exit
                    (reset! stop true)
                    (proc/run-async! (fn [] (fake-handle-prompt! scenario ctx id sid text)))))

                (= "session/cancel" (:method msg))
                (fake-reply! ctx (:id msg) {})

                (some? (:id msg))
                (fake-reply! ctx (:id msg) {})

                :else nil))))
        (when-not @stop (recur))))))

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

;; ===========================================================================
;; conformance/acp-client/cases.json — SPEC §8C, replayed case by case
;; ===========================================================================

(defn- cases []
  (json/read-str (fs/read-file (str (te/examples-dir) "/../conformance/acp-client/cases.json"))))

(defn- err-of [f]
  (try (f) nil (catch Throwable e (ex-message e))))

(defn- fake-env [scenario out-file extra]
  (cond-> (merge {"ACP_FAKE_SERVER" "1" "ACP_SCENARIO" scenario}
                 (into {} (map (fn [[k v]] [(name k) (str v)]) extra)))
    out-file (assoc "ACP_OUT_FILE" out-file)))

(defn- connect-with! [extra-env opts & {:keys [out-file]}]
  (acp/connect (fake-server-command)
               (merge {:environment (fake-env "warm" out-file extra-env)
                       :timeout 20000 :permission-timeout 20000}
                      opts)))

(defn- events-of [out-file type]
  (filterv #(= type (:type %)) (read-events out-file)))

(deftest conformance-stream-in-process-is-incremental
  (let [cs     (get-in (cases) [:stream :inProcess])
        evs    (atom [])
        wait!  (fn [delta]
                 (let [deadline (+ (ktime/mono-ms) (:waitMs cs))]
                   (loop []
                     (cond
                       (some #(and (= "text" (:type %)) (= delta (:delta %))) @evs) nil
                       (> (ktime/mono-ms) deadline) (throw (ex-info (:notIncrementalError cs) {}))
                       :else (do (ktime/sleep! 1) (recur))))))
        c      (client/create-in-process-client
                {:model "m"
                 :generate-stream
                 (fn [_req emit]
                   (doseq [e (:emits cs)]
                     (emit (dissoc e :awaitConsumer))
                     (when (:awaitConsumer e) (wait! (:delta e))))
                   {:content (get-in cs [:returns :content])})})
        r      (client/run c (:prompt cs) {:toolkit (toolnexus/build {:builtins false})
                                           :on-event #(swap! evs conj %)})
        texts  (mapv :delta (filter #(= "text" (:type %)) @evs))
        others (filterv #(contains? #{"thought" "tool_call"} (:type %)) @evs)
        done   (:result (last @evs))]
    (is (= (:expectTextDeltas cs) texts))
    (is (= (:expectPassThrough cs)
           (mapv (fn [want got] (select-keys got (keys want))) (:expectPassThrough cs) others)))
    (is (= (count (:expectPassThrough cs)) (count others)))
    (is (= "done" (:type (last @evs))))
    (is (= (:expectDoneText cs) (:text done) (:text r)))
    (is (= (:expectDoneToolCalls cs) (count (:tool-calls done))))
    (testing "a stream that never reaches the consumer trips the latch loudly"
      (let [c2 (client/create-in-process-client
                {:model "m"
                 :generate-stream (fn [_ emit]
                                    (emit {:type "text" :delta "al"})
                                    (throw (ex-info (:notIncrementalError cs) {})))})]
        (is (str/includes? (str (err-of #(client/run c2 "q" {:toolkit (toolnexus/build {:builtins false})})))
                           (:notIncrementalError cs)))))
    (when (:plainGenerateStillRefuses cs)
      ;; This port has no separate streaming entry point (the :on-event sink is
      ;; the event channel), so there is nothing to refuse: a plain :generate
      ;; serves the run whole and yields no text deltas.
      (testing "a plain :generate yields no text deltas"
        (let [seen (atom [])
              c3   (client/create-in-process-client {:model "m" :generate (fn [_] {:content "whole"})})
              r3   (client/run c3 "q" {:toolkit (toolnexus/build {:builtins false})
                                       :on-event #(swap! seen conj %)})]
          (is (= "whole" (:text r3)))
          (is (not-any? #(= "text" (:type %)) @seen)))))))

(deftest conformance-stream-acp-drip-is-incremental
  (let [cs  (get-in (cases) [:stream :acpDrip])
        a   (connect-with! (:fakeEnv cs) {})
        c   (client/create-in-process-client {:model "m" :generate-stream (acp/generate-stream a)})
        tk  (toolnexus/build {:builtins false})
        evs (atom [])]
    (try
      (let [r       (client/run c "drip please" {:toolkit tk :on-event #(swap! evs conj [(ktime/mono-ms) %])})
            ev      (mapv second @evs)
            texts   (filterv #(= "text" (:type %)) ev)
            th      (filterv #(= "thought" (:type %)) ev)
            first-t (some (fn [[t e]] (when (= "text" (:type e)) t)) @evs)
            done-t  (some (fn [[t e]] (when (= "done" (:type e)) t)) @evs)]
        (is (= (:chunks cs) (mapv :delta texts)))
        (is (= [(:expectThought cs)] (mapv #(select-keys % [:type :delta :via]) th)))
        (is (= (:expectDoneText cs) (:text (:result (last ev))) (:text r)))
        (is (>= (- done-t first-t) (:minLeadMs cs))
            (str "first text delta only " (- done-t first-t) "ms before done"))
        (is (= (:expectDoneText cs) (:text (client/run c "again" {:toolkit tk})))))
      (finally (acp/close a)))))

(deftest conformance-stream-acp-tool-update
  (let [cs  (get-in (cases) [:stream :acpToolUpdate])
        a   (connect-with! (:fakeEnv cs) {})
        c   (client/create-in-process-client {:model "m" :generate-stream (acp/generate-stream a)})
        evs (atom [])]
    (try
      (let [r     (client/run c "q" {:toolkit (toolnexus/build {:builtins false})
                                     :on-event #(swap! evs conj %)})
            tools (filterv #(contains? #{"tool_call" "tool_call_args" "tool_result"} (:type %)) @evs)
            got   (mapv (fn [e]
                          (if (= "tool_call" (:type e))
                            (select-keys e [:type :id :name :via])
                            (select-keys e [:type :id :name :args :output :isError :via])))
                        tools)]
        (is (= (:expectEvents cs) got))
        (is (= (:expectDoneText cs) (:text (:result (last @evs))) (:text r))))
      (finally (acp/close a)))))

(deftest conformance-passthrough
  (let [cs  (:passthrough (cases))
        o   (:options cs)
        opts {:mcp-servers (:mcpServers o) :session-meta (:sessionMeta o)
              :client-caps-meta (:clientCapsMeta o) :model (:model o)}]
    (testing "every option arrives verbatim"
      (let [out (str (fs/temp-dir! "acp") "/events.ndjson")
            a   (connect-with! {} opts :out-file out)]
        (acp/close a)
        (let [init (:data (first (events-of out "initialize")))
              sn   (:data (first (events-of out "session/new")))
              sco  (:data (first (events-of out "session/set_config_option")))]
          (is (= (:expectInitializeClientCapabilities cs) (:clientCapabilities init)))
          (is (= (:mcpServers o) (:mcpServers sn)) "mcpServers deep-equal and in order")
          (is (= (:sessionMeta o) (:_meta sn)))
          (is (= (:expectSetConfigOption cs) (select-keys sco [:configId :value])))
          (is (empty? (events-of out "session/set_model"))))))
    (testing "set_model fallback"
      (let [fb  (:fallbackSetModel cs)
            out (str (fs/temp-dir! "acp") "/events.ndjson")
            a   (connect-with! (:fakeEnv fb) opts :out-file out)]
        (acp/close a)
        (is (= (:expectSetModel fb)
               (select-keys (:data (first (events-of out "session/set_model"))) [:modelId])))))
    (testing "bare: nothing set"
      (let [be  (:bareExpect cs)
            out (str (fs/temp-dir! "acp") "/events.ndjson")
            a   (connect-with! {} {} :out-file out)]
        (acp/close a)
        (let [init (:data (first (events-of out "initialize")))
              sn   (:data (first (events-of out "session/new")))]
          (is (= (:mcpServers be) (:mcpServers sn)))
          (is (contains? sn :mcpServers))
          (is (= (:sessionNewHasMeta be) (contains? sn :_meta)))
          (is (= (:clientCapabilitiesHasMeta be) (contains? (:clientCapabilities init) :_meta)))
          (is (empty? (events-of out "session/set_config_option"))))))))

(deftest conformance-agent-exit
  (let [cs (:agentExit (cases))
        a  (connect-with! (:fakeEnv cs) {:permission-timeout 30000})
        t0 (ktime/mono-ms)]
    (try
      (let [e (err-of #((acp/generate a) {:messages [{:role "user" :content "hi"}]}))]
        (is (str/includes? (str e) (:expectError cs)) (str e))
        (is (< (- (ktime/mono-ms) t0) 15000) "failed promptly, not by the 30s timeout"))
      (finally (acp/close a)))))

(deftest conformance-unknown-request
  (let [cs  (:unknownRequest (cases))
        out (str (fs/temp-dir! "acp") "/events.ndjson")
        a   (connect-with! (:fakeEnv cs) {} :out-file out)]
    (try
      (is (= (:expectText cs) (:content ((acp/generate a) {:messages [{:role "user" :content "hi"}]}))))
      (finally (acp/close a)))
    (let [reply (:data (first (events-of out "fs-reply")))]
      (is (= (:expectCode cs) (get-in reply [:error :code])) (pr-str reply)))))

(deftest conformance-as-mcp-server
  (let [cs   (:asMcpServer (cases))
        tk   (toolnexus/build
              {:builtins false
               :skills   (str (te/examples-dir) "/skills")
               :tools    [(native/native-tool {:name "echo" :description "Echo text back."
                                               :input-schema {:type "object"
                                                              :properties {:text {:type "string"}}}
                                               :run (fn [a] (str (:text a)))})]})
        srv  (serve/as-mcp-server tk)
        srv2 (serve/as-mcp-server tk)
        url  (:url srv)
        auth {"Authorization" (str "Bearer " (:token srv))}
        init "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"
        post (fn [u headers]
               (http/request {:method :post :url u :body init :timeout-ms 5000
                              :headers (merge {"Content-Type" "application/json"} headers)}))
        hdr  (fn [res k] (some (fn [[hk v]] (when (= (str/lower-case (str hk)) (str/lower-case k)) v))
                               (:headers res)))]
    (try
      (is (re-matches (re-pattern (:urlPattern cs)) url))
      (is (re-matches (re-pattern (:tokenPattern cs)) (:token srv)))
      (let [r (post url {})]
        (is (= (:noTokenStatus cs) (:status r)))
        (doseq [[k v] (:noTokenHeader cs)]
          (is (= v (str (let [x (hdr r (name k))] (if (sequential? x) (first x) x)))))))
      (is (= (:wrongTokenStatus cs) (:status (post url {"Authorization" "Bearer nope"}))))
      (is (= (:getWithTokenStatus cs)
             (:status (http/request {:method :get :url url :headers auth :timeout-ms 5000}))))
      (is (= (:otherPathStatus cs)
             (:status (post (str/replace url #"/mcp$" "/other") auth))))
      (is (= 200 (:status (post url auth))))
      (when (:twoServersDistinctTokens cs)
        (is (not= (:token srv) (:token srv2)))
        (is (= 401 (:status (post url {"Authorization" (str "Bearer " (:token srv2))})))))
      (testing "through this port's own MCP client"
        (let [mtk   (toolnexus/build {:builtins false
                                      :mcp {:mcpServers {"tn" {:type "remote" :url url
                                                               :headers auth}}}})
              names (toolnexus/tool-names mtk)
              find  (fn [n] (some #(when (or (= n %) (str/ends-with? % (str "_" n))) %) names))]
          (try
            (doseq [n (:toolsListContains cs)]
              (is (find n) (str n " in " (pr-str names))))
            (let [ce (:callEcho cs)
                  r  (toolnexus/execute mtk (find (:name ce)) (:arguments ce))]
              (is (= (:expectText ce) (:output r))))
            (finally (toolnexus/shutdown! mtk)))))
      (finally
        ((:close srv)) ((:close srv)) ((:close srv2))))
    (when (:afterCloseRefused cs)
      ;; koine answers a refused connection with no :status (or a throw)
      (is (nil? (try (:status (post url auth)) (catch Throwable _ nil)))))))
