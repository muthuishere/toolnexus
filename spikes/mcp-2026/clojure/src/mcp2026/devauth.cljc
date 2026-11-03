(ns mcp2026.devauth
  "DEV/TEST ONLY. NEVER USE IN PRODUCTION.
  A one-call OAuth 2.1 authorization server that auto-approves every request as \"dev-user\"
  (no login, no consent screen), so local dev and tests exercise the real MCP auth flow:
  RFC 8414 metadata, JWKS, auth code + PKCE S256, resource (RFC 8707), iss in the response
  (RFC 9207), refresh-token rotation, pre-registered + CIMD clients.

    (def as (start))   ; https://127.0.0.1:<port>/dev, trusted by this process's clients
    (assoc srv :auth {:issuer (:issuer as) :scopes {...}})"
  (:require [clojure.string :as str]
            [mcp2026.auth :as auth]
            [mcp2026.wire :as w]))

(defn- loopback-ip? [h] (boolean (re-matches #"127\.\d+\.\d+\.\d+|::1|\[::1\]" (str h))))

(defn- redirect-allowed? [as cid redirect]
  (let [cfg @(:cfg as)
        allowed (if (str/starts-with? (str cid) "https://") ; Client ID Metadata Document
                  (let [doc (try (auth/get-json w/request cid) (catch Exception _ nil))]
                    (when (and (map? doc) (= cid (get doc "client_id")) (not (str/blank? (get doc "client_name"))))
                      (get doc "redirect_uris")))
                  (get-in cfg [:clients cid]))
        ru (try (w/parse-url redirect) (catch Exception _ nil))]
    (boolean
     (and ru
          (some (fn [a]
                  (or (= a redirect)
                      ;; loopback IPs: the port is ignored (RFC 8252 §7.3)
                      (let [au (try (w/parse-url a) (catch Exception _ nil))]
                        (and au (loopback-ip? (:hostname ru)) (= (:hostname au) (:hostname ru))
                             (= (:scheme au) (:scheme ru)) (= (:path au) (:path ru))))))
                allowed)))))

(defn mint
  "Sign an access token directly (tests: forge wrong audience / expired tokens)."
  [as claims]
  (let [in (str (w/b64url (w/write-json {"alg" "RS256" "typ" "JWT" "kid" "dev"})) "." (w/b64url (w/write-json claims)))]
    (str in "." (w/b64url (w/rs256-sign (.getPrivate (:key as)) in)))))

(defn- redirect [base q] {:status 302 :headers {"Location" (str base "?" (w/form-encode q))}})

(defn- authorize [as req]
  (let [q (w/parse-query (:query req))
        g #(w/q1 q %)
        _ (swap! (:stats as) (fn [s] (-> s (update :authorizations inc) (assoc :last-authorize-query q))))
        cid (g "client_id") ru (g "redirect_uri")]
    (cond
      (not (redirect-allowed? as cid ru)) {:status 400 :body "invalid_client or redirect_uri"} ; never redirect to an unverified URI
      (or (not= "code" (g "response_type")) (not= "S256" (g "code_challenge_method")) (str/blank? (g "code_challenge")))
      (redirect ru {"error" "invalid_request" "state" (g "state") "iss" (:issuer as)})
      (str/blank? (g "resource")) (redirect ru {"error" "invalid_target" "state" (g "state") "iss" (:issuer as)})
      :else
      (let [code (w/rand-str 16)]
        (swap! (:codes as) assoc code {:client-id cid :redirect ru :challenge (g "code_challenge")
                                       :resource (g "resource") :scope (g "scope")})
        (redirect ru {"code" code "state" (g "state") "iss" (or (not-empty (:iss-override @(:cfg as))) (:issuer as))})))))

(defn- token [as req js]
  (locking as
    (let [cfg @(:cfg as)
          form (w/parse-query (:body req))
          g #(w/q1 form %)
          _ (swap! (:stats as) assoc :last-token-form form)
          bad (fn [e] (js 400 {"error" e}))
          authz (str (get-in req [:headers "authorization"]))
          [bid bsec] (when (str/starts-with? authz "Basic ")
                       (let [[i s] (str/split (w/bytes->str (w/b64-decode (subs authz 6))) #":" 2)]
                         [(w/url-decode i) (w/url-decode (or s ""))]))
          secrets (:secrets cfg)
          cid (or bid (g "client_id"))]
      (cond
        (and bid (or (and (seq (g "client_id")) (not= (g "client_id") bid))
                     (str/blank? (get secrets bid)) (not= (get secrets bid) bsec)))
        (js 401 {"error" "invalid_client"})
        (and (not bid) (seq (get secrets cid)) (not= (g "client_secret") (get secrets cid)))
        (js 401 {"error" "invalid_client"})
        :else
        (let [c (case (g "grant_type")
                  "authorization_code"
                  (let [c (get @(:codes as) (g "code"))]
                    (swap! (:codes as) dissoc (g "code")) ; single use
                    (cond (nil? c) "invalid_grant"
                          (not= (w/b64url (w/sha256 (g "code_verifier"))) (:challenge c)) "invalid_grant" ; PKCE
                          (or (not= cid (:client-id c)) (not= (g "redirect_uri") (:redirect c))
                              (not= (g "resource") (:resource c))) "invalid_grant"
                          :else c))
                  "refresh_token"
                  (let [c (get @(:rts as) (g "refresh_token"))]
                    (cond (or (nil? c) (not= cid (:client-id c))) "invalid_grant"
                          :else (do (swap! (:rts as) dissoc (g "refresh_token")) ; rotation
                                    (if (and (contains? form "resource") (seq (g "resource"))
                                             (or (not= (g "resource") (:resource c)) (:reject-refresh-resource cfg)))
                                      "invalid_target"
                                      (do (swap! (:stats as) update :refreshes inc) c)))))
                  "unsupported_grant_type")]
          (if (string? c)
            (bad c)
            (let [ttl (or (:access-ttl-s cfg) 3600)
                  rt (w/rand-str 24)
                  now (quot (w/now-ms) 1000)]
              (swap! (:rts as) assoc rt c)
              (js 200 {"access_token" (mint as {"iss" (:issuer as) "sub" "dev-user" "aud" (:resource c) "scope" (:scope c)
                                                "client_id" (:client-id c) "jti" (w/rand-str 8) "exp" (+ now ttl) "iat" now})
                       "token_type" "Bearer" "expires_in" ttl "refresh_token" rt "scope" (:scope c)}))))))))

(defn- handle [as req]
  (let [js (fn [status v] {:status status :headers {"Content-Type" "application/json"} :body (w/write-json v)})
        iss (:issuer as)
        cfg @(:cfg as)]
    (case (:path req)
      "/.well-known/oauth-authorization-server/dev"
      (js 200 {"issuer" iss "authorization_endpoint" (str iss "/authorize") "token_endpoint" (str iss "/token")
               "jwks_uri" (str iss "/jwks") "response_types_supported" ["code"]
               "grant_types_supported" ["authorization_code" "refresh_token"]
               "code_challenge_methods_supported" ["S256"]
               "token_endpoint_auth_methods_supported" ["none" "client_secret_basic" "client_secret_post"]
               "authorization_response_iss_parameter_supported" true
               "client_id_metadata_document_supported" (not (:no-cimd cfg))})
      "/dev/jwks" (js 200 {"keys" [(merge {"kty" "RSA" "kid" "dev" "alg" "RS256" "use" "sig"} (w/rsa-jwk (.getPublic (:key as))))]})
      "/dev/authorize" (authorize as req)
      "/dev/token" (token as req js)
      {:status 404 :body "404 page not found"})))

(defn start
  "Start a TLS dev AS on 127.0.0.1; issuer https://127.0.0.1:<port>/dev. The returned map has
  :issuer, :cfg (atom: :clients {id [redirects]} :secrets {id secret} :access-ttl-s, and test knobs
  :iss-override :no-cimd :reject-refresh-resource) and :stats (atom: :authorizations :refreshes
  :last-authorize-query :last-token-form)."
  []
  (let [as {:key (w/rsa-keypair) :cfg (atom {:clients {} :secrets {}})
            :stats (atom {:authorizations 0 :refreshes 0}) :codes (atom {}) :rts (atom {})}
        self (promise)
        srv (w/serve! (fn [req] (handle @self req)) {:tls? true})
        as (assoc as :issuer (str (:url srv) "/dev") :stop (:stop srv))]
    (deliver self as)
    as))

(defn close [as] ((:stop as)))

(defn register-client!
  "Pre-register client id with its redirect URIs (loopback ports are ignored)."
  [as id redirects]
  (swap! (:cfg as) assoc-in [:clients id] (vec redirects)))

(defn stats [as] @(:stats as))

(defn approve
  "A headless 'browser' for dev/tests: visit an authorization URL and follow the redirect back to
  the callback. Use as (auth/loopback-authorizer {:open #(devauth/approve as %)})."
  [_as url]
  (w/browser {:method "GET" :url url})
  nil)

(defn capture-callback
  "Visit an authorization URL and return the URL the AS redirected back to (not followed)."
  [url]
  (let [r (w/request {:method "GET" :url url})]
    (when-not (= 302 (:status r)) (throw (ex-info (str "authorize: HTTP " (:status r)) {})))
    (get-in r [:headers "location"])))
