;; S20 — SPEC §7A, A2A agents OUTBOUND (source "a2a"), on both hosts.
;;
;; The question: can toolnexus CALL a remote A2A agent and expose its skills as
;; tools, in portable Clojure? §7A is the first tool source that is BOTH a
;; network client and a state machine:
;;
;;   resolve   GET /.well-known/agent-card.json -> one Tool per advertised SKILL
;;             name = sanitize(card.name)_sanitize(skill.id ?? skill.name)
;;   execute   ONE JSON-RPC `SendMessage`, then poll `GetTask` (every pollEvery)
;;             until a terminal state: completed | failed | canceled
;;   map       completed => joined text parts of artifacts[].parts[] (fallback:
;;             the last role:"agent" history message)
;;             failed/canceled => "A2A task <id> <state>[: <status.message text>]"
;;             timeout        => "A2A task <id> timed out after <ms>ms (state=<state>)"
;;             abort          => "A2A task <id> canceled"
;;             metadata on EVERY result = {agent, taskId, state, polls, ms}
;;
;; The remote agent is a koine.server on 127.0.0.1:0 that this file scripts, so
;; the whole §7A wire — card fetch, JSON-RPC envelope, submit->poll, all five
;; terminal outcomes — is measured, not mocked. Hermetic: no LLM, no key, no
;; internet. Timeout budget is 300ms so the spike stays fast.
;;
;; No reader conditional, no java.*, no Go interop.
;;
;; Run: see run-both.sh.

(ns toolnexus.a2aout
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.host :as host]
            [koine.http :as http]
            [koine.json :as json]
            [koine.server :as server]
            [koine.time :as time]))

;; ---------------------------------------------------------------------------
;; §7A defaults
;; ---------------------------------------------------------------------------

(def default-timeout 300000)
(def default-poll-every 1000)

