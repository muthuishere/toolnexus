;; The unified LLM client — SPEC §0.10 (the loop), §8 (parallel tool calls,
;; RunResult telemetry) and §0.12 / §10 (suspension).
;;
;; Give it a base URL + a "style" and it runs the whole tool-calling agent loop
;; against a `toolnexus.tool` toolkit:
;;
;;   system = systemPrompt + "\n\n" + skillsPrompt
;;   call the endpoint -> execute the tool calls -> feed the results back in the
;;   provider's tool-result shape -> repeat, bounded by max-turns (default 10).
;;
;; Both styles are implemented and both are measured: "openai" posts to
;; {base}/chat/completions and feeds results back as {role:"tool"} messages;
;; "anthropic" posts to {base}/v1/messages and feeds them back as a `user`
;; message of `tool_result` content blocks. The two wire shapes are different
;; enough that only the second one being exercised makes "it composes" a claim
;; rather than a measurement.
;;
;; Casing, deliberately (§10 + spike S23 finding 3): `Request`/`Answer`/
;; `ToolResult` keys are PINNED across the ports because they cross the wire,
;; and `koine.json/write-str` emits a keyword's name verbatim — the keyword IS
;; the wire key. So `:isError` and `:expiresAt` appear literally in this file,
;; camelCase, in a language whose whole convention is kebab-case. There is no
;; renaming layer and there must not be one: a rename here would be exactly the
;; cross-port drift the ports exist to prevent. Everything NOT on the wire
;; (client options, `RunResult`) is idiomatic kebab-case, which is what §8 asks
;; for ("idiomatic-cased like RunResult").
;;
;; Zero reader conditionals, zero java.*, zero Go interop.
(ns toolnexus.client
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.http :as http]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.adapter :as adapter]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; §10  Request / Answer — byte-identical wire data
;; ---------------------------------------------------------------------------
;;
;;   Request { id, kind, prompt, url?, data?, expiresAt? }
;;   Answer  { id, ok, data?, reason? }
;;
;; Optional keys are OMITTED when absent, never emitted as null.

(def ^:private id-counter (atom 0))

(defn new-request-id
  "A unique correlation key for one suspension."
  []
  (str "sus-" (ktime/now-ms) "-" (swap! id-counter inc)))

(defn make-request
  "§10 `Request`. `opts` may carry :url, :data (incl. R2's `data.schema`) and
  :expiresAt (RFC3339)."
  ([kind prompt] (make-request kind prompt {}))
  ([kind prompt opts]
   (merge {:id (or (:id opts) (new-request-id)) :kind (str kind) :prompt (str prompt)}
          (select-keys opts [:url :data :expiresAt]))))

(defn make-answer
  "§10 `Answer`. R1: `reason` is populated ONLY when ok == false; the loop rule
  branches on `ok` alone and never reads it."
  ([id ok] (make-answer id ok nil nil))
  ([id ok data] (make-answer id ok data nil))
  ([id ok data reason]
   (cond-> {:id id :ok (boolean ok)}
     (some? data)                  (assoc :data data)
     (and (not ok) (some? reason)) (assoc :reason reason))))

(defn suspend
  "A `ToolResult` whose `metadata.pending` is a `Request` IS a suspension
  (§0.12). `execute`'s signature is untouched — suspension is data on the
  existing result, not a new return type."
  ([request] (suspend request (:prompt request)))
  ([request output]
   {:output (str output) :isError true :metadata {:pending request}}))

(defn pending-of
  "The `Request` iff this ToolResult is a suspension, else nil."
  [result]
  (get-in result [:metadata :pending]))

(defn auth-required
  "§10 sugar: a `kind:\"authorization\"` suspension at `url`."
  ([url] (auth-required url (str "Login required: " url)))
  ([url prompt]
   (suspend (make-request "authorization" prompt {:url url})
            (str "Login required: " url))))

;; ---------------------------------------------------------------------------
;; client options
;; ---------------------------------------------------------------------------

(def ^:private default-max-turns 10)

