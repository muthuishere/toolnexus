;; SPEC §2A.1 / §2A.1a — the MCP OAuth 2.1 CLIENT (MCP 2026-07-28 `basic/authorization`).
;;
;;   401/403 + WWW-Authenticate -> Protected Resource Metadata (RFC 9728)
;;   -> AS metadata (RFC 8414 / OIDC) -> client identity (pre-registered |
;;   Client ID Metadata Document; NEVER Dynamic Client Registration)
;;   -> authorization code + PKCE S256 + resource (RFC 8707) -> iss (RFC 9207)
;;   and state check -> token -> retry with Bearer.
;;
;; Refresh is single-flight; a 403 insufficient_scope steps up with the union of
;; the scopes. Two modes: `auto` (loopback callback + an `open-url` hook) and
;; `pause` (a §10 `authorization` Request the host answers with the callback URL).
;;
;; One `.cljc`, zero reader conditionals, zero `java.*`: everything host-shaped
;; goes through koine (HTTP client, HTTP server, base64, clock). Two things koine
;; does not have are written here in plain clojure.core, because both hosts can
;; run arithmetic: SHA-256 (for PKCE and skill digests) and percent-encoding.
;; Randomness comes from `random-uuid`, which is a CSPRNG on both hosts
;; (java.util.UUID/randomUUID is SecureRandom; cljgo's is crypto/rand).
;;
;; SECRETS. Tokens, codes, verifiers and client secrets are never logged and
;; never put into error text. A Token keeps its two secret values inside
;; closures (`reveal` reads them), so printing a Token — or a connection, or a
;; toolkit holding one — shows `#object[fn]` where a value would have been.
(ns toolnexus.mcp-auth
  (:require [clojure.string :as str]
            [koine.codec :as codec]
            [koine.http :as http]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.server :as server]
            [koine.time :as ktime]))

;; ---------------------------------------------------------------------------
;; bytes, SHA-256, base64url
;; ---------------------------------------------------------------------------

(defn utf8-vals
  "A string's UTF-8 bytes as unsigned values (0-255). Through koine's base64,
  which is the one portable string<->bytes seam both hosts already share."
  [s]
  (codec/b64-decode-vals (codec/encode (str s))))

(defn vals->utf8
  "Unsigned byte values back to a string (UTF-8)."
  [vs]
  (if (empty? vs) "" (codec/decode (codec/b64-encode-vals vs))))

(def ^:private k256
  [0x428a2f98 0x71374491 0xb5c0fbcf 0xe9b5dba5 0x3956c25b 0x59f111f1 0x923f82a4 0xab1c5ed5
   0xd807aa98 0x12835b01 0x243185be 0x550c7dc3 0x72be5d74 0x80deb1fe 0x9bdc06a7 0xc19bf174
   0xe49b69c1 0xefbe4786 0x0fc19dc6 0x240ca1cc 0x2de92c6f 0x4a7484aa 0x5cb0a9dc 0x76f988da
   0x983e5152 0xa831c66d 0xb00327c8 0xbf597fc7 0xc6e00bf3 0xd5a79147 0x06ca6351 0x14292967
   0x27b70a85 0x2e1b2138 0x4d2c6dfc 0x53380d13 0x650a7354 0x766a0abb 0x81c2c92e 0x92722c85
   0xa2bfe8a1 0xa81a664b 0xc24b8b70 0xc76c51a3 0xd192e819 0xd6990624 0xf40e3585 0x106aa070
   0x19a4c116 0x1e376c08 0x2748774c 0x34b0bcb5 0x391c0cb3 0x4ed8aa4a 0x5b9cca4f 0x682e6ff3
   0x748f82ee 0x78a5636f 0x84c87814 0x8cc70208 0x90befffa 0xa4506ceb 0xbef9a3f7 0xc67178f2])

(def ^:private m32 0xffffffff)

