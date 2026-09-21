;; SPEC §4A + §0.11 — the ten builtins. Dual-host: no java.*, no Thread/sleep.
;;
;; Everything that touches the filesystem happens inside `koine.fs/temp-dir!`
;; and is deleted in a fixture; the repo is never written to. The only network
;; is a koine.server on 127.0.0.1:0.
(ns toolnexus.builtin-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [koine.fs :as fs]
            [koine.json :as json]
            [koine.server :as server]
            [toolnexus.builtin :as builtin]
            [toolnexus.tool :as tool]))

(def ^:private root (atom nil))
(def ^:private srv  (atom nil))
(def ^:private base (atom nil))

(defn- page-handler [req]
  (if (= "/page" (str (:path req)))
    {:status 200 :headers {"content-type" "text/html"}
     :body "<html><body><h1>Title</h1><p>Hello <b>world</b></p></body></html>"}
    {:status 404 :headers {"content-type" "text/plain"} :body "not found"}))

(use-fixtures :once
  (fn [f]
    (let [d (fs/temp-dir! "tn-builtin")
          s (server/serve page-handler {:port 0})]
      (reset! root d)
      (reset! srv s)
      (reset! base (str "http://127.0.0.1:" (server/port s)))
      (try
        (f)
        (finally
          (server/stop! s)
          ;; koine.fs/delete-tree! — s22 had to shell out to `rm -rf` here.
          (fs/delete-tree! d))))))

(def ^:private all (builtin/enabled-builtins nil))
(def ^:private tk (builtin/builtin-toolkit))

(defn- run
  ([n args] (run n args nil))
  ([n args ctx] (tool/execute tk n args ctx)))

(defn- p [& parts] (str/join "/" (cons @root parts)))

;; ---------------------------------------------------------------------------
;; the set, the schemas
;; ---------------------------------------------------------------------------

