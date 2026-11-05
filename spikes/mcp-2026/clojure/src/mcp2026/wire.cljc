(ns mcp2026.wire
  "Host plumbing for the spike: HTTP client + server, JSON, URL/form codecs, base64url,
  sha256, RSA, randomness. JVM host only (java.net.http + com.sun.net.httpserver); this is the
  one namespace that touches java.*, so every other namespace is plain Clojure over data.

  An HTTP client is just a function: (request-fn {:method :url :headers :body}) ->
  {:status :headers (lower-case name -> first value) :body}. `request` is the default; wrap it
  to add headers or record traffic. An HTTP handler is (fn [req] resp) over the same shape."
  (:require [clojure.string :as str]
            [koine.json :as kjson])
  (:import (java.io ByteArrayOutputStream File InputStream)
           (java.math BigInteger)
           (java.net CookieManager InetSocketAddress URI URLDecoder URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.security KeyFactory KeyPairGenerator KeyStore MessageDigest SecureRandom Signature)
           (java.security.cert X509Certificate)
           (java.security.spec RSAPublicKeySpec)
           (java.time Duration)
           (java.util Base64)
           (java.util.concurrent Executors ThreadFactory)
           (javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory X509TrustManager)
           (com.sun.net.httpserver HttpExchange HttpHandler HttpServer HttpsConfigurator
                                   HttpsExchange HttpsServer)))

;; ------------------------------------------------------------------ JSON (koine, sorted keys)

(defn read-json
  "Parse JSON; object keys stay strings (wire data is string-keyed, like Go's map[string]any)."
  [s]
  (kjson/read-str s {:key-fn identity}))

(defn write-json [x] (kjson/write-str x))

;; ------------------------------------------------------------------ bytes, hashes, randomness

(defn utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))
(defn bytes->str [^bytes b] (String. b StandardCharsets/UTF_8))

(defn sha256 ^bytes [x]
  (.digest (MessageDigest/getInstance "SHA-256") (if (string? x) (utf8 x) ^bytes x)))

(defn hex [^bytes b] (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn b64url [x] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (if (string? x) (utf8 x) ^bytes x)))
(defn b64url-decode ^bytes [^String s] (.decode (Base64/getUrlDecoder) s))
(defn b64 [x] (.encodeToString (Base64/getEncoder) (if (string? x) (utf8 x) ^bytes x)))
(defn b64-decode ^bytes [^String s] (.decode (Base64/getDecoder) s))

(def ^:private rng (SecureRandom.))
(defn rand-str
  "n random bytes, base64url (no padding)."
  [n]
  (let [b (byte-array n)] (.nextBytes ^SecureRandom rng b) (b64url b)))

(defn now-ms [] (System/currentTimeMillis))
(defn rfc3339 [ms] (str (.truncatedTo (java.time.Instant/ofEpochMilli ms) java.time.temporal.ChronoUnit/SECONDS)))

;; ------------------------------------------------------------------ RSA (RS256)

