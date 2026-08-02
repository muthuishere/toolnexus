;; The port's test entry point, for BOTH hosts.
;;
;; It runs the suite in-process and gates on the summary map that `run-tests`
;; returns — never on the exit code. On cljgo, exit 0 means nothing threw, not
;; that anything ran: a suite that collects zero tests still exits 0 and looks
;; green forever. The count gate is the only thing standing between us and that.
;;
;; It also lives under src/ rather than test/ for a measured reason: `cljgo run`
;; and `cljgo build` resolve requires relative to the ENTRY FILE's own root, so
;; an entry under src/ cannot require a namespace in test/. (`cljgo test` walks
;; both trees — that is a different mechanism. Measured 2026-07-31.)
(ns toolnexus.test-main
  (:require [clojure.test :as t]
            [koine.json :as json]
            [koine.host :as host]
            [toolnexus.tool-test]
            [toolnexus.frontmatter-test]
            [toolnexus.mcp-test]
            [toolnexus.mcp-http-test]
            [toolnexus.skill-test]
            [toolnexus.adapter-test]
            [toolnexus.native-test]
            [toolnexus.http-test]
            [toolnexus.builtin-test]
            [toolnexus.client-test]
            [toolnexus.translate-test]
            [toolnexus.a2a-test]
            [toolnexus.serve-test]
            [toolnexus.core-test]
            [toolnexus.agents.compaction-test]
            [toolnexus.agents.home-test]
            [toolnexus.agents.runtime-test]
            [toolnexus.agents.runtime-fixture-test]))

(def suites
  '[toolnexus.tool-test
    toolnexus.frontmatter-test
    toolnexus.mcp-test
    toolnexus.mcp-http-test
    toolnexus.skill-test
    toolnexus.adapter-test
    toolnexus.native-test
    toolnexus.http-test
    toolnexus.builtin-test
    toolnexus.client-test
    toolnexus.translate-test
    toolnexus.a2a-test
    toolnexus.serve-test
    toolnexus.core-test
    toolnexus.agents.compaction-test
    toolnexus.agents.home-test
    toolnexus.agents.runtime-test
    toolnexus.agents.runtime-fixture-test])

;; A floor, not an exact count — it must fail on an EMPTY collection without
;; needing an edit every time a test is added. RAISED from 100 after an audit
;; removed the four largest suites from `suites` and this gate still reported OK:
;; 150 of 291 tests is 48% of the suite gone, and the floor could not see it.
(def minimum-tests 280)

;; The number of suites that MUST be registered. `suites` is a hand-kept
;; duplicate of the `:require` list above, and dropping an entry from it is the
;; exact bug that once hid 27 tests: the namespace still loads, so nothing errors,
;; and the count stays above any floor. Comparing `suites` against a constant is
;; the only check that can see the vector shrink, because every count derived
;; FROM the vector shrinks with it. Adding a suite is meant to be a two-line diff.
(def expected-suite-count 18)

(defn- declared-tests
  "How many deftests a namespace actually holds, read off its interns rather than
  off the run. A suite that is registered but collected NOTHING — a load that
  half-failed, a namespace whose tests were all renamed — is invisible in an
  aggregate that only sums what ran."
  [ns-sym]
  (count (filter (fn [v] (:test (meta v))) (vals (ns-interns ns-sym)))))

(defn run []
  (let [s          (apply t/run-tests suites)
        assertions (+ (:pass s 0) (:fail s 0) (:error s 0))
        empty-ns   (vec (filter (fn [n] (zero? (declared-tests n))) suites))
        declared   (reduce + (map declared-tests suites))]
    {:host       (name host/id)
     :suites     (count suites)
     :tests      (:test s 0)
     :assertions assertions
     :fail       (:fail s 0)
     :error      (:error s 0)
     :gate       (cond
                   (zero? (:test s 0))            "FAILED: zero tests collected"
                   (not= (count suites) expected-suite-count)
                   (str "FAILED: " (count suites) " suites registered, expected "
                        expected-suite-count " — a suite was dropped from `suites`, "
                        "or one was added without updating `expected-suite-count`")
                   (seq empty-ns)
                   (str "FAILED: registered but holding zero tests: " (pr-str empty-ns))
                   (not= declared (:test s 0))
                   (str "FAILED: " declared " tests are declared across the registered "
                        "namespaces but " (:test s 0) " ran")
                   (< (:test s 0) minimum-tests)  (str "FAILED: only " (:test s 0)
                                                       " tests, expected >= " minimum-tests)
                   (pos? (+ (:fail s 0) (:error s 0))) "FAILED: assertions failed"
                   :else "OK")}))

(defn -main [& _]
  (let [r (run)]
    (println (json/write-str r))
    ;; The suite uses `future` for parallel tool calls. On the JVM the agent
    ;; pool's non-daemon threads keep the process alive for their 60s keepalive
    ;; after the last assertion, so a 7s suite takes 67s of wall clock. Present
    ;; on BOTH hosts — checked with (resolve 'clojure.core/shutdown-agents) —
    ;; so it needs no reader conditional and is a no-op where there is no pool.
    (shutdown-agents)
    (when-not (= "OK" (:gate r))
      (throw (ex-info (:gate r) r)))))
