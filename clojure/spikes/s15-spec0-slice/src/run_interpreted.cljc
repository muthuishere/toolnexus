;; `cljgo run <file>` evaluates a file's top-level forms; unlike the AOT `exe`
;; entrypoint it does not call -main for you. This one-line wrapper is the
;; interpreted entrypoint, so the same namespace is exercised both ways.
(require 'toolnexus.slice)
(toolnexus.slice/-main)
