(ns mcp2026.keycloak-test
  "Live tests against a real Keycloak (../golang/keycloak/docker-compose.yml). Skipped unless
  KEYCLOAK=1, so the suite stays hermetic. The MCP server runs on the fixed 127.0.0.1:7199 because
  the realm's audience mapper pins aud = http://127.0.0.1:7199/mcp."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mcp2026.auth :as auth]
            [mcp2026.checks :as checks]
            [mcp2026.core :as mcp]
            [mcp2026.env :as env]
            [mcp2026.wire :as w]))

(load-file "examples/09-keycloak/headless.cljc") ; the same headless browser example 09 uses

(def kc-issuer "http://localhost:8180/realms/toolnexus")
(def kc-url "http://127.0.0.1:7199/mcp")

(defn- on? [] (= "1" (System/getenv "KEYCLOAK")))

(defn- kc-server [audience port]
  (mcp/serve! (-> (mcp/fixture-server)
                  env/principal-tool
                  (assoc :auth {:issuer kc-issuer :audience audience
                                :scopes {"*" ["mcp:tools"] "greet" ["mcp:greet"]}}))
              {:port port}))

(defn- kc-env []
  (let [reqs (atom [])
        stats (atom {:forms 0 :consents 0})
        callbacks (atom [])
        b (example.keycloak.headless/browser)
        login (fn [u] (let [cb (example.keycloak.headless/login b "alice" "alice-test-pw" stats u)]
                        (swap! callbacks conj cb) cb))]
    {:reqs reqs :stats stats :callbacks callbacks :login login :store (auth/memory-store)
     ;; records URL + status + form of every OAuth request (never headers)
     :http (fn [req] (let [r (w/request req)]
                       (swap! reqs conj {:line (str (:method req) " " (:url req) " -> " (:status r))
                                         :form (when (= "POST" (:method req)) (w/parse-query (:body req)))})
                       r))}))

(defn- kc-client [e & {:as extra}]
  (mcp/client kc-url {:auth (auth/oauth (merge {:http (:http e) :allow-http true :client-ids {kc-issuer "toolnexus-mcp"}
                                                :store (:store e)
                                                :authorizer (auth/loopback-authorizer
                                                             {:open (fn [u] (w/request {:method "GET" :url ((:login e) u)}))})}
                                               extra))}))

