;; S24 — the counting test gate.
;;
;; QUESTION: can ONE .cljc clojure.test suite run identically on Clojure (JVM)
;; and on cljgo — and can we TRUST the result?
;;
;; The second half is why this spike exists. On cljgo, EXIT 0 MEANS NOTHING
;; THREW, not that anything happened:
;;   * `cljgo run <file>` does not call -main; it exits 0 having printed nothing.
;;   * `cljgo test` has been reported to collect zero .cljc test files and still
;;     print "Ran 0 tests containing 0 assertions" and exit 0.
;; A CI job that shells out to a test runner and checks $? is therefore green
;; on a suite that never ran. That is the failure this namespace exists to
;; make impossible.
;;
;; The gate: run the suite IN-PROCESS via clojure.test/run-tests, read the
;; returned summary map, and REFUSE to call it green unless the observed test
;; and assertion counts are non-zero AND meet a declared floor. The floor is
;; checked in, so deleting tests is as loud as breaking them.
;;
;; One .cljc, zero reader conditionals, zero java.*.
(ns toolnexus.harness
  (:require [clojure.test :as test]
            [clojure.string :as strings]
            [koine.json :as json]
            [koine.host :as host]
            [koine.process :as proc]
            [toolnexus.logic :as logic]
            [toolnexus.logic-test]
            [toolnexus.empty-target-test]))

