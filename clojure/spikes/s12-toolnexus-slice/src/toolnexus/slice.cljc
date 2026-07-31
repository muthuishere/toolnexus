;; S12 — a vertical slice of toolnexus SPEC §0, in ONE .cljc, on TWO hosts.
;;
;; The question this spike answers is not "does Clojure work" but: can the
;; toolnexus conformance contract be expressed in portable Clojure over koine
;; alone, with NO reader conditional in toolnexus' own source, and produce
;; byte-identical output on Clojure (JVM) and cljgo?
;;
;; It covers the five parts of §0 that carry the real risk:
;;
;;   §0.2  sanitize + MCP tool naming          — pure
;;   §0.3  mcp.json parsing + ${ENV} expansion — koine.json, koine.env
;;   §0.4  a REAL MCP stdio session            — koine.process/spawn  <- the risk
;;   §0.5  skill discovery + §0.6 byte-exact `skill` output — koine.fs
;;   §0.7  OpenAI / Anthropic / Gemini adapters — pure
;;
;; Everything else in §0 (the client loop, builtins, suspension) is composition
;; over these; if these hold, the port is a writing exercise.
;;
;; Run:  TN_EXAMPLES=/abs/path/to/examples  and see run-both.sh.

(ns toolnexus.slice
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.fs :as fs]
            [koine.env :as env]
            [koine.host :as host]
            [koine.process :as proc]))

;; ---------------------------------------------------------------------------
;; §0.2  sanitize / tool naming
;; ---------------------------------------------------------------------------

