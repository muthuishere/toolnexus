;; The SERVER runner of the MCP 2026-07-28 interop matrix
;; (interop/mcp2026/README.md), mirroring golang/internal/interop/mcp2026server.
;; TEST TOOLING: this root (clojure/interop) is outside `src/`, so it is neither
;; in the Clojars jar nor in the examples/src mirror. JVM host only.
;;
;;   clojure -M -m toolnexus.interop.mcp2026-server [--keycloak] <skillsDir>
;;
;; Prints {"base","issuer"} on one line and serves until stdin closes.
(ns toolnexus.interop.mcp2026-server
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.client :as client]
            [toolnexus.mcp-dev-auth-test-support :as da]
            [toolnexus.mcp-handler :as mh]
            [toolnexus.mcp-server-auth :as sa]
            [toolnexus.tool :as tool]))

(def ^:private keycloak-issuer "http://localhost:8180/realms/toolnexus")
(def ^:private keycloak-port 7199)

(defn- jvm-serve
  "A loopback listener that, unlike koine.server, hands the handler the URL
  query as :query-string — so the dev AS answers GET /authorize?… with a 302,
  which is what the contract's `openUrl = HTTP GET` client expects."
  [handler]
  (let [srv (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/"
                    (reify com.sun.net.httpserver.HttpHandler
                      (handle [_ ex]
                        (try
                          (let [uri (.getRequestURI ex)
                                req {:method (keyword (str/lower-case (.getRequestMethod ex)))
                                     :path (.getPath uri)
                                     :query-string (.getRawQuery uri)
                                     :headers (reduce (fn [m [k v]] (assoc m (str/lower-case k) (first v)))
                                                      {} (.getRequestHeaders ex))
                                     :body (slurp (.getRequestBody ex) :encoding "UTF-8")}
                                res (handler req)
                                body (.getBytes (str (:body res)) "UTF-8")]
                            (doseq [[k v] (:headers res)] (.add (.getResponseHeaders ex) (str k) (str v)))
                            (.sendResponseHeaders ex (int (:status res 200)) (if (zero? (alength body)) -1 (alength body)))
                            (with-open [os (.getResponseBody ex)] (.write os body)))
                          (catch Throwable e
                            (binding [*out* *err*] (println "as handler:" e)))
                          (finally (.close ex))))))
    (.setExecutor srv (java.util.concurrent.Executors/newCachedThreadPool))
    (.start srv)
    {:port (.getPort (.getAddress srv)) :stop (fn [] (.stop srv 0))}))

(def ^:private obj {:type "object"})

(def tools
  [(tool/tool {:name "echo" :description "Echo the given text back." :source "native"
               :input-schema {:type "object" :properties {:text {:type "string"}} :required ["text"]}
               :execute (fn [a] (let [s (str (or (:text a) (get a "text") ""))]
                                  (tool/with-structured (tool/success s) {:text s})))})
   (tool/tool {:name "greet" :description "Greet the user; asks for their name." :source "native"
               :input-schema obj :context? true
               :execute (fn [_ ctx]
                          (let [a (:answer ctx)]
                            (if (and a (:ok a))
                              (tool/success (str "Hello, " (or (get-in a [:data :name]) (get-in a [:data "name"])) "!"))
                              (client/suspend (client/make-request "input" "What is your name?"
                                                                   {:id "name"
                                                                    :data {:schema {:type "object"
                                                                                    :properties {:name {:type "string"}}
                                                                                    :required ["name"]}}})))))})
   (tool/tool {:name "wipe" :description "Destructive; asks for confirmation first." :source "native"
               :input-schema obj :execute (fn [_] (tool/success "wiped"))})
   (tool/tool {:name "whoami" :description "Who is calling." :source "native" :input-schema obj :context? true
               :execute (fn [_ ctx] (tool/success (str (:subject (mh/mcp-identity ctx)))))})])

(defn -main [& argv]
  (let [keycloak? (= "--keycloak" (first argv))
        args      (if keycloak? (rest argv) argv)
        skills    (or (first args) "../examples/skills")
        as        (when-not keycloak?
                    (da/start-mcp-dev-auth-server {:clients {"interop-client" ["http://127.0.0.1/callback"]}
                                                   :serve jvm-serve}))
        issuer    (if keycloak? keycloak-issuer (:issuer as))
        seen      (atom [])
        calls     (atom {})
        tk        (tool/toolkit tools)
        cfg       {:name "interop" :version "1.0.0"
                   :protocol-versions [mh/protocol-2026]
                   :list-tools (fn [_] tools)
                   :list-skills (fn [_] (mh/load-mcp-skills skills))
                   :before-call (fn [_ t _] (when (= "wipe" (:name t)) {:kind :confirm :message "Really wipe?"}))
                   :on-call (fn [ev] (swap! calls update (:name ev) (fnil inc 0)))}
        auth-a    (atom nil)
        open      (:handler (mh/mcp-handler tk cfg))
        protected (:handler (mh/mcp-handler tk (assoc cfg :authenticate (fn [req] ((:authenticate @auth-a) req)))))
        legacy    (:handler (mh/mcp-handler tk {:stateless true :list-tools (fn [_] tools)}))
        logged    (fn [mount h]
                    (fn [req]
                      (swap! seen conj (str mount " " (get-in req [:headers "mcp-protocol-version"] "")
                                            " " (get-in req [:headers "mcp-method"] "")))
                      (h req)))
        routes    {"/open/mcp" (logged "open" open)
                   "/mcp" (logged "auth" protected)
                   "/legacy/mcp" (logged "legacy" legacy)}
        srv       (server/serve (fn [req]
                                  (let [p (:path req)]
                                    (cond
                                      (routes p) ((routes p) req)
                                      (str/starts-with? p "/.well-known/oauth-protected-resource")
                                      ((:metadata @auth-a) (assoc req :uri p))
                                      (= "/log" p)
                                      {:status 200 :headers {"Content-Type" "application/json"}
                                       :body (json/write-str {"seen" @seen "calls" @calls})}
                                      :else {:status 404 :body "404 page not found\n"})))
                                {:port (if keycloak? keycloak-port 0) :host "127.0.0.1"})
        base      (str "http://127.0.0.1:" (server/port srv))]
    (reset! auth-a (sa/mcp-server-auth
                    (if keycloak?
                      {:issuer issuer :audience (str base "/mcp") :scopes {"*" ["mcp:tools"] "whoami" ["mcp:greet"]}}
                      {:issuer issuer :audience (str base "/mcp") :scopes {"*" ["mcp"] "whoami" ["mcp:whoami"]}})))
    (println (json/write-str {"base" base "issuer" issuer}))
    (flush)
    ;; serve until stdin closes — the runner owns our lifetime
    (while (not= -1 (.read System/in)))
    (server/stop! srv)
    (when as ((:close as)))
    (shutdown-agents)
    (System/exit 0)))
