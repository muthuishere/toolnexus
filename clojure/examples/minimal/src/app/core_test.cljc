;; ONE test file, `.cljc`, run on BOTH runtimes with the same assertions.
(ns app.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [app.core :as core]
            [koine.json :as json]
            [koine.host :as host]))

(deftest greeting-is-pure
  (is (= "hello, world" (core/greet "world"))))

(deftest json-choices-hold
  (testing "keys are sorted, so two runtimes emit the same bytes"
    (is (= "{\"a\":2,\"b\":1}" (json/write-str {:b 1 :a 2}))))
  (testing "a float keeps its fraction - 1.0 must not collapse to 1"
    (is (= "{\"pi\":1.0}" (json/write-str {:pi 1.0}))))
  (testing "round trip"
    (is (= {:x [1 2 3]} (json/read-str (json/write-str {:x [1 2 3]}))))))

(deftest snapshot-touches-the-host
  (let [s (core/snapshot "/tmp/koine-minimal-test.txt")]
    (is (= (name host/id) (:runtime s)))
    (is (true? (get-in s [:file :written])))
    (is (= "written by app.core\n" (get-in s [:file :content])))
    (is (= "from a real subprocess" (:shell s)))
    (is (= {:x [1 2 3]} (:parsed s)))))
