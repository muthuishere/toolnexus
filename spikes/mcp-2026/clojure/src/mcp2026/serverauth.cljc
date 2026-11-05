(ns mcp2026.serverauth
  "Server-side authorization: the MCP server as an OAuth 2.1 protected resource.
  Config is a plain map on the server's :auth:
    {:issuer   \"https://as.example\"          ; required
     :audience \"https://mcp.example/mcp\"     ; default: derived from the request
     :scopes   {\"*\" [\"mcp:read\"] \"greet\" [\"mcp:greet\"]} ; \"*\" = every request, name = that tool
     :verify   (fn [token] principal)          ; optional: replaces JWT/JWKS (throw = invalid)
     :http     request-fn}                     ; AS metadata + JWKS fetches
  A principal is {:subject :scopes :claims}; tool handlers see it as (:principal ctx)."
  (:require [clojure.string :as str]
            [mcp2026.auth :as auth]
            [mcp2026.wire :as w]))

(defn- scheme [req] (if (:tls? req) "https" "http"))

(defn audience [a req]
  (or (not-empty (:audience a)) (str (scheme req) "://" (:host req) "/mcp")))

(defn serve-metadata
  "RFC 9728 PRM at the path-inserted and root well-known URIs; nil for any other path."
  [a req]
  (when (#{auth/prm-path (str auth/prm-path "/mcp")} (:path req))
    {:status 200 :headers {"Content-Type" "application/json"}
     :body (w/write-json {"resource" (audience a req) "authorization_servers" [(:issuer a)]
                          "scopes_supported" (get (:scopes a) "*") "bearer_methods_supported" ["header"]})}))

;; ------------------------------------------------------------------ default verifier: RS256 via JWKS

(defn- jwks-key [a cache kid]
  (locking cache
    (or (get-in @cache [:keys kid])
        (if (and (:keys @cache) (< (- (w/now-ms) (:fetched @cache 0)) 30000)) ; garbage kids must not hammer the AS
          (throw (ex-info "unknown kid" {}))
          (let [http (or (:http a) w/request)
                md (auth/discover-as http (:issuer a))
                jwks (auth/get-json http (get md "jwks_uri"))
                ks (into {} (for [k (get jwks "keys") :when (= "RSA" (get k "kty"))]
                              [(get k "kid") (w/rsa-public-key (get k "n") (get k "e"))]))]
            (reset! cache {:keys ks :fetched (w/now-ms)})
            (or (get ks kid) (throw (ex-info "unknown kid" {}))))))))

(defn verify-jwt [a cache tok aud]
  (let [parts (str/split tok #"\." -1)
        _ (when-not (= 3 (count parts)) (throw (ex-info "malformed jwt" {})))
        [h c s] parts
        hdr (w/read-json (w/bytes->str (w/b64url-decode h)))
        claims (w/read-json (w/bytes->str (w/b64url-decode c)))]
    (when-not (and (map? hdr) (map? claims) (= "RS256" (get hdr "alg")))
      (throw (ex-info "bad jwt header/claims" {})))
    (when-not (w/rs256-verify? (jwks-key a cache (get hdr "kid")) (str h "." c) (w/b64url-decode s))
      (throw (ex-info "bad signature" {})))
    (when-not (= (get claims "iss") (:issuer a)) (throw (ex-info "wrong issuer" {})))
    (let [ad (get claims "aud")]
      (when-not (if (sequential? ad) (some #{aud} ad) (= ad aud)) (throw (ex-info "wrong audience" {}))))
    (let [exp (get claims "exp")]
      (when-not (and (number? exp) (> (long exp) (quot (w/now-ms) 1000))) (throw (ex-info "expired" {}))))
    {:subject (str (get claims "sub" ""))
     :scopes (vec (remove str/blank? (str/split (str (get claims "scope" "")) #"\s+")))
     :claims claims}))

(defn check
  "Authenticate req. Returns [:ok principal] or [:deny ring-response] (401/403 + WWW-Authenticate)."
  [a cache req method params]
  (let [challenge (fn [status extra scopes]
                    [:deny {:status status
                            :headers {"WWW-Authenticate"
                                      (str "Bearer resource_metadata=\"" (scheme req) "://" (:host req) auth/prm-path "/mcp\""
                                           (when (seq scopes) (str ", scope=\"" (str/join " " scopes) "\""))
                                           extra)
                                      "Content-Type" "application/json"}
                            :body "{\"error\":\"unauthorized\"}"}])
        authz (str (get-in req [:headers "authorization"]))
        tok (when (str/starts-with? authz "Bearer ") (subs authz 7))
        base (vec (get (:scopes a) "*"))]
    (if (str/blank? tok)
      (challenge 401 "" base)
      (let [verify (or (:verify a) (fn [t] (verify-jwt a cache t (audience a req))))
            pr (try (verify tok) (catch Exception _ nil))] ; never echo the token or the reason
        (if-not pr
          (challenge 401 ", error=\"invalid_token\"" base)
          (let [need (cond-> base (= method "tools/call") (into (get (:scopes a) (get params "name"))))
                missing (remove (set (:scopes pr)) need)]
            (if (seq missing)
              (challenge 403 ", error=\"insufficient_scope\"" need)
              [:ok pr])))))))
