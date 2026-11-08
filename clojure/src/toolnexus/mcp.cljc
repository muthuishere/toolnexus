;; SPEC §2 — the MCP source. The port's hardest namespace, and the one place
;; where the spikes' documented shortcut had to be paid off.
;;
;; Three ideas carry the whole file:
;;
;; 1. A TRANSPORT IS DATA — `{:rpc! fn :notify! fn :close! fn …}`. stdio and
;;    streamable-HTTP differ ONLY in those closures, so everything above §2
;;    (initialize, tools/list, tools/call, result shaping, naming) is written
;;    exactly once and cannot drift between the two legs. Proved in S17.
;;
;; 2. THE READER OWNS THE ID MATCH. S15/S17 matched ids on the CALLER's side of
;;    the read loop and said so in a comment: with two calls in flight the other
;;    caller's response looks like noise and gets dropped, timing that caller
;;    out. Here ONE loop reads the child's stdout, looks each `id` up in a
;;    pending set, files the message in an inbox, and treats only an ID-LESS
;;    message as a notification. §8 parallel tool calls make two in flight the
;;    normal case, so this is not a refinement, it is the correct design.
;;
;; 3. NOTHING THROWS ACROSS THE §2 BOUNDARY. §0.3 requires a bad server to be
;;    isolated (`failed`) and never fatal; an exception crossing this boundary is
;;    precisely how "isolated" becomes "fatal". Every failure is data with a
;;    stable name.
;;
;; One `.cljc`, zero reader conditionals, zero `java.*`. Concurrency is `future`,
;; `atom`, `compare-and-set!` and `koine.time/sleep!` — the four primitives both
;; hosts have.
(ns toolnexus.mcp
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.http :as http]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.stream :as stream]
            [koine.time :as ktime]
            [toolnexus.content :as content]
            [toolnexus.mcp-auth :as auth]
            [toolnexus.mcp2026 :as m26]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; ids
;; ---------------------------------------------------------------------------

(def ^:private ids
  "JSON-RPC ids come from a counter, never from a literal. A literal id is
  correct exactly as long as one call is in flight and is a silent
  wrong-answer-to-the-wrong-caller bug the moment two are."
  (atom 0))

(defn- next-id! [] (swap! ids inc))

;; ---------------------------------------------------------------------------
;; §10  the elicitation bridge — a server asking US, mapped onto the ONE waitFor
;; ---------------------------------------------------------------------------
;;
;; A connected server may send `elicitation/create` mid-`tools/call`: a REVERSE
;; request, the only place in §2 where traffic flows server -> client. The
;; suspension spec pins the mapping — form mode becomes a §10 `kind:"input"`
;; Request, URL mode a `kind:"authorization"` Request carrying the url — and the
;; Answer maps back onto MCP's three actions. Both directions are PURE, which is
;; what makes them byte-parity-testable without a live peer.
;;
;; They are also the only §10 surface the host has to trust, so the security pin
;; is enforced by the mapping itself: `requestedSchema` rides along ONLY in form
;; mode. A credential is never collected through a form.

(def ^:private elicitation-seq (atom 0))

(defn elicitation->request
  "SPEC §10 — an MCP `elicitation/create` params object as a §10 Request.

  The id is opaque and per-process (js/src/mcp.ts builds it from a timestamp and
  a counter, and no port compares ids with another). Uniqueness is the only
  property anything depends on, so the counter — not the clock — is what
  guarantees it here."
  [params]
  (let [url? (= "url" (str (:mode params)))
        req  {:id     (str "elc-" (ktime/mono-ms) "-" (swap! elicitation-seq inc))
              :kind   (if url? "authorization" "input")
              :prompt (str (or (:message params) ""))}]
    (cond-> req
      (and url? (:url params))                (assoc :url (:url params))
      (and (not url?) (:requestedSchema params))
      (assoc :data {:schema (:requestedSchema params)}))))

(defn answer->elicit-result
  "SPEC §10 — a resolved §10 Answer as an MCP `ElicitResult`.
  `ok` ⇒ accept carrying `data` as content; otherwise `reason:\"declined\"` ⇒
  decline and anything else (cancelled, expired, absent) ⇒ cancel."
  [answer]
  (if (:ok answer)
    {:action "accept" :content (or (:data answer) {})}
    {:action (if (= "declined" (str (:reason answer))) "decline" "cancel")}))

(defn server-request-response
  "The response to ONE server-initiated JSON-RPC request, or nil when the
  message is not one.

  Satisfied INLINE, as §10 requires: the in-flight `tools/call` resumes when
  this returns, and nothing re-executes. With no `wait-for` the client never
  advertised `elicitation`, so a request for it is simply an unknown method —
  a clean refusal rather than a hang. A host callback that THROWS becomes a
  cancel: §0.3's isolation rule does not stop at the reverse-request boundary."
  [wait-for msg]
  (when (and (some? (:id msg)) (:method msg))
    (let [reply (fn [body] (merge {:jsonrpc "2.0" :id (:id msg)} body))]
      (if (and wait-for (= "elicitation/create" (:method msg)))
        (reply {:result (try (answer->elicit-result (wait-for (elicitation->request (:params msg))))
                             (catch Throwable _ {:action "cancel"}))})
        (reply {:error {:code -32601 :message "Method not found"}})))))

;; ---------------------------------------------------------------------------
;; §0.2  naming
;; ---------------------------------------------------------------------------

(defn mcp-tool-name
  "SPEC §0.2 — sanitize(server)_sanitize(tool)."
  [server-name remote-name]
  (str (tool/sanitize server-name) "_" (tool/sanitize remote-name)))

;; ---------------------------------------------------------------------------
;; §0.3 / §2  config
;; ---------------------------------------------------------------------------

(def default-timeout-ms
  "SPEC §0.3 — the per-phase budget when a server does not state one."
  30000)

(def ^:private reserved-keys
  "SPEC §2 — sibling config sections, never MCP servers. They only matter when
  the object IS the raw server map (no mcpServers/servers/mcp wrapper)."
  #{:builtins :agents :a2a :mcpServer})

(defn- key-str
  "A map key as a plain string, whether it arrived as a keyword (koine's
  `read-str` keywordizes by default) or as a string."
  [k]
  (if (keyword? k) (name k) (str k)))

(defn expand-headers
  "SPEC §0.3 — remote `headers` VALUES expand ${ENV_VAR} from the environment.
  The expanded value is used and NEVER logged. Callers may report the SHAPE
  (`header-shape`) and nothing else."
  [headers]
  (reduce (fn [acc entry] (assoc acc (key-str (key entry)) (env/expand (str (val entry)))))
          {} headers))

(defn header-shape
  "The only thing about headers that may leave this process: which keys exist,
  and whether ${ENV} expansion CHANGED anything. Never a value, and never a
  length — a length leaks a secret's size."
  [raw expanded]
  (let [raw (reduce (fn [acc e] (assoc acc (key-str (key e)) (str (val e)))) {} raw)]
    ;; Bare `sort` is fine: these are HTTP header field names, which RFC 7230
    ;; restricts to ASCII tokens, so the two hosts cannot order them differently.
    {:keys     (vec (sort (keys expanded)))
     :expanded (boolean (some (fn [e] (not= (val e) (get raw (key e)))) expanded))}))

(defn- server-kind
  "SPEC §2 — explicit `type` wins; otherwise url ⇒ remote, command ⇒ local."
  [m]
  (let [t (some-> (:type m) str)]
    (cond
      (= t "remote") "remote"
      (= t "local")  "local"
      (:url m)       "remote"
      (:command m)   "local"
      :else          "unknown")))

(defn- config-error [server-name field why]
  (ex-info (str "mcp server \"" server-name "\": invalid " field ": " why)
           {:error "config" :server server-name :field field}))

(defn- string-vec
  "`xs` as a vector of strings, or nil when it is not a sequential of strings."
  [xs]
  (when (and (sequential? xs) (every? string? xs)) (vec xs)))

(defn normalize-command
  "SPEC §2 `command` + `args` (the Claude shape) — the argv to spawn.

  `command` is a non-empty string or a non-empty vector of strings; `args`, when
  present, is a vector of strings appended to it. A remote server ignores
  `args`. No shell is involved: the argv goes to the spawner as-is. An absent
  command is nil (a local server without one fails at connect, as before).
  Anything else throws, naming the server and the field."
  [server-name {:keys [command args]} remote?]
  (when (some? command)
    (let [argv (cond
                 (string? command)
                 (if (= "" command)
                   (throw (config-error server-name "command" "empty string"))
                   [command])

                 (sequential? command)
                 (cond
                   (empty? command)
                   (throw (config-error server-name "command" "empty array"))
                   (string-vec command) (string-vec command)
                   :else
                   (throw (config-error server-name "command" "array elements must be strings")))

                 :else
                 (throw (config-error server-name "command" "must be a string or an array of strings")))]
      (cond
        (or remote? (nil? args)) argv
        (string-vec args)        (into argv args)
        :else (throw (config-error server-name "args" "must be an array of strings"))))))

