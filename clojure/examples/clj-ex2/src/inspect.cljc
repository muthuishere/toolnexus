;; toolnexus + MCP in Clojure — ask a question; the agent answers it with a
;; real MCP server's tools (stdio), no hand-written file tools anywhere.
;;
;;   OPENROUTER_API_KEY=... clojure -M -m inspect
;;
;; What you will see, in order:
;;   1. the scan — every tool the MCP server advertised, now in the toolkit
;;   2. the question
;;   3. every MCP tools/call the model makes, live
;;   4. the answer
(ns inspect
  (:require [koine.env :as env]
            [koine.json :as json]
            [toolnexus.core :as tn]
            [toolnexus.client :as client]))

(def mcp-config
  ;; The same shape as an mcp.json file — `tn/build` takes the map directly.
  {:mcpServers
   {:files {:type    "local"
            :command ["npx" "-y" "@modelcontextprotocol/server-filesystem" "."]
            :timeout 30000}}})

(defn print-scan [tk]
  (println "== scan ==============================================")
  (doseq [n (tn/tool-names tk)]
    (let [t (get-in tk [:tools n])
          d (str (:description t))]
      (println (str "  tool:  " n " -- "
                    (if (> (count d) 90) (str (subs d 0 90) "...") d)))))
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
  "What files are in this project, and what is it for? Read whatever you need to find out.")

(defn -main [& _]
  (let [tk (tn/build {:mcp mcp-config :builtins false})
        _  (print-scan tk)
        _  (println "question:" question)
        _  (println)
        c  (client/create-client
            {:base-url "https://openrouter.ai/api/v1"
             :style    "openai"
             :model    (or (env/get-env "TN_MODEL") "openai/gpt-4o-mini")
             :api-key  (env/get-env "OPENROUTER_API_KEY")})
        r  (client/run c question {:toolkit tk :on-event trace})]
    (println)
    (println "== answer ============================================")
    (println (:text r))
    (println "======================================================")
    (println "turns:" (:turns r)
             "| tool calls:" (:tool-call-count r)
             "| tokens:" (:total-tokens (:usage r)))
    ;; disconnect the stdio server — otherwise the npx child outlives us
    (tn/shutdown! tk)))
