;; `cljgo run <file>` does not call -main; this is the interpreted entrypoint.
(require 'app.test-main)
(app.test-main/-main)
