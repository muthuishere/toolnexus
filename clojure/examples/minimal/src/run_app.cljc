;; `cljgo run <file>` does not call -main; this is the interpreted app entry.
;; It requires app.core directly rather than app.main, because main.cljg is the
;; cljgo-native entry and main.clj is the JVM one — the shared code is core.
(require 'app.core)
(println (app.core/report "/tmp/koine-minimal-cljgo.txt"))