(defn- rotr [x n] (bit-and m32 (bit-or (unsigned-bit-shift-right x n) (bit-shift-left x (- 32 n)))))
(defn- add32 [& xs] (bit-and m32 (reduce + 0 xs)))

(defn- sha256-block [h w16]
  (let [w (loop [w (vec w16) i 16]
            (if (= i 64)
              w
              (let [a (nth w (- i 15)) b (nth w (- i 2))
                    s0 (bit-xor (rotr a 7) (rotr a 18) (unsigned-bit-shift-right a 3))
                    s1 (bit-xor (rotr b 17) (rotr b 19) (unsigned-bit-shift-right b 10))]
                (recur (conj w (add32 (nth w (- i 16)) s0 (nth w (- i 7)) s1)) (inc i)))))
        out (loop [i 0 [a b c d e f g hh] h]
              (if (= i 64)
                [a b c d e f g hh]
                (let [S1 (bit-xor (rotr e 6) (rotr e 11) (rotr e 25))
                      ch (bit-xor (bit-and e f) (bit-and (bit-xor e m32) g))
                      t1 (add32 hh S1 ch (nth k256 i) (nth w i))
                      S0 (bit-xor (rotr a 2) (rotr a 13) (rotr a 22))
                      mj (bit-xor (bit-and a b) (bit-and a c) (bit-and b c))
                      t2 (add32 S0 mj)]
                  (recur (inc i) [(add32 t1 t2) a b c (add32 d t1) e f g]))))]
    (mapv add32 h out)))

(defn sha256
  "SHA-256 of a string (UTF-8) or a seq of unsigned byte values; returns 32
  unsigned byte values. FIPS 180-4, plain arithmetic, identical on both hosts."
  [x]
  (let [bs   (vec (if (string? x) (utf8-vals x) x))
        bits (* 8 (count bs))
        padded (-> bs (conj 0x80)
                   (into (repeat (mod (- 56 (mod (inc (count bs)) 64)) 64) 0))
                   (into (map (fn [i] (bit-and 0xff (unsigned-bit-shift-right bits (* 8 (- 7 i)))))
                              (range 8))))
        h (reduce (fn [h block]
                    (sha256-block h (map (fn [j]
                                           (let [o (* 4 j)]
                                             (bit-or (bit-shift-left (nth block o) 24)
                                                     (bit-shift-left (nth block (+ o 1)) 16)
                                                     (bit-shift-left (nth block (+ o 2)) 8)
                                                     (nth block (+ o 3)))))
                                         (range 16))))
                  [0x6a09e667 0xbb67ae85 0x3c6ef372 0xa54ff53a 0x510e527f 0x9b05688c 0x1f83d9ab 0x5be0cd19]
                  (partition 64 padded))]
    (vec (mapcat (fn [x] [(bit-and 0xff (unsigned-bit-shift-right x 24))
                          (bit-and 0xff (unsigned-bit-shift-right x 16))
                          (bit-and 0xff (unsigned-bit-shift-right x 8))
                          (bit-and 0xff x)])
                 h))))

(def ^:private hex-chars "0123456789abcdef")

(defn hex "Lower-case hex of unsigned byte values." [vs]
  (apply str (mapcat (fn [b] [(nth hex-chars (quot b 16)) (nth hex-chars (mod b 16))]) vs)))

