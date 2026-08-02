;; Tests for toolnexus.skill (SPEC §0.5 / §0.6 / §3).
;;
;; ONE .cljc suite, run unchanged on Clojure (JVM) and on cljgo. No java.*,
;; no Thread/sleep, no network, no LLM.
;;
;; Two fixture sources on purpose:
;;   * the SHARED examples/skills tree (path from TN_EXAMPLES) — the byte-exact
;;     conformance fixture, the one that carries an ABSOLUTE base path;
;;   * a small tree this suite builds in a PROCESS-UNIQUE temp dir — every
;;     expectation over it is built from `fixture-root`, so the random absolute
;;     path lands on both sides of the comparison and no byte count moves. It
;;     used to be a fixed relative path; see the note at `fixture-base` for the
;;     concurrency defect that cost.
(ns toolnexus.skill-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [koine.env :as env]
            [koine.fs :as fs]
            [toolnexus.skill :as skill]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; the private fixture tree
;; ---------------------------------------------------------------------------

;; PROCESS-UNIQUE, and that is a bug fix, not tidying. These were the fixed
;; relative paths "test-fixtures" / "test-fixtures/skills", i.e. a shared
;; mutable directory in whatever cwd the suite ran from — so TWO suite runs in
;; the same checkout (two agents, or a CI matrix sharing a workspace) trampled
;; each other: one run's `build-fixtures!` opens with `delete-tree!` and the
;; `:once` teardown deletes the base, while the other is mid-read. "alpha" is
;; written first, so it disappears first; the captured failure was 28 red
;; assertions, every one of them alpha missing, plus a `str/includes?` on nil.
;; That is the "cljgo-only intermittent under load" this port has been carrying
;; — it reproduced on the JVM under a concurrent triple run too. cljgo was not
;; the cause, it just lost the race more often.
;;
;; The header above says a relative path is needed because a mktemp path would
;; move the byte counts. It would not: every assertion over this tree builds its
;; expected string from `fixture-root` itself (see `output-shape-is-byte-exact`),
;; and the one hardcoded byte count in this file — 995 — is measured against the
;; SHARED examples/ tree and normalises for its own path length.
(def fixture-base (str (fs/temp-dir! "tn-skill-fixtures")))
(def fixture-root (str fixture-base "/skills"))

(defn- write-fixture! [path text]
  (fs/mkdirs! (skill/parent-dir path))
  (fs/write-file path text)
  nil)

(defn- skill-md [nm desc body]
  (str "---\n"
       "name: " nm "\n"
       (if desc (str "description: " desc "\n") "")
       "---\n"
       "\n" body "\n"))

(defn build-fixtures! []
  (fs/delete-tree! fixture-root)
  ;; alpha — a normal skill with two siblings, one of them nested
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
  ;; nodesc — discovered, but absent from the prompt (no `description` key)
  (write-fixture! (str fixture-root "/nodesc/SKILL.md")
                  (skill-md "gamma" nil "Gamma body."))
  ;; noname — frontmatter without `name`
  (write-fixture! (str fixture-root "/noname/SKILL.md")
                  "---\ndescription: I have no name.\n---\n\nBody.\n")
  ;; unclosed — an opening fence that never closes; frontmatter/parse THROWS
  (write-fixture! (str fixture-root "/unclosed/SKILL.md")
                  "---\nname: unclosed\ndescription: never closed\n\nBody.\n")
  ;; blockscalar — legal YAML, OUTSIDE toolnexus.frontmatter's subset, so it
  ;; throws too. This is the documented divergence from the five shipped ports.
  (write-fixture! (str fixture-root "/blockscalar/SKILL.md")
                  "---\nname: blocky\ndescription: >\n  folded text\n---\n\nBody.\n")
  ;; many — 12 siblings, so the sample cap of 10 is actually exercised
  (write-fixture! (str fixture-root "/many/SKILL.md")
                  (skill-md "many" "Twelve siblings." "Many body."))
  (doseq [i (range 12)]
    (write-fixture! (str fixture-root "/many/f" (if (< i 10) (str "0" i) (str i)) ".txt") "x\n"))
  ;; empty-root — no SKILL.md at all
  (write-fixture! (str fixture-root "/empty-root/README.md") "nothing here\n")
  nil)

;; Build once, tear the whole tree down afterwards — the suite must leave the
;; working directory exactly as it found it.
(use-fixtures :once (fn [f] (build-fixtures!) (f) (fs/delete-tree! fixture-base)))

(defn- loaded [] (skill/load-skills fixture-root))
(defn- empty-loaded [] (skill/load-skills (str fixture-root "/empty-root")))

(defn- skip-reasons [ld] (set (map :reason (:skipped ld))))

;; ---------------------------------------------------------------------------
;; §0.5 — discovery
;; ---------------------------------------------------------------------------

(deftest discovery-finds-every-named-skill
  (let [ld (loaded)]
    (is (= ["alpha" "beta" "gamma" "many"] (vec (sort (keys (:by-name ld))))))
    (testing "nested SKILL.md is discovered at any depth"
      (is (= (str fixture-root "/nested/deep/beta") (:dir (get (:by-name ld) "beta")))))
    (testing "the body after the frontmatter is the content"
      (is (str/includes? (:content (get (:by-name ld) "alpha")) "Alpha body line.")))))

(deftest discovery-order-is-deterministic
  ;; The first-wins rule is order-dependent, so the port is only deterministic
  ;; because koine.fs/find-files SORTS. Assert the sorted property directly:
  ;; if that sort ever leaves koine, this is the test that catches it.
  (let [paths (vec (fs/find-files fixture-root "/SKILL.md"))]
    (is (= (vec (sort paths)) paths))
    (is (= 8 (count paths)))))

(deftest first-name-wins
  (let [ld (loaded)]
    ;; fixtures/alpha sorts before fixtures/dup, so alpha must keep its own dir
    (is (= (str fixture-root "/alpha") (:dir (get (:by-name ld) "alpha"))))
    (is (= "The alpha skill." (:description (get (:by-name ld) "alpha"))))
    (is (contains? (skip-reasons ld) "duplicate-name"))))

(deftest name-is-required
  (let [ld (loaded)]
    (is (contains? (skip-reasons ld) "missing-name"))
    (is (some #(and (= "missing-name" (:reason %))
                    (str/includes? (:location %) "/noname/"))
              (:skipped ld)))))