(defn- one-server [server-name m]
  (let [raw-headers (:headers m)
        kind        (server-kind m)]
    {:name        (key-str server-name)
     :kind        kind
     ;; `disabled:true` and `enabled:false` are the same statement.
     :enabled     (not (or (true? (:disabled m)) (false? (:enabled m))))
     :command     (vec (normalize-command (key-str server-name) m (= "remote" kind)))
     :cwd         (:cwd m)
     ;; `environment` with `env` as the documented alias.
     :environment (or (:environment m) (:env m) {})
     :url         (:url m)
     :raw-headers raw-headers
     :headers     (expand-headers raw-headers)
     :timeout     (or (:timeout m) default-timeout-ms)
     ;; SPEC §2 — new optional keys; absent ⇒ byte-identical behaviour.
     :auth        (:auth m)
     :description (:description m)
     :tool-search (true? (:toolSearch m))}))

(defn parse-config
  "SPEC §0.3 / §2 — a JSON string or an already-parsed map becomes a vector of
  server maps, sorted by name so two hosts cannot disagree about order.

  Accepts `mcpServers` | `servers` | `mcp`; with no wrapper the object itself is
  the server map, minus the reserved sibling keys.

  Throws ex-info naming the server for an invalid `command` / `args` shape —
  a config error is the caller's to fix, not a server to isolate."
  [config]
  (let [cfg     (if (string? config) (json/read-str config {:key-fn keyword}) config)
        servers (or (:mcpServers cfg) (:servers cfg) (:mcp cfg)
                    (apply dissoc cfg reserved-keys))]
    (->> servers
         (map (fn [entry] (one-server (key entry) (val entry))))
         ;; tool/compare-strings, not bare `sort-by`. These are the RAW server
         ;; names out of the user's mcp.json — the one name in the MCP path that
         ;; `mcp-tool-name` has not yet run through `tool/sanitize` — and this
         ;; order is load-bearing twice over: it is the connection order, and
         ;; `from-config`'s first-wins collision rule inherits it. Bare sort put
         ;; a non-BMP server name in a different place on each host, so which
         ;; server won a name clash could depend on the runtime.
         (sort-by :name tool/compare-strings)
         vec)))

;; ---------------------------------------------------------------------------
;; failure vocabulary
;; ---------------------------------------------------------------------------
;;
;; Every way §2 can go wrong has ONE name, shared by both transports, because a
;; caller's retry/report logic must not have to know which leg it is on.
;;
;;   transport       could not reach the peer at all (koine classifies: :dns /
;;                   :connect-failed / :timeout / :transport)
;;   http-status     a real HTTP answer that is not 2xx
;;   malformed-body  a 2xx (or a line) that is not JSON
;;   rpc-error       well-formed JSON-RPC carrying an `error` member
;;   timeout         the peer accepted the write and never answered in budget
;;   peer-eof        the peer's stdout closed (see the honesty note on `connect`)
;;   closed          the transport was disconnected under the caller

(defn- rpc-failed? [res] (some? (:error res)))

;; ---------------------------------------------------------------------------
;; §2  transport — stdio
;; ---------------------------------------------------------------------------

(defn- with-write-lock!
  "Serialize writers. `send-line!` is a write + a flush on a shared handle: two
  threads calling it can interleave one JSON-RPC frame INTO another, and a torn
  frame is unparseable to the peer and unattributable to either caller.

  A compare-and-set! spin is the lock both hosts have — `locking` is a JVM
  special form and an agent/executor is not portable. Contention here is
  microscopic (a single line write), so spinning at 1 ms costs nothing."
  [lock f]
  (loop []
    (if (compare-and-set! lock false true)
      (try (f) (finally (reset! lock false)))
      (do (ktime/sleep! 1) (recur)))))

(defn- start-reader!
  "THE dedicated reader loop. Exactly one per child — do not start a second:
  two readers on one stdout race for lines and each would see half a session.

  It reads, and for each message:
    - an `id` this client registered as pending  -> file it in the inbox
    - an `id` nobody is waiting for              -> count it, drop it
    - NO `id`                                    -> a notification
  Only the absence of an id makes something a notification. That is the whole
  fix over the spikes, where a non-matching id was indistinguishable from noise.

  §10 REFINES THE THIRD BRANCH. An id nobody is waiting for is not automatically
  noise: an id that arrives WITH a `method` is a server-initiated REQUEST (an
  elicitation), and dropping it as unmatched is how a bridged `waitFor` would
  silently never fire. So: id + method + not-ours ⇒ answer it inline, on this
  thread, through `respond!`. Answering on the reader thread is correct rather
  than convenient — nothing else can arrive that matters until the server has
  its answer, because the `tools/call` we are blocked on is what is waiting.

  On EOF it marks the transport closed, which is what unblocks every waiter —
  including one parked on a peer that will never speak again, once `kill!` has
  closed that peer's stdout."
  [child state respond!]
  ;; run-async!, not `future` — see execute-calls in client.cljc. A reader loop
  ;; is exactly the case koine documents: library code, one non-daemon pool
  ;; thread per connected MCP server, each holding the consumer's process open
  ;; for the pool's 60s keep-alive after the program is done.
  (proc/run-async!
   (fn []
    (loop []
      (let [line (try (proc/read-line! child) (catch Throwable _ nil))]
        (if (nil? line)
          ;; §2 — EOF alone cannot say WHY the peer stopped. koine 0.8.0's
          ;; `exit-code` can: nil = has not exited, a number = the status it
          ;; exited with. This replaces a `kill!` timeout that guessed.
          ;;
          ;; BUT IT MUST NOT BE READ THE INSTANT EOF ARRIVES. Measured: a child
          ;; running `sh -c 'echo …>&2; exit 3'` reported exit-code nil on 4 of 5
          ;; runs at the moment its stdout closed, because closing stdout and the
          ;; reaper recording the status are two different events and EOF wins
          ;; the race. Reading it immediately turns "I do not know yet" into a
          ;; confident "still running" — a worse answer than the honest
          ;; `peer-eof` this replaced.
          ;;
          ;; So: give the reaper a bounded grace. A peer whose stdout just closed
          ;; has either exited already or is genuinely still alive, and the first
          ;; case resolves in milliseconds.
          (let [code (loop [tries 0]
                       (let [c (try (proc/exit-code child) (catch Throwable _ nil))]
                         (cond c c
                               (>= tries 50) nil          ; ~250ms, then give up
                               :else (do (ktime/sleep! 5) (recur (inc tries))))))]
            (swap! state assoc :closed? true
                   :exit-code code
                   ;; nil after the grace means UNKNOWN, not "alive". Say so.
                   :close-reason (if code
                                   (str "peer-exited (status " code ")")
                                   "peer-eof (stdout closed, exit status unknown)")))
          (do
            (when-not (str/blank? line)
              (let [msg (try (json/read-str line) (catch Throwable _ ::bad))]
                (cond
                  (or (= ::bad msg) (not (map? msg)))
                  (swap! state update :junk inc)

                  (nil? (:id msg))
                  (let [method (str (:method msg))
                        token  (get-in msg [:params :progressToken])]
                    (swap! state
                           (fn [s]
                             (cond-> (update s :notifications conj method)
                               ;; §2 idle timeout: ONLY a progress notification
                               ;; carrying an in-flight call's own token resets
                               ;; that call's budget. Any other token is noise.
                               (and (= "notifications/progress" method)
                                    (contains? (:progress s) token))
                               (assoc-in [:progress token] (ktime/mono-ms))))))

                  (contains? (:pending (deref state)) (:id msg))
                  (swap! state assoc-in [:inbox (:id msg)] msg)

                  ;; an id we never sent, carrying a method — the server is
                  ;; asking US something (§10 elicitation)
                  (:method msg)
                  (do (swap! state update :server-requests conj (str (:method msg)))
                      (try (respond! msg) (catch Throwable _ nil)))

                  :else
                  (swap! state update :unmatched inc))))
            (if (:closed? @state) nil (recur)))))))))

(defn- await-response!
  "Poll for this id's response until it lands, the transport closes, or the
  budget runs out. Polling rather than `promise`/`deref`-with-timeout because a
  timed deref is the one concurrency primitive whose portability across these
  hosts is not established; an atom and `koine.time/sleep!` are.

  With a progress `token` (§2, tools/call) the budget is an IDLE timeout: the
  deadline is `timeout-ms` after the later of the call's start and the last
  matching `notifications/progress` the reader recorded under that token. A
  closed transport (disconnect = cancellation) still wins over any progress."
  ([state id timeout-ms] (await-response! state id timeout-ms nil))
  ([state id timeout-ms token]
  (let [started (ktime/mono-ms)
        budget  (long timeout-ms)]
    (loop []
      (let [s   (deref state)
            msg (get-in s [:inbox id])
            last-seen (max started (long (or (when token (get-in s [:progress token])) 0)))]
        (cond
          (some? msg) {:ok msg}
          (:closed? s) {:error (or (:close-reason s) "closed")}
          (>= (ktime/mono-ms) (+ last-seen budget)) {:error "timeout" :timeout-ms timeout-ms}
          :else (do (ktime/sleep! 2) (recur))))))))

