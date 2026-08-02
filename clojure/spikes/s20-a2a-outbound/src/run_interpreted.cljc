;; `cljgo run <file>` does not call -main (it evaluates top-level forms and
;; exits 0). This is the interpreted entrypoint. See s15-spec0-slice/README.md.
(require 'toolnexus.a2aout)
(toolnexus.a2aout/-main)