;; ---------------------------------------------------------------------------
;; Declared floor. The suite is checked in; so is what it must produce.
;; ---------------------------------------------------------------------------
(def suite-ns 'toolnexus.logic-test)
(def empty-ns 'toolnexus.empty-target-test)
(def min-tests 8)
(def min-assertions 22)

;; ---------------------------------------------------------------------------
;; Running a suite and reading the counts.
;;
;; Portability notes, both measured (see README):
;;   * `run-tests` must be handed a namespace SYMBOL. cljgo rejects a namespace
;;     OBJECT — `(run-tests *ns*)` errors with
;;     "-collect-test-vars expects namespace symbols" ... and still exits 0.
;;   * clojure.test/*report-counters* is a ref, and cljgo's ref is not a JVM
;;     ref (`swap!` on it errors). Never touch it — read the summary map that
;;     run-tests returns instead. That map is identical on both hosts.
;;   * run-tests prints its report to *out*; -main must emit exactly one JSON
;;     line, so the report is captured with with-out-str (works on both hosts)
;;     and only its size is reported.
;; ---------------------------------------------------------------------------
(defn run-suite
  "Run one namespace's clojure.test suite in-process. Returns the observed
  counts, never throws — a thrown suite is data, not a crash."
  [ns-sym]
  (try
    (let [summary (atom nil)
          ;; clojure.test writes through *test-out*, not *out*, so with-out-str
          ;; ALONE captures nothing on the JVM (it silently captures on cljgo —
          ;; a real host divergence, see README). Bind both.
          report  (with-out-str
                    (binding [test/*test-out* *out*]
                      (reset! summary (test/run-tests ns-sym))))
          {:keys [test pass fail error]} @summary]
      {:tests       (or test 0)
       :assertions  (+ (or pass 0) (or fail 0) (or error 0))
       :pass        (or pass 0)
       :fail        (or fail 0)
       :error       (or error 0)
       :report-bytes (count report)
       :threw       false})
    (catch Throwable t
      {:tests 0 :assertions 0 :pass 0 :fail 0 :error 0 :report-bytes 0
       :threw true :message (str (ex-message t))})))

;; ---------------------------------------------------------------------------
;; THE GATE. This is the deliverable.
;; ---------------------------------------------------------------------------
(defn gate
  "Verdict over an observed run. :green only when the suite demonstrably RAN:
  non-zero tests, non-zero assertions, at or above the declared floor, nothing
  thrown, and no failures or errors. Every rejection carries a reason, so a
  silently-empty suite reports :no-tests-collected rather than looking green."
  [{:keys [tests assertions fail error threw] :as observed}]
  (let [reasons (cond-> []
                  threw                  (conj :suite-threw)
                  (zero? tests)          (conj :no-tests-collected)
                  (zero? assertions)     (conj :no-assertions-run)
                  (< tests min-tests)    (conj :below-test-floor)
                  (< assertions min-assertions) (conj :below-assertion-floor)
                  (pos? (or fail 0))     (conj :failures)
                  (pos? (or error 0))    (conj :errors))]
    {:verdict (if (empty? reasons) :green :red)
     :reasons (vec (sort (map name reasons)))
     :floor   {:tests min-tests :assertions min-assertions}
     :observed (select-keys observed [:tests :assertions :pass :fail :error])}))

(defn gate-ignoring-failures
  "Same gate, but tolerant of :failures — used to score the armed-canary run,
  where a failure is the expected result, not a defect."
  [observed]
  (let [g (gate observed)
        r (vec (remove #(= "failures" %) (:reasons g)))]
    (assoc g :reasons r :verdict (if (empty? r) :green :red))))

;; ---------------------------------------------------------------------------
;; Modes measured in one -main run
;; ---------------------------------------------------------------------------
(defn measure-passing []
  (reset! logic/force-fail? false)
  (run-suite suite-ns))

(defn measure-armed []
  (reset! logic/force-fail? true)
  (let [r (run-suite suite-ns)]
    (reset! logic/force-fail? false)
    r))

(defn measure-empty []
  (run-suite empty-ns))

(defn measured-cljgo-version
  "The cljgo version this measurement was taken against. Shelled out (not read
  from the host) so the JVM run reports the same string as the cljgo runs and
  the three reports stay byte-comparable. Absent cljgo => \"absent\".

  NOT named `cljgo-version`: cljgo's clojure.core HAS a fn by that name (JVM
  Clojure does not), so a defn of it shadows clojure.core on one host only.
  See README, FINDING 3."
  []
  (try
    (let [{:keys [out exit]} (proc/sh ["cljgo" "version"])]
      (if (= 0 (or exit 0))
        (strings/trim (str out))
        "absent"))
    (catch Throwable _ "absent")))

(defn report []
  (let [passing (measure-passing)
        armed   (measure-armed)
        empty-r (measure-empty)
        g-pass  (gate passing)
        g-armed (gate-ignoring-failures armed)
        g-empty (gate empty-r)]
    {:spike   "s24-test-harness"
     :host    (name host/id)
     :cljgo-version (measured-cljgo-version)
     :suite   {:ns (str suite-ns) :floor {:tests min-tests :assertions min-assertions}}
     :modes
     {:passing {:tests (:tests passing) :assertions (:assertions passing)
                :fail (:fail passing) :error (:error passing)
                :failure-detected (pos? (:fail passing))
                :verdict (name (:verdict g-pass)) :reasons (:reasons g-pass)}
      :armed   {:tests (:tests armed) :assertions (:assertions armed)
                :fail (:fail armed) :error (:error armed)
                :failure-detected (pos? (:fail armed))
                :verdict (name (:verdict g-armed)) :reasons (:reasons g-armed)}
      :empty   {:tests (:tests empty-r) :assertions (:assertions empty-r)
                :fail (:fail empty-r) :error (:error empty-r)
                :failure-detected (pos? (:fail empty-r))
                :verdict (name (:verdict g-empty)) :reasons (:reasons g-empty)}}
     :fixtures-ran (vec (map name @toolnexus.logic-test/fixture-log))
     ;; The three claims this spike has to make good on, as booleans.
     :gate
     {:suite-runs            (and (pos? (:tests passing)) (zero? (:fail passing)))
      :reports-failures      (= 1 (:fail armed))
      :catches-empty         (and (= :red (:verdict g-empty))
                                  (some #{"no-tests-collected"} (:reasons g-empty))
                                  true)
      :verdict               (if (and (= :green (:verdict g-pass))
                                      (= 1 (:fail armed))
                                      (= :red (:verdict g-empty)))
                               "green" "red")}}))

(defn -main [& _]
  (let [r (report)]
    (println (json/write-str r))
    ;; Exit code is a courtesy on the JVM and meaningless on cljgo; the callers
    ;; assert on the JSON. Nothing here calls System/exit — that is java.*.
    r))
