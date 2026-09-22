;; The host boundary (ADR 0034, issues #100/#101/#102): which interpreter runs,
;; what a relative path means, and what a timeout kills.
;;
;; Every assertion carries its control. The spike that produced these fixes twice
;; reported a clean kill from a broken probe, so "no orphan" is evidence only next
;; to a run proving the command can write the marker at all
;; (spikes/builtin-host-boundary/SPIKE.md §1).
;;
;; Dual-host: no java.*, no Thread/sleep, no reader conditionals.
(ns toolnexus.host-boundary-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [koine.fs :as fs]
            [koine.process :as proc]
            [koine.time :as ktime]
            [toolnexus.builtin :as builtin]
            [toolnexus.tool :as tool]))

(def ^:private root (atom nil))

(use-fixtures :once
  (fn [f]
    (let [d (fs/temp-dir! "tn-boundary")]
      (reset! root d)
      (try (f) (finally (fs/delete-tree! d))))))

(defn- fresh-dir [name]
  (let [d (str @root "/" name "-" (rand-int 1000000))]
    (fs/mkdirs! d)
    d))

(defn- tool-named [cfg n]
  (first (filter (fn [t] (= n (:name t))) (builtin/builtin-tools-for cfg))))

(defn- run [cfg n args]
  (tool/execute (tool/toolkit (builtin/builtin-tools-for cfg)) n args nil))

;; The GRANDCHILD writes the marker, and `sleep 0.2` in front stops the shell
;; exec-optimising the single command away — the difference between measuring an
;; orphan and measuring nothing.
(defn- orphan-command [marker]
  (str "sleep 0.2; sh -c 'sleep 1; touch " marker "'"))

;; ---------------------------------------------------------------------------
;; #102 — a timeout kills the job, not the shell
;; ---------------------------------------------------------------------------

(deftest timeout-kills-the-whole-job
  (let [marker (str (fresh-dir "orphan") "/orphan.marker")
        r      (run nil "bash" {:command (orphan-command marker) :timeout 300})]
    (is (:isError r))
    (is (str/includes? (:output r) "timed out"))
    (is (true? (get-in r [:metadata :timedOut])))
    (is (true? (get-in r [:metadata :killedTree])))
    (ktime/sleep! 2000)
    (is (not (fs/exists? marker)) "the grandchild outlived the kill")))

(deftest control-the-probe-command-can-write-the-marker
  (let [marker (str (fresh-dir "control") "/control.marker")
        r      (run nil "bash" {:command (orphan-command marker) :timeout 20000})]
    (is (not (:isError r)) (:output r))
    (is (fs/exists? marker)
        "the command cannot write the marker at all — the orphan test proves nothing")))

;; ---------------------------------------------------------------------------
;; #100 — the interpreter is chosen, and reported
;; ---------------------------------------------------------------------------

(deftest the-resolved-interpreter-is-reported
  (let [r (run nil "bash" {:command "echo hi"})]
    (is (string? (get-in r [:metadata :shell])))
    (is (str/includes? (get-in r [:metadata :shell]) "sh"))))

(deftest a-host-supplied-shell-is-used-verbatim
  (let [r (run {:shell ["/bin/sh" "-c"]} "bash" {:command "echo verbatim"})]
    (is (not (:isError r)) (:output r))
    (is (str/includes? (:output r) "verbatim"))
    (is (= "/bin/sh -c" (get-in r [:metadata :shell])))))

(deftest shell-reports-detection-and-bash-can-be-disabled
  (is (= :ok (first (builtin/shell nil))))
  (is (not (some (fn [t] (= "bash" (:name t)))
                 (builtin/enabled-builtins {:tools {"bash" false}})))))

;; ---------------------------------------------------------------------------
;; #101 — one base directory, and optional confinement
;; ---------------------------------------------------------------------------

