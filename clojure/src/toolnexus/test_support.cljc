;; Test support — NOT library code (excluded from the jar in build.clj).
;;
;; `serve` is koine.server/serve for the suite's scripted peers, with one extra
;; guarantee: the returned server PROVABLY owns http://127.0.0.1:<port>.
;;
;; Why this exists (the "one 404 from the local mock server" flake). On cljgo,
;; koine.server/serve goes through bri, which binds the WILDCARD ":0" and ignores
;; :host. The suite then talks to 127.0.0.1:<port>. macOS/BSD hands a wildcard
;; ephemeral bind a port that another socket already holds on 127.0.0.1
;; SPECIFICALLY (measured: 200/200 when the next ports are pre-claimed; the
;; reverse order, a specific :0 landing on a wildcard-held port, is 0/200), and a
;; connection to 127.0.0.1:<port> is delivered to the MORE SPECIFIC listener. So
;; whenever any other process on the box — e.g. a concurrent `go test`, whose
;; httptest servers bind 127.0.0.1:0 — holds that port, our requests reach it and
;; come back as Go's "404 page not found". The JVM binds 127.0.0.1 itself, which
;; is why the flake was cljgo-only.
;;
;; The fix is a handshake, not a sleep: after binding, GET a private probe path
;; and require the per-server token back. Anything else means another listener
;; shadows the port, so the server is stopped and a fresh one bound. Because a
;; later specific ephemeral bind never lands on our (wildcard-held) port, a port
;; that passes the probe stays ours for the server's life.
(ns toolnexus.test-support
  (:require [koine.http :as http]
            [koine.server :as server]))

(def ^:private probe-path "/__toolnexus-test-support-probe")

(def ^:private counter (atom 0))

(defn serve
  "Like koine.server/serve with {:port 0}, but the returned server is proven to
  answer on http://127.0.0.1:<port>. The probe request never reaches `handler`."
  ([handler] (serve handler {}))
  ([handler opts]
   (loop [attempt 1]
     (let [token (str "tn-" (swap! counter inc) "-" (rand-int 1000000000))
           srv   (server/serve (fn [req]
                                 (if (= probe-path (:path req))
                                   {:status 200 :headers {"content-type" "text/plain"} :body token}
                                   (handler req)))
                               (merge {:port 0} opts))
           res   (http/request {:method :get :timeout-ms 5000
                                :url (str "http://127.0.0.1:" (server/port srv) probe-path)})]
       (if (and (= 200 (:status res)) (= token (:body res)))
         srv
         (do (server/stop! srv)
             (if (< attempt 25)
               (recur (inc attempt))
               (throw (ex-info "test-support/serve: every bound port was shadowed by another listener"
                               {:attempts attempt})))))))))
