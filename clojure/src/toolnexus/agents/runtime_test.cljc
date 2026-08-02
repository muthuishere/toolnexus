;; toolnexus.agents.runtime — the suite. SPEC §7D.
;;
;; Every expected value comes from SPEC.md §7D and `js/src/agents/runtime.ts`,
;; never from this port's own output. §7D says conformance is "identical
;; per-handle transition TRACES on a virtual clock", so most of what is asserted
;; here is the trace, not the return value: a runtime can return the right answer
;; through the wrong transitions, and that is exactly the drift six ports exist
;; to prevent.
;;
;; Two rules govern how the assertions are chosen:
;;
;;   * THE LOUD PATHS GET TESTED FIRST. A bounded inbox that never fills, a
;;     budget that is never exceeded and a `maxConcurrent` of 1 all pass against
;;     a runtime with no gates at all. Every gate test below therefore EXCEEDS
;;     its limit, and every concurrency test uses a cap greater than 1.
;;   * SCHEDULING IS UNOBSERVABLE. Nothing here asserts thread placement, wall
;;     time or the order two independent handles happen to finish in.
;;
;; The "LLM" is an injected `:http-client` replaying a per-model script. No
;; network, no key, no server — a turn is a pure function of the script.
;;
;; No java.*, no `future`, no reader conditionals.
(ns toolnexus.agents.runtime-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.agents.runtime :as rt]
            [toolnexus.client :as client]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; the scripted LLM
;; ---------------------------------------------------------------------------
;;
;; A script is {model -> [turn-spec ...]}, a turn spec being
;;   {:calls [{:id :name :args}]}  a tool-calling turn
;;   {:text "…"}                   a terminal turn
;; Anything past the end of a model's script answers with text, ending the loop.
;; Every OpenAI-shaped turn reports 15 total tokens, so a budget assertion below
;; is arithmetic rather than a guess.

(defn- calls-response [calls]
  {:choices [{:message {:role "assistant" :content nil
                        :tool_calls (mapv (fn [c] {:id (:id c) :type "function"
                                                   :function {:name (:name c)
                                                              :arguments (json/write-str (or (:args c) {}))}})
                                          calls)}}]
   :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})

(defn- text-response [t]
  {:choices [{:message {:role "assistant" :content t}}]
   :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}})

(defn- spec->response [spec]
  (if (seq (:calls spec)) (calls-response (:calls spec)) (text-response (or (:text spec) "done"))))

(defn- mock-llm
  "An `:http-client` replaying `script` per MODEL, plus the request log.

  Returns `{:http-client f :requests atom :counts atom}`. `on-call` is an
  optional (fn [body n]) hook run BEFORE the response is produced — the seam the
  blocking tests use to hold a turn open at a known point."
  ([script] (mock-llm script nil))
  ([script on-call]
   (let [requests (atom [])
         counts   (atom {})]
     {:requests requests
      :counts   counts
      :http-client
      (fn [_url _headers body]
        (let [b     (json/read-str body)
              model (:model b)
              n     (get (swap! counts update model (fnil inc 0)) model)]
          (swap! requests conj b)
          (when on-call (on-call b n))
          {:status 200 :headers {"content-type" "application/json"}
           :body (json/write-str (spec->response (get (get script model) (dec n))))}))})))

(defn- runtime-with
  "A runtime over `registry` and `script`, plus any extra runtime options."
  ([registry script] (runtime-with registry script {}))
  ([registry script opts]
   (let [m (mock-llm script (:on-call opts))]
     (assoc m :rt (rt/create-runtime
                   (merge {:registry registry
                           :llm {:base-url "http://mock.local" :model "default"}
                           :http-client (:http-client m)}
                          (dissoc opts :on-call)))))))

(defn- until!
  "Spin until `pred` holds (2s ceiling). Used ONLY to observe that an async turn
  has reached a known state — never to assert an ordering, which §7D says is
  unobservable."
  [pred]
  (loop [i 0]
    (if (or (pred) (>= i 400))
      (boolean (pred))
      (do (ktime/sleep! 5) (recur (inc i))))))

