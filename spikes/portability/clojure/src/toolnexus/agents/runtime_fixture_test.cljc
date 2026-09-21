;; toolnexus.agents.runtime — the SHARED-FIXTURE suite. SPEC §7D / §0.
;;
;; `toolnexus.agents.runtime-test` proves the runtime against the prose of
;; SPEC §7D. This namespace proves it against the artifact §7D actually names as
;; the conformance check: the shared `examples/subagent-*/fixture.json` files,
;; which every port runs and whose PER-HANDLE TRANSITION TRACES must match.
;;
;; The distinction matters. A per-port test can be right about a behavior and
;; still disagree with five other ports about the number in it, because the port
;; wrote its own expected value. Here the registry, the budgets, the token
;; arithmetic, the child ids and the transition lists all come out of a file
;; that is shared by JS, Python, Go, Java, C#, Elixir and Clojure — so a
;; disagreement is caught rather than ratified.
;;
;; What is NOT read from the file is the mock LLM's behavior: the fixtures write
;; the scripts as prose ("text": "found:<first tool msg content>"), so every port
;; implements them natively, exactly as `js/test/agents.test.ts` does. The
;; EXPECTED VALUES are always the file's.
;;
;; The examples/ path comes from TN_EXAMPLES, like the rest of the suite.
;;
;; No java.*, no `future`, no reader conditionals.
(ns toolnexus.agents.runtime-fixture-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.time :as ktime]
            [toolnexus.agents.runtime :as rt]
            [toolnexus.client :as client]
            [toolnexus.tool :as tool]))

