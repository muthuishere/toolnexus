;; Loop — a live execution of an agent, and the completion gate that stops it
;; claiming `done` too early. A layer over the shipped §8 client: nothing here
;; changes existing behaviour.
;;
;; The placement law this encodes:
;;
;;   the agent def (the harness) answers "MAY it?"      — capability, ceilings.  Per problem.
;;   run opts                    answers "with WHAT?"   — model for this call.   Per call.
;;   the loop                    answers "DID it?"      — status, turns.         Observed.
;;   none of them                answers "is it RIGHT?" — a tool, skill or agent.
;;
;; So a loop takes no options: it is read, not configured.
;;
;; A loop is a plain MAP, and `run` returns `[outcome loop']`. There is no mutable
;; handle because there does not need to be one, and a value threads identically on
;; both hosts.
;;
;; No java.*, no Go interop, zero reader conditionals.
(ns toolnexus.agents.loop
  (:require [clojure.string :as str]
            [toolnexus.client :as client]))

(defn harness
  "`harness` is a NAME, not a type.

  An agent def already IS the harness — tools, soul, team, budget, model, policy,
  ceilings — so this is the word landing in the API without a second concept to
  learn. A def built through `harness` and one written inline are indistinguishable."
  [spec]
  spec)

(defn guarded-hooks
  "Compile guardrails into one `:before-tool` with FIRST-DENY-WINS, composed ahead
  of any hook already set. A guardrail returns `\"allow\"` (or nil) to permit; any
  other string DENIES with that reason.

  No guardrails ⇒ `hooks` is returned untouched, so absent is byte-identical."
  [guardrails hooks]
  (if (empty? guardrails)
    hooks
    (let [prior (:before-tool hooks)]
      (assoc (or hooks {})
             :before-tool
             (fn [ev]
               (let [denial (some (fn [rail]
                                    (let [v (rail ev)]
                                      (when (and (string? v) (seq v) (not= v "allow")) v)))
                                  guardrails)]
                 (cond
                   denial {:result {:output (str "denied: " denial) :is-error true}}
                   prior  (prior ev)
                   :else  nil)))))))

