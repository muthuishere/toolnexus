;; S24 — the `cljgo test` COLLECTION SENTINEL.
;;
;; Two facts about `cljgo test` on 0.1.0-dev, both measured here (README):
;;   1. it refuses to run at all unless a `test/` directory EXISTS
;;      ("cljgo test: no test/ directory here"), and
;;   2. it then scans BOTH `src/` and `test/` for namespaces whose name ends in
;;      `-test`, so the real suite can stay in the single `src/` .cljc tree that
;;      ADR 0009 Decision 3 calls for.
;;
;; So this file is not a second suite. It is the marker that keeps `test/`
;; non-empty, plus 2 tests / 3 assertions of known size: `cljgo test` must
;; report 10 tests / 25 assertions (8/22 from toolnexus.logic-test + 2/3 here).
;; If it ever reports 2/3, collection of the src tree silently broke — and the
;; counting gate in run-both.sh catches exactly that.
(ns toolnexus.collection-sentinel-test
  (:require [clojure.test :refer [deftest is testing]]
            [toolnexus.logic :as logic]))

(deftest sentinel-reaches-the-src-tree
  (testing "the runner that collected this file can also load src/"
    (is (= "a_b" (logic/qualified-name "a" "b")))
    (is (= "" (logic/sanitize "")))))

(deftest sentinel-counts-are-fixed
  (is (= 3 (+ 1 2))))
