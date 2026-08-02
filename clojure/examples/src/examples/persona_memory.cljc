;; 4. A PERSONA THAT REMEMBERS (SPEC §7E)
;;
;; The DIRECTORY is the agent. Its identity lives in files, and it edits its own
;; durable notes through the `memory` tool — an opt-in builtin that exists only
;; when you wire a home directory.
;;
;; The rule that surprises people: a memory write lands on DISK immediately but
;; does NOT change the current session's system prompt. It loads at the start of
;; the next session. That frozen snapshot is what keeps a long-lived persona
;; cache-stable, and this example demonstrates both halves.
(ns examples.persona-memory
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [toolnexus.agents.home :as home]
            [toolnexus.core :as toolnexus]
            [toolnexus.native :as native]))

(defn -main [& _]
  (let [dir (str (fs/temp-dir! "ava"))]
    (try
      (fs/write-file (str dir "/SOUL.md") "I am Ava. I answer in one sentence.")
      (fs/write-file (str dir "/MEMORY.md") "- the user ships on Fridays")

      ;; 1. The soul: every present bootstrap file, in the pinned §7E order.
      (let [{:keys [soul found]} (home/compose-soul dir)]
        (println "bootstrap files found:" (pr-str found))
        (println "\n-- composed soul --")
        (println soul)
        (assert (= ["SOUL.md" "MEMORY.md"] found)
                "SOUL.md outranks MEMORY.md — identity first, memory last"))

      ;; 2. The memory tool, in a toolkit beside anything else.
      (let [mem (home/memory-tool dir)
            tk  (toolnexus/build {:builtins false :tools [mem]})]
        (println "\ntools:" (pr-str (sort (toolnexus/tool-names tk))))

        (println "add    =>" (:output (toolnexus/execute
                                       tk "memory"
                                       {:action "add" :text "prefers Clojure"})))
        (println "replace=>" (:output (toolnexus/execute
                                       tk "memory"
                                       {:action "replace"
                                        :text "ships on Fridays"
                                        :with "ships on Tuesdays"})))

        ;; A miss is LOUD. An agent that thinks it saved something and did not is
        ;; worse than one that is told it failed.
        (let [miss (toolnexus/execute tk "memory"
                                      {:action "remove" :text "never written"})]
          (println "miss   =>" (:isError miss) "|" (:output miss))
          (assert (true? (:isError miss))))

        (println "\n-- MEMORY.md on disk --")
        (println (fs/read-file (str dir "/MEMORY.md")))
        (assert (str/includes? (fs/read-file (str dir "/MEMORY.md")) "prefers Clojure"))
        (assert (str/includes? (fs/read-file (str dir "/MEMORY.md")) "Tuesdays")))

      ;; 3. The frozen snapshot: the NEXT composition sees the edits.
      (let [{:keys [soul]} (home/compose-soul dir)]
        (assert (str/includes? soul "prefers Clojure")
                "next session's soul carries what this session wrote"))

      (println "OK")
      (finally (fs/delete-tree! dir)))))
