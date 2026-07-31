;; SPEC §0.8 / §6 — native tools. Dual-host: no java.*, no Thread/sleep.
(ns toolnexus.native-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [toolnexus.native :as native]
            [toolnexus.tool :as tool]))

(def upper
  (native/native-tool {:name "upper"
                       :description "Uppercase the text"
                       :input-schema {:type "object"
                                      :properties {:text {:type "string"}}
                                      :required ["text"]}
                       :run (fn [args] (str/upper-case (str (:text args))))}))

(def boom
  (native/native-tool {:name "boom"
                       :description "Always throws"
                       :run (fn [_] (throw (ex-info "kaboom" {:why "test"})))}))

(deftest shape-of-a-native-tool
  (testing "§0.1 — a Tool is a map with the five keys, source native"
    (is (= "upper" (:name upper)))
    (is (= "Uppercase the text" (:description upper)))
    (is (= "native" (:source upper)))
    (is (= {:type "object" :properties {:text {:type "string"}} :required ["text"]}
           (:input-schema upper)))
    (is (fn? (:execute upper))))
  (testing "an omitted description/schema falls back to the §1 defaults"
    (is (= "" (:description boom)))
    (is (= {:type "object"} (:input-schema boom)))))

(deftest string-return-becomes-output
  (is (= {:output "ABC" :isError false} ((:execute upper) {:text "abc"})))
  (testing "the positional arity builds the same tool"
    (let [t (native/native-tool "id" "echo" {:type "object"} (fn [a] (str (:v a))))]
      (is (= {:output "x" :isError false} ((:execute t) {:v "x"}))))))

(deftest non-string-returns
  (testing "nil ⇒ empty output (an effect-only tool), not a crash"
    (let [t (native/native-tool {:name "eff" :run (fn [_] nil)})]
      (is (= {:output "" :isError false} ((:execute t) {})))))
  (testing "anything else is str-ed"
    (let [t (native/native-tool {:name "n" :run (fn [_] 42)})]
      (is (= {:output "42" :isError false} ((:execute t) {})))))
  (testing "a ToolResult passes through, with isError forced boolean"
    (let [t (native/native-tool {:name "r" :run (fn [_] {:output "no" :isError "yes"})})]
      ;; a truthy non-boolean must not leak into the wire shape
      (is (= false (:isError ((:execute t) {})))))
    (let [t (native/native-tool {:name "r2" :run (fn [_] (tool/failure "nope"))})]
      (is (= {:output "nope" :isError true} ((:execute t) {}))))))

(deftest throw-becomes-is-error
  (testing "§0.8 — a throw is a ToolResult, never an exception across the boundary"
    (let [r (native/execute-native boom {})]
      (is (true? (:isError r)))
      (is (= "kaboom" (:output r)))))
  (testing "…and the same via the toolkit's boundary rule"
    (let [tk (tool/toolkit [upper boom])
          r  (tool/execute tk "boom" {})]
      (is (true? (:isError r)))
      (is (= "kaboom" (:output r))))))

(deftest context-is-opt-in
  (testing "a one-arg :run is called with args alone, on both execute arities"
    (is (= "ABC" (:output ((:execute upper) {:text "abc"} {:answer {:ok true}})))))
  (testing ":ctx? true hands the Context through"
    (let [t (native/native-tool {:name "ctx" :ctx? true
                                 :run (fn [args ctx] (str (:v args) "/" (:tag ctx)))})]
      (is (= "a/t1" (:output ((:execute t) {:v "a"} {:tag "t1"}))))
      (is (= "a/" (:output ((:execute t) {:v "a"})))))))

(deftest registers-in-a-toolkit
  (let [tk (tool/toolkit [upper boom])]
    (is (= ["boom" "upper"] (tool/tool-names tk)))
    (is (= "ABC" (:output (tool/execute tk "upper" {:text "abc"}))))
    (is (true? (:isError (tool/execute tk "nosuch" {}))))))
