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
    (if (seq (:calls spec))
      (calls-response style (:calls spec))
      (text-response style (or (:text spec) "done")))))

(defn with-llm
  "Start a scripted LLM, hand `f` a map {:base :requests :paths}, stop it after.
  `:requests` is an atom of the PARSED request bodies, in arrival order — the
  only way to assert on what the client actually fed back."
  [style script f]
  (let [n        (atom 0)
        requests (atom [])
        paths    (atom [])
        srv      (server/serve
                   (fn [req]
                     (swap! paths conj (:path req))
                     (swap! requests conj (json/read-str (str (:body req))))
                     {:status 200
                      :headers {"content-type" "application/json"}
                      :body (json/write-str (script-response style script (swap! n inc)))})
                   {:port 0})]
    (try
      (f {:base (str "http://127.0.0.1:" (server/port srv))
          :requests requests
          :paths paths})
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

(deftest anthropic-base-url-already-versioned
  (with-llm "anthropic" [{:text "hi"}]
    (fn [{:keys [base paths]}]
      (client/run (client-for "anthropic" (str base "/v1") {}) "go" {:toolkit toolkit})
      (is (= ["/v1/messages"] @paths) "a /v1 suffix is not doubled"))))

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
