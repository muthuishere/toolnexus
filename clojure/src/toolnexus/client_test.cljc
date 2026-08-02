;; toolnexus.client — the suite. One .cljc, run identically on Clojure (JVM)
;; and on cljgo (S24's arrangement: an ordinary `-test` namespace in the single
;; src/ tree, executed in-process by clojure.test/run-tests with a SYMBOL).
;;
;; The "LLM" is a real koine.server on 127.0.0.1:0 replaying a scripted
;; conversation — OpenAI-shaped or Anthropic-shaped — and it RECORDS every
;; request body, so what the client feeds back is asserted rather than assumed.
;; Hermetic: no API key, no network beyond loopback, no live model.
;;
;; No java.*, no Thread/sleep (koine.time/sleep!), no reader conditionals.
(ns toolnexus.client-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.http :as http]
            [koine.json :as json]
            [koine.server :as server]
            [koine.time :as ktime]
            [toolnexus.builtin :as builtin]
            [toolnexus.client :as client]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; the scripted LLM
;; ---------------------------------------------------------------------------
;;
;; A script is a vector of turn specs:
;;   {:calls [{:id "c1" :name "upper" :args {...}} ...]}   -> a tool-calling turn
;;   {:text "final answer"}                                -> a terminal turn
;; Anything past the end of the script answers with text, which ends the loop.

(defn- calls-response [style calls]
  (if (= "anthropic" style)
    {:content (mapv (fn [c] {:type "tool_use" :id (:id c) :name (:name c)
                             :input (or (:args c) {})})
                    calls)
     :usage {:input_tokens 7 :output_tokens 3}}
    {:choices [{:message {:role "assistant" :content nil
                          :tool_calls (mapv (fn [c] {:id (:id c) :type "function"
                                                     :function {:name (:name c)
                                                                :arguments (json/write-str (or (:args c) {}))}})
                                            calls)}}]
     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}))

(defn- text-response [style text]
  (if (= "anthropic" style)
    {:content [{:type "text" :text text}] :usage {:input_tokens 7 :output_tokens 3}}
    {:choices [{:message {:role "assistant" :content text}}]
     :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}))

(defn- script-response [style script n]
  (let [spec (get script (dec n))]
    (cond
      ;; a VERBATIM provider payload, for shapes the two helpers above cannot
      ;; build (an Anthropic turn with several text blocks, a usage block whose
      ;; total is not the sum of its parts)
      (:raw spec)        (:raw spec)
      (seq (:calls spec)) (calls-response style (:calls spec))
      :else               (text-response style (or (:text spec) "done")))))

(defn- lower-keys
  "Header CASE is a host detail (the JVM's server lowercases, cljgo's does not
  promise to), so every header assertion normalises first."
  [m]
  (reduce (fn [acc [k v]] (assoc acc (str/lower-case (name k)) v)) {} m))

(defn with-llm
  "Start a scripted LLM, hand `f` a map {:base :requests :paths :headers}, stop
  it after. `:requests` is an atom of the PARSED request bodies, in arrival
  order — the only way to assert on what the client actually fed back;
  `:headers` is the matching atom of REQUEST headers (lowercased), which is what
  makes the auth / api-key path observable at all."
  [style script f]
  (let [n        (atom 0)
        requests (atom [])
        paths    (atom [])
        headers  (atom [])
        srv      (server/serve
                   (fn [req]
                     (swap! paths conj (:path req))
                     (swap! headers conj (lower-keys (:headers req)))
                     (swap! requests conj (json/read-str (str (:body req))))
                     {:status 200
                      :headers {"content-type" "application/json"}
                      :body (json/write-str (script-response style script (swap! n inc)))})
                   {:port 0})]
    (try
      (f {:base (str "http://127.0.0.1:" (server/port srv))
          :requests requests
          :paths paths
          :headers headers})
      (finally (server/stop! srv)))))

;; ---------------------------------------------------------------------------
;; the tools
;; ---------------------------------------------------------------------------
;;
;; A suspending tool is MULTI-ARITY on purpose: §1's Context is optional, and in
;; Clojure "optional trailing argument" is arity. toolnexus.tool/execute calls
;; (f args) with no ctx and (f args ctx) with one, so the first execution and
;; the post-waitFor retry land on different arities of the same fn.

(defn- mk [nm f]
  (tool/tool {:name nm :description (str nm " tool") :execute f}))

(def city-schema
  {:type "object" :properties {:city {:type "string"}} :required ["city"]})

(def tools
  [(mk "upper" (fn [args] (tool/success (str/upper-case (str (:text args))))))
   (mk "boom"  (fn [_args] (throw (ex-info "kaboom" {}))))
   (mk "slow"  (fn [_args] (ktime/sleep! 250) (tool/success "slow")))
   (mk "quick" (fn [_args] (tool/success "quick")))

   ;; kind:"authorization" — the retry IGNORES answer.data; the world changed
   ;; out of band and the session is simply valid now.
   (mk "login" (fn ([_args] (client/auth-required "https://127.0.0.1/authorize"
                                                  "Log in to continue"))
                 ([_args ctx] (if (get-in ctx [:answer :ok])
                                (tool/success "session valid")
                                (client/auth-required "https://127.0.0.1/authorize"
                                                      "Log in to continue")))))

   ;; kind:"input" — the resolution IS the payload (and carries R2's
   ;; data.schema so a generic host can render/validate it).
   (mk "ask_city" (fn ([_args]
                       (client/suspend
                         (client/make-request "input" "Which city?"
                                              {:data {:schema city-schema}
                                               :expiresAt (ktime/iso-str (+ (ktime/now-ms) 300000))})
                         "Input required: Which city?"))
                    ([_args ctx] (tool/success (json/write-str (get-in ctx [:answer :data]))))))

   ;; suspends AGAIN on the retry — the "never loop forever" path.
   (mk "stubborn" (fn ([_args] (client/suspend (client/make-request "approval" "Approve the impossible")))
                    ([_args _ctx] (client/suspend (client/make-request "approval" "Approve the impossible")))))])

(def toolkit (tool/toolkit tools))

(defn- client-for [style base opts]
  (client/create-client (merge {:base-url base :style style :model "mock-model"} opts)))

(defn- wait-ok [request]
  (client/make-answer (:id request) true
                      (case (:kind request)
                        "input" {:city "Chennai"}
                        nil)))

(defn- wait-declined [request]
  (client/make-answer (:id request) false nil "declined"))

(defn- names-of [rr] (mapv :name (:tool-calls rr)))
(defn- outputs-of [rr] (mapv :output (:tool-calls rr)))
(defn- key-names [m] (vec (sort (map name (keys m)))))

(defn- first-index
  "Index of the first item equal to `x`, or -1. Hand-rolled: `.indexOf` is Java
  interop and this suite must run on cljgo too."
  [coll x]
  (or (first (keep-indexed (fn [i v] (when (= v x) i)) coll)) -1))

;; ---------------------------------------------------------------------------
;; §0.10 — the loop, openai style
;; ---------------------------------------------------------------------------

