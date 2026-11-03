;; The unified LLM client — SPEC §0.10 (the loop), §8 (parallel tool calls,
;; RunResult telemetry) and §0.12 / §10 (suspension).
;;
;; Give it a base URL + a "style" and it runs the whole tool-calling agent loop
;; against a `toolnexus.tool` toolkit:
;;
;;   system = systemPrompt + "\n\n" + skillsPrompt
;;   call the endpoint -> execute the tool calls -> feed the results back in the
;;   provider's tool-result shape -> repeat, bounded by max-turns (default 10).
;;
;; Both styles are implemented and both are measured: "openai" posts to
;; {base}/chat/completions and feeds results back as {role:"tool"} messages;
;; "anthropic" posts to {base}/v1/messages and feeds them back as a `user`
;; message of `tool_result` content blocks. The two wire shapes are different
;; enough that only the second one being exercised makes "it composes" a claim
;; rather than a measurement.
;;
;; Casing, deliberately (§10 + spike S23 finding 3): `Request`/`Answer`/
;; `ToolResult` keys are PINNED across the ports because they cross the wire,
;; and `koine.json/write-str` emits a keyword's name verbatim — the keyword IS
;; the wire key. So `:isError` and `:expiresAt` appear literally in this file,
;; camelCase, in a language whose whole convention is kebab-case. There is no
;; renaming layer and there must not be one: a rename here would be exactly the
;; cross-port drift the ports exist to prevent. Everything NOT on the wire
;; (client options, `RunResult`) is idiomatic kebab-case, which is what §8 asks
;; for ("idiomatic-cased like RunResult").
;;
;; Zero reader conditionals, zero java.*, zero Go interop.
(ns toolnexus.client
  (:require [clojure.string :as str]
            [koine.env :as env]
            [koine.fs :as fs]
            [koine.http :as http]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.adapter :as adapter]
            [toolnexus.builtin :as builtin]
            [toolnexus.content :as content]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; §10  Request / Answer — byte-identical wire data
;; ---------------------------------------------------------------------------
;;
;;   Request { id, kind, prompt, url?, data?, expiresAt? }
;;   Answer  { id, ok, data?, reason? }
;;
;; Optional keys are OMITTED when absent, never emitted as null.

(def ^:private id-counter (atom 0))

(defn new-request-id
  "A unique correlation key for one suspension."
  []
  (str "sus-" (ktime/now-ms) "-" (swap! id-counter inc)))

(defn make-request
  "§10 `Request`. `opts` may carry :url, :data (incl. R2's `data.schema`) and
  :expiresAt (RFC3339)."
  ([kind prompt] (make-request kind prompt {}))
  ([kind prompt opts]
   (merge {:id (or (:id opts) (new-request-id)) :kind (str kind) :prompt (str prompt)}
          (select-keys opts [:url :data :expiresAt]))))

(defn make-answer
  "§10 `Answer`. R1: `reason` is populated ONLY when ok == false; the loop rule
  branches on `ok` alone and never reads it."
  ([id ok] (make-answer id ok nil nil))
  ([id ok data] (make-answer id ok data nil))
  ([id ok data reason]
   (cond-> {:id id :ok (boolean ok)}
     (some? data)                  (assoc :data data)
     (and (not ok) (some? reason)) (assoc :reason reason))))

(defn as-answer
  "§10 `Answer`, normalised so a STRING-KEYED map means what it says.

  §10 pins `Answer`'s keys because they cross the wire, and that is precisely
  why a host round-tripping an Answer through JSON — a database column, a
  webhook body, a queue — hands this port `{\"id\" … \"ok\" true}`. Before this,
  `(:ok answer)` read nil from such a map and the run treated a GRANTED answer
  as DECLINED: no error, no log, the opposite outcome (issue #89, ADR 0026 §5).
  It is the worst failure mode in that batch precisely because it is silent.

  Only the TOP-LEVEL keys are normalised. `:data` is the host's own payload and
  is handed to the tool untouched."
  [answer]
  (when (map? answer)
    (reduce (fn [m e]
              (let [k (key e)]
                (assoc m (if (keyword? k) k (keyword (str k))) (val e))))
            {} answer)))

(defn answer-output
  "§10 `Answer` carrying ONE tool output for a durable resume (ADR 0026: the
  constructor exists so the map stops being hand-built and there is no key left
  to get wrong). Recognised payload keys are `results` then `output`, in that
  precedence (addendum A3); this builds the `output` shape.

  A non-string `output` THROWS. Degrading it to \"\" is how a wrong answer
  reaches the model quietly, which is the whole defect this constructor closes."
  ([id output] (answer-output id output false))
  ([id output is-error]
   (when-not (string? output)
     (throw (ex-info "toolnexus: Answer output must be a string" {:output output})))
   (make-answer id true (cond-> {:output output}
                          is-error (assoc :isError true)))))

(defn answer-declined
  "§10 `Answer` for a request the host will NOT grant (addendum A9, shipped in
  all seven ports beside `answer-output`). R1: `reason` is carried only here,
  because the loop rule branches on `ok` alone."
  ([id] (answer-declined id nil))
  ([id reason] (make-answer id false nil reason)))

(defn suspend
  "A `ToolResult` whose `metadata.pending` is a `Request` IS a suspension
  (§0.12). `execute`'s signature is untouched — suspension is data on the
  existing result, not a new return type."
  ([request] (suspend request (:prompt request)))
  ([request output]
   {:output (str output) :isError true :metadata {:pending request}}))

(defn pending-of
  "The `Request` iff this ToolResult is a suspension, else nil."
  [result]
  (get-in result [:metadata :pending]))

(defn auth-required
  "§10 sugar: a `kind:\"authorization\"` suspension at `url`."
  ([url] (auth-required url (str "Login required: " url)))
  ([url prompt]
   (suspend (make-request "authorization" prompt {:url url})
            (str "Login required: " url))))

;; ---------------------------------------------------------------------------
;; client options
;; ---------------------------------------------------------------------------

