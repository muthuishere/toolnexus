(ns mcp2026.stdio-server
  "TEST ONLY: a tiny stdio MCP server process. MCP2026_STDIO_SERVER=1 serves; =exit exits 3."
  (:require [mcp2026.core :as mcp]))

(defn -main [& _]
  (case (System/getenv "MCP2026_STDIO_SERVER")
    "exit" (System/exit 3)
    (do (binding [*out* *err*] (println "stdio-test-server ready {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")) ; log noise
        (mcp/serve-stdio (mcp/fixture-server) *in* *out*)
        (System/exit 0))))
