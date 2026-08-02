;; toolnexus in Clojure — an interactive agent in your terminal.
;;
;;   OPENROUTER_API_KEY=... clojure -M -m chat
;;
;; One toolkit, three sources:
;;   - an HTTP TOOL   — fetches the official Clojure events page, one declaration
;;   - an MCP SERVER  — @modelcontextprotocol/server-filesystem over stdio
;;   - an AGENT SKILL — skills/clojure-events/SKILL.md, loaded on demand
;;
;; You keep asking questions; the agent keeps answering. `client/ask` with an
;; :id remembers the conversation, so follow-ups work. Every tool call prints
;; as it happens. Type `exit` (or Ctrl-D) to quit.
(ns chat
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.json :as json]
            [toolnexus.core :as tn]
            [toolnexus.client :as client]
            [toolnexus.http :as http]))

(def events-page
  (http/http-tool
   {:name        "clojure-events-page"
    :description "Fetches the official Clojure community events page and returns it as text."
    :method      :get
    :url         "https://clojure.org/community/events"
    :result-mode "text"}))

(def mcp-config
  ;; The same shape as an mcp.json file — `tn/build` takes the map directly.
  {:mcpServers
   {:files {:type    "local"
            :command ["npx" "-y" "@modelcontextprotocol/server-filesystem" "."]
            :timeout 30000}}})

(defn print-welcome [tk]
  (println)
  (println "toolnexus chat — one toolkit, three sources. What I have:")
  (println)
  (doseq [n (tn/tool-names tk)]
    (let [t (get-in tk [:tools n])
          d (str (:description t))
          src (if (str/starts-with? n "files_") "mcp:files" "tool")]
      (println (str "  [" src "] " n " -- "
                    (if (> (count d) 80) (str (subs d 0 80) "...") d)))))
  (doseq [s (get-in tk [:skills :skills])]
    (println (str "  [skill] " (:name s) " — " (:description s))))
  (println)
  (println "Things to try:")
  (println "  what clojure events are coming up?")
  (println "  what files are in this folder?")
  (println "  read deps.edn — what does this project depend on?")
  (println "  which of those events is closest to the deps you just read? (memory!)")
  (println)
  (println "Type your question. `exit` or Ctrl-D quits.")
  (println))

(defn trace [ev]
  (case (:type ev)
    "tool_call"   (println (str "  >> tool call   " (:name ev) " "
                                (json/write-str (or (:args ev) {}))))
    "tool_result" (let [o (str (:output ev))]
                    (println (str "  << tool result " (:name ev)
                                  (when (:isError ev) " [ERROR]") " — "
                                  (if (> (count o) 120) (str (subs o 0 120) "…") o))))
    nil))

(defn -main [& _]
  (let [tk (tn/build {:skills   "skills"
                      :builtins false
                      :mcp      mcp-config
                      :tools    [events-page]})
        c  (client/create-client
            {:base-url "https://openrouter.ai/api/v1"
             :style    "openai"
             :model    (or (env/get-env "TN_MODEL") "openai/gpt-4o-mini")
             ;; explicit, because the env fallback prefers OPENAI_API_KEY for
             ;; openai style — and that key is not valid at OpenRouter
             :api-key  (env/get-env "OPENROUTER_API_KEY")})]
    (print-welcome tk)
    (loop []
      (print "you> ")
      (flush)
      (let [line (read-line)]
        (cond
          (or (nil? line) (contains? #{"exit" "quit"} (str/trim line)))
          (do (println "bye.")
              (tn/shutdown! tk))

          (str/blank? line)
          (recur)

          :else
          (let [r (client/ask c line {:toolkit tk :id "cli" :on-event trace})]
            (println)
            (println (str "agent> " (:text r)))
            (println (str "       (turns " (:turns r)
                          " | tool calls " (:tool-call-count r)
                          " | tokens " (:total-tokens (:usage r)) ")"))
            (println)
            (recur)))))))
