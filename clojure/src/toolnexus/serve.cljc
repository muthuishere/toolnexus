;; SPEC §7B + §7C — the INBOUND side. `toolkit.serve`.
;;
;; One `koine.server`, two OPT-IN profiles co-mounted on it:
;;
;;   §7B  `a2a`  GET  /.well-known/agent-card.json   the Agent Card
;;               POST /                              JSON-RPC SendMessage / GetTask
;;   §7C  `mcp`  POST /mcp                           JSON-RPC MCP streamable-HTTP
;;
;; Routing is `cond` on `(:path req)` in plain Clojure. koine.server has no
;; router by design — its `:path` is a catch-all prefix on both hosts and the
;; handler always receives `:path`, so a router would be a dependency bought to
;; replace four lines. Spikes S17 and S21 both did exactly this.
;;
;; ABSENCE IS A BEHAVIOUR. A nil profile mounts NOTHING for that protocol and
;; its paths 404 like any other. §7B says an absent `a2a` means "every request
;; 404s" and §7C says an MCP-only serve "404s all other paths"; those two
;; sentences only reconcile under PER-PROFILE mounting, which is what this does.
;;
;; ---------------------------------------------------------------------------
;; THE ASYMMETRY THAT IS LOAD-BEARING
;; ---------------------------------------------------------------------------
;; §7A/§7B skill ids are SANITIZED. §7C tool names are used VERBATIM — they were
;; already sanitized at registration (§0.2), and re-sanitizing at a gateway
;; double-prefixes names on every hop, so a toolkit holding `calc.sum` serves
;; `calc.sum` and the string `calc_sum` must appear nowhere. The test suite
;; asserts that by construction, as S21 did.
;;
;; ---------------------------------------------------------------------------
;; A KNOWN SPEC DEFECT, DELIBERATELY NOT PORTED LITERALLY — see `exposed-tools`.
;; ---------------------------------------------------------------------------
;; Portability: no reader conditional, no java.*, koine + toolnexus.tool only.
(ns toolnexus.serve
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.server :as server]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; TaskStore (§7B) — `get(id)` / `save(task)`, pluggable.
;; ---------------------------------------------------------------------------

(defn memory-store
  "The default store. A store is a plain map of two closures — not a protocol,
  not a record: those are the two things guaranteed to differ across hosts."
  []
  (let [state (atom {})]
    {:get  (fn [id] (get @state id))
     :save (fn [task] (swap! state assoc (:id task) task) task)
     :all  (fn [] @state)}))

(defn file-store
  "§7B `\"file:<dir>\"` — one `<id>.json` per task."
  [dir]
  (fs/mkdirs! dir)
  (let [path (fn [id] (str dir "/" id ".json"))]
    {:get  (fn [id] (when (fs/exists? (path id))
                      (try (json/read-str (fs/read-file (path id)))
                           (catch Throwable _ nil))))
     :save (fn [task] (fs/write-file (path (:id task)) (json/write-str task)) task)}))

(defn resolve-store
  "§7B `resolveStore(store?)`: nil | \"memory\" ⇒ in-memory (default);
  \"file:<dir>\" ⇒ a file store; anything else is used as-is."
  [store]
  (cond
    (or (nil? store) (= "memory" store))     (memory-store)
    (and (string? store)
         (str/starts-with? store "file:"))   (file-store (subs store 5))
    :else                                    store))

(defn- store-get  [store id]   ((:get store) id))
(defn- store-save [store task] ((:save store) task))

;; ---------------------------------------------------------------------------
;; §7B — the Agent Card
;; ---------------------------------------------------------------------------

(defn- skill-seq
  "Skills arrive in any of the three shapes the port produces:
    * `toolnexus.skill/list-skills` ⇒ `{:skills [info …] :skipped […]}`
    * a SkillSource-shaped index    ⇒ `{name -> info}`
    * a plain sequence of `{:name :description}`
  All normalize to a sorted seq — sorted because two runtimes must not disagree
  on order, and sorted through `tool/compare-strings` because bare `sort-by` is
  exactly where they DO disagree: skill names are not sanitized, and above the
  BMP the JVM orders by UTF-16 code unit and cljgo by UTF-8 byte. This list goes
  out on the §7B Agent Card."
  [skills]
  (sort-by :name tool/compare-strings
           (cond
             (nil? skills)                     []
             (and (map? skills)
                  (contains? skills :skills))  (:skills skills)
             (map? skills)                     (vals skills)
             :else                             skills)))

