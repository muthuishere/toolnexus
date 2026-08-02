;; Single-turn translation — SPEC §11, the `tool-translation` capability.
;;
;; `translate` is toolnexus used as a pure WIRE-FORMAT TRANSLATOR: OpenAI shapes
;; in, exactly ONE provider call, OpenAI shapes out. No agent loop, no tool
;; execution, no conversation state — so a caller may run it statelessly and
;; concurrently.
;;
;; It is the INBOUND half of `toolnexus.adapter`: `to-openai`/`to-anthropic`/
;; `to-gemini` send declarations OUT, this reads the provider's tool calls back
;; IN. Use it when the CALLER owns the conversation and executes tools itself
;; (the standard OpenAI function-calling posture, where every request carries the
;; full history including prior tool results). When toolnexus owns the
;; conversation, use `toolnexus.client/run` with §10 suspension instead. Two
;; postures, two mechanisms.
;;
;; NOTHING EXECUTES, EVER. There is no execution path in this namespace — a
;; `:toolkit` in the request is declared through the adapters and never run.
;; That is a property of the design, not of configuration.
;;
;; Casing, deliberately — the same rule `toolnexus.client` states. §11's field
;; names ARE the cross-port contract (`toolCalls`, `finishReason`, and on the
;; wire `tool_call_id` / `tool_use_id` / `input_schema` / `max_tokens`), and
;; koine.json emits a keyword's name verbatim, so the keyword IS the wire key.
;; They appear here camelCase/snake_case in a kebab-case language on purpose;
;; renaming them would be exactly the cross-port drift the ports exist to
;; prevent. `:usage` alone stays in the port's idiomatic §8 Usage shape
;; (`:prompt-tokens` …), because that is the shape `RunResult` already returns
;; and Usage never crosses the wire.
;;
;; Retries/backoff (§7), request-param merging (§8) and the `llm` observability
;; event (§9) are SHARED with the loop, via `client/call-provider` — a
;; translating caller loses neither resilience nor metrics.
;;
;; Zero reader conditionals, zero java.*.
(ns toolnexus.translate
  (:require [clojure.string :as str]
            [koine.json :as json]
            [toolnexus.adapter :as adapter]
            [toolnexus.client :as client]))

;; ---------------------------------------------------------------------------
;; pure helpers
;; ---------------------------------------------------------------------------

(defn finish-reason-for
  "Maps a provider stop reason onto an OpenAI finish reason.

  TOOL CALLS WIN: a turn that emitted any tool call is always `\"tool_calls\"`
  to a conforming client, whatever the provider said (§11). Otherwise
  max_tokens/length -> \"length\", refusal/content_filter -> \"content_filter\",
  everything else -> \"stop\"."
  [has-tool-calls? provider-stop]
  (if has-tool-calls?
    "tool_calls"
    (case (str provider-stop)
      ("max_tokens" "length")           "length"
      ("refusal" "content_filter")      "content_filter"
      "stop")))

(defn content-text
  "Flattens an OpenAI `content` value to text: the string form and the
  parts-array form. Parts with no string `text` contribute nothing."
  [content]
  (cond
    (string? content) content
    (sequential? content) (->> content
                               (map (fn [p] (let [t (and (map? p) (:text p))]
                                              (if (string? t) t ""))))
                               (str/join))
    :else ""))

(defn args-object
  "Parses a tool-call `arguments` value into a map, tolerating BOTH wire forms:
  §11 requires a port to accept `arguments` supplied as an object as well as the
  JSON string, because some clients send it that way. A malformed string is not
  fatal — it yields `{}`."
  [args]
  (cond
    (map? args) args
    (and (string? args) (not (str/blank? args)))
    (try (let [parsed (json/read-str (str/trim args))]
           (if (map? parsed) parsed {}))
         (catch Throwable _ {}))
    :else {}))

(defn args-string
  "Renders a tool-call `arguments` value as the JSON STRING the OpenAI wire
  format uses, so a caller can hand it to a conforming client byte-for-byte."
  [args]
  (cond
    (string? args) args
    (map? args)    (json/write-str args)
    :else          "{}"))

(defn- role-of
  "The message role as a string. A caller writing `:role :user` in Clojure means
  the same thing as `\"user\"`, and refusing that would be a transliteration of
  JS rather than a port."
  [m]
  (let [r (:role m)]
    (cond (keyword? r) (name r)
          (nil? r)     ""
          :else        (str r))))

(defn tool-calls-of
  "Reads an assistant message's OpenAI `tool_calls` into
  `[{:id :name :arguments}]`, arguments in the JSON-string wire form. An entry
  with no `function` is skipped."
  [m]
  (->> (:tool_calls m)
       (filter (fn [tc] (map? (:function tc))))
       (mapv (fn [tc] {:id        (str (or (:id tc) ""))
                       :name      (str (or (get-in tc [:function :name]) ""))
                       :arguments (args-string (get-in tc [:function :arguments]))}))))

