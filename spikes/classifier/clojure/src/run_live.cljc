;; Optional gate 5 (live), interpreted entrypoint — cljgo side. Runs only when
;; OPENROUTER_API_KEY is set; the value is read by NAME inside the backend.
(require 'toolnexus.classifier)
(prn (toolnexus.classifier/gate-5-live))