(defn- stdio-rpc!
  "One request/response over stdio. Registers the id BEFORE writing — a fast
  peer can answer before `send-line!` has returned, and an unregistered id is
  dropped as unmatched."
  [child state lock method params timeout-ms]
  (let [id    (next-id!)
        ;; §2 — every tools/call asks for progress, so a server is PERMITTED to
        ;; report liveness. A fresh opaque token per call: a notification for any
        ;; other token must not keep this call alive.
        token (when (= "tools/call" method) (str "tn-progress-" id))
        params (if token (assoc-in params [:_meta :progressToken] token) params)]
    (if (:closed? @state)
      {:error (or (:close-reason @state) "closed")}
      (do
        (swap! state (fn [s] (cond-> (update s :pending conj id)
                               token (assoc-in [:progress token] 0))))
        (try
          (with-write-lock! lock
            (fn [] (proc/send-line! child (json/write-str (cond-> {:jsonrpc "2.0" :id id :method method}
                                                            params (assoc :params params))))))
          (let [res (await-response! state id timeout-ms token)]
            (cond
              (rpc-failed? res) res
              (get-in res [:ok :error]) {:error "rpc-error"
                                         :code    (get-in res [:ok :error :code])
                                         :message (get-in res [:ok :error :message])}
              :else res))
          (catch Throwable e
            {:error "transport" :transport-error "write-failed" :message (str (ex-message e))})
          (finally
            (swap! state (fn [s] (-> s
                                     (update :pending disj id)
                                     (update :inbox dissoc id)
                                     (update :progress dissoc token))))))))))

(defn stdio-transport
  "Spawn the child and hand back a transport. §2: merged env = process env +
  `environment`, in `cwd`. koine's `spawn` drains stderr into a bounded ring
  from the first instant, which is why a verbose server no longer deadlocks on a
  full pipe, and why `:stderr` below can explain a crash."
  ([server] (stdio-transport server nil))
  ([{:keys [command cwd environment]} {:keys [wait-for]}]
  (let [child (proc/spawn (vec command)
                          (cond-> {}
                            cwd          (assoc :dir cwd)
                            (seq environment) (assoc :env environment)))
        state (atom {:pending #{} :inbox {} :progress {} :closed? false :close-reason nil
                     :notifications [] :server-requests [] :unmatched 0 :junk 0})
        lock  (atom false)
        respond! (fn [msg]
                   (when-let [reply (server-request-response wait-for msg)]
                     (with-write-lock! lock
                       (fn [] (proc/send-line! child (json/write-str reply))))))]
    (start-reader! child state respond!)
    {:kind    "stdio"
     :state   state
     :rpc!    (fn [method params timeout-ms] (stdio-rpc! child state lock method params timeout-ms))
     :notify! (fn [method params]
                (when-not (:closed? @state)
                  (with-write-lock! lock
                    (fn [] (proc/send-line! child (json/write-str {:jsonrpc "2.0"
                                                                   :method method
                                                                   :params (or params {})})))))
                nil)
     :stderr  (fn [] (proc/stderr-lines child))
     :closed? (fn [] (:closed? @state))
     ;; §2 close() "kills stdio child trees", and requirement 4 is that a HUNG
     ;; peer be abandonable. `proc/close!` closes stdin and WAITS — a child that
     ;; ignores its stdin closing hangs there forever, which is the failure this
     ;; is supposed to end. `kill!` is the guarantee: it force-terminates the
     ;; child, which closes its stdout, which makes the reader's parked
     ;; `read-line!` return nil, which closes the transport and releases every
     ;; waiter. One mechanism, no hang.
     :close!  (fn []
                (swap! state assoc :closed? true :close-reason "closed")
                (try (proc/kill! child) (catch Throwable _ nil))
                nil)})))

;; ---------------------------------------------------------------------------
;; §2  transport — streamable-HTTP
;; ---------------------------------------------------------------------------

(defn- ok-status? [status] (and status (>= status 200) (< status 300)))

;; RESPONSE HEADERS ARE READ THROUGH `koine.http/header`, NEVER `get`.
;;
;; The two hosts' HTTP clients disagreed about the CASE of the names they hand
;; back — java.net.http lowercases, Go's http.Header canonicalises — so
;; a literal `(get (:headers res) "Mcp-Session-Id")` returned the value on cljgo
;; and nil on the JVM, and the lowercase spelling did the exact reverse. No portable
;; spelling existed, and it failed SILENTLY: a missing header and a mis-cased
;; one are both nil, so the session id simply stopped being echoed and the
;; server started a fresh session per request.
;;
;; koine 0.10.0 made lowercase the normal form on every host and added `header`
;; to read one case-insensitively. This port used to carry its own private
;; `header-value` for the same reason; it is gone, because two implementations
;; of one normalisation rule is exactly how the two hosts drift apart again.
;; Every response-header read in the port goes through the one function.

(defn- sse-messages
  "A streamable-HTTP server MAY answer a POST with `text/event-stream`, carrying
  the JSON-RPC response in one or more `data:` frames (interleaved with
  comment/keep-alive frames, which are skipped). Without this a client is doing
  plain JSON-over-POST and calling it streamable-HTTP."
  [body]
  (->> (str/split-lines (str body))
       (keep (fn [line]
               (when (str/starts-with? line "data:")
                 (let [payload (str/trim (subs line 5))]
                   (try (json/read-str payload) (catch Throwable _ nil))))))
       (filter map?)
       vec))

(defn- pick-message
  "The message for OUR id, or the last one if the peer did not echo an id."
  [msgs id]
  (or (some (fn [m] (when (= id (:id m)) m)) msgs) (last msgs)))

(defn- request-headers
  "The headers on every POST to a streamable-HTTP endpoint. `Mcp-Session-Id`
  rides along once the server has issued one — that is the whole reason reading
  it back correctly matters."
  [headers session]
  (cond-> (merge {"content-type" "application/json"
                  "accept"       "application/json, text/event-stream"}
                 headers)
    (deref session) (assoc "mcp-session-id" (deref session))))

(defn- take-session!
  "Record the session id from a response head, if it carries one. `res` is any
  map with `:headers` — a buffered response or `sse-post`'s open head."
  [session res]
  (when-let [sid (http/header res "mcp-session-id")]
    (reset! session sid))
  nil)

(defn- rpc-message-result
  "The shared tail of both HTTP legs: the JSON-RPC message for our id becomes an
  {:ok …} or the named failure. Written once so the buffered and streaming legs
  cannot disagree about what a response means."
  [msgs id status]
  (let [msg (pick-message msgs id)]
    (cond
      (nil? msg)   {:error "malformed-body" :status status}
      (:error msg) {:error "rpc-error"
                    :code    (get-in msg [:error :code])
                    :message (get-in msg [:error :message])}
      :else        {:ok msg})))

(defn- http-rpc!
  "One JSON-RPC message per HTTP POST, BUFFERED. Never throws: koine's
  `http/request` returns a transport failure as DATA, which is exactly what
  §0.3's isolation requires of this boundary.

  `sse?` is a latch, not a decoration: a server that answered in
  `text/event-stream` can send a server→client request mid-call, and buffering
  makes that impossible to answer. Seeing it once switches the transport onto
  the streaming leg below."
  [url headers session sse? method params timeout-ms]
  (let [id  (next-id!)
        res (http/request
             {:method  :post
              :url     url
              :timeout-ms timeout-ms
              :headers (request-headers headers session)
              :body    (json/write-str (cond-> {:jsonrpc "2.0" :id id :method method}
                                         params (assoc :params params)))})]
    (cond
      (http/failed? res)
      {:error "transport"
       :transport-error (if (:error res) (name (:error res)) "unknown")}

      (not (ok-status? (:status res)))
      {:error "http-status" :status (:status res)}

      :else
      (do
        ;; A streamable-HTTP server issues a session id on initialize and
        ;; expects it echoed on every later request.
        (take-session! session res)
        (let [ct  (str (http/header res "content-type"))
              sse (str/includes? ct "text/event-stream")]
          (when (and sse sse?) (reset! sse? true))
          (rpc-message-result (if sse
                                (sse-messages (:body res))
                                (let [m (try (json/read-str (:body res))
                                             (catch Throwable _ ::bad))]
                                  (if (map? m) [m] [])))
                              id
                              (:status res)))))))

