;; The CLIENT runner of the MCP 2026-07-28 interop matrix
;; (interop/mcp2026/README.md), mirroring golang/internal/interop/mcp2026client
;; scenario for scenario. TEST TOOLING (outside `src/`, never in the jar).
;; JVM host only: the headless browser uses java.net.http with a cookie jar.
;;
;;   clojure -M -m toolnexus.interop.mcp2026-client <base> <skillsDir>
;;   clojure -M -m toolnexus.interop.mcp2026-client --keycloak <mcpURL> <skillsDir>
(ns toolnexus.interop.mcp2026-client
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.client :as client]
            [toolnexus.core :as core]
            [toolnexus.mcp :as mcp]
            [toolnexus.mcp-auth :as auth]
            [toolnexus.tool :as tool])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private failed (atom false))

(defn- scenario [id f]
  (let [err (try (f) (catch Throwable e (str "exception " (or (ex-message e) e))))]
    (if err
      (do (reset! failed true) (println (str "FAIL " id ": " (str/replace (str err) "\n" " "))))
      (println (str "PASS " id)))
    (flush)))

(defn- answers [r]
  (case (:prompt r)
    "What is your name?" (client/make-answer (:id r) true {:name "toolnexus"})
    "Really wipe?"       (client/make-answer (:id r) true {:confirm true})
    (client/make-answer (:id r) false)))

;; ---- plain HTTP (no cookie jar), redirects followed by hand ----

(defn- http-client
  "A client that never follows redirects. With `jar` (an atom) it keeps a
  minimal per-host cookie jar by hand: java.net.CookieManager files cookies
  for a bare `localhost` under `localhost.local` and never sends them back,
  which breaks Keycloak's login (\"Restart login cookie not found\")."
  ([] (http-client nil))
  ([jar]
   (cond-> {:hc (-> (HttpClient/newBuilder)
                    (.followRedirects HttpClient$Redirect/NEVER)
                    (.connectTimeout (Duration/ofSeconds 15))
                    (.build))}
     jar (assoc :jar jar))))

