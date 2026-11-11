;; ACP (Agent Client Protocol) model source — issue #96, ADR 0031,
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
;; A WARM SESSION (ADR 0031 — "the warm session is the feature"): `connect`
;; spawns the child and completes `initialize` + `session/new` (+ optional
;; `session/set_mode`) ONCE; every later turn is one `session/prompt` on that
;; same session. Concretely, mirroring the Go/Elixir gates:
;;
;;   - only `agent_message_chunk` `session/update` notifications accumulate
;;     into a turn's reply — `agent_thought_chunk` and tool-call narration are
;;     kept out of it, or they wrap prose around structured output and break
;;     JSON parsing outright (`generate-stream` still REPORTS them, as
;;     `thought` / `tool_call` / `tool_result` events, never as answer text);
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
            [koine.env :as env]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.builtin :as builtin]
            [toolnexus.content :as content]))

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
;; marker (ADR 0031's default; spikes/acp/SPIKE.md gate 1).
;; ---------------------------------------------------------------------------

(defn- msg-role [m]
  (str (or (:role m) (get m "role") "user")))

(defn- g
  "`k` from a map read with keyword OR string keys."
  [m k]
  (when (map? m) (let [v (get m k)] (if (some? v) v (get m (name k))))))

(defn- base64-data-url
  "`data:<mime>;base64,<payload>` with a non-empty mime -> {:mimeType :data};
  anything else (no `;base64`, no mime) is not a data URL (SPEC §8C)."
  [u]
  (when (str/starts-with? u "data:")
    (let [comma (str/index-of u ",")
          meta  (when comma (subs u 5 comma))]
      (when (and meta (str/ends-with? meta ";base64"))
        (let [mime (subs meta 0 (- (count meta) 7))]
          (when-not (= "" mime)
            {:mimeType mime :data (subs u (inc comma))}))))))

(defn- wire-part
  "One OpenAI chat content block read back into a §1B part (Go acpWirePart),
  or nil when it is not one: text -> text part; image_url data URL -> image
  part (any other URL -> url-only image part); input_audio -> audio part
  (mp3 -> audio/mpeg); file with a data-URL file_data -> part by mime, named
  by filename."
  [blk]
  (case (str (g blk :type))
    "text"        {:type "text" :text (str (g blk :text))}
    "image_url"   (let [u (str (g (g blk :image_url) :url))
                        p (base64-data-url u)]
                    (cond
                      p           (assoc p :type "image")
                      ;; URL-only: an EMPTY mimeType, never inferred (SPEC §8C).
                      (not= "" u) {:type "image" :mimeType "" :url u}
                      :else       nil))
    "input_audio" (let [a    (g blk :input_audio)
                        mime (str "audio/" (g a :format))]
                    {:type "audio" :mimeType (if (= "audio/mp3" mime) "audio/mpeg" mime)
                     :data (str (g a :data))})
    "file"        (let [f (g blk :file)
                        p (base64-data-url (str (g f :file_data)))]
                    (when p
                      (cond-> (assoc p :type (content/part-type-for-mime (:mimeType p)))
                        (not (str/blank? (str (g f :filename)))) (assoc :name (str (g f :filename))))))
    nil))

(defn- flatten-content
  "A message's `:content` as [text media-parts] (Go acpFlattenMessage): a
  string as-is; a parts list renders text parts as text and each media part as
  its §1B description — the part itself travels as a native ACP block, its
  base64 never enters the text; pieces join with \n. Anything else is
  JSON-encoded."
  [m]
  (let [c (g m :content)]
    (cond
      (string? c)     [c []]
      (nil? c)        ["" []]
      (sequential? c) (let [ps (map (fn [item] (or (wire-part item) (json/write-str item))) c)]
                        [(str/join "\n" (map (fn [p] (if (map? p) (content/summarize-part p) (str p))) ps))
                         (filterv #(and (map? %) (not= "text" (:type %))) ps)])
      :else           [(json/write-str c) []])))

(defn- msg-text [m] (first (flatten-content m)))

(defn assemble-prompt-text
  "Flatten `messages` into \"role: content\" lines — the FULL request, every
  turn (an ACP session is stateful, so sending only the delta would make this
  client a second, shadow copy of conversation state) — and append the
  supersedes marker naming the latest message's text, so a stateful agent
  answers the CURRENT request rather than an earlier near-duplicate already
  sitting in its own session history (ADR 0031)."
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

(defn- first-option-of-kind
  "The optionId of the first option whose kind starts with `prefix`, or nil."
  [options prefix]
  (some (fn [o] (when (str/starts-with? (str (:kind o)) prefix) (:optionId o))) options))

(defn- reply-outcome!
  [client id chosen]
  (send-msg! client {:jsonrpc "2.0" :id id
                     :result  (if chosen
                                {:outcome {:outcome "selected" :optionId chosen}}
                                {:outcome {:outcome "cancelled"}})}))

(defn- answer-permission!
  "Answer a `session/request_permission` request with the first `allow`-kind
  option, inline — an unanswered permission request hangs the turn forever,
  even in bypass mode (ADR 0031).

  With `:on-permission` set (SPEC §8C), the host decides instead: it is handed
  the request's params and returns an optionId. A throw, nil or an empty id answers
  with the first `reject`-kind option, else `cancelled`. It runs OFF the reader
  thread, so it may block without stalling session/update delivery."
  [client req]
  (let [options (get-in req [:params :options])]
    (if-let [decide (:on-permission client)]
      (proc/run-async!
       (fn []
         (let [chosen (try (decide (:params req)) (catch Throwable _ nil))
               chosen (if (str/blank? (str (or chosen "")))
                        (first-option-of-kind options "reject")
                        chosen)]
           (reply-outcome! client (:id req) chosen))))
      (reply-outcome! client (:id req) (first-option-of-kind options "allow")))))

(defn- block-part
  "An ACP image/audio ContentBlock {type mimeType data uri?} as a §1B part (Go
  acpBlockPart): data wins, else a uri-only url part; anything else is nil."
  [blk]
  (when (and (map? blk) (contains? #{"image" "audio"} (:type blk)))
    (cond
      (not (str/blank? (str (:data blk)))) {:type (:type blk) :mimeType (:mimeType blk) :data (:data blk)}
      (not (str/blank? (str (:uri blk))))  {:type (:type blk) :mimeType (:mimeType blk) :url (:uri blk)}
      :else nil)))

(defn- describe-parts [parts] (str/join "\n" (map content/summarize-part parts)))

(defn- tool-content
  "A tool_call_update's content [{type content, content ContentBlock}, ...] as
  [output parts] (Go acpToolContent): text blocks concatenate into output,
  image/audio blocks become parts; with no text the output is the parts'
  §1B description."
  [items]
  (let [items (if (sequential? items) items [])
        parts (vec (keep #(block-part (:content %)) items))
        out   (str/join "" (map #(if (block-part (:content %)) "" (str (get-in % [:content :text]))) items))]
    [(if (and (= "" out) (seq parts)) (describe-parts parts) out) parts]))

(defn- run-file
  "One inbound part as a RunResult.files entry."
  [p from call-id]
  (assoc p :from from :callId (or call-id "")))

(defn- stream-update!
  "Hand one session/update to the CURRENT turn's emitter (if any), as it is
  read — this runs on the reader thread, so delivery is incremental:
    agent_message_chunk -> {:type \"text\" :delta}
    agent_thought_chunk -> {:type \"thought\" :delta :via \"acp\"} (never part
                           of the answer)
    tool_call           -> {:type \"tool_call\" :id :name (title) :args (rawInput) :via \"acp\"}
    tool_call_update    -> a non-empty :title is remembered as the call's name;
                           a non-empty :rawInput object first emits
                           {:type \"tool_call_args\" :id :name :args :via \"acp\"} (#188);
                           completed|failed then emits
                           {:type \"tool_result\" :id :name :output :isError :via \"acp\"}
  An emitter that throws never breaks the reader."
  [client upd]
  (let [st   (:state client)
        kind (:sessionUpdate upd)
        id   (:toolCallId upd)]
    (when (= "tool_call" kind)
      (swap! st assoc-in [:tool-names id] (if (str/blank? (str (:title upd))) (:kind upd) (:title upd))))
    (when (and (= "tool_call_update" kind) (not (str/blank? (str (:title upd)))))
      (swap! st assoc-in [:tool-names id] (:title upd)))
    (let [final?   (and (= "tool_call_update" kind) (contains? #{"completed" "failed"} (:status upd)))
          [out tp] (when final? (tool-content (:content upd)))]
    ;; inbound media files are collected even with no emitter (a plain run)
    (when final?
      (let [nm (get-in @st [:tool-names id])]
        (swap! st update :files into (map #(run-file % nm id) tp))))
    (when-let [emit (:emit @st)]
      (let [txt (get-in upd [:content :text])
            ev  (case (str kind)
                  "agent_message_chunk" (when-not (= "" (str txt)) {:type "text" :delta (str txt)})
                  "agent_thought_chunk" (when-not (= "" (str txt)) {:type "thought" :delta (str txt) :via "acp"})
                  "tool_call"           {:type "tool_call" :id id :name (get-in @st [:tool-names id])
                                         :args (or (:rawInput upd) {}) :via "acp"}
                  "tool_call_update"    (let [nm   (get-in @st [:tool-names id])
                                              args (:rawInput upd)]
                                          (cond-> []
                                            (and (map? args) (seq args))
                                            (conj {:type "tool_call_args" :id id :name nm
                                                   :args args :via "acp"})
                                            final?
                                            (conj (cond-> {:type "tool_result" :id id :name nm
                                                           :output out
                                                           :isError (= "failed" (:status upd)) :via "acp"}
                                                    (seq tp) (assoc :parts tp)))))
                  nil)]
        (doseq [e (if (sequential? ev) ev (when ev [ev]))]
          (try (emit e) (catch Throwable _ nil))))))))

(defn- route!
  "Dispatch one parsed JSON-RPC line from the child:
    - an id with no method  -> a reply to one of OUR requests, filed by id
    - session/update        -> handed to :on-update (raw params), then only
                               agent_message_chunk text is accumulated
    - session/request_permission -> answered (inline, or via :on-permission)
    - any OTHER request (has an id) -> -32601: fs/* and terminal/* are declared
      unsupported in clientCapabilities, and an unanswered request makes the
      agent wait forever
    - a notification        -> ignored (nothing this client needs)."
  [client msg]
  (cond
    (and (nil? (:method msg)) (some? (:id msg)))
    (swap! (:state client) assoc-in [:inbox (:id msg)] msg)

    (= "session/update" (:method msg))
    (let [upd (get-in msg [:params :update])]
      (when-let [on (:on-update client)]
        (try (on (:params msg)) (catch Throwable _ nil)))
      (when (= "agent_message_chunk" (:sessionUpdate upd))
        (if-let [mp (block-part (:content upd))]
          ;; an image/audio chunk is a part, never text (SPEC §8C ACP content parts)
          (swap! (:state client) #(-> % (update :message-parts conj mp)
                                      (update :files conj (run-file mp "acp" ""))))
          (swap! (:state client) update :chunks conj (get-in upd [:content :text]))))
      (stream-update! client upd))

    (and (= "session/request_permission" (:method msg)) (some? (:id msg)))
    (answer-permission! client {:id (:id msg) :params (:params msg)})

    (and (some? (:method msg)) (some? (:id msg)))
    (send-msg! client {:jsonrpc "2.0" :id (:id msg)
                       :error   {:code -32601 :message (str "method not found: " (:method msg))}})

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

(defn- await-reply!
  "await-response! for `method`, with a closed transport reported as SPEC §8C
  words it: the agent exited before replying — as soon as its stdout closes,
  never by waiting out a timeout."
  [client id method timeout-ms]
  (let [res (await-response! (:state client) id timeout-ms)]
    (if (= "peer-eof" (:error res))
      {:error (str "agent exited before replying to " method)}
      res)))

(defn- call!
  "One request/response over the child — used for the one-shot setup calls
  (`initialize`, `session/new`, `session/set_mode`), never for
  `session/prompt` (see send-prompt!, which has its own permission-aware
  wait)."
  [client method params]
  (let [id (next-id! (:id-counter client))]
    (send-msg! client {:jsonrpc "2.0" :id id :method method :params (or params {})})
    (let [res (unwrap-reply (await-reply! client id method (:timeout-ms client)))]
      (swap! (:state client) update :inbox dissoc id)
      res)))

;; ---------------------------------------------------------------------------
;; model / mode switch (shared by connect and the running client, #197)
;; ---------------------------------------------------------------------------

(defn- set-model-call!
  "session/set_config_option {configId \"model\"}, then the older
  session/set_model if the agent rejects that. {:ok _} or
  {:error [err1 err2]} when both fail."
  [client model]
  (let [sid (:session-id client)
        r1  (call! client "session/set_config_option" {:sessionId sid :configId "model" :value model})]
    (if-not (:error r1)
      r1
      (let [r2 (call! client "session/set_model" {:sessionId sid :modelId model})]
        (if (:error r2) {:error [r1 r2]} r2)))))

(defn- closed-error [client]
  (when (:closed? @(:state client))
    {:error (or (:close-reason @(:state client)) "closed")}))

;; ---------------------------------------------------------------------------
;; connect
;; ---------------------------------------------------------------------------

(defn stderr
  "The agent's most recent stderr lines (koine keeps a bounded tail), joined
  with newlines — for error messages. Never contains anything we wrote."
  [client]
  ;; await-stderr, not stderr-lines: the drain is a background thread, so a
  ;; child that just died routinely reads as silent for a few ms.
  (try (str/join "\n" (proc/await-stderr (:child client) 300)) (catch Throwable _ "")))

(declare kill-tree!)

(defn- kill-and-throw!
  "A handshake phase (`initialize` / `session/new` / `session/set_mode` / set
  model) failed: force-kill the half-open child so `connect` never leaks a
  process, then throw. `:phase` and the agent's `:stderr` tail ride in ex-data."
  [client phase res]
  (kill-tree! client)
  (throw (ex-info (str "toolnexus: acp: " phase ": " (pr-str (:error res)))
                  (assoc res :phase phase :stderr (stderr client)))))

(defn- which
  "Resolve `cmd` like exec.LookPath: a name with a slash must exist; a bare name
  is searched on PATH. Returns the path, or nil."
  [cmd]
  (let [cmd (str cmd)]
    (cond
      (str/blank? cmd) nil
      (str/includes? cmd "/") (when (and (fs/exists? cmd) (not (fs/directory? cmd))) cmd)
      :else (some (fn [dir]
                    (let [p (str (if (str/blank? dir) "." dir) "/" cmd)]
                      (when (and (fs/exists? p) (not (fs/directory? p))) p)))
                  (str/split (or (env/get-env "PATH") "") #":")))))

(def ^:private pid-wrapper
  "Records the agent's pid, then `exec`s it: the shell is REPLACED, so the
  agent runs with its argv untouched and no shell in between. The pid is what
  lets `close` end the agent's whole process tree."
  "echo $$ > \"$0\"; exec \"$@\"")

(defn- spawn-agent!
  "Spawn `command` (no shell — see `pid-wrapper`). Returns [child pidfile]."
  [command cwd env-map]
  (when-not (which (first command))
    (throw (ex-info (str "toolnexus: acp: start " (pr-str (first command)) ": command not found")
                    {:phase "start" :stderr ""})))
  (let [pidfile (str (fs/real-path (fs/temp-dir! "toolnexus-acp")) "/pid")
        child   (try
                  (proc/spawn (into ["sh" "-c" pid-wrapper pidfile] (map str command))
                              (cond-> {:dir cwd}
                                (seq env-map) (assoc :env env-map)))
                  (catch Throwable e
                    (throw (ex-info (str "toolnexus: acp: start " (pr-str (first command)) ": "
                                         (or (ex-message e) (str e)))
                                    {:phase "start" :stderr ""}))))]
    [child pidfile]))

(defn- kill-tree!
  "Force-end the agent and every descendant it started (captured while the
  agent is still alive — after it dies its children are reparented)."
  [client]
  (let [pidfile (:pidfile client)
        pid     (try (when (and pidfile (fs/exists? pidfile))
                       (let [v (json/read-str (str/trim (fs/read-file pidfile)))]
                         (when (integer? v) v)))
                     (catch Throwable _ nil))
        kids    (when pid (try (builtin/child-pids pid) (catch Throwable _ nil)))]
    (try (proc/kill! (:child client)) (catch Throwable _ nil))
    (doseq [p kids]
      (try (proc/sh ["kill" "-KILL" (str p)] {:timeout-ms 5000}) (catch Throwable _ nil)))
    (when pidfile
      (try (fs/delete-tree! (subs pidfile 0 (str/last-index-of pidfile "/"))) (catch Throwable _ nil)))))

(defn connect
  "Spawn `command` (a vector, e.g. [\"my-agent\" \"acp\"]) and complete
  `initialize` -> `session/new` (-> optional `session/set_mode`) (-> optional
  model). Returns a live, warm client map. Throws (ex-info) on any handshake
  failure — a model source that cannot connect is a construction-time error,
  matching `toolnexus.agents.runtime/create-runtime`'s own validation. The
  ex-data carries `:phase` (\"initialize\", \"session/new\", ...) and the
  agent's `:stderr` tail.

  opts:
    :cwd                 absolute working directory handed to `session/new`
                          AND the child's working directory.
                          Real agents REJECT session/new with -32602 without
                          an absolute cwd (ADR 0031) — defaults to
                          the process's own resolved cwd (`koine.fs/real-path`
                          on \".\"), which is always absolute.
    :mcp-servers          `session/new` mcpServers: a list of JSON objects
                          sent VERBATIM and in order (SPEC §8C) — never
                          validated, renamed or reshaped. Default [] (the key
                          is always present; real agents require it).
    :session-meta         sent as session/new `_meta` (opaque); the key is
                          absent when unset.
    :client-caps-meta     sent as initialize clientCapabilities._meta (opaque).
    :model                if set, passed through unchanged after session/new:
                          session/set_config_option {configId \"model\"}, then
                          session/set_model if that is rejected. Both failing
                          is a connect error.
    :on-permission        (fn [params] option-id) decides every
                          session/request_permission (see answer-permission!).
    :on-update            (fn [params]) sees every raw session/update (must not
                          block — it runs on the reader thread).
    :environment / :env   extra env vars for the child (merged; matches
                          toolnexus.mcp's `environment`/`env` alias).
    :mode                 if set, issues an optional `session/set_mode` after
                          `session/new`.
    :timeout               per-call timeout ms for the setup handshake.
                          Default 30000.
    :permission-timeout    the safety net bounding a `session/prompt` reply
                          — see default-permission-timeout-ms. Default 30000.

  The client map also carries `:initialize-result`.

  The agent is spawned without a shell (`exec` replaces the pid-recording
  wrapper, argv untouched); `close` ends the agent's whole process tree, since
  koine exposes no process groups."
  ([command] (connect command {}))
  ([command opts]
   (let [{:keys [cwd mcp-servers environment env mode timeout permission-timeout
                 client-caps-meta session-meta model on-permission on-update]} opts
         timeout-ms            (or timeout default-timeout-ms)
         permission-timeout-ms (or permission-timeout default-permission-timeout-ms)
         cwd                   (or cwd (fs/real-path "."))]
     (when-not (str/starts-with? (str cwd) "/")
       (throw (ex-info (str "toolnexus: acp: :cwd must be absolute, got " (pr-str cwd))
                       {:cwd cwd})))
     (let [env-map    (merge (or environment {}) (or env {}))
           [child pidfile] (spawn-agent! (vec command) cwd env-map)
           state      (atom {:inbox {} :closed? false :close-reason nil :chunks []
                              :files [] :message-parts []})
           write-lock (atom false)
           client     {:child               child
                       :pidfile             pidfile
                       :state               state
                       :write-lock          write-lock
                       :prompt-lock         (atom false)
                       :id-counter          (atom 0)
                       :session-id          nil
                       :prompt-caps         (atom {})
                       :timeout-ms          timeout-ms
                       :permission-timeout-ms permission-timeout-ms
                       :on-permission       on-permission
                       :on-update           on-update
                       :close-once          (atom false)}]
       (start-reader! client)
       (let [caps     (cond-> {:fs {:readTextFile false :writeTextFile false}
                               :terminal false}
                        client-caps-meta (assoc :_meta client-caps-meta))
             ;; An agent that dies at once makes the write itself throw
             ;; (Stream closed / Broken pipe): that is an initialize failure.
             init-res (try (call! client "initialize"
                                  {:protocolVersion   1
                                   :clientCapabilities caps})
                           (catch Throwable e {:error (ex-message e)}))]
         (when (:error init-res)
           (kill-and-throw! client "initialize" init-res))
         ;; which non-text ContentBlocks session/prompt may carry (absent => false)
         (let [pc (get-in init-res [:ok :agentCapabilities :promptCapabilities])]
           (reset! (:prompt-caps client) {:image           (true? (:image pc))
                                          :audio           (true? (:audio pc))
                                          :embeddedContext (true? (:embeddedContext pc))}))
         (let [new-res (call! client "session/new"
                              (cond-> {:cwd cwd :mcpServers (vec (or mcp-servers []))}
                                session-meta (assoc :_meta session-meta)))]
           (when (:error new-res)
             (kill-and-throw! client "session/new" new-res))
           (let [session-id (get-in new-res [:ok :sessionId])]
             (when (str/blank? (str session-id))
               (kill-and-throw! client "session/new" {:error "no sessionId in response"}))
             (let [client (assoc client
                                 :session-id session-id
                                 :initialize-result (:ok init-res))]
               (when mode
                 (let [mode-res (call! client "session/set_mode" {:sessionId session-id :modeId mode})]
                   (when (:error mode-res)
                     (kill-and-throw! client "session/set_mode" mode-res))))
               (when-not (str/blank? (str (or model "")))
                 ;; Passed through unchanged. Stable ACP spelling first, then
                 ;; the older unstable session/set_model.
                 (let [r (set-model-call! client model)]
                   (when (:error r)
                     (kill-and-throw! client (str "set model " (pr-str model)) r))))
               client))))))))

(defn set-mode!
  "Change the agent's mode on a RUNNING client (SPEC §8C, #197): sends
  session/set_mode {sessionId, modeId} at once — no restart, same session,
  value unvalidated. {:ok result} or {:error ...}; a rejection fails only
  this call."
  [client mode]
  (or (closed-error client)
      (call! client "session/set_mode" {:sessionId (:session-id client) :modeId mode})))

(defn set-model!
  "Change the agent's model on a RUNNING client (SPEC §8C, #197) — the
  :model connect option's logic: session/set_config_option, then
  session/set_model. {:ok result} or {:error [err1 err2]}."
  [client model]
  (or (closed-error client) (set-model-call! client model)))

;; ---------------------------------------------------------------------------
;; prompt / generate
;; ---------------------------------------------------------------------------

(defn- prompt-blocks
  "The session/prompt ContentBlocks (Go promptBlocks): the text block, then one
  native block per media part with data the agent advertised support for."
  [client text parts]
  (let [caps @(:prompt-caps client)]
    (into [{:type "text" :text text}]
          (keep (fn [p]
                  (when-not (str/blank? (str (:data p)))
                    (cond
                      (and (= "image" (:type p)) (:image caps))
                      {:type "image" :mimeType (:mimeType p) :data (:data p)}
                      (and (= "audio" (:type p)) (:audio caps))
                      {:type "audio" :mimeType (:mimeType p) :data (:data p)}
                      (and (= "file" (:type p)) (:embeddedContext caps))
                      {:type "resource"
                       :resource {:uri (str "attachment:///" (if (str/blank? (str (:name p))) "attachment" (:name p)))
                                  :mimeType (:mimeType p) :blob (:data p)}}))))
          parts)))

(defn- send-prompt!
  "One session/prompt turn, accumulating only agent_message_chunk text.
  Not serialised itself — the caller (`prompt`) holds :prompt-lock for the
  whole call, matching golang/acp.go's `promptMu`. {:ok text :files [...]}."
  [client text parts emit]
  (if (:closed? @(:state client))
    {:error (if (= "peer-eof" (:close-reason @(:state client)))
              "agent exited before replying to session/prompt"
              (or (:close-reason @(:state client)) "closed"))}
    (let [id (next-id! (:id-counter client))]
      (swap! (:state client) assoc :chunks [] :emit emit :tool-names {} :files [] :message-parts [])
      (send-msg! client {:jsonrpc "2.0" :id id :method "session/prompt"
                         :params {:sessionId (:session-id client)
                                  :prompt    (prompt-blocks client text parts)}})
      (let [res (unwrap-reply (await-reply! client id "session/prompt" (:permission-timeout-ms client)))]
        (swap! (:state client) #(-> % (update :inbox dissoc id) (assoc :emit nil)))
        (if (:error res)
          res
          (let [s    @(:state client)
                text (str/join "" (:chunks s))]
            {:ok    (if (and (= "" text) (seq (:message-parts s))) (describe-parts (:message-parts s)) text)
             :files (:files s)}))))))

(defn prompt
  "Send one turn's FULL prompt text and wait for the accumulated
  agent_message_chunk reply. Turns on the same client are serialised —
  {:ok content} or {:error reason}. With `emit`, every session/update of the
  turn is also handed to it AS IT ARRIVES (see `stream-update!`)."
  ([client text] (prompt client text nil))
  ([client text emit] (prompt client text [] emit))
  ([client text parts emit]
   (with-lock! (:prompt-lock client) (fn [] (send-prompt! client text parts emit)))))

(defn generate-stream
  "Build a streaming semantic generate — (fn [req emit] -> {:content ...}) —
  the shape `toolnexus.client/create-in-process-client`'s `:generate-stream`
  accepts. `emit` receives text / thought / tool_call / tool_result events
  from the reader thread while the turn runs (incremental, not after it);
  the returned content is only the agent_message_chunk text. Throws like
  `generate` on an ACP-level failure."
  [client]
  (fn [req emit]
    (let [text  (assemble-prompt-text (:messages req))
          parts (vec (mapcat #(second (flatten-content %)) (:messages req)))
          res   (prompt client text parts emit)]
      (if (:error res)
        (throw (ex-info (str "toolnexus: acp: generate failed: " (pr-str (:error res)))
                        {:acp-error (:error res)}))
        (cond-> {:content (:ok res)}
          (seq (:files res)) (assoc :files (:files res)))))))

(defn generate
  "Build a semantic `generate` fn — (fn [req] -> {:content ...}) — backed by
  this warm ACP client, in the exact shape
  `toolnexus.client/create-in-process-client` and
  `toolnexus.agents.runtime/create-runtime`'s `:in-process` option accept.

    (let [c (toolnexus.acp/connect [\"my-agent\" \"acp\"])]
      (toolnexus.client/create-in-process-client
        {:model \"my-agent\" :generate (toolnexus.acp/generate c)}))

  Throws on an ACP-level failure (a closed session, a timed-out call, an RPC
  error) — the same failure shape any other `generate` fn raising surfaces to
  the caller."
  [client]
  (let [g (generate-stream client)]
    (fn [req] (g req nil))))

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
    (kill-tree! client))
  nil)
