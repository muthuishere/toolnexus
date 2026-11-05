;; Example 10 — GitHub's hosted MCP server (speaks 2026-07-28) with OAuth.
;;
;; GitHub supports neither DCR nor CIMD, so you register an OAuth App once
;; (github.com/settings/developers -> New OAuth App, callback URL http://127.0.0.1/callback,
;; enable "expiring user tokens" if you want refresh) and pass its client id + secret.
;;
;;   sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ./run.sh example 10-github
;;
;; Credentials are read from the environment and never printed; neither is any token.
;; BROWSER=chrome-agent opens the consent page in the chrome-agent profile (used by the test);
;; otherwise the system browser opens.
(ns example.github
  (:require [mcp2026.auth :as auth]
            [mcp2026.core :as mcp]))

(defn main []
  (let [id (System/getenv "GITHUB_MCP_CLIENT_ID")
        secret (System/getenv "GITHUB_MCP_CLIENT_SECRET")]
    (if (or (empty? id) (empty? secret))
      (println "skipped: run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ...")

      (let [login (if (= "chrome-agent" (System/getenv "BROWSER"))
                    {:open (fn [u] (-> (ProcessBuilder. ["chrome-agent" "goto" u]) (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD) (.start) (.waitFor)))}
                    {}) ; system browser + http://127.0.0.1:<random>/callback
            c (mcp/client "https://api.githubcopilot.com/mcp/"
                          {:auth (auth/oauth {:client-id id
                                              :client-secret secret ; GitHub OAuth Apps are confidential (client_secret_basic)
                                              :authorizer (auth/loopback-authorizer login)})})]

        (println "tools listed:" (boolean (seq (mcp/tools c))))

        (let [res (mcp/call-tool c "get_me" {})] ; read-only: who am I
          (println "get_me:" (get res "resultType") "| isError:" (true? (get res "isError")))))))) ; content withheld on purpose

(main)
(shutdown-agents)
(System/exit 0)
