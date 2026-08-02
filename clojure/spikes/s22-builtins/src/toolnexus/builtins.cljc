;; S22 — the built-in coding toolset (SPEC §0.11 + §4A), in ONE .cljc, on TWO
;; hosts.
;;
;; The question: can the ten default builtins — bash, read, write, edit, grep,
;; glob, webfetch, question, apply_patch, todowrite — be implemented in portable
;; Clojure over koine alone, with NO reader conditional in toolnexus' own
;; source, and behave byte-identically on Clojure (JVM) and cljgo?
;;
;;   §4A  the ten names + input SCHEMAS (the contract) and their behaviour
;;   §0.11 / §4 assembly — the toggling semantics:
;;        on by default · global off (builtins:false | {disabled:true} |
;;        {enabled:false}, MCP precedence) · per-tool builtins.tools on the
;;        all-on baseline · global-off short-circuits the map · surfaced through
;;        the tool-schema array ONLY, never the system prompt.
;;
;; Nothing here shadows a clojure.core name: every tool implementation is `t-*`
;; (`read`, `merge`, `remove` and friends are core, and cljgo's static interop
;; scan rejects a whole namespace over one shadowed symbol).
;;
;; Hermetic: the only network is a koine.server on 127.0.0.1:0 that webfetch
;; fetches from. Everything that touches the filesystem works inside a temp
;; directory this file creates and deletes; the repo is never written to.

(ns toolnexus.builtins
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.fs :as fs]
            [koine.env :as env]
            [koine.host :as host]
            [koine.http :as http]
            [koine.server :as server]
            [koine.process :as proc]))

;; ---------------------------------------------------------------------------
;; ToolResult (§1) — a failure is DATA, never a throw across the boundary
;; ---------------------------------------------------------------------------

(defn- ok [s] {:output (str s) :isError false})
(defn- fail [s] {:output (str s) :isError true})
(defn- suspend [request] {:output "" :isError false :metadata {:pending request}})

;; ---------------------------------------------------------------------------
;; small pure helpers (no regex where a dialect difference could bite: the JVM
;; is java.util.regex, cljgo is Go RE2, and glob matching is easy to do without)
;; ---------------------------------------------------------------------------