(defn- traced?
  "Is there a trace line containing `frag`?"
  [r frag]
  (boolean (some #(str/includes? % frag) (rt/trace r))))

(defn- trace-index [r frag]
  (first (keep-indexed (fn [i l] (when (str/includes? l frag) i)) (rt/trace r))))

(defn- state-of [r id] (:state (rt/inspect r id)))

;; A registry entry that just answers.
(defn- adef [nm model & {:as extra}]
  (merge {:name nm :does (str nm " does things") :model model} extra))

;; ===========================================================================
;; Clock (§7D: every timer, timeout and deadline goes through this seam)
;; ===========================================================================

(deftest virtual-clock-fires-timers-in-deadline-order
  (testing "advance! fires due timers oldest-deadline-first and moves `now`"
    (let [c    (rt/virtual-clock 1000)
          fired (atom [])]
      ((:set-timeout c) #(swap! fired conj :late) 300)
      ((:set-timeout c) #(swap! fired conj :early) 100)
      (is (= 1000 ((:now c))) "time does not move on its own")
      ((:advance! c) 100)
      (is (= [:early] @fired) "only the due timer fired")
      (is (= 1100 ((:now c))))
      ((:advance! c) 500)
      (is (= [:early :late] @fired) "deadline order, not registration order")
      (is (= 1600 ((:now c)))))))

(deftest virtual-clock-cancel-is-honoured
  (testing "the cancel fn returned by set-timeout un-schedules the callback"
    (let [c (rt/virtual-clock)
          fired (atom 0)
          cancel ((:set-timeout c) #(swap! fired inc) 50)]
      (cancel)
      ((:advance! c) 1000)
      (is (zero? @fired) "a cancelled timer must never fire"))))

;; ===========================================================================
;; Deterministic, parent-scoped ids
;; ===========================================================================

(deftest ids-are-deterministic-and-parent-scoped
  (testing "`root/coordinator.1/explore.2` — never random, and the sequence is per PARENT"
    (let [{:keys [rt]} (runtime-with {"coordinator" (adef "coordinator" "m" :budget {:max-depth 5})
                                      "explore"     (adef "explore" "m")}
                                     {})
          c  (rt/spawn rt rt/root "coordinator")
          e1 (rt/spawn rt c "explore")
          e2 (rt/spawn rt c "explore")
          c2 (rt/spawn rt rt/root "coordinator")]
      (is (= "root/coordinator.1" c))
      (is (= "root/coordinator.1/explore.1" e1))
      (is (= "root/coordinator.1/explore.2" e2)
          "the counter is scoped to the parent and increments per spawn")
      (is (= "root/coordinator.2" c2)
          "root's own counter is independent of its children's"))))

(deftest ids-repeat-exactly-across-two-identical-runs
  (testing "determinism is the point: two runtimes, same calls, same ids AND same trace"
    (let [build (fn []
                  (let [{:keys [rt]} (runtime-with {"a" (adef "a" "m")} {})]
                    [(rt/spawn rt rt/root "a") (rt/spawn rt rt/root "a") (rt/trace rt)]))]
      (is (= (build) (build))))))

(deftest unknown-agent-is-a-verb-error-listing-the-registry
  (let [{:keys [rt]} (runtime-with {"beta" (adef "beta" "m") "alpha" (adef "alpha" "m")} {})
        e (rt/spawn rt rt/root "gamma")]
    (is (rt/verb-error? e))
    (is (= "unknown agent \"gamma\" (known: alpha, beta)" (:error e))
        "the registry is listed SORTED — prose composed from the registry is name-sorted everywhere")))

;; ===========================================================================
;; The handle state machine, as a trace
;; ===========================================================================

(deftest a-plain-run-traces-spawn-then-idle-running-idle
  (testing "idle → running → idle, with the turn and token counts in the trace"
    (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {"wm" [{:text "the answer"}]})
          h (rt/spawn rt rt/root "w")]
      (is (= "idle" (state-of rt h)) "a fresh handle is idle, not running")
      (is (= {:ok true} (rt/wake rt h "go")))
      (let [r (rt/wait rt h)]
        (is (= "done" (:status r)))
        (is (= "the answer" (:text r)))
        (is (= 1 (:turns r)))
        (is (= 15 (:total-tokens r)))
        (is (= "idle" (state-of rt h)) "a settled handle returns to idle, never `closed`"))
      (is (= ["root/w.1: spawned (depth 1, tokens Infinity)"
              "root/w.1: idle→running (wake)"
              "root/w.1: running→idle (done, turns=1, tokens=15)"]
             (rt/trace rt))
          "the WHOLE trace — an extra transition is as wrong as a missing one"))))

(deftest wait-on-a-settled-handle-answers-immediately-with-the-last-result
  (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {"wm" [{:text "once"}]})
        h (rt/spawn rt rt/root "w")]
    (rt/wake rt h "go")
    (let [a (rt/wait rt h)
          b (rt/wait rt h)]
      (is (= a b) "a settled handle answers with its LAST result, and does not re-run")
      (is (= 3 (count (rt/trace rt)))
          "no second turn was traced"))))

(deftest wait-refuses-a-non-spawner
  (testing "handles are capabilities — wait only on what you spawned"
    (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm") "p" (adef "p" "pm")} {"wm" [{:text "x"}]})
          p (rt/spawn rt rt/root "p")
          h (rt/spawn rt rt/root "w")
          r (rt/wait rt h {:by p})]
      (is (= "error" (:status r)))
      (is (str/includes? (:text r) "only the spawner may wait on root/w.2")))))

;; ===========================================================================
;; Gate 1 — the bounded inbox, LOUD
;; ===========================================================================

(deftest inbox-full-rejects-synchronously-to-the-sender
  (testing "over cap the post is REJECTED, in the caller's own call, and traced"
    (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {} {:inbox-cap 2})
          h (rt/spawn rt rt/root "w")
          item (fn [t] {:from "external" :channel "external" :text t})]
      (is (= {:ok true} (rt/post rt h (item "one"))))
      (is (= {:ok true} (rt/post rt h (item "two"))))
      (let [r (rt/post rt h (item "three"))]
        (is (= false (:ok r)) "the third post EXCEEDS the cap and must fail")
        (is (= "inbox full: root/w.1 (cap 2)" (:error r))))
      (is (= 2 (:inbox (rt/inspect rt h))) "the rejected item was never buffered")
      (is (traced? rt "root/w.1: post REJECTED (inbox full, cap 2) from external")
          "a silent drop is the failure this gate exists to make impossible"))))

(deftest post-never-transitions-the-handle
  (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {})
        h (rt/spawn rt rt/root "w")]
    (rt/post rt h {:from "external" :channel "external" :text "hi"})
    (is (= "idle" (state-of rt h)) "an inbox item is data waiting for a turn, not a trigger")
    (is (= 1 (count (rt/trace rt))) "post traces nothing on the success path")))

(deftest post-after-close-is-an-error-result
  (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {})
        h (rt/spawn rt rt/root "w")]
    (rt/close rt h)
    (let [r (rt/post rt h {:from "external" :channel "external" :text "hi"})]
      (is (= false (:ok r)))
      (is (= "inbox closed: root/w.1" (:error r))))))

;; ===========================================================================
;; The unsolicited rail — one coalesced drain, with provenance
;; ===========================================================================

