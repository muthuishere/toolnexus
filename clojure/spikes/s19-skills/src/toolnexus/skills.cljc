;; S19 — the FULL agent-skills capability (SPEC §3 / §0.5 / §0.6) in ONE .cljc,
;; on both hosts.
;;
;; S15 proved the happy path: one well-formed skill, discovered, rendered. This
;; spike asks the harder question — does the WHOLE capability survive the port?
;; Discovery with a missing `name`, duplicate names, nested skills, the sibling
;; sampler and its cap, the byte-exact `skill` output, the `skillsPrompt()`
;; preamble, the empty case, and the not-found error result.
;;
;; Reference implementation for every byte here is js/src/skill.ts (the six
;; shipped ports agree with it); SPEC.md §3 is the prose. Where the two disagree
;; the README records it as a finding — this file follows the SHIPPED ports,
;; because byte-parity is measured against them, not against prose.
;;
;; ZERO reader conditionals. ZERO java.*. koine only.
;;
;; Run:  TN_EXAMPLES=/abs/path/to/examples  and see run-both.sh.

(ns toolnexus.skills
  (:require [clojure.string :as str]
            [koine.json :as json]
            [koine.fs :as fs]
            [koine.env :as env]
            [koine.host :as host]
            [koine.process :as proc]))

;; ---------------------------------------------------------------------------
;; constants that must be byte-identical across all ports (SPEC §3)
;; ---------------------------------------------------------------------------

