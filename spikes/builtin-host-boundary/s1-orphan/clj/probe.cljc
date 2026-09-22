;; S1 probe (clojure, both hosts). Modes:
;;   naive   — koine proc/sh with :timeout-ms, which is what builtin.cljc:254 does
;;             today. koine kills the DIRECT child (.destroyForcibly / cljg kill).
;;   postwalk— write $$ to a pidfile, and after the timeout try to walk `ps` from
;;             it. Asserts the reparenting trap: the parent is already gone, so
;;             the descendants are no longer reachable from its pid.
;;   pgroup  — make the child a process-group LEADER with perl's setpgrp, so the
;;             group id is knowable up front and `kill -- -PGID` reaches the tree
;;             without needing a live parent.
(ns probe
  (:require [koine.process :as proc]
            [koine.fs :as fs]
            [clojure.string :as str]))

(defn -main [& [mode marker pidfile]]
  (let [cmd (str "sleep 0.2; sh -c 'sleep 1; touch " marker "'")]
    (case mode
      "naive"
      (proc/sh ["sh" "-c" cmd] {:timeout-ms 300})

      "postwalk"
      (do (proc/sh ["sh" "-c" (str "echo $$ > " pidfile "; " cmd)] {:timeout-ms 300})
          (let [pid (str/trim (fs/read-file pidfile))
                ps  (:out (proc/sh ["ps" "-eo" "pid=,ppid="] {:timeout-ms 5000}))
                kids (->> (str/split-lines ps)
                          (keep (fn [l]
                                  (let [[p pp] (str/split (str/trim l) #"\s+")]
                                    (when (= pp pid) p)))))]
            (println (str "REACHABLE_AFTER_KILL=" (count kids)))
            (doseq [k kids] (proc/sh ["kill" "-KILL" k] {:timeout-ms 2000}))))

      "pgroup"
      (do (proc/sh ["sh" "-c" (str "echo $$ > " pidfile
                                   "; exec perl -e 'setpgrp(0,0); exec @ARGV' sh -c " (pr-str cmd))]
                   {:timeout-ms 300})
          (let [pid (str/trim (fs/read-file pidfile))]
            (println (str "PGID=" pid))
            (proc/sh ["kill" "-TERM" (str "-" pid)] {:timeout-ms 2000})
            (proc/sh ["sh" "-c" (str "sleep 0.2; kill -KILL -" pid " 2>/dev/null || true")] {:timeout-ms 3000}))))
    (println "killed")))
