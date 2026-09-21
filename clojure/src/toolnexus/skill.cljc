;; The agent-skill source — SPEC §0.5, §0.6 and §3.
;;
;; Discover `**/SKILL.md` under one or more roots, parse the `---`-fenced
;; frontmatter, and expose ONE `skill` tool that injects a skill's instructions
;; plus a sampled list of its sibling files (progressive disclosure).
;;
;; The reference implementations are `golang/skill.go` and `js/src/skill.ts`.
;; Where SPEC prose and the shipped ports disagree, THIS FILE FOLLOWS THE PORTS
;; — byte-parity is measured against them, not against prose. Each such place is
;; called out inline.
;;
;; ZERO reader conditionals. ZERO `java.*`. koine + clojure.core only.
(ns toolnexus.skill
  (:require [clojure.string :as str]
            [koine.fs :as fs]
            [toolnexus.frontmatter :as frontmatter]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; Constants that must be byte-identical across all ports (SPEC §3)
;; ---------------------------------------------------------------------------

(def skills-prompt-preamble
  "SPEC §3 — the fixed instruction preamble, copied verbatim from
  `golang/skill.go`'s `SkillsPromptPreamble`. Do not reword."
  (str "Skills provide specialized instructions and workflows for specific tasks.\n"
       "Use the skill tool to load a skill when a task matches its description."))

(def no-skills-message "No skills are currently available.")

(def skill-tool-description
  "SPEC §3 skill.txt — verbatim from opencode."
  (str "Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.\n"
       "\n"
       "Use this tool to inject the skill's instructions and resources into current conversation. "
       "The output may contain detailed workflow guidance as well as references to scripts, files, "
       "etc in the same directory as the skill.\n"
       "\n"
       "The skill name must match one of the skills listed in your system prompt."))

(def skill-tool-input-schema
  {:type       "object"
   :properties {:name {:type "string" :description "The name of the skill to load"}}
   :required   ["name"]
   :additionalProperties false})

(def default-sample-limit 10)

;; ---------------------------------------------------------------------------
;; Portable path helpers — no java.io.File, no filepath
;; ---------------------------------------------------------------------------

(defn- last-sep
  "Index of the last `/`, via the text seam — NEVER `clojure.string/last-index-of`,
  which returns a BYTE offset on the cljgo host while `subs` works in runes. See
  `toolnexus.tool/last-index-of-char` for the measurement."
  [s]
  (tool/last-index-of-char s \/))

(defn parent-dir
  "The directory part of `path`, or \".\" when there is no separator."
  [path]
  (let [p   (str path)
        idx (last-sep p)]
    (if idx (subs p 0 idx) ".")))

(defn file-name
  "The last segment of `path`. NOT named `base-name`/`basename` — nothing in
  clojure.core collides, but the name is kept explicit for readability."
  [path]
  (let [p   (str path)
        idx (last-sep p)]
    (if idx (subs p (inc idx)) p)))

;; ---------------------------------------------------------------------------
;; Discovery (SPEC §0.5 / §3)
;; ---------------------------------------------------------------------------

(defn- ignored-path? [path]
  (or (str/includes? (str path) "/node_modules/")
      (str/includes? (str path) "/.git/")))

(defn- parse-skill-file
  "Read + parse one SKILL.md into `{:info …}` or `{:skip {:location :reason :detail}}`.

  `toolnexus.frontmatter/read-frontmatter` NEVER throws: it reads the file with
  a real YAML parser (`toolnexus.yaml`) and, only when YAML has already refused
  the frontmatter, rescues `name`/`description` line-wise (ADR 0028, issue #93).
  A file neither path can name is a typed skip carrying `:detail` — the YAML
  parser's own message, e.g. \"tabs are not allowed as indentation at line 3\" —
  so a host can fix the file instead of guessing. `:reason` stays byte-identical
  across ports; `:detail` is native and is never compared for parity.

  The DIVERGENCE this port used to record here — block scalars, sequences,
  nested maps and anchors rejected by a hand-rolled subset — is GONE. Measured
  over ~/.claude/skills it cost 42 of 87 files."
  [path]
  (let [text (try (fs/read-file path) (catch Throwable _ ::unreadable))]
    (if (= ::unreadable text)
      {:skip {:location path :reason "unreadable"}}
      (let [r    (frontmatter/read-frontmatter text)
            data (:data r)
            body (:body r)]
        (if (:reason r)
          (cond-> {:skip {:location path :reason (:reason r)}}
            (:detail r) (assoc-in [:skip :detail] (:detail r)))
          (let [dir (parent-dir path)]
            {:info {:name        (:name data)
                    ;; ABSENT description (nil) and EMPTY description ("") are
                    ;; different: js/src/skill.ts filters the prompt catalog on
                    ;; `description !== undefined`, golang/skill.go on
                    ;; `!= ""`. Those two shipped ports genuinely disagree for
                    ;; `description:` with an empty value; we follow JS.
                    :description (:description data)
                    :location    path
                    :content     body
                    :dir         dir
                    ;; js/src/skill.ts uses pathToFileURL(dir).href, which for
                    ;; an absolute POSIX path is exactly "file://" + dir.
                    :base        (str "file://" dir)
                    :origin      "fs"}}))))))

;; ---------------------------------------------------------------------------
;; §3 S1 — skills supplied as DATA (no filesystem) and §3 S4 — a logical base
;; ---------------------------------------------------------------------------

(defn- def->candidate
  "One caller-supplied skill definition as a candidate.

  `{:name :description :content :resources :base}`. A def with no `name` is the
  same typed skip a nameless SKILL.md is, located at its base (js/src/skill.ts
  `candidatesFromDefs`: `d.base ?? \"skill://\"`)."
  [d]
  (if (str/blank? (str (:name d)))
    {:skip {:location (if (str/blank? (str (:base d))) "skill://" (str (:base d)))
            :reason   "missing-name"}}
    (let [base (if (str/blank? (str (:base d)))
                 (str "skill://" (:name d) "/")
                 (str (:base d)))]
      {:info {:name        (str (:name d))
              :description (:description d)
              :location    base
              :content     (or (:content d) "")
              ;; `:dir` is what reaches the ToolResult metadata; for a logical
              ;; skill that IS the base, so no host path can leak from a source
              ;; that never touched a disk.
              :dir         base
              :base        base
              :origin      "logical"
              :resources   (vec (:resources d))}})))

(defn- path-depth
  "Number of segments in a relative discovery path. `/` only — `find-files`
  hands back `/`-separated paths on both hosts, so there is nothing
  host-specific to normalise here."
  [rel-path]
  (count (remove str/blank? (str/split rel-path #"/"))))

(defn discovery-compare
  "The §3 discovery order over two paths RELATIVE to a root's logical base:
  DEPTH ascending, then Unicode CODE POINT (addendum A15, correcting A1a).

  WHY DEPTH FIRST, and why A1a alone was wrong. §3 and the OpenSpec scenario
  both say a SHALLOWER path beats a nested copy of the same name. A pure
  code-point sort does not deliver that — it delivers it only for names that
  happen to sort before the nested directory's first segment:

      docx -> docx/            (d < s, top-level wins)
      xlsx -> synced/…/xlsx    (x > s, the NESTED copy wins)

  So the winner depended on the skill's first letter relative to a sibling
  directory's name, which is indefensible and is not what anyone intended. The
  external consumer found it on a real corpus. Sorting by depth first makes the
  rule that was already written down actually true, for every name.

  A1a/A1c are unchanged and remain the TIE-BREAK WITHIN a depth: code POINT,
  via `tool/compare-strings`, never a locale collator, never case folding,
  never `compare` (which is UTF-16 code-UNIT order on the JVM host and
  disagrees for anything above U+FFFF)."
  [a b]
  (let [d (compare (path-depth a) (path-depth b))]
    (if (zero? d) (tool/compare-strings a b) d)))

(defn candidates
  "Every SKILL.md under `root`, in DISCOVERY ORDER, as a parsed skill or a typed
  skip.

  DISCOVERY ORDER is pinned, not inherited: `discovery-compare` over the path
  RELATIVE to this root's logical base — depth, then code point (A15/A1c). A
  symlink sorts at the path it was DISCOVERED at, not at its target, because
  that is the only one of the two a caller can see.

  It is sorted HERE rather than relied upon from `koine.fs/find-files` even
  though find-files sorts: first-name-wins is order-dependent, a rule that
  decides which duplicate survives may not rest on another library's promise,
  and find-files' own order is plain lexicographic — which is precisely the
  order A15 corrects.

  Note find-files takes a SUFFIX, not a glob, so the suffix is \"/SKILL.md\" —
  a bare \"SKILL.md\" would also match a file named MYSKILL.md."
  [root]
  (if-not (fs/exists? root)
    []
    (let [base (str root)
          rel  (fn [p] (let [p (str p)]
                         (if (str/starts-with? p base) (subs p (count base)) p)))]
      (->> (fs/find-files root "/SKILL.md")
           (map str)
           (remove ignored-path?)
           (sort-by rel discovery-compare)
           (mapv parse-skill-file)))))

(defn merge-candidates
  "Dedupe by name, FIRST WINS; later duplicates become typed skips.
  Returns `{:skills [info …] :by-name {name info} :skipped [skip …]}`."
  [cands]
  (reduce (fn [acc c]
            (cond
              (:skip c) (update acc :skipped conj (:skip c))

              (contains? (:by-name acc) (:name (:info c)))
              (update acc :skipped conj {:location (:location (:info c))
                                         :reason   "duplicate-name"})

              :else
              (-> acc
                  (update :skills conj (:info c))
                  (assoc-in [:by-name (:name (:info c))] (:info c)))))
          {:skills [] :by-name {} :skipped []}
          cands))

(defn- warn!
  "The port's one log line. Everything it can say is already data on the
  returned map, so nothing here is load-bearing — it exists because §3 S2 asks
  for a warn-level record of an allowlist name that matched nothing."
  [msg]
  (println (str "[toolnexus] " msg)))

(def ^:private load-opt-keys
  "The keys that make a map an OPTIONS map rather than a single skill def.
  Mirrors js/src/skill.ts `LoadSkillsOptions`."
  #{:dirs :skills :filter :sample-limit})

(defn load-opts
  "`load-skills` accepts every shape js/src/skill.ts' `loadSkills` accepts, plus
  the two data shapes: a root string, a seq of roots, a seq of skill defs, one
  skill def, or the full options map."
  [input]
  (cond
    (nil? input)     {}
    (string? input)  {:dirs [input]}
    (and (map? input) (some (fn [k] (contains? input k)) load-opt-keys)) input
    (map? input)     {:skills [input]}
    (sequential? input) (if (map? (first input))
                          {:skills (vec input)}
                          {:dirs (vec input)})
    :else            {:dirs [(str input)]}))

(defn- filter-name
  "A filter key as a plain skill name. A caller writing Clojure reaches for
  keywords; a caller reading a JSON config has strings. Both mean the name."
  [k]
  (if (keyword? k) (name k) (str k)))

(defn apply-skills-filter
  "SPEC §3 S2 — the per-agent allowlist, semantics IDENTICAL to the MCP
  per-server tools filter: nil or empty ⇒ every skill; at least one `true` ⇒ an
  allowlist of exactly the true-mapped names; only `false` values ⇒ a drop-list
  over the all-on baseline. Unknown names are ignored.

  Returns `[filtered-by-name unmatched-names]`. The unmatched list is RETURNED
  rather than only logged so the requirement is testable without capturing
  stdout — the warn line is emitted from `load-skills`."
  [by-name filter-map]
  (if (empty? filter-map)
    [by-name []]
    (let [m         (reduce (fn [acc e] (assoc acc (filter-name (key e)) (val e))) {} filter-map)
          has-true? (some true? (vals m))
          ;; tool/sort-strings: this list is RETURNED as `:filter-unmatched`
          ;; and printed by the warn line, so its order is host-visible.
          unmatched (tool/sort-strings (remove (fn [k] (contains? by-name k)) (keys m)))]
      [(reduce (fn [acc e]
                 (let [nm (key e)]
                   (if (if has-true? (true? (get m nm)) (not (false? (get m nm))))
                     (assoc acc nm (val e))
                     acc)))
               {} by-name)
       unmatched])))

(defn load-skills
  "Discover skills from every source §3 offers and shape them into one loaded
  map.

  `input` is a root string, a seq of roots, a seq of skill DEFS, or an options
  map `{:dirs … :skills … :filter … :sample-limit …}` — the same union
  js/src/skill.ts' `loadSkills` takes. Directory roots are collected first, in
  the order given, then the data defs, so the existing first-name-wins rule
  resolves collisions ACROSS sources without needing a second rule.

  Returns `{:skills [info…] :by-name {} :skipped [] :filter-unmatched []
  :sample-limit n}`. A caller passing only roots gets byte-identical behaviour
  to before this option existed."
  [input]
  (let [opts   (load-opts input)
        dirs   (let [d (:dirs opts)]
                 (cond (nil? d) [] (string? d) [d] :else (vec d)))
        defs   (vec (:skills opts))
        merged (merge-candidates (into (vec (mapcat candidates dirs))
                                       (mapv def->candidate defs)))
        [by-name unmatched] (apply-skills-filter (:by-name merged) (:filter opts))]
    (doseq [nm unmatched] (warn! (str "skill filter name \"" nm "\" matched no skill")))
    {:skills           (vec (filter (fn [s] (contains? by-name (:name s))) (:skills merged)))
     :by-name          by-name
     :skipped          (:skipped merged)
     :filter-unmatched unmatched
     :sample-limit     (or (:sample-limit opts) 0)}))

(defn list-skills
  "SPEC §3 S3 — list/validate inventory: `{:skills … :skipped …}`, no toolkit
  wired, nothing left open.

  DELIBERATELY UNFILTERED, following js/src/skill.ts `listSkills`, which ignores
  `opts.filter` entirely: the inventory is what you AUTHOR an allowlist from, so
  filtering it would be circular."
  [input]
  (let [m (load-skills (dissoc (load-opts input) :filter))]
    {:skills (:skills m) :skipped (:skipped m)}))

;; ---------------------------------------------------------------------------
;; The sibling-file sampler
;; ---------------------------------------------------------------------------

(defn sample-sibling-files
  "Up to `limit` files under the skill's directory, EXCLUDING every SKILL.md.

  The exclusion is the detail that moves bytes and that spike s15 got wrong:
  `golang/skill.go:217` and `js/src/skill.ts:198` both guard the sampler with
  `entry.Name() != \"SKILL.md\"`. Leaving SKILL.md in adds one `<file>` line and
  the shared hello-world output stops matching the other ports.

  `limit`: 0 ⇒ default 10 · n>0 ⇒ cap at n · -1 ⇒ nil, i.e. sampling disabled
  (SPEC §3 S5).

  ORDER — deliberate divergence. SPEC §3 does not pin the sample's order, and no
  shipped port sorts: js/go/python all walk a DFS stack over raw readdir order
  and slice the first `limit` entries. Go's os.ReadDir sorts, Python's
  os.scandir does not, so with >1 sibling the shipped ports are not guaranteed
  to agree with each other. WE SORT, because a port whose whole claim is
  byte-identical output on two hosts cannot depend on readdir order. Pending a
  SPEC fix that pins the sampler's order for everyone; recorded here rather than
  smuggled in."
  [dir limit]
  (if (neg? limit)
    nil
    (let [base (str dir)
          rel  (fn [p] (let [p (str p)]
                         (if (str/starts-with? p base) (subs p (count base)) p)))]
      (->> (fs/list-tree dir)
           (map str)
           (remove ignored-path?)
           (remove fs/directory?)
           (remove (fn [p] (= "SKILL.md" (file-name p))))
           ;; A25 — the settled rule, identical in all seven ports: COLLECT
           ;; every candidate, SORT by the path RELATIVE to the skill directory
           ;; in PLAIN Unicode code-point order, THEN truncate to the cap.
           ;;
           ;; Plain code point, with no depth rule and no per-directory sorting.
           ;; A15's depth ordering exists to resolve duplicate skill NAMES in
           ;; discovery and has no business ordering a flat listing; and sorting
           ;; per directory would make the result a function of the TRAVERSAL,
           ;; so every port would have to reproduce the same stack discipline.
           ;; A global sort makes it a function of the file set and the cap
           ;; alone, which is the thing seven ports can actually agree on.
           ;;
           ;; SORT BEFORE CAPPING is the load-bearing half (ADR-0004 K1). All
           ;; five other ports capped mid-traversal, which let the FILESYSTEM
           ;; decide which files the model saw, not merely their order. This
           ;; port already sorted first; the change here is that the key is the
           ;; RELATIVE path rather than the absolute one.
           ;;
           ;; `tool/compare-strings`, never bare `sort`: `koine.fs/list-tree`
           ;; promises no order on either host, so an unsorted read would let
           ;; THIS PORT'S TWO HOSTS ship different sample CONTENTS — not merely
           ;; a different order — for the same skill.
           (sort-by rel tool/compare-strings)
           (take (if (zero? limit) default-sample-limit limit))
           vec))))

(defn skill-files
  "The `<skill_files>` list for one skill, or nil when there is no block.

  Two sources, one rule, following js/src/skill.ts' execute:
    * an on-disk skill samples its siblings and ALWAYS emits the block (even
      empty) unless sampling is off — that is the shipped byte-exact behaviour;
    * a data/provider skill lists its SUPPLIED logical resources, in the order
      supplied, and omits the block entirely when it has none (an
      instruction-only skill has nothing to disclose)."
  [info limit]
  (if (= "logical" (:origin info))
    (let [res (vec (:resources info))]
      (cond
        (neg? limit)  nil
        (empty? res)  nil
        :else         (vec (take (if (zero? limit) default-sample-limit limit) res))))
    (sample-sibling-files (:dir info) limit)))

;; ---------------------------------------------------------------------------
;; §0.6 — the byte-exact `skill` output
;; ---------------------------------------------------------------------------

(defn skill-output
  "SPEC §0.6. Built by explicit concatenation, never a template: every newline
  here is part of the contract, and there is no trailing newline (the ports join
  lines). `files` = nil disables sampling (S5 `-1`): the `<skill_files>` block
  AND its \"Note: file list is sampled.\" line are both omitted."
  [skill files]
  (str "<skill_content name=\"" (:name skill) "\">\n"
       "# Skill: " (:name skill) "\n"
       "\n"
       (str/trim (str (:content skill))) "\n"
       "\n"
       "Base directory for this skill: " (or (:base skill) (str "file://" (:dir skill))) "\n"
       "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.\n"
       (if (nil? files)
         ""
         (str "Note: file list is sampled.\n"
              "\n"
              "<skill_files>\n"
              (str/join "\n" (map #(str "<file>" % "</file>") files)) "\n"
              "</skill_files>\n"))
       "</skill_content>"))

(defn execute-skill
  "The `skill` tool's execute, as a plain fn over a loaded map.

  An unknown name is NOT a throw — SPEC §3 step 1 makes it
  `ToolResult{isError:true}`, so the model sees it and can retry.

  DIVERGENCE, following the ports: SPEC §3 prose says `\"Available: \"`, while
  golang/skill.go, js, python, java, csharp and elixir all emit
  `\"Available skills: \"` (and the literal `none` when there are no skills).
  We match the ports. SPEC §3 should be corrected."
  ([loaded skill-name] (execute-skill loaded skill-name (or (:sample-limit loaded) 0)))
  ([loaded skill-name limit]
   (let [by-name (:by-name loaded)
         info    (get by-name (str skill-name))]
     (if-not info
       (tool/failure (str "Skill \"" (str skill-name) "\" not found. Available skills: "
                      (let [avail (tool/sort-strings (keys by-name))]
                        (if (seq avail) (str/join ", " avail) "none"))))
       (tool/success (skill-output info (skill-files info limit))
                {:name (:name info) :dir (:dir info)})))))

(defn skill-tool
  "The single `skill` tool (SPEC §3), shipped by default alongside the skills."
  ([loaded] (skill-tool loaded {:sample-limit (:sample-limit loaded)}))
  ([loaded {:keys [sample-limit]}]
   (tool/tool {:name         "skill"
               :description  skill-tool-description
               :input-schema skill-tool-input-schema
               :source       "skill"
               :execute      (fn execute-skill-tool
                               ([args] (execute-skill-tool args nil))
                               ([args _ctx]
                                (execute-skill loaded
                                               (or (get args "name") (get args :name) "")
                                               (or sample-limit 0))))})))

;; ---------------------------------------------------------------------------
;; §0.6 / §3 — skillsPrompt()
;; ---------------------------------------------------------------------------

(defn skills-prompt
  "preamble + \"\\n\\n\" + \"## Available Skills\" + one line per DESCRIBED skill,
  sorted by name. No described skill at all ⇒ the no-skills message, with NO
  preamble (SPEC §3)."
  [loaded]
  (let [described (->> (:skills loaded)
                       (filter #(some? (:description %)))
                       ;; tool/compare-strings, not bare `sort-by`: this is the
                       ;; §3 system-prompt catalog, a byte-exact cross-port
                       ;; surface, and skill names are NOT sanitized — a name
                       ;; above the BMP ordered one way on the JVM and the other
                       ;; on cljgo.
                       (sort-by :name tool/compare-strings))]
    (if (empty? described)
      no-skills-message
      (str skills-prompt-preamble
           "\n\n"
           "## Available Skills\n"
           (str/join "\n" (map #(str "- **" (:name %) "**: " (:description %)) described))))))
