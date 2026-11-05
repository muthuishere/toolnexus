;; SPEC §7C.2 — the DEV authorization server.
;;
;; *** DEVELOPMENT AND TESTS ONLY. NEVER USE IN PRODUCTION. ***
;;
;; An in-process OAuth 2.1 authorization server that auto-approves every request
;; as "dev-user" (no login, no consent): RFC 8414 metadata, JWKS, authorization
;; code + PKCE S256, resource indicators (RFC 8707), `iss` in the response
;; (RFC 9207), refresh-token rotation, pre-registered clients and Client ID
;; Metadata Documents. No dynamic client registration. Plain HTTP, so a
;; toolnexus client needs auth.allowHttp against it. Its RSA signing is not
;; constant-time (toolnexus.mcp-crypto) — one more reason it is dev-only.
;;
;;   (def as (da/start-mcp-dev-auth-server {:clients {"my-app" ["http://127.0.0.1/callback"]}}))
;;   (def auth (sa/mcp-server-auth {:issuer (:issuer as) :scopes {"*" ["mcp"]}}))
;;   ((:close as))
;;
;; Mirrors golang/mcp_devauth.go, with ONE host-driven difference on
;; /authorize: koine.server hands a handler the request PATH and never its query
;; string (on both hosts), so the query OAuth puts on GET /authorize cannot be
;; read. GET /authorize therefore answers a tiny page whose script POSTs its own
;; `location.search` back to /authorize, and the POST (form body =
;; the authorization request) is the real endpoint: it answers `302` to the
;; redirect URI, or — when the POST sends `Accept: application/json` — `200
;; {"redirect_to": <that URL>}` (the page uses that, and so can a test client
;; whose HTTP stack follows redirects, as cljgo's does). Every rule (client and
;; redirect checks, 400 never-a-redirect, invalid_target, PKCE) is the same.
;;
;; HOST NOTE: koine.server cannot bind a specific interface on cljgo (its
;; `:host` option is JVM-only), so there the dev server listens on every
;; interface of the machine — keep it to a trusted network, as dev-only says.
(ns toolnexus.mcp-dev-auth
  (:require [clojure.string :as str]
            [koine.http :as http]
            [koine.json :as json]
            [koine.server :as server]
            [koine.time :as ktime]
            [toolnexus.mcp-crypto :as crypto]))

(defn mint
  "Sign an access token with the dev server's key (tests: forge expired or
  wrong-audience tokens). `claims` is a JSON-able map."
  [as claims]
  (let [in (str (crypto/b64url (json/write-str {"alg" "RS256" "typ" "JWT" "kid" "dev"}))
                "." (crypto/b64url (json/write-str claims)))]
    (str in "." (crypto/b64url (crypto/rsa-sign (:key as) in)))))

(defn- js [status v]
  {:status status :headers {"Content-Type" "application/json"} :body (str (json/write-str v) "\n")})