(defn b64url
  "base64url without padding (RFC 4648 §5) of byte values or a string."
  [x]
  (-> (codec/b64-encode-vals (if (string? x) (utf8-vals x) x))
      (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" "")))

(defn rand-str
  "n url-safe random characters from the host CSPRNG (via random-uuid)."
  [n]
  (let [src (apply str (repeatedly (inc (quot n 32)) (fn [] (str/replace (str (random-uuid)) "-" ""))))]
    (subs src 0 n)))

;; ---------------------------------------------------------------------------
;; URLs and forms
;; ---------------------------------------------------------------------------

(def ^:private unreserved
  (set "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"))

(defn url-encode
  "Percent-encode every byte outside RFC 3986's unreserved set (UTF-8)."
  [s]
  (apply str (map (fn [b]
                    (let [c (char b)]
                      (if (and (< b 128) (contains? unreserved c))
                        (str c)
                        (str "%" (str/upper-case (hex [b]))))))
                  (utf8-vals s))))

(defn url-decode
  "Percent-decode ONCE (and `+` as space, for forms). Throws on a malformed escape."
  ([s] (url-decode s true))
  ([s plus?]
   (let [s (str s) n (count s)]
     (loop [i 0 out []]
       (if (>= i n)
         (vals->utf8 out)
         (let [c (nth s i)]
           (cond
             (= c \%)
             (let [hx (when (<= (+ i 3) n) (str/lower-case (subs s (inc i) (+ i 3))))
                   hi (when hx (str/index-of hex-chars (nth hx 0)))
                   lo (when hx (str/index-of hex-chars (nth hx 1)))]
               (when-not (and hi lo) (throw (ex-info "bad percent-encoding" {})))
               (recur (+ i 3) (conj out (+ (* 16 hi) lo))))
             (and plus? (= c \+)) (recur (inc i) (conj out 32))
             :else (recur (inc i) (into out (utf8-vals (str c)))))))))))

(defn form-encode
  "application/x-www-form-urlencoded, keys in sorted order (deterministic)."
  [m]
  (str/join "&" (for [k (sort (map str (keys m)))
                      :let [v (get m k)]
                      :when (some? v)]
                  (str (url-encode k) "=" (url-encode (str v))))))

(defn parse-query
  "A query/form string as {name first-value}."
  [q]
  (reduce (fn [acc part]
            (if (str/blank? part)
              acc
              (let [[k v] (str/split part #"=" 2)
                    k (try (url-decode k) (catch Throwable _ k))]
                (if (contains? acc k) acc
                    (assoc acc k (try (url-decode (or v "")) (catch Throwable _ (or v ""))))))))
          {} (str/split (str q) #"&")))

(defn parse-url
  "{:scheme :host (with port) :hostname :path :query} of an absolute URL, or nil."
  [u]
  (when-let [[_ scheme host path query] (re-matches #"([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/?#]*)([^?#]*)(?:\?([^#]*))?(?:#.*)?" (str u))]
    {:scheme (str/lower-case scheme) :host host
     :hostname (str/replace host #":\d+$" "")
     :path path :query (or query "")}))

;; ---------------------------------------------------------------------------
;; tokens and the token store (§2A.1a)
;; ---------------------------------------------------------------------------

(defn- secret [v] (when (some? v) (let [s (str v)] (fn [] s))))

(defn reveal
  "The value of a Token secret (`:access-token` / `:refresh-token`), or nil."
  [x]
  (cond (fn? x) (x) (string? x) x :else nil))

(defn token
  "A Token: `{:access-token :refresh-token :expires-at :scopes :issuer :client-id
  :resource}`. The two token values are held in closures, so the Token's printed
  form never shows them; read them with `reveal`."
  [{:keys [access-token refresh-token expires-at scopes issuer client-id resource]}]
  {:access-token  (secret (reveal access-token))
   :refresh-token (secret (reveal refresh-token))
   :expires-at    expires-at
   :scopes        (vec scopes)
   :issuer        issuer
   :client-id     client-id
   :resource      resource})

(defn token->data
  "A Token as plain data (secrets revealed) — for a store that persists it.
  Persist it confidentially."
  [t]
  (-> t (update :access-token reveal) (update :refresh-token reveal)))

(defn memory-store
  "The default token store: in-memory, per load. A store is a map of three fns
  `{:load (fn [resource]) :save (fn [resource token]) :delete (fn [resource])}`."
  []
  (let [m (atom {})]
    {:load   (fn [r] (get @m r))
     :save   (fn [r t] (swap! m assoc r t) nil)
     :delete (fn [r] (swap! m dissoc r) nil)}))

(defn- st-load [e r] (some-> ((:load (:store e)) r) token))
(defn- st-save! [e r t] ((:save (:store e)) r t) nil)
(defn- st-delete! [e r] ((:delete (:store e)) r) nil)

(defn- expired? [t]
  (boolean (and (:expires-at t) (> (ktime/now-ms) (- (:expires-at t) 10000)))))

;; ---------------------------------------------------------------------------
;; the engine
;; ---------------------------------------------------------------------------

(defn- with-lock!
  "A compare-and-set! spin lock — the lock both hosts have (see toolnexus.mcp)."
  [lock f]
  (loop []
    (if (compare-and-set! lock false true)
      (try (f) (finally (reset! lock false)))
      (do (ktime/sleep! 1) (recur)))))

(defn- key-str [k] (if (keyword? k) (name k) (str k)))

(defn engine
  "One auth engine per remote server. `cfg` is the mcp.json `auth` object
  (camelCase keys, keywords or strings); `opts` are the load options
  `:token-store` and `:open-url`, plus `:http` (a request fn, tests only)."
  [cfg {:keys [token-store open-url http]}]
  (let [cfg (reduce (fn [acc e] (assoc acc (keyword (key-str (key e))) (val e))) {} (or cfg {}))]
    {:cfg      (update cfg :clientIds (fn [m] (reduce (fn [acc e] (assoc acc (key-str (key e)) (val e))) {} m)))
     :store    (or token-store (memory-store))
     :open-url open-url
     :http     (or http http/request)
     :state    (atom {:issuer {} :flows {}})
     :lock     (atom false)
     :refresh-lock (atom false)
     :stats    (atom {:refreshes 0})}))

(defn mode "\"auto\" unless the config says \"pause\"." [e]
  (if (= "pause" (str (get-in e [:cfg :mode]))) "pause" "auto"))

(defn- get-json [e url]
  (let [r ((:http e) {:method :get :url url :timeout-ms 30000 :headers {"accept" "application/json"}})]
    (when-not (= 200 (:status r))
      (throw (ex-info (str "GET " url ": HTTP " (or (:status r) (some-> (:error r) name))) {})))
    (let [m (try (json/read-str (:body r) {:key-fn str}) (catch Throwable _ nil))]
      (when-not (map? m) (throw (ex-info (str "GET " url ": not a JSON object") {})))
      m)))

(defn discover-as
  "RFC 8414 then OIDC metadata, in SPEC §2A.1 step 3's order; issuer must match."
  [e issuer]
  (let [{:keys [scheme host path]} (parse-url issuer)
        base  (str scheme "://" host)
        p     (str/replace (str path) #"/$" "")
        cands (if (seq p)
                [(str base "/.well-known/oauth-authorization-server" p)
                 (str base "/.well-known/openid-configuration" p)
                 (str base p "/.well-known/openid-configuration")]
                [(str base "/.well-known/oauth-authorization-server")
                 (str base "/.well-known/openid-configuration")])]
    (or (some (fn [c]
                (when-let [m (try (get-json e c) (catch Throwable _ nil))]
                  (when-not (= (get m "issuer") issuer)
                    (throw (ex-info (str "AS metadata issuer " (get m "issuer") " != " issuer) {})))
                  m))
              cands)
        (throw (ex-info (str "no authorization server metadata for " issuer) {})))))

(def ^:private prm-path "/.well-known/oauth-protected-resource")

(defn- fetch-prm [e resource hinted]
  (let [cands (if (seq hinted)
                [hinted]
                (let [{:keys [scheme host path]} (parse-url resource)
                      root (str scheme "://" host prm-path)
                      p    (str/replace (str path) #"/$" "")]
                  (cond-> [] (seq p) (conj (str root p)) true (conj root))))]
    (loop [[c & more] cands last-err nil]
      (if-not c
        (throw (ex-info (str "protected resource metadata: " (some-> last-err ex-message)) {}))
        (let [[m err] (try [(get-json e c) nil] (catch Throwable x [nil x]))]
          (cond err (recur more err)
                (not= (get m "resource") resource)
                (throw (ex-info (str "PRM resource " (get m "resource") " does not match " resource) {}))
                :else m))))))

(defn parse-challenge
  "`Bearer k=\"v\", k2=v2` (RFC 6750 §3) as {lower-key value}."
  [h]
  (let [h (str/trim (str h))
        h (str/trim (if (str/starts-with? (str/lower-case h) "bearer") (subs h 6) h))]
    (loop [h h out {}]
      (let [eq (str/index-of h "=")]
        (if (or (str/blank? h) (nil? eq))
          out
          (let [k (str/lower-case (str/trim (subs h 0 eq)))
                h (str/trim (subs h (inc eq)))
                [v rst] (if (str/starts-with? h "\"")
                          (let [end (loop [i 1] (cond (>= i (count h)) i
                                                      (= \\ (nth h i)) (recur (+ i 2))
                                                      (= \" (nth h i)) i
                                                      :else (recur (inc i))))]
                            [(str/replace (subs h 1 (min end (count h))) "\\\"" "\"")
                             (subs h (min (inc end) (count h)))])
                          (let [end (or (str/index-of h ",") (count h))]
                            [(str/trim (subs h 0 end)) (subs h end)]))
                rst (let [r (str/trim rst)] (str/trim (if (str/starts-with? r ",") (subs r 1) r)))]
            (recur rst (assoc out k v))))))))

(defn- token-request [e ep form issuer resource client-id scopes]
  (let [{:keys [tokenAuthMethod clientSecret clientMetadataUrl]} (:cfg e)
        secret? (and (not= client-id clientMetadataUrl) (not (str/blank? (str clientSecret))))
        method  (cond (not secret?) "none"
                      (str/blank? (str tokenAuthMethod)) "client_secret_basic"
                      :else (str tokenAuthMethod))
        form    (cond-> form
                  (= method "client_secret_post")  (assoc "client_secret" (str clientSecret))
                  (= method "client_secret_basic") (dissoc "client_id"))
        r ((:http e) {:method :post :url ep :timeout-ms 30000 :body (form-encode form)
                      :headers (cond-> {"content-type" "application/x-www-form-urlencoded"
                                        ;; GitHub answers form-encoded without it
                                        "accept" "application/json"}
                                 (= method "client_secret_basic")
                                 (assoc "authorization"
                                        (str "Basic " (codec/encode (str (url-encode client-id) ":"
                                                                         (url-encode (str clientSecret)))))))})
        tr (or (try (let [m (json/read-str (str (:body r)) {:key-fn str})] (when (map? m) m))
                    (catch Throwable _ nil))
               ;; RFC 6749 §5.1 mandates JSON; some ASes (GitHub) still answer forms
               (when (str/starts-with? (str (http/header r "content-type")) "application/x-www-form-urlencoded")
                 (parse-query (:body r))))
        at (get tr "access_token")]
    (when (or (nil? tr) (not= 200 (:status r)) (str/blank? (str at)))
      ;; SPEC §2A.1 step 7: the status and the error code — NEVER the body.
      (throw (ex-info (str "token endpoint: HTTP " (:status r) " " (str (get tr "error" ""))) {})))
    (let [ei (get tr "expires_in")
          ei (if (string? ei) (parse-long (if (str/blank? ei) "0" ei)) ei)
          sc (str (get tr "scope" ""))]
      (token {:access-token at
              :refresh-token (let [rt (get tr "refresh_token")] (when-not (str/blank? (str rt)) rt))
              :issuer issuer :resource resource :client-id client-id
              :scopes (if (str/blank? sc) scopes (str/split (str/trim sc) #"\s+"))
              :expires-at (when (and (number? ei) (pos? ei)) (+ (ktime/now-ms) (* 1000 (long ei))))}))))

(defn- refresh! [e t]
  (when-not (reveal (:refresh-token t)) (throw (ex-info "no refresh token" {})))
  (swap! (:stats e) update :refreshes inc)
  (let [md   (discover-as e (:issuer t))
        form (cond-> {"grant_type" "refresh_token" "refresh_token" (reveal (:refresh-token t))
                      "client_id" (:client-id t)}
               (not (true? (get-in e [:cfg :omitRefreshResource]))) (assoc "resource" (:resource t)))
        nt   (try (token-request e (get md "token_endpoint") form (:issuer t) (:resource t)
                                 (:client-id t) (:scopes t))
                  (catch Throwable x (st-delete! e (:resource t)) (throw x)))]
    (st-save! e (:resource t)
              (cond-> nt (nil? (reveal (:refresh-token nt))) (assoc :refresh-token (:refresh-token t))))))

(defn bearer
  "The access token to send to `resource` (refreshed if expired), or nil = send
  none. A token is sent only once this server's issuer is confirmed (§2A.1.11).
  Refresh is single-flight: N concurrent callers share one refresh request."
  [e resource]
  (let [iss (get-in @(:state e) [:issuer resource])
        t   (st-load e resource)]
    (when (and t iss (= (:issuer t) iss))
      (if-not (expired? t)
        (reveal (:access-token t))
        (with-lock! (:refresh-lock e)
          (fn []
            (when-let [t (st-load e resource)]
              (when (or (not (expired? t)) (try (refresh! e t) true (catch Throwable _ false)))
                (some-> (st-load e resource) :access-token reveal)))))))))

(defn- pkce-challenge [verifier] (b64url (sha256 verifier)))

(defn complete!
  "Validate the callback (iss, then state, then error — SPEC §2A.1 step 6) and
  exchange the code. Nothing is stored on failure."
  [e f callback-url]
  (let [q (parse-query (:query (parse-url callback-url)))]
    (if (contains? q "iss")
      (when-not (= (get q "iss") (:issuer f))
        (throw (ex-info "authorization response iss mismatch (possible mix-up attack)" {})))
      (when (:iss-supported f) (throw (ex-info "authorization response missing iss" {}))))
    (when-not (= (get q "state") (:state f)) (throw (ex-info "authorization response state mismatch" {})))
    (when-not (str/blank? (get q "error")) (throw (ex-info (str "authorization denied: " (get q "error")) {})))
    (let [t (token-request e (:token-endpoint f)
                           {"grant_type" "authorization_code" "code" (get q "code") "redirect_uri" (:redirect f)
                            "client_id" (:client-id f) "code_verifier" (:verifier f) "resource" (:resource f)}
                           (:issuer f) (:resource f) (:client-id f) (:scopes f))]
      (st-save! e (:resource f) t)
      nil)))

;; ---------------------------------------------------------------------------
;; auto mode — the loopback receiver (RFC 8252 §7.3)
;; ---------------------------------------------------------------------------
;;
;; koine.server hands a handler the PATH and never the query string, and the
;; query is where the AS puts `code`, `state` and `iss`. So the callback page
;; is a few lines of script: the browser GETs /callback?code=…, the page POSTs
;; its own `location.search` back to /callback, and the POST body — which koine
;; does deliver — is the query. A real browser runs it; a test's `open-url`
;; hook does the same two requests by hand.

(def ^:private callback-page
  (str "<!doctype html><meta charset=utf-8><title>Authorization</title>"
       "<p id=m>Completing authorization…</p><script>"
       "fetch(location.pathname,{method:'POST',body:location.search.replace(/^\\?/,'')})"
       ".then(function(){document.getElementById('m').textContent="
       "'Authorization received. You can close this window.'})</script>"))

(defn default-open-url
  "Open `url` in the system browser (macOS `open`, else `xdg-open`, else Windows `start`)."
  [url]
  (let [try-cmd (fn [cmd] (try (= 0 (:exit (proc/sh cmd {:timeout-ms 10000}))) (catch Throwable _ false)))]
    (or (try-cmd ["open" url]) (try-cmd ["xdg-open" url]) (try-cmd ["cmd" "/c" "start" "" url])
        (throw (ex-info "could not open a browser" {})))))

(defn- loopback-session [e]
  (let [result (atom nil)
        srv (server/serve (fn [req]
                            (cond
                              (not= "/callback" (:path req)) {:status 404 :body "not found"}
                              (= :post (:method req))
                              (do (compare-and-set! result nil [:cb (:body req)])
                                  {:status 200 :headers {"content-type" "text/plain"}
                                   :body "Authorization received. You can close this window."})
                              :else {:status 200 :headers {"content-type" "text/html; charset=utf-8"}
                                     :body callback-page}))
                          {:port 0 :host "127.0.0.1"})
        redirect (or (not-empty (str (get-in e [:cfg :redirectUri] "")))
                     (str "http://127.0.0.1:" (server/port srv) "/callback"))]
    {:redirect-uri redirect
     :close (fn [] (server/stop! srv))
     :wait (fn [request]
             (let [open (or (:open-url e) default-open-url)]
               ;; the opener may block (a test hook drives both requests); a
               ;; failing opener must not leave us waiting minutes
               (proc/run-async! (fn [] (try (open (:url request))
                                            (catch Throwable x (compare-and-set! result nil [:err x])))))
               (let [deadline (+ (ktime/mono-ms) (* 5 60 1000))
                     [k v] (loop [] (or @result
                                        (if (> (ktime/mono-ms) deadline) [:timeout nil]
                                            (do (ktime/sleep! 5) (recur)))))]
                 (case k
                   :cb  (str redirect "?" v)
                   :err (throw (ex-info (str "open authorization URL: " (ex-message v)) {}))
                   (throw (ex-info "authorization timed out" {}))))))}))

;; ---------------------------------------------------------------------------
;; the challenge
;; ---------------------------------------------------------------------------

(defn challenge!
  "Handle a 401/403 from `resource`. `sent` is the access token the failed
  request carried (nil = none). Returns `:retry` (send the request again now)
  or `{:pending request}` (pause mode — a §10 `authorization` Request; finish
  with `resume!`). Throws ex-info carrying a safe message on any failure."
  [e resource status www sent]
  (let [params (parse-challenge www)
        c      (:cfg e)]
    (with-lock! (:lock e)
      (fn []
        (let [cur   (st-load e resource)
              known (get-in @(:state e) [:issuer resource])]
          (if (and cur known (= (:issuer cur) known) (not= (reveal (:access-token cur)) sent)
                   (not (expired? cur)))
            :retry ; another caller already authorized while we waited
            (let [cur (if (and (= status 401) cur (reveal (:refresh-token cur))
                               (= (get params "error") "invalid_token"))
                        (if (try (refresh! e cur) true (catch Throwable _ false)) ::refreshed nil)
                        cur)]
              (if (= cur ::refreshed)
                :retry
                (do
                  (when (and (= status 403) (not= (get params "error") "insufficient_scope"))
                    (throw (ex-info (str "forbidden: " (get params "error")) {})))
                  (let [prm     (fetch-prm e resource (get params "resource_metadata"))
                        servers (get prm "authorization_servers")
                        _ (when (empty? servers) (throw (ex-info "PRM lists no authorization_servers" {})))
                        issuer  (str (first servers))
                        _ (when (and (not (true? (:allowHttp c))) (not (str/starts-with? issuer "https://")))
                            (throw (ex-info "authorization server must use https" {})))
                        md (discover-as e issuer)
                        _ (when-not (some #{"S256"} (get md "code_challenge_methods_supported"))
                            (throw (ex-info "authorization server does not advertise PKCE S256; refusing" {})))
                        _ (swap! (:state e) assoc-in [:issuer resource] issuer)]
                    (if (and (= status 401) cur (= (:issuer cur) issuer) (str/blank? (get params "error")) (nil? sent))
                      :retry ; first request after a restart: the issuer is now known
                      (let [scopes (let [s (remove str/blank? (str/split (str (get params "scope" "")) #"\s+"))]
                                     (vec (if (seq s) s (filter string? (get prm "scopes_supported")))))
                            scopes (if (and cur (= status 403)) ; step-up: the union
                                     (into scopes (remove (set scopes) (:scopes cur)))
                                     scopes)
                            cimd?  (true? (get md "client_id_metadata_document_supported"))
                            client-id (or (not-empty (str (get (:clientIds c) issuer "")))
                                          (not-empty (str (or (:clientId c) "")))
                                          (when (and cimd? (not-empty (str (or (:clientMetadataUrl c) ""))))
                                            (str (:clientMetadataUrl c))))
                            _ (when-not client-id
                                (throw (ex-info (str "no client identity for " issuer ": "
                                                     (if cimd? "clientMetadataUrl is not set"
                                                         "it does not support Client ID Metadata Documents")
                                                     " and no client id is configured — register an app with "
                                                     issuer " and set auth.clientId (dynamic client registration is not supported)")
                                                {})))
                            pause? (= "pause" (mode e))
                            _ (when (and pause? (str/blank? (str (:redirectUri c))))
                                (throw (ex-info "auth.mode \"pause\" requires auth.redirectUri" {})))
                            sess (if pause?
                                   {:redirect-uri (str (:redirectUri c)) :wait (fn [_] ::pause) :close (fn [])}
                                   (loopback-session e))
                            f {:resource resource :issuer issuer :client-id client-id
                               :redirect (:redirect-uri sess)
                               :verifier (str (rand-str 32) (rand-str 32)) :state (rand-str 24)
                               :token-endpoint (get md "token_endpoint") :scopes scopes
                               :iss-supported (true? (get md "authorization_response_iss_parameter_supported"))
                               :expires (+ (ktime/now-ms) (* 10 60 1000))}
                            q (cond-> {"response_type" "code" "client_id" client-id "redirect_uri" (:redirect f)
                                       "state" (:state f) "code_challenge" (pkce-challenge (:verifier f))
                                       "code_challenge_method" "S256" "resource" resource}
                                (seq scopes) (assoc "scope" (str/join " " scopes)))
                            ae (str (get md "authorization_endpoint"))
                            request {:id (str "auth-" (rand-str 16)) :kind "authorization"
                                     :prompt (str "Authorize access to " resource)
                                     :url (str ae (if (str/includes? ae "?") "&" "?") (form-encode q))
                                     :expiresAt (ktime/iso-str (:expires f))}
                            cb (try ((:wait sess) request)
                                    (catch Throwable x ((:close sess)) (throw x)))]
                        (if (= cb ::pause)
                          (do (swap! (:state e) update :flows
                                     (fn [fl] (assoc (into {} (remove (fn [[_ v]] (> (ktime/now-ms) (:expires v))) fl))
                                                     (:id request) f)))
                              {:pending request})
                          (do ((:close sess))
                              (complete! e f cb)
                              :retry))))))))))))))

(defn resume!
  "Finish a paused authorization with the callback URL the AS redirected to.
  The caller then sends the paused request (for the first time)."
  [e id callback-url]
  (let [[old _] (swap-vals! (:state e) update :flows dissoc id)
        f (get-in old [:flows id])]
    (when-not f (throw (ex-info (str "unknown pending authorization \"" id "\"") {})))
    (when (> (ktime/now-ms) (:expires f)) (throw (ex-info "pending authorization expired" {})))
    (complete! e f callback-url)))

(defn answer-callback
  "The callback URL out of a §10 Answer's data (`callbackUrl`, any key spelling)."
  [answer]
  (let [d (:data answer)]
    (when (map? d) (or (get d :callbackUrl) (get d "callbackUrl")))))

(defn answer-denied-text
  "SPEC §2A.1a — the error for a not-ok answer to an authorization request."
  [answer]
  (str "authorization denied: " (let [r (:reason answer)] (if (str/blank? (str r)) "cancelled" (str r)))))
