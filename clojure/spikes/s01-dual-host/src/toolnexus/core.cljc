(ns toolnexus.core
  "Pure port logic — NO reader conditionals below this line, ever.
  This is the shape the whole toolnexus port takes: a Tool is a map."
  (:require [toolnexus.host :as host]
            [clojure.string :as str]))

(defn define-tool [{:keys [name description parameters execute]}]
  {:name name :description description :parameters parameters
   :execute execute :source "native"})

(defn sanitize [s]
  (-> s (str/replace #"[^a-zA-Z0-9_-]" "_")))

(defn openai-schema [tool]
  {:type "function"
   :function {:name (sanitize (:name tool))
              :description (:description tool)
              :parameters (:parameters tool)}})

(defn run-demo []
  (let [t (define-tool {:name "add.two" :description "adds"
                        :parameters {:type "object"}
                        :execute (fn [{:keys [a b]}] (+ a b))})]
    (println "platform:" (host/platform))
    (println "sanitized:" (:name (:function (openai-schema t))))
    (println "execute:" ((:execute t) {:a 2 :b 3}))
    (println "sh:" (pr-str (host/sh ["tr" "a-z" "A-Z"] "hello dual host")))))
