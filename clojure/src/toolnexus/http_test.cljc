;; SPEC §0.9 / §7 — http tools. Dual-host: no java.*, no Thread/sleep.
;; The only network is a koine.server on 127.0.0.1:0 (hermetic, no key).
(ns toolnexus.http-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.http :as http]))

;; ---------------------------------------------------------------------------
;; the fake endpoint
;; ---------------------------------------------------------------------------

(def ^:private srv (atom nil))
(def ^:private base (atom nil))

(defn- lower-keys
  "Header CASE is a host detail (the JVM server lowercases, cljgo's does not
  promise to), so the test normalises before looking anything up."
  [m]
  (reduce (fn [acc [k v]] (assoc acc (str/lower-case (name k)) v)) {} m))

(defn- handler [req]
  (let [p (str (:path req))
        h (lower-keys (:headers req))]
    (cond
      (= p "/boom")
      {:status 500 :headers {"content-type" "text/plain"} :body "kaboom"}

      (= p "/teapot")
      {:status 418 :headers {"content-type" "text/plain"} :body "short and stout"}

      :else
      {:status 200
       :headers {"content-type" "application/json"}
       ;; SHAPE ONLY. A header value can carry a credential, so what comes back
       ;; is whether the key exists and whether an unexpanded `${` survived —
       ;; never the value itself.
       :body (json/write-str
               {:path            p
                :method          (name (or (:request-method req) (:method req) :get))
                :body            (str (:body req))
                :authPresent     (contains? h "authorization")
                :authUnexpanded  (str/includes? (str (get h "authorization" "")) "${")
                :contentType     (str (get h "content-type" ""))})})))

(use-fixtures :once
  (fn [f]
    (let [s (server/serve handler {:port 0})]
      (reset! srv s)
      (reset! base (str "http://127.0.0.1:" (server/port s)))
      (try (f) (finally (server/stop! s) (reset! srv nil))))))

(defn- body-of [res] (json/read-str (:output res) {:key-fn keyword}))

;; ---------------------------------------------------------------------------
;; pure pieces
;; ---------------------------------------------------------------------------

(deftest placeholder-scanning
  (is (= ["a" "b"] (http/placeholders "http://h/{a}/x/{b}")))
  (is (= [] (http/placeholders "http://h/plain")))
  (testing "an unterminated or empty brace group is not a placeholder"
    (is (= [] (http/placeholders "http://h/{unclosed")))
    (is (= [] (http/placeholders "http://h/{}")))))

(deftest url-substitution
  (testing "§0.9 — {ph} filled from args"
    (is (= "http://h/1/x/2" (http/substitute "http://h/{a}/x/{b}" {:a "1" :b "2"}))))
  (testing "a placeholder with no arg is left verbatim, never blanked"
    (is (= "http://h/1/x/{b}" (http/substitute "http://h/{a}/x/{b}" {:a "1"}))))
  (testing "an arg with no placeholder does not corrupt the URL"
    (is (= "http://h/1" (http/substitute "http://h/{a}" {:a "1" :spare "z"}))))
  (testing "values are percent-encoded (see the substitute docstring: UNPINNED in §0.9)"
    (is (= "http://h/a%20b" (http/substitute "http://h/{a}" {:a "a b"})))))

(deftest percent-encoding
  (is (= "abcXYZ019-_.~" (http/url-encode "abcXYZ019-_.~")))
  (is (= "a%20b" (http/url-encode "a b")))
  (is (= "%26%3D%3F%2F" (http/url-encode "&=?/")))
  (testing "multi-byte characters encode as UTF-8 byte sequences"
    (is (= "%C3%A9" (http/url-encode "é")))
    (is (= "%E2%82%AC" (http/url-encode "€")))))

;; ---------------------------------------------------------------------------
;; the tool
;; ---------------------------------------------------------------------------

(deftest tool-shape
  (let [t (http/http-tool {:name "getThing" :description "Get a thing"
                           :input-schema {:type "object"} :url "http://x/{id}"})]
    (is (= "getThing" (:name t)))
    (is (= "http" (:source t)))
    (is (= "Get a thing" (:description t)))
    (is (fn? (:execute t)))))

(deftest get-success
  (let [t (http/http-tool {:name "get" :url (str @base "/echo/{id}")})
        r ((:execute t) {:id "42"})]
    (is (false? (:isError r)))
    (is (= 200 (get-in r [:metadata :status])))
    (is (= "/echo/42" (:path (body-of r))))
    (is (= "get" (:method (body-of r))))))

