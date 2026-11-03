;; Example 01 — connect to an MCP server over Streamable HTTP and call a tool.
;;
;; Run: ./run.sh example 01-connect-http
;;
;; The MCP 2026-07-28 protocol is stateless: there is no initialize handshake and no session.
;; Every request carries its own protocol version, so "connecting" is just (mcp/client url).
(ns example.connect-http
  (:require [clojure.string :as str]
            [mcp2026.core :as mcp]))

(defn main []
  ;; A local MCP server to talk to (the spike fixture: tools `echo` and `greet`, skill `hello-world`).
  ;; In real use this is someone else's server, e.g. "https://api.example.com/mcp".
  (let [srv (mcp/serve! (mcp/fixture-server))]

    ;; 1. Point a client at the server's MCP endpoint. No handshake happens here.
    (let [c (mcp/client (str (:url srv) "/mcp"))]

      ;; 2. Optional: server/discover tells you which versions and capabilities the server has.
      (let [info (mcp/call c "server/discover")]
        (println "supported versions:" (get info "supportedVersions")))

      ;; 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
      (println "tools:" (str/join ", " (map #(get % "name") (mcp/tools c))))

      ;; 4. Call a tool. mcp/text returns the first text content of the result.
      (let [res (mcp/call-tool c "echo" {"text" "hello over HTTP"})]
        (println "echo:" (mcp/text res))))

    ((:stop srv))))

(main)
(shutdown-agents)
(System/exit 0)
