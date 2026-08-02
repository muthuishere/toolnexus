;; The CLOJURE (JVM) entry file. A plain `.clj` — the JVM reader picks this and
;; never sees main.cljg.
;;
;;   clojure -M -m app.main
(ns app.main
  (:require [app.core :as core]))

(defn -main [& _]
  (println (core/report "/tmp/koine-minimal-jvm.txt")))