;; ---------------------------------------------------------------------------
;; §2 + §10  the streaming leg — the elicitation bridge over streamable-HTTP
;; ---------------------------------------------------------------------------
;;
;; stdio gets a server→client request for free: the child's stdout is a channel
;; that stays open, so `start-reader!` can answer an `elicitation/create` while
;; the `tools/call` is still in flight. HTTP had no such channel here, and the
;; gap was koine's, not §2's: `http/request` buffers (the body arrives only once
;; the server is done, so a reverse request can never be seen in time) and
;; `stream/sse-post` streamed but exposed no response headers — and MCP carries
;; session identity in the `Mcp-Session-Id` RESPONSE header, which the reply POST
;; must echo. A consumer had to choose between learning the session id and
;; receiving events incrementally.
;;
;; koine 0.10.0 closed it: `sse-post` takes `{:on-open f}`, applied ONCE to
;; `{:status :headers}` before the first event while the stream is still open.
;; So this leg reads the session id from the open head, streams the events, and
;; when one of them is a server request answers it INLINE on a second POST — MCP
;; streamable-HTTP's channel for a client→server response, which the server
;; answers 202. The in-flight `tools/call` then resumes on the same stream and
;; the tool is NOT re-executed, which is exactly the stdio behaviour and exactly
;; what §10 requires.
;;
;; Why a latch on `sse?` rather than streaming unconditionally: `sse-post`
;; surfaces `data:` frames and nothing else, so a plain `application/json` body
;; would be dropped on the floor. The transport therefore streams only once it
;; has SEEN the server answer in `text/event-stream` — which `initialize`, always
;; the first message, establishes. A JSON-only server keeps the buffered leg and
;; cannot elicit anyway (it has no channel to elicit on).

(defn- post-server-response!
  "Answer a server-initiated request on its own POST. The server replies 202 with
  no body, so nothing is parsed — but the session id MUST be on it, or the server
  cannot match the answer to the call it is blocked on."
  [url headers session reply]
  (http/request {:method     :post
                 :url        url
                 :timeout-ms 30000
                 :headers    (request-headers headers session)
                 :body       (json/write-str reply)})
  nil)

(defn- http-stream-rpc!
  "One JSON-RPC request over an SSE POST, answering any server→client request
  that arrives mid-stream. Never throws — `sse-post` DOES throw on a transport
  failure, and §0.3 does not allow that to cross this boundary."
  [url headers session wait-for method params timeout-ms]
  (let [id   (next-id!)
        ;; §2 idle timeout, same contract as stdio: a fresh token per tools/call,
        ;; and only a progress notification for THAT token extends the deadline.
        token  (when (= "tools/call" method) (str "tn-progress-" id))
        params (if token (assoc-in params [:_meta :progressToken] token) params)
        st   (atom {:msgs [] :head nil :done false :error nil :progress-at 0})
        body (json/write-str (cond-> {:jsonrpc "2.0" :id id :method method}
                               params (assoc :params params)))
        hdrs (request-headers headers session)
        on-open  (fn [head]
                   (take-session! session head)
                   (swap! st assoc :head head))
        on-event (fn [data]
                   (let [m (try (json/read-str data) (catch Throwable _ nil))]
                     (when (map? m)
                       ;; A `method` is what makes a message a REQUEST. A
                       ;; response never carries one, so this is the whole
                       ;; discrimination — ids cannot do it, since the server
                       ;; numbers its requests in its own space and may well
                       ;; reuse one of ours.
                       (cond
                         (and (some? (:id m)) (:method m))
                         (when-let [reply (server-request-response wait-for m)]
                           (try (post-server-response! url headers session reply)
                                (catch Throwable _ nil)))

                         (and token (nil? (:id m))
                              (= "notifications/progress" (:method m))
                              (= token (get-in m [:params :progressToken])))
                         (swap! st assoc :progress-at (ktime/mono-ms))

                         :else
                         (swap! st update :msgs conj m)))))]
    ;; run-async!, not `future` — same reason as `start-reader!`: library code
    ;; must not hold a consumer's process open on a non-daemon pool thread.
    (proc/run-async!
     (fn []
       (try (stream/sse-post url hdrs body on-event {:on-open on-open})
            (catch Throwable e (swap! st assoc :error (or (ex-message e) (str e))))
            (finally (swap! st assoc :done true)))))
    (let [started  (ktime/mono-ms)
          budget   (long timeout-ms)
          ;; Stop at OUR answer, not at end-of-stream: a server is entitled to
          ;; hold the stream open with keep-alives after it has responded, and
          ;; waiting for the close would spend the caller's whole budget on a
          ;; call that already succeeded.
          final    (loop []
                     (let [s @st]
                       (cond
                         (some (fn [m] (= id (:id m))) (:msgs s)) s
                         (:done s)                                s
                         (>= (ktime/mono-ms) (+ (max started (long (:progress-at s))) budget))
                         (assoc s :timed-out true)
                         :else (do (ktime/sleep! 2) (recur)))))]
      (cond
        (:timed-out final) {:error "timeout" :timeout-ms timeout-ms}
        (:error final)     {:error "transport" :transport-error "stream-failed"
                            :message (:error final)}
        (not (ok-status? (get-in final [:head :status])))
        {:error "http-status" :status (get-in final [:head :status])}
        :else (rpc-message-result (:msgs final) id (get-in final [:head :status]))))))

(defn http-transport
  "The remote leg. Identical shape to `stdio-transport`, which is the point:
  every line above §2 is written once."
  ([server] (http-transport server nil))
  ([{:keys [url headers]} {:keys [wait-for]}]
   (let [session (atom nil)
         sse?    (atom false)]
     {:kind    "http"
      :url     url
      :rpc!    (fn [method params timeout-ms]
                 (if (deref sse?)
                   (http-stream-rpc! url headers session wait-for method params timeout-ms)
                   (http-rpc! url headers session sse? method params timeout-ms)))
      ;; A notification is a POST the server answers 202 to; a failure to deliver
      ;; one is not worth failing a connect over. Always buffered — a
      ;; notification has no response to wait for, so there is nothing to stream.
      :notify! (fn [method params] (http-rpc! url headers session nil method params 5000) nil)
      :stderr  (fn [] [])
      :closed? (fn [] false)
      :close!  (fn [] nil)})))

;; ---------------------------------------------------------------------------
;; §0.4  result shaping
;; ---------------------------------------------------------------------------

(defn- embedded-texts
  "An embedded `resource` carrying TEXT is text the server meant the model to
  read, not a binary attachment, so §0.4 appends it to `output` rather than
  making it a part."
  [content]
  (keep (fn [item]
          (when (= "resource" (:type item))
            (let [t (get-in item [:resource :text])]
              (when (string? t) t))))
        content))

