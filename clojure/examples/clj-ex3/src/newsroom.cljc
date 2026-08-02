;; toolnexus sub-agents in Clojure — an agent IS a tool.
;;
;;   OPENROUTER_API_KEY=... clojure -M -m newsroom
;;
;; A "researcher" sub-agent lives in a §7D runtime with its OWN soul, its own
;; tool view (release-facts — the writer cannot call it directly) and its own
;; budget that stops it loudly if it runs away. `rt/agent-tool` drops that whole
;; agent into the writer's toolkit as one ordinary tool, next to the announcer
;; SKILL.md. The writer delegates; it cannot tell a sub-agent from a function.
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
        tk (tn/build {:skills   "skills"   ; the announcer house style, on demand
                      :builtins false
                      :tools    [(rt/agent-tool runtime "researcher")]})
        c  (client/create-client llm)
        r  (client/run c
             (str "Announce the new Clojure support in toolnexus. Load your "
                  "announcer skill for the house style, and delegate ALL "
                  "fact-gathering to your researcher — never invent a number.")
             {:toolkit tk})]
    (println "----------------------------------------")
    (println (:text r))
    (println "----------------------------------------")
    (println "turns:" (:turns r)
             "| tool calls:" (:tool-call-count r)
             "| tokens:" (:total-tokens (:usage r)))))