(deftest openai-multi-turn-loop
  (with-llm "openai"
    [{:calls [{:id "c1" :name "upper" :args {:text "toolnexus"}}]}
     {:calls [{:id "c2" :name "upper" :args {:text "again"}}]}
     {:text "all done"}]
    (fn [{:keys [base requests paths]}]
      (let [rr (client/run (client-for "openai" base {:system-prompt "you are a test"})
                           "go" {:toolkit toolkit})]
        (testing "the loop ran to a text answer"
          (is (= "all done" (:text rr)))
          (is (= "done" (:status rr)))
          (is (= 3 (:turns rr)))
          (is (= 2 (:tool-call-count rr)))
          (is (= ["TOOLNEXUS" "AGAIN"] (outputs-of rr))))
        (testing "usage is summed across turns"
          (is (= {:prompt-tokens 30 :completion-tokens 15 :total-tokens 45} (:usage rr))))
        (testing "the endpoint is {base}/chat/completions"
          (is (= ["/chat/completions" "/chat/completions" "/chat/completions"] @paths)))
        (testing "openai tool results go back as {role:tool, tool_call_id, content}"
          (let [second-req (get @requests 1)
                msgs (:messages second-req)
                last-msg (last msgs)]
            (is (= "tool" (:role last-msg)))
            (is (= "c1" (:tool_call_id last-msg)))
            (is (= "TOOLNEXUS" (:content last-msg)))))
        (testing "system = systemPrompt + \\n\\n + skillsPrompt, as message 0"
          (is (= {:role "system" :content "you are a test"}
                 (first (:messages (first @requests))))))
        (testing "the openai tool schema is {type:function, function:{...}}"
          (let [t (first (:tools (first @requests)))]
            (is (= "function" (:type t)))
            (is (= ["ask_city" "boom" "login" "quick" "slow" "stubborn" "upper"]
                   (mapv (fn [x] (get-in x [:function :name])) (:tools (first @requests)))))
            (is (= {:type "object"} (get-in t [:function :parameters])))
            (is (= "auto" (:tool_choice (first @requests))))))))))

(deftest skills-prompt-joins-with-two-newlines
  (with-llm "openai" [{:text "hi"}]
    (fn [{:keys [base requests]}]
      (let [tk (assoc toolkit :skills-prompt "## Available Skills\n- **a**: b")]
        (client/run (client-for "openai" base {:system-prompt "sys"}) "go" {:toolkit tk})
        (is (= "sys\n\n## Available Skills\n- **a**: b"
               (:content (first (:messages (first @requests))))))))))

;; ---------------------------------------------------------------------------
;; §0.10 — the loop, anthropic style (the wire shape S16 never exercised)
;; ---------------------------------------------------------------------------

(deftest anthropic-multi-turn-loop
  (with-llm "anthropic"
    [{:calls [{:id "u1" :name "upper" :args {:text "toolnexus"}}
              {:id "u2" :name "quick"}]}
     {:text "all done"}]
    (fn [{:keys [base requests paths]}]
      (let [rr (client/run (client-for "anthropic" base {:system-prompt "you are a test"})
                           "go" {:toolkit toolkit})]
        (testing "the anthropic loop reaches the same RunResult shape"
          (is (= "all done" (:text rr)))
          (is (= "done" (:status rr)))
          (is (= 2 (:turns rr)))
          (is (= ["upper" "quick"] (names-of rr)))
          (is (= ["TOOLNEXUS" "quick"] (outputs-of rr))))
        (testing "anthropic usage maps input/output tokens onto prompt/completion"
          (is (= {:prompt-tokens 14 :completion-tokens 6 :total-tokens 20} (:usage rr))))
        (testing "the endpoint is {base}/v1/messages"
          (is (= ["/v1/messages" "/v1/messages"] @paths)))
        (testing "system rides the BODY, not a message, and max_tokens is set"
          (let [req (first @requests)]
            (is (= "you are a test" (:system req)))
            (is (= 4096 (:max_tokens req)))
            (is (= [{:role "user" :content "go"}] (:messages req)))))
        (testing "the anthropic tool schema is {name, description, input_schema}"
          (let [t (first (:tools (first @requests)))]
            (is (= ["description" "input_schema" "name"] (key-names t)))
            (is (= {:type "object"} (:input_schema t)))))
        (testing "results go back as ONE user message of tool_result blocks, in call order"
          (let [msgs (:messages (get @requests 1))
                assistant (get msgs 1)
                results (get msgs 2)]
            (is (= 3 (count msgs)))
            (is (= "assistant" (:role assistant)))
            (is (= "tool_use" (:type (first (:content assistant)))))
            (is (= "user" (:role results)))
            (is (= [{:type "tool_result" :tool_use_id "u1" :content "TOOLNEXUS" :is_error false}
                    {:type "tool_result" :tool_use_id "u2" :content "quick"     :is_error false}]
                   (:content results)))))))))

(deftest anthropic-final-text-joins-every-text-block
  ;; A real Anthropic turn interleaves text blocks around `tool_use`, and every
  ;; fixture in this suite used to carry exactly ONE — so "join all text blocks"
  ;; and "return the first one" were indistinguishable, and a loop that silently
  ;; truncated the model's answer passed clean.
  (with-llm "anthropic"
    [{:raw {:content [{:type "text" :text "part one. "}
                      {:type "text" :text "part two. "}
                      {:type "thinking" :thinking "not text — contributes nothing"}
                      {:type "text" :text "part three."}]
            :usage {:input_tokens 7 :output_tokens 3}}}]
    (fn [{:keys [base]}]
      (let [rr (client/run (client-for "anthropic" base {}) "go" {:toolkit toolkit})]
        (is (= "part one. part two. part three." (:text rr))
            "ALL text blocks, in order, with no separator")
        (is (= "done" (:status rr)))))))

(deftest usage-honours-a-provider-total-that-is-not-the-sum
  ;; OpenAI reasoning models report total_tokens > prompt + completion (reasoning
  ;; / cached tokens). Every usage fixture in this repo used to be internally
  ;; consistent, so `(or (:total_tokens raw) (+ p c))` and a plain `(+ p c)`
  ;; agreed on all of them and dropping the field under-reported billed tokens
  ;; invisibly. Asserted on the unit AND over two summed turns.
  (testing "one payload"
    (is (= {:prompt-tokens 10 :completion-tokens 5 :total-tokens 42}
           (client/add-usage client/zero-usage {:style "openai"}
                             {:prompt_tokens 10 :completion_tokens 5 :total_tokens 42}))))
  (testing "summed across turns, each keeping its own stated total"
    (is (= {:prompt-tokens 20 :completion-tokens 10 :total-tokens 84}
           (-> client/zero-usage
               (client/add-usage {:style "openai"}
                                 {:prompt_tokens 10 :completion_tokens 5 :total_tokens 42})
               (client/add-usage {:style "openai"}
                                 {:prompt_tokens 10 :completion_tokens 5 :total_tokens 42})))))
  (testing "absent total_tokens still falls back to prompt+completion"
    (is (= {:prompt-tokens 10 :completion-tokens 5 :total-tokens 15}
           (client/add-usage client/zero-usage {:style "openai"}
                             {:prompt_tokens 10 :completion_tokens 5}))))
  (testing "and it reaches the RunResult through the loop"
    (with-llm "openai"
      [{:raw {:choices [{:message {:role "assistant" :content "done"}}]
              :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 42}}}]
      (fn [{:keys [base]}]
        (is (= {:prompt-tokens 10 :completion-tokens 5 :total-tokens 42}
               (:usage (client/run (client-for "openai" base {}) "go" {:toolkit toolkit}))))))))