(defn collect-parts
  "SPEC §0.4 — an MCP `content[]` as §1B parts: `image`⇒image, `audio`⇒audio,
  `resource_link`⇒`file{url}`, `resource` with a blob⇒`file{data}`. A `resource`
  carrying text went to `output` instead, and text entries ARE `output`. Nothing
  is ever dropped silently — five content types, and the fifth (`resource_link`)
  is the one every port was also losing.

  `data`/`blob` are base64 STRINGS off the wire and are passed through VERBATIM,
  so no encoder on either host can drift the bytes.

  Called on EVERY branch of `shape-result`. A short-circuit that skips it — the
  `structuredContent` early return, the error path — reintroduces exactly the
  silent drop this mapping exists to remove."
  [content]
  (vec
   (keep
    (fn [item]
      (let [kind (:type item)]
        (cond
          (contains? #{"image" "audio"} kind)
          (when (and (string? (:data item)) (string? (:mimeType item)))
            {:type kind :mimeType (:mimeType item) :data (:data item)})

          (= "resource_link" kind)
          (when (some? (:uri item))
            (cond-> {:type "file"
                     :mimeType (or (:mimeType item) "application/octet-stream")
                     :url (str (:uri item))}
              (and (string? (:name item)) (not= "" (:name item))) (assoc :name (:name item))))

          (= "resource" kind)
          (let [res (:resource item)]
            (when (string? (:blob res))
              (cond-> {:type "file"
                       :mimeType (or (:mimeType res) "application/octet-stream")
                       :data (:blob res)}
                (some? (:uri res)) (assoc :name (str (:uri res))))))

          :else nil)))
    (when (sequential? content) content))))

(defn- describe-parts
  "What `output` says when a server returned ONLY non-text content: name the
  parts rather than hand the model an empty string."
  [parts]
  (str/join "\n" (map content/summarize-part parts)))

(defn shape-result
  "SPEC §0.4, all three branches:
     isError            ⇒ error ToolResult carrying the joined text
     structuredContent  ⇒ output is the JSON encoding of it (text ignored)
     else               ⇒ the joined text parts

  koine's `write-str` sorts keys, so the structured branch is byte-identical on
  both hosts — which is what makes §0's cross-language byte comparison possible
  at all.

  §1B: non-text `content[]` entries additionally become `ToolResult.parts`, and
  the parts are collected BEFORE the branch, not inside one. A text-only result
  gets no `:parts` key and is byte-identical to what this returned before."
  [result]
  (let [content (:content result)
        text    (str/join "\n" (concat (keep :text content) (embedded-texts content)))
        parts   (collect-parts content)]
    (tool/with-parts
      (cond
        (:isError result)           (tool/failure text)
        (:structuredContent result) (tool/success (json/write-str (:structuredContent result)))
        ;; §1B: an image-only result must not hand the model an empty string.
        (and (= "" text) (seq parts)) (tool/success (describe-parts parts))
        :else                       (tool/success text))
      parts)))

(defn- failure-text [server-name phase failure]
  (str "mcp server \"" server-name "\" failed at " phase ": " (:error failure)
       (when-let [t (:transport-error failure)] (str " (" t ")"))
       (when-let [s (:status failure)] (str " (HTTP " s ")"))
       (when-let [c (:code failure)] (str " (code " c ")"))
       (when-let [lines (seq (:stderr failure))]
         (str "; stderr: " (str/join " | " lines)))))

;; ---------------------------------------------------------------------------
;; §2  lifecycle — initialize, notifications/initialized, tools/list
;; ---------------------------------------------------------------------------

(def ^:private protocol-version "2024-11-05")
(def ^:private client-info {:name "toolnexus" :version "0.11.0"})

(def ^:private max-pages
  "A `nextCursor` that never advances is a peer bug that must not become an
  infinite loop in this process."
  100)

(defn- list-all-tools
  "tools/list, following `nextCursor`. Each PAGE gets a fresh budget, matching
  §2's per-phase rule."
  [transport timeout-ms]
  (loop [cursor nil, acc [], page 0]
    (let [res ((:rpc! transport) "tools/list" (if cursor {:cursor cursor} {}) timeout-ms)]
      (cond
        (rpc-failed? res) res
        :else
        (let [defs   (vec (get-in res [:ok :result :tools]))
              nxt    (get-in res [:ok :result :nextCursor])
              acc    (into acc defs)]
          (if (and nxt (not= nxt cursor) (< (inc page) max-pages))
            (recur nxt acc (inc page))
            {:ok acc}))))))

(defn mcp-hints
  "SPEC §4C — MCP tool annotations as Tool hints. No hint fields at all ⇒ nil
  (unknown). MCP's defaults when a field is absent: readOnlyHint false,
  destructiveHint true, openWorldHint true; a read-only tool is never
  destructive. Advisory — a server can mislabel its tools."
  [annotations]
  (let [a  (if (map? annotations) annotations {})
        ro (get a :readOnlyHint)
        de (get a :destructiveHint)
        ow (get a :openWorldHint)]
    (when-not (and (nil? ro) (nil? de) (nil? ow))
      (let [read-only (true? ro)]
        {:read-only   read-only
         :destructive (and (not read-only) (if (nil? de) true (true? de)))
         :open-world  (if (nil? ow) true (true? ow))}))))

(defn- uniform-tool
  "A listed MCP tool def becomes a §0.1 Tool. `execute` closes over the
  transport, so a tool from a stdio server and a tool from a remote server are
  the same value to everything downstream."
  [server-name transport timeout-ms t]
  (let [remote-name (:name t)]
    (assoc (tool/tool {:name         (mcp-tool-name server-name remote-name)
                       :description  (or (:description t) "")
                       :input-schema (or (:inputSchema t) {:type "object"})
                       :source       "mcp"
                       :hints        (mcp-hints (:annotations t))
                       :execute
                       (fn execute-mcp
                         ([args] (execute-mcp args nil))
                         ([args _ctx]
                          (let [res ((:rpc! transport) "tools/call"
                                     {:name remote-name :arguments (or args {})}
                                     timeout-ms)]
                            (if (rpc-failed? res)
                              (tool/failure (failure-text server-name "tools/call" res))
                              (shape-result (get-in res [:ok :result]))))))})
           :server      server-name
           :remote-name remote-name)))

(defn connect-frozen
  "SPEC §2.F — THE FROZEN FALLBACK: the pre-2026 path, reached only when
  `server/discover` says the server is not a 2026-07-28 server (§2.2a).

  Reach the server, `initialize`, `notifications/initialized`,
  `tools/list`. Returns a CONNECTION:

    {:name … :status \"connected\"|\"disabled\"|\"failed\" :tools [Tool…]
     :transport … :server-info … :error {…}}

  Never throws. §0.3: a server that fails to connect is recorded `failed` and
  the caller carries on with the servers that did work.

  EXIT vs EOF — ANSWERED, as of koine 0.8.0. When a stdio peer stops talking,
  `read-line!` returns nil, and that alone cannot say whether the child exited,
  crashed, or merely closed stdout while still running. This port used to report
  a bare `peer-eof` for all three, and distinguished a dead peer from a quiet
  one with a `kill!` timeout — a guess dressed as a policy, wrong in both
  directions: too short kills a slow peer, too long hangs on a dead one.

  `koine.process/exit-code` replaces the guess with an observation: nil = has
  not exited, a number = the status. The close reason is now `peer-exited
  (status N)` where the status is known, and `:exit-code` is on the connection
  for a caller whose retry logic needs to tell a crash from a hang.

  ONE TRAP, MEASURED RATHER THAN REASONED: exit-code must NOT be read the
  instant EOF arrives. A child running `sh -c 'echo …>&2; exit 3'` reported nil
  on 4 of 5 runs at that moment — closing stdout and the reaper recording the
  status are different events, and EOF wins the race. Read naively, that turns
  a do-not-know-yet into a confident still-running, which is a worse answer
  than the `peer-eof` it replaced. This layer waits up to ~250 ms for the reaper,
  and if the status is still unknown it SAYS unknown rather than guessing
  alive.

  Note that `alive?` is still not consulted for this. It answers a different
  question, and koine reads its own reaper for exit rather than cljgo's native
  `:exit-code` so there is ONE source of truth for has-it-exited — two would
  drift. The child's stderr ring remains attached to the failure, because a
  status code says THAT it died and the stderr says WHY."
  ([server] (connect-frozen server nil))
  ([server {:keys [wait-for] :as conn-opts}]
  (let [timeout-ms (or (:timeout server) default-timeout-ms)
        fail       (fn [phase transport failure]
                     (when transport (try ((:close! transport)) (catch Throwable _ nil)))
                     {:name   (:name server)
                      :status "failed"
                      :phase  phase
                      :error  failure
                      :message (failure-text (:name server) phase failure)
                      :tools  []})]
    (cond
      (not (:enabled server))
      {:name (:name server) :status "disabled" :tools []}

      (= "unknown" (:kind server))
      (fail "config" nil {:error "config" :message "server has neither `command` nor `url`"})

      :else
      (let [transport (try
                        (cond
                          ;; §2.2a: a stdio discover probe that got a fast
                          ;; reply hands its LIVE child over — no respawn.
                          (::probe-transport conn-opts) (::probe-transport conn-opts)
                          (= "remote" (:kind server))
                          ;; conn-opts carries the §10 waitFor to BOTH legs now —
                          ;; the elicitation bridge is no longer stdio-only.
                          (http-transport server conn-opts)
                          :else (stdio-transport server conn-opts))
                        (catch Throwable e
                          {:spawn-error (or (ex-message e) (str e))}))]
        (if (:spawn-error transport)
          (fail "connect" nil {:error "transport" :transport-error "spawn-failed"
                               :message (:spawn-error transport)})
          (let [with-stderr (fn [failure]
                              ;; stderr is drained on ANOTHER thread, so the
                              ;; lines that explain a crash can still be in
                              ;; flight at the instant we notice the crash.
                              ;;
                              ;; koine 0.8.2 corrected its own docstring here:
                              ;; an EMPTY ring means nothing has ARRIVED YET,
                              ;; never that the child wrote nothing. Reading it
                              ;; once and concluding no-stderr is the same race
                              ;; as reading exit-code once and concluding
                              ;; still-running — and it bites hardest exactly
                              ;; when it matters, collecting the crash report
                              ;; from a child that just died.
                              ;;
                              ;; This used to be a fixed 50ms sleep, which was
                              ;; the same GUESS shape as the kill! timeout this
                              ;; port already deleted: too short loses the
                              ;; reason, too long taxes every failure. Now it
                              ;; polls to a deadline and stops as soon as a line
                              ;; lands.
                              ;;
                              ;; A genuinely silent child pays the full deadline.
                              ;; That is accepted: this runs only on the FAILURE
                              ;; path, where 300ms buys the difference between
                              ;; "server failed" and "server failed: <reason>".
                              (let [lines (loop [tries 0]
                                            (let [ls (seq ((:stderr transport)))]
                                              (cond ls ls
                                                    (>= tries 60) nil
                                                    :else (do (ktime/sleep! 5)
                                                              (recur (inc tries))))))]
                                (assoc failure :stderr (vec (take-last 5 lines)))))
                init ((:rpc! transport) "initialize"
                      {:protocolVersion protocol-version
                       ;; SPEC §10: advertise `elicitation` ONLY when a host
                       ;; waitFor can actually satisfy it. Promising a
                       ;; capability we would then have to refuse is worse than
                       ;; degrading — a spec-compliant server simply never asks.
                       :capabilities    (if wait-for {:elicitation {}} {})
                       :clientInfo      client-info}
                      timeout-ms)]
            (if (rpc-failed? init)
              (fail "initialize" transport (with-stderr init))
              (do
                ((:notify! transport) "notifications/initialized" {})
                (let [listed (list-all-tools transport timeout-ms)]
                  (if (rpc-failed? listed)
                    (fail "tools/list" transport (with-stderr listed))
                    {:name        (:name server)
                     :status      "connected"
                     :transport   transport
                     :timeout-ms  timeout-ms
                     :server-info (get-in init [:ok :result :serverInfo])
                     ;; Bare `sort-by` is CORRECT here, unlike `parse-config`'s
                     ;; above: every name in this list came out of
                     ;; `mcp-tool-name`, i.e. `tool/sanitize` twice, so it is
                     ;; `[a-zA-Z0-9_-]+` by construction. Code-unit and
                     ;; code-point order coincide over ASCII, so there is no
                     ;; host divergence to fix and no mutation that could prove
                     ;; one — which is why this is a comment and not a change.
                     :tools       (->> (:ok listed)
                                       (map (fn [t] (uniform-tool (:name server) transport timeout-ms t)))
                                       (sort-by :name)
                                       vec)})))))))))))

;; ---------------------------------------------------------------------------
;; §2 (2026-07-28) — discover first; the frozen path above is the fallback
;; ---------------------------------------------------------------------------
;;
;; `connect` sends `server/discover` before anything else. A 2026 server is used
;; on the path below: no `initialize`, no session, every request enveloped
;; (`toolnexus.mcp2026`). Anything §2.2a calls a fallback goes to
;; `connect-frozen`, untouched. Both paths build Tools through the SAME
;; `mcp-tool-name`, `mcp-hints` and `shape-result`, so naming and the result
;; mapping cannot drift between them.

(def ^:private discover-probe-ms
  "§2.2a — a stdio discover that gets no reply within min(timeout, 5 s) falls back."
  5000)

(def ^:private max-input-rounds 8)

(defn- answer-map
  "A §10 Answer with its top-level keys as keywords (a host may round-trip it
  through JSON). Same normalisation as `toolnexus.client/as-answer`."
  [a]
  (when (map? a)
    (reduce (fn [m e] (assoc m (if (keyword? (key e)) (key e) (keyword (str (key e)))) (val e))) {} a)))

