;; Example 02 — connect to a local MCP server over stdio (spawn a process, talk on stdin/stdout).
;;
;; Run: ./run.sh example 02-connect-stdio
;;
;; stdio carries one JSON-RPC message per line. stderr is the server's log stream: it is passed
;; through (or captured via :stderr) and never parsed. There are no HTTP headers and no OAuth
;; over stdio: a stdio server takes its credentials from its own environment.
(ns example.connect-stdio
  (:require [mcp2026.core :as mcp]))

(def self *file*)

(defn main []
  ;; Demo harness: this same script is the stdio server when started with SERVE_STDIO=1.
  ;; In real use the command is your server, e.g. ["npx" "-y" "some-mcp-server"].
  (if (= "1" (System/getenv "SERVE_STDIO"))
    (mcp/serve-stdio (mcp/fixture-server) *in* *out*)

    ;; 1. Describe the process to spawn (command vector + env, like ProcessBuilder).
    (let [cmd ["java" "-cp" (System/getProperty "java.class.path") "clojure.main" self]

          ;; 2. Start it and get a client. Same API as the HTTP client: call, tools, call-tool.
          c (mcp/stdio-client cmd {:env {"SERVE_STDIO" "1"}})]
      (try
        ;; 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
        (println "tool count:" (count (mcp/tools c)))

        (let [res (mcp/call-tool c "echo" {"text" "hello over stdio"})]
          (println "echo:" (mcp/text res)))
        (finally (mcp/close c)))))) ; stops the server process

(main)
(shutdown-agents)
(System/exit 0)
