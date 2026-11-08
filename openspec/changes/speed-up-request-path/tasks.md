# Tasks

## 1. Ports
- [x] 1.1 python: keep-alive LLM transport + stale retry; loop pipe I/O; cached proxy; tests (test_keepalive_transport.py)
- [x] 1.2 js: nodeFetch default on Node only; lazy Ajv
- [x] 1.3 java: lazy clients, inline executor, spawn-first, prewarm, inline tool call + watchdog; idle-timeout tests
- [x] 1.4 java: drop jackson-databind 2.x; byte-parity golden test
- [x] 1.5 csharp: SDK warm-up, cached proxy, direct JSON writer
- [ ] 1.6 rust: replace rmcp on the stdio client path; match or beat golang and AutoAgents per request
- [x] 1.7 golang: measured at floor, no change
- [ ] 1.8 elixir, clojure: out of scope for this change

## 2. Verify
- [ ] 2.1 all suites + conformance + option parity green
- [ ] 2.2 quiet interleaved re-run before the standing page changes
- [ ] 2.3 CHANGELOG entry