(defn has-system-message?
  "True when the message list already carries a system-ish message."
  [messages]
  (boolean (some (fn [m] (contains? #{"system" "developer"} (role-of m))) messages)))

;; ---------------------------------------------------------------------------
;; inbound translation — the part a text-flattening translator gets wrong
;; ---------------------------------------------------------------------------

(defn- drain
  "Close off any accumulated tool results as ONE user turn. §11: providers expect
  a single result-bearing turn answering the preceding assistant turn, not one
  turn per result.

  NOT named `flush` — `clojure.core/flush` exists on both hosts and this port
  does not shadow core names, not even in a local binding."
  [acc]
  (if (seq (:pending acc))
    (-> acc
        (update :out conj {:role "user" :content (:pending acc)})
        (assoc :pending []))
    acc))

(defn- add-assistant [acc m]
  (let [s      (content-text (:content m))
        blocks (into (if (= "" s) [] [{:type "text" :text s}])
                     (mapv (fn [tc] {:type  "tool_use"
                                     :id    (:id tc)
                                     :name  (:name tc)
                                     :input (args-object (:arguments tc))})
                           (tool-calls-of m)))]
    ;; an assistant turn with neither text nor tool calls would be rejected
    (if (seq blocks)
      (update acc :out conj {:role "assistant" :content blocks})
      acc)))

(defn- add-user [acc m]
  (let [content (:content m)
        s       (content-text content)]
    (cond
      (not= "" s) (update acc :out conj {:role "user" :content s})
      ;; a parts array carrying no text (images, say) passes through untouched
      (and (sequential? content) (seq content))
      (update acc :out conj {:role "user" :content content})
      :else acc)))

(defn openai-messages-to-anthropic
  "Converts an OpenAI `messages` array into Anthropic-native messages plus the
  extracted system prompt, preserving the tool structure a text flattening
  destroys (§11):

    - an assistant turn's `tool_calls` become `tool_use` blocks, with
      `arguments` re-parsed from its JSON string into an OBJECT;
    - a `tool`-role result becomes a `tool_result` block keyed by its
      `tool_call_id`, MERGED into a single user turn when consecutive;
    - `system`/`developer` messages are HOISTED out, since Anthropic takes
      system separately (multiple ones join with a blank line).

  Returns `{:messages [...] :system \"...\"}`."
  [messages]
  (let [acc (reduce
             (fn [acc m]
               (let [role (role-of m)]
                 (cond
                   (contains? #{"system" "developer"} role)
                   (let [acc (drain acc)
                         s   (content-text (:content m))]
                     (if (= "" s) acc (update acc :sys conj s)))

                   (contains? #{"tool" "function"} role)
                   (let [id    (:tool_call_id m)
                         block (cond-> {:type "tool_result" :content (content-text (:content m))}
                                 (and id (not= "" (str id))) (assoc :tool_use_id (str id)))]
                     (update acc :pending conj block))

                   (= "assistant" role) (add-assistant (drain acc) m)
                   :else                (add-user (drain acc) m))))
             {:out [] :sys [] :pending []}
             (filter map? messages))
        acc (drain acc)]
    {:messages (:out acc)
     :system   (str/join "\n\n" (:sys acc))}))

(defn openai-tools-to-anthropic
  "Converts an OpenAI `tools` array into Anthropic tool declarations. Entries
  that are already provider-native pass through; anything unrecognized is
  skipped. A missing `parameters` becomes an empty object schema."
  [tools]
  (reduce
   (fn [acc t]
     (if-not (map? t)
       acc
       (let [f (:function t)]
         (cond
           (not (map? f)) (if (:name t) (conj acc t) acc)
           ;; `= ""` rather than `blank?`: js/go/python/elixir all test the value
           ;; for emptiness, not for whitespace, and a whitespace-only name is a
           ;; caller's problem to notice, not ours to silently drop.
           (= "" (str (or (:name f) ""))) acc
           :else (conj acc (cond-> {:name (:name f)}
                             (not= "" (str (or (:description f) "")))
                             (assoc :description (:description f))

                             :always
                             (assoc :input_schema (if (map? (:parameters f))
                                                    (:parameters f)
                                                    {:type "object" :properties {}}))))))))
   []
   tools))

(defn openai-tool-choice-to-anthropic
  "Maps OpenAI `tool_choice` onto Anthropic's shape. Returns nil for absent /
  `\"auto\"` (the provider default) and for anything unrecognized, so nothing is
  sent and the provider decides."
  [choice]
  (cond
    (string? choice) (case choice
                       ("required" "any") {:type "any"}
                       "none"             {:type "none"}
                       nil)
    (map? choice) (let [nm (get-in choice [:function :name])]
                    (when-not (= "" (str (or nm ""))) {:type "tool" :name nm}))
    :else nil))

;; ---------------------------------------------------------------------------
;; the entry point
;; ---------------------------------------------------------------------------

(defn- declared-openai [req]
  (into (if-let [tk (:toolkit req)] (adapter/to-openai tk) [])
        (or (:tools req) [])))

(defn- declared-anthropic [req]
  (into (if-let [tk (:toolkit req)] (adapter/to-anthropic tk) [])
        (openai-tools-to-anthropic (:tools req))))

(defn- positive-max-tokens [req]
  (let [n (:maxTokens req)]
    (when (and (number? n) (pos? n)) n)))

(defn- translate-openai
  "OpenAI-style upstream: near-passthrough. `messages`, `tools` and
  `tool_choice` go out exactly as the caller gave them."
  [client req]
  (let [given    (vec (filter map? (:messages req)))
        sys      (or (:system req) (:system-prompt client) "")
        messages (if (and (not (str/blank? sys)) (not (has-system-message? given)))
                   (into [{:role "system" :content sys}] given)
                   given)
        declared (declared-openai req)
        body     (cond-> {:model (:model client) :messages messages}
                   (seq declared)             (assoc :tools declared)
                   (some? (:toolChoice req))  (assoc :tool_choice (:toolChoice req))
                   (positive-max-tokens req)  (assoc :max_tokens (positive-max-tokens req)))
        data     (client/call-provider client body)
        choice   (get-in data [:choices 0])
        message  (or (:message choice) {})
        calls    (tool-calls-of message)
        stated   (:finish_reason choice)]
    {:text         (or (:content message) "")
     :toolCalls    calls
     ;; The provider's own `finish_reason` wins when it sent one — matching
     ;; js/go/python/elixir, whose OpenAI paths all read
     ;; `choice.finish_reason ?? finishReasonFor(...)`.
     :finishReason (if (str/blank? (str stated))
                     (finish-reason-for (seq calls) nil)
                     stated)
     :usage        (client/add-usage client/zero-usage client (:usage data))
     :model        (:model client)
     :raw          data}))

(defn- translate-anthropic
  "Anthropic-style upstream: the real translation."
  [client req]
  (let [converted (openai-messages-to-anthropic (:messages req))
        sys       (or (:system req)
                      (when-not (str/blank? (:system-prompt client)) (:system-prompt client))
                      (:system converted))
        declared  (declared-anthropic req)
        choice    (openai-tool-choice-to-anthropic (:toolChoice req))
        body      (cond-> {:model      (:model client)
                           :max_tokens (or (positive-max-tokens req) 4096)
                           :messages   (:messages converted)}
                    (not (str/blank? sys)) (assoc :system sys)
                    (seq declared)         (assoc :tools declared)
                    choice                 (assoc :tool_choice choice))
        data      (client/call-provider client body)
        blocks    (filter map? (:content data))
        calls     (->> blocks
                       (filter (fn [b] (= "tool_use" (:type b))))
                       (mapv (fn [b] {:id        (str (or (:id b) ""))
                                      :name      (str (or (:name b) ""))
                                      :arguments (json/write-str (or (:input b) {}))})))]
    {:text         (->> blocks
                        (filter (fn [b] (= "text" (:type b))))
                        (map (fn [b] (or (:text b) "")))
                        (str/join))
     :toolCalls    calls
     :finishReason (finish-reason-for (seq calls) (:stop_reason data))
     :usage        (client/add-usage client/zero-usage client (:usage data))
     :model        (:model client)
     :raw          data}))

(defn translate
  "SPEC §11 — exactly ONE provider call, returned in OpenAI shape. Drives no
  loop, executes no tool, touches no conversation store.

  `client` is a `toolnexus.client/create-client` map. `request`:

    :messages     the OpenAI `messages` array, VERBATIM (assistant turns with
                  `tool_calls`, `tool`-role results with `tool_call_id`)
    :tools        the OpenAI `tools` array, VERBATIM — declaration-only
    :toolkit      an ordinary toolkit, DECLARED via the §5 adapters and NEVER
                  executed; composes with :tools, toolkit declarations first
    :toolChoice   the OpenAI `tool_choice`, VERBATIM (mapped for anthropic)
    :system       overrides the system prompt
    :maxTokens    overrides the per-provider default (anthropic default 4096)

  Returns:

    {:text \"\" :toolCalls [{:id :name :arguments}] :finishReason
     \"stop\"|\"tool_calls\"|\"length\"|\"content_filter\"
     :usage {:prompt-tokens :completion-tokens :total-tokens}
     :model \"\" :raw <the provider's decoded response>}

  `:arguments` is a JSON STRING — the OpenAI wire form."
  [client request]
  (if (= "anthropic" (:style client))
    (translate-anthropic client request)
    (translate-openai client request)))

(defn tool-calls-json
  "Renders a result's tool calls as an OpenAI `tool_calls` array, ready to put on
  an assistant message. Convenience for assembling a response envelope."
  [result]
  (mapv (fn [tc] {:id       (:id tc)
                  :type     "function"
                  :function {:name (:name tc) :arguments (:arguments tc)}})
        (:toolCalls result)))