(deftest base-dir-scopes-relative-paths
  (let [base (fresh-dir "base")
        cfg  {:base-dir base}
        r    (run cfg "write" {:path "sub/nested.txt" :content "landed"})]
    (is (not (:isError r)) (:output r))
    (is (fs/exists? (str base "/sub/nested.txt")))
    (is (= "landed" (:output (run cfg "read" {:path "sub/nested.txt"}))))))

(deftest an-absolute-path-with-no-base-dir-behaves-as-before
  (let [target (str (fresh-dir "abs") "/absolute.txt")
        r      (run nil "write" {:path target :content "x"})]
    (is (not (:isError r)) (:output r))
    (is (fs/exists? target))))

(deftest apply-patch-resolves-paths-inside-the-patch-text
  (let [base  (fresh-dir "patch")
        patch "*** Begin Patch\n*** Add File: pkg/new.txt\n+hello\n*** End Patch"
        r     (run {:base-dir base} "apply_patch" {:patchText patch})]
    (is (not (:isError r)) (:output r))
    (is (fs/exists? (str base "/pkg/new.txt"))
        "the path inside the patch text was not resolved")))

(deftest bash-defaults-its-workdir-to-base-dir
  (let [base (fresh-dir "workdir")]
    (fs/write-file (str base "/marker.txt") "x")
    (let [r (run {:base-dir base} "bash" {:command "ls marker.txt"})]
      (is (not (:isError r)) (:output r))
      (is (str/includes? (:output r) "marker.txt")))))

(deftest confinement-refuses-escapes-and-still-serves-what-is-inside
  (let [holder  (fresh-dir "confine")
        base    (str holder "/base")
        outside (str holder "/outside")]
    (fs/mkdirs! base)
    (fs/mkdirs! outside)
    (fs/write-file (str outside "/secret.txt") "secret")
    (let [cfg {:base-dir base :confine-to-base-dir true}]
      (doseq [p ["../outside/secret.txt" (str outside "/secret.txt")]]
        (let [r (run cfg "read" {:path p})]
          (is (:isError r) (str p " should be refused"))
          (is (str/includes? (:output r) "outside baseDir"))))
      (fs/write-file (str base "/ok.txt") "fine")
      (is (not (:isError (run cfg "read" {:path "ok.txt"})))
          "a path inside base-dir must still be read"))))

(deftest confinement-follows-symlinks-before-deciding
  (let [holder  (fresh-dir "symlink")
        base    (str holder "/base")
        outside (str holder "/outside")]
    (fs/mkdirs! base)
    (fs/mkdirs! outside)
    (fs/write-file (str outside "/secret.txt") "secret")
    ;; `ln -s` rather than a host call: neither koine nor a .cljc can create a
    ;; symlink, and this test is about what confinement does with one.
    (proc/sh ["ln" "-s" outside (str base "/link")] {:timeout-ms 5000})
    (when (fs/exists? (str base "/link"))
      (is (:isError (run {:base-dir base :confine-to-base-dir true}
                         "read" {:path "link/secret.txt"}))
          "a symlink out of base-dir must be refused"))))

(deftest confinement-covers-paths-that-do-not-exist-yet
  (let [cfg {:base-dir (fresh-dir "notyet") :confine-to-base-dir true}]
    (is (:isError (run cfg "write" {:path "../escape.txt" :content "x"})))
    (is (not (:isError (run cfg "write" {:path "deep/new/file.txt" :content "x"}))))))

;; ---------------------------------------------------------------------------
;; §4A — grep emits the string it sorted by
;; ---------------------------------------------------------------------------

(deftest grep-emits-walk-root-relative-forward-slashed-paths
  (let [base (fresh-dir "grep")]
    (fs/mkdirs! (str base "/tree/sub"))
    (fs/write-file (str base "/tree/sub/a.txt") "needle\n")
    (let [r (run {:base-dir base} "grep" {:pattern "needle" :path "tree"})]
      (is (not (:isError r)) (:output r))
      (is (= "sub/a.txt:1:needle" (:output r)))
      (is (not (str/includes? (:output r) "\\"))))))
