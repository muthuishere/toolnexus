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

(defn in-memory-store
  "§conversation-store — the shipped default: per-client, process lifetime.
  A store is exactly two operations, `:get` and `:save`, so a host can swap in
  a file or a database without this namespace knowing anything about it."
  []
  (let [a (atom {})]
    {:get  (fn [id] (get @a id))
     :save (fn [id msgs] (swap! a assoc id msgs) nil)}))

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
    :hooks          §8 lifecycle middleware — a map of any of
                    {:before-llm :after-llm :before-tool :after-tool}; see
                    `before-llm!` / `execute-tool`. Absent => nothing changes.
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
      (update :max-turns #(or % default-max-turns))
      ;; §conversation-store: the default is in-memory and per-client, so two
      ;; clients never share a transcript by accident.
      (update :store #(or % (in-memory-store)))))

;; ---------------------------------------------------------------------------
;; In-process models (SPEC §8 Gap 2, semantic form)
;; ---------------------------------------------------------------------------

(def ^:private in-process-base-url
  "A sentinel. Never dialled — the `:http-client` below answers every request before
  the network is reached — but a URL string is built internally, so it must be
  syntactically valid. `.invalid` is reserved by RFC 2606 precisely so a name can
  never resolve."
  "http://in-process.invalid/v1")

(defn- encode-args
  "Pass an already-encoded string through; encode anything else, so a model author
  never has to think about the wire."
  [v]
  (cond
    (nil? v) "{}"
    (string? v) v
    :else (json/write-str v)))

(defn- in-process-http-client
  "Turns a semantic `generate` into the shipped `:http-client` seam: the host returns
  ONE assistant message and this builds the provider envelope."
  [generate]
  (fn [_url _headers body]
    (let [payload (json/read-str (if (string? body) body (json/write-str body)))
          answer  (or (generate {:messages (:messages payload)
                                 :tools    (:tools payload)
                                 :model    (:model payload)
                                 :body     payload})
                      {})
          calls   (or (:tool-calls answer) (:tool_calls answer))
          message (if (seq calls)
                    {:role "assistant"
                     :tool_calls
                     (vec (map-indexed
                           (fn [i c]
                             {:id (or (:id c) (str "call_" i))
                              :type "function"
                              :function {:name (:name c)
                                         :arguments (encode-args (:arguments c))}})
                           calls))}
                    {:role "assistant" :content (or (:content answer) "")})
          usage   (or (:usage answer) {})
          prompt  (or (:prompt-tokens usage) (:prompt_tokens usage) 0)
          compl   (or (:completion-tokens usage) (:completion_tokens usage) 0)
          total   (or (:total-tokens usage) (:total_tokens usage) (+ prompt compl))]
      {:status 200
       :headers {"content-type" "application/json"}
       :body (json/write-str
              {:choices [{:index 0
                          :message message
                          :finish_reason (if (seq calls) "tool_calls" "stop")}]
               :usage {:prompt_tokens prompt
                       :completion_tokens compl
                       :total_tokens total}})})))

(defn create-in-process-client
  "A client backed by a model running IN THIS PROCESS — no server, no socket, and no
  HTTP shapes to construct.

  This is a second constructor, not a second seam: it builds on the same
  `:http-client` transport, so the tool-calling loop, MCP servers, skills,
  sub-agents, hooks, metrics and the completion gate behave identically.

    (create-in-process-client
      {:model \"my-local\"
       :generate (fn [req]
                   ;; req = {:messages [...] :tools [...] :model \"my-local\" :body {...}}
                   {:content \"hello\"})})
                   ;; or {:tool-calls [{:name \"add\" :arguments {:a 2 :b 3}}]}

  `:generate` returns ONE assistant message; `:usage` is optional. There is no
  `:base-url`, `:api-key` or `:style` — there is no wire to configure.

  NOTE this port has no streaming entry point (`:on-event` is a sink on the
  non-streaming loop), so unlike the other six there is nothing here to refuse."
  [{:keys [model generate] :as opts}]
  (when-not (fn? generate)
    (throw (ex-info "toolnexus: create-in-process-client requires a `:generate` function" {})))
  (doseq [reserved [:base-url :api-key :style :http-client]]
    (when (contains? opts reserved)
      (throw (ex-info (str "toolnexus: create-in-process-client does not take " reserved
                           " — an in-process model has no wire to configure. Use "
                           "create-client for a network-backed model.")
                      {:option reserved}))))
  ;; Zero retries by default: there is no wire, so there is no transient failure to
  ;; ride out, and retrying only buys backoff before the caller sees their own bug.
  (-> opts
      (update :retries #(or % 0))
      (dissoc :generate)
      (assoc :base-url in-process-base-url
             :style "openai"
             :model model
             :http-client (in-process-http-client generate))
      create-client))

(defn- metric!
  "§client-observability — SEMANTIC events, not counter primitives. Guarded so
  that with `:on-metric` unset there is no measurable overhead: the map is not
  even built."
  [client f]
  (when-let [on (:on-metric client)] (on (f)))
  nil)

;; ---------------------------------------------------------------------------
;; §8 hooks — lifecycle middleware
;; ---------------------------------------------------------------------------
;;
;;   :before-llm  {:messages :tools :model :turn}  -> {:messages? :tools?}
;;   :after-llm   {:response :model :turn}         -> observe only
;;   :before-tool {:name :args :id :turn}          -> {:result} short-circuits,
;;                                                    {:args} rewrites
;;   :after-tool  {:name :args :result :id :turn}  -> {:result} replaces
;;
;; The event maps are kebab-case because a hook never crosses the wire; the
;; `ToolResult` one carries keeps its pinned `:isError`, because that one does.
;;
;; A hook returning nil (the common case — most hooks observe) changes nothing,
;; which is why every application below is an `or` against the current value
;; rather than a blind overwrite.

(defn- hook-of [client k] (get-in client [:hooks k]))

(defn before-llm!
  "§8 `beforeLLM`, applied to ONE round trip. Returns `[messages tools]` — the
  hook's replacements when it returned them, the originals otherwise.

  Public because §11 `toolnexus.translate` must fire this hook exactly once for
  its single call, and a second copy of the rule in that namespace is exactly
  the drift the ports exist to prevent.

  Ordering (SPEC.md §8 Gap 1, and it IS the spec):

    base body -> beforeLLM hook -> :request-params merge -> :body-transform
              -> marshal -> wire

  so this runs BEFORE the body is assembled, and its `tools` replacement feeds
  the same empty-list omission (Gap 5) the toolkit's own list does."
  [client messages tools turn]
  (if-let [f (hook-of client :before-llm)]
    (let [ov (f {:messages messages :tools tools :model (:model client) :turn turn})]
      [(or (:messages ov) messages) (or (:tools ov) tools)])
    [messages tools]))

(defn after-llm!
  "§8 `afterLLM` — observe only (logging, cost, tracing). `:response` is the
  provider's decoded payload, so it carries `usage`. Returns nil; a return value
  from an observer would be a silent contract nobody could rely on."
  [client response turn]
  (when-let [f (hook-of client :after-llm)]
    (f {:response response :model (:model client) :turn turn}))
  nil)

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

(def ^:private retryable-statuses
  "§resilience-policy — the retryable set. Everything else is terminal unless a
  host `:on-error` says otherwise."
  #{429 500 502 503 504})

(def ^:private retry-after-max-seconds
  "~68 years; the widest whole-second count all seven ports represent exactly."
  2147483647)

(defn- retry-after-ms
  "Honour a `Retry-After` header when the server sends one. Seconds only: the
  HTTP-date form needs date parsing, which is not portable across these two
  hosts without reaching past koine, and a server that sends it gets our
  backoff instead of a wrong answer.

  Read through `koine.http/header`, not `get`: the two hosts' clients used to
  disagree about the case of the names they hand back, so trying two spellings
  was a guess that happened to cover the two known ones. koine 0.10.0 lowercases
  on every host and `header` is the one lookup that cannot be mis-cased."
  [res]
  (let [h (http/header res "retry-after")
        digits (when h (re-matches #"[0-9]+" (str/trim (str h))))
        ;; `parse-long` answers nil for a digit string wider than a long, and
        ;; `(* 1000 nil)` throws — so a server sending an absurd value used to kill
        ;; the run from inside the retry path. Range-check before multiplying.
        n (some-> digits parse-long)]
    (when (and n (<= 0 n retry-after-max-seconds))
      (* 1000 n))))

(defn- post-llm
  "The one HTTP call. `:http-client` lets a host supply the transport — for a
  proxy, mTLS, or credentials this library must never see — and it takes the
  same (url headers body) shape as koine.http/post-json so wrapping the default
  is a one-liner. Absent, the default is used and nothing changes.

  Scope is the LLM path only, per the capability spec: MCP transports are their
  own seam and are NOT routed through this."
  [client url headers body]
  (if-let [f (:http-client client)]
    (f url headers body)
    ;; `request` rather than `post-json`, because only `request` takes
    ;; :timeout-ms. koine classifies a timeout as DATA ({:status nil :error
    ;; :timeout}), never a throw, which is what lets the retry loop below treat
    ;; it as one more retryable failure instead of a host-specific exception.
    (http/request (cond-> {:method :post :url url :headers headers :body body}
                    (:timeout-ms client) (assoc :timeout-ms (:timeout-ms client))))))

(defn- classify
  "§resilience-policy — retry | fail, and NOTHING ELSE. The archived spec is
  explicit that this capability does not add a failure-originated suspend tier:
  §10 suspension stays a user-action pause, so an LLM failure can never become
  one here.

  With no `:on-error` the default is today's behaviour — retryable set retries,
  everything else fails. A host verdict overrides the default in EITHER
  direction, but `:retries` still bounds it: a classifier that could loop
  unbounded would be a denial-of-service on the caller's own bill."
  [client info]
  (let [default (if (:retryable? info) :retry :fail)]
    (if-let [f (:on-error client)]
      (if (= :retry (f info)) :retry :fail)
      default)))

(defn- post-with-retry
  "One LLM round trip, with the §resilience-policy retry loop around it. A
  transport failure and a non-2xx both become a thrown ex-info — unlike a TOOL
  error, an LLM failure is not something the model can be shown and asked to
  retry. `body` is the already-marshalled request string."
  [client url headers body]
  (let [budget  (or (:retries client) 0)
        base-ms (or (:retry-base-ms client) 250)]
    (loop [attempt 0]
      (let [t0      (ktime/now-ms)
            res     (post-llm client url headers body)
            failed? (http/failed? res)
            status  (:status res)
            ok?     (and (not failed?) status (<= 200 status) (< status 300))]
        (if ok?
          (do (metric! client
                       (fn [] {:event "llm" :model (:model client) :status status
                               :ms (- (ktime/now-ms) t0)
                               :prompt_tokens (get-in res [:usage :prompt_tokens])
                               :completion_tokens (get-in res [:usage :completion_tokens])}))
              (json/read-str (:body res)))
          (let [info    {:error     (if failed? (:error res) (:body res))
                         :status    status
                         :attempt   attempt
                         ;; a transport failure has no status and is retryable
                         :retryable? (boolean (or failed? (contains? retryable-statuses status)))}
                verdict (classify client info)
                throw!  (fn []
                          (if failed?
                            (throw (ex-info (str "LLM transport " (name (:error res)))
                                            {:error (:error res)}))
                            (throw (ex-info (str "LLM " status ": " (:body res))
                                            {:status status}))))]
            (if (and (= :retry verdict) (< attempt budget))
              (do (ktime/sleep! (or (retry-after-ms res)
                                    ;; exponential backoff: base * 2^attempt
                                    (* base-ms (bit-shift-left 1 attempt))))
                  (recur (inc attempt)))
              (throw!))))))))

(defn- llm-call
  "The agent loop's round trip: build the loop's body, then post it."
  [client system messages tools]
  (post-with-retry client
                   (endpoint client)
                   (merge {"content-type" "application/json"} (request-headers client))
                   (json/write-str (body-map client system messages tools))))

(defn call-provider
  "ONE provider round trip for a CALLER-BUILT body map, through exactly the
  endpoint, headers, §resilience-policy retry/backoff and `llm` metric the agent
  loop uses. §8's `:request-params` merge and `:body-transform` apply unchanged.

  This is the seam §11 `toolnexus.translate` sits on: single-turn translation
  must lose neither resilience nor metrics, and the only way to guarantee that
  is to share the code rather than copy it."
  [client body]
  (post-with-retry client
                   (endpoint client)
                   (merge {"content-type" "application/json"} (request-headers client))
                   (json/write-str (shape-body client body))))

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

(def zero-usage
  "The empty §8 Usage. Idiomatic kebab-case — Usage is a RETURN value, it never
  crosses the wire."
  {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})

(defn add-usage
  "Sum token usage across turns. OpenAI prompt/completion/total_tokens;
  Anthropic input_tokens -> prompt, output_tokens -> completion. Public because
  §11 translation reports the same Usage from the same provider payloads."
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

(defn- execute-tool
  "ONE tool call, through the §8 `:before-tool` / `:after-tool` hooks.

    :before-tool -> {:result r}  SHORT-CIRCUITS: the real tool never runs
                                 (deny / cache hit / dry-run)
                 -> {:args a}    rewrites the call; those args are what runs,
                                 what the transcript records, and what a §10
                                 retry re-executes with
    :after-tool  -> {:result r}  replaces the output (redact, annotate)

  A SUSPENSION SKIPS `:after-tool` (js/src/client.ts: `if (h?.afterTool &&
  !pendingOf(result))`) — a pending Request is not a real result, and the
  resolved one still flows through the hook in `resolve-pending`.

  A tool that throws must not park the caller's deref forever, so the execution
  is wrapped: tool/execute already turns a throw into an isError result and the
  catch here covers the residue."
  [client toolkit call turn]
  (let [before (hook-of client :before-tool)
        after  (hook-of client :after-tool)
        ov     (when before
                 (before {:name (:name call) :args (:args call)
                          :id (:id call) :turn turn}))]
    (if (:result ov)
      (assoc call :result (:result ov))
      (let [args   (or (:args ov) (:args call))
            result (try (tool/execute toolkit (:name call) args)
                        (catch Throwable e
                          (tool/failure (or (ex-message e) (str e)))))
            result (if (and after (not (pending-of result)))
                     (or (:result (after {:name (:name call) :args args :result result
                                          :id (:id call) :turn turn}))
                         result)
                     result)]
        (assoc call :args args :result result)))))

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

  NOTHING may park the caller's deref forever, so the body delivers on every
  exit including a throw. `tool/execute` converts a throwing TOOL into an isError
  result; the catch here covers the rest of the body — in particular the two §8
  tool hooks, which are host code and are invoked outside that conversion. A
  throw from a hook is rethrown on the calling thread, matching the shipped
  ports, where an unguarded hook rejects the run."
  [client toolkit calls turn]
  (->> calls
       (mapv (fn [c]
               (let [p (promise)
                     t0 (ktime/now-ms)]
                 ;; EVERY exit from the body delivers, including a throw. Only
                 ;; `tool/execute` was guarded before, and both §8 tool hooks are
                 ;; invoked OUTSIDE that guard — so a host whose `:before-tool`
                 ;; threw killed this thread before `deliver` ran and parked the
                 ;; consumer on the `deref` below FOREVER. On the JVM that at
                 ;; least printed a stack trace from the dying thread; on cljgo it
                 ;; hung with no diagnostic at all.
                 (proc/run-async!
                  (fn []
                    (deliver p (try {:value (execute-tool client toolkit c turn)}
                                    (catch Throwable e {:thrown e})))))
                 [p t0])))
       (mapv (fn [pair]
               ;; Rethrow on the CALLING thread rather than converting to an
               ;; isError result: js/src/client.ts wraps neither hook, so a hook
               ;; that throws rejects `run()` and the host sees its own bug. A
               ;; tool that throws is still an isError result — that rule is
               ;; `tool/execute`'s and is unchanged. This is about the host's
               ;; code failing, not the tool's.
               (let [outcome (deref (first pair))
                     _       (when-let [e (:thrown outcome)] (throw e))
                     r       (:value outcome)]
                 ;; §client-observability — one `tool` event per call, emitted
                 ;; after the result lands so :ms and :is_error are real.
                 (metric! client
                          (fn [] {:event "tool"
                                  :tool (:name r)
                                  :source (:source (get (:tools toolkit) (:name r)))
                                  :is_error (boolean (:isError (:result r)))
                                  :ms (- (ktime/now-ms) (second pair))}))
                 r)))))

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
  [client toolkit call request on-event turn]
  (emit! on-event {:type "pending" :request request})   ; BEFORE wait-for runs
  (if-let [wait-for (:wait-for client)]
    (let [answer (wait-for request)]
      (if-not (:ok answer)
        {:result {:output (str "declined/expired: " (:prompt request)) :isError true}}
        ;; §8: the RESOLVED result is a real result, so `:after-tool` sees it
        ;; here — the suspension it replaces was skipped in `execute-tool`.
        (let [retried (tool/execute toolkit (:name call) (:args call) {:answer answer})
              retried (if-let [f (hook-of client :after-tool)]
                        (or (:result (f {:name (:name call) :args (:args call) :result retried
                                         :id (:id call) :turn turn}))
                            retried)
                        retried)]
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
  [client prompt {:keys [toolkit history on-event conversation-id]}]
  (let [anthropic? (= "anthropic" (:style client))
        system     (system-message client toolkit)
        ;; §0.7 schema comes from toolnexus.adapter — one source of truth for
        ;; the provider shapes, never a second copy in the loop.
        tools      (if anthropic? (adapter/to-anthropic toolkit) (adapter/to-openai toolkit))
        max-turns  (or (:max-turns client) default-max-turns)
        ;; §conversation-store — an explicit :history still wins; the store is
        ;; consulted only when a :conversation-id is given. Without an id a run
        ;; is one-shot and must not inherit a previous transcript.
        remembered (when (and conversation-id (:store client))
                     ((:get (:store client)) conversation-id))
        run-t0     (ktime/now-ms)
        ;; ONE exit path for the store save and the run metric. Two `run-result`
        ;; call sites return from this loop, and a save wired into only one of
        ;; them is the classic half-fix — the turn-limit exit would silently
        ;; forget the conversation.
        finish!    (fn [r]
                     (when (and conversation-id (:store client))
                       ((:save (:store client)) conversation-id (:messages r)))
                     (metric! client
                              (fn [] {:event "run" :model (:model client)
                                      :turns (:turns r) :tool_calls (:tool-call-count r)
                                      :total_tokens (get-in r [:usage :total-tokens])
                                      :ms (- (ktime/now-ms) run-t0)
                                      :error (:limit? r)}))
                     r)
        seed       (cond
                     (seq history)    (vec history)
                     (seq remembered) (vec remembered)
                     ;; the anthropic style carries `system` in the body, not as
                     ;; a message; the openai style carries it as message 0.
                     (or anthropic? (str/blank? system)) []
                     :else [{:role "system" :content system}])]
    ;; `tools` is loop state, not a constant: §8 says a `:before-llm` hook that
    ;; returns `:messages`/`:tools` replaces them FOR THE REST OF THE RUN, so a
    ;; turn-0 override must still be in force on turn 1.
    (loop [messages   (conj seed {:role "user" :content prompt})
           tools      tools
           turn       0
           turns      0
           tool-calls []
           usage      zero-usage]
      (if (>= turn max-turns)
        (let [text (if anthropic? "" (last-assistant-text messages))
              r    (finish! (run-result client text messages tool-calls turns usage true))]
          (emit! on-event {:type "done" :result r})
          r)
        ;; §8 ordering: the hook sees (and may replace) the transcript and the
        ;; tool list BEFORE the body is assembled, so :request-params and
        ;; :body-transform both run downstream of it.
        (let [hooked  (before-llm! client messages tools turn)
              messages (first hooked)
              tools    (second hooked)
              body    (llm-call client system messages tools)
              usage   (add-usage usage client (:usage body))
              _       (after-llm! client body turn)
              _       (emit! on-event {:type "usage" :usage usage})
              turns   (inc turns)
              msg     (assistant-message client body)
              calls   (tool-calls-of client body)
              messages (conj messages msg)]
          (if (empty? calls)
            (let [r (finish! (run-result client (final-text client body) messages tool-calls turns usage false))]
              (emit! on-event {:type "done" :result r})
              r)
            (let [_       (doseq [c calls]
                            (emit! on-event {:type "tool_call" :id (:id c) :name (:name c) :args (:args c)}))
                  ;; parallel execution, results in call order …
                  settled (execute-calls client toolkit calls turn)
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
                                                    (merge s (resolve-pending client toolkit s req on-event turn))
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
                (recur msgs tools (inc turn) turns (into tool-calls records) usage)))))))))


(defn ask
  "`run`, remembering the conversation (§conversation-store) — the id-keyed
  sugar every other port ships as `ask`.

      (ask client \"and the second one?\" {:toolkit tk :id \"muthu\"})

  With an `:id`, the store's history for that id is loaded before the call and
  the full transcript saved after it, so the next `ask` with the same id
  continues the conversation. WITHOUT an id it is a stateless one-shot,
  identical to `run` — the same rule as js/src/client.ts, where `ask` with no
  id delegates straight to `run`.

  This is deliberately nothing more than `run` with `:conversation-id` filled
  in: the memory mechanics live in ONE place, and a second copy of the
  load-then-save rule here is exactly the drift the ports exist to prevent."
  [client prompt {:keys [toolkit id on-event] :as ctx}]
  (run client prompt (cond-> {:toolkit toolkit :on-event on-event}
                       id (assoc :conversation-id id)
                       (:history ctx) (assoc :history (:history ctx)))))