(deftest env-expansion-in-headers
  (testing "§0.9 — ${ENV} expands; the value never appears anywhere here"
    (let [t (http/http-tool {:name "auth" :url (str @base "/echo")
                             :headers {"authorization" "Bearer ${TN_TEST_TOKEN_UNSET}"}})
          b (body-of ((:execute t) {}))]
      (is (true? (:authPresent b)) "the header was sent")
      (is (false? (:authUnexpanded b))
          "no ${...} survived — expansion ran (an unset var expands to empty)"))))

(deftest non-2xx-is-an-error-result
  (testing "§0.9 — non-2xx ⇒ `HTTP <status>: <body>` isError"
    (let [t (http/http-tool {:name "boom" :url (str @base "/boom")})
          r ((:execute t) {})]
      (is (true? (:isError r)))
      (is (= "HTTP 500: kaboom" (:output r)))
      (is (= 500 (get-in r [:metadata :status])))))
  (testing "a 4xx too — the rule is the 200..299 window, not a 5xx check"
    (let [t (http/http-tool {:name "tea" :url (str @base "/teapot")})
          r ((:execute t) {})]
      (is (true? (:isError r)))
      (is (= "HTTP 418: short and stout" (:output r))))))

(deftest transport-failure-is-named-data
  (testing "koine gives {:error :timeout|:dns|:connect-failed|:transport} as DATA;
            nothing here catches a host exception class"
    (let [t (http/http-tool {:name "dead" :url "http://127.0.0.1:1/nope"
                             :timeout-ms 2000})
          r ((:execute t) {})]
      (is (true? (:isError r)))
      (is (str/starts-with? (:output r) "HTTP transport failure: "))
      (is (contains? #{:timeout :dns :connect-failed :transport}
                     (get-in r [:metadata :error])))
      (is (nil? (:status r)) "no status was ever produced — this is not an HTTP answer")))
  ;; NOT tested: the :dns classification. Reaching it needs a name lookup, and
  ;; a DNS query is network — the BRIEF allows 127.0.0.1 only. koine covers it.
  )

(deftest post-bodies
  (testing "json (default): args left after placeholders become the body"
    (let [t (http/http-tool {:name "p" :method :post :url (str @base "/echo/{id}")})
          b (body-of ((:execute t) {:id "7" :a 1 :b "two"}))]
      (is (= "/echo/7" (:path b)))
      (is (= "post" (:method b)))
      (is (= "application/json" (:contentType b)))
      (is (= {:a 1 :b "two"} (json/read-str (:body b) {:key-fn keyword})))))
  (testing "form"
    (let [t (http/http-tool {:name "f" :method "POST" :body "form"
                             :url (str @base "/echo")})
          b (body-of ((:execute t) {:x "a b" :y "z"}))]
      (is (= "application/x-www-form-urlencoded" (:contentType b)))
      (is (= "x=a%20b&y=z" (:body b)))))
  (testing "raw"
    (let [t (http/http-tool {:name "r" :method :post :body "raw"
                             :url (str @base "/echo")})
          b (body-of ((:execute t) {:text "hello"}))]
      (is (= "hello" (:body b)))))
  (testing "a GET never carries a body"
    (let [t (http/http-tool {:name "g" :method :get :url (str @base "/echo")})
          b (body-of ((:execute t) {:a 1}))]
      (is (= "" (:body b))))))

(deftest query-parameters
  (testing "named args go to the querystring and NOT the body"
    ;; koine.server hands the handler a :path with the query stripped on the JVM
    ;; (`URI.getPath`), so the query cannot be observed server-side portably.
    ;; What is asserted here is that the request still succeeds and the query
    ;; args are consumed — the encoding itself is covered by percent-encoding.
    (let [t (http/http-tool {:name "q" :method :post :query [:v]
                             :url (str @base "/echo")})
          b (body-of ((:execute t) {:v "a b" :keep "yes"}))]
      (is (= {:keep "yes"} (json/read-str (:body b) {:key-fn keyword}))))))

(deftest result-modes
  (let [mk (fn [mode] (http/http-tool {:name "m" :result-mode mode
                                       :url (str @base "/echo")}))]
    (testing "text (default) is the raw body"
      (is (str/starts-with? (:output ((:execute (mk "text")) {})) "{")))
    (testing "status+text prefixes the status line"
      (is (str/starts-with? (:output ((:execute (mk "status+text")) {})) "HTTP 200\n")))
    (testing "json canonicalises through koine.json (sorted keys) — UNPINNED in §7"
      (let [o (:output ((:execute (mk "json")) {}))]
        (is (str/starts-with? o "{\"authPresent\":"))))))
