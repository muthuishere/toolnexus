(ns mcp2026.core
  "In-house MCP 2026-07-28 client + server (stateless Streamable HTTP, stdio client, MRTR,
  skills extension SEP-2640, lazy tool search). No SDK. Wire data is string-keyed maps.

  Client: (client url opts) | (stdio-client cmd opts) -> call, call-tool, tools, invoke,
          search-tools, download-skill, close.
  Server: (server) | (fixture-server) | (load-server path) -> (tool srv name desc handler),
          (assoc srv :auth {...}), (handler srv), (serve! srv opts), (serve-stdio srv in out)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [mcp2026.auth :as auth]
            [mcp2026.serverauth :as sa]
            [mcp2026.wire :as w])
  (:import (clojure.lang ExceptionInfo)
           (java.io BufferedReader File InputStreamReader OutputStreamWriter)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent TimeUnit)))

;; ------------------------------------------------------------------ shared wire constants

(def protocol-version "2026-07-28")
(def meta-version "io.modelcontextprotocol/protocolVersion")
(def meta-client-caps "io.modelcontextprotocol/clientCapabilities")
(def meta-client-info "io.modelcontextprotocol/clientInfo")
(def meta-server-info "io.modelcontextprotocol/serverInfo")
(def skills-ext "io.modelcontextprotocol/skills")

(def code-method-not-found -32601)
(def code-invalid-params -32602)
(def code-header-mismatch -32020)
(def code-unsupported-protocol -32022)

(defn rpc-error
  "A JSON-RPC error as an exception: (ex-data e) has :mcp/rpc-error {\"code\" \"message\" \"data\"}."
  [e]
  (ex-info (str (get e "message")) {:mcp/rpc-error e}))

(defn rpc-code "The JSON-RPC error code carried by e, or nil." [e]
  (some-> (ex-data e) :mcp/rpc-error (get "code")))

(defn text
  "The first text content of a tool result (\"\" if none)."
  [r]
  (let [c (get r "content")] (or (when (seq c) (get (first c) "text")) "")))

(def pending-of
  "The pending authorization {:id :kind :prompt :url :expires-at} in an exception, or nil."
  auth/pending-of)

