;; `cljgo run <file>` evaluates a file's top-level forms; unlike the AOT `exe`
;; entrypoint it does NOT call -main for you. It would exit 0 having printed
;; nothing — which for a TEST harness is the exact lie this spike is about.
;; This one-line wrapper is the interpreted entrypoint.
;;
;; The (ns …) form is NOT decoration. Without it this file is a bare script that
;; declares no namespace, and `cljgo test --compiled` — which walks the source
;; tree and loads what it finds — refuses it, correctly. On cljgo v0.8.5 that
;; refusal was indistinguishable from #182 (a namespace symbol keeping its
;; `.cljc` extension) because both surfaced as "could not locate namespace". On
;; v0.8.6 the two are finally separable, and this file turned out to be the
;; second one: my problem, not cljgo's.
(ns run-interpreted
  (:require [toolnexus.harness]))

(toolnexus.harness/-main)
