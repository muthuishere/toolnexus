;; `cljgo run <file>` does not call -main; this is the interpreted entrypoint.
(require 'toolnexus.test-main)
(toolnexus.test-main/-main)