(deftest rejected-frontmatter-does-not-kill-discovery
  ;; toolnexus.frontmatter/parse THROWS outside its subset. Discovery isolates
  ;; each candidate the way §0.3 isolates a failed MCP server: the throwing
  ;; skill becomes a typed skip and every other skill still loads.
  (let [ld (loaded)
        malformed (filter #(= "malformed-frontmatter" (:reason %)) (:skipped ld))]
    (is (= 2 (count malformed)))
    (testing "an unclosed fence is a skip, not a crash"
      (is (some #(str/includes? (:location %) "/unclosed/") malformed)))
    (testing "a block scalar is legal YAML but outside our subset — DIVERGENCE"
      (is (some #(str/includes? (:location %) "/blockscalar/") malformed))
      (is (nil? (get (:by-name ld) "blocky"))))
    (testing "the four healthy skills survived"
      (is (= 4 (count (:skills ld)))))))

(deftest list-skills-inventory
  (let [inv (skill/list-skills fixture-root)]
    (is (= 4 (count (:skills inv))))
    (is (= #{"missing-name" "malformed-frontmatter" "duplicate-name"}
           (set (map :reason (:skipped inv)))))))

;; ---------------------------------------------------------------------------
;; the sibling sampler
;; ---------------------------------------------------------------------------

(deftest sampler-excludes-skill-md
  ;; The single detail spike s15 got wrong. golang/skill.go:217 and
  ;; js/src/skill.ts:198 both guard the sampler with `!= "SKILL.md"`.
  (let [files (skill/sample-sibling-files (str fixture-root "/alpha") 0)]
    (is (= [(str fixture-root "/alpha/notes.txt")
            (str fixture-root "/alpha/scripts/run.sh")]
           files))
    (is (not-any? #(str/includes? % "SKILL.md") files))))

(deftest sampler-cap
  (is (= 10 (count (skill/sample-sibling-files (str fixture-root "/many") 0))))
  (is (= 3 (count (skill/sample-sibling-files (str fixture-root "/many") 3))))
  (testing "-1 disables sampling entirely (S5)"
    (is (nil? (skill/sample-sibling-files (str fixture-root "/many") -1)))))

(deftest sampler-order-is-sorted
  ;; Deliberate divergence: SPEC §3 leaves the sample's order unspecified and no
  ;; shipped port sorts. We sort so two hosts cannot disagree. Pending SPEC fix.
  (let [files (skill/sample-sibling-files (str fixture-root "/many") 0)]
    (is (= (vec (sort files)) files))
    (is (str/ends-with? (first files) "/f00.txt"))))

;; ---------------------------------------------------------------------------
;; §0.6 — byte-exact output
;; ---------------------------------------------------------------------------

(deftest output-shape-is-byte-exact
  (let [ld  (loaded)
        out (:output (skill/execute-skill ld "alpha"))]
    (is (= (str "<skill_content name=\"alpha\">\n"
                "# Skill: alpha\n"
                "\n"
                "Alpha body line.\n"
                "\n"
                "Base directory for this skill: file://" fixture-root "/alpha\n"
                "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.\n"
                "Note: file list is sampled.\n"
                "\n"
                "<skill_files>\n"
                "<file>" fixture-root "/alpha/notes.txt</file>\n"
                "<file>" fixture-root "/alpha/scripts/run.sh</file>\n"
                "</skill_files>\n"
                "</skill_content>")
           out))
    (testing "no trailing newline — the ports join lines"
      (is (not (str/ends-with? out "\n"))))))

(deftest output-omits-file-block-when-sampling-disabled
  (let [ld  (loaded)
        out (:output (skill/execute-skill ld "many" -1))]
    (is (not (str/includes? out "<skill_files>")))
    (testing "the Note line goes with it"
      (is (not (str/includes? out "Note: file list is sampled."))))))

(deftest unknown-skill-is-an-error-result-not-a-throw
  (let [ld (loaded)
        r  (skill/execute-skill ld "does-not-exist")]
    (is (true? (:isError r)))
    (is (= "Skill \"does-not-exist\" not found. Available skills: alpha, beta, gamma, many"
           (:output r))))
  (testing "with no skills at all the list is the literal \"none\""
    (is (= "Skill \"anything\" not found. Available skills: none"
           (:output (skill/execute-skill (empty-loaded) "anything"))))))

;; ---------------------------------------------------------------------------
;; §3 — skillsPrompt()
;; ---------------------------------------------------------------------------

(deftest prompt-preamble-and-catalog
  (let [p (skill/skills-prompt (loaded))]
    (is (str/starts-with? p skill/skills-prompt-preamble))
    (is (= (str skill/skills-prompt-preamble
                "\n\n## Available Skills\n"
                "- **alpha**: The alpha skill.\n"
                "- **beta**: A nested skill.\n"
                "- **many**: Twelve siblings.")
           p))
    (testing "a discovered skill with no description key is NOT in the catalog"
      (is (not (str/includes? p "gamma"))))))

(deftest prompt-when-no-described-skill
  ;; DIVERGENCE from the task brief, following the ports: all six shipped ports
  ;; return this literal string, NOT an empty string (golang/skill.go Prompt(),
  ;; js skill.ts:416, python skill.py:304, java SkillSource:162,
  ;; csharp SkillSource:86, elixir skill.ex:496). SPEC §0.6 says
  ;; "empty/'no skills'"; byte-parity is measured against the ports.
  (is (= "No skills are currently available." (skill/skills-prompt (empty-loaded))))
  (is (= skill/no-skills-message (skill/skills-prompt (empty-loaded))))
  (testing "and it carries NO preamble"
    (is (not (str/includes? (skill/skills-prompt (empty-loaded))
                            skill/skills-prompt-preamble)))))

;; ---------------------------------------------------------------------------
;; the `skill` tool itself
;; ---------------------------------------------------------------------------

(deftest skill-tool-shape
  (let [t (skill/skill-tool (loaded))]
    (is (= "skill" (:name t)))
    (is (= "skill" (:source t)))
    (is (= skill/skill-tool-input-schema (:input-schema t)))
    ;; SPEC §3 "skill.txt (loader description, verbatim from opencode)" pins
    ;; this string byte for byte across every port; js holds the same bytes in
    ;; `SKILL_TOOL_DESCRIPTION` (js/src/skill.ts:18-22). A LENGTH check was the
    ;; only thing guarding it, and a reword of the same length is exactly what a
    ;; length check cannot see — so it is transcribed here from SPEC.md §3 and
    ;; compared whole, like every other §3 constant in this file. Written out
    ;; rather than referred to: comparing `skill/skill-tool-description` with
    ;; itself would prove nothing.
    (is (= (str "Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.\n"
                "\n"
                "Use this tool to inject the skill's instructions and resources into current conversation."
                " The output may contain detailed workflow guidance as well as references to scripts, files,"
                " etc in the same directory as the skill.\n"
                "\n"
                "The skill name must match one of the skills listed in your system prompt.")
           (:description t)))
    ;; The old assertion, kept: it is now redundant, but a second, independent
    ;; statement of the same fact is what catches a transcription slip ABOVE.
    (is (= 398 (count (:description t))))))

(deftest skill-tool-executes-through-the-toolkit
  (let [tk (tool/toolkit [(skill/skill-tool (loaded))])
        r  (tool/execute tk "skill" {"name" "alpha"})]
    (is (false? (:isError r)))
    (is (str/starts-with? (:output r) "<skill_content name=\"alpha\">"))
    (is (= "alpha" (:name (:metadata r))))
    (testing "a keyword arg key works too"
      (is (false? (:isError (tool/execute tk "skill" {:name "alpha"})))))
    (testing "unknown name comes back as an error RESULT"
      (is (true? (:isError (tool/execute tk "skill" {"name" "nope"})))))))

;; ---------------------------------------------------------------------------
;; the SHARED examples/skills fixture — the byte-exact conformance number
;; ---------------------------------------------------------------------------

(def ^:private reference-dir-len
  "Length of the absolute hello-world directory at the checkout where 995 was
  measured. The output embeds that directory TWICE (the `Base directory:` line
  and the one `<file>` line), so the total byte count MOVES WITH THE CHECKOUT
  PATH: 995 is the number at a 109-character directory, not a universal
  constant. The assertion below normalises for that so it holds anywhere."
  109)

(deftest shared-hello-world-is-995-bytes
  (let [examples (env/get-env "TN_EXAMPLES")]
    (is (some? examples) "TN_EXAMPLES must point at the repo's shared examples/ directory")
    (when examples
      (let [ld    (skill/load-skills (str examples "/skills"))
            hello (get (:by-name ld) "hello-world")
            out   (:output (skill/execute-skill ld "hello-world"))
            n     (count (:dir hello))]
        (is (= ["hello-world"] (vec (sort (keys (:by-name ld))))))
        (is (str/starts-with? (:dir hello) "/") "the shared fixture must be an ABSOLUTE path")
        (testing "byte-exact §0.6 output, normalised for checkout-path length"
          (is (= 995 (+ (count out) (* 2 (- reference-dir-len n))))))
        (testing "exactly one sibling is sampled, and it is NOT SKILL.md"
          (is (= 1 (count (skill/sample-sibling-files (:dir hello) 0))))
          (is (not (str/includes? out "hello-world/SKILL.md")))
          (is (str/includes? out "/scripts/greet.sh</file>")))
        (testing "the base is a file:// URL over the absolute dir"
          (is (str/includes? out (str "Base directory for this skill: file://" (:dir hello) "\n"))))
        (testing "the prompt is preamble + catalog, 293 bytes"
          (let [p (skill/skills-prompt ld)]
            (is (= 293 (count p)))
            (is (str/starts-with? p skill/skills-prompt-preamble))
            (is (str/includes? p "\n\n## Available Skills\n- **hello-world**: "))))
        (testing "unknown skill against the shared fixture"
          (is (= "Skill \"nope\" not found. Available skills: hello-world"
                 (:output (skill/execute-skill ld "nope")))))))))

;; ---------------------------------------------------------------------------
;; §3 S1 — skills supplied as DATA or by a PROVIDER
;; ---------------------------------------------------------------------------
;;
;; Expected values below are read off js/src/skill.ts (candidatesFromDefs +
;; the `origin === "logical"` branch of the skill tool's execute), which is the
;; shipped behaviour the other five ports were built to. Nothing here is
;; snapshotted from this port's own output.

(def ^:private data-skill
  {:name "data-a" :description "From data." :content "Data body."})

(defn- file-lines [out]
  (vec (filter #(str/starts-with? % "<file>") (str/split-lines out))))

(deftest data-sourced-skill-needs-no-directory
  (let [ld (skill/load-skills {:skills [data-skill]})]
    (is (= ["data-a"] (vec (sort (keys (:by-name ld))))))
    (is (str/includes? (skill/skills-prompt ld) "- **data-a**: From data."))
    (let [out (:output (skill/execute-skill ld "data-a"))]
      (is (str/includes? out "Data body."))
      (testing "a LOGICAL base, and no absolute host path anywhere (S4)"
        (is (str/includes? out "Base directory for this skill: skill://data-a/\n"))
        (is (not (str/includes? out "file://")))))))

(deftest data-skill-resources-become-the-file-block
  (let [ld  (skill/load-skills
             {:skills [(assoc data-skill :resources ["scripts/foo.sh" "ref/x.md"])]})
        out (:output (skill/execute-skill ld "data-a"))]
    (is (= ["<file>scripts/foo.sh</file>" "<file>ref/x.md</file>"] (file-lines out)))
    (testing "the logical resources are emitted in the ORDER SUPPLIED, unsorted —
              they are a caller-authored list, not a directory walk"
      (is (str/includes? out "<skill_files>\n<file>scripts/foo.sh</file>\n<file>ref/x.md</file>\n</skill_files>\n")))))

(deftest a-data-skill-with-no-resources-omits-the-file-block
  ;; js/src/skill.ts: `if (res.length === 0) emitFiles = false` — an
  ;; instruction-only data skill has no <skill_files>, where an EMPTY on-disk
  ;; skill still emits an empty block.
  (is (not (str/includes? (:output (skill/execute-skill (skill/load-skills {:skills [data-skill]})
                                                        "data-a"))
                          "<skill_files>"))))

(deftest a-supplied-base-wins-over-the-default
  (let [ld (skill/load-skills {:skills [(assoc data-skill :base "mem://pack/")]})]
    (is (str/includes? (:output (skill/execute-skill ld "data-a"))
                       "Base directory for this skill: mem://pack/\n"))
    (is (= "mem://pack/" (:dir (:metadata (skill/execute-skill ld "data-a")))))))

(deftest a-data-skill-without-a-name-is-a-typed-skip
  (let [ld (skill/load-skills {:skills [{:description "d" :content "c"}]})]
    (is (empty? (:skills ld)))
    (is (= [{:location "skill://" :reason "missing-name"}] (:skipped ld)))))

(deftest directories-and-data-compose-with-first-wins
  (let [ld (skill/load-skills {:dirs   fixture-root
                               :skills [{:name "alpha" :description "impostor" :content "no"}
                                        data-skill]})]
    (testing "the directory source was collected first, so it keeps the name"
      (is (str/includes? (:output (skill/execute-skill ld "alpha")) "Alpha body line.")))
    (is (contains? (skip-reasons ld) "duplicate-name"))
    (testing "the non-colliding data skill still lands"
      (is (contains? (:by-name ld) "data-a")))))

(deftest a-directory-sourced-skill-is-untouched-by-the-data-path
  ;; S4's byte-identity clause: adding a data source must not move the on-disk
  ;; output by one byte.
  (is (= (:output (skill/execute-skill (skill/load-skills fixture-root) "alpha"))
         (:output (skill/execute-skill (skill/load-skills {:dirs fixture-root :skills [data-skill]})
                                       "alpha")))))

(deftest name-ordering-is-by-code-point-on-every-host-visible-list
  ;; Skill names are NOT sanitized — unlike MCP tool names, which
  ;; `mcp-tool-name` reduces to [a-zA-Z0-9_-] — so a name above the BMP reaches
  ;; these sorts intact, and a bare `sort`/`sort-by` orders it OPPOSITELY on the
  ;; two hosts: the JVM by UTF-16 code unit (a surrogate D83D below E000),
  ;; cljgo by UTF-8 byte (F0 above EE). Both lists below are host-visible — the
  ;; §3 system-prompt catalog, and the `:filter-unmatched` names the warn line
  ;; prints — and every other fixture in this file is ASCII, where the two
  ;; orders coincide. U+E000 is an escape (an ordinary BMP char); U+1F600 is
  ;; written DIRECTLY, because an escaped surrogate is not portable source.
  (let [nm (fn [n] {:name n :description "D." :content "C."})
        ld (skill/load-skills {:skills [(nm "😀s") (nm "\uE000s") (nm "zs") (nm "as")]})]
    (testing "§3 catalog order"
      (is (= (str skill/skills-prompt-preamble
                  "\n\n## Available Skills\n"
                  "- **as**: D.\n"
                  "- **zs**: D.\n"
                  "- **\uE000s**: D.\n"
                  "- **😀s**: D.")
             (skill/skills-prompt ld))))
    (testing ":filter-unmatched order"
      (let [f (skill/load-skills {:skills [(nm "as")]
                                  :filter {"😀no" true "\uE000no" true "zno" true "ano" true}})]
        (is (= ["ano" "zno" "\uE000no" "😀no"] (vec (:filter-unmatched f))))))))

;; ---------------------------------------------------------------------------
;; §3 S2 — the per-agent skills allowlist
;; ---------------------------------------------------------------------------

(deftest skills-filter-allowlist-exposes-only-true-names
  (let [ld (skill/load-skills {:dirs fixture-root :filter {"alpha" true "beta" true}})]
    (is (= ["alpha" "beta"] (vec (sort (keys (:by-name ld))))))
    (is (= ["alpha" "beta"] (vec (sort (map :name (:skills ld))))))
    (testing "a filtered-out skill is not loadable either"
      (is (true? (:isError (skill/execute-skill ld "gamma")))))))

(deftest skills-filter-droplist-removes-named-skills
  (let [ld (skill/load-skills {:dirs fixture-root :filter {"gamma" false}})]
    (is (= ["alpha" "beta" "many"] (vec (sort (keys (:by-name ld))))))))

(deftest an-unknown-filter-name-is-ignored-and-recorded
  (let [ld (skill/load-skills {:dirs fixture-root :filter {"alpha" true "nope" true}})]
    (is (= ["alpha"] (vec (sort (keys (:by-name ld))))))
    (testing "the unmatched name is DATA on the result, so the warning is testable
              without capturing stdout"
      (is (= ["nope"] (:filter-unmatched ld))))))

(deftest nil-and-empty-filters-expose-everything
  (is (= 4 (count (:skills (skill/load-skills {:dirs fixture-root :filter nil})))))
  (is (= 4 (count (:skills (skill/load-skills {:dirs fixture-root :filter {}})))))
  (is (= [] (:filter-unmatched (skill/load-skills {:dirs fixture-root})))))

(deftest keyword-filter-keys-work-too
  ;; A caller writing Clojure will reach for keywords; a caller reading an
  ;; mcp.json will have strings. Both must mean the same thing.
  (is (= ["alpha"] (vec (sort (keys (:by-name (skill/load-skills
                                               {:dirs fixture-root :filter {:alpha true}}))))))))

(deftest the-inventory-is-unfiltered
  ;; js/src/skill.ts listSkills() ignores opts.filter entirely — the inventory
  ;; exists to AUTHOR the allowlist, so filtering it would be circular.
  (is (= 4 (count (:skills (skill/list-skills {:dirs fixture-root :filter {"alpha" true}}))))))

;; ---------------------------------------------------------------------------
;; §3 S5 — the sample cap travels with the source
;; ---------------------------------------------------------------------------

(deftest sample-limit-travels-with-the-loaded-source
  (testing "-1 omits the block"
    (let [tk (tool/toolkit [(skill/skill-tool (skill/load-skills {:dirs fixture-root
                                                                  :sample-limit -1}))])]
      (is (not (str/includes? (:output (tool/execute tk "skill" {"name" "alpha"}))
                              "<skill_files>")))))
  (testing "a positive cap caps"
    (let [tk (tool/toolkit [(skill/skill-tool (skill/load-skills {:dirs fixture-root
                                                                  :sample-limit 2}))])]
      (is (= 2 (count (file-lines (:output (tool/execute tk "skill" {"name" "many"}))))))))
  (testing "0 is the default 10"
    (let [tk (tool/toolkit [(skill/skill-tool (skill/load-skills {:dirs fixture-root
                                                                  :sample-limit 0}))])]
      (is (= 10 (count (file-lines (:output (tool/execute tk "skill" {"name" "many"})))))))))

(deftest a-positive-cap-also-caps-logical-resources
  (let [ld (skill/load-skills {:skills [(assoc data-skill :resources ["a" "b" "c"])]
                               :sample-limit 2})]
    (is (= ["<file>a</file>" "<file>b</file>"]
           (file-lines (:output (skill/execute-skill ld "data-a")))))))
