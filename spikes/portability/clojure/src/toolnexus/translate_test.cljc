;; toolnexus.translate — the suite. SPEC §11 (single-turn translation) and the
;; `tool-translation` capability spec.
;;
;; Every expected value here comes from SPEC.md §11 / `js/src/translate.ts`, not
;; from this port's own output: a snapshot of what we happen to emit would prove
;; only that we are self-consistent, which is the one thing six ports never need
;; proving.
;;
;; The "provider" is a real koine.server on 127.0.0.1:0 replaying canned,
;; provider-shaped responses and RECORDING every request body — so "exactly one
;; provider call" and "the body carried native tool_use blocks" are measured,
;; not assumed. Hermetic: no API key, no network beyond loopback, no live model.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.translate-test
  (:require [clojure.test :refer [deftest is testing]]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.client :as client]
            [toolnexus.tool :as tool]
            [toolnexus.translate :as tr]))

;; ---------------------------------------------------------------------------
;; the canned provider
;; ---------------------------------------------------------------------------

(defn with-provider
  "Serve `responses` (already provider-shaped maps) in order, hand `f`
  {:base :requests :paths}, stop the server after. A request past the end of the
  vector re-serves the last one, so a stateless-repeat test needs one entry."
  [responses f]
  (let [n        (atom 0)
        requests (atom [])
        paths    (atom [])
        srv      (server/serve
                  (fn [req]
                    (swap! paths conj (:path req))
                    (swap! requests conj (json/read-str (str (:body req))))
                    (let [i (min (dec (swap! n inc)) (dec (count responses)))]
                      {:status 200
                       :headers {"content-type" "application/json"}
                       :body (json/write-str (nth responses i))}))
                  {:port 0})]
    (try
      (f {:base (str "http://127.0.0.1:" (server/port srv))
          :requests requests
          :paths paths})
      (finally (server/stop! srv)))))

(defn- client-for [style base opts]
  (client/create-client (merge {:base-url base :style style :model "mock-model"} opts)))

;; A toolkit whose tool RECORDS every invocation. §11's central claim is that
;; nothing here is ever executed, and the only honest way to assert that is a
;; tool that would tell on us.
(def executions (atom 0))

(defn- probe-toolkit []
  (tool/toolkit
   [(tool/tool {:name "get_weather"
                :description "Weather for a city"
                :input-schema {:type "object" :properties {:city {:type "string"}}}
                :execute (fn [_args] (swap! executions inc) (tool/success "sunny"))})]))

(def openai-tools-fixture
  [{:type "function"
    :function {:name "lookup"
               :description "Look something up"
               :parameters {:type "object" :properties {:q {:type "string"}}}}}])

;; ---------------------------------------------------------------------------
;; pure helpers — §11 "Outbound translation" + the inbound rules
;; ---------------------------------------------------------------------------

(deftest finish-reason-mapping
  (testing "a turn emitting any tool call is ALWAYS tool_calls, whatever the provider said"
    (is (= "tool_calls" (tr/finish-reason-for true nil)))
    (is (= "tool_calls" (tr/finish-reason-for true "end_turn")))
    (is (= "tool_calls" (tr/finish-reason-for true "max_tokens"))))
  (testing "otherwise max_tokens/length -> length"
    (is (= "length" (tr/finish-reason-for false "max_tokens")))
    (is (= "length" (tr/finish-reason-for false "length"))))
  (testing "refusal/content_filter -> content_filter"
    (is (= "content_filter" (tr/finish-reason-for false "refusal")))
    (is (= "content_filter" (tr/finish-reason-for false "content_filter"))))
  (testing "everything else -> stop"
    (is (= "stop" (tr/finish-reason-for false "end_turn")))
    (is (= "stop" (tr/finish-reason-for false nil)))
    (is (= "stop" (tr/finish-reason-for false "who_knows")))))

(deftest content-flattening
  (is (= "plain" (tr/content-text "plain")))
  (testing "a content array of parts is flattened to text"
    (is (= "ab" (tr/content-text [{:type "text" :text "a"} {:type "text" :text "b"}]))))
  (testing "non-text parts contribute nothing"
    (is (= "a" (tr/content-text [{:type "text" :text "a"} {:type "image" :url "u"}]))))
  (testing "anything else is empty"
    (is (= "" (tr/content-text nil)))
    (is (= "" (tr/content-text 42)))))

