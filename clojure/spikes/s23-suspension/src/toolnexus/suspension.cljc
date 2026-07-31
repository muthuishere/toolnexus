;; S23 — SPEC §0.12 / §10: suspension (Pending / waitFor) on both hosts.
;;
;; The question: does the thing that lets a tool STOP and ask a human work in
;; portable Clojure, identically on Clojure (JVM) and on cljgo?
;;
;; §10 is the one part of the contract whose reference wording is explicitly
;; about ASYNC ("idiomatic async per port — JS Promise, Go blocking + ctx,
;; Python coroutine, Java CompletableFuture, C# Task"). Clojure has no
;; async/await and no function colouring, so this spike is also the place to
;; find out what §10 costs, or saves, in a language that never had the problem.
;;
;; Everything measured here is exercised for real: the "LLM" is a koine.server
;; on 127.0.0.1 replaying a canned OpenAI-shaped script, and the A2A leg is a
;; second local server that fulfils an inbound task from a suspended run.
;;
;; Zero reader conditionals, zero java.*, zero Go interop.

(ns toolnexus.suspension
  (:require [clojure.string :as str]
            [koine.host :as host]
            [koine.http :as http]
            [koine.json :as json]
            [koine.server :as server]
            [koine.time :as ktime]))

;; ---------------------------------------------------------------------------
;; §10  Request / Answer — byte-identical wire data
;; ---------------------------------------------------------------------------
;;
;;   Request { id, kind, prompt, url?, data?, expiresAt? }
;;   Answer  { id, ok, data?, reason? }
;;
;; SPEC: "Request/Answer keys are FIXED across all ports ... pinned exactly as
;; above, *not* idiomatic-cased like RunResult." In Clojure that means the map
;; keys must be :expiresAt and :isError — camelCase keywords in a language whose
;; whole convention is kebab-case. koine.json/write-str emits a keyword's name
;; verbatim, so the spelling of the keyword IS the wire format. There is no
;; renaming layer to hide behind, and no reader conditional involved.

(def ^:private seq-counter (atom 0))

(defn- new-id
  "Unique per suspension; the correlation key. Deliberately non-deterministic —
  it never reaches the report as a value."
  []
  (str "sus-" (ktime/now-ms) "-" (swap! seq-counter inc)))

(defn make-request
  "§10 Request. Optional keys are OMITTED when absent (url?, data?, expiresAt?),
  never emitted as null."
  ([kind prompt] (make-request kind prompt {}))
  ([kind prompt opts]
   (merge {:id (new-id) :kind kind :prompt prompt}
          (select-keys opts [:url :data :expiresAt]))))

(defn make-answer
  "§10 Answer. `reason` (R1) is populated ONLY when ok == false."
  ([id ok] (make-answer id ok nil nil))
  ([id ok data reason]
   (cond-> {:id id :ok ok}
     (some? data)               (assoc :data data)
     (and (not ok) (some? reason)) (assoc :reason reason))))

(defn- wire
  "The wire shape with the two non-deterministic values redacted, so the JSON can
  be diffed byte-for-byte across hosts without leaking an id or a timestamp."
  [m]
  (cond-> m
    (contains? m :id)        (assoc :id "<id>")
    (contains? m :expiresAt) (assoc :expiresAt "<rfc3339>")))

(defn- key-names [m] (vec (sort (map name (keys m)))))

;; ---------------------------------------------------------------------------
;; §10  Pending rides on ToolResult — no signature change
;; ---------------------------------------------------------------------------

(defn suspend
  "A ToolResult whose metadata.pending is a Request IS a suspension (§0.12)."
  [request output]
  {:output output :isError true :metadata {:pending request}})

(defn suspension-of
  "The Request iff this ToolResult is a suspension, else nil."
  [result]
  (get-in result [:metadata :pending]))

(defn auth-required
  "§10 sugar: kind:\"authorization\" + url + a generated id."
  [url prompt]
  (suspend (make-request "authorization" prompt {:url url})
           (str "Login required: " url)))

;; ---------------------------------------------------------------------------
;; §4A  the `question` builtin's rendered prompt — byte-identical across ports
;; ---------------------------------------------------------------------------

(defn render-questions
  "Each question's text in order, \" (options: a, b, c)\" appended when it has
  non-empty options, joined by \\n. `header` is NOT rendered."
  [questions]
  (str/join "\n"
            (mapv (fn [q]
                    (let [opts (:options q)]
                      (str (:question q)
                           (when (seq opts)
                             (str " (options: " (str/join ", " opts) ")")))))
                  questions)))

;; ---------------------------------------------------------------------------
;; the suspending tools
;; ---------------------------------------------------------------------------
;;
;; execute is (fn [args ctx]) — ctx.answer is present ONLY on a post-waitFor
;; retry (§1 Context).

(defn- tool [nm description f]
  {:name nm :description description :inputSchema {:type "object"}
   :source "custom" :execute f})

(def tools
  [;; kind:"authorization" — the tool IGNORES answer.data; the world changed
   ;; out-of-band and the session is simply valid now.
   (tool "login" "Log in to the fake service"
         (fn [_args ctx]
           (if (get-in ctx [:answer :ok])
             {:output "session valid" :isError false}
             (auth-required "https://127.0.0.1/authorize" "Log in to continue"))))

   ;; kind:"input" — the resolution IS the payload, and R2 data.schema rides
   ;; under `data` so a generic host can render/validate without bespoke glue.
   (tool "ask_city" "Ask the human for a city"
         (fn [_args ctx]
           (if-let [ans (:answer ctx)]
             {:output (json/write-str (:data ans)) :isError false}
             (suspend (make-request
                        "input" "Which city?"
                        {:data      {:schema {:type "object"
                                              :properties {:city {:type "string"}}
                                              :required ["city"]}}
                         :expiresAt (ktime/iso-str (+ (ktime/now-ms) 300000))})
                      "Input required: Which city?"))))

   ;; kind:"question" — §4A's canonical producer. Returns answer.data verbatim.
   (tool "question" "Ask the human structured questions"
         (fn [_args ctx]
           (let [qs [{:question "Ship it?" :header "release" :options ["yes" "no"]}
                     {:question "Any notes?"}]]
             (if-let [ans (:answer ctx)]
               {:output (json/write-str (:data ans)) :isError false}
               (suspend (make-request "question" (render-questions qs)
                                      {:data {:questions qs}})
                        (str "Question: " (render-questions qs)))))))

   ;; suspends again even on the retry — the "never loop forever" path.
   (tool "stubborn" "Suspends forever"
         (fn [_args _ctx]
           (suspend (make-request "approval" "Approve the impossible")
                    "Approval required")))])

(def toolkit (reduce (fn [acc t] (assoc acc (:name t) t)) {} tools))

(defn- call-tool [tk nm args ctx]
  (if-let [t (get tk nm)]
    ((:execute t) args ctx)
    {:output (str "unknown tool: " nm) :isError true}))

;; ---------------------------------------------------------------------------
;; §10  the loop rule — the one behavioral pin
;; ---------------------------------------------------------------------------
;;
;; The host slot is `wait-for`: data -> data, (fn [request] answer). SPEC:
;; "Named waitFor, NEVER await — await is a reserved word in JS/Python/C#."
;; In Clojure `await` is a real clojure.core fn (agents), so naming the slot
;; `await` would ALSO be wrong here, for a different reason: BRIEF rule 3 says a
;; def that shadows a clojure.core name can make cljgo's interop scan reject the
;; whole namespace. Two languages, two reasons, same answer.

(defn resolve-call
  "One tool call under the §10 loop rule. Returns
  {:result r}                   — resolved (or a non-suspending call)
  {:halt request :placeholder r} — suspended with no wait-for configured
  plus the observability :event and any streaming :emits."
  [tk call wait-for]
  (let [r1  (call-tool tk (:name call) (:args call) {})
        req (suspension-of r1)]
    (if-not req
      {:result r1
       :event  {:tool (:name call) :isError (boolean (:isError r1)) :pending false}}
      ;; §10: "A suspension is never a tool error" — the observability event
      ;; carries isError:false + pending:true even though the ToolResult itself
      ;; has isError:true, so error-rate metrics do not count it.
      (let [event {:tool (:name call) :isError false :pending true}
            ;; §10 streaming: emitted BEFORE wait-for runs, so a channel handler
            ;; can push the link in real time.
            emits [{:type "pending" :request req}]]
        (if-not wait-for
          {:halt req :placeholder r1 :event event :emits emits}
          (let [ans (wait-for req)]
            (if (:ok ans)
              (let [r2 (call-tool tk (:name call) (:args call) {:answer ans})]
                (if (suspension-of r2)
                  ;; a SECOND suspension — feed back an error, never loop.
                  {:result {:output (str "unresolved: " (:prompt req)) :isError true}
                   :event event :emits emits :answer ans}
                  {:result r2 :event event :emits emits :answer ans}))
              {:result {:output (str "declined/expired: " (:prompt req)) :isError true}
               :event event :emits emits :answer ans})))))))

(defn- openai-tool-calls [msg]
  (mapv (fn [tc] {:id   (:id tc)
                  :name (get-in tc [:function :name])
                  :args (json/read-str (or (get-in tc [:function :arguments]) "{}"))})
        (:tool_calls msg)))

(defn run-loop
  "§0.10 + §10. Returns a RunResult {status, pending?, text, ...}.
  status is \"done\" | \"pending\"."
  [tk base-url wait-for max-turns]
  (loop [messages  [{:role "system" :content "s23"} {:role "user" :content "go"}]
         turn      1
         events    []
         emitted   []
         outputs   []
         answers   []]
    (let [res  (http/post-json (str base-url "/v1/chat/completions")
                               {"content-type" "application/json"}
                               (json/write-str {:model "mock" :messages messages}))
          body (json/read-str (:body res))
          msg  (get-in body [:choices 0 :message])
          tcs  (openai-tool-calls msg)]
      (cond
        (empty? tcs)
        {:status "done" :turns turn :text (:content msg)
         :transcript (mapv :role messages) :toolEvents events
         :streamEvents emitted :outputs outputs :answers answers}

        (>= turn max-turns)
        {:status "incomplete" :limit "maxTurns" :turns turn
         :transcript (mapv :role messages) :toolEvents events
         :streamEvents emitted :outputs outputs :answers answers}

        :else
        ;; Each call is resolved in tool-call ORDER, so concurrent suspensions
        ;; surface deterministically (§10) rather than by scheduling.
        (let [done (mapv (fn [c] (assoc c :res (resolve-call tk c wait-for))) tcs)
              ev   (into events (mapv (comp :event :res) done))
              em   (into emitted (mapcat (fn [d] (or (:emits (:res d)) [])) done))
              ans  (into answers (keep (fn [d] (:answer (:res d))) done))
              halt (first (filter (fn [d] (:halt (:res d))) done))]
          (if halt
            ;; §10 durable halt: append the FIRST halted tool's placeholder
            ;; result to the transcript, then return pending. Later concurrent
            ;; suspensions' placeholders never enter — they re-suspend on resume.
            (let [msgs (conj (conj messages msg)
                             {:role "tool" :tool_call_id (:id halt)
                              :content (:output (:placeholder (:res halt)))})]
              {:status "pending" :pending (:halt (:res halt)) :turns turn
               :transcript (mapv :role msgs) :toolEvents ev
               :streamEvents em :outputs outputs :answers ans})
            (recur (into (conj messages msg)
                         (mapv (fn [d] {:role "tool" :tool_call_id (:id d)
                                        :content (:output (:result (:res d)))})
                               done))
                   (inc turn) ev em
                   (into outputs (mapv (fn [d] (:output (:result (:res d)))) done))
                   ans)))))))

;; ---------------------------------------------------------------------------
;; the scripted LLM — a real local server, not a stub
;; ---------------------------------------------------------------------------

(defn- call-msg [calls]
  {:choices [{:message {:role "assistant" :content nil
                        :tool_calls (mapv (fn [c]
                                            {:id (str "call_" (:name c)) :type "function"
                                             :function {:name (:name c) :arguments "{}"}})
                                          calls)}}]})

(defn- text-msg [t] {:choices [{:message {:role "assistant" :content t}}]})

(defn- script->response [script n]
  (let [step (get script (dec n))]
    (if (and step (seq step)) (call-msg step) (text-msg "done"))))

(defn scripted-llm!
  "Serves POST /v1/chat/completions from `script` — a vector of turns, each a
  vector of {:name tool-name} calls; anything past the end answers with text."
  [script]
  (let [n (atom 0)]
    (server/serve (fn [_req]
                    {:status  200
                     :headers {"content-type" "application/json"}
                     :body    (json/write-str (script->response script (swap! n inc)))})
                  {:port 0})))

(defn- with-llm [script f]
  (let [srv (scripted-llm! script)]
    (try (f (str "http://127.0.0.1:" (server/port srv)))
         (finally (server/stop! srv)))))

;; ---------------------------------------------------------------------------
;; the four wait-for hosts
;; ---------------------------------------------------------------------------

(defn wait-for-ok
  "An in-process host slot. Blocking, synchronous, data -> data. That is the
  WHOLE of the async story in Clojure — see the README."
  [request]
  (make-answer (:id request) true
               (case (:kind request)
                 "input"    {:city "Chennai"}
                 "question" {:answers ["yes" "ship on friday"]}
                 nil)
               nil))

(defn wait-for-declined [request]
  (make-answer (:id request) false nil "declined"))

;; ---------------------------------------------------------------------------
;; §7B  suspension crossing A2A
;; ---------------------------------------------------------------------------
;;
;; "A run that halts with status:pending and is being fulfilled as an inbound
;; A2A task MUST surface input-required (carrying pending.prompt in the task's
;; status message) — never a completed task."

(defn a2a-task
  "RunResult -> A2A task object."
  [rr]
  (if (= "pending" (:status rr))
    {:state "input-required"
     :status {:message {:role "agent"
                        :parts [{:kind "text" :text (get-in rr [:pending :prompt])}]}}}
    {:state "completed"
     :status {:message {:role "agent"
                        :parts [{:kind "text" :text (:text rr)}]}}}))

(defn a2a-serve!
  "An inbound A2A endpoint that fulfils a task by running the loop with NO
  wait-for, so the suspension must cross the protocol boundary as data."
  [llm-base]
  (server/serve (fn [_req]
                  (let [rr (run-loop toolkit llm-base nil 10)]
                    {:status  200
                     :headers {"content-type" "application/json"}
                     :body    (json/write-str {:jsonrpc "2.0" :id 1
                                               :result (a2a-task rr)})}))
                {:port 0}))

;; ---------------------------------------------------------------------------
;; the six modes
;; ---------------------------------------------------------------------------

(defn- summarize
  "Report OUTCOMES and KEY SETS only — never an id, never a timestamp."
  [rr]
  (cond-> {:status       (:status rr)
           :turns        (:turns rr)
           :transcript   (:transcript rr)
           :toolEvents   (:toolEvents rr)
           :streamEvents (mapv (fn [e] {:type    (:type e)
                                        :reqKeys (key-names (:request e))
                                        :kind    (:kind (:request e))})
                               (:streamEvents rr))
           :outputs      (:outputs rr)
           :answerWire   (mapv (fn [a] (wire a)) (:answers rr))
           :answerKeys   (mapv key-names (:answers rr))}
    (:text rr)    (assoc :text (:text rr))
    (:pending rr) (assoc :pendingKeys (key-names (:pending rr))
                         :pendingKind (:kind (:pending rr))
                         :pendingWire (wire (:pending rr)))))

(defn run-slice []
  (let [;; --- A. waitFor ok -> re-execute ONCE with Context.answer -----------
        mode-ok
        (with-llm [[{:name "login"} {:name "ask_city"} {:name "question"}]]
          (fn [base] (summarize (run-loop toolkit base wait-for-ok 10))))

        ;; --- B. waitFor ok=false -> "declined/expired: <prompt>" ------------
        mode-declined
        (with-llm [[{:name "login"}]]
          (fn [base] (summarize (run-loop toolkit base wait-for-declined 10))))

        ;; --- C. the retry suspends AGAIN -> "unresolved: <prompt>" ----------
        mode-double
        (with-llm [[{:name "stubborn"}]]
          (fn [base] (summarize (run-loop toolkit base wait-for-ok 10))))

        ;; --- D. NO waitFor -> the run RETURNS, it does not hang -------------
        ;; Bounded: if `run-loop` blocked instead of returning, this elapsed
        ;; measurement would never be taken at all. The boolean, not the
        ;; duration, is what goes in the report.
        t0        (ktime/mono-ms)
        rr-no-wf  (with-llm [[{:name "login"}]]
                    (fn [base] (run-loop toolkit base nil 10)))
        returned? (< (ktime/elapsed-ms t0) 5000)

        ;; --- E. concurrent suspensions, no waitFor -> FIRST in call order ---
        mode-concurrent
        (with-llm [[{:name "stubborn"} {:name "login"} {:name "ask_city"}]]
          (fn [base] (summarize (run-loop toolkit base nil 10))))

        ;; --- F. the same halt, crossing A2A ---------------------------------
        a2a
        (with-llm [[{:name "login"}]]
          (fn [base]
            (let [srv (a2a-serve! base)]
              (try
                (let [res  (http/post-json (str "http://127.0.0.1:" (server/port srv) "/")
                                           {"content-type" "application/json"}
                                           (json/write-str {:jsonrpc "2.0" :id 1
                                                            :method "message/send"}))
                      task (get-in (json/read-str (:body res)) [:result])]
                  {:state    (:state task)
                   :promptCarried (= "Log in to continue"
                                    (get-in task [:status :message :parts 0 :text]))
                   :completedReported (= "completed" (:state task))})
                (finally (server/stop! srv))))))]

    {:host (name host/id)
     :spec {:requestKeys (key-names (make-request "authorization" "p" {:url "u"}))
            :answerKeysOk (key-names (make-answer "x" true {:a 1} nil))
            :answerKeysNotOk (key-names (make-answer "x" false nil "declined"))
            ;; R1: reason is populated ONLY when ok == false.
            :reasonOnOk (contains? (make-answer "x" true nil "declined") :reason)
            ;; the slot is named waitFor (`wait-for` as a local); `await` is BOTH
            ;; a JS/Python/C# reserved word AND a clojure.core fn, so it is
            ;; doubly wrong — see the README.
            :slotName "waitFor"
            ;; R2: data.schema on a kind:"input" Request.
            :inputSchemaKeys (key-names (get-in (suspension-of
                                                  ((:execute (get toolkit "ask_city")) {} {}))
                                                [:data :schema]))
            :renderedQuestions (render-questions
                                 [{:question "Ship it?" :header "h" :options ["yes" "no"]}
                                  {:question "Any notes?"}])}
     :modes {:waitForOk        mode-ok
             :waitForDeclined  mode-declined
             :doubleSuspension mode-double
             :noWaitFor        (assoc (summarize rr-no-wf) :returnedNotBlocked returned?)
             :concurrent       mode-concurrent
             :a2a              a2a}}))

(defn -main [& _]
  (println (json/write-str (run-slice))))
