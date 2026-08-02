;; toolnexus sub-agents in Clojure — ask a question; the agent delegates to a
;; budgeted SUB-AGENT that has its own tools, and uses a SKILL for the style.
;;
;;   OPENROUTER_API_KEY=... clojure -M -m newsroom
;;
;; The "researcher" lives in a §7D runtime with its OWN soul, its own tool view
;; (release-facts — the main agent cannot call it directly) and a hard budget.
;; `rt/agent-tool` drops that whole agent into the toolkit as one ordinary
;; tool. Watch the trace: the main agent calls `researcher` like any tool; a
;; whole second agent loop runs behind that one call.
(ns newsroom
  (:require [koine.env :as env]
            [koine.json :as json]
            [toolnexus.core :as tn]
            [toolnexus.client :as client]
            [toolnexus.native :as native]
            [toolnexus.agents.runtime :as rt]))

(def release-facts
  (native/native-tool
   {:name         "release-facts"
    :description  "The verified facts about the toolnexus 0.13.0 release."
    :input-schema {:type "object" :properties {}}
    :run (fn [_]
           (json/write-str
            {:version    "0.13.0"
             :languages  7
             :clojure    {:tests 395 :assertions 1614
                          :runtimes ["Clojure (JVM)" "cljgo (compiles to a native Go binary)"]
                          :dep "net.clojars.muthuishere/toolnexus {:mvn/version \"0.13.0\"}"}
             :honest-limitation "streaming is buffered in the Clojure port — no token deltas yet"}))}))

(def llm
  {:base-url "https://openrouter.ai/api/v1"
   :style    "openai"
   :model    (or (env/get-env "TN_MODEL") "openai/gpt-4o-mini")
   :api-key  (env/get-env "OPENROUTER_API_KEY")})

(defn print-scan [tk]
  (println "== scan ==============================================")
  (doseq [n (tn/tool-names tk)]
    (let [t (get-in tk [:tools n])
          d (str (:description t))]
      (println (str "  tool:  " n " -- "
                    (if (> (count d) 90) (str (subs d 0 90) "...") d)))))
  (doseq [s (get-in tk [:skills :skills])]
    (println (str "  skill: " (:name s) " — " (:description s))))
  (println "======================================================"))

(defn trace [ev]
  (case (:type ev)
    "tool_call"   (println (str ">> tool call   " (:name ev) " "
                                (json/write-str (or (:args ev) {}))))
    "tool_result" (let [o (str (:output ev))]
                    (println (str "<< tool result " (:name ev)
                                  (when (:isError ev) " [ERROR]") " — "
                                  (if (> (count o) 160) (str (subs o 0 160) "…") o))))
    nil))

(def question
  "How well is the Clojure port of toolnexus tested, and on which runtimes does it run? Ask your researcher — don't guess.")

(defn -main [& _]
  (let [runtime (rt/create-runtime
                 {:llm llm
                  :registry
                  {"researcher"
                   {:name   "researcher"
                    :does   "Digs up verified toolnexus release facts. Ask it a question; it answers ONLY from its tools, never from memory."
                    :soul   "You are a meticulous researcher. Answer strictly from your tools' output, tersely. If a fact is not in the output, say so."
                    :tools  [release-facts]
                    :budget {:max-turns 4 :max-tokens 20000}}}})
        tk (tn/build {:skills   "skills"   ; the helper style, loaded on demand
                      :builtins false
                      :tools    [(rt/agent-tool runtime "researcher")]})
        _  (print-scan tk)
        _  (println "question:" question)
        _  (println)
        c  (client/create-client llm)
        r  (client/run c question {:toolkit tk :on-event trace})]
    (println)
    (println "== answer ============================================")
    (println (:text r))
    (println "======================================================")
    (println "turns:" (:turns r)
             "| tool calls:" (:tool-call-count r)
             "| tokens:" (:total-tokens (:usage r)))))