(defn- trim-slashes [s] (str/replace (str s) #"/+$" ""))

(defn- style-of [opts]
  (let [s (:style opts)]
    (if (= "anthropic" (name (or s "openai"))) "anthropic" "openai")))

(defn- resolve-key
  "The API key, from the options or the environment. NEVER logged, never put in
  a result — it only ever reaches a request header."
  [opts]
  (or (:api-key opts)
      (if (= "anthropic" (:style opts))
        (env/get-env "ANTHROPIC_API_KEY")
        (env/get-env "OPENAI_API_KEY"))
      (env/get-env "OPENROUTER_API_KEY")
      ""))

(defn create-client
  "Build a client. Options (idiomatic kebab-case — these never hit the wire):

    :base-url       required, e.g. \"https://api.anthropic.com\"
    :style          \"openai\" | \"anthropic\"   (default \"openai\")
    :model          required
    :api-key        optional; falls back to OPENAI_API_KEY / ANTHROPIC_API_KEY
                    / OPENROUTER_API_KEY
    :headers        extra request headers
    :system-prompt  prepended to the toolkit's skills prompt
    :max-turns      default 10
    :wait-for       (fn [request] answer) — the ONE host slot of §10.

  On the name of that slot: §10 says \"never `await`\" because `await` is
  reserved in JS/Python/C#. In Clojure it is not reserved — it is
  `clojure.core/await`, and it exists on BOTH hosts, so `(defn await ...)`
  would shadow a core name and (per the spike brief) can make cljgo's static
  interop scan reject the WHOLE namespace. Same answer, sharper teeth."
  [opts]
  {:pre [(some? (:base-url opts)) (some? (:model opts))]}
  (-> opts
      (assoc :style (style-of opts))
      (update :max-turns #(or % default-max-turns))))

(defn- system-message
  "§0.10 — system = systemPrompt + \"\\n\\n\" + skillsPrompt. Empty parts are
  dropped, so a toolkit with no described skills yields just the system prompt
  (and no leading blank lines)."
  [client toolkit]
  (->> [(:system-prompt client) (:skills-prompt toolkit)]
       (map #(or % ""))
       (remove str/blank?)
       (str/join "\n\n")))

;; ---------------------------------------------------------------------------
;; transport
;; ---------------------------------------------------------------------------

(defn- endpoint
  "openai  -> {base}/chat/completions
   anthropic -> {base}/messages when base already ends in /v1, else {base}/v1/messages"
  [client]
  (let [base (trim-slashes (:base-url client))]
    (if (= "anthropic" (:style client))
      (if (str/ends-with? base "/v1") (str base "/messages") (str base "/v1/messages"))
      (str base "/chat/completions"))))

(defn- request-headers [client]
  (merge (if (= "anthropic" (:style client))
           {"x-api-key" (resolve-key client) "anthropic-version" "2023-06-01"}
           {"authorization" (str "Bearer " (resolve-key client))})
         (:headers client)))

(def ^:private forbidden-request-params
  "§client-request-shaping — keys `:request-params` may NOT set. They are owned
  by the loop: `messages` is the conversation it is maintaining, `tools` is the
  toolkit's schema, `stream` decides which code path runs. Rewriting messages is
  what `:body-transform` is for, which sees the assembled body and can do it
  deliberately rather than by collision."
  #{:messages :tools :stream})

(defn- shape-body
  "§client-request-shaping — the ordering contract, and the order is the spec:

     base body -> :request-params merge -> :body-transform -> marshal -> wire

  `:request-params` is a SHALLOW merge applied AFTER the client builds its own
  keys, and a param WINS on collision — that is what makes it useful, e.g.
  overriding the anthropic style's built-in max_tokens 4096.

  `:body-transform` runs LAST and its return value is what is sent, so it can
  drop keys the merge added.

  With neither set the body is unchanged, which is the spec's own guarantee and
  the reason both are applied through `cond->` rather than always-on `merge`."
  [client body]
  (let [params (:request-params client)
        params (when (seq params)
                 (let [kept (apply dissoc params forbidden-request-params)]
                   (when (not= (count kept) (count params))
                     (println "toolnexus: ignoring request-params"
                              (vec (sort (filter forbidden-request-params (keys params))))
                              "— owned by the client loop; use :body-transform"))
                   kept))
        merged (if (seq params) (merge body params) body)]
    (if-let [f (:body-transform client)] (f merged) merged)))

(defn- body-map [client system messages tools]
  (shape-body
   client
   (if (= "anthropic" (:style client))
     (cond-> {:model (:model client) :max_tokens 4096 :system system :messages messages}
       (seq tools) (assoc :tools tools))
     (cond-> {:model (:model client) :messages messages}
       (seq tools) (assoc :tools tools :tool_choice "auto")))))

(defn- llm-call
  "One LLM round trip. A transport failure and a non-2xx both become a thrown
  ex-info — unlike a TOOL error, an LLM failure is not something the model can
  be shown and asked to retry."
  [client system messages tools]
  (let [res (http/post-json (endpoint client)
                            (merge {"content-type" "application/json"}
                                   (request-headers client))
                            (json/write-str (body-map client system messages tools)))]
    (cond
      (http/failed? res)
      (throw (ex-info (str "LLM transport " (name (:error res))) {:error (:error res)}))

      (or (< (:status res) 200) (>= (:status res) 300))
      (throw (ex-info (str "LLM " (:status res) ": " (:body res)) {:status (:status res)}))

      :else (json/read-str (:body res)))))

;; ---------------------------------------------------------------------------
;; per-style response reading
;; ---------------------------------------------------------------------------

(defn- parse-args [s]
  (try (or (json/read-str (if (str/blank? (str s)) "{}" (str s))) {})
       (catch Throwable _ {})))

(defn- openai-calls [body]
  (mapv (fn [tc] {:id   (:id tc)
                  :name (get-in tc [:function :name])
                  :args (parse-args (get-in tc [:function :arguments]))})
        (get-in body [:choices 0 :message :tool_calls])))

(defn- anthropic-calls [body]
  (->> (:content body)
       (filter #(= "tool_use" (:type %)))
       (mapv (fn [b] {:id (:id b) :name (:name b) :args (or (:input b) {})}))))

(defn- tool-calls-of [client body]
  (if (= "anthropic" (:style client)) (anthropic-calls body) (openai-calls body)))

(defn- assistant-message
  "The assistant turn, appended to the transcript verbatim in the provider's own
  shape (its keys came off the wire, so they go back onto it unchanged)."
  [client body]
  (if (= "anthropic" (:style client))
    {:role "assistant" :content (:content body)}
    (get-in body [:choices 0 :message])))

(defn- final-text [client body]
  (if (= "anthropic" (:style client))
    (->> (:content body) (filter #(= "text" (:type %))) (map :text) (str/join))
    (or (get-in body [:choices 0 :message :content]) "")))

(defn- tool-result-messages
  "The provider's tool-result shape. THIS is the part the two styles disagree
  about: OpenAI wants one `tool` message per call; Anthropic wants ONE `user`
  message carrying a `tool_result` block per call."
  [client settled]
  (if (= "anthropic" (:style client))
    [{:role "user"
      :content (mapv (fn [s] {:type "tool_result"
                              :tool_use_id (:id s)
                              :content (:output (:result s))
                              :is_error (boolean (:isError (:result s)))})
                     settled)}]
    (mapv (fn [s] {:role "tool" :tool_call_id (:id s) :content (:output (:result s))})
          settled)))

(defn- add-usage
  "Sum token usage across turns. OpenAI prompt/completion/total_tokens;
  Anthropic input_tokens -> prompt, output_tokens -> completion."
  [acc client raw]
  (if-not raw
    acc
    (if (= "anthropic" (:style client))
      (let [p (or (:input_tokens raw) 0) c (or (:output_tokens raw) 0)]
        (-> acc
            (update :prompt-tokens + p)
            (update :completion-tokens + c)
            (update :total-tokens + (+ p c))))
      (let [p (or (:prompt_tokens raw) 0) c (or (:completion_tokens raw) 0)]
        (-> acc
            (update :prompt-tokens + p)
            (update :completion-tokens + c)
            (update :total-tokens + (or (:total_tokens raw) (+ p c))))))))

;; ---------------------------------------------------------------------------
;; tool execution
;; ---------------------------------------------------------------------------
;;
;; §1 Context is OPTIONAL, and in Clojure "optional trailing argument" is arity,
;; not a nullable parameter: `toolnexus.tool/execute` calls `(f args)` when ctx
;; is nil and `(f args ctx)` when it is not. So a tool that wants `ctx.answer`
;; must be written multi-arity — `(fn ([args] ...) ([args ctx] ...))` — because
;; the FIRST execution has no context to pass. That is the portable contract
;; here; see the report.

(defn- execute-calls
  "§8 — the tool calls of one turn, in parallel, with the results in CALL ORDER
  regardless of completion order. Order is not cosmetic: a scrambled transcript
  pairs the wrong result with the wrong tool_call_id and the model never sees a
  crash, only a lie.

  `koine.process/run-async!` + a promise, NOT `future`. This is library code
  running inside somebody else's process, and Clojure's future pool threads are
  non-daemon with a 60-second keep-alive — one tool call would hold a consumer's
  program open for a minute after it finished. `(shutdown-agents)` is the fix
  for an APPLICATION that owns its process (our test runner calls it); a library
  may never decide when its host program exits. run-async! is a daemon thread on
  the JVM and a goroutine on cljgo. The promise is how a fire-and-forget
  primitive gives a value back — run-async! discards its fn's return.

  A tool that throws must not park the deref forever, so the body is wrapped:
  tool/execute already converts a throw into an isError result, and the catch
  here covers the residue."
  [toolkit calls]
  (->> calls
       (mapv (fn [c]
               (let [p (promise)]
                 (proc/run-async!
                  (fn [] (deliver p (assoc c :result
                                           (try (tool/execute toolkit (:name c) (:args c))
                                                (catch Throwable e
                                                  (tool/failure (or (ex-message e) (str e)))))))))
                 p)))
       (mapv deref)))

(defn- emit! [on-event ev] (when on-event (on-event ev)) nil)

(defn- resolve-pending
  "§10 loop rule, for ONE suspended call.

  wait-for configured:
    ok       -> re-execute the SAME tool with the SAME args exactly once, with
                Context.answer = answer; that result goes back to the model.
                If the retry suspends AGAIN -> \"unresolved: <prompt>\" (never
                loop forever on one request).
    not ok   -> \"declined/expired: <prompt>\". The loop continues; the model
                decides what to do.
  wait-for absent:
    -> {:halt request}. The run does not hang.

  Resolution runs sequentially, in tool-call order, which is what makes
  concurrent suspensions surface deterministically (§10) instead of by
  scheduling. `wait-for` itself is a plain blocking (fn [request] answer): §10's
  async framing assumes a function-colouring problem Clojure does not have, so
  there is no channel, no core.async and no executor anywhere in this rule."
  [client toolkit call request on-event]
  (emit! on-event {:type "pending" :request request})   ; BEFORE wait-for runs
  (if-let [wait-for (:wait-for client)]
    (let [answer (wait-for request)]
      (if-not (:ok answer)
        {:result {:output (str "declined/expired: " (:prompt request)) :isError true}}
        (let [retried (tool/execute toolkit (:name call) (:args call) {:answer answer})]
          (if (pending-of retried)
            {:result {:output (str "unresolved: " (:prompt request)) :isError true}}
            {:result retried}))))
    ;; §10 rule 2 — durable halt. The placeholder that enters the transcript is
    ;; the request's prompt (matching the five shipped ports), not the tool's
    ;; own output.
    {:result {:output (:prompt request) :isError true} :halt request}))

;; ---------------------------------------------------------------------------
;; RunResult
;; ---------------------------------------------------------------------------

(def ^:private zero-usage {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})

(defn- run-result [client text messages tool-calls turns usage exhausted?]
  (let [incomplete? (and exhausted? (= "" text))]
    (cond-> {:text text
             :messages messages
             :tool-calls tool-calls
             :tool-call-count (count tool-calls)
             :turns turns
             :usage usage
             :model (:model client)
             :status (if incomplete? "incomplete" "done")}
      incomplete? (assoc :limit "maxTurns"))))

(defn- pending-result [client request messages tool-calls turns usage]
  {:text (:prompt request)
   :messages messages
   :tool-calls tool-calls
   :tool-call-count (count tool-calls)
   :turns turns
   :usage usage
   :model (:model client)
   :status "pending"
   :pending request})

(defn- last-assistant-text [messages]
  (or (->> messages
           (filter (fn [m] (and (= "assistant" (:role m)) (string? (:content m)))))
           last
           :content)
      ""))

;; ---------------------------------------------------------------------------
;; the loop (§0.10)
;; ---------------------------------------------------------------------------

(defn run
  "Run the agent loop. `ctx` = {:toolkit tk :history [...] :on-event f}.

  Returns a RunResult:
    {:text :messages :tool-calls :tool-call-count :turns
     :usage {:prompt-tokens :completion-tokens :total-tokens}
     :model :status (\"done\"|\"pending\"|\"incomplete\") :limit? :pending?}

  `:on-event` is an optional synchronous sink for the §8 event vocabulary this
  non-streaming loop can honestly produce — `tool_call`, `tool_result`,
  `pending` (emitted BEFORE `wait-for` runs, so a channel handler can push the
  link in real time), `usage`, `done`. There are no `text` deltas here: this
  loop buffers the whole response, and faking deltas out of a buffered body
  would be a lie. Real SSE streaming is a separate seam (`koine.stream/sse-post`
  exists and is proven on both hosts) and is NOT implemented in this namespace."
  [client prompt {:keys [toolkit history on-event]}]
  (let [anthropic? (= "anthropic" (:style client))
        system     (system-message client toolkit)
        ;; §0.7 schema comes from toolnexus.adapter — one source of truth for
        ;; the provider shapes, never a second copy in the loop.
        tools      (if anthropic? (adapter/to-anthropic toolkit) (adapter/to-openai toolkit))
        max-turns  (or (:max-turns client) default-max-turns)
        seed       (cond
                     (seq history) (vec history)
                     ;; the anthropic style carries `system` in the body, not as
                     ;; a message; the openai style carries it as message 0.
                     (or anthropic? (str/blank? system)) []
                     :else [{:role "system" :content system}])]
    (loop [messages   (conj seed {:role "user" :content prompt})
           turn       0
           turns      0
           tool-calls []
           usage      zero-usage]
      (if (>= turn max-turns)
        (let [text (if anthropic? "" (last-assistant-text messages))
              r    (run-result client text messages tool-calls turns usage true)]
          (emit! on-event {:type "done" :result r})
          r)
        (let [body    (llm-call client system messages tools)
              usage   (add-usage usage client (:usage body))
              _       (emit! on-event {:type "usage" :usage usage})
              turns   (inc turns)
              msg     (assistant-message client body)
              calls   (tool-calls-of client body)
              messages (conj messages msg)]
          (if (empty? calls)
            (let [r (run-result client (final-text client body) messages tool-calls turns usage false)]
              (emit! on-event {:type "done" :result r})
              r)
            (let [_       (doseq [c calls]
                            (emit! on-event {:type "tool_call" :id (:id c) :name (:name c) :args (:args c)}))
                  ;; parallel execution, results in call order …
                  settled (execute-calls toolkit calls)
                  ;; … then §10 resolution, sequential and in the same order.
                  settled (reduce (fn [acc s]
                                    (if (some :halt acc)
                                      ;; a durable halt already happened earlier in
                                      ;; call order: later suspensions' placeholders
                                      ;; never enter the transcript — they re-suspend
                                      ;; on resume (§10).
                                      acc
                                      (let [req (pending-of (:result s))]
                                        (conj acc (if req
                                                    (merge s (resolve-pending client toolkit s req on-event))
                                                    s)))))
                                  [] settled)
                  ;; §10 durable halt: the transcript keeps the calls UP TO AND
                  ;; INCLUDING the first halted one, and nothing after it —
                  ;; deterministic by call order, never by scheduling.
                  halt-ix (first (keep-indexed (fn [i s] (when (:halt s) i)) settled))
                  settled (if halt-ix (vec (take (inc halt-ix) settled)) settled)
                  _       (doseq [s settled]
                            (emit! on-event {:type "tool_result" :id (:id s) :name (:name s)
                                             :output (:output (:result s))
                                             :isError (boolean (:isError (:result s)))}))
                  records (mapv (fn [s] {:name (:name s) :args (:args s)
                                         :output (:output (:result s))
                                         :isError (boolean (:isError (:result s)))
                                         :metadata (:metadata (:result s))})
                                settled)
                  halted  (first (keep :halt settled))
                  msgs    (into messages (tool-result-messages client settled))]
              (if halted
                (let [r (pending-result client halted msgs (into tool-calls records) turns usage)]
                  (emit! on-event {:type "done" :result r})
                  r)
                (recur msgs (inc turn) turns (into tool-calls records) usage)))))))))
