;; CLI client, ACP transport (SPEC §8C, ADR 0037 "ACP primary"). Ports
;; golang/cli_acp.go to this dual-host `.cljc` — no reader conditionals, koine
;; only.
;;
;; On the first model call the user's toolkit is served as a streamable-HTTP MCP
;; endpoint on 127.0.0.1:<ephemeral>/mcp (koine.server + toolnexus.serve's §7C
;; JSON-RPC), guarded by a per-session bearer token, and handed to the agent in
;; session/new mcpServers (http when the agent advertises
;; mcpCapabilities.http, else a stdio bridge; a project MCP file in a temp cwd
;; for agents that ignore session servers). The agent calls the tools natively;
;; toolnexus hooks run at the server boundary. Every permission request is
;; decided per toolCallId: tools served by our server are allowed, everything
;; else (the agent's own shell, edit, fetch ...) is rejected.
(ns toolnexus.cli-acp
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.http :as http]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.server :as server]
            [toolnexus.acp :as acp]
            [toolnexus.serve :as serve]
            [toolnexus.skill :as skill]
            [toolnexus.tool :as tool]))

(def served-skill-tool-name
  "The name the toolkit's `skill` tool is served under to an ACP agent. Several
  agents (copilot, devin) ship their own tool called `skill`, and a model asked
  to use \"skill\" picks the agent's."
  "load_skill")

(def server-name
  "The MCP server name the agent sees."
  "toolnexus")

(def mcp-token-env
  "Carries the bearer token to a stdio bridge."
  "TOOLNEXUS_MCP_TOKEN")

;; ---------------------------------------------------------------------------
;; small shared helpers (also used by toolnexus.cli)
;; ---------------------------------------------------------------------------

(defn which
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

(defn expand-env
  "Style env + caller env, each value expanding ${VAR} from this process's
  environment at call time. Values are never logged."
  [env-map]
  (reduce (fn [acc [k v]] (assoc acc (if (keyword? k) (name k) (str k)) (env/expand (str v))))
          {} (or env-map {})))

(defn- new-token
  "A per-session bearer token: 64 hex chars from two random UUIDs (122 random
  bits each). koine has no CSPRNG byte API; `random-uuid` is the secure source
  both hosts already use (JVM UUID.randomUUID is SecureRandom-backed)."
  []
  (str (str/replace (str (random-uuid)) "-" "")
       (str/replace (str (random-uuid)) "-" "")))

(defn- constant-time=
  [a b]
  (let [a (str a) b (str b)]
    (and (= (count a) (count b))
         (zero? (reduce (fn [acc i] (bit-or acc (bit-xor (int (nth a i)) (int (nth b i)))))
                        0 (range (count a)))))))

;; ---------------------------------------------------------------------------
;; served tools
;; ---------------------------------------------------------------------------

(defn served-tools
  "The toolkit as served to the agent: the `skill` tool is renamed to
  `load_skill` with the skills list in its description, and every execute runs
  through `:before-tool` / `:after-tool` (hooks see the ORIGINAL name). `ran` is
  called with the served name each time a tool really executes."
  [tk hooks ran]
  (if (nil? tk)
    []
    (vec
     (for [t (vals (:tools tk))]
       (let [orig   (:name t)
             skill? (and (= "skill" orig) (= "skill" (str (:source t))))
             sp     (when skill? (skill/skills-prompt (:skills tk)))
             t'     (if skill?
                      (cond-> (assoc t :name served-skill-tool-name)
                        (not (str/blank? (str sp)))
                        (assoc :description (str (:description t) "\n\n" sp)))
                      t)
             served (:name t')
             before (:before-tool hooks)
             after  (:after-tool hooks)]
         (assoc t' :context? false
                :execute
                (fn [args & _]
                  (let [ov (when before
                             (try (before (cond-> {:name orig :args args}
                                            (:hints t) (assoc :hints (:hints t))))
                                  (catch Throwable e {:result (tool/failure (or (ex-message e) (str e)))})))]
                    (if (:result ov)
                      (:result ov)
                      (let [args (or (:args ov) args)
                            _    (when ran (ran served))
                            res  (tool/execute {:tools {orig t}} orig args)]
                        (if after
                          (or (try (:result (after {:name orig :args args :result res}))
                                   (catch Throwable e (tool/failure (or (ex-message e) (str e)))))
                              res)
                          res)))))))))))

