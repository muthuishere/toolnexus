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

(defn- one-server [server-name m]
  (let [raw-headers (:headers m)]
    {:name        (key-str server-name)
     :kind        (server-kind m)
     ;; `disabled:true` and `enabled:false` are the same statement.
     :enabled     (not (or (true? (:disabled m)) (false? (:enabled m))))
     :command     (vec (:command m))
     :cwd         (:cwd m)
     ;; `environment` with `env` as the documented alias.
     :environment (or (:environment m) (:env m) {})
     :url         (:url m)
     :raw-headers raw-headers
     :headers     (expand-headers raw-headers)
     :timeout     (or (:timeout m) default-timeout-ms)}))

(defn parse-config
  "SPEC §0.3 / §2 — a JSON string or an already-parsed map becomes a vector of
  server maps, sorted by name so two hosts cannot disagree about order.

  Accepts `mcpServers` | `servers` | `mcp`; with no wrapper the object itself is
  the server map, minus the reserved sibling keys."
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
                  (swap! state update :notifications conj (str (:method msg)))

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
  hosts is not established; an atom and `koine.time/sleep!` are."
  [state id timeout-ms]
  (let [deadline (+ (ktime/mono-ms) (long timeout-ms))]
    (loop []
      (let [s   (deref state)
            msg (get-in s [:inbox id])]
        (cond
          (some? msg) {:ok msg}
          (:closed? s) {:error (or (:close-reason s) "closed")}
          (>= (ktime/mono-ms) deadline) {:error "timeout" :timeout-ms timeout-ms}
          :else (do (ktime/sleep! 2) (recur)))))))

(defn- stdio-rpc!
  "One request/response over stdio. Registers the id BEFORE writing — a fast
  peer can answer before `send-line!` has returned, and an unregistered id is
  dropped as unmatched."
  [child state lock method params timeout-ms]
  (let [id (next-id!)]
    (if (:closed? @state)
      {:error (or (:close-reason @state) "closed")}
      (do
        (swap! state update :pending conj id)
        (try
          (with-write-lock! lock
            (fn [] (proc/send-line! child (json/write-str (cond-> {:jsonrpc "2.0" :id id :method method}
                                                            params (assoc :params params))))))
          (let [res (await-response! state id timeout-ms)]
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
                                     (update :inbox dissoc id))))))))))

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
        state (atom {:pending #{} :inbox {} :closed? false :close-reason nil
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
        st   (atom {:msgs [] :head nil :done false :error nil})
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
                       (if (and (some? (:id m)) (:method m))
                         (when-let [reply (server-request-response wait-for m)]
                           (try (post-server-response! url headers session reply)
                                (catch Throwable _ nil)))
                         (swap! st update :msgs conj m)))))]
    ;; run-async!, not `future` — same reason as `start-reader!`: library code
    ;; must not hold a consumer's process open on a non-daemon pool thread.
    (proc/run-async!
     (fn []
       (try (stream/sse-post url hdrs body on-event {:on-open on-open})
            (catch Throwable e (swap! st assoc :error (or (ex-message e) (str e))))
            (finally (swap! st assoc :done true)))))
    (let [deadline (+ (ktime/mono-ms) (long timeout-ms))
          ;; Stop at OUR answer, not at end-of-stream: a server is entitled to
          ;; hold the stream open with keep-alives after it has responded, and
          ;; waiting for the close would spend the caller's whole budget on a
          ;; call that already succeeded.
          final    (loop []
                     (let [s @st]
                       (cond
                         (some (fn [m] (= id (:id m))) (:msgs s)) s
                         (:done s)                                s
                         (>= (ktime/mono-ms) deadline)            (assoc s :timed-out true)
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

(defn connect
  "SPEC §2 — reach the server, `initialize`, `notifications/initialized`,
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
  ([server] (connect server nil))
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
                        (if (= "remote" (:kind server))
                          ;; conn-opts carries the §10 waitFor to BOTH legs now —
                          ;; the elicitation bridge is no longer stdio-only.
                          (http-transport server conn-opts)
                          (stdio-transport server conn-opts))
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

(defn from-config
  "SPEC §2 — the whole source in one call. `config` is a JSON string or a map.

  Returns `{:tools [Tool…] :statuses {name -> \"connected\"|\"disabled\"|\"failed\"}
  :connections [conn…] :errors {name -> message}}`.

  Servers are connected in name order, one at a time. Not because serial is
  better — it is slower — but because it is DETERMINISTIC, and §0's whole
  premise is that two runtimes given the same fixture produce the same output.
  A tool-name collision resolved by whichever server answered first is exactly
  the kind of drift this repo exists to prevent."
  ([config] (from-config config nil))
  ([config conn-opts]
  (let [conns (mapv (fn [srv] (connect srv conn-opts)) (parse-config config))]
    ;; Bare `sort-by` again, and again deliberately: `mcp-tool-name` sanitized
    ;; every one of these to `[a-zA-Z0-9_-]+`. See `connect`.
    {:tools       (->> conns (mapcat :tools) (sort-by :name) vec)
     :statuses    (reduce (fn [acc c] (assoc acc (:name c) (:status c))) {} conns)
     :errors      (reduce (fn [acc c] (if (:message c) (assoc acc (:name c) (:message c)) acc))
                          {} conns)
     :connections conns})))

(defn disconnect-all
  "Close every connection a `from-config` opened."
  [result]
  (doseq [c (:connections result)] (disconnect c))
  nil)