(def skills-prompt-preamble
  "SPEC §3 — the fixed instruction preamble. Do not reword."
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
;; small portable path helpers  (no java.io.File, no filepath)
;; ---------------------------------------------------------------------------

(defn parent-dir [path]
  (let [idx (str/last-index-of (str path) "/")]
    (if idx (subs (str path) 0 idx) ".")))

(defn base-name [path]
  (let [idx (str/last-index-of (str path) "/")]
    (if idx (subs (str path) (inc idx)) (str path))))

;; ---------------------------------------------------------------------------
;; YAML frontmatter
;; ---------------------------------------------------------------------------
;;
;; SPEC §3 demands a STANDARD YAML parser (js `yaml`, py PyYAML, go yaml.v3 ...).
;; There is no YAML in koine and no third-party dependency is allowed in a spike,
;; so this is a deliberate, documented subset: `key: value` scalars with optional
;; surrounding quotes, `#` comment lines, blank lines. Block scalars (`>` / `|`),
;; nesting and flow collections are NOT handled. That is a FINDING, not a
;; workaround — see README.
;;
;; Returns {:data {kw->string} :body string :malformed bool}. `malformed` is true
;; only when an opening fence exists with no closing fence — the distinction the
;; S3 inventory needs to tell "no frontmatter" from "broken frontmatter".

(defn- strip-quotes [s]
  (let [s (str/trim s)
        n (count s)]
    (if (and (>= n 2)
             (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
                 (and (str/starts-with? s "'") (str/ends-with? s "'"))))
      (subs s 1 (dec n))
      s)))

(defn parse-frontmatter [text]
  (let [lines (str/split-lines (str text))]
    (if-not (= "---" (str/trim (str (first lines))))
      {:data {} :body (str text) :malformed false}
      (loop [remaining (rest lines) acc {}]
        (cond
          ;; opening fence, never closed => malformed header
          (empty? remaining)
          {:data {} :body "" :malformed true}

          (= "---" (str/trim (str (first remaining))))
          {:data acc :body (str/join "\n" (rest remaining)) :malformed false}

          :else
          (let [line (str (first remaining))
                trimmed (str/trim line)
                idx  (str/index-of line ":")]
            (recur (rest remaining)
                   (if (or (str/blank? trimmed)
                           (str/starts-with? trimmed "#")
                           (nil? idx))
                     acc
                     ;; scalars are coerced to string and TRIMMED (SPEC §3), so
                     ;; whitespace cannot leak into a byte-compared prompt line.
                     (assoc acc
                            (keyword (str/trim (subs line 0 idx)))
                            (strip-quotes (subs line (inc idx))))))))))))

;; ---------------------------------------------------------------------------
;; discovery  (SPEC §0.5 / §3)
;; ---------------------------------------------------------------------------

(defn- ignored-path? [path]
  (or (str/includes? (str path) "/node_modules/")
      (str/includes? (str path) "/.git/")))

(defn candidates
  "Every SKILL.md under `root`, in DISCOVERY ORDER, as either a parsed skill or
  a typed skip.

  Discovery order is the order koine.fs/find-files returns, and find-files SORTS
  (koine/fs.cljc: `Sorted because skill discovery must be deterministic across
  hosts`). Every port's first-wins rule is order-dependent, so this sort is the
  only reason two hosts cannot disagree about which duplicate survives. We rely
  on it and do not re-sort.

  Note find-files takes a SUFFIX, not a glob, so the suffix is \"/SKILL.md\" —
  plain \"SKILL.md\" would also match a file called MYSKILL.md."
  [root]
  (if-not (fs/exists? root)
    []
    (->> (fs/find-files root "/SKILL.md")
         (remove ignored-path?)
         (mapv (fn [path]
                 (let [{:keys [data body malformed]} (parse-frontmatter (fs/read-file path))]
                   (cond
                     malformed
                     {:skip {:location path :reason "malformed-frontmatter"}}

                     (str/blank? (str (:name data)))
                     {:skip {:location path :reason "missing-name"}}

                     :else
                     {:info {:name        (:name data)
                             ;; description ABSENT (nil) and description EMPTY
                             ;; are different: the ports filter the prompt on
                             ;; `description !== undefined`, not on blankness.
                             :description (:description data)
                             :location    path
                             :content     body
                             :dir         (parent-dir path)}})))))))

(defn merge-candidates
  "Dedupe by name, FIRST WINS; later duplicates become typed skips.
  Returns {:skills ordered-vector :by-name map :skipped vector}."
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
  "Discover skills under one or more roots. Roots are visited in the order given
  (SPEC §3: directory candidates precede data candidates; here, earlier root
  wins)."
  [roots]
  (merge-candidates (vec (mapcat candidates (if (string? roots) [roots] roots)))))

(defn list-skills
  "SPEC §3 S3 — list/validate inventory: skills + typed skip reasons, no toolkit."
  [roots]
  (let [m (load-skills roots)]
    {:skills (:skills m) :skipped (:skipped m)}))

;; ---------------------------------------------------------------------------
;; the sibling-file sampler
;; ---------------------------------------------------------------------------

(defn sample-sibling-files
  "Up to `limit` files under the skill dir, excluding every SKILL.md.

  The shipped ports walk a DFS stack over readdir order and do NOT sort, so with
  more than one sibling their sample ORDER is filesystem-dependent — a real
  cross-port byte-parity hazard that the shared hello-world fixture (one sibling,
  one subdirectory) happens not to expose. We sort, because a spike whose whole
  claim is byte-identical output across hosts cannot depend on readdir order.
  Recorded as a finding rather than smuggled in."
  [dir limit]
  (if (neg? limit)
    nil
    (->> (fs/list-tree dir)
         (map str)
         (remove ignored-path?)
         (remove fs/directory?)
         (remove #(= "SKILL.md" (base-name %)))
         sort
         (take (if (zero? limit) default-sample-limit limit))
         vec)))

;; ---------------------------------------------------------------------------
;; §0.6 — the byte-exact `skill` tool output
;; ---------------------------------------------------------------------------

(defn skill-output
  "SPEC §0.6. Built by explicit concatenation, never a template: every newline
  here is part of the contract. No trailing newline — the ports join lines.
  `files` = nil means sampling is disabled (S5 sampleLimit -1): the whole
  <skill_files> block AND its \"Note: file list is sampled.\" line are omitted."
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
  "The `skill` tool's execute. SPEC §3 step 1: an unknown name is NOT a throw —
  it is ToolResult{isError:true}. Wording follows the six shipped ports
  (\"Available skills: \", \"none\" when empty); SPEC prose says \"Available: \"
  — see README finding F4."
  ([loaded skill-name] (execute-skill loaded skill-name 0))
  ([loaded skill-name limit]
   (let [by-name (:by-name loaded)
         info    (get by-name (str skill-name))]
     (if-not info
       {:output (str "Skill \"" (str skill-name) "\" not found. Available skills: "
                     (let [avail (sort (keys by-name))]
                       (if (seq avail) (str/join ", " avail) "none")))
        :isError true}
       {:output  (skill-output info (sample-sibling-files (:dir info) limit))
        :isError false}))))

;; ---------------------------------------------------------------------------
;; §0.6 / §3 — skillsPrompt()
;; ---------------------------------------------------------------------------

(defn skills-prompt
  "preamble + \"\\n\\n\" + \"## Available Skills\" + one line per DESCRIBED skill,
  sorted by name. No described skill at all => the no-skills message, with NO
  preamble."
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

;; ---------------------------------------------------------------------------
;; the extra fixture tree the spike builds for itself
;; ---------------------------------------------------------------------------
;;
;; It is created at a RELATIVE root on purpose. A mktemp path would put a random
;; absolute string inside `Base directory for this skill:` and inside every
;; <file> line, and every byte count in the report would move between runs —
;; the diff would then be measuring the temp dir, not the code. A fixed relative
;; root makes the temp half of the report deterministic AND machine-independent,
;; at the cost of a `file://fixtures/...` base that is relative where production
;; is absolute. The shared hello-world fixture below is the one that proves the
;; absolute-path shape.

(def fixture-root "fixtures")

(defn- write-fixture! [path text]
  (proc/sh ["mkdir" "-p" (parent-dir path)])
  (fs/write-file path text)
  nil)

(defn- skill-md [nm desc body]
  (str "---\n"
       "name: " nm "\n"
       (if desc (str "description: " desc "\n") "")
       "---\n"
       "\n" body "\n"))

(defn build-fixtures! []
  (proc/sh ["rm" "-rf" fixture-root])
  ;; alpha — a normal skill with two siblings, one nested
  (write-fixture! (str fixture-root "/alpha/SKILL.md")
                  (skill-md "alpha" "The alpha skill." "Alpha body line."))
  (write-fixture! (str fixture-root "/alpha/notes.txt") "notes\n")
  (write-fixture! (str fixture-root "/alpha/scripts/run.sh") "#!/bin/sh\necho alpha\n")
  ;; dup — sorts AFTER alpha, so first-wins must keep alpha's real directory
  (write-fixture! (str fixture-root "/dup/SKILL.md")
                  (skill-md "alpha" "An impostor that must lose." "Impostor body."))
  ;; nested — a SKILL.md three directories deep
  (write-fixture! (str fixture-root "/nested/deep/beta/SKILL.md")
                  (skill-md "beta" "A nested skill." "Beta body."))
  ;; nodesc — discovered, but NOT in the prompt (no description key)
  (write-fixture! (str fixture-root "/nodesc/SKILL.md")
                  (skill-md "gamma" nil "Gamma body."))
  ;; noname — frontmatter without `name`: skipped entirely
  (write-fixture! (str fixture-root "/noname/SKILL.md")
                  (str "---\ndescription: I have no name.\n---\n\nBody.\n"))
  ;; broken — opening fence, no closing fence: malformed-frontmatter
  (write-fixture! (str fixture-root "/broken/SKILL.md")
                  (str "---\nname: broken\ndescription: never closed\n\nBody.\n"))
  ;; many — 12 siblings, so the sample cap of 10 is actually exercised
  (write-fixture! (str fixture-root "/many/SKILL.md")
                  (skill-md "many" "Twelve siblings." "Many body."))
  (doseq [i (range 12)]
    (write-fixture! (str fixture-root "/many/f" (if (< i 10) (str "0" i) (str i)) ".txt") "x\n"))
  ;; empty — a root with no SKILL.md at all, for the no-skills prompt
  (write-fixture! (str fixture-root "/empty-root/README.md") "nothing here\n")
  nil)

;; ---------------------------------------------------------------------------
;; the report
;; ---------------------------------------------------------------------------

(defn- s15-compat-bytes
  "S15 sampled the skill dir WITHOUT excluding SKILL.md and measured 1127 bytes
  for hello-world. Recomputing that number here is how this spike proves it is
  looking at the same fixture and the same renderer, while its own SPEC-correct
  number is smaller by exactly one <file> line."
  [skill]
  (count (skill-output skill
                       (->> (fs/list-tree (:dir skill))
                            (map str)
                            (remove fs/directory?)
                            sort
                            vec))))

(defn shared-report [examples-dir]
  (let [loaded (load-skills (str examples-dir "/skills"))
        hello  (get (:by-name loaded) "hello-world")
        out    (execute-skill loaded "hello-world")]
    {:names        (vec (sort (keys (:by-name loaded))))
     :skipped      (:skipped loaded)
     :sampled      (sample-sibling-files (:dir hello) 0)
     ;; the FULL string: its only variable part is TN_EXAMPLES, which is
     ;; identical across the three runs, so a drift anywhere shows in the diff.
     :skill-output (:output out)
     :output-bytes (count (:output out))
     :s15-bytes    (s15-compat-bytes hello)
     :prompt       (skills-prompt loaded)
     :prompt-bytes (count (skills-prompt loaded))
     :not-found    (execute-skill loaded "nope")}))

(defn temp-report []
  (build-fixtures!)
  (let [loaded    (load-skills fixture-root)
        empty-ld  (load-skills (str fixture-root "/empty-root"))
        alpha-out (execute-skill loaded "alpha")
        beta-out  (execute-skill loaded "beta")
        many-out  (execute-skill loaded "many")
        capped-3  (execute-skill loaded "many" 3)
        no-files  (execute-skill loaded "many" -1)]
    {:discovered      (mapv :name (:skills loaded))
     :skipped         (:skipped loaded)
     ;; first-wins: alpha must resolve to fixtures/alpha, never fixtures/dup
     :first-wins-dir  (:dir (get (:by-name loaded) "alpha"))
     :nested-dir      (:dir (get (:by-name loaded) "beta"))
     :alpha-bytes     (count (:output alpha-out))
     :alpha-files     (sample-sibling-files (:dir (get (:by-name loaded) "alpha")) 0)
     :nested-bytes    (count (:output beta-out))
     :sample-cap      {:sibling-total (count (remove fs/directory?
                                                     (map str (fs/list-tree (str fixture-root "/many")))))
                       :sampled       (count (sample-sibling-files (str fixture-root "/many") 0))
                       :first         (first (sample-sibling-files (str fixture-root "/many") 0))
                       :last          (last (sample-sibling-files (str fixture-root "/many") 0))
                       :many-bytes    (count (:output many-out))
                       :cap-3         (count (sample-sibling-files (str fixture-root "/many") 3))
                       :cap-3-bytes   (count (:output capped-3))
                       :cap--1-bytes  (count (:output no-files))}
     :prompt          (skills-prompt loaded)
     :empty-prompt    (skills-prompt empty-ld)
     :empty-names     (mapv :name (:skills empty-ld))
     :not-found       (execute-skill loaded "does-not-exist")
     :not-found-empty (execute-skill empty-ld "anything")}))

(defn- assertions [shared temp]
  {:hello-discovered      (= ["hello-world"] (:names shared))
   :hello-single-sibling  (= 1 (count (:sampled shared)))
   :hello-excludes-skill  (not (str/includes? (:skill-output shared) "hello-world/SKILL.md"))
   :hello-s15-1127        (= 1127 (:s15-bytes shared))
   :hello-spec-smaller    (< (:output-bytes shared) (:s15-bytes shared))
   :prompt-has-preamble   (str/starts-with? (:prompt shared) skills-prompt-preamble)
   :prompt-has-catalog    (str/includes? (:prompt shared) "\n\n## Available Skills\n- **hello-world**: ")
   :not-found-is-error    (true? (:isError (:not-found shared)))
   :not-found-not-throw   (string? (:output (:not-found shared)))
   :temp-names            (= ["alpha" "beta" "gamma" "many"] (vec (sort (:discovered temp))))
   :temp-first-wins       (= "fixtures/alpha" (:first-wins-dir temp))
   :temp-noname-skipped   (some? (some #(when (= "missing-name" (:reason %)) %) (:skipped temp)))
   :temp-malformed-skip   (some? (some #(when (= "malformed-frontmatter" (:reason %)) %) (:skipped temp)))
   :temp-dup-skip         (some? (some #(when (= "duplicate-name" (:reason %)) %) (:skipped temp)))
   :temp-nested-found     (= "fixtures/nested/deep/beta" (:nested-dir temp))
   :temp-sample-capped    (= 10 (:sampled (:sample-cap temp)))
   :temp-cap-3            (= 3 (:cap-3 (:sample-cap temp)))
   :temp-nodesc-hidden    (not (str/includes? (:prompt temp) "gamma"))
   :temp-empty-prompt     (= no-skills-message (:empty-prompt temp))
   :temp-not-found-none   (= "Skill \"anything\" not found. Available skills: none"
                             (:output (:not-found-empty temp)))})

(defn run-spike [examples-dir]
  (let [shared (shared-report examples-dir)
        temp   (temp-report)
        asserts (assertions shared temp)]
    {:host       (name host/id)
     :shared     shared
     :temp       temp
     :assertions asserts
     :ok         (every? true? (vals asserts))
     :tool       {:name        "skill"
                  :source      "skill"
                  :description skill-tool-description
                  :inputSchema skill-tool-input-schema
                  :desc-bytes  (count skill-tool-description)}}))

(defn -main [& _]
  (let [dir (env/get-env "TN_EXAMPLES")]
    (when-not dir
      (throw (ex-info "set TN_EXAMPLES to the toolnexus examples/ directory" {})))
    (println (json/write-str (run-spike dir)))))
