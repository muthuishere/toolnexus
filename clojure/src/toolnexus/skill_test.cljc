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
  ;; unclosed — an opening fence that never closes. That is NOT frontmatter, so
  ;; it is `missing-name`, which is what the other six ports report (their
  ;; frontmatter regex simply fails to match). This port used to call it
  ;; malformed-frontmatter — issue #93 / ADR 0028.
  (write-fixture! (str fixture-root "/unclosed/SKILL.md")
                  "---\nname: unclosed\ndescription: never closed\n\nBody.\n")
  ;; blockscalar — a folded block scalar. Ordinary YAML, LOADED since the port
  ;; grew a real YAML parser; it used to be the documented divergence that cost
  ;; 42 of 87 real skills.
  (write-fixture! (str fixture-root "/blockscalar/SKILL.md")
                  "---\nname: blocky\ndescription: >\n  folded text\n---\n\nBody.\n")
  ;; tabbed — genuinely malformed: tab indentation, and no `name` the lenient
  ;; rescue can take. Still refused, and the skip now carries the parser's own
  ;; message in `:detail`.
  (write-fixture! (str fixture-root "/tabbed/SKILL.md")
                  "---\ndescription: x\n\tbad: value\n---\n\nBody.\n")
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
    (is (= ["alpha" "beta" "blocky" "gamma" "many"] (vec (sort (keys (:by-name ld))))))
    (testing "nested SKILL.md is discovered at any depth"
      (is (= (str fixture-root "/nested/deep/beta") (:dir (get (:by-name ld) "beta")))))
    (testing "the body after the frontmatter is the content"
      (is (str/includes? (:content (get (:by-name ld) "alpha")) "Alpha body line.")))))

(deftest discovery-order-is-pinned-by-code-point
  ;; Addendum A1/A1a: lexicographic by path relative to the root's logical base,
  ;; compared by CODE POINT. A1b's consequence is asserted directly below —
  ;; `docx/SKILL.md` beats `synced/<hash>/docx/SKILL.md`, and all seven ports
  ;; must pick the same winner or first-name-wins means a different file per
  ;; language.
  (let [root (str fixture-base "/order")]
    (write-fixture! (str root "/synced/9f2/docx/SKILL.md")
                    (skill-md "docx" "The synced copy that must LOSE." "synced body"))
    (write-fixture! (str root "/docx/SKILL.md")
                    (skill-md "docx" "The top-level copy that must WIN." "top body"))
    (let [ld (skill/load-skills root)]
      (is (= "The top-level copy that must WIN."
             (:description (get (:by-name ld) "docx"))))
      (is (= (str root "/docx") (:dir (get (:by-name ld) "docx"))))
      (is (some #(and (= "duplicate-name" (:reason %))
                      (str/includes? (:location %) "/synced/"))
                (:skipped ld))))))