(defn all-todos-done
  "The built-in completion verifier. Reads the SHIPPED `todowrite` builtin's result
  metadata and requires every item to be checked.

  Structural, not domain: it counts unchecked boxes and never learns what a todo
  means, so the loop stays domain-blind. No plan declared ⇒ nothing to verify ⇒
  pass, so the gate never punishes an agent that does not use the builtin."
  [result]
  (let [last-todo (->> (:tool-calls result)
                       (filter #(= "todowrite" (:name %)))
                       last)]
    (if (nil? last-todo)
      {:ok true}
      (let [todos (get-in last-todo [:metadata :todos])]
        (if-not (sequential? todos)
          {:ok true}
          (let [open (->> todos
                          (remove #(true? (:completed %)))
                          (mapv #(str (:text %))))]
            (if (seq open)
              {:ok false
               :reason (str (count open) " item(s) still open: " (str/join "; " open))}
              {:ok true})))))))

(defn run-gated
  "Wrap a client run with the completion gate. SHARED by the standalone loop and the
  §7D runtime turn, so a delegated child gets exactly the same guarantee as a
  directly-driven one.

  `ask` takes `[prompt state]` and returns `[result state']`, which is what lets one
  implementation serve both the value-threading loop and the runtime's turn.

  Rule 2 in force: a run that is `pending` (suspended on a human) or otherwise
  non-done already carries its own reason, so the gate never re-judges it. That
  keeps `pending` and `incomplete` distinct — the caller can always tell whether it
  owes an Answer or a fix."
  [ask prompt completion state]
  (if (nil? completion)
    (ask prompt state)
    (let [max-attempts (:max-attempts completion)
          verify       (:verify completion)]
      (when-not (and (integer? max-attempts) (pos? max-attempts))
        (throw (ex-info "toolnexus: completion :max-attempts must be an integer >= 1"
                        {:max-attempts max-attempts})))
      (when-not (fn? verify)
        (throw (ex-info "toolnexus: completion :verify is required" {})))
      (loop [attempt 1, acc [], reason "", state state, last nil]
        (if (> attempt max-attempts)
          ;; Structured, not prose: `:limit` is how a caller (and the §7D runtime)
          ;; tells WHICH limit stopped the run; `:text` carries the human reason.
          [(assoc last
                  :status "incomplete"
                  :limit "completion"
                  :text (str "completion.verify failed " max-attempts "x: " reason))
           state]
          (let [p (if (= attempt 1)
                    prompt
                    (str "Your work did not verify: " reason ". Fix it and finish."))
                [r state] (ask p state)
                ;; The gate judges the ACCUMULATED work, so an agent cannot escape it
                ;; by declining to re-declare its plan on a retry.
                acc (into acc (:tool-calls r))
                r   (assoc r :tool-calls acc :tool-call-count (count acc))]
            (if (and (:status r) (not= "done" (:status r)))
              ;; The run stopped for its own reason (suspension, budget). If the gate
              ;; was mid-retry the caller must learn BOTH — otherwise a budget stop
              ;; masks the verification failure and they never see why it was looping.
              [(if (and (seq reason) (not= "pending" (:status r)))
                 (assoc r :text (str (:text r) " [while verifying: attempt " attempt
                                     " last failed: " reason "]"))
                 r)
               state]
              (let [v (verify r)]
                (if (:ok v)
                  [r state]
                  (recur (inc attempt) acc
                         (if (seq (str (:reason v))) (:reason v) "unspecified")
                         state r))))))))))

(defn create
  "Open a LIVE EXECUTION of an agent def, over client OPTIONS (not a built client)
  — because a per-call `:model` override must be able to change the model, which is
  fixed when a client is constructed."
  [agent-def options toolkit]
  {:def agent-def :options options :toolkit toolkit
   :status "idle" :turns 0 :history []})

(defn- client-options
  "Apply a per-call model override via `:request-params` (`model` is not in the
  forbidden set — the client forbids only messages/tools/stream)."
  [lp model]
  (let [d    (:def lp)
        opts (:options lp)
        opts (if (and (:soul d) (not (:system-prompt opts)))
               (assoc opts :system-prompt (:soul d))
               opts)
        opts (assoc opts :hooks (guarded-hooks (:guardrails d) (or (:hooks d) (:hooks opts))))]
    (if (and model (seq (str model)))
      (assoc opts :request-params (assoc (or (:request-params opts) {}) "model" model))
      opts)))

(defn run
  "Run one request. Returns `[outcome loop']` — the loop carries the turn count and
  the transcript forward, so a caller may run again on the same conversation.

  `opts` may carry `:model`, which applies to THIS CALL ONLY."
  ([lp prompt] (run lp prompt {}))
  ([lp prompt opts]
   (let [c          (client/create-client (client-options lp (:model opts)))
         completion (:completion (:def lp))
         ask (fn [text state]
               (let [r (client/run c text {:toolkit (:toolkit lp)
                                           :history (:history state)})]
                 [r (-> state
                        (update :attempts inc)
                        (update :turns + (or (:turns r) 0))
                        (assoc :history (:messages r)))]))
         [r state] (run-gated ask prompt completion
                              {:attempts 0 :turns (:turns lp) :history (:history lp)})
         lp        (assoc lp :turns (:turns state) :history (:history state))
         status    (or (:status r) "done")]
     (if (not= "done" status)
       [{:text (:text r) :status status
         :stopped-by (if (= "completion" (:limit r))
                       (:text r)
                       (str "run reported " status))
         :attempts (:attempts state) :turns (:turns state) :result r}
        (assoc lp :status status)]
       [{:text (:text r) :status "done" :stopped-by nil
         :attempts (:attempts state) :turns (:turns state) :result r}
        (assoc lp :status "idle")]))))
