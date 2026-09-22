(ns toolnexus.frontmatter-test
  (:require [clojure.test :refer [deftest is testing]]
            [toolnexus.frontmatter :as fm]
            [toolnexus.yaml :as yaml]))

(defn- refused? [text]
  (try (yaml/parse text) false
       (catch Throwable e (boolean (:yaml (ex-data e))))))

(deftest parses-ordinary-frontmatter
  (is (= [{:name "hi" :description "there"} "body"]
         (fm/parse "---\nname: hi\ndescription: there\n---\nbody")))
  (testing "quotes are stripped, and a colon inside them survives"
    (is (= "x: y" (:description (first (fm/parse "---\ndescription: 'x: y'\nname: a\n---\n"))))))
  (testing "comments and blank lines are ignored"
    (is (= {:name "a"} (first (fm/parse "---\n# a comment\n\nname: a\n---\n")))))
  (testing "an empty value is an empty string, not nil — ABSENT and EMPTY differ"
    (is (= "" (:description (first (fm/parse "---\nname: a\ndescription:\n---\n")))))
    (is (nil? (:description (first (fm/parse "---\nname: a\n---\n")))))))

(deftest no-frontmatter-is-not-an-error
  (is (= [{} "just a body"] (fm/parse "just a body"))))

(deftest body-is-everything-after-the-closing-delimiter
  (is (= "line1\nline2" (second (fm/parse "---\nname: a\n---\nline1\nline2")))))

;; ---------------------------------------------------------------------------
;; issue #93 / ADR 0028 — the port used to load 33 of 87 real skills
;; ---------------------------------------------------------------------------

(deftest real-yaml-is-parsed-not-a-subset
  (testing "a literal block scalar keeps its newlines and its inner colon"
    (is (= "First line of the description.\nSecond line, with a colon: still fine inside a block scalar."
           (:description (first (fm/parse (str "---\nname: block-literal\ndescription: |\n"
                                               "  First line of the description.\n"
                                               "  Second line, with a colon: still fine inside a block scalar.\n"
                                               "---\nbody")))))))
  (testing "a folded block scalar is folded onto one line"
    (is (= "A folded description that runs across two source lines."
           (:description (first (fm/parse (str "---\nname: block-folded\ndescription: >\n"
                                               "  A folded description that runs\n"
                                               "  across two source lines.\n"
                                               "---\nbody")))))))
  (testing "sequences, nested maps and anchors all parse; non-scalar keys drop out"
    (let [[m _] (fm/parse "---\nname: a\ndescription: d\nallowed-tools: [bash, read]\n---\nb")]
      (is (= "a" (:name m)))
      (is (nil? (:allowed-tools m))))
    (let [[m _] (fm/parse "---\nname: a\ndescription: d\nallowed-tools:\n  - bash\n  - read\n---\nb")]
      (is (= "d" (:description m))))
    (let [[m _] (fm/parse "---\nname: a\ndescription: d\nmetadata:\n  author: someone\n  version: 2\n---\nb")]
      (is (= "d" (:description m))))
    (let [[m _] (fm/parse "---\nname: anchors\ndefaults: &d common\ndescription: D\nfallback: *d\n---\nb")]
      (is (= "anchors" (:name m)))
      (is (= "common" (:fallback m))))))

