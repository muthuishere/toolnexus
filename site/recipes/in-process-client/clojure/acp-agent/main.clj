;; deps.edn: {:deps {net.clojars.muthuishere/toolnexus {:mvn/version "0.27.0"}}}
;; run:      clojure -M main.clj npx -y @agentclientprotocol/claude-agent-acp@0.100.0
(ns main
  (:require [toolnexus.acp :as acp]
            [toolnexus.client :as client]
            [toolnexus.core :as tn]
            [toolnexus.native :as native]
            [toolnexus.serve :as serve]))

(def add
  (native/native-tool
   {:name "add" :description "Add two numbers."
    :input-schema {:type "object" :properties {:a {:type "number"} :b {:type "number"}} :required ["a" "b"]}
    :run (fn [{:keys [a b]}] (println (str "\n[tool] add(" a ", " b ")")) (str (+ a b)))}))

;; 1. Serve YOUR toolkit on loopback, behind a fresh bearer token.
(def toolkit (tn/build {:builtins false :tools [add]}))
(def server (serve/as-mcp-server toolkit))

;; 2. Start the agent; :mcp-servers is sent to session/new exactly as written.
(def command (vec *command-line-args*))
(def acp-agent
  (acp/connect command
               {:mcp-servers [{:type "http" :name "toolnexus" :url (:url server)
                               :headers [{:name "Authorization" :value (str "Bearer " (:token server))}]}]
                :permission-timeout 300000})) ; one turn of a real agent can take minutes

;; 3. The agent runs the tools itself (over MCP), so the client's own toolkit is empty.
(def c (client/create-in-process-client {:model (first command) :generate-stream (acp/generate-stream acp-agent)}))
(try
  (let [result (client/run c "Use the add tool to add 17 and 25. Reply with just the sum."
                           {:toolkit (tn/build {:builtins false})
                            :on-event (fn [ev]
                                        (cond
                                          (= "text" (:type ev)) (do (print (:delta ev)) (flush)) ; live text
                                          (and (= "tool_call" (:type ev)) (= "acp" (:via ev)))
                                          (println (str "\n[agent tool] " (:name ev)))))})]
    (println "\nfinal:" (:text result)))
  (finally
    (acp/close acp-agent) ; ends the agent's whole process tree
    ((:close server))
    (shutdown-agents)))