(deftest anthropic-base-url-already-versioned
  (with-llm "anthropic" [{:text "hi"}]
    (fn [{:keys [base paths]}]
      (client/run (client-for "anthropic" (str base "/v1") {}) "go" {:toolkit toolkit})
      (is (= ["/v1/messages"] @paths) "a /v1 suffix is not doubled"))))

;; ---------------------------------------------------------------------------
;; request headers — the auth path
;; ---------------------------------------------------------------------------
;;
;; Nothing used to assert `(:headers req)` at all, so `request-headers` could
;; return `{}` — no authorization, no x-api-key, no anthropic-version, `:headers`
;; silently dropped — and the whole suite stayed green. A client that sends the
;; Anthropic key in the OpenAI header is exactly the regression a provider-style
;; switch produces, and it would have shipped.
;;
;; Values here are obvious fakes passed as `:api-key`, never a real credential
;; and never read from the environment: the env fallback chain cannot be tested
;; portably (no host-neutral way to SET a variable in-process), so `:api-key` is
;; asserted to WIN, which is the half that is testable.

(deftest openai-sends-a-bearer-authorization-header
  (with-llm "openai" [{:text "hi"}]
    (fn [{:keys [base headers]}]
      (client/run (client-for "openai" base {:api-key "FAKE_NOT_A_SECRET"}) "go"
                  {:toolkit toolkit})
      (let [h (first @headers)]
        (is (= "Bearer FAKE_NOT_A_SECRET" (get h "authorization")))
        (is (nil? (get h "x-api-key")) "the anthropic header must not appear on this style")
        (is (nil? (get h "anthropic-version")))
        (is (= "application/json" (get h "content-type")))))))

(deftest anthropic-sends-x-api-key-and-the-pinned-version
  (with-llm "anthropic" [{:text "hi"}]
    (fn [{:keys [base headers]}]
      (client/run (client-for "anthropic" base {:api-key "FAKE_NOT_A_SECRET"}) "go"
                  {:toolkit toolkit})
      (let [h (first @headers)]
        (is (= "FAKE_NOT_A_SECRET" (get h "x-api-key"))
            "anthropic takes the raw key in x-api-key, NOT a Bearer authorization")
        (is (= "2023-06-01" (get h "anthropic-version"))
            "the version header is a pinned constant; anthropic rejects a request without it")
        (is (nil? (get h "authorization")) "the openai header must not appear on this style")))))

(deftest client-headers-are-merged-and-can-override
  (with-llm "openai" [{:text "hi"}]
    (fn [{:keys [base headers]}]
      (client/run (client-for "openai" base {:api-key "FAKE_NOT_A_SECRET"
                                             :headers {"x-tenant" "acme"
                                                       "authorization" "Bearer OVERRIDDEN"}})
                  "go" {:toolkit toolkit})
      (let [h (first @headers)]
        (is (= "acme" (get h "x-tenant")) ":headers must reach the wire")
        (is (= "Bearer OVERRIDDEN" (get h "authorization"))
            ":headers is merged LAST, so a host can replace the auth scheme entirely")))))

(deftest an-explicit-api-key-beats-the-environment
  ;; resolve-key's first branch. The env fallback itself (OPENAI_API_KEY /
  ;; ANTHROPIC_API_KEY / OPENROUTER_API_KEY) is NOT covered — see the note above.
  (with-llm "openai" [{:text "hi"}]
    (fn [{:keys [base headers]}]
      (client/run (client-for "openai" base {:api-key "FAKE_EXPLICIT"}) "go" {:toolkit toolkit})
      (is (= "Bearer FAKE_EXPLICIT" (get (first @headers) "authorization"))))))

;; ---------------------------------------------------------------------------
;; §8 — parallel tool calls, results in CALL ORDER
;; ---------------------------------------------------------------------------

(deftest parallel-tool-calls-keep-call-order
  (with-llm "openai"
    [{:calls [{:id "s1" :name "slow"}
              {:id "q1" :name "quick"}
              {:id "s2" :name "slow"}]}
     {:text "ok"}]
    (fn [{:keys [base requests]}]
      (let [t0 (ktime/mono-ms)
            rr (client/run (client-for "openai" base {}) "go" {:toolkit toolkit})
            elapsed (ktime/elapsed-ms t0)]
        (testing "results are in CALL order, not completion order"
          (is (= ["slow" "quick" "slow"] (names-of rr)))
          (is (= ["slow" "quick" "slow"] (outputs-of rr)))
          (is (= ["s1" "q1" "s2"]
                 (mapv :tool_call_id (filter #(= "tool" (:role %))
                                             (:messages (get @requests 1)))))))
        (testing "and they really ran in parallel (2x250ms sequential would exceed this)"
          (is (< elapsed 450) (str "elapsed " elapsed "ms")))))))

;; ---------------------------------------------------------------------------
;; §0.8 — a throwing tool is an error VALUE, not a crash
;; ---------------------------------------------------------------------------

(deftest throwing-tool-becomes-an-error-result
  (with-llm "openai"
    [{:calls [{:id "b1" :name "boom"} {:id "u1" :name "upper" :args {:text "x"}}]}
     {:text "recovered"}]
    (fn [{:keys [base]}]
      (let [rr (client/run (client-for "openai" base {}) "go" {:toolkit toolkit})]
        (is (= "recovered" (:text rr)))
        (is (= "done" (:status rr)))
        (is (= [true false] (mapv :isError (:tool-calls rr))))
        (is (= "kaboom" (:output (first (:tool-calls rr)))))
        (is (= "X" (:output (second (:tool-calls rr)))))))))

;; ---------------------------------------------------------------------------
;; §0.10 — maxTurns
;; ---------------------------------------------------------------------------

(deftest max-turns-stops-the-loop-loudly
  (with-llm "openai"
    (vec (repeat 10 {:calls [{:id "u" :name "upper" :args {:text "a"}}]}))
    (fn [{:keys [base]}]
      (let [rr (client/run (client-for "openai" base {:max-turns 3}) "go" {:toolkit toolkit})]
        (is (= 3 (:turns rr)))
        (is (= 3 (:tool-call-count rr)))
        (is (= "incomplete" (:status rr)))
        (is (= "maxTurns" (:limit rr)))))))

(deftest max-turns-defaults-to-ten
  (is (= 10 (:max-turns (client/create-client {:base-url "http://x" :model "m"})))))

;; ---------------------------------------------------------------------------
;; §10 — the three waitFor paths
;; ---------------------------------------------------------------------------

