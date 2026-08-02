;; SPEC §4A + §0.11 — the built-in coding toolset (source: "builtin").
;;
;; The ten default tools — bash, read, write, edit, grep, glob, webfetch,
;; question, apply_patch, todowrite — with the §4A names and input schemas, plus
;; §0.11's toggle semantics.
;;
;; Ported from spike s22, which proved the whole set byte-identical on Clojure
;; (JVM) and cljgo. Two things s22 could NOT do, and koine 0.6.0 now can:
;;
;;   1. `bash`'s timeout is real. `koine.process/sh` takes `:timeout-ms`; a
;;      killed command comes back `{:timed-out? true :exit nil}` — nil, not a
;;      number, because the JVM would say 137 and Go -1 and koine refuses to
;;      invent agreement. `:timed-out?` is ALWAYS present, so it is tested
;;      unconditionally and BEFORE `:exit`.
;;   2. `mkdir`/`delete` no longer shell out. s22 ran `mkdir -p` / `rm -f`,
;;      which is portable across HOSTS but not across OPERATING SYSTEMS.
;;      `koine.fs/mkdirs!` and `koine.fs/delete!` are syscalls on both hosts.
;;
;; Every implementation is named `t-*`: `read` is `clojure.core/read`, and
;; cljgo's static Java-interop scan rejects a WHOLE namespace over one shadowed
;; core symbol. Zero reader conditionals, zero `java.*`, zero Go interop.
(ns toolnexus.builtin
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [koine.http :as khttp]
            [koine.json :as json]
            [koine.process :as proc]
            [koine.text :as text]
            [toolnexus.tool :as tool]))

(defn- suspend
  "§10 — a ToolResult whose metadata.pending is a Request is a suspension.

  isError is TRUE, per §10's ToolResult block: the call \"did not produce a
  usable answer\". That does NOT contradict §10's later \"a suspension is never
  a tool error\" — that sentence is about the `tool` OBSERVABILITY EVENT, which
  carries isError:false plus a pending:true marker so error-rate metrics and
  circuit-breakers do not count it. Two different objects, two different flags.

  This namespace originally produced isError:false here while `client/suspend`
  produced true. Both worked, because the loop branches on metadata.pending
  alone — which is exactly why it would have drifted unnoticed."
  [request]
  (tool/failure "Waiting for a response." {:pending request}))

;; ---------------------------------------------------------------------------
;; small pure helpers
;; ---------------------------------------------------------------------------

(def utf8-count
  "UTF-8 byte length of `s` — `koine.text/utf8-length`.

  The hand-rolled version here was WRONG on both hosts (8 and 5 bytes for a
  6-byte answer — it folded code units, not code points) and was fixed locally
  before koine 0.11.0 lifted the correct fold into the seam. Delegating keeps
  one implementation; the regression test in builtin_test stays here, guarding
  the call site rather than trusting the seam blindly."
  text/utf8-length)

(defn- index-all
  "Every index at which `sub` occurs in `s`, left to right."
  [s sub]
  (if (str/blank? sub)
    []
    (loop [from 0 acc []]
      (let [j (str/index-of s sub from)]
        (if (nil? j) acc (recur (+ (long j) (count sub)) (conj acc j)))))))

(defn- replace-all-literal [s old new]
  (if (str/blank? old)
    s
    (loop [rest-s s acc ""]
      (let [j (str/index-of rest-s old)]
        (if (nil? j)
          (str acc rest-s)
          (recur (subs rest-s (+ (long j) (count old)))
                 (str acc (subs rest-s 0 (long j)) new)))))))

(defn- replace-first-literal [s old new]
  (let [j (str/index-of s old)]
    (if (nil? j) s (str (subs s 0 (long j)) new (subs s (+ (long j) (count old)))))))

;; -- glob, matched structurally rather than via a translated regex -----------
;; Top-level defn- rather than letfn: named self-recursion is the shape every
;; host agrees on, and the AOT pass sees an ordinary fn. Structural matching
;; also sidesteps the regex-dialect gap (java.util.regex vs Go RE2).

(defn- seg-match*
  "`*` (any run within a segment) and `?` (one char) against one path segment."
  [pat s pi si]
  (cond
    (and (= pi (count pat)) (= si (count s))) true
    (= pi (count pat))                        false
    (= \* (nth pat pi))                       (or (seg-match* pat s (inc pi) si)
                                                  (and (< si (count s))
                                                       (seg-match* pat s pi (inc si))))
    (= si (count s))                          false
    (or (= \? (nth pat pi))
        (= (nth pat pi) (nth s si)))          (seg-match* pat s (inc pi) (inc si))
    :else                                     false))

