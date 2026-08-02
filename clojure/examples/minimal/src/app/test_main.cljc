;; The test ENTRY, shared. Runs the suite in-process and gates on the returned
;; summary map rather than on the exit code, because on cljgo exit 0 means
;; nothing threw - not that anything ran. A suite that collects zero tests must
;; fail, not look green.
(ns app.test-main
  (:require [clojure.test :as t]
            [app.core-test]))

(defn -main [& _]
  (let [summary (t/run-tests 'app.core-test)]
    (println (str "tests=" (:test summary)
                  " assertions=" (+ (:pass summary) (:fail summary) (:error summary))
                  " fail=" (:fail summary)
                  " error=" (:error summary)))
    (when (zero? (:test summary))
      (println "GATE FAILED: zero tests collected")
      (throw (ex-info "zero tests collected" {})))
    (when (pos? (+ (:fail summary) (:error summary)))
      (throw (ex-info "test failures" {:summary summary})))
    (println "GATE OK")))
