;; `cljgo run <file>` evaluates top-level forms and does NOT call -main; this
;; one-line wrapper is the interpreted entrypoint, so the same namespace is
;; exercised both ways (cljgo ADR 0007: a REPL-vs-binary divergence is a bug).
(require 'toolnexus.classifier)
(toolnexus.classifier/-main)
