;; SPEC §7C.2 / §2A.2 — MCP server authorization: an `mcp-handler` as an OAuth
;; 2.1 protected resource. `mcp-server-auth` is a plain helper that produces the
;; handler's `:authenticate` hook and the RFC 9728 metadata handler; the host
;; mounts both. Identity, tenants and token storage stay the host's.
;;
;;   (def auth (sa/mcp-server-auth {:issuer "https://login.example" :scopes {"*" ["mcp"]}}))
;;   (mh/mcp-handler tk {:protocol-versions ["2026-07-28"] :authenticate (:authenticate auth)})
;;   ;; route GET /.well-known/oauth-protected-resource[/mcp] to (:metadata auth)
;;
;; Mirrors golang/mcp_serverauth.go. The default verifier is RS256 over the
;; issuer's JWKS, written in plain arithmetic (toolnexus.mcp-crypto) so it runs
;; on both hosts. Portability: no reader conditional, no java.*.
(ns toolnexus.mcp-server-auth
  (:require [clojure.string :as str]
            [koine.http :as http]
            [koine.json :as json]
            [koine.time :as ktime]
            [toolnexus.mcp-crypto :as crypto]
            [toolnexus.mcp-handler :as mh]))

(def prm-path "/.well-known/oauth-protected-resource")

(defn- header [req k] (get (:headers req) k))

(defn- scopes-for [scopes k]
  (vec (or (get scopes k) (get scopes (keyword k)))))

(defn- origin [req]
  (let [scheme (let [s (:scheme req)] (if (some? s) (name s) "http"))
        host   (or (not-empty (header req "host"))
                   (when (:server-name req)
                     (str (:server-name req) (when (:server-port req) (str ":" (:server-port req)))))
                   "")]
    (str scheme "://" host)))

(defn- resource-path [a] (if (seq (:resource-path a)) (:resource-path a) "/mcp"))

(defn audience
  "The expected audience: `:audience`, else <scheme>://<Host><resource-path>."
  [a req]
  (if (seq (:audience a)) (:audience a) (str (origin req) (resource-path a))))

