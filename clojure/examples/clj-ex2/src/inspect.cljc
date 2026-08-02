;; toolnexus + MCP in Clojure — the agent inspects its own project through a
;; real MCP server (stdio), no hand-written file tools anywhere.
;;
;;   OPENROUTER_API_KEY=... clojure -M -m inspect
;;
;; `tn/build` reads the mcp config, spawns `@modelcontextprotocol/server-filesystem`
;; over stdio, and every tool the server advertises joins the registry under
;; `files_<tool>`. The LLM discovers them like any other tool — that uniformity
;; is the whole product.
(ns inspect
  (:require [koine.env :as env]
            [toolnexus.core :as tn]
            [toolnexus.client :as client]))

(def mcp-config
  ;; The same shape as an mcp.json file — `tn/build` takes the map directly.
  {:mcpServers
   {:files {:type    "local"
            :command ["npx" "-y" "@modelcontextprotocol/server-filesystem" "."]
            :timeout 30000}}})

(defn -main [& _]
  (let [tk (tn/build {:mcp mcp-config :builtins false})
        _  (println "tools from the MCP server:" (tn/tool-names tk))
        c  (client/create-client
            {:base-url "https://openrouter.ai/api/v1"
             :style    "openai"
             :model    (or (env/get-env "TN_MODEL") "openai/gpt-4o-mini")
             :api-key  (env/get-env "OPENROUTER_API_KEY")})
        r  (client/run c
             (str "Using your filesystem tools, list the files in the current "
                  "directory, then read deps.edn and the source under src/. "
                  "Report in at most 5 bullets: what this project is, its one "
                  "dependency and version, and which MCP tools you called.")
             {:toolkit tk})]
    (println "----------------------------------------")
    (println (:text r))
    (println "----------------------------------------")
    (println "turns:" (:turns r)
             "| tool calls:" (:tool-call-count r)
             "| tokens:" (:total-tokens (:usage r)))
    ;; disconnect the stdio server — otherwise the npx child outlives us
    (tn/shutdown! tk)))