(defn- loopback-ip? [h] (boolean (re-matches #"127\.\d+\.\d+\.\d+|::1|\[::1\]" (str h))))

(defn- parse-url [u]
  (when-let [[_ scheme host path] (re-matches #"([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/?#]*)([^?#]*)(?:\?[^#]*)?(?:#.*)?" (str u))]
    {:scheme (str/lower-case scheme)
     :hostname (str/replace host #":\d+$" "")
     :path path}))

(defn- get-json [as url]
  (let [res ((:http-request as) {:method :get :url url :headers {"accept" "application/json"} :timeout-ms 10000})]
    (when (= 200 (:status res))
      (json/read-str (:body res) {:key-fn str}))))

(defn- redirect-allowed? [as cid redirect]
  (let [opts (:opts as)
        cimd? (or (str/starts-with? (str cid) "https://") (str/starts-with? (str cid) "http://"))
        allowed (if cimd? ; a Client ID Metadata Document
                  (when-not (:no-cimd opts)
                    (let [doc (try (get-json as cid) (catch Throwable _ nil))]
                      (when (and (map? doc) (= cid (get doc "client_id"))
                                 (string? (get doc "client_name")) (seq (get doc "client_name")))
                        (get doc "redirect_uris"))))
                  (or (get (:clients opts) cid) (get (:clients opts) (keyword (str cid)))))
        ru (parse-url redirect)]
    (boolean
      (and ru
           (some (fn [a]
                   (or (= a redirect)
                       ;; a loopback redirect matches on any port (RFC 8252 §7.3)
                       (let [au (parse-url a)]
                         (and au (loopback-ip? (:hostname ru)) (= (:hostname au) (:hostname ru))
                              (= (:scheme au) (:scheme ru)) (= (:path au) (:path ru))))))
                 allowed)))))

(def ^:private authorize-page
  (str "<!doctype html><meta charset=utf-8><title>Dev authorization</title>"
       "<p id=m>Authorizing (dev server, auto-approve)…</p><script>"
       "fetch(location.pathname,{method:'POST',headers:{'Accept':'application/json',"
       "'Content-Type':'application/x-www-form-urlencoded'},body:location.search.replace(/^\\?/,'')})"
       ".then(function(r){return r.ok?r.json():r.text().then(function(t){throw new Error(t)})})"
       ".then(function(j){location.replace(j.redirect_to)})"
       ".catch(function(e){document.getElementById('m').textContent=String(e.message||e)})</script>"))

(defn- authorize [as req]
  (if (not= :post (:method req))
    {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body authorize-page}
    (let [q        (crypto/parse-query (:body req))
          g        (fn [k] (get q k ""))
          _        (swap! (:auths as) inc)
          cid      (g "client_id")
          redirect (g "redirect_uri")
          json?    (str/includes? (str (get-in req [:headers "accept"])) "application/json")
          back     (fn [v]
                     (let [url (str redirect (if (str/includes? redirect "?") "&" "?")
                                    (crypto/form-encode (assoc v "state" (g "state") "iss" (:issuer as))))]
                       (if json?
                         (js 200 {"redirect_to" url})
                         {:status 302 :headers {"Location" url} :body ""})))]
      (cond
        ;; never redirect to an unverified URI
        (not (redirect-allowed? as cid redirect))
        {:status 400 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "invalid_client or redirect_uri\n"}

        (or (not= "code" (g "response_type")) (not= "S256" (g "code_challenge_method")) (= "" (g "code_challenge")))
        (back {"error" "invalid_request"})

        (= "" (g "resource"))
        (back {"error" "invalid_target"})

        :else
        (let [code (crypto/rand-token 32)]
          (swap! (:codes as) assoc code {:client-id cid :redirect redirect :challenge (g "code_challenge")
                                         :resource (g "resource") :scope (g "scope")})
          (back {"code" code}))))))

(defn- token [as req]
  (locking (:lock as)
    (let [opts    (:opts as)
          f       (crypto/parse-query (:body req))
          g       (fn [k] (get f k ""))
          bad     (fn [e] (js 400 {"error" e}))
          secrets (or (:secrets opts) {})
          secret  (fn [id] (str (or (get secrets id) (get secrets (keyword (str id))) "")))
          authz   (str (or (get-in req [:headers "authorization"]) ""))
          basic   (when (str/starts-with? authz "Basic ")
                    (let [[i s] (str/split (or (crypto/b64-std-decode (subs authz 6)) "") #":" 2)]
                      [(try (crypto/url-decode (or i "") true) (catch Throwable _ ""))
                       (try (crypto/url-decode (or s "") true) (catch Throwable _ ""))]))]
      (cond
        (and basic (let [[id sec] basic]
                     (or (and (seq (g "client_id")) (not= (g "client_id") id))
                         (= "" (secret id)) (not= (secret id) sec))))
        (js 401 {"error" "invalid_client"})

        (and (not basic) (seq (secret (g "client_id"))) (not= (g "client_secret") (secret (g "client_id"))))
        (js 401 {"error" "invalid_client"})

        :else
        (let [cid (if basic (first basic) (g "client_id"))
              grant (case (g "grant_type")
                      "authorization_code"
                      (let [c (get @(:codes as) (g "code"))]
                        (swap! (:codes as) dissoc (g "code")) ; single use
                        (cond (nil? c) "invalid_grant"
                              (or (not= (crypto/b64url (crypto/sha256 (g "code_verifier"))) (:challenge c))
                                  (not= cid (:client-id c)) (not= (g "redirect_uri") (:redirect c))
                                  (not= (g "resource") (:resource c)))
                              "invalid_grant"
                              :else c))

                      "refresh_token"
                      (let [c (get @(:rts as) (g "refresh_token"))]
                        (if (or (nil? c) (not= cid (:client-id c)))
                          "invalid_grant"
                          (do (swap! (:rts as) dissoc (g "refresh_token")) ; rotation
                              (if (and (seq (g "resource"))
                                       (or (not= (g "resource") (:resource c)) (:reject-refresh-resource opts)))
                                "invalid_target"
                                c))))

                      "unsupported_grant_type")]
          (if (string? grant)
            (bad grant)
            (let [ttl (let [t (:access-ttl-s opts)] (if (and t (pos? t)) t 3600))
                  rt  (crypto/rand-token 48)
                  now (quot (ktime/now-ms) 1000)]
              (swap! (:rts as) assoc rt grant)
              (js 200 {"access_token" (mint as {"iss" (:issuer as) "sub" "dev-user" "aud" (:resource grant)
                                                "scope" (:scope grant) "client_id" (:client-id grant)
                                                "jti" (crypto/rand-token 16) "exp" (+ now ttl) "iat" now})
                       "token_type" "Bearer" "expires_in" ttl "refresh_token" rt "scope" (:scope grant)}))))))))

(defn- handle [as req]
  (let [iss (:issuer as)]
    (case (:path req)
      "/.well-known/oauth-authorization-server"
      (js 200 {"issuer" iss "authorization_endpoint" (str iss "/authorize") "token_endpoint" (str iss "/token")
               "jwks_uri" (str iss "/jwks") "response_types_supported" ["code"]
               "grant_types_supported" ["authorization_code" "refresh_token"]
               "code_challenge_methods_supported" ["S256"]
               "token_endpoint_auth_methods_supported" ["none" "client_secret_basic" "client_secret_post"]
               "authorization_response_iss_parameter_supported" true
               "client_id_metadata_document_supported" (not (:no-cimd (:opts as)))})
      "/jwks"      (js 200 {"keys" [(merge {"kid" "dev" "alg" "RS256" "use" "sig"} (crypto/rsa-jwk (:key as)))]})
      "/authorize" (authorize as req)
      "/token"     (token as req)
      {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "404 page not found\n"})))

(defn start-mcp-dev-auth-server
  "DEVELOPMENT AND TESTS ONLY — NEVER IN PRODUCTION. Start the dev OAuth 2.1
  authorization server (SPEC §7C.2) on 127.0.0.1:<random>, auto-approving every
  request as \"dev-user\". opts (all optional):

    :clients  {client-id [redirect-uri …]}  pre-registered (loopback redirects
              match on any port)
    :secrets  {client-id secret}            confidential clients (basic or post)
    :access-ttl-s             access-token lifetime, seconds (default 3600)
    :no-cimd                  stop accepting / advertising Client ID Metadata Documents
    :reject-refresh-resource  invalid_target when a refresh carries `resource`
    :http-request             (fn [koine-request] koine-response) for CIMD fetches

  Returns {:issuer \"http://127.0.0.1:<port>\"
           :mint (fn [claims] jwt)   sign any claims with the server key
           :authorizations (fn [] n) how many authorization requests were served
           :close (fn [])}"
  ([] (start-mcp-dev-auth-server {}))
  ([opts]
   (let [as   {:opts   opts
               :key    (crypto/rsa-generate 2048)
               :lock   (atom nil)
               :codes  (atom {})
               :rts    (atom {})
               :auths  (atom 0)
               :http-request (or (:http-request opts) http/request)}
         self (atom nil)
         srv  (server/serve (fn [req] (handle @self req)) {:port 0 :host "127.0.0.1"})
         as   (assoc as :issuer (str "http://127.0.0.1:" (server/port srv)))]
     (reset! self as)
     (assoc as
            :mint           (fn [claims] (mint as claims))
            :authorizations (fn [] @(:auths as))
            :close          (fn [] (server/stop! srv))))))