(defn metadata-url
  "The RFC 9728 metadata URL the challenge names."
  [a req]
  (or (when (seq (:audience a))
        (when-let [[_ scheme host path] (re-matches #"([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/?#]+)([^?#]*).*" (:audience a))]
          (str scheme "://" host prm-path path)))
      (str (origin req) prm-path (resource-path a))))

(defn- challenge [a req status scopes err]
  (mh/mcp-auth-error status
                     (str "Bearer resource_metadata=\"" (metadata-url a req) "\""
                          (when (seq scopes) (str ", scope=\"" (str/join " " scopes) "\""))
                          (when (seq err) (str ", error=\"" err "\"")))
                     err))

;; ---------------------------------------------------------------------------
;; the default verifier: RS256 JWT, keys from the issuer's JWKS
;; ---------------------------------------------------------------------------

(defn- default-http [req] (http/request req))

(defn- get-json [a url]
  (let [res ((or (:http-request a) default-http)
             {:method :get :url url :headers {"accept" "application/json"} :timeout-ms 10000})]
    (when-not (= 200 (:status res))
      (throw (ex-info (str "GET " url ": HTTP " (:status res)) {})))
    (json/read-str (:body res) {:key-fn str})))

(defn discover-as
  "Authorization server metadata in the SPEC §2A.1 step 3 order, whose `issuer`
  must equal `issuer`."
  [a issuer]
  (let [[_ scheme host path] (or (re-matches #"([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/?#]+)([^?#]*).*" (str issuer))
                                 (throw (ex-info "bad issuer" {})))
        o     (str scheme "://" host)
        path  (str/replace path #"/$" "")
        cands (cond-> [(str o "/.well-known/oauth-authorization-server" path)
                       (str o "/.well-known/openid-configuration" path)]
                (seq path) (conj (str o path "/.well-known/openid-configuration")))]
    (or (some (fn [c] (let [md (try (get-json a c) (catch Throwable _ nil))]
                        (when (and (map? md) (= issuer (get md "issuer"))) md)))
              cands)
        (throw (ex-info (str "authorization server metadata not found for " issuer) {})))))

(defn- jwks-key
  "The JWKS key for `kid`; an unknown kid refetches at most every 30 s."
  [a kid]
  (let [cache (:cache a)]
    (locking cache
      (or (get-in @cache [:keys kid])
          (if (and (:keys @cache) (< (- (ktime/mono-ms) (:fetched @cache 0)) 30000))
            (throw (ex-info "unknown kid" {}))
            (let [md   (discover-as a (:issuer a))
                  jwks (get-json a (str (get md "jwks_uri")))
                  ks   (into {} (keep (fn [k]
                                        (when (= "RSA" (get k "kty"))
                                          (try [(get k "kid") (crypto/jwk->public k)]
                                               (catch Throwable _ nil))))
                                      (get jwks "keys")))]
              (reset! cache {:keys ks :fetched (ktime/mono-ms)})
              (or (get ks kid) (throw (ex-info "unknown kid" {})))))))))

(defn verify-jwt
  "The default `:verify`: an RS256 JWT signed by a key in the issuer's JWKS,
  `iss` = issuer, `aud` (string or array) containing `aud`, `exp` > now.
  Returns the Principal {:subject :scopes :claims}; throws otherwise."
  [a token aud]
  (let [parts (str/split (str token) #"\." -1)
        _     (when-not (= 3 (count parts)) (throw (ex-info "malformed jwt" {})))
        [h c s] parts
        seg   (fn [x] (json/read-str (crypto/vals->utf8 (crypto/b64url-decode-vals x)) {:key-fn str}))
        hdr   (try (seg h) (catch Throwable _ nil))
        claims (try (seg c) (catch Throwable _ nil))]
    (when-not (and (map? hdr) (map? claims) (= "RS256" (get hdr "alg")))
      (throw (ex-info "bad jwt header or claims" {})))
    (let [key (jwks-key a (get hdr "kid"))
          sig (crypto/b64url-decode-vals s)]
      (when-not (crypto/rsa-verify? key (str h "." c) sig)
        (throw (ex-info "bad signature" {}))))
    (when-not (= (:issuer a) (get claims "iss")) (throw (ex-info "wrong issuer" {})))
    (let [ad (get claims "aud")]
      (when-not (cond (string? ad) (= ad aud)
                      (sequential? ad) (boolean (some #{aud} ad))
                      :else false)
        (throw (ex-info "wrong audience" {}))))
    (let [exp (get claims "exp")]
      (when-not (and (number? exp) (> (long exp) (quot (ktime/now-ms) 1000)))
        (throw (ex-info "expired" {}))))
    {:subject (let [x (get claims "sub")] (if (string? x) x ""))
     :scopes  (vec (remove str/blank? (str/split (let [x (get claims "scope")] (if (string? x) x "")) #"\s+")))
     :claims  claims}))

;; ---------------------------------------------------------------------------
;; authenticate + metadata
;; ---------------------------------------------------------------------------

(defn- authenticate [a req]
  (let [authz (str (or (header req "authorization") ""))
        tok   (when (str/starts-with? authz "Bearer ") (subs authz 7))
        base  (scopes-for (:scopes a) "*")]
    (when (str/blank? tok)
      (throw (challenge a req 401 base "")))
    (let [aud (audience a req)
          p   (try (if-let [f (:verify a)]
                     (f {:request req} tok aud)
                     (verify-jwt a tok aud))
                   (catch Throwable _ nil))] ; never echo the token or the reason
      (when-not (map? p)
        (throw (challenge a req 401 base "invalid_token")))
      (let [need (if (= "tools/call" (header req "mcp-method"))
                   (let [[nm ok] (mh/decode-header-value (header req "mcp-name"))]
                     (if ok
                       (reduce (fn [acc s] (if (some #{s} acc) acc (conj acc s)))
                               base (scopes-for (:scopes a) nm))
                       base))
                   base)
            have (set (:scopes p))]
        (when-not (every? have need)
          (throw (challenge a req 403 need "insufficient_scope")))
        p))))

(defn- metadata [a req]
  (let [path   (or (:uri req) (:path req) "")
        method (keyword (str/lower-case (name (or (:request-method req) (:method req) :get))))]
    (if (or (not= :get method) (not (#{prm-path (str prm-path (resource-path a))} path)))
      {:status 404 :headers {"Content-Type" "text/plain; charset=utf-8"} :body "404 page not found\n"}
      {:status  200
       :headers {"Content-Type" "application/json"}
       :body    (str (json/write-str {"resource" (audience a req)
                                      "authorization_servers" [(:issuer a)]
                                      "scopes_supported" (scopes-for (:scopes a) "*")
                                      "bearer_methods_supported" ["header"]})
                     "\n")})))

(defn mcp-server-auth
  "SPEC §7C.2 `MCPServerAuth` — protect an `mcp-handler` with bearer tokens.

    :issuer         the authorization server's issuer (PRM + required `iss`)
    :audience       the canonical resource URI; nil ⇒ <scheme>://<Host><resource-path>
    :resource-path  where the handler is mounted (default \"/mcp\")
    :scopes         {\"*\" [..] <tool-name> [..]} — \"*\" for every request (and
                    advertised); a tool name adds scopes for that tools/call
                    (read from the validated Mcp-Method / Mcp-Name headers, so
                    per-tool scopes apply on the 2026-07-28 wire only)
    :verify         (fn [ctx token audience] principal) replaces the default
                    RS256/JWKS verifier; a throw ⇒ invalid_token
    :http-request   (fn [koine-request] koine-response) for AS metadata + JWKS

  Returns {:authenticate (fn [ring-request] principal) — plug into the handler's
  `:authenticate`; failures throw `mcp-auth-error` (401 / 403 + challenge)
           :metadata     (fn [ring-request] ring-response) — the RFC 9728 document
  at GET /.well-known/oauth-protected-resource[<resource-path>], else 404
           :metadata-url (fn [ring-request] url)}.
  A Principal is {:subject :scopes :claims}; `mcp-identity` returns it."
  [opts]
  (let [a (assoc opts :cache (atom {}))]
    (assoc a
           :authenticate (fn [req] (authenticate a req))
           :metadata     (fn [req] (metadata a req))
           :metadata-url (fn [req] (metadata-url a req)))))
