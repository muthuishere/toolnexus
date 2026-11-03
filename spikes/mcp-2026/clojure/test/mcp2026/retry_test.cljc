(ns mcp2026.retry-test
  "Side effects run exactly once across auth pauses, refresh-after-401 and step-up; a lost or
  failed response is never retried (spec issue #3394)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [mcp2026.auth :as auth]
            [mcp2026.core :as mcp]
            [mcp2026.devauth :as devauth]
            [mcp2026.env :as env :refer [with-cleanup]]
            [mcp2026.wire :as w]))

(defn- counted [tick charge]
  (fn [s]
    (cond-> (-> s
                (mcp/tool "tick" "counts" (fn [_ _] (swap! tick inc) "tick"))
                (mcp/tool "charge" "counts, extra scope" (fn [_ _] (swap! charge inc) "charged")))
      (:auth s) (assoc-in [:auth :scopes "charge"] ["mcp:charge"]))))

(deftest pause-resume-executes-once
  (with-cleanup
    (let [tick (atom 0) e (env/new-env (counted tick (atom 0))) c (env/pause-client e)
          p (env/pending! #(mcp/call-tool c "tick" {}))]
      (is (= 0 @tick) "paused call must not have executed")
      (let [cb (env/visit (:url p))]
        (is (= "tick" (mcp/text (auth/resume (:auth c) (:id p) cb))))
        (is (env/err-msg #(auth/resume (:auth c) (:id p) cb)))
        (is (= 1 @tick))))))

(deftest refresh-after-401-executes-once
  (with-cleanup
    (let [tick (atom 0) e (env/new-env (counted tick (atom 0))) c (env/auto-client e)]
      (mcp/call-tool c "tick" {})
      ;; not expired client-side: the server's 401 invalid_token drives the refresh
      (env/save-tok! c (:url e) (assoc (env/tok c (:url e)) :access-token "revoked-by-server"))
      (is (= "tick" (mcp/text (mcp/call-tool c "tick" {}))))
      (is (= [2 1 1] [@tick (:refreshes (env/stats e)) (:authorizations (env/stats e))])))))

(deftest step-up-executes-once
  (with-cleanup
    (let [charge (atom 0) e (env/new-env (counted (atom 0) charge)) c (env/auto-client e)]
      ;; 401 (base scope) -> login -> 403 insufficient_scope -> step-up login -> run
      (is (= "charged" (mcp/text (mcp/call-tool c "charge" {}))))
      (is (= [1 2] [@charge (:authorizations (env/stats e))])))))

(deftest pause-mid-mrtr-resumes-same-round
  (with-cleanup
    (let [e (env/new-env (fn [s] (update-in s [:auth :scopes] dissoc "greet")))
          c (env/pause-client e)
          p (env/pending! #(mcp/call-tool c "echo" {"text" "login"}))
          _ (auth/resume (:auth c) (:id p) (env/visit (:url p)))
          asked (atom 0)
          p (env/pending! #(mcp/call-tool c "greet" {}
                                          (fn [_ _]
                                            (swap! asked inc)
                                            (auth/delete-token! (auth/store (:auth c)) (:url e)) ; token lost between rounds
                                            {"action" "accept" "content" {"name" "once"}})))
          r (auth/resume (:auth c) (:id p) (env/visit (:url p)))]
      (is (= "Hello, once!" (mcp/text r)))
      (is (= 1 @asked) "the paused round is resent with its inputResponses, not re-asked"))))

(deftest lost-or-failed-response-not-retried
  (doseq [mode [:dropped :bad-gateway]]
    (testing mode
      (let [tick (atom 0)
            h (mcp/handler ((counted tick (atom 0)) (mcp/fixture-server)))
            ts (w/serve! (fn [req] (h req) ; the tool runs; its response is lost
                           (if (= mode :dropped) {:drop? true} {:status 502 :body "bad gateway"})))]
        (try
          (is (env/err-msg #(mcp/call-tool (mcp/client (str (:url ts) "/mcp")) "tick" {})) "want an error")
          (Thread/sleep 50) ; a hidden retry would land here
          (is (= 1 @tick) "exactly one execution, no auto-retry of tools/call")
          (finally ((:stop ts))))))))

(deftest refresh-resource-configurable
  (doseq [omit [false true]]
    (with-cleanup
      (let [e (env/new-env)
            _ (swap! (:cfg (:as e)) assoc :reject-refresh-resource true) ; Entra-like
            c (env/auto-client e :omit-refresh-resource omit)]
        (mcp/call-tool c "echo" {"text" "1"})
        (is (= (:url e) (env/last-q e "resource")))
        (is (= (:url e) (w/q1 (env/last-form e) "resource")) "code exchange always sends resource")
        (env/save-tok! c (:url e) (assoc (env/tok c (:url e)) :expiry (- (w/now-ms) 60000)))
        (is (= "2" (mcp/text (mcp/call-tool c "echo" {"text" "2"}))))
        (let [{:keys [authorizations refreshes]} (env/stats e)]
          (if omit
            (is (and (= 1 refreshes) (= 1 authorizations) (not (contains? (env/last-form e) "resource"))))
            (is (and (= 0 refreshes) (= 2 authorizations)) "default sends resource; Entra rejects -> new login"))))))
  (with-cleanup
    (let [e (env/new-env) c (env/auto-client e)]
      (mcp/call-tool c "echo" {"text" "1"})
      (env/save-tok! c (:url e) (assoc (env/tok c (:url e)) :expiry (- (w/now-ms) 60000)))
      (mcp/call-tool c "echo" {"text" "2"})
      (is (= "refresh_token" (w/q1 (env/last-form e) "grant_type")))
      (is (= (:url e) (w/q1 (env/last-form e) "resource"))))))

(deftest pre-registered-confidential-client
  (doseq [method ["" "client_secret_basic" "client_secret_post"]]
    (with-cleanup
      (let [e (env/new-env)
            _ (swap! (:cfg (:as e)) assoc :no-cimd true :secrets {"conf" "test-only-secret"})
            _ (devauth/register-client! (:as e) "conf" ["http://127.0.0.1/callback"])
            o (auth/oauth {:client-id "conf" :client-secret "test-only-secret" :token-auth-method method
                           :authorizer (auth/loopback-authorizer {:open env/follow})})
            c (mcp/client (:url e) {:auth o})]
        (testing method
          (is (= "ok" (mcp/text (mcp/call-tool c "echo" {"text" "ok"}))))
          (is (= (= method "client_secret_post") (contains? (env/last-form e) "client_secret")) "secret placement")
          (auth/configure! o :client-secret "wrong" :authorizer (auth/pause-authorizer "http://127.0.0.1:9/callback"))
          (env/save-tok! c (:url e) (assoc (env/tok c (:url e)) :expiry (- (w/now-ms) 60000)))
          (is (env/pending! #(mcp/call-tool c "echo" {"text" "x"}))
              "a wrong secret fails the refresh and falls back to a new authorization"))))))

(deftest no-cimd-and-no-client-id-is-a-clear-error
  (with-cleanup
    (let [e (env/new-env)
          _ (swap! (:cfg (:as e)) assoc :no-cimd true)
          m (str (env/err-msg #(mcp/call-tool (env/auto-client e) "echo" {"text" "x"})))]
      (is (str/includes? m "does not support Client ID Metadata Documents"))
      (is (str/includes? m (str "register an app with " (:issuer (:as e)))))
      (is (= 0 (:authorizations (env/stats e))) "must not start an authorization"))))