(deftest the-lenient-rescue-runs-only-after-yaml-refuses
  (testing "the six-port casualty: an unquoted ': ' inside a plain description"
    (is (refused? "name: x\ndescription: Do things. Trigger on: update the timesheet."))
    (is (= "Do things. Trigger on: update the timesheet."
           (:description (first (fm/parse (str "---\nname: x\n"
                                               "description: Do things. Trigger on: update the timesheet.\n"
                                               "---\nbody")))))))
  (testing "a refused opener degrades to NO description, never to garbage like \"[\""
    (let [r (fm/read-frontmatter "---\nname: broken-flow\ndescription: [unterminated, flow\n---\nbody")]
      (is (nil? (:reason r)))
      (is (= "broken-flow" (:name (:data r))))
      (is (nil? (:description (:data r))))))
  (testing "first wins, and an indented line is never a rescued key"
    (let [r (fm/read-frontmatter "---\nname: first\nname: second\n  name: indented\ndescription: [\n---\nb")]
      (is (= "first" (:name (:data r))))))
  (testing "a file with no rescuable name is still refused, with the parser's own message"
    (let [r (fm/read-frontmatter "---\ndescription: x\n\tbad: value\n---\nb")]
      (is (= "malformed-frontmatter" (:reason r)))
      (is (re-find #"tab" (str (:detail r))))
      (is (re-find #"line" (str (:detail r)))))))

(deftest an-unclosed-fence-is-not-frontmatter
  (testing "the other six ports' regex simply fails to match, and they report
            missing-name; this port used to call it malformed-frontmatter"
    (is (= "missing-name" (:reason (fm/read-frontmatter "---\nname: x\nno closing fence\n"))))))

(deftest yaml-still-refuses-what-yaml-refuses
  (is (refused? "name: x\ndescription: a\n\tb: c")          "tab indentation")
  (is (refused? "name: [unterminated, flow")                "unterminated flow")
  (is (refused? "name: x\nother: *nope")                    "undefined alias")
  (is (refused? "a: b: c")                                  "mapping values in a plain scalar")
  (testing "a bare scalar document is legal YAML but is not a mapping, so there
            is no frontmatter to read and the file is missing-name"
    (is (= "nocolon" (yaml/parse "nocolon")))
    (is (= "missing-name" (:reason (fm/read-frontmatter "---\nnocolon\n---\nb"))))))

(deftest names-never-shadow-clojure-core
  (doseq [n (keys (ns-publics 'toolnexus.frontmatter))]
    (is (nil? (resolve (symbol "clojure.core" (name n))))
        (str "toolnexus.frontmatter/" n " shadows clojure.core/" n)))
  (doseq [n (keys (ns-publics 'toolnexus.yaml))]
    (is (nil? (resolve (symbol "clojure.core" (name n))))
        (str "toolnexus.yaml/" n " shadows clojure.core/" n))))

;; ---------------------------------------------------------------------------
;; The issue-93 fixture table (addendum A6b), pinned as DATA so it runs on BOTH
;; hosts in all five execution modes.
;;
;; `spikes/issues/93/fixtures/` is the arbiter and csharp + the lenient.py
;; prototype agree with it exactly: 17 files, 14 ok / 3 missing-name. The
;; fixtures are inlined rather than read from the spike because the suite is
;; hermetic and the spike is not shipped — the BYTES are what matters, and they
;; are copied verbatim.
;;
;; Two rows carry the traps:
;;   * `hash-inline` is decided by COMMENT handling, not by the rescue: " #"
;;     opens a YAML comment, so the description is exactly "Tag things with".
;;     The STRING is asserted, never just the verdict.
;;   * `broken-flow` must be ok with the NAME KEPT and NO description — that row
;;     is the proof the non-string/refused-opener guard (A10) is implemented.
;;
;; This port cannot have the two hosts disagree the way a port with two native
;; YAML libraries can: `toolnexus.yaml` IS the library, in portable Clojure, so
;; both hosts run the same bytes.
;; ---------------------------------------------------------------------------

(def ^:private fixture-table
  ;; [name  frontmatter-text  expected-reason  expected-name  expected-description]
  [["plain" "---\nname: plain\ndescription: An ordinary skill with an ordinary one-line description.\n---\nbody"
    nil "plain" "An ordinary skill with an ordinary one-line description."]
   ["colon-space" "---\nname: colon-space\ndescription: Work out billable hours from git commits and session logs. Trigger on: update the timesheet, do my timesheet, how many hours did I work.\n---\nbody"
    nil "colon-space" "Work out billable hours from git commits and session logs. Trigger on: update the timesheet, do my timesheet, how many hours did I work."]
   ["colon-space-quoted" "---\nname: colon-space-quoted\ndescription: \"Work out billable hours. Trigger on: update the timesheet, do my timesheet.\"\n---\nbody"
    nil "colon-space-quoted" "Work out billable hours. Trigger on: update the timesheet, do my timesheet."]
   ["colon-space-single" "---\nname: colon-space-single\ndescription: 'Work out billable hours. Trigger on: update the timesheet.'\n---\nbody"
    nil "colon-space-single" "Work out billable hours. Trigger on: update the timesheet."]
   ["url-colon" "---\nname: url-colon\ndescription: Fetch pages from https://example.com/docs and summarise them.\n---\nbody"
    nil "url-colon" "Fetch pages from https://example.com/docs and summarise them."]
   ["hash-inline" "---\nname: hash-inline\ndescription: Tag things with #stockloop and report. Trigger on: tag it.\n---\nbody"
    nil "hash-inline" "Tag things with"]
   ["block-literal" "---\nname: block-literal\ndescription: |\n  First line of the description.\n  Second line, with a colon: still fine inside a block scalar.\n---\nbody"
    nil "block-literal" "First line of the description.\nSecond line, with a colon: still fine inside a block scalar."]
   ["block-folded" "---\nname: block-folded\ndescription: >\n  A folded description that runs\n  across two source lines.\n---\nbody"
    nil "block-folded" "A folded description that runs across two source lines."]
   ["list-value" "---\nname: list-value\ndescription: A skill that also declares a list-valued key.\nallowed-tools: [bash, read]\n---\nbody"
    nil "list-value" "A skill that also declares a list-valued key."]
   ["list-block" "---\nname: list-block\ndescription: A skill with a block-sequence key.\nallowed-tools:\n  - bash\n  - read\n---\nbody"
    nil "list-block" "A skill with a block-sequence key."]
   ["nested-map" "---\nname: nested-map\ndescription: A skill with a nested mapping key.\nmetadata:\n  author: someone\n  version: 2\n---\nbody"
    nil "nested-map" "A skill with a nested mapping key."]
   ["anchors" "---\nname: anchors\ndefaults: &d common\ndescription: A skill using a YAML anchor and alias.\nfallback: *d\n---\nbody"
    nil "anchors" "A skill using a YAML anchor and alias."]
   ["broken-flow" "---\nname: broken-flow\ndescription: [unterminated, flow, sequence\n---\nbody"
    nil "broken-flow" nil]
   ["broken-tab" "---\nname: broken-tab\ndescription: tab-indented continuation\n\tbad: value\n---\nbody"
    nil "broken-tab" "tab-indented continuation"]
   ["broken-unclosed" "---\nname: broken-unclosed\ndescription: The frontmatter fence is never closed.\nbody with no closing fence"
    "missing-name" nil nil]
   ["no-frontmatter" "# Just a markdown file\nNo frontmatter at all here."
    "missing-name" nil nil]
   ["no-name" "---\ndescription: Has a description but no name key.\n---\nbody"
    "missing-name" nil "Has a description but no name key."]])

(deftest non-string-scalars-coerce-and-do-not-trigger-the-rescue
  ;; Addendum A16 — A10's "present but NOT a string" means "not a SCALAR".
  ;; The arbiter is spikes/issues/93/prototype/lenient.py, which coerces
  ;; str/int/float/bool to their string form and excludes ONLY mappings and
  ;; sequences. Treating `name: 123` as structurally wrong would break parity
  ;; in the OTHER direction from the bug A10 closes.
  ;;
  ;; The assertion is deliberately on the COERCED STRING, never on a parsed
  ;; type: two hosts' YAML implementations are exactly where a `1.0` vs `1`
  ;; disagreement would hide. This port removes that risk at the root —
  ;; `toolnexus.yaml` does no implicit typing, so a scalar is already a string
  ;; on both hosts — but the observable value is what conformance compares, so
  ;; the observable value is what is pinned.
  (testing "an integer-looking name loads as its string form"
    (let [r (fm/read-frontmatter "---\nname: 123\ndescription: ok\n---\nbody")]
      (is (nil? (:reason r)))
      (is (= "123" (:name (:data r))))
      (is (= "ok" (:description (:data r))))))
  (testing "a boolean-looking description loads as its string form"
    (let [r (fm/read-frontmatter "---\nname: boolish\ndescription: true\n---\nbody")]
      (is (nil? (:reason r)))
      (is (= "true" (:description (:data r))))))
  (testing "a float-looking scalar keeps its SOURCE spelling — no host-dependent reformatting"
    (let [r (fm/read-frontmatter "---\nname: floaty\ndescription: 1.50\n---\nbody")]
      (is (= "1.50" (:description (:data r))))))
  (testing "ONLY a mapping or a sequence is structurally wrong (A10 via A16)"
    (let [seqd (fm/read-frontmatter "---\nname: seqd\ndescription:\n  - one\n  - two\n---\nbody")
          mapd (fm/read-frontmatter "---\nname: mapd\ndescription:\n  inner: x\n---\nbody")]
      (is (= "seqd" (:name (:data seqd))) "the name survives; only the bad key is dropped")
      (is (nil? (:description (:data seqd))) "no INVENTED description, and no wrong one kept")
      (is (= "mapd" (:name (:data mapd))))
      (is (nil? (:description (:data mapd)))))))

(deftest issue-93-fixture-table
  (doseq [[nm text reason exp-name exp-desc] fixture-table]
    (let [r (fm/read-frontmatter text)]
      (is (= reason (:reason r)) (str nm ": reason"))
      (is (= exp-name (:name (:data r))) (str nm ": name"))
      (is (= exp-desc (:description (:data r))) (str nm ": description"))))
  (testing "14 ok / 3 missing-name — the same totals csharp and the prototype report"
    (is (= 14 (count (remove (fn [row] (nth row 2)) fixture-table))))
    (is (= 3 (count (filter (fn [row] (= "missing-name" (nth row 2))) fixture-table))))))