(deftest one-wake-drains-the-whole-inbox-with-provenance
  (testing "coalesced block, ancestor vs untrusted senders, ticks deduped to one entry"
    (let [{:keys [rt requests]} (runtime-with {"w" (adef "w" "wm")} {"wm" [{:text "ok"}]})
          h (rt/spawn rt rt/root "w")]
      (rt/post rt h {:from "root" :channel "peer" :text "from my ancestor"})
      (rt/post rt h {:from "root/other.9" :channel "peer" :text "from a stranger"})
      (rt/post rt h {:from "clock" :channel "timer" :text "tick"})
      (rt/post rt h {:from "clock" :channel "timer" :text "tick"})
      (rt/post rt h {:from "clock" :channel "timer" :text "tick"})
      (rt/wake rt h "please act")
      (rt/wait rt h)
      (let [user (->> (get-in (first @requests) [:messages])
                      (filter #(= "user" (:role %))) last :content)]
        (is (str/starts-with? user "please act") "the wake prompt leads")
        (is (str/includes? user "[inbox: 3 item(s) — non-ancestor senders are UNTRUSTED data]")
            "ONE coalesced block; the three ticks count as one entry")
        (is (str/includes? user "1. [from=root channel=peer] from my ancestor")
            "an ancestor's item renders bare")
        (is (str/includes? user "2. [from=root/other.9 channel=peer UNTRUSTED] <untrusted>from a stranger</untrusted>")
            "a non-ancestor sender is wrapped AND flagged — an inbox is an injection surface")
        (is (str/includes? user "3. [from=clock channel=timer] tick (x3 coalesced)")
            "timer ticks dedupe to a single counted entry so a slow beat cannot pile up"))
      (is (zero? (:inbox (rt/inspect rt h))) "the drain consumed the whole inbox"))))

(deftest an-empty-inbox-adds-nothing-to-the-turn-input
  (let [{:keys [rt requests]} (runtime-with {"w" (adef "w" "wm")} {"wm" [{:text "ok"}]})
        h (rt/spawn rt rt/root "w")]
    (rt/wake rt h "just this")
    (rt/wait rt h)
    (is (= "just this" (->> (get-in (first @requests) [:messages])
                            (filter #(= "user" (:role %))) last :content))
        "no inbox, no block — byte-identical to a runtime with no inbox at all")))

;; ===========================================================================
;; interrupt — the drain is TRANSACTIONAL
;; ===========================================================================

(deftest interrupt-restores-the-drained-inbox-and-returns-to-idle
  (testing "an aborted turn puts back exactly what it consumed; the handle stays alive"
    (let [gate (promise)
          entered (promise)
          {:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {"wm" [{:text "never seen"}]}
                                     {:on-call (fn [_b _n] (deliver entered true) @gate)})
          h (rt/spawn rt rt/root "w")]
      (rt/post rt h {:from "root" :channel "peer" :text "a"})
      (rt/post rt h {:from "root" :channel "peer" :text "b"})
      (rt/wake rt h "go")
      (is (until! #(realized? entered)) "the turn reached the LLM call")
      (is (zero? (:inbox (rt/inspect rt h))) "mid-turn the items are drained, not in the inbox")
      (rt/interrupt rt h)
      (deliver gate true)
      (let [r (rt/wait rt h)]
        (is (= "interrupted" (:status r)) "waiters get a uniform result, NEVER an exception")
        (is (true? (:isError r))))
      (is (until! #(= "idle" (state-of rt h))) "interrupt is never a kill — the handle is idle and alive")
      (is (= 2 (:inbox (rt/inspect rt h)))
          "the drain is TRANSACTIONAL: items are consumed only by a COMPLETED turn")
      (is (traced? rt "root/w.1: running→idle (interrupted; inbox intact)")))))

(deftest interrupting-a-suspended-handle-cancels-its-pending-request
  (testing "the operator escape hatch: suspended → idle, pending discarded"
    (let [approve (tool/tool {:name "approve" :description "asks"
                              :execute (fn ([_a] (client/suspend (client/make-request "approval" "ok?" {:id "r1"})))
                                         ([_a _c] (tool/success "yes")))})
          {:keys [rt]} (runtime-with {"w" (adef "w" "wm" :tools [approve])}
                                     {"wm" [{:calls [{:id "c1" :name "approve" :args {}}]}]})
          h (rt/spawn rt rt/root "w")]
      (rt/wake rt h "go")
      (is (= "pending" (:status (rt/wait rt h))))
      (is (= "suspended" (state-of rt h)))
      (rt/interrupt rt h)
      (is (= "idle" (state-of rt h)))
      (is (nil? (:pending (rt/inspect rt h))) "the pending Request is gone")
      (is (traced? rt "root/w.1: suspended→idle (interrupt cancelled pending \"approval\")")))))

;; ===========================================================================
;; Budgets — carve at spawn AND a live ancestor-chain walk
;; ===========================================================================

(deftest a-turn-limit-stop-is-loud-and-names-the-limit
  (testing "over maxTurns: status `incomplete`, the limit NAMED, never a silent done"
    (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm" :budget {:max-turns 1})}
                                     {"wm" [{:text "one"} {:text "two"}]})
          h (rt/spawn rt rt/root "w")]
      (rt/wake rt h "first")
      (is (= "done" (:status (rt/wait rt h))))
      (let [r2 (rt/wake rt h "second")]
        (is (= false (:ok r2)) "the SECOND wake exceeds the lifetime turn cap")
        (is (= "budget exhausted (maxTurns); partial work preserved" (:error r2))))
      (let [r (rt/wait rt h)]
        (is (= "incomplete" (:status r)) "not `done`, not `error` — the §7D limit status")
        (is (str/includes? (:text r) "maxTurns") "the limit is NAMED in the text")
        (is (= 1 (:turns r)) "partial work is preserved: the first turn still counts"))
      (is (= "idle" (state-of rt h)) "a limit stop never closes or crashes the handle"))))

(deftest the-live-ancestor-walk-catches-sibling-spend-the-carve-missed
  (testing "carve alone is not enough — a sibling that drains the shared pool must stop the other"
    (let [{:keys [rt]} (runtime-with
                        {"boss" (adef "boss" "bm" :budget {:max-tokens 20 :max-depth 5})
                         "w"    (adef "w" "wm")}
                        ;; two round trips = 30 tokens, which overruns the 20 the
                        ;; whole subtree shares
                        {"wm" [{:calls [{:id "c1" :name "nope" :args {}}]} {:text "done"}]})
          boss (rt/spawn rt rt/root "boss")
          a    (rt/spawn rt boss "w")
          b    (rt/spawn rt boss "w")]
      ;; b was carved BEFORE a spent anything: its own pool still says 20.
      (is (= 20 (:pool-tokens (rt/inspect rt b))) "the carve gave b the full shared pool")
      (rt/wake rt a "burn it")
      (rt/wait rt a)
      (is (= 30 (:tokens (rt/inspect rt a))) "a spent 30 of the subtree's 20")
      (is (= 20 (:pool-tokens (rt/inspect rt b)))
          "b's OWN carved pool is untouched — which is exactly why the carve is not enough")
      (let [r (rt/wake rt b "my turn")]
        (is (= false (:ok r)) "the live walk sees the ancestor's drained pool")
        (is (= "budget exhausted (maxTokens); partial work preserved" (:error r))))
      (is (= "incomplete" (:status (rt/wait rt b))))
      ;; and a spawn is refused on the same walk
      (let [e (rt/spawn rt boss "w")]
        (is (rt/verb-error? e))
        (is (= "budget exhausted (maxTokens); spawn refused" (:error e)))))))

(deftest max-children-and-max-depth-are-checked-at-spawn
  (let [{:keys [rt]} (runtime-with {"boss" (adef "boss" "bm" :budget {:max-children 2 :max-depth 2})
                                    "w"    (adef "w" "wm")}
                                   {})
        boss (rt/spawn rt rt/root "boss")]
    (is (string? (rt/spawn rt boss "w")))
    (let [c2 (rt/spawn rt boss "w")]
      (is (string? c2))
      (let [e (rt/spawn rt boss "w")]
        (is (= "maxChildren 2 exceeded" (:error e)) "the THIRD child exceeds the cap"))
      ;; Depth is checked against the SPAWNING parent's own cap, not against an
      ;; ancestor's: c2's def carries no budget, so its default maxDepth 3 governs
      ;; its children, and a depth-3 grandchild is allowed.
      (is (string? (rt/spawn rt c2 "w"))))))

(deftest max-depth-refuses-the-first-spawn-past-the-cap
  (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {})
        a (rt/spawn rt rt/root "w")            ; depth 1
        b (rt/spawn rt a "w")                  ; depth 2
        c (rt/spawn rt b "w")]                 ; depth 3 — at the default cap
    (is (= "root/w.1/w.1/w.1" c))
    (let [e (rt/spawn rt c "w")]               ; depth 4 — over it
      (is (rt/verb-error? e))
      (is (= "maxDepth 3 exceeded" (:error e))))))

(deftest budget-carve-is-min-of-own-and-parent-remaining
  (let [{:keys [rt]} (runtime-with {"boss" (adef "boss" "bm" :budget {:max-tokens 100 :max-depth 5})
                                    "w"    (adef "w" "wm" :budget {:max-tokens 1000})}
                                   {})
        boss (rt/spawn rt rt/root "boss")
        w    (rt/spawn rt boss "w")]
    (is (= 100 (:pool-tokens (rt/inspect rt w)))
        "a child may ASK for more than its parent has and still be carved down to it")
    (is (traced? rt "root/boss.1/w.1: spawned (depth 2, tokens 100)"))
    (is (traced? rt "root/boss.1: spawned (depth 1, tokens 100)"))))

(deftest usage-rolls-up-the-whole-ancestor-chain
  (let [{:keys [rt]} (runtime-with {"boss" (adef "boss" "bm" :budget {:max-depth 5})
                                    "w"    (adef "w" "wm")}
                                   {"wm" [{:text "hi"}]})
        boss (rt/spawn rt rt/root "boss")
        w    (rt/spawn rt boss "w")]
    (rt/wake rt w "go")
    (rt/wait rt w)
    (is (= 15 (:tokens (rt/inspect rt w))))
    (is (= 15 (:tokens (rt/inspect rt boss)))
        "the roll-up IS the ledger — a child's spend is visible on every ancestor")))

;; ===========================================================================
;; Gate 2 — maxConcurrent per parent, FIFO queue, slot transfer
;; ===========================================================================

(deftest over-max-concurrent-a-wake-queues-fifo-and-a-freed-slot-transfers
  (testing "cap 2 with 3 children — the third QUEUES and is DEQUEUED, never dropped"
    (let [gate (promise)
          seen (atom 0)
          {:keys [rt]} (runtime-with
                        {"boss" (adef "boss" "bm" :budget {:max-concurrent 2 :max-depth 5})
                         "w"    (adef "w" "wm")}
                        {"wm" [{:text "ok"}]}
                        {:on-call (fn [_b _n] (swap! seen inc) @gate)})
          boss (rt/spawn rt rt/root "boss")
          a (rt/spawn rt boss "w") b (rt/spawn rt boss "w") c (rt/spawn rt boss "w")]
      (is (= {:ok true} (rt/wake rt a "1")))
      (is (= {:ok true} (rt/wake rt b "2")))
      (is (= {:ok true} (rt/wake rt c "3")) "a queued wake still reports ok — deferred, not refused")
      (is (until! #(= 2 @seen)) "exactly two children were admitted")
      (is (traced? rt "root/boss.1/w.3: wake QUEUED (parent concurrency 2)"))
      (is (= "idle" (state-of rt c)) "a queued handle has NOT transitioned")
      (deliver gate true)
      (is (= "done" (:status (rt/wait rt c))) "the queued wake eventually ran")
      (is (traced? rt "root/boss.1/w.3: DEQUEUED wake (slot transferred)"))
      (is (< (trace-index rt "wake QUEUED") (trace-index rt "DEQUEUED wake"))
          "queued before dequeued — the one ordering that IS observable")
      (is (= 3 @seen) "all three turns ran; a full gate defers work, it does not lose it"))))

(deftest a-queued-wake-is-removed-when-the-handle-is-closed
  (let [gate (promise)
        {:keys [rt]} (runtime-with
                      {"boss" (adef "boss" "bm" :budget {:max-concurrent 1 :max-depth 5})
                       "w"    (adef "w" "wm")}
                      {"wm" [{:text "ok"}]}
                      {:on-call (fn [_b _n] @gate) :shutdown-ms 10})
        boss (rt/spawn rt rt/root "boss")
        a (rt/spawn rt boss "w") b (rt/spawn rt boss "w")]
    (rt/wake rt a "1")
    (rt/wake rt b "2")
    (is (until! #(traced? rt "wake QUEUED")))
    (rt/close rt b)
    (deliver gate true)
    (is (= "closed" (state-of rt b)))
    (is (not (traced? rt "root/boss.1/w.2: DEQUEUED wake"))
        "a closed handle must not be resurrected by a slot transfer")))

;; ===========================================================================
;; Gate 3 — the global turn gate wraps ONLY the LLM call
;; ===========================================================================

(deftest the-turn-gate-bounds-concurrent-llm-calls
  (testing "with the gate at 1 nothing overlaps; with it at 8 the same work does"
    (let [measure
          (fn [gate-size]
            (let [live (atom 0) peak (atom 0)
                  {:keys [rt]} (runtime-with
                                {"w" (adef "w" "wm")}
                                {"wm" [{:text "ok"}]}
                                {:max-concurrent-turns gate-size
                                 :on-call (fn [_b _n]
                                            (let [n (swap! live inc)]
                                              (swap! peak max n)
                                              (ktime/sleep! 60)
                                              (swap! live dec)))})
                  hs (mapv (fn [_] (rt/spawn rt rt/root "w")) (range 3))]
              (doseq [h hs] (rt/wake rt h "go"))
              (doseq [h hs] (rt/wait rt h))
              [@peak (:max-observed (rt/gate-stats rt))]))]
      (is (= [1 1] (measure 1))
          "gate 1 serializes the LLM calls of three independent handles")
      (is (< 1 (first (measure 8)))
          "gate 8 lets them overlap — otherwise the gate-1 result proves nothing"))))

;; ===========================================================================
;; close — graceful, leaf-first, and close ≠ loss
;; ===========================================================================

(deftest close-cascades-leaf-first-and-keeps-the-final-state-queryable
  (let [{:keys [rt]} (runtime-with {"boss" (adef "boss" "bm" :budget {:max-depth 5})
                                    "w"    (adef "w" "wm")}
                                   {"wm" [{:text "hi"}]})
        boss (rt/spawn rt rt/root "boss")
        w    (rt/spawn rt boss "w")]
    (rt/wake rt w "go")
    (rt/wait rt w)
    (rt/close rt boss)
    (is (= "closed" (state-of rt w)))
    (is (= "closed" (state-of rt boss)))
    (is (< (trace-index rt "root/boss.1/w.1: idle→closed")
           (trace-index rt "root/boss.1: idle→closed"))
        "leaf-first: a parent is never closed while a child is still open")
    (is (= 15 (:tokens (rt/inspect rt w)))
        "close ≠ loss — the final state stays queryable")
    (is (= "done" (:status (rt/wait rt w)))
        "…and `wait` on it still answers with the result it recorded before closing")))

(deftest a-close-with-no-recorded-result-answers-closed
  (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {})
        h (rt/spawn rt rt/root "w")]
    (rt/close rt h)
    (let [r (rt/wait rt h)]
      (is (= "closed" (:status r)) "a handle that never ran answers with the `closed` status")
      (is (true? (:isError r))))))

(deftest close-runs-on-close-with-the-reason
  (let [seen (atom nil)
        {:keys [rt]} (runtime-with
                      {"w" (adef "w" "wm" :on-close (fn [_rt id reason] (reset! seen [id reason])))}
                      {})
        h (rt/spawn rt rt/root "w")]
    (rt/close rt h)
    (is (= ["root/w.1" "closed"] @seen))
    (rt/close rt h)
    (is (= ["root/w.1" "closed"] @seen) "closing twice is idempotent — on-close runs once")))

(deftest on-spawn-runs-once-before-the-first-turn
  (let [calls (atom [])
        {:keys [rt]} (runtime-with
                      {"w" (adef "w" "wm" :on-spawn (fn [r id] (swap! calls conj [id (state-of r id)])))}
                      {"wm" [{:text "hi"}]})
        h (rt/spawn rt rt/root "w")]
    (rt/wake rt h "go")
    (rt/wait rt h)
    (is (= [["root/w.1" "idle"]] @calls)
        "once, at spawn, before the handle has ever run")))

(deftest a-throwing-on-spawn-is-traced-not-fatal
  (let [{:keys [rt]} (runtime-with
                      {"w" (adef "w" "wm" :on-spawn (fn [_r _id] (throw (ex-info "boom" {}))))}
                      {"wm" [{:text "hi"}]})
        h (rt/spawn rt rt/root "w")]
    (is (string? h) "the handle still exists")
    (is (traced? rt "root/w.1: onSpawn error: boom"))
    (rt/wake rt h "go")
    (is (= "done" (:status (rt/wait rt h))) "and it still runs")))

;; ===========================================================================
;; wait timeouts, on the injectable clock
;; ===========================================================================

(deftest a-wait-timeout-is-explicit-and-the-child-keeps-running
  (testing "the deadline belongs to the WAITER, never to the child"
    (let [gate (promise)
          entered (promise)
          clock (rt/virtual-clock)
          {:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {"wm" [{:text "eventually"}]}
                                     {:clock clock
                                      :on-call (fn [_b _n] (deliver entered true) @gate)})
          h (rt/spawn rt rt/root "w")
          got (promise)]
      (rt/wake rt h "go")
      (is (until! #(realized? entered)))
      (proc/run-async! (fn [] (deliver got (rt/wait rt h {:timeout-ms 500}))))
      ;; give the waiter a moment to register, then move VIRTUAL time only
      (is (until! #(do ((:advance! clock) 600) (realized? got)))
          "the timeout fired off the injected clock, not off wall time")
      (let [r @got]
        (is (= "timeout" (:status r)))
        (is (str/includes? (:text r) "wait timeout after 500ms"))
        (is (str/includes? (:text r) "child still running")))
      (is (= "running" (state-of rt h)) "the child was NOT cancelled by the waiter's deadline")
      (deliver gate true)
      (is (= "done" (:status (rt/wait rt h))) "and it finished normally afterwards"))))

;; ===========================================================================
;; The `task` tool — opt-in, team-scoped, spawn→wake→wait→close fused
;; ===========================================================================

(deftest no-team-means-no-task-tool
  (testing "delegation, like recursion, is OPT-IN"
    (let [{:keys [rt requests]} (runtime-with {"w" (adef "w" "wm")} {"wm" [{:text "hi"}]})
          h (rt/spawn rt rt/root "w")]
      (rt/wake rt h "go")
      (rt/wait rt h)
      (is (nil? (:tools (first @requests)))
          "an agent with no :team is offered no tools at all — the toolkit view IS the security model"))))

(deftest the-task-description-advertises-only-the-sorted-team
  (let [{:keys [rt requests]} (runtime-with
                               {"boss"   (adef "boss" "bm" :team ["writer" "auditor"] :budget {:max-depth 5})
                                "writer" (adef "writer" "wm" :does "writes prose")
                                "auditor" (adef "auditor" "am" :does "checks numbers")
                                "secret" (adef "secret" "sm" :does "must not be advertised")}
                               {"bm" [{:text "hi"}]})
        h (rt/spawn rt rt/root "boss")]
    (rt/wake rt h "go")
    (rt/wait rt h)
    (let [tools (:tools (first @requests))
          desc  (get-in (first tools) [:function :description])]
      (is (= 1 (count tools)) "exactly one delegation tool")
      (is (= "task" (get-in (first tools) [:function :name])))
      (is (str/ends-with? desc "Available agents — auditor: checks numbers; writer: writes prose")
          "the CALLER's team only, sorted by NAME, composed from each agent's :does")
      (is (not (str/includes? desc "secret"))
          "the registry is not the team — an unrelated agent is never advertised"))))

(deftest an-out-of-team-target-errors-and-lists-the-team
  (let [{:keys [rt]} (runtime-with
                      {"boss"   (adef "boss" "bm" :team ["writer"] :budget {:max-depth 5})
                       "writer" (adef "writer" "wm" :does "writes")
                       "other"  (adef "other" "om" :does "elsewhere")}
                      {"bm" [{:calls [{:id "c1" :name "task" :args {:agent "other" :prompt "sneak"}}]}
                             {:text "gave up"}]})
        h (rt/spawn rt rt/root "boss")]
    (rt/wake rt h "go")
    (rt/wait rt h)
    (is (= ["root/boss.1"] (mapv :id (rt/handles rt)))
        "an out-of-team target must not spawn ANYTHING")
    (is (= "done" (:status (rt/wait rt h))) "the refusal is a tool result, not a crash")))

(deftest task-delegates-and-rolls-the-child-usage-into-the-parent
  (testing "spawn→wake→wait→close fused; the parent gains exactly one tool message"
    (let [{:keys [rt requests]} (runtime-with
                                 {"boss"   (adef "boss" "bm" :team ["writer"] :budget {:max-depth 5})
                                  "writer" (adef "writer" "wm" :does "writes")}
                                 {"bm" [{:calls [{:id "c1" :name "task" :args {:agent "writer" :prompt "the intro"}}]}
                                        {:text "shipped"}]
                                  "wm" [{:text "a fine intro"}]})
          h (rt/spawn rt rt/root "boss")]
      (rt/wake rt h "write me a post")
      (is (= "shipped" (:text (rt/wait rt h))))
      (let [child "root/boss.1/writer.1"]
        (is (= "closed" (state-of rt child)) "a settled delegate is CLOSED by the fused verb")
        (is (= 15 (:tokens (rt/inspect rt child))))
        (is (= 45 (:tokens (rt/inspect rt h)))
            "the child's 15 rolls up into the parent's own 30"))
      (let [tool-msgs (->> (last @requests) :messages (filter #(= "tool" (:role %))))]
        (is (= 1 (count tool-msgs)) "EXACTLY one tool message per task call")
        (is (= "a fine intro" (:content (first tool-msgs)))
            "the parent sees only the child's final text, never its transcript"))
      (let [child-req (->> @requests (filter #(= "wm" (:model %))) first)]
        (is (= 1 (count (:messages child-req)))
            "the child runs on a FRESH transcript — no system prompt, no parent history")
        (is (= "the intro" (:content (first (:messages child-req)))))))))

;; ===========================================================================
;; §10 escalation, durable resume and REATTACHMENT
;; ===========================================================================

(defn- approve-tool []
  (tool/tool {:name "approve" :description "requests approval"
              :execute (fn ([_a] (client/suspend (client/make-request "approval" "approve the spend?" {:id "r1"})))
                         ([_a ctx] (if (:ok (:answer ctx))
                                     (tool/success "approved")
                                     (tool/success "declined"))))}))

(deftest a-suspending-child-presents-to-its-parent-as-a-suspending-tool
  (testing "§10 verbatim, one hop at a time, and the root returns `pending`"
    (let [{:keys [rt]} (runtime-with
                        {"boss"   (adef "boss" "bm" :team ["worker"] :budget {:max-depth 5})
                         "worker" (adef "worker" "wm" :does "works" :tools [(approve-tool)])}
                        ;; The boss's replay re-emits the SAME task call — that is
                        ;; what a real model does off the rewound transcript, and it
                        ;; is the only way reattachment can be exercised at all.
                        {"bm" [{:calls [{:id "c1" :name "task" :args {:agent "worker" :prompt "spend it"}}]}
                               {:calls [{:id "c1" :name "task" :args {:agent "worker" :prompt "spend it"}}]}
                               {:text "all done"}]
                         "wm" [{:calls [{:id "t1" :name "approve" :args {}}]}
                               {:calls [{:id "t1" :name "approve" :args {}}]}
                               {:text "spent"}]})
          h (rt/spawn rt rt/root "boss")]
      (rt/wake rt h "go")
      (let [r (rt/wait rt h)]
        (is (= "pending" (:status r)) "the root's answer is a pending, not an error")
        (is (= false (:isError r)) "a pending is NOT a failure — the §10 shape is a pause")
        (is (= "approval" (:kind (:pending r)))))
      (is (= "suspended" (state-of rt "root/boss.1")))
      (is (= "suspended" (state-of rt "root/boss.1/worker.1"))
          "both levels are parked — a parked level burns zero tokens")
      (is (traced? rt "root/boss.1/worker.1: running→suspended (pending \"approval\")"))
      (is (traced? rt "root/boss.1: running→suspended (pending \"approval\")"))

      ;; --- resume ------------------------------------------------------
      (let [before (:tokens (rt/inspect rt "root/boss.1/worker.1"))]
        (rt/resume rt (client/make-answer "r1" true))
        (is (traced? rt "root/boss.1/worker.1: resume with Answer(ok=true) at checkpoint")
            "the Answer routes to the DEEPEST suspended handle, not to the nearest")
        (is (traced? rt "root/boss.1/worker.1: suspended→idle (Answer accepted, checkpoint restored)")
            "durable resume traces suspended→idle …")
        (is (= ["root/boss.1/worker.1: resume with Answer(ok=true) at checkpoint (turns so far: 1)"
                "root/boss.1/worker.1: suspended→idle (Answer accepted, checkpoint restored)"
                "root/boss.1/worker.1: idle→running (wake)"]
               (subvec (rt/trace rt) 6 9))
            "… and then idle→running for the replay wake — the Answer is the only exit from suspended")
        (is (traced? rt "root/boss.1: cascade resume (reattaching delegated work)"))
        (is (traced? rt "root/boss.1: task replay → REATTACH to root/boss.1/worker.1")
            "REATTACHMENT — not transcript inspection — is the idempotency mechanism")
        (is (> (:tokens (rt/inspect rt "root/boss.1/worker.1")) before)
            "usage GROWS across a resume, it never resets"))
      (is (= ["root/boss.1" "root/boss.1/worker.1"] (mapv :id (rt/handles rt)))
          "the replayed task reattached; a duplicate child is the bug this prevents")
      (is (= "all done" (:text (rt/wait rt h))) "the cascade completed the parent's turn"))))

(deftest the-nearest-interpreter-wins
  (testing "a def with waitFor answers its own subtree's Request; nothing escalates past it"
    (let [asked (atom [])
          {:keys [rt]} (runtime-with
                        {"boss"   (adef "boss" "bm" :team ["worker"] :budget {:max-depth 5}
                                        :wait-for (fn [req]
                                                    (swap! asked conj (:kind req))
                                                    (client/make-answer (:id req) true)))
                         "worker" (adef "worker" "wm" :does "works" :tools [(approve-tool)])}
                        {"bm" [{:calls [{:id "c1" :name "task" :args {:agent "worker" :prompt "spend"}}]}
                               {:text "handled"}]
                         "wm" [{:calls [{:id "t1" :name "approve" :args {}}]}
                               {:text "spent"}]})
          h (rt/spawn rt rt/root "boss")]
      (rt/wake rt h "go")
      (let [r (rt/wait rt h)]
        (is (= "done" (:status r)) "the boss's interpreter resolved it — nothing reached the host")
        (is (= "handled" (:text r))))
      (is (= ["approval"] @asked) "asked exactly once")
      (is (traced? rt "root/boss.1/worker.1: escalate → root/boss.1 answers (\"nearest interpreter\")")
          "the trace names WHO asked and WHO answered"))))

(deftest a-durable-pending-rewinds-the-stored-transcript
  (testing "the halted turn must not survive in the durable transcript"
    (let [store (client/in-memory-store)
          {:keys [rt]} (runtime-with
                        {"w" (adef "w" "wm" :tools [(approve-tool)])}
                        {"wm" [{:calls [{:id "t1" :name "approve" :args {}}]}]}
                        {:store store})
          h (rt/spawn rt rt/root "w")]
      (rt/wake rt h "go")
      (is (= "pending" (:status (rt/wait rt h))))
      (is (empty? (or ((:get store) "root/w.1") []))
          "rewound to the PRE-TURN snapshot — a persisted §10 placeholder would make a resumed parent skip re-invoking task"))))

(deftest the-runtime-store-is-one-store-keyed-by-handle-id
  (testing "conversation id = handle id, so transcripts genuinely survive turns"
    (let [store (client/in-memory-store)
          {:keys [rt]} (runtime-with {"w" (adef "w" "wm")}
                                     {"wm" [{:text "one"} {:text "two"}]}
                                     {:store store})
          h (rt/spawn rt rt/root "w")]
      (rt/wake rt h "first")
      (rt/wait rt h)
      (rt/wake rt h "second")
      (rt/wait rt h)
      (let [msgs ((:get store) "root/w.1")]
        (is (some #(= "first" (:content %)) msgs))
        (is (some #(= "second" (:content %)) msgs)
            "the second turn continued the SAME conversation, it did not start a new one")))))

;; ===========================================================================
;; The §8 seams — def-over-runtime, REPLACE never merge, forwarded verbatim
;; ===========================================================================

(deftest hooks-and-on-metric-resolve-def-over-runtime-and-replace
  (let [seen (atom [])
        rt-hook   {:before-llm (fn [_e] (swap! seen conj :runtime-hook) nil)}
        def-hook  {:before-llm (fn [_e] (swap! seen conj :def-hook) nil)}
        {:keys [rt]} (runtime-with
                      {"plain"    (adef "plain" "pm")
                       "override" (adef "override" "om" :hooks def-hook)}
                      {"pm" [{:text "a"}] "om" [{:text "b"}]}
                      {:hooks rt-hook})
        a (rt/spawn rt rt/root "plain")
        b (rt/spawn rt rt/root "override")]
    (rt/wake rt a "x") (rt/wait rt a)
    (is (= [:runtime-hook] @seen) "an agent with no hooks inherits the runtime's")
    (reset! seen [])
    (rt/wake rt b "y") (rt/wait rt b)
    (is (= [:def-hook] @seen)
        "a def that sets :hooks REPLACES the runtime's — composing two transcript rewrites has no defined order")))

(deftest hooks-and-on-metric-resolve-independently
  (let [hooks-seen (atom 0) metric-seen (atom 0)
        {:keys [rt]} (runtime-with
                      {"w" (adef "w" "wm" :hooks {:before-llm (fn [_e] (swap! hooks-seen inc) nil)})}
                      {"wm" [{:text "a"}]}
                      {:hooks {:before-llm (fn [_e] nil)}
                       :on-metric (fn [_ev] (swap! metric-seen inc))})
        h (rt/spawn rt rt/root "w")]
    (rt/wake rt h "x") (rt/wait rt h)
    (is (= 1 @hooks-seen) "the def's hooks ran")
    (is (pos? @metric-seen)
        "and the runtime's :on-metric STILL ran — the two fields resolve independently")))

(deftest with-neither-seam-set-nothing-changes
  (testing "unset ⇒ byte-identical: the same trace and the same wire body"
    (let [run-once (fn [opts]
                     (let [{:keys [rt requests]} (runtime-with {"w" (adef "w" "wm")}
                                                               {"wm" [{:text "a"}]} opts)
                           h (rt/spawn rt rt/root "w")]
                       (rt/wake rt h "x") (rt/wait rt h)
                       [(rt/trace rt) @requests]))]
      (is (= (run-once {}) (run-once {:on-metric (fn [_] nil)}))
          "an observer-only sink cannot move the trace or the wire body"))))

;; ===========================================================================
;; The closed status vocabulary
;; ===========================================================================

(deftest every-status-this-runtime-produces-is-in-the-closed-vocabulary
  (is (= #{"done" "pending" "incomplete" "interrupted" "closed" "timeout" "error"} rt/statuses)
      "the vocabulary is CLOSED and identical in every port")
  (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm" :budget {:max-turns 1})}
                                   {"wm" [{:text "a"}]})
        h (rt/spawn rt rt/root "w")]
    (rt/wake rt h "x")
    (is (contains? rt/statuses (:status (rt/wait rt h))))
    (rt/wake rt h "y")
    (is (contains? rt/statuses (:status (rt/wait rt h))))
    (rt/close rt h)
    (is (contains? rt/statuses (:status (rt/wait rt h))))))

;; ===========================================================================
;; The axiom — an Agent IS a Tool
;; ===========================================================================

(deftest agent-tool-returns-only-the-final-text-plus-metadata
  (let [{:keys [rt]} (runtime-with {"writer" (adef "writer" "wm" :does "writes prose")}
                                   {"wm" [{:text "the prose"}]})
        t (rt/agent-tool rt "writer")
        r ((:execute t) {:prompt "write"})]
    (is (= "writer" (:name t)))
    (is (= "writes prose" (:description t)) "the tool's description IS the agent's `does`")
    (is (= "the prose" (:output r)) "ONLY the final text — never the transcript")
    (is (= false (:isError r)))
    (is (= {:agent "writer" :turns 1 :total-tokens 15} (:metadata r))
        "…plus exactly {agent, turns, totalTokens}")))

(deftest run-agent-is-the-one-shot-surface
  (let [{:keys [rt]} (runtime-with {"w" (adef "w" "wm")} {"wm" [{:text "answer"}]})
        r (rt/run-agent rt "w" "question")]
    (is (= "done" (:status r)))
    (is (= "answer" (:text r)))
    (is (= "closed" (state-of rt "root/w.1")) "one-shot closes its handle")))

;; ===========================================================================
;; Views
;; ===========================================================================

(deftest handles-lists-the-tree-in-order-excluding-root
  (let [{:keys [rt]} (runtime-with {"boss" (adef "boss" "bm" :budget {:max-depth 5})
                                    "w"    (adef "w" "wm")}
                                   {})
        boss (rt/spawn rt rt/root "boss")]
    (rt/spawn rt boss "w")
    (rt/spawn rt rt/root "w")
    (is (= ["root/boss.1" "root/boss.1/w.1" "root/w.2"]
           (mapv :id (rt/handles rt)))
        "tree order, root excluded")
    (is (= ["root/boss.1" "root/boss.1/w.1"] (mapv :id (rt/handles rt "root/boss.1")))
        "a subtree view starts AT the given handle — only the RUNTIME root is excluded")))