(deftest suspension-resolves-and-re-executes-once
  (with-llm "openai"
    [{:calls [{:id "l1" :name "login"} {:id "a1" :name "ask_city"}]}
     {:text "resumed"}]
    (fn [{:keys [base]}]
      (let [events (atom [])
            rr (client/run (client-for "openai" base {:wait-for wait-ok}) "go"
                           {:toolkit toolkit :on-event #(swap! events conj %)})]
        (testing "ok => re-execute the same tool once with Context.answer"
          (is (= "done" (:status rr)))
          (is (nil? (:pending rr)))
          (is (= "resumed" (:text rr)))
          (is (= ["session valid" "{\"city\":\"Chennai\"}"] (outputs-of rr)))
          (is (= [false false] (mapv :isError (:tool-calls rr)))))
        (testing "a `pending` event is emitted for each suspension, before waitFor"
          (let [pendings (filterv #(= "pending" (:type %)) @events)]
            (is (= 2 (count pendings)))
            (is (= ["authorization" "input"] (mapv #(get-in % [:request :kind]) pendings)))
            ;; the pending event precedes that call's tool_result event
            (is (< (first-index (mapv :type @events) "pending")
                   (first-index (mapv :type @events) "tool_result")))))))))

(deftest suspension-declined-feeds-back-an-error
  (with-llm "openai"
    [{:calls [{:id "l1" :name "login"}]} {:text "moved on"}]
    (fn [{:keys [base]}]
      (let [rr (client/run (client-for "openai" base {:wait-for wait-declined}) "go"
                           {:toolkit toolkit})]
        (is (= "done" (:status rr)))
        (is (= "moved on" (:text rr)))
        (is (= ["declined/expired: Log in to continue"] (outputs-of rr)))
        (is (= [true] (mapv :isError (:tool-calls rr))))))))

(deftest double-suspension-never-loops
  (with-llm "openai"
    [{:calls [{:id "s1" :name "stubborn"}]} {:text "gave up"}]
    (fn [{:keys [base]}]
      (let [rr (client/run (client-for "openai" base {:wait-for wait-ok}) "go"
                           {:toolkit toolkit})]
        (is (= "done" (:status rr)))
        (is (= ["unresolved: Approve the impossible"] (outputs-of rr)))
        (is (= [true] (mapv :isError (:tool-calls rr))))))))

;; ---------------------------------------------------------------------------
;; §10 rule 2 — no waitFor: the run RETURNS, it does not hang
;; ---------------------------------------------------------------------------

(deftest pending-without-wait-for-returns-rather-than-blocking
  (with-llm "openai"
    [{:calls [{:id "l1" :name "login"}]} {:text "never reached"}]
    (fn [{:keys [base requests]}]
      (let [t0 (ktime/mono-ms)
            rr (client/run (client-for "openai" base {}) "go" {:toolkit toolkit})
            elapsed (ktime/elapsed-ms t0)]
        (testing "status pending, carrying the Request"
          (is (= "pending" (:status rr)))
          (is (= "authorization" (get-in rr [:pending :kind])))
          (is (= "Log in to continue" (get-in rr [:pending :prompt])))
          (is (= "Log in to continue" (:text rr)) "text is the unanswered prompt"))
        (testing "it returned instead of blocking"
          ;; if run had hung, this assertion would never be reached at all;
          ;; the bound makes an accidental long block visible too.
          (is (< elapsed 5000)))
        (testing "the halted tool's placeholder is in the transcript"
          (let [msgs (:messages rr)]
            (is (= ["user" "assistant" "tool"] (mapv :role msgs)))
            (is (= "Log in to continue" (:content (last msgs))))))
        (testing "the loop stopped: only ONE LLM round trip happened"
          (is (= 1 (count @requests)))
          (is (= 1 (:turns rr))))))))

(deftest concurrent-suspensions-halt-on-the-first-in-call-order
  (with-llm "openai"
    [{:calls [{:id "s1" :name "stubborn"}
              {:id "l1" :name "login"}
              {:id "a1" :name "ask_city"}]}
     {:text "never reached"}]
    (fn [{:keys [base]}]
      (let [rr (client/run (client-for "openai" base {}) "go" {:toolkit toolkit})]
        (is (= "pending" (:status rr)))
        (is (= "approval" (get-in rr [:pending :kind])) "first in tool-call order wins")
        (is (= 1 (:tool-call-count rr)) "later suspensions' placeholders never enter")
        (is (= ["user" "assistant" "tool"] (mapv :role (:messages rr))))))))

(deftest anthropic-suspension-halts-the-same-way
  (with-llm "anthropic"
    [{:calls [{:id "l1" :name "login"}]} {:text "never reached"}]
    (fn [{:keys [base]}]
      (let [rr (client/run (client-for "anthropic" base {}) "go" {:toolkit toolkit})]
        (is (= "pending" (:status rr)))
        (is (= "Log in to continue" (get-in rr [:pending :prompt])))
        (is (= ["user" "assistant" "user"] (mapv :role (:messages rr))))
        (is (= [{:type "tool_result" :tool_use_id "l1"
                 :content "Log in to continue" :is_error true}]
               (:content (last (:messages rr)))))))))

;; ---------------------------------------------------------------------------
;; §10 — the exact Request / Answer key sets (byte-identical across ports)
;; ---------------------------------------------------------------------------

(deftest request-and-answer-key-sets-are-pinned
  (testing "Request = {id,kind,prompt,url?,data?,expiresAt?}; absent optionals are OMITTED"
    (is (= ["id" "kind" "prompt"] (key-names (client/make-request "approval" "p"))))
    (is (= ["id" "kind" "prompt" "url"]
           (key-names (client/make-request "authorization" "p" {:url "https://x"}))))
    (is (= ["data" "expiresAt" "id" "kind" "prompt" "url"]
           (key-names (client/make-request "input" "p" {:url "u" :data {:a 1}
                                                        :expiresAt "2026-01-01T00:00:00Z"})))))
  (testing "the camelCase wire key survives serialization verbatim (§10, S23 finding 3)"
    (is (str/includes? (json/write-str (client/make-request "input" "p" {:expiresAt "2026-01-01T00:00:00Z"}))
                       "\"expiresAt\":"))
    (is (str/includes? (json/write-str (client/suspend (client/make-request "input" "p")))
                       "\"isError\":true")))
  (testing "Answer = {id,ok,data?,reason?}; ok is a JSON boolean"
    (is (= ["id" "ok"] (key-names (client/make-answer "x" true))))
    (is (= ["data" "id" "ok"] (key-names (client/make-answer "x" true {:city "Chennai"}))))
    (is (= ["id" "ok" "reason"] (key-names (client/make-answer "x" false nil "declined"))))
    (is (str/includes? (json/write-str (client/make-answer "x" true)) "\"ok\":true")))
  (testing "R1 — reason is populated ONLY when ok == false"
    (is (not (contains? (client/make-answer "x" true nil "declined") :reason))))
  (testing "R2 — data.schema rides under `data`, leaving the Request shape unchanged"
    (let [req (client/pending-of ((:execute (get (:tools toolkit) "ask_city")) {}))]
      (is (= ["data" "expiresAt" "id" "kind" "prompt"] (key-names req)))
      (is (= ["properties" "required" "type"] (key-names (get-in req [:data :schema]))))))
  (testing "ids are unique per suspension"
    (is (not= (client/new-request-id) (client/new-request-id))))
  (testing "a suspension IS a ToolResult carrying metadata.pending"
    (let [r (client/auth-required "https://x" "Log in")]
      (is (= true (:isError r)))
      (is (= "Login required: https://x" (:output r)))
      (is (= "authorization" (:kind (client/pending-of r))))
      (is (nil? (client/pending-of (tool/success "plain")))))))

;; ---------------------------------------------------------------------------
;; §4A + §10 — a REAL suspending builtin, end to end through the loop
;; ---------------------------------------------------------------------------
;;
;; Not a fixture tool: `toolnexus.builtin`'s `question` is the canonical
;; kind:"question" producer, written by another namespace against the same
;; §1 Context contract. If the client's arity convention or its pending
;; detection drifted from it, this is where it shows.

(deftest builtin-question-suspends-and-resumes-through-the-client
  (with-llm "openai"
    [{:calls [{:id "q1" :name "question"
               :args {:questions [{:question "Ship it?" :header "release"
                                   :options ["yes" "no"]}]}}]}
     {:text "shipped"}]
    (fn [{:keys [base]}]
      (let [tk   (tool/toolkit builtin/builtin-tools)
            seen (atom nil)
            wf   (fn [req]
                   (reset! seen req)
                   (client/make-answer (:id req) true {:answers ["yes"]}))
            rr   (client/run (client-for "openai" base {:wait-for wf}) "go" {:toolkit tk})]
        (testing "the client saw the builtin's Request"
          (is (= "question" (:kind @seen)))
          (is (= "Ship it? (options: yes, no)" (:prompt @seen)))
          (is (= [{:question "Ship it?" :header "release" :options ["yes" "no"]}]
                 (get-in @seen [:data :questions]))))
        (testing "and the resolution IS the answer, verbatim"
          (is (= "done" (:status rr)))
          (is (= "shipped" (:text rr)))
          (is (= ["{\"answers\":[\"yes\"]}"] (outputs-of rr))))))))

;; ---------------------------------------------------------------------------
;; client-request-shaping — :request-params and :body-transform
;; ---------------------------------------------------------------------------
;;
;; Written TEST-FIRST, and the expected values come from the capability spec
;; (openspec/specs/client-request-shaping), never from this port's own output.
;; The ordering contract there is:
;;
;;   base body -> RequestParams merge -> BodyTransform -> marshal -> wire
;;
;; with RequestParams WINNING on collision, and `messages`/`tools`/`stream`
;; stripped because message rewriting belongs to BodyTransform.
;;
;; These assert on the REQUEST BODY the fake LLM actually received, not on a
;; return value, because "what went on the wire" is the only thing the spec
;; constrains and the only thing another port could disagree about.

(deftest request-params-appear-at-the-top-level
  (with-llm "openai" [{:text "done"}]
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"
                                     :request-params {:temperature 0.2
                                                      :provider_extra "x"}})]
        (client/run c "hi" {:toolkit (tool/toolkit tools)})
        (let [body (first @requests)]
          (is (= 0.2 (:temperature body)))
          (is (= "x" (:provider_extra body))))))))

(deftest request-params-win-on-collision
  ;; §client-request-shaping: "RequestParams wins on collision" — the anthropic
  ;; style sets max_tokens 4096 itself, so this is the case the spec names.
  (with-llm "anthropic" [{:text "done"}]
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"
                                     :style "anthropic"
                                     :request-params {:max_tokens 99}})]
        (client/run c "hi" {:toolkit (tool/toolkit tools)})
        (is (= 99 (:max_tokens (first @requests)))
            "the client's own default 4096 must lose to request-params")))))

