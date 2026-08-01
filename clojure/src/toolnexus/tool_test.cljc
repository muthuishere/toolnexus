(ns toolnexus.tool-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [toolnexus.tool :as tool]))

(deftest sanitize-is-spec-0-2
  (is (= "a_b_c"     (tool/sanitize "a b.c")))
  (is (= "foo_bar"   (tool/sanitize "foo/bar")))
  (is (= "ok_name-1" (tool/sanitize "ok_name-1")))
  (testing "already-safe characters are untouched"
    (is (= "AZaz09_-" (tool/sanitize "AZaz09_-")))))

(deftest names-never-shadow-clojure-core
  (testing "on THIS host, no public name in toolnexus.tool collides with core.
            cljgo's clojure.core has vars the JVM's does not (ok, err), so this
            must be asserted per host, not reasoned about once."
    (doseq [n (keys (ns-publics 'toolnexus.tool))]
      (is (nil? (resolve (symbol "clojure.core" (name n))))
          (str "toolnexus.tool/" n " shadows clojure.core/" n)))))

(deftest results-are-values
  (is (= {:output "hi" :isError false} (tool/success "hi")))
  (is (= {:output "no" :isError true}  (tool/failure "no")))
  (is (= {:name "s"} (:metadata (tool/success "x" {:name "s"}))))
  (testing "output is always a string — the model reads text"
    (is (= "42" (:output (tool/success 42))))))

(deftest execute-turns-a-throw-into-a-result
  (let [tk (tool/toolkit [(tool/tool {:name "boom"
                                      :execute (fn [_] (throw (ex-info "kaboom" {})))})])]
    (testing "a misbehaving tool must not take down the loop (§0.8)"
      (is (= {:output "kaboom" :isError true} (tool/execute tk "boom" {}))))))

(deftest unknown-tool-is-an-error-not-an-exception
  (is (true? (:isError (tool/execute (tool/toolkit []) "nope" {})))))

(deftest later-tools-win-which-is-how-mcp-precedence-works
  (let [a  (tool/tool {:name "read" :source "builtin" :execute (fn [_] (tool/success "builtin"))})
        b  (tool/tool {:name "read" :source "mcp"     :execute (fn [_] (tool/success "mcp"))})
        tk (-> (tool/toolkit [a]) (tool/add-tools [b]))]
    (testing "SPEC §0.11 — MCP takes precedence over a builtin of the same name"
      (is (= "mcp" (:output (tool/execute tk "read" {}))))
      (is (= 1 (count (:tools tk)))))))

(deftest tool-names-are-sorted
  (testing "two runtimes must not disagree on order"
    (let [tk (tool/toolkit (map #(tool/tool {:name % :execute (fn [_] nil)})
                                ["c" "a" "b"]))]
      (is (= ["a" "b" "c"] (tool/tool-names tk))))))

(deftest ctx-is-passed-only-when-given
  (let [t  (tool/tool {:name "t" :execute (fn ([_] (tool/success "no-ctx"))
                                            ([_ c] (tool/success (str "ctx:" (:answer c)))))})
        tk (tool/toolkit [t])]
    (is (= "no-ctx"  (:output (tool/execute tk "t" {}))))
    (is (= "ctx:yes" (:output (tool/execute tk "t" {} {:answer "yes"}))))))

;; ---------------------------------------------------------------------------
;; Byte-order of JSON keys OUTSIDE the BMP — the state where the hosts diverge
;; ---------------------------------------------------------------------------
;;
;; koine's encoder sorts keys, and that sort is what makes §0 byte-comparison
;; possible at all. But sorting a string means sorting its CODE UNITS on the JVM
;; (UTF-16) and its BYTES on cljgo (UTF-8), and those two orders disagree for
;; any key outside the Basic Multilingual Plane:
;;
;;   U+1F600 GRINNING FACE   UTF-16 D83D DE00   UTF-8 F0 9F 98 80
;;   U+E000  PRIVATE USE     UTF-16 E000        UTF-8 EE 80 80
;;
;; D83D < E000, so a UTF-16 sort puts the emoji FIRST; F0 > EE, so a UTF-8 sort
;; puts it SECOND. Same input, two different byte streams, no error on either
;; side — exactly the shape a cross-host diff can catch but a single-host suite
;; cannot. Fixed in koine 0.7.3 (0.7.2 briefly got it wrong in the other
;; direction and lived about an hour; this port never ran it).
;;
;; This asserts CODEPOINT order, which is the one both hosts must agree on, so
;; it fails on whichever host is wrong rather than merely reporting that they
;; differ. We had 707 assertions and not one of them entered this state — the
;; suite was green on both hosts and blind to the whole class.
;;
;; Reach: tool NAMES are ASCII by §0.2's sanitize, so the exposure is
;; model-supplied argument and result keys, which we do not control. Narrow, not
;; zero.
(deftest json-keys-outside-the-bmp-sort-by-codepoint
  (let [json-write (requiring-resolve 'koine.json/write-str)
        emoji      "😀"          ; U+1F600, supplementary plane
        private    ""                ; U+E000, BMP private use
        encoded    (json-write {private 1 emoji 2})]
    (testing "U+E000 precedes U+1F600 by codepoint, on BOTH hosts"
      ;; clojure.string/index-of, NOT (.indexOf s) — a bare `.method` call is
      ;; Java interop and would not survive the trip to cljgo.
      (is (< (str/index-of encoded private)
             (str/index-of encoded emoji))
          (str "key order disagrees with codepoint order: " encoded)))))
