;; SPEC §7C.2 — the 2026-07-28 server half, mirroring golang/mcphandler2026_test.go:
;; the default RS256/JWKS verifier against the test-only dev authorization
;; server, the dev AS's code + PKCE flow (pre-registered and CIMD clients) and
;; refresh, onCall/prompts/discover on the 2026 wire, and load-mcp-skills.
;; Everything is loopback; the handler is mounted on a real koine.server.
(ns toolnexus.mcp-handler2026-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [koine.codec :as codec]
            [koine.http :as http]
            [koine.json :as json]
            [koine.server :as server]
            [koine.time :as ktime]
            [toolnexus.mcp-crypto :as crypto]
            [toolnexus.mcp-dev-auth-test-support :as da]
            [toolnexus.mcp-handler :as mh]
            [toolnexus.mcp-server-auth :as sa]
            [toolnexus.shared-examples-test :as te]
            [toolnexus.tool :as tool]))

(def ^:private v-key (keyword "io.modelcontextprotocol/protocolVersion"))

(defn- post26
  "One 2026-07-28 request (headers + _meta stamped) -> {:status :www :json}."
  [url token method params]
  (let [params (assoc-in (or params {}) [:_meta v-key] "2026-07-28")
        res    (http/request {:method :post :url url
                              :headers (cond-> {"content-type" "application/json"
                                                "mcp-protocol-version" "2026-07-28"
                                                "mcp-method" method}
                                         (string? (:name params)) (assoc "mcp-name" (:name params))
                                         (seq token) (assoc "authorization" (str "Bearer " token)))
                              :body (json/write-str {:jsonrpc "2.0" :id 1 :method method :params params})})]
    {:status (:status res)
     :www    (or (http/header res "www-authenticate") "")
     :json   (try (json/read-str (:body res)) (catch Throwable _ nil))}))

(def ^:private who-tool
  (tool/tool {:name "who" :description "who" :source "native" :input-schema {:type "object"} :context? true
              :execute (fn [_ ctx] (tool/success (str (:subject (mh/mcp-identity ctx)))))}))

(defn- mount!
  "Serve `handler` at /mcp and `metadata` elsewhere on a loopback koine server."
  ([handler] (mount! handler nil))
  ([handler metadata]
   (server/serve (fn [req]
                   (if (= "/mcp" (:path req))
                     (handler req)
                     (if metadata (metadata (assoc req :uri (:path req))) {:status 404 :body "nf"})))
                 {:port 0 :host "127.0.0.1"})))

(defn- text-of [r] (get-in r [:json :result :content 0 :text]))

(deftest default-jwt-verifier-with-dev-as
  (let [as   (da/start-mcp-dev-auth-server)]
    (try
      (let [srv-a (atom nil)
            base  (atom "")
            auth  (atom nil)
            srv   (mount! (fn [req] ((:handler (mh/mcp-handler (tool/toolkit [who-tool])
                                                              {:protocol-versions ["2026-07-28"]
                                                               :authenticate (:authenticate @auth)}))
                                     req))
                          (fn [req] ((:metadata @auth) req)))]
        (reset! srv-a srv)
        (reset! base (str "http://127.0.0.1:" (server/port srv)))
        (try
          (let [u    (str @base "/mcp")
                _    (reset! auth (sa/mcp-server-auth {:issuer (:issuer as) :audience u
                                                       :scopes {"*" ["mcp"] "who" ["mcp:who"]}}))
                now  (quot (ktime/now-ms) 1000)
                mint (fn [over] ((:mint as) (merge {"iss" (:issuer as) "sub" "dev-user" "aud" u
                                                    "scope" "mcp mcp:who" "exp" (+ now 60)}
                                                   over)))
                ok   (post26 u (mint {}) "tools/call" {:name "who" :arguments {}})]
            (is (= 200 (:status ok)) (pr-str ok))
            (is (= "dev-user" (text-of ok)))
            (let [good (mint {})]
              (doseq [[label tok] {"wrong audience" (mint {"aud" "https://other.example/mcp"})
                                   "aud array miss" (mint {"aud" ["x"]})
                                   "expired"        (mint {"exp" (- now 1)})
                                   "wrong issuer"   (mint {"iss" "https://evil.example"})
                                   "tampered"       (str (subs good 0 20) (if (= \x (nth good 20)) "y" "x") (subs good 21))
                                   "not a jwt"      "opaque"}]
                (let [r (post26 u tok "server/discover" nil)]
                  (is (= 401 (:status r)) label)
                  (is (str/includes? (:www r) "error=\"invalid_token\"") label)
                  (is (not (str/includes? (:www r) tok)) (str label ": token never echoed")))))
            (let [r (post26 u (mint {"scope" "mcp"}) "tools/call" {:name "who" :arguments {}})]
              (is (= 403 (:status r)))
              (is (= (str "Bearer resource_metadata=\"" @base "/.well-known/oauth-protected-resource/mcp\", "
                          "scope=\"mcp mcp:who\", error=\"insufficient_scope\"")
                     (:www r))))
            (let [r (post26 u "" "server/discover" nil)]
              (is (= 401 (:status r)))
              (is (= (str "Bearer resource_metadata=\"" @base "/.well-known/oauth-protected-resource/mcp\", scope=\"mcp\"")
                     (:www r))))
            (let [prm (json/read-str (:body (http/request {:method :get :url (str @base "/.well-known/oauth-protected-resource/mcp")}))
                                     {:key-fn str})]
              (is (= {"resource" u "authorization_servers" [(:issuer as)] "scopes_supported" ["mcp"]
                      "bearer_methods_supported" ["header"]}
                     prm))))
          (finally (server/stop! srv))))
      (finally ((:close as))))))

