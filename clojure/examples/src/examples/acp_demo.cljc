;; ACP (Agent Client Protocol) as the model behind the unified client — issue
;; #96, ADR 0025, openspec/changes/add-acp-model-source. A local coding-agent
;; CLI (`devin acp` or `opencode acp`) is spawned ONCE by `toolnexus.acp/connect`
;; and every turn below reuses that same warm session, instead of paying the
;; CLI's process-startup cost again per turn.
;;
;; HONESTY CHECK, read before you time anything: whatever speedup turn 2/3
;; show over turn 1 here is the CLI's own process-startup cost amortised
;; across turns of ONE warm session — it is NOT a protocol-level speedup, and
;; ACP itself adds framing overhead, not less. This example needs the agent
;; CLI installed and already authenticated on this machine (real `devin` or
;; `opencode` credentials), so it is NOT hermetic — it is deliberately left
;; out of clj/run.sh and cljgo/run.sh, and CI does not run it. See
;; clojure/src/toolnexus/acp_test.cljc for the hermetic, fake-server-backed
;; coverage of `toolnexus.acp` itself.
;;
;; Agent CLI is selectable, not hardcoded:
;;
;;   clojure -M -m examples.acp-demo                              # default: devin acp
;;   ACP_AGENT_CMD=opencode ACP_AGENT_ARGS=acp clojure -M -m examples.acp-demo  # or: opencode acp
;;
;; cljgo: `cljgo run src/run_acp_demo.cljc` (same env vars).
(ns examples.acp-demo
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.time :as ktime]
            [toolnexus.core :as toolnexus]
            [toolnexus.acp :as acp]
            [toolnexus.client :as client]
            [toolnexus.native :as native]))

(defn- resolve-command
  "[cmd & args] the ACP agent is spawned with — ACP_AGENT_CMD/ACP_AGENT_ARGS
  env vars, defaulting to `devin acp`. `opencode acp` works exactly the same
  way — swap the command, nothing else in this example changes."
  []
  (let [cmd  (let [v (env/get-env "ACP_AGENT_CMD")] (if (str/blank? v) "devin" v))
        args (let [v (env/get-env "ACP_AGENT_ARGS")] (if (str/blank? v) "acp" v))]
    (into [cmd] (remove str/blank? (str/split args #"\s+")))))

(def ^:private clock-tool
  ;; A trivial native tool — proves the tool-calling loop is unchanged: the
  ;; ACP agent calls this exactly like it would call any MCP/HTTP/native tool.
  (native/native-tool
   {:name         "clock"
    :description  "Return the current time, in milliseconds since the epoch."
    :input-schema {:type "object" :properties {}}
    :run          (fn [_args] (str (ktime/now-ms) "ms since epoch"))}))

(defn -main [& _]
  (let [command (resolve-command)]
    (println "ACP agent command:" (str/join " " command))
    (let [acp-client (try
                        (acp/connect command)
                        (catch Throwable e
                          (println "(could not spawn ACP agent" (pr-str command)
                                   "— is it installed and on PATH?" (ex-message e) ")")
                          nil))]
      (if (nil? acp-client)
        (println "OK")
        (try
          (let [tk (toolnexus/build {:builtins false :tools [clock-tool]})
                llm (client/create-in-process-client
                     {:model "acp-agent" :generate (acp/generate acp-client)})]
            (doseq [[i q] (map-indexed vector
                                       ["What time is it right now? Use the clock tool."
                                        "Thanks. Now just say 'ready' — no tool needed."])]
              (let [start (ktime/now-ms)
                    r     (client/ask llm q {:toolkit tk :id "acp-demo"})
                    elapsed-ms (- (ktime/now-ms) start)]
                (println (str "turn " (inc i) " (" elapsed-ms "ms), session=" (:session-id acp-client)
                              ": " (:text r)))))
            (toolnexus/shutdown! tk)
            (println "\nOK ACP-backed client completed its turns on one warm session")
            (println "OK"))
          (finally
            (acp/close acp-client)))))))