(defn rsa-keypair [] (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(defn rs256-sign ^bytes [private-key ^String input]
  (.sign (doto (Signature/getInstance "SHA256withRSA") (.initSign private-key) (.update (utf8 input)))))

(defn rs256-verify? [public-key ^String input ^bytes sig]
  (try (.verify (doto (Signature/getInstance "SHA256withRSA") (.initVerify public-key) (.update (utf8 input))) sig)
       (catch Exception _ false)))

(defn- unsigned ^bytes [^BigInteger n]
  (let [b (.toByteArray n)]
    (if (and (> (alength b) 1) (zero? (aget b 0))) (java.util.Arrays/copyOfRange b 1 (alength b)) b)))

(defn rsa-jwk
  "Public JWK fields {n e} (base64url, unsigned big-endian) of an RSA public key."
  [pub]
  {"n" (b64url (unsigned (.getModulus ^java.security.interfaces.RSAPublicKey pub)))
   "e" (b64url (unsigned (.getPublicExponent ^java.security.interfaces.RSAPublicKey pub)))})

(defn rsa-public-key [n-b64 e-b64]
  (.generatePublic (KeyFactory/getInstance "RSA")
                   (RSAPublicKeySpec. (BigInteger. 1 (b64url-decode n-b64)) (BigInteger. 1 (b64url-decode e-b64)))))

;; ------------------------------------------------------------------ URL + form codecs

(defn url-encode [s] (URLEncoder/encode (str s) "UTF-8"))
(defn url-decode [s] (URLDecoder/decode (str s) "UTF-8"))

(defn form-encode
  "Encode a map (or seq of pairs) as application/x-www-form-urlencoded, keys sorted like Go."
  [m]
  (str/join "&" (for [[k v] (sort-by key m) :when (some? v)] (str (url-encode k) "=" (url-encode v)))))

(defn parse-query
  "Parse a query/form string into {key [values...]} (presence of a key is observable)."
  [s]
  (reduce (fn [m pair]
            (if (str/blank? pair)
              m
              (let [[k v] (str/split pair #"=" 2)]
                (update m (url-decode k) (fnil conj []) (url-decode (or v ""))))))
          {} (str/split (or s "") #"&")))

(defn q1 "First value of key k in a parse-query map, or \"\"." [q k] (or (first (get q k)) ""))

(defn parse-url
  "{:scheme :host (host[:port]) :hostname :path :query} of an absolute (or path-only) URL."
  [u]
  (let [uri (URI. u)
        port (.getPort uri)]
    {:scheme (.getScheme uri)
     :hostname (.getHost uri)
     :host (when (.getHost uri) (str (.getHost uri) (when (pos? port) (str ":" port))))
     :path (or (.getRawPath uri) "")
     :query (.getRawQuery uri)}))

(defn percent-decode
  "Go url.PathUnescape: %XX -> byte (UTF-8), '+' kept, a malformed escape throws."
  [^String s]
  (let [out (ByteArrayOutputStream.)
        n (count s)]
    (loop [i 0]
      (when (< i n)
        (let [c (.charAt s i)]
          (if (= c \%)
            (let [h (when (<= (+ i 3) n) (subs s (inc i) (+ i 3)))]
              (when-not (and h (re-matches #"[0-9a-fA-F]{2}" h))
                (throw (ex-info (str "invalid URL escape " (pr-str (subs s i (min n (+ i 3))))) {})))
              (.write out (Integer/parseInt h 16))
              (recur (+ i 3)))
            (let [b (utf8 (str c))]
              (.write out b 0 (alength b))
              (recur (inc i)))))))
    (bytes->str (.toByteArray out))))

;; ------------------------------------------------------------------ TLS trust for dev servers

(defonce ^:private dev-certs (atom #{}))

(def ^:private system-tm
  (delay (let [f (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                   (.init ^KeyStore (cast KeyStore nil)))]
           (first (filter #(instance? X509TrustManager %) (.getTrustManagers f))))))

(def ^:private trust-manager
  (reify X509TrustManager
    (checkClientTrusted [_ chain auth] (.checkClientTrusted ^X509TrustManager @system-tm chain auth))
    (checkServerTrusted [_ chain auth]
      (when-not (contains? @dev-certs (aget ^"[Ljava.security.cert.X509Certificate;" chain 0))
        (.checkServerTrusted ^X509TrustManager @system-tm chain auth)))
    (getAcceptedIssuers [_] (.getAcceptedIssuers ^X509TrustManager @system-tm))))

(defn trust-dev-cert!
  "Dev only: every client in this process now trusts cert (a DevAuthServer's self-signed cert)."
  [cert]
  (swap! dev-certs conj cert))

(def ^:private client-ssl (delay (doto (SSLContext/getInstance "TLS") (.init nil (into-array [trust-manager]) nil))))

;; ------------------------------------------------------------------ HTTP client

(def default-timeout-ms 30000)

(defn- build-client [{:keys [follow cookies]}]
  (cond-> (HttpClient/newBuilder)
    true (.version HttpClient$Version/HTTP_1_1)
    true (.sslContext @client-ssl)
    true (.connectTimeout (Duration/ofSeconds 10))
    true (.followRedirects (if (= follow :always) HttpClient$Redirect/ALWAYS HttpClient$Redirect/NEVER))
    cookies (.cookieHandler (CookieManager.))
    true (.build)))

(def ^:private restricted #{"host" "content-length" "connection" "expect" "upgrade"})

(defn http-fn
  "A request function over its own HttpClient. opts: {:follow :always|:never (default),
  :cookies true (a cookie jar, for headless browsers)}."
  ([] (http-fn {}))
  ([opts]
   (let [client (build-client opts)]
     (fn [{:keys [method url headers body timeout-ms]}]
       (let [method (or method "GET")
             b (cond-> (HttpRequest/newBuilder (URI. url))
                 true (.timeout (Duration/ofMillis (or timeout-ms default-timeout-ms)))
                 true (.method method (if body
                                        (HttpRequest$BodyPublishers/ofString body)
                                        (HttpRequest$BodyPublishers/noBody))))
             _ (doseq [[k v] headers :when (and v (not (restricted (str/lower-case k))))]
                 (.header b k (str v)))
             resp (.send ^HttpClient client (.build b) (HttpResponse$BodyHandlers/ofString))]
         {:status (.statusCode resp)
          :headers (into {} (for [[k vs] (.map (.headers resp))] [(str/lower-case k) (first vs)]))
          :header-lists (into {} (for [[k vs] (.map (.headers resp))] [(str/lower-case k) (vec vs)]))
          :body (.body resp)
          :url (str (.uri resp))})))))

(def request
  "The default HTTP client function: 30 s timeout, no redirects, trusts in-process dev servers."
  (http-fn))

(def browser
  "A redirect-following client (follows https -> http loopback too): a test/dev 'browser'."
  (http-fn {:follow :always}))

(defn basic-auth [user pass] (str "Basic " (b64 (str user ":" pass))))

;; ------------------------------------------------------------------ HTTP server

(def max-body-bytes (* 4 1024 1024))

(defn- daemon-pool []
  (Executors/newCachedThreadPool
   (reify ThreadFactory (newThread [_ r] (doto (Thread. ^Runnable r) (.setDaemon true))))))

(defn- read-body [^InputStream in]
  (let [out (ByteArrayOutputStream.) buf (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in buf)]
        (cond (neg? n) (bytes->str (.toByteArray out))
              (> (+ total n) max-body-bytes) ::too-large
              :else (do (.write out buf 0 n) (recur (+ total n))))))))

(defn- exchange->req [^HttpExchange ex]
  (let [uri (.getRequestURI ex)
        headers (into {} (for [[k vs] (.getRequestHeaders ex)] [(str/lower-case k) (first vs)]))
        body (read-body (.getRequestBody ex))]
    {:method (.getRequestMethod ex)
     :path (.getPath uri)
     :query (.getRawQuery uri)
     :uri (str (.getRawPath uri) (when-let [q (.getRawQuery uri)] (str "?" q)))
     :headers headers
     :host (get headers "host")
     :tls? (instance? HttpsExchange ex)
     :body (when-not (= body ::too-large) body)
     :too-large? (= body ::too-large)}))

(defn- send! [^HttpExchange ex {:keys [status headers body drop?]}]
  (if drop?
    (.close ex) ; no status line at all: the client sees a dropped connection
    (let [b (utf8 (or body ""))]
      (doseq [[k v] headers] (.set (.getResponseHeaders ex) k (str v)))
      (.sendResponseHeaders ex (or status 200) (if (zero? (alength b)) -1 (alength b)))
      (with-open [o (.getResponseBody ex)] (.write o b))
      (.close ex))))

(defonce ^:private dev-keystore
  (delay
    (let [dir (doto (File/createTempFile "mcp2026-dev" "") (.delete) (.mkdirs))
          f (File. dir "dev.p12")
          keytool (str (System/getProperty "java.home") "/bin/keytool")
          p (-> (ProcessBuilder. ^java.util.List
                 [keytool "-genkeypair" "-alias" "dev" "-keyalg" "RSA" "-keysize" "2048" "-validity" "3"
                  "-dname" "CN=127.0.0.1" "-ext" "SAN=ip:127.0.0.1,dns:localhost"
                  "-keystore" (str f) "-storetype" "PKCS12" "-storepass" "dev-only" "-keypass" "dev-only"])
                (.redirectErrorStream true) (.start))]
      (slurp (.getInputStream p))
      (when-not (zero? (.waitFor p)) (throw (ex-info "keytool failed" {})))
      (let [ks (doto (KeyStore/getInstance "PKCS12")
                 (.load (java.io.FileInputStream. f) (.toCharArray "dev-only")))]
        (.delete f) (.delete dir)
        ks))))

(defn- server-ssl []
  (let [ks @dev-keystore
        kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init ks (.toCharArray "dev-only")))]
    {:ctx (doto (SSLContext/getInstance "TLS") (.init (.getKeyManagers kmf) nil nil))
     :cert (.getCertificate ks "dev")}))

(defn serve!
  "Serve handler on host:port (port 0 = random). opts {:host \"127.0.0.1\" :port 0 :tls? false}.
  :tls? uses a self-signed 127.0.0.1 cert (dev/tests) that this process's clients trust.
  Returns {:url \"http(s)://127.0.0.1:<port>\" :port :stop (fn [])}."
  ([handler] (serve! handler {}))
  ([handler {:keys [host port tls?] :or {host "127.0.0.1" port 0}}]
   (let [addr (InetSocketAddress. ^String host (int port))
         srv (if tls?
               (let [{:keys [ctx cert]} (server-ssl)]
                 (trust-dev-cert! cert)
                 (doto (HttpsServer/create addr 0) (.setHttpsConfigurator (HttpsConfigurator. ctx))))
               (HttpServer/create addr 0))]
     (.createContext srv "/"
                     (reify HttpHandler
                       (handle [_ ex]
                         (try
                           (send! ex (handler (exchange->req ex)))
                           (catch Throwable t
                             (try (send! ex {:status 500 :body (str "internal error: " (.getMessage t))})
                                  (catch Throwable _ (.close ex))))))))
     (.setExecutor srv (daemon-pool))
     (.start srv)
     (let [p (.getPort (.getAddress srv))]
       {:url (str (if tls? "https" "http") "://" host ":" p)
        :port p
        :stop (fn [] (.stop srv 0))}))))

(defn open-browser
  "Open url in the system browser."
  [url]
  (let [os (str/lower-case (System/getProperty "os.name"))
        cmd (cond (str/includes? os "mac") ["open" url]
                  (str/includes? os "win") ["rundll32" "url.dll,FileProtocolHandler" url]
                  :else ["xdg-open" url])]
    (.start (ProcessBuilder. ^java.util.List cmd))
    nil))
