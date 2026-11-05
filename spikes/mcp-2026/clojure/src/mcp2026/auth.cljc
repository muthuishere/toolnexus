(ns mcp2026.auth
  "Client-side OAuth for MCP 2026-07-28 (basic/authorization).
  Flow: 401/403 -> WWW-Authenticate -> PRM (RFC 9728) -> AS metadata (RFC 8414 / OIDC)
  -> client id (pre-registered | CIMD; never DCR) -> code + PKCE S256 + resource (RFC 8707)
  -> iss (RFC 9207) + state check -> token -> retry with Bearer. Refresh is single-flight;
  a 403 insufficient_scope steps up with the union of scopes."
  (:require [clojure.string :as str]
            [mcp2026.wire :as w]))

;; ------------------------------------------------------------------ tokens

(defrecord Token [access-token refresh-token expiry scopes issuer resource client-id]
  Object
  (toString [_] (str "Token{issuer=" issuer ", redacted}")))

(defmethod print-method Token [t ^java.io.Writer wr] (.write wr (str t)))

(defprotocol TokenStore
  (load-token [s resource] "the Token for resource, or nil")
  (save-token! [s resource token])
  (delete-token! [s resource]))

(defn memory-store
  "An in-memory TokenStore (the default). Implement TokenStore to persist tokens confidentially."
  []
  (let [m (atom {})]
    (reify TokenStore
      (load-token [_ r] (get @m r))
      (save-token! [_ r t] (swap! m assoc r t) nil)
      (delete-token! [_ r] (swap! m dissoc r) nil))))

(defn- expired? [t]
  (boolean (and (:expiry t) (> (w/now-ms) (- (:expiry t) 10000)))))

;; ------------------------------------------------------------------ pending (SPEC §10 Request)

(defn pending-of
  "The pending authorization {:id :kind \"authorization\" :prompt :url :expires-at} carried by
  exception e (or any cause), or nil."
  [e]
  (loop [e e]
    (when e
      (or (:mcp/pending (ex-data e)) (recur (.getCause ^Throwable e))))))

;; ------------------------------------------------------------------ authorizers
;; An authorizer is (fn [] session); a session is {:redirect-uri str, :wait (fn [pending] url | :pause), :close (fn [])}.

(defn pause-authorizer
  "Never blocks: every authorization becomes a pending (ex-info with :mcp/pending)."
  [redirect]
  (fn [] {:redirect-uri redirect :wait (fn [_] :pause) :close (fn [])}))

(defn loopback-authorizer
  "Opens the authorization URL (opts :open, default the system browser) and listens on
  http://127.0.0.1:<random>/callback (RFC 8252 §7.3)."
  ([] (loopback-authorizer {}))
  ([{:keys [open timeout-ms] :or {open w/open-browser timeout-ms (* 5 60 1000)}}]
   (fn []
     (let [result (promise)
           srv (w/serve! (fn [req]
                           (if (= (:path req) "/callback")
                             (do (deliver result [:cb (str "http://" (:host req) (:uri req))])
                                 {:status 200 :body "Authorization received. You can close this window."})
                             {:status 404 :body "not found"}))
                         {})]
       {:redirect-uri (str (:url srv) "/callback")
        :close (:stop srv)
        :wait (fn [p]
                ;; the opener may block (tests follow redirects synchronously); a failing opener must
                ;; not leave us waiting minutes for a callback that cannot come
                (future (try (open (:url p))
                             (catch Throwable t (deliver result [:err t]))))
                (let [[k v] (deref result timeout-ms [:timeout nil])]
                  (case k
                    :cb v
                    :err (throw (ex-info (str "open authorization URL: " (.getMessage ^Throwable v)) {} v))
                    :timeout (throw (ex-info "authorization timed out" {})))))}))))

;; ------------------------------------------------------------------ the engine

