;; S7 (JVM) — a LONG-LIVED child with piped stdin/stdout, exchanging
;; line-delimited JSON-RPC. This is exactly what MCP's stdio transport needs,
;; and what `cljg.io/exec` (run-to-completion) cannot express.
(import '[java.lang ProcessBuilder]
        '[java.io BufferedReader InputStreamReader OutputStreamWriter])

(let [p   (.start (ProcessBuilder. ["cat"]))
      out (OutputStreamWriter. (.getOutputStream p) "UTF-8")
      in  (BufferedReader. (InputStreamReader. (.getInputStream p) "UTF-8"))]
  (doseq [msg ["{\"id\":1,\"method\":\"initialize\"}"
               "{\"id\":2,\"method\":\"tools/list\"}"
               "{\"id\":3,\"method\":\"tools/call\"}"]]
    (.write out (str msg "\n"))
    (.flush out)
    (println "recv:" (.readLine in)))
  (.close out)
  (println "exit:" (.waitFor p))
  (println "PASS: 3 round-trips over ONE persistent child"))
