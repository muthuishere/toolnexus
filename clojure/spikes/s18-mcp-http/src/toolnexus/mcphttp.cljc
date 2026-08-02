;; S18 — MCP streamable-HTTP (the REMOTE transport) in portable Clojure.
;;
;; S15 proved the stdio transport against a real MCP child. This spike asks the
;; other half of SPEC §2: does the REMOTE leg work — JSON-RPC over HTTP —
;; identically on Clojure (JVM) and cljgo, in ONE .cljc with no reader
;; conditional?
;;
;; What it drives, all of it real, all of it on 127.0.0.1:
;;
;;   §0.2  tool naming              sanitize(server)_sanitize(tool)
;;   §0.3  ${ENV} header expansion  — reported as SHAPE only, never a value
;;   §0.3  failure isolation        — a remote server that is DOWN is `failed`,
;;                                    never fatal; the toolkit keeps working
;;   §0.4  result shaping           — all THREE branches, each proved by a
;;                                    separate tool on the fake server:
;;                                    isError / structuredContent / joined text
;;   §2    initialize, tools/list, tools/call over streamable-HTTP
;;   +     the two degradations the transport must survive without crashing:
;;         a non-2xx response, and a 200 whose body is not JSON
;;
;; The remote server is a koine.server we stand up ourselves on 127.0.0.1:0
;; speaking MCP JSON-RPC. It advertises four tools in SCRAMBLED order on the
;; wire, so the client's sort is a measurement and not a coincidence.
;;
;; Hermetic: no internet, no key, no LLM. One .cljc, zero reader conditionals,
;; zero java.*, zero Go interop.

(ns toolnexus.mcphttp
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.fs :as fs]
            [koine.env :as env]
            [koine.host :as host]
            [koine.http :as http]
            [koine.stream :as stream]
            [koine.server :as server]))

;; ---------------------------------------------------------------------------
;; ids — never literals; SPEC §8 parallel tool calls make two calls in flight
;; the normal case, and a literal id is a silent wrong-answer-to-the-wrong-caller
;; bug the moment that happens.
;; ---------------------------------------------------------------------------

(def ^:private ids (atom 0))
(defn- next-id [] (swap! ids inc))

;; ---------------------------------------------------------------------------
;; §0.2  naming
;; ---------------------------------------------------------------------------