(deftest request-params-cannot-set-forbidden-keys
  (with-llm "openai" [{:text "done"}]
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"
                                     :request-params {:messages [{:role "user" :content "hijacked"}]
                                                      :tools []
                                                      :stream true}})]
        (client/run c "hi" {:toolkit (tool/toolkit tools)})
        (let [body (first @requests)]
          (is (= "hi" (:content (last (:messages body))))
              "messages must come from the loop, never from request-params")
          (is (nil? (:stream body)))
          (is (seq (:tools body)) "the toolkit's tools must survive"))))))

(deftest body-transform-runs-last-and-its-output-is-sent
  (with-llm "openai" [{:text "done"}]
    (fn [{:keys [base requests]}]
      (let [c (client/create-client
               {:base-url base :model "m" :api-key "k"
                :request-params {:temperature 0.2}
                ;; it must observe the POST-MERGE body and its output is final
                :body-transform (fn [b]
                                  (is (= 0.2 (:temperature b))
                                      "body-transform must see the merged body")
                                  (-> b (dissoc :temperature) (assoc :added "yes")))})]
        (client/run c "hi" {:toolkit (tool/toolkit tools)})
        (let [body (first @requests)]
          (is (nil? (:temperature body)) "a key dropped by body-transform must not be sent")
          (is (= "yes" (:added body))))))))

(deftest neither-option-leaves-the-body-untouched
  ;; The spec's own guarantee: absent => byte-identical to today. Asserted by
  ;; comparing two runs rather than against a snapshot of our own output.
  (let [capture (fn [opts]
                  (let [out (atom nil)]
                    (with-llm "openai" [{:text "done"}]
                      (fn [{:keys [base requests]}]
                        (client/run (client/create-client
                                     (merge {:base-url base :model "m" :api-key "k"} opts))
                                    "hi" {:toolkit (tool/toolkit tools)})
                        (reset! out (json/write-str (first @requests)))))
                    @out))]
    (is (= (capture {})
           (capture {:request-params nil :body-transform nil}))
        "explicitly-nil options must be indistinguishable from absent ones")))

;; ---------------------------------------------------------------------------
;; resilience-policy + injectable transport
;; ---------------------------------------------------------------------------
;;
;; Expected behaviour is taken from openspec/specs/resilience-policy and
;; client-request-shaping, NOT from this port. Two things the specs pin that are
;; easy to get wrong:
;;
;;   * `:on-error` classifies into retry | fail ONLY. The archived spec is
;;     explicit that this capability does NOT add a failure-originated suspend
;;     tier — §10 suspension stays a user-action pause.
;;   * A `retry` verdict is still bounded by the `:retries` budget. A classifier
;;     that could loop unbounded would be a denial-of-service on your own bill.

(defn- flaky-llm
  "A fake LLM that answers with `statuses` in order, then 200 forever.
  Returns {:base :hits}; :hits counts requests actually received."
  [statuses f]
  (let [hits (atom 0)
        srv  (server/serve
              (fn [_req]
                (let [n (swap! hits inc)
                      s (get statuses (dec n))]
                  (if s
                    {:status s :headers {"content-type" "application/json"} :body "{\"error\":\"nope\"}"}
                    {:status 200 :headers {"content-type" "application/json"}
                     :body (json/write-str (text-response "openai" "done"))})))
              {:port 0})]
    (try (f {:base (str "http://127.0.0.1:" (server/port srv)) :hits hits})
         (finally (server/stop! srv)))))

(deftest retries-a-retryable-status-then-succeeds
  (flaky-llm [429 429]
    (fn [{:keys [base hits]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"
                                     :retries 3 :retry-base-ms 1})
            r (client/run c "hi" {:toolkit (tool/toolkit tools)})]
        (is (= "done" (:text r)))
        (is (= 3 @hits) "two 429s consumed two retries, the third call succeeded")))))