(defn sanitize
  "SPEC §0.2 — replace [^a-zA-Z0-9_-] with _."
  [s]
  (str/replace (str s) #"[^a-zA-Z0-9_-]" "_"))

(defn mcp-tool-name
  "SPEC §0.2 — sanitize(server)_sanitize(tool)."
  [server tool]
  (str (sanitize server) "_" (sanitize tool)))

;; ---------------------------------------------------------------------------
;; §0.3  MCP config
;; ---------------------------------------------------------------------------

(def default-timeout-ms 30000)

(defn- expand-headers
  "SPEC §0.3 — remote `headers` values expand ${ENV_VAR}. Never logged, so the
  spike reports only whether expansion CHANGED the value, never the value."
  [headers]
  (reduce (fn [acc [k v]] (assoc acc k (env/expand (str v)))) {} headers))

(defn- one-server [server-name m]
  {:name    server-name
   :kind    (cond (:url m)     "remote"
                  (:command m) "local"
                  :else        "unknown")
   :enabled (not (or (true? (:disabled m)) (false? (:enabled m))))
   :command (vec (:command m))
   :url     (:url m)
   :headers (expand-headers (:headers m))
   :timeout (or (:timeout m) default-timeout-ms)})

(defn parse-mcp-config
  "SPEC §0.3 — accept top-level mcpServers | servers | mcp. Sorted by name so
  two hosts cannot disagree on order."
  [text]
  (let [cfg     (json/read-str text {:key-fn keyword})
        servers (or (:mcpServers cfg) (:servers cfg) (:mcp cfg) {})]
    (->> servers
         (map (fn [entry] (one-server (name (key entry)) (val entry))))
         (sort-by :name)
         vec)))

;; ---------------------------------------------------------------------------
;; §0.4  MCP stdio — a real JSON-RPC session over koine.process/spawn
;; ---------------------------------------------------------------------------
;;
;; This is the part that cannot be faked. A line-delimited JSON-RPC peer needs a
;; LIVE child: write a request, keep reading until the matching id arrives, and
;; skip the notifications the server interleaves. `sh` cannot express it.

(defn- rpc!
  "Send one JSON-RPC request and read until the response with this id.
  Skips blank lines and any interleaved notification."
  [child id method params]
  (proc/send-line! child (json/write-str (cond-> {:jsonrpc "2.0" :id id :method method}
                                           params (assoc :params params))))
  (loop [seen 0]
    (when (> seen 500)
      (throw (ex-info "toolnexus/rpc!: no response" {:id id :method method})))
    (let [line (proc/read-line! child)]
      (cond
        ;; SPEC §0.3 — a peer that dies is isolated, never fatal to the toolkit.
        (nil? line)          (throw (ex-info "toolnexus/rpc!: peer exited"
                                             {:id id :method method}))
        (str/blank? line)    (recur (inc seen))
        :else (let [msg (json/read-str line {:key-fn keyword})]
                (if (= id (:id msg)) msg (recur (inc seen))))))))

(defn- notify! [child method params]
  (proc/send-line! child (json/write-str {:jsonrpc "2.0" :method method :params params})))

(defn connect-stdio!
  "initialize -> notifications/initialized. Returns {:child :server-info}."
  [server]
  (let [child (proc/spawn (:command server))
        init  (rpc! child 1 "initialize"
                    {:protocolVersion "2024-11-05"
                     :capabilities    {}
                     :clientInfo      {:name "toolnexus-clj-spike" :version "0.0.1"}})]
    (notify! child "notifications/initialized" {})
    {:child child :server-info (get-in init [:result :serverInfo])}))

(defn mcp-result
  "SPEC §0.4 — isError => error with joined text; structuredContent =>
  JSON string; else joined text parts."
  [result]
  (let [text (str/join "\n" (keep :text (:content result)))]
    (cond
      (:isError result)          {:output text :isError true}
      (:structuredContent result) {:output (json/write-str (:structuredContent result))
                                   :isError false}
      :else                      {:output text :isError false})))

(defn list-mcp-tools
  "tools/list -> uniform Tools (SPEC §0.1) with §0.2 names."
  [child server-name]
  (->> (get-in (rpc! child 2 "tools/list" {}) [:result :tools])
       (map (fn [t]
              {:name        (mcp-tool-name server-name (:name t))
               :description (or (:description t) "")
               :inputSchema (or (:inputSchema t) {:type "object"})
               :source      "mcp"}))
       (sort-by :name)
       vec))

(defn call-mcp-tool [child tool-name args]
  (mcp-result (:result (rpc! child 3 "tools/call" {:name tool-name :arguments args}))))

;; ---------------------------------------------------------------------------
;; §0.5 / §0.6  skills
;; ---------------------------------------------------------------------------

(defn- parse-frontmatter
  "YAML frontmatter, the narrow subset SPEC §0.5 needs: leading `---` line,
  `key: value` pairs, closing `---`. Returns [meta body]."
  [text]
  (let [lines (str/split-lines text)]
    (if-not (= "---" (str/trim (first lines)))
      [{} text]
      (loop [remaining (rest lines) acc {}]
        (cond
          (empty? remaining)                      [acc ""]
          (= "---" (str/trim (first remaining)))  [acc (str/join "\n" (rest remaining))]
          :else
          (let [line (first remaining)
                idx  (str/index-of line ":")]
            (recur (rest remaining)
                   (if idx
                     (assoc acc
                            (keyword (str/trim (subs line 0 idx)))
                            (str/trim (subs line (inc idx))))
                     acc))))))))

(defn- parent-dir [path]
  (let [idx (str/last-index-of path "/")]
    (if idx (subs path 0 idx) ".")))

(defn discover-skills
  "SPEC §0.5 — glob **/SKILL.md, `name` required, first name wins.
  koine.fs/find-files sorts, so first-wins is deterministic across hosts."
  [root]
  (->> (fs/find-files root "SKILL.md")
       (reduce (fn [acc path]
                 (let [[meta body] (parse-frontmatter (fs/read-file path))
                       nm          (:name meta)]
                   (if (or (str/blank? (str nm)) (contains? acc nm))
                     acc
                     (assoc acc nm {:name        nm
                                    :description (or (:description meta) "")
                                    :body        (str/trim body)
                                    :dir         (parent-dir path)
                                    :files       (vec (sort (remove #(fs/directory? %)
                                                                    (fs/list-tree (parent-dir path)))))}))))
               {})))

(defn skill-output
  "SPEC §0.6 — byte-exact. Any drift here is a conformance failure, so it is
  built by explicit concatenation rather than a template."
  [skill]
  (str "<skill_content name=\"" (:name skill) "\">\n"
       "# Skill: " (:name skill) "\n\n"
       (:body skill) "\n\n"
       "Base directory for this skill: file://" (:dir skill) "\n"
       "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.\n"
       "Note: file list is sampled.\n\n"
       "<skill_files>\n"
       (str/join "" (map #(str "<file>" % "</file>\n") (:files skill)))
       "</skill_files>\n"
       "</skill_content>"))

(defn skills-prompt
  "SPEC §0.6 — `## Available Skills` list, sorted, described only."
  [skills]
  (let [described (->> (vals skills)
                       (remove #(str/blank? (:description %)))
                       (sort-by :name))]
    (if (empty? described)
      ""
      (str "## Available Skills\n"
           (str/join "\n" (map #(str "- **" (:name %) "**: " (:description %)) described))))))

;; ---------------------------------------------------------------------------
;; §0.7  adapters (schema only)
;; ---------------------------------------------------------------------------

(defn openai-schema [tools]
  (mapv (fn [t] {:type "function"
                 :function {:name        (:name t)
                            :description (:description t)
                            :parameters  (:inputSchema t)}})
        tools))

(defn anthropic-schema [tools]
  (mapv (fn [t] {:name         (:name t)
                 :description  (:description t)
                 :input_schema (:inputSchema t)})
        tools))

(defn gemini-schema [tools]
  [{:functionDeclarations
    (mapv (fn [t] {:name        (:name t)
                   :description (:description t)
                   :parameters  (:inputSchema t)})
          tools)}])

;; ---------------------------------------------------------------------------
;; the report — one JSON document, sorted keys, identical on both hosts
;; ---------------------------------------------------------------------------

(defn- header-report
  "Never print a header VALUE (§0.3: secrets are use-only). Report only the
  shape: which keys exist and whether ${ENV} expansion changed anything."
  [raw expanded]
  {:keys     (vec (sort (map name (keys expanded))))
   :expanded (boolean (some (fn [[k v]] (not= v (str (get raw k)))) expanded))})

(defn run-slice [examples-dir]
  (let [cfg-text (fs/read-file (str examples-dir "/mcp.json"))
        servers  (parse-mcp-config cfg-text)
        local    (first (filter #(and (:enabled %) (= "local" (:kind %))) servers))
        skills   (discover-skills (str examples-dir "/skills"))
        hello    (get skills "hello-world")

        ;; §0.4 — the live MCP session. Guarded by host/supports? so a host
        ;; without a streaming child degrades instead of throwing (§0.3: a
        ;; failed server is isolated, never fatal).
        mcp      (if-not (host/supports? :process/spawn)
                   {:skipped "no :process/spawn on this host"}
                   (let [{:keys [child server-info]} (connect-stdio! local)]
                     (try
                       (let [tools (list-mcp-tools child (:name local))
                             echo  (call-mcp-tool child "echo" {:message "toolnexus"})]
                         {:server-name  (:name server-info)
                          :tool-count   (count tools)
                          :first-tools  (vec (take 5 (map :name tools)))
                          :echo         echo
                          :openai       (first (openai-schema tools))
                          :anthropic    (first (anthropic-schema tools))
                          :gemini-count (count (:functionDeclarations (first (gemini-schema tools))))})
                       (finally (proc/close! child)))))]
    {:host      (name host/id)
     :servers   (mapv (fn [s]
                        (-> s
                            (dissoc :headers)
                            (assoc :headers (header-report (:headers s) (:headers s)))))
                      servers)
     :sanitize  {"foo/bar"   (sanitize "foo/bar")
                 "a b.c"     (sanitize "a b.c")
                 "ok_name-1" (sanitize "ok_name-1")}
     :tool-name (mcp-tool-name "my server" "read/file")
     :skills    {:names         (vec (sort (keys skills)))
                 :prompt        (skills-prompt skills)
                 :output-bytes  (count (skill-output hello))
                 :output-sha    (skill-output hello)}
     :mcp       mcp}))

(defn -main [& _]
  (let [dir (env/get-env "TN_EXAMPLES")]
    (when-not dir
      (throw (ex-info "set TN_EXAMPLES to the toolnexus examples/ directory" {})))
    (println (json/write-str (run-slice dir)))))
