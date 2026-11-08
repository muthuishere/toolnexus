;; deps.edn: {:paths ["."] :deps {net.clojars.muthuishere/toolnexus {:mvn/version "0.27.0"}}}
;; run:      clojure -M -m main claude -p        (any CLI that reads a prompt on stdin)
(ns main
  (:require [koine.json :as json]
            [oneshot :refer [one-shot-client]]
            [toolnexus.client :as client]
            [toolnexus.core :as tn]
            [toolnexus.native :as native]))

(def add
  (native/native-tool
   {:name "add" :description "Add two numbers."
    :input-schema {:type "object" :properties {:a {:type "number"} :b {:type "number"}} :required ["a" "b"]}
    :run (fn [{:keys [a b]}] (str (+ a b)))}))

(defn -main [& command]
  (let [tk (tn/build {:builtins false :tools [add]})
        r  (client/run (one-shot-client {:command command})
                       "Use the add tool to add 19 and 23, then tell me the sum." {:toolkit tk})]
    (println "text:" (json/write-str (:text r)) "toolCalls:" (json/write-str (:tool-calls r)))
    (shutdown-agents)))
