;; Gate 4, against the REAL shipped composer — JVM only, and only because the
;; spike must not put the port's source on the dual-host build path. Same
;; assertion as classifier/gate-4; the replica in classifier.cljc is what runs
;; on cljgo.
;;
;;   clojure -Sdeps '{:paths ["src" "jvmonly" "../../../clojure/src"]}' -M -m toolnexus.realloop
(ns toolnexus.realloop
  (:require [toolnexus.agents.loop :as tnloop]
            [toolnexus.classifier :as c]))

(defn -main [& _]
  (let [ran   (atom 0)
        prior (fn [_ev] "blocked by policy")
        rail  (c/as-guardrail (c/bash-judge) {:ask "needs your say-so" :deny "refused"})
        rail' (fn [ev] (swap! ran inc) (rail ev))
        hooks (tnloop/guarded-hooks [prior rail'] nil)
        out   ((:before-tool hooks) {:command "git status --short"})]
    (println (pr-str {:real-fn        'toolnexus.agents.loop/guarded-hooks
                      :result         (get-in out [:result :output])
                      :judge-ran      @ran
                      :pass           (and (= "denied: blocked by policy" (get-in out [:result :output]))
                                           (true? (get-in out [:result :is-error]))
                                           (zero? @ran))}))))
