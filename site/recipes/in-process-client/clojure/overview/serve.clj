;; deps.edn: {:deps {net.clojars.muthuishere/toolnexus {:mvn/version "0.29.0"}}}
;; run:      clojure -M serve.clj
(ns serve
  (:require [koine.http :as http] ; koine ships with toolnexus; no extra dependency
            [koine.json :as json]
            [toolnexus.core :as tn]
            [toolnexus.native :as native]
            [toolnexus.serve :as serve]))

(def add
  (native/native-tool
   {:name "add" :description "Add two numbers."
    :input-schema {:type "object" :properties {:a {:type "number"} :b {:type "number"}} :required ["a" "b"]}
    :run (fn [{:keys [a b]}] (str (+ a b)))}))

;; Any toolkit (MCP servers, skills, native, HTTP, builtins) can be served to a local agent.
(def toolkit (tn/build {:builtins false :tools [add]}))
(def server (serve/as-mcp-server toolkit)) ; (as-mcp-server tk {:tools [...]}) narrows what is exposed
(println "MCP endpoint:" (:url server))    ; http://127.0.0.1:<port>/mcp, POST only

;; Every request needs the bearer token; this is the entry an ACP agent takes in session/new.
(def entry {:type "http" :name "toolnexus" :url (:url server)
            :headers [{:name "Authorization" :value (str "Bearer " (:token server))}]})
(def res (http/request {:method :post :url (:url entry)
                        :headers {"Content-Type" "application/json"
                                  "Accept" "application/json, text/event-stream"
                                  "Authorization" (-> entry :headers first :value)}
                        :body (json/write-str {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}})}))
(println "tools/list:" (:status res))
((:close server))
(shutdown-agents)
