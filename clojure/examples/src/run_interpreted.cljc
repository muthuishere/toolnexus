;; `cljgo run <file>` evaluates top-level forms and does NOT call -main.
;; This file is the two lines that make the interpreted mode run the same
;; program the AOT binary runs.
(require 'toolnexus.demo)
(toolnexus.demo/-main)
