;; S16 — the OTHER half of SPEC §0: the tool-calling loop, on both hosts.
;;
;; S15 proved the tool SOURCES port (MCP stdio, skills, config, adapters).
;; It left the part that actually drives an LLM untested, and that part carries
;; the two things most likely to differ between a JVM and a Go runtime:
;;
;;   §0.8   native tool  — fn -> Tool, throw => isError
;;   §0.9   http tool    — {ph} URL substitution, ${ENV} headers, non-2xx
;;   §0.10  client loop  — call -> execute tool calls -> feed back -> repeat
;;   S10    PARALLEL tool calls via future/promise   <- the real question
;;   S6     HTTP POST against a local server, both hosts
;;
;; Hermetic by construction: the "LLM" is a koine.server on 127.0.0.1 replaying
;; a canned OpenAI-shaped script. No API key, no network, no cost, and the loop
;; is exercised for real rather than mocked out.
;;
;; Still no reader conditional, no java.*, no Go interop.

(ns toolnexus.loopslice
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.env :as env]
            [koine.host :as host]
            [koine.http :as http]
            [koine.server :as server]))

;; ---------------------------------------------------------------------------
;; §0.8  native tools
;; ---------------------------------------------------------------------------

(defn native-tool
  "SPEC §0.8 — fn -> Tool. A string return is the output; a throw is isError."
  [tool-name description schema f]
  {:name        tool-name
   :description description
   :inputSchema schema
   :source      "native"
   :execute     (fn [args]
                  (try
                    {:output (str (f args)) :isError false}
                    ;; `Throwable` is the portable catch across JVM and cljgo
                    ;; (koine README: throw ex-info, catch Throwable). It is a
                    ;; plain symbol, not a java.* class name, so cljgo accepts
                    ;; it — which is why this needs no reader conditional.
                    (catch Throwable e
                      {:output (str (ex-message e)) :isError true})))})

;; ---------------------------------------------------------------------------
;; §0.9  http tools
;; ---------------------------------------------------------------------------

(defn- substitute
  "SPEC §0.9 — {placeholder} substitution from args."
  [template args]
  (reduce (fn [acc [k v]] (str/replace acc (str "{" (name k) "}") (str v)))
          (str template) args))

(defn http-tool
  "SPEC §0.9 — non-2xx => `HTTP <status>: <body>` isError, else the body text.
  Header values expand ${ENV} and are never logged."
  [tool-name description schema url headers]
  {:name        tool-name
   :description description
   :inputSchema schema
   :source      "http"
   :execute     (fn [args]
                  (let [res (http/request
                              {:method  :get
                               :url     (substitute url args)
                               :headers (reduce (fn [acc [k v]]
                                                  (assoc acc k (env/expand (str v))))
                                                {} headers)})]
                    (cond
                      (http/failed? res)
                      {:output (str "HTTP transport " (name (:error res))) :isError true}

                      (or (< (:status res) 200) (>= (:status res) 300))
                      {:output (str "HTTP " (:status res) ": " (:body res)) :isError true}

                      :else {:output (:body res) :isError false})))})

;; ---------------------------------------------------------------------------
;; the toolkit
;; ---------------------------------------------------------------------------

(defn toolkit [tools]
  (reduce (fn [acc t] (assoc acc (:name t) t)) {} tools))

(defn execute-tool [tk tool-name args]
  (if-let [t (get tk tool-name)]
    ((:execute t) args)
    {:output (str "unknown tool: " tool-name) :isError true}))

(defn execute-parallel
  "SPEC §8 — parallel tool calls. The whole point of this spike: `future` +
  `deref` must work on BOTH hosts, and results must come back in CALL ORDER
  regardless of completion order, or the model is fed a scrambled transcript."
  [tk calls]
  (->> calls
       (mapv (fn [c] (future (assoc c :result (execute-tool tk (:name c) (:args c))))))
       (mapv deref)))

;; ---------------------------------------------------------------------------
;; §0.10  the client loop, against a scripted local "LLM"
;; ---------------------------------------------------------------------------

(defn- openai-tool-calls [msg]
  (mapv (fn [tc] {:id   (:id tc)
                  :name (get-in tc [:function :name])
                  :args (json/read-str (or (get-in tc [:function :arguments]) "{}"))})
        (:tool_calls msg)))