(defn served-call?
  "Match a permission title against the exact spellings agents use for a tool
  of OUR server: mcp__toolnexus__<tool> (claude), toolnexus/<tool>,
  \"<tool> (toolnexus MCP Server)\" (gemini), \"Calling <tool> from toolnexus\"
  (devin), \"<tool>(...)\" (gemini skill), or the bare tool name (copilot). No
  substring matching."
  [title served]
  (let [title (str (or title ""))]
    (and (not= "" title)
         (boolean
          (some (fn [n]
                  (or (contains? #{n
                                   (str "mcp__" server-name "__" n)
                                   (str server-name "/" n)
                                   (str n " (" server-name " MCP Server)")
                                   (str "Calling " n " from " server-name)}
                                 title)
                      (and (str/starts-with? title (str n "("))
                           (str/ends-with? title ")"))))
                served)))))

(defn with-system-hint
  "Append a style's ACP system hint to the first system message (a copy; the
  caller's messages are not mutated), or prepend a system message holding only
  the hint when the conversation has none."
  [msgs hint]
  (if (str/blank? (str (or hint "")))
    msgs
    (let [m0 (first msgs)
          role (when (map? m0) (str (or (:role m0) (get m0 "role"))))
          c    (when (map? m0) (if (contains? m0 :content) (:content m0) (get m0 "content")))]
      (if (and (= "system" role) (string? c))
        (into [(assoc m0 (if (contains? m0 :content) :content "content")
                      (if (= "" c) hint (str c "\n\n" hint)))]
              (rest msgs))
        (into [{:role "system" :content hint}] msgs)))))

;; ---------------------------------------------------------------------------
;; the served endpoint
;; ---------------------------------------------------------------------------

(defn- mcp-handler
  [tools token]
  (let [tk   {:tools (into {} (map (fn [t] [(:name t) t]) tools))}
        cfg  {:name server-name}
        want (str "Bearer " token)]
    (fn [req]
      (cond
        (not= "/mcp" (:path req))
        {:status 404 :headers {"content-type" "text/plain"} :body "Not Found"}

        (not (constant-time= (get (:headers req) "authorization") want))
        {:status 401 :headers {"content-type" "text/plain" "www-authenticate" "Bearer"}
         :body "unauthorized"}

        ;; A stateless server has no GET stream to offer, and an agent that opens
        ;; one waits on it forever (opencode, found by the python port).
        (not= :post (:method req))
        {:status 405 :headers {"content-type" "text/plain" "allow" "POST"} :body "Method Not Allowed"}

        :else
        (let [msg (try (json/read-str (:body req)) (catch Throwable _ nil))]
          (if (and (map? msg) (some? (:method msg)) (nil? (:id msg)))
            {:status 202 :body ""}           ; a notification: accepted, no body
            {:status  200
             :headers {"content-type" "application/json"}
             :body    (json/write-str
                       (try (serve/mcp-rpc tk cfg nil (:body req))
                            (catch Throwable e
                              {:jsonrpc "2.0" :id (when (map? msg) (:id msg))
                               :error {:code -32603 :message (or (ex-message e) (str e))}})))}))))))

;; ---------------------------------------------------------------------------
;; session
;; ---------------------------------------------------------------------------

(defn- launch-error
  "A failure to start the agent or complete initialize — the only failures on
  which Auto falls back to the one-shot CLI."
  [msg]
  (ex-info msg {::launch true}))

(defn launch-error? [e] (true? (::launch (ex-data e))))

(defn- redact [servers]
  (mapv (fn [e]
          (reduce (fn [e k]
                    (if (sequential? (get e k))
                      (assoc e k (mapv (fn [nv] (assoc nv :value "[redacted]")) (get e k)))
                      e))
                  e [:headers :env]))
        servers))

