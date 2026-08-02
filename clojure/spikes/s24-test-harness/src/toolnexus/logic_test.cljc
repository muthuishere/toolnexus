;; S24 — a REAL clojure.test suite in ONE .cljc, run unchanged on Clojure (JVM)
;; and on cljgo. deftest / is / testing / use-fixtures, pure logic AND koine.
;;
;; The counts this file produces are the numbers the harness gates on, so they
;; are load-bearing: 8 deftests, 26 assertions. Change a test, change the
;; README.
(ns toolnexus.logic-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as strings]
            [toolnexus.logic :as logic]
            [koine.json :as json]
            [koine.fs :as fs]
            [koine.env :as env]))

;; ---------------------------------------------------------------------------
;; Fixtures — do they work on both hosts at all? (part of the question)
;; ---------------------------------------------------------------------------
(def fixture-log (atom []))

(use-fixtures :once (fn [f]
                      (swap! fixture-log conj :once-before)
                      (f)
                      (swap! fixture-log conj :once-after)))

(use-fixtures :each (fn [f]
                      (swap! fixture-log conj :each)
                      (f)))

;; ---------------------------------------------------------------------------
;; Pure logic
;; ---------------------------------------------------------------------------
(deftest sanitize-basics
  (is (= "hello" (logic/sanitize "hello")))
  (is (= "hello_world" (logic/sanitize "Hello World")))
  (is (= "a_b" (logic/sanitize "a---b")))
  (testing "leading and trailing separators are trimmed"
    (is (= "x" (logic/sanitize "***x***")))
    (is (= "" (logic/sanitize "---")))))

(deftest sanitize-case-and-digits
  (is (= "tool_2" (logic/sanitize "Tool 2")))
  (is (= "get_weather" (logic/sanitize "get.Weather"))))

(deftest qualified-naming
  (is (= "everything_echo" (logic/qualified-name "everything" "echo")))
  (is (= "my_server_add_two" (logic/qualified-name "My-Server" "add two"))))

(deftest unique-names-first-wins
  (let [names (logic/unique-names [["a" "x"] ["a" "X"] ["b" "y"]])]
    (is (= ["a_x" "b_y"] names))
    (is (= 2 (count names))))
  (testing "order is preserved"
    (is (= ["z_1" "a_2"] (logic/unique-names [["z" "1"] ["a" "2"]])))))

(deftest merge-config-nil-safety
  (is (= {:a 1 :b 2} (logic/merge-config {:a 1} {:b 2})))
  (is (= {:a 1} (logic/merge-config {:a 1} {:a nil})))
  (is (= {:a 2} (logic/merge-config {:a 1} {:a 2}))))

;; ---------------------------------------------------------------------------
;; koine-touching — a suite that never leaves pure arithmetic proves nothing
;; about whether the port's real dependencies load under the test runner.
;; ---------------------------------------------------------------------------
(deftest koine-json-round-trip
  (let [m {:name "everything_echo" :nested {:b 2 :a [1 2 3]} :flag true}
        s (json/write-str m)]
    (testing "write-str sorts keys, so the bytes are host-independent"
      (is (= "{\"flag\":true,\"name\":\"everything_echo\",\"nested\":{\"a\":[1,2,3],\"b\":2}}" s)))
    (testing "round-trip"
      (is (= m (json/read-str s {:key-fn keyword})))))
  (testing "a shaped tool survives the round-trip"
    (let [t (logic/tool-summary {:name "Get Weather" :description "d"})]
      (is (= t (json/read-str (json/write-str t) {:key-fn keyword}))))))

(deftest koine-fs-reads-this-file
  (testing "koine.fs works under the test runner on both hosts"
    (let [path "src/toolnexus/logic.cljc"]
      (is (fs/exists? path))
      (let [text (fs/read-file path)]
        (is (< 100 (count text)))
        (is (strings/includes? (str text) "defn sanitize"))))))

;; ---------------------------------------------------------------------------
;; The canary. Passes normally; fails when the harness (or TN_FORCE_FAIL=1)
;; arms it. Its assertion COUNT is the same either way, so only :fail moves —
;; which is exactly what lets us prove the runner reports failures at all.
;; ---------------------------------------------------------------------------
(deftest deliberate-failure-canary
  (let [armed? (logic/forced-failure? (env/get-env "TN_FORCE_FAIL"))]
    (is (if armed?
          (= :deliberate-failure :this-must-not-match)
          (= :canary :canary)))))
