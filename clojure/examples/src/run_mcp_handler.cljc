;; `cljgo run <file>` evaluates top-level forms and does NOT call -main.
(require (quote examples.mcp-handler))
(examples.mcp-handler/-main)
