;; ACP (Agent Client Protocol) model source — issue #96, ADR 0025,
;; openspec/changes/add-acp-model-source. Ports golang/acp.go (the reference,
;; already green) and elixir/lib/toolnexus/acp.ex (the closest functional
;; sibling) to this dual-host `.cljc` — zero reader conditionals, no java.*,
;; koine only.
;;
;; ACP is to *agents* what MCP is to *tools*: JSON-RPC 2.0, one object per
;; line, over a child process's stdin/stdout — the SAME framing `toolnexus.mcp`
;; already uses for local stdio servers (SPEC §2), so this file reuses
;; `koine.process/spawn` rather than hand-rolling a second subprocess path (the
;; portability spike, `spikes/portability/SPIKE.md`, proves `koine.process` is
;; already dual-host-proven for exactly this shape).
;;
;; A WARM SESSION (ADR 0025 — "the warm session is the feature"): `connect`
;; spawns the child and completes `initialize` + `session/new` (+ optional
;; `session/set_mode`) ONCE; every later turn is one `session/prompt` on that
;; same session. Concretely, mirroring the Go/Elixir gates:
;;
;;   - only `agent_message_chunk` `session/update` notifications accumulate
;;     into a turn's reply — `agent_thought_chunk` and tool-call narration are
;;     dropped, or they wrap prose around structured output and break JSON
;;     parsing outright;
;;   - replies to OUR calls and `session/update` notifications interleave on
;;     the same stream, so replies are demultiplexed by JSON-RPC id (the ONE
;;     reader thread files a reply in `:inbox`, keyed by id — same shape as
;;     `toolnexus.mcp`'s stdio reader);
;;   - `session/request_permission` is answered INLINE, from the reader
;;     thread, with the first `allow`-kind option, the instant it arrives —
;;     never exposed to the caller, never awaited. An unanswered permission
;;     request hangs the turn forever, even in bypass mode; answering
;;     immediately is what this module is FOR;
;;   - turns are serialised with a spin-lock (`:prompt-lock`, the same
;;     compare-and-set! pattern `toolnexus.mcp` uses to serialise stdin
;;     writes): one ACP session is one conversation, so a second concurrent
;;     `prompt`/`generate` call blocks until the first turn's reply lands
;;     rather than interleaving into the same transcript;
;;   - the child process's lifetime is independent of any one turn — a failed
;;     or timed-out turn does not kill the session — and `close` is
;;     idempotent (guarded by a compare-and-set! "close once" flag);
;;   - every turn sends the FULL assembled request (every message, every
;;     turn — matching every other toolnexus model source, which is
;;     stateless by default) PLUS an explicit supersedes marker BUILT BY THIS
;;     LIBRARY (never left to the caller), naming the current turn's content —
;;     the mitigation `spikes/acp/SPIKE.md` gate 1 proved against a stateful
;;     session that otherwise answers a near-duplicate, stale prompt.
;;
;; `generate` returns the exact `(fn [req] -> {:content ...})` shape
;; `toolnexus.client/create-in-process-client` and
;; `toolnexus.agents.runtime/create-runtime`'s `:in-process` option already
;; accept (see `toolnexus.agents.inprocess-test`), so the tool-calling loop,
;; skills, MCP and sub-agents are completely unmodified — ACP is a model
;; source, not a new tool source and not a new client.
(ns toolnexus.acp
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as ktime]))

;; ---------------------------------------------------------------------------
;; config
;; ---------------------------------------------------------------------------

(def ^:private default-timeout-ms
  "Bounds `initialize` / `session/new` / `session/set_mode` during connect.
  Default 30s."
  30000)

(def ^:private default-permission-timeout-ms
  "Bounds how long a turn's `session/prompt` reply is awaited. This is a
  SAFETY NET for a broken/misbehaving agent, not the normal path: a
  well-formed `session/request_permission` is answered immediately, from the
  reader thread, the instant it arrives — see `answer-permission!`. Default
  30s."
  30000)

;; acpSupersedesMarker's twin — the exact text is a CLIENT-SIDE convention,
;; not part of the ACP protocol, kept recognizable and stable so an agent (or
;; a test fixture) can key off it. Matches golang/acp.go's
;; acpSupersedesMarker and elixir/lib/toolnexus/acp.ex's @supersedes_marker
;; byte-for-byte.
(def ^:private supersedes-marker "SUPERSEDES-ALL-PRIOR:")

;; ---------------------------------------------------------------------------
;; small helpers shared with toolnexus.mcp's stdio transport
;; ---------------------------------------------------------------------------