(deftest derived-audience-and-metadata-url
  ;; no :audience ⇒ <scheme>://<Host><resource-path> from the Ring request
  (let [a   (sa/mcp-server-auth {:issuer "https://as.example" :resource-path "/api/mcp"})
        req {:request-method :get :scheme :https :uri "/.well-known/oauth-protected-resource/api/mcp"
             :headers {"host" "mcp.example:8443"}}]
    (is (= "https://mcp.example:8443/api/mcp" (sa/audience a req)))
    (is (= "https://mcp.example:8443/.well-known/oauth-protected-resource/api/mcp" ((:metadata-url a) req)))
    (is (= "https://mcp.example:8443/api/mcp" (get (json/read-str (:body ((:metadata a) req)) {:key-fn str}) "resource")))
    (is (= 404 (:status ((:metadata a) (assoc req :uri "/elsewhere")))))
    (is (= "https://x.example/.well-known/oauth-protected-resource/mcp"
           ((:metadata-url (sa/mcp-server-auth {:issuer "i" :audience "https://x.example/mcp"})) req)))))

(defn- authorize!
  "Drive /authorize headlessly (POST form + Accept JSON, see mcp-dev-auth) ->
  the callback query as a map, or the raw response when not 200."
  [as q]
  (let [res (http/request {:method :post :url (str (:issuer as) "/authorize")
                           :headers {"content-type" "application/x-www-form-urlencoded" "accept" "application/json"}
                           :body (crypto/form-encode q)})]
    (if (= 200 (:status res))
      (let [to (get (json/read-str (:body res) {:key-fn str}) "redirect_to")]
        (crypto/parse-query (second (str/split to #"\?" 2))))
      res)))

(defn- pkce-login [as client-id redirect resource scope]
  (let [verifier (str "v-" (apply str (repeat 50 "a")))
        cb (authorize! as {"response_type" "code" "client_id" client-id "redirect_uri" redirect "state" "s1"
                           "code_challenge" (crypto/b64url (crypto/sha256 verifier))
                           "code_challenge_method" "S256" "resource" resource "scope" scope})]
    (is (= (:issuer as) (get cb "iss")))
    (is (= "s1" (get cb "state")))
    (is (seq (get cb "code")))
    (let [tr (http/request {:method :post :url (str (:issuer as) "/token")
                            :headers {"content-type" "application/x-www-form-urlencoded"}
                            :body (crypto/form-encode {"grant_type" "authorization_code" "code" (get cb "code")
                                                       "code_verifier" verifier "client_id" client-id
                                                       "redirect_uri" redirect "resource" resource})})]
      (is (= 200 (:status tr)) (:body tr))
      (json/read-str (:body tr) {:key-fn str}))))

(deftest dev-auth-server-pre-registered-and-cimd
  (let [doc-url (atom "")
        cimd    (server/serve (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                                       :body (json/write-str {"client_id" @doc-url "client_name" "CIMD app"
                                                              "redirect_uris" ["http://127.0.0.1/cb"]})})
                              {:port 0 :host "127.0.0.1"})
        _       (reset! doc-url (str "http://127.0.0.1:" (server/port cimd) "/client.json"))
        as      (da/start-mcp-dev-auth-server {:clients {"app" ["http://127.0.0.1/cb"]}})
        u-atom  (atom "")
        auth    (atom nil)
        srv     (mount! (fn [req] ((:handler (mh/mcp-handler (tool/toolkit [who-tool])
                                                            {:protocol-versions ["2026-07-28"]
                                                             :authenticate (:authenticate @auth)}))
                                   req)))]
    (try
      (reset! u-atom (str "http://127.0.0.1:" (server/port srv) "/mcp"))
      (reset! auth (sa/mcp-server-auth {:issuer (:issuer as) :audience @u-atom :scopes {"*" ["mcp"]}}))
      (let [md (json/read-str (:body (http/request {:method :get :url (str (:issuer as) "/.well-known/oauth-authorization-server")}))
                              {:key-fn str})]
        (is (= (:issuer as) (get md "issuer")))
        (is (= ["S256"] (get md "code_challenge_methods_supported")))
        (is (true? (get md "client_id_metadata_document_supported")))
        (is (true? (get md "authorization_response_iss_parameter_supported"))))
      (doseq [cid ["app" @doc-url]]
        (testing cid
          (let [tok (pkce-login as cid "http://127.0.0.1:5555/cb" @u-atom "mcp")
                r   (post26 @u-atom (get tok "access_token") "tools/call" {:name "who" :arguments {}})]
            (is (= 200 (:status r)) (pr-str r))
            (is (= "dev-user" (text-of r)))
            ;; refresh rotates and keeps working; the old refresh token is spent
            (let [refresh (fn [rt] (http/request {:method :post :url (str (:issuer as) "/token")
                                                  :headers {"content-type" "application/x-www-form-urlencoded"}
                                                  :body (crypto/form-encode {"grant_type" "refresh_token" "refresh_token" rt
                                                                             "client_id" cid "resource" @u-atom})}))
                  rr (refresh (get tok "refresh_token"))]
              (is (= 200 (:status rr)))
              (is (= 200 (:status (post26 @u-atom (get (json/read-str (:body rr) {:key-fn str}) "access_token")
                                          "tools/call" {:name "who" :arguments {}}))))
              (is (= 400 (:status (refresh (get tok "refresh_token")))))))))
      ;; unknown client / unregistered redirect never redirects
      (is (= 400 (:status (authorize! as {"client_id" "nobody" "redirect_uri" "http://127.0.0.1/cb"}))))
      (is (= 400 (:status (authorize! as {"client_id" "app" "redirect_uri" "http://evil.example/cb"}))))
      (is (= 4 ((:authorizations as))))
      ;; missing resource ⇒ invalid_target back on the redirect
      (is (= "invalid_target" (get (authorize! as {"response_type" "code" "client_id" "app" "redirect_uri" "http://127.0.0.1/cb"
                                                   "code_challenge" "x" "code_challenge_method" "S256"})
                                   "error")))
      ;; GET /authorize answers the page that re-POSTs its own query
      (is (str/includes? (:body (http/request {:method :get :url (str (:issuer as) "/authorize?client_id=app")})) "location.search"))
      (finally ((:close as)) (server/stop! srv) (server/stop! cimd)))))

(deftest dev-auth-server-confidential-client
  (let [as (da/start-mcp-dev-auth-server {:clients {"conf" ["http://127.0.0.1/cb"]} :secrets {"conf" "s3cret"}
                                          :reject-refresh-resource true})]
    (try
      (let [verifier "verifier-0123456789-0123456789-0123456789"
            go (fn [extra headers]
                 (let [cb (authorize! as {"response_type" "code" "client_id" "conf" "redirect_uri" "http://127.0.0.1/cb"
                                          "code_challenge" (crypto/b64url (crypto/sha256 verifier))
                                          "code_challenge_method" "S256" "resource" "https://r.example/mcp"})]
                   (http/request {:method :post :url (str (:issuer as) "/token")
                                  :headers (merge {"content-type" "application/x-www-form-urlencoded"} headers)
                                  :body (crypto/form-encode (merge {"grant_type" "authorization_code" "code" (get cb "code")
                                                                    "code_verifier" verifier "redirect_uri" "http://127.0.0.1/cb"
                                                                    "resource" "https://r.example/mcp"}
                                                                   extra))})))]
        (is (= 401 (:status (go {"client_id" "conf"} {}))))
        (is (= 200 (:status (go {"client_id" "conf" "client_secret" "s3cret"} {}))))
        (is (= 401 (:status (go {} {"authorization" (str "Basic " (codec/encode "conf:wrong"))}))))
        (let [basic (str "Basic " (codec/encode "conf:s3cret"))
              r     (go {} {"authorization" basic})
              rt    (get (json/read-str (:body r) {:key-fn str}) "refresh_token")]
          (is (= 200 (:status r)))
          (is (= "invalid_target"
                 (get (json/read-str (:body (http/request {:method :post :url (str (:issuer as) "/token")
                                                           :headers {"content-type" "application/x-www-form-urlencoded"
                                                                     "authorization" basic}
                                                           :body (crypto/form-encode {"grant_type" "refresh_token" "refresh_token" rt
                                                                                      "resource" "https://r.example/mcp"})}))
                                     {:key-fn str})
                      "error")))))
      (finally ((:close as))))))

(deftest on-call-prompts-discover-2026
  (let [events (atom [])
        h      (mh/mcp-handler (tool/toolkit [who-tool])
                               {:protocol-versions ["2026-07-28"]
                                :on-call (fn [ev] (swap! events conj ev) (throw (ex-info "isolated" {})))
                                :list-prompts (fn [_] [{:name "p" :description "d"}])
                                :get-prompt (fn [_ nm _] {:messages [{:role "user" :text (str "hi " nm)}]})})
        srv    (mount! (:handler h))
        u      (str "http://127.0.0.1:" (server/port srv) "/mcp")]
    (try
      (is (= 200 (:status (post26 u "" "tools/call" {:name "who" :arguments {}}))))
      (is (= 1 (count @events)))
      (is (= "who" (:name (first @events))))
      (is (= :allow (:decision (first @events))))
      (let [caps (get-in (post26 u "" "server/discover" nil) [:json :result :capabilities])]
        (is (= {} (:prompts caps)))
        (is (not (contains? caps :extensions)))
        (is (not (contains? caps :resources))))
      (let [g (:json (post26 u "" "prompts/get" {:name "p"}))]
        (is (= "hi p" (get-in g [:result :messages 0 :content :text])))
        (is (= "complete" (get-in g [:result :resultType]))))
      (is (= "p" (get-in (post26 u "" "prompts/list" nil) [:json :result :prompts 0 :name])))
      (finally (server/stop! srv)))))

(deftest load-mcp-skills-examples
  (let [skills (mh/load-mcp-skills (str (te/examples-dir) "/skills"))]
    (is (= 1 (count skills)))
    (is (= "hello-world" (:name (first skills))))
    (is (= ["SKILL.md" "scripts/greet.sh"] (mapv :path (:files (first skills)))))
    (is (= "hello-world" (get-in (first skills) [:frontmatter :name])))))

(deftest skill-uri-containment
  (doseq [[uri why] {"file:///etc/passwd"           "not a skill:// URI"
                     "skill://a%2"                  "bad percent-encoding"
                     "skill://a/%252e%252e/x"       "skill URI has encoded, backslash or NUL characters"
                     "skill://a\\b/c"               "skill URI has encoded, backslash or NUL characters"
                     "skill://onlyname"             "skill URI needs skill://<name>/<path>"
                     "skill://a/../b"               "skill URI path escapes the skill (empty, '.', '..' or ':' segment)"
                     "skill://a/%2e%2e/b"           "skill URI path escapes the skill (empty, '.', '..' or ':' segment)"
                     "skill://a//b"                 "skill URI path escapes the skill (empty, '.', '..' or ':' segment)"
                     "skill://a/c:d"                "skill URI path escapes the skill (empty, '.', '..' or ':' segment)"}]
    (is (= why (try (mh/parse-skill-uri uri) nil (catch Throwable e (ex-message e)))) uri))
  (is (= ["hello-world" "scripts/greet.sh"] (mh/parse-skill-uri "skill://hello-world/scripts/greet.sh"))))

(deftest rs256-primitives
  ;; RFC 4231-free sanity: SHA-256 known answer, sign/verify round trip, tamper
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (crypto/hex (crypto/sha256 "abc"))))
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" (crypto/hex (crypto/sha256 ""))))
  (let [k (crypto/rsa-generate 1024)
        s (crypto/rsa-sign k "payload")]
    (is (crypto/rsa-verify? k "payload" s))
    (is (not (crypto/rsa-verify? k "payloaD" s)))
    (is (not (crypto/rsa-verify? (crypto/jwk->public (crypto/rsa-jwk (crypto/rsa-generate 1024))) "payload" s)))))
