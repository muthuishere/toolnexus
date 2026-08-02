;; toolnexus.agents.home — the suite. SPEC §7E (agent home / personas).
;;
;; Expected values come from SPEC.md §7E and `js/src/agents/home.ts` — the
;; bootstrap order, the `## <filename>` section shape, the byte cap, and the
;; memory tool's three actions. Not from this port's own output.
;;
;; Every test writes into a real temp directory and reads the files back off
;; disk, because "all actions write to disk" is the actual §7E claim: asserting
;; on the tool's return string alone would pass just as happily if nothing were
;; ever persisted.
;;
;; No java.*, no reader conditionals.
(ns toolnexus.agents.home-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [koine.fs :as fs]
            [toolnexus.agents.home :as home]
            [toolnexus.native :as native]))

(defn- with-home
  "Run `f` against a fresh temp dir seeded with `files` (a name->content map)."
  [files f]
  (let [dir (fs/temp-dir! "tn-home")]
    (try
      (doseq [[name content] files]
        (fs/write-file (str dir "/" name) content))
      (f (str dir))
      (finally (fs/delete-tree! dir)))))

(defn- call [t args] (native/execute-native t args))

;; ---------------------------------------------------------------------------
;; compose-soul
;; ---------------------------------------------------------------------------

(deftest bootstrap-order-is-pinned
  (testing "the seven files, in the §7E order — identity first, memory last"
    (is (= ["AGENTS.md" "SOUL.md" "IDENTITY.md" "USER.md"
            "TOOLS.md" "HEARTBEAT.md" "MEMORY.md"]
           home/bootstrap-order))))

(deftest composes-present-files-in-order
  (with-home {"MEMORY.md"   "remembered things"
              "SOUL.md"     "I am Ava"
              "HEARTBEAT.md" "check the queue"}
    (fn [dir]
      (let [{:keys [soul found]} (home/compose-soul dir)]
        (testing "only the present files, in bootstrap order — not disk order"
          (is (= ["SOUL.md" "HEARTBEAT.md" "MEMORY.md"] found)))
        (testing "each file is a `## <filename>` section"
          (is (str/starts-with? soul "## SOUL.md\n\nI am Ava"))
          (is (str/includes? soul "## HEARTBEAT.md\n\ncheck the queue"))
          (is (str/includes? soul "## MEMORY.md\n\nremembered things")))
        (testing "sections are separated by a blank line"
          (is (str/includes? soul "I am Ava\n\n## HEARTBEAT.md")))
        (testing "SOUL.md precedes MEMORY.md in the composed text"
          (is (< (str/index-of soul "## SOUL.md")
                 (str/index-of soul "## MEMORY.md"))))))))

(deftest absent-files-are-skipped-not-empty-sections
  (with-home {"SOUL.md" "just me"}
    (fn [dir]
      (let [{:keys [soul found]} (home/compose-soul dir)]
        (is (= ["SOUL.md"] found))
        (is (= "## SOUL.md\n\njust me" soul)
            "an absent file leaves NO trace — not a heading with an empty body")))))

(deftest an-empty-directory-composes-an-empty-soul
  (with-home {}
    (fn [dir]
      (let [{:keys [soul found]} (home/compose-soul dir)]
        (is (= [] found))
        (is (= "" soul) "no files ⇒ empty string, never nil")))))

(deftest bodies-are-trimmed
  (with-home {"SOUL.md" "\n\n  I am Ava  \n\n\n"}
    (fn [dir]
      (is (= "## SOUL.md\n\nI am Ava" (:soul (home/compose-soul dir)))
          "leading/trailing whitespace is trimmed so the section shape is stable"))))

(deftest the-cap-is-two-mebibytes-of-bytes
  (testing "measured in bytes, not characters (§7E)"
    (is (= 2097152 home/max-file-bytes))))

(deftest an-oversized-file-is-truncated-and-disk-is-untouched
  ;; A 2 MiB+ file, built once. The point is not the exact truncation index — the
  ;; spec allows a split multibyte character — but that the soul shrinks, carries
  ;; the notice, and the FILE does not change.
  (let [big (apply str (repeat 300000 "0123456789"))]   ; 3,000,000 bytes ASCII
    (with-home {"SOUL.md" big}
      (fn [dir]
        (let [{:keys [soul]} (home/compose-soul dir)]
          (is (str/ends-with? soul "[truncated: exceeds 2 MB bootstrap cap]")
              "the truncation is announced, never silent")
          (is (< (count soul) (count big))
              "the injected body is smaller than the file")
          (is (= (count big) (count (fs/read-file (str dir "/SOUL.md"))))
              "the file ON DISK is untouched — the cap is an injection cap"))))))

