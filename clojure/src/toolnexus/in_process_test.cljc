;; create-in-process-client — a model in this process, with no wire configuration.
;; openspec/changes/add-in-process-client. Mirrored in all seven ports.
;;
;; No java.*, no Go interop, zero reader conditionals.
(ns toolnexus.in-process-test
  (:require [clojure.test :refer [deftest is testing]]
            [toolnexus.client :as client]
            [toolnexus.core :as toolnexus]
            [toolnexus.native :as native]))

(defn- add-tool []
  (native/native-tool
   {:name "add" :description "Add two numbers."
    :input-schema {:type "object"
                   :properties {:a {:type "number"} :b {:type "number"}}
                   :required ["a" "b"]}
    :run (fn [args] (str (long (+ (:a args) (:b args)))))}))

(defn- bare-toolkit [] (toolnexus/build {:builtins false}))
(defn- add-toolkit [] (toolnexus/build {:builtins false :tools [(add-tool)]}))

(deftest no-wire-configuration-is-required
  ;; No :base-url. No :api-key. No :style. That is the whole point.
  (let [c (client/create-in-process-client
           {:model "my-local" :generate (fn [_] {:content "hello from in-process"})})
        r (client/run c "hi" {:toolkit (bare-toolkit)})]
    (is (= "hello from in-process" (:text r)))
    (is (= "done" (:status r)))))

(deftest generate-sees-the-assembled-request
  (let [seen (atom nil)
        c (client/create-in-process-client
           {:model "my-local" :system-prompt "You are terse."
            :generate (fn [req] (reset! seen req) {:content "ok"})})]
    (client/run c "What is 2 + 3?" {:toolkit (add-toolkit)})
    (is (= "my-local" (:model @seen)))
    (is (seq (:tools @seen)) "tool schemas are offered")
    (let [blob (pr-str (:messages @seen))]
      (is (re-find #"terse" blob))
      (is (re-find #"2 \+ 3" blob)))))

(deftest tool-calls-loop-back-with-the-result
  (let [n (atom 0)
        c (client/create-in-process-client
           {:model "m"
            :generate (fn [_]
                        (if (= 1 (swap! n inc))
                          {:tool-calls [{:name "add" :arguments {:a 2 :b 3}}]}
                          {:content "the answer is 5"}))})
        r (client/run c "What is 2 + 3?" {:toolkit (add-toolkit)})]
    (is (= 1 (count (:tool-calls r))))
    (is (= "add" (:name (first (:tool-calls r)))))
    (is (= "5" (:output (first (:tool-calls r)))))))

(deftest arguments-structured-or-pre-encoded
  (doseq [args [{:a 2 :b 3} "{\"a\":2,\"b\":3}"]]
    (let [n (atom 0)
          c (client/create-in-process-client
             {:model "m"
              :generate (fn [_]
                          (if (= 1 (swap! n inc))
                            {:tool-calls [{:name "add" :arguments args}]}
                            {:content "done"}))})
          r (client/run c "go" {:toolkit (add-toolkit)})]
      (is (= "5" (:output (first (:tool-calls r)))) (str "args form: " (pr-str args))))))

(deftest usage-is-optional-and-derived
  (let [bare (client/create-in-process-client {:model "m" :generate (fn [_] {:content "x"})})
        r1   (client/run bare "hi" {:toolkit (bare-toolkit)})]
    (is (= 0 (get-in r1 [:usage :total-tokens])) "absent usage is zero, not a failure"))
  (let [counted (client/create-in-process-client
                 {:model "m"
                  :generate (fn [_] {:content "x" :usage {:prompt-tokens 11 :completion-tokens 4}})})
        r2 (client/run counted "hi" {:toolkit (bare-toolkit)})]
    (is (= 11 (get-in r2 [:usage :prompt-tokens])))
    (is (= 15 (get-in r2 [:usage :total-tokens])) "total is derived when not given")))

(deftest generate-is-required-and-wire-options-are-refused
  (is (thrown-with-msg? Throwable #"`:generate` function"
                        (client/create-in-process-client {:model "m"})))
  (is (thrown-with-msg? Throwable #"no wire to configure"
                        (client/create-in-process-client
                         {:model "m" :generate (fn [_] {}) :base-url "http://x"}))))

(deftest streaming-has-no-entry-point-in-this-port
  (testing "this port has no streaming loop, so unlike the other six there is nothing
            to refuse — recorded as a fact rather than faked with a raise"
    (is (nil? (resolve 'toolnexus.client/stream)))
    (is (nil? (resolve 'toolnexus.client/run-stream)))))
