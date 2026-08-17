;; Harness, loop and the completion gate (openspec/changes/add-harness-and-loop).
;;
;; Hermetic — the `:http-client` seam replays scripted assistant messages, so no
;; network and no key. Mirrors golang/agents/loop_test.go, js/test/loop.test.ts,
;; python/tests/test_harness_loop.py and the java/csharp/elixir suites case for
;; case: the point of the change is that seven ports agree, and a test that exists
;; in one port only is how that stops being true.
;;
;; No java.*, no Go interop, zero reader conditionals — this file runs unchanged
;; on the JVM and on cljgo.
(ns toolnexus.agents.loop-test
  (:require [clojure.test :refer [deftest is testing]]
            [koine.json :as json]
            [toolnexus.agents.loop :as tnloop]
            [toolnexus.agents.runtime :as runtime]
            [toolnexus.builtin :as builtin]
            [toolnexus.core :as toolnexus]))

;; ---------------------------------------------------------------------------
;; the scripted LLM
;; ---------------------------------------------------------------------------

(defn- scripted
  "Returns [http-client models-fn] — `models-fn` reveals the `model` each request
  carried, so a test can assert what reached the wire rather than what was set."
  [messages]
  (let [state (atom {:i 0 :models []})]
    [(fn [_url _headers body]
       (let [payload (json/read-str (if (string? body) body (json/write-str body)))
             i       (:i @state)
             _       (swap! state #(-> % (update :i inc)
                                       (update :models conj (:model payload))))
             message (nth messages (min i (dec (count messages))))
             finish  (if (contains? message :tool_calls) "tool_calls" "stop")]
         {:status 200
          :headers {"content-type" "application/json"}
          :body (json/write-str
                 {:choices [{:index 0 :message message :finish_reason finish}]
                  :usage {:prompt_tokens 1 :completion_tokens 1 :total_tokens 2}})}))
     (fn [] (:models @state))]))

(defn- say [content] {:role "assistant" :content content})

(defn- call-todo [todos]
  {:role "assistant"
   :tool_calls [{:id "t1" :type "function"
                 :function {:name "todowrite"
                            :arguments (json/write-str {:todos todos})}}]})

(defn- todo [id text done] {:id id :text text :completed done})

(defn- base-opts [http-client]
  {:base-url "http://scripted.invalid" :style "openai" :model "test-model"
   :api-key "unused" :http-client http-client})

(defn- todo-toolkit []
  (toolnexus/build {:builtins {:tools {:todowrite true :bash false :read false
                                       :write false :edit false :glob false
                                       :grep false :webfetch false
                                       :apply_patch false :question false}}}))

(defn- bare-toolkit [] (toolnexus/build {:builtins false}))

;; ---------------------------------------------------------------------------
;; the tests
;; ---------------------------------------------------------------------------

(deftest harness-is-the-spec
  (let [spec {:does "x" :soul "y"}]
    (is (identical? spec (tnloop/harness spec))
        "harness is a name, not a wrapper")))

(deftest absent-options-are-unchanged
  (let [[http _] (scripted [(say "hello")])
        lp       (tnloop/create {:name "plain" :does "answers"} (base-opts http) (bare-toolkit))
        [out _]  (tnloop/run lp "hi")]
    (is (= "done" (:status out)))
    (is (= "hello" (:text out)))
    (is (= 1 (:attempts out)))
    (is (nil? (:stopped-by out)) "a done run names no stop reason")))

(deftest gate-blocks-an-open-todo-then-passes
  ;; Attempt 1 must END with an open item: the client loops on tool calls, so a
  ;; closing todowrite in the same run would be judged and pass with no retry.
  (let [[http _] (scripted [(call-todo [(todo "1" "draft" true) (todo "2" "proofread" false)])
                            (say "I think I am finished")
                            (call-todo [(todo "1" "draft" true) (todo "2" "proofread" true)])
                            (say "all done")])
        lp       (tnloop/create {:name "gated" :does "plans"
                                 :completion {:verify tnloop/all-todos-done :max-attempts 3}}
                                (base-opts http) (todo-toolkit))
        [out _]  (tnloop/run lp "do the thing")]
    (is (= "done" (:status out)))
    (is (>= (:attempts out) 2) (str "expected a retry, got " (:attempts out)))))

