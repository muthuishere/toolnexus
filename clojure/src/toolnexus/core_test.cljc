(ns toolnexus.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [koine.env :as env]
            [toolnexus.core :as tn]
            [toolnexus.tool :as tool]))

(defn- skills-root [] (str (env/get-env "TN_EXAMPLES") "/skills"))

(deftest builds-from-several-sources-at-once
  (let [tk (tn/build {:skills   (skills-root)
                      :builtins {:tools {:bash false}}
                      :tools    [(tool/tool {:name "mine" :source "native"
                                             :execute (fn [_] (tool/success "ok"))})]})]
    (testing "every source lands in one flat namespace"
      (is (contains? (:tools tk) "skill"))
      (is (contains? (:tools tk) "mine"))
      (is (contains? (:tools tk) "read")))
    (testing "the §0.11 per-tool toggle actually drops one"
      (is (not (contains? (:tools tk) "bash"))))
    (is (= "connected" (get (:statuses tk) "builtin")))))

(deftest mcp-takes-precedence-over-a-builtin-of-the-same-name
  (testing "SPEC §0.11. Registration order is the mechanism: builtins first,
            MCP last, and tool/add-tools lets the later one win. If this ever
            inverts, a server could no longer override our `read`."
    (let [tk (tn/build {:builtins {}
                        :tools [(tool/tool {:name "read" :source "mcp"
                                            :execute (fn [_] (tool/success "from-mcp"))})]})]
      ;; :tools is applied after builtins, standing in for the MCP source here
      (is (= "from-mcp" (:output (tn/execute tk "read" {})))))))

(deftest a-broken-mcp-config-is-isolated-not-fatal
  (testing "SPEC §0.3 — a source that fails contributes an error, never a throw.
            A toolkit with one dead source is still a working toolkit."
    (let [tk (tn/build {:mcp "{not json" :builtins {}})]
      (is (seq (:errors tk)))
      (is (contains? (:tools tk) "read")))))

(deftest builtins-can-be-turned-off-entirely
  (let [tk (tn/build {:builtins false :skills (skills-root)})]
    (is (not (contains? (:tools tk) "read")))
    (is (contains? (:tools tk) "skill"))
    (is (= "disabled" (get (:statuses tk) "builtin")))))

(deftest skills-prompt-comes-from-the-skill-source
  (let [tk (tn/build {:skills (skills-root) :builtins false})
        p  (tn/skills-prompt tk)]
    (is (re-find #"## Available Skills" p))
    (is (re-find #"hello-world" p))))

(deftest adapters-agree-on-the-tool-set
  (let [tk (tn/build {:builtins {} :skills (skills-root)})
        n  (count (:tools tk))]
    (is (= n (count (tn/to-openai tk))))
    (is (= n (count (tn/to-anthropic tk))))
    (is (= n (count (:functionDeclarations (first (tn/to-gemini tk))))))
    (testing "and on the order, so two runtimes emit the same schema array"
      (is (= (tn/tool-names tk)
             (mapv #(get-in % [:function :name]) (tn/to-openai tk)))))))

(deftest shutdown-is-idempotent
  (let [tk (tn/build {:builtins false})]
    (is (= [] (:connections (tn/shutdown! (tn/shutdown! tk)))))))

(deftest names-never-shadow-clojure-core
  (doseq [n (keys (ns-publics 'toolnexus.core))]
    (is (nil? (resolve (symbol "clojure.core" (name n))))
        (str "toolnexus.core/" n " shadows clojure.core/" n))))
