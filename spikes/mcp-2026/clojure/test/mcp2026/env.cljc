(ns mcp2026.env
  "Test fixtures: a dev AS + CIMD document server + protected fixture MCP server."
  (:require [clojure.string :as str]
            [mcp2026.auth :as auth]
            [mcp2026.core :as mcp]
            [mcp2026.devauth :as devauth]
            [mcp2026.wire :as w]))

(def ^:dynamic *cleanups* nil)
(defn on-cleanup [f] (when *cleanups* (swap! *cleanups* conj f)))

(defn with-cleanup* [f]
  (binding [*cleanups* (atom ())]
    (try (f) (finally (doseq [g @*cleanups*] (try (g) (catch Exception _)))))))

(defmacro with-cleanup [& body] `(with-cleanup* (fn [] ~@body)))

(defn principal-tool [srv]
  (mcp/tool srv "whoami" "who is calling"
            (fn [{p :principal} _]
              (when-not p (throw (ex-info "no principal" {})))
              (str (:subject p) " " (str/join "," (:scopes p))))))

(defn new-env
  "cfgs: fns srv -> srv applied before serving (the server has :auth set)."
  [& cfgs]
  (let [as (devauth/start)
        _ (on-cleanup #(devauth/close as))
        cimd-url (promise)
        cimd (w/serve! (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                                :body (w/write-json {"client_id" @cimd-url "client_name" "toolnexus spike"
                                                     "redirect_uris" ["http://127.0.0.1/callback"]
                                                     "grant_types" ["authorization_code" "refresh_token"]
                                                     "response_types" ["code"] "token_endpoint_auth_method" "none"})})
                       {:tls? true})
        _ (deliver cimd-url (str (:url cimd) "/client.json"))
        _ (on-cleanup (:stop cimd))
        srv (reduce (fn [s f] (f s))
                    (-> (mcp/fixture-server)
                        (assoc :auth {:issuer (:issuer as) :scopes {"*" ["mcp:read"] "greet" ["mcp:greet"]}})
                        principal-tool)
                    cfgs)
        ts (mcp/serve! srv {:tls? true})]
    (on-cleanup (:stop ts))
    {:as as :base (:url ts) :url (str (:url ts) "/mcp") :cimd-url @cimd-url}))

(defn follow [u] (w/browser {:method "GET" :url u}) nil)

(defn visit
  "The AS redirect Location without following it (pause mode: the host 'receives' the callback)."
  [u]
  (devauth/capture-callback u))

(defn auto-client [e & {:as extra}]
  (mcp/client (:url e) {:auth (auth/oauth (merge {:client-metadata-url (:cimd-url e)
                                                  :authorizer (auth/loopback-authorizer {:open follow})}
                                                 extra))}))

(defn pause-client [e]
  (devauth/register-client! (:as e) "spike-prereg" ["http://127.0.0.1:9/callback"])
  (mcp/client (:url e) {:auth (auth/oauth {:client-ids {(:issuer (:as e)) "spike-prereg"}
                                           :authorizer (auth/pause-authorizer "http://127.0.0.1:9/callback")})}))

(defn pending! [f]
  (let [p (try (f) nil (catch Exception e (or (mcp/pending-of e) (throw e))))]
    (when-not p (throw (ex-info "want a pending, the call did not pause" {})))
    p))

(defn err-msg [f] (try (f) nil (catch Exception e (.getMessage e))))

(defn tok [c url] (auth/load-token (auth/store (:auth c)) url))
(defn save-tok! [c url t] (auth/save-token! (auth/store (:auth c)) url t))
(defn stats [e] (devauth/stats (:as e)))
(defn last-q [e k] (w/q1 (:last-authorize-query (stats e)) k))
(defn last-form [e] (:last-token-form (stats e)))
