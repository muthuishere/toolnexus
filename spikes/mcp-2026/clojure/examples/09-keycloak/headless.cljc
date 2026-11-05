;; headless-login is TEST HARNESS, not something an app needs: it plays the person at the browser.
;; It follows the authorization URL, submits Keycloak's login form (and a consent page if shown),
;; stops at the 127.0.0.1 redirect and delivers it to the client's loopback listener.
(ns example.keycloak.headless
  (:require [clojure.string :as str]
            [mcp2026.wire :as w]))

(defn- unescape [s] (-> s (str/replace "&amp;" "&") (str/replace "&quot;" "\"") (str/replace "&#x2F;" "/")))

(defn- resolve-url [base action] (str (.resolve (java.net.URI. base) (unescape action))))

(defn browser
  "A cookie-jar browser that follows redirects until a 200 or a redirect to 127.0.0.1.
  Returns (fn [method url form] {:status :headers :body :url}). Its jar is per host and, like a
  browser on localhost, sends Secure cookies over http://localhost (java.net.CookieManager won't)."
  []
  (let [jar (atom {})]
    (fn go [method url form]
      (loop [method method url url form form hops 0]
        (let [host (:hostname (w/parse-url url))
              cookie (str/join "; " (for [[k v] (get @jar host)] (str k "=" v)))
              r (w/request {:method method :url url :timeout-ms 15000
                            :headers (cond-> {}
                                       form (assoc "Content-Type" "application/x-www-form-urlencoded")
                                       (seq cookie) (assoc "Cookie" cookie))
                            :body (when form (w/form-encode form))})
              _ (doseq [sc (get-in r [:header-lists "set-cookie"])
                        :let [[k v] (str/split (first (str/split sc #";")) #"=" 2)]]
                  (swap! jar assoc-in [host (str/trim k)] (or v "")))
              loc (get-in r [:headers "location"])]
          (if (and (#{301 302 303 307} (:status r)) loc (< hops 10)
                   (not= "127.0.0.1" (:hostname (w/parse-url (resolve-url url loc)))))
            (recur "GET" (resolve-url url loc) nil (inc hops))
            (assoc r :url url)))))))

(defn login
  "Walk authURL to the loopback callback URL, submitting the login form / consent if shown.
  stats (atom) counts :forms and :consents."
  [b user password stats auth-url]
  (loop [r (b "GET" auth-url nil) page 0]
    (if (and (= 200 (:status r)) (< page 3))
      (let [body (str (:body r))
            login-action (second (re-find #"<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"" body))
            any-action (second (re-find #"<form[^>]*action=\"([^\"]+)\"" body))]
        (cond login-action (do (swap! stats update :forms (fnil inc 0))
                               (recur (b "POST" (resolve-url (:url r) login-action)
                                         {"username" user "password" password "credentialId" ""}) (inc page)))
              (and any-action (str/includes? body "name=\"accept\""))
              (do (swap! stats update :consents (fnil inc 0))
                  (recur (b "POST" (resolve-url (:url r) any-action) {"accept" "Yes"}) (inc page)))
              :else (throw (ex-info (str "login stuck on HTTP 200 page") {}))))
      (if (= 302 (:status r))
        (resolve-url (:url r) (get-in r [:headers "location"]))
        (throw (ex-info (str "login did not redirect back (HTTP " (:status r) ")") {}))))))

(defn headless-login
  "A loopback-authorizer :open fn that logs in as user and hands the callback to the listener."
  [user password]
  (let [b (browser) stats (atom {})]
    (fn [auth-url]
      (w/request {:method "GET" :url (login b user password stats auth-url)})
      nil)))
