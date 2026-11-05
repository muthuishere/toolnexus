;; SPEC §2A.1 / §2A.1a — the MCP OAuth client against an in-process DEV
;; authorization server and the protected fixture server. Hermetic: 127.0.0.1.
(ns toolnexus.mcp-auth-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [toolnexus.mcp :as mcp]
            [toolnexus.mcp-auth :as auth]
            [toolnexus.mcp2026-fixture-test :as fx]))

(defn- run [conn nm args & [ctx]]
  (let [t (first (filter #(= nm (:name %)) (:tools conn)))]
    (if ctx ((:execute t) args ctx) ((:execute t) args))))

(defn- env [f & {:as cfg}]
  (let [as (fx/start-as)
        _  (swap! (:cfg as) merge cfg)
        s  (fx/start-server {:as as})]
    (try (f as s)
         (finally ((:stop s)) (koine.server/stop! (:server as))))))

(defn- server [s auth]
  {:name "fx" :kind "remote" :enabled true :url (:url s) :headers {} :timeout 10000
   :auth (merge {:clientId "test-client" :allowHttp true} auth)})

(defn- secrets-of [as] (concat (keys @(:tokens as)) (keys @(:rts as))))

(deftest primitives
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (auth/hex (auth/sha256 "abc"))))
  (is (= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
         (auth/b64url (auth/sha256 "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))) "RFC 7636 appendix B")
  (is (= {"resource_metadata" "https://x/y" "scope" "a b" "error" "invalid_token"}
         (auth/parse-challenge "Bearer resource_metadata=\"https://x/y\", scope=\"a b\", error=invalid_token")))
  (let [t (auth/token {:access-token "SECRET-AT" :refresh-token "SECRET-RT" :issuer "i"})]
    (is (not (str/includes? (pr-str t) "SECRET")) "a Token's printed form hides its values")
    (is (= "SECRET-AT" (auth/reveal (:access-token t))))))

(deftest auto-mode-login
  (env (fn [as s]
         (let [c (mcp/connect (server s {}) {:open-url (fx/open-url-hook as)})]
           (is (= "connected" (:status c)) (:message c))
           (is (= "{\"text\":\"authed\"}" (:output (run c "fx_echo" {:text "authed"}))))
           (is (= "dev-user" (:output (run c "fx_whoami" {}))) "principal visible to the tool")
           (is (= 1 (:authorizations (:stats-snapshot {:stats-snapshot @(:stats as)}))))
           (let [q (:last-authorize-query @(:stats as))]
             (is (= "S256" (get q "code_challenge_method")))
             (is (= (:url s) (get q "resource")))
             (is (str/starts-with? (get q "redirect_uri") "http://127.0.0.1:")))
           (testing "tokens never appear in the connection's printed form"
             (let [printed (pr-str c)]
               (is (not-any? #(str/includes? printed %) (secrets-of as)))))))))

(deftest pause-mode-at-load
  (env (fn [as s]
         (testing "with wait-for: the §10 authorization request is answered inline"
           (let [seen (atom nil)
                 c (mcp/connect (server s {:mode "pause" :redirectUri "https://app.example/cb"})
                                {:wait-for (fn [req] (reset! seen req)
                                             {:id (:id req) :ok true :data {"callbackUrl" (fx/approve! as (:url req))}})})]
             (is (= "connected" (:status c)) (:message c))
             (is (= "authorization" (:kind @seen)))
             (is (str/starts-with? (:id @seen) "auth-"))
             (is (= (str "Authorize access to " (:url s)) (:prompt @seen)))
             (is (some? (:expiresAt @seen)))))
         (testing "without wait-for: the server is failed with `authorization pending`"
           (let [c (mcp/connect (server s {:mode "pause" :redirectUri "https://app.example/cb"}))]
             (is (= "failed" (:status c)))
             (is (str/ends-with? (:message c) "authorization pending"))))
         (testing "a declined answer"
           (let [c (mcp/connect (server s {:mode "pause" :redirectUri "https://app.example/cb"})
                                {:wait-for (fn [req] {:id (:id req) :ok false :reason "declined"})})]
             (is (str/ends-with? (:message c) "authorization denied: declined")))))))

(deftest step-up-pauses-a-tool-call-then-resumes-once
  (env (fn [as s]
         (let [store (auth/memory-store)
               cfg   (server s {:mode "pause" :redirectUri "https://app.example/cb"})
               answer (fn [req] {:id (:id req) :ok true :data {:callbackUrl (fx/approve! as (:url req))}})
               ;; log in once (mcp:read) with a wait-for, sharing the store
               _  (mcp/connect cfg {:wait-for answer :token-store store})
               c  (mcp/connect cfg {:token-store store})]
           (is (= "connected" (:status c)) "a stored token is reused after the issuer is learned")
           (let [p   (run c "fx_greet" {})
                 req (get-in p [:metadata :pending])]
             (is (= "authorization" (:kind req)) "403 insufficient_scope pauses as §10 pending")
             (let [a (answer req)]
               (is (= "mcp:read mcp:greet" (get (:last-authorize-query @(:stats as)) "scope"))
                   "step-up asks for the union of scopes")
             (let [p2 (run c "fx_greet" {} {:answer a})]
               ;; resumed: the paused round is sent; greet then asks for input
               (is (= "input" (get-in p2 [:metadata :pending :kind])))
               (let [r (run c "fx_greet" {} {:answer {:id (get-in p2 [:metadata :pending :id]) :ok true
                                                      :data {:name "toolnexus"}}})]
                 (is (str/includes? (:output r) "Hello, toolnexus!")))))
             (is (= 1 (:executions @(:state s)))))
           (testing "unknown pending id"
             (is (thrown-with-msg? Throwable #"unknown pending authorization \"auth-nope\""
                                   (auth/resume! (auth/engine {} {}) "auth-nope" "https://x/cb"))))))))

(deftest callback-validation-errors
  (doseq [[knob msg] [[{:iss-override "https://evil.example"} "authorization response iss mismatch (possible mix-up attack)"]
                      [{:omit-iss true} "authorization response missing iss"]
                      [{:bad-state true} "authorization response state mismatch"]
                      [{:no-s256 true} "authorization server does not advertise PKCE S256; refusing"]]]
    (env (fn [as s]
           (swap! (:cfg as) merge knob)
           (let [c (mcp/connect (server s {}) {:open-url (fx/open-url-hook as)})]
             (is (= "failed" (:status c)))
             (is (str/includes? (str (:message c)) msg) (str (:message c)))
             (is (empty? @(:tokens as)) "nothing issued / stored"))))))

(deftest no-client-identity
  (env (fn [as s]
         (swap! (:cfg as) assoc :no-cimd true)
         (let [c (mcp/connect (assoc (server s {}) :auth {:allowHttp true}) {:open-url (fx/open-url-hook as)})]
           (is (str/includes? (:message c) (str "no client identity for " (:issuer as))))
           (is (str/includes? (:message c) "register an app with"))
           (is (str/ends-with? (:message c) (str "register an app with " (:issuer as) " and set auth.clientId (dynamic client registration is not supported)")))))))

(deftest token-response-variants
  (testing "form-encoded token response"
    (env (fn [as s]
           (swap! (:cfg as) assoc :form-token true)
           (is (= "connected" (:status (mcp/connect (server s {}) {:open-url (fx/open-url-hook as)})))))))
  (testing "a token-endpoint error never carries the body"
    (env (fn [as s]
           (let [c (mcp/connect (server s {:clientId "unknown-client"}) {:open-url (fx/open-url-hook as)})]
             (is (= "failed" (:status c))))))))

(deftest refresh-is-single-flight
  (env (fn [as s]
         (swap! (:cfg as) assoc :access-ttl-s 1) ; inside the 10 s skew: expired at once
         (let [c (mcp/connect (server s {}) {:open-url (fx/open-url-hook as)})]
           (is (= "connected" (:status c)))
           (swap! (:cfg as) assoc :access-ttl-s 3600)
           (let [before (:refreshes @(:stats as))
                 rs (mapv (fn [i] (future (:output (run c "fx_echo" {:text (str i)})))) (range 30))
                 outs (mapv deref rs)]
             (is (every? #(str/starts-with? % "{\"text\"") outs))
             (is (= 1 (- (:refreshes @(:stats as)) before)) "one refresh for 30 concurrent callers")
             (is (some? (get (:last-token-form @(:stats as)) "resource")) "resource sent on refresh"))))))

(deftest omit-refresh-resource
  (env (fn [as s]
         (swap! (:cfg as) assoc :access-ttl-s 1 :reject-refresh-resource true)
         (let [c (mcp/connect (server s {:omitRefreshResource true}) {:open-url (fx/open-url-hook as)})]
           (swap! (:cfg as) assoc :access-ttl-s 3600)
           (is (= "{\"text\":\"ok\"}" (:output (run c "fx_echo" {:text "ok"}))))
           (is (nil? (get (:last-token-form @(:stats as)) "resource")))))))