(defn- with-lock!
  "Serialize `f` against concurrent callers via a compare-and-set! spin —
  the portable lock both hosts have (see toolnexus.mcp's `with-write-lock!`,
  which this mirrors). Contention here is microscopic (a line write, or one
  turn waiting on the previous), so spinning at 1 ms costs nothing."
  [lock f]
  (loop []
    (if (compare-and-set! lock false true)
      (try (f) (finally (reset! lock false)))
      (do (ktime/sleep! 1) (recur)))))

(defn- next-id!
  "Client-issued JSON-RPC ids, `c-1`, `c-2`, ... A counter, never a literal —
  see toolnexus.mcp's identical rationale."
  [counter]
  (str "c-" (swap! counter inc)))

;; ---------------------------------------------------------------------------
;; prompt assembly — full request every turn + the library-built supersedes
;; marker (ADR 0025's default; spikes/acp/SPIKE.md gate 1).
;; ---------------------------------------------------------------------------

(defn- msg-role [m]
  (str (or (:role m) (get m "role") "user")))

(defn- part-text
  "One element of a multimodal `:content` list rendered as text."
  [p]
  (cond
    (string? p) p
    (map? p)    (str (or (:text p) (get p "text") ""))
    :else       ""))

(defn- msg-text
  "A message's `:content` (string, a list of parts, or absent) as plain
  text."
  [m]
  (let [c (or (:content m) (get m "content"))]
    (cond
      (string? c)     c
      (sequential? c) (str/join "" (map part-text c))
      (nil? c)        ""
      :else           (json/write-str c))))

(defn assemble-prompt-text
  "Flatten `messages` into \"role: content\" lines — the FULL request, every
  turn (an ACP session is stateful, so sending only the delta would make this
  client a second, shadow copy of conversation state) — and append the
  supersedes marker naming the latest message's text, so a stateful agent
  answers the CURRENT request rather than an earlier near-duplicate already
  sitting in its own session history (ADR 0025)."
  [messages]
  (let [transcript (str/join "\n" (map (fn [m] (str (msg-role m) ": " (msg-text m))) messages))
        latest     (if (seq messages) (msg-text (last messages)) "")
        marker     (str supersedes-marker " " latest)]
    (if (str/blank? transcript) marker (str transcript "\n" marker))))

;; ---------------------------------------------------------------------------
;; wire I/O
;; ---------------------------------------------------------------------------

(defn- send-msg!
  "Write one JSON-RPC object + newline to the child's stdin. Serialised
  against concurrent writers (a permission answer from the reader thread and
  a `session/prompt` from a caller thread could otherwise interleave into one
  torn, unparseable line)."
  [client msg]
  (with-lock! (:write-lock client)
    (fn [] (proc/send-line! (:child client) (json/write-str msg)))))

(defn- answer-permission!
  "Answer a `session/request_permission` request with the first `allow`-kind
  option, inline — an unanswered permission request hangs the turn forever,
  even in bypass mode (ADR 0025)."
  [client req]
  (let [options (get-in req [:params :options])
        chosen  (some (fn [o] (when (str/starts-with? (str (:kind o)) "allow") (:optionId o)))
                       options)
        result  (if chosen
                  {:outcome {:outcome "selected" :optionId chosen}}
                  {:outcome {:outcome "cancelled"}})]
    (send-msg! client {:jsonrpc "2.0" :id (:id req) :result result})))

(defn- route!
  "Dispatch one parsed JSON-RPC line from the child:
    - an id with no method  -> a reply to one of OUR requests, filed by id
    - session/update        -> only agent_message_chunk text is accumulated
    - session/request_permission -> answered inline, never surfaced
    - anything else         -> ignored (nothing this client needs)."
  [client msg]
  (cond
    (and (nil? (:method msg)) (some? (:id msg)))
    (swap! (:state client) assoc-in [:inbox (:id msg)] msg)

    (= "session/update" (:method msg))
    (let [upd (get-in msg [:params :update])]
      (when (= "agent_message_chunk" (:sessionUpdate upd))
        (swap! (:state client) update :chunks conj (get-in upd [:content :text]))))

    (and (= "session/request_permission" (:method msg)) (some? (:id msg)))
    (answer-permission! client {:id (:id msg) :params (:params msg)})

    :else nil))

(defn- start-reader!
  "THE dedicated reader loop — exactly one per child (see toolnexus.mcp's
  identical rationale: two readers on one stdout would race for lines).
  `proc/run-async!` (a daemon thread on the JVM, a goroutine on cljgo) so a
  program that opened an ACP client is never held open by this loop."
  [client]
  (proc/run-async!
   (fn []
     (loop []
       (let [line (try (proc/read-line! (:child client)) (catch Throwable _ nil))]
         (if (nil? line)
           (swap! (:state client) assoc :closed? true :close-reason "peer-eof")
           (do
             (when-not (str/blank? line)
               (let [msg (try (json/read-str line) (catch Throwable _ ::bad))]
                 (when (and (not= ::bad msg) (map? msg))
                   (route! client msg))))
             (when-not (:closed? @(:state client)) (recur)))))))))