(deftest arguments-both-wire-forms
  (testing "args-object: a port MUST also accept `arguments` supplied as an object"
    (is (= {:city "Chennai"} (tr/args-object {:city "Chennai"})))
    (is (= {:city "Chennai"} (tr/args-object "{\"city\":\"Chennai\"}"))))
  (testing "a malformed or empty arguments string is not fatal"
    (is (= {} (tr/args-object "not json")))
    (is (= {} (tr/args-object "")))
    (is (= {} (tr/args-object "   ")))
    (is (= {} (tr/args-object nil))))
  (testing "args-string renders the OpenAI wire form"
    (is (= "{\"city\":\"Chennai\"}" (tr/args-string "{\"city\":\"Chennai\"}")))
    (is (= {:city "Chennai"} (json/read-str (tr/args-string {:city "Chennai"}))))
    (is (= "{}" (tr/args-string nil)))))

(deftest tool-calls-of-an-assistant-turn
  (is (= [{:id "c1" :name "w" :arguments "{\"city\":\"Pune\"}"}]
         (tr/tool-calls-of {:role "assistant"
                            :tool_calls [{:id "c1" :type "function"
                                          :function {:name "w" :arguments "{\"city\":\"Pune\"}"}}]})))
  (testing "no tool_calls, or an entry with no function, yields nothing"
    (is (= [] (tr/tool-calls-of {:role "assistant" :content "hi"})))
    (is (= [] (tr/tool-calls-of {:role "assistant" :tool_calls [{:id "x"}]})))))

(deftest inbound-preserves-tool-structure
  (let [{:keys [messages system]}
        (tr/openai-messages-to-anthropic
         [{:role "system" :content "be brief"}
          {:role "user" :content "weather?"}
          {:role "assistant" :content "checking"
           :tool_calls [{:id "c1" :type "function" :function {:name "w" :arguments "{\"city\":\"Chennai\"}"}}
                        {:id "c2" :type "function" :function {:name "w" :arguments "{\"city\":\"Delhi\"}"}}
                        {:id "c3" :type "function" :function {:name "w" :arguments "{\"city\":\"Pune\"}"}}]}
          {:role "tool" :tool_call_id "c1" :content "sunny"}
          {:role "tool" :tool_call_id "c2" :content "rain"}
          {:role "tool" :tool_call_id "c3" :content "fog"}])]
    (testing "system is hoisted into the provider's separate field"
      (is (= "be brief" system)))
    (testing "THREE consecutive tool results become exactly ONE user turn"
      (is (= 3 (count messages)))
      (is (= ["user" "assistant" "user"] (mapv :role messages))))
    (testing "tool_calls become tool_use blocks with arguments re-parsed to an OBJECT"
      (is (= [{:type "text" :text "checking"}
              {:type "tool_use" :id "c1" :name "w" :input {:city "Chennai"}}
              {:type "tool_use" :id "c2" :name "w" :input {:city "Delhi"}}
              {:type "tool_use" :id "c3" :name "w" :input {:city "Pune"}}]
             (:content (nth messages 1)))))
    (testing "each tool result is a tool_result block keyed by its tool_call_id"
      (is (= [{:type "tool_result" :content "sunny" :tool_use_id "c1"}
              {:type "tool_result" :content "rain" :tool_use_id "c2"}
              {:type "tool_result" :content "fog" :tool_use_id "c3"}]
             (:content (nth messages 2)))))))

(deftest inbound-edge-cases
  (testing "two system messages join with a blank line"
    (is (= "one\n\ntwo" (:system (tr/openai-messages-to-anthropic
                                 [{:role "system" :content "one"}
                                  {:role "developer" :content "two"}])))))
  (testing "a user content array of text parts is flattened"
    (is (= [{:role "user" :content "ab"}]
           (:messages (tr/openai-messages-to-anthropic
                       [{:role "user" :content [{:type "text" :text "a"} {:type "text" :text "b"}]}])))))
  (testing "an assistant turn with neither text nor tool calls is dropped — a provider rejects it"
    (is (= [] (:messages (tr/openai-messages-to-anthropic [{:role "assistant" :content ""}])))))
  (testing "a trailing tool result still flushes into its own user turn"
    (is (= [{:role "user" :content [{:type "tool_result" :content "x" :tool_use_id "c9"}]}]
           (:messages (tr/openai-messages-to-anthropic
                       [{:role "tool" :tool_call_id "c9" :content "x"}])))))
  (testing "non-map entries are ignored"
    (is (= [] (:messages (tr/openai-messages-to-anthropic [nil "junk"]))))))

