(ns toolnexus.agents.home
  "Agent home — the persona surface (SPEC.md §7E).

  A persona is an identity that lives in FILES, keeps durable MEMORY it can edit,
  and runs on a HEARTBEAT so it can act unprompted. All three ride seams that
  already ship: the composed soul is just a system prompt, the memory tool is a
  plain `Tool`, and the heartbeat is `post` + `wake` on the §7D runtime's
  INJECTABLE CLOCK. This layer adds no runtime behavior — it is a directory
  convention plus composition, not new machinery.

      (def ava (home/from-dir \"./personas/ava\"))

      ;; one-shot, or as a tool in someone else's toolkit
      (def rt (rt/create-runtime {:registry {(:name ava) ava} :llm llm}))
      (rt/run-agent rt (:name ava) \"what is on my plate?\")
      (rt/agent-tool rt (:name ava))

      ;; …or give it its own clock
      (def started (home/start-agent ava {:llm llm} {:every-ms 1800000
                                                     :on-beat println}))
      ((:stop started))

  Everything here is also usable against a plain client with no runtime at all —
  a composed soul is a `:system-prompt`, and the memory tool is a tool like any
  other."
  (:require [clojure.string :as str]
            [koine.codec :as codec]
            [koine.fs :as fs]
            [koine.process :as proc]
            [koine.text :as ktext]
            [koine.time :as ktime]
            [toolnexus.agents.runtime :as rt]
            [toolnexus.native :as native]
            [toolnexus.skill :as skill]
            [toolnexus.tool :as tool]
            [toolnexus.transcript-search :as ts]))

(def bootstrap-order
  "Bootstrap discovery order (§7E): identity first, durable memory last. Each
  present file is injected into the soul as a `## <filename>` section, in exactly
  this order. The order is part of the cross-port contract — a persona whose
  MEMORY.md outranked its SOUL.md would behave differently in seven languages."
  ["AGENTS.md"
   "SOUL.md"
   "IDENTITY.md"
   "USER.md"
   "TOOLS.md"
   "HEARTBEAT.md"
   "MEMORY.md"])

(def max-file-bytes
  "Per-file bootstrap cap (§7E), measured in BYTES (2 MiB), not characters. A
  larger file is injected truncated; the file on disk is untouched."
  2097152)

(def truncation-notice
  "Notice appended to a bootstrap file truncated at `max-file-bytes`."
  "\n[truncated: exceeds 2 MB bootstrap cap]")

(def heartbeat-ok
  "The silent-no-op sentinel: a heartbeat reply containing this surfaces nothing."
  "HEARTBEAT_OK")

(def heartbeat-prompt
  "The prompt a heartbeat wakes the persona with. It contains `HEARTBEAT_OK` so
  the model knows the silent-reply contract, and the word \"Heartbeat\" so a
  HEARTBEAT.md that keys off it can recognise the trigger."
  (str "Heartbeat. Read your HEARTBEAT.md section and follow it. "
       "If nothing needs attention, reply " heartbeat-ok "."))

;; ---------------------------------------------------------------------------
;; Reading, with the byte cap
;; ---------------------------------------------------------------------------

(defn- read-capped
  "The file at `path` as a string, truncated to `max-file-bytes` BYTES; nil when
  the file is absent (absent bootstrap files are skipped, §7E).

  The cap is measured on the encoded bytes rather than on characters because the
  spec says bytes: a 2 MB budget that counted characters would admit twice the
  payload for a Devanagari persona and a different one for an ASCII persona.
  Truncation may split a multibyte character — that is spec'd, and the split byte
  decodes to the replacement character rather than throwing."
  [path]
  (when (fs/exists? path)
    (let [bs (fs/read-bytes path)]
      (if (<= (count bs) max-file-bytes)
        (fs/read-file path)
        ;; Base64 is the portable bytes->string seam koine gives both hosts;
        ;; `String.` is java.lang and Go's []byte->string is not reachable from
        ;; portable Clojure, so the round trip is what keeps this one source tree.
        (str (codec/decode (codec/encode (byte-array (take max-file-bytes (seq bs)))))
             truncation-notice)))))

;; ---------------------------------------------------------------------------
;; The MEMORY.md index cap (§7E "Memory hierarchy") — opt-in
;; ---------------------------------------------------------------------------

(def memory-index-cap-preset
  "`:memory-index-cap true` ⇒ this preset: 200 lines / 25600 bytes."
  {:max-lines 200 :max-bytes 25600})

(defn- resolve-index-cap
  "nil/false ⇒ nil (off); true ⇒ the preset; a map's missing field ⇒ the
  preset's. A value < 1 or non-integer is a construction error."
  [c]
  (when (and (some? c) (not (false? c)))
    (let [v  (if (true? c) {} c)
          ml (if (contains? v :max-lines) (:max-lines v) (:max-lines memory-index-cap-preset))
          mb (if (contains? v :max-bytes) (:max-bytes v) (:max-bytes memory-index-cap-preset))]
      (when-not (and (integer? ml) (>= ml 1) (integer? mb) (>= mb 1))
        (throw (ex-info "memoryIndexCap: maxLines and maxBytes must be integers >= 1" {})))
      {:max-lines ml :max-bytes mb})))

(defn memory-index-notice
  "The exact notice appended to a capped MEMORY.md (§7E). `<N>`/`<B>` are the
  CAP values, not the kept counts."
  [max-lines max-bytes notes?]
  (str "\n\n[MEMORY.md index truncated at " max-lines " lines / " max-bytes
       " bytes. Keep MEMORY.md a short index"
       (if notes?
         "; move detail into notes (memory target \"note:<slug>\")."
         " (keep entries short).")
       "]"))

(defn- cut-utf8
  "`s` cut at the last whole code point whose UTF-8 end is <= `max` bytes."
  [s max]
  (loop [cps (ts/code-point-strings s) out [] bytes 0]
    (if-let [c (first cps)]
      (let [n (ktext/utf8-length c)]
        (if (> (+ bytes n) max)
          (apply str out)
          (recur (rest cps) (conj out c) (+ bytes n))))
      (apply str out))))

(defn apply-index-cap
  "The §7E index-cap algorithm over MEMORY.md's (already 2 MB-capped) content.
  Within both bounds ⇒ unchanged; else keep whole leading lines (a lone
  over-long first line is cut at a code-point boundary) + the notice."
  [content {:keys [max-lines max-bytes]} notes?]
  (let [lines (str/split content #"\n" -1)
        lines (if (str/ends-with? content "\n") (vec (butlast lines)) (vec lines))
        bytes (fn [ls] (ktext/utf8-length (str/join "\n" ls)))]
    (if (and (<= (count lines) max-lines) (<= (bytes lines) max-bytes))
      content
      (let [kept (loop [k (vec (take max-lines lines))]
                   (if (and (> (count k) 1) (> (bytes k) max-bytes)) (recur (pop k)) k))
            text (str/join "\n" kept)
            text (if (> (ktext/utf8-length text) max-bytes) (cut-utf8 text max-bytes) text)]
        (str text (memory-index-notice max-lines max-bytes notes?))))))

(defn compose-soul
  "Compose the bootstrap files present in `dir` into one soul string — the frozen
  snapshot injected as the system prompt for a whole run.

  Returns `{:soul \"…\" :found [\"SOUL.md\" …]}`. Only present files appear, always
  in `bootstrap-order`, each as a `## <filename>` section with its body trimmed.
  Composition happens once, at session start: the soul is fixed for the run, which
  is what keeps a long-lived persona cache-stable.

  Options (§7E \"Memory hierarchy\", both opt-in — absent ⇒ byte-identical):

    :memory-index-cap  true (200 lines / 25600 bytes) or {:max-lines :max-bytes};
                       caps MEMORY.md only, after the 2 MB cap, before the trim
    :notes             notes tier on — selects the notice wording"
  ([dir] (compose-soul dir {}))
  ([dir opts]
   (let [cap     (resolve-index-cap (:memory-index-cap opts))
         entries (keep (fn [file]
                         (when-let [body (read-capped (str dir "/" file))]
                           (let [body (if (and cap (= file "MEMORY.md"))
                                        (apply-index-cap body cap (true? (:notes opts)))
                                        body)]
                             [file (str "## " file "\n\n" (str/trim body))])))
                       bootstrap-order)]
     {:soul  (str/join "\n\n" (map second entries))
      :found (mapv first entries)})))

;; ---------------------------------------------------------------------------
;; The `memory` builtin (§7E) — file-backed, opt-in
;; ---------------------------------------------------------------------------

(def memory-tool-description
  (str "Persist durable memory. action=add appends an entry; replace swaps an "
       "existing substring (with); remove deletes one. target=self (MEMORY.md, "
       "default) or user (USER.md). Writes persist to disk and load at the START of "
       "your NEXT session — they do NOT change your current context."))

(def memory-tool-input-schema
  {:type       "object"
   :properties {:action {:type "string" :enum ["add" "replace" "remove"]}
                :target {:type "string" :enum ["self" "user"]
                         :description "self=MEMORY.md (default), user=USER.md"}
                :text   {:type "string"
                         :description "For add: the entry. For replace/remove: the existing text."}
                :with   {:type "string" :description "For replace: the replacement text."}}
   :required   ["action" "text"]})

(def memory-notes-description
  "The notes-on description (§7E, exact): says what memory is FOR."
  (str "Persist durable memory. MEMORY.md is a short index loaded at the start of every "
       "session; put detail in notes (target note:<slug>, stored in memory/<slug>.md, never "
       "loaded automatically) and point to them from the index. Record what will change a "
       "future decision. action=add appends an entry; replace swaps the first occurrence of "
       "an existing substring (with); remove deletes it; read returns a file; list lists the "
       "notes. target=self (MEMORY.md, default), user (USER.md) or note:<slug> (slug: "
       "lowercase letters, digits, '.', '_', '-'). Writes persist to disk and load at the "
       "START of your NEXT session — they do NOT change your current context."))

(def memory-notes-input-schema
  {:type       "object"
   :properties {:action {:type "string" :enum ["add" "replace" "remove" "read" "list"]}
                :target {:type "string"
                         :description "self=MEMORY.md (default), user=USER.md, note:<slug>=memory/<slug>.md"}
                :text   {:type "string"
                         :description "For add: the entry. For replace/remove: the existing text."}
                :with   {:type "string" :description "For replace: the replacement text."}}
   :required   ["action"]})

(defn valid-note-slug?
  "§7E: `^[a-z0-9][a-z0-9._-]{0,63}$` and no `..` — no separators, no traversal."
  [slug]
  (boolean (and (string? slug)
                (re-matches #"[a-z0-9][a-z0-9._-]{0,63}" slug)
                (not (str/includes? slug "..")))))

(defn- memory-file [dir target]
  (str dir "/" (if (= "user" (str target)) "USER.md" "MEMORY.md")))

;; ---------------------------------------------------------------------------
;; Write safety (§7E) — per-path serialization within this runtime/process
;; ---------------------------------------------------------------------------
;;
;; Read-modify-write is serialized per RESOLVED ABSOLUTE PATH: two memory tools
;; over one home in one process lose nothing. koine has no mutex, and `locking`
;; is a JVM special form, so the lock is the port's portable one — a
;; compare-and-set! spin on a per-path atom (the same pattern toolnexus.mcp and
;; toolnexus.acp use). Limits, stated: in-process only (another process's write
;; is not seen — the last write wins), and the lock map grows by one atom per
;; distinct path ever written.
;;
;; KNOWN GAP (clojure only): the write itself is NOT atomic. §7E asks for temp
;; file + fsync + rename; koine.fs has no rename/fsync and this port forbids
;; interop, so a crash mid-write can leave a torn file. Tracked in
;; openspec/changes/add-memory-hierarchy/tasks.md.

(def ^:private path-locks (atom {}))

(defn- lock-key
  "The per-path lock key is the resolved REAL path (§7E): symlinks resolved, so a
  tool over a symlinked home and one over the real path share one lock. A file
  that does not exist yet resolves its nearest existing ancestor and appends the
  rest; failing that, the path as given."
  [file]
  (try
    (if (fs/exists? file)
      (fs/real-path file)
      (loop [dir (skill/parent-dir file) tail [(skill/file-name file)]]
        (cond
          (or (nil? dir) (= "" dir) (= dir file)) file
          (fs/exists? dir) (str (fs/real-path dir) "/" (str/join "/" tail))
          :else (let [up (skill/parent-dir dir)]
                  (if (or (nil? up) (= up dir))
                    file
                    (recur up (cons (skill/file-name dir) tail)))))))
    (catch Throwable _ file)))

(defn- with-path-lock [file f]
  (let [k    (lock-key file)
        lock (get (swap! path-locks (fn [m] (if (contains? m k) m (assoc m k (atom false))))) k)]
    (loop []
      (if (compare-and-set! lock false true)
        (try (f) (finally (reset! lock false)))
        (do (ktime/sleep! 1) (recur))))))

(defn- replace-first-literal
  "`s` with the first occurrence of `needle` swapped for `with`, verbatim; nil
  when `needle` is absent."
  [s needle with]
  (when-let [i (str/index-of s needle)]
    (str (subs s 0 i) with (subs s (+ i (count needle))))))

(defn- rmw!
  "Read-modify-write over the FULL file (never the capped view — the cap is a
  soul-injection cap; editing the capped view would truncate the file on disk).
  Returns nil on success, or a failed ToolResult."
  [file action text with-text]
  (with-path-lock
    file
    (fn []
      (let [current (if (fs/exists? file) (fs/read-file file) "")
            next-s  (case action
                      "add"     (str/triml (str (str/trimr current) "\n- " text "\n"))
                      ;; FIRST occurrence only (§7E)
                      ;; FIRST occurrence, `with` inserted LITERALLY — splice by
                      ;; index, so no host's replacement-string syntax ($&, $1,
                      ;; \\1 …) can ever expand.
                      "replace" (replace-first-literal current text (str (or with-text "")))
                      "remove"  (replace-first-literal current text "")
                      nil)]
        (cond
          (and (nil? next-s) (contains? #{"replace" "remove"} action))
          (tool/failure (str "not found: " text))

          (nil? next-s)
          (tool/failure (str "unknown action: " action))

          :else
          (do (fs/mkdirs! (skill/parent-dir file))
              (fs/write-file file next-s)
              nil))))))

;; koine.fs has no lstat (KNOWN GAP, clojure only). Without following links is
;; approximated: a path is a regular note when it exists, is not a directory,
;; and its real path is exactly <real notes dir>/<name> — a symlink (to a file
;; anywhere, even a sibling) resolves elsewhere, and a dangling one does not
;; exist. Never throws. Residual difference: a FIFO/socket named x.md is not
;; distinguishable from a regular file here.
(defn- regular-note? [real-dir p]
  (try
    (and (fs/exists? p)
         (not (fs/directory? p))
         (= (fs/real-path p) (str real-dir "/" (skill/file-name p))))
    (catch Throwable _ false)))

(defn- list-notes
  "`list` (§7E): `<slug>\\t<bytes>` per regular file memory/<slug>.md with a valid
  slug, sorted by slug by EXPLICIT code-point sort; `(no notes)` when none."
  [notes-dir]
  (let [real-dir (when (fs/directory? notes-dir)
                   (try (fs/real-path notes-dir) (catch Throwable _ nil)))
        rows (when real-dir
               (keep (fn [p]
                       (let [p (str p)]
                         (when (and (str/starts-with? p (str notes-dir "/"))
                                    (str/ends-with? p ".md"))
                           (let [nm   (subs p (inc (count notes-dir)))
                                 slug (subs nm 0 (- (count nm) 3))]
                             (when (and (not (str/includes? nm "/"))
                                        (valid-note-slug? slug)
                                        (regular-note? real-dir p))
                               [slug (count (fs/read-bytes p))])))))
                     (fs/list-tree notes-dir)))
        by   (into {} rows)
        slugs (ktext/sort-strings (keys by))]
    (if (empty? slugs)
      "(no notes)"
      (str/join "\n" (map (fn [s] (str s "\t" (get by s))) slugs)))))

(defn- notes-memory-tool [dir]
  (let [notes-dir (str dir "/memory")]
    (native/native-tool
     {:name         "memory"
      :description  memory-notes-description
      :input-schema memory-notes-input-schema
      :run
      (fn [args]
        (let [action (:action args)]
          (if (= "list" action)
            (list-notes notes-dir)
            (let [target (if (some? (:target args)) (str (:target args)) "self")
                  [file label err]
                  (cond
                    (contains? #{"self" "user"} target)
                    (let [f (memory-file dir target)] [f (skill/file-name f) nil])

                    (str/starts-with? target "note:")
                    (let [slug (subs target 5)]
                      (if (valid-note-slug? slug)
                        [(str notes-dir "/" slug ".md") (str "memory/" slug ".md") nil]
                        [nil nil (tool/failure (str "invalid note slug: " slug))]))

                    :else [nil nil (tool/failure (str "unknown target: " target))])]
              (cond
                err err

                (= "read" action)
                (or (read-capped file) (tool/failure (str "not found: " target)))

                (not (contains? #{"add" "replace" "remove"} action))
                (tool/failure (str "unknown action: " action))

                (not (string? (:text args)))
                (tool/failure "text is required")

                :else
                (or (rmw! file action (:text args) (:with args))
                    (if (str/starts-with? label "memory/")
                      (str "ok (" action " → " label ")")
                      (str "ok (" action " → " label "); loads next session"))))))))})))

(defn memory-tool
  "The `memory` builtin (§7E) — file-backed and OPT-IN. Not one of the default
  §4A builtins: it exists only when a home directory is wired.

  One tool, three actions over `MEMORY.md` (the agent's own notes) and `USER.md`
  (its model of the user):

    add      append an entry
    replace  swap the FIRST occurrence of an existing substring for `with`
    remove   delete the FIRST occurrence of an existing substring

  With `{:notes true}` it also takes `target \"note:<slug>\"` (`<dir>/memory/<slug>.md`,
  never injected into the soul) and two read actions, `read` and `list`. Without
  it, the tool is byte-identical to the pre-hierarchy tool.

  Every action writes to DISK. It does NOT touch the live session's system
  prompt: under the frozen-snapshot rule the edit loads at the START of the next
  session. A `replace`/`remove` whose substring is absent is a loud `isError`.
  Writes over one path are serialized within this process (see `with-path-lock`)."
  ([dir] (memory-tool dir {}))
  ([dir {:keys [notes]}]
   (if (true? notes)
     (notes-memory-tool dir)
     (native/native-tool
      {:name         "memory"
       :description  memory-tool-description
       :input-schema memory-tool-input-schema
       :run
       (fn [args]
         (let [file   (memory-file dir (or (:target args) "self"))
               action (str (:action args))]
           (or (rmw! file action (str (:text args)) (:with args))
               (str "ok (" action " → " (skill/file-name file) "); loads next session"))))}))))

;; ---------------------------------------------------------------------------
;; Dreaming (§7E) — the dreamer preset
;; ---------------------------------------------------------------------------

(def dream-prompt
  "What a dream pass is FOR and the order it works in (§7E \"Dreaming\").
  Byte-identical in every port. It becomes the dreamer's `## HEARTBEAT.md`
  section, so the ordinary `heartbeat-prompt` wake drives a pass and
  `HEARTBEAT_OK` keeps an idle pass silent."
  (str/join
   "\n"
   ["You are dreaming: an offline pass over your own memory. Memory exists so that a future session decides better. MEMORY.md is a short index loaded at the start of every session; notes (memory target \"note:<slug>\") hold the detail; the transcripts (transcript_search) are the ground truth of what actually happened."
    "Work in this order:"
    "1. Verify: read MEMORY.md and the notes it points to (memory read, memory list) and check each claim against the transcripts. Fix a claim the transcripts contradict; remove one they no longer support."
    "2. Demote: move detail out of MEMORY.md into a note and leave a one-line [[slug]] pointer, so the index stays short."
    "3. Merge: fold duplicate or conflicting notes into one."
    "4. Repair links: point every [[slug]] at an existing note or create it; give every note an index line."
    "5. Log: if you changed anything, append exactly one entry with memory add, target \"note:log\", text starting \"dream: \" and naming every change and why."
    "If nothing needs changing, write nothing and reply HEARTBEAT_OK."]))

(defn dreamer
  "The built-in dreamer preset (§7E \"Dreaming\"): a plain AgentDef whose tools
  are EXACTLY `memory` (notes on, over `dir`) and `transcript_search` (over
  `:store`) — never the persona's own tools. Its soul is one `## HEARTBEAT.md`
  section: `dream-prompt`, then `<dir>/DREAM.md` (trimmed, if non-empty), then
  `:instructions` (trimmed, if non-empty), joined by blank lines.

  Options: `:store` (required; must carry `:list`), `:instructions`, `:name`
  (default `<basename(dir)>-dreamer`), `:model` (default `\"inherit\"`).

  Run one pass with `(rt/run-agent rt name heartbeat-prompt)` or schedule it
  with `start-agent`."
  [dir opts]
  (when-not (and (map? opts) (some? (:store opts)))
    (throw (ex-info "dreamer: store is required" {})))
  (let [dir    (str/replace (str dir) #"/+$" "")
        search (ts/transcript-search-tool (:store opts))
        dmd    (some-> (read-capped (str dir "/DREAM.md")) str/trim)
        extra  (some-> (:instructions opts) str str/trim)
        parts  (cond-> [dream-prompt]
                 (seq dmd)   (conj dmd)
                 (seq extra) (conj extra))]
    {:name  (or (:name opts) (str (skill/file-name dir) "-dreamer"))
     :does  (str "dreams over the memory in " dir)
     :soul  (str "## HEARTBEAT.md\n\n" (str/join "\n\n" parts))
     :model (or (:model opts) "inherit")
     :tools [(memory-tool dir {:notes true}) search]}))

;; ---------------------------------------------------------------------------
;; The directory IS the agent (§7E)
;; ---------------------------------------------------------------------------

(defn from-dir
  "Build a persona AgentDef from a home directory (§7E).

  Discovers the bootstrap files in `dir`, composes them into a FROZEN soul
  snapshot (once, here — the soul is fixed for the whole run, which is what keeps
  a long-lived persona cache-stable), and wires a `memory` tool over the same
  directory unless `:memory false` asks for a read-only persona.

  Options:

    :name              the agent name; default the directory's last segment
    :does              the routing description a delegating model sees
    :model             default `\"inherit\"` — the runtime's own `:llm` model
    :tools             extra tools, placed BEFORE the memory tool
    :memory            `false` omits the memory tool
    :memory-index-cap  cap the injected MEMORY.md (`true` ⇒ 200 lines / 25600
                       bytes, or `{:max-lines :max-bytes}`); off by default
    :notes             notes tier of the memory tool (`note:<slug>`, read, list)
    :dream             `{:every-ms :store :instructions}` — when this persona is
                       started with `start-agent`, a `dreamer` over the same dir
                       runs on the same runtime every `:every-ms`

  The return value is a plain AgentDef map, which is this port's spelling of
  §7D's Level-1 agent: put it in a runtime's `:registry` and both directions of
  the axiom work on it unchanged — `(rt/run-agent rt name prompt)` is `.run`, and
  `(rt/agent-tool rt name)` is `.asTool`. There is no Agent object to learn,
  because a def IS the agent here. `run`/`agent-tool` never dream."
  ([dir] (from-dir dir {}))
  ;; `:name` is read off the map rather than destructured: binding a local called
  ;; `name` would shadow `clojure.core/name`, and cljgo's core carries MORE names
  ;; than the JVM's, so shadowing is never a local decision here.
  ([dir {:keys [does model tools memory notes dream] :as opts}]
   (let [dir  (str/replace (str dir) #"/+$" "")
         nm   (or (:name opts) (skill/file-name dir))
         persona {:name  nm
                  :does  (or does (str "persona agent from " dir))
                  :soul  (:soul (compose-soul dir {:memory-index-cap (:memory-index-cap opts)
                                                   :notes notes}))
                  :model (or model "inherit")
                  :tools (cond-> (vec tools)
                           (not (false? memory)) (conj (memory-tool dir {:notes notes})))}]
     (if (some? dream)
       (let [every (:every-ms dream)]
         (when-not (and (number? every) (pos? every))
           (throw (ex-info "fromDir: dream.everyMs must be > 0" {})))
         (assoc persona ::dream
                {:every-ms every
                 :dreamer  (dreamer dir {:store        (:store dream)
                                         :instructions (:instructions dream)
                                         :name         (str nm "-dreamer")})}))
       persona))))

;; ---------------------------------------------------------------------------
;; The heartbeat (§7E)
;; ---------------------------------------------------------------------------

(defn- heartbeat!
  "One self-rescheduling heartbeat on handle `h`; returns its cancel fn."
  [runtime h every-ms stopped on-text]
  (let [cancel (atom nil)
        clock  (:clock runtime)]
    (letfn [(collect! []
              ;; The wait runs off the beat thread so a slow turn cannot delay
              ;; the next tick. `run-async!`, never `future` — a library may not
              ;; hold its consumer's process open.
              (proc/run-async!
               (fn []
                 (let [r (rt/wait runtime h)]
                   (when (and (not (:isError r))
                              (not (str/includes? (str (:text r)) heartbeat-ok)))
                     (on-text (:text r)))))))
            (beat []
              (when-not @stopped
                (rt/post runtime h {:from "clock" :channel "timer" :text "tick"})
                ;; Only at idle. A persona still working through the last beat
                ;; just accumulates one coalesced tick and picks it up on its
                ;; next turn — the unsolicited rail, exactly as §7D writes it.
                (when (= "idle" (:state (rt/inspect runtime h)))
                  (when (:ok (rt/wake runtime h heartbeat-prompt))
                    (collect!)))
                ;; Self-reschedule, so there is only ever ONE live timer.
                (reset! cancel ((:set-timeout clock) beat every-ms))))]
      (reset! cancel ((:set-timeout clock) beat every-ms))
      (fn [] (when-let [c @cancel] (c))))))

(defn start-agent
  "Give a persona its own clock (§7E).

  On each `:every-ms` interval the persona `post`s a tick to its OWN inbox — the
  unsolicited rail, where timer ticks COALESCE, so a beat slower than the turn it
  starts can never pile up — and, WHEN IDLE, `wake`s it with `heartbeat-prompt`.
  A reply containing `heartbeat-ok` is SILENT: only a substantive reply is
  collected and handed to `:on-beat`. Silence is the default, which is the whole
  point — a persona that reported every beat would be a cron job with a bill.

  Every timer goes through the RUNTIME'S INJECTABLE CLOCK, never a sleep: pass
  `{:clock (rt/virtual-clock)}` in `run-opts` and a fixture drives the beats with
  `((:advance! clock) ms)`, deterministically.

  `run-opts` is anything `rt/create-runtime` takes except `:registry`, which is
  derived from `agent-def` (plus any registry the caller supplies, so a persona
  with a `:team` still resolves its team-mates).

  A persona built by `from-dir` with `:dream` also gets its dreamer registered
  on the SAME runtime, spawned under its root, on its own heartbeat at the
  dream's `:every-ms`. Dreamer replies never reach `:on-beat` / `:beats` — what
  a dream did is in memory/log.md. `:stop` stops both.

  Returns:

    :runtime  the live runtime — the host's seam for INBOUND channels. §7E is
              explicit that channels are the host's job: deliver an external
              event by calling `rt/post` / `rt/wake` on `:handle`
    :handle   the persona's handle id
    :beats    an atom holding the substantive beats so far (HEARTBEAT_OK
              excluded)
    :stop     `(fn [])` — cancel the heartbeat(s) and close the tree gracefully

  Throws only if the persona cannot be spawned at all; a spawn failure is a
  configuration error at the root, which is the one place §7D permits a throw."
  [agent-def run-opts {:keys [every-ms on-beat]}]
  (let [nm      (:name agent-def)
        dream   (::dream agent-def)
        persona (dissoc agent-def ::dream)
        base    (assoc (:registry run-opts) nm persona)
        _       (when (and dream (contains? base (:name (:dreamer dream))))
                  (throw (ex-info (str "startAgent: dreamer name collision: "
                                       (:name (:dreamer dream)) " is already registered")
                                  {:agent (:name (:dreamer dream))})))
        reg     (cond-> base
                  dream (assoc (:name (:dreamer dream)) (:dreamer dream)))
        runtime (rt/create-runtime (assoc run-opts :registry reg))
        h       (rt/spawn runtime rt/root nm)]
    (when (rt/verb-error? h) (throw (ex-info (:error h) {:agent nm})))
    (let [beats   (atom [])
          stopped (atom false)
          cancels (atom [(heartbeat! runtime h every-ms stopped
                                     (fn [text]
                                       (swap! beats conj text)
                                       (when on-beat (on-beat text))))])]
      (when dream
        (let [dh (rt/spawn runtime rt/root (:name (:dreamer dream)))]
          (when (rt/verb-error? dh)
            ;; Never leak the persona's heartbeat when the dreamer cannot start.
            (reset! stopped true)
            (doseq [c @cancels] (c))
            (rt/close runtime rt/root)
            (throw (ex-info (:error dh) {:agent (:name (:dreamer dream))})))
          ;; Housekeeping: a dream's reply never reaches on-beat.
          (swap! cancels conj (heartbeat! runtime dh (:every-ms dream) stopped (fn [_] nil)))))
      {:runtime runtime
       :handle  h
       :beats   beats
       :stop    (fn []
                  (reset! stopped true)
                  (doseq [c @cancels] (c))
                  (rt/close runtime rt/root)
                  nil)})))