(deftest unverifiable-run-stops-loudly
  (let [[http _] (scripted [(say "done!")])
        lp       (tnloop/create {:name "never" :does "never verifies"
                                 :completion {:verify (fn [_] {:ok false :reason "always red"})
                                              :max-attempts 2}}
                                (base-opts http) (bare-toolkit))
        [out _]  (tnloop/run lp "go")]
    (is (= "incomplete" (:status out)) "never a silent done")
    (is (= 2 (:attempts out)) "bounded by :max-attempts")
    (is (re-find #"always red" (str (:stopped-by out))) "the reason is named")
    (is (= "completion" (:limit (:result out)))
        "structured, so a caller can tell WHICH limit")))

(deftest max-attempts-is-required
  (let [[http _] (scripted [(say "hi")])
        lp       (tnloop/create {:name "bad" :does "x"
                                 :completion {:verify (fn [_] {:ok true}) :max-attempts 0}}
                                (base-opts http) (bare-toolkit))]
    ;; `Throwable` is the port's portable spelling — it resolves on both hosts, and
    ;; a reader conditional here would be the first one in the whole tree.
    (is (thrown-with-msg? Throwable #"max-attempts" (tnloop/run lp "go")))))

(deftest no-plan-declared-passes
  (let [[http _] (scripted [(say "answered without a plan")])
        lp       (tnloop/create {:name "noplan" :does "x"
                                 :completion {:verify tnloop/all-todos-done :max-attempts 2}}
                                (base-opts http) (todo-toolkit))
        [out _]  (tnloop/run lp "go")]
    (is (= "done" (:status out))
        "the gate must not punish an agent for not using the builtin")
    (is (= 1 (:attempts out)))))

(deftest gate-judges-accumulated-work
  ;; Attempt 1 declares an open item; attempt 2 declares no plan at all. Judging
  ;; only the latest attempt would see "no plan" and pass.
  (let [[http _] (scripted [(call-todo [(todo "1" "ship it" false)])
                            (say "I am finished, honest")])
        lp       (tnloop/create {:name "escaper" :does "x"
                                 :completion {:verify tnloop/all-todos-done :max-attempts 2}}
                                (base-opts http) (todo-toolkit))
        [out _]  (tnloop/run lp "go")]
    (is (= "incomplete" (:status out)) "the earlier open plan must still be visible")
    (is (re-find #"ship it" (str (:stopped-by out))))))

(deftest guardrails-first-deny-wins
  (let [seen  (atom 0)
        hooks (tnloop/guarded-hooks
               [(fn [ev] (if (= "danger" (:name ev)) "policy: no" "allow"))
                (fn [_] (swap! seen inc) "allow")]
               nil)
        denied ((:before-tool hooks) {:name "danger" :args {} :turn 1})]
    (is (true? (get-in denied [:result :is-error])))
    (is (re-find #"policy: no" (get-in denied [:result :output])))
    (is (zero? @seen) "a later guardrail never runs after a denial")
    (is (nil? ((:before-tool hooks) {:name "safe" :args {} :turn 1}))
        "an allowed call falls through")
    (is (= 1 @seen))))

(deftest guardrails-run-before-an-existing-hook
  (let [prior (atom 0)
        hooks (tnloop/guarded-hooks
               [(fn [ev] (if (= "danger" (:name ev)) "nope" "allow"))]
               {:before-tool (fn [_] (swap! prior inc) nil)})]
    ((:before-tool hooks) {:name "danger" :args {} :turn 1})
    (is (zero? @prior) "denied => the prior hook is not reached")
    ((:before-tool hooks) {:name "safe" :args {} :turn 1})
    (is (= 1 @prior) "allowed => the prior hook runs")))

(deftest guardrails-compose-with-no-guardrails-unchanged
  (testing "absent guardrails returns the hooks untouched, so absent is byte-identical"
    (let [hooks {:before-tool (fn [_] nil)}]
      (is (identical? hooks (tnloop/guarded-hooks nil hooks)))
      (is (identical? hooks (tnloop/guarded-hooks [] hooks)))
      (is (nil? (tnloop/guarded-hooks [] nil))))))

(deftest per-call-model-override-reaches-the-wire
  (let [[http models] (scripted [(say "a") (say "b")])
        lp            (tnloop/create {:name "m" :does "x"} (base-opts http) (bare-toolkit))
        [_ lp]        (tnloop/run lp "one" {:model "override-model"})
        [_ _]         (tnloop/run lp "two")]
    (is (= "override-model" (nth (models) 0)) "the override reaches the request body")
    (is (= "test-model" (nth (models) 1)) "and does not persist to the next call")))

(deftest turns-accumulate-and-status-is-observed
  (let [[http _] (scripted [(say "a") (say "b")])
        lp       (tnloop/create {:name "t" :does "x"} (base-opts http) (bare-toolkit))]
    (is (= "idle" (:status lp)))
    (let [[_ lp1] (tnloop/run lp "one")
          [_ lp2] (tnloop/run lp1 "two")]
      (is (> (:turns lp2) (:turns lp1)) "turns accumulate across runs")
      (is (= "idle" (:status lp2))))))