(defn- await-response!
  "Poll for `id`'s reply until it lands in :inbox, the transport closes, or
  `timeout-ms` elapses. Polling, not a timed deref — see toolnexus.mcp's
  identical rationale (a timed deref's portability across both hosts is not
  established; an atom and koine.time/sleep! are)."
  [state id timeout-ms]
  (let [deadline (+ (ktime/mono-ms) (long timeout-ms))]
    (loop []
      (let [s   (deref state)
            msg (get-in s [:inbox id])]
        (cond
          (some? msg)                   {:ok msg}
          (:closed? s)                  {:error (or (:close-reason s) "closed")}
          (>= (ktime/mono-ms) deadline) {:error "timeout"}
          :else                         (do (ktime/sleep! 2) (recur)))))))

(defn- unwrap-reply
  "A raw {:ok reply-msg} / {:error reason} from await-response! into
  {:ok result} / {:error ...}, translating a JSON-RPC `error` member into the
  same shape a transport failure already has."
  [res]
  (cond
    (:error res)                    res
    (get-in res [:ok :error])       {:error   "rpc-error"
                                     :code    (get-in res [:ok :error :code])
                                     :message (get-in res [:ok :error :message])}
    :else                           {:ok (get-in res [:ok :result])}))

(defn- call!
  "One request/response over the child — used for the one-shot setup calls
  (`initialize`, `session/new`, `session/set_mode`), never for
  `session/prompt` (see send-prompt!, which has its own permission-aware
  wait)."
  [client method params]
  (let [id (next-id! (:id-counter client))]
    (send-msg! client {:jsonrpc "2.0" :id id :method method :params (or params {})})
    (let [res (unwrap-reply (await-response! (:state client) id (:timeout-ms client)))]
      (swap! (:state client) update :inbox dissoc id)
      res)))

;; ---------------------------------------------------------------------------
;; connect
;; ---------------------------------------------------------------------------

(defn- kill-and-throw!
  "A handshake phase (`initialize` / `session/new` / `session/set_mode`)
  failed: force-kill the half-open child so `connect` never leaks a process,
  then throw."
  [client phase res]
  (try (proc/kill! (:child client)) (catch Throwable _ nil))
  (throw (ex-info (str "toolnexus: acp: " phase ": " (pr-str (:error res))) (assoc res :phase phase))))

