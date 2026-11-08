# Tasks

## 1. java
- [x] 1.1 `StdioMcpClient`: direct writes, one reader, pending by id, raw-bytes hand-off
- [x] 1.2 server requests off the reader thread (the deadlock fix from rust/src/mcp/stdio.rs)
- [x] 1.3 `McpSource`: stdio uses the in-house client, remote uses the SDK, behind one `McpSession` seam; drop `ProcessStdioTransport`
- [x] 1.4 `StdioMcpClientTest` + fixture `stdio_behaviour_server.py`
- [x] 1.5 interleaved before/after benchmark; LangChain4j head-to-head
- [x] 1.6 `notifications/cancelled {requestId, reason}` on idle timeout / cancel; late reply dropped (tested)
- [x] 1.7 tried and dropped (no gain on the micro-benchmark): Jackson writing straight into a
      BufferedOutputStream (~20.8 µs vs ~20.2 µs per call); a spin-then-park pending slot instead of
      CompletableFuture (~22 µs vs ~20.2 µs)
- [x] 1.8 interop: `CLIENTS=java interop/mcp2026/matrix.sh` 44/44 against golang/java/python/js; option parity OK

## 2. Other ports
- [x] 2.1 rust: already in-house (speed-up-request-path)
- [ ] 2.2 js / python / golang / csharp / elixir / clojure: not in scope (performance only, no behaviour change)

## 3. Verify
- [x] 3.1 `./gradlew test` green
- [x] 3.2 CHANGELOG entry