(deftest openai-tool-declarations-to-anthropic
  (is (= [{:name "lookup"
           :description "Look something up"
           :input_schema {:type "object" :properties {:q {:type "string"}}}}]
         (tr/openai-tools-to-anthropic openai-tools-fixture)))
  (testing "a missing `parameters` becomes an empty object schema"
    (is (= [{:name "bare" :input_schema {:type "object" :properties {}}}]
           (tr/openai-tools-to-anthropic [{:type "function" :function {:name "bare"}}]))))
  (testing "an already-native declaration passes through, and junk is skipped"
    (is (= [{:name "native" :input_schema {:type "object"}}]
           (tr/openai-tools-to-anthropic [{:name "native" :input_schema {:type "object"}}])))
    (is (= [] (tr/openai-tools-to-anthropic [{:type "function" :function {}} nil 7])))
    (is (= [] (tr/openai-tools-to-anthropic nil)))))

(deftest tool-choice-mapping
  (is (= {:type "any"} (tr/openai-tool-choice-to-anthropic "required")))
  (is (= {:type "any"} (tr/openai-tool-choice-to-anthropic "any")))
  (is (= {:type "none"} (tr/openai-tool-choice-to-anthropic "none")))
  (is (= {:type "tool" :name "lookup"}
         (tr/openai-tool-choice-to-anthropic {:type "function" :function {:name "lookup"}})))
  (testing "absent / auto / unrecognized send nothing — the provider default"
    (is (nil? (tr/openai-tool-choice-to-anthropic nil)))
    (is (nil? (tr/openai-tool-choice-to-anthropic "auto")))
    (is (nil? (tr/openai-tool-choice-to-anthropic {:type "function"})))))

(deftest system-message-detection
  (is (true? (tr/has-system-message? [{:role "user"} {:role "system"}])))
  (is (true? (tr/has-system-message? [{:role "developer"}])))
  (is (false? (tr/has-system-message? [{:role "user"}])))
  (is (false? (tr/has-system-message? nil))))

;; ---------------------------------------------------------------------------
;; §11 — the entry point, openai-style upstream
;; ---------------------------------------------------------------------------

(def ^:private openai-text-turn
  {:choices [{:message {:role "assistant" :content "hello there"} :finish_reason "stop"}]
   :usage {:prompt_tokens 3 :completion_tokens 4 :total_tokens 7}})

(def ^:private openai-tool-turn
  {:choices [{:message {:role "assistant" :content nil
                        :tool_calls [{:id "call_1" :type "function"
                                      :function {:name "get_weather"
                                                 :arguments "{\"city\":\"Chennai\"}"}}]}
              :finish_reason "tool_calls"}]
   :usage {:prompt_tokens 11 :completion_tokens 2 :total_tokens 13}})

(deftest openai-single-turn-text
  (with-provider [openai-text-turn]
    (fn [{:keys [base requests paths]}]
      (let [res (tr/translate (client-for "openai" base {})
                              {:messages [{:role "user" :content "hi"}]})]
        (testing "exactly ONE provider call, at {base}/chat/completions"
          (is (= 1 (count @requests)))
          (is (= ["/chat/completions"] @paths)))
        (testing "the OpenAI-shaped result"
          (is (= "hello there" (:text res)))
          (is (= [] (:toolCalls res)))
          (is (= "stop" (:finishReason res)))
          (is (= {:prompt-tokens 3 :completion-tokens 4 :total-tokens 7} (:usage res)))
          (is (= "mock-model" (:model res))))
        (testing ":raw is the provider's decoded response"
          (is (= "stop" (get-in res [:raw :choices 0 :finish_reason]))))
        (testing "messages are handed over VERBATIM and no tools key is sent"
          (is (= [{:role "user" :content "hi"}] (:messages (first @requests))))
          (is (nil? (:tools (first @requests)))))))))

