;; toolnexus in Clojure — ask a question; the agent uses a SKILL and an HTTP TOOL.
;;
;;   OPENROUTER_API_KEY=... clojure -M -m demo
;;
;; The tool is `http-tool` — a declarative HTTP endpoint as a tool, no client
;; code: it fetches https://clojure.org/community/events and returns the page.
;; The skill (skills/clojure-events/SKILL.md) tells the model HOW to report
;; what it fetched. Watch the trace: loading a skill IS a call to the `skill`
;; tool, then the fetch happens as an ordinary tool call.
(ns demo
  (:require [koine.env :as env]
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

(defn print-scan
  "What did the toolkit discover? Tools from every source, skills by name."
  [tk]
  (println "== scan ==============================================")
  (doseq [n (tn/tool-names tk)]
    (let [t (get-in tk [:tools n])
          d (str (:description t))]
      (println (str "  tool:  " n " -- "
                    (if (> (count d) 90) (str (subs d 0 90) "...") d)))))
  (doseq [s (get-in tk [:skills :skills])]
    (println (str "  skill: " (:name s) " — " (:description s))))
  (println "======================================================"))

(defn trace
  "Every step of the loop, as it happens."
  [ev]
  (case (:type ev)
    "tool_call"   (println (str ">> tool call   " (:name ev) " "
                                (json/write-str (or (:args ev) {}))))
    "tool_result" (let [o (str (:output ev))]
                    (println (str "<< tool result " (:name ev)
                                  (when (:isError ev) " [ERROR]") " — "
                                  (if (> (count o) 160) (str (subs o 0 160) "…") o))))
    nil))

(def question
  "What Clojure community events are coming up? Load your clojure-events skill first, then answer.")

(defn -main [& _]
  (let [tk (tn/build {:skills   "skills"        ; every SKILL.md under skills/
                      :builtins false
                      :tools    [events-page]})
        _  (print-scan tk)
        _  (println "question:" question)
        _  (println)
        c  (client/create-client
            {:base-url "https://openrouter.ai/api/v1"
             :style    "openai"
             :model    (or (env/get-env "TN_MODEL") "openai/gpt-4o-mini")
             ;; explicit, because the env fallback prefers OPENAI_API_KEY for
             ;; openai style — and that key is not valid at OpenRouter
             :api-key  (env/get-env "OPENROUTER_API_KEY")})
        r  (client/run c question {:toolkit tk :on-event trace})]
    (println)
    (println "== answer ============================================")
    (println (:text r))
    (println "======================================================")
    (println "turns:" (:turns r)
             "| tool calls:" (:tool-call-count r)
             "| tokens:" (:total-tokens (:usage r)))))