(defn- pending-result
  "§0.12 — a suspension is data on an ordinary ToolResult."
  [request]
  {:output (str (:prompt request)) :isError true :metadata {:pending request}})

(defn- resolve-auth!
  "Answer a paused authorization through `wait-for` inline. nil on success, else
  the failure text (§2A.1a: `authorization denied: <reason or \"cancelled\">`)."
  [engine wait-for request]
  (try
    (let [a (answer-map (wait-for request))]
      (if (:ok a)
        (do (auth/resume! engine (:id request) (auth/answer-callback a)) nil)
        (auth/answer-denied-text a)))
    (catch Throwable x (or (ex-message x) "authorization failed"))))

(defn- failure-text-2026 [server-name phase failure]
  (if (and (= "auth" (:error failure)) (:message failure))
    (str "mcp server \"" server-name "\" failed at " phase ": " (:message failure))
    (failure-text server-name phase failure)))

(defn- load-rpc!
  "A load-phase request: a paused authorization is answered inline through
  `wait-for`; without one the server fails with `authorization pending` (§2A.1a)."
  [transport engine wait-for method params timeout-ms]
  (loop [n 0]
    (let [r ((:rpc! transport) method params timeout-ms)]
      (if (= "auth-pending" (:error r))
        (cond
          (nil? wait-for) {:error "auth" :message "authorization pending"}
          (>= n m26/max-auth-retries) {:error "auth" :message "authorization failed"}
          :else (if-let [msg (resolve-auth! engine wait-for (:request r))]
                  {:error "auth" :message msg}
                  (recur (inc n))))
        r))))

(defn- input->request
  "One MRTR input request as a §10 Request (same mapping as the §2.F
  elicitation bridge), or nil for a method §2.5 does not define."
  [ir]
  (when (= "elicitation/create" (:method ir))
    ;; same mapping as the bridge; the id is pinned `mrtr-<random>`
    (assoc (elicitation->request (or (:params ir) {})) :id (str "mrtr-" (auth/rand-str 16)))))

(declare answer-inputs)

(defn- run-tool-call
  "SPEC §2.5 — tools/call, looping over `input_required` rounds.

  `st` is the round being sent: {:name :args :responses :request-state :round}.
  With `wait-for` every input request is answered inline; without one the call
  returns a §10 `pending` result and the round is SAVED under the request id,
  so re-executing the tool with `ctx :answer` resumes exactly that round —
  earlier rounds are never re-asked and nothing is re-sent behind the caller's
  back (§2.6). A paused AUTHORIZATION is the same: the paused round is sent for
  the first time after the answer."
  [{:keys [server-name transport engine wait-for timeout-ms saved] :as env} st]
  (loop [st st auth-n 0]
    (if (>= (:round st) max-input-rounds)
      (tool/failure (str "tools/call " (:name st) ": too many input_required rounds"))
      (let [params (cond-> {:name (:name st) :arguments (or (:args st) {})}
                     (:responses st)           (assoc :inputResponses (:responses st))
                     (some? (:request-state st)) (assoc :requestState (:request-state st)))
            r ((:rpc! transport) "tools/call" params timeout-ms)]
        (cond
          (= "auth-pending" (:error r))
          (let [req (:request r)]
            (cond
              (nil? wait-for)
              (do (swap! saved assoc (:id req) {:type :auth :round st})
                  (pending-result req))
              (>= auth-n m26/max-auth-retries) (tool/failure "authorization failed")
              :else (if-let [msg (resolve-auth! engine wait-for req)]
                      (tool/failure msg)
                      (recur st (inc auth-n)))))

          (:error r)
          (tool/failure (if (= "auth" (:error r))
                          (str (:message r))
                          (failure-text-2026 server-name "tools/call" r)))

          :else
          (let [result (get-in r [:ok :result])
                rt     (str (:resultType result))]
            (cond
              (= "complete" rt) (shape-result result)
              (not= "input_required" rt)
              (tool/failure (str "tools/call " (:name st) ": unknown resultType \"" rt "\""))
              :else
              (let [asks (vec (sort-by (fn [e] (str (key e))) (seq (:inputRequests result))))
                    next-st {:name (:name st) :args (:args st) :request-state (:requestState result)
                             :round (inc (:round st))}
                    out (answer-inputs env next-st asks {})]
                (if (:pending out) (:pending out) (recur (assoc next-st :responses (:responses out)) 0))))))))))

(defn- answer-inputs
  "Answer `asks` ([key input-request]…) in key order into `responses`. Returns
  {:responses …} or {:pending <ToolResult>} after saving the rest of the round."
  [{:keys [wait-for saved]} next-st asks responses]
  (loop [[[k ir] & more :as asks] asks responses responses]
    (if (empty? asks)
      {:responses responses}
      (let [req (when (map? ir) (input->request ir))]
        (cond
          (nil? req)
          (recur more (assoc responses k {:action "cancel"}))

          wait-for
          (recur more (assoc responses k
                             (try (answer->elicit-result (answer-map (wait-for req)))
                                  (catch Throwable _ {:action "cancel"}))))

          :else
          (do (swap! saved assoc (:id req) {:type :input :round next-st :key k
                                            :rest (vec more) :responses responses})
              {:pending (pending-result req)}))))))

(defn- resume-tool-call
  "Resume a saved round with the §10 Answer that names it."
  [{:keys [engine] :as env} s answer]
  (case (:type s)
    :auth  (if (:ok answer)
             (if-let [msg (try (auth/resume! engine (:id answer) (auth/answer-callback answer)) nil
                               (catch Throwable x (or (ex-message x) "authorization failed")))]
               (tool/failure msg)
               (run-tool-call env (:round s)))
             (tool/failure (auth/answer-denied-text answer)))
    :input (let [responses (assoc (:responses s) (:key s) (answer->elicit-result answer))
                 out (answer-inputs env (:round s) (:rest s) responses)]
             (or (:pending out)
                 (run-tool-call env (assoc (:round s) :responses (:responses out)))))))

(defn- call-2026
  "The execute body every 2026 tool shares: resume a saved round when ctx
  carries the Answer for it, else start a fresh call."
  [env remote-name args ctx]
  (let [answer (answer-map (:answer ctx))
        [old _] (if answer (swap-vals! (:saved env) dissoc (:id answer)) [nil nil])
        s (when answer (get old (:id answer)))]
    (if s
      (resume-tool-call env s answer)
      (run-tool-call env {:name remote-name :args args :round 0}))))