(defn agent-card
  "§7B's Agent Card, with every default the spec names.

  `skills[]` comes from the toolkit's SkillSource — NEVER from the tools —
  filtered to `a2a.skills` when given. The skill `id` is sanitized (§0.2); the
  skill `name` is not. `provider` is present ONLY when configured (absent, not
  null).

  AMBIGUITY (§7B): unknown names in `a2a.skills` are undefined. §7C explicitly
  says unknown `mcp.tools` names are ignored; we ignore them here too, by
  symmetry — an untested asymmetry is exactly how six ports drift."
  [cfg base skills]
  (let [allow   (:skills cfg)
        allowed (set allow)
        visible (cond->> (skill-seq skills)
                  (seq allow) (filter (fn [s] (contains? allowed (:name s)))))
        card    {:name               (or (:name cfg) "toolnexus-agent")
                 :description        (or (:description cfg) "")
                 :version            (or (:version cfg) "0.1.0")
                 :protocolVersion    (or (:protocolVersion cfg) "0.3.0")
                 :capabilities       {:streaming         false
                                      :pushNotifications false}
                 :defaultInputModes  ["text"]
                 ;; §1B DECISION: these stay "text", and §1B content parts are
                 ;; scoped OUT of the A2A profile rather than the card being
                 ;; widened. The card describes THIS profile only — §7B, whose
                 ;; `fulfil!` builds artifacts from `(:text r)`, a run's final
                 ;; text, which is text and nothing else. §7C `tools/call` (which
                 ;; DOES now emit image/audio/resource blocks) is a separate,
                 ;; separately-mounted profile that no Agent Card describes. A2A
                 ;; message parts are a NAMED deferral in the change's design, so
                 ;; advertising an output mode nothing produces would be the lie,
                 ;; not the reverse.
                 :defaultOutputModes ["text"]
                 :skills             (mapv (fn [s] {:id          (tool/sanitize (:name s))
                                                    :name        (:name s)
                                                    :description (or (:description s) "")})
                                           visible)
                 ;; §7B: url = base + "/" — the JSON-RPC POST endpoint.
                 :url                (str base "/")}]
    (if (:provider cfg) (assoc card :provider (:provider cfg)) card)))