(defn- glob-match* [p q pi qi]
  (cond
    (= pi (count p))    (= qi (count q))
    (= "**" (nth p pi)) (or (glob-match* p q (inc pi) qi)
                            (and (< qi (count q)) (glob-match* p q pi (inc qi))))
    (= qi (count q))    false
    (seg-match* (nth p pi) (nth q qi) 0 0) (glob-match* p q (inc pi) (inc qi))
    :else               false))

(defn glob-match?
  "`**` crosses separators, `*`/`?` do not — so a recursive sweep is `**/*.md`."
  [pattern path]
  (glob-match* (vec (str/split (str pattern) #"/"))
               (vec (str/split (str path) #"/"))
               0 0))

(defn- files-under
  "Every FILE under `root`, sorted. `koine.fs/list-tree` is unordered per host,
  so the sort is what makes any listing tool comparable across hosts."
  [root]
  (->> (fs/list-tree root)
       (map str)
       (remove fs/directory?)
       ;; tool/sort-strings, not `sort` — glob output must not depend on the host.
       tool/sort-strings))

(defn- rel-path [root p]
  (let [root (str root)
        pfx  (if (str/ends-with? root "/") root (str root "/"))]
    (if (str/starts-with? (str p) pfx) (subs (str p) (count pfx)) (str p))))

(defn- parent-of [p]
  (let [i (str/last-index-of (str p) "/")]
    (when i (subs (str p) 0 (long i)))))

;; ---------------------------------------------------------------------------
;; §4A — the ten input SCHEMAS. This is the contract.
;; ---------------------------------------------------------------------------

(def bash-schema
  {:type "object"
   :properties {:command     {:type "string"  :description "The shell command to run"}
                :workdir     {:type "string"  :description "Working directory (default: process cwd)"}
                :timeout     {:type "number"  :description "Timeout in milliseconds" :default 60000}
                :description {:type "string"  :description "What this command does"}}
   :required ["command"]})

(def read-schema
  {:type "object"
   :properties {:path   {:type "string" :description "Path to the file to read"}
                :offset {:type "number" :description "1-based line to start from"}
                :limit  {:type "number" :description "Number of lines to read"}}
   :required ["path"]})

(def write-schema
  {:type "object"
   :properties {:path    {:type "string" :description "Path to write to"}
                :content {:type "string" :description "Content to write"}}
   :required ["path" "content"]})

(def edit-schema
  {:type "object"
   :properties {:path       {:type "string"  :description "Path to the file to edit"}
                :oldString  {:type "string"  :description "Exact text to replace"}
                :newString  {:type "string"  :description "Replacement text"}
                :replaceAll {:type "boolean" :description "Replace every occurrence"}}
   :required ["path" "oldString" "newString"]})

(def grep-schema
  {:type "object"
   :properties {:pattern {:type "string" :description "Regular expression to search for"}
                :path    {:type "string" :description "Directory to search (default: cwd)"}
                :include {:type "string" :description "Glob filter on file paths"}
                :limit   {:type "number" :description "Maximum matches" :default 100}}
   :required ["pattern"]})

(def glob-schema
  {:type "object"
   :properties {:pattern {:type "string" :description "Glob pattern to match"}
                :path    {:type "string" :description "Directory to search (default: cwd)"}
                :limit   {:type "number" :description "Maximum results" :default 100}}
   :required ["pattern"]})

(def webfetch-schema
  {:type "object"
   :properties {:url     {:type "string" :description "URL to fetch"}
                :format  {:type "string" :description "Output format"
                          :enum ["text" "markdown" "html"] :default "markdown"}
                :timeout {:type "number" :description "Timeout in seconds" :default 30}}
   :required ["url"]})

(def question-schema
  {:type "object"
   :properties {:questions
                {:type "array"
                 :description "Questions to ask the human"
                 :items {:type "object"
                         :properties {:question {:type "string"}
                                      :header   {:type "string"}
                                      :options  {:type "array" :items {:type "string"}}
                                      :multiple {:type "boolean"}}
                         :required ["question"]}}}
   :required ["questions"]})

(def apply-patch-schema
  {:type "object"
   :properties {:patchText {:type "string" :description "The patch to apply"}}
   :required ["patchText"]})

(def todowrite-schema
  {:type "object"
   :properties {:todos
                {:type "array"
                 :description "The full todo list, replacing the current one"
                 :items {:type "object"
                         :properties {:id        {:type "string"}
                                      :text      {:type "string"}
                                      :completed {:type "boolean"}}
                         :required ["id" "text" "completed"]}}}
   :required ["todos"]})

;; ---------------------------------------------------------------------------
;; §4A — behaviour
;; ---------------------------------------------------------------------------

(def default-bash-timeout-ms
  "§4A: `timeout?:number(ms, default 60000)`."
  60000)

(defn- t-bash
  "Combined stdout+stderr. Non-zero exit ⇒ isError with the exit code appended.
  Timeout kills the child ⇒ isError.

  `:timed-out?` is checked FIRST and unconditionally: on a kill `:exit` is nil,
  not a number, so `(zero? (:exit r))` would blow up (or, worse, a port that
  defaults nil to 0 would read a kill as a clean run)."
  [args _ctx]
  (let [ms  (long (or (:timeout args) default-bash-timeout-ms))
        r   (proc/sh ["sh" "-c" (str (:command args))]
                     (cond-> {:timeout-ms ms}
                       (:workdir args) (assoc :dir (str (:workdir args)))))
        out (str (:out r) (:err r))]
    (cond
      (true? (:timed-out? r)) (tool/failure (str out "command timed out after " ms "ms"))
      (zero? (long (:exit r))) (tool/success out)
      :else (tool/failure (str out "exit code " (:exit r))))))

(defn- t-read [args _ctx]
  (let [p (str (:path args))]
    (if-not (fs/exists? p)
      (tool/failure (str "read: file not found: " p))
      (let [text (str (fs/read-file p))
            off  (:offset args)
            lim  (:limit args)]
        (if (and (nil? off) (nil? lim))
          (tool/success text)
          (let [lines (str/split-lines text)
                start (max 0 (dec (long (or off 1))))
                win   (drop start lines)
                win   (if lim (take (long lim) win) win)]
            (tool/success (str/join "\n" win))))))))

(defn- t-write [args _ctx]
  (let [p (str (:path args))
        c (str (:content args))]
    (when-let [d (parent-of p)] (fs/mkdirs! d))
    (fs/write-file p c)
    (tool/success (str "Wrote " (utf8-count c) " bytes to " p))))

(defn- t-edit [args _ctx]
  (let [p (str (:path args))]
    (if-not (fs/exists? p)
      (tool/failure (str "edit: file not found: " p))
      (let [text (str (fs/read-file p))
            old  (str (:oldString args))
            new  (str (:newString args))
            hits (index-all text old)]
        (cond
          (empty? hits)
          (tool/failure (str "edit: oldString not found in " p))

          (and (> (count hits) 1) (not (true? (:replaceAll args))))
          (tool/failure (str "edit: oldString is not unique in " p
                         " (" (count hits) " occurrences)"))

          :else
          (let [out (if (true? (:replaceAll args))
                      (replace-all-literal text old new)
                      (replace-first-literal text old new))]
            (fs/write-file p out)
            (tool/success (str "Replaced " (if (true? (:replaceAll args)) (count hits) 1)
                          " occurrence(s) in " p))))))))

(defn- grep-file-hits [pat f]
  (let [lines (str/split-lines (str (fs/read-file f)))]
    (->> (map-indexed (fn [i line] [(inc i) line]) lines)
         (filter (fn [pair] (some? (re-find pat (nth pair 1)))))
         (map (fn [pair] (str f ":" (nth pair 0) ":" (nth pair 1))))
         vec)))

(defn- t-grep
  "`pattern` is a REGEX, and the dialect differs per host (java.util.regex vs Go
  RE2). Only the common subset is portable; that is a §4A limit, not a bug here."
  [args _ctx]
  (let [root  (str (or (:path args) "."))
        limit (long (or (:limit args) 100))
        inc-g (:include args)
        pat   (re-pattern (str (:pattern args)))
        files (->> (files-under root)
                   (filter (fn [f] (or (nil? inc-g)
                                       (glob-match? inc-g (rel-path root f))))))
        hits  (vec (mapcat (fn [f] (grep-file-hits pat f)) files))]
    (tool/success (str/join "\n" (take limit hits)))))

(defn- t-glob [args _ctx]
  (let [root  (str (or (:path args) "."))
        limit (long (or (:limit args) 100))
        rels  (->> (files-under root)
                   (map (fn [f] (rel-path root f)))
                   (filter (fn [r] (glob-match? (str (:pattern args)) r)))
                   ;; tool/sort-strings, not `sort`. `files-under` already
                   ;; ordered these portably, but by ABSOLUTE path — mapping to
                   ;; relative changes the keys, so this re-sort is what the
                   ;; output order actually is, and a bare `sort` handed it back
                   ;; to the host: the JVM orders by UTF-16 code unit, cljgo by
                   ;; UTF-8 byte, and above the BMP those are opposite answers.
                   tool/sort-strings)]
    (tool/success (str/join "\n" (take limit rels)))))

(defn- strip-tags [s]
  (-> (str s)
      (str/replace #"<[^>]*>" "")
      (str/replace #"[ \t]+" " ")
      str/trim))

(defn- html->markdown
  "Deliberately minimal: §4A pins the FORMAT NAMES but not the conversion, so
  these bytes are this port's choice and not the contract. Reported as a §4A
  gap that six ports cannot converge on by luck."
  [s]
  (-> (str s)
      (str/replace #"<h1[^>]*>" "# ")
      (str/replace #"</h1>" "\n")
      (str/replace #"<b>" "**")
      (str/replace #"</b>" "**")
      (str/replace #"<p[^>]*>" "")
      (str/replace #"</p>" "\n")
      strip-tags))

(defn- t-webfetch [args _ctx]
  (let [secs (long (or (:timeout args) 30))
        res  (khttp/request {:method :get :url (str (:url args))
                             :timeout-ms (* 1000 secs)})
        fmt  (str (or (:format args) "markdown"))]
    (if (khttp/failed? res)
      ;; DATA, never a caught class — koine classifies the transport failure.
      (tool/failure (str "webfetch: transport failure: " (name (:error res)))
                {:error (:error res)})
      (let [st   (long (:status res))
            body (str (:body res))]
        (cond
          (or (< st 200) (> st 299)) (tool/failure (str "HTTP " st))
          (= fmt "html")             (tool/success body)
          (= fmt "text")             (tool/success (strip-tags body))
          :else                      (tool/success (html->markdown body)))))))

(defn render-questions
  "§4A, byte-identical across ports: each question's text in order, with
  \" (options: a, b, c)\" appended when it has non-empty options, joined by
  \"\\n\", no trailing newline. `header` is NOT rendered — it stays in
  `data.questions`."
  [questions]
  (str/join "\n"
            (map (fn [q]
                   (str (:question q)
                        (if (seq (:options q))
                          (str " (options: " (str/join ", " (:options q)) ")")
                          "")))
                 questions)))

(defn- t-question
  "§4A + §10 — asking the human is a Request, not a special case. First call
  suspends; the loop re-executes with `ctx.answer`, and the resolution IS the
  answer. A `!ok` answer suspends again rather than inventing one."
  [args ctx]
  (let [answer (:answer ctx)]
    (if (and (map? answer) (true? (:ok answer)))
      (tool/success (json/write-str (:data answer)))
      (suspend {:id     (str (or (:request-id ctx) "req-1"))
                :kind   "question"
                :prompt (render-questions (:questions args))
                :data   {:questions (vec (:questions args))}}))))

;; -- apply_patch ------------------------------------------------------------

(def ^:private add-marker    "*** Add File: ")
(def ^:private update-marker "*** Update File: ")
(def ^:private delete-marker "*** Delete File: ")

(defn- parse-patch
  "opencode's grammar, ONE hunk per Update section. Returns {:ops [...]} or
  {:error \"...\"}."
  [text]
  (let [lines (vec (str/split-lines (str text)))]
    (if-not (= "*** Begin Patch" (str/trim (str (first lines))))
      {:error "apply_patch: patch must start with *** Begin Patch"}
      (loop [i 1 ops [] cur nil]
        (if (>= i (count lines))
          {:error "apply_patch: patch must end with *** End Patch"}
          (let [l (nth lines i)
                t (str/trim l)]
            (cond
              (= t "*** End Patch")
              {:ops (if cur (conj ops cur) ops)}

              (str/starts-with? t add-marker)
              (recur (inc i) (if cur (conj ops cur) ops)
                     {:op "add" :path (subs t (count add-marker)) :lines []})

              (str/starts-with? t update-marker)
              (recur (inc i) (if cur (conj ops cur) ops)
                     {:op "update" :path (subs t (count update-marker)) :lines []})

              (str/starts-with? t delete-marker)
              (recur (inc i) (if cur (conj ops cur) ops)
                     {:op "delete" :path (subs t (count delete-marker)) :lines []})

              (nil? cur) (recur (inc i) ops cur)
              :else      (recur (inc i) ops (assoc cur :lines (conj (:lines cur) l))))))))))

(defn- hunk-blocks
  "[old-lines new-lines] for an Update hunk: ' ' context, '-' removed, '+' added."
  [lines]
  (reduce (fn [acc l]
            (let [tag  (if (empty? l) \space (nth l 0))
                  body (if (empty? l) "" (subs l 1))]
              (cond
                (= tag \-) [(conj (nth acc 0) body) (nth acc 1)]
                (= tag \+) [(nth acc 0) (conj (nth acc 1) body)]
                :else      [(conj (nth acc 0) body) (conj (nth acc 1) body)])))
          [[] []]
          lines))

(defn- find-block
  "Index of the first contiguous occurrence of `block` in `lines`, or nil."
  [lines block]
  (if (empty? block)
    nil
    (loop [i 0]
      (cond
        (> (+ i (count block)) (count lines)) nil
        (= block (vec (take (count block) (drop i lines)))) i
        :else (recur (inc i))))))

(defn- plan-op
  "Resolve one op to {:kind :path :content?} or {:error ...}. READS ONLY — this
  is what makes §4A's 'no partial write' true by construction."
  [op]
  (let [p (:path op)]
    (cond
      (= "add" (:op op))
      (if (fs/exists? p)
        {:error (str "apply_patch: Add File target already exists: " p)}
        {:kind "add" :path p
         :content (str (str/join "\n" (map (fn [l] (if (empty? l) "" (subs l 1)))
                                           (:lines op)))
                       "\n")})

      (= "delete" (:op op))
      (if-not (fs/exists? p)
        {:error (str "apply_patch: Delete File target does not exist: " p)}
        {:kind "delete" :path p})

      (= "update" (:op op))
      (if-not (fs/exists? p)
        {:error (str "apply_patch: Update File target does not exist: " p)}
        (let [text   (str (fs/read-file p))
              trail? (str/ends-with? text "\n")
              lines  (vec (str/split-lines text))
              blocks (hunk-blocks (:lines op))
              at     (find-block lines (nth blocks 0))]
          (if (nil? at)
            {:error (str "apply_patch: hunk does not match " p)}
            (let [out (vec (concat (take at lines)
                                   (nth blocks 1)
                                   (drop (+ (long at) (count (nth blocks 0))) lines)))]
              {:kind "update" :path p
               :content (str (str/join "\n" out) (if trail? "\n" ""))}))))

      :else {:error (str "apply_patch: unknown op " (:op op))})))

(defn- t-apply-patch [args _ctx]
  (let [parsed (parse-patch (:patchText args))]
    (if (:error parsed)
      (tool/failure (:error parsed))
      (let [plans (mapv plan-op (:ops parsed))
            bad   (first (filter :error plans))]
        (if bad
          ;; Atomic: everything was planned from READS, so nothing has been
          ;; written and there is no partial state to unwind.
          (tool/failure (:error bad))
          (do (doseq [pl plans]
                (if (= "delete" (:kind pl))
                  (fs/delete! (:path pl))
                  (do (when-let [d (parent-of (:path pl))] (fs/mkdirs! d))
                      (fs/write-file (:path pl) (:content pl)))))
              (tool/success (str "Applied " (count plans) " file change(s): "
                            (str/join ", " (map (fn [pl] (str (:kind pl) " " (:path pl)))
                                                plans))))))))))

(defn- t-todowrite
  "Stateless in v1 — it echoes the list back. The RENDERING is not pinned by
  §4A; `- [x] text` / `- [ ] text` is this port's choice (reported)."
  [args _ctx]
  (tool/success (str/join "\n"
                     (map (fn [t] (str "- [" (if (true? (:completed t)) "x" " ") "] "
                                       (:text t)))
                          (vec (:todos args))))))

;; ---------------------------------------------------------------------------
;; the builtin source
;; ---------------------------------------------------------------------------

(defn- builtin
  [tool-name description schema f]
  (tool/tool {:name tool-name
              :description description
              :input-schema schema
              :source "builtin"
              ;; two arities, because toolnexus.tool/execute calls (f args) when
              ;; it has no Context and (f args ctx) when it does.
              :execute (fn ([args] (f args nil))
                         ([args ctx] (f args ctx)))}))

(def builtin-tools
  "The ten §4A builtins, in the order of the SPEC table."
  [(builtin "bash"        "Run a shell command."                bash-schema        t-bash)
   (builtin "read"        "Read a UTF-8 text file."             read-schema        t-read)
   (builtin "write"       "Write a file, creating parent dirs." write-schema       t-write)
   (builtin "edit"        "Exact-string replace in a file."     edit-schema        t-edit)
   (builtin "grep"        "Search file contents by regex."      grep-schema        t-grep)
   (builtin "glob"        "List files matching a glob."         glob-schema        t-glob)
   (builtin "webfetch"    "HTTP GET a URL and return its body." webfetch-schema    t-webfetch)
   (builtin "question"    "Ask the human one or more questions." question-schema   t-question)
   (builtin "apply_patch" "Apply an add/update/delete patch."   apply-patch-schema t-apply-patch)
   (builtin "todowrite"   "Replace the session todo list."      todowrite-schema   t-todowrite)])

(def builtin-names (mapv :name builtin-tools))

;; ---------------------------------------------------------------------------
;; §0.11 / §4 — toggling
;; ---------------------------------------------------------------------------

(defn source-on?
  "Whole-source gate. `builtins:false` | `{disabled:true}` | `{enabled:false}`
  turn the source off, with MCP precedence: `disabled:true` wins, else
  `enabled:false` disables, else on. Absent ⇒ ON (the default)."
  [opt]
  (cond
    (nil? opt)   true
    (true? opt)  true
    (false? opt) false
    (map? opt)   (cond (true? (:disabled opt)) false
                       (false? (:enabled opt)) false
                       :else                   true)
    :else        true))

(defn enabled-builtins
  "The builtin tools this config exposes. A whole-source-off SHORT-CIRCUITS:
  the `tools` map is never consulted (§0.11).

  ===================================================================
  KNOWN SPEC DEFECT — `builtins.tools` with a `true` value. REPORTED.
  ===================================================================
  Two sections of SPEC.md specify DIFFERENT FUNCTIONS for the same input,
  while each claims to be the other:

    §4A + §4 'Assembly order & builtin toggle' (and §0.11 itself) —
      'a name→bool map applied on the ALL-ON BASELINE: a tool mapped to false
      is dropped, true (or absent) stays on.'
      ⇒ {:tools {:bash true}} = ALL TEN.

    §3 S2 (skills filter) and §2 Gap 7 (MCP per-server filter), both of which
      describe themselves as 'identical to ... builtins §4A' —
      'nil/empty ⇒ all; ≥1 true ⇒ ALLOWLIST (only true-mapped names)'
      ⇒ {:tools {:bash true}} = BASH ONLY.

  This implements the §4A wording — the section that OWNS builtins, states it
  twice, and which §0.11 restates. Spike s22 made the same call. Whichever way
  it is resolved, §3-S2 and §2-Gap-7 must stop claiming they are identical to
  §4A unless they are. Six ports cannot be byte-identical against a spec that
  specifies two behaviours for one input."
  [opt]
  (if-not (source-on? opt)
    []
    ;; Keys are normalised to plain NAMES before lookup. `(get m (keyword …))`
    ;; matched keyword keys only, so `{:tools {"bash" false}}` — and EVERY config
    ;; read from JSON without `:key-fn keyword` — left all ten builtins armed.
    ;; It failed OPEN: the one direction a disable toggle must never fail. The
    ;; same both-spellings rule already governs the §3 skills filter
    ;; (`skill/filter-name`); this is that rule, applied where it was missing.
    (let [m       (when (map? opt) (:tools opt))
          by-name (when (map? m)
                    (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) m)))]
      (vec (filter (fn [t] (not (false? (get by-name (:name t))))) builtin-tools)))))

(defn enabled-builtin-names [opt] (mapv :name (enabled-builtins opt)))

(defn builtin-toolkit
  "The enabled builtins as a `toolnexus.tool` toolkit."
  ([] (builtin-toolkit nil))
  ([opt] (tool/toolkit (enabled-builtins opt) {"builtin" (if (source-on? opt)
                                                           "connected" "disabled")})))
