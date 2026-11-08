# Tasks

## 1. java
- [x] 1.1 `StdioMcpClient`: direct writes, one reader, pending by id, raw-bytes hand-off
- [x] 1.2 server requests off the reader thread (the deadlock fix from rust/src/mcp/stdio.rs)
- [x] 1.3 `McpSource`: stdio uses the in-house client, remote uses the SDK, behind one `McpSession` seam; drop `ProcessStdioTransport`
- [x] 1.4 `StdioMcpClientTest` + fixture `stdio_behaviour_server.py`
- [x] 1.5 interleaved before/after benchmark; LangChain4j head-to-head

## 2. Other ports
- [x] 2.1 rust: already in-house (speed-up-request-path)
- [ ] 2.2 js / python / golang / csharp / elixir / clojure: not in scope (performance only, no behaviour change)

## 3. Verify
- [x] 3.1 `./gradlew test` green
- [x] 3.2 CHANGELOG entry
