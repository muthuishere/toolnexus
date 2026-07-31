;; `cljgo run <file>` evaluates a file's top-level forms; unlike the AOT `exe`
;; entrypoint it does not call -main for you. This is the interpreted entrypoint.
(require 'toolnexus.serve)
(toolnexus.serve/-main)
