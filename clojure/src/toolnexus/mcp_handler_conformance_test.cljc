;; SPEC §7C.1 cross-port conformance: replays ../conformance/mcp_handler/cases.json
;; (harness contract: conformance/mcp_handler/README.md; Go reference:
;; golang/mcphandler_conformance_test.go). Located relative to the shared
;; examples/ dir, never copied. Each step is a Ring request handed to the handler
;; in-process — the README's "port's native in-process request".
(ns toolnexus.mcp-handler-conformance-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [koine.fs :as fs]
            [koine.json :as json]
            [toolnexus.mcp-handler :as mh]
            [toolnexus.shared-examples-test :as te]
            [toolnexus.tool :as tool]))

(defn- cases []
  (json/read-str (fs/read-file (str (te/examples-dir) "/../conformance/mcp_handler/cases.json"))
                 {:key-fn str}))

(defn- kw
  "Fixture JSON is read with string keys (deep-equality on wire values must not
  care); the port's native maps are keyword-keyed."
  [x]
  (cond (map? x) (into {} (map (fn [[k v]] [(keyword k) (kw v)]) x))
        (sequential? x) (mapv kw x)
        :else x))

(defn- tool-result [r]
  (cond-> {:output (str (or (get r "output") "")) :isError (true? (get r "isError"))}
    (contains? r "structured") (assoc :structured (kw (get r "structured")))
    (contains? r "meta")       (assoc :meta (kw (get r "meta")))))

(defn- build-tools [fixture executed]
  (mapv (fn [ft]
          (let [nm (get ft "name")
                h  (get ft "hints")]
            (tool/tool (cond-> {:name nm :description (get ft "description") :source "native"
                                :input-schema (kw (get ft "inputSchema")) :context? true
                                :execute (fn [args ctx]
                                           (swap! executed conj nm)
                                           (case (get ft "behavior")
                                             "echo"   (tool/success (str (or (:text args) "")))
                                             "whoami" (tool/success (str (or (mh/mcp-identity ctx) "") "|"
                                                                         (or (:trace (mh/mcp-request-meta ctx)) "")))
                                             "error"  (throw (ex-info (get ft "error") {}))
                                             (tool-result (get ft "result"))))}
                         (get ft "outputSchema") (assoc :output-schema (kw (get ft "outputSchema")))
                         (get ft "annotations")  (assoc :annotations (kw (get ft "annotations")))
                         h (assoc :hints {:read-only (get h "readOnly") :destructive (get h "destructive")
                                          :open-world (get h "openWorld")})))))
        (get fixture "tools")))

(defn- path-get [v p]
  (reduce (fn [v seg]
            (cond (map? v) (get v seg)
                  (sequential? v) (let [n (try (parse-long seg) (catch Throwable _ nil))]
                                    (when (and n (< n (count v))) (nth v n)))
                  :else nil))
          v (str/split p #"\.")))

(defn- hdr [res k]
  (let [v (some (fn [[hk v]] (when (= (str/lower-case (name hk)) (str/lower-case k)) v)) (:headers res))]
    (if (nil? v) "" v)))

(defn- run-scenario [fixture sc]
  (let [executed (atom [])
        on-calls (atom [])
        cfg      (get sc "config" {})
        by-name  (into {} (map (fn [ft] [(get ft "name") ft]) (get fixture "tools")))
        tools    (build-tools fixture executed)
        auth     (get fixture "auth")
        opts     (cond-> {:list-tools (fn [ctx]
                                        (filterv (fn [t] (let [v (get-in by-name [(:name t) "visibleTo"])]
                                                           (or (nil? v) (some #{(mh/mcp-identity ctx)} v))))
                                                 tools))
                          :before-call (fn [_ t _]
                                         (when-let [d (get-in by-name [(:name t) "decision"])]
                                           (cond-> {:kind (keyword (get d "kind")) :message (get d "message")}
                                             (get d "result") (assoc :result (tool-result (get d "result"))))))
                          :on-call (fn [ev] (swap! on-calls conj {"name" (:name ev) "isError" (:is-error ev)
                                                                  "decision" (name (:decision ev))}))}
                   (get cfg "auth") (assoc :authenticate (fn [r] (or (get (get auth "tokens") (get-in r [:headers "authorization"]))
                                                                     (throw (ex-info "unauthorized" {}))))
                                           :www-authenticate (fn [_ _] (get auth "wwwAuthenticate")))
                   (get cfg "name")               (assoc :name (get cfg "name"))
                   (get cfg "title")              (assoc :title (get cfg "title"))
                   (get cfg "version")            (assoc :version (get cfg "version"))
                   (get cfg "instructions")       (assoc :instructions (get cfg "instructions"))
                   (get cfg "stateless")          (assoc :stateless true)
                   (get cfg "statelessSessionId") (assoc :stateless-session-id (get cfg "statelessSessionId"))
                   (get cfg "protocolVersions")   (assoc :protocol-versions (get cfg "protocolVersions")))
        handler  (:handler (mh/mcp-handler (tool/toolkit []) opts))
        sid      (atom "")]
    (doseq [[i st] (map-indexed vector (get sc "steps"))]
      (let [rq     (get st "request")
            method (keyword (str/lower-case (get rq "method")))
            body   (cond (contains? rq "rawBody") (get rq "rawBody")
                         (contains? rq "body")    (json/write-str (get rq "body"))
                         :else "")
            token  (or (not-empty (get rq "token")) (not-empty (get sc "token")))
            hs     (cond-> {"accept" (or (get rq "accept") "application/json, text/event-stream")}
                     (= :post method)          (assoc "content-type" (or (get rq "contentType") "application/json"))
                     token                     (assoc "authorization" token)
                     (seq (get rq "sessionId")) (assoc "mcp-session-id" (get rq "sessionId"))
                     (and (empty? (get rq "sessionId")) (get rq "session")) (assoc "mcp-session-id" @sid)
                     (get rq "version")        (assoc "mcp-protocol-version" (get rq "version")))
            res    (handler {:request-method method :headers hs :body body})
            rb     (let [b (:body res)] (if (string? b) b (apply str b)))
            ex     (get st "expect")
            label  (str (get sc "name") " step " i)]
        (when-let [s (get ex "status")] (is (= s (:status res)) (str label " status; body " rb)))
        (doseq [[k v] (get ex "headers")] (is (= v (hdr res k)) (str label " header " k)))
        (doseq [k (get ex "headersPresent")] (is (seq (hdr res k)) (str label " header present " k)))
        (doseq [k (get ex "headersAbsent")] (is (empty? (hdr res k)) (str label " header absent " k)))
        (when (= "sid" (get st "capture")) (reset! sid (hdr res "Mcp-Session-Id")))
        (when (or (contains? ex "json") (contains? ex "jsonPath"))
          (let [got (json/read-str rb {:key-fn str})]
            (when (contains? ex "json") (is (= (get ex "json") got) (str label " body")))
            (doseq [[p want] (get ex "jsonPath")] (is (= want (path-get got p)) (str label " " p)))))))
    (when (contains? sc "expectExecuted")
      (is (= (get sc "expectExecuted") @executed) (str (get sc "name") " executed")))
    (when (contains? sc "expectOnCall")
      (is (= (get sc "expectOnCall") @on-calls) (str (get sc "name") " onCall")))))

(deftest mcp-handler-conformance
  (let [f (cases)]
    (is (seq (get f "scenarios")))
    (doseq [sc (get f "scenarios")]
      (testing (get sc "name") (run-scenario (get f "fixture") sc)))))
