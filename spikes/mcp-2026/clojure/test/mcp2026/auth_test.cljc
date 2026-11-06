(ns mcp2026.auth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mcp2026.auth :as auth]
            [mcp2026.checks :as checks]
            [mcp2026.core :as mcp]
            [mcp2026.devauth :as devauth]
            [mcp2026.env :as env :refer [with-cleanup]]
            [mcp2026.wire :as w]))

(deftest existing-steps-unprotected
  (let [s (mcp/serve! (mcp/fixture-server))]
    (try (is (checks/run-checks (str (:url s) "/mcp"))) (finally ((:stop s))))))

(deftest existing-steps-protected-auto-mode
  (with-cleanup
    (let [e (env/new-env)]
      (is (checks/run-checks (:url e) #(env/auto-client e))))))

(deftest pause-resume
  (with-cleanup
    (let [e (env/new-env) c (env/pause-client e)
          p (env/pending! #(mcp/call-tool c "echo" {"text" "after login"}))]
      (is (= "authorization" (:kind p)))
      (is (seq (:id p)))
      (is (str/starts-with? (:url p) (str (:issuer (:as e)) "/authorize?")))
      (is (seq (:prompt p)))
      (let [cb (env/visit (:url p))]
        (is (= (:url e) (env/last-q e "resource")))
        (is (= "S256" (env/last-q e "code_challenge_method")))
        (is (= "mcp:read" (env/last-q e "scope")))
        (is (= "after login" (mcp/text (auth/resume (:auth c) (:id p) cb))))
        (is (env/err-msg #(auth/resume (:auth c) (:id p) cb)) "second resume of the same id must fail")
        (is (= "again" (mcp/text (mcp/call-tool c "echo" {"text" "again"}))))
        (is (= 1 (:authorizations (env/stats e))))))))

(deftest auto-mode-whoami-principal
  (with-cleanup
    (let [e (env/new-env)]
      (is (= "dev-user mcp:read" (mcp/text (mcp/call-tool (env/auto-client e) "whoami" {})))))))

(deftest iss-mismatch-rejected
  (with-cleanup
    (let [e (env/new-env)
          _ (swap! (:cfg (:as e)) assoc :iss-override "https://evil.example")
          c (env/auto-client e)]
      (is (str/includes? (str (env/err-msg #(mcp/call-tool c "echo" {"text" "x"}))) "iss mismatch"))
      (is (nil? (env/tok c (:url e))) "no token may be stored"))))

(deftest state-mismatch-rejected
  (with-cleanup
    (let [e (env/new-env) c (env/pause-client e)
          p (env/pending! #(mcp/call-tool c "echo" {"text" "x"}))
          cb (str/replace (env/visit (:url p)) #"state=[^&]*" "state=forged")]
      (is (str/includes? (str (env/err-msg #(auth/resume (:auth c) (:id p) cb))) "state mismatch")))))

(deftest pkce-verified-by-as
  (with-cleanup
    (let [e (env/new-env) c (env/pause-client e)
          p (env/pending! #(mcp/call-tool c "echo" {"text" "x"}))
          cb (env/visit (:url p))]
      (auth/update-flow! (:auth c) (:id p) assoc :verifier "not-the-verifier") ; the AS must reject the exchange
      (is (str/includes? (str (env/err-msg #(auth/resume (:auth c) (:id p) cb))) "invalid_grant")))))

(defn- expire! [c url] (env/save-tok! c url (assoc (env/tok c url) :expiry (- (w/now-ms) 60000))))

(deftest refresh-on-expiry
  (with-cleanup
    (let [e (env/new-env) c (env/auto-client e)]
      (mcp/call-tool c "echo" {"text" "1"})
      (let [old (:access-token (env/tok c (:url e)))]
        (expire! c (:url e))
        (is (= "2" (mcp/text (mcp/call-tool c "echo" {"text" "2"}))))
        (is (= [1 1] ((juxt :refreshes :authorizations) (env/stats e))))
        (is (not= old (:access-token (env/tok c (:url e)))))))))

(deftest insufficient-scope-step-up
  (with-cleanup
    (let [e (env/new-env) c (env/auto-client e)]
      (mcp/call-tool c "echo" {"text" "1"})
      (is (= "Hello, scoped!" (mcp/text (mcp/call-tool c "greet" {} (fn [_ _] {"action" "accept" "content" {"name" "scoped"}})))))
      (is (= 2 (:authorizations (env/stats e))))
      (is (= #{"mcp:read" "mcp:greet"} (set (str/split (env/last-q e "scope") #" "))) "step-up scope = union")
      (mcp/call-tool c "echo" {"text" "still"})
      (is (= 2 (:authorizations (env/stats e))) "previous scope kept"))))

(deftest prm-well-known-fallback-and-no-client-identity
  (with-cleanup
    (let [e (env/new-env)]
      (is (= (:url e) (get (auth/fetch-prm (auth/oauth {}) (:url e) "") "resource")))
      (let [c (mcp/client (:url e) {:auth (auth/oauth {:authorizer (auth/pause-authorizer "http://127.0.0.1:9/callback")})})]
        (is (str/includes? (str (env/err-msg #(mcp/call c "tools/list"))) (str "register an app with " (:issuer (:as e)))))))))

(deftest token-is-redacted
  (let [t (auth/map->Token {:access-token "secret-at" :issuer "https://i"})]
    (is (not (str/includes? (str t) "secret-at")))
    (is (not (str/includes? (pr-str t) "secret-at")))))

;; --- server-side ---

(defn- raw-call [e token tool]
  (let [r (w/request {:method "POST" :url (:url e)
                      :headers (cond-> {"Content-Type" "application/json" "MCP-Protocol-Version" mcp/protocol-version
                                        "Mcp-Method" "tools/call" "Mcp-Name" tool}
                                 token (assoc "Authorization" (str "Bearer " token)))
                      :body (w/write-json {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                                           "params" {"name" tool "arguments" {"text" "x"}
                                                     "_meta" {mcp/meta-version mcp/protocol-version}}})})]
    [(:status r) (str (get-in r [:headers "www-authenticate"])) (:body r)]))

(defn- claims [e & [f]]
  (devauth/mint (:as e) ((or f identity) {"iss" (:issuer (:as e)) "sub" "alice" "aud" (:url e) "scope" "mcp:read"
                                          "exp" (+ (quot (w/now-ms) 1000) 3600)})))

(deftest server-jwt-via-jwks-and-principal
  (with-cleanup
    (let [e (env/new-env) [st _ body] (raw-call e (claims e) "whoami")]
      (is (= 200 st))
      (is (str/includes? body "alice mcp:read")))))

(deftest server-rejects
  (with-cleanup
    (let [e (env/new-env)]
      (doseq [[name tok] {"missing" nil
                          "wrong audience" (claims e #(assoc % "aud" "https://other.example/mcp"))
                          "expired" (claims e #(assoc % "exp" (- (quot (w/now-ms) 1000) 60)))
                          "wrong issuer" (claims e #(assoc % "iss" "https://evil.example"))
                          "tampered" (str (claims e) "x")}]
        (let [[st www] (raw-call e tok "echo")]
          (testing name
            (is (= 401 st))
            (is (str/includes? www (str "resource_metadata=\"" (:base e) "/.well-known/oauth-protected-resource/mcp")))
            (when tok (is (str/includes? www "error=\"invalid_token\"")))))))))

(deftest server-per-tool-scope-403
  (with-cleanup
    (let [e (env/new-env)
          [st www] (raw-call e (claims e) "greet")]
      (is (= 403 st))
      (is (str/includes? www "error=\"insufficient_scope\""))
      (is (str/includes? www "scope=\"mcp:read mcp:greet\""))
      (is (= 200 (first (raw-call e (claims e #(assoc % "scope" "mcp:read mcp:greet")) "greet")))))))

(deftest server-custom-verify
  (with-cleanup
    (let [e (env/new-env (fn [s] (assoc-in s [:auth :verify]
                                           (fn [t] (when (= t "opaque-ok") {:subject "svc" :scopes ["mcp:read"]})))))
          [st _ body] (raw-call e "opaque-ok" "whoami")]
      (is (= 200 st))
      (is (str/includes? body "svc mcp:read"))
      (is (= 401 (first (raw-call e (claims e) "whoami"))) "JWT must not bypass a custom verify"))))

(deftest loopback-opener-error-surfaces
  (with-cleanup
    (let [e (env/new-env)
          c (env/auto-client e :authorizer (auth/loopback-authorizer {:open (fn [_] (throw (ex-info "no browser" {})))}))
          f (future (env/err-msg #(mcp/call-tool c "echo" {"text" "x"})))
          r (deref f 5000 ::stuck)]
      (is (not= ::stuck r) "opener error swallowed: client still waiting for a callback")
      (is (str/includes? (str r) "no browser")))))
