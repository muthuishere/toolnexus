(ns toolnexus.agents.runtime
  "The agent runtime substrate — SPEC.md §7D.

  One axiom: **an Agent IS a Tool** — (system prompt × a filtered toolkit view ×
  the §8 loop), invocable, returning ONLY its final text plus
  `{:agent :turns :total-tokens}`. Everything below is the machinery that makes
  that safe to do recursively: a tree of handles, three loud backpressure gates,
  hierarchical budgets, and §10 suspension that escalates one hop at a time.

  A Handle is a live agent: a state machine (`idle → running → idle|suspended|
  closed`, and `suspended → running` ONLY via the Answer to its pending Request),
  an inbox held as AGENT state (never a language mailbox), a carved budget, and a
  deterministic parent-scoped id (`root/coordinator.1/explore.2`). The runtime
  exposes exactly six host verbs — `spawn` `post` `wake` `wait` `interrupt`
  `close` — plus the read-only `handles` / `inspect` views, and owns the
  cross-cutting infrastructure: ONE conversation store for every handle
  (conversation id = handle id, so transcripts genuinely survive turns), an
  injectable clock, and the handle table.

  SPEC pins TRANSITIONS, never scheduling. Conformance is identical per-handle
  transition traces on a virtual clock — which is why `trace` is a first-class
  return value here and why every timer goes through `:clock`.

      (def rt (runtime/create-runtime
                {:registry {\"writer\" {:name \"writer\" :does \"writes\" :soul \"…\"}}
                 :llm      {:base-url \"http://127.0.0.1:9999\" :model \"m\"}}))
      (def h (runtime/spawn rt runtime/root \"writer\"))
      (runtime/wake rt h \"draft the intro\")
      (:text (runtime/wait rt h))

  THREE THINGS THIS PORT DOES DIFFERENTLY, all forced and all recorded:

  1. **Handles are ids, not objects.** A handle is the string `root/writer.1`;
     the mutable tree lives in one atom. Every verb is then a compare-and-set
     transaction over the whole table, which is how \"admission is atomic with
     the verb\" is *implemented* rather than merely asserted. It also keeps the
     capability rule honest: holding the string IS holding the capability.
  2. **`wait` blocks; `wake` does not.** §7D's contract is written in promises
     because JS has no other choice. Clojure does: `wake` starts the turn on a
     `koine.process/run-async!` thread and returns, `wait` derefs a promise. The
     observable contract (next-or-last result, timeout leaves the child running)
     is unchanged.
  3. **Cancellation is cooperative**, checked either side of every LLM round
     trip — the same tier §7D's table gives python and java. `interrupt`
     therefore lands *between* attempts, and the SPEC is explicit that only
     abort LATENCY may differ: the outcome (`idle` + restored inbox + an
     `interrupted` result to waiters) is identical.

  No `future` (a library may not hold its consumer's process open), no java.*,
  no reader conditionals."
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [koine.http :as khttp]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.client :as client]
            [toolnexus.native :as native]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; The clock (§7D: every timer, timeout and deadline goes through this seam)
;; ---------------------------------------------------------------------------
;;
;; A clock is a map of two functions, not a koine concern: koine owns HOST
;; differences, and a virtual clock is not a host difference — it is a toolnexus
;; test seam. Fixtures run virtual, which is the only way a timeout trace is
;; reproducible.

(defn system-clock
  "The default clock: real wall time, real sleeps.

  `:set-timeout` is `run-async!` + `sleep!` rather than a timer object, because a
  timer object is `java.util.Timer` on one host and a `time.Timer` on the other,
  and neither is reachable from portable Clojure. The thread is a daemon on the
  JVM and a goroutine on cljgo, so a pending timer never holds a consumer's
  process open."
  []
  {:now         (fn [] (ktime/now-ms))
   :set-timeout (fn [f ms]
                  (let [cancelled (atom false)]
                    (proc/run-async! (fn []
                                       (ktime/sleep! ms)
                                       (when-not @cancelled (f))))
                    (fn [] (reset! cancelled true))))})

(defn virtual-clock
  "A clock whose time only moves when a test moves it.

  Same two keys as `system-clock`, plus `:advance!` — `((:advance! c) 250)` moves
  time forward and fires every timer that came due, in DEADLINE order (insertion
  order breaks ties), synchronously on the caller's thread. Deterministic by
  construction: nothing about a trace recorded under this clock depends on how
  fast a machine is."
  ([] (virtual-clock 0))
  ([start-ms]
   (let [st (atom {:now start-ms :seq 0 :timers []})]
     {:now         (fn [] (:now @st))
      :set-timeout (fn [f ms]
                     (let [id (:seq (swap! st update :seq inc))]
                       (swap! st update :timers conj
                              {:id id :at (+ (:now @st) ms) :f f})
                       (fn [] (swap! st update :timers
                                     (fn [ts] (vec (remove #(= id (:id %)) ts)))))))
      :advance!    (fn [ms]
                     (let [target (+ (:now @st) ms)]
                       ;; Fire in deadline order, one at a time, re-reading the
                       ;; queue each pass: a timer callback may schedule another
                       ;; timer that is ALSO due before `target`.
                       (loop []
                         (let [due (->> (:timers @st)
                                        (filter #(<= (:at %) target))
                                        (sort-by (juxt :at :id))
                                        first)]
                           (if-not due
                             (swap! st assoc :now target)
                             (do (swap! st (fn [s]
                                             (-> s
                                                 (assoc :now (max (:now s) (:at due)))
                                                 (update :timers
                                                         (fn [ts] (vec (remove #(= (:id due) (:id %)) ts)))))))
                                 ((:f due))
                                 (recur)))))
                       (:now @st)))})))

;; ---------------------------------------------------------------------------
;; Defaults, pinned across the ports
;; ---------------------------------------------------------------------------

(def ^:private default-max-turns 6)
(def ^:private default-max-concurrent 8)
(def ^:private default-max-depth 3)
(def ^:private default-inbox-cap 8)
(def ^:private default-max-concurrent-turns 8)
(def ^:private default-shutdown-ms 200)

(def root
  "The runtime root handle's id. `close(root)` is stop-all; `spawn` from it is
  how a host makes its first agent."
  "root")

(def statuses
  "The CLOSED result-status vocabulary (§7D). Identical strings in every port —
  a host that switches on these must never meet a seventh value.

    done         the turn produced a final answer
    pending      a §10 durable suspension; resume with `resume`
    incomplete   a §7D limit stopped the run, and the text NAMES the limit
    interrupted  the turn was aborted; the handle is idle and alive
    closed       the handle was closed
    timeout      a `wait` deadline expired — the child KEEPS RUNNING
    error        the run failed; failures cross a handle boundary as results"
  #{"done" "pending" "incomplete" "interrupted" "closed" "timeout" "error"})

;; ---------------------------------------------------------------------------
;; Small helpers
;; ---------------------------------------------------------------------------

(defn- min*
  "`min` where nil means unbounded. An absent pool limit is Infinity in the other
  ports; nil is Clojure's honest spelling of the same thing, and it keeps the
  arithmetic below from ever producing a float."
  [a b]
  (cond (nil? a) b (nil? b) a :else (min a b)))

(defn- fmt-num
  "Trace rendering for a possibly-unbounded pool. Prints `Infinity` for nil so
  the trace line is byte-identical to the ports that hold a real Infinity."
  [n]
  (if (nil? n) "Infinity" (str n)))

(defn- sub*
  "Drain a pool. Unbounded stays unbounded."
  [pool n]
  (if (nil? pool) nil (- pool n)))

(defn verb-error?
  "True for the `{:error \"…\"}` shape a verb returns instead of a handle. Verbs
  return failures, they do not throw — only the root may throw to the host."
  [x]
  (and (map? x) (string? (:error x))))

(defn- trace-in [st line] (update st :trace conj line))

;; ---------------------------------------------------------------------------
;; The state transaction
;; ---------------------------------------------------------------------------

(defn- transact!
  "Apply `f` to the runtime state atomically. `f` returns `[state' outcome]`;
  `transact!` commits `state'` by compare-and-set and returns `outcome`.

  Plain `swap!` cannot express a verb: a verb has to commit a state change AND
  report a decision computed from the state it actually committed. `swap-vals!`
  would do it on the JVM, so this is a CAS loop instead — `compare-and-set!` is
  the primitive both hosts are guaranteed to have. `f` MUST be pure: it can be
  retried, so every side effect (starting a turn, delivering a promise) is
  carried out in the outcome and performed by the caller, once."
  [rt f]
  (let [a (:state rt)]
    (loop []
      (let [old @a
            pair (f old)]
        (if (compare-and-set! a old (first pair))
          (second pair)
          (recur))))))

(defn- h-of [st id] (get-in st [:handles id]))

;; ---------------------------------------------------------------------------
;; Budgets — carve at spawn, then a LIVE ancestor-chain walk
;; ---------------------------------------------------------------------------

(defn- ancestors-of
  "This handle and every ancestor, nearest first. Budget enforcement walks it
  LIVE before each turn and each spawn: the carve at spawn time cannot see a
  sibling that spent the shared pool afterwards."
  [st id]
  (loop [i id acc []]
    (if-not i
      acc
      (let [h (h-of st i)]
        (if-not h acc (recur (:parent h) (conj acc h)))))))

(defn- pool-limit
  "Name the first exhausted pool on the live ancestor chain, or nil."
  [st clock-now id]
  (some (fn [a]
          (cond
            (and (:tokens (:pool a)) (<= (:tokens (:pool a)) 0))         "maxTokens"
            (and (:tool-calls (:pool a)) (<= (:tool-calls (:pool a)) 0)) "maxToolCalls"
            (and (:deadline a) (>= clock-now (:deadline a)))             "maxWallMs"
            :else nil))
        (ancestors-of st id)))

(defn- turn-cap
  "The lifetime turn cap on the handle itself. Turns NEVER reset — a resumed
  handle keeps counting, which is what stops an unbounded suspend/resume cycle
  from being a free turn machine."
  [st id]
  (let [h (h-of st id)]
    (when (>= (:turns-total h) (get-in h [:eff :max-turns])) "maxTurns")))

(defn- roll-up
  "Usage roll-up IS the budget ledger: a child's spend drains every ancestor's
  pool live, so `maxTokens` on a coordinator really does bound its whole subtree."
  [st id tokens tool-calls]
  (reduce (fn [s a]
            (update-in s [:handles (:id a)]
                       (fn [h]
                         (-> h
                             (update :usage-total + tokens)
                             (update :tool-calls-total + tool-calls)
                             (update-in [:pool :tokens] sub* tokens)
                             (update-in [:pool :tool-calls] sub* tool-calls)))))
          st
          (ancestors-of st id)))

;; ---------------------------------------------------------------------------
;; Results
;; ---------------------------------------------------------------------------
;;
;; `:isError` keeps its wire spelling even though a TaskResult is not itself a
;; wire type: this exact boolean is handed straight to the `task` tool's
;; ToolResult, where §0.1 pins the key. Renaming it here would mean one rename
;; layer in the one place a rename is forbidden.

(defn- result
  [status text err? turns total-tokens]
  {:text text :isError (boolean err?) :status status
   :turns turns :total-tokens total-tokens})

(defn- closed-result [h]
  (result "closed" "closed" true (:turns-total h) (:usage-total h)))

(defn- pending-result [h]
  (let [req (:pending-request h)]
    (assoc (result "pending" (or (:prompt req) "") false
                   (:turns-total h) (:usage-total h))
           :pending req)))

(defn- settle-refused
  "A limit stop is LOUD (§7D): it settles an `incomplete` result that NAMES the
  limit, never a silent `done` and never a crash. Partial work is preserved —
  the handle stays idle and alive with its transcript intact.

  Returns `[state' text waiters]`; the caller delivers to the waiters."
  [st id limit]
  (let [h    (h-of st id)
        text (str "budget exhausted (" limit "); partial work preserved")
        r    (result "incomplete" text true (:turns-total h) (:usage-total h))]
    [(update-in st [:handles id] assoc :last-result r :waiters [] :state "idle")
     text
     (:waiters h)]))

(defn- deliver-all!
  "Hand one result to every registered waiter. `deliver` on an already-delivered
  promise is a no-op, which is exactly what makes the `wait` timeout race safe:
  whichever of the two fires first wins and the other is discarded."
  [waiters r]
  (doseq [p waiters] (deliver p r)))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(defn- new-handle
  "Carve a handle. Effective pools = `min(own, parent remaining)`; the live walk
  above still applies per turn, because the carve alone misses sibling spend."
  [id def-map parent-h now]
  (let [own      (or (:budget def-map) {})
        p-tokens (when parent-h (:tokens (:pool parent-h)))
        p-calls  (when parent-h (:tool-calls (:pool parent-h)))
        deadline (min* (when (:max-wall-ms own) (+ now (:max-wall-ms own)))
                       (when parent-h (:deadline parent-h)))]
    {:id               id
     :def              def-map
     :parent           (when parent-h (:id parent-h))
     :depth            (if parent-h (inc (:depth parent-h)) 0)
     :children         []
     :inbox            []
     :state            "idle"
     :pool             {:tokens     (min* (or (:max-tokens own) p-tokens) p-tokens)
                        :tool-calls (min* (or (:max-tool-calls own) p-calls) p-calls)
                        :children   (:max-children own)}
     :eff              {:max-turns      (or (:max-turns own) default-max-turns)
                        :max-concurrent (or (:max-concurrent own) default-max-concurrent)
                        :max-depth      (or (:max-depth own) default-max-depth)}
     :deadline         deadline
     :usage-total      0
     :tool-calls-total 0
     :turns-total      0
     :pending-request  nil
     ;; `:on-budget` bookkeeping (§7D). `:budget-grant` is a ONE-SHOT permit — an
     ;; `"extend"` decision sets it, the next admission consumes it. `:budget-pending`
     ;; names the limit a `"suspend"` decision parked on, so the Answer knows whether
     ;; it is granting the turn or refusing it.
     :budget-grant     false
     :budget-pending   nil
     :last-result      nil
     :checkpoint       nil
     :drained          []
     :waiters          []
     :wake-queue       []
     :task-key         nil
     :running-children 0
     :seq              0
     :cancel           nil}))

(defn create-runtime
  "Build a runtime.

    :registry             agent definitions by name — the `task` tool resolves
                          targets here (REQUIRED for delegation)
    :llm                  {:base-url :style :model :api-key} for every handle's
                          client; a def's own `:model` overrides `:model` unless
                          it is \"inherit\"
    :http-client          the LLM transport (fn [url headers body] response) —
                          the hermetic-test seam, and the thing the turn gate
                          wraps. Same shape as `koine.http/post-json`
    :clock                the time source; default `system-clock`
    :store                the ONE conversation store for every handle
                          (conversation id = handle id); default in-memory
    :inbox-cap            gate 1 — inbox capacity per handle (default 8)
    :max-concurrent-turns gate 3 — concurrent LLM calls tree-wide (default 8)
    :shutdown-ms          graceful-close bound before `close` escalates to an
                          interrupt (default 200)
    :hooks                §8 lifecycle callbacks applied to EVERY agent, unless
                          that agent's def sets its own. Forwarded verbatim
    :on-metric            §8 observability sink, same resolution, resolved
                          INDEPENDENTLY of `:hooks`
    :on-budget            the optional §7D host budget callback,
                          `(fn [info] \"stop\"|\"extend\"|\"suspend\")`. Consulted
                          ONLY when a limit would stop a turn; absent ⇒ the limit
                          always stops it, byte-identically. See `budget-decision!`

  An AgentDef is a map:

    {:name :does :soul :model :tools :team :budget
     :wait-for :on-spawn :on-close :hooks :on-metric}

  `:on-spawn` is `(fn [runtime handle-id])`, `:on-close` is
  `(fn [runtime handle-id reason])` and `:wait-for` is `(fn [request] answer)`.
  A handle here is an id, so a lifecycle callback that wants to DO anything
  needs the runtime as well — hence two arguments where the other ports pass
  one object.

  A Budget is a map of any of `:max-turns :max-tokens :max-tool-calls
  :max-wall-ms :max-children :max-concurrent :max-depth`. Money is deliberately
  absent — it is vendor data, and a host converts tokens to money itself."
  [opts]
  (let [clock (or (:clock opts) (system-clock))
        store (or (:store opts) (client/in-memory-store))
        rt    {:opts     opts
               :registry (or (:registry opts) {})
               :clock    clock
               :store    store
               :inbox-cap (or (:inbox-cap opts) default-inbox-cap)
               :shutdown-ms (or (:shutdown-ms opts) default-shutdown-ms)
               :state    (atom {:handles {}
                                :trace   []
                                :turn-slots (or (:max-concurrent-turns opts)
                                                default-max-concurrent-turns)
                                :turn-queue []
                                :concurrent-turns 0
                                :max-observed-concurrent-turns 0})}
        root-h (new-handle root {:name root :does "runtime root" :model "none"}
                           nil ((:now clock)))]
    (swap! (:state rt) assoc-in [:handles root] root-h)
    rt))

(defn trace
  "The transition trace — the §0 conformance artifact. Every state transition,
  every refused verb, every escalation, in the order they committed."
  [rt]
  (:trace @(:state rt)))

(defn gate-stats
  "Turn-gate observability: LLM calls in flight and the high-water mark. A
  fixture asserts gate 3 with `:max-observed`, which is the only honest way to
  test a limit — a test that never reaches the cap proves nothing."
  [rt]
  (let [st @(:state rt)]
    {:in-flight   (:concurrent-turns st)
     :max-observed (:max-observed-concurrent-turns st)}))

;; ---------------------------------------------------------------------------
;; Views (read-only)
;; ---------------------------------------------------------------------------

(defn- view [h]
  {:id (:id h) :state (:state h) :tokens (:usage-total h) :inbox (count (:inbox h))})

(defn handles
  "Read-only snapshot of every handle in tree order, root excluded.

  NOT named `list` — `clojure.core/list` exists on both hosts and shadowing a
  core name is how a whole namespace gets rejected by cljgo's interop scan."
  ([rt] (handles rt root))
  ([rt id]
   (let [st @(:state rt)]
     (letfn [(walk [i acc]
               (let [h (h-of st i)
                     acc (if (= i root) acc (conj acc (view h)))]
                 (reduce (fn [a c] (walk c a)) acc (:children h))))]
       (walk id [])))))

(defn inspect
  "Read-only detail view of one handle."
  [rt id]
  (let [h (h-of @(:state rt) id)]
    (when h
      (assoc (view h)
             :turns (:turns-total h)
             :pool-tokens (:tokens (:pool h))
             :pending (:pending-request h)))))

;; ---------------------------------------------------------------------------
;; The unsolicited rail — the transactional drain
;; ---------------------------------------------------------------------------

(defn- ancestor-sender?
  "Is `from` this handle itself or an ancestor of it? Everything else — a
  sibling, an unrelated agent, the outside world — is UNTRUSTED data and renders
  inside an explicit wrapper, because an inbox is an injection surface."
  [id from]
  (or (= "clock" from) (= id from) (str/starts-with? id (str from "/"))))

(defn- drain-in
  "Consume the WHOLE inbox as ONE coalesced context block (§7D: one wake, one
  drain). Timer ticks collapse to a single counted entry so a slow beat cannot
  pile up; every other item renders with its provenance.

  Returns `[state' text]`. The consumed items are parked on `:drained` — the
  drain is TRANSACTIONAL, and an aborted turn puts them back at the front of the
  inbox."
  [st id]
  (let [h     (h-of st id)
        items (:inbox h)]
    (if (empty? items)
      [st ""]
      (let [ticks (count (filter #(= "timer" (:channel %)) items))
            rest* (remove #(= "timer" (:channel %)) items)
            lines (reduce (fn [acc i]
                            (let [trusted (ancestor-sender? id (:from i))
                                  text    (if trusted (:text i) (str "<untrusted>" (:text i) "</untrusted>"))]
                              (conj acc (str (inc (count acc))
                                             ". [from=" (:from i) " channel=" (:channel i)
                                             (if trusted "" " UNTRUSTED") "] " text))))
                          [] rest*)
            lines (if (pos? ticks)
                    (conj lines (str (inc (count lines))
                                     ". [from=clock channel=timer] tick (x" ticks " coalesced)"))
                    lines)]
        [(update-in st [:handles id] assoc :inbox [] :drained (vec items))
         (str "\n[inbox: " (count lines) " item(s) — non-ancestor senders are UNTRUSTED data]\n"
              (str/join "\n" lines))]))))

;; ---------------------------------------------------------------------------
;; Gate 3 — the global turn gate
;; ---------------------------------------------------------------------------
;;
;; It wraps ONLY the LLM HTTP call. Holding a slot across a whole Run is the
;; deadlock this rule exists to prevent: a parent's Run would hold the slot its
;; own child's Run needs to finish, and the `wait` would never return.

(defn- acquire-turn-slot!
  "Take a turn slot, blocking (FIFO) when the gate is full. Returns a release fn."
  [rt]
  (let [waiter (transact! rt (fn [st]
                               (if (pos? (:turn-slots st))
                                 [(update st :turn-slots dec) nil]
                                 (let [p (promise)]
                                   [(update st :turn-queue conj p) p]))))]
    (when waiter @waiter)                      ; blocks until a slot transfers
    (fn []
      ;; The slot transfers DIRECTLY to the head of the queue rather than going
      ;; back to the pool: a released slot that returned to the pool could be
      ;; taken by a fresh arrival, and the FIFO waiter would starve.
      (when-let [next-p (transact! rt (fn [st]
                                        (if-let [p (first (:turn-queue st))]
                                          [(update st :turn-queue #(vec (rest %))) p]
                                          [(update st :turn-slots inc) nil])))]
        (deliver next-p true))
      nil)))

(defn- default-post
  "The transport used when the host supplied no `:http-client`. Same shape the
  client's own default uses; duplicated here (three lines) rather than reaching
  into a private fn, because the gate has to sit OUTSIDE the client."
  [url headers body]
  (khttp/request {:method :post :url url :headers headers :body body}))

(defn- check-cancel!
  "The cooperative cancellation point. Throws when the turn has been interrupted
  or closed — checked either side of every round trip, which is the `between
  attempts` tier §7D's table assigns to the cooperative-cancel ports."
  [cancel]
  (when-let [reason @cancel]
    (throw (ex-info reason {:cancelled reason}))))

(defn- gated-http-client
  "The LLM transport, wrapped in gate 3 and the cancellation checks. The slot is
  released in a `finally`, so it is released on the acquirer's DEATH (a thrown
  cancel, a transport blow-up) and not only on its happy path."
  [rt cancel]
  (let [base (or (:http-client (:opts rt)) default-post)]
    (fn [url headers body]
      (check-cancel! cancel)
      (let [release (acquire-turn-slot! rt)]
        (try
          (transact! rt (fn [st]
                          (let [n (inc (:concurrent-turns st))]
                            [(-> st
                                 (assoc :concurrent-turns n)
                                 (update :max-observed-concurrent-turns max n))
                             nil])))
          (let [res (base url headers body)]
            (check-cancel! cancel)
            res)
          (finally
            (transact! rt (fn [st] [(update st :concurrent-turns dec) nil]))
            (release)))))))

;; ---------------------------------------------------------------------------
;; verb: spawn
;; ---------------------------------------------------------------------------

(declare task-tool)

(defn spawn
  "Create a child handle under `parent-id` with a DETERMINISTIC, parent-scoped id
  (`root/coordinator.1/explore.2`) — never random, so two runs of one fixture
  produce the same trace.

  `maxDepth`, `maxChildren` and the live budget walk are all checked HERE. The id
  is returned to the spawner alone: handles are capabilities — post and wake
  what you hold, wait only on what you spawned.

  Returns the child's id, or `{:error \"…\"}` (see `verb-error?`)."
  ([rt parent-id def-name] (spawn rt parent-id def-name nil))
  ([rt parent-id def-name budget]
   (let [reg (:registry rt)
         now ((:now (:clock rt)))
         ;; §7E: an AgentDef may carry :soul-file instead of :soul — the other
         ;; six ports spell it soulFile/soul_file, and its absence here was the
         ;; single most repeated divergence when the docs were written. Resolved
         ;; ONCE, at spawn, matching js/src/agents/agent.ts (registry-build
         ;; time): the frozen-snapshot rule — an edit to the file lands on the
         ;; NEXT spawn, never mid-run. Read OUTSIDE the transaction below, whose
         ;; body may retry under contention; :soul-file wins over :soul, as in
         ;; every other port.
         souled (when-let [d (get reg def-name)]
                  (if-let [f (:soul-file d)]
                    (assoc d :soul (fs/read-file (str f)))
                    d))
         outcome
         (transact!
          rt
          (fn [st]
            (let [p   (h-of st parent-id)
                  d   souled
                  err (cond
                        (nil? d) (str "unknown agent \"" def-name "\" (known: "
                                      (str/join ", " (sort (keys reg))) ")")
                        (nil? p) (str "unknown parent: " parent-id)
                        (= "closed" (:state p)) (str "parent closed: " parent-id)
                        (> (inc (:depth p)) (get-in p [:eff :max-depth]))
                        (str "maxDepth " (get-in p [:eff :max-depth]) " exceeded")
                        (and (:children (:pool p))
                             (> (inc (count (:children p))) (:children (:pool p))))
                        (str "maxChildren " (:children (:pool p)) " exceeded")
                        :else (when-let [l (pool-limit st now parent-id)]
                                (str "budget exhausted (" l "); spawn refused")))]
              (if err
                [st {:error err}]
                (let [n      (inc (:seq p))
                      id     (str parent-id "/" def-name "." n)
                      merged (if budget (assoc d :budget (merge (:budget d) budget)) d)
                      child  (new-handle id merged (assoc p :seq n) now)]
                  [(-> st
                       (assoc-in [:handles parent-id :seq] n)
                       (update-in [:handles parent-id :children] conj id)
                       (assoc-in [:handles id] child)
                       (trace-in (str id ": spawned (depth " (:depth child)
                                      ", tokens " (fmt-num (:tokens (:pool child))) ")")))
                   {:id id :def merged}]))))) ]
     (if (:error outcome)
       outcome
       (let [{:keys [id def]} outcome]
         ;; `on-spawn` runs ONCE, before the first turn — the session-start
         ;; injection point. It may not take the runtime down: a persona whose
         ;; bootstrap file is missing must still be a live agent.
         (when-let [f (:on-spawn def)]
           (try (f rt id)
                (catch Throwable e
                  (transact! rt (fn [st]
                                  [(trace-in st (str id ": onSpawn error: "
                                                     (or (ex-message e) (str e))))
                                   nil])))))
         id)))))

;; ---------------------------------------------------------------------------
;; verb: post  (gate 1 — bounded inbox, loud)
;; ---------------------------------------------------------------------------

(defn post
  "Append an item to a handle's inbox. NO state transition — an inbox item is
  data waiting for a turn, not a trigger.

  Gate 1 is LOUD: at capacity the post is REJECTED SYNCHRONOUSLY to the sender.
  Silently dropping it (or growing without bound) are the two failure modes this
  gate exists to make impossible.

  `item` = `{:from \"root/coordinator.1\"|\"external\"|\"clock\"
             :channel \"peer\"|\"timer\"|\"external\" :text \"…\"}`.

  Returns `{:ok true}` or `{:ok false :error \"…\"}`."
  [rt id item]
  (transact!
   rt
   (fn [st]
     (let [h (h-of st id)]
       (cond
         (nil? h)                [st {:ok false :error (str "unknown handle: " id)}]
         (= "closed" (:state h)) [st {:ok false :error (str "inbox closed: " id)}]
         (>= (count (:inbox h)) (:inbox-cap rt))
         [(trace-in st (str id ": post REJECTED (inbox full, cap " (:inbox-cap rt)
                            ") from " (:from item)))
          {:ok false :error (str "inbox full: " id " (cap " (:inbox-cap rt) ")")}]
         :else
         [(update-in st [:handles id :inbox] conj item) {:ok true}])))))

;; ---------------------------------------------------------------------------
;; The turn
;; ---------------------------------------------------------------------------

(declare execute-turn! release-child-slot!)

(defn- admit-in
  "Gate 2's atomic admission: take the parent's concurrency slot, flip the state,
  arm a fresh cancellation cell. Pure — it runs INSIDE the verb's transaction,
  which is what makes 'admission is atomic with the verb' true rather than
  merely intended."
  [st id trace?]
  (let [h (h-of st id)]
    (cond-> st
      (:parent h) (update-in [:handles (:parent h) :running-children] inc)
      ;; `:budget-grant` is consumed HERE, by the admission it paid for: an
      ;; `"extend"` decision buys exactly one turn, so the host is asked again
      ;; before the next one and owns the loop bound.
      true        (update-in [:handles id] assoc :state "running" :cancel (atom nil)
                             :budget-grant false)
      trace?      (trace-in (str id ": idle→running (wake)")))))

(defn- queued?
  [st id]
  (let [h (h-of st id)
        p (when (:parent h) (h-of st (:parent h)))]
    (boolean (some #(= id (:h %)) (:wake-queue p)))))

(defn- toolkit-for
  "The handle's toolkit view — its own tools, plus the `task` tool IFF its def
  declares a team. Delegation, like recursion, is OPT-IN: an agent with no
  `:team` never sees `task` and therefore cannot spawn at all.

  No builtins: an agent's tool view IS its security model, so nothing arrives
  that the def did not ask for."
  [rt id def-map]
  (tool/toolkit (cond-> (vec (:tools def-map))
                  (seq (:team def-map)) (conj (task-tool rt id def-map)))))

(defn- stamp-path
  "Stamp this level's handle path onto the Request at `data.path` (§10 agent
  addendum). The path travels INSIDE `data`, never as a field grafted onto
  Request — §10's shape is closed, and the escalation is the one thing that must
  not fork it."
  [req id]
  (assoc req :data (assoc (or (:data req) {}) :path (vec (str/split id #"/")))))

(defn- wrap-wait-for
  "This handle's §10 interpreter authority, with the escalation traced. Nearest
  interpreter wins and the hop is strictly one level: a def with no `:wait-for`
  relays its child's Request upward untouched, and the root — with none either —
  returns `pending` to the host."
  [rt id def-map]
  (when-let [f (:wait-for def-map)]
    (fn [req]
      (let [p (get-in req [:data :path])
            path (if (sequential? p) (str/join "/" p) id)]
        (transact! rt (fn [st] [(trace-in st (str path ": escalate → " id
                                                  " answers (\"nearest interpreter\")"))
                                nil]))
        (f req)))))

(defn- build-client
  "The handle's §8 client. The runtime BUILDS it, which is precisely why `:hooks`
  and `:on-metric` have to be handed in: they are the only §8 seams that reach an
  agent run, and they resolve DEF-OVER-RUNTIME, REPLACE never merge, each field
  independently. Forwarded verbatim — never composed, wrapped, reordered, read.

  `:max-turns` is what is LEFT of the handle's lifetime cap, so a resumed handle
  cannot buy itself a fresh allowance."
  [rt id h one-shot-wait-for]
  (let [d    (:def h)
        llm  (or (:llm (:opts rt)) {})
        opts (:opts rt)]
    (client/create-client
     {:base-url      (or (:base-url llm) "http://mock.local")
      :style         (or (:style llm) "openai")
      :model         (if (or (nil? (:model d)) (= "inherit" (:model d)))
                       (or (:model llm) "inherit")
                       (:model d))
      :api-key       (or (:api-key llm) "unused-local")
      :system-prompt (when-not (str/blank? (str (:soul d))) (:soul d))
      :max-turns     (max 1 (- (get-in h [:eff :max-turns]) (:turns-total h)))
      :http-client   (gated-http-client rt (:cancel h))
      :store         (:store rt)
      :wait-for      (or one-shot-wait-for (wrap-wait-for rt id d))
      :hooks         (or (:hooks d) (:hooks opts))
      :on-metric     (or (:on-metric d) (:on-metric opts))})))

(defn- finish-turn!
  "Commit one turn's outcome: the transition + trace, the roll-up, the child
  slot, the settled result. Returns the result after delivering it to waiters."
  [rt id r]
  (let [outcome
        (transact!
         rt
         (fn [st]
           (let [h (h-of st id)
                 st (update-in st [:handles id] assoc :last-result r :waiters [] :drained [])]
             [st {:waiters (:waiters h)}])))]
    (deliver-all! (:waiters outcome) r)
    r))

(defn execute-turn!
  "ONE turn: the handle's client runs the §8 loop over its conversation.

  The caller has already admitted the handle and drained its inbox into `input`
  (both inside the verb's transaction), so what is left here is genuinely just
  the Run. Failures cross the handle boundary as RESULTS — never as exceptions —
  for the parent's model to judge.

  On a durable pending the stored transcript is REWOUND to its pre-turn
  snapshot: a persisted §10 placeholder would make the resumed parent believe it
  already delegated, and skip re-invoking `task`. Idempotency for delegated work
  comes from task-key reattachment, not from reading a transcript."
  [rt id input one-shot-wait-for]
  (let [st0      (deref (:state rt))
        h        (h-of st0 id)
        cancel   (:cancel h)
        store    (:store rt)
        pre-turn ((:get store) id)
        outcome
        (try
          (let [c  (build-client rt id h one-shot-wait-for)
                tk (toolkit-for rt id (:def h))
                r  (client/run c input {:toolkit tk :conversation-id id})]
            {:run r})
          (catch Throwable e {:err e}))]
    (if-let [r (:run outcome)]
      (let [tokens (get-in r [:usage :total-tokens] 0)
            calls  (or (:tool-call-count r) 0)
            final
            (transact!
             rt
             (fn [st]
               (let [st (-> st
                            (update-in [:handles id :turns-total] + (:turns r))
                            (roll-up id tokens calls))
                     h  (h-of st id)]
                 (cond
                   (and (= "pending" (:status r)) (:pending r))
                   (let [stamped (stamp-path (:pending r) id)]
                     [(-> st
                          (update-in [:handles id] assoc
                                     :state "suspended"
                                     :pending-request stamped
                                     :checkpoint {:input input}
                                     :drained [])
                          (trace-in (str id ": running→suspended (pending \""
                                         (:kind stamped) "\")")))
                      (assoc (result "pending" (:text r) false (:turns r) tokens)
                             :pending stamped)])

                   (= "incomplete" (:status r))
                   [(-> st
                        (assoc-in [:handles id :state] "idle")
                        (trace-in (str id ": running→idle (incomplete: "
                                       (or (:limit r) "maxTurns") ")")))
                    (result "incomplete" "hit maxTurns without a final answer" true
                            (:turns r) tokens)]

                   :else
                   [(-> st
                        (assoc-in [:handles id :state] "idle")
                        (trace-in (str id ": running→idle (done, turns=" (:turns r)
                                       ", tokens=" tokens ")")))
                    (result "done" (:text r) false (:turns r) tokens)]))))]
        (when (= "pending" (:status final))
          ;; Rewind AFTER the state commit, so an observer that sees `suspended`
          ;; can never read a transcript that still holds the halted turn.
          ((:save store) id (vec (or pre-turn []))))
        (release-child-slot! rt id)
        (finish-turn! rt id final))
      ;; --- the failure path ------------------------------------------------
      (let [e      (:err outcome)
            reason @cancel
            msg    (or (ex-message e) (str e))
            final
            (transact!
             rt
             (fn [st]
               (let [h (h-of st id)
                     ;; the drain is TRANSACTIONAL: an aborted turn puts the
                     ;; items it consumed back at the FRONT of the inbox
                     st (cond-> st
                          reason (update-in [:handles id :inbox]
                                            (fn [ib] (vec (concat (:drained h) ib))))
                          true   (assoc-in [:handles id :drained] [])
                          ;; an aborted Run must never resurrect a closed handle
                          (not= "closed" (:state h))
                          (assoc-in [:handles id :state] "idle"))]
                 [(trace-in st (str id ": running→idle ("
                                    (if reason "interrupted; inbox intact"
                                        (str "error: " msg)) ")"))
                  (result (cond (= "closed" reason) "closed"
                                reason              "interrupted"
                                :else               "error")
                          msg true (:turns-total h) (:usage-total h))])))]
        (release-child-slot! rt id)
        (finish-turn! rt id final)))))

(defn- start-turn!
  "Run a turn on its own thread. `koine.process/run-async!`, NEVER `future`:
  Clojure's future pool threads are non-daemon with a 60-second keep-alive, so
  one agent turn would hold a CONSUMER's process open for a minute after it
  finished. Measured at 61.6s vs 1.19s; there is a CI gate on it."
  [rt id input one-shot]
  (proc/run-async! (fn [] (execute-turn! rt id input one-shot)))
  nil)

;; ---------------------------------------------------------------------------
;; `onBudget` — the optional host budget callback (§7D)
;; ---------------------------------------------------------------------------

(declare wake)

(defn- on-budget-fn [rt] (:on-budget (:opts rt)))

(defn- budget-limit-of
  "The limit that would stop this handle's next turn, or nil. A one-shot
  `:budget-grant` from an `\"extend\"` decision suppresses the check for exactly
  one admission — otherwise `\"extend\"` would ask the host again forever without
  ever running the turn it granted."
  [st now id]
  (let [h (h-of st id)]
    (when-not (:budget-grant h)
      (or (pool-limit st now id) (turn-cap st id)))))

(defn- budget-info
  "What the host is told. Deliberately NOT money: §7D excludes monetary budgets
  from the library because price tables are vendor data — a host converts tokens
  to money here, which is the whole reason this callback exists."
  [st id limit prompt]
  (let [h (h-of st id)]
    {:handle     id
     :limit      limit
     :prompt     prompt
     :turns      (:turns-total h)
     :tokens     (:usage-total h)
     :tool-calls (:tool-calls-total h)
     :pool       (:pool h)}))

(defn- budget-stop!
  "The default: settle `incomplete` naming the limit. Identical to what a runtime
  with NO `:on-budget` does, plus one trace line recording that the host was
  asked — with no hook there is no decision to record, so the trace of a
  hook-less runtime is unchanged."
  [rt id limit]
  (let [outcome
        (transact! rt (fn [st]
                        (let [[st* text waiters] (settle-refused st id limit)]
                          [(trace-in st* (str id ": budget " limit " → stop"))
                           {:error text :deliver waiters
                            :result (:last-result (h-of st* id))}])))]
    (deliver-all! (:deliver outcome) (:result outcome))
    {:ok false :error (:error outcome)}))

(defn- budget-suspend!
  "Route the limit through §10 as an APPROVAL (`suspend`). The handle parks with
  a pending Request and only the Answer may move it: `ok` ⇒ the granted turn
  runs from the checkpoint (the 402 → top-up → resume story), not-ok ⇒ exactly
  the `incomplete` that `\"stop\"` would have settled.

  The transition recorded is `idle→suspended`. §7D's state graph writes
  `suspended` as reachable from `running`, because every suspension it
  contemplates comes OUT of a halted turn; a budget suspension happens BEFORE the
  turn, and tracing a `running` the handle never entered would be a lie in the one
  artifact conformance is measured on. `suspended → running only via the Answer`
  — the invariant that graph exists to state — is untouched."
  [rt id limit prompt]
  (let [req (client/make-request
             "approval"
             (str "budget exhausted (" limit "); approve to continue?")
             {:id (str id "#budget:" limit)
              :data {:limit limit :handle id}})
        outcome
        (transact!
         rt
         (fn [st]
           (let [[st text] (drain-in st id)]
             [(-> st
                  (update-in [:handles id] assoc
                             :state "suspended"
                             :pending-request (stamp-path req id)
                             :budget-pending limit
                             :checkpoint {:input (str (or prompt "") text)}
                             :drained [])
                  (trace-in (str id ": idle→suspended (budget " limit " → §10 approval)")))
              nil])))]
    outcome
    {:ok true}))

(defn- budget-decision!
  "Ask the host's `:on-budget` what to do about a limit that would stop this
  turn, and apply the answer. §7D pins the vocabulary — three strings:

    \"stop\"     settle `incomplete` naming the limit. What a runtime with no
                 hook always does
    \"extend\"   grant EXACTLY ONE more turn past the limit, then re-run the wake
                 verb so gate 2 and the queue are honoured unchanged. The pool
                 itself is not rewritten, so the host is asked again before the
                 next turn: the HOST owns the loop bound, never the runtime
    \"suspend\"  park on a §10 `approval` Request (see `budget-suspend!`)

  Anything else — nil, a typo, a thrown callback — is read as \"stop\". An
  unrecognised decision must never become a silent grant, which is the one way a
  budget hook could turn a bounded run into an unbounded one."
  [rt id limit prompt]
  (let [f (on-budget-fn rt)
        decision (try (str (f (budget-info @(:state rt) id limit prompt)))
                      (catch Throwable e
                        (transact! rt (fn [st]
                                        [(trace-in st (str id ": onBudget error: "
                                                           (or (ex-message e) (str e))))
                                         nil]))
                        "stop"))]
    (cond
      (= "extend" decision)
      (do (transact! rt (fn [st]
                          [(-> st
                               (assoc-in [:handles id :budget-grant] true)
                               (trace-in (str id ": budget " limit " → extend (one granted turn)")))
                           nil]))
          (wake rt id prompt))

      (= "suspend" decision) (budget-suspend! rt id limit prompt)
      :else                  (budget-stop! rt id limit))))

;; ---------------------------------------------------------------------------
;; verb: wake  (gate 2 — atomic admission, FIFO queue, slot transfer)
;; ---------------------------------------------------------------------------

(defn wake
  "`idle → running`. The turn's input is `prompt` plus the WHOLE drained inbox,
  coalesced into one block.

  Admission is ATOMIC with the verb: the budget walk, the concurrency slot, the
  state flip and the drain all commit together. Over the parent's
  `:max-concurrent` the wake QUEUES FIFO and a completing sibling transfers its
  slot — a queued wake is deferred, never dropped.

  Waking a `suspended` handle is a no-op: items buffer, and only the Answer to
  its pending Request may move it. Waking a `running` handle is a no-op too —
  the inbox drains on its next turn.

  Returns `{:ok true}` or `{:ok false :error \"…\"}` and does NOT block; use
  `wait` for the result."
  ([rt id] (wake rt id nil))
  ([rt id prompt]
   (let [now ((:now (:clock rt)))
         outcome
         (transact!
          rt
          (fn [st]
            (let [h (h-of st id)]
              (cond
                (nil? h)                   [st {:ok false :error (str "unknown handle: " id)}]
                (= "closed" (:state h))    [st {:ok false :error (str "closed: " id)}]
                (= "suspended" (:state h)) [st {:ok true}]
                (= "running" (:state h))   [st {:ok true}]
                :else
                (let [p (when (:parent h) (h-of st (:parent h)))]
                  (if (and p (>= (:running-children p) (get-in p [:eff :max-concurrent])))
                    [(-> st
                         (update-in [:handles (:id p) :wake-queue] conj {:h id :prompt prompt})
                         (trace-in (str id ": wake QUEUED (parent concurrency "
                                        (get-in p [:eff :max-concurrent]) ")")))
                     {:ok true}]
                    (if-let [limit (budget-limit-of st now id)]
                      ;; With an `:on-budget` hook the refusal is NOT committed
                      ;; here: the host has to be asked first, and a host callback
                      ;; may not run inside a transaction that can be retried.
                      (if (on-budget-fn rt)
                        [st {:ok false :budget-limit limit}]
                        (let [[st* text waiters] (settle-refused st id limit)]
                          [st* {:ok false :error text :deliver waiters
                                :result (:last-result (h-of st* id))}]))
                      (let [[st* text] (drain-in st id)]
                        [(admit-in st* id true)
                         {:ok true :start (str (or prompt "") text)}]))))))))]
     (when (:deliver outcome) (deliver-all! (:deliver outcome) (:result outcome)))
     (when (contains? outcome :start) (start-turn! rt id (:start outcome) nil))
     (if-let [limit (:budget-limit outcome)]
       (budget-decision! rt id limit prompt)
       (select-keys outcome [:ok :error])))))

(defn release-child-slot!
  "Gate 2's other half: free this Run's slot and TRANSFER it to queued sibling
  wakes, FIFO, re-checking budgets at dequeue time (a wake queued five minutes
  ago may be over budget by the time its slot arrives)."
  [rt id]
  (let [now ((:now (:clock rt)))
        outcome
        (transact!
         rt
         (fn [st]
           (let [h (h-of st id)
                 pid (:parent h)]
             (if-not pid
               [st {:starts [] :deliver [] :decisions []}]
               (loop [st (update-in st [:handles pid :running-children] dec)
                      starts [] deliver [] decisions []]
                 (let [p (h-of st pid)
                       q (:wake-queue p)]
                   (if (or (empty? q) (>= (:running-children p) (get-in p [:eff :max-concurrent])))
                     [st {:starts starts :deliver deliver :decisions decisions}]
                     (let [nx (first q)
                           st (assoc-in st [:handles pid :wake-queue] (vec (rest q)))
                           nh (h-of st (:h nx))]
                       (cond
                         (not= "idle" (:state nh)) (recur st starts deliver decisions)
                         :else
                         (if-let [limit (budget-limit-of st now (:h nx))]
                           ;; Same rule as `wake`: with a host hook the refusal is
                           ;; deferred out of the transaction, so a queued wake whose
                           ;; budget expired while it waited gets the same decision a
                           ;; direct wake would have.
                           (if (on-budget-fn rt)
                             (recur st starts deliver
                                    (conj decisions [(:h nx) limit (:prompt nx)]))
                             (let [[st* _ waiters] (settle-refused st (:h nx) limit)]
                               (recur st* starts
                                      (conj deliver [waiters (:last-result (h-of st* (:h nx)))])
                                      decisions)))
                           (let [[st* text] (drain-in st (:h nx))
                                 st* (-> st*
                                         (trace-in (str (:h nx) ": DEQUEUED wake (slot transferred)"))
                                         (admit-in (:h nx) true))]
                             (recur st* (conj starts [(:h nx) (str (or (:prompt nx) "") text)])
                                    deliver decisions))))))))))))]
    (doseq [[waiters r] (:deliver outcome)] (deliver-all! waiters r))
    (doseq [[cid input] (:starts outcome)] (start-turn! rt cid input nil))
    (doseq [[cid limit prompt] (:decisions outcome)] (budget-decision! rt cid limit prompt))
    nil))

;; ---------------------------------------------------------------------------
;; verb: wait
;; ---------------------------------------------------------------------------

(defn wait
  "Block until this handle's NEXT result — or answer immediately with its LAST
  one when it is already settled (idle with a recorded result, suspended with its
  pending, closed). Registration order is unobservable.

  `:timeout-ms` yields an explicit `timeout` result and the CHILD KEEPS RUNNING —
  a wait deadline is the waiter's deadline, never the child's. The timer goes
  through the injectable clock, so a fixture on a virtual clock produces the same
  trace every time.

  `:by` enforces the capability rule: only the spawner may wait on a handle."
  ([rt id] (wait rt id {}))
  ([rt id {:keys [timeout-ms by]}]
   (let [outcome
         (transact!
          rt
          (fn [st]
            (let [h (h-of st id)]
              (cond
                (nil? h)
                [st {:now (result "error" (str "unknown handle: " id) true 0 0)}]

                (and by (not= (:parent h) by))
                [st {:now (result "error" (str "wait refused: only the spawner may wait on " id)
                                  true (:turns-total h) (:usage-total h))}]

                (= "closed" (:state h))    [st {:now (or (:last-result h) (closed-result h))}]
                (= "suspended" (:state h)) [st {:now (pending-result h)}]
                (and (= "idle" (:state h)) (:last-result h) (not (queued? st id)))
                [st {:now (:last-result h)}]

                :else
                (let [p (promise)]
                  [(update-in st [:handles id :waiters] conj p) {:promise p}])))))]
     (if-let [r (:now outcome)]
       r
       (let [p (:promise outcome)
             cancel (when timeout-ms
                      ((:set-timeout (:clock rt))
                       (fn []
                         (let [h (h-of @(:state rt) id)]
                           (transact! rt (fn [st]
                                           [(update-in st [:handles id :waiters]
                                                       (fn [ws] (vec (remove #(= p %) ws))))
                                            nil]))
                           (deliver p (result "timeout"
                                              (str "wait timeout after " timeout-ms
                                                   "ms (child still " (:state h) ")")
                                              true (:turns-total h) (:usage-total h)))))
                       timeout-ms))
             r @p]
         (when cancel (cancel))
         r)))))

;; ---------------------------------------------------------------------------
;; verb: interrupt
;; ---------------------------------------------------------------------------

(defn interrupt
  "Abort the in-flight Run → `idle`, with the DRAINED INBOX ITEMS RESTORED. It is
  never a kill: the handle stays alive, its transcript intact, ready to be woken
  again. On a `suspended` handle it cancels the pending Request → `idle` — the
  operator's escape hatch from a suspension nobody is going to answer.

  Waiters receive a uniform `interrupted` result, never an exception.

  Landing is cooperative here (checked either side of each LLM round trip), so an
  interrupt takes effect at the next checkpoint rather than mid-socket. §7D says
  only abort LATENCY may differ between ports; the outcome does not."
  [rt id]
  (let [cancel
        (transact!
         rt
         (fn [st]
           (let [h (h-of st id)]
             (cond
               (nil? h) [st nil]
               (= "suspended" (:state h))
               [(-> st
                    (update-in [:handles id] assoc :state "idle"
                               :pending-request nil :checkpoint nil :budget-pending nil)
                    (trace-in (str id ": suspended→idle (interrupt cancelled pending \""
                                   (:kind (:pending-request h)) "\")")))
                nil]
               (= "running" (:state h)) [st (:cancel h)]
               :else [st nil]))))]
    (when cancel (reset! cancel "interrupted"))
    nil))

;; ---------------------------------------------------------------------------
;; verb: close
;; ---------------------------------------------------------------------------

(defn close
  "Graceful shutdown, LEAF-FIRST: stop accepting, close children first, let a
  running turn finish bounded by `:shutdown-ms` (then escalate to an abort), run
  `:on-close`, notify waiters, and KEEP THE FINAL STATE QUERYABLE.

  close ≠ loss. `inspect` still answers, `wait` still returns the recorded
  result, and a successor may be spawned from the checkpoint. Stop-all is
  `(close rt runtime/root)`.

  `{:force true}` skips the grace period and aborts immediately."
  ([rt id] (close rt id {}))
  ([rt id {:keys [force reason] :as opts}]
   (let [st0 (deref (:state rt))
         h0  (h-of st0 id)]
     (when (and h0 (not= "closed" (:state h0)))
       ;; leaf-first: a parent must never be closed while a child still runs
       (doseq [c (:children h0)] (close rt c opts))
       ;; take this handle out of any parent's wake queue before waiting
       (transact! rt (fn [st]
                       (let [h (h-of st id)
                             p (:parent h)]
                         [(if p
                            (update-in st [:handles p :wake-queue]
                                       (fn [q] (vec (remove #(= id (:h %)) q))))
                            st)
                          nil])))
       (when (= "running" (:state (h-of @(:state rt) id)))
         (if force
           (when-let [c (:cancel (h-of @(:state rt) id))] (reset! c "closed"))
           (let [bounded (wait rt id {:timeout-ms (:shutdown-ms rt)})]
             (when (and (= "timeout" (:status bounded))
                        (= "running" (:state (h-of @(:state rt) id))))
               (when-let [c (:cancel (h-of @(:state rt) id))] (reset! c "closed")))))
         (wait rt id))
       (let [h (h-of @(:state rt) id)
             r (or reason (if force "interrupted" "closed"))]
         (when-let [f (:on-close (:def h))]
           (try (f rt id r) (catch Throwable _ nil)))
         (let [outcome
               (transact!
                rt
                (fn [st]
                  (let [h (h-of st id)]
                    [(-> st
                         (update-in [:handles id] assoc :state "closed"
                                    :pending-request nil :checkpoint nil :waiters [])
                         (trace-in (str id ": " (:state h) "→closed (" r ")")))
                     {:waiters (:waiters h)
                      :result  (result "closed" "closed" true
                                       (:turns-total h) (:usage-total h))}])))]
           (deliver-all! (:waiters outcome) (:result outcome)))))
     nil)))

;; ---------------------------------------------------------------------------
;; §10 escalation and durable resume
;; ---------------------------------------------------------------------------

(defn- deepest-suspended
  "The DEEPEST suspended handle — depth-first, children before self. An Answer
  belongs to the level that actually asked, not to whichever ancestor happens to
  be relaying its Request."
  [st id]
  (let [h (h-of st id)]
    (or (some #(deepest-suspended st %) (:children h))
        (when (= "suspended" (:state h)) id))))

(declare resume-turn!)

(defn- resume-suspended!
  "Resume a suspended handle by REPLAYING its halted turn from the checkpoint.
  The stored transcript was rewound when the Run halted, so the whole turn
  re-runs with the same input — this time with a one-shot §10 interpreter that
  answers (when this level holds the Answer). A replayed `task` call REATTACHES
  to the existing child by task key.

  Two resume SHAPES, both spec'd, and the Answer is the only exit from
  `suspended` in either:

    inline   `suspended→running` — the Run never ended (a `task` retry carrying
             ctx.answer while the parent's turn is still on the stack)
    durable  `suspended→idle` (Answer accepted, checkpoint restored) then
             `idle→running` (the replay wake)"
  [rt id answer inline?]
  (if (and (:budget-pending (h-of @(:state rt) id)) answer (not (:ok answer)))
    ;; An `:on-budget` "suspend" that the host then DECLINED. The Answer is still
    ;; the only exit from `suspended`, and the outcome is exactly the `incomplete`
    ;; a "stop" decision would have settled — a declined top-up must not look
    ;; different from a refused one.
    (let [outcome
          (transact!
           rt
           (fn [st]
             (let [limit (:budget-pending (h-of st id))
                   st (update-in st [:handles id] assoc
                                 :pending-request nil :checkpoint nil :budget-pending nil)
                   [st* _ waiters] (settle-refused st id limit)]
               [(trace-in st* (str id ": suspended→idle (Answer ok=false, budget "
                                   limit " refused)"))
                {:deliver waiters :result (:last-result (h-of st* id))}])))]
      (deliver-all! (:deliver outcome) (:result outcome))
      (:result outcome))
    (resume-turn! rt id answer inline?)))

(defn- resume-turn!
  "The ordinary resume: restore the checkpoint and replay the halted turn."
  [rt id answer inline?]
  (let [outcome
        (transact!
         rt
         (fn [st]
           (let [h  (h-of st id)
                 cp (:checkpoint h)
                 st (update-in st [:handles id] assoc :pending-request nil :checkpoint nil
                               :budget-pending nil)
                 st (if inline?
                      (-> st
                          (update-in [:handles id] assoc :state "running" :cancel (atom nil))
                          (update-in [:handles (:parent h) :running-children] inc)
                          (trace-in (str id ": suspended→running (Answer ok="
                                         (if answer (boolean (:ok answer)) true) ")")))
                      (-> st
                          (assoc-in [:handles id :state] "idle")
                          (trace-in (str id ": suspended→idle (Answer accepted, checkpoint restored)"))
                          (admit-in id true)))
                 [st* text] (drain-in st id)]
             [st* {:input (str (or (:input cp) "continue") text)}])))]
    (execute-turn! rt id (:input outcome)
                   (when answer (fn [_req] answer)))))

(defn resume
  "Route an Answer to the DEEPEST suspended handle and cascade upward.

  The deepest handle resumes from its checkpoint (turns and usage GROW, never
  reset), then each suspended parent re-runs; the parent's re-invoked `task`
  reattaches to the child that already resumed and never spawns a duplicate.
  Parked levels burn zero tokens while they wait.

  Throws only when there is nothing suspended — the root is the one place §7D
  permits a throw to the host."
  [rt answer]
  (let [leaf (deepest-suspended @(:state rt) root)]
    (when-not leaf (throw (ex-info "no suspended handle to resume" {})))
    (transact! rt (fn [st]
                    [(trace-in st (str leaf ": resume with Answer(ok=" (boolean (:ok answer))
                                       ") at checkpoint (turns so far: "
                                       (:turns-total (h-of st leaf)) ")"))
                     nil]))
    (resume-suspended! rt leaf answer false)
    (loop [p (:parent (h-of @(:state rt) leaf))]
      (when (and p (not= p root) (= "suspended" (:state (h-of @(:state rt) p))))
        (transact! rt (fn [st] [(trace-in st (str p ": cascade resume (reattaching delegated work)")) nil]))
        (resume-suspended! rt p nil false)
        (recur (:parent (h-of @(:state rt) p)))))
    nil))

;; ---------------------------------------------------------------------------
;; The model surface: the `task` tool
;; ---------------------------------------------------------------------------

(defn- task-description [rt def-map]
  (let [reg  (:registry rt)
        team (vec (sort (:team def-map)))
        ads  (str/join "; " (map (fn [n] (if-let [d (get reg n)] (str n ": " (:does d)) n)) team))]
    (str "Delegate a subtask to an isolated subagent (it runs on a fresh transcript "
         "and returns only its final answer). Available agents — " ads)))

(def ^:private task-input-schema
  {:type       "object"
   :properties {:agent  {:type "string" :description "Team agent to delegate to"}
                :prompt {:type "string" :description "The subtask prompt"}}
   :required   ["agent" "prompt"]})

(defn task-tool
  "`task {agent, prompt}` = spawn→wake→wait→close, fused into one tool call.

  The child runs on a FRESH transcript and the parent gains exactly one tool
  message; the child's usage rolls up into the parent's. The description
  advertises ONLY the caller's team, sorted by name and composed from each
  agent's `:does` — an out-of-team target is an error that lists the team, never
  a silent reach into the registry.

  A re-invoked call REATTACHES to the existing child by task key (agent+prompt):
  settled ⇒ its recorded result, suspended ⇒ its pending (or an inline resume
  when the retry carries the Answer), running ⇒ await. Reattachment — not
  transcript inspection, not a completion cache — is the required idempotency
  mechanism, and it is what makes an upward resume cascade safe."
  [rt parent-id def-map]
  (let [team (vec (sort (:team def-map)))]
    (native/native-tool
     {:name         "task"
      :description  (task-description rt def-map)
      :input-schema task-input-schema
      :ctx?         true
      :run
      (fn [args ctx]
        (let [nm     (str (:agent args))
              prompt (str (:prompt args))]
          (if-not (some #(= nm %) team)
            (tool/failure (str "agent \"" nm "\" is not in this agent's team (available: "
                               (if (seq team) (str/join ", " team) "none") ")"))
            (let [key      (str nm ":" prompt)
                  existing (some (fn [c] (when (= key (:task-key (h-of @(:state rt) c))) c))
                                 (:children (h-of @(:state rt) parent-id)))
                  [child r]
                  (if existing
                    (do
                      (transact! rt (fn [st]
                                      [(trace-in st (str parent-id ": task replay → REATTACH to "
                                                         existing " (state "
                                                         (:state (h-of st existing)) ")"))
                                       nil]))
                      (let [h (h-of @(:state rt) existing)]
                        [existing
                         (cond
                           (= "suspended" (:state h))
                           (if-let [a (:answer ctx)]
                             (resume-suspended! rt existing a true)
                             (pending-result h))
                           (= "running" (:state h)) (wait rt existing {:by parent-id})
                           :else (or (:last-result h)
                                     (result "error" (str "no recorded result for " existing)
                                             true (:turns-total h) (:usage-total h))))]))
                    (let [spawned (spawn rt parent-id nm)]
                      (if (verb-error? spawned)
                        [nil spawned]
                        (do
                          (transact! rt (fn [st] [(assoc-in st [:handles spawned :task-key] key) nil]))
                          (let [woke (wake rt spawned prompt)]
                            (if-not (:ok woke)
                              [nil {:error (:error woke)}]
                              [spawned (wait rt spawned {:by parent-id})]))))))]
              (cond
                (nil? child) (tool/failure (:error r))

                ;; A suspending child presents to its parent EXACTLY as a
                ;; suspending tool — §10 verbatim, path already stamped. No new
                ;; pending type, which is the whole point of the escalation rule.
                (and (= "pending" (:status r)) (:pending r))
                (client/suspend (:pending r) (:prompt (:pending r)))

                :else
                (do
                  ;; …→CLOSE fused. The child's final state stays queryable, so a
                  ;; later reattach still answers from its recorded result.
                  (when (not= "closed" (:state (h-of @(:state rt) child)))
                    (close rt child))
                  {:output  (if (= "done" (:status r)) (:text r) (str "[" (:status r) "] " (:text r)))
                   :isError (boolean (:isError r))
                   :metadata {:agent nm :turns (:turns r) :total-tokens (:total-tokens r)}}))))))})))

;; ---------------------------------------------------------------------------
;; The axiom, both directions
;; ---------------------------------------------------------------------------

(defn run-agent
  "One-shot: spawn a handle for `def-name`, wake it with `prompt`, wait, close.
  The §7D Level-1 `.run(prompt)`.

  A `pending` result is NOT closed — a suspended handle still has an Answer
  coming, and closing it would discard the checkpoint that `resume` needs."
  ([rt def-name prompt] (run-agent rt root def-name prompt))
  ([rt parent-id def-name prompt]
   (let [h (spawn rt parent-id def-name)]
     (if (verb-error? h)
       (result "error" (:error h) true 0 0)
       (let [woke (wake rt h prompt)]
         (if-not (:ok woke)
           (result "error" (:error woke) true 0 0)
           (let [r (wait rt h)]
             (when-not (= "pending" (:status r)) (close rt h))
             r)))))))

(defn agent-tool
  "The axiom's other direction: an AgentDef AS a Tool, droppable into any
  toolkit's `:tools`.

  `{name, description: does, inputSchema:{prompt}, execute: run its loop}` —
  returning ONLY the agent's final text plus `{:agent :turns :total-tokens}`.
  The caller sees a tool; what is behind it is a whole agent with its own soul,
  its own tool view and its own budget, and it cannot tell the difference. That
  is §7A/§7B's symmetry closed locally."
  [rt def-name]
  (let [d (get (:registry rt) def-name)]
    (native/native-tool
     {:name         (tool/sanitize def-name)
      :description  (or (:does d) "")
      :input-schema {:type "object"
                     :properties {:prompt {:type "string" :description "The task"}}
                     :required ["prompt"]}
      :run
      (fn [args]
        (let [r (run-agent rt def-name (str (:prompt args)))]
          (if (= "pending" (:status r))
            (client/suspend (:pending r) (:prompt (:pending r)))
            {:output  (if (= "done" (:status r)) (:text r) (str "[" (:status r) "] " (:text r)))
             :isError (boolean (:isError r))
             :metadata {:agent def-name :turns (:turns r) :total-tokens (:total-tokens r)}})))})))
