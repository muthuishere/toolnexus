;; SPEC §2.1–§2.8 — the MCP 2026-07-28 wire, owned in-house (no SDK).
;;
;; This namespace is the PROTOCOL: wire constants, the per-request `_meta`
;; envelope, the HTTP headers (MCP-Protocol-Version / Mcp-Method / Mcp-Name with
;; the base64 sentinel), the streamable-HTTP transport (JSON or SSE replies, the
;; OAuth challenge loop, NO hidden re-send), `server/discover` classification
;; (2026 / fallback / failed, §2.2a), skill-URI containment, remote skills and
;; BM25 tool search. It knows nothing about Tools; `toolnexus.mcp` maps what it
;; returns onto the uniform Tool shape, so both paths share one result mapping.
;;
;; Transports keep `toolnexus.mcp`'s shape — `{:rpc! (fn [method params
;; timeout-ms]) …}` returning `{:ok msg}` or a named failure — so stdio reuses
;; the frozen path's reader loop (demux by id, dead child fails pending) as-is.
;;
;; One `.cljc`, zero reader conditionals, zero `java.*`.
(ns toolnexus.mcp2026
  (:require [clojure.string :as str]
            [koine.codec :as codec]
            [koine.fs :as fs]
            [koine.http :as http]
            [koine.json :as json]
            [toolnexus.frontmatter :as frontmatter]
            [toolnexus.mcp-auth :as auth]))

;; ---------------------------------------------------------------------------
;; §2.1 wire constants (byte-exact)
;; ---------------------------------------------------------------------------

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

(def client-info {"name" "toolnexus" "version" "0.25.0"})

(defn envelope
  "SPEC §2.3 — the `_meta` every 2026 request carries."
  []
  {meta-version     protocol-version
   meta-client-caps {"elicitation" {"form" {}}}
   meta-client-info client-info})

(defn with-envelope
  "params with the §2.3 `_meta` merged in (a caller's own `_meta` keys survive)."
  [params]
  (let [p (or params {})]
    (assoc p :_meta (merge (:_meta p) (envelope)))))

(defn encode-header
  "SPEC §2.3 — a header value as-is, or `=?base64?<std-base64>?=` when it has
  leading/trailing whitespace, a character outside 0x20–0x7E, or itself looks
  like the sentinel."
  [v]
  (let [v (str v)]
    (if (and (= v (str/trim v))
             (not (and (str/starts-with? v "=?base64?") (str/ends-with? v "?=")))
             (every? (fn [c] (<= 0x20 (int c) 0x7e)) v))
      v
      (str "=?base64?" (codec/encode v) "?="))))

(defn- name-header [method params]
  (case method
    ("tools/call" "prompts/get") (encode-header (str (get params :name (get params "name" ""))))
    "resources/read"             (encode-header (str (get params :uri (get params "uri" ""))))
    nil))

;; ---------------------------------------------------------------------------
;; §2.3 responses: JSON or SSE
;; ---------------------------------------------------------------------------

(defn- rpc-message? [m] (and (map? m) (or (map? (:result m)) (map? (:error m)))))

(defn parse-body
  "The JSON-RPC response in a reply body: plain JSON, or the LAST SSE `data:`
  frame carrying a result or error. nil when there is none."
  [content-type body]
  (if (str/includes? (str/lower-case (str content-type)) "text/event-stream")
    (reduce (fn [m line]
              (if (str/starts-with? line "data:")
                (let [x (try (json/read-str (str/trim (subs line 5))) (catch Throwable _ nil))]
                  (if (rpc-message? x) x m))
                m))
            nil (str/split-lines (str body)))
    (let [m (try (json/read-str (str body)) (catch Throwable _ nil))]
      (when (rpc-message? m) m))))

(defn- message->result
  "{:ok msg} for a result (resultType defaulted), or the named rpc-error."
  [msg status]
  (if-let [e (:error msg)]
    {:error "rpc-error" :code (:code e) :message (:message e) :data (:data e) :status status}
    {:ok (update msg :result (fn [r] (if (contains? r :resultType) r (assoc r :resultType "complete"))))}))

;; ---------------------------------------------------------------------------
;; §2.3 + §2.6 + §2A the streamable-HTTP transport
;; ---------------------------------------------------------------------------

(def ^:private ids (atom 0))

(def max-auth-retries 3)

(defn http-rpc!
  "One 2026 request over HTTP. Never throws. Returns {:ok msg} or a failure:
    transport | http-status | malformed-body | rpc-error | auth (with :message)
    | auth-pending (with :request, a §10 authorization Request).

  §2.6: the request is re-sent ONLY after a pre-dispatch rejection — a 401, or
  a 403 `insufficient_scope`, carrying WWW-Authenticate — at most 3 times. A
  lost connection, a timeout, a 5xx or an unreadable reply is returned as an
  error and never retried: the server may already have executed it."
  [{:keys [url headers auth http]} method params timeout-ms]
  (let [request (or http http/request)
        params  (with-envelope params)
        body    (json/write-str {:jsonrpc "2.0" :id (swap! ids inc) :method method :params params})
        nh      (name-header method params)]
    (loop [attempt 0]
      (let [sent (when auth (auth/bearer auth url))
            res  (request {:method :post :url url :timeout-ms timeout-ms :body body
                           :headers (cond-> (merge {"content-type" "application/json"
                                                    "accept" "application/json, text/event-stream"}
                                                   headers
                                                   {"mcp-protocol-version" protocol-version
                                                    "mcp-method" method})
                                      nh   (assoc "mcp-name" nh)
                                      sent (assoc "authorization" (str "Bearer " sent)))})
            status (:status res)
            www  (http/header res "www-authenticate")]
        (cond
          (http/failed? res)
          {:error "transport" :transport-error (if (:error res) (name (:error res)) "unknown")}

          (and auth (contains? #{401 403} status) (not (str/blank? (str www))))
          (if (>= attempt max-auth-retries)
            {:error "auth" :status status
             :message (str "authorization failed: HTTP " status " after " (inc attempt) " attempts")}
            (let [r (try (auth/challenge! auth url status www sent)
                         (catch Throwable x {:error "auth" :status status :message (ex-message x)}))]
              (cond
                (= :retry r)  (recur (inc attempt))
                (:pending r)  {:error "auth-pending" :request (:pending r)}
                :else         r)))

          (>= status 500) {:error "http-status" :status status}

          :else
          (let [msg (parse-body (http/header res "content-type") (:body res))]
            (cond
              msg                    (message->result msg status)
              (< 199 status 300)     {:error "malformed-body" :status status}
              :else                  {:error "http-status" :status status})))))))

(defn http-transport
  "The 2026 remote leg, in `toolnexus.mcp`'s transport shape."
  [{:keys [url headers]} auth-engine]
  {:kind    "http"
   :url     url
   :rpc!    (fn [method params timeout-ms]
              (http-rpc! {:url url :headers headers :auth auth-engine} method params timeout-ms))
   :notify! (fn [_ _] nil)
   :stderr  (fn [] [])
   :closed? (fn [] false)
   :close!  (fn [] nil)})

(defn stdio-wrap
  "A frozen-path stdio transport speaking 2026: every request carries the
  envelope. stdio has no headers, so only `-32022` can apply there (§2.3)."
  [transport]
  (assoc transport
         :rpc! (fn [method params timeout-ms]
                 (let [r ((:rpc! transport) method (with-envelope params) timeout-ms)]
                   (if (:ok r)
                     (message->result (:ok r) nil)
                     r)))))

;; ---------------------------------------------------------------------------
;; §2.2 / §2.2a discover first
;; ---------------------------------------------------------------------------

(defn discover-outcome
  "Classify a `server/discover` reply (§2.2a): `:2026`, `:fallback` or `:failed`."
  [res]
  (cond
    (:ok res)
    (if (some #{protocol-version} (get-in res [:ok :result :supportedVersions])) :2026 :fallback)
    (= "rpc-error" (:error res))      :fallback
    ;; 5xx too (amended §2.2a): Java SDK servers answer unknown methods with 500
    (= "http-status" (:error res))    (if (or (contains? #{400 404 405} (:status res))
                                              (>= (long (or (:status res) 0)) 500))
                                        :fallback :failed)
    (= "malformed-body" (:error res)) :fallback
    ;; stdio: no reply within min(timeout, 5 s) — older servers may ignore a
    ;; pre-`initialize` request
    (= "timeout" (:error res))        :fallback
    :else                             :failed))

(defn server-info
  "serverInfo from the discover result, else from its `_meta` (GitHub's shape)."
  [result]
  (or (:serverInfo result) (get-in result [:_meta (keyword meta-server-info)])))

(defn skills-extension? [result]
  (contains? (get-in result [:capabilities :extensions]) (keyword skills-ext)))

;; ---------------------------------------------------------------------------
;; §2.7 skill URIs and remote skills
;; ---------------------------------------------------------------------------

(defn parse-skill-uri
  "skill://<name>/<path> -> [name rel], percent-decoded ONCE; throws on any
  leftover `%`, `\\`, NUL, `:` or an empty / `.` / `..` segment."
  [uri]
  (when-not (str/starts-with? (str uri) "skill://") (throw (ex-info "not a skill:// URI" {})))
  (let [dec (try (auth/url-decode (subs uri 8) false)
                 (catch Throwable _ (throw (ex-info "bad percent-encoding" {}))))]
    (when (re-find #"[%\\\x00]" dec)
      (throw (ex-info "skill URI has encoded, backslash or NUL characters" {})))
    (let [segs (str/split dec #"/" -1)]
      (when (< (count segs) 2) (throw (ex-info "skill URI needs skill://<name>/<path>" {})))
      (when (some (fn [s] (or (= s "") (= s ".") (= s "..") (str/includes? s ":"))) segs)
        (throw (ex-info "skill URI path escapes the skill (empty, '.', '..' or ':' segment)" {})))
      [(first segs) (str/join "/" (rest segs))])))

(defn- read-text [rpc uri]
  (let [r (rpc "resources/read" {:uri uri})]
    (when-not (:ok r) (throw (ex-info (str uri ": resources/read failed: " (:error r)) {})))
    (let [cs (get-in r [:ok :result :contents])]
      (when (empty? cs) (throw (ex-info (str uri ": empty resources/read") {})))
      (str (:text (first cs))))))

(defn- digest-ok? [digest text]
  (or (str/blank? (str digest)) (= digest (str "sha256:" (auth/hex (auth/sha256 text))))))

(defn fetch-skill
  "One remote skill: skills/get + its resources' metadata, every resource URI
  contained in the skill. Returns {:name :uri :files [{:uri :rel :digest}]}."
  [rpc uri]
  (let [[nm _] (parse-skill-uri uri)
        r  (rpc "skills/get" {:uri uri})
        _  (when-not (:ok r) (throw (ex-info (str uri ": skills/get failed: " (:error r)) {})))
        sk (get-in r [:ok :result :skill])]
    {:name nm :uri uri :frontmatter (:frontmatter sk)
     :files (vec (for [m (:resources sk)]
                   (let [fu (str (:uri m))
                         [n rel] (try (parse-skill-uri fu) (catch Throwable _ nil))]
                     (when-not (and n (= n nm) (not (str/starts-with? rel "/")))
                       (throw (ex-info (str "refusing skill resource \"" fu "\": outside skill " nm) {})))
                     {:uri fu :rel rel :digest (:digest m)})))}))

(defn remote-skill-defs
  "SPEC §2.7 — every skill the server lists, as §3 S1 skill DEFS, so remote
  skills join the SAME `skill` tool inventory as local ones. The body is the
  SKILL.md text through the same frontmatter reader a local SKILL.md goes
  through, so it is byte-identical to the local copy. A skill that fails
  containment or its digest is skipped; it never fails the server."
  [rpc]
  (let [r (rpc "skills/list" {})]
    (if-not (:ok r)
      []
      (vec
       (keep (fn [entry]
               (try
                 (let [sk   (fetch-skill rpc (str (:uri entry)))
                       main (first (filter (fn [f] (= "SKILL.md" (:rel f))) (:files sk)))
                       text (read-text rpc (str (:uri entry)))
                       _    (when-not (digest-ok? (:digest main) text)
                              (throw (ex-info "digest mismatch" {})))
                       fm   (frontmatter/read-frontmatter text)
                       data (:data fm)]
                   {:name        (or (:name data) (:name sk))
                    :description (or (:description data) (get-in entry [:frontmatter :description]))
                    :content     (:body fm)
                    :resources   (vec (sort (remove #{"SKILL.md"} (map :rel (:files sk)))))
                    :base        (str "skill://" (:name sk) "/")})
                 (catch Throwable _ nil)))
             (get-in r [:ok :result :skills]))))))

(defn download-skill!
  "Fetch a skill into dir/<name>/ (skills/get + resources/read per file),
  checking every file's sha256 digest and containment BEFORE writing anything."
  [rpc uri dir]
  (let [sk    (fetch-skill rpc uri)
        files (mapv (fn [f]
                      (let [t (read-text rpc (:uri f))]
                        (when-not (digest-ok? (:digest f) t)
                          (throw (ex-info (str (:uri f) ": digest mismatch") {})))
                        [(str dir "/" (:name sk) "/" (:rel f)) t]))
                    (:files sk))]
    (doseq [[p t] files]
      (let [i (str/last-index-of p "/")] (fs/mkdirs! (subs p 0 i)))
      (fs/write-file p t))
    nil))

;; ---------------------------------------------------------------------------
;; §2.8 lazy tool search — BM25
;; ---------------------------------------------------------------------------

(defn search-description [about]
  (str "Search the tools of " about " by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool."))

(def search-schema
  {:type "object" :properties {:query {:type "string"} :limit {:type "integer"}} :required ["query"]})

(def call-description "Run a tool found with search_tools.")

(def call-schema
  {:type "object" :properties {:name {:type "string"} :arguments {:type "object"}} :required ["name"]})

(defn- ch-class [c]
  (let [s (str c)]
    (cond (re-matches #"\p{Lu}" s) :upper
          (re-matches #"\p{Ll}" s) :lower
          (re-matches #"[\p{L}\p{N}]" s) :other
          :else nil)))

(defn tokens
  "Lowercase words, split on non-alphanumerics and lower->Upper boundaries."
  [s]
  (loop [cs (seq (str s)) cur [] out [] prev-lower false]
    (let [flush (fn [out cur] (if (seq cur) (conj out (str/lower-case (apply str cur))) out))]
      (if-not cs
        (flush out cur)
        (let [c (first cs) k (ch-class c)]
          (cond (and (= k :upper) prev-lower) (recur (next cs) [c] (flush out cur) false)
                k (recur (next cs) (conj cur c) out (= k :lower))
                :else (recur (next cs) [] (flush out cur) false)))))))

(defn- ln
  "Natural log in plain arithmetic (cljgo has no Math/log): x = m·2^k, m in
  [1,2), ln m = 2·atanh((m-1)/(m+1))."
  [x]
  (let [x (double x)]
    (loop [m x k 0]
      (cond (>= m 2.0) (recur (/ m 2.0) (inc k))
            (< m 1.0) (recur (* m 2.0) (dec k))
            :else
            (let [y (/ (- m 1.0) (+ m 1.0)) y2 (* y y)
                  s (loop [i 0 term y acc 0.0]
                      (if (= i 60) acc (recur (inc i) (* term y2) (+ acc (/ term (+ 1.0 (* 2.0 i)))))))]
              (+ (* 2.0 s) (* k 0.6931471805599453)))))))

(defn hits-json
  "SPEC §2.8 pin — compact JSON array, each hit's keys in the order name,
  description, inputSchema, server. koine sorts object keys, so the hit object
  is assembled here; `inputSchema` itself is koine-encoded (sorted keys)."
  [hits]
  (str "[" (str/join "," (map (fn [h]
                                (str "{\"name\":" (json/write-str (:name h))
                                     ",\"description\":" (json/write-str (:description h))
                                     ",\"inputSchema\":" (json/write-str (:inputSchema h))
                                     ",\"server\":" (json/write-str (:server h)) "}"))
                              hits))
       "]"))

(defn search
  "Rank `defs` ({:name :description :inputSchema}) against `query` — BM25 over
  the name (weight ×2) and description; top `limit` hits as
  {name description inputSchema server}."
  [defs query limit about]
  (let [n    (count defs)
        docs (mapv (fn [t] (let [nt (tokens (:name t))] (vec (concat nt nt (tokens (:description t)))))) defs)
        df   (frequencies (mapcat distinct docs))
        avg  (/ (double (reduce + 0 (map count docs))) (max 1.0 (double n)))
        hits (keep-indexed
              (fn [i d]
                (let [tf (frequencies d)
                      s  (reduce (fn [s q]
                                   (let [f (double (get tf q 0))]
                                     (if (zero? f)
                                       s
                                       (let [dq  (double (get df q))
                                             idf (ln (+ 1.0 (/ (+ (- n dq) 0.5) (+ dq 0.5))))]
                                         (+ s (/ (* idf f 2.2) (+ f (* 1.2 (+ 0.25 (/ (* 0.75 (count d)) avg))))))))))
                                 0.0 (tokens query))]
                  (when (pos? s) [i s])))
              docs)]
    (vec (for [[i _] (take limit (sort-by (fn [[i s]] [(- s) i]) hits))
               :let [t (nth defs i)]]
           {:name (:name t) :description (:description t) :inputSchema (:inputSchema t) :server about}))))
