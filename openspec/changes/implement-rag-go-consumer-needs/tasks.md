Per the ADR clusters. Each cluster: JS reference first (settle wire bytes), then port to
python/golang/java/csharp, each verified green. Gap 5 is a behavior change — pin SPEC first.

Ergonomic addition (owner request): toolkit-level `DisableTools []string` (drop by final exposed
name across every source) + `DisableSkills []string` (drop skills by name) — simple "just turn these
off" lists on top of the map filters. Shipped in JS+Go with tests; port to py/java/csharp.

## 1. Contract & fixtures

- [x] 1.1 `SPEC.md §8`: request shaping (RequestParams/BodyTransform ordering), empty-tools omission, store accessor.
- [x] 1.2 `SPEC.md §2`: ctx-aware load + SSE bound, per-server `tools` allowlist, list-only inventory, DisableTools/DisableSkills.
- [ ] 1.3 Shared `examples/` MCP fixture with a `tools`-filtered server (gap 7). (covered by hermetic per-port server tests for now)

## 2. Cluster A — client request shaping (gaps 1, 2, 5) · §8

- [x] 2.1 JS: `requestParams` + `bodyTransform` + injectable `fetch` on ClientOptions; omit empty tools; ordering contract; all 4 paths. Tests (httptest wire assertions) + green.
- [x] 2.2 Python: `request_params` + `body_transform` + injectable `httpx` client; omit empty tools. Tests + green.
- [x] 2.3 Go: `RequestParams` + `BodyTransform` + `HTTPClient` on ClientOptions; omit empty tools. Seed openai-path tests from rag_go fixtures. Tests -race + green.
- [x] 2.4 Java: `requestParams` + `bodyTransform` + injectable `HttpClient`; omit empty tools. Tests + green.
- [x] 2.5 C#: `RequestParams` + `BodyTransform` + injectable `HttpClient`/handler; omit empty tools. Tests + green.

## 3. Cluster B — MCP load lifecycle (gaps 3, 6, 7) · §2

- [x] 3.1 JS: ctx/AbortSignal passthrough on load; `ServerConfig.tools` allowlist; `listMcpTools`. Tests + green.
- [x] 3.2 Python: cancellation plumbing; `tools` allowlist; `list_mcp_tools`. Tests + green.
- [x] 3.3 Go: `LoadMcpWithContext` + ctx through connect/init/list + bounded SSE `Start`; `ServerConfig.Tools`; `ListMcpTools`/`McpInventory`/`ToolInfo`. Hanging-server cancel test. Tests -race + green.
- [x] 3.4 Java: cancellation plumbing; `tools` allowlist; `listMcpTools`. Tests + green.
- [x] 3.5 C#: `CancellationToken` plumbing; `tools` allowlist; `ListMcpTools`. Tests + green.

## 4. Cluster C — conversation-store accessor (gap 4) · §8

- [x] 4.1 JS `client.conversationStore` getter (green); [x] 4.3 Go `(*Client).ConversationStore()` (green); [x] 4.2 Python; [x] 4.4 Java; [x] 4.5 C#. Identity + default + shared-reuse tests.

## 5. Verify & release

- [x] 5.1 All five suites green; conformance parity (empty-tools omission + allowlist fixture identical across ports).
- [x] 5.2 `openspec validate implement-rag-go-consumer-needs`.
- [x] 5.3 Bump all four manifests to 0.9.0; PR; CI green; merge; cut release v0.9.0; archive.
- [ ] 5.4 Ping rag_go to strip the four workarounds (reverse-proxy → A, watchdog+tool-filter+validate → B, shadow store → C).