(deftest a-shallower-path-beats-a-nested-one-for-EVERY-name
  ;; Addendum A15 — the row a `docx`-only fixture cannot catch.
  ;;
  ;; §3 says a shallower path beats a nested copy of the same name. Under the
  ;; PURE code-point sort A1a originally specified, that was true only by
  ;; accident of the first letter:
  ;;
  ;;   "docx/SKILL.md"           < "synced/9f2/docx/SKILL.md"   (d < s)  WINS
  ;;   "xlsx/SKILL.md"           > "synced/9f2/xlsx/SKILL.md"   (x > s)  LOSES
  ;;
  ;; So the winner depended on the skill's first letter relative to a sibling
  ;; DIRECTORY's name. Both names are asserted here on purpose: `docx` passes
  ;; under either rule and proves nothing, and that is exactly how six ports
  ;; and a spec review all missed this. `xlsx` is the discriminator.
  (let [root (str fixture-base "/depth")]
    (doseq [nm ["docx" "xlsx"]]
      (write-fixture! (str root "/synced/9f2/" nm "/SKILL.md")
                      (skill-md nm (str "The nested " nm " copy that must LOSE.") "nested body"))
      (write-fixture! (str root "/" nm "/SKILL.md")
                      (skill-md nm (str "The top-level " nm " copy that must WIN.") "top body")))
    (let [ld (skill/load-skills root)]
      (doseq [nm ["docx" "xlsx"]]
        (is (= (str "The top-level " nm " copy that must WIN.")
               (:description (get (:by-name ld) nm)))
            (str nm ": the SHALLOWER path must win regardless of its first letter"))
        (is (= (str root "/" nm) (:dir (get (:by-name ld) nm)))))
      (is (= 2 (count (filter #(= "duplicate-name" (:reason %)) (:skipped ld))))
          "both nested copies are typed duplicate skips")))
  (testing "the comparator itself: depth ascending, then code point within a depth"
    (is (neg? (skill/discovery-compare "/xlsx/SKILL.md" "/synced/9f2/xlsx/SKILL.md"))
        "depth wins over the letter — this is the whole of A15")
    (testing "and the two rules genuinely DISAGREE here — otherwise this proves nothing"
      ;; The assertion that makes the fixture a test. A `docx` fixture passes
      ;; under BOTH rules, which is exactly how six ports and one spec review
      ;; all shipped this bug. Deleting the depth comparison from
      ;; `discovery-compare` must turn this row red, and here is the reason it
      ;; would: the OLD rule returns the OPPOSITE sign for `xlsx`.
      (is (pos? (tool/compare-strings "/xlsx/SKILL.md" "/synced/9f2/xlsx/SKILL.md"))
          "pure code point (the pre-A15 rule) puts the NESTED xlsx copy first")
      (is (neg? (tool/compare-strings "/docx/SKILL.md" "/synced/9f2/docx/SKILL.md"))
          "…and agrees with A15 for docx, which is why docx alone is not a fixture"))
    (is (neg? (skill/discovery-compare "/docx/SKILL.md" "/synced/9f2/docx/SKILL.md")))
    (is (neg? (skill/discovery-compare "/a/SKILL.md" "/b/SKILL.md"))
        "within one depth the A1c code-point tie-break still decides")
    (is (neg? (skill/discovery-compare "/\uE000/SKILL.md" "/\uD83D\uDE00/SKILL.md"))
        "and it is still code POINT, not UTF-16 code unit")))

(deftest path-helpers-are-rune-safe-on-both-hosts
  ;; A REAL host divergence, found by the astral discovery fixture above and
  ;; fixed in `toolnexus.tool/last-index-of-char`.
  ;;
  ;; `clojure.string/last-index-of` returns a BYTE offset on the cljgo host,
  ;; while `subs`/`count`/`nth` work in RUNES. So for ANY path containing a
  ;; non-ASCII character the index and the slice disagreed and the split landed
  ;; mid-name — measured, before the fix, on cljgo:
  ;;
  ;;   parent-dir "/base/\uE000dir/SKILL.md"  ->  "/base/\uE000dir/S"   (+2)
  ;;   parent-dir "/base/😀dir/SKILL.md"       ->  "/base/😀dir/SK"      (+3)
  ;;
  ;; U+E000 is 3 UTF-8 bytes and 1 rune; an astral character is 4 and 1. The JVM
  ;; host was always correct, which is precisely why this survived: every path
  ;; in every fixture was ASCII, so no test could see it. It is asserted here
  ;; rather than only through discovery because `file-name` also feeds a skill's
  ;; reported directory and a content part's extension.
  (doseq [[path dir nm]
          [["/base/plain/dir/SKILL.md"        "/base/plain/dir"        "SKILL.md"]
           ["/base/astral/\uE000dir/SKILL.md" "/base/astral/\uE000dir" "SKILL.md"]
           ["/base/astral/\uD83D\uDE00dir/SKILL.md"
            "/base/astral/\uD83D\uDE00dir" "SKILL.md"]
           ["/base/\uE000/SKILL.md"           "/base/\uE000"           "SKILL.md"]
           ["SKILL.md"                        "."                      "SKILL.md"]]]
    (is (= dir (skill/parent-dir path)) (str "parent-dir of " (pr-str path)))
    (is (= nm (skill/file-name path))   (str "file-name of "  (pr-str path))))
  (testing "the seam itself: the index is in the SAME units as `subs` on this host"
    (doseq [s ["/a/b" "/\uE000/b" "/\uD83D\uDE00/b"]]
      (let [i (tool/last-index-of-char s \/)]
        (is (= "b" (subs s (inc i)))
            (str "index and slice must agree for " (pr-str s)))))))

(deftest discovery-order-astral-plane-path-agrees-on-both-hosts
  ;; Addendum A1c — the ONE case where code-POINT order and the natural
  ;; UTF-16 code-UNIT order of a JVM `compare`/`compareTo` disagree, expressed
  ;; where it actually bites: a DIRECTORY NAME, not a skill name.
  ;;
  ;;   U+E000 PRIVATE USE      UTF-16 = one unit  E000
  ;;   U+1F600 GRINNING FACE   UTF-16 = D83D DE00, and D83D < E000
  ;;
  ;; So by code POINT "\uE000" sorts BEFORE the emoji, and by code UNIT it
  ;; sorts AFTER it. Both directories declare the SAME skill name, so
  ;; first-wins turns that ordering into a DIFFERENT FILE and a DIFFERENT
  ;; `content` — silently, and only on an astral path. Asserting the winner is
  ;; what stops the JVM host and the cljgo host (whose runes are already code
  ;; points) from quietly disagreeing.
  (let [root (str fixture-base "/astral")]
    (write-fixture! (str root "/\uE000dir/SKILL.md")
                    (skill-md "astral" "The U+E000 copy that must WIN." "pua body"))
    (write-fixture! (str root "/\uD83D\uDE00dir/SKILL.md")
                    (skill-md "astral" "The U+1F600 copy that must LOSE." "emoji body"))
    (let [ld (skill/load-skills root)]
      (is (= "The U+E000 copy that must WIN."
             (:description (get (:by-name ld) "astral")))
          "code-POINT order wins; UTF-16 code-unit order would pick the emoji")
      (is (= (str root "/\uE000dir") (:dir (get (:by-name ld) "astral"))))
      (is (some (fn [s] (and (= "duplicate-name" (:reason s))
                             (str/includes? (:location s) "\uD83D\uDE00dir")))
                (:skipped ld))
          "the emoji-path copy is the typed duplicate skip"))))

(deftest discovery-order-is-deterministic
  ;; The first-wins rule is order-dependent, so the port is only deterministic
  ;; because koine.fs/find-files SORTS. Assert the sorted property directly:
  ;; if that sort ever leaves koine, this is the test that catches it.
  (let [paths (vec (fs/find-files fixture-root "/SKILL.md"))]
    (is (= (vec (sort paths)) paths))
    (is (= 9 (count paths)))))

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
  ;; A file neither the YAML parser nor the lenient rescue can name is a typed
  ;; skip, isolated the way §0.3 isolates a failed MCP server: every other skill
  ;; still loads.
  (let [ld (loaded)
        malformed (filter #(= "malformed-frontmatter" (:reason %)) (:skipped ld))]
    (is (= 1 (count malformed)))
    (testing "tab indentation with no rescuable name is still refused"
      (is (some #(str/includes? (:location %) "/tabbed/") malformed)))
    (testing "and the skip carries the parser's OWN message, so the file is fixable
              — ADR 0028 decision 2. `:reason` stays byte-identical across ports;
              `:detail` is native and is never compared for parity."
      (is (re-find #"tab" (str (:detail (first malformed)))))
      (is (re-find #"line" (str (:detail (first malformed))))))
    (testing "an unclosed fence is missing-name, as in the other six ports"
      (is (some #(and (= "missing-name" (:reason %))
                      (str/includes? (:location %) "/unclosed/"))
                (:skipped ld))))
    (testing "a folded block scalar LOADS now — issue #93"
      (is (= "folded text" (:description (get (:by-name ld) "blocky")))))
    (testing "the five healthy skills survived"
      (is (= 5 (count (:skills ld)))))))

(deftest load-skills-returns-its-skips
  ;; A host calling load-skills must not need list-skills to learn that files
  ;; vanished (ADR 0028 decision 3 / addendum A2: RETURNED DATA, not a hook).
  (let [ld (loaded)]
    (is (seq (:skipped ld)))
    (is (every? (fn [s] (and (string? (:location s)) (string? (:reason s)))) (:skipped ld)))))

(deftest list-skills-inventory
  (let [inv (skill/list-skills fixture-root)]
    (is (= 5 (count (:skills inv))))
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
    (is (= "Skill \"does-not-exist\" not found. Available skills: alpha, beta, blocky, gamma, many"
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
                "- **blocky**: folded text\n"
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

(def ^:private a22-names
  "Golang's fixture, reused verbatim so all seven ports argue over one list.
  Every pair of adjacent entries is ranked DIFFERENTLY by at least one of the
  three candidate rules:

    Zurich   U+005A  uppercase — a COLLATOR interleaves case and leads with `apple`
    apple    U+0061
    zebra    U+007A
    Äpfel    U+00C4  a collator files this beside `apple`, code point puts it after `zebra`
    \uFFFD       U+FFFD  the last BMP code point
    \uD835\uDD1E  U+1D51E astral — UTF-16 code-UNIT order puts its LEAD SURROGATE
                          (U+D835) BEFORE U+FFFD, code point puts it after

  So: a locale collator fails on the case rows, `compare`/`compareTo`/
  `StringComparer.Ordinal` (UTF-16 code unit) fails on the last row, and only
  code point produces the order below. The JVM host's default string `compare`
  IS UTF-16 code-unit order, so this is exactly where the two hosts diverge."
  ["Zurich" "apple" "zebra" "\u00C4pfel" "\uFFFD" "\uD835\uDD1E"])

(deftest the-a22-fixture-is-itself-code-point-ordered
  ;; Golang's test self-checks its own expectation, and so does this one: an
  ;; expectation hand-written in the wrong order would make every assertion
  ;; below agree with a bug. Proven against the seam, and then against the
  ;; RAW code points, so it does not merely agree with itself.
  (is (= a22-names (tool/sort-strings a22-names)))
  (let [cp-lex (fn [a b]
                 ;; LEXICOGRAPHIC over code points. NOT `compare` on the
                 ;; code-point vectors — clojure.core/compare orders vectors by
                 ;; LENGTH first, which would rank "\uFFFD" above "apple"; the
                 ;; port's own tool_test records the same trap.
                 (let [xs (vec (tool/code-points a))
                       ys (vec (tool/code-points b))]
                   (loop [i 0]
                     (cond (and (>= i (count xs)) (>= i (count ys))) 0
                           (>= i (count xs)) -1
                           (>= i (count ys)) 1
                           (not= (nth xs i) (nth ys i)) (compare (nth xs i) (nth ys i))
                           :else (recur (inc i))))))]
    (is (= (vec (sort cp-lex a22-names)) a22-names)
        "…and against the RAW code points, independently of the seam"))
  ;; NOT asserted here: "a bare `sort` disagrees". That is true on the JVM host
  ;; (UTF-16 code units) and FALSE on cljgo (UTF-8 bytes, which ARE code-point
  ;; order), so it is a HOST-SPECIFIC claim and a suite that runs unchanged on
  ;; both cannot make it. That asymmetry is precisely why the seam exists: the
  ;; bug can only ever appear on one of the two hosts, so the assertions below
  ;; pin the ORDER ITSELF rather than the failure of an alternative.
  )

(deftest a22-every-user-visible-skill-list-is-code-point-ordered
  ;; A22. The §0.10 prompt was the reported site; the audit found FOUR
  ;; user-visible orderings, and the lesson was that nobody had asked where ELSE
  ;; the codebase sorts model-visible data. All four are pinned here.
  (let [mk (fn [n] {:name n :description "D." :content "C."})
        ld (skill/load-skills {:skills (mapv mk (reverse a22-names))})]

    (testing "(1) the §0.10 skills-prompt catalog — SPEC-pinned byte-identical"
      (is (= (str skill/skills-prompt-preamble
                  "\n\n## Available Skills\n"
                  (str/join "\n" (map (fn [n] (str "- **" n "**: D.")) a22-names)))
             (skill/skills-prompt ld))))

    (testing "(2) the `skill` tool's NOT-FOUND message, which enumerates every
              skill name TO THE MODEL. js and csharp each had this wrong and it
              was in nobody's bug report."
      (is (= (str "Skill \"nope\" not found. Available skills: "
                  (str/join ", " a22-names))
             (:output (skill/execute-skill ld "nope")))))))

(def ^:private a25-rels
  "The `<skill_files>` fixture, in the order A25's rule produces: sort by the
  path RELATIVE to the skill directory, in PLAIN Unicode code point.

  Every entry earns its place by RULING OUT a specific wrong rule. csharp's
  first attempt at this test passed under the wrong rule and it found out only
  by mutating the implementation, because its nested files sorted identically
  by bare name and by relative path — so it asserted only that SOME sort had
  happened.

    `alpha-b.txt` before `alpha/f.txt`   rules out PER-LEVEL (flat vs per-level)
        Flat relative-path order compares `-` (0x2D) against `/` (0x2F) at the
        sixth character, so the FILE wins. A level-by-level walk sorts the
        names `alpha` and `alpha-b.txt` first, descends into `alpha` and emits
        `alpha/f.txt` FIRST. The two rules disagree on these two rows alone.

    `c.txt` fourth, not second           rules out BARE NAME
        By bare name the entries sort `alpha-b.txt, c.txt, f.txt, zz-a.txt,
        zz.txt, \u00DF.txt`, putting `c.txt` second; by relative path `alpha/f.txt`
        and `b-nested/zz-a.txt` both precede it. `b-nested/zz-a.txt` is the
        mirror image — third by path, fifth by bare name.

    `\u00DF.txt` last                         rules out a LOCALE COLLATOR
        `\u00DF` collates as `ss`, so a collator files it among the `s`/`z` names;
        by code point it is 0x00DF, after every ASCII letter. It also has no NFD
        decomposition, so macOS filename normalisation cannot hollow the row
        out. MEASURED CAVEAT for the other ports: a sibling literally named
        `ss` cannot be used beside it — macOS folds the two to ONE file, and
        the fixture then silently shrinks rather than failing.

    `U+FFFD.txt` before the astral name   rules out UTF-16 CODE UNIT
        U+FFFD is the last BMP code point and U+1D51E is astral. By CODE POINT
        U+FFFD comes first; in UTF-16 CODE-UNIT order the astral name's LEAD
        SURROGATE (U+D835) sorts BELOW U+FFFD, so the two swap. This is the only
        pair in the fixture that separates code point from the JVM host's
        default `compare`, and without it a bare `sort` passes this test — which
        is exactly what the mutation run caught."
  ["alpha-b.txt" "alpha/f.txt" "b-nested/zz-a.txt" "c.txt" "zz.txt" "\u00DF.txt"
   "�.txt" "𝔞.txt"])

(deftest a25-the-skill-files-sample-is-sorted-by-relative-path-then-capped
  ;; (4) The `<skill_files>` SAMPLE LIST — model-visible text built from a
  ;; DIRECTORY READ, so with no explicit sort it carries filesystem order.
  ;; A25's settled rule: COLLECT every candidate, SORT by the path relative to
  ;; the skill directory in plain code point, THEN truncate to the cap.
  ;;
  ;; SORT-BEFORE-CAP is the load-bearing half (ADR-0004 K1): the list is capped,
  ;; so the order decides WHICH files reach the model at all. It is sharper for
  ;; this port than any other — `koine.fs/list-tree` promises no order on either
  ;; host, so a cap applied mid-walk could ship different sample CONTENTS from
  ;; the JVM host and the cljgo host for the same skill.
  (let [root  (str fixture-base "/a25files")
        sdir  (str root "/sampled")
        rels  (fn [out]
                (mapv (fn [l] (-> l
                                  (str/replace "<file>" "")
                                  (str/replace "</file>" "")
                                  (str/replace (str sdir "/") "")))
                      (file-lines out)))]
    (write-fixture! (str sdir "/SKILL.md") (skill-md "sampled" "Has siblings." "Body."))
    ;; created in REVERSE, so a walk that preserved creation order, or capped
    ;; before sorting, produces a visibly different answer
    (doseq [r (reverse a25-rels)]
      (write-fixture! (str sdir "/" r) "x\n"))
    (testing "the fixture really landed — a name the filesystem folded away would
              make this test vacuous rather than red"
      (is (= (count a25-rels)
             (count (remove (fn [p] (str/ends-with? (str p) "SKILL.md"))
                            (fs/find-files sdir ".txt"))))))
    (testing "the FULL order, uncapped"
      (let [ld (skill/load-skills {:dirs root :sample-limit 10})]
        (is (= a25-rels (rels (:output (skill/execute-skill ld "sampled")))))))
    (testing "the CAPPED prefix — WHICH files appear, not merely their order"
      (let [ld  (skill/load-skills {:dirs root :sample-limit 2})
            got (rels (:output (skill/execute-skill ld "sampled")))]
        (is (= ["alpha-b.txt" "alpha/f.txt"] got)
            "the cap takes the first two IN SORTED ORDER")
        (is (not (some #{"\u00DF.txt" "zz.txt"} got))
            "a cap-during-walk implementation leaks a late-sorting file in here")))))

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
        (is (= ["ano" "zno" "\uE000no" "😀no"] (vec (:filter-unmatched f)))))))
  (testing "A22 — the §0.10 prompt order is CODE POINT, which also rules out a
            LOCALE COLLATOR. The astral pair above discriminates code point from
            UTF-16 code unit (csharp's `StringComparer.Ordinal` bug); it does NOT
            discriminate either from a collator, because a collator agrees with
            both there. Case does: by code point every uppercase letter precedes
            every lowercase one (`Z` = 0x5A < `a` = 0x61), while essentially any
            collator interleaves them and puts `apple` first. js sorted this
            prompt with `localeCompare`, so its order was not even stable across
            MACHINES — and SPEC §0.10 pins this prompt as byte-identical."
    (let [nm (fn [n] {:name n :description "D." :content "C."})
          ld (skill/load-skills {:skills [(nm "apple") (nm "Zebra") (nm "Banana") (nm "cherry")]})]
      (is (= (str skill/skills-prompt-preamble
                  "\n\n## Available Skills\n"
                  "- **Banana**: D.\n"
                  "- **Zebra**: D.\n"
                  "- **apple**: D.\n"
                  "- **cherry**: D.")
             (skill/skills-prompt ld))
          "uppercase before lowercase — a collator would lead with `apple`"))))

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
    (is (= ["alpha" "beta" "blocky" "many"] (vec (sort (keys (:by-name ld))))))))

(deftest an-unknown-filter-name-is-ignored-and-recorded
  (let [ld (skill/load-skills {:dirs fixture-root :filter {"alpha" true "nope" true}})]
    (is (= ["alpha"] (vec (sort (keys (:by-name ld))))))
    (testing "the unmatched name is DATA on the result, so the warning is testable
              without capturing stdout"
      (is (= ["nope"] (:filter-unmatched ld))))))

(deftest nil-and-empty-filters-expose-everything
  (is (= 5 (count (:skills (skill/load-skills {:dirs fixture-root :filter nil})))))
  (is (= 5 (count (:skills (skill/load-skills {:dirs fixture-root :filter {}})))))
  (is (= [] (:filter-unmatched (skill/load-skills {:dirs fixture-root})))))

(deftest keyword-filter-keys-work-too
  ;; A caller writing Clojure will reach for keywords; a caller reading an
  ;; mcp.json will have strings. Both must mean the same thing.
  (is (= ["alpha"] (vec (sort (keys (:by-name (skill/load-skills
                                               {:dirs fixture-root :filter {:alpha true}}))))))))

(deftest the-inventory-is-unfiltered
  ;; js/src/skill.ts listSkills() ignores opts.filter entirely — the inventory
  ;; exists to AUTHOR the allowlist, so filtering it would be circular.
  (is (= 5 (count (:skills (skill/list-skills {:dirs fixture-root :filter {"alpha" true}}))))))

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
