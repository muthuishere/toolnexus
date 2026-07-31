;; SPEC §7A — A2A agents, OUTBOUND (source: "a2a").
;;
;; Call a remote A2A agent and expose each of its advertised SKILLS as a Tool.
;; This is the first tool source that is both a network client AND a state
;; machine, so it is written as two halves that never mix:
;;
;;   remote-agent   GET <card> -> AgentCard -> one Tool per advertised skill
;;                  name = sanitize(card.name) "_" sanitize(skill.id ?? skill.name)
;;   execute        ONE JSON-RPC `SendMessage`, then poll `GetTask` every
;;                  `poll-every` ms until a terminal state
;;                  (completed | failed | canceled), a timeout, or a ctx abort.
;;
;; The OUTPUT STRINGS ARE THE CONTRACT. Six ports must produce the same bytes:
;;
;;   completed      all kind:"text" parts across artifacts[].parts[] joined "\n"
;;                  (fallback: the LAST role:"agent" history message's text)
;;   failed|canceled  "A2A task <id> <state>[: <status.message text>]"
;;   timeout          "A2A task <id> timed out after <ms>ms (state=<state>)"
;;   ctx abort        "A2A task <id> canceled"
;;   metadata (every result)  {agent, taskId, state, polls, ms}
;;
;; ---------------------------------------------------------------------------
;; NAMING — why this is not `agent`.
;; ---------------------------------------------------------------------------
;; SPEC §7A names the factory `agent({card, ...})`. In Clojure that is
;; `clojure.core/agent` (STM agents). Shadowing a clojure.core name with a def
;; is banned by the port's spike brief for a concrete reason: cljgo's static
;; Java-interop scan reads a bare core-shaped symbol as a JVM special form and
;; rejects THE WHOLE NAMESPACE, not just that fn. So the factory is
;; `remote-agent` — `a2a/remote-agent` at the call site, unambiguous, and it
;; cannot collide. (Spike S20 called it `resolve-agent`; `resolve` is also a
;; clojure.core name, so `remote-agent` is the safer of the two.)
;;
;; Portability: no reader conditional, no java.*, koine only.
(ns toolnexus.a2a
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.http :as http]
            [koine.json :as json]
            [koine.time :as time]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; §7A defaults and vocabulary
;; ---------------------------------------------------------------------------

(def default-timeout
  "§7A: `timeout` defaults to 300000ms."
  300000)

(def default-poll-every
  "§7A: `pollEvery` defaults to 1000ms."
  1000)

(def task-states
  "§7A TaskState, in full."
  #{"submitted" "working" "completed" "failed" "canceled"})

(def terminal-states
  "§7A: terminal = completed / failed / canceled."
  #{"completed" "failed" "canceled"})

(def task-input-schema
  "§7A, verbatim: `{type:\"object\", properties:{task:{type:\"string\"}},
  required:[\"task\"]}`. Kept literally to the spec — S20 carried an extra
  `description` on the property, which is friendlier to a model but is drift the
  other five ports would not reproduce."
  {:type       "object"
   :properties {:task {:type "string"}}
   :required   ["task"]})

;; ---------------------------------------------------------------------------
;; transport — one JSON-RPC POST, reusing httpTool's non-2xx mapping (§7A)
;; ---------------------------------------------------------------------------

(defn- expand-headers
  "§7A: `headers` support `${ENV}` expansion, and are NEVER logged. Nothing in
  this namespace prints a header value."
  [headers]
  (reduce (fn [acc [k v]] (assoc acc (name k) (env/expand (str v)))) {} headers))

(defn- transport-error
  "koine returns a transport failure as DATA (`{:status nil :error :timeout|:dns|
  :connect-failed|:transport}`), never a throw.

  DECISION (§7A ambiguity 3 — see the README of spike S20): §7A says the source
  \"reuses httpTool's ${ENV} header expansion + timeout + non-2xx mapping\", so a
  non-2xx is `HTTP <status>: <body>`, but it says NOTHING about a transport
  failure or a JSON-RPC `error` object arriving mid-poll. There is no JS analogue
  to copy, because `fetch` throws and JS's §7A lets that propagate into the same
  isError ToolResult. We render it as `HTTP transport <kind>` and return an error
  ToolResult carrying the metadata built so far — never a throw, because a
  failing agent is isolated in §7A, never fatal."
  [res]
  (str "HTTP transport " (name (:error res))))

(defn- rpc!
  "One JSON-RPC 2.0 POST. Returns {:result …} or {:error \"…\"}; never throws."
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
      {:error (transport-error res)}

      (or (< (:status res) 200) (>= (:status res) 300))
      {:error (str "HTTP " (:status res) ": " (:body res))}

      :else
      (let [payload (try (json/read-str (:body res)) (catch Throwable _ nil))
            rpc-err (:error payload)]
        (cond
          (nil? payload) {:error "invalid JSON-RPC response"}
          rpc-err        {:error (or (:message rpc-err)
                                     (str "JSON-RPC error " (:code rpc-err)))}
          :else          {:result (:result payload)})))))

(defn- fetch-card!
  "GET the Agent Card. {:card …} or {:error \"…\"}."
  [card-url headers timeout-ms]
  (let [res (http/request {:method :get :url card-url
                           :headers headers :timeout-ms timeout-ms})]
    (cond
      (http/failed? res) {:error (transport-error res)}

      (or (< (:status res) 200) (>= (:status res) 300))
      {:error (str "HTTP " (:status res) ": " (:body res))}

      :else (try {:card (json/read-str (:body res))}
                 (catch Throwable e {:error (str "bad card: " (or (ex-message e) (str e)))})))))

;; ---------------------------------------------------------------------------
;; Task -> text
;; ---------------------------------------------------------------------------

(defn- text-parts
  "Every kind:\"text\" part's text, in order. Other kinds are skipped."
  [parts]
  (->> parts
       (filter (fn [p] (and (= "text" (:kind p)) (string? (:text p)))))
       (mapv :text)))

(defn task-output
  "§7A `completed` mapping: all kind:\"text\" parts across `artifacts[].parts[]`
  joined by \"\\n\"; fallback = the LAST role:\"agent\" history message's text."
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

(defn- status-detail
  "The `[: <status.message text>]` half of the failed/canceled string — absent
  when the Task carries no status.message."
  [task]
  (str/join "\n" (text-parts (get-in task [:status :message :parts]))))

;; ---------------------------------------------------------------------------
;; execute — SendMessage, then poll GetTask to a terminal state
;; ---------------------------------------------------------------------------

(defn- aborted?
  "`ctx` abort. §0.3's Context carries an abort predicate; a ctx without one is
  simply never aborted."
  [ctx]
  (boolean (when-let [f (:aborted? ctx)] (f))))

(defn- run-task
  [{:keys [agent-name endpoint headers timeout-ms poll-every]} args ctx]
  (let [started   (time/mono-ms)
        task-text (str (:task args))
        ;; §7A: `metadata` on EVERY result = {agent, taskId, state, polls, ms}.
        ;;
        ;; DECISION (§7A ambiguity 1): the paragraph uses "ms" twice with two
        ;; different meanings — the message `timed out after <ms>ms` and
        ;; `metadata.ms`. `metadata.ms` is ELAPSED wall time; the message carries
        ;; the CONFIGURED BUDGET (below). That split is the JS reference's
        ;; reading and the only one that makes the string deterministic.
        ;;
        ;; DECISION (§7A ambiguity 2): `polls` is the number of SUCCESSFUL
        ;; `GetTask` responses. The initial `SendMessage` is not a poll and does
        ;; not count, and a GetTask that failed in transport does not count.
        meta-for  (fn [task-id state polls]
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
      (tool/failure (:error sent) (meta-for "" "submitted" 0))
      (loop [task  (:result sent)
             polls 0]
        (let [task-id (or (:id task) "")
              state   (or (get-in task [:status :state]) "submitted")]
          (cond
            ;; ---- terminal: map the Task to a ToolResult
            (contains? terminal-states state)
            (if (= "completed" state)
              (tool/success (task-output task) (meta-for task-id state polls))
              (let [detail (status-detail task)]
                (tool/failure (str "A2A task " task-id " " state
                               (when (seq detail) (str ": " detail)))
                          (meta-for task-id state polls))))

            ;; ---- §7A poll loop order: abort -> timeout -> sleep -> abort -> GetTask
            ;;
            ;; DECISION: on abort, `metadata.state` is "canceled" — the LOCAL
            ;; verdict, not the remote Task's state (which is still `working`).
            ;; §7A pins only the output string. Matches the JS reference.
            (aborted? ctx)
            (tool/failure (str "A2A task " task-id " canceled")
                      (meta-for task-id "canceled" polls))

            (>= (time/elapsed-ms started) timeout-ms)
            (tool/failure (str "A2A task " task-id " timed out after " timeout-ms
                           "ms (state=" state ")")
                      (meta-for task-id state polls))

            :else
            (do
              (time/sleep! poll-every)
              (if (aborted? ctx)
                (tool/failure (str "A2A task " task-id " canceled")
                          (meta-for task-id "canceled" polls))
                (let [got (rpc! endpoint "GetTask" {:id task-id} headers timeout-ms)]
                  (if (:error got)
                    ;; transport / JSON-RPC error mid-poll — see `transport-error`
                    (tool/failure (:error got) (meta-for task-id state polls))
                    (recur (:result got) (inc polls))))))))))))

;; ---------------------------------------------------------------------------
;; resolve — one card, one Tool per advertised skill
;; ---------------------------------------------------------------------------

(defn- origin
  "The scheme://host:port of a URL — §7A's JSON-RPC endpoint fallback when the
  card carries no `url`. String arithmetic, because no host URL parser is
  portable across four runtimes."
  [url]
  (str/join "/" (take 3 (str/split (str url) #"/"))))

(defn- skill-tool
  "§7A tool naming: sanitize(card.name) \"_\" sanitize(skill.id ?? skill.name)."
  [conn skill]
  (let [skill-id (or (:id skill) (:name skill) "")]
    (tool/tool
      {:name         (str (tool/sanitize (:agent-name conn)) "_" (tool/sanitize skill-id))
       :description  (or (:description skill) (:name skill) skill-id)
       :input-schema task-input-schema
       :source       "a2a"
       ;; two arities: toolnexus.tool/execute passes ctx only when it has one.
       :execute      (fn ([args]     (run-task conn args nil))
                         ([args ctx] (run-task conn args ctx)))})))

(defn remote-agent
  "§7A's `agent({card, headers?, timeout?, pollEvery?})` — renamed, see the
  namespace comment. Fetches the Agent Card and returns

    {:card <AgentCard> :endpoint <json-rpc url> :tools [Tool …]}

  A FAILING AGENT IS ISOLATED, never fatal (like MCP): `{:tools [] :error \"…\"
  :card nil}`. Accepts both `:poll-every` and §7A's config-file spelling
  `:pollEvery`."
  [{:keys [card headers timeout poll-every pollEvery]}]
  (let [timeout-ms (or timeout default-timeout)
        every-ms   (or poll-every pollEvery default-poll-every)
        expanded   (expand-headers headers)
        fetched    (fetch-card! card expanded timeout-ms)]
    (if (:error fetched)
      {:tools [] :error (:error fetched) :card nil}
      (let [c    (:card fetched)
            conn {:agent-name (or (:name c) "agent")
                  ;; §7A: JSON-RPC endpoint = card.url, fallback = card URL origin.
                  :endpoint   (or (:url c) (origin card))
                  :headers    expanded
                  :timeout-ms timeout-ms
                  :poll-every every-ms}]
        {:card     c
         :endpoint (:endpoint conn)
         :tools    (mapv (fn [s] (skill-tool conn s)) (:skills c))}))))

(defn agent-tools
  "Convenience: just the tools, isolating a failing agent to `[]`."
  [opts]
  (:tools (remote-agent opts)))
