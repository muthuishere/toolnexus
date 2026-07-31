(ns toolnexus.tool-test
  (:require [clojure.test :refer [deftest is testing]]
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
