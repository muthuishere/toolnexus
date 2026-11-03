;; Example 08 — serve MCP tools behind OAuth: one :issuer line, per-tool scopes, the caller's
;; identity in the handler, and a custom :verify hook for opaque tokens.
;;
;; Run: ./run.sh example 08-serve-protected   (hermetic: everything runs on 127.0.0.1)
;;
;; With :auth set the server: serves Protected Resource Metadata at
;; /.well-known/oauth-protected-resource/mcp, answers 401 + WWW-Authenticate without a valid
;; token, 403 insufficient_scope when a tool needs more, and verifies RS256 JWTs against the
;; issuer's JWKS (signature, iss, aud = this server's URL, exp).
(ns example.serve-protected
  (:require [clojure.string :as str]
            [mcp2026.auth :as auth]
            [mcp2026.core :as mcp]
            [mcp2026.devauth :as devauth]
            [mcp2026.wire :as w]))

(defn main []
  (let [as (devauth/start) ; DEV/TEST ONLY. Production: your Keycloak / Entra / Auth0 issuer.
        _ (devauth/register-client! as "my-app" ["http://127.0.0.1/callback"])

        ;; ---- (1) the server: :issuer one-liner + per-tool scope + principal ----
        srv (-> (mcp/server)
                (mcp/tool "whoami" "Who is calling"
                          (fn [{:keys [principal]} _] ; :subject :scopes :claims of the verified token
                            (str "hello " (:subject principal) " (" (str/join " " (:scopes principal)) ")")))
                (mcp/tool "delete_all" "Dangerous admin tool" (fn [_ _] "deleted"))
                (assoc :auth {:issuer (:issuer as) ; the whole required config
                              :scopes {"*" ["mcp:read"]            ; every request
                                       "delete_all" ["mcp:admin"]}})) ; additionally required for this tool
        ts (mcp/serve! srv {}) ; production: (mcp/serve! srv {:host "0.0.0.0" :port 8080})
        ;; ---- end server ----
        url (str (:url ts) "/mcp")]

    ;; No token: 401 with WWW-Authenticate pointing at the resource metadata.
    (let [resp (w/request {:method "POST" :url url
                           :headers {"Content-Type" "application/json" "MCP-Protocol-Version" "2026-07-28"
                                     "Mcp-Method" "tools/list"}
                           :body "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}"})]
      (println "no token:" (:status resp) "| WWW-Authenticate has resource_metadata:"
               (str/includes? (str (get-in resp [:headers "www-authenticate"])) "resource_metadata=")))

    ;; The metadata a client discovers (RFC 9728): which AS to use and which scopes exist.
    (let [prm (auth/get-json w/request (str (:url ts) "/.well-known/oauth-protected-resource/mcp"))]
      (println "metadata: issuer ok:" (= (first (get prm "authorization_servers")) (:issuer as))
               "| scopes:" (get prm "scopes_supported")))

    ;; A logged-in client (see example 06): whoami gets the principal; delete_all steps up.
    (let [c (mcp/client url {:auth (auth/oauth {:client-id "my-app"
                                                :authorizer (auth/loopback-authorizer {:open #(devauth/approve as %)})})})]
      (println "whoami:" (mcp/text (mcp/call-tool c "whoami" nil)))
      (println "delete_all after step-up:" (mcp/text (mcp/call-tool c "delete_all" nil)))
      (println "whoami:" (mcp/text (mcp/call-tool c "whoami" nil))))

    ;; ---- (2) custom :verify: opaque API keys / token introspection instead of JWT ----
    (let [keys {"svc-key-123" {:subject "billing-service" :scopes ["mcp:read"]}} ; e.g. a DB lookup or RFC 7662 call
          srv2 (-> (mcp/server)
                   (mcp/tool "whoami" "Who is calling" (fn [{:keys [principal]} _] (str "hello " (:subject principal))))
                   (assoc :auth {:issuer (:issuer as) ; still advertised in the resource metadata
                                 :scopes {"*" ["mcp:read"]}
                                 :verify (fn [token] (get keys token))})) ; nil (or a throw) -> 401 invalid_token
          ts2 (mcp/serve! srv2)
          ;; A service caller with a static key: wrap the request fn to add the header.
          bearer (fn [k] (fn [req] (w/request (assoc-in req [:headers "Authorization"] (str "Bearer " k)))))
          svc (mcp/client (str (:url ts2) "/mcp") {:http (bearer "svc-key-123")})]
      (println "verify hook:" (mcp/text (mcp/call-tool svc "whoami" nil)))

      (let [bad (mcp/client (str (:url ts2) "/mcp") {:http (bearer "wrong")})]
        (println "wrong key rejected:" (try (mcp/call-tool bad "whoami" nil) false (catch Exception _ true))))
      ((:stop ts2)))

    ((:stop ts))
    (devauth/close as)))

(main)
(shutdown-agents)
(System/exit 0)
