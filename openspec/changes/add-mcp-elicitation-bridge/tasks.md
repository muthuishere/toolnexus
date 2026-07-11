# Tasks — MCP elicitation bridge (design-stage until the seam is reviewed)

## 0. Resolved open questions (2026-07-11)
- [x] 0.1 `schema` location → **`Request.data.schema`** (no `Request`-shape change; consistent with `data.questions`).
- [x] 0.2 `ok=false` default → **`cancel`** (safe/retry-later); `decline` only when `reason=="declined"`; `expired`→`cancel`.
- [x] 0.3 content validation → **bridge does NOT validate in v1** (host owns it; server rejects malformed per spec). Avoids a 5-port validator; delta says "MAY". Documented future enhancement.
- [x] 0.4 URL mode → **form mode (`kind:"input"`) all ports in v1; URL mode fast-follow** (Python SDK 1.28.0 is form-only). Pure mapping still handles url for parity.

## 1. Contract (spec-first, all ports)
- [x] 1.1 `SPEC.md §10`: added optional `Answer.reason` (R1) + `Request.data.schema` (R2), loop rule
      unchanged. `SPEC.md §2`: MCP outbound elicitation-bridge subsection (map form→input/URL→auth;
      capability advertised iff `waitFor` present; inline resolution).
- [x] 1.2 JS: `Answer.reason` added; `data.schema` is a convention (no `Request`-shape change). Ports
      add `reason` to their `Answer` (fan-out).

## 2. The bridge (per port — shared PURE mapping helpers, per-SDK registration)
Pure helpers `elicitationToRequest` / `answerToElicitResult` (byte-parity tested) + register the
handler & advertise the capability iff `waitFor` present; map `create → Request → waitFor → Answer →
ElicitResult` inline.
- [x] 2.1 JS (reference, commit 12b1e24) — `client.setRequestHandler(ElicitRequestSchema, fn)`;
      `loadMcp(input,{waitFor})`; `createToolkit({waitFor})`; mapping unit test. 64 tests green.
- [x] 2.2 Python — `ClientSession(elicitation_callback=…)`; capability gated on wait_for (SDK default-callback check). 85 tests.
- [x] 2.3 Go — swapped convenience constructors for `transport + mcpclient.NewClient(WithElicitationHandler)` at all 3 sites (stdio/HTTP/SSE). green -race.
- [x] 2.4 Java — `ClientCapabilities.builder().elicitation(true,true)` + `.elicitation(h)` + `.urlElicitation(h)` (SDK 2.0.0 has both). green.
- [x] 2.5 C# — `McpClientOptions.Handlers.ElicitationHandler` + `Capabilities.Elicitation{Form,Url}`. 105 tests.

## 3. Tests (per port — the mapping is the parity lock)
- [x] 3.x Unit-tested the pure mapping identically in all five: form→input+`data.schema` (no url),
      url→authorization+url (no schema), accept+data / accept-empty→empty map / declined→decline /
      bare-false→cancel / expired→cancel. (A full stub-server round-trip was descoped — loadMcp uses
      real transports, not injectable ones; the pure mapping is the byte-parity-critical unit. Full
      round-trip integration test = future enhancement.)

## 4. Parity + validate
- [x] 4.1 Request/Answer additions (`reason`, `data.schema`) + mapping behavior identical across ports;
      capability gated on `waitFor` in every port (clean degrade). Suites: JS 64, Python 85, Go ok,
      C# 105, Java green — all verified in-tree.
- [x] 4.2 `openspec validate add-mcp-elicitation-bridge --strict`.

## Known limitations (v1)
- Form mode everywhere; URL mode wired where the SDK supports it (JS/Go/Java/C#), Python is form-only
  (SDK 1.28.x) — URL mode is the one fast-follow.
- Bridge does not validate `answer.data` against `data.schema` (0.3) — host/server responsibility.
- No durable (no-`waitFor`) elicitation — MCP has no cross-process suspend of an open JSON-RPC call.
- Parity locked at the mapping level; a full stub-server-elicits round-trip test is a future add.

## Dependencies / notes
- Lands AFTER `harden-suspension-layer` (needs the `suspension` capability + pending-not-error).
- Not SDK-blocked: all five expose a client elicitation handler; URL mode everywhere except Python 1.28.0.
- opencode does NOT implement this (commented-out TODO, issue #23066) — no upstream port; design from
  the MCP spec + SDK. The interactive half reuses `waitFor` (opencode's own out-of-band idiom).
- Durable (no-`waitFor`) elicitation-over-MCP is out of scope — MCP has no cross-process suspend of an
  open JSON-RPC call; documented as a known limitation.