(defn- kstr
  "A parsed-JSON key as its original string. `koine.json` keywordizes, and a key
  like \"root/coordinator.1\" becomes a NAMESPACED keyword — `name` would silently
  return \"coordinator.1\" and every handle-id lookup would miss."
  [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn- kebab
  "`maxConcurrent` -> `:max-concurrent`. The fixtures spell budgets in the wire
  casing every port shares; this port's own options are kebab-case, and the
  conversion belongs here rather than in the runtime — a runtime that accepted
  both spellings would let the fixture and the API disagree quietly."
  [k]
  (keyword (str/lower-case (str/replace (kstr k) #"([a-z])([A-Z])" "$1-$2"))))

(defn- budget-of [m]
  (reduce (fn [acc e] (assoc acc (kebab (key e)) (val e))) {} m))

(defn- fixture
  "One shared fixture, parsed. `examples/<name>/fixture.json`."
  [nm]
  (json/read-str (fs/read-file (str (env/get-env "TN_EXAMPLES") "/" nm "/fixture.json"))))

;; ---------------------------------------------------------------------------
;; The fixtures' tools
;; ---------------------------------------------------------------------------

(def ^:private lookup-tool
  (tool/tool {:name "lookup"
              :description "look something up"
              :input-schema {:type "object" :properties {:q {:type "string"}}}
              :execute (fn [args] (tool/success (str "data(" (:q args) ")")))}))

(def ^:private check-secret-tool
  ;; "without ctx.answer → pending({kind:approval}); with ctx.answer.ok → secret-token"
  ;; Multi-arity because §1's Context is optional, and in Clojure an optional
  ;; trailing argument IS arity: the first execution lands on the 1-arg body and
  ;; the post-Answer retry on the 2-arg one.
  (tool/tool {:name "check_secret"
              :description "reads a secret, with approval"
              :input-schema {:type "object" :properties {}}
              :execute (fn
                         ([_args] (client/suspend (client/make-request
                                                   "approval" "approve secret access?"
                                                   {:id "fixture-approval-1"})))
                         ([_args ctx] (if (:ok (:answer ctx))
                                        (tool/success "secret-token")
                                        (tool/success "denied"))))}))

;; ---------------------------------------------------------------------------
;; The fixtures' mock LLM (openai style, dispatched on body.model)
;; ---------------------------------------------------------------------------

(def ^:private usage
  "`mockLLM.usagePerCall` — the same 40 tokens per call in every fixture, which
  is what makes the budget numbers below arithmetic instead of guesses."
  {:prompt_tokens 30 :completion_tokens 10 :total_tokens 40})

(defn- msg-calls [calls]
  {:choices [{:message {:role "assistant" :content nil
                        :tool_calls (vec (map-indexed
                                          (fn [i c] {:id (str "f" (inc i)) :type "function"
                                                     :function {:name (:name c)
                                                                :arguments (json/write-str (or (:args c) {}))}})
                                          calls))}}]
   :usage usage})

(defn- msg-text [t] {:choices [{:message {:role "assistant" :content t}}] :usage usage})

(defn- tool-msgs [body] (filterv #(= "tool" (:role %)) (:messages body)))
(defn- last-user [body] (or (:content (last (filterv #(= "user" (:role %)) (:messages body)))) ""))

(defn- inbox-item-count
  "`m-peer`: \"processed <count of numbered inbox items in last user msg> items\"."
  [body]
  (count (re-seq #"(?m)^\d+\. \[from=" (str (last-user body)))))

(defn- respond
  "The fixtures' `mockLLM.scripts`, written natively. `ctl` carries the two
  controls the lifecycle fixture needs: `:gate` (a promise `m-slow` blocks on)
  and `:barrier` (how many `m-explore` calls must be in flight before any of
  them is answered — the fanout fixture's `concurrentTurnsObservedAtLeast`)."
  [ctl body]
  (let [tms (tool-msgs body)]
    (case (:model body)
      "m-coordinator"
      (if (zero? (count tms))
        (msg-calls [{:name "task" :args {:agent "explore" :prompt "find A"}}
                    {:name "task" :args {:agent "explore" :prompt "find B"}}])
        (msg-text (str "synthesis: " (str/join " + " (map :content tms)))))

      "m-explore"
      (if (zero? (count tms))
        (do (when-let [n (:barrier ctl)]
              ;; Hold every explore call until `n` of them are in flight. It
              ;; asserts CONCURRENCY (which the fixture demands) without
              ;; asserting SCHEDULING (which §7D says is unobservable): the
              ;; ceiling means a serialized runtime fails rather than hangs.
              (swap! (:in-flight ctl) inc)
              (loop [i 0]
                (when (and (< @(:in-flight ctl) n) (< i 200))
                  (ktime/sleep! 5)
                  (recur (inc i)))))
            (msg-calls [{:name "lookup" :args {:q "x"}}]))
        (msg-text (str "found:" (:content (first tms)))))

      "m-loop" (msg-calls [{:name "lookup" :args {:q "again"}}])

      "m-peer" (msg-text (str "processed " (inbox-item-count body) " items"))

      "m-slow" (do (when (:started ctl) (deliver (:started ctl) true))
                   (when (:gate ctl) (deref (:gate ctl)))
                   (msg-text "slow-done"))

      "m-approver-parent"
      (if (zero? (count tms))
        (msg-calls [{:name "task" :args {:agent "asker" :prompt "get the secret"}}])
        (msg-text (str "parent-final: " (:content (last tms)))))

      "m-asker"
      (if (some #(str/includes? (str (:content %)) "secret-token") tms)
        (msg-text "asker-done: secret-token")
        (msg-calls [{:name "check_secret" :args {}}]))

      (msg-text "unscripted"))))

;; ---------------------------------------------------------------------------
;; Building a runtime from a fixture's own registry
;; ---------------------------------------------------------------------------

(def ^:private tools-by-name
  {"lookup" lookup-tool "check_secret" check-secret-tool})

(defn- registry-of
  "The fixture's `registry` block as AgentDefs. Names, `does`, models, teams and
  tool wiring all come from the file — nothing is retyped here."
  [fx & {:keys [wait-for overrides]}]
  (reduce (fn [acc e]
            (let [nm (kstr (key e)) d (val e)]
              (assoc acc nm
                     (merge (cond-> {:name nm :does (:does d) :model (:model d)}
                              (:team d)  (assoc :team (mapv str (:team d)))
                              (:tools d) (assoc :tools (mapv tools-by-name (map str (:tools d))))
                              (and wait-for (:waitFor d)) (assoc :wait-for wait-for))
                            (get overrides nm)))))
          {} (:registry fx)))

(defn- make-rt [fx & {:keys [ctl opts wait-for overrides]}]
  (rt/create-runtime
   (merge {:registry (registry-of fx :wait-for wait-for :overrides overrides)
           :llm {:base-url "http://mock.local" :model "m-default"}
           :http-client (fn [_url _headers body]
                          {:status 200 :headers {}
                           :body (json/write-str (respond (or ctl {}) (json/read-str body)))})}
          opts)))

;; ---------------------------------------------------------------------------
;; Transition extraction — the fixtures' `transitions` blocks
;; ---------------------------------------------------------------------------

(defn- transitions-of
  "Every `from→to` this handle went through, in order, from the trace. The
  fixtures list exactly these, per handle, and that list IS the §0 conformance
  artifact."
  [r id]
  (->> (rt/trace r)
       (keep (fn [line]
               (when (str/starts-with? line (str id ": "))
                 (let [rest* (subs line (count (str id ": ")))]
                   (first (re-find #"^([a-z]+→[a-z]+)" rest*))))))
       vec))

(defn- matches-transitions?
  "Compare against a fixture list. An entry written `→closed` matches any
  predecessor state, which is how the fixtures spell 'closed from wherever it
  was'."
  [actual expected]
  (and (= (count actual) (count expected))
       (every? true?
               (map (fn [a e]
                      (if (str/starts-with? e "→") (str/ends-with? a e) (= a e)))
                    actual expected))))

(defn- run-entry
  "The fixtures' `run` block: spawn the entry agent, wake it, wait. Deliberately
  NOT `run-agent` — that one-shot CLOSES the handle, and the fixtures' expected
  transition list for the entry agent has no `→closed` in it."
  [r fx-run]
  (let [h (rt/spawn r rt/root (kstr (:entry fx-run)))]
    (rt/wake r h (str (:prompt fx-run)))
    [h (rt/wait r h)]))

(defn- expect-transitions [r fx-transitions]
  (doseq [e fx-transitions]
    (let [id (kstr (key e)) expected (mapv str (val e)) actual (transitions-of r id)]
      (is (matches-transitions? actual expected)
          (str id ": expected " (pr-str expected) " got " (pr-str actual))))))

;; ===========================================================================
;; subagent-fanout — S1/S2
;; ===========================================================================

(deftest fixture-subagent-fanout
  (testing "parallel task fan-out: context isolation, usage roll-up, fused close"
    (let [fx  (fixture "subagent-fanout")
          exp (:expect fx)
          ctl {:barrier 2 :in-flight (atom 0)}
          r   (make-rt fx :ctl ctl)
          res (second (run-entry r (:run fx)))]
      (is (= (:status exp) (:status res)))
      (doseq [frag (:textContains exp)]
        (is (str/includes? (:text res) (str frag))))
      (doseq [e (:textOccurrences exp)]
        (is (= (val e) (count (re-seq (re-pattern (name (key e))) (:text res))))
            (str "expected " (val e) " occurrences of " (name (key e)))))
      (is (= (:parentTurns exp) (:turns res)))
      (is (= (mapv str (:childIds exp))
             (mapv :id (remove #(= "root/coordinator.1" (:id %)) (rt/handles r))))
          "exactly the two deterministic child ids the fixture names")
      (is (= (:parentUsageTotal exp) (:tokens (rt/inspect r "root/coordinator.1")))
          "child usage rolls UP into the parent — the roll-up is the budget ledger")
      (when (:childrenClosedAfterTask exp)
        (doseq [c (:childIds exp)]
          (is (= "closed" (:state (rt/inspect r (str c)))) "spawn→wake→wait→CLOSE is fused")))
      (is (>= (:max-observed (rt/gate-stats r)) (:concurrentTurnsObservedAtLeast exp))
          "the two task calls of one turn really did run concurrently")
      (expect-transitions r (:transitions exp)))))

;; ===========================================================================
;; subagent-budgets — S7
;; ===========================================================================

(deftest fixture-subagent-budgets
  (testing "carve, LIVE ancestor-chain enforcement, maxChildren, and loud limit stops"
    (let [fx    (fixture "subagent-budgets")
          steps (:steps fx)
          r     (make-rt fx)
          coord (rt/spawn r rt/root "coordinator"
                          (budget-of (get-in (nth steps 0) [:spawn :budget])))]
      ;; step 2 — carve: min(own 500, parent remaining 100)
      (let [e1 (rt/spawn r coord "explore" (budget-of (get-in (nth steps 1) [:spawn :budget])))]
        (is (= (get-in (nth steps 1) [:expect :effectiveTokens])
               (:pool-tokens (rt/inspect r e1)))
            "carve = min(own, parent remaining)"))
      (let [_e2 (rt/spawn r coord "explore")
            e3  (rt/spawn r coord "explore")]
        (is (rt/verb-error? e3))
        (is (str/includes? (:error e3) (str (get-in (nth steps 3) [:expect :isError])))
            "maxChildren is checked at spawn"))
      ;; step 5 — explore.1 runs 2 turns x 40, draining the shared pool to 20
      (rt/wake r "root/coordinator.1/explore.1" "go")
      (is (= "done" (:status (rt/wait r "root/coordinator.1/explore.1"))))
      (is (= (get-in (nth steps 4) [:expect :parentPoolAfter])
             (:pool-tokens (rt/inspect r "root/coordinator.1")))
          "the roll-up drained the ancestor's pool live")
      ;; step 6 — explore.2 is admitted: pool 20 > 0 at admission
      (rt/wake r "root/coordinator.1/explore.2" "go")
      (is (= "done" (:status (rt/wait r "root/coordinator.1/explore.2"))))
      ;; step 7 — now the ancestor pool is negative; the LIVE walk refuses
      (let [w (rt/wake r "root/coordinator.1/explore.2" "again")]
        (is (= false (:ok w)))
        (is (str/includes? (:error w) (str (get-in (nth steps 6) [:expect :errorContains])))))
      (let [res (rt/wait r "root/coordinator.1/explore.2")]
        (is (= (get-in (nth steps 6) [:expect :status]) (:status res))
            "a limit stop is LOUD — incomplete, never a silent done and never a crash"))
      ;; steps 8/9 — the maxTurns cap on a model that never finishes
      (let [loop-h (rt/spawn r rt/root "looper" (budget-of (get-in (nth steps 7) [:spawn :budget])))]
        (rt/wake r loop-h "loop forever")
        (let [res (rt/wait r loop-h)]
          (is (= (get-in (nth steps 8) [:expect :status]) (:status res)))
          (is (= (get-in (nth steps 7) [:spawn :budget :maxTurns]) (:turns res))
              "it ran its whole allowance before stopping"))))))

;; ===========================================================================
;; subagent-lifecycle — S5/S6/S8/S9
;; ===========================================================================

(deftest fixture-lifecycle-coalesced-drain
  (let [fx  (fixture "subagent-lifecycle")
        sc  (get-in fx [:scenarios :coalescedDrain])
        exp (:expect sc)
        r   (make-rt fx)
        peer (rt/spawn r rt/root "peer")]
    (doseq [step (:steps sc)]
      (when-let [p (:post step)]
        (rt/post r peer {:from (or (:from p) "external")
                         :channel (or (:channel p) "external")
                         :text (:text p)})))
    (rt/wake r peer)
    (let [res (rt/wait r peer)]
      (is (= (:turns exp) (:turns res)))
      (is (= (:text exp) (:text res))
          "2 messages + 3 ticks coalesced into 3 items in ONE turn"))))

(deftest fixture-lifecycle-inbox-gate
  (let [fx  (fixture "subagent-lifecycle")
        sc  (get-in fx [:scenarios :inboxGate])
        r   (make-rt fx :opts {:inbox-cap (get-in sc [:runtime :inboxCap])})
        peer (rt/spawn r rt/root "peer")]
    (doseq [step (:steps sc)]
      (let [p   (:post step)
            got (rt/post r peer {:from "external" :channel "external" :text (:text p)})
            exp (:expect step)]
        (if (:rejected exp)
          (do (is (= false (:ok got)) "over cap the post is REJECTED, synchronously")
              (is (str/includes? (:error got) (str (:errorContains exp)))))
          (is (= {:ok true} got)))))))

(deftest fixture-lifecycle-concurrency-gate
  (let [fx  (fixture "subagent-lifecycle")
        sc  (get-in fx [:scenarios :concurrencyGate])
        exp (:expect sc)
        r   (make-rt fx :overrides {"coordinator" {:budget (budget-of (get-in sc [:registryOverride :coordinator :budget]))}})
        res (second (run-entry r (:run sc)))]
    (is (= (:status exp) (:status res))
        "a full concurrency gate DEFERS work; it never loses it")
    (doseq [frag (:traceContains exp)]
      (is (some #(str/includes? % (str frag)) (rt/trace r)) (str "trace must contain " frag)))))

(deftest fixture-lifecycle-turn-gate
  (let [fx  (fixture "subagent-lifecycle")
        sc  (get-in fx [:scenarios :turnGate])
        exp (:expect sc)
        r   (make-rt fx :opts {:max-concurrent-turns (get-in sc [:runtime :maxConcurrentTurns])})
        res (second (run-entry r (:run sc)))]
    (is (= (:status exp) (:status res))
        "the gate wraps only the LLM call — a parent delegating to children cannot deadlock on it")
    (is (= (:maxObservedConcurrentTurns exp) (:max-observed (rt/gate-stats r))))))

(deftest fixture-lifecycle-interrupt
  (let [fx  (fixture "subagent-lifecycle")
        sc  (get-in fx [:scenarios :interrupt])
        exp (:expect sc)
        ctl {:gate (promise) :started (promise)}
        r   (make-rt fx :ctl ctl)
        slow (rt/spawn r rt/root "slow")]
    (doseq [step (:steps sc)]
      (cond
        (:post step)   (rt/post r slow {:from "external" :channel "external" :text (:text (:post step))})
        (:wake step)   (rt/wake r slow (:prompt (:wake step)))
        (:awaitState step) (is (loop [i 0]
                                 (cond (= (str (:state (:awaitState step))) (:state (rt/inspect r slow))) true
                                       (>= i 400) false
                                       :else (do (ktime/sleep! 5) (recur (inc i)))))
                               "the handle reached the awaited state")
        (:interrupt step) (do (rt/interrupt r slow) (deliver (:gate ctl) true))))
    (let [res (rt/wait r slow)]
      (is (= (:waiterStatus exp) (:status res)) "a uniform result, never an exception"))
    (is (loop [i 0]
          (cond (= (str (:finalState exp)) (:state (rt/inspect r slow))) true
                (>= i 400) false
                :else (do (ktime/sleep! 5) (recur (inc i)))))
        "turn-abort, NOT kill")
    (is (= (:inboxLen exp) (:inbox (rt/inspect r slow)))
        "the transactional drain restored the item the aborted turn had consumed")))

(deftest fixture-lifecycle-close-cascade
  (let [fx  (fixture "subagent-lifecycle")
        sc  (get-in fx [:scenarios :closeCascade])
        order (atom [])
        note  (fn [_r id _reason] (swap! order conj id))
        r   (rt/create-runtime
             {:registry (-> (registry-of fx)
                            (update "coordinator" assoc :on-close note)
                            (update "peer" assoc :on-close note))
              :llm {:base-url "http://mock.local" :model "m-default"}
              :http-client (fn [_u _h b] {:status 200 :headers {}
                                          :body (json/write-str (respond {} (json/read-str b)))})})
        c  (rt/spawn r rt/root "coordinator")
        p1 (rt/spawn r c "peer")
        p2 (rt/spawn r p1 "peer")]
    (rt/close r rt/root)
    (is (= [p2 p1 c] @order) "onClose runs leaf-first: grandchild, child, parent")
    (doseq [h [c p1 p2]] (is (= "closed" (:state (rt/inspect r h)))))
    (let [got (rt/post r p1 {:from "external" :channel "external" :text "late"})]
      (is (= false (:ok got)))
      (is (str/includes? (:error got)
                         (str (get-in (nth (:steps sc) 1) [:expect :errorContains])))
          "a closed inbox rejects, loudly"))))

;; ===========================================================================
;; subagent-escalation — S3 (nearest interpreter wins)
;; ===========================================================================

(deftest fixture-subagent-escalation
  (testing "the child's suspension is answered INLINE by the parent's waitFor authority"
    (let [fx   (fixture "subagent-escalation")
          exp  (:expect fx)
          seen (atom [])
          r    (make-rt fx :wait-for (fn [req]
                                       (swap! seen conj (:kind req))
                                       (client/make-answer (:id req) true)))
          res  (second (run-entry r (:run fx)))]
      (is (= (:status exp) (:status res)) "nothing escalated past the parent to the host")
      (doseq [frag (:textContains exp)]
        (is (str/includes? (:text res) (str frag))))
      (is (= ["approval"] @seen) "the nearest interpreter answered, exactly once")
      (is (some #(str/includes? % (str "escalate → " (get-in exp [:escalation :answeredBy])))
                (rt/trace r))
          "the trace names WHO answered")
      (let [child-trace (transitions-of r "root/approverParent.1/asker.1")]
        (doseq [t (get-in exp [:escalation :childTrace])]
          (is (some #(= (str t) %) child-trace) (str "child trace must contain " t))))
      (expect-transitions r (:transitions exp)))))

;; ===========================================================================
;; subagent-durable-resume — S4 (no interpreter anywhere)
;; ===========================================================================

(deftest fixture-subagent-durable-resume
  (testing "durable pending at the root, then resume routes to the deepest handle and the parent REATTACHES"
    (let [fx  (fixture "subagent-durable-resume")
          p1  (:phase1_expect fx)
          p2  (:phase2_expect fx)
          r   (make-rt fx)
          res (second (run-entry r (:run fx)))]
      (do
        (is (= (:status p1) (:status res)) "no interpreter anywhere ⇒ the root returns pending")
        (is (= (:pendingKind p1) (:kind (:pending res))))
        (is (= (str (:pendingDataPathEndsWithin p1))
               (str/join "/" (get-in res [:pending :data :path])))
            "the Request carries data.path — §10's shape is closed, nothing is grafted on"))
      (doseq [v (rt/handles r)]
        (is (= (str (:allHandles p1)) (:state v)) "every level is parked, burning zero tokens"))

      (let [before (:tokens (rt/inspect r "root/approverParent.1/asker.1"))]
        (rt/resume r (client/make-answer "fixture-approval-1"
                                         (boolean (get-in fx [:resume :answer :ok]))))
        (when (:leafUsageGrewNotReset p2)
          (is (> (:tokens (rt/inspect r "root/approverParent.1/asker.1")) before)
              "usage GROWS across a resume; it never resets")))
      (when (:noDuplicateChildIds p2)
        (is (= ["root/approverParent.1" "root/approverParent.1/asker.1"]
               (mapv :id (rt/handles r)))
            "the replayed task reattached by task key — no duplicate child"))
      (is (= (str (:finalParentState p2)) (:state (rt/inspect r "root/approverParent.1"))))
      (doseq [frag (:traceContains p2)]
        (is (some #(str/includes? % (str frag)) (rt/trace r)) (str "trace must contain " frag)))
      (expect-transitions r (:transitions p2)))))