(defn- utf8-count
  "UTF-8 byte length of `s`, computed from code units — `String.getBytes` is
  java.*, and koine.codec only exposes base64. Astral chars (surrogate pairs)
  would count 6 rather than 4; the fixtures here are ASCII."
  [s]
  (reduce (fn [n c]
            (let [v (int c)]
              (+ n (cond (< v 128) 1 (< v 2048) 2 :else 3))))
          0 (str s)))

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
;; host agrees on, and the AOT pass sees an ordinary fn.

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
  "`**` crosses separators, `*`/`?` do not. A bare `*.md` also matches at the
  root only, which is why callers pass `**/*.md` for a recursive sweep."
  [pattern path]
  (glob-match* (vec (str/split (str pattern) #"/"))
               (vec (str/split (str path) #"/"))
               0 0))

(defn- files-under
  "Every FILE under `root`, sorted. koine.fs/list-tree is unordered per host, so
  the sort is what makes any listing tool comparable across hosts."
  [root]
  (->> (fs/list-tree root)
       (map str)
       (remove fs/directory?)
       sort
       vec))

(defn- rel-path [root p]
  (let [root (str root)
        pfx  (if (str/ends-with? root "/") root (str root "/"))]
    (if (str/starts-with? (str p) pfx) (subs (str p) (count pfx)) (str p))))

(defn- parent-of [p]
  (let [i (str/last-index-of (str p) "/")]
    (when i (subs (str p) 0 (long i)))))

;; koine.fs has no mkdir and no delete (see FINDINGS in the README). These two
;; shell out through koine.process/sh, which is portable across HOSTS but not
;; across OPERATING SYSTEMS — `mkdir`/`rm` are POSIX.
(defn- mkdir-p! [d] (when d (proc/sh ["mkdir" "-p" (str d)])) nil)
(defn- rm-f!    [p] (proc/sh ["rm" "-f" (str p)]) nil)

;; ---------------------------------------------------------------------------
;; §4A — the ten input SCHEMAS. This is the contract; it is emitted into the
;; report verbatim so a diff catches drift.
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

(defn- t-bash
  "FINDING — `timeout` is accepted (it is in the §4A schema) but NOT ENFORCED.
  koine.process/sh takes only :in/:dir/:env and runs to completion, and
  koine.process has no kill: `close!` closes the child's stdin and then WAITS.
  There is no portable way to bound this call, so §4A's \"Timeout kills the
  child ⇒ isError:true\" is the one builtin behaviour this spike cannot deliver.
  Faking it (a `timeout`/`gtimeout` shell wrapper) would be a POSIX-only lie."
  [args _ctx]
  (let [r    (proc/sh ["sh" "-c" (str (:command args))]
                      (if (:workdir args) {:dir (str (:workdir args))} {}))
        out  (str (:out r) (:err r))
        exit (long (or (:exit r) 0))]
    (if (zero? exit)
      (ok out)
      (fail (str out "exit code " exit)))))

(defn- t-read [args _ctx]
  (let [p (str (:path args))]
    (if-not (fs/exists? p)
      (fail (str "read: file not found: " p))
      (let [text (str (fs/read-file p))
            off  (:offset args)
            lim  (:limit args)]
        (if (and (nil? off) (nil? lim))
          (ok text)
          (let [lines (str/split-lines text)
                start (max 0 (dec (long (or off 1))))
                win   (drop start lines)
                win   (if lim (take (long lim) win) win)]
            (ok (str/join "\n" win))))))))

(defn- t-write [args _ctx]
  (let [p (str (:path args))
        c (str (:content args))]
    (mkdir-p! (parent-of p))
    (fs/write-file p c)
    (ok (str "Wrote " (utf8-count c) " bytes to " p))))

(defn- t-edit [args _ctx]
  (let [p (str (:path args))]
    (if-not (fs/exists? p)
      (fail (str "edit: file not found: " p))
      (let [text (str (fs/read-file p))
            old  (str (:oldString args))
            new  (str (:newString args))
            hits (index-all text old)]
        (cond
          (empty? hits)
          (fail (str "edit: oldString not found in " p))

          (and (> (count hits) 1) (not (true? (:replaceAll args))))
          (fail (str "edit: oldString is not unique in " p " (" (count hits) " occurrences)"))

          :else
          (let [out (if (true? (:replaceAll args))
                      (replace-all-literal text old new)
                      (replace-first-literal text old new))]
            (fs/write-file p out)
            (ok (str "Replaced " (if (true? (:replaceAll args)) (count hits) 1)
                     " occurrence(s) in " p))))))))

(defn- grep-file-hits [pat f]
  (let [lines (str/split-lines (str (fs/read-file f)))]
    (->> (map-indexed (fn [i line] [(inc i) line]) lines)
         (filter (fn [pair] (some? (re-find pat (nth pair 1)))))
         (map (fn [pair] (str f ":" (nth pair 0) ":" (nth pair 1))))
         vec)))

(defn- t-grep [args _ctx]
  (let [root  (str (or (:path args) "."))
        limit (long (or (:limit args) 100))
        inc-g (:include args)
        pat   (re-pattern (str (:pattern args)))
        files (->> (files-under root)
                   (filter (fn [f] (or (nil? inc-g) (glob-match? inc-g (rel-path root f))))))
        hits  (vec (mapcat (fn [f] (grep-file-hits pat f)) files))]
    (ok (str/join "\n" (take limit hits)))))

(defn- t-glob [args _ctx]
  (let [root  (str (or (:path args) "."))
        limit (long (or (:limit args) 100))
        rels  (->> (files-under root)
                   (map (fn [f] (rel-path root f)))
                   (filter (fn [r] (glob-match? (str (:pattern args)) r)))
                   sort
                   vec)]
    (ok (str/join "\n" (take limit rels)))))

(defn- strip-tags [s]
  (-> (str s)
      (str/replace #"<[^>]*>" "")
      (str/replace #"[ \t]+" " ")
      str/trim))

(defn- html->markdown
  "A deliberately minimal converter: §4A pins the FORMAT NAMES but not the
  conversion, so this is the spike's choice, not the contract (see FINDINGS)."
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
        res  (http/request {:method :get :url (str (:url args)) :timeout-ms (* 1000 secs)})
        st   (long (or (:status res) 0))
        fmt  (str (or (:format args) "markdown"))
        body (str (:body res))]
    (cond
      (http/failed? res)          (fail (str "webfetch: transport failure: " (name (:error res))))
      (or (< st 200) (> st 299))  (fail (str "HTTP " st))
      (= fmt "html")              (ok body)
      (= fmt "text")              (ok (strip-tags body))
      :else                       (ok (html->markdown body)))))

(defn render-questions
  "§4A — byte-identical across ports: each question's text in order, with
  \" (options: a, b, c)\" appended when it has non-empty options, joined by
  \"\\n\", no trailing newline. `header` is NOT rendered."
  [questions]
  (str/join "\n"
            (map (fn [q]
                   (str (:question q)
                        (if (seq (:options q))
                          (str " (options: " (str/join ", " (:options q)) ")")
                          "")))
                 questions)))

(defn- t-question [args ctx]
  (let [answer (:answer ctx)]
    (if (and (map? answer) (true? (:ok answer)))
      (ok (json/write-str (:data answer)))
      (suspend {:id     (str (or (:request-id ctx) "req-1"))
                :kind   "question"
                :prompt (render-questions (:questions args))
                :data   {:questions (vec (:questions args))}}))))

;; -- apply_patch ------------------------------------------------------------

(def ^:private add-marker    "*** Add File: ")
(def ^:private update-marker "*** Update File: ")
(def ^:private delete-marker "*** Delete File: ")

(defn- parse-patch
  "opencode's grammar, one hunk per Update section (see README: what it does
  NOT cover). Returns {:ops [...]} or {:error \"...\"}."
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
  "Resolve one op to {:kind :path :content?} or {:error ...}. Reads only."
  [op]
  (let [p (:path op)]
    (cond
      (= "add" (:op op))
      (if (fs/exists? p)
        {:error (str "apply_patch: Add File target already exists: " p)}
        {:kind "add" :path p
         :content (str (str/join "\n" (map (fn [l] (if (empty? l) "" (subs l 1))) (:lines op)))
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
      (fail (:error parsed))
      (let [plans (mapv plan-op (:ops parsed))
            bad   (first (filter :error plans))]
        (if bad
          ;; Atomic: everything was planned from READS, so nothing has been
          ;; written and there is no partial state to unwind.
          (fail (:error bad))
          (do (doseq [pl plans]
                (if (= "delete" (:kind pl))
                  (rm-f! (:path pl))
                  (do (mkdir-p! (parent-of (:path pl)))
                      (fs/write-file (:path pl) (:content pl)))))
              (ok (str "Applied " (count plans) " file change(s): "
                       (str/join ", " (map (fn [pl] (str (:kind pl) " " (:path pl))) plans))))))))))

(defn- t-todowrite [args _ctx]
  (let [todos (vec (:todos args))]
    (ok (str/join "\n"
                  (map (fn [t] (str "- [" (if (true? (:completed t)) "x" " ") "] " (:text t)))
                       todos)))))

;; ---------------------------------------------------------------------------
;; the builtin source
;; ---------------------------------------------------------------------------

(def builtin-tools
  "The ten §4A builtins, in the order of the SPEC table. `source` is \"builtin\"
  on every one of them (§4A)."
  [{:name "bash"        :description "Run a shell command."                     :inputSchema bash-schema        :source "builtin" :run t-bash}
   {:name "read"        :description "Read a UTF-8 text file."                  :inputSchema read-schema        :source "builtin" :run t-read}
   {:name "write"       :description "Write a file, creating parent dirs."      :inputSchema write-schema       :source "builtin" :run t-write}
   {:name "edit"        :description "Exact-string replace in a file."          :inputSchema edit-schema        :source "builtin" :run t-edit}
   {:name "grep"        :description "Search file contents by regex."           :inputSchema grep-schema        :source "builtin" :run t-grep}
   {:name "glob"        :description "List files matching a glob."              :inputSchema glob-schema        :source "builtin" :run t-glob}
   {:name "webfetch"    :description "HTTP GET a URL and return its body."      :inputSchema webfetch-schema    :source "builtin" :run t-webfetch}
   {:name "question"    :description "Ask the human one or more questions."     :inputSchema question-schema    :source "builtin" :run t-question}
   {:name "apply_patch" :description "Apply an add/update/delete patch."        :inputSchema apply-patch-schema :source "builtin" :run t-apply-patch}
   {:name "todowrite"   :description "Replace the session todo list."           :inputSchema todowrite-schema   :source "builtin" :run t-todowrite}])

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
    (nil? opt)     true
    (true? opt)    true
    (false? opt)   false
    (map? opt)     (cond (true? (:disabled opt)) false
                         (false? (:enabled opt)) false
                         :else                   true)
    :else          true))

(defn enabled-builtins
  "The builtin tools this config exposes.

  Per-tool: `builtins.tools` is a name->bool map applied on the ALL-ON baseline
  — a name mapped to false is dropped, true (or absent) stays on, unknown names
  are ignored. A whole-source-off SHORT-CIRCUITS: the map is never consulted."
  [opt]
  (if-not (source-on? opt)
    []
    (let [m (when (map? opt) (:tools opt))]
      (vec (filter (fn [t] (not (false? (get m (keyword (:name t)))))) builtin-tools)))))

(defn enabled-builtin-names [opt] (mapv :name (enabled-builtins opt)))

;; ---------------------------------------------------------------------------
;; §4 assembly — MCP -> skill -> builtin -> extraTools, dedupe first-wins,
;; with extraTools shadowing a builtin by name BEFORE the concat.
;; ---------------------------------------------------------------------------

(defn- dedupe-by-name [tools]
  (vec (reduce (fn [acc t]
                 (if (some (fn [x] (= (:name x) (:name t))) acc) acc (conj acc t)))
               []
               tools)))

(defn assemble
  [{:keys [mcp skill builtins extras]}]
  (let [shadowed (set (map :name extras))
        bs       (vec (filter (fn [t] (not (contains? shadowed (:name t))))
                              (enabled-builtins builtins)))]
    (dedupe-by-name (concat (vec mcp) (vec skill) bs (vec extras)))))

;; ---------------------------------------------------------------------------
;; §0.7 adapters — the ONLY route builtins take to the model
;; ---------------------------------------------------------------------------

(defn to-openai [tools]
  (mapv (fn [t] {:type "function"
                 :function {:name (:name t) :description (:description t)
                            :parameters (:inputSchema t)}})
        tools))

(defn to-anthropic [tools]
  (mapv (fn [t] {:name (:name t) :description (:description t)
                 :input_schema (:inputSchema t)})
        tools))

(defn to-gemini [tools]
  [{:functionDeclarations
    (mapv (fn [t] {:name (:name t) :description (:description t)
                   :parameters (:inputSchema t)})
          tools)}])

;; ---------------------------------------------------------------------------
;; the system prompt — §3 preamble + catalog. Takes NO builtin argument; that
;; is the structural half of the proof, and the report measures the other half.
;; ---------------------------------------------------------------------------

(def skills-preamble
  (str "Skills provide specialized instructions and workflows for specific tasks.\n"
       "Use the skill tool to load a skill when a task matches its description."))

(defn- parse-frontmatter [text]
  (let [lines (str/split-lines text)]
    (if-not (= "---" (str/trim (str (first lines))))
      [{} text]
      (loop [remaining (rest lines) acc {}]
        (cond
          (empty? remaining)                     [acc ""]
          (= "---" (str/trim (first remaining))) [acc (str/join "\n" (rest remaining))]
          :else
          (let [line (first remaining)
                idx  (str/index-of line ":")]
            (recur (rest remaining)
                   (if idx
                     (assoc acc (keyword (str/trim (subs line 0 (long idx))))
                            (str/trim (subs line (inc (long idx)))))
                     acc))))))))

(defn discover-skills [root]
  (->> (fs/find-files root "SKILL.md")
       (reduce (fn [acc path]
                 (let [pair (parse-frontmatter (str (fs/read-file path)))
                       meta (nth pair 0)
                       nm   (:name meta)]
                   (if (or (str/blank? (str nm)) (contains? acc nm))
                     acc
                     (assoc acc nm {:name nm :description (str (or (:description meta) ""))}))))
               {})))

(defn skills-prompt [skills]
  (let [described (->> (vals skills)
                       (filter (fn [s] (not (str/blank? (:description s)))))
                       (sort-by :name))]
    (if (empty? described)
      "No skills are currently available."
      (str skills-preamble "\n\n## Available Skills\n"
           (str/join "\n" (map (fn [s] (str "- **" (:name s) "**: " (:description s))) described))))))

(defn system-message
  "§0.10 — system = systemPrompt + \"\\n\\n\" + skillsPrompt(). Note the arglist:
  there is nowhere for a builtin to enter."
  [system-prompt skills]
  (str system-prompt "\n\n" (skills-prompt skills)))

;; ---------------------------------------------------------------------------
;; execution
;; ---------------------------------------------------------------------------

(defn execute [tools tool-name args ctx]
  (let [t (first (filter (fn [x] (= tool-name (:name x))) tools))]
    (if (nil? t)
      (fail (str "unknown tool: " tool-name))
      ((:run t) args ctx))))

;; ---------------------------------------------------------------------------
;; the run — everything below exists to produce ONE deterministic JSON line
;; ---------------------------------------------------------------------------

(defn- redact
  "Temp paths are non-deterministic by construction, so they are replaced by a
  fixed token before anything is reported. Nothing else is rewritten."
  [tmp s]
  (str/replace (str s) (str tmp) "<tmp>"))

(defn- shape
  "A ToolResult, reduced to what can be diffed across hosts. `outputBytes` is
  measured on the REDACTED text — the raw text carries a temp path whose length
  is a property of the machine, not of the tool."
  [tmp res]
  (let [out (redact tmp (:output res))]
    (cond-> {:isError     (true? (:isError res))
             :outputBytes (utf8-count out)
             :output      out}
      (:metadata res) (assoc :pending (let [p (get-in res [:metadata :pending])]
                                        {:id (:id p) :kind (:kind p) :prompt (:prompt p)
                                         :dataKeys (vec (sort (map name (keys (:data p)))))})))))

(defn- toggle-case [label opt]
  {:case label
   :sourceOn (source-on? opt)
   :count (count (enabled-builtin-names opt))
   :tools (enabled-builtin-names opt)})

;; These two run EFFECTS in a fixed sequence, so every step is a `let` binding.
;; A map literal is NOT a sequencing construct: the first cut of this spike put
;; the steps in a 12-entry map literal and got them evaluated out of source
;; order — write, then read, then an edit that reported "file not found" while a
;; later glob listed the file. A map literal orders its KEYS, not its effects.

(defn- run-fs-tools [tmp all]
  (let [f  (str tmp "/work/notes.txt")
        g  (str tmp "/work/sub/deep.txt")
        ex (fn [n a] (shape tmp (execute all n a {})))
        r-write        (ex "write" {:path f :content "alpha\nbeta\ngamma\n"})
        r-write-deep   (ex "write" {:path g :content "TODO: alpha\nplain line\nTODO: omega\n"})
        r-read-whole   (ex "read"  {:path f})
        r-read-window  (ex "read"  {:path f :offset 2 :limit 1})
        r-read-missing (ex "read"  {:path (str tmp "/work/nope.txt")})
        r-edit-missing (ex "edit"  {:path f :oldString "zeta" :newString "Z"})
        r-edit-ambig   (ex "edit"  {:path f :oldString "a" :newString "A"})
        r-edit-one     (ex "edit"  {:path f :oldString "beta" :newString "BETA"})
        r-after-edit   (ex "read"  {:path f})
        r-edit-all     (ex "edit"  {:path f :oldString "a" :newString "A" :replaceAll true})
        r-after-all    (ex "read"  {:path f})
        r-edit-nofile  (ex "edit"  {:path (str tmp "/work/nope.txt") :oldString "x" :newString "y"})
        r-glob         (ex "glob"  {:pattern "**/*.txt" :path tmp})
        r-glob-shallow (ex "glob"  {:pattern "*.txt"    :path (str tmp "/work")})
        r-glob-none    (ex "glob"  {:pattern "**/*.rs"  :path tmp})
        r-glob-limit   (ex "glob"  {:pattern "**/*.txt" :path tmp :limit 1})
        r-grep         (ex "grep"  {:pattern "TODO:\\s*\\w+" :path tmp :include "**/*.txt"})
        r-grep-filter  (ex "grep"  {:pattern "TODO" :path tmp :include "**/nomatch/*.txt"})
        r-grep-none    (ex "grep"  {:pattern "nothingmatchesthis" :path tmp})]
    {:write r-write :write-deep r-write-deep
     :read-whole r-read-whole :read-window r-read-window :read-missing r-read-missing
     :edit-missing r-edit-missing :edit-ambiguous r-edit-ambig :edit-one r-edit-one
     :after-edit-one r-after-edit :edit-all r-edit-all :after-edit-all r-after-all
     :edit-nofile r-edit-nofile
     :glob r-glob :glob-shallow r-glob-shallow :glob-none r-glob-none :glob-limit r-glob-limit
     :grep r-grep :grep-include-filtered r-grep-filter :grep-none r-grep-none}))

(defn- run-patch-tools [tmp all]
  (let [p    (str tmp "/patch/added.txt")
        ex   (fn [n a] (shape tmp (execute all n a {})))
        addp (str "*** Begin Patch\n*** Add File: " p "\n+one\n+two\n*** End Patch")
        updp (str "*** Begin Patch\n*** Update File: " p "\n one\n-two\n+TWO\n*** End Patch")
        badp (str "*** Begin Patch\n*** Update File: " p "\n-nosuchline\n+x\n*** End Patch")
        delp (str "*** Begin Patch\n*** Delete File: " p "\n*** End Patch")
        r-malformed (ex "apply_patch" {:patchText "no markers here"})
        r-add       (ex "apply_patch" {:patchText addp})
        r-added     (ex "read" {:path p})
        r-update    (ex "apply_patch" {:patchText updp})
        r-updated   (ex "read" {:path p})
        r-bad       (ex "apply_patch" {:patchText badp})
        r-untouched (ex "read" {:path p})
        r-delete    (ex "apply_patch" {:patchText delp})
        r-gone      (ex "read" {:path p})
        r-redelete  (ex "apply_patch" {:patchText delp})]
    {:malformed r-malformed
     :add r-add :added-content r-added
     :update r-update :updated-content r-updated
     :hunk-mismatch r-bad :untouched-after-mismatch r-untouched
     :delete r-delete :gone-after-delete r-gone :delete-again r-redelete}))

(defn- fake-page-handler [req]
  (let [p (str (:path req))]
    (cond
      (= p "/page") {:status 200
                     :headers {"content-type" "text/html"}
                     :body "<html><body><h1>Title</h1><p>Hello <b>world</b></p></body></html>"}
      :else         {:status 404 :headers {"content-type" "text/plain"} :body "not found"})))

(defn- run-webfetch [tmp all]
  (let [srv  (server/serve fake-page-handler {:port 0})
        base (str "http://127.0.0.1:" (server/port srv))
        ex   (fn [a] (shape tmp (execute all "webfetch" a {})))]
    (try
      {:markdown (ex {:url (str base "/page")})
       :text     (ex {:url (str base "/page") :format "text"})
       :html     (ex {:url (str base "/page") :format "html"})
       :notfound (ex {:url (str base "/missing")})}
      (finally (server/stop! srv)))))

(defn- run-question [tmp all]
  (let [qs   [{:question "Pick a colour" :header "Colour" :options ["red" "green"]}
              {:question "Free text?"}]
        p1   (execute all "question" {:questions qs} {})
        ans  {:id "req-1" :ok true :data {:answers ["green" "hello"]}}
        p2   (execute all "question" {:questions qs} {:answer ans})
        decl (execute all "question" {:questions qs} {:answer {:id "req-1" :ok false}})]
    {:rendered  (render-questions qs)
     :suspended (shape tmp p1)
     :resumed   (shape tmp p2)
     :declined  (shape tmp decl)}))

(defn- run-assembly [builtins-opt]
  (let [mcp    [{:name "everything_echo" :source "mcp"} {:name "read" :source "mcp"}]
        skill  [{:name "skill" :source "skill"}]
        extras [{:name "bash" :source "native"} {:name "deploy" :source "native"}]]
    {:baseline
     (mapv (fn [t] [(:name t) (:source t)])
           (assemble {:mcp [] :skill skill :builtins builtins-opt :extras []}))
     :mcp-beats-builtin
     (mapv (fn [t] [(:name t) (:source t)])
           (assemble {:mcp mcp :skill skill :builtins builtins-opt :extras []}))
     :extras-shadow-builtin
     (mapv (fn [t] [(:name t) (:source t)])
           (assemble {:mcp mcp :skill skill :builtins builtins-opt :extras extras}))
     :per-tool-off
     (mapv (fn [t] [(:name t) (:source t)])
           (assemble {:mcp [] :skill skill :builtins {:tools {:bash false :apply_patch false}}
                      :extras []}))
     :source-off
     (mapv (fn [t] [(:name t) (:source t)])
           (assemble {:mcp mcp :skill skill :builtins false :extras extras}))}))

(defn- run-prompt-proof [skills-dir]
  (let [skills (discover-skills skills-dir)
        on     (system-message "You are a helpful agent." skills)
        ;; The same call, in a world where the builtin source is off. It cannot
        ;; differ: `system-message` has no builtin parameter. Measured anyway.
        off    (system-message "You are a helpful agent." skills)
        catalog-only skills-preamble]
    {:bytesOn        (utf8-count on)
     :bytesOff       (utf8-count off)
     :identical      (= on off)
     ;; Which builtin names appear anywhere in the prompt. The hello-world skill
     ;; body is NOT in the prompt (only its description is), so this is expected
     ;; to be empty — reported rather than asserted.
     :builtinNamesInPrompt (vec (filter (fn [n] (str/includes? on n)) builtin-names))
     :preambleMentionsBuiltin (vec (filter (fn [n] (str/includes? catalog-only n)) builtin-names))
     :schemaArrayCarriesThem
     {:openai    (mapv (fn [x] (get-in x [:function :name])) (to-openai builtin-tools))
      :anthropic (mapv :name (to-anthropic builtin-tools))
      :gemini    (mapv :name (:functionDeclarations (first (to-gemini builtin-tools))))}}))

(defn build-report [examples-dir]
  (let [tmp (str (str/replace (str (env/get-env "TMPDIR" "/tmp")) #"/$" "") "/toolnexus-s22")
        _   (rm-f! tmp)
        _   (proc/sh ["rm" "-rf" tmp])
        _   (mkdir-p! (str tmp "/work"))
        _   (mkdir-p! (str tmp "/patch"))
        all (enabled-builtins nil)
        ;; Every effectful step is a binding — see the note above run-fs-tools.
        ex        (fn [n a] (shape tmp (execute all n a {})))
        r-bash    (ex "bash" {:command "printf 'hi from bash\\n'"})
        r-bash-d  (ex "bash" {:command "basename \"$(pwd)\"" :workdir tmp})
        r-bash-f  (ex "bash" {:command "echo oops 1>&2; exit 7"})
        r-todo    (ex "todowrite" {:todos [{:id "1" :text "spike builtins" :completed true}
                                           {:id "2" :text "write README" :completed false}]})
        r-todo-0  (ex "todowrite" {:todos []})
        r-unknown (ex "nosuchtool" {})
        r-fs      (run-fs-tools tmp all)
        r-patch   (run-patch-tools tmp all)
        r-web     (run-webfetch tmp all)
        r-q       (run-question tmp all)]
    (try
      {:host    (name host/id)
       :spec    "0.11+4A"
       :names   builtin-names
       :sources (vec (sort (distinct (map :source builtin-tools))))
       :schemas (reduce (fn [m t] (assoc m (keyword (:name t)) (:inputSchema t))) {} builtin-tools)

       :exec
       (assoc r-fs
              :bash-ok r-bash :bash-workdir r-bash-d :bash-nonzero r-bash-f
              :todowrite r-todo :todowrite-empty r-todo-0
              :unknown-tool r-unknown)

       :patch    r-patch
       :webfetch r-web
       :question r-q

       :toggle
       [(toggle-case "absent (default)"            nil)
        (toggle-case "true"                        true)
        (toggle-case "false"                       false)
        (toggle-case "{disabled:true}"             {:disabled true})
        (toggle-case "{enabled:false}"             {:enabled false})
        (toggle-case "{disabled:true,enabled:true} (MCP precedence: disabled wins)"
                     {:disabled true :enabled true})
        (toggle-case "{disabled:false,enabled:false} (else enabled:false disables)"
                     {:disabled false :enabled false})
        (toggle-case "{disabled:false} (else on)"  {:disabled false})
        (toggle-case "{enabled:true}"              {:enabled true})
        (toggle-case "{tools:{bash:false}}"        {:tools {:bash false}})
        (toggle-case "{tools:{bash:true}} (all-on baseline, NOT an allowlist)"
                     {:tools {:bash true}})
        (toggle-case "{tools:{bash:false,write:false}}" {:tools {:bash false :write false}})
        (toggle-case "{tools:{nosuch:false}} (unknown ignored)" {:tools {:nosuch false}})
        (toggle-case "{disabled:true,tools:{bash:true}} (global-off short-circuits)"
                     {:disabled true :tools {:bash true}})
        (toggle-case "{enabled:false,tools:{bash:true}} (global-off short-circuits)"
                     {:enabled false :tools {:bash true}})
        (toggle-case "parsed from JSON config {\"builtins\":{\"tools\":{\"write\":false}}}"
                     (:builtins (json/read-str "{\"builtins\":{\"tools\":{\"write\":false}}}"
                                               {:key-fn keyword})))]

       :assembly (run-assembly nil)
       :prompt   (run-prompt-proof (str examples-dir "/skills"))

       ;; Carried in the diffed report on purpose: an unimplementable behaviour
       ;; is a fact about the port, and a fact about the port belongs where a
       ;; future change will notice it moving.
       :limits
       {:bash-timeout-enforced      false
        :koine-has-mkdir            false
        :koine-has-delete           false
        :posix-shellouts-used       ["mkdir -p" "rm -f" "rm -rf" "sh -c"]
        :apply-patch-hunks-per-file 1}}
      (finally (proc/sh ["rm" "-rf" tmp])))))

(defn -main [& _]
  (println (json/write-str (build-report (str (env/get-env "TN_EXAMPLES" "../../../examples"))))))