(def terminal-states #{"completed" "failed" "canceled"})

;; §0.2 — the same sanitize every source shares.
(defn sanitize [s] (str/replace (str s) #"[^a-zA-Z0-9_-]" "_"))

;; §7A — the inputSchema every a2a tool carries, verbatim.
(def task-schema
  {:type       "object"
   :properties {:task {:type "string"
                       :description "The task to send to the agent, in natural language."}}
   :required   ["task"]
   :additionalProperties false})

;; ---------------------------------------------------------------------------
;; JSON-RPC transport — one POST, reusing httpTool's non-2xx mapping.
;; ---------------------------------------------------------------------------

(defn- rpc!
  "One JSON-RPC 2.0 POST. Returns {:result …} or {:error \"…\"} — never throws.
  Header values are passed through verbatim and never logged."
  [endpoint method params headers timeout-ms]
  (let [res (http/request {:method     :post
                           :url        endpoint
                           :headers    (assoc headers "content-type" "application/json")
                           :body       (json/write-str {:jsonrpc "2.0"
                                                        :id      (str (random-uuid))
                                                        :method  method
                                                        :params  params})
                           :timeout-ms timeout-ms})]
    (cond
      (http/failed? res)
      {:error (str "HTTP transport " (name (:error res)))}

      (or (< (:status res) 200) (>= (:status res) 300))
      {:error (str "HTTP " (:status res) ": " (:body res))}

      :else
      (let [payload (try (json/read-str (:body res)) (catch Throwable _ nil))
            err     (:error payload)]
        (if err
          {:error (or (:message err) (str "JSON-RPC error " (:code err)))}
          {:result (:result payload)})))))

(defn- fetch-card!
  "GET the Agent Card. {:card …} or {:error \"…\"}."
  [card-url headers timeout-ms]
  (let [res (http/request {:method :get :url card-url :headers headers :timeout-ms timeout-ms})]
    (cond
      (http/failed? res) {:error (str "HTTP transport " (name (:error res)))}
      (or (< (:status res) 200) (>= (:status res) 300))
      {:error (str "HTTP " (:status res) ": " (:body res))}
      :else (try {:card (json/read-str (:body res))}
                 (catch Throwable e {:error (str "bad card: " (ex-message e))})))))

;; ---------------------------------------------------------------------------
;; Task -> text
;; ---------------------------------------------------------------------------

(defn- text-parts
  "Every kind:\"text\" part's text, in order."
  [parts]
  (->> parts
       (filter (fn [p] (and (= "text" (:kind p)) (string? (:text p)))))
       (mapv :text)))

(defn- extract-output
  "§7A — all kind:\"text\" parts across artifacts[].parts[] joined by \"\\n\";
  fallback: the last role:\"agent\" history message's text."
  [task]
  (let [from-artifacts (vec (mapcat (fn [a] (text-parts (:parts a))) (:artifacts task)))]
    (if (seq from-artifacts)
      (str/join "\n" from-artifacts)
      (or (->> (:history task)
               (filter (fn [m] (= "agent" (:role m))))
               (mapv (fn [m] (str/join "\n" (text-parts (:parts m)))))
               (filter seq)
               last)
          ""))))

(defn- status-message-text
  "Text of status.message — the optional detail on failed/canceled."
  [task]
  (str/join "\n" (text-parts (get-in task [:status :message :parts]))))

;; ---------------------------------------------------------------------------
;; execute — SendMessage, then poll GetTask to a terminal state
;; ---------------------------------------------------------------------------

(defn- aborted? [ctx]
  (boolean (when-let [f (:aborted? ctx)] (f))))

(defn- a2a-execute
  [{:keys [agent-name endpoint headers timeout-ms poll-every]} args ctx]
  (let [started   (time/mono-ms)
        task-text (str (:task args))
        meta      (fn [task-id state polls]
                    {:agent  agent-name
                     :taskId task-id
                     :state  state
                     :polls  polls
                     :ms     (time/elapsed-ms started)})
        sent      (rpc! endpoint "SendMessage"
                        {:message       {:role      "user"
                                         :messageId (str (random-uuid))
                                         :parts     [{:kind "text" :text task-text}]}
                         :configuration {:blocking false}}
                        headers timeout-ms)]
    (if (:error sent)
      {:output (:error sent) :isError true :metadata (meta "" "submitted" 0)}
      (loop [task  (:result sent)
             polls 0]
        (let [task-id (or (:id task) "")
              state   (or (get-in task [:status :state]) "submitted")]
          (cond
            ;; terminal — map the Task to a ToolResult
            (contains? terminal-states state)
            (if (= "completed" state)
              {:output (extract-output task) :isError false :metadata (meta task-id state polls)}
              (let [detail (status-message-text task)]
                {:output   (str "A2A task " task-id " " state
                                (when (seq detail) (str ": " detail)))
                 :isError  true
                 :metadata (meta task-id state polls)}))

            ;; §7A poll loop order: abort -> timeout -> sleep -> abort -> GetTask
            (aborted? ctx)
            {:output (str "A2A task " task-id " canceled") :isError true
             :metadata (meta task-id "canceled" polls)}

            (>= (time/elapsed-ms started) timeout-ms)
            {:output   (str "A2A task " task-id " timed out after " timeout-ms
                            "ms (state=" state ")")
             :isError  true
             :metadata (meta task-id state polls)}

            :else
            (do
              (time/sleep! poll-every)
              (if (aborted? ctx)
                {:output (str "A2A task " task-id " canceled") :isError true
                 :metadata (meta task-id "canceled" polls)}
                (let [got (rpc! endpoint "GetTask" {:id task-id} headers timeout-ms)]
                  (if (:error got)
                    {:output (:error got) :isError true :metadata (meta task-id state polls)}
                    (recur (:result got) (inc polls))))))))))))

;; ---------------------------------------------------------------------------
;; resolve — card -> one Tool per skill
;; ---------------------------------------------------------------------------

(defn- origin
  "The scheme://host:port of a URL — the §7A endpoint fallback when the card
  carries no `url`. String arithmetic, not java.net.URL."
  [url]
  (str/join "/" (take 3 (str/split (str url) #"/"))))

(defn- skill-tool [conn skill]
  (let [skill-id (or (:id skill) (:name skill) "")]
    {:name        (str (sanitize (:agent-name conn)) "_" (sanitize skill-id))
     :description (or (:description skill) (:name skill) skill-id)
     :inputSchema task-schema
     :source      "a2a"
     :execute     (fn [args ctx] (a2a-execute conn args ctx))}))

(defn resolve-agent
  "§7A — fetch the Agent Card and emit one source:\"a2a\" Tool per advertised
  skill. A failing agent is ISOLATED: {:tools [] :error \"…\"}, never fatal.

  NOTE the name: SPEC §7A calls this factory `agent()`, but `agent` is
  clojure.core/agent — see the README finding."
  [{:keys [card headers timeout pollEvery]}]
  (let [timeout-ms (or timeout default-timeout)
        poll-every (or pollEvery default-poll-every)
        expanded   (reduce (fn [acc [k v]] (assoc acc (name k) (env/expand (str v))))
                           {} headers)
        fetched    (fetch-card! card expanded timeout-ms)]
    (if (:error fetched)
      {:tools [] :error (:error fetched) :card nil}
      (let [c    (:card fetched)
            conn {:agent-name (or (:name c) "agent")
                  :endpoint   (or (:url c) (origin card))
                  :headers    expanded
                  :timeout-ms timeout-ms
                  :poll-every poll-every}]
        {:card c
         :endpoint (:endpoint conn)
         :tools (mapv (fn [s] (skill-tool conn s)) (:skills c))}))))

;; ---------------------------------------------------------------------------
;; The remote A2A agent — a scripted koine.server, not a stub
;; ---------------------------------------------------------------------------
;;
;; Task ids are FIXED strings, not uuids, so the error strings this spike must
;; prove byte-for-byte ("A2A task task-fail failed: boom") are deterministic.
;; polls/ms are not, which is why the report never prints them.

(def ^:private script
  {"ok"     "task-ok"
   "hist"   "task-hist"
   "fail"   "task-fail"
   "cancel" "task-cancel"
   "hang"   "task-hang"})

(defn- task-state
  "The Task the agent reports for `kind` after `n` GetTask polls."
  [kind n]
  (let [task-id (get script kind "task-unknown")]
    (cond
      ;; still working on the first poll — proves the loop really polls
      (and (not= "hang" kind) (< n 1))
      {:id task-id :status {:state "working"}}

      (= "ok" kind)
      {:id task-id :status {:state "completed"}
       :artifacts [{:artifactId "artifact-1"
                    :parts [{:kind "text" :text "line one"}
                            {:kind "data" :data {:ignored true}}
                            {:kind "text" :text "line two"}]}]}

      ;; no artifacts => the history fallback (LAST role:"agent" message)
      (= "hist" kind)
      {:id task-id :status {:state "completed"}
       :history [{:role "user"  :parts [{:kind "text" :text "hist"}]}
                 {:role "agent" :parts [{:kind "text" :text "earlier reply"}]}
                 {:role "agent" :parts [{:kind "text" :text "final reply"}]}]}

      (= "fail" kind)
      {:id task-id :status {:state "failed"
                            :message {:role "agent"
                                      :parts [{:kind "text" :text "boom"}]}}}

      ;; canceled with NO status.message — proves the "[: <text>]" is optional
      (= "cancel" kind)
      {:id task-id :status {:state "canceled"}}

      ;; never leaves working — the timeout path
      :else
      {:id task-id :status {:state "working"}})))

(defn- agent-card [base with-url?]
  (merge {:name              "Demo Bot"
          :description       "A scripted A2A peer"
          :version           "0.1.0"
          :protocolVersion   "0.3.0"
          :capabilities      {:streaming false :pushNotifications false}
          :defaultInputModes ["text"]
          :defaultOutputModes ["text"]
          :skills [{:id "echo" :name "Echo" :description "Echo a task back"}
                   ;; no :id — the tool name must fall back to sanitize(name)
                   {:name "Slow Work" :description "Never finishes"}
                   ;; a dotted id — proves sanitize is applied to the skill id
                   {:id "fail.now" :name "Fail" :description "Always fails"}]}
         (when with-url? {:url (str base "/")})))

(defn- rpc-handler [polls body]
  (let [req    (try (json/read-str body) (catch Throwable _ nil))
        method (:method req)
        rpc-id (:id req)]
    (cond
      (nil? req)
      {:jsonrpc "2.0" :id nil :error {:code -32700 :message "parse error"}}

      (= "SendMessage" method)
      (let [text (or (get-in req [:params :message :parts 0 :text]) "")
            kind (if (contains? script text) text "hang")
            tid  (get script kind)]
        (swap! polls assoc tid 0)
        {:jsonrpc "2.0" :id rpc-id :result {:id tid :status {:state "submitted"}}})

      (= "GetTask" method)
      (let [tid  (get-in req [:params :id])
            kind (or (some (fn [[k v]] (when (= v tid) k)) script) "hang")
            n    (get (swap! polls update tid (fn [x] (inc (or x 0)))) tid)]
        {:jsonrpc "2.0" :id rpc-id :result (task-state kind n)})

      :else
      {:jsonrpc "2.0" :id rpc-id :error {:code -32601 :message "method not found"}})))

(defn start-agent!
  "The remote A2A agent on 127.0.0.1:0. One handler, dispatching on :path —
  koine.server has no routing table, by design."
  []
  (let [polls (atom {})
        base  (atom "")
        h     (server/serve
                (fn [req]
                  (let [p (:path req)]
                    (cond
                      (= p "/.well-known/agent-card.json")
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (agent-card @base true))}

                      ;; a card with NO `url` — the endpoint must fall back to
                      ;; the card URL's origin
                      (= p "/.well-known/agent-card-nourl.json")
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (agent-card @base false))}

                      (= :post (:method req))
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (rpc-handler polls (:body req)))}

                      :else {:status 404 :body "no card here"})))
                {:port 0})]
    (reset! base (str "http://127.0.0.1:" (server/port h)))
    {:handle h :base @base}))

;; ---------------------------------------------------------------------------
;; The report
;; ---------------------------------------------------------------------------

(defn- call [tools tool-name task ctx]
  (let [t (first (filter (fn [x] (= tool-name (:name x))) tools))
        r ((:execute t) {:task task} ctx)]
    {:isError      (:isError r)
     :output       (:output r)
     ;; polls/ms are non-deterministic BY CONSTRUCTION — report the KEYS only.
     :metadataKeys (vec (sort (mapv name (keys (:metadata r)))))
     :metadataStable (select-keys (:metadata r) [:agent :taskId :state])}))

(defn run-spike []
  (let [{:keys [handle base]} (start-agent!)]
    (try
      (let [resolved (resolve-agent {:card      (str base "/.well-known/agent-card.json")
                                     :headers   {"x-a2a-key" "${TN_A2A_TOKEN}"}
                                     :timeout   300
                                     :pollEvery 60})
            tools    (:tools resolved)
            no-url   (resolve-agent {:card (str base "/.well-known/agent-card-nourl.json")
                                     :timeout 300 :pollEvery 60})
            missing  (resolve-agent {:card (str base "/.well-known/nope.json")
                                     :timeout 300 :pollEvery 60})
            card     (:card resolved)]
        {:host (name host/id)
         :defaults {:timeout default-timeout :pollEvery default-poll-every
                    :terminal (vec (sort terminal-states))}
         :card {:name (:name card)
                :version (:version card)
                :protocolVersion (:protocolVersion card)
                :capabilities (:capabilities card)
                :defaultInputModes (:defaultInputModes card)
                :defaultOutputModes (:defaultOutputModes card)
                :skillCount (count (:skills card))}
         ;; secrets are use-only: the KEY, and whether ${ENV} changed the value.
         :headers {:keys ["x-a2a-key"]
                   :expandedChangedValue (not= "${TN_A2A_TOKEN}"
                                               (env/expand "${TN_A2A_TOKEN}"))}
         :endpoint {:fromCardUrl  (= (:endpoint resolved) (str base "/"))
                    :originFallback (= (:endpoint no-url) (origin base))}
         ;; a failing agent is isolated, never fatal
         :failedAgent {:toolCount (count (:tools missing))
                       :error (:error missing)}
         :tools (mapv (fn [t] {:name (:name t) :source (:source t)
                               :description (:description t)
                               :inputSchema (:inputSchema t)})
                      tools)
         :completed      (call tools "Demo_Bot_echo" "ok" nil)
         :historyFallback (call tools "Demo_Bot_echo" "hist" nil)
         :failed         (call tools "Demo_Bot_fail_now" "fail" nil)
         :canceled       (call tools "Demo_Bot_echo" "cancel" nil)
         :timedOut       (call tools "Demo_Bot_Slow_Work" "hang" nil)
         :aborted        (call tools "Demo_Bot_Slow_Work" "hang"
                               {:aborted? (fn [] true)})})
      (finally (server/stop! handle)))))

(defn -main [& _]
  (println (json/write-str (run-spike))))