(deftest a-non-retryable-status-fails-immediately
  (flaky-llm [400]
    (fn [{:keys [base hits]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"
                                     :retries 3 :retry-base-ms 1})]
        (is (thrown? Throwable (client/run c "hi" {:toolkit (tool/toolkit tools)})))
        (is (= 1 @hits) "400 is terminal — no retry budget may be spent")))))

(deftest on-error-can-force-fail-on-a-retryable-status
  (flaky-llm [429 429 429]
    (fn [{:keys [base hits]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"
                                     :retries 5 :retry-base-ms 1
                                     :on-error (fn [_info] :fail)})]
        (is (thrown? Throwable (client/run c "hi" {:toolkit (tool/toolkit tools)})))
        (is (= 1 @hits) "a forced fail must not consume the remaining budget")))))

(deftest on-error-can-force-retry-on-a-terminal-status-but-stays-bounded
  (flaky-llm [400 400 400 400 400 400 400 400]
    (fn [{:keys [base hits]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"
                                     :retries 2 :retry-base-ms 1
                                     :on-error (fn [_info] :retry)})]
        (is (thrown? Throwable (client/run c "hi" {:toolkit (tool/toolkit tools)})))
        (is (= 3 @hits)
            "the classifier may force retry, but :retries still bounds it")))))

(deftest on-error-receives-the-failure-context
  (flaky-llm [503]
    (fn [{:keys [base]}]
      (let [seen (atom nil)
            c    (client/create-client {:base-url base :model "m" :api-key "k"
                                        :retries 1 :retry-base-ms 1
                                        :on-error (fn [info] (reset! seen info) :retry)})]
        (client/run c "hi" {:toolkit (tool/toolkit tools)})
        (is (= 503 (:status @seen)))
        (is (= 0 (:attempt @seen)) "attempt is zero-based")
        (is (true? (:retryable? @seen)) "503 is in the retryable set")))))

(deftest an-injected-http-client-receives-the-llm-calls
  (with-llm "openai" [{:text "done"}]
    (fn [{:keys [base]}]
      (let [calls (atom 0)
            c (client/create-client
               {:base-url base :model "m" :api-key "k"
                ;; same shape as koine.http/post-json, so a host can wrap it
                :http-client (fn [url headers body]
                               (swap! calls inc)
                               (http/post-json url headers body))})]
        (is (= "done" (:text (client/run c "hi" {:toolkit (tool/toolkit tools)}))))
        (is (pos? @calls) "the injected transport must carry the LLM call")))))

(defn- http-timeout-honoured?
  "Does THIS HOST's koine actually bound an HTTP call, or merely narrate it?
  Probed directly against koine — no toolnexus code involved — because the
  answer decides whether the assertion below is testing our client or an
  upstream gap.

  Measured 2026-08-01, koine 0.9.0, against a server sleeping 2000ms with
  :timeout-ms 150:
      JVM    224ms  {:status nil :error :timeout}   bounded
      cljgo  2002ms {:status 200}                   IGNORED

  Same class as the `sh :timeout-ms` defect koine fixed in 0.7.1 — a timeout
  that reports rather than enforces. Reported upstream with this repro."
  []
  (let [srv (server/serve (fn [_] (ktime/sleep! 800) {:status 200 :body "late"}) {:port 0})]
    (try
      (let [t0  (ktime/now-ms)
            res (http/request {:method :post
                               :url (str "http://127.0.0.1:" (server/port srv))
                               :headers {} :body "{}" :timeout-ms 100})]
        (and (< (- (ktime/now-ms) t0) 600) (nil? (:status res))))
      (finally (server/stop! srv)))))

(deftest timeout-ms-bounds-the-llm-call
  ;; koine.http/request takes :timeout-ms and classifies a timeout as DATA
  ;; ({:status nil :error :timeout}), so this asserts the client passes the
  ;; budget through rather than that the JVM has a socket timeout.
  (if-not (http-timeout-honoured?)
    ;; Reported, never silently skipped, and never counted as a pass — the same
    ;; discipline as the cljgo-repl leg in all-modes-check.sh. When koine bounds
    ;; :timeout-ms on this host, this branch stops being taken and the real
    ;; assertion below runs with no edit here.
    (println "toolnexus TEST GAP: koine does not BOUND an HTTP call on this host"
             "(:timeout-ms accepted and ignored) — upstream. The client passes the"
             "budget through, which is all this port can do.")
    (let [srv (server/serve (fn [_req]
                            (ktime/sleep! 2000)
                            {:status 200 :headers {"content-type" "application/json"}
                             :body (json/write-str (text-response "openai" "late"))})
                          {:port 0})]
    (try
      (let [c (client/create-client {:base-url (str "http://127.0.0.1:" (server/port srv))
                                     :model "m" :api-key "k" :timeout-ms 150})
            started (ktime/now-ms)]
        (is (thrown? Throwable (client/run c "hi" {:toolkit (tool/toolkit tools)})))
        (is (< (- (ktime/now-ms) started) 1500)
            "the call must be BOUNDED, not merely reported as slow afterwards"))
      (finally (server/stop! srv))))))

;; ---------------------------------------------------------------------------
;; client-observability :on-metric  +  conversation-store :store
;; ---------------------------------------------------------------------------
;;
;; §client-observability pins SEMANTIC events, not counter primitives:
;;   {:event "llm"  :model :status :ms :prompt_tokens :completion_tokens}
;;   {:event "tool" :tool :source :is_error :ms}
;;   {:event "run"  :model :turns :tool_calls :total_tokens :ms :error}
;; §conversation-store pins a two-operation provider: get(id) / save(id msgs),
;; with a shipped in-memory default.

(deftest on-metric-emits-llm-tool-and-run-events
  ;; COUNTS, not floors. `(is (seq ...))` is a floor of 1 where 2 are expected,
  ;; and it hid the classic "fires once per process instead of once per round
  ;; trip". The tool fixture carries TWO calls for the same reason: with N=1 a
  ;; loop that emitted the metric for only the first of N parallel calls was
  ;; invisible.
  (with-llm "openai" [{:calls [{:id "1" :name "upper" :args {:text "x"}}
                               {:id "2" :name "quick"}]}
                      {:text "done"}]
    (fn [{:keys [base]}]
      (let [seen (atom [])
            c    (client/create-client {:base-url base :model "m" :api-key "k"
                                        :on-metric (fn [m] (swap! seen conj m))})]
        (client/run c "hi" {:toolkit (tool/toolkit tools)})
        (let [by-event (group-by :event @seen)]
          (is (= 2 (count (get by-event "llm")))
              "ONE llm event per round trip — this run made two")
          (is (= 2 (count (get by-event "tool")))
              "ONE tool event per tool call — this turn made two")
          (is (= 1 (count (get by-event "run"))) "exactly one run event")
          (testing "the whole emission order is pinned: llm, both tools, llm, run"
            (is (= ["llm" "tool" "tool" "llm" "run"] (mapv :event @seen))))
          (is (= ["upper" "quick"] (mapv :tool (get by-event "tool")))
              "in call order, one per call")
          (doseq [llm (get by-event "llm")]
            (is (= "m" (:model llm)))
            (is (= 200 (:status llm))))
          (let [t (first (get by-event "tool"))]
            (is (= "upper" (:tool t)))
            (is (false? (:is_error t))))
          (let [r (first (get by-event "run"))]
            (is (= 2 (:turns r)))
            (is (= 2 (:tool_calls r)))
            (is (pos? (:total_tokens r)))))))))