(defn run-loop
  "SPEC §0.10 — call -> execute tool calls -> feed the results back -> repeat,
  bounded by max-turns. Returns {:turns :text :calls}."
  [tk base-url system user max-turns]
  (loop [messages [{:role "system" :content system}
                   {:role "user"   :content user}]
         turn     1
         executed []]
    (let [res  (http/post-json (str base-url "/v1/chat/completions")
                               {"content-type" "application/json"}
                               (json/write-str {:model "mock" :messages messages}))
          body (json/read-str (:body res))
          msg  (get-in body [:choices 0 :message])
          tcs  (openai-tool-calls msg)]
      (cond
        (empty? tcs)
        {:turns turn :text (:content msg) :calls executed}

        (>= turn max-turns)
        {:turns turn :text "max turns reached" :calls executed}

        :else
        (let [done (execute-parallel tk tcs)]
          (recur (into (conj messages msg)
                       (mapv (fn [d] {:role         "tool"
                                      :tool_call_id (:id d)
                                      :content      (:output (:result d))})
                             done))
                 (inc turn)
                 (into executed (mapv (fn [d] {:name   (:name d)
                                               :output (:output (:result d))
                                               :error  (:isError (:result d))})
                                      done))))))))

;; ---------------------------------------------------------------------------
;; the scripted LLM — a real HTTP server, not a stub
;; ---------------------------------------------------------------------------
;;
;; Turn 1 answers with TWO tool calls, so the parallel path is taken rather than
;; described. Turn 2 answers with text, which ends the loop.

(defn- llm-response [n]
  (if (= 1 n)
    {:choices [{:message {:role "assistant"
                          :content nil
                          :tool_calls [{:id "call_a" :type "function"
                                        :function {:name "upper" :arguments "{\"text\":\"toolnexus\"}"}}
                                       {:id "call_b" :type "function"
                                        :function {:name "boom" :arguments "{}"}}]}}]}
    {:choices [{:message {:role "assistant" :content "done"}}]}))

(defn scripted-llm!
  "Serves POST /v1/chat/completions, replying from the script above."
  []
  (let [n (atom 0)]
    (server/serve (fn [_req]
                    {:status  200
                     :headers {"content-type" "application/json"}
                     :body    (json/write-str (llm-response (swap! n inc)))})
                  {:port 0})))

;; ---------------------------------------------------------------------------
;; the report
;; ---------------------------------------------------------------------------

(defn run-slice []
  (let [llm  (scripted-llm!)
        base (str "http://127.0.0.1:" (server/port llm))]
    (try
      (let [tk (toolkit
                 [(native-tool "upper" "Uppercase the text" {:type "object"}
                               (fn [args] (str/upper-case (str (:text args)))))
                  (native-tool "boom" "Always throws" {:type "object"}
                               (fn [_] (throw (ex-info "kaboom" {}))))
                  ;; points at the same local server, so §0.9 is measured
                  ;; without reaching the network: /v1/chat/completions answers
                  ;; anything, and the 404 case uses a port nothing listens on.
                  (http-tool "fetch" "GET a local URL" {:type "object"}
                             (str base "/{path}")
                             {"x-token" "${TN_FAKE_TOKEN}"})])

            loop-result (run-loop tk base "you are a test" "go" 10)

            ;; §0.9 — a transport failure must be data, never a throw.
            dead        (execute-tool tk "fetch" {:path "x"})
            unreachable ((:execute (http-tool "dead" "" {} "http://127.0.0.1:1/{path}" {}))
                         {:path "x"})]
        {:host        (name host/id)
         :supports    {:spawn   (host/supports? :process/spawn)
                       :timeout (host/supports? :http/timeout)
                       :serve   (host/supports? :server/serve)}
         :native      {:ok    (:output ((:execute (get tk "upper")) {:text "abc"}))
                       :throw ((:execute (get tk "boom")) {})}
         :http        {:ok-status  (:isError dead)
                       :unreachable unreachable}
         :substitute  (substitute "http://h/{a}/x/{b}" {:a "1" :b "2"})
         :loop        loop-result})
      (finally (server/stop! llm)))))

(defn -main [& _]
  (println (json/write-str (run-slice))))
