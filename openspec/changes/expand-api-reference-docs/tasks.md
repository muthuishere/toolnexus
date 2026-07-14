## 1. Fixes already applied (down-payment)

- [x] 1.1 Install page: correct the stale Java `<version>` `0.6.0` → `0.8.0`.
- [x] 1.2 `api/go.mdx`: rewrite as the **depth exemplar** — every symbol described (params, returns, behavior, semantics). Use it as the template for the other four ports.

## 2. Combined `/api/` page — full coverage + descriptions

- [ ] 2.1 Add prose description before each aspect (what it does + key behavior), keeping the five-language synced tabs for signatures.
- [ ] 2.2 Add the missing aspects: Built-in tools, Native tools (`defineTool`), HTTP tools, A2A outbound (`agent`), A2A inbound (`serve` + Agent Card + `TaskStore`), Suspension §10 (`pending`/`Request`/`Answer`/`waitFor`/`RunResult.status`), Memory (`ConversationStore`), Streaming & hooks, Observability (`onMetric`).

## 3. Per-port pages — bring js/python/java/csharp to the go.mdx depth

- [ ] 3.1 `api/javascript.mdx` — detailed descriptions for every aspect, JS signatures.
- [ ] 3.2 `api/python.mdx` — same depth, Python signatures.
- [ ] 3.3 `api/java.mdx` — same depth, Java signatures.
- [ ] 3.4 `api/csharp.mdx` — same depth, C# signatures.
- [ ] 3.5 Every symbol names its five-language equivalents; verify parity (no port missing a shipped symbol).

## 4. Accuracy source-of-truth

- [ ] 4.1 Ground every signature in the actual code (`js/src/*` exports as the reference), not from memory: `ClientOptions`/`Hooks`/`RunResult`, `serve`/`buildAgentCard`/`agent`/`A2AConfig`/`Agent`/`TaskStore`, `pending`/`Request`/`Answer`, `defineTool`/`DefineToolOptions`, `httpTool`/`HttpToolOptions`, `ConversationStore`, `onMetric`, `createBuiltinTools`/`BuiltinsConfig`.
- [ ] 4.2 Cross-check each documented symbol's per-language name against that port's public surface.

## 5. Verify

- [ ] 5.1 `astro build` succeeds; all `/api/*` pages render.
- [ ] 5.2 Spot-check the live pages contain the new aspects (a2a, suspension, builtins, memory, observability) and per-symbol descriptions.
- [ ] 5.3 `openspec validate expand-api-reference-docs`.