(def statuses
  "The §8 `RunResult` status vocabulary — THREE values, and the ONLY three a
  `run` can return:

    done         the model produced a final answer
    pending      a §10 durable suspension; the Request is on `:pending`
    incomplete   a limit stopped the run, and `:limit` NAMES which

  IT IS NOT THE SAME VOCABULARY as `toolnexus.agents.runtime/statuses`, which
  has SEVEN values for a TaskResult and includes `\"timeout\"`. Two different
  sets on two fields both spelled `status` is the collision behind #92.1, and
  the fix is to name both rather than rename either (D5): a host switching on a
  status must know WHICH vocabulary it holds. A §8 run never returns
  `\"timeout\"` — a whole-run deadline throws `timeout-error`.

  RESIDUAL GAP, stated rather than implied (addendum A7): these constants pin
  the VALUES; nothing pins the invariant that a third vocabulary can never land
  on a third field called `status`. Tracked as a follow-up conformance row."
  #{"done" "pending" "incomplete"})

(def limits
  "The §8 `RunResult.limit` vocabulary — which limit stopped an `incomplete`
  run. Identical strings in every port.

    maxTurns     the turn budget was exhausted
    contentPart  a content part could not be sent (§8A)
    timeout      the whole-run `:timeout-ms` deadline expired"
  #{"maxTurns" "contentPart" "timeout"})

(def ^:private default-max-turns 10)
(def ^:private default-retries 2)

(defn- trim-slashes [s] (str/replace (str s) #"/+$" ""))

(defn- style-of [opts]
  (let [s (:style opts)]
    (if (= "anthropic" (name (or s "openai"))) "anthropic" "openai")))

(defn ^:no-doc host-of
  "The lower-cased host of an HTTPS `base-url`, or nil — a provider key is never
  sent over plain http, so any other scheme has no host here. Userinfo and port
  are dropped. Hand-rolled: there is no URL parser both hosts share."
  [base-url]
  (when-let [m (re-find #"^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)" (str base-url))]
    (when (= "https" (str/lower-case (nth m 1)))
      (let [auth (nth m 2)
            auth (if-let [i (tool/last-index-of-char auth \@)] (subs auth (inc i)) auth)
            host (if (str/starts-with? auth "[")
                   (if-let [j (str/index-of auth "]")] (subs auth 0 (inc j)) auth)
                   (first (str/split auth #":" 2)))]
        (str/lower-case (str host))))))

(defn ^:no-doc key-env-for-base-url
  "The NAME of the one env var whose key may be sent to `base-url`'s host, or nil
  when none may (SPEC §4C, API key by host)."
  [base-url]
  (let [h (host-of base-url)]
    (cond
      (= h "api.openai.com")    "OPENAI_API_KEY"
      (= h "api.anthropic.com") "ANTHROPIC_API_KEY"
      (and h (or (= h "openrouter.ai") (str/ends-with? h ".openrouter.ai"))) "OPENROUTER_API_KEY"
      :else nil)))

(defn ^:no-doc resolve-key
  "The API key (SPEC §4C, API key by host — security). An explicit `:api-key` is
  used as-is. Otherwise the key comes from `:api-key-env` (an env var NAME), else
  ONLY from the env var that belongs to the `:base-url` host:
  https://api.openai.com → OPENAI_API_KEY, https://api.anthropic.com →
  ANTHROPIC_API_KEY, https://openrouter.ai (and subdomains) → OPENROUTER_API_KEY.
  Any other host, or plain http, without `:api-key`/`:api-key-env` THROWS: a key
  is never sent to a host it does not belong to, and there is no cross-provider
  fallback. The value is never logged, never put in a result or an error — it
  only ever reaches a request header."
  [opts]
  (let [explicit (:api-key opts)]
    (if (and (string? explicit) (not= "" explicit))
      explicit
      (let [given    (:api-key-env opts)
            env-name (if (and (string? given) (not= "" given)) given (key-env-for-base-url (:base-url opts)))]
        (if-not env-name
          (throw (ex-info (str "no API key: set :api-key or :api-key-env (provider env vars are only "
                               "used for https://api.openai.com, https://api.anthropic.com and "
                               "https://openrouter.ai, never for " (pr-str (str (:base-url opts))) ")")
                          {:toolnexus/error :config}))
          (let [k (env/get-env env-name)]
            (if (and (string? k) (not= "" k))
              k
              (throw (ex-info (str "no API key: set :api-key or " env-name)
                              {:toolnexus/error :config :api-key-env env-name})))))))))

(defn- newest-first
  "§conversation-store list order: newest first, ties by id ascending."
  [entries]
  (vec (sort (fn [a b]
               (let [c (compare (:updated-at b) (:updated-at a))]
                 (if (zero? c) (tool/compare-strings (:id a) (:id b)) c)))
             entries)))

(defn in-memory-store
  "§conversation-store — the shipped default: per-client, process lifetime.
  The CONTRACT is exactly two operations, `:get` and `:save`, so a host can swap
  in a file or a database without this namespace knowing anything about it. The
  shipped stores also carry `:list` (`[{:id :updated-at}]`, newest first, ties
  by id; `:updated-at` is epoch ms) and `:delete` (absent id is not an error)."
  []
  (let [a (atom {})]
    {:get    (fn [id] (get-in @a [id :messages]))
     :save   (fn [id msgs] (swap! a assoc id {:messages msgs :updated-at (ktime/now-ms)}) nil)
     :list   (fn [] (newest-first (map (fn [[id v]] {:id id :updated-at (:updated-at v)}) @a)))
     :delete (fn [id] (swap! a dissoc id) nil)}))

;; ---------------------------------------------------------------------------
;; §conversation-store — the durable file store
;; ---------------------------------------------------------------------------
;;
;; koine has no rename, no mtime, no chmod and no fsync, and this namespace may
;; not branch per host, so those four go through POSIX tools (`mv`, `stat`,
;; `chmod` via umask, `sync`) with an argv and no shell interpretation of any
;; value. POSIX only — the same boundary the `bash` builtin already has.

(def ^:private id-chars
  (set "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-"))

(defn valid-conversation-id?
  "SPEC §conversation-store — `^[A-Za-z0-9._-]{1,128}$` and not `.`/`..`."
  [id]
  (and (string? id)
       (<= 1 (count id) 128)
       (every? id-chars id)
       (not (#{"." ".."} id))))

(defn- check-id! [id]
  (when-not (valid-conversation-id? id)
    (throw (ex-info (str "file-store: invalid conversation id: " (pr-str id))
                    {:error "invalid-id" :id id})))
  id)

(defn- sh! [argv what]
  (let [r (proc/sh argv {:timeout-ms 10000})]
    (when-not (= 0 (:exit r))
      (throw (ex-info (str "file-store: " what " failed: " (str/trim (str (:err r))))
                      {:error "io" :argv argv})))
    r))

(def ^:private digit-val
  {\0 0 \1 1 \2 2 \3 3 \4 4 \5 5 \6 6 \7 7 \8 8 \9 9})

(defn- seconds->ms
  "`stat`'s \"1792818339.694926003\" as epoch ms. Hand-rolled: `parse-double`
  is unverified on cljgo and this namespace may not branch per host."
  [s]
  (let [[whole frac] (str/split (str/trim s) #"\." 2)
        num (fn [cs] (reduce (fn [n c] (+ (* 10 n) (long (digit-val c 0)))) 0 cs))
        ms  (take 3 (concat (seq (or frac "")) (repeat \0)))]
    (+ (* 1000 (num whole)) (num ms))))

(defn- mtimes-ms
  "File mtimes in epoch ms, one per path, in order. GNU `stat -c`, then BSD
  `stat -f` — the two spellings of the same question."
  [paths]
  (if (empty? paths)
    []
    (let [gnu (proc/sh (into ["stat" "-c" "%.9Y" "--"] paths) {:timeout-ms 10000})
          r   (if (= 0 (:exit gnu))
                gnu
                (sh! (into ["stat" "-f" "%Fm" "--"] paths) "stat"))]
      (mapv seconds->ms (remove str/blank? (str/split-lines (:out r)))))))

(def ^:private save-lock
  "In-process serialization of saves — the same compare-and-set! spin the MCP
  writer uses, because `locking` is a JVM special form."
  (atom false))

(defn- with-save-lock! [f]
  (loop []
    (if (compare-and-set! save-lock false true)
      (try (f) (finally (reset! save-lock false)))
      (do (ktime/sleep! 1) (recur)))))

(defn file-store
  "§conversation-store `FileConversationStore(root)` — durable, one file per id:
  `<root>/<id>.json` holding `{\"version\":1,\"messages\":[...]}`.

  `root` is required and never defaulted; it is created (0700) on first save.
  `:save` writes `<root>/.<id>.<random>.tmp` (0600), syncs, and renames it over
  the target, so a reader sees the old or the new transcript, never a torn one
  (power-loss durability of the rename is best-effort). `:get` of a missing id
  is nil; corrupt JSON or an unknown `version` THROWS. `:list` returns
  `[{:id :updated-at}]` (file mtime, epoch ms) newest first, ties by id, without
  parsing bodies. `:delete` of an absent id is not an error. An id outside
  `^[A-Za-z0-9._-]{1,128}$` (or `.`/`..`) is rejected by every operation.
  Same-process saves are serialized; across processes the last rename wins.

  Opts `{:sync mode}` (SPEC §8 \"Durability mode\"): `:full` (default) runs
  `sync FILE` before the rename and `sync ROOT` after it (best-effort);
  `:fsync` only `sync FILE`; `:none` no flush. Temp-then-rename holds in every
  mode; an unknown mode throws. GNU `sync FILE` is an fsync(2) of that file;
  BSD/macOS `sync` ignores its argument and runs a global sync(2) — no
  F_FULLFSYNC is reachable from a POSIX tool."
  ([root] (file-store root {}))
  ([root {:keys [sync] :or {sync :full}}]
  (when-not (and (string? root) (not (str/blank? root)))
    (throw (ex-info "file-store: root is required" {:error "config"})))
  (when-not (#{:full :fsync :none} sync)
    (throw (ex-info (str "file-store: unknown sync mode " (pr-str sync))
                    {:error "config" :sync sync})))
  (let [path (fn [id] (str root "/" (check-id! id) ".json"))]
    {:get
     (fn [id]
       (let [p (path id)]
         (when (fs/exists? p)
           (let [doc (try (json/read-str (fs/read-file p))
                          (catch Throwable e
                            (throw (ex-info (str "file-store: corrupt conversation " id ": "
                                                 (or (ex-message e) (str e)))
                                            {:error "corrupt" :id id}))))]
             (when-not (and (map? doc) (= 1 (:version doc)))
               (throw (ex-info (str "file-store: conversation " id " has unsupported version "
                                    (pr-str (when (map? doc) (:version doc))))
                               {:error "version" :id id})))
             (vec (:messages doc))))))

     :save
     (fn [id msgs]
       (let [target (path id)
             tmp    (str root "/." id "." (ktime/mono-ms) "-" (rand-int 1000000000) ".tmp")
             body   (json/write-str {:version 1 :messages (vec msgs)})]
         (with-save-lock!
          (fn []
            (when-not (fs/directory? root)
              (sh! ["mkdir" "-p" "-m" "700" "--" root] "mkdir"))
            (try
              ;; created 0600 BEFORE any content lands in it
              (sh! ["sh" "-c" "umask 077 && : > \"$1\"" "sh" tmp] "create temp")
              (fs/write-file tmp body)
              ;; best-effort flush: GNU `sync FILE`; BSD `sync` ignores the arg
              (when-not (= :none sync)
                (proc/sh ["sync" tmp] {:timeout-ms 10000}))
              (sh! ["mv" "-f" "--" tmp target] "rename")
              (catch Throwable e
                (try (fs/delete! tmp) (catch Throwable _ nil))
                (throw e)))
            ;; best-effort directory flush so the rename survives power loss
            (when (= :full sync)
              (proc/sh ["sync" root] {:timeout-ms 10000}))))
         nil))

     :list
     (fn []
       (if-not (fs/directory? root)
         []
         (let [prefix (str root "/")
               ids    (->> (fs/list-tree root)
                           (keep (fn [p]
                                   (when (and (str/starts-with? p prefix)
                                              (str/ends-with? p ".json"))
                                     (let [n (subs p (count prefix) (- (count p) 5))]
                                       (when (valid-conversation-id? n) n)))))
                           (filter #(not (fs/directory? (str prefix % ".json"))))
                           distinct
                           vec)
               times  (mtimes-ms (mapv #(str prefix % ".json") ids))]
           (newest-first (map (fn [id t] {:id id :updated-at t}) ids times)))))

     :delete
     (fn [id] (fs/delete! (path id)) nil)})))

(defn create-client
  "Build a client. Options (idiomatic kebab-case — these never hit the wire):

    :base-url       required, e.g. \"https://api.anthropic.com\"
    :style          \"openai\" | \"anthropic\"   (default \"openai\")
    :model          required
    :api-key        optional. Absent, the key comes from :api-key-env, else ONLY
                    from the env var of the :base-url host (https://api.openai.com
                    → OPENAI_API_KEY, https://api.anthropic.com →
                    ANTHROPIC_API_KEY, https://openrouter.ai → OPENROUTER_API_KEY).
                    Any other host, or plain http, needs :api-key or :api-key-env
                    — a key is never sent to a host it does not belong to.
    :api-key-env    the NAME of the env var to read the key from (never the value)
    :headers        extra request headers
    :system-prompt  prepended to the toolkit's skills prompt
    :max-turns      default 10
    :max-tool-concurrency  cap on concurrency-safe tool calls of one response
                    running at once (SPEC §8, Tool scheduling). Absent ⇒ env
                    TOOLNEXUS_MAX_TOOL_CONCURRENCY when a positive integer, else
                    10. Unsafe calls (anything not read-only) always run alone.
    :retries        transient-failure budget (default 2); retries on
                    `429`/`500`/`502`/`503`/`504`/`529` + network. Widen the
                    status set with `:retryable-statuses`.
    :retry-base-ms  base of the exponential backoff in ms (default 500, as in
                    the other six ports). The wait is
                    `base * 2^attempt + jitter[0,100)ms`, and a usable
                    `Retry-After` still wins over it.
    :retryable-statuses
                    extra HTTP statuses to treat as retryable, ADDED to the
                    default set (`429`/`500`/`502`/`503`/`504`/`529`). It can
                    only widen: a host cannot remove `429` and lose
                    `Retry-After` handling with it. This sets the DEFAULT
                    classification; `:on-error` still runs per attempt and has
                    the final say, so `:on-error` returning `:fail` overrides a
                    status listed here. Example: a Cloudflare-fronted origin
                    that answers `520`–`527`.
    :hooks          §8 lifecycle middleware — a map of any of
                    {:before-llm :after-llm :before-tool :after-tool}; see
                    `before-llm!` / `execute-tool`. Absent => nothing changes.
    :wait-for       (fn [request] answer) — the ONE host slot of §10.
    :wait-for-ctx   (fn [ctx request] answer) — `:wait-for` that also receives
                    the turn's context `{:cancel :cancelled? :call-id :turn}`, so
                    a pending ask can stop when the turn is cancelled (throw). Wins
                    over `:wait-for` when both are set (SPEC §4C).
    :prompt-cache   {:key :long-ttl} — mark the stable request prefix for the
                    provider's cache (SPEC §4C prompt caching). Absent ⇒ the body
                    is byte-identical.
    :tool-tiers     {:core :deferred :budget-tokens :context-window :usage-count
                    :count-tokens} — send only core tool schemas plus a
                    `tool_search`/`call_tool` pair (SPEC §4C tool tiers). Absent ⇒
                    every tool is sent, as before.
    :max-part-bytes §1B — reject a content part carrying more than this many
                    DECODED bytes (never the +33% base64 string). Unset => no
                    limit, exactly as before.
    :on-unsupported-part
                    §8A — \"error\" | \"text\", overriding the provenance rule
                    uniformly. Unset => an ATTACHED part the style cannot
                    represent stops the run, a TOOL-DERIVED one degrades to a
                    text placeholder and warns once.

  On the name of that slot: §10 says \"never `await`\" because `await` is
  reserved in JS/Python/C#. In Clojure it is not reserved — it is
  `clojure.core/await`, and it exists on BOTH hosts, so `(defn await ...)`
  would shadow a core name and (per the spike brief) can make cljgo's static
  interop scan reject the WHOLE namespace. Same answer, sharper teeth."
  [opts]
  {:pre [(some? (:base-url opts)) (some? (:model opts))]}
  (-> opts
      (assoc :style (style-of opts))
      (update :max-turns #(or % default-max-turns))
      ;; :retries is explicitly given a value at construction time so that
      ;; every other reader (post-with-retry included) always sees an
      ;; explicit budget — never a bare absence that could be mistaken for
      ;; zero. Unset => 2 (matching all six other ports: 3 total attempts);
      ;; an explicit value, including 0, is honored as-is.
      (update :retries #(if (some? %) % default-retries))
      ;; §conversation-store: the default is in-memory and per-client, so two
      ;; clients never share a transcript by accident.
      (update :store #(or % (in-memory-store)))
      ;; SPEC §4C tool tiers: one frozen plan per toolkit identity.
      (cond-> (:tool-tiers opts) (assoc ::tier-plans (atom {})))))

;; ---------------------------------------------------------------------------
;; In-process models (SPEC §8 Gap 2, semantic form)
;; ---------------------------------------------------------------------------

(def ^:private in-process-base-url
  "A sentinel. Never dialled — the `:http-client` below answers every request before
  the network is reached — but a URL string is built internally, so it must be
  syntactically valid. `.invalid` is reserved by RFC 2606 precisely so a name can
  never resolve."
  "http://in-process.invalid/v1")

(defn- encode-args
  "Pass an already-encoded string through; encode anything else, so a model author
  never has to think about the wire."
  [v]
  (cond
    (nil? v) "{}"
    (string? v) v
    :else (json/write-str v)))

(defn in-process-http-client
  "Turns a semantic `generate` into the shipped `:http-client` seam: the host returns
  ONE assistant message and this builds the provider envelope.

  Public (ADR 0030) so a caller OTHER than `create-in-process-client` — the
  agent runtime's `:in-process` option, in particular — can build the exact
  same `:http-client` from a `generate` function with zero duplicated logic.
  `create-in-process-client` itself is unchanged: it still calls this."
  [generate]
  (fn [_url _headers body]
    (let [payload (json/read-str (if (string? body) body (json/write-str body)))
          answer  (or (generate {:messages (:messages payload)
                                 :tools    (:tools payload)
                                 :model    (:model payload)
                                 :body     payload})
                      {})
          calls   (or (:tool-calls answer) (:tool_calls answer))
          message (if (seq calls)
                    {:role "assistant"
                     :tool_calls
                     (vec (map-indexed
                           (fn [i c]
                             {:id (or (:id c) (str "call_" i))
                              :type "function"
                              :function {:name (:name c)
                                         :arguments (encode-args (:arguments c))}})
                           calls))}
                    {:role "assistant" :content (or (:content answer) "")})
          usage   (or (:usage answer) {})
          prompt  (or (:prompt-tokens usage) (:prompt_tokens usage) 0)
          compl   (or (:completion-tokens usage) (:completion_tokens usage) 0)
          total   (or (:total-tokens usage) (:total_tokens usage) (+ prompt compl))]
      {:status 200
       :headers {"content-type" "application/json"}
       :body (json/write-str
              {:choices [{:index 0
                          :message message
                          :finish_reason (if (seq calls) "tool_calls" "stop")}]
               :usage {:prompt_tokens prompt
                       :completion_tokens compl
                       :total_tokens total}})})))

(defn create-in-process-client
  "A client backed by a model running IN THIS PROCESS — no server, no socket, and no
  HTTP shapes to construct.

  This is a second constructor, not a second seam: it builds on the same
  `:http-client` transport, so the tool-calling loop, MCP servers, skills,
  sub-agents, hooks, metrics and the completion gate behave identically.

    (create-in-process-client
      {:model \"my-local\"
       :generate (fn [req]
                   ;; req = {:messages [...] :tools [...] :model \"my-local\" :body {...}}
                   {:content \"hello\"})})
                   ;; or {:tool-calls [{:name \"add\" :arguments {:a 2 :b 3}}]}

  `:generate` returns ONE assistant message; `:usage` is optional. There is no
  `:base-url`, `:api-key` or `:style` — there is no wire to configure.

  NOTE this port has no streaming entry point (`:on-event` is a sink on the
  non-streaming loop), so unlike the other six there is nothing here to refuse."
  [{:keys [model generate] :as opts}]
  (when-not (fn? generate)
    (throw (ex-info "toolnexus: create-in-process-client requires a `:generate` function" {})))
  (doseq [reserved [:base-url :api-key :style :http-client]]
    (when (contains? opts reserved)
      (throw (ex-info (str "toolnexus: create-in-process-client does not take " reserved
                           " — an in-process model has no wire to configure. Use "
                           "create-client for a network-backed model.")
                      {:option reserved}))))
  ;; Zero retries by default: there is no wire, so there is no transient failure to
  ;; ride out, and retrying only buys backoff before the caller sees their own bug.
  (-> opts
      (update :retries #(or % 0))
      (dissoc :generate)
      (assoc :base-url in-process-base-url
             :style "openai"
             ;; An in-process model has no endpoint to authenticate to, so the host must never need a key — but the client resolves one from the environment and fails when it finds none. A sentinel keeps that resolution from ever running. Caught by CI, which has no OPENROUTER_API_KEY; every local run passed because a developer shell has one.
             :api-key "in-process"
             :model model
             :http-client (in-process-http-client generate))
      create-client))

(defn- metric!
  "§client-observability — SEMANTIC events, not counter primitives. Guarded so
  that with `:on-metric` unset there is no measurable overhead: the map is not
  even built."
  [client f]
  (when-let [on (:on-metric client)] (on (f)))
  nil)

;; ---------------------------------------------------------------------------
;; §8 hooks — lifecycle middleware
;; ---------------------------------------------------------------------------
;;
;;   :before-llm  {:messages :tools :model :turn}  -> {:messages? :tools? :model?}
;;                  a non-empty :model is sent for THAT turn only (body +
;;                  :after-llm event); nil/"" => the configured model verbatim
;;   :after-llm   {:response :model :turn}         -> observe only
;;   :before-tool {:name :args :id :turn}          -> {:result} short-circuits,
;;                                                    {:args} rewrites
;;   :after-tool  {:name :args :result :id :turn}  -> {:result} replaces
;;
;; The event maps are kebab-case because a hook never crosses the wire; the
;; `ToolResult` one carries keeps its pinned `:isError`, because that one does.
;;
;; A hook returning nil (the common case — most hooks observe) changes nothing,
;; which is why every application below is an `or` against the current value
;; rather than a blind overwrite.

(defn- hook-of [client k] (get-in client [:hooks k]))

(defn before-llm!
  "§8 `beforeLLM`, applied to ONE round trip. Returns `[messages tools model]` —
  the hook's replacements when it returned them, the originals otherwise.
  `model` is the hook's non-empty `:model` for THIS turn only (change
  add-judge-batteries), else the configured model verbatim.

  Public because §11 `toolnexus.translate` must fire this hook exactly once for
  its single call, and a second copy of the rule in that namespace is exactly
  the drift the ports exist to prevent.

  Ordering (SPEC.md §8 Gap 1, and it IS the spec):

    base body -> beforeLLM hook -> :request-params merge -> :body-transform
              -> marshal -> wire

  so this runs BEFORE the body is assembled, and its `tools` replacement feeds
  the same empty-list omission (Gap 5) the toolkit's own list does."
  [client messages tools turn]
  (if-let [f (hook-of client :before-llm)]
    (let [ov (f {:messages messages :tools tools :model (:model client) :turn turn})
          m  (:model ov)]
      [(or (:messages ov) messages) (or (:tools ov) tools)
       (if (and (string? m) (not= "" m)) m (:model client))])
    [messages tools (:model client)]))

(defn after-llm!
  "§8 `afterLLM` — observe only (logging, cost, tracing). `:response` is the
  provider's decoded payload, so it carries `usage`. Returns nil; a return value
  from an observer would be a silent contract nobody could rely on."
  ([client response turn] (after-llm! client response turn (:model client)))
  ([client response turn model]
   (when-let [f (hook-of client :after-llm)]
     (f {:response response :model model :turn turn}))
   nil))

(defn- system-message
  "§0.10 — system = systemPrompt + \"\\n\\n\" + skillsPrompt. Empty parts are
  dropped, so a toolkit with no described skills yields just the system prompt
  (and no leading blank lines)."
  [client toolkit]
  (->> [(:system-prompt client) (:skills-prompt toolkit)]
       (map #(or % ""))
       (remove str/blank?)
       (str/join "\n\n")))

;; ---------------------------------------------------------------------------
;; transport
;; ---------------------------------------------------------------------------

(defn- endpoint
  "openai  -> {base}/chat/completions
   anthropic -> {base}/messages when base already ends in /v1, else {base}/v1/messages"
  [client]
  (let [base (trim-slashes (:base-url client))]
    (if (= "anthropic" (:style client))
      (if (str/ends-with? base "/v1") (str base "/messages") (str base "/v1/messages"))
      (str base "/chat/completions"))))

(defn- request-headers [client]
  (merge (if (= "anthropic" (:style client))
           {"x-api-key" (resolve-key client) "anthropic-version" "2023-06-01"}
           {"authorization" (str "Bearer " (resolve-key client))})
         (:headers client)))

(def ^:private forbidden-request-params
  "§client-request-shaping — keys `:request-params` may NOT set. They are owned
  by the loop: `messages` is the conversation it is maintaining, `tools` is the
  toolkit's schema, `stream` decides which code path runs. Rewriting messages is
  what `:body-transform` is for, which sees the assembled body and can do it
  deliberately rather than by collision."
  #{:messages :tools :stream})

(defn- canonical-param-key
  "A `:request-params` key in the body's own spelling: a string that round-trips
  as a plain keyword becomes that keyword; anything else (a keyword, or a string
  like \"a/b\" that a keyword would split) is kept as given."
  [k]
  (if (string? k)
    (let [kw (keyword k)]
      (if (and (nil? (namespace kw)) (= k (name kw))) kw k))
    k))

(defn- with-cache-control [v mark]
  (if (map? v) (assoc v :cache_control mark) v))

(defn- mark-last [xs mark]
  (let [v (vec xs)]
    (if (seq v) (assoc v (dec (count v)) (with-cache-control (peek v) mark)) v)))

(defn ^:no-doc apply-prompt-cache
  "SPEC §4C prompt caching — mark the cacheable prefix of an assembled body.

    anthropic  `cache_control` {type:\"ephemeral\"} (+ ttl \"1h\" with :long-ttl)
               on the last tool, the system prompt (a string becomes one text
               block) and the last content block of the latest message — 3 of
               the 4 breakpoints the provider allows
    openai     `prompt_cache_key` = :key, when set (the prefix caches itself)
    gemini     nothing yet

  Values are immutable here, so the transcript cannot be mutated by this."
  [body style opts]
  (if (= "anthropic" style)
    (let [mark (cond-> {:type "ephemeral"} (:long-ttl opts) (assoc :ttl "1h"))
          sys  (:system body)
          msgs (:messages body)]
      (cond-> body
        (seq (:tools body)) (assoc :tools (mark-last (:tools body) mark))
        (and (string? sys) (not= "" sys)) (assoc :system [{:type "text" :text sys :cache_control mark}])
        (and (sequential? sys) (seq sys)) (assoc :system (mark-last sys mark))
        (seq msgs)
        (assoc :messages
               (let [v    (vec msgs)
                     last (peek v)
                     c    (when (map? last) (:content last))]
                 (cond
                   (string? c) (assoc v (dec (count v)) (assoc last :content [{:type "text" :text c :cache_control mark}]))
                   (and (sequential? c) (seq c)) (assoc v (dec (count v)) (assoc last :content (mark-last c mark)))
                   :else v)))))
    (if (and (string? (:key opts)) (not= "" (:key opts)))
      (assoc body :prompt_cache_key (:key opts))
      body)))

(defn- shape-body
  "§client-request-shaping — the ordering contract, and the order is the spec:

     base body -> :request-params merge -> :body-transform -> marshal -> wire

  `:request-params` is a SHALLOW merge applied AFTER the client builds its own
  keys, and a param WINS on collision — that is what makes it useful, e.g.
  overriding the anthropic style's built-in max_tokens 4096.

  `:body-transform` runs LAST and its return value is what is sent, so it can
  drop keys the merge added.

  With neither set the body is unchanged, which is the spec's own guarantee and
  the reason both are applied through `cond->` rather than always-on `merge`.

  Param keys are CANONICALISED first (`canonical-param-key`): the body is built
  with keyword keys, so a string \"model\" (the agent Loop's per-call override, or
  any caller's) must REPLACE `:model`, not sit beside it — two keys that marshal
  to the same JSON name put `model` on the wire twice. Canonicalising before the
  forbidden-key filter also makes a string \"messages\" as forbidden as `:messages`."
  [client body]
  (let [params (:request-params client)
        params (when (seq params)
                 (into {} (map (fn [[k v]] [(canonical-param-key k) v])) params))
        params (when (seq params)
                 (let [kept (apply dissoc params forbidden-request-params)]
                   (when (not= (count kept) (count params))
                     (println "toolnexus: ignoring request-params"
                              (vec (sort (filter forbidden-request-params (keys params))))
                              "— owned by the client loop; use :body-transform"))
                   kept))
        ;; SPEC §4C: the prompt cache marks go on BEFORE :request-params and
        ;; :body-transform, so both can still override them.
        body   (if-let [pc (:prompt-cache client)] (apply-prompt-cache body (:style client) pc) body)
        merged (if (seq params) (merge body params) body)]
    (if-let [f (:body-transform client)] (f merged) merged)))

(defn- body-map [client model system messages tools]
  (shape-body
   client
   (if (= "anthropic" (:style client))
     (cond-> {:model model :max_tokens 4096 :system system :messages messages}
       (seq tools) (assoc :tools tools))
     (cond-> {:model model :messages messages}
       (seq tools) (assoc :tools tools :tool_choice "auto")))))

(def ^:private default-retryable-statuses
  "The default retryable set: `429` plus the 5xx worth another try — and `529
  Overloaded`, which TypeSafe documents as \"retry with backoff\" and which the
  older five-status set made terminal on the first attempt.

  It is an ENUMERATION on purpose. \"Any 5xx\" would sweep in permanently-broken
  statuses (`501 Not Implemented`, `505 HTTP Version Not Supported`) and change
  the retry behaviour of every existing host without asking. A backend with its
  own transient status — a Cloudflare origin answering `520`–`527`, say — opts
  in declaratively through `:retryable-statuses`, which ADDS to this set and
  cannot remove from it.

  Shared with §8B's classifier, which adds `408` to it rather than inventing a
  second policy."
  #{429 500 502 503 504 529})

(defn ^:no-doc retryable-status?
  "§resilience-policy — whether a status is retryable by default, optionally
  widened by a host's `:retryable-statuses`.

  `extra` is ADDITIVE: it can only make more statuses retryable, never fewer, so
  a host cannot accidentally drop `429` and lose `Retry-After` handling with it.
  It decides the DEFAULT classification only — `:on-error` still runs afterwards
  and has the final say on every attempt, so `:on-error` returning `:fail`
  overrides a status the host itself listed here.

  INTERNAL. A Clojure var has no package-private tier, so `^:no-doc` is the
  marker: this is not API, it is not in the parity-checked option surface, and
  it is public only because SPEC §8B's `toolnexus.classifier` reuses this policy
  verbatim rather than shipping a second one."
  ([status] (retryable-status? status nil))
  ([status extra]
   (boolean (and status
                 (or (contains? default-retryable-statuses status)
                     (and extra (some #(= % status) extra)))))))

(def ^:private retry-after-max-seconds
  "~68 years; the widest whole-second count all seven ports represent exactly."
  2147483647)

(defn ^:no-doc retry-after-ms
  "Honour a `Retry-After` header when the server sends one.

  INTERNAL (`^:no-doc`), not API. It is a var rather than a private fn only
  because SPEC §8B's `toolnexus.classifier` honours the SAME rule and calls this
  instead of carrying its own copy: §8B says the classifier reuses the §8
  Retry-After delay-seconds rule verbatim, and two implementations of
  \"verbatim\" is how they stop being the same. Seconds only: the
  HTTP-date form needs date parsing, which is not portable across these two
  hosts without reaching past koine, and a server that sends it gets our
  backoff instead of a wrong answer.

  Read through `koine.http/header`, not `get`: the two hosts' clients used to
  disagree about the case of the names they hand back, so trying two spellings
  was a guess that happened to cover the two known ones. koine 0.10.0 lowercases
  on every host and `header` is the one lookup that cannot be mis-cased."
  [res]
  (let [h (http/header res "retry-after")
        digits (when h (re-matches #"[0-9]+" (str/trim (str h))))
        ;; `parse-long` answers nil for a digit string wider than a long, and
        ;; `(* 1000 nil)` throws — so a server sending an absurd value used to kill
        ;; the run from inside the retry path. Range-check before multiplying.
        n (some-> digits parse-long)]
    (when (and n (<= 0 n retry-after-max-seconds))
      (* 1000 n))))

(def ^:private account-identifier-keys
  "The fields an upstream routinely echoes back in an error body that identify
  the ACCOUNT rather than the failure. §8's credentials guarantee now covers
  error messages too (D5): a body is diagnostics, not a place to learn who is
  paying."
  ["user_id" "account_id" "org_id" "organization"])

(def ^:private redacted-marker "«redacted»")

(defn ^:no-doc redact-body
  "An upstream error body with every account identifier's VALUE replaced.

  A CAP IS NOT REDACTION — the leaking body measured in #91 is 96 bytes, so
  truncating at 200 hid nothing. Redaction is applied to the typed `:body` AND
  to the message; the 200-character cap applies to the MESSAGE ONLY (addendum
  A5), because a host that opted into a typed error asked for the whole thing.

  INTERNAL (`^:no-doc`): public only because §8B's classifier applies the SAME
  policy and must not carry a second copy of it."
  [body]
  (reduce (fn [acc k]
            (str/replace acc
                         (re-pattern (str "(\"" k "\"\\s*:\\s*)(\"[^\"]*\"|[^,}\\s]+)"))
                         (fn [m] (str (nth m 1) "\"" redacted-marker "\""))))
          (str body)
          account-identifier-keys))

(defn ^:no-doc safe-body
  "The body as it may be SEEN: blank on an authentication status, redacted
  otherwise. A 401/403 body routinely reflects the credential or the header that
  was sent, so it never reaches a log, a metric, an error or a return value —
  the classifier's rule, lifted to the §8 path (D5)."
  [status body]
  (if (or (= 401 status) (= 403 status)) "" (redact-body body)))

(defn ^:no-doc cap-message
  "The 200-character cap, MESSAGE ONLY (addendum A5)."
  [s]
  (let [s (str/trim (str s))]
    (cond (str/blank? s) ""
          (> (count s) 200) (str (subs s 0 200) "…")
          :else s)))

(defn provider-error
  "The typed §8 provider failure: an ex-info whose DATA carries `:status`,
  `:body` (fully redacted) and `:retry-after` (the RAW `Retry-After` header
  verbatim, absent when the response sent none), and whose MESSAGE carries
  the capped, redacted body.

  `:retry-after` is deliberately NOT pre-parsed: an HTTP-date, fractional or
  out-of-range value is information the response really supplied, and a numeric
  field would have to drop it. The library's waiting rule (`retry-after-ms`,
  delay-seconds only, falling back to backoff) is separate and unchanged.

  Before this, a host could only regex the message — and the message was the raw
  body, account identifiers and all (#91/#92, ADR 0027)."
  [status body retry-after]
  (let [safe (safe-body status body)]
    (ex-info (str "LLM " status (when (seq (cap-message safe)) (str ": " (cap-message safe))))
             (cond-> {:toolnexus/error :provider :status status :body safe}
               retry-after (assoc :retry-after retry-after)))))

(defn timeout-error
  "The typed whole-run deadline failure. `:timeout-ms` is the WHOLE-RUN deadline
  §8 specifies, and the message NAMES the budget that stopped the run."
  [timeout-ms elapsed-ms]
  (ex-info (str "LLM run timeout after " elapsed-ms "ms (timeoutMs budget " timeout-ms "ms)")
           {:toolnexus/error :timeout :limit "timeout" :timeout-ms timeout-ms}))

(defn ^:no-doc with-deadline
  "Stamp the whole-run deadline onto the client for this run. Absent
  `:timeout-ms` ⇒ the client is returned untouched and nothing changes."
  [client]
  (if-let [ms (:timeout-ms client)]
    (assoc client ::deadline (+ (ktime/now-ms) ms))
    client))

(defn- remaining-ms
  "Milliseconds left on the run deadline, or nil when there is none. A stamped
  deadline is the ONLY source; an unstamped client (a single `call-provider`,
  say) still bounds the one call by the raw `:timeout-ms`."
  [client]
  (if-let [d (::deadline client)]
    (- d (ktime/now-ms))
    (:timeout-ms client)))

(defn- post-llm
  "The one HTTP call. `:http-client` lets a host supply the transport — for a
  proxy, mTLS, or credentials this library must never see — and it takes the
  same (url headers body) shape as koine.http/post-json so wrapping the default
  is a one-liner. Absent, the default is used and nothing changes.

  Scope is the LLM path only, per the capability spec: MCP transports are their
  own seam and are NOT routed through this."
  [client url headers body]
  (if-let [f (:http-client client)]
    (f url headers body)
    ;; `request` rather than `post-json`, because only `request` takes
    ;; :timeout-ms. koine classifies a timeout as DATA ({:status nil :error
    ;; :timeout}), never a throw, which is what lets the retry loop below treat
    ;; it as one more retryable failure instead of a host-specific exception.
    ;; The per-call timeout is the REMAINING run budget, never the whole budget
    ;; again: `:timeout-ms` is the WHOLE-RUN deadline (§8 `timeoutMs`), and
    ;; handing it to every attempt turned a 30s budget into 30s PER RETRY.
    (let [remaining (remaining-ms client)]
      (http/request (cond-> {:method :post :url url :headers headers :body body}
                      remaining (assoc :timeout-ms (max 1 remaining)))))))

(defn ^:no-doc classify
  "§resilience-policy — retry | fail, and NOTHING ELSE.

  INTERNAL (`^:no-doc`), not API, for the same reason as `retry-after-ms`: SPEC
  §8B's classifier reuses this `ErrorInfo -> verdict` policy rather than
  shipping a second one. It reads
  only `:on-error` off its first argument, so a classifier options map answers
  it exactly as a client does. The archived spec is
  explicit that this capability does not add a failure-originated suspend tier:
  §10 suspension stays a user-action pause, so an LLM failure can never become
  one here.

  With no `:on-error` the default is today's behaviour — retryable set retries,
  everything else fails. A host verdict overrides the default in EITHER
  direction, but `:retries` still bounds it: a classifier that could loop
  unbounded would be a denial-of-service on the caller's own bill."
  [client info]
  (let [default (if (:retryable? info) :retry :fail)]
    (if-let [f (:on-error client)]
      (if (= :retry (f info)) :retry :fail)
      default)))

(defn- post-with-retry
  "One LLM round trip, with the §resilience-policy retry loop around it. A
  transport failure and a non-2xx both become a thrown ex-info — unlike a TOOL
  error, an LLM failure is not something the model can be shown and asked to
  retry. `body` is the already-marshalled request string."
  [client url headers body model]
  (let [budget  (or (:retries client) 0)
        base-ms (or (:retry-base-ms client) 500)]
    (loop [attempt 0]
      ;; §8 — the WHOLE-RUN deadline, checked before every attempt. This port had
      ;; no run-level deadline at all: `:timeout-ms` bounded ONE http call and a
      ;; timed-out call was then RETRIED, so a client asking for a 30s budget
      ;; could spend `retries × 30s` (#91/#92, ADR 0027). An abort is never
      ;; retried, and it bypasses `:on-error` — a deadline is not a classifiable
      ;; upstream failure, it is the caller's own limit arriving.
      (when-let [left (and (::deadline client) (remaining-ms client))]
        (when (<= left 0)
          (throw (timeout-error (:timeout-ms client) (:timeout-ms client)))))
      (let [t0      (ktime/now-ms)
            res     (post-llm client url headers body)
            failed? (http/failed? res)
            status  (:status res)
            ok?     (and (not failed?) status (<= 200 status) (< status 300))]
        (if ok?
          (do (metric! client
                       (fn [] {:event "llm" :model model :status status
                               :ms (- (ktime/now-ms) t0)
                               :prompt_tokens (get-in res [:usage :prompt_tokens])
                               :completion_tokens (get-in res [:usage :completion_tokens])}))
              (json/read-str (:body res)))
          (let [timed-out? (= :timeout (:error res))
                info    {:error     (if failed? (:error res) (safe-body status (:body res)))
                         :status    status
                         :attempt   attempt
                         ;; A transport failure has no status and is retryable —
                         ;; EXCEPT a timeout, which is the run's own deadline
                         ;; expiring. Retrying it is retrying past the budget the
                         ;; caller set (D5).
                         :retryable? (boolean (and (not timed-out?)
                                                   (or failed?
                                                       (retryable-status?
                                                        status (:retryable-statuses client)))))}
                verdict (if timed-out? :fail (classify client info))
                throw!  (fn []
                          (cond
                            timed-out?
                            (throw (timeout-error (:timeout-ms client)
                                                  (- (ktime/now-ms) t0)))

                            failed?
                            (throw (ex-info (str "LLM transport " (name (:error res)))
                                            {:toolnexus/error :transport :error (:error res)}))

                            :else
                            (throw (provider-error status (:body res) (http/header res "retry-after")))))]
            (if (and (= :retry verdict) (< attempt budget))
              (do (ktime/sleep! (or (retry-after-ms res)
                                    ;; exponential backoff + jitter, identical to
                                    ;; the other six ports: base * 2^attempt plus
                                    ;; a uniform 0..99ms so a fleet retrying the
                                    ;; same upstream does not re-collide in lockstep.
                                    (+ (* base-ms (bit-shift-left 1 attempt))
                                       (rand-int 100))))
                  (recur (inc attempt)))
              (throw!))))))))

(defn wire-opts
  "The §1B/§8A shaping options every wire build shares."
  [client]
  {:style (:style client)
   :on-unsupported-part (:on-unsupported-part client)
   :max-part-bytes (:max-part-bytes client)})

(defn user-message
  "§1B — the ONE place a caller's prompt becomes a message in this port.

  `run` is fixed 3-arity (`ctx` is required), so a new arity is impossible and
  the dispatch is on the VALUE: a string is the pre-0.17 path and is stored
  verbatim, byte-identical; a sequence is a ContentPart list and is stored as the
  canonical transcript shape, which `content/build-wire` encodes on the way out."
  [prompt]
  {:role "user"
   :content (if (sequential? prompt) (vec prompt) prompt)})

(defn- model-of [shaped fallback]
  ;; :request-params keys are canonicalised to keywords (shape-body), so a string
  ;; "model" here can only come from a :body-transform — and then it is the one
  ;; the transform chose to send.
  (let [m (if (contains? shaped "model") (get shaped "model") (get shaped :model))]
    (if (string? m) m fallback)))

(defn- llm-call
  "The agent loop's round trip: build the loop's body, then post it."
  [client model system messages tools]
  (let [shaped (body-map client model system messages tools)
        ;; §8 — report the model TRANSMITTED: a :request-params / :body-transform
        ;; model (the agent Loop's per-call override) wins over the hook's.
        sent   (model-of shaped model)]
    [(post-with-retry client
                      (endpoint client)
                      (merge {"content-type" "application/json"} (request-headers client))
                      (json/write-str shaped)
                      sent)
     sent]))

(defn call-provider*
  "`call-provider`, returning `[decoded-response transmitted-model]` — the model
  the shaped body actually carried (§8/§11: the model reported is the model sent)."
  [client body]
  (let [shaped (shape-body client body)
        m      (model-of shaped (or (:model body) (:model client)))]
    [(post-with-retry client
                      (endpoint client)
                      (merge {"content-type" "application/json"} (request-headers client))
                      (json/write-str shaped)
                      m)
     m]))

(defn call-provider
  "ONE provider round trip for a CALLER-BUILT body map, through exactly the
  endpoint, headers, §resilience-policy retry/backoff and `llm` metric the agent
  loop uses. §8's `:request-params` merge and `:body-transform` apply unchanged.

  This is the seam §11 `toolnexus.translate` sits on: single-turn translation
  must lose neither resilience nor metrics, and the only way to guarantee that
  is to share the code rather than copy it."
  [client body]
  (first (call-provider* client body)))

;; ---------------------------------------------------------------------------
;; per-style response reading
;; ---------------------------------------------------------------------------

(defn- parse-args [s]
  (try (or (json/read-str (if (str/blank? (str s)) "{}" (str s))) {})
       (catch Throwable _ {})))

(defn- openai-calls [body]
  (mapv (fn [tc] {:id   (:id tc)
                  :name (get-in tc [:function :name])
                  :args (parse-args (get-in tc [:function :arguments]))})
        (get-in body [:choices 0 :message :tool_calls])))

(defn- anthropic-calls [body]
  (->> (:content body)
       (filter #(= "tool_use" (:type %)))
       (mapv (fn [b] {:id (:id b) :name (:name b) :args (or (:input b) {})}))))

(defn- tool-calls-of [client body]
  (if (= "anthropic" (:style client)) (anthropic-calls body) (openai-calls body)))

(defn- assistant-message
  "The assistant turn, appended to the transcript verbatim in the provider's own
  shape (its keys came off the wire, so they go back onto it unchanged)."
  [client body]
  (if (= "anthropic" (:style client))
    {:role "assistant" :content (:content body)}
    (get-in body [:choices 0 :message])))

(defn- final-text [client body]
  (if (= "anthropic" (:style client))
    (->> (:content body) (filter #(= "text" (:type %))) (map :text) (str/join))
    (or (get-in body [:choices 0 :message :content]) "")))

(defn- tool-result-messages
  "The provider's tool-result shape. THIS is the part the two styles disagree
  about: OpenAI wants one `tool` message per call; Anthropic wants ONE `user`
  message carrying a `tool_result` block per call."
  [client settled]
  (if (= "anthropic" (:style client))
    [{:role "user"
      :content (mapv (fn [s] (cond-> {:type "tool_result"
                                      :tool_use_id (:id s)
                                      :content (:output (:result s))
                                      :is_error (boolean (:isError (:result s)))}
                               ;; §1B/§8A: parts ride on the CANONICAL transcript
                               ;; under `:parts`; `toolnexus.content/build-wire`
                               ;; is the only thing that turns them into blocks.
                               ;; `cond->`, so a text-only result is byte-identical.
                               (seq (:parts (:result s))) (assoc :parts (vec (:parts (:result s))))))
                     settled)}]
    (mapv (fn [s] (cond-> {:role "tool" :tool_call_id (:id s) :content (:output (:result s))}
                    ;; `:name` is carried ONLY when there are parts — the §8A
                    ;; relocation header needs it, and the wire build strips both.
                    (seq (:parts (:result s))) (assoc :name (str (:name s))
                                                      :parts (vec (:parts (:result s))))))
          settled)))

(def zero-usage
  "The empty §8 Usage. Idiomatic kebab-case — Usage is a RETURN value, it never
  crosses the wire."
  {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})

(defn add-usage
  "Sum token usage across turns. OpenAI prompt/completion/total_tokens;
  Anthropic input_tokens -> prompt, output_tokens -> completion. Public because
  §11 translation reports the same Usage from the same provider payloads."
  [acc client raw]
  (if-not raw
    acc
    ;; SPEC §4C prompt caching: `:cache-read-tokens` / `:cache-write-tokens`,
    ;; present only once a provider reports one, so an uncached Usage is the same
    ;; map as before. Under anthropic `:prompt-tokens` EXCLUDES both (the context
    ;; sent is prompt + cache-read + cache-write); under openai it already
    ;; INCLUDES the cached part.
    (let [add (fn [m k n] (if (and (number? n) (pos? n)) (update m k (fnil + 0) n) m))]
      (if (= "anthropic" (:style client))
        (let [p (or (:input_tokens raw) 0) c (or (:output_tokens raw) 0)]
          (-> acc
              (update :prompt-tokens + p)
              (update :completion-tokens + c)
              (update :total-tokens + (+ p c))
              (add :cache-read-tokens (:cache_read_input_tokens raw))
              (add :cache-write-tokens (:cache_creation_input_tokens raw))))
        (let [p (or (:prompt_tokens raw) 0) c (or (:completion_tokens raw) 0)]
          (-> acc
              (update :prompt-tokens + p)
              (update :completion-tokens + c)
              (update :total-tokens + (or (:total_tokens raw) (+ p c)))
              (add :cache-read-tokens (get-in raw [:prompt_tokens_details :cached_tokens]))))))))

;; ---------------------------------------------------------------------------
;; SPEC §4C — cancellation, tool context, tool tiers
;; ---------------------------------------------------------------------------

(defn- cancel-reason
  "The run's cancel token (`:cancel` on the run ctx — an atom, cancelled once it
  holds a truthy value, or a 0-arg fn), read without blocking."
  [token]
  (cond (nil? token) nil
        (fn? token)  (token)
        :else        @token))

(defn cancelled-error
  "The typed cancellation failure a cancelled run throws."
  [reason]
  (ex-info (str "cancelled" (when (string? reason) (str ": " reason)))
           {:toolnexus/error :cancelled :reason reason}))

(defn- check-cancel! [client]
  (when-let [r (cancel-reason (::cancel client))]
    (throw (cancelled-error r))))

(defn- tool-context
  "The ToolContext a loop-run tool sees: the model's call id, plus the run's
  cancel token when there is one (and `:answer` on a §10 re-run)."
  [client call-id]
  (cond-> {:call-id (str (or call-id ""))}
    (::cancel client) (assoc :cancel (::cancel client))))

(defn- tool-def
  "A tool as tool_search shows it — the shape the Go reference marshals."
  [t]
  {"name" (:name t) "description" (or (:description t) "") "inputSchema" (:input-schema t)})

(defn- default-count-tokens [v]
  (quot (+ (builtin/utf8-count (json/write-str v)) 3) 4))

(defn ^:no-doc first-line
  "First line of `s`, cut to the longest valid UTF-8 prefix of at most 120
  BYTES, as Go does — never a split character. A cut at `j` splits a
  surrogate pair exactly when the two halves' byte counts do not add up to
  the whole, which needs no host char interop."
  [s]
  (let [l     (str (first (str/split (str s) #"\n" 2)))
        total (builtin/utf8-count l)]
    (if (<= total 120)
      l
      (loop [j (min (count l) 120)]
        (let [head (subs l 0 j)
              n    (builtin/utf8-count head)]
          (if (and (<= n 120) (= total (+ n (builtin/utf8-count (subs l j)))))
            (str head "…")
            (recur (dec j))))))))

(defn- plan-lists [tier]
  (let [of (fn [t] (tool/sort-strings (keep (fn [[n x]] (when (= x t) n)) tier)))]
    {:core (of "core") :listed (of "listed") :deferred (of "deferred")}))

(defn- meta-tools
  "tool_search (whose description lists the listed + deferred tools) and call_tool."
  [lists by-name]
  (let [listed   (:listed lists)
        deferred (:deferred lists)
        desc     (str "Load the full schema of tools that are not shown in full, then call them by "
                      "name (or with call_tool). Query by keywords, or \"select:a,b\" for exact names."
                      (when (seq listed)
                        (str "\n\nAvailable tools:"
                             (apply str (map (fn [n]
                                               (let [d (first-line (:description (get by-name n)))]
                                                 (str "\n- " n (when (not= "" d) (str " — " d)))))
                                             listed))))
                      (when (seq deferred)
                        (str "\n\nMore tools (name only): " (str/join ", " deferred))))]
    [{:name "tool_search" :description desc :source "builtin"
      :hints {:read-only true :destructive false :open-world false}
      :input-schema {:type "object"
                     :properties {:query {:type "string" :description "Keywords, or select:name1,name2"}
                                  :limit {:type "number" :description "Maximum results for a keyword query (default 5)"}}
                     :required ["query"]
                     :additionalProperties false}}
     {:name "call_tool" :description "Call a tool loaded with tool_search, by name, with its arguments."
      :source "builtin"
      :input-schema {:type "object"
                     :properties {:name {:type "string"} :arguments {:type "object"}}
                     :required ["name"]
                     :additionalProperties false}}]))

(defn ^:no-doc build-tier-plan
  "SPEC §4C — the tier assignment for `tools`: {:tier {name tier} :core :listed
  :deferred :search :call}. Core tools are named in `:core`, deferred in
  `:deferred`, everything else is listed. Over the budget (`:budget-tokens`,
  else 5% of `:context-window`), the least-used core tool moves to listed (ties:
  larger schema first, then name), then the least-used listed tool to deferred,
  until the core schemas plus the two meta tools fit."
  [opts tools]
  (let [count-fn (or (:count-tokens opts) default-count-tokens)
        usage    (fn [n] (if-let [f (:usage-count opts)] (or (f n) 0) 0))
        by-name  (into {} (map (fn [t] [(:name t) t]) tools))
        tier0    (as-> (into {} (map (fn [t] [(:name t) "listed"]) tools)) m
                   (reduce (fn [m n] (if (contains? m (str n)) (assoc m (str n) "deferred") m)) m (:deferred opts))
                   (reduce (fn [m n] (if (contains? m (str n)) (assoc m (str n) "core") m)) m (:core opts)))
        build    (fn [tier]
                   (let [lists (plan-lists tier)
                         [search call] (meta-tools lists by-name)]
                     (assoc lists :tier tier :search search :call call)))
        budget   (or (let [b (:budget-tokens opts)] (when (and (number? b) (pos? b)) b))
                     (let [w (:context-window opts)] (when (and (number? w) (pos? w)) (quot w 20))))
        cost     (fn [p] (reduce + (+ (count-fn (tool-def (:search p))) (count-fn (tool-def (:call p))))
                                 (map (fn [n] (count-fn (tool-def (get by-name n)))) (:core p))))
        order    (fn [names]
                   (sort (fn [a b]
                           (let [ua (usage a) ub (usage b)]
                             (if (not= ua ub)
                               (compare ua ub)
                               (let [sa (count-fn (tool-def (get by-name a)))
                                     sb (count-fn (tool-def (get by-name b)))]
                                 (if (not= sa sb) (compare sb sa) (tool/compare-strings a b))))))
                         names))
        demote   (fn [p from to]
                   (reduce (fn [p n] (if (<= (cost p) budget) (reduced p) (build (assoc (:tier p) n to))))
                           p (order (get p from))))]
    (cond-> (build tier0)
      budget (-> (demote :core "listed") (demote :listed "deferred")))))

(defn- tier-key [toolkit] (or (tool/toolkit-id toolkit) ::anonymous))

(defn- tier-plan
  "The frozen plan for `toolkit`, built on its first request. Nil without tiers."
  [client toolkit]
  (when-let [opts (:tool-tiers client)]
    (let [a (::tier-plans client)
          k (tier-key toolkit)]
      (or (get @a k)
          (get (swap! a (fn [m] (if (contains? m k) m (assoc m k (build-tier-plan opts (tool/all-tools toolkit)))))) k)))))

(defn tool-tier-plan
  "SPEC §4C — tool name → \"core\" | \"listed\" | \"deferred\" for `toolkit` (built on
  first use, then frozen), for a host UI or log. Nil when :tool-tiers is unset."
  [client toolkit]
  (:tier (tier-plan client toolkit)))

(defn reset-tool-tiers!
  "Forget the frozen plan for `toolkit` (or every plan) so the next request
  rebuilds it — call at a new session start. Mid-session it breaks the cached
  prefix."
  ([client] (when-let [a (::tier-plans client)] (reset! a {})) nil)
  ([client toolkit] (when-let [a (::tier-plans client)] (swap! a dissoc (tier-key toolkit))) nil))

(defn ^:no-doc request-tools
  "The tools a request offers: every tool, or with tiers the core tools (sorted)
  then tool_search and call_tool."
  [client toolkit]
  (if-let [p (tier-plan client toolkit)]
    (-> (vec (keep (fn [n] (get (:tools toolkit) n)) (:core p)))
        (conj (:search p) (:call p)))
    (tool/all-tools toolkit)))

(defn- unwrap-call
  "call_tool {name, arguments} as the real call: [name args wrapped?]."
  [client nm args]
  (if (and (:tool-tiers client) (= "call_tool" nm))
    [(str (or (:name args) "")) (let [a (:arguments args)] (if (map? a) a {})) true]
    [nm args false]))

(defn ^:no-doc tool-search
  "tool_search against every tool in `toolkit`, whatever its tier: `select:a,b`
  for exact names, else keyword scoring (name match 3, description match 1)."
  [toolkit args]
  (let [q (str/trim (str (or (:query args) "")))]
    (if (= "" q)
      (tool/failure "tool_search: query is required")
      (let [picked (if (str/starts-with? q "select:")
                     (let [ns      (remove #(= "" %) (map str/trim (str/split (subs q 7) #",")))
                           found   (vec (keep #(get (:tools toolkit) %) ns))
                           missing (vec (remove #(get (:tools toolkit) %) ns))]
                       (if (and (seq missing) (empty? found))
                         {:error (str "tool_search: no such tool: " (str/join ", " missing))}
                         found))
                     (let [limit (let [l (:limit args)] (if (and (number? l) (>= l 1)) (long l) 5))
                           terms (remove #(= "" %) (str/split (str/lower-case q) #"\s+"))
                           hits  (keep (fn [t]
                                         (let [nm (str/lower-case (:name t))
                                               d  (str/lower-case (str (:description t)))
                                               sc (reduce + (map (fn [term] (+ (if (str/includes? nm term) 3 0)
                                                                               (if (str/includes? d term) 1 0)))
                                                                 terms))]
                                           (when (pos? sc) [sc t])))
                                       (tool/all-tools toolkit))]
                       (vec (take limit (map second (sort (fn [[sa ta] [sb tb]]
                                                            (if (not= sa sb) (compare sb sa)
                                                                (tool/compare-strings (:name ta) (:name tb))))
                                                          hits))))))]
        (cond
          (:error picked) (tool/failure (:error picked))
          (empty? picked) (tool/success "No matching tools." {:count 0})
          :else (tool/success (str "Loaded tools (call by name, or with call_tool):\n"
                                   (builtin/json-indent (mapv tool-def picked) "  "))
                              {:count (count picked) :tools (mapv :name picked)}))))))

;; ---------------------------------------------------------------------------
;; tool execution
;; ---------------------------------------------------------------------------
;;
;; §1 Context is OPTIONAL, and in Clojure "optional trailing argument" is arity,
;; not a nullable parameter: `toolnexus.tool/execute` calls `(f args)` when ctx
;; is nil and `(f args ctx)` when it is not. So a tool that wants `ctx.answer`
;; must be written multi-arity — `(fn ([args] ...) ([args ctx] ...))` — because
;; the FIRST execution has no context to pass. That is the portable contract
;; here; see the report.

(defn- execute-tool
  "ONE tool call, through the §8 `:before-tool` / `:after-tool` hooks.

    :before-tool -> {:result r}  SHORT-CIRCUITS: the real tool never runs
                                 (deny / cache hit / dry-run)
                 -> {:args a}    rewrites the call; those args are what runs,
                                 what the transcript records, and what a §10
                                 retry re-executes with
    :after-tool  -> {:result r}  replaces the output (redact, annotate)

  A SUSPENSION SKIPS `:after-tool` (js/src/client.ts: `if (h?.afterTool &&
  !pendingOf(result))`) — a pending Request is not a real result, and the
  resolved one still flows through the hook in `resolve-pending`.

  A tool that throws must not park the caller's deref forever, so the execution
  is wrapped: tool/execute already turns a throw into an isError result and the
  catch here covers the residue."
  [client toolkit call turn]
  (if (and (:tool-tiers client) (= "tool_search" (:name call)))
    ;; SPEC §4C: tool_search is engine-internal and read-only — no hooks.
    (assoc call :result (tool-search toolkit (:args call)))
    ;; call_tool is unwrapped HERE, so hooks, hints and permission gates see the
    ;; REAL tool. The recorded args keep the wrapper shape (with any :before-tool
    ;; rewrite inside), so a §10 re-run unwraps the same way.
    (let [[nm args0 wrapped?] (unwrap-call client (:name call) (:args call))
          t      (get (:tools toolkit) nm)
          before (hook-of client :before-tool)
          after  (hook-of client :after-tool)
          ov     (when before
                   (before (cond-> {:name nm :args args0 :id (:id call) :turn turn}
                             ;; SPEC §4C — the called tool's hints, for a permission gate
                             (:hints t) (assoc :hints (:hints t)))))
          rewrap (fn [a] (if wrapped? {:name nm :arguments a} a))]
      (if (:result ov)
        (assoc call :result (:result ov))
        (let [args   (or (:args ov) args0)
              result (try (tool/execute toolkit nm args
                                        (when (:context? t) (tool-context client (:id call))))
                          (catch Throwable e
                            (tool/failure (or (ex-message e) (str e)))))
              result (if (and after (not (pending-of result)))
                       (or (:result (after {:name nm :args args :result result
                                            :id (:id call) :turn turn}))
                           result)
                       result)]
          (assoc call :args (rewrap args) :result result))))))

(def default-max-tool-concurrency
  "The cap when neither :max-tool-concurrency nor TOOLNEXUS_MAX_TOOL_CONCURRENCY sets one."
  10)

(defn resolve-max-tool-concurrency
  "option > env TOOLNEXUS_MAX_TOOL_CONCURRENCY > 10. Non-positive or non-numeric
  values fall back. `get-env` is the lookup (default: the process env)."
  ([option] (resolve-max-tool-concurrency option env/get-env))
  ([option get-env]
   (if (and (integer? option) (pos? option))
     option
     (let [raw (some-> (get-env "TOOLNEXUS_MAX_TOOL_CONCURRENCY") str/trim)
           n   (when (and raw (re-matches #"\+?\d{1,9}" raw))
                 (parse-long (str/replace raw "+" "")))]
       (if (and n (pos? n)) n default-max-tool-concurrency)))))

(defn- call-safe?
  "One call's safety; looks through call_tool to the tool it names. Unknown ⇒ unsafe."
  [client toolkit c]
  (if (and (:tool-tiers client) (= "tool_search" (:name c)))
    true
    (let [[nm args _] (unwrap-call client (:name c) (or (:args c) {}))]
      (tool/concurrency-safe? (get (:tools toolkit) nm) (if (map? args) args {})))))

(defn- execute-calls
  "§8 — the tool calls of one turn, in parallel, with the results in CALL ORDER
  regardless of completion order. Order is not cosmetic: a scrambled transcript
  pairs the wrong result with the wrong tool_call_id and the model never sees a
  crash, only a lie.

  `koine.process/run-async!` + a promise, NOT `future`. This is library code
  running inside somebody else's process, and Clojure's future pool threads are
  non-daemon with a 60-second keep-alive — one tool call would hold a consumer's
  program open for a minute after it finished. `(shutdown-agents)` is the fix
  for an APPLICATION that owns its process (our test runner calls it); a library
  may never decide when its host program exits. run-async! is a daemon thread on
  the JVM and a goroutine on cljgo. The promise is how a fire-and-forget
  primitive gives a value back — run-async! discards its fn's return.

  NOTHING may park the caller's deref forever, so the body delivers on every
  exit including a throw. `tool/execute` converts a throwing TOOL into an isError
  result; the catch here covers the rest of the body — in particular the two §8
  tool hooks, which are host code and are invoked outside that conversion. A
  throw from a hook is rethrown on the calling thread, matching the shipped
  ports, where an unguarded hook rejects the run."
  ([client toolkit calls turn] (execute-calls client toolkit calls turn nil))
  ([client toolkit calls turn on-settled]
  ;; §8 "Tool scheduling": calls start in emitted order. A safe call starts once
  ;; fewer than `cap` are in flight (else it waits for the oldest in flight); an
  ;; unsafe call waits for every earlier call, runs alone, and finishes before
  ;; the next starts. Results are still read in call order below.
  (->> (let [cap   (resolve-max-tool-concurrency (:max-tool-concurrency client))
             start (fn [c]
               (let [p (promise)
                     t0 (ktime/now-ms)]
                 ;; EVERY exit from the body delivers, including a throw. Only
                 ;; `tool/execute` was guarded before, and both §8 tool hooks are
                 ;; invoked OUTSIDE that guard — so a host whose `:before-tool`
                 ;; threw killed this thread before `deliver` ran and parked the
                 ;; consumer on the `deref` below FOREVER. On the JVM that at
                 ;; least printed a stack trace from the dying thread; on cljgo it
                 ;; hung with no diagnostic at all.
                 (proc/run-async!
                  (fn []
                    (deliver p (try {:value (execute-tool client toolkit c turn)}
                                    (catch Throwable e {:thrown e})))))
                 [p t0]))]
         (loop [cs calls, started [], inflight []]
           (if-let [c (first cs)]
             (if (call-safe? client toolkit c)
               (let [inflight (vec (remove #(realized? (first %)) inflight))
                     inflight (if (>= (count inflight) cap)
                                (do (deref (first (first inflight))) (subvec inflight 1))
                                inflight)
                     pair     (start c)]
                 (recur (rest cs) (conj started pair) (conj inflight pair)))
               (do (doseq [pr inflight] (deref (first pr)))
                   (let [pair (start c)]
                     (deref (first pair))
                     (recur (rest cs) (conj started pair) []))))
             started)))
       (mapv (fn [pair]
               ;; Rethrow on the CALLING thread rather than converting to an
               ;; isError result: js/src/client.ts wraps neither hook, so a hook
               ;; that throws rejects `run()` and the host sees its own bug. A
               ;; tool that throws is still an isError result — that rule is
               ;; `tool/execute`'s and is unchanged. This is about the host's
               ;; code failing, not the tool's.
               (let [outcome (deref (first pair))
                     _       (when-let [e (:thrown outcome)] (throw e))
                     r       (:value outcome)]
                 ;; §client-observability — one `tool` event per call, emitted
                 ;; after the result lands so :ms and :is_error are real.
                 (metric! client
                          (fn [] {:event "tool"
                                  :tool (first (unwrap-call client (:name r) (:args r)))
                                  :source (:source (get (:tools toolkit) (first (unwrap-call client (:name r) (:args r)))))
                                  :is_error (boolean (:isError (:result r)))
                                  :ms (- (ktime/now-ms) (second pair))}))
                 ;; recorded as it lands, in call order, so an interrupted turn
                 ;; can still save the results that DID finish.
                 (when on-settled (on-settled r))
                 r))))))

(defn- emit! [on-event ev] (when on-event (on-event ev)) nil)


(defn- resolve-pending
  "§10 loop rule, for ONE suspended call.

  wait-for configured:
    ok       -> re-execute the SAME tool with the SAME args exactly once, with
                Context.answer = answer; that result goes back to the model.
                If the retry suspends AGAIN -> \"unresolved: <prompt>\" (never
                loop forever on one request).
    not ok   -> \"declined/expired: <prompt>\". The loop continues; the model
                decides what to do.
  wait-for absent:
    -> {:halt request}. The run does not hang.

  Resolution runs sequentially, in tool-call order, which is what makes
  concurrent suspensions surface deterministically (§10) instead of by
  scheduling. `wait-for` itself is a plain blocking (fn [request] answer): §10's
  async framing assumes a function-colouring problem Clojure does not have, so
  there is no channel, no core.async and no executor anywhere in this rule."
  [client toolkit call request on-event turn]
  (emit! on-event {:type "pending" :request request})   ; BEFORE wait-for runs
  (if-let [wait-for (if-let [f (:wait-for-ctx client)]
                      ;; SPEC §4C: the turn's context, so a pending ask can end
                      ;; with the turn. Wins over :wait-for.
                      (let [token (::cancel client)]
                        (fn [req] (f {:cancel token
                                      :cancelled? (fn [] (boolean (cancel-reason token)))
                                      :call-id (:id call) :turn turn}
                                     req)))
                      (:wait-for client))]
    (let [answer (as-answer (wait-for request))
          [nm args] (unwrap-call client (:name call) (:args call))]
      (if-not (:ok answer)
        {:result {:output (str "declined/expired: " (:prompt request)) :isError true}}
        ;; §8: the RESOLVED result is a real result, so `:after-tool` sees it
        ;; here — the suspension it replaces was skipped in `execute-tool`.
        (let [retried (tool/execute toolkit nm args
                                    (assoc (tool-context client (:id call)) :answer answer))
              retried (if-let [f (hook-of client :after-tool)]
                        (or (:result (f {:name nm :args args :result retried
                                         :id (:id call) :turn turn}))
                            retried)
                        retried)]
          (if (pending-of retried)
            {:result {:output (str "unresolved: " (:prompt request)) :isError true}}
            {:result retried}))))
    ;; §10 rule 2 — durable halt. The placeholder that enters the transcript is
    ;; the request's prompt (matching the five shipped ports), not the tool's
    ;; own output.
    {:result {:output (:prompt request) :isError true} :halt request}))

;; ---------------------------------------------------------------------------
;; RunResult
;; ---------------------------------------------------------------------------

(defn run-files
  "§8 `RunResult.files` — every non-text part the run's tool calls produced, in
  call order, each tagged `:from` (tool name) and `:callId` (the model's
  tool-call id). Always a vector."
  [tool-calls]
  (vec (for [c tool-calls
             p (:parts c)
             :when (not= "text" (:type p))]
         (assoc p :from (:name c) :callId (or (::call-id (meta c)) "")))))

(defn- run-result [client text messages tool-calls turns usage exhausted?]
  (let [incomplete? (and exhausted? (= "" text))]
    (cond-> {:text text
             :messages messages
             :tool-calls tool-calls
             :files (run-files tool-calls)
             :tool-call-count (count tool-calls)
             :turns turns
             :usage usage
             :model (:model client)
             :status (if incomplete? "incomplete" "done")}
      incomplete? (assoc :limit "maxTurns"))))

(defn- content-error-result
  "§8A — the run stopped before any HTTP call because a content part could not be
  sent. Reported in the vocabulary §8 already has: a non-`done` status plus a
  STRUCTURED `:limit` naming which limit stopped it, and `:text` carrying the
  human reason. No exception crosses the boundary; see `toolnexus.content`."
  [client err messages tool-calls turns usage]
  {:text (:error err)
   :messages messages
   :tool-calls tool-calls
   :files (run-files tool-calls)
   :tool-call-count (count tool-calls)
   :turns turns
   :usage usage
   :model (:model client)
   :status "incomplete"
   :limit "contentPart"
   :error {:code (:code err) :message (:error err)}})

(defn- pending-result [client request messages tool-calls turns usage]
  {:text (:prompt request)
   :messages messages
   :tool-calls tool-calls
   :files (run-files tool-calls)
   :tool-call-count (count tool-calls)
   :turns turns
   :usage usage
   :model (:model client)
   :status "pending"
   :pending request})

(def ^:private interrupted-result
  "The synthetic result an unanswered tool call gets when a turn is interrupted —
  so every call keeps its result in both dialects."
  {:isError true :output "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first."})

(defn- interrupted-transcript
  "The transcript as it stood when a turn was interrupted: every complete
  message, plus — when the last assistant message's tool calls had not all been
  answered — one tool result per call: the real one if it finished, else
  `interrupted-result`. A pending §10 placeholder is not a real result, so it
  counts as interrupted too."
  [client {:keys [messages calls done]}]
  (if (seq calls)
    (into (vec messages)
          (tool-result-messages
           client
           (mapv (fn [c]
                   (let [d (get done (:id c))]
                     (assoc c :result (if (and d (not (pending-of (:result d))))
                                        (:result d)
                                        interrupted-result))))
                 calls)))
    (vec messages)))

(defn- last-assistant-text [messages]
  (or (->> messages
           (filter (fn [m] (and (= "assistant" (:role m)) (string? (:content m)))))
           last
           :content)
      ""))

;; ---------------------------------------------------------------------------
;; the loop (§0.10)
;; ---------------------------------------------------------------------------

(defn run
  "Run the agent loop. `ctx` = {:toolkit tk :history [...] :on-event f}.

  Returns a RunResult:
    {:text :messages :tool-calls :tool-call-count :turns
     :usage {:prompt-tokens :completion-tokens :total-tokens}
     :model :status (\"done\"|\"pending\"|\"incomplete\") :limit? :pending?}

  `:on-event` is an optional synchronous sink for the §8 event vocabulary this
  non-streaming loop can honestly produce — `tool_call`, `tool_result`,
  `pending` (emitted BEFORE `wait-for` runs, so a channel handler can push the
  link in real time), `usage`, `done`. There are no `text` deltas here: this
  loop buffers the whole response, and faking deltas out of a buffered body
  would be a lie. Real SSE streaming is a separate seam (`koine.stream/sse-post`
  exists and is proven on both hosts) and is NOT implemented in this namespace."
  [client prompt {:keys [toolkit history on-event conversation-id cancel]}]
  ;; §8 `timeoutMs` is a WHOLE-RUN deadline, so it is stamped ONCE here and every
  ;; attempt in every turn is measured against that one instant.
  (let [client     (cond-> (with-deadline client)
                     ;; SPEC §4C: the run's cancel token, for wait-for-ctx and tools
                     cancel (assoc ::cancel cancel))
        anthropic? (= "anthropic" (:style client))
        system     (system-message client toolkit)
        ;; §0.7 schema comes from toolnexus.adapter — one source of truth for
        ;; the provider shapes, never a second copy in the loop. With SPEC §4C
        ;; tool tiers the list is the frozen plan's core tools + the meta tools.
        offered    (request-tools client toolkit)
        tools      (if anthropic? (adapter/to-anthropic offered) (adapter/to-openai offered))
        max-turns  (or (:max-turns client) default-max-turns)
        ;; §conversation-store — an explicit :history still wins; the store is
        ;; consulted only when a :conversation-id is given. Without an id a run
        ;; is one-shot and must not inherit a previous transcript.
        remembered (when (and conversation-id (:store client))
                     ((:get (:store client)) conversation-id))
        run-t0     (ktime/now-ms)
        ;; §conversation-store — the turn as it stands, for the interrupted-turn
        ;; save: {:messages complete-so-far :calls unanswered :done {id settled}}.
        progress   (atom nil)
        ;; ONE exit path for the store save and the run metric. Two `run-result`
        ;; call sites return from this loop, and a save wired into only one of
        ;; them is the classic half-fix — the turn-limit exit would silently
        ;; forget the conversation.
        run-metric! (fn [r]
                     (metric! client
                              (fn [] {:event "run" :model (:model r)
                                      :turns (:turns r) :tool_calls (:tool-call-count r)
                                      :total_tokens (get-in r [:usage :total-tokens])
                                      :ms (- (ktime/now-ms) run-t0)
                                      :error (:limit? r)}))
                     r)
        finish!    (fn [r]
                     (reset! progress nil)
                     (when (and conversation-id (:store client))
                       ((:save (:store client)) conversation-id (:messages r)))
                     (run-metric! r))
        seed       (cond
                     (seq history)    (vec history)
                     (seq remembered) (vec remembered)
                     ;; the anthropic style carries `system` in the body, not as
                     ;; a message; the openai style carries it as message 0.
                     (or anthropic? (str/blank? system)) []
                     :else [{:role "system" :content system}])]
    ;; `tools` is loop state, not a constant: §8 says a `:before-llm` hook that
    ;; returns `:messages`/`:tools` replaces them FOR THE REST OF THE RUN, so a
    ;; turn-0 override must still be in force on turn 1.
    (try
    (loop [messages   (conj seed (user-message prompt))
           tools      tools
           turn       0
           turns      0
           tool-calls []
           usage      zero-usage
           ;; §8 — RunResult.model / the `run` metric report the model of the
           ;; LAST call the run made (configured until a call is made).
           last-model (:model client)]
      (reset! progress {:messages messages})
      (check-cancel! client)
      (if (>= turn max-turns)
        (let [text (if anthropic? "" (last-assistant-text messages))
              r    (finish! (assoc (run-result client text messages tool-calls turns usage true) :model last-model))]
          (emit! on-event {:type "done" :result r})
          r)
        ;; §8 ordering: the hook sees (and may replace) the transcript and the
        ;; tool list BEFORE the body is assembled, so :request-params and
        ;; :body-transform both run downstream of it.
        (let [hooked  (before-llm! client messages tools turn)
              messages (first hooked)
              tools    (second hooked)
              turn-model (nth hooked 2)
              ;; §8A: the canonical transcript becomes wire messages HERE, so
              ;; `messages` (and therefore RunResult.messages, the store and
              ;; `translate`) never sees a synthetic user turn or a provider
              ;; block. A part the style cannot send stops the run before any
              ;; HTTP call, as DATA.
              wire    (content/build-wire messages (wire-opts client))]
          (if (:error wire)
            (let [r (finish! (assoc (content-error-result client wire messages tool-calls turns usage) :model last-model))]
              (emit! on-event {:type "done" :result r})
              r)
            (let [[body turn-model] (llm-call client turn-model system (:messages wire) tools)
                  usage   (add-usage usage client (:usage body))
                  _       (after-llm! client body turn turn-model)
                  _       (emit! on-event {:type "usage" :usage usage})
                  turns   (inc turns)
                  msg     (assistant-message client body)
                  calls   (tool-calls-of client body)
                  messages (conj messages msg)
                  _       (reset! progress {:messages messages :calls calls :done {}})]
              (if (empty? calls)
                (let [r (finish! (assoc (run-result client (final-text client body) messages tool-calls turns usage false) :model turn-model))]
                  (emit! on-event {:type "done" :result r})
                  r)
                (let [_       (doseq [c calls]
                                (emit! on-event
                                       ;; SPEC §4C: a call_tool call names the REAL tool
                                       (let [[nm a w?] (unwrap-call client (:name c) (:args c))]
                                         (cond-> {:type "tool_call" :id (:id c) :name nm :args a}
                                           w? (assoc :via "call_tool")))))
                      ;; parallel execution, results in call order …
                      settled (execute-calls client toolkit calls turn
                                             (fn [r] (swap! progress assoc-in [:done (:id r)] r)))
                      ;; a batch that finished after cancellation is interrupted,
                      ;; not a result (the finished pairs are still saved)
                      _       (check-cancel! client)
                      ;; … then §10 resolution, sequential and in the same order.
                      settled (reduce (fn [acc s]
                                        (if (some :halt acc)
                                          ;; a durable halt already happened earlier in
                                          ;; call order: later suspensions' placeholders
                                          ;; never enter the transcript — they re-suspend
                                          ;; on resume (§10).
                                          acc
                                          (let [req (pending-of (:result s))]
                                            (conj acc (if req
                                                        (merge s (resolve-pending client toolkit s req on-event turn))
                                                        s)))))
                                      [] settled)
                      ;; §10 durable halt: the transcript keeps the calls UP TO AND
                      ;; INCLUDING the first halted one, and nothing after it —
                      ;; deterministic by call order, never by scheduling.
                      halt-ix (first (keep-indexed (fn [i s] (when (:halt s) i)) settled))
                      settled (if halt-ix (vec (take (inc halt-ix) settled)) settled)
                      _       (doseq [s settled]
                                (emit! on-event
                                       ;; §1B: a part is rendered {type, mimeType, bytes}.
                                       ;; `data` NEVER reaches an event payload or a log
                                       ;; line — same rule as never-log-headers.
                                       (cond-> {:type "tool_result" :id (:id s)
                                                :name (first (unwrap-call client (:name s) (:args s)))
                                                :output (:output (:result s))
                                                :isError (boolean (:isError (:result s)))}
                                         (nth (unwrap-call client (:name s) (:args s)) 2)
                                         (assoc :via "call_tool")
                                         (seq (:parts (:result s)))
                                         (assoc :parts (mapv content/describe-part
                                                             (:parts (:result s))))
                                         ;; SPEC §4A edit diffs: the event carries
                                         ;; the result's metadata.
                                         (some? (:metadata (:result s)))
                                         (assoc :metadata (:metadata (:result s))))))
                      ;; SPEC §4C: the RunResult records a call_tool call as the
                      ;; REAL tool with its inner (post-:before-tool) arguments —
                      ;; what hooks and events see. The transcript keeps the wrapper.
                      ;; §8 `toolCalls[].parts` iff the result had parts; the
                      ;; model's call id rides in METADATA (invisible to `=` and
                      ;; to every serialised shape) so `run-files` can tag
                      ;; `RunResult.files` with it without changing a record.
                      records (mapv (fn [s] (let [[nm a] (unwrap-call client (:name s) (:args s))]
                                             (with-meta
                                               (cond-> {:name nm :args a
                                                        :output (:output (:result s))
                                                        :isError (boolean (:isError (:result s)))
                                                        :metadata (:metadata (:result s))}
                                                 (seq (:parts (:result s)))
                                                 (assoc :parts (vec (:parts (:result s)))))
                                               {::call-id (str (or (:id s) ""))})))
                                    settled)
                      halted  (first (keep :halt settled))
                      msgs    (into messages (tool-result-messages client settled))]
                  (if halted
                    (let [r (run-metric! (assoc (pending-result client halted msgs (into tool-calls records) turns usage) :model turn-model))]
                      (emit! on-event {:type "done" :result r})
                      r)
                    (recur msgs tools (inc turn) turns (into tool-calls records) usage turn-model)))))))))
    ;; SPEC §8 "Interrupted turns are saved": an error or a cancellation still
    ;; saves the prompt and every complete message, unanswered tool calls
    ;; closed with an `interrupted` result. The ORIGINAL error is rethrown; a
    ;; store that fails here must not replace it.
    (catch Throwable e
      (when-let [st @progress]
        (when (and conversation-id (:store client))
          (try ((:save (:store client)) conversation-id (interrupted-transcript client st))
               (catch Throwable _ nil))))
      (throw e)))))


(defn ask
  "`run`, remembering the conversation (§conversation-store) — the id-keyed
  sugar every other port ships as `ask`.

      (ask client \"and the second one?\" {:toolkit tk :id \"muthu\"})

  With an `:id`, the store's history for that id is loaded before the call and
  the full transcript saved after it, so the next `ask` with the same id
  continues the conversation. WITHOUT an id it is a stateless one-shot,
  identical to `run` — the same rule as js/src/client.ts, where `ask` with no
  id delegates straight to `run`.

  This is deliberately nothing more than `run` with `:conversation-id` filled
  in: the memory mechanics live in ONE place, and a second copy of the
  load-then-save rule here is exactly the drift the ports exist to prevent."
  [client prompt {:keys [toolkit id on-event] :as ctx}]
  (run client prompt (cond-> {:toolkit toolkit :on-event on-event}
                       id (assoc :conversation-id id)
                       (:history ctx) (assoc :history (:history ctx)))))

;; ---------------------------------------------------------------------------
;; SPEC §4C — list-models
;; ---------------------------------------------------------------------------

(def ^:private hex-digits "0123456789ABCDEF")

(defn- url-encode
  "Percent-encode an ASCII query value (a model id). Non-ASCII passes through."
  [s]
  (apply str (map (fn [c]
                    (let [ch (str c) n (int c)]
                      (if (or (re-matches #"[A-Za-z0-9._~-]" ch) (> n 127))
                        ch
                        (str "%" (nth hex-digits (quot n 16)) (nth hex-digits (rem n 16))))))
                  (str s))))

(defn- get-json [url headers]
  (let [res (http/request {:method :get :url url :headers headers :timeout-ms 60000})]
    (cond
      (http/failed? res)
      (throw (ex-info (str "list models: transport " (name (:error res)))
                      {:toolnexus/error :transport :error (:error res)}))

      (not (and (:status res) (<= 200 (:status res) 299)))
      (let [b (safe-body (:status res) (:body res))]
        (throw (ex-info (str "list models: HTTP " (:status res) ": "
                             (if (> (count b) 300) (subs b 0 300) b))
                        {:toolnexus/error :provider :status (:status res)})))

      :else (json/read-str (str (:body res))))))

(defn list-models
  "SPEC §4C — the models a provider offers, for model switching:
  `[{:id :name? :context-length?}]` sorted by id. One GET, no retries.

  openai style: GET {base}/models (OpenRouter's `context_length` is read when
  present). anthropic style: GET {base}/v1/models (or {base}/models when the base
  already ends in /v1), following `has_more`/`last_id`. The key follows the
  client's rules exactly — `:api-key`, `:api-key-env`, or the host's own env var."
  [opts]
  (let [key   (resolve-key opts)
        base  (trim-slashes (:base-url opts))
        extra (or (:headers opts) {})
        out   (if (= "anthropic" (style-of opts))
                (let [endpoint (if (str/ends-with? base "/v1") (str base "/models") (str base "/v1/models"))]
                  (loop [after nil page 0 acc []]
                    (let [body (get-json (str endpoint "?limit=1000"
                                              (when after (str "&after_id=" (url-encode after))))
                                         (merge {"x-api-key" key "anthropic-version" "2023-06-01"} extra))
                          acc  (into acc (map (fn [m] (cond-> {:id (str (:id m))}
                                                        (seq (str (or (:display_name m) ""))) (assoc :name (:display_name m))))
                                              (:data body)))
                          nxt  (:last_id body)]
                      (if (and (true? (:has_more body)) (string? nxt) (not= "" nxt) (< (inc page) 100))
                        (recur nxt (inc page) acc)
                        acc))))
                (let [body (get-json (str base "/models") (merge {"authorization" (str "Bearer " key)} extra))]
                  (mapv (fn [m] (cond-> {:id (str (:id m))}
                                  (seq (str (or (:name m) ""))) (assoc :name (:name m))
                                  (and (number? (:context_length m)) (pos? (:context_length m)))
                                  (assoc :context-length (long (:context_length m)))))
                        (:data body))))]
    (vec (sort (fn [a b] (tool/compare-strings (:id a) (:id b))) out))))
