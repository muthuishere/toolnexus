# Tasks — MCP elicitation bridge (design-stage until the seam is reviewed)

## 0. Resolve open questions (design.md) — BEFORE code
- [ ] 0.1 `schema` location: `Request.data.schema` (leaning) vs first-class `Request.schema`.
- [ ] 0.2 `ok=false` default MCP action when `reason` absent: `cancel` (leaning) vs `decline`.
- [ ] 0.3 `content` validation ownership: bridge validates vs trusts host (leaning bridge-validates).
- [ ] 0.4 Python URL-mode scope: form-only now + follow-up, or bump the Python SDK.

## 1. Contract (spec-first, all ports)
- [ ] 1.1 `SPEC.md §10`: add optional `Answer.reason` (R1) + `Request.data.schema` (R2), loop rule
      unchanged (branch on `ok`). `SPEC.md §2`: MCP outbound gains the elicitation bridge + `waitFor`
      wiring; capability advertised iff `waitFor` present.
- [ ] 1.2 Add `reason?` to `Answer` and (if 0.1 picks first-class) `schema?` to `Request`, in all five
      ports. Non-breaking (optional).

## 2. The bridge (per port — shared logic, per-SDK registration)
Register the elicitation handler + advertise the capability iff `waitFor` present; map
`create → Request → waitFor → Answer → ElicitResult` inline; handle URL-mode re-entrancy.
- [ ] 2.1 JS — `client.setRequestHandler(ElicitRequestSchema, fn)`; plumb `waitFor` into `loadMcp` (`js/src/mcp.ts:171`).
- [ ] 2.2 Python — `ClientSession(elicitation_callback=…)` (`mcp_source.py:236`). (Form mode; URL per 0.4.)
- [ ] 2.3 Go — `client.WithElicitationHandler(h)` (`golang/mcp.go:298` + HTTP/SSE builders).
- [ ] 2.4 Java — `.capabilities(...elicitation(true,true)).elicitation(h).urlElicitation(h)`.
- [ ] 2.5 C# — `McpClientOptions.Handlers.ElicitationHandler`.

## 3. Tests (per port, hermetic — stub MCP server that elicits during a tool call)
- [ ] 3.x form-mode elicit → `waitFor` called with `kind:"input"` + schema, `accept` returns answer,
      tool completes; `ok=false` → decline/cancel per 0.2, tool degrades; URL mode → `kind:"authorization"`
      + url, `accept` no content; no-`waitFor` → capability not advertised.

## 4. Parity + validate
- [ ] 4.1 The Request/Answer additions + bridge behavior identical across ports (form mode at least).
- [ ] 4.2 `openspec validate add-mcp-elicitation-bridge --strict`.

## Dependencies / notes
- Lands AFTER `harden-suspension-layer` (needs the `suspension` capability + pending-not-error).
- Not SDK-blocked: all five expose a client elicitation handler; URL mode everywhere except Python 1.28.0.
- opencode does NOT implement this (commented-out TODO, issue #23066) — no upstream port; design from
  the MCP spec + SDK. The interactive half reuses `waitFor` (opencode's own out-of-band idiom).
- Durable (no-`waitFor`) elicitation-over-MCP is out of scope — MCP has no cross-process suspend of an
  open JSON-RPC call; documented as a known limitation.
