(ns toolnexus.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [koine.env :as env]
            [koine.json :as json]
            [koine.server :as server]
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

;; ---------------------------------------------------------------------------
;; the remaining toolkit options (§3 S1/S2/S5, §7A agents, §4A drop-lists, §10)
;; ---------------------------------------------------------------------------
;;
;; Expected behaviour is read off js/src/toolkit.ts (`Toolkit.create`) and
;; js/src/skill.ts, which is what the other five ports were built to.

;; A minimal A2A peer, only complete enough to serve one Agent Card.
(def ^:private peer (atom nil))

(defn- start-card-peer! []
  (let [base (atom "")
        h    (server/serve
              (fn [req]
                (if (str/includes? (str (:path req)) "agent-card.json")
                  {:status 200 :headers {"content-type" "application/json"}
                   :body (json/write-str
                          {:name "Card Bot" :description "one skill"
                           :version "0.1.0" :protocolVersion "0.3.0"
                           :url (str @base "/")
                           :capabilities {} :defaultInputModes ["text"]
                           :defaultOutputModes ["text"]
                           :skills [{:id "echo" :name "Echo" :description "Echo a task back"}]})}
                  {:status 404 :body "no"}))
              {:port 0})]
    (reset! base (str "http://127.0.0.1:" (server/port h)))
    {:handle h :base @base}))

(use-fixtures :once
  (fn [f]
    (reset! peer (start-card-peer!))
    (try (f)
         (finally (server/stop! (:handle @peer)) (reset! peer nil)))))

(defn- card-url [] (str (:base @peer) "/.well-known/agent-card.json"))

(defn- dead-card-url []
  (let [h (server/serve (fn [_] {:status 200 :body "ok"}) {:port 0})
        p (server/port h)]
    (server/stop! h)
    (str "http://127.0.0.1:" p "/.well-known/agent-card.json")))

;; --- 4.1 :skill-provider ---------------------------------------------------

(deftest skill-provider-contributes-skills
  (let [tk (tn/build {:builtins false
                      :skill-provider (fn [] [{:name "prov" :description "From a provider."
                                               :content "Provider body."}])})]
    (is (contains? (:tools tk) "skill"))
    (is (str/includes? (tn/skills-prompt tk) "- **prov**: From a provider."))
    (is (str/includes? (:output (tn/execute tk "skill" {"name" "prov"})) "Provider body."))))

(deftest a-failing-skill-provider-is-isolated
  ;; Scenario "Provider failure is isolated" — the directory source still loads,
  ;; mirroring the per-server MCP isolation of §0.3.
  (let [tk (tn/build {:builtins false :skills (skills-root)
                      :skill-provider (fn [] (throw (ex-info "boom" {})))})]
    (is (str/includes? (tn/skills-prompt tk) "hello-world"))))

(deftest provider-and-data-and-directory-all-compose
  (let [tk (tn/build {:builtins false
                      :skills (skills-root)
                      :skill-defs [{:name "inline" :description "d1" :content "b1"}]
                      :skill-provider (fn [] [{:name "lazy" :description "d2" :content "b2"}])})
        p  (tn/skills-prompt tk)]
    (is (str/includes? p "hello-world"))
    (is (str/includes? p "- **inline**: d1"))
    (is (str/includes? p "- **lazy**: d2"))))

;; --- 4.2 :skills-filter ----------------------------------------------------

(deftest skills-filter-is-an-allowlist-over-the-catalog
  (let [defs [{:name "a" :description "da" :content "ba"}
              {:name "b" :description "db" :content "bb"}
              {:name "c" :description "dc" :content "bc"}]
        tk   (tn/build {:builtins false :skill-defs defs :skills-filter {"a" true "b" true}})
        p    (tn/skills-prompt tk)]
    (is (str/includes? p "- **a**: da"))
    (is (str/includes? p "- **b**: db"))
    (is (not (str/includes? p "- **c**: dc")))
    (testing "the prompt catalog and the tool's lookup agree"
      (is (true? (:isError (tn/execute tk "skill" {"name" "c"})))))))

(deftest skills-filter-drop-list-removes-named-skills
  (let [defs [{:name "a" :description "da" :content "ba"}
              {:name "c" :description "dc" :content "bc"}]
        tk   (tn/build {:builtins false :skill-defs defs :skills-filter {"c" false}})]
    (is (str/includes? (tn/skills-prompt tk) "- **a**: da"))
    (is (true? (:isError (tn/execute tk "skill" {"name" "c"}))))))

;; --- 4.3 :skill-sample-limit ----------------------------------------------

(deftest skill-sample-limit-of-minus-one-omits-the-file-block
  (let [tk (tn/build {:builtins false :skills (skills-root) :skill-sample-limit -1})]
    (is (not (str/includes? (:output (tn/execute tk "skill" {"name" "hello-world"}))
                            "<skill_files>")))))

(deftest skill-sample-limit-of-zero-is-the-default
  (let [a (tn/build {:builtins false :skills (skills-root)})
        b (tn/build {:builtins false :skills (skills-root) :skill-sample-limit 0})]
    (is (= (:output (tn/execute a "skill" {"name" "hello-world"}))
           (:output (tn/execute b "skill" {"name" "hello-world"}))))))

;; --- 5.1 :agents -----------------------------------------------------------

(deftest agents-become-tools
  (let [tk (tn/build {:builtins false :agents [{:card (card-url) :timeout 3000}]})]
    (is (contains? (:tools tk) "Card_Bot_echo"))
    (is (= "a2a" (:source (get (:tools tk) "Card_Bot_echo"))))))

(deftest a-failing-agent-is-isolated-not-fatal
  (let [tk (tn/build {:builtins {} :agents [{:card (dead-card-url) :timeout 500}]})]
    (is (contains? (:tools tk) "read"))
    (is (empty? (filter #(str/starts-with? % "Card_Bot") (tn/tool-names tk))))))

(deftest a-top-level-agents-block-in-the-config-is-honoured
  ;; js/src/toolkit.ts pushes parseAgentsConfig(config.agents) alongside the
  ;; `agents:` option — mirroring how `mcpServers` is read off the same object.
  (let [tk (tn/build {:builtins false
                      :mcp {:agents {:bot {:card (card-url) :timeout 3000}}}})]
    (is (contains? (:tools tk) "Card_Bot_echo"))))

(deftest a-disabled-agent-in-the-config-block-is-skipped
  (let [tk (tn/build {:builtins false
                      :mcp {:agents {:bot {:card (card-url) :timeout 3000 :enabled false}}}})]
    (is (not (contains? (:tools tk) "Card_Bot_echo")))))

;; --- 5.2 toolkit-level :wait-for ------------------------------------------

(deftest wait-for-is-carried-onto-the-toolkit
  ;; The wire-level proof (capabilities.elicitation advertised only with a
  ;; waitFor) lives in mcp_test against a real peer; here the contract is that
  ;; build accepts it, keeps it, and stays byte-identical without one.
  (let [wf (fn [_] {:ok true})
        tk (tn/build {:builtins false :skills (skills-root) :wait-for wf})]
    (is (= wf (:wait-for tk)))
    (is (= (tn/tool-names (tn/build {:builtins false :skills (skills-root)}))
           (tn/tool-names tk)))))

;; --- 5.3 :disable-tools / :disable-skills ---------------------------------

(deftest disable-tools-drops-by-final-exposed-name
  (let [tk (tn/build {:skills (skills-root)
                      :tools [(tool/tool {:name "mine" :source "native"
                                          :execute (fn [_] (tool/success "ok"))})]
                      :disable-tools ["bash" "mine" "skill"]})]
    (is (not (contains? (:tools tk) "bash")))
    (is (not (contains? (:tools tk) "mine")))
    (is (not (contains? (:tools tk) "skill")))
    (testing "everything else survives"
      (is (contains? (:tools tk) "read")))))

(deftest disable-tools-is-applied-across-every-source
  (let [tk (tn/build {:builtins false :agents [{:card (card-url) :timeout 3000}]
                      :disable-tools ["Card_Bot_echo"]})]
    (is (not (contains? (:tools tk) "Card_Bot_echo")))))

(deftest an-empty-disable-tools-changes-nothing
  (is (= (tn/tool-names (tn/build {:skills (skills-root)}))
         (tn/tool-names (tn/build {:skills (skills-root) :disable-tools []})))))

(deftest disable-skills-is-sugar-over-a-filter-drop-list
  (let [defs [{:name "a" :description "da" :content "ba"}
              {:name "c" :description "dc" :content "bc"}]
        tk   (tn/build {:builtins false :skill-defs defs :disable-skills ["c"]})]
    (is (str/includes? (tn/skills-prompt tk) "- **a**: da"))
    (is (true? (:isError (tn/execute tk "skill" {"name" "c"}))))
    (testing "the `skill` tool itself is untouched — only its catalog shrinks"
      (is (contains? (:tools tk) "skill")))))

(deftest an-explicit-skills-filter-entry-beats-disable-skills
  ;; js/src/toolkit.ts spreads disableSkills FIRST and skillsFilter after, so an
  ;; explicit entry wins. Asserted because the merge order is the whole rule.
  (let [defs [{:name "a" :description "da" :content "ba"}
              {:name "c" :description "dc" :content "bc"}]
        tk   (tn/build {:builtins false :skill-defs defs
                        :disable-skills ["c"] :skills-filter {"c" true}})]
    (is (false? (:isError (tn/execute tk "skill" {"name" "c"}))))
    (testing "and that explicit true turns the map into an allowlist, dropping a"
      (is (true? (:isError (tn/execute tk "skill" {"name" "a"})))))))
