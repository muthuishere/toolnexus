;; toolnexus in Clojure, against a real LLM — one dep, three sources.
;;
;;   OPENROUTER_API_KEY=... clojure -M -m demo
;;
;; The agent gets: an AGENT SKILL (skills/announcer — loaded on demand, its
;; catalog injected into the system prompt) and a NATIVE TOOL (release-facts —
;; a plain Clojure function). It is asked to announce toolnexus itself, and the
;; skill's style rules + the tool's real numbers shape what comes back.
(ns demo
  (:require [koine.env :as env]
            [koine.json :as json]
            [toolnexus.core :as tn]
            [toolnexus.client :as client]
            [toolnexus.native :as native]))

(def release-facts
  (native/native-tool
   {:name         "release-facts"
    :description  "The verified facts about the toolnexus 0.13.0 release. Call this before announcing anything."
    :input-schema {:type "object" :properties {}}
    :run (fn [_]
           (json/write-str
            {:version    "0.13.0"
             :languages  7
             :clojure    {:tests 395 :assertions 1614
                          :runtimes ["Clojure (JVM)" "cljgo (compiles to a native Go binary)"]
                          :execution-modes-verified 5
                          :dep "net.clojars.muthuishere/toolnexus {:mvn/version \"0.13.0\"}"}
             :registries ["Clojars" "npm" "PyPI" "Maven Central" "NuGet" "Hex" "Go modules"]
             :honest-limitation "streaming is buffered in the Clojure port — no token deltas yet"}))}))

(defn -main [& _]
  (let [tk (tn/build {:skills   "skills"        ; every SKILL.md, loaded on demand
                      :builtins false
                      :tools    [release-facts]})
        c  (client/create-client
            {:base-url "https://openrouter.ai/api/v1"
             :style    "openai"
             :model    (or (env/get-env "TN_MODEL") "openai/gpt-4o-mini")
             ;; explicit, because the env fallback prefers OPENAI_API_KEY for
             ;; openai style — and that key is not valid at OpenRouter
             :api-key  (env/get-env "OPENROUTER_API_KEY")})
        r  (client/run c
             "Announce the new Clojure support in toolnexus. Load your announcer skill first, then get the facts."
             {:toolkit tk})]
    (println "----------------------------------------")
    (println (:text r))
    (println "----------------------------------------")
    (println "turns:" (:turns r)
             "| tool calls:" (:tool-call-count r)
             "| tokens:" (:total-tokens (:usage r)))))