(defn oauth
  "Client-side auth engine; attach as the client's :auth. cfg keys:
  :authorizer (default (loopback-authorizer)) :client-id :client-secret
  :token-auth-method (\"none\"|\"client_secret_basic\"|\"client_secret_post\"; default basic if secret)
  :omit-refresh-resource :store (TokenStore) :client-ids {issuer id} :client-metadata-url (CIMD)
  :http (request fn) :allow-http (dev only: non-HTTPS AS)."
  [cfg]
  {:cfg (atom cfg)
   :store (or (:store cfg) (memory-store))
   :state (atom {:issuer {} :flows {}})
   :lock (Object.)})

(defn configure!
  "Change cfg keys on a live engine (e.g. swap the authorizer)."
  [o & kvs]
  (apply swap! (:cfg o) assoc kvs))

(defn store [o] (:store o))
(defn- cfg [o] @(:cfg o))
(defn- hc [o] (or (:http (cfg o)) w/request))

(defn get-json [http url]
  (let [r (http {:method "GET" :url url})]
    (when-not (= 200 (:status r))
      (throw (ex-info (str "GET " url ": HTTP " (:status r)) {})))
    (w/read-json (:body r))))

(defn discover-as
  "RFC 8414 then OIDC metadata in the spec's order; validates issuer equality."
  [http issuer]
  (let [{:keys [scheme host path]} (w/parse-url issuer)
        base (str scheme "://" host)
        p (str/replace path #"/$" "")
        cands (if (seq p)
                [(str base "/.well-known/oauth-authorization-server" p)
                 (str base "/.well-known/openid-configuration" p)
                 (str base p "/.well-known/openid-configuration")]
                [(str base "/.well-known/oauth-authorization-server")
                 (str base "/.well-known/openid-configuration")])]
    (or (some (fn [c]
                (when-let [m (try (get-json http c) (catch Exception _ nil))]
                  (when-not (= (get m "issuer") issuer)
                    (throw (ex-info (str "AS metadata issuer " (get m "issuer") " != " issuer) {})))
                  m))
              cands)
        (throw (ex-info (str "no authorization server metadata for " issuer) {})))))

(def prm-path "/.well-known/oauth-protected-resource")

(defn fetch-prm
  "PRM from the WWW-Authenticate hint, else path-inserted, else root well-known."
  [o resource hinted]
  (let [cands (if (seq hinted)
                [hinted]
                (let [{:keys [scheme host path]} (w/parse-url resource)
                      root (str scheme "://" host prm-path)
                      p (str/replace path #"/$" "")]
                  (cond-> [] (seq p) (conj (str root p)) true (conj root))))]
    (loop [[c & more] cands last-err nil]
      (if-not c
        (throw (ex-info (str "protected resource metadata: " (some-> ^Throwable last-err .getMessage)) {}))
        (let [[m err] (try [(get-json (hc o) c) nil] (catch Exception e [nil e]))]
          (cond err (recur more err)
                (not= (get m "resource") resource)
                (throw (ex-info (str "PRM resource " (get m "resource") " does not match " resource) {}))
                :else m))))))

(defn parse-challenge
  "Parse `Bearer k=\"v\", k2=v2` (RFC 6750 §3) into {lower-key value}."
  [h]
  (let [h (str/trim (or h ""))
        h (str/trim (if (str/starts-with? h "Bearer") (subs h 6) h))]
    (loop [h h out {}]
      (let [eq (str/index-of h "=")]
        (if (or (str/blank? h) (nil? eq))
          out
          (let [k (str/lower-case (str/trim (subs h 0 eq)))
                h (str/trim (subs h (inc eq)))
                [v rest] (if (str/starts-with? h "\"")
                           (let [end (loop [i 1] (cond (>= i (count h)) i
                                                       (= \\ (.charAt ^String h i)) (recur (+ i 2))
                                                       (= \" (.charAt ^String h i)) i
                                                       :else (recur (inc i))))]
                             [(str/replace (subs h 1 (min end (count h))) "\\\"" "\"")
                              (subs h (min (inc end) (count h)))])
                           (let [end (or (str/index-of h ",") (count h))]
                             [(str/trim (subs h 0 end)) (subs h end)]))
                rest (str/trim (let [r (str/trim rest)] (if (str/starts-with? r ",") (subs r 1) r)))]
            (recur rest (assoc out k v))))))))

(defn- token-request [o ep form issuer resource client-id scopes]
  (let [{:keys [token-auth-method client-secret client-metadata-url]} (cfg o)
        method (cond (or (= client-id client-metadata-url) (str/blank? client-secret)) "none"
                     (str/blank? token-auth-method) "client_secret_basic"
                     :else token-auth-method)
        form (cond-> form
               (= method "client_secret_post") (assoc "client_secret" client-secret)
               (= method "client_secret_basic") (dissoc "client_id")) ; RFC 6749 §2.3.1: one method only
        r ((hc o) {:method "POST" :url ep :body (w/form-encode form)
                   :headers (cond-> {"Content-Type" "application/x-www-form-urlencoded"
                                     "Accept" "application/json"} ; GitHub answers form-encoded without it
                              (= method "client_secret_basic")
                              (assoc "Authorization" (w/basic-auth (w/url-encode client-id) (w/url-encode client-secret))))})
        tr (or (try (let [m (w/read-json (:body r))] (when (map? m) m)) (catch Exception _ nil))
               ;; RFC 6749 §5.1 mandates JSON, but some ASes (GitHub) still default to form encoding
               (when (str/starts-with? (str (get-in r [:headers "content-type"])) "application/x-www-form-urlencoded")
                 (let [q (w/parse-query (:body r))]
                   (into {} (for [k ["access_token" "refresh_token" "scope" "error" "expires_in"]]
                              [k (w/q1 q k)])))))
        at (get tr "access_token")]
    (when (or (nil? tr) (not= 200 (:status r)) (str/blank? at))
      (throw (ex-info (str "token endpoint: HTTP " (:status r) " " (get tr "error" "")) {}))) ; never the body
    (let [ei (get tr "expires_in")
          ei (if (string? ei) (parse-long (if (str/blank? ei) "0" ei)) ei)]
      (map->Token {:access-token at
                   :refresh-token (let [rt (get tr "refresh_token")] (when-not (str/blank? rt) rt))
                   :issuer issuer :resource resource :client-id client-id
                   :scopes (if (str/blank? (get tr "scope")) scopes (str/split (str/trim (get tr "scope")) #"\s+"))
                   :expiry (when (and ei (pos? ei)) (+ (w/now-ms) (* 1000 (long ei))))}))))

(defn- refresh! [o t]
  (when-not (:refresh-token t) (throw (ex-info "no refresh token" {})))
  (let [md (discover-as (hc o) (:issuer t))
        form (cond-> {"grant_type" "refresh_token" "refresh_token" (:refresh-token t) "client_id" (:client-id t)}
               (not (:omit-refresh-resource (cfg o))) (assoc "resource" (:resource t)))
        nt (try (token-request o (get md "token_endpoint") form (:issuer t) (:resource t) (:client-id t) (:scopes t))
                (catch Exception e (delete-token! (store o) (:resource t)) (throw e)))]
    (save-token! (store o) (:resource t) (cond-> nt (nil? (:refresh-token nt)) (assoc :refresh-token (:refresh-token t))))))

(defn bearer
  "The access token to send for resource (refreshing if expired), or nil = send none.
  A token is only sent once this server's issuer is known (spec: Token Handling)."
  [o resource]
  (let [iss (get-in @(:state o) [:issuer resource])
        t (load-token (store o) resource)]
    (when (and t iss (= (:issuer t) iss))
      (if-not (expired? t)
        (:access-token t)
        (locking (:lock o) ; one refresh for N callers (refresh tokens rotate)
          (when-let [t (load-token (store o) resource)]
            (when (or (not (expired? t)) (try (refresh! o t) true (catch Exception _ false)))
              (:access-token (load-token (store o) resource)))))))))

(defn- pkce-challenge [verifier] (w/b64url (w/sha256 verifier)))

(defn- complete! [o f callback-url]
  (let [q (w/parse-query (:query (w/parse-url callback-url)))]
    ;; RFC 9207 §2.4 — before anything else, error responses included
    (if (contains? q "iss")
      (when-not (= (w/q1 q "iss") (:issuer f)) ; simple string comparison, no normalization
        (throw (ex-info "authorization response iss mismatch (possible mix-up attack)" {})))
      (when (:iss-supported f) (throw (ex-info "authorization response missing iss" {}))))
    (when-not (= (w/q1 q "state") (:state f)) (throw (ex-info "authorization response state mismatch" {})))
    (when-not (str/blank? (w/q1 q "error")) (throw (ex-info (str "authorization denied: " (w/q1 q "error")) {})))
    (let [t (token-request o (:token-endpoint f)
                           {"grant_type" "authorization_code" "code" (w/q1 q "code") "redirect_uri" (:redirect f)
                            "client_id" (:client-id f) "code_verifier" (:verifier f) "resource" (:resource f)}
                           (:issuer f) (:resource f) (:client-id f) (:scopes f))]
      (save-token! (store o) (:resource f) t))))

(defn challenge!
  "Handle a 401/403 for resource. Returns nil = retry the request now; throws a pending ex-info
  (pause) or an error. sent = the access token the failed request carried (nil = none)."
  [o resource status www sent retry]
  (let [params (parse-challenge www)
        c (cfg o)]
    (locking (:lock o) ; N concurrent 401s -> one authorization, the rest retry with its token
      (let [cur (load-token (store o) resource)
            known (get-in @(:state o) [:issuer resource])]
        (when-not (and cur known (= (:issuer cur) known) (not= (:access-token cur) sent) (not (expired? cur)))
          (let [cur (if (and (= status 401) cur (:refresh-token cur) (= (get params "error") "invalid_token"))
                      (if (try (refresh! o cur) true (catch Exception _ false)) ::refreshed nil)
                      cur)]
            (when-not (= cur ::refreshed)
              (when (and (= status 403) (not= (get params "error") "insufficient_scope"))
                (throw (ex-info (str "forbidden: " (get params "error")) {})))
              (let [prm (fetch-prm o resource (get params "resource_metadata"))
                    servers (get prm "authorization_servers")
                    _ (when (empty? servers) (throw (ex-info "PRM lists no authorization_servers" {})))
                    issuer (first servers) ; RFC 9728 §7.6: client's choice; we take the first
                    _ (when (and (not (:allow-http c)) (not (str/starts-with? (str issuer) "https://")))
                        (throw (ex-info "authorization server must use https" {})))
                    md (discover-as (hc o) issuer)
                    _ (when-not (some #{"S256"} (get md "code_challenge_methods_supported"))
                        (throw (ex-info "authorization server does not advertise PKCE S256; refusing" {})))
                    _ (swap! (:state o) assoc-in [:issuer resource] issuer)]
                (when-not (and (= status 401) cur (= (:issuer cur) issuer) (str/blank? (get params "error")))
                  ;; (else: first request after restart — the issuer is now known, the stored token may be sent)
                  (let [scopes (let [s (remove str/blank? (str/split (str (get params "scope")) #"\s+"))]
                                 (vec (if (seq s) s (filter string? (get prm "scopes_supported")))))
                        scopes (if (and cur (= status 403)) ; step-up: union with what we had
                                 (into scopes (remove (set scopes) (:scopes cur)))
                                 scopes)
                        cimd? (true? (get md "client_id_metadata_document_supported"))
                        client-id (or (not-empty (get (:client-ids c) issuer))
                                      (not-empty (:client-id c))
                                      (when (and cimd? (not-empty (:client-metadata-url c))) (:client-metadata-url c)))
                        _ (when-not client-id ; no DCR, ever: register an app once and configure its id
                            (throw (ex-info (str "no client identity for " issuer ": "
                                                 (if cimd? ":client-metadata-url is not set"
                                                     "it does not support Client ID Metadata Documents")
                                                 " and no client id is configured — register an app with " issuer
                                                 " and set OAuth.ClientID / :client-id (dynamic client registration is not supported)")
                                            {})))
                        sess ((or (:authorizer c) (loopback-authorizer)))
                        f {:resource resource :issuer issuer :client-id client-id :redirect (:redirect-uri sess)
                           :verifier (w/rand-str 32) :state (w/rand-str 16)
                           :token-endpoint (get md "token_endpoint") :scopes scopes :retry retry
                           :iss-supported (true? (get md "authorization_response_iss_parameter_supported"))
                           :expires (+ (w/now-ms) (* 10 60 1000))}
                        q (cond-> {"response_type" "code" "client_id" client-id "redirect_uri" (:redirect f)
                                   "state" (:state f) "code_challenge" (pkce-challenge (:verifier f))
                                   "code_challenge_method" "S256" "resource" resource}
                            (seq scopes) (assoc "scope" (str/join " " scopes)))
                        p {:id (str "auth-" (w/rand-str 12)) :kind "authorization"
                           :url (str (get md "authorization_endpoint") "?" (w/form-encode q))
                           :prompt (str "Authorize access to " resource)
                           :expires-at (w/rfc3339 (:expires f))}
                        cb (try ((:wait sess) p)
                                (catch Exception e ((:close sess)) (throw e)))]
                    (if (= cb :pause)
                      (do (swap! (:state o) update :flows
                                 (fn [fl] (assoc (into {} (remove (fn [[_ v]] (> (w/now-ms) (:expires v))) fl))
                                                 (:id p) f)))
                          (throw (ex-info (str "authorization pending: " (:prompt p)) {:mcp/pending p})))
                      (do ((:close sess))
                          (complete! o f cb)
                          nil))))))))))))

(defn set-retry!
  "Replace what Resume re-runs for pending id (the client's tools/call loop uses it)."
  [o id f]
  (swap! (:state o) (fn [s] (if (get-in s [:flows id]) (assoc-in s [:flows id :retry] f) s))))

(defn flow
  "The internal flow of a pending id (tests only)."
  [o id] (get-in @(:state o) [:flows id]))

(defn update-flow! [o id f & args] (apply swap! (:state o) update-in [:flows id] f args))

(defn resume
  "Finish a paused authorization with the callback URL and re-run the original operation;
  returns its result."
  [o id callback-url]
  (let [[old _] (swap-vals! (:state o) update :flows dissoc id)
        f (get-in old [:flows id])]
    (when-not f (throw (ex-info (str "unknown pending authorization " (pr-str id)) {})))
    (when (> (w/now-ms) (:expires f)) (throw (ex-info "pending authorization expired" {})))
    (complete! o f callback-url)
    ((:retry f))))

(defn resume-callback
  "Resume for web apps: the pending is found by the callback's state."
  [o callback-url]
  (let [st (w/q1 (w/parse-query (:query (w/parse-url callback-url))) "state")
        id (some (fn [[k f]] (when (and (seq st) (= st (:state f))) k)) (:flows @(:state o)))]
    (resume o id callback-url)))

(defn web-handler
  "An HTTP handler serving one tool call to a browser user: (call req) runs; if it pauses, the
  browser is redirected to the AS; when the AS redirects back to this handler (use its URL as the
  pause-authorizer redirect) the original call is resumed and its text written. Errors -> 502.
  text-fn extracts the text of a result."
  [o call text-fn]
  (fn [req]
    (let [q (w/parse-query (:query req))
          [res err] (try [(if (and (seq (w/q1 q "state")) (or (contains? q "code") (contains? q "error")))
                                (resume-callback o (:uri req))
                                (call req))
                          nil]
                         (catch Exception e [nil e]))]
      (if-let [p (some-> err pending-of)]
        {:status 302 :headers {"Location" (:url p)}}
        (if err
          {:status 502 :headers {"Content-Type" "text/plain"} :body (.getMessage ^Throwable err)}
          {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8"} :body (text-fn res)})))))
