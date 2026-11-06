;; Example 09 — a real authorization server: Keycloak 26.8.1 protecting an MCP server.
;;
;; Start Keycloak first (realm "toolnexus", user alice / alice-test-pw, client "toolnexus-mcp"):
;;
;;   cd ../golang/keycloak && docker compose up -d && cd -
;;   KEYCLOAK=1 ./run.sh example 09-keycloak                  # logs in through your system browser
;;   KEYCLOAK=1 HEADLESS_LOGIN=1 ./run.sh example 09-keycloak # scripted login (used by the test)
;;
;; The MCP server listens on 127.0.0.1:7199 because the realm's audience mapper puts
;; aud = http://127.0.0.1:7199/mcp into tokens; the server's :audience must equal it.
(ns example.keycloak
  (:require [clojure.string :as str]
            [mcp2026.auth :as auth]
            [mcp2026.core :as mcp]))

(load-file (str (.getParent (java.io.File. ^String *file*)) "/headless.cljc"))

(def issuer "http://localhost:8180/realms/toolnexus") ; exactly the `iss` Keycloak puts in tokens
(def resource "http://127.0.0.1:7199/mcp")

(defn main []
  (if (not= "1" (System/getenv "KEYCLOAK"))
    (println "skipped: set KEYCLOAK=1 with keycloak/docker-compose.yml running")

    ;; ---- server: Keycloak is the issuer; tokens are RS256 JWTs checked against its JWKS ----
    (let [srv (-> (mcp/fixture-server)
                  (mcp/tool "whoami" "Who is calling"
                            (fn [{p :principal} _]
                              ;; :subject is Keycloak's user id (a UUID); azp is the client the user logged in through.
                              (str "client=" (get-in p [:claims "azp"]) " scopes=" (str/join " " (:scopes p))
                                   " subject-set=" (boolean (seq (:subject p))))))
                  (assoc :auth {:issuer issuer
                                :audience resource ; = the audience mapper's included.custom.audience
                                :scopes {"*" ["mcp:tools"] "greet" ["mcp:greet"]}}))
          ts (mcp/serve! srv {:port 7199})

          ;; ---- client: pre-registered public client, PKCE, loopback redirect http://127.0.0.1/* ----
          open (if (= "1" (System/getenv "HEADLESS_LOGIN"))
                 {:open (example.keycloak.headless/headless-login "alice" "alice-test-pw")} ; throwaway local test user
                 {}) ; default :open = system browser
          c (mcp/client resource {:auth (auth/oauth {:client-ids {issuer "toolnexus-mcp"} ; client id bound to THIS issuer
                                                     :allow-http true ; dev Keycloak is plain HTTP; never in production
                                                     :authorizer (auth/loopback-authorizer open)})})]

      (println "whoami:" (mcp/text (mcp/call-tool c "whoami" nil)))

      ;; greet needs mcp:greet: 403 insufficient_scope -> step-up (Keycloak SSO cookie, no 2nd form).
      (println "greet:" (mcp/text (mcp/call-tool c "greet" {} (fn [_ _] {"action" "accept" "content" {"name" "Alice"}}))))
      ((:stop ts)))))

(main)
(shutdown-agents)
(System/exit 0)
