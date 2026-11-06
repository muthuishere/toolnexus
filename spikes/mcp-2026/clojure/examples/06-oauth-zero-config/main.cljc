;; Example 06 — call an OAuth-protected MCP server with zero auth configuration (auto mode).
;;
;; Run: ./run.sh example 06-oauth-zero-config   (hermetic: everything runs on 127.0.0.1)
;;
;; The client learns everything from the server's 401: Protected Resource Metadata -> the
;; authorization server's metadata -> PKCE S256 login with `resource` -> token -> retry.
;; You only say WHO the client is: a pre-registered :client-id, or a CIMD :client-metadata-url.
;; There is no Dynamic Client Registration.
(ns example.oauth-zero-config
  (:require [mcp2026.auth :as auth]
            [mcp2026.core :as mcp]
            [mcp2026.devauth :as devauth]))

(defn main []
  ;; ---- demo harness: an authorization server + a protected MCP server, both in-process ----
  (let [as (devauth/start) ; DEV/TEST ONLY: auto-approves everyone as "dev-user"
        _ (devauth/register-client! as "my-app" ["http://127.0.0.1/callback"]) ; the AS admin registered "my-app"
        srv (assoc (mcp/fixture-server)
                   :auth {:issuer (:issuer as)
                          :scopes {"*" ["mcp:read"] "greet" ["mcp:greet"]}}) ; greet needs more
        ts (mcp/serve! srv)
        url (str (:url ts) "/mcp")]
    ;; ---- end harness ----

    (let [store (auth/memory-store) ; implement auth/TokenStore to persist tokens (keyed by resource)

          ;; The user code. A real app leaves :authorizer out: (auth/loopback-authorizer) opens the system
          ;; browser and listens on http://127.0.0.1:<random>/callback. Here the dev AS "clicks" for us.
          c (mcp/client url {:auth (auth/oauth {:client-id "my-app"
                                                :store store
                                                :authorizer (auth/loopback-authorizer {:open #(devauth/approve as %)})})})]

      (println "echo:" (mcp/text (mcp/call-tool c "echo" {"text" "hello after login"})))

      ;; Step-up: greet needs scope mcp:greet. The server answers 403 insufficient_scope; the client
      ;; re-authorizes with the union of scopes and re-sends the call (the tool never ran twice).
      (println "greet:" (mcp/text (mcp/call-tool c "greet" {}
                                                 (fn [_ _] {"action" "accept" "content" {"name" "Ada"}}))))

      ;; A second client sharing the token store needs no new login.
      (let [c2 (mcp/client url {:auth (auth/oauth {:client-id "my-app" :store store
                                                   :authorizer (auth/loopback-authorizer {:open #(devauth/approve as %)})})})]
        (mcp/call-tool c2 "echo" {"text" "again"}))
      (println "browser logins:" (:authorizations (devauth/stats as))) ; 1 login + 1 step-up

      (let [tok (auth/load-token store url)]
        (println "token stored for this issuer:" (= (:issuer tok) (:issuer as))))) ; (str tok) is redacted

    ((:stop ts))
    (devauth/close as)))

(main)
(shutdown-agents)
(System/exit 0)