(defn sanitize
  "SPEC §0.2 — replace [^a-zA-Z0-9_-] with _."
  [s]
  (str/replace (str s) #"[^a-zA-Z0-9_-]" "_"))

(defn mcp-tool-name
  "SPEC §0.2 — sanitize(server)_sanitize(tool)."
  [server-name tool]
  (str (sanitize server-name) "_" (sanitize tool)))

;; ---------------------------------------------------------------------------
;; §0.3  remote server config
;; ---------------------------------------------------------------------------

(def default-timeout-ms 30000)

(defn expand-headers
  "SPEC §0.3 — remote `headers` VALUES expand ${ENV_VAR} from the environment.
  The expanded value is used and NEVER logged; callers may report only whether
  expansion changed something (see `header-shape`)."
  [headers]
  (reduce (fn [acc entry] (assoc acc (name (key entry)) (env/expand (str (val entry)))))
          {} headers))

(defn header-shape
  "The only thing about headers that may leave this process: which keys exist,
  and whether ${ENV} expansion CHANGED anything. Never a value, never a length
  (a length leaks a secret's size)."
  [raw expanded]
  {:keys     (vec (sort (map (fn [entry] (name (key entry))) expanded)))
   :expanded (boolean (some (fn [entry]
                              (not= (val entry) (str (get raw (key entry)))))
                            (reduce (fn [acc e] (assoc acc (name (key e)) (val e))) {} expanded)))})

(defn- raw-by-name [headers]
  (reduce (fn [acc e] (assoc acc (name (key e)) (str (val e)))) {} headers))

(defn parse-mcp-config
  "SPEC §0.3 — accept mcpServers | servers | mcp; url ⇒ remote, command ⇒ local;
  disabled / enabled:false ⇒ skipped. Sorted by name so no two hosts can
  disagree about order."
  [text]
  (let [cfg     (json/read-str text {:key-fn keyword})
        servers (or (:mcpServers cfg) (:servers cfg) (:mcp cfg) {})]
    (->> servers
         (map (fn [entry]
                (let [m (val entry)]
                  {:name        (name (key entry))
                   :kind        (cond (:url m) "remote" (:command m) "local" :else "unknown")
                   :enabled     (not (or (true? (:disabled m)) (false? (:enabled m))))
                   :url         (:url m)
                   :raw-headers (raw-by-name (:headers m))
                   :headers     (expand-headers (:headers m))
                   :timeout     (or (:timeout m) default-timeout-ms)})))
         (sort-by :name)
         vec)))

;; ---------------------------------------------------------------------------
;; §2  the streamable-HTTP transport
;; ---------------------------------------------------------------------------
;;
;; One JSON-RPC message per HTTP POST. Every way this can go wrong is DATA with
;; a stable name — nothing here ever throws, because SPEC §0.3 requires a bad
;; remote server to be isolated, and an exception crossing this boundary is
;; exactly how "isolated" turns into "fatal".

(defn- err-name [e] (if e (name e) "unknown"))

(defn- ok-status? [status] (and status (>= status 200) (< status 300)))

(defn http-rpc!
  "Send one JSON-RPC request over HTTP. Returns {:ok <message>} or
  {:error \"<name>\" ...}. NEVER throws.

  The four named failures:
    transport      — koine.http/failed? is true; :error carries koine's
                     classification (:connect-failed / :timeout / :dns /
                     :transport) as DATA, which is the whole reason koine
                     classifies centrally.
    http-status    — a real HTTP answer that is not 2xx.
    malformed-body — 2xx whose body is not JSON.
    rpc-error      — well-formed JSON-RPC carrying an `error` member."
  [url headers method params]
  (let [res (http/request {:method  :post
                           :url     url
                           :headers (merge {"content-type" "application/json"
                                            "accept"       "application/json, text/event-stream"}
                                           headers)
                           :body    (json/write-str {:jsonrpc "2.0" :id (next-id)
                                                     :method method :params params})})]
    (cond
      (http/failed? res)
      {:error "transport" :transport-error (err-name (:error res)) :status (:status res)}

      (not (ok-status? (:status res)))
      {:error "http-status" :status (:status res)}

      :else
      (let [parsed (try (json/read-str (:body res)) (catch Throwable _ ::bad))]
        (cond
          (or (= ::bad parsed) (not (map? parsed))) {:error "malformed-body" :status (:status res)}
          (:error parsed) {:error "rpc-error" :code (get-in parsed [:error :code])}
          :else {:ok parsed})))))

(defn sse-rpc!
  "The OTHER half of streamable-HTTP: the server is allowed to answer a POST
  with `text/event-stream` instead of `application/json`, and the JSON-RPC
  response arrives as one or more `data:` frames. Without this a client is
  doing plain JSON-over-POST and calling it streamable-HTTP.

  Returns {:ok <message> :frames n} or {:error \"...\"}; never throws —
  `sse-post` DOES throw on a host with no incremental route, and that has to
  become an isolated failure like every other one (§0.3)."
  [url headers method params]
  (let [frames (atom [])]
    (try
      (let [res (stream/sse-post url
                                 (merge {"content-type" "application/json"
                                         "accept"       "text/event-stream"}
                                        headers)
                                 (json/write-str {:jsonrpc "2.0" :id (next-id)
                                                  :method method :params params})
                                 (fn [data] (swap! frames conj data)))
            msgs (keep (fn [d] (try (json/read-str d) (catch Throwable _ nil))) @frames)]
        (cond
          (not (ok-status? (:status res))) {:error "http-status" :status (:status res)}
          (empty? msgs)                    {:error "no-sse-frames" :frames (count @frames)}
          :else {:ok (last msgs) :frames (count @frames)}))
      (catch Throwable _ {:error "sse-unsupported" :frames (count @frames)}))))

(defn http-transport
  "A transport is a plain map — the same shape S17 gives stdio — so everything
  above §2 is written once against `rpc!` and never against a host object."
  [url headers]
  {:kind "http" :url url :headers headers})

(defn rpc! [transport method params]
  (http-rpc! (:url transport) (:headers transport) method params))

;; ---------------------------------------------------------------------------
;; §2  lifecycle: initialize -> tools/list ; §0.3 isolation on failure
;; ---------------------------------------------------------------------------

(defn- uniform-tool [server-name transport t]
  {:name        (mcp-tool-name server-name (:name t))
   :remote-name (:name t)
   :description (or (:description t) "")
   :inputSchema (or (:inputSchema t) {:type "object"})
   :source      "mcp"
   :transport   transport})

(defn connect-remote!
  "SPEC §2 — initialize, then tools/list, over streamable-HTTP.
  SPEC §0.3 — on ANY failure the server is recorded `failed` and the caller
  carries on with the servers that did work. Never throws, never fatal."
  [server]
  (let [transport (http-transport (:url server) (:headers server))
        init      (rpc! transport "initialize"
                        {:protocolVersion "2024-11-05"
                         :capabilities    {}
                         :clientInfo      {:name "toolnexus-clj-spike" :version "0.0.1"}})]
    (if (:error init)
      {:name (:name server) :status "failed" :phase "initialize"
       :failure (dissoc init :ok) :tools []}
      (let [listed (rpc! transport "tools/list" {})]
        (if (:error listed)
          {:name (:name server) :status "failed" :phase "tools/list"
           :failure (dissoc listed :ok) :tools []}
          {:name        (:name server)
           :status      "ready"
           :server-info (get-in init [:ok :result :serverInfo])
           :tools       (->> (get-in listed [:ok :result :tools])
                             (map (fn [t] (uniform-tool (:name server) transport t)))
                             (sort-by :name)
                             vec)})))))

;; ---------------------------------------------------------------------------
;; §0.4  result shaping — the three branches
;; ---------------------------------------------------------------------------

(defn mcp-result
  "SPEC §0.4 — isError ⇒ error with joined text; structuredContent ⇒ JSON
  string; else joined text parts."
  [result]
  (let [text (str/join "\n" (keep :text (:content result)))]
    (cond
      (:isError result)           {:output text :isError true :branch "isError"}
      (:structuredContent result) {:output (json/write-str (:structuredContent result))
                                   :isError false :branch "structuredContent"}
      :else                       {:output text :isError false :branch "text"})))

(defn call-tool!
  "tools/call over the tool's own transport. A transport failure becomes an
  error ToolResult (§0.1) rather than an exception."
  [tool args]
  (let [res (rpc! (:transport tool) "tools/call"
                  {:name (:remote-name tool) :arguments args})]
    (if (:error res)
      {:output (str "mcp remote call failed: " (:error res)) :isError true :branch "failure"}
      (mcp-result (get-in res [:ok :result])))))

;; ---------------------------------------------------------------------------
;; the toolkit — §0.3's "one bad server never breaks the toolkit", measured
;; ---------------------------------------------------------------------------

(defn toolkit [connections]
  {:tools    (reduce (fn [acc t] (assoc acc (:name t) t))
                     {} (mapcat :tools connections))
   :statuses (->> connections
                  (map (fn [c] [(:name c) (:status c)]))
                  (sort-by first)
                  (map (fn [pair] {:server (first pair) :status (second pair)}))
                  vec)})

(defn tool-names [tk] (vec (sort (keys (:tools tk)))))

(defn execute-tool [tk tool-name args]
  (if-let [t (get (:tools tk) tool-name)]
    (call-tool! t args)
    {:output (str "unknown tool: " tool-name) :isError true :branch "unknown"}))

;; ---------------------------------------------------------------------------
;; the fake remote MCP server (koine.server on 127.0.0.1:0)
;; ---------------------------------------------------------------------------
;;
;; Four tools, advertised on the wire in SCRAMBLED order, so the client's sort
;; is a measurement. One tool per §0.4 branch, plus an echo.

(def ^:private advertised
  [{:name "zebra note"  :description "two text parts"
    :inputSchema {:type "object"}}
   {:name "alpha/stats" :description "structuredContent wins over text"
    :inputSchema {:type "object"}}
   {:name "mid.boom"    :description "isError"
    :inputSchema {:type "object"}}
   {:name "echo"        :description "echoes a message"
    :inputSchema {:type "object" :properties {:message {:type "string"}}
                  :required ["message"]}}])

(defn- tool-call-result [tool-name args]
  (cond
    (= tool-name "zebra note")
    {:content [{:type "text" :text "line one"} {:type "text" :text "line two"}]}

    (= tool-name "alpha/stats")
    {:content           [{:type "text" :text "ignored when structured is present"}]
     :structuredContent {:zulu 1 :alpha 2 :nested {:b false :a true}}}

    (= tool-name "mid.boom")
    {:content [{:type "text" :text "boom: the tool failed"}] :isError true}

    (= tool-name "echo")
    {:content [{:type "text" :text (str "Echo: " (:message args))}]}

    :else
    {:content [{:type "text" :text (str "unknown tool: " tool-name)}] :isError true}))

(defn- rpc-response [body]
  (let [msg    (try (json/read-str body) (catch Throwable _ nil))
        id     (:id msg)
        method (:method msg)
        params (:params msg)]
    (cond
      (= method "initialize")
      {:jsonrpc "2.0" :id id
       :result {:protocolVersion "2024-11-05"
                :capabilities    {:tools {}}
                :serverInfo      {:name "toolnexus-fake-remote" :version "1.0.0"}}}

      (= method "tools/list")
      {:jsonrpc "2.0" :id id :result {:tools advertised}}

      (= method "tools/call")
      {:jsonrpc "2.0" :id id :result (tool-call-result (:name params) (:arguments params))}

      :else
      {:jsonrpc "2.0" :id id :error {:code -32601 :message "Method not found"}})))

(defn- observe-headers!
  "What the server SAW, reported as shape only. Only the x-tn-* keys and the
  presence of `authorization` are reported: the rest of the header set (host,
  user-agent, accept-encoding, content-length) is host-client specific and
  would make the byte-diff meaningless.

  The two ${ENV} assertions are computed HERE, in-process, and only their
  boolean answers leave: whether the value still looks like an unexpanded
  template, and whether it equals what the environment says it should be. The
  value itself is never stored, never returned, never printed."
  [seen req]
  (let [hs       (:headers req)
        auth     (get hs "authorization")
        expected (str "Bearer " (or (env/get-env "TN_FAKE_TOKEN") ""))]
    (reset! seen
            {:tn-keys        (vec (sort (filter (fn [k] (str/starts-with? (str k) "x-tn-"))
                                                (map (fn [e] (str (key e))) hs))))
             :authorization  (boolean auth)
             :still-template (boolean (and auth (str/includes? auth "${")))
             :matches-env    (boolean (and auth (= auth expected)))})))

(defn start-remote!
  "One koine.server, three paths:
     /mcp        a working MCP JSON-RPC endpoint
     /badstatus  a real HTTP answer that is not 2xx
     /garbage    a 200 whose body is not JSON
  The last two exist so the two degradations §2 must survive are measured on a
  real socket rather than asserted."
  [seen]
  (server/serve
    (fn [req]
      (let [path (:path req)]
        (cond
          (= path "/mcp")
          (do (observe-headers! seen req)
              {:status 200 :headers {"content-type" "application/json"}
               :body (json/write-str (rpc-response (:body req)))})

          ;; the same MCP endpoint answering in the OTHER streamable-HTTP mode:
          ;; text/event-stream, the JSON-RPC response carried in `data:` frames
          ;; (with a comment/keep-alive frame first, which the client must skip).
          (= path "/mcp-sse")
          (do (observe-headers! seen req)
              {:status 200 :headers {"content-type" "text/event-stream"}
               :body (str ": keep-alive\n\n"
                          "event: message\n"
                          "data: " (json/write-str (rpc-response (:body req))) "\n\n")})

          (= path "/badstatus")
          {:status 503 :headers {"content-type" "text/plain"}
           :body "service unavailable"}

          (= path "/garbage")
          {:status 200 :headers {"content-type" "application/json"}
           :body "<html>not json at all</html>"}

          :else {:status 404 :headers {"content-type" "text/plain"} :body "Not Found"})))
    {:port 0}))

(defn closed-port-url
  "A URL nothing is listening on — bound, read back, then closed. Asking the OS
  for a free port and immediately releasing it is the only portable way to be
  sure the port is dead; a hardcoded number is a coin flip."
  []
  (let [h (server/serve (fn [_req] {:status 200 :body "ok"}) {:port 0})
        p (server/port h)]
    (server/stop! h)
    (str "http://127.0.0.1:" p "/mcp")))

;; ---------------------------------------------------------------------------
;; the report — one JSON line, sorted keys, nothing non-deterministic
;; ---------------------------------------------------------------------------

(defn- config-text
  "The remote server config, with a ${ENV} header, as it would appear in an
  mcp.json. `${TN_FAKE_TOKEN}` is exported by run-both.sh with an obvious
  non-secret (`not-a-real-secret`); the point measured is that expansion
  CHANGED the value and that the server received the expanded form, never what
  either form was."
  [url]
  (json/write-str
    {:mcpServers
     {"remote api" {:type    "remote"
                    :url     url
                    :headers {"Authorization" "Bearer ${TN_FAKE_TOKEN}"
                              "X-TN-Static"   "plain"
                              "X-TN-Missing"  "${TN_DEFINITELY_UNSET_VAR}"}
                    :timeout 5000}
      "dead remote" {:type "remote" :url "http://127.0.0.1:1/mcp"}
      "off remote"  {:type "remote" :url "http://127.0.0.1:2/mcp" :enabled false}}}))

(defn run-spike [examples-dir]
  (let [seen   (atom {})
        handle (start-remote! seen)
        base   (str "http://127.0.0.1:" (server/port handle))]
    (try
      (let [;; the shared fixture, unchanged — its remote entry must parse as a
            ;; disabled remote (SPEC §0.3), which is why S15 could not exercise
            ;; this transport at all.
            fixture  (->> (parse-mcp-config (fs/read-file (str examples-dir "/mcp.json")))
                          (filter (fn [s] (= "remote" (:kind s))))
                          (map (fn [s] (select-keys s [:name :kind :enabled :timeout])))
                          vec)

            ;; the spike's own config: one live remote, one dead remote, one
            ;; disabled remote.
            servers  (parse-mcp-config (config-text (str base "/mcp")))
            live-cfg (assoc (first (filter (fn [s] (= "remote api" (:name s))) servers))
                            :url (str base "/mcp"))
            dead-cfg (assoc (first (filter (fn [s] (= "dead remote" (:name s))) servers))
                            :url (closed-port-url))

            live     (connect-remote! live-cfg)
            dead     (connect-remote! dead-cfg)
            tk       (toolkit [live dead])

            ;; §0.4 — one call per branch.
            text-r   (execute-tool tk "remote_api_zebra_note" {})
            struct-r (execute-tool tk "remote_api_alpha_stats" {})
            err-r    (execute-tool tk "remote_api_mid_boom" {})
            echo-r   (execute-tool tk "remote_api_echo" {:message "toolnexus"})

            ;; the two degradations, on the same live socket.
            ;; streamable-HTTP's SSE response mode, over the same config.
            sse-list (sse-rpc! (str base "/mcp-sse") (:headers live-cfg) "tools/list" {})
            sse-call (sse-rpc! (str base "/mcp-sse") (:headers live-cfg) "tools/call"
                               {:name "echo" :arguments {:message "over-sse"}})

            bad-st   (connect-remote! (assoc live-cfg :name "bad status"
                                             :url (str base "/badstatus")))
            garbage  (connect-remote! (assoc live-cfg :name "garbage"
                                             :url (str base "/garbage")))]
        {:host    (name host/id)
         :support {:serve   (host/supports? :server/serve)
                   :http    (host/supports? :http/request)}
         :fixture fixture
         :config  (mapv (fn [s]
                          {:name    (:name s)
                           :kind    (:kind s)
                           :enabled (:enabled s)
                           :timeout (:timeout s)
                           :headers (header-shape (:raw-headers s) (:headers s))})
                        servers)
         :headers-server-saw @seen
         :remote  {:status      (:status live)
                   :server-info (:server-info live)
                   :tool-count  (count (:tools live))
                   :tool-names  (mapv :name (:tools live))
                   :wire-order  (mapv :name advertised)}
         :sse     {:host-supports (host/supports? :stream/sse)
                   :tools-list    (if (:ok sse-list)
                                    {:frames (:frames sse-list)
                                     :tool-count (count (get-in sse-list [:ok :result :tools]))}
                                    sse-list)
                   :tools-call    (if (:ok sse-call)
                                    (assoc (mcp-result (get-in sse-call [:ok :result]))
                                           :frames (:frames sse-call))
                                    sse-call)}
         :results {:text       (dissoc text-r :isError)
                   :text-error (:isError text-r)
                   :structured struct-r
                   :error      err-r
                   :echo       echo-r}
         :isolation {:dead     {:status (:status dead) :phase (:phase dead)
                                :failure (:failure dead)}
                     :statuses (:statuses tk)
                     :toolkit-tools (tool-names tk)
                     :toolkit-still-works (not (:isError echo-r))
                     :unknown-tool (execute-tool tk "remote_api_nope" {})}
         :degradation {:non-2xx   {:status (:status bad-st) :phase (:phase bad-st)
                                   :failure (:failure bad-st)}
                       :malformed {:status (:status garbage) :phase (:phase garbage)
                                   :failure (:failure garbage)}}
         :naming  {"remote api / zebra note"  (mcp-tool-name "remote api" "zebra note")
                   "remote api / alpha/stats" (mcp-tool-name "remote api" "alpha/stats")
                   "remote api / mid.boom"    (mcp-tool-name "remote api" "mid.boom")}})
      (finally (server/stop! handle)))))

(defn -main [& _]
  (let [dir (env/get-env "TN_EXAMPLES")]
    (when-not dir
      (throw (ex-info "set TN_EXAMPLES to the toolnexus examples/ directory" {})))
    (println (json/write-str (run-spike dir)))))