;; ------------------------------------------------------------------ skill URIs (python-sdk #3685)

(defn parse-skill-uri
  "skill://<name>/<path> -> [name rel], percent-decoded ONCE; throws on any leftover '%' (double
  encoding), backslash, NUL, ':' or an empty/'.'/'..' segment."
  [uri]
  (when-not (str/starts-with? (str uri) "skill://") (throw (ex-info "not a skill:// URI" {})))
  (let [dec (try (w/percent-decode (subs uri 8))
                 (catch Exception e (throw (ex-info (str "bad percent-encoding: " (.getMessage e)) {}))))]
    (when (re-find #"[%\\\u0000]" dec)
      (throw (ex-info "skill URI has encoded, backslash or NUL characters" {})))
    (let [segs (str/split dec #"/" -1)]
      (when (< (count segs) 2) (throw (ex-info "skill URI needs skill://<name>/<path>" {})))
      (when (some #(or (= % "") (= % ".") (= % "..") (str/includes? % ":")) segs)
        (throw (ex-info "skill URI path escapes the skill (empty, '.', '..' or ':' segment)" {})))
      [(first segs) (str/join "/" (rest segs))])))

;; ------------------------------------------------------------------ client

(defn client
  "A stateless MCP 2026-07-28 Streamable HTTP client. opts: :http (request fn) :auth (auth/oauth)
  :tool-search (tools -> search_tools + call_tool) :description (what the server is for)
  :version :info :caps."
  ([url] (client url {}))
  ([url opts]
   (merge {:url url :version protocol-version :http w/request
           :info {"name" "toolnexus-spike-clojure" "version" "0.0.1"}
           :caps {"elicitation" {"form" {}}}
           :tool-search false :description ""}
          opts
          {:next-id (atom 0) :tool-cache (atom nil) :tool-lock (Object.)})))

(defn- encode-header [v]
  (if (and (= v (str/trim v))
           (not (and (str/starts-with? v "=?base64?") (str/ends-with? v "?=")))
           (every? #(<= 0x20 (int %) 0x7e) v))
    v
    (str "=?base64?" (w/b64 v) "?=")))

(defn- post [c method params body bearer]
  ((:http c) {:method "POST" :url (:url c) :body body
              :headers (merge {"Content-Type" "application/json"
                               "Accept" "application/json, text/event-stream"
                               "MCP-Protocol-Version" (:version c)
                               "Mcp-Method" method}
                              (case method
                                ("tools/call" "prompts/get") {"Mcp-Name" (encode-header (str (get params "name")))}
                                "resources/read" {"Mcp-Name" (encode-header (str (get params "uri")))}
                                {})
                              (:header-override c)
                              (when bearer {"Authorization" (str "Bearer " bearer)}))}))

(defn- parse-response [resp]
  (let [ct (str (get-in resp [:headers "content-type"]))
        msg (if (str/starts-with? ct "text/event-stream")
              ;; the last data: payload carrying a result or error is the final response
              (reduce (fn [m line]
                        (if-let [[_ d] (re-matches #"data:(.*)" line)]
                          (let [x (try (w/read-json d) (catch Exception _ nil))]
                            (if (and (map? x) (or (contains? x "result") (contains? x "error"))) x m))
                          m))
                      nil (str/split-lines (str (:body resp))))
              (try (w/read-json (:body resp))
                   (catch Exception e
                     (throw (ex-info (str "HTTP " (:status resp) ": " (.getMessage e)) {})))))]
    (when-not (and (map? msg) (or (map? (get msg "result")) (map? (get msg "error"))))
      (throw (ex-info (str "HTTP " (:status resp) ": response has neither result nor error") {})))
    msg))

(declare call)

(defn- http-round-trip
  ;; Retry rule (spec issue #3394): re-sent ONLY after a 401, or a 403 insufficient_scope, with
  ;; WWW-Authenticate — rejected before dispatch, so the tool never ran. A lost connection, timeout,
  ;; 5xx or unreadable response is thrown and NEVER retried: the server may have executed it.
  [c method params body]
  (let [o (:auth c)]
    (loop [attempt 0]
      (let [sent (when o (auth/bearer o (:url c)))
            resp (post c method params body sent)
            www (get-in resp [:headers "www-authenticate"])]
        (if (or (nil? o) (not (#{401 403} (:status resp))) (str/blank? www))
          (parse-response resp)
          (do (when (>= attempt 3) ; spec: Step-Up retry limit
                (throw (ex-info (str "authorization failed: HTTP " (:status resp) " after " (inc attempt) " attempts") {})))
              (auth/challenge! o (:url c) (:status resp) www sent (fn [] (call c method params)))
              (recur (inc attempt))))))))

(declare stdio-round-trip)

(defn call
  "Send one request (params get the per-request _meta) and return its result map; throws an
  rpc-error for a JSON-RPC error, a pending ex-info when authorization pauses."
  ([c method] (call c method nil))
  ([c method params]
   (let [params (assoc (or params {}) "_meta" {meta-version (:version c) meta-client-caps (:caps c)
                                               meta-client-info (:info c)})
         id (swap! (:next-id c) inc)
         body (w/write-json {"jsonrpc" "2.0" "id" id "method" method "params" params})
         msg (if (:stdio c)
               (stdio-round-trip (:stdio c) id body)
               (http-round-trip c method params body))]
     (when-let [e (get msg "error")] (throw (rpc-error e)))
     (let [r (get msg "result")]
       (if (contains? r "resultType") r (assoc r "resultType" "complete")))))) ; earlier-protocol servers

(defn- call-tool* [c name args respond responses state round]
  (loop [responses responses state state round round]
    (when (>= round 8) (throw (ex-info (str "tools/call " name ": too many input_required rounds") {})))
    (let [p (cond-> {"name" name "arguments" args}
              responses (assoc "inputResponses" responses)
              (some? state) (assoc "requestState" state))
          res (try (call c "tools/call" p)
                   (catch ExceptionInfo e
                     ;; the paused round got 401/403 before dispatch: the server has NOT run it. Resume
                     ;; sends exactly this round (same inputResponses/requestState) for the first time.
                     (when-let [pd (auth/pending-of e)]
                       (auth/set-retry! (:auth c) (:id pd)
                                        (fn [] (call-tool* c name args respond responses state round))))
                     (throw e)))]
      (if (not= "input_required" (get res "resultType"))
        res
        (do (when-not respond
              (throw (ex-info (str "tools/call " name ": server asked for input but no responder was given") {})))
            (recur (into {} (for [[k v] (get res "inputRequests")]
                              (if (map? v)
                                [k (respond k v)]
                                (throw (ex-info (str "tools/call " name ": malformed input request " (pr-str k)) {})))))
                   (get res "requestState")
                   (inc round)))))))

(defn call-tool
  "tools/call, looping over MRTR input_required rounds via respond: (fn [key input-request] answer)
  — the stand-in for toolnexus SPEC §10 waitFor."
  ([c name args] (call-tool c name args nil))
  ([c name args respond] (call-tool* c name args respond nil nil 0)))

;; ------------------------------------------------------------------ lazy tool search

(defn- list-tools [c]
  (locking (:tool-lock c)
    (let [{:keys [tools expiry]} @(:tool-cache c)]
      (if (and tools (< (w/now-ms) expiry))
        tools
        (let [r (call c "tools/list")
              tools (vec (filter map? (get r "tools")))]
          (reset! (:tool-cache c) {:tools tools :expiry (+ (w/now-ms) (long (or (get r "ttlMs") 0)))})
          tools)))))

(defn- tokens
  "Lowercase, split on non-alphanumerics and camelCase boundaries."
  [s]
  (loop [[ch & more :as cs] (seq (str s)) cur [] out [] prev-lower false]
    (let [flush (fn [out cur] (if (seq cur) (conj out (str/lower-case (apply str cur))) out))]
      (if-not cs
        (flush out cur)
        (let [lower (Character/isLowerCase (char ch))]
          (cond (and (Character/isUpperCase (char ch)) prev-lower) (recur more [ch] (flush out cur) lower)
                (Character/isLetterOrDigit (char ch)) (recur more (conj cur ch) out lower)
                :else (recur more [] (flush out cur) lower)))))))

(defn search-tools
  "Rank the server's tools against query (BM25 over name x2 + description); top `limit`."
  [c query limit]
  (let [tools (list-tools c)
        n (count tools)
        docs (mapv (fn [t] (let [nt (tokens (get t "name"))] (concat nt nt (tokens (get t "description"))))) tools)
        df (frequencies (mapcat distinct docs))
        avg (/ (double (reduce + (map count docs))) (max 1.0 (double n)))
        hits (keep-indexed
              (fn [i d]
                (let [tf (frequencies d)
                      s (reduce (fn [s q]
                                  (let [f (double (get tf q 0))]
                                    (if (zero? f)
                                      s
                                      (let [dq (double (get df q))
                                            idf (Math/log (+ 1 (/ (+ (- n dq) 0.5) (+ dq 0.5))))]
                                        (+ s (/ (* idf f 2.2) (+ f (* 1.2 (+ 0.25 (/ (* 0.75 (count d)) avg))))))))))
                                0.0 (tokens query))]
                  (when (pos? s) {:i i :score s})))
              docs)]
    (vec (for [{:keys [i]} (take limit (sort-by :score > hits)) :let [t (nth tools i)]]
           {"name" (get t "name") "description" (get t "description")
            "inputSchema" (get t "inputSchema") "server" (:description c)}))))

(defn tools
  "Tool definitions to give a model: every server tool, or (:tool-search) the two meta-tools."
  [c]
  (if-not (:tool-search c)
    (list-tools c)
    (let [about (if (str/blank? (:description c)) "this MCP server" (:description c))]
      [{"name" "search_tools"
        "description" (str "Search the tools of " about " by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.")
        "inputSchema" {"type" "object" "properties" {"query" {"type" "string"} "limit" {"type" "integer"}} "required" ["query"]}}
       {"name" "call_tool" "description" "Run a tool found with search_tools."
        "inputSchema" {"type" "object" "properties" {"name" {"type" "string"} "arguments" {"type" "object"}} "required" ["name"]}}])))

(defn invoke
  "Run a tool call the model emitted: a server tool, or search_tools / call_tool in tool-search mode."
  ([c name args] (invoke c name args nil))
  ([c name args respond]
   (if-not (:tool-search c)
     (call-tool c name args respond)
     (case name
       "search_tools" (let [l (get args "limit")
                            hits (search-tools c (str (get args "query" "")) (if (and (number? l) (pos? l)) (long l) 5))]
                        {"resultType" "complete" "content" [{"type" "text" "text" (w/write-json hits)}]
                         "structuredContent" {"tools" hits} "isError" false})
       "call_tool" (call-tool c (str (get args "name")) (get args "arguments") respond)
       (throw (ex-info (str "unknown tool " (pr-str name) " (tool-search mode exposes search_tools and call_tool)") {}))))))

;; ------------------------------------------------------------------ skills: client download

(defn download-skill
  "Fetch a skill (skills/get + resources/read per file) into dir/<name>/, checking each file's
  sha256 digest; every resource URI must stay inside the skill. Validates all before writing."
  [c uri dir]
  (let [[name _] (parse-skill-uri uri)
        sk (get (call c "skills/get" {"uri" uri}) "skill")
        files (vec (for [m (get sk "resources")]
                     (let [fu (str (get m "uri"))
                           [n rel] (try (parse-skill-uri fu) (catch Exception _ nil))
                           _ (when-not (and n (= n name) (not (.isAbsolute (File. ^String rel))))
                               (throw (ex-info (str "refusing skill resource " (pr-str fu) ": outside skill " name) {})))
                           cs (get (call c "resources/read" {"uri" fu}) "contents")
                           _ (when (empty? cs) (throw (ex-info (str fu ": empty resources/read") {})))
                           t (str (get (first cs) "text"))
                           d (get m "digest")]
                       (when (and (seq d) (not= d (str "sha256:" (w/hex (w/sha256 t)))))
                         (throw (ex-info (str fu ": digest mismatch") {})))
                       [(io/file dir name rel) t])))]
    (doseq [[f t] files]
      (io/make-parents f)
      (spit f t))
    nil))

;; ------------------------------------------------------------------ stdio client transport

(defn stdio-client
  "Start cmd (a vector) and return a client with the same API as `client`, speaking one JSON-RPC
  message per line. opts: :env {k v} :dir :stderr (:inherit default | :discard | (fn [line])).
  stderr is the server's log, never parsed. No headers, no auth. Call `close` to stop it."
  ([cmd] (stdio-client cmd {}))
  ([cmd {:keys [env dir stderr] :or {stderr :inherit}}]
   (let [pb (ProcessBuilder. ^java.util.List (mapv str cmd))
         _ (when dir (.directory pb (io/file dir)))
         _ (when env (let [e (.environment pb)] (doseq [[k v] env] (.put e (str k) (str v)))))
         _ (case stderr
             :inherit (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT)
             :discard (.redirectError pb java.lang.ProcessBuilder$Redirect/DISCARD)
             nil)
         proc (.start pb)
         conn {:proc proc
               :in (OutputStreamWriter. (.getOutputStream proc) StandardCharsets/UTF_8)
               :pending (atom {}) :err (atom nil) :done (promise)}
         lines (fn [stream f] (with-open [r (BufferedReader. (InputStreamReader. stream StandardCharsets/UTF_8))]
                                (doseq [l (line-seq r)] (f l))))]
     (when (fn? stderr)
       (doto (Thread. ^Runnable (fn [] (try (lines (.getErrorStream proc) stderr) (catch Exception _))))
         (.setDaemon true) (.start)))
     (doto (Thread.
            ^Runnable
            (fn []
              (try
                (lines (.getInputStream proc)
                       (fn [l]
                         (let [m (try (w/read-json l) (catch Exception _ nil))]
                           ;; only responses: notifications / server requests / noise are ignored
                           (when (and (map? m) (not (contains? m "method")) (some? (get m "id")))
                             (let [k (str (get m "id"))
                                   [old _] (swap-vals! (:pending conn) dissoc k)]
                               (some-> (get old k) (deliver m)))))))
                (catch Exception _))
              (locking conn
                (reset! (:err conn) "stdio server closed its stdout")
                (doseq [[_ p] @(:pending conn)] (deliver p ::closed)))
              (deliver (:done conn) true)))
       (.setDaemon true) (.start))
     (assoc (client "") :stdio conn))))

(defn- stdio-round-trip [conn id body]
  (let [k (str id) p (promise)]
    (locking conn
      (when-let [e @(:err conn)] (throw (ex-info e {})))
      (swap! (:pending conn) assoc k p))
    (try
      (locking (:in conn)
        (doto ^java.io.Writer (:in conn) (.write (str body "\n")) (.flush)))
      (let [m (deref p w/default-timeout-ms ::timeout)]
        (cond (= m ::timeout) (throw (ex-info "stdio request timed out" {}))
              (= m ::closed) (throw (ex-info @(:err conn) {}))
              (not (or (map? (get m "result")) (map? (get m "error"))))
              (throw (ex-info "response has neither result nor error" {}))
              :else m))
      (catch java.io.IOException e
        (throw (ex-info (str "stdio write: " (.getMessage e)) {} e)))
      (finally (swap! (:pending conn) dissoc k)))))

(defn close
  "Stop a stdio server (close stdin, kill after 2 s); no-op for HTTP clients."
  [c]
  (when-let [{:keys [^Process proc in done]} (:stdio c)]
    (try (.close ^java.io.Writer in) (catch Exception _))
    (when (= ::slow (deref done 2000 ::slow)) (.destroyForcibly proc))
    (.waitFor proc 5 TimeUnit/SECONDS)
    nil))

;; ------------------------------------------------------------------ server

(defn- sort-tools [ts] (vec (sort-by #(get % "name") ts)))

(defn server
  "An empty server: add tools with `tool`, protect it with (assoc srv :auth {...})."
  []
  {:info {"name" "toolnexus-spike" "version" "0.0.1"} :tools [] :handlers {} :skills []})

(defn tool
  "Register a code-defined tool. handler: (fn [ctx args] result), ctx has :principal. Return a
  string (text), a map with \"content\" (a full CallToolResult) or any JSON value (structured);
  a thrown exception becomes an isError result."
  [srv name description handler]
  (-> srv
      (assoc-in [:handlers name] handler)
      (update :tools (fn [ts] (sort-tools (conj (vec (remove #(= name (get % "name")) ts))
                                                {"name" name "description" description "inputSchema" {"type" "object"}}))))))

(defn- parse-frontmatter
  "The flat `key: value` YAML subset the fixture skills use."
  [md]
  (let [md (str/replace md "\r\n" "\n")]
    (if-not (str/starts-with? md "---\n")
      {}
      (if-let [end (str/index-of (subs md 4) "\n---")]
        (into {} (for [line (str/split (subs md 4 (+ 4 end)) #"\n")
                       :let [i (str/index-of line ":")]
                       :when (and i (not (str/starts-with? line " ")))]
                   [(str/trim (subs line 0 i))
                    (-> (str/trim (subs line (inc i))) (str/replace #"^[\"']+|[\"']+$" ""))]))
        {}))))

(defn- load-skill [^File dir]
  (let [fm (parse-frontmatter (slurp (io/file dir "SKILL.md")))
        name (get fm "name")
        root (.toPath dir)]
    (when (or (str/blank? name) (not= name (.getName dir)))
      (throw (ex-info (str "skill " dir ": name must match dir") {})))
    {:uri (str "skill://" name "/SKILL.md") :frontmatter fm
     :files (vec (sort-by :uri (for [^File f (file-seq dir) :when (.isFile f)]
                                 {:uri (str "skill://" name "/" (str/replace (str (.relativize root (.toPath f))) File/separator "/"))
                                  :data (java.nio.file.Files/readAllBytes (.toPath f))})))}))

(defn load-server
  "A server from a fixture JSON {server, tools, skillsDir} (skillsDir relative to the fixture)."
  [fixture-path]
  (let [fx (w/read-json (slurp fixture-path))
        _ (doseq [t (get fx "tools")] (when-not (string? (get t "name")) (throw (ex-info (str "fixture tool without a name: " t) {}))))
        root (io/file (.getParentFile (.getAbsoluteFile (io/file fixture-path))) (str (get fx "skillsDir")))]
    (assoc (server)
           :info (get fx "server")
           :tools (sort-tools (get fx "tools"))
           :skills (vec (sort-by :uri (for [^File f (file-seq root) :when (and (.isFile f) (= "SKILL.md" (.getName f)))]
                                        (load-skill (.getParentFile f))))))))

(defn fixture-server
  "The shared spike server (tools echo + greet, skill hello-world) from ../fixture.json, or $FIXTURE."
  []
  (load-server (or (not-empty (System/getenv "FIXTURE"))
                   (str (io/file (-> (io/resource "mcp2026/core.cljc") io/as-file .getParentFile .getParentFile .getParentFile .getParentFile)
                                 "fixture.json")))))

(defn- server-info [srv] {meta-server-info (:info srv)})

(defn- skill-entry [sk]
  {"uri" (:uri sk) "frontmatter" (:frontmatter sk)
   "resources" (vec (for [f (:files sk)] {"uri" (:uri f) "digest" (str "sha256:" (w/hex (w/sha256 (:data f))))
                                          "size" (alength ^bytes (:data f))}))})

(defn- err [code msg & [data]] [nil (cond-> {"code" code "message" msg} data (assoc "data" data))])

(defn- call-tool-server [srv ctx p]
  (let [name (get p "name")
        args (let [a (get p "arguments")] (if (map? a) a {}))
        complete (fn [t structured]
                   [(cond-> {"resultType" "complete" "content" [{"type" "text" "text" t}] "isError" false
                             "_meta" (server-info srv)}
                      (some? structured) (assoc "structuredContent" structured)) nil])]
    (if-let [h (get-in srv [:handlers name])]
      (let [[v e] (try [(h ctx args) nil] (catch Exception e [nil e]))]
        (cond e [{"resultType" "complete" "content" [{"type" "text" "text" (str (.getMessage ^Exception e))}] "isError" true} nil]
              (string? v) [{"resultType" "complete" "content" [{"type" "text" "text" v}] "isError" false} nil]
              (and (map? v) (contains? v "content")) [(merge {"resultType" "complete"} v) nil]
              :else [{"resultType" "complete" "content" [{"type" "text" "text" (w/write-json v)}]
                      "structuredContent" v "isError" false} nil]))
      (case name
        "echo" (let [t (str (get args "text" ""))] (complete t {"text" t}))
        "greet" (let [nr (get-in p ["inputResponses" "name"])
                      n (when (and (map? nr) (= "accept" (get nr "action"))) (get-in nr ["content" "name"]))]
                  (if (and (string? n) (seq n))
                    (complete (str "Hello, " n "!") {"greeting" (str "Hello, " n "!")})
                    ;; missing/declined answer: ask (again), per MRTR error-handling guidance
                    [{"resultType" "input_required" "_meta" (server-info srv)
                      "inputRequests" {"name" {"method" "elicitation/create"
                                               "params" {"mode" "form" "message" "What is your name?"
                                                         "requestedSchema" {"type" "object" "properties" {"name" {"type" "string"}}
                                                                            "required" ["name"]}}}}} nil]))
        (err code-invalid-params (str "Unknown tool: " name))))))

(defn dispatch
  "Run one method. Returns [result nil] or [nil error-map]."
  [srv ctx method p]
  (let [si (server-info srv)
        skill-uri-ok (fn [uri what] (try (parse-skill-uri uri) nil
                                         (catch Exception e (err code-invalid-params (str "Invalid " what " URI: " (.getMessage e))))))]
    (case method
      "server/discover" [{"resultType" "complete" "supportedVersions" [protocol-version]
                          "capabilities" {"tools" {} "resources" {} "extensions" {skills-ext {}}}
                          "_meta" si "ttlMs" 3600000 "cacheScope" "public"} nil]
      "tools/list" [{"resultType" "complete" "tools" (:tools srv) "ttlMs" 60000 "cacheScope" "public" "_meta" si} nil]
      "tools/call" (call-tool-server srv ctx p)
      "skills/list" [{"resultType" "complete" "skills" (mapv skill-entry (:skills srv)) "ttlMs" 60000
                      "cacheScope" "public" "_meta" si} nil]
      "skills/get" (let [uri (str (get p "uri"))]
                     (or (skill-uri-ok uri "skill")
                         (if-let [sk (first (filter #(= uri (:uri %)) (:skills srv)))]
                           [{"resultType" "complete" "skill" (skill-entry sk) "_meta" si} nil]
                           (err code-invalid-params (str "Unknown skill: " uri)))))
      "resources/read" (let [uri (str (get p "uri"))]
                         (or (skill-uri-ok uri "resource")
                             (if-let [f (first (filter #(= uri (:uri %)) (mapcat :files (:skills srv))))]
                               [{"resultType" "complete"
                                 "contents" [{"uri" uri "mimeType" (if (str/ends-with? uri ".md") "text/markdown" "text/plain")
                                              "text" (w/bytes->str (:data f))}]
                                 "ttlMs" 60000 "cacheScope" "public" "_meta" si} nil]
                               (err code-invalid-params (str "Resource not found: " uri)))))
      (err code-method-not-found (str "Method not found: " method)))))

(defn- decode-header [v]
  (if (and (str/starts-with? v "=?base64?") (str/ends-with? v "?=") (>= (count v) 11))
    (try [(w/bytes->str (w/b64-decode (subs v 9 (- (count v) 2)))) true] (catch Exception _ ["" false]))
    [v (not= v "")]))

(defn- validate [req method p]
  (let [mismatch (fn [msg] [400 {"code" code-header-mismatch "message" (str "Header mismatch: " msg)}])
        h (:headers req)
        hv (get h "mcp-protocol-version" "")
        hm (get h "mcp-method" "")
        mv (let [m (get p "_meta")] (str (when (map? m) (get m meta-version))))
        name-mismatch (when (#{"tools/call" "resources/read" "prompts/get"} method)
                        (let [want (str (get p (if (= method "resources/read") "uri" "name")))
                              [got ok] (decode-header (get h "mcp-name" ""))]
                          (when-not (and ok (= got want))
                            (str "Mcp-Name header value '" got "' does not match body value '" want "'"))))]
    (cond
      (= hv "") (mismatch "MCP-Protocol-Version header missing")
      (not= hm method) (mismatch (str "Mcp-Method header value '" hm "' does not match body value '" method "'"))
      name-mismatch (mismatch name-mismatch)
      (not= mv hv) (mismatch (str "MCP-Protocol-Version header value '" hv "' does not match _meta value '" mv "'"))
      (not= mv protocol-version) [400 {"code" code-unsupported-protocol "message" "Unsupported protocol version"
                                       "data" {"supported" [protocol-version] "requested" mv}}]
      :else nil)))

(defn- rpc-response [status id result e]
  {:status status :headers {"Content-Type" "application/json"}
   :body (w/write-json (cond-> {"jsonrpc" "2.0" "id" id} e (assoc "error" e) (not e) (assoc "result" result)))})

(defn handler
  "The server as an HTTP handler (fn [req] resp): POST /mcp, plus PRM well-known when :auth is set."
  [srv]
  (let [jwks-cache (atom {})
        a (:auth srv)]
    (fn [req]
      (or (when (and a (= "GET" (:method req))) (sa/serve-metadata a req))
          (cond
            (not= (:path req) "/mcp") {:status 404 :body "404 page not found"}
            (not= (:method req) "POST") {:status 405}
            (:too-large? req) (rpc-response 413 nil nil {"code" -32600 "message" "Request body too large or unreadable"})
            :else
            (let [msg (try (w/read-json (:body req)) (catch Exception _ ::bad))]
              (if-not (map? msg)
                (rpc-response 400 nil nil {"code" -32700 "message" "Parse error"})
                (let [id (get msg "id")
                      method (str (get msg "method" ""))
                      p (let [p (get msg "params")] (if (map? p) p {}))]
                  (if (nil? id)
                    {:status 202} ; notification: none defined in core; accept
                    (if-let [[status e] (validate req method p)]
                      (rpc-response status id nil e)
                      (let [[ok x] (if a (sa/check a jwks-cache req method p) [:ok nil])]
                        (if (= ok :deny)
                          x
                          (let [[res e] (dispatch srv {:principal x} method p)]
                            (rpc-response (if (and e (= (get e "code") code-method-not-found)) 404 200) id res e))))))))))))))

(defn serve!
  "Serve srv over HTTP. opts {:host \"127.0.0.1\" :port 0 :tls? false}. Returns {:url :port :stop}."
  ([srv] (serve! srv {}))
  ([srv opts] (w/serve! (handler srv) opts)))

(defn serve-stdio
  "Serve srv as newline-delimited JSON-RPC over reader `in` / writer `out` until in closes.
  No auth, no headers on stdio: only the _meta protocol version is checked. Tests/examples only."
  [srv ^java.io.Reader in ^java.io.Writer out]
  (doseq [line (line-seq (BufferedReader. in))]
    (let [m (try (w/read-json line) (catch Exception _ ::bad))
          reply (fn [msg] (locking out (.write out (str (w/write-json msg) "\n")) (.flush out)))]
      (cond
        (not (map? m)) (reply {"jsonrpc" "2.0" "id" nil "error" {"code" -32700 "message" "Parse error"}})
        (nil? (get m "id")) nil
        :else
        (let [p (let [p (get m "params")] (if (map? p) p {}))
              mv (str (get-in p ["_meta" meta-version]))
              [res e] (if (not= mv protocol-version)
                        [nil {"code" code-unsupported-protocol "message" "Unsupported protocol version"
                              "data" {"supported" [protocol-version] "requested" mv}}]
                        (dispatch srv {} (str (get m "method")) p))]
          (reply (cond-> {"jsonrpc" "2.0" "id" (get m "id")} e (assoc "error" e) (not e) (assoc "result" res))))))))
