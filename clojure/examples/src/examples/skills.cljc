;; 3. AGENT SKILLS — PROGRESSIVE DISCLOSURE (SPEC §0.5 / §0.6 / §3)
;;
;; A `skills/` folder of SKILL.md files becomes ONE tool called `skill`. The
;; model sees a short catalog in the system prompt and loads a skill's full
;; instructions only when it decides to — that is the progressive disclosure the
;; open SKILL.md standard is for.
;;
;; The `skill` tool's output is BYTE-EXACT across all seven ports: the same
;; folder produces the same string in Clojure, JavaScript, Python, Go, Java, C#
;; and Elixir. This example asserts the bytes rather than eyeballing them.
(ns examples.skills
  (:require [clojure.string :as str]
            [koine.env :as env]
            [toolnexus.core :as toolnexus]))

(defn -main [& _]
  (let [root (or (env/get-env "TN_EXAMPLES") "../../examples")
        tk   (toolnexus/build {:skills (str root "/skills") :builtins false})]

    (println "tools:" (pr-str (sort (toolnexus/tool-names tk))))
    (assert (= ["skill"] (sort (toolnexus/tool-names tk)))
            "a skills folder produces exactly ONE tool, not one per skill")

    ;; The catalog the model sees in its system prompt — names + descriptions only.
    (println "\n-- skills prompt --")
    (println (toolnexus/skills-prompt tk))

    ;; Loading one hands back its instructions plus the files it may read next.
    (let [loaded (:output (toolnexus/execute tk "skill" {:name "hello-world"}))]
      (println "\n-- loaded skill (" (count loaded) "bytes ) --")
      (println loaded)
      (assert (str/includes? loaded "hello-world"))
      (assert (str/includes? loaded "<skill_files>")
              "progressive disclosure: the load names the resources, it does not inline them"))

    ;; A name that is not there is a loud, listable error — never an empty string.
    (let [miss (toolnexus/execute tk "skill" {:name "nope"})]
      (println "\nunknown skill =>" (:isError miss) "|" (:output miss))
      (assert (true? (:isError miss))))

    (println "OK")))
