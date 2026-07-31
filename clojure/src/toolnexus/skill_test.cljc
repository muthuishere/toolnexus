;; Tests for toolnexus.skill (SPEC §0.5 / §0.6 / §3).
;;
;; ONE .cljc suite, run unchanged on Clojure (JVM) and on cljgo. No java.*,
;; no Thread/sleep, no network, no LLM.
;;
;; Two fixture sources on purpose:
;;   * the SHARED examples/skills tree (path from TN_EXAMPLES) — the byte-exact
;;     conformance fixture, the one that carries an ABSOLUTE base path;
;;   * a small tree this suite builds at a RELATIVE path — a mktemp path would
;;     put a random absolute string inside every `Base directory:` and `<file>`
;;     line and every byte count below would move between runs.
(ns toolnexus.skill-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [koine.env :as env]
            [koine.fs :as fs]
            [toolnexus.skill :as skill]
            [toolnexus.tool :as tool]))

;; ---------------------------------------------------------------------------
;; the relative fixture tree
;; ---------------------------------------------------------------------------

(def fixture-base "test-fixtures")
(def fixture-root "test-fixtures/skills")

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
