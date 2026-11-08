;; deps.edn: {:deps {net.clojars.muthuishere/toolnexus {:mvn/version "0.29.0"}}}
;; run:      clojure -M main.clj npx -y @agentclientprotocol/claude-agent-acp@0.100.0
(ns main
  (:require [clojure.string :as str]
            [koine.json :as json] ; ships with toolnexus
            [toolnexus.acp :as acp]
            [toolnexus.client :as client]
            [toolnexus.core :as tn]
            [toolnexus.native :as native]
            [toolnexus.serve :as serve]))

(def add
  (native/native-tool
   {:name "add" :description "Add two numbers."
    :input-schema {:type "object" :properties {:a {:type "number"} :b {:type "number"}} :required ["a" "b"]}
    :run (fn [{:keys [a b]}] (str (+ a b)))}))
(def toolkit (tn/build {:builtins false :tools [add]}))
(def server (serve/as-mcp-server toolkit))

;; Permission policy: allow tool calls whose title names one of your tools, reject the rest.
;; Return an optionId to pick it; return nil to answer with the first reject option.
(defn allow-only [names]
  (fn [params]
    (let [title (str (get-in params [:toolCall :title]))
          ok?   (some #(str/includes? title %) names)]
      (some #(when (str/starts-with? (str (:kind %)) (if ok? "allow" "reject")) (:optionId %))
            (:options params)))))

(def command (vec *command-line-args*))
(def acp-agent
  (acp/connect command
               (cond-> {:mcp-servers [{:type "http" :name "toolnexus" :url (:url server)
                                       :headers [{:name "Authorization" :value (str "Bearer " (:token server))}]}]
                        ;; Agent-specific knobs are opaque data: this one isolates Claude Code.
                        :session-meta {:claudeCode {:options {:settingSources [] :strictMcpConfig true
                                                              :plugins [] :skills [] :tools []}}}
                        :on-permission (allow-only (tn/tool-names toolkit))
                        :on-update (fn [u] (when (= "usage_update" (get-in u [:update :sessionUpdate]))
                                             (binding [*out* *err*] (println "[usage]" (json/write-str (:update u))))))
                        :permission-timeout 300000}
                 (System/getenv "ACP_MODEL") (assoc :model (System/getenv "ACP_MODEL"))))) ; e.g. "haiku"; never validated

(def c (client/create-in-process-client {:model (first command) :generate (acp/generate acp-agent)}))
(try
  (println (:text (client/run c "Use the add tool to add 17 and 25. Reply with just the sum."
                              {:toolkit (tn/build {:builtins false})})))
  (finally
    (acp/close acp-agent)
    ((:close server))
    (shutdown-agents)))
