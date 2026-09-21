;; toolnexus.agents.home — the suite. SPEC §7E (agent home / personas).
;;
;; Expected values come from SPEC.md §7E and `js/src/agents/home.ts` — the
;; bootstrap order, the `## <filename>` section shape, the byte cap, and the
;; memory tool's three actions. Not from this port's own output.
;;
;; Every test writes into a real temp directory and reads the files back off
;; disk, because "all actions write to disk" is the actual §7E claim: asserting
;; on the tool's return string alone would pass just as happily if nothing were
;; ever persisted.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.agents.home-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.time :as ktime]
            [toolnexus.agents.home :as home]
            [toolnexus.agents.runtime :as rt]
            [toolnexus.native :as native]))

(defn- with-home
  "Run `f` against a fresh temp dir seeded with `files` (a name->content map)."
  [files f]
  (let [dir (fs/temp-dir! "tn-home")]
    (try
      (doseq [[name content] files]
        (fs/write-file (str dir "/" name) content))
      (f (str dir))
      (finally (fs/delete-tree! dir)))))

(defn- call [t args] (native/execute-native t args))

(defn- skill-name
  "The directory's own last segment — what `from-dir` names an unnamed persona."
  [dir]
  (last (str/split (str dir) #"/")))

;; A scripted \"LLM\", identical in shape to the one `runtime-test` uses: a
;; per-model list of turn specs replayed over an injected `:http-client`. Kept
;; local rather than shared, because a test namespace that depends on another
;; test namespace's fixtures fails in whichever order the runner happens to load
;; them.
(defn- mock-llm
  ([script] (mock-llm script nil))
  ([script on-call]
   (let [requests (atom [])
         counts   (atom {})]
     {:requests requests
      :http-client
      (fn [_url _headers body]
        (let [b     (json/read-str body)
              model (:model b)
              n     (get (swap! counts update model (fnil inc 0)) model)
              spec  (get (get script model) (dec n))]
          (swap! requests conj b)
          (when on-call (on-call b n))
          {:status 200 :headers {"content-type" "application/json"}
           :body (json/write-str
                  {:choices [{:message {:role "assistant" :content (or (:text spec) "done")}}]
                   :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})}))})))

(defn- persona-runtime
  "A runtime holding exactly this persona, over a scripted LLM."
  [agent-def script]
  (let [m (mock-llm script)]
    (assoc m :rt (rt/create-runtime {:registry {(:name agent-def) agent-def}
                                     :llm {:base-url "http://mock.local" :model "m"}
                                     :http-client (:http-client m)}))))

(defn- until!
  "Spin until `pred` holds (2s ceiling). Only ever used to observe that an async
  turn has REACHED a state — never to assert an ordering, which §7D says is
  unobservable. The heartbeat's own timing is virtual; this is the seam between a
  virtual timer and the real thread that runs the turn it starts."
  [pred]
  (loop [i 0]
    (if (or (pred) (>= i 400))
      (boolean (pred))
      (do (ktime/sleep! 5) (recur (inc i))))))

;; ---------------------------------------------------------------------------
;; compose-soul
;; ---------------------------------------------------------------------------

(deftest bootstrap-order-is-pinned
  (testing "the seven files, in the §7E order — identity first, memory last"
    (is (= ["AGENTS.md" "SOUL.md" "IDENTITY.md" "USER.md"
            "TOOLS.md" "HEARTBEAT.md" "MEMORY.md"]
           home/bootstrap-order))))

(deftest composes-present-files-in-order
  (with-home {"MEMORY.md"   "remembered things"
              "SOUL.md"     "I am Ava"
              "HEARTBEAT.md" "check the queue"}
    (fn [dir]
      (let [{:keys [soul found]} (home/compose-soul dir)]
        (testing "only the present files, in bootstrap order — not disk order"
          (is (= ["SOUL.md" "HEARTBEAT.md" "MEMORY.md"] found)))
        (testing "each file is a `## <filename>` section"
          (is (str/starts-with? soul "## SOUL.md\n\nI am Ava"))
          (is (str/includes? soul "## HEARTBEAT.md\n\ncheck the queue"))
          (is (str/includes? soul "## MEMORY.md\n\nremembered things")))
        (testing "sections are separated by a blank line"
          (is (str/includes? soul "I am Ava\n\n## HEARTBEAT.md")))
        (testing "SOUL.md precedes MEMORY.md in the composed text"
          (is (< (str/index-of soul "## SOUL.md")
                 (str/index-of soul "## MEMORY.md"))))))))

(deftest absent-files-are-skipped-not-empty-sections
  (with-home {"SOUL.md" "just me"}
    (fn [dir]
      (let [{:keys [soul found]} (home/compose-soul dir)]
        (is (= ["SOUL.md"] found))
        (is (= "## SOUL.md\n\njust me" soul)
            "an absent file leaves NO trace — not a heading with an empty body")))))

(deftest an-empty-directory-composes-an-empty-soul
  (with-home {}
    (fn [dir]
      (let [{:keys [soul found]} (home/compose-soul dir)]
        (is (= [] found))
        (is (= "" soul) "no files ⇒ empty string, never nil")))))

(deftest bodies-are-trimmed
  (with-home {"SOUL.md" "\n\n  I am Ava  \n\n\n"}
    (fn [dir]
      (is (= "## SOUL.md\n\nI am Ava" (:soul (home/compose-soul dir)))
          "leading/trailing whitespace is trimmed so the section shape is stable"))))

(deftest the-cap-is-two-mebibytes-of-bytes
  (testing "measured in bytes, not characters (§7E)"
    (is (= 2097152 home/max-file-bytes))))

(deftest an-oversized-file-is-truncated-and-disk-is-untouched
  ;; A 2 MiB+ file, built once. The point is not the exact truncation index — the
  ;; spec allows a split multibyte character — but that the soul shrinks, carries
  ;; the notice, and the FILE does not change.
  (let [big (apply str (repeat 300000 "0123456789"))]   ; 3,000,000 bytes ASCII
    (with-home {"SOUL.md" big}
      (fn [dir]
        (let [{:keys [soul]} (home/compose-soul dir)]
          (is (str/ends-with? soul "[truncated: exceeds 2 MB bootstrap cap]")
              "the truncation is announced, never silent")
          (is (< (count soul) (count big))
              "the injected body is smaller than the file")
          (is (= (count big) (count (fs/read-file (str dir "/SOUL.md"))))
              "the file ON DISK is untouched — the cap is an injection cap"))))))

;; ---------------------------------------------------------------------------
;; the memory tool
;; ---------------------------------------------------------------------------

(deftest memory-tool-shape
  (with-home {}
    (fn [dir]
      (let [t (home/memory-tool dir)]
        (is (= "memory" (:name t)))
        (is (str/includes? (:description t) "NEXT session")
            "the description tells the MODEL that writes do not change its current context")
        (is (= ["action" "text"] (:required (:input-schema t))))))))

(deftest add-appends-and-persists
  (with-home {}
    (fn [dir]
      (let [t (home/memory-tool dir)
            r (call t {:action "add" :text "muthu prefers Clojure"})]
        (is (false? (:isError r)))
        (is (str/includes? (:output r) "MEMORY.md"))
        (is (= "- muthu prefers Clojure\n" (fs/read-file (str dir "/MEMORY.md")))
            "written to DISK as a list entry, not merely reported")
        (call t {:action "add" :text "second"})
        (is (= "- muthu prefers Clojure\n- second\n" (fs/read-file (str dir "/MEMORY.md")))
            "a second add APPENDS — it does not overwrite")))))

(deftest target-user-writes-user-md
  (with-home {}
    (fn [dir]
      (let [t (home/memory-tool dir)]
        (call t {:action "add" :target "user" :text "works in IST"})
        (is (= "- works in IST\n" (fs/read-file (str dir "/USER.md"))))
        (is (not (fs/exists? (str dir "/MEMORY.md")))
            "target=user must not also touch the agent's own notes")))))

(deftest self-is-the-default-target
  (with-home {}
    (fn [dir]
      (call (home/memory-tool dir) {:action "add" :text "x"})
      (is (fs/exists? (str dir "/MEMORY.md")))
      (is (not (fs/exists? (str dir "/USER.md")))))))

(deftest replace-swaps-an-existing-substring
  (with-home {"MEMORY.md" "- likes tea\n"}
    (fn [dir]
      (let [r (call (home/memory-tool dir) {:action "replace" :text "tea" :with "coffee"})]
        (is (false? (:isError r)))
        (is (= "- likes coffee\n" (fs/read-file (str dir "/MEMORY.md"))))))))

(deftest remove-deletes-an-existing-substring
  (with-home {"MEMORY.md" "- likes tea\n- likes coffee\n"}
    (fn [dir]
      (let [r (call (home/memory-tool dir) {:action "remove" :text "- likes tea\n"})]
        (is (false? (:isError r)))
        (is (= "- likes coffee\n" (fs/read-file (str dir "/MEMORY.md"))))))))

(deftest a-missing-substring-is-a-loud-error
  (with-home {"MEMORY.md" "- likes tea\n"}
    (fn [dir]
      (let [t (home/memory-tool dir)]
        (doseq [action ["replace" "remove"]]
          (let [r (call t {:action action :text "absent" :with "x"})]
            (is (true? (:isError r)) (str action " on a missing substring must be loud"))
            (is (str/includes? (:output r) "not found"))))
        (is (= "- likes tea\n" (fs/read-file (str dir "/MEMORY.md")))
            "a failed edit leaves the file EXACTLY as it was")))))

(deftest an-unknown-action-is-an-error
  (with-home {}
    (fn [dir]
      (let [r (call (home/memory-tool dir) {:action "delete-everything" :text "x"})]
        (is (true? (:isError r)))
        (is (str/includes? (:output r) "unknown action"))))))

(deftest the-memory-tool-does-not-mutate-the-composed-soul
  (testing "the frozen-snapshot rule: an edit loads NEXT session, not this one"
    (with-home {"MEMORY.md" "- old\n"}
      (fn [dir]
        (let [before (:soul (home/compose-soul dir))]
          (call (home/memory-tool dir) {:action "add" :text "new"})
          (is (str/includes? before "- old"))
          (is (not (str/includes? before "- new"))
              "the soul captured earlier is a SNAPSHOT — it cannot have changed")
          (is (str/includes? (:soul (home/compose-soul dir)) "- new")
              "…and the next composition does see it"))))))

(deftest heartbeat-constants-are-pinned
  (testing "the sentinel and the prompt are part of the cross-port contract"
    (is (= "HEARTBEAT_OK" home/heartbeat-ok))
    (is (str/includes? home/heartbeat-prompt "HEARTBEAT_OK")
        "the prompt must teach the model the silent-reply contract")
    (is (str/includes? home/heartbeat-prompt "Heartbeat")
        "…and carry the trigger word a HEARTBEAT.md can key off")
    (is (str/includes? home/heartbeat-prompt "HEARTBEAT.md"))))

;; ---------------------------------------------------------------------------
;; from-dir — the directory IS the agent
;; ---------------------------------------------------------------------------

(deftest from-dir-composes-the-soul-and-wires-the-memory-tool
  (with-home {"SOUL.md" "I am Ava" "HEARTBEAT.md" "check the queue"}
    (fn [dir]
      (let [a (home/from-dir dir)]
        (is (= (skill-name dir) (:name a))
            "the default name is the directory's own last segment — the dir IS the agent")
        (is (str/includes? (:does a) dir) "a routing description a delegating model can read")
        (is (= (:soul (home/compose-soul dir)) (:soul a))
            "the soul is the §7E composition, frozen at build time")
        (is (= ["memory"] (mapv :name (:tools a)))
            "the memory tool is wired over the SAME directory, and nothing else is")
        (is (= "inherit" (:model a))
            "§7E's default model is \"inherit\" — the runtime's own llm model")))))

(deftest from-dir-options-override-every-default
  (with-home {"SOUL.md" "x"}
    (fn [dir]
      (let [extra (native/native-tool {:name "extra" :description "d"
                                       :input-schema {:type "object" :properties {}}
                                       :run (fn [_a] "ok")})
            a (home/from-dir dir {:name "ava" :does "keeps the desk" :model "gpt-x"
                                  :tools [extra]})]
        (is (= "ava" (:name a)))
        (is (= "keeps the desk" (:does a)))
        (is (= "gpt-x" (:model a)))
        (is (= ["extra" "memory"] (mapv :name (:tools a)))
            "extra tools come FIRST; the memory builtin is appended")))))

(deftest memory-false-makes-a-read-only-persona
  (with-home {"SOUL.md" "x"}
    (fn [dir]
      (is (= [] (:tools (home/from-dir dir {:memory false})))
          "§7E: the memory tool is OPT-OUTABLE — a read-only persona gets no writer")
      (is (= ["memory"] (mapv :name (:tools (home/from-dir dir {:memory true}))))
          "only an explicit false omits it"))))

(deftest a-from-dir-persona-runs-and-is-a-tool
  (testing "both directions of the §7D axiom work on the def from-dir returns"
    (with-home {"SOUL.md" "I am Ava, terse."}
      (fn [dir]
        (let [{:keys [rt requests]} (persona-runtime (home/from-dir dir {:name "ava"})
                                                     {"m" [{:text "on it"} {:text "on it"}]})]
          ;; .run
          (let [r (rt/run-agent rt "ava" "status?")]
            (is (= "done" (:status r)))
            (is (= "on it" (:text r))))
          (let [sys (->> (first @requests) :messages (filter #(= "system" (:role %))) first)]
            (is (str/includes? (:content sys) "## SOUL.md")
                "the composed soul was injected as the SYSTEM prompt")
            (is (str/includes? (:content sys) "I am Ava, terse.")))
          (is (= "m" (:model (first @requests)))
              "\"inherit\" resolved to the runtime's own llm model")
          (is (some #(= "memory" (get-in % [:function :name])) (:tools (first @requests)))
              "the persona was offered its memory tool")
          ;; .asTool
          (let [t (rt/agent-tool rt "ava")
                out ((:execute t) {:prompt "again"})]
            (is (= "ava" (:name t)))
            (is (= "on it" (:output out)) "ONLY the final text crosses the tool boundary")
            (is (= "ava" (:agent (:metadata out))))))))))

;; ---------------------------------------------------------------------------
;; start-agent — the heartbeat, on the injectable clock
;; ---------------------------------------------------------------------------

(deftest a-heartbeat-beats-on-the-injected-clock-and-never-on-wall-time
  (with-home {"HEARTBEAT.md" "check the queue"}
    (fn [dir]
      (let [clock (rt/virtual-clock)
            {:keys [http-client requests]} (mock-llm {"m" [{:text "HEARTBEAT_OK"}]})
            started (home/start-agent (home/from-dir dir {:name "ava"})
                                      {:llm {:base-url "http://mock.local" :model "m"}
                                       :http-client http-client
                                       :clock clock}
                                      {:every-ms 1000})]
        (try
          (is (empty? @requests) "no beat has come due — the clock has not moved")
          (ktime/sleep! 60)
          (is (empty? @requests)
              "and real time passing does NOT produce one: every timer is on the injected clock")
          ((:advance! clock) 1000)
          (is (until! #(= 1 (count @requests))) "the first beat woke the persona")
          (is (str/includes? (->> (first @requests) :messages (filter #(= "user" (:role %))) last :content)
                             home/heartbeat-prompt)
              "…with the pinned heartbeat prompt")
          ;; The beat is virtual but the TURN it starts runs on a real thread, so
          ;; the next tick only produces a wake once this one has settled. Waiting
          ;; for `idle` is the seam between the two — advancing while the persona
          ;; is still busy is exactly the case the NEXT test covers on purpose.
          (is (until! #(= "idle" (:state (rt/inspect (:runtime started) (:handle started))))))
          ((:advance! clock) 1000)
          (is (until! #(= 2 (count @requests)))
              "the timer RESCHEDULES itself — a heartbeat beats more than once")
          (finally ((:stop started))))))))

(deftest a-heartbeat-ok-reply-is-silent-and-a-substantive-one-is-not
  (with-home {"HEARTBEAT.md" "check the queue"}
    (fn [dir]
      (let [clock (rt/virtual-clock)
            heard (atom [])
            {:keys [http-client requests]} (mock-llm {"m" [{:text "HEARTBEAT_OK"}
                                                           {:text "the queue is on fire"}
                                                           {:text "all quiet, HEARTBEAT_OK"}]})
            started (home/start-agent (home/from-dir dir {:name "ava"})
                                      {:llm {:base-url "http://mock.local" :model "m"}
                                       :http-client http-client
                                       :clock clock}
                                      {:every-ms 1000 :on-beat (fn [t] (swap! heard conj t))})]
        (try
          ((:advance! clock) 1000)
          (is (until! #(= "idle" (:state (rt/inspect (:runtime started) (:handle started))))))
          (ktime/sleep! 60)
          (is (= [] @heard) "a HEARTBEAT_OK reply surfaces NOTHING — silence is the default")
          (is (= [] @(:beats started)))
          ((:advance! clock) 1000)
          (is (until! #(= 1 (count @heard))) "a substantive reply DOES surface")
          (is (= ["the queue is on fire"] @heard))
          (is (= ["the queue is on fire"] @(:beats started)))
          (is (until! #(= "idle" (:state (rt/inspect (:runtime started) (:handle started))))))
          ((:advance! clock) 1000)
          (is (until! #(= 3 (count @requests))))
          (is (until! #(= "idle" (:state (rt/inspect (:runtime started) (:handle started))))))
          (ktime/sleep! 60)
          (is (= 1 (count @heard))
              "a reply that CONTAINS the sentinel is silent too, wherever it sits in the text")
          (finally ((:stop started))))))))

(deftest ticks-coalesce-so-a-slow-beat-cannot-pile-up
  (testing "§7E: the tick rides the UNSOLICITED rail, and the whole inbox drains as one block"
    (with-home {"HEARTBEAT.md" "work"}
      (fn [dir]
        (let [clock (rt/virtual-clock)
              gate  (promise)
              {:keys [http-client requests]} (mock-llm {"m" [{:text "HEARTBEAT_OK"}
                                                             {:text "HEARTBEAT_OK"}]}
                                                       (fn [_b n] (when (= 1 n) @gate)))
              started (home/start-agent (home/from-dir dir {:name "ava"})
                                        {:llm {:base-url "http://mock.local" :model "m"}
                                         :http-client http-client
                                         :clock clock}
                                        {:every-ms 1000})
              h (:handle started)
              runtime (:runtime started)]
          (try
            ((:advance! clock) 1000)                 ; beat 1 — wakes, then blocks
            (is (until! #(= 1 (count @requests))))
            ((:advance! clock) 1000)                 ; beat 2 — busy, tick only
            ((:advance! clock) 1000)                 ; beat 3 — busy, tick only
            ((:advance! clock) 1000)                 ; beat 4 — busy, tick only
            (is (until! #(= 3 (:inbox (rt/inspect runtime h))))
                "three ticks BUFFERED while the persona was busy; no second turn was started")
            (is (= 1 (count @requests)) "a busy persona is never woken again mid-turn")
            (deliver gate true)
            (is (until! #(= "idle" (:state (rt/inspect runtime h)))))
            ((:advance! clock) 1000)
            (is (until! #(= 2 (count @requests))))
            (let [user (->> (last @requests) :messages (filter #(= "user" (:role %))) last :content)]
              (is (str/includes? user "tick (x4 coalesced)")
                  "the buffered ticks entered as ONE counted entry — a slow beat cannot pile up")
              (is (= 1 (count (re-seq #"\[from=clock channel=timer\]" user)))
                  "…exactly one, not four"))
            (finally ((:stop started)))))))))

(deftest a-busy-persona-reports-its-turn-once-however-many-beats-landed
  (testing "the wake is guarded on IDLE — otherwise every beat that lands mid-turn
            registers another waiter and the one reply is reported once per beat"
    (with-home {"HEARTBEAT.md" "work"}
      (fn [dir]
        (let [clock (rt/virtual-clock)
              gate  (promise)
              heard (atom [])
              {:keys [http-client requests]} (mock-llm {"m" [{:text "the queue is on fire"}]}
                                                       (fn [_b n] (when (= 1 n) @gate)))
              started (home/start-agent (home/from-dir dir {:name "ava"})
                                        {:llm {:base-url "http://mock.local" :model "m"}
                                         :http-client http-client
                                         :clock clock}
                                        {:every-ms 1000 :on-beat (fn [t] (swap! heard conj t))})]
          (try
            ((:advance! clock) 1000)                  ; beat 1 — starts the slow turn
            (is (until! #(= 1 (count @requests))))
            ((:advance! clock) 1000)                  ; beats 2 and 3 land while it is busy
            ((:advance! clock) 1000)
            (deliver gate true)
            (is (until! #(seq @heard)))
            (ktime/sleep! 100)
            (is (= ["the queue is on fire"] @heard)
                "ONE report for ONE turn, however many beats were waiting on it")
            (is (= ["the queue is on fire"] @(:beats started)))
            (finally ((:stop started)))))))))

(deftest stop-cancels-the-heartbeat-and-closes-the-tree
  (with-home {"HEARTBEAT.md" "work"}
    (fn [dir]
      (let [clock (rt/virtual-clock)
            {:keys [http-client requests]} (mock-llm {"m" [{:text "HEARTBEAT_OK"}]})
            started (home/start-agent (home/from-dir dir {:name "ava"})
                                      {:llm {:base-url "http://mock.local" :model "m"}
                                       :http-client http-client
                                       :clock clock}
                                      {:every-ms 1000})]
        ((:stop started))
        (is (= "closed" (:state (rt/inspect (:runtime started) (:handle started))))
            "stop closes the tree gracefully")
        ((:advance! clock) 10000)
        (ktime/sleep! 60)
        (is (empty? @requests)
            "and the timer is CANCELLED — ten intervals later, still no beat")))))