(defn- allow-option [options]
  (or (some (fn [o] (when (= "allow_once" (str (:kind o))) (:optionId o))) options)
      (some (fn [o] (when (str/starts-with? (str (:kind o)) "allow") (:optionId o))) options)))

(defn close!
  "End the agent, stop the served endpoint, remove the temp session dir."
  [s]
  (when-let [c @(:client s)] (try (acp/close c) (catch Throwable _ nil)))
  (when-let [h (:server s)] (try (server/stop! h) (catch Throwable _ nil)))
  (when-let [d (:temp-dir s)] (try (fs/delete-tree! d) (catch Throwable _ nil)))
  nil)

(defn open-session
  "Open the ACP session for a CLI client (see toolnexus.cli). Throws; a launch
  failure is marked so `launch-error?` is true.

  cfg: {:argv :style :toolkit :hooks :model :cwd :env :timeout
        :acp-command? :bridge-command :on-acp-update}"
  [{:keys [argv style toolkit hooks model cwd env timeout acp-command? bridge-command on-acp-update]}]
  (let [bin (which (first argv))]
    (when-not bin
      (throw (launch-error (str "cli: command not found: " (first argv)))))
    (let [token     (new-token)
          state     (atom {:calls {} :decisions {} :log [] :ran []})
          tools     (served-tools toolkit hooks (fn [n] (swap! state update :ran conj n)))
          served    (set (map :name tools))
          h         (server/serve (mcp-handler tools token) {:port 0 :host "127.0.0.1"})
          url       (str "http://127.0.0.1:" (server/port h) "/mcp")
          launch    (:acp style)
          project   (when (and launch (not (str/blank? (str (:projectMcpFile launch)))) (not acp-command?))
                      (:projectMcpFile launch))
          temp-dir  (when project (fs/real-path (fs/temp-dir! "toolnexus-acp")))
          s         {:server h :url url :temp-dir temp-dir :state state :served served
                     :hint (:systemHint launch) :timeout timeout :client (atom nil)}
          cwd       (cond temp-dir temp-dir
                          (str/blank? (str (or cwd ""))) (fs/real-path ".")
                          :else (fs/real-path cwd))
          servers   (if project
                      (let [f (str temp-dir "/" project)
                            parent (subs f 0 (str/last-index-of f "/"))]
                        (fs/mkdirs! parent)
                        (fs/write-file f (json/write-str
                                          {:mcpServers {server-name {:type "http" :url url
                                                                     :headers {"Authorization" (str "Bearer " token)}}}}))
                        (proc/sh ["chmod" "600" f] {:timeout-ms 5000})
                        [])
                      (let [bridge (or (seq bridge-command)
                                       (when-let [p (which "toolnexus")] [p "mcp-bridge"]))]
                        [(cond-> {:name server-name :url url
                                  :headers {"Authorization" (str "Bearer " token)}}
                           bridge (assoc :command (first bridge)
                                         :args (conj (vec (rest bridge)) url)
                                         :env {mcp-token-env token}))]))
          permission (fn [params]
                       (let [id    (str (or (get-in params [:toolCall :toolCallId]) ""))
                             title (str (or (get-in params [:toolCall :title]) ""))
                             r     (swap! state
                                          (fn [st]
                                            (let [title   (if (= "" title) (get-in st [:calls id] "") title)
                                                  decided (contains? (:decisions st) id)
                                                  allowed (if decided
                                                            (get-in st [:decisions id])
                                                            (and (not= "" id) (served-call? title served)))]
                                              (cond-> (update st :log conj {:tool-call-id id :title title :allowed allowed})
                                                (and (not decided) (not= "" id)) (assoc-in [:decisions id] allowed)))))]
                         (when (:allowed (peek (:log r)))
                           (allow-option (:options params)))))
          update!    (fn [params]
                       (let [u (:update params)
                             id (str (or (:toolCallId u) ""))
                             title (str (or (:title u) ""))]
                         (when (and (not= "" id) (not= "" title)
                                    (contains? #{"tool_call" "tool_call_update"} (str (:sessionUpdate u))))
                           (swap! state (fn [st] (if (contains? (:calls st) id) st (assoc-in st [:calls id] title))))))
                       (when on-acp-update (on-acp-update params)))]
      (try
        (let [c (acp/connect (into [bin] (rest argv))
                             {:cwd cwd
                              :environment (expand-env env)
                              :timeout timeout
                              :permission-timeout timeout
                              :on-permission permission
                              :on-update update!
                              :mcp-servers servers
                              :model model})]
          (reset! (:client s) c)
          s)
        (catch Throwable e
          (close! s)
          (let [d     (ex-data e)
                msg   (str (ex-message e)
                           (let [t (str/trim (str (:stderr d)))] (when-not (= "" t) (str ": " t))))
                msg   (str/replace msg "toolnexus: acp:" "cli: acp:")]
            (if (contains? #{"start" "initialize"} (:phase d))
              (throw (launch-error msg))
              (throw (ex-info msg {})))))))))

(defn generate
  "One model turn over the live session. With `emit`, the turn's text /
  thought / tool events reach it as each session/update arrives
  (`toolnexus.acp/generate-stream`) — incremental, not after the turn."
  ([s req] (generate s req nil))
  ([s req emit]
  (let [c   @(:client s)
        msgs (with-system-hint (vec (:messages req)) (:hint s))]
    (try
      ((acp/generate-stream c) (assoc req :messages msgs) emit)
      (catch Throwable e
        (let [err (:acp-error (ex-data e))]
          (cond
            (= "timeout" err)  (throw (ex-info (str "cli: timed out after " (:timeout s) "ms") {}))
            (= "peer-eof" err) (throw (ex-info "cli: acp: agent exited before replying to session/prompt" {}))
            :else (throw e))))))))

(defn session-info
  "A read-only view of the live session. The bearer token is never exposed:
  header and env values in :mcp-servers-sent are redacted."
  [s]
  (let [st @(:state s)]
    {:url              (:url s)
     :mcp-servers-sent (redact (:mcp-servers-sent @(:client s)))
     :permissions      (:log st)
     :served-calls     (:ran st)
     :session-cwd      (:temp-dir s)}))

;; ---------------------------------------------------------------------------
;; the stdio <-> HTTP bridge
;; ---------------------------------------------------------------------------

(defn run-mcp-bridge
  "The stdio side of a stdio McpServer entry: each JSON-RPC line read from
  `read-line-fn` (default: stdin) is POSTed to the toolkit's streamable-HTTP
  endpoint (with the bearer token when set), and each reply — plain JSON or SSE
  `data:` frames — is written with `write-line-fn` (default: println + flush)
  as one line. Throws on 401."
  ([url token] (run-mcp-bridge url token read-line (fn [l] (println l) (flush))))
  ([url token read-line-fn write-line-fn]
   (loop [session nil]
     (when-let [line (read-line-fn)]
       (let [line (str/trim line)]
         (if (= "" line)
           (recur session)
           (let [res (http/request {:method :post :url url :body line :timeout-ms 600000
                                    :headers (cond-> {"Content-Type" "application/json"
                                                      "Accept" "application/json, text/event-stream"}
                                               (not (str/blank? (str (or token ""))))
                                               (assoc "Authorization" (str "Bearer " token))
                                               session (assoc "Mcp-Session-Id" session))})]
             (when (http/failed? res)
               (throw (ex-info (str "mcp-bridge: " (:error-message res)) {})))
             (when (= 401 (:status res))
               (throw (ex-info (str "mcp-bridge: unauthorized (missing or wrong " mcp-token-env ")") {})))
             (let [session (or (http/header res "Mcp-Session-Id") session)
                   body    (str (:body res))]
               (if (str/starts-with? (str (http/header res "Content-Type")) "text/event-stream")
                 (doseq [l (str/split-lines body)]
                   (let [l (str/replace l "\r" "")]
                     (when (str/starts-with? l "data:")
                       (write-line-fn (str/trim (subs l 5))))))
                 (let [b (str/trim body)]
                   (when (str/starts-with? b "{")
                     (write-line-fn b))))
               (recur session)))))))))