(defn- def->tool [server-name call-fn t]
  (let [remote-name (:name t)]
    (assoc (tool/tool {:name         (mcp-tool-name server-name remote-name)
                       :description  (or (:description t) "")
                       :input-schema (or (:inputSchema t) {:type "object"})
                       :source       "mcp"
                       :hints        (mcp-hints (:annotations t))
                       :context?     true
                       :execute      (fn ex ([args] (ex args nil))
                                       ([args ctx] (call-fn remote-name args ctx)))})
           :server server-name :remote-name remote-name)))

(defn search-tools-pair
  "SPEC §2.8 — the two meta-tools that replace every server tool when
  `toolSearch` is on. `defs-fn` returns the server's tool defs
  ({:name :description :inputSchema}); `call-fn` runs one by its SERVER name.
  `disabled` (final toolkit names, the `:disable-tools` list) is applied BEFORE
  search: a disabled tool is neither ranked nor callable."
  ([server-name description defs-fn call-fn] (search-tools-pair server-name description defs-fn call-fn nil))
  ([server-name description defs-fn0 call-fn disabled]
  (let [off     (set (map str disabled))
        defs-fn (fn [] (vec (remove (fn [d] (contains? off (mcp-tool-name server-name (:name d)))) (defs-fn0))))
        about (if (str/blank? (str description)) server-name (str description))]
    [(assoc (tool/tool {:name (mcp-tool-name server-name "search_tools")
                        :description (m26/search-description about)
                        :input-schema m26/search-schema
                        :source "mcp"
                        :execute (fn ex ([args] (ex args nil))
                                   ([args _ctx]
                                    (let [l    (:limit args)
                                          hits (m26/search (defs-fn) (str (or (:query args) ""))
                                                           (if (and (number? l) (pos? l)) (long l) 5)
                                                           (str (or description "")))]
                                      ;; SPEC §2.8 pin: the text IS the compact JSON array of hits
                                      (tool/success (m26/hits-json hits)))))})
            :server server-name :remote-name "search_tools")
     (assoc (tool/tool {:name (mcp-tool-name server-name "call_tool")
                        :description m26/call-description
                        :input-schema m26/call-schema
                        :source "mcp"
                        :context? true
                        :execute (fn ex ([args] (ex args nil))
                                   ([args ctx]
                                    (let [n (str (or (:name args) ""))]
                                      (if (some (fn [d] (= n (:name d))) (defs-fn))
                                        (call-fn n (let [a (:arguments args)] (if (map? a) a {})) ctx)
                                        (tool/failure (str "unknown tool \"" n "\" on " server-name))))))})
            :server server-name :remote-name "call_tool")])))

(defn- frozen-with-search
  "§2.8 is client-side, so it applies to a fallback server too: the frozen
  connection's own Tools are the search corpus."
  [conn server conn-opts]
  (if (and (:tool-search server) (= "connected" (:status conn)))
    (let [by-remote (into {} (map (fn [t] [(:remote-name t) t]) (:tools conn)))
          defs (mapv (fn [t] {:name (:remote-name t) :description (:description t)
                              :inputSchema (:input-schema t)})
                     (:tools conn))]
      (assoc conn :tools (search-tools-pair (:name server) (:description server) (constantly defs)
                                            (fn [n args ctx] (let [f (:execute (get by-remote n))] (if ctx (f args ctx) (f args))))
                                            (:disable-tools conn-opts))))
    conn))

(defn- finish-2026
  "A discovered 2026 server: tools/list once (§2.2a), remote skills (§2.7),
  tool search (§2.8). Returns the connection."
  [server transport engine wait-for timeout-ms discover-result fail disabled]
  (let [listed (load-rpc! transport engine wait-for "tools/list" {} timeout-ms)]
    (if (:error listed)
      (fail "tools/list" listed)
      (let [lr    (get-in listed [:ok :result])
            cache (atom {:defs (vec (filter map? (:tools lr)))
                         :expiry (when-let [ttl (:ttlMs lr)] (+ (ktime/now-ms) (long ttl)))})
            defs-fn (fn []
                      (let [{:keys [defs expiry]} @cache]
                        (if (and expiry (> (ktime/now-ms) expiry))
                          (let [r (load-rpc! transport engine wait-for "tools/list" {} timeout-ms)]
                            (if (:ok r)
                              (let [nr (get-in r [:ok :result])
                                    d  (vec (filter map? (:tools nr)))]
                                (reset! cache {:defs d :expiry (when-let [ttl (:ttlMs nr)] (+ (ktime/now-ms) (long ttl)))})
                                d)
                              defs))
                          defs)))
            env   {:server-name (:name server) :transport transport :engine engine
                   :wait-for wait-for :timeout-ms timeout-ms :saved (atom {})}
            call-fn (fn [n args ctx] (call-2026 env n args ctx))
            tools (if (:tool-search server)
                    (search-tools-pair (:name server) (:description server) defs-fn call-fn disabled)
                    (->> (:defs @cache) (map (fn [t] (def->tool (:name server) call-fn t))) (sort-by :name) vec))
            skills (if (m26/skills-extension? discover-result)
                     (m26/remote-skill-defs (fn [m p] ((:rpc! transport) m p timeout-ms)))
                     [])]
        {:name        (:name server)
         :status      "connected"
         :protocol    m26/protocol-version
         :transport   transport
         :timeout-ms  timeout-ms
         :server-info (m26/server-info discover-result)
         :skills      skills
         :tools       tools}))))

(defn- connect-2026
  [server {:keys [wait-for] :as conn-opts}]
  (let [timeout-ms (or (:timeout server) default-timeout-ms)
        fail (fn [phase transport failure]
               (when transport (try ((:close! transport)) (catch Throwable _ nil)))
               {:name (:name server) :status "failed" :phase phase :error failure
                :message (failure-text-2026 (:name server) phase failure) :tools []})]
    (if (= "remote" (:kind server))
      (let [engine (auth/engine (:auth server) conn-opts)
            t      (m26/http-transport server engine)
            disc   (load-rpc! t engine wait-for "server/discover" {} timeout-ms)]
        (case (m26/discover-outcome disc)
          :2026 (finish-2026 server t engine wait-for timeout-ms (get-in disc [:ok :result])
                             (fn [phase f] (fail phase t f)) (:disable-tools conn-opts))
          ;; §2A.1a: the frozen path carries the bearer obtained during discover
          ;; as a static header. It cannot refresh it (see README).
          :fallback (let [b (auth/bearer engine (:url server))]
                      (frozen-with-search
                       (connect-frozen (cond-> server b (assoc-in [:headers "authorization"] (str "Bearer " b)))
                                       conn-opts)
                       server conn-opts))
          (fail "server/discover" nil disc)))
      (let [raw (try (stdio-transport server conn-opts)
                     (catch Throwable e {:spawn-error (or (ex-message e) (str e))}))]
        (if (:spawn-error raw)
          (fail "connect" nil {:error "transport" :transport-error "spawn-failed" :message (:spawn-error raw)})
          (let [t    (m26/stdio-wrap raw)
                disc ((:rpc! t) "server/discover" {} (min timeout-ms discover-probe-ms))]
            (if (= :2026 (m26/discover-outcome disc))
              (finish-2026 server t nil (:wait-for conn-opts) timeout-ms (get-in disc [:ok :result])
                           (fn [phase f] (fail phase t f)) (:disable-tools conn-opts))
              ;; §2.2a (amended): a fast JSON-RPC reply (an error, or a result
              ;; without 2026-07-28) means the old server wrote exactly one line
              ;; and is now waiting on stdin — the SAME child continues into
              ;; `initialize` on the frozen path; the reader only demuxes by id,
              ;; so nothing is owed or stolen. Only on silence (timeout) is the
              ;; probe child terminated and a fresh one spawned. A child that
              ;; died before replying is ALSO respawned: the frozen path
              ;; re-observes the death and reports it as the load failure it
              ;; is, with its exit status and stderr.
              (if (or (:ok disc) (= "rpc-error" (:error disc)))
                (frozen-with-search (connect-frozen server (assoc conn-opts ::probe-transport raw))
                                    server conn-opts)
                (do ((:close! raw))
                    (frozen-with-search (connect-frozen server conn-opts) server conn-opts))))))))))

(defn connect
  "SPEC §2 — connect one server: `server/discover` first (§2.2), the 2026 path
  for a 2026-07-28 server, the frozen path (§2.F, `connect-frozen`) otherwise.
  Never throws; a server that fails is `failed`, isolated (§0.3).

  conn-opts: `:wait-for` (§10), `:token-store`, `:open-url` (§2A.1a)."
  ([server] (connect server nil))
  ([server conn-opts]
   (if (or (not (:enabled server)) (= "unknown" (:kind server)))
     (connect-frozen server conn-opts)
     (try (connect-2026 server conn-opts)
          (catch Throwable e
            {:name (:name server) :status "failed" :phase "connect" :tools []
             :error {:error "transport" :message (or (ex-message e) (str e))}
             :message (str "mcp server \"" (:name server) "\" failed at connect: " (or (ex-message e) (str e)))})))))

