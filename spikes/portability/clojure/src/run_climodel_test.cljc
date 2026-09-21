;; cljgo entry point (mirrors the real port's src/run_tests.cljc — cljgo
;; resolves `require` relative to the ENTRY FILE's own root, so this must
;; live under src/, not test/). No `System/exit` — cljgo ADR 0054 forbids
;; Java interop entirely; an uncaught throw is what turns a failure into a
;; non-zero exit on both hosts (mirrors toolnexus.test-main/-main).
(require 'clojure.test)
(require 'toolnexus.climodel-test)

(let [r (clojure.test/run-tests 'toolnexus.climodel-test)]
  (println r)
  (when-not (and (zero? (:fail r)) (zero? (:error r)) (pos? (:test r)))
    (throw (ex-info "FAILED: zero tests collected, or a failure/error present" r))))