(defn- token-forms [e] (keep #(when (str/includes? (:line %) "/protocol/openid-connect/token") (:form %)) @(:reqs e)))

(defn- claims [t] (w/read-json (w/bytes->str (w/b64url-decode (second (str/split (:access-token t) #"\."))))))

(defmacro kc-deftest [name & body]
  `(deftest ~name
     (if-not (on?)
       (println ~(str "SKIP " name ": set KEYCLOAK=1 with keycloak/docker-compose.yml running"))
       (let [srv# (kc-server kc-url 7199)]
         (try ~@body (finally ((:stop srv#))))))))

(kc-deftest keycloak-end-to-end
  (let [e (kc-env) c (kc-client e)
        r (mcp/text (mcp/call-tool c "whoami" {}))]
    (println "whoami:" r)
    (is (str/includes? r "mcp:tools"))
    (is (some #(str/includes? (:line %) "/.well-known/oauth-authorization-server/realms/toolnexus -> 200") @(:reqs e))
        "RFC 8414 path-insert metadata answered")
    (is (= kc-issuer (w/q1 (w/parse-query (:query (w/parse-url (first @(:callbacks e))))) "iss")) "RFC 9207 iss")
    (let [[f :as tf] (token-forms e)]
      (is (= 1 (count tf)))
      (is (= kc-url (w/q1 f "resource")))
      (is (seq (w/q1 f "code_verifier"))))
    (let [cl (claims (auth/load-token (:store e) kc-url))]
      (println "aud=" (get cl "aud") "scope=" (get cl "scope") "azp=" (get cl "azp")))))

(kc-deftest keycloak-eight-steps
  (let [e (kc-env)]
    (is (checks/run-checks kc-url #(kc-client e)))
    (println "login forms submitted:" (:forms @(:stats e)) "callbacks:" (count @(:callbacks e)))))

(kc-deftest keycloak-refresh
  (doseq [omit [false true]]
    (testing (str "omit-refresh-resource=" omit)
      (let [e (kc-env) c (kc-client e :omit-refresh-resource omit)]
        (mcp/call-tool c "echo" {"text" "a"})
        (let [old (auth/load-token (:store e) kc-url)]
          (auth/save-token! (:store e) kc-url (assoc old :expiry (- (w/now-ms) 60000)))
          (is (= "b" (mcp/text (mcp/call-tool c "echo" {"text" "b"}))))
          (let [last-form (last (token-forms e))]
            (is (= "refresh_token" (w/q1 last-form "grant_type")))
            (is (= (not omit) (contains? last-form "resource"))))
          (is (not= (:access-token old) (:access-token (auth/load-token (:store e) kc-url))))
          (is (= 1 (:forms @(:stats e))) "no second login"))))))

(kc-deftest keycloak-step-up
  (let [e (kc-env) c (kc-client e)]
    (mcp/call-tool c "echo" {"text" "a"})
    (is (= "Hello, Alice!" (mcp/text (mcp/call-tool c "greet" {} (fn [_ _] {"action" "accept" "content" {"name" "Alice"}})))))
    (is (= 2 (count @(:callbacks e))) "login + step-up")
    (is (some #{"mcp:greet"} (:scopes (auth/load-token (:store e) kc-url))))
    (println "step-up login forms submitted:" (:forms @(:stats e)) "(SSO session reused when 1)")))

(kc-deftest keycloak-pause-resume
  (let [e (kc-env)
        c (kc-client e :authorizer (auth/pause-authorizer "http://127.0.0.1:9/callback"))
        p (env/pending! #(mcp/call-tool c "echo" {"text" "after login"}))]
    (is (= "after login" (mcp/text (auth/resume (:auth c) (:id p) ((:login e) (:url p))))))))

(kc-deftest keycloak-wrong-audience-rejected
  (let [e (kc-env) c (kc-client e)]
    (mcp/call-tool c "echo" {"text" "a"})
    (let [t (:access-token (auth/load-token (:store e) kc-url))
          other (kc-server "http://127.0.0.1:7200/mcp" 7200) ; its canonical URI is NOT in the token's aud
          call (fn [u] (w/request {:method "POST" :url u
                                   :headers {"Content-Type" "application/json" "MCP-Protocol-Version" mcp/protocol-version
                                             "Mcp-Method" "tools/list" "Authorization" (str "Bearer " t)}
                                   :body (w/write-json {"jsonrpc" "2.0" "id" 1 "method" "tools/list"
                                                        "params" {"_meta" {mcp/meta-version mcp/protocol-version}}})}))]
      (try
        (let [r (call "http://127.0.0.1:7200/mcp")]
          (is (= 401 (:status r)))
          (is (str/includes? (str (get-in r [:headers "www-authenticate"])) "error=\"invalid_token\"")))
        (is (= 200 (:status (call kc-url))) "right-aud control")
        (finally ((:stop other)))))))

(kc-deftest keycloak-cimd
  ;; the client_id URL must be fetchable BY KEYCLOAK (in docker: host.docker.internal)
  (let [doc-url "http://host.docker.internal:7198/client.json"
        fetched (atom 0)
        doc (w/serve! (fn [_] (swap! fetched inc)
                        {:status 200 :headers {"Content-Type" "application/json"}
                         :body (w/write-json {"client_id" doc-url "client_name" "toolnexus spike"
                                              "redirect_uris" ["http://127.0.0.1/callback"]
                                              "grant_types" ["authorization_code" "refresh_token"]
                                              "response_types" ["code"] "token_endpoint_auth_method" "none"})})
                      {:host "0.0.0.0" :port 7198})
        e (kc-env)
        c (kc-client e :client-ids nil :client-metadata-url doc-url)]
    (try
      (is (= "cimd" (mcp/text (mcp/call-tool c "echo" {"text" "cimd"}))))
      (println "CIMD token client_id:" (w/q1 (last (token-forms e)) "client_id") "; Keycloak fetched the document" @fetched "time(s); consents accepted:" (:consents @(:stats e)))
      (finally ((:stop doc))))))
