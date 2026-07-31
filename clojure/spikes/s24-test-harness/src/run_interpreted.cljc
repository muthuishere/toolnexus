;; `cljgo run <file>` evaluates a file's top-level forms; unlike the AOT `exe`
;; entrypoint it does NOT call -main for you. It would exit 0 having printed
;; nothing — which for a TEST harness is the exact lie this spike is about.
;; This one-line wrapper is the interpreted entrypoint.
(require 'toolnexus.harness)
(toolnexus.harness/-main)