(defn part->content-block
  "SPEC §7C + §1B — one ContentPart as its MCP content block.

  `image`/`audio` carrying bytes map to the SDK's own image/audio content;
  anything URL-backed becomes a `resource_link` (an MCP link is a uri, and a part
  that is already a url has no bytes to embed); a `file` carrying bytes becomes an
  embedded resource with the blob. Nothing is dropped — the reverse of
  `toolnexus.mcp/collect-parts`, so a toolkit served over MCP and consumed over
  MCP round-trips."
  [part]
  (let [mime (or (:mimeType part) "application/octet-stream")
        link (fn [] (when (:url part)
                      {:type "resource_link" :uri (str (:url part))
                       :name (str (or (:name part) "")) :mimeType mime}))]
    (cond
      (= "text" (:type part))  {:type "text" :text (str (:text part))}

      (contains? #{"image" "audio"} (:type part))
      (if (:data part)
        {:type (:type part) :data (:data part) :mimeType mime}
        (link))

      (= "file" (:type part))
      (if (:data part)
        {:type "resource"
         :resource {:uri (str (or (:name part) "file://part"))
                    :mimeType mime
                    :blob (:data part)}}
        (link))

      :else nil)))

;; ---------------------------------------------------------------------------
;; JSON-RPC plumbing shared by both profiles
;; ---------------------------------------------------------------------------

(defn- rpc-error [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- parse-body
  "[:ok msg] or [:parse-error]. The ONLY place a request body is decoded, so a
  malformed body can only ever become -32700 — never a 500, never a dead server.
  `Throwable` is a bare symbol: portable on both hosts.

  AMBIGUITY (§7B/§7C): neither section says whether a JSON-RPC error travels on
  HTTP 200 or HTTP 400, and §7C never names a parse-error code at all. We answer
  HTTP 200 with `-32700` on both paths (JSON-RPC 2.0's usual reading)."
  [body]
  (try
    (let [m (json/read-str (str body))]
      (if (map? m) [:ok m] [:parse-error]))
    (catch Throwable _ [:parse-error])))

(defn- guarded
  "Run an RPC handler so that NOTHING escapes onto the server thread. §7B's
  \"a fulfilment error never crashes the server\" and §7C's \"never crashes the
  server\" are only true if this holds for the request path too."
  [f]
  (try (f)
       (catch Throwable e
         (rpc-error nil -32603 (str "Internal error: " (or (ex-message e) (str e)))))))

;; ---------------------------------------------------------------------------
;; §7B — the JSON-RPC endpoint and async fulfilment
;; ---------------------------------------------------------------------------

(defn- fulfil!
  "§7B async fulfilment: save `working` → run → save `completed` with artifacts,
  or on a throw save `failed` with `status.message`.

  The load-bearing part is that NOTHING escapes this fn. It runs on a `future`;
  an escaped throw would be swallowed by the future and leave the task stuck in
  `working` forever, which is the failure mode §7B's sentence is really about.

  NOTE (§7B, S21 finding 6): saving `working` before the run is a STORAGE
  obligation, not an observability one — a fast run may go submitted →
  completed with no peer ever seeing `working`. The tests gate a slow
  fulfilment rather than racing it."
  [store task-id text run-fn on-task]
  (try
    (store-save store {:id task-id :status {:state "working"}})
    (let [settled (try
                    (let [r (run-fn text)]
                      {:id        task-id
                       :status    {:state "completed"}
                       :artifacts [{:artifactId (str (random-uuid))
                                    :parts      [{:kind "text"
                                                  :text (str (:text r))}]}]})
                    (catch Throwable e
                      ;; `failed` carries status.message and NO artifacts key.
                      {:id     task-id
                       :status {:state   "failed"
                                :message {:role  "agent"
                                          :parts [{:kind "text"
                                                   :text (str (or (ex-message e) (str e)))}]}}}))]
      (store-save store settled)
      (when on-task
        (try (on-task {:id     task-id
                       :task   text
                       :state  (get-in settled [:status :state])
                       :result settled})
             (catch Throwable _ nil)))
      settled)
    (catch Throwable _ nil)))

(defn- a2a-rpc [store run-fn on-task body]
  (let [[tag msg] (parse-body body)]
    (if (= tag :parse-error)
      (rpc-error nil -32700 "Parse error")
      (let [id (:id msg)]
        (case (:method msg)
          "SendMessage"
          (let [task-id (str (random-uuid))
                text    (->> (get-in msg [:params :message :parts])
                             (keep :text)
                             (str/join " "))
                task    {:id task-id :status {:state "submitted"}}]
            (store-save store task)
            ;; run-async!, not `future` — see execute-calls in client.cljc.
            ;; A server is the worst place for the non-daemon pool: one thread
            ;; per A2A task, each keeping the host process alive after shutdown.
            (proc/run-async! (fn [] (fulfil! store task-id text run-fn on-task)))
            ;; §7B: return it IMMEDIATELY, in the submitted state.
            {:jsonrpc "2.0" :id id :result task})

          "GetTask"
          (if-let [t (store-get store (get-in msg [:params :id]))]
            {:jsonrpc "2.0" :id id :result t}
            (rpc-error id -32001 "Task not found"))

          (rpc-error id -32601 "Method not found"))))))

;; ---------------------------------------------------------------------------
;; §7C — the MCP streamable-HTTP endpoint
;; ---------------------------------------------------------------------------

(defn exposed-tools
  "The tools this MCP profile exposes: `mcp.tools` filtered to exactly those
  names, UNKNOWN NAMES IGNORED (§7C, explicit); omitted ⇒ every toolkit tool.
  Sorted, so two runtimes cannot disagree on order.

  ============================ KNOWN SPEC DEFECT ============================
  §7C says only that `mcp.tools` filters the LIST. Ported literally, a tool
  excluded from `tools/list` is STILL CALLABLE through `tools/call` — which
  turns what every reader takes for an allowlist into a cosmetic filter, on the
  one surface (`serve`) whose whole job is exposing a toolkit to strangers.

  This function is therefore AUTHORITATIVE FOR CALLS TOO: `tools/call` resolves
  the tool out of this same filtered set, so an excluded tool answers -32602
  exactly like a tool that does not exist. Spike S21 made the same call. It is a
  deliberate, security-motivated deviation from a literal reading, it is
  reported upward, and §7C needs a sentence — not six ports each guessing."
  [tk cfg]
  (let [allow   (:tools cfg)
        allowed (set allow)
        ts      (vals (:tools tk))]
    ;; `tool/compare-strings`, not bare `sort-by`: above the BMP the JVM orders
    ;; by UTF-16 code unit and cljgo by UTF-8 byte, which are OPPOSITE answers —
    ;; so plain `sort` would have made the docstring's "two runtimes cannot
    ;; disagree on order" false on exactly the surface it is claimed for.
    (sort-by :name tool/compare-strings
             (if (seq allow)
               (filter (fn [t] (contains? allowed (:name t))) ts)
               ts))))

(defn- mcp-rpc [tk cfg on-call body]
  (let [[tag msg] (parse-body body)]
    (if (= tag :parse-error)
      (rpc-error nil -32700 "Parse error")
      (let [id     (:id msg)
            params (:params msg)]
        (case (:method msg)
          "initialize"
          {:jsonrpc "2.0" :id id
           :result  {:protocolVersion (or (:protocolVersion cfg) "2024-11-05")
                     :capabilities    {:tools {}}
                     :serverInfo      {:name    (or (:name cfg) "toolnexus")
                                       :version (or (:version cfg) "0.1.0")}}}

          "tools/list"
          {:jsonrpc "2.0" :id id
           :result  {:tools (mapv (fn [t]
                                    ;; §7C: Tool.name VERBATIM. No sanitize here.
                                    {:name        (:name t)
                                     :description (:description t)
                                     :inputSchema (:input-schema t)})
                                  (exposed-tools tk cfg))}}

          "tools/call"
          (let [tool-name (:name params)
                visible   (exposed-tools tk cfg)
                t         (first (filter (fn [x] (= tool-name (:name x))) visible))]
            (if-not t
              ;; unknown OR filtered-out — see `exposed-tools`.
              (rpc-error id -32602 (str "Unknown tool: " tool-name))
              (let [;; §0.8 via toolnexus.tool/execute: an execute THROW becomes
                    ;; an error ToolResult, never an escaped exception.
                    r (tool/execute {:tools {tool-name t}} tool-name
                                    (or (:arguments params) {}))]
                (when on-call
                  (try (on-call {:name    tool-name
                                 :source  (:source t)
                                 :isError (boolean (:isError r))})
                       (catch Throwable _ nil)))
                {:jsonrpc "2.0" :id id
                 ;; §7C: output -> one {type:"text"} part; isError propagates.
                 ;; §1B: each non-text ContentPart follows it as the matching MCP
                 ;; content block, in order. `metadata` is NOT on the MCP wire
                 ;; (on-call only).
                 :result  {:content (into [{:type "text" :text (str (:output r))}]
                                          (keep part->content-block (:parts r)))
                           :isError (boolean (:isError r))}})))

          (rpc-error id -32601 "Method not found"))))))

;; ---------------------------------------------------------------------------
;; serve
;; ---------------------------------------------------------------------------

(def ^:private not-found
  {:status 404 :headers {"content-type" "text/plain"} :body "Not Found"})

(defn- run-fn-of
  "§7B fulfils a Task through `client.run(<task text>, {toolkit})`. Until the §8
  client namespace lands, `serve` accepts either `:client` (a map carrying a
  `:run` of [prompt opts]) or a bare `:run` of [prompt]. Neither ⇒ every task
  fails loudly rather than hanging in `working`."
  [tk {:keys [client run]}]
  (cond
    run    run
    client (fn [text] ((:run client) text {:toolkit tk}))
    :else  (fn [_] (throw (ex-info "serve: no :client and no :run configured" {})))))

(defn serve
  "SPEC §7B/§7C `toolkit.serve(addr, {client, a2a?, mcp?, onTask?, onCall?})`.

    (serve tk {:port 0 :a2a {…} :mcp {…} :skills … :run f})

  opts
    :port :host   passed to koine.server (`:port 0` ⇒ an OS-assigned port)
    :a2a          §7B profile map, or nil/absent ⇒ NO A2A routes
    :mcp          §7C profile map, or nil/absent ⇒ NO /mcp
    :skills       the SkillSource the Agent Card advertises
    :client/:run  the fulfilment (see `run-fn-of`)
    :store        §7B TaskStore (see `resolve-store`)
    :on-task      fires on a terminal Task state
    :on-call      fires per inbound `tools/call`

  Returns a ServeHandle: `{:url :port :stop! :store :handle}`. `stop!` is also
  available as a fn of the handle."
  [tk {:keys [port host a2a mcp skills store on-task on-call] :as opts}]
  (let [base    (atom "")
        st      (resolve-store store)
        run-fn  (run-fn-of tk opts)
        handler (fn [req]
                  (let [path     (:path req)
                        json-res (fn [m] {:status  200
                                          :headers {"content-type" "application/json"}
                                          :body    (json/write-str m)})]
                    (cond
                      (and a2a (= path "/.well-known/agent-card.json"))
                      (json-res (agent-card a2a @base skills))

                      (and a2a (= path "/"))
                      (json-res (guarded #(a2a-rpc st run-fn on-task (:body req))))

                      (and mcp (= path "/mcp"))
                      (json-res (guarded #(mcp-rpc tk mcp on-call (:body req))))

                      :else not-found)))
        h       (server/serve handler (cond-> {:port (or port 0)}
                                        host (assoc :host host)))
        url     (str "http://127.0.0.1:" (server/port h))]
    (reset! base url)
    {:handle h
     :url    url
     :port   (server/port h)
     :store  st
     :stop!  (fn [] (server/stop! h))}))

(defn stop!
  "Shut a ServeHandle down. Idempotent, like koine's."
  [handle]
  (server/stop! (:handle handle))
  nil)