(deftest openai-tool-call-returned-not-executed
  (reset! executions 0)
  (with-provider [openai-tool-turn]
    (fn [{:keys [base requests]}]
      (let [res (tr/translate (client-for "openai" base {})
                              {:messages [{:role "user" :content "weather in Chennai?"}]
                               :toolkit (probe-toolkit)})]
        (testing "the toolkit is DECLARED via the §5 openai adapter"
          (is (= [{:type "function"
                   :function {:name "get_weather"
                              :description "Weather for a city"
                              :parameters {:type "object" :properties {:city {:type "string"}}}}}]
                 (:tools (first @requests)))))
        (testing "nothing executes, ever"
          (is (= 0 @executions)))
        (testing "the call is handed back with its id, name and JSON-STRING arguments"
          (is (= [{:id "call_1" :name "get_weather" :arguments "{\"city\":\"Chennai\"}"}]
                 (:toolCalls res)))
          (is (string? (:arguments (first (:toolCalls res)))))
          (is (= "tool_calls" (:finishReason res)))
          (is (= "" (:text res))))))))

(deftest openai-toolkit-and-tools-compose
  (with-provider [openai-text-turn]
    (fn [{:keys [base requests]}]
      (tr/translate (client-for "openai" base {})
                    {:messages [{:role "user" :content "hi"}]
                     :toolkit (probe-toolkit)
                     :tools openai-tools-fixture})
      (testing "both sources are declared, toolkit declarations FIRST"
        (is (= ["get_weather" "lookup"]
               (mapv #(get-in % [:function :name]) (:tools (first @requests)))))))))

(deftest openai-verbatim-passthrough-of-choice-and-max-tokens
  (with-provider [openai-text-turn]
    (fn [{:keys [base requests]}]
      (tr/translate (client-for "openai" base {})
                    {:messages [{:role "user" :content "hi"}]
                     :tools openai-tools-fixture
                     :toolChoice {:type "function" :function {:name "lookup"}}
                     :maxTokens 55})
      (let [body (first @requests)]
        (testing "tool_choice is passed through VERBATIM on the openai path"
          (is (= {:type "function" :function {:name "lookup"}} (:tool_choice body))))
        (is (= 55 (:max_tokens body)))))))

(deftest openai-system-handling
  (testing "an explicit :system is injected as message 0"
    (with-provider [openai-text-turn]
      (fn [{:keys [base requests]}]
        (tr/translate (client-for "openai" base {}) {:messages [{:role "user" :content "hi"}]
                                                     :system "be terse"})
        (is (= {:role "system" :content "be terse"} (first (:messages (first @requests))))))))
  (testing "the client's :system-prompt is used when the request has none"
    (with-provider [openai-text-turn]
      (fn [{:keys [base requests]}]
        (tr/translate (client-for "openai" base {:system-prompt "from client"})
                      {:messages [{:role "user" :content "hi"}]})
        (is (= {:role "system" :content "from client"} (first (:messages (first @requests))))))))
  (testing "a system message already in `messages` is NOT displaced"
    (with-provider [openai-text-turn]
      (fn [{:keys [base requests]}]
        (tr/translate (client-for "openai" base {:system-prompt "from client"})
                      {:messages [{:role "system" :content "mine"} {:role "user" :content "hi"}]})
        (is (= [{:role "system" :content "mine"} {:role "user" :content "hi"}]
               (:messages (first @requests))))))))

(defn- finish-reason-turn
  "One openai-shaped text turn stating `stated` — `:none` omits the field."
  [stated]
  {:choices [(cond-> {:message {:role "assistant" :content "x"}}
               (not= :none stated) (assoc :finish_reason stated))]})

(deftest openai-provider-finish-reason-wins-when-present
  ;; Matches js/src/translate.ts (`choice?.finish_reason ?? finishReasonFor(...)`)
  ;; and the go/python/elixir ports. See the report note on §11's prose.
  ;;
  ;; This is the port's DELIBERATE §11 divergence from the spec prose, so it is
  ;; exactly the rule that needs a fixture where passthrough and mapping DISAGREE.
  ;; Every value this file used to test ("stop", "length", "tool_calls") maps to
  ;; itself, so the two implementations were indistinguishable and replacing the
  ;; whole rule with `(finish-reason-for (seq calls) stated)` passed clean.
  ;;
  ;; All the cases share ONE server, answered in order: a server per case meant
  ;; seven more short-lived loopback listeners in a suite that already has plenty,
  ;; and that is the shape the port's known load flake lives in.
  (let [stated ["end_turn" "refusal" "max_tokens" "provider_specific_reason"
                "length" "stop" :none ""]]
    (with-provider (mapv finish-reason-turn stated)
      (fn [{:keys [base]}]
        (let [c   (client-for "openai" base {})
              got (mapv (fn [_] (:finishReason
                                 (tr/translate c {:messages [{:role "user" :content "hi"}]})))
                        stated)]
          (testing "a value the MAPPER would rewrite is passed through VERBATIM"
            ;; finish-reason-for(false,"end_turn") is "stop", "refusal" is
            ;; "content_filter" and "max_tokens" is "length" — so a mapping
            ;; implementation cannot produce any of these three answers.
            (is (= ["end_turn" "refusal" "max_tokens"] (subvec got 0 3))))
          (testing "an unknown provider value survives unrecognized, not as stop"
            (is (= "provider_specific_reason" (nth got 3))))
          (testing "a value that happens to agree with the mapper is unchanged too"
            (is (= ["length" "stop"] (subvec got 4 6))))
          (testing "only an ABSENT or blank finish_reason falls back to the mapping"
            (is (= ["stop" "stop"] (subvec got 6 8)))))))))

(deftest openai-empty-choices-is-not-fatal
  (with-provider [{:choices []}]
    (fn [{:keys [base]}]
      (let [res (tr/translate (client-for "openai" base {}) {:messages [{:role "user" :content "hi"}]})]
        (is (= "" (:text res)))
        (is (= [] (:toolCalls res)))
        (is (= "stop" (:finishReason res)))))))

;; ---------------------------------------------------------------------------
;; §11 — the entry point, anthropic-style upstream (the real translation)
;; ---------------------------------------------------------------------------

(def ^:private anthropic-tool-turn
  {:content [{:type "text" :text "let me check"}
             {:type "tool_use" :id "toolu_1" :name "get_weather" :input {:city "Chennai"}}
             {:type "tool_use" :id "toolu_2" :name "get_weather" :input {:city "Delhi"}}
             {:type "tool_use" :id "toolu_3" :name "get_weather" :input {:city "Pune"}}]
   :stop_reason "tool_use"
   :usage {:input_tokens 9 :output_tokens 6}})

(deftest anthropic-inbound-body-is-natively-blocked
  (reset! executions 0)
  (with-provider [anthropic-tool-turn]
    (fn [{:keys [base requests paths]}]
      (tr/translate (client-for "anthropic" base {})
                    {:messages [{:role "system" :content "be brief"}
                                {:role "user" :content "weather?"}
                                {:role "assistant" :content "checking"
                                 :tool_calls [{:id "c1" :type "function"
                                               :function {:name "get_weather" :arguments "{\"city\":\"Chennai\"}"}}]}
                                {:role "tool" :tool_call_id "c1" :content "sunny"}]
                     :toolkit (probe-toolkit)
                     :tools openai-tools-fixture
                     :toolChoice "required"})
      (let [body (first @requests)]
        (testing "exactly one call, at {base}/v1/messages"
          (is (= 1 (count @requests)))
          (is (= ["/v1/messages"] @paths)))
        (testing "system is hoisted into the provider's own field"
          (is (= "be brief" (:system body))))
        (testing "the assistant turn carries a tool_use block whose input is an OBJECT"
          (is (= [{:type "text" :text "checking"}
                  {:type "tool_use" :id "c1" :name "get_weather" :input {:city "Chennai"}}]
                 (:content (nth (:messages body) 1)))))
        (testing "the tool result is a tool_result block keyed by the same tool_call_id"
          (is (= [{:type "tool_result" :content "sunny" :tool_use_id "c1"}]
                 (:content (nth (:messages body) 2)))))
        (testing "declarations are native, toolkit first, and OpenAI's `parameters` is gone"
          (is (= [{:name "get_weather"
                   :description "Weather for a city"
                   :input_schema {:type "object" :properties {:city {:type "string"}}}}
                  {:name "lookup"
                   :description "Look something up"
                   :input_schema {:type "object" :properties {:q {:type "string"}}}}]
                 (:tools body))))
        (testing "tool_choice is mapped, not passed through"
          (is (= {:type "any"} (:tool_choice body))))
        (testing "max_tokens defaults to 4096"
          (is (= 4096 (:max_tokens body)))))
      (is (= 0 @executions)))))

(deftest anthropic-parallel-calls-all-returned-in-provider-order
  (with-provider [anthropic-tool-turn]
    (fn [{:keys [base]}]
      (let [res (tr/translate (client-for "anthropic" base {}) {:messages [{:role "user" :content "hi"}]})]
        (is (= "let me check" (:text res)))
        (is (= ["toolu_1" "toolu_2" "toolu_3"] (mapv :id (:toolCalls res))))
        (is (= ["Chennai" "Delhi" "Pune"]
               (mapv #(:city (json/read-str (:arguments %))) (:toolCalls res))))
        (testing "arguments is a JSON STRING, not an object"
          (is (every? string? (map :arguments (:toolCalls res)))))
        (testing "tool calls win the finish reason"
          (is (= "tool_calls" (:finishReason res))))
        (is (= {:prompt-tokens 9 :completion-tokens 6 :total-tokens 15} (:usage res)))))))

(deftest anthropic-stop-reason-mapping-over-the-wire
  (with-provider [{:content [{:type "text" :text "…"}] :stop_reason "max_tokens"}]
    (fn [{:keys [base]}]
      (is (= "length" (:finishReason (tr/translate (client-for "anthropic" base {})
                                                   {:messages [{:role "user" :content "hi"}]}))))))
  (with-provider [{:content [{:type "text" :text "no"}] :stop_reason "refusal"}]
    (fn [{:keys [base]}]
      (is (= "content_filter" (:finishReason (tr/translate (client-for "anthropic" base {})
                                                           {:messages [{:role "user" :content "hi"}]}))))))
  (with-provider [{:content [{:type "text" :text "ok"}] :stop_reason "end_turn"}]
    (fn [{:keys [base]}]
      (is (= "stop" (:finishReason (tr/translate (client-for "anthropic" base {})
                                                 {:messages [{:role "user" :content "hi"}]})))))))

(deftest anthropic-text-blocks-are-JOINED-not-taken-first
  ;; A real Anthropic turn interleaves text blocks around `tool_use`; every
  ;; fixture in both suites used to carry exactly ONE text block, so "join all
  ;; text blocks" and "return the first one" were indistinguishable and a port
  ;; that silently truncated the answer shipped green.
  (with-provider [{:content [{:type "text" :text "first, "}
                             {:type "tool_use" :id "t1" :name "get_weather" :input {:city "Pune"}}
                             {:type "text" :text "second, "}
                             {:type "thinking" :thinking "ignored"}
                             {:type "text" :text "third"}]
                   :stop_reason "tool_use"}]
    (fn [{:keys [base]}]
      (let [res (tr/translate (client-for "anthropic" base {})
                              {:messages [{:role "user" :content "hi"}]})]
        (is (= "first, second, third" (:text res))
            "ALL text blocks, in order, with no separator — a non-text block contributes nothing")
        (is (= ["t1"] (mapv :id (:toolCalls res)))
            "the interleaved tool_use is still picked up")))))

(deftest usage-honours-a-provider-total-that-is-not-the-sum
  ;; OpenAI reasoning models report total_tokens > prompt + completion (reasoning
  ;; and cached tokens). Every fixture in this repo used to be internally
  ;; consistent, so `(or (:total_tokens raw) (+ p c))` and a plain `(+ p c)`
  ;; agreed everywhere and dropping the field under-reported billed tokens
  ;; invisibly.
  (with-provider [{:choices [{:message {:role "assistant" :content "hi"} :finish_reason "stop"}]
                   :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 42}}]
    (fn [{:keys [base]}]
      (is (= {:prompt-tokens 10 :completion-tokens 5 :total-tokens 42}
             (:usage (tr/translate (client-for "openai" base {})
                                   {:messages [{:role "user" :content "hi"}]})))
          "the provider's own total wins over prompt+completion"))))

(deftest anthropic-system-precedence
  (testing ":system beats both the client prompt and a hoisted system message"
    (with-provider [{:content [{:type "text" :text "x"}]}]
      (fn [{:keys [base requests]}]
        (tr/translate (client-for "anthropic" base {:system-prompt "from client"})
                      {:messages [{:role "system" :content "hoisted"} {:role "user" :content "hi"}]
                       :system "explicit"})
        (is (= "explicit" (:system (first @requests)))))))
  (testing "the client prompt beats a hoisted system message"
    (with-provider [{:content [{:type "text" :text "x"}]}]
      (fn [{:keys [base requests]}]
        (tr/translate (client-for "anthropic" base {:system-prompt "from client"})
                      {:messages [{:role "system" :content "hoisted"} {:role "user" :content "hi"}]})
        (is (= "from client" (:system (first @requests)))))))
  (testing "with neither, the hoisted system message is used"
    (with-provider [{:content [{:type "text" :text "x"}]}]
      (fn [{:keys [base requests]}]
        (tr/translate (client-for "anthropic" base {})
                      {:messages [{:role "system" :content "hoisted"} {:role "user" :content "hi"}]})
        (is (= "hoisted" (:system (first @requests))))))))

(deftest anthropic-max-tokens-override
  (with-provider [{:content [{:type "text" :text "x"}]}]
    (fn [{:keys [base requests]}]
      (tr/translate (client-for "anthropic" base {}) {:messages [{:role "user" :content "hi"}]
                                                      :maxTokens 128})
      (is (= 128 (:max_tokens (first @requests)))))))

;; ---------------------------------------------------------------------------
;; statelessness + shared infrastructure
;; ---------------------------------------------------------------------------

(deftest repeated-calls-accumulate-no-state
  (with-provider [openai-text-turn]
    (fn [{:keys [base requests]}]
      (let [c (client-for "openai" base {})]
        (dotimes [_ 3] (tr/translate c {:messages [{:role "user" :content "hi"}]}))
        (testing "each call sends exactly the one message it was given"
          (is (= 3 (count @requests)))
          (is (= [1 1 1] (mapv #(count (:messages %)) @requests))))))))

(deftest translate-reuses-the-clients-request-shaping
  (with-provider [openai-text-turn]
    (fn [{:keys [base requests]}]
      (tr/translate (client-for "openai" base {:request-params {:temperature 0.2 :top_p 0.9}})
                    {:messages [{:role "user" :content "hi"}]})
      (testing "§8 :request-params are merged into the translate body too"
        (is (= 0.2 (:temperature (first @requests))))
        (is (= 0.9 (:top_p (first @requests))))))))

(deftest translate-emits-the-llm-metric-and-no-tool-metric
  (let [events (atom [])]
    (with-provider [openai-tool-turn]
      (fn [{:keys [base]}]
        (tr/translate (client-for "openai" base {:on-metric (fn [e] (swap! events conj e))})
                      {:messages [{:role "user" :content "hi"}]
                       :toolkit (probe-toolkit)})
        (testing "the `llm` observability event fires exactly once"
          (is (= ["llm"] (mapv :event @events))))
        (testing "no `tool` event — no tool ran"
          (is (empty? (filter #(= "tool" (:event %)) @events))))))))

(deftest translate-retries-like-the-loop
  (let [n (atom 0)
        srv (server/serve
             (fn [_req]
               (if (= 1 (swap! n inc))
                 {:status 503 :headers {} :body "busy"}
                 {:status 200
                  :headers {"content-type" "application/json"}
                  :body (json/write-str openai-text-turn)}))
             {:port 0})]
    (try
      (let [c (client/create-client {:base-url (str "http://127.0.0.1:" (server/port srv))
                                     :style "openai" :model "mock-model"
                                     :retries 2 :retry-base-ms 1})]
        (is (= "hello there" (:text (tr/translate c {:messages [{:role "user" :content "hi"}]}))))
        (is (= 2 @n)))
      (finally (server/stop! srv)))))

(deftest translate-throws-on-a-terminal-provider-error
  (let [srv (server/serve (fn [_req] {:status 400 :headers {} :body "bad request"}) {:port 0})]
    (try
      (let [c (client/create-client {:base-url (str "http://127.0.0.1:" (server/port srv))
                                     :style "openai" :model "mock-model"})]
        (is (thrown? Throwable (tr/translate c {:messages [{:role "user" :content "hi"}]}))))
      (finally (server/stop! srv)))))

;; ---------------------------------------------------------------------------
;; the envelope helper
;; ---------------------------------------------------------------------------

(deftest tool-calls-json-rebuilds-the-openai-assistant-shape
  (is (= [{:id "call_1" :type "function"
           :function {:name "get_weather" :arguments "{\"city\":\"Chennai\"}"}}]
         (tr/tool-calls-json {:toolCalls [{:id "call_1" :name "get_weather"
                                           :arguments "{\"city\":\"Chennai\"}"}]})))
  (is (= [] (tr/tool-calls-json {:toolCalls []}))))

;; ---------------------------------------------------------------------------
;; §11 + `tool-translation` — the §8 hooks on the single-turn path
;; ---------------------------------------------------------------------------
;;
;; SPEC.md §11 "Shared infrastructure": "`beforeLLM` and `afterLLM` each fire
;; EXACTLY ONCE. Tool hooks do NOT fire — no tool runs." The capability spec
;; (openspec/specs/tool-translation) says the same in requirement form. Both
;; upstream styles, because they are two separate code paths.

(defn- hook-recorder
  "All four §8 hooks, each appending [tag turn] to `seen`. The LLM hooks return
  nil so they observe only."
  [seen]
  {:before-llm  (fn [ev] (swap! seen conj [:before-llm (:turn ev)]) nil)
   :after-llm   (fn [ev] (swap! seen conj [:after-llm (:turn ev)]) nil)
   :before-tool (fn [ev] (swap! seen conj [:before-tool (:turn ev)]) nil)
   :after-tool  (fn [ev] (swap! seen conj [:after-tool (:turn ev)]) nil)})

(deftest translate-fires-llm-hooks-once-and-no-tool-hooks-openai
  (reset! executions 0)
  (let [seen (atom [])]
    (with-provider [openai-tool-turn]
      (fn [{:keys [base]}]
        (tr/translate (client-for "openai" base {:hooks (hook-recorder seen)})
                      {:messages [{:role "user" :content "weather?"}]
                       :toolkit (probe-toolkit)})
        (testing "beforeLLM and afterLLM each fire exactly once, in that order"
          (is (= [[:before-llm 0] [:after-llm 0]] @seen)))
        (testing "no tool hook fires, because no tool runs"
          (is (zero? @executions)))))))

(deftest translate-fires-llm-hooks-once-and-no-tool-hooks-anthropic
  (reset! executions 0)
  (let [seen (atom [])]
    (with-provider [anthropic-tool-turn]
      (fn [{:keys [base]}]
        (tr/translate (client-for "anthropic" base {:hooks (hook-recorder seen)})
                      {:messages [{:role "user" :content "weather?"}]
                       :toolkit (probe-toolkit)})
        (is (= [[:before-llm 0] [:after-llm 0]] @seen))
        (is (zero? @executions))))))

(deftest translate-after-llm-sees-the-raw-provider-payload
  (let [seen (atom [])]
    (with-provider [openai-tool-turn]
      (fn [{:keys [base]}]
        (tr/translate (client-for "openai" base
                                  {:hooks {:after-llm (fn [ev] (swap! seen conj ev) nil)}})
                      {:messages [{:role "user" :content "hi"}]})
        (is (= 1 (count @seen)))
        (is (= "mock-model" (:model (first @seen))))
        (is (= "tool_calls" (get-in (first @seen) [:response :choices 0 :finish_reason])))))))

(deftest translate-before-llm-can-replace-messages-and-tools
  (with-provider [openai-text-turn]
    (fn [{:keys [base requests]}]
      (tr/translate (client-for "openai" base
                                {:hooks {:before-llm (fn [_ev]
                                                       {:messages [{:role "user" :content "rewritten"}]
                                                        :tools []})}})
                    {:messages [{:role "user" :content "hi"}]
                     :toolkit (probe-toolkit)})
      (let [body (first @requests)]
        (is (= [{:role "user" :content "rewritten"}] (:messages body)))
        (is (nil? (:tools body)) "an empty tool list omits the key entirely")))))

(deftest translate-before-llm-runs-before-request-params-and-body-transform
  ;; SPEC.md §8 Gap 1 ordering, on the §11 path:
  ;;   base body -> beforeLLM -> :request-params -> :body-transform -> wire
  (with-provider [openai-text-turn]
    (fn [{:keys [base requests]}]
      (tr/translate (client-for "openai" base
                                {:hooks {:before-llm (fn [_ev] {:messages [{:role "user" :content "hooked"}]})}
                                 :request-params {:temperature 0.2}
                                 :body-transform (fn [b]
                                                   (is (= [{:role "user" :content "hooked"}] (:messages b)))
                                                   (is (= 0.2 (:temperature b)))
                                                   (assoc b :ordered "yes"))})
                    {:messages [{:role "user" :content "hi"}]})
      (let [body (first @requests)]
        (is (= [{:role "user" :content "hooked"}] (:messages body)))
        (is (= 0.2 (:temperature body)))
        (is (= "yes" (:ordered body)))))))