(defn- send! [{:keys [hc jar]} ^String method ^String u form]
  (let [host (.getHost (URI/create u))
        b (-> (HttpRequest/newBuilder (URI/create u)) (.timeout (Duration/ofSeconds 15)))
        b (if-let [c (and jar (seq (get @jar host)))]
            (.header b "Cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) c)))
            b)
        b (if form
            (-> b (.header "Content-Type" "application/x-www-form-urlencoded")
                (.header "Accept" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (if (string? form)
                          form
                          (str/join "&" (map (fn [[k v]] (str (URLEncoder/encode (str k) "UTF-8") "=" (URLEncoder/encode (str v) "UTF-8"))) form))))))
            (.GET b))
        r (.send ^HttpClient hc (.build b) (HttpResponse$BodyHandlers/ofString))]
    (when jar
      (doseq [sc (.allValues (.headers r) "set-cookie")]
        (let [[nv] (str/split sc #";" 2) [k v] (str/split (str/trim nv) #"=" 2)]
          (swap! jar assoc-in [host k] (or v "")))))
    {:status (.statusCode r) :body (.body r) :uri (str (.uri r))
     :location (.orElse (.firstValue (.headers r) "location") nil)}))

(defn- get-following
  "HTTP GET, following redirects (the `openUrl` of the hermetic scenarios)."
  ([u] (get-following (http-client) u))
  ([hc u]
   (loop [u u n 0]
     (let [r (send! hc "GET" u nil)]
       (if (and (<= 300 (:status r) 399) (:location r) (< n 10))
         (recur (str (.resolve (URI/create u) ^String (:location r))) (inc n))
         (assoc r :uri u))))))

(defn- get-browsing
  "`get-following`, plus the one thing a browser adds that this port's pages
  need: koine.server never sees a query string, so this port's loopback
  callback (and its dev AS's GET /authorize) answer a page whose script POSTs
  `location.search` back to `location.pathname`. Do what that script does."
  [u]
  (let [hc (http-client)]
    (loop [r (get-following hc u) n 0]
      (if (and (= 200 (:status r)) (< n 3) (str/includes? (str (:body r)) "fetch(location.pathname"))
        (let [uri (URI/create (:uri r))
              to  (str (.getScheme uri) "://" (.getRawAuthority uri) (.getRawPath uri))
              p   (send! hc "POST" to (or (.getRawQuery uri) ""))
              nxt (try (get (json/read-str (:body p) {:key-fn str}) "redirect_to") (catch Throwable _ nil))]
          (if (string? nxt) (recur (get-following hc nxt) (inc n)) p))
        r))))

(defn- open-get [u] (get-browsing u) nil)

;; ---- the toolnexus side ----

(defn- load-src [cfg opts]
  (let [src (mcp/from-config {:mcpServers {:srv cfg}} opts)]
    (if (= "connected" (get (:statuses src) "srv"))
      src
      (do (mcp/disconnect-all src)
          (throw (ex-info (str "not connected: " (pr-str (:statuses src)) " " (pr-str (:errors src))) {}))))))

(defn- tool-of [src n]
  (or (first (filter #(= n (:name %)) (:tools src)))
      (throw (ex-info (str "no tool " n) {}))))

(defn- run [src n args] ((:execute (tool-of src n)) (or args {})))

(defn- expect [src n args want]
  (let [r (run src n args)]
    (when (or (:isError r) (not= want (:output r)))
      (str n ": got " (pr-str (:output r)) " (isError=" (:isError r) "), want " (pr-str want)))))

(defn- skill-body [dir]
  (let [s (str/replace (slurp (str dir "/hello-world/SKILL.md")) "\r\n" "\n")
        s (if (str/starts-with? s "---\n")
            (let [i (str/index-of s "\n---" 4)] (if i (subs s (+ i 4)) s))
            s)]
    (str/trim s)))

(defn- scopes-of [store resource]
  (let [t ((:load store) resource)]
    (set (map str (or (:scopes t) (get t "scopes"))))))

(defn- hermetic [base skills-dir]
  (let [open {:url (str base "/open/mcp")}]
    (scenario "discover"
      (fn []
        (let [src (load-src open {:wait-for answers})]
          (try
            (let [names (str/join "," (map :name (:tools src)))]
              (if (not= names "srv_echo,srv_greet,srv_whoami,srv_wipe")
                (str "tools " names)
                (or (expect src "srv_echo" {:text "hi"} "{\"text\":\"hi\"}")
                    (let [l (json/read-str (:body (get-following (str base "/log"))) {:key-fn str})]
                      (some (fn [s] (when (and (str/starts-with? s "open ") (not (str/starts-with? s "open 2026-07-28 ")))
                                      (str "non-2026 request on the 2026 mount: " (pr-str s))))
                            (get l "seen"))))))
            (finally (mcp/disconnect-all src))))))

    (scenario "mrtr_input"
      (fn [] (let [src (load-src open {:wait-for answers})]
               (try (expect src "srv_greet" {} "Hello, toolnexus!") (finally (mcp/disconnect-all src))))))

    (scenario "mrtr_confirm"
      (fn [] (let [src (load-src open {:wait-for answers})]
               (try (expect src "srv_wipe" {} "wiped") (finally (mcp/disconnect-all src))))))

    (scenario "mrtr_pending"
      (fn [] (let [src (load-src open {})]
               (try
                 (let [r (run src "srv_greet" {}) q (client/pending-of r)]
                   (when-not (and q (= "input" (:kind q)) (= "What is your name?" (:prompt q)))
                     (str "not pending input: " (pr-str r))))
                 (finally (mcp/disconnect-all src))))))

    (scenario "skills"
      (fn [] (let [tk (core/build {:mcp {:mcpServers {:srv open}}})]
               (try
                 (if-not (get (:tools tk) "skill")
                   "no skill tool"
                   (let [r (tool/execute tk "skill" {:name "hello-world"}) body (skill-body skills-dir)]
                     (when (or (:isError r) (not (str/includes? (:output r) body)))
                       (str "remote skill output lacks the SKILL.md body: " (subs (str (:output r)) 0 (min 200 (count (str (:output r)))))))))
                 (finally (core/shutdown! tk))))))

    (scenario "tool_search"
      (fn [] (let [src (load-src (assoc open :toolSearch true :description "interop") {:wait-for answers})]
               (try
                 (if (not= 2 (count (:tools src)))
                   (str "tool search exposes " (count (:tools src)) " tools, want 2")
                   (let [r (run src "srv_search_tools" {:query "echo text"})]
                     (if-not (str/starts-with? (str (:output r)) "[{\"name\":\"echo\"")
                       (str "search output " (pr-str (:output r)))
                       (let [r (run src "srv_call_tool" {:name "greet"})]
                         (when (not= "Hello, toolnexus!" (:output r)) (str "call_tool greet " (pr-str (:output r))))))))
                 (finally (mcp/disconnect-all src))))))

    (let [auth-cfg {:url (str base "/mcp") :auth {:clientId "interop-client" :allowHttp true}}
          store    (auth/memory-store)
          authed   (atom nil)]
      (scenario "oauth_preregistered"
        (fn [] (let [src (load-src auth-cfg {:open-url open-get :token-store store :wait-for answers})]
                 (reset! authed src)
                 (expect src "srv_echo" {:text "authed"} "{\"text\":\"authed\"}"))))

      (scenario "oauth_stepup"
        (fn [] (if-let [src @authed]
                 (try
                   (or (expect src "srv_whoami" {} "dev-user")
                       (let [sc (scopes-of store (str base "/mcp"))]
                         (when-not (contains? sc "mcp:whoami") (str "stored token scopes lack mcp:whoami: " (pr-str sc)))))
                   (finally (mcp/disconnect-all src)))
                 "needs oauth_preregistered"))))

    (scenario "oauth_cimd"
      (fn [] (let [doc-url (atom nil)
                   srv (server/serve (fn [_] {:status 200 :headers {"Content-Type" "application/json"}
                                              :body (json/write-str {"client_id" @doc-url "client_name" "interop CIMD"
                                                                     "redirect_uris" ["http://127.0.0.1/callback"]})})
                                     {:port 0 :host "127.0.0.1"})
                   _   (reset! doc-url (str "http://127.0.0.1:" (server/port srv) "/client.json"))
                   bad (atom nil)
                   follow (fn [u]
                            (when-not (str/includes? u (str "client_id=" (URLEncoder/encode ^String @doc-url "UTF-8")))
                              (reset! bad (str "authorize URL lacks the CIMD client_id: " u)))
                            (open-get u))]
               (try
                 (let [src (load-src {:url (str base "/mcp") :auth {:clientMetadataUrl @doc-url :allowHttp true}}
                                     {:open-url follow})]
                   (try (or (expect src "srv_echo" {:text "cimd"} "{\"text\":\"cimd\"}") @bad)
                        (finally (mcp/disconnect-all src))))
                 (finally (server/stop! srv))))))

    (scenario "oauth_no_identity"
      (fn [] (let [src (mcp/from-config {:mcpServers {:srv {:url (str base "/mcp") :auth {:allowHttp true}}}}
                                        {:open-url (fn [_] nil)})]
               (try (when (not= "failed" (get (:statuses src) "srv"))
                      (str "status " (pr-str (:statuses src)) ", want failed"))
                    (finally (mcp/disconnect-all src))))))

    (scenario "legacy_fallback"
      (fn [] (let [src (load-src {:url (str base "/legacy/mcp")} {})]
               (try (expect src "srv_echo" {:text "old"} "{\"text\":\"old\"}") (finally (mcp/disconnect-all src))))))))

;; ---- live Keycloak (interop/mcp2026/keycloak) ----

(def ^:private kc-client-id "toolnexus-mcp")
(def ^:private kc-user "alice")
(def ^:private kc-password "alice-test-pw") ; throwaway user of the local-only realm-toolnexus.json

(defn- unescape [s]
  (-> s (str/replace "&amp;" "&") (str/replace "&quot;" "\"") (str/replace "&#39;" "'")
      (str/replace "&lt;" "<") (str/replace "&gt;" ">")))

(defn- kc-opener
  "A headless browser: follow the authorize URL, submit Keycloak's login (and
  consent) form, then deliver the loopback callback."
  []
  (let [hc (http-client (atom {}))
        ;; follow redirects, but stop at the 127.0.0.1 callback
        follow (fn [r from]
                 (loop [r r from from n 0]
                   (let [loc (:location r)
                         to  (when loc (str (.resolve (URI/create from) ^String loc)))]
                     (if (and (<= 300 (:status r) 399) to (not= "127.0.0.1" (.getHost (URI/create to))) (< n 10))
                       (recur (send! hc "GET" to nil) to (inc n))
                       (assoc r :uri from)))))]
    (fn [auth-url]
      (let [final (loop [r (follow (send! hc "GET" auth-url nil) auth-url) page 0]
                    (if (and (= 200 (:status r)) (< page 3))
                      (let [body (:body r)
                            login (re-find #"<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"" body)
                            anyf  (re-find #"<form[^>]*action=\"([^\"]+)\"" body)
                            [action form] (cond login [(second login) {"username" kc-user "password" kc-password "credentialId" ""}]
                                                (and anyf (str/includes? body "name=\"accept\"")) [(second anyf) {"accept" "Yes"}])]
                        (if action
                          (let [target (str (.resolve (URI/create (:uri r)) ^String (unescape action)))]
                            (recur (follow (send! hc "POST" target form) target) (inc page)))
                          r))
                      r))]
        (when (not= 302 (:status final))
          (throw (ex-info (str "keycloak login: HTTP " (:status final)) {})))
        (get-browsing (str (.resolve (URI/create (:uri final)) ^String (:location final))))
        nil))))

(defn- keycloak [mcp-url]
  (let [store (auth/memory-store) src (atom nil)]
    (scenario "kc_login"
      (fn [] (reset! src (load-src {:url mcp-url :auth {:clientId kc-client-id :allowHttp true}}
                                   {:open-url (kc-opener) :token-store store :wait-for answers}))
        (expect @src "srv_echo" {:text "keycloak"} "{\"text\":\"keycloak\"}")))
    (scenario "kc_stepup"
      (fn [] (if-let [s @src]
               (try
                 (let [r (run s "srv_whoami" {})]
                   (if (or (:isError r) (str/blank? (:output r)))
                     (str "whoami " (pr-str r))
                     (let [sc (scopes-of store mcp-url)]
                       (when-not (contains? sc "mcp:greet") (str "stored token scopes lack mcp:greet: " (pr-str sc))))))
                 (finally (mcp/disconnect-all s)))
               "needs kc_login")))))

(defn -main [& argv]
  (cond
    (and (= 3 (count argv)) (= "--keycloak" (first argv))) (keycloak (second argv))
    (= 2 (count argv)) (hermetic (first argv) (second argv))
    :else (do (binding [*out* *err*] (println "usage: mcp2026-client <base> <skillsDir> | --keycloak <mcpURL> <skillsDir>"))
              (System/exit 2)))
  (flush)
  (shutdown-agents)
  (System/exit (if @failed 1 0)))