(defn download-skill!
  "SPEC §2.7 — fetch a remote skill from a connected 2026 server into
  dir/<name>/, digest- and containment-checked before anything is written."
  [connection uri dir]
  (let [t (:transport connection)]
    (m26/download-skill! (fn [m p] ((:rpc! t) m p (or (:timeout-ms connection) default-timeout-ms))) uri dir)))

(defn disconnect
  "Close a connection's transport. Idempotent, never throws, and BOUNDED — for
  stdio it kills rather than politely waits, because an unkillable disconnect is
  how one wedged server takes the host down with it."
  [connection]
  (when-let [t (:transport connection)]
    (try ((:close! t)) (catch Throwable _ nil)))
  nil)

(defn tools
  "The Tools of a connection, or of a `from-config` result."
  [x]
  (vec (or (:tools x) [])))

(defn stderr
  "The connection's recent child stderr (stdio only; [] for remote). The reason
  koine drains it: it is the only thing that explains why a server died."
  [connection]
  (if-let [t (:transport connection)] ((:stderr t)) (vec (get-in connection [:error :stderr]))))

;; ---------------------------------------------------------------------------
;; the source
;; ---------------------------------------------------------------------------

(def ^:private report-lock (atom false))

(defn- connect-reporting
  "SPEC §4C per-server readiness: connect every server CONCURRENTLY and call
  `on-server` `{:name :status :tools :error}` the moment each one is connected,
  failed or found disabled — a fast server never waits for a slow one. Calls
  are serialized. The result is still collected in name order, so the returned
  source is exactly what the serial path builds.

  (No disabled-status race to fix here: statuses are built from the returned
  connections after every server finished, never written to a shared map.)"
  [servers conn-opts on]
  (let [report! (fn [c]
                  (loop []
                    (if (compare-and-set! report-lock false true)
                      (try (on (cond-> {:name (:name c) :status (:status c) :tools (vec (:tools c))}
                                 (:message c) (assoc :error (:message c))))
                           (catch Throwable _ nil)
                           (finally (reset! report-lock false)))
                      (do (ktime/sleep! 1) (recur)))))
        ps (mapv (fn [srv]
                   (let [p (promise)]
                     (proc/run-async!
                      (fn []
                        (let [c (try (connect srv conn-opts)
                                     (catch Throwable e
                                       {:name (:name srv) :status "failed" :tools []
                                        :message (or (ex-message e) (str e))}))]
                          (try (report! c) (finally (deliver p c))))))
                     p))
                 servers)]
    (mapv deref ps)))

(defn from-config
  "SPEC §2 — the whole source in one call. `config` is a JSON string or a map.

  Returns `{:tools [Tool…] :statuses {name -> \"connected\"|\"disabled\"|\"failed\"}
  :connections [conn…] :errors {name -> message}}`.

  Servers are connected in name order, one at a time. Not because serial is
  better — it is slower — but because it is DETERMINISTIC, and §0's whole
  premise is that two runtimes given the same fixture produce the same output.
  A tool-name collision resolved by whichever server answered first is exactly
  the kind of drift this repo exists to prevent.

  `conn-opts` may carry `:on-server` (SPEC §4C): `(fn [{:keys [name status tools
  error]}])`, called once per server as soon as it finishes. With it set the
  servers connect concurrently; the returned source is still built in name
  order and is the same value."
  ([config] (from-config config nil))
  ([config conn-opts]
  (let [servers (parse-config config)
        on      (:on-server conn-opts)
        conns   (if on
                  (connect-reporting servers conn-opts on)
                  (mapv (fn [srv] (connect srv conn-opts)) servers))]
    ;; Bare `sort-by` again, and again deliberately: `mcp-tool-name` sanitized
    ;; every one of these to `[a-zA-Z0-9_-]+`. See `connect`.
    {:tools       (->> conns (mapcat :tools) (sort-by :name) vec)
     :statuses    (reduce (fn [acc c] (assoc acc (:name c) (:status c))) {} conns)
     :errors      (reduce (fn [acc c] (if (:message c) (assoc acc (:name c) (:message c)) acc))
                          {} conns)
     ;; §2.7 remote skills, as §3 S1 defs, in server-name order
     :skills      (vec (mapcat :skills conns))
     :connections conns})))

(defn disconnect-all
  "Close every connection a `from-config` opened."
  [result]
  (doseq [c (:connections result)] (disconnect c))
  nil)

;; ---------------------------------------------------------------------------
;; SPEC §4C — MCP resource tools (opt-in)
;; ---------------------------------------------------------------------------

(def ^:private read-only-hints {:read-only true :destructive false :open-world false})

(defn- rpc-error-text [f]
  (str (:error f)
       (when-let [m (:message f)] (str ": " m))
       (when-let [c (:code f)] (str " (code " c ")"))))

(defn resource-tools
  "`list_mcp_resources` and `read_mcp_resource`, bound to the CONNECTED servers
  of a `from-config` result (or a seq of connections). Both are read-only. They
  stop working once the source is disconnected. A source with no connected
  server still returns both tools; they report that no server is connected."
  [source]
  (let [conns (->> (if (map? source) (:connections source) source)
                   (filter #(= "connected" (:status %)))
                   (reduce (fn [acc c] (assoc acc (:name c) c)) {}))
        names (tool/sort-strings (keys conns))
        missing (fn [server]
                  (str "no connected MCP server " (pr-str server)
                       " (connected: " (str/join ", " names) ")"))
        rpc (fn [c method params]
              ((:rpc! (:transport c)) method params (or (:timeout-ms c) default-timeout-ms)))
        list-tool
        (tool/tool
         {:name "list_mcp_resources"
          :description "List the resources MCP servers offer (uri, name, description, mimeType). Optionally for one server."
          :input-schema {:type "object"
                         :properties {:server {:type "string" :description "Only this server (default: all)"}}
                         :additionalProperties false}
          :source "mcp"
          :hints read-only-hints
          :execute
          (fn list-resources
            ([args] (list-resources args nil))
            ([args _ctx]
             (let [want (str (or (:server args) ""))]
               (if (and (not= "" want) (not (contains? conns want)))
                 (tool/failure (str "list_mcp_resources: " (missing want)))
                 (let [rows (mapcat
                             (fn [n]
                               (let [res (rpc (get conns n) "resources/list" {})]
                                 (if (rpc-failed? res)
                                   [{:line (str n ": error: " (rpc-error-text res))}]
                                   (map (fn [r]
                                          {:line (str n " " (:uri r)
                                                      (when (seq (str (:name r))) (str " — " (:name r)))
                                                      (when (seq (str (:mimeType r))) (str " (" (:mimeType r) ")"))
                                                      (when (seq (str (:description r))) (str ": " (:description r))))
                                           :item {:server n :uri (str (:uri r)) :name (str (or (:name r) ""))
                                                  :mimeType (str (or (:mimeType r) ""))
                                                  :description (str (or (:description r) ""))}})
                                        (get-in res [:ok :result :resources])))))
                             (if (= "" want) names [want]))
                       items (vec (keep :item rows))]
                   (if (empty? rows)
                     (tool/success "No resources." {:count 0})
                     (tool/success (str/join "\n" (map :line rows))
                                   {:count (count items) :resources items})))))))})
        read-tool
        (tool/tool
         {:name "read_mcp_resource"
          :description "Read one MCP resource by server and uri. Text comes back as text; binary content is described, not inlined."
          :input-schema {:type "object"
                         :properties {:server {:type "string" :description "The MCP server name"}
                                      :uri    {:type "string" :description "The resource uri"}}
                         :required ["server" "uri"]
                         :additionalProperties false}
          :source "mcp"
          :hints read-only-hints
          :execute
          (fn read-resource
            ([args] (read-resource args nil))
            ([args _ctx]
             (let [server (str (or (:server args) ""))
                   uri    (str (or (:uri args) ""))
                   c      (get conns server)]
               (cond
                 (nil? c)   (tool/failure (str "read_mcp_resource: " (missing server)))
                 (= "" uri) (tool/failure "read_mcp_resource: uri is required")
                 :else
                 (let [res (rpc c "resources/read" {:uri uri})]
                   (if (rpc-failed? res)
                     (tool/failure (str "read_mcp_resource: " (rpc-error-text res)))
                     (tool/success
                      (str/join "\n"
                                (keep (fn [rc]
                                        (cond
                                          (string? (:text rc)) (:text rc)
                                          (string? (:blob rc))
                                          (str "[binary " (:mimeType rc) ", " (count (:blob rc)) " base64 chars]")))
                                      (get-in res [:ok :result :contents])))
                      {:server server :uri uri})))))))})]
    [list-tool read-tool]))