;; ---------------------------------------------------------------------------
;; the memory tool
;; ---------------------------------------------------------------------------

(deftest memory-tool-shape
  (with-home {}
    (fn [dir]
      (let [t (home/memory-tool dir)]
        (is (= "memory" (:name t)))
        (is (str/includes? (:description t) "NEXT session")
            "the description tells the MODEL that writes do not change its current context")
        (is (= ["action" "text"] (:required (:input-schema t))))))))

(deftest add-appends-and-persists
  (with-home {}
    (fn [dir]
      (let [t (home/memory-tool dir)
            r (call t {:action "add" :text "muthu prefers Clojure"})]
        (is (false? (:isError r)))
        (is (str/includes? (:output r) "MEMORY.md"))
        (is (= "- muthu prefers Clojure\n" (fs/read-file (str dir "/MEMORY.md")))
            "written to DISK as a list entry, not merely reported")
        (call t {:action "add" :text "second"})
        (is (= "- muthu prefers Clojure\n- second\n" (fs/read-file (str dir "/MEMORY.md")))
            "a second add APPENDS — it does not overwrite")))))

(deftest target-user-writes-user-md
  (with-home {}
    (fn [dir]
      (let [t (home/memory-tool dir)]
        (call t {:action "add" :target "user" :text "works in IST"})
        (is (= "- works in IST\n" (fs/read-file (str dir "/USER.md"))))
        (is (not (fs/exists? (str dir "/MEMORY.md")))
            "target=user must not also touch the agent's own notes")))))

(deftest self-is-the-default-target
  (with-home {}
    (fn [dir]
      (call (home/memory-tool dir) {:action "add" :text "x"})
      (is (fs/exists? (str dir "/MEMORY.md")))
      (is (not (fs/exists? (str dir "/USER.md")))))))

(deftest replace-swaps-an-existing-substring
  (with-home {"MEMORY.md" "- likes tea\n"}
    (fn [dir]
      (let [r (call (home/memory-tool dir) {:action "replace" :text "tea" :with "coffee"})]
        (is (false? (:isError r)))
        (is (= "- likes coffee\n" (fs/read-file (str dir "/MEMORY.md"))))))))

(deftest remove-deletes-an-existing-substring
  (with-home {"MEMORY.md" "- likes tea\n- likes coffee\n"}
    (fn [dir]
      (let [r (call (home/memory-tool dir) {:action "remove" :text "- likes tea\n"})]
        (is (false? (:isError r)))
        (is (= "- likes coffee\n" (fs/read-file (str dir "/MEMORY.md"))))))))

(deftest a-missing-substring-is-a-loud-error
  (with-home {"MEMORY.md" "- likes tea\n"}
    (fn [dir]
      (let [t (home/memory-tool dir)]
        (doseq [action ["replace" "remove"]]
          (let [r (call t {:action action :text "absent" :with "x"})]
            (is (true? (:isError r)) (str action " on a missing substring must be loud"))
            (is (str/includes? (:output r) "not found"))))
        (is (= "- likes tea\n" (fs/read-file (str dir "/MEMORY.md")))
            "a failed edit leaves the file EXACTLY as it was")))))

(deftest an-unknown-action-is-an-error
  (with-home {}
    (fn [dir]
      (let [r (call (home/memory-tool dir) {:action "delete-everything" :text "x"})]
        (is (true? (:isError r)))
        (is (str/includes? (:output r) "unknown action"))))))

(deftest the-memory-tool-does-not-mutate-the-composed-soul
  (testing "the frozen-snapshot rule: an edit loads NEXT session, not this one"
    (with-home {"MEMORY.md" "- old\n"}
      (fn [dir]
        (let [before (:soul (home/compose-soul dir))]
          (call (home/memory-tool dir) {:action "add" :text "new"})
          (is (str/includes? before "- old"))
          (is (not (str/includes? before "- new"))
              "the soul captured earlier is a SNAPSHOT — it cannot have changed")
          (is (str/includes? (:soul (home/compose-soul dir)) "- new")
              "…and the next composition does see it"))))))

(deftest heartbeat-constants-are-pinned
  (testing "the sentinel and the prompt are part of the cross-port contract"
    (is (= "HEARTBEAT_OK" home/heartbeat-ok))
    (is (str/includes? home/heartbeat-prompt "HEARTBEAT_OK")
        "the prompt must teach the model the silent-reply contract")
    (is (str/includes? home/heartbeat-prompt "Heartbeat")
        "…and carry the trigger word a HEARTBEAT.md can key off")
    (is (str/includes? home/heartbeat-prompt "HEARTBEAT.md"))))
