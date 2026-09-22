;; cljgo entrypoint. `cljgo run <file>` does not call -main, and *command-line-args*
;; holds the args AFTER the file name (measured). Kept separate from probe.cljc so
;; the JVM's `clojure -M -m probe` does not run -main twice — it did, and the first
;; version of this probe silently executed every kill arm two times.
(require 'probe)
(apply probe/-main *command-line-args*)
