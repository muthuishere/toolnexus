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

(defn parent-dir
  "The directory part of `path`, or \".\" when there is no separator."
  [path]
  (let [p   (str path)
        idx (str/last-index-of p "/")]
    (if idx (subs p 0 idx) ".")))

(defn file-name
  "The last segment of `path`. NOT named `base-name`/`basename` — nothing in
  clojure.core collides, but the name is kept explicit for readability."
  [path]
  (let [p   (str path)
        idx (str/last-index-of p "/")]
    (if idx (subs p (inc idx)) p)))

;; ---------------------------------------------------------------------------
;; Discovery (SPEC §0.5 / §3)
;; ---------------------------------------------------------------------------

(defn- ignored-path? [path]
  (or (str/includes? (str path) "/node_modules/")
      (str/includes? (str path) "/.git/")))

(defn- parse-skill-file
  "Read + parse one SKILL.md into `{:info …}` or `{:skip {:location :reason}}`.

  `toolnexus.frontmatter/parse` THROWS on anything outside its documented
  subset, by design. Discovery must therefore isolate it exactly the way §0.3
  isolates a failed MCP server: a single unparseable SKILL.md is recorded as a
  typed skip and every OTHER skill still loads. A throwing parser that takes
  down discovery of an entire skills tree would be a far worse bug than the
  strictness it is protecting.

  DIVERGENCE, recorded not hidden: SPEC §3 mandates a *standard YAML parser*, so
  a block scalar (`description: >`) is legal frontmatter in the five shipped
  ports and is REJECTED here — this port skips that skill as
  `malformed-frontmatter` where Go/JS/Python would load it. That is a real
  parity gap for skills outside the subset; it does not move the shared
  `examples/` fixture, which uses plain scalars only."
  [path]
  (let [text (try (fs/read-file path) (catch Throwable _ ::unreadable))]
    (if (= ::unreadable text)
      {:skip {:location path :reason "unreadable"}}
      (let [parsed (try (frontmatter/parse text)
                        (catch Throwable _ ::malformed))]
        (if (= ::malformed parsed)
          {:skip {:location path :reason "malformed-frontmatter"}}
          (let [[data body] parsed]
            (if (str/blank? (str (:name data)))
              ;; `name` is REQUIRED. No frontmatter at all lands here too, since
              ;; frontmatter/parse returns [{} text] for a plain body.
              {:skip {:location path :reason "missing-name"}}
              {:info {:name        (:name data)
                      ;; ABSENT description (nil) and EMPTY description ("") are
                      ;; different: js/src/skill.ts filters the prompt catalog on
                      ;; `description !== undefined`, golang/skill.go on
                      ;; `!= ""`. Those two shipped ports genuinely disagree for
                      ;; `description:` with an empty value; we follow JS.
                      :description (:description data)
                      :location    path
                      :content     body
                      :dir         (parent-dir path)}})))))))

(defn candidates
  "Every SKILL.md under `root`, in DISCOVERY ORDER, as a parsed skill or a typed
  skip.

  Discovery order is whatever `koine.fs/find-files` returns, and find-files
  SORTS (koine/fs.cljc: \"Every file under `root` whose path ends with `suffix`,
  SORTED\"). Every port's first-name-wins rule is order-dependent, so that sort
  is the only reason two hosts cannot disagree about which duplicate survives.
  We rely on it and deliberately do not re-sort.

  Note find-files takes a SUFFIX, not a glob, so the suffix is \"/SKILL.md\" —
  a bare \"SKILL.md\" would also match a file named MYSKILL.md."
  [root]
  (if-not (fs/exists? root)
    []
    (->> (fs/find-files root "/SKILL.md")
         (map str)
         (remove ignored-path?)
         (mapv parse-skill-file))))

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

(defn load-skills
  "Discover skills under one root (a string) or several (a seq). Roots are
  visited in the order given, so an earlier root wins a name collision."
  [roots]
  (merge-candidates (vec (mapcat candidates (if (string? roots) [roots] roots)))))

(defn list-skills
  "SPEC §3 S3 — list/validate inventory: `{:skills … :skipped …}`, no toolkit
  wired, nothing left open."
  [roots]
  (let [m (load-skills roots)]
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
    (->> (fs/list-tree dir)
         (map str)
         (remove ignored-path?)
         (remove fs/directory?)
         (remove #(= "SKILL.md" (file-name %)))
         sort
         (take (if (zero? limit) default-sample-limit limit))
         vec)))

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
       "Base directory for this skill: file://" (:dir skill) "\n"
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
  ([loaded skill-name] (execute-skill loaded skill-name 0))
  ([loaded skill-name limit]
   (let [by-name (:by-name loaded)
         info    (get by-name (str skill-name))]
     (if-not info
       (tool/failure (str "Skill \"" (str skill-name) "\" not found. Available skills: "
                      (let [avail (sort (keys by-name))]
                        (if (seq avail) (str/join ", " avail) "none"))))
       (tool/success (skill-output info (sample-sibling-files (:dir info) limit))
                {:name (:name info) :dir (:dir info)})))))

(defn skill-tool
  "The single `skill` tool (SPEC §3), shipped by default alongside the skills."
  ([loaded] (skill-tool loaded {}))
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
                       (sort-by :name))]
    (if (empty? described)
      no-skills-message
      (str skills-prompt-preamble
           "\n\n"
           "## Available Skills\n"
           (str/join "\n" (map #(str "- **" (:name %) "**: " (:description %)) described))))))
