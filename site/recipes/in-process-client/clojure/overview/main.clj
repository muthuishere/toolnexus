;; deps.edn: {:deps {net.clojars.muthuishere/toolnexus {:mvn/version "0.30.0"}}}
;; run:      clojure -M main.clj
(ns main
  (:require [toolnexus.client :as client]
            [toolnexus.core :as tn]
            [toolnexus.native :as native]))

(def add
  (native/native-tool
   {:name "add" :description "Add two numbers."
    :input-schema {:type "object" :properties {:a {:type "number"} :b {:type "number"}} :required ["a" "b"]}
    :run (fn [{:keys [a b]}] (str (+ a b)))}))

;; Your "model": first turn asks for a tool, second turn streams the answer.
(def c
  (client/create-in-process-client
   {:model "my-model"
    :generate-stream
    (fn [req emit]
      (let [last-msg (last (:messages req))]
        (if (not= "tool" (:role last-msg))
          {:tool-calls [{:name "add" :arguments {:a 2 :b 3}}]}
          (let [words ["The " "sum " "is " (str (:content last-msg)) "."]]
            (doseq [w words] (emit {:type "text" :delta w})) ; deltas must add up to content
            {:content (apply str words)}))))}))

(def toolkit (tn/build {:builtins false :tools [add]}))

;; No stream API in this port: events go to run's :on-event sink as they happen.
(client/run c "What is 2 + 3?"
            {:toolkit toolkit
             :on-event (fn [ev]
                         (case (:type ev)
                           "text"      (do (print (:delta ev)) (flush))
                           "tool_call" (println (str "[calling " (:name ev) "]"))
                           nil))})
(println) ; The sum is 5.
(shutdown-agents)