(deftest a-client-without-on-metric-still-runs
  (with-llm "openai" [{:text "done"}]
    (fn [{:keys [base]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"})]
        (is (= "done" (:text (client/run c "hi" {:toolkit (tool/toolkit tools)}))))))))

(deftest the-store-remembers-a-conversation-by-id
  (with-llm "openai" [{:text "first"} {:text "second"}]
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"})]
        (client/run c "one" {:toolkit (tool/toolkit tools) :conversation-id "abc"})
        (client/run c "two" {:toolkit (tool/toolkit tools) :conversation-id "abc"})
        (let [second-body (last @requests)
              contents    (mapv :content (:messages second-body))]
          (is (some #{"one"} contents)
              "turn 2 must carry turn 1's user message — that is what memory IS")
          (is (some #{"two"} contents)))))))

(deftest a-custom-store-provider-is-used
  (with-llm "openai" [{:text "ok"}]
    (fn [{:keys [base]}]
      (let [saved (atom {})
            store {:get  (fn [id] (get @saved id))
                   :save (fn [id msgs] (swap! saved assoc id msgs))}
            c     (client/create-client {:base-url base :model "m" :api-key "k"
                                         :store store})]
        (client/run c "hi" {:toolkit (tool/toolkit tools) :conversation-id "z"})
        (is (seq (get @saved "z")) "the host's provider must receive the transcript")))))

(deftest without-a-conversation-id-nothing-is-remembered
  (with-llm "openai" [{:text "a"} {:text "b"}]
    (fn [{:keys [base requests]}]
      (let [c (client/create-client {:base-url base :model "m" :api-key "k"})]
        (client/run c "one" {:toolkit (tool/toolkit tools)})
        (client/run c "two" {:toolkit (tool/toolkit tools)})
        (is (not-any? #{"one"} (mapv :content (:messages (last @requests))))
            "a one-shot run must not inherit a previous run's transcript")))))

;; ---------------------------------------------------------------------------
;; §8 hooks — beforeLLM / afterLLM / beforeTool / afterTool
;; ---------------------------------------------------------------------------
;;
;; Written TEST-FIRST. Every expected value comes from SPEC.md §8 ("Hooks
;; (lifecycle middleware)"), SPEC.md §8 Gap 1 (the ordering contract) and
;; `js/src/client.ts` — never from this port's own output.
;;
;;   beforeLLM({messages, tools, model, turn}) -> {messages?, tools?}  (replace)
;;   afterLLM({response, model, turn})                                 (observe)
;;   beforeTool({name, args, id, turn}) -> {result} short-circuits, {args} rewrites
;;   afterTool({name, args, result, id, turn}) -> {result} replaces
;;
;; and the ordering SPEC.md pins for the body:
;;
;;   base body -> beforeLLM hook -> :request-params merge -> :body-transform
;;                -> marshal -> wire
;;
;; The hook map's keys are kebab-case because hooks never cross the wire; the
;; ToolResult they carry keeps its pinned `:isError`.

(deftest before-llm-fires-once-per-turn-with-the-turn-index
  (with-llm "openai" [{:calls [{:id "c1" :name "upper" :args {:text "x"}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [seen (atom [])
            c    (client-for "openai" base
                             {:hooks {:before-llm (fn [ev] (swap! seen conj ev) nil)}})]
        (client/run c "hi" {:toolkit toolkit})
        (testing "one call per LLM round trip, turn index 0-based"
          (is (= [0 1] (mapv :turn @seen))))
        (testing "the event carries the model and the live transcript + tools"
          (is (= ["mock-model" "mock-model"] (mapv :model @seen)))
          (is (every? (fn [ev] (seq (:tools ev))) @seen))
          (is (= "hi" (:content (last (:messages (first @seen)))))))))))

(deftest before-llm-can-replace-messages-and-tools
  (with-llm "openai" [{:text "done"}]
    (fn [{:keys [base requests]}]
      (let [c (client-for "openai" base
                          {:hooks {:before-llm (fn [_ev]
                                                 {:messages [{:role "user" :content "rewritten"}]
                                                  :tools []})}})]
        (client/run c "hi" {:toolkit toolkit})
        (let [body (first @requests)]
          (testing "the replacement transcript is what goes on the wire"
            (is (= [{:role "user" :content "rewritten"}] (:messages body))))
          (testing "Gap 5 — an empty tool list omits `tools` (and `tool_choice`)"
            (is (nil? (:tools body)))
            (is (nil? (:tool_choice body)))))))))

(deftest before-llm-replacement-persists-for-the-rest-of-the-run
  ;; SPEC §8: "Returning `messages` replaces the working transcript for the rest
  ;; of the run" — so a turn-0-only override must still be in force on turn 1.
  (with-llm "openai" [{:calls [{:id "c1" :name "upper" :args {:text "x"}}]} {:text "done"}]
    (fn [{:keys [base requests]}]
      (let [c (client-for "openai" base
                          {:hooks {:before-llm (fn [ev] (when (= 0 (:turn ev)) {:tools []}))}})]
        (client/run c "hi" {:toolkit toolkit})
        (is (= [nil nil] (mapv :tools @requests))
            "tools replaced on turn 0 must stay replaced on turn 1")))))

(deftest after-llm-observes-the-raw-provider-payload
  (with-llm "openai" [{:calls [{:id "c1" :name "upper" :args {:text "x"}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [seen (atom [])
            c    (client-for "openai" base
                             {:hooks {:after-llm (fn [ev] (swap! seen conj ev) nil)}})]
        (client/run c "hi" {:toolkit toolkit})
        (is (= [0 1] (mapv :turn @seen)))
        (is (= ["mock-model" "mock-model"] (mapv :model @seen)))
        (testing "`response` is the raw provider payload, and it carries usage"
          (is (= 10 (get-in (first @seen) [:response :usage :prompt_tokens])))
          (is (= "done" (get-in (last @seen) [:response :choices 0 :message :content]))))))))

(deftest before-llm-runs-before-request-params-and-body-transform
  ;; SPEC.md §8 Gap 1: base body -> BeforeLLM -> RequestParams -> BodyTransform.
  (with-llm "openai" [{:text "done"}]
    (fn [{:keys [base requests]}]
      (let [c (client-for "openai" base
                          {:hooks {:before-llm (fn [_ev] {:messages [{:role "user" :content "hooked"}]})}
                           :request-params {:temperature 0.2}
                           :body-transform (fn [b]
                                             (is (= [{:role "user" :content "hooked"}] (:messages b))
                                                 "body-transform must see the hook's messages")
                                             (is (= 0.2 (:temperature b))
                                                 "body-transform must see the merged request-params")
                                             (assoc b :ordered "yes"))})]
        (client/run c "hi" {:toolkit toolkit})
        (let [body (first @requests)]
          (is (= [{:role "user" :content "hooked"}] (:messages body)))
          (is (= 0.2 (:temperature body)))
          (is (= "yes" (:ordered body))))))))

(deftest before-tool-can-rewrite-the-args
  (with-llm "openai" [{:calls [{:id "c1" :name "upper" :args {:text "quiet"}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [seen (atom [])
            c    (client-for "openai" base
                             {:hooks {:before-tool (fn [ev]
                                                     (swap! seen conj ev)
                                                     {:args {:text "loud"}})}})
            rr   (client/run c "hi" {:toolkit toolkit})]
        (is (= ["LOUD"] (outputs-of rr)) "the rewritten args are what the tool ran on")
        (let [ev (first @seen)]
          (is (= "upper" (:name ev)))
          (is (= {:text "quiet"} (:args ev)))
          (is (= "c1" (:id ev)))
          (is (= 0 (:turn ev))))))))

(deftest a-throwing-tool-hook-must-not-park-the-consumer-forever
  ;; REGRESSION. `execute-calls` runs each tool on `run-async!` and blocks on a
  ;; promise. The `try/catch` covered `tool/execute` ONLY, and both §8 tool hooks
  ;; are invoked outside it — so a host whose hook threw killed the worker before
  ;; `deliver` ran and the `deref` blocked FOREVER. The JVM printed a stack trace
  ;; from the dying thread; cljgo hung with no diagnostic at all.
  ;;
  ;; The assertion is deliberately "it comes back at all". A test that only
  ;; checked the exception type would pass against an implementation that threw
  ;; correctly for one call and hung for a parallel one, and hanging is the whole
  ;; failure. `deftest` cannot time out portably, so a hang shows up as a suite
  ;; that never finishes — which the CI job's own timeout reports.
  (doseq [hook [:before-tool :after-tool]]
    (with-llm "openai" [{:calls [{:id "c1" :name "upper" :args {:text "x"}}]} {:text "done"}]
      (fn [{:keys [base]}]
        (let [c (client-for "openai" base
                            {:hooks {hook (fn [_ev] (throw (ex-info "hook exploded" {:hook hook})))}})
              outcome (try (client/run c "hi" {:toolkit toolkit})
                           :returned-normally
                           (catch Throwable e (ex-message e)))]
          ;; Rethrown on the CALLING thread, not swallowed into an isError: the
          ;; shipped ports wrap neither hook, so a throwing hook rejects the run
          ;; and the host sees its own bug rather than a mystery tool failure.
          (is (= "hook exploded" outcome)
              (str hook " must surface the host's exception, not hang and not hide it")))))))

(deftest before-tool-result-short-circuits-the-tool
  ;; `boom` throws when it runs. A short-circuit that returns a clean result
  ;; therefore PROVES the real tool never ran (SPEC §8: "the real tool never
  ;; runs").
  (with-llm "openai" [{:calls [{:id "c1" :name "boom" :args {}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [c  (client-for "openai" base
                           {:hooks {:before-tool (fn [_ev] {:result (tool/success "denied")})}})
            rr (client/run c "hi" {:toolkit toolkit})]
        (is (= ["denied"] (outputs-of rr)))
        (is (= [false] (mapv :isError (:tool-calls rr))))))))

(deftest after-tool-can-replace-the-result
  (with-llm "openai" [{:calls [{:id "c1" :name "upper" :args {:text "toolnexus"}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [seen (atom [])
            c    (client-for "openai" base
                             {:hooks {:after-tool (fn [ev]
                                                    (swap! seen conj ev)
                                                    {:result (tool/success (str "[" (:output (:result ev)) "]"))})}})
            rr   (client/run c "hi" {:toolkit toolkit})]
        (is (= ["[TOOLNEXUS]"] (outputs-of rr)))
        (let [ev (first @seen)]
          (is (= "upper" (:name ev)))
          (is (= {:text "toolnexus"} (:args ev)))
          (is (= "TOOLNEXUS" (:output (:result ev))))
          (is (= "c1" (:id ev)))
          (is (= 0 (:turn ev))))))))

(deftest after-tool-skips-a-suspension-and-sees-the-resolved-result
  ;; js/src/client.ts runTool: `if (h?.afterTool && !pendingOf(result))` — a
  ;; suspension is not a real result, so afterTool is skipped on it; the
  ;; post-waitFor result still flows through afterTool in resolvePending.
  (with-llm "openai" [{:calls [{:id "c1" :name "ask_city" :args {}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [seen (atom [])
            c    (client-for "openai" base
                             {:wait-for wait-ok
                              :hooks {:after-tool (fn [ev] (swap! seen conj ev) nil)}})
            rr   (client/run c "hi" {:toolkit toolkit})]
        (is (= 1 (count @seen)) "afterTool fires once — on the RESOLVED result, not the suspension")
        (is (= "{\"city\":\"Chennai\"}" (:output (:result (first @seen)))))
        (is (= ["{\"city\":\"Chennai\"}"] (outputs-of rr)))))))

(deftest hooks-absent-leave-the-body-untouched
  ;; The same guarantee the shaping options carry: absent => byte-identical.
  (let [capture (fn [opts]
                  (let [out (atom nil)]
                    (with-llm "openai" [{:text "done"}]
                      (fn [{:keys [base requests]}]
                        (client/run (client-for "openai" base opts) "hi" {:toolkit toolkit})
                        (reset! out (json/write-str (first @requests)))))
                    @out))]
    (is (= (capture {}) (capture {:hooks nil}))
        "an explicitly-nil :hooks must be indistinguishable from an absent one")
    (is (= (capture {}) (capture {:hooks {}}))
        "an empty hook map must be indistinguishable from an absent one")))

(deftest a-hook-returning-nil-changes-nothing
  (with-llm "openai" [{:calls [{:id "c1" :name "upper" :args {:text "x"}}]} {:text "done"}]
    (fn [{:keys [base]}]
      (let [nilf (fn [_ev] nil)
            c    (client-for "openai" base
                             {:hooks {:before-llm nilf :after-llm nilf
                                      :before-tool nilf :after-tool nilf}})
            rr   (client/run c "hi" {:toolkit toolkit})]
        (is (= "done" (:text rr)))
        (is (= ["X"] (outputs-of rr)))))))

(deftest hooks-fire-on-the-anthropic-style-too
  (with-llm "anthropic" [{:calls [{:id "u1" :name "upper" :args {:text "x"}}]} {:text "done"}]
    (fn [{:keys [base requests]}]
      (let [seen (atom [])
            c    (client-for "anthropic" base
                             {:hooks {:before-llm (fn [ev] (swap! seen conj [:before (:turn ev)]) nil)
                                      :after-llm  (fn [ev] (swap! seen conj [:after (:turn ev)]) nil)
                                      :before-tool (fn [_ev] {:args {:text "loud"}})
                                      :after-tool  (fn [ev] {:result (tool/success (str "<" (:output (:result ev)) ">"))})}})
            rr   (client/run c "hi" {:toolkit toolkit})]
        (is (= [[:before 0] [:after 0] [:before 1] [:after 1]] @seen))
        (is (= ["<LOUD>"] (outputs-of rr)))
        (is (seq (:tools (first @requests))))))))