(defn connect
  "Spawn `command` (a vector, e.g. [\"devin\" \"acp\"]) and complete
  `initialize` -> `session/new` (-> optional `session/set_mode`). Returns a
  live, warm client map. Throws (ex-info) on any handshake failure — a model
  source that cannot connect is a construction-time error, matching
  `toolnexus.agents.runtime/create-runtime`'s own validation.

  opts:
    :cwd                 absolute working directory handed to `session/new`.
                          A real `devin acp` REJECTS session/new with -32602
                          without an absolute cwd (ADR 0025) — defaults to
                          the process's own resolved cwd (`koine.fs/real-path`
                          on \".\"), which is always absolute.
    :mcp-servers          the `mcpServers` array on `session/new` (default
                          []). Real `devin acp` requires the key to be
                          present, even empty.
    :environment / :env   extra env vars for the child (merged; matches
                          toolnexus.mcp's `environment`/`env` alias).
    :mode                 if set, issues an optional `session/set_mode` after
                          `session/new`.
    :timeout               per-call timeout ms for the setup handshake.
                          Default 30000.
    :permission-timeout    the safety net bounding a `session/prompt` reply
                          — see default-permission-timeout-ms. Default 30000."
  ([command] (connect command {}))
  ([command opts]
   (let [{:keys [cwd mcp-servers environment env mode timeout permission-timeout]} opts
         timeout-ms            (or timeout default-timeout-ms)
         permission-timeout-ms (or permission-timeout default-permission-timeout-ms)
         cwd                   (or cwd (fs/real-path "."))]
     (when-not (str/starts-with? (str cwd) "/")
       (throw (ex-info (str "toolnexus: acp: :cwd must be absolute, got " (pr-str cwd))
                       {:cwd cwd})))
     (let [env-map    (merge (or environment {}) (or env {}))
           child      (proc/spawn (vec command) (if (seq env-map) {:env env-map} {}))
           state      (atom {:inbox {} :closed? false :close-reason nil :chunks []})
           write-lock (atom false)
           client     {:child               child
                       :state               state
                       :write-lock          write-lock
                       :prompt-lock         (atom false)
                       :id-counter          (atom 0)
                       :session-id          nil
                       :timeout-ms          timeout-ms
                       :permission-timeout-ms permission-timeout-ms
                       :close-once          (atom false)}]
       (start-reader! client)
       (let [init-res (call! client "initialize"
                              {:protocolVersion   1
                               :clientCapabilities {:fs {:readTextFile false :writeTextFile false}}})]
         (when (:error init-res)
           (kill-and-throw! client "initialize" init-res))
         (let [new-res (call! client "session/new" {:cwd cwd :mcpServers (or mcp-servers [])})]
           (when (:error new-res)
             (kill-and-throw! client "session/new" new-res))
           (let [session-id (get-in new-res [:ok :sessionId])]
             (when (str/blank? (str session-id))
               (kill-and-throw! client "session/new" {:error "no sessionId in response"}))
             (let [client (assoc client :session-id session-id)]
               (when mode
                 (let [mode-res (call! client "session/set_mode" {:sessionId session-id :modeId mode})]
                   (when (:error mode-res)
                     (kill-and-throw! client "session/set_mode" mode-res))))
               client))))))))

;; ---------------------------------------------------------------------------
;; prompt / generate
;; ---------------------------------------------------------------------------

(defn- send-prompt!
  "One session/prompt turn, accumulating only agent_message_chunk text.
  Not serialised itself — the caller (`prompt`) holds :prompt-lock for the
  whole call, matching golang/acp.go's `promptMu`."
  [client text]
  (if (:closed? @(:state client))
    {:error (or (:close-reason @(:state client)) "closed")}
    (let [id (next-id! (:id-counter client))]
      (swap! (:state client) assoc :chunks [])
      (send-msg! client {:jsonrpc "2.0" :id id :method "session/prompt"
                         :params {:sessionId (:session-id client)
                                  :prompt    [{:type "text" :text text}]}})
      (let [res (unwrap-reply (await-response! (:state client) id (:permission-timeout-ms client)))]
        (swap! (:state client) update :inbox dissoc id)
        (if (:error res)
          res
          {:ok (str/join "" (:chunks @(:state client)))})))))

(defn prompt
  "Send one turn's FULL prompt text and wait for the accumulated
  agent_message_chunk reply. Turns on the same client are serialised —
  {:ok content} or {:error reason}."
  [client text]
  (with-lock! (:prompt-lock client) (fn [] (send-prompt! client text))))

(defn generate
  "Build a semantic `generate` fn — (fn [req] -> {:content ...}) — backed by
  this warm ACP client, in the exact shape
  `toolnexus.client/create-in-process-client` and
  `toolnexus.agents.runtime/create-runtime`'s `:in-process` option accept.

    (let [c (toolnexus.acp/connect [\"devin\" \"acp\"])]
      (toolnexus.client/create-in-process-client
        {:model \"devin\" :generate (toolnexus.acp/generate c)}))

  Throws on an ACP-level failure (a closed session, a timed-out call, an RPC
  error) — the same failure shape any other `generate` fn raising surfaces to
  the caller."
  [client]
  (fn [req]
    (let [text (assemble-prompt-text (:messages req))
          res  (prompt client text)]
      (if (:error res)
        (throw (ex-info (str "toolnexus: acp: generate failed: " (pr-str (:error res)))
                        {:acp-error (:error res)}))
        {:content (:ok res)}))))

;; ---------------------------------------------------------------------------
;; close
;; ---------------------------------------------------------------------------

(defn close
  "Terminate the child process. Idempotent — calling it more than once past
  the first call is a no-op (guarded by a compare-and-set! \"close once\"
  flag, the portable equivalent of Go's sync.Once)."
  [client]
  (when (compare-and-set! (:close-once client) false true)
    (swap! (:state client) assoc :closed? true :close-reason "closed")
    (try (proc/kill! (:child client)) (catch Throwable _ nil)))
  nil)