(deftest the-ten-names-in-spec-order
  (is (= ["bash" "read" "write" "edit" "grep" "glob" "webfetch" "question"
          "apply_patch" "todowrite"]
         builtin/builtin-names))
  (is (= 10 (count all)))
  (testing "every one carries source \"builtin\" (§4A)"
    (is (= #{"builtin"} (set (map :source all)))))
  (testing "the toolkit indexes all ten, sorted"
    (is (= ["apply_patch" "bash" "edit" "glob" "grep" "question" "read"
            "todowrite" "webfetch" "write"]
           (tool/tool-names tk)))))

(deftest schemas-match-the-4a-table
  (let [schema (fn [n] (:input-schema (get (:tools tk) n)))
        req    (fn [n] (:required (schema n)))
        props  (fn [n] (set (map name (keys (:properties (schema n))))))]
    (is (= ["command"] (req "bash")))
    (is (= #{"command" "workdir" "timeout" "description"} (props "bash")))
    (is (= 60000 (get-in (schema "bash") [:properties :timeout :default])))
    (is (= ["path"] (req "read")))
    (is (= #{"path" "offset" "limit"} (props "read")))
    (is (= ["path" "content"] (req "write")))
    (is (= ["path" "oldString" "newString"] (req "edit")))
    (is (= #{"path" "oldString" "newString" "replaceAll"} (props "edit")))
    (is (= ["pattern"] (req "grep")))
    (is (= 100 (get-in (schema "grep") [:properties :limit :default])))
    (is (= ["pattern"] (req "glob")))
    (is (= 100 (get-in (schema "glob") [:properties :limit :default])))
    (is (= ["url"] (req "webfetch")))
    (is (= ["text" "markdown" "html"] (get-in (schema "webfetch") [:properties :format :enum])))
    (is (= "markdown" (get-in (schema "webfetch") [:properties :format :default])))
    (is (= 30 (get-in (schema "webfetch") [:properties :timeout :default])))
    (is (= ["questions"] (req "question")))
    (is (= ["question"] (get-in (schema "question") [:properties :questions :items :required])))
    (is (= ["patchText"] (req "apply_patch")))
    (is (= ["todos"] (req "todowrite")))
    (is (= ["id" "text" "completed"]
           (get-in (schema "todowrite") [:properties :todos :items :required])))
    (testing "every schema is a JSON-Schema object"
      (is (= #{"object"} (set (map (fn [t] (:type (:input-schema t))) all)))))))

;; ---------------------------------------------------------------------------
;; bash — including the timeout koine 0.6.0 made implementable
;; ---------------------------------------------------------------------------

(deftest bash-basics
  (testing "stdout"
    (let [r (run "bash" {:command "printf 'hi from bash\\n'"})]
      (is (false? (:isError r)))
      (is (= "hi from bash\n" (:output r)))))
  (testing "workdir is honoured"
    (let [r (run "bash" {:command "basename \"$(pwd)\"" :workdir @root})]
      (is (false? (:isError r)))
      (is (= (last (str/split (str @root) #"/")) (str/trim (:output r))))))
  (testing "non-zero exit ⇒ isError, combined stdout+stderr, exit code appended"
    (let [r (run "bash" {:command "echo oops 1>&2; exit 7"})]
      (is (true? (:isError r)))
      (is (= "oops\nexit code 7" (:output r)))))
  ;; §4A: "Output = combined stdout+stderr". Every other bash case in this file
  ;; leaves ONE of the two streams empty, so the ORDER of the join was unpinned:
  ;; `(str (:err r) (:out r))` produced identical output for all of them. Order
  ;; is a cross-port byte question — `bash` output is a ToolResult a model reads
  ;; — so it is asserted here with both streams non-empty, in both exit states.
  ;;
  ;; CROSS-PORT NOTE, recorded not silently matched: js appends stdout and
  ;; stderr to one buffer as the DATA EVENTS ARRIVE (js/src/builtin.ts:166-167),
  ;; so js interleaves by timing and cannot promise an order at all. §4A's own
  ;; wording ("stdout+stderr") is the only deterministic reading, and it is the
  ;; one this port implements. `printf` without a newline keeps the two writes
  ;; adjacent so nothing but the order can explain the result.
  (testing "both streams non-empty ⇒ stdout FIRST, then stderr (§4A)"
    (let [r (run "bash" {:command "printf out; printf err 1>&2"})]
      (is (false? (:isError r)))
      (is (= "outerr" (:output r))))
    (let [r (run "bash" {:command "printf out; printf err 1>&2; exit 3"})]
      (is (true? (:isError r)))
      (is (= "outerrexit code 3" (:output r))))))

(deftest bash-timeout-kills-the-child
  (testing "§4A — 'Timeout kills the child ⇒ isError:true'. s22 could not do
            this; koine 0.6.0's sh :timeout-ms can."
    (let [r (run "bash" {:command "sleep 5; echo never" :timeout 300})]
      (is (true? (:isError r)))
      (is (str/includes? (:output r) "command timed out after 300ms"))
      (is (not (str/includes? (:output r) "never")) "the child did not finish")
      (is (not (str/includes? (:output r) "exit code"))
          "a kill has NO exit code — the JVM would say 137 and Go -1; neither is reported")))
  (testing "a command that finishes inside its timeout is a normal success"
    (let [r (run "bash" {:command "printf ok" :timeout 10000})]
      (is (false? (:isError r)))
      (is (= "ok" (:output r))))))

;; ---------------------------------------------------------------------------
;; filesystem tools
;; ---------------------------------------------------------------------------

(deftest write-and-read
  (let [f (p "work" "notes.txt")]
    (testing "write creates parent dirs (koine.fs/mkdirs!, no shell-out)"
      (let [r (run "write" {:path f :content "alpha\nbeta\ngamma\n"})]
        (is (false? (:isError r)))
        (is (= (str "Wrote 17 bytes to " f) (:output r)))
        (is (true? (fs/exists? f)))))
    (testing "read whole file"
      (is (= "alpha\nbeta\ngamma\n" (:output (run "read" {:path f})))))
    (testing "read a line window (1-based offset)"
      (is (= "beta" (:output (run "read" {:path f :offset 2 :limit 1}))))
      (is (= "beta\ngamma" (:output (run "read" {:path f :offset 2})))))
    (testing "a missing file is an error RESULT, not a throw"
      (let [r (run "read" {:path (p "work" "nope.txt")})]
        (is (true? (:isError r)))
        (is (str/includes? (:output r) "file not found"))))
    ;; `Wrote N bytes` is a §4A output string, and js computes N with
    ;; `Buffer.byteLength(content,"utf8")` (js/src/builtin.ts:236) — UTF-8
    ;; BYTES. The old fold over code UNITS agreed with that only for ASCII,
    ;; which is all "alpha\nbeta\ngamma\n" above is, so a `(count s)` byte
    ;; count was indistinguishable from a correct one. These four cover every
    ;; branch of the UTF-8 length table, including the 4-byte one no code-unit
    ;; walk can reach. Non-ASCII is written DIRECTLY, never as a \u escape: an
    ;; escaped lone surrogate is not portable source.
    (testing "the byte count is UTF-8 BYTES, not characters (§4A, = Buffer.byteLength)"
      (doseq [[label content bytes] [["ascii"  "hello"  5]   ; 1-byte
                                     ["latin1" "héllo"  6]   ; +1 two-byte  (U+00E9)
                                     ["cjk"    "日本"    6]   ; two three-byte (U+65E5 U+672C)
                                     ["astral" "a😀b"   6]]] ; +1 FOUR-byte  (U+1F600)
        (let [g (p "bytes" (str label ".txt"))
              r (run "write" {:path g :content content})]
          (is (= (str "Wrote " bytes " bytes to " g) (:output r))
              (str "byte count for " (pr-str content)))
          (is (= content (:output (run "read" {:path g})))))))
    (testing "a deep write also creates its parents"
      (run "write" {:path (p "work" "sub" "deep.txt")
                    :content "TODO: alpha\nplain line\nTODO: omega\n"})
      (is (true? (fs/exists? (p "work" "sub" "deep.txt")))))))

(deftest edit-tool
  (let [f (p "edit" "e.txt")]
    (run "write" {:path f :content "alpha\nbeta\ngamma\n"})
    (testing "oldString absent ⇒ error"
      (is (true? (:isError (run "edit" {:path f :oldString "zeta" :newString "Z"})))))
    (testing "non-unique without replaceAll ⇒ error naming the count"
      (let [r (run "edit" {:path f :oldString "a" :newString "A"})]
        (is (true? (:isError r)))
        (is (str/includes? (:output r) "(5 occurrences)"))))
    (testing "unique ⇒ one replacement"
      (let [r (run "edit" {:path f :oldString "beta" :newString "BETA"})]
        (is (false? (:isError r)))
        (is (str/includes? (:output r) "Replaced 1 occurrence(s)"))
        (is (= "alpha\nBETA\ngamma\n" (:output (run "read" {:path f}))))))
    (testing "replaceAll ⇒ all of them"
      (let [r (run "edit" {:path f :oldString "a" :newString "A" :replaceAll true})]
        (is (false? (:isError r)))
        (is (str/includes? (:output r) "Replaced 4 occurrence(s)"))
        (is (= "AlphA\nBETA\ngAmmA\n" (:output (run "read" {:path f}))))))
    (testing "missing file ⇒ error"
      (is (true? (:isError (run "edit" {:path (p "edit" "nope.txt")
                                        :oldString "x" :newString "y"})))))))

;; glob/grep own their tree: `deftest` ORDER is a JVM guarantee this port cannot
;; assume on cljgo, so no test may depend on a file another test wrote.

(deftest glob-tool
  (let [d (p "globs")]
    (run "write" {:path (str d "/notes.txt")   :content "x\n"})
    (run "write" {:path (str d "/sub/deep.txt") :content "y\n"})
    (run "write" {:path (str d "/sub/other.md") :content "z\n"})
    (testing "** crosses separators"
      (is (= "notes.txt\nsub/deep.txt"
             (:output (run "glob" {:pattern "**/*.txt" :path d})))))
    (testing "a single * does NOT cross a separator"
      (is (= "notes.txt" (:output (run "glob" {:pattern "*.txt" :path d})))))
    (testing "no match ⇒ empty output, not an error"
      (let [r (run "glob" {:pattern "**/*.rs" :path d})]
        (is (false? (:isError r)))
        (is (= "" (:output r)))))
    (testing "limit caps the output"
      (is (= "notes.txt" (:output (run "glob" {:pattern "**/*.txt" :path d :limit 1})))))
    ;; §4A glob output is a listing a model reads, so its ORDER must not depend
    ;; on which host produced it — and above the BMP a bare `sort` is precisely
    ;; where the two disagree: the JVM compares UTF-16 code units (a surrogate
    ;; D83D below E000), cljgo compares UTF-8 bytes (F0 above EE). Every ASCII
    ;; fixture above orders identically either way, which is why the bare sort
    ;; survived. U+E000 is an escape (a normal BMP char); U+1F600 is written
    ;; DIRECTLY, because an escaped surrogate is not portable source.
    (testing "order is by CODE POINT, identically on both hosts"
      (let [nb (p "globs-nonbmp")]
        (doseq [n ["a.txt" "\uE000.txt" "😀.txt" "z.txt"]]
          (run "write" {:path (str nb "/" n) :content "x\n"}))
        (is (= "a.txt\nz.txt\n\uE000.txt\n😀.txt"
               (:output (run "glob" {:pattern "*.txt" :path nb}))))))))

(deftest grep-tool
  (let [d (p "greps")]
    (run "write" {:path (str d "/a.txt") :content "TODO: alpha\nplain line\nTODO: omega\n"})
    (run "write" {:path (str d "/b.md")  :content "TODO: ignored\n"})
    (testing "file:line:text, filtered by an include glob"
      (let [r (run "grep" {:pattern "TODO:\\s*\\w+" :path d :include "**/*.txt"})]
        (is (false? (:isError r)))
        ;; The FULL line, not `ends-with?`. Asserting only the tail left the
        ;; `file:` half of "file:line:text" unproven — emitting an empty
        ;; filename passed just as well. js pushes the walked path verbatim
        ;; (`matches.push(\`${file}:${i+1}:${lines[i]}\`)`, js/src/builtin.ts:329)
        ;; where `glob` pushes `path.relative(root,file)` (:360), so grep being
        ;; ABSOLUTE while glob is RELATIVE is faithful parity, not a slip.
        ;; §4A pins neither; reported upward rather than quietly normalised.
        (let [lines (str/split-lines (:output r))]
          (is (= 2 (count lines)))
          (is (= (str (p "greps") "/a.txt:1:TODO: alpha") (nth lines 0)))
          (is (= (str (p "greps") "/a.txt:3:TODO: omega") (nth lines 1))))))
    (testing "an include that matches nothing ⇒ empty"
      (is (= "" (:output (run "grep" {:pattern "TODO" :path d
                                      :include "**/nomatch/*.txt"})))))
    (testing "limit caps the hits"
      (is (= 1 (count (str/split-lines (:output (run "grep" {:pattern "TODO" :path d
                                                             :limit 1})))))))
    (testing "no hits ⇒ empty"
      (is (= "" (:output (run "grep" {:pattern "nothingmatchesthis" :path d})))))))

;; ---------------------------------------------------------------------------
;; webfetch
;; ---------------------------------------------------------------------------

(deftest webfetch-tool
  (testing "markdown (the default format)"
    (is (= "# Title\nHello **world**" (:output (run "webfetch" {:url (str @base "/page")})))))
  (testing "text"
    (is (= "TitleHello world" (:output (run "webfetch" {:url (str @base "/page")
                                                        :format "text"})))))
  (testing "html is the raw body"
    (is (str/starts-with? (:output (run "webfetch" {:url (str @base "/page")
                                                    :format "html"}))
                          "<html>")))
  (testing "non-2xx ⇒ isError with HTTP <status>"
    (let [r (run "webfetch" {:url (str @base "/missing")})]
      (is (true? (:isError r)))
      (is (= "HTTP 404" (:output r)))))
  (testing "a transport failure is koine DATA, named, never a caught class"
    (let [r (run "webfetch" {:url "http://127.0.0.1:1/x" :timeout 2})]
      (is (true? (:isError r)))
      (is (str/starts-with? (:output r) "webfetch: transport failure: "))
      (is (contains? #{:timeout :dns :connect-failed :transport}
                     (get-in r [:metadata :error]))))))

;; ---------------------------------------------------------------------------
;; question (§10 suspension)
;; ---------------------------------------------------------------------------

(def ^:private qs
  [{:question "Pick a colour" :header "Colour" :options ["red" "green"]}
   {:question "Free text?"}])

(deftest question-rendering-is-byte-pinned
  (testing "§4A — options appended, header NOT rendered, \\n-joined, no trailing newline"
    (is (= "Pick a colour (options: red, green)\nFree text?"
           (builtin/render-questions qs))))
  (testing "empty options render nothing"
    (is (= "Just this" (builtin/render-questions [{:question "Just this" :options []}])))))

(deftest question-suspends-then-resumes
  (testing "first call suspends with a §10 Request in metadata.pending"
    (let [r (run "question" {:questions qs})
          pending (get-in r [:metadata :pending])]
      ;; §10's ToolResult block: isError TRUE — the call "did not produce a
      ;; usable answer". §10's later "a suspension is never a tool error" is
      ;; about the `tool` OBSERVABILITY EVENT (isError:false + pending:true, so
      ;; circuit-breakers do not count it), not about this result.
      (is (true? (:isError r)))
      (is (= "Waiting for a response." (:output r)))
      (is (= "question" (:kind pending)))
      (is (= "Pick a colour (options: red, green)\nFree text?" (:prompt pending)))
      (is (= qs (get-in pending [:data :questions])))
      (is (some? (:id pending)))))
  (testing "re-executed with ctx.answer ⇒ ok(JSON of answer.data)"
    (let [r (run "question" {:questions qs}
                 {:answer {:id "req-1" :ok true :data {:answers ["green" "hello"]}}})]
      (is (false? (:isError r)))
      (is (= {:answers ["green" "hello"]} (json/read-str (:output r) {:key-fn keyword})))))
  (testing "a declined answer suspends again rather than inventing one"
    (let [r (run "question" {:questions qs} {:answer {:id "req-1" :ok false}})]
      (is (some? (get-in r [:metadata :pending]))))))

;; ---------------------------------------------------------------------------
;; apply_patch
;; ---------------------------------------------------------------------------

(deftest apply-patch-tool
  (let [f    (p "patch" "added.txt")
        addp (str "*** Begin Patch\n*** Add File: " f "\n+one\n+two\n*** End Patch")
        updp (str "*** Begin Patch\n*** Update File: " f "\n one\n-two\n+TWO\n*** End Patch")
        badp (str "*** Begin Patch\n*** Update File: " f "\n-nosuchline\n+x\n*** End Patch")
        delp (str "*** Begin Patch\n*** Delete File: " f "\n*** End Patch")]
    (testing "a malformed patch is an error result"
      (is (true? (:isError (run "apply_patch" {:patchText "no markers here"})))))
    (testing "Add File"
      (is (false? (:isError (run "apply_patch" {:patchText addp}))))
      (is (= "one\ntwo\n" (:output (run "read" {:path f})))))
    (testing "Update File"
      (is (false? (:isError (run "apply_patch" {:patchText updp}))))
      (is (= "one\nTWO\n" (:output (run "read" {:path f})))))
    (testing "a hunk that does not match ⇒ isError AND no partial write"
      (let [r (run "apply_patch" {:patchText badp})]
        (is (true? (:isError r)))
        (is (str/includes? (:output r) "hunk does not match"))
        (is (= "one\nTWO\n" (:output (run "read" {:path f})))
            "atomicity: every op is planned from READS before anything is written")))
    (testing "Delete File — koine.fs/delete!, no `rm -f`"
      (is (false? (:isError (run "apply_patch" {:patchText delp}))))
      (is (false? (fs/exists? f)))
      (is (true? (:isError (run "read" {:path f})))))
    (testing "deleting again ⇒ error"
      (is (true? (:isError (run "apply_patch" {:patchText delp})))))))

;; ---------------------------------------------------------------------------
;; todowrite + the unknown-tool boundary
;; ---------------------------------------------------------------------------

(deftest todowrite-tool
  (is (= "- [x] spike builtins\n- [ ] write README"
         (:output (run "todowrite" {:todos [{:id "1" :text "spike builtins" :completed true}
                                            {:id "2" :text "write README" :completed false}]}))))
  (is (= "" (:output (run "todowrite" {:todos []})))))

(deftest unknown-tool-is-an-error-result
  (let [r (run "nosuchtool" {})]
    (is (true? (:isError r)))
    (is (= "unknown tool: nosuchtool" (:output r)))))

;; ---------------------------------------------------------------------------
;; §0.11 — the toggle matrix (all sixteen cases s22 proved)
;; ---------------------------------------------------------------------------

(deftest toggle-whole-source
  (testing "on by default"
    (is (true? (builtin/source-on? nil)))
    (is (= 10 (count (builtin/enabled-builtins nil))))
    (is (= 10 (count (builtin/enabled-builtins true)))))
  (testing "builtins:false | {disabled:true} | {enabled:false} ⇒ whole source off"
    (is (= [] (builtin/enabled-builtin-names false)))
    (is (= [] (builtin/enabled-builtin-names {:disabled true})))
    (is (= [] (builtin/enabled-builtin-names {:enabled false}))))
  (testing "MCP precedence: disabled:true wins, else enabled:false disables, else on"
    (is (= [] (builtin/enabled-builtin-names {:disabled true :enabled true})))
    (is (= [] (builtin/enabled-builtin-names {:disabled false :enabled false})))
    (is (= 10 (count (builtin/enabled-builtin-names {:disabled false}))))
    (is (= 10 (count (builtin/enabled-builtin-names {:enabled true}))))))

(deftest toggle-per-tool
  (testing "a name mapped to false is dropped"
    (is (= 9 (count (builtin/enabled-builtin-names {:tools {:bash false}}))))
    (is (not (contains? (set (builtin/enabled-builtin-names {:tools {:bash false}})) "bash")))
    (is (= 8 (count (builtin/enabled-builtin-names {:tools {:bash false :write false}})))))
  (testing "KNOWN SPEC DEFECT — §4A says all-on baseline ({bash:true} ⇒ all ten);
            §3-S2 and §2-Gap-7 say '≥1 true ⇒ allowlist' (⇒ bash only) while
            claiming to be identical to §4A. §4A is implemented. REPORTED."
    (is (= 10 (count (builtin/enabled-builtin-names {:tools {:bash true}})))))
  (testing "unknown names are ignored"
    (is (= 10 (count (builtin/enabled-builtin-names {:tools {:nosuch false}})))))
  (testing "a whole-source-off SHORT-CIRCUITS the map"
    (is (= [] (builtin/enabled-builtin-names {:disabled true :tools {:bash true}})))
    (is (= [] (builtin/enabled-builtin-names {:enabled false :tools {:bash true}})))))

(deftest toggle-from-a-parsed-json-config
  (testing "the toggle works on a parsed config object, not only a hand-built map"
    (let [cfg (json/read-str "{\"builtins\":{\"tools\":{\"write\":false}}}" {:key-fn keyword})
          ns' (builtin/enabled-builtin-names (:builtins cfg))]
      (is (= 9 (count ns')))
      (is (not (contains? (set ns') "write"))))))

(deftest toggled-toolkit
  (testing "builtin-toolkit reflects the toggle and reports source status"
    (is (= 10 (count (tool/tool-names (builtin/builtin-toolkit)))))
    (is (= 0 (count (tool/tool-names (builtin/builtin-toolkit false)))))
    (is (= {"builtin" "connected"} (:sources (builtin/builtin-toolkit))))
    (is (= {"builtin" "disabled"} (:sources (builtin/builtin-toolkit false))))))

(deftest builtins-never-reach-the-system-prompt
  (testing "§0.11 — builtins are surfaced through the tool-schema array ONLY.
            The structural half: nothing in this namespace produces prompt text."
    (is (= [] (vec (filter (fn [t] (contains? t :prompt)) all))))
    (is (= [] (vec (filter (fn [t] (contains? t :system-prompt)) all))))
    (testing "and every one is fully describable as a schema entry"
      (is (= 10 (count (filter (fn [t] (and (string? (:name t))
                                            (string? (:description t))
                                            (map? (:input-schema t))))
                               all)))))))
