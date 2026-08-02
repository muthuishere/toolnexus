;; 2. YOUR OWN FUNCTIONS AND A REST ENDPOINT, AS TOOLS (SPEC §0.8 / §0.9)
;;
;; A native tool is a Clojure fn. An HTTP tool is a URL. To the model they are
;; the same thing an MCP tool is — a named, described, schema'd callable. That
;; is the whole idea the library is built on.
;;
;; Hermetic: the "REST API" is a koine server on 127.0.0.1, started here.
(ns examples.native-and-http
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.core :as toolnexus]
            [toolnexus.http :as http]
            [toolnexus.native :as native]))

(defn -main [& _]
  (let [srv (server/serve
             (fn [_req] {:status 200
                         :headers {"content-type" "application/json"}
                         :body (json/write-str {:tempC 21 :sky "clear"})})
             {:port 0})
        base (str "http://127.0.0.1:" (server/port srv))]
    (try
      (let [tk (toolnexus/build
                {:builtins false
                 :tools [(native/native-tool
                          {:name         "word_count"
                           :description  "Count the words in a piece of text."
                           :input-schema {:type "object"
                                          :properties {:text {:type "string"}}
                                          :required ["text"]}
                           :run (fn [args]
                                  (str (count (remove empty? (str/split
                                                              (str (:text args)) #"\s+")))))})
                         (http/http-tool
                          {:name        "weather"
                           :description "Current weather for a city."
                           :url         (str base "/weather")
                           :method      :get
                           :input-schema {:type "object"
                                          :properties {:city {:type "string"}}
                                          :required ["city"]}})]})]
        (println "tools:" (pr-str (sort (toolnexus/tool-names tk))))
        (println "word_count:" (:output (toolnexus/execute tk "word_count"
                                                           {:text "one two three"})))
        (println "weather:   " (:output (toolnexus/execute tk "weather" {:city "Chennai"})))
        ;; Both tools emit provider schema in the same shape — that is the point.
        (println "openai schema names:"
                 (pr-str (sort (map #(get-in % [:function :name]) (toolnexus/to-openai tk)))))
        (println "OK"))
      (finally (server/stop! srv)))))
