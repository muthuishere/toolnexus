# MCP handler conformance (SPEC §7C.1, ADR 0039)

`cases.json` pins the wire of `toolkit.mcpHandler(cfg)` byte-for-byte (as parsed JSON) across all eight
ports. Each port's suite loads it and replays it; the Go reference is
`golang/mcphandler_conformance_test.go`.

## Harness contract

**Fixture tools.** Build one `Tool` per `fixture.tools[]` entry with `name`, `description`,
`inputSchema`, and when present `annotations` (verbatim), `hints` (`readOnly`/`destructive`/`openWorld`)
and `outputSchema`. Execution, by `behavior`:

| behavior | result |
|---|---|
| `echo` | `output` = `args.text` |
| `whoami` | `output` = `<identity name>` + `"|"` + `<mcpRequestMetaFrom(ctx).trace or "">` |
| `ask` | when the context carries an `Answer` with `ok`: `output` = `"Hello, " + answer.data.name + "!"`; else a §10 suspension `Request{id:"name", kind:"input", prompt:"What is your name?", data:{schema:{type:"object", properties:{name:{type:"string"}}, required:["name"]}}}` |

The **identity name** is the identity when it is a string, the `subject` when it is a `Principal`
(§7C.2), else `""`. `listTools`' `visibleTo` filter compares the identity name.
| `static` | the `result` object as a `ToolResult` (`output`, `isError`, `structured`, `meta`) |
| `error` | `execute` fails with the message `error` (the port's native failure: Go error, thrown exception, `{:error, msg}`, `Err`) |

Every execution is recorded by tool name (for `expectExecuted`).

**Fixture config.** From the scenario's `config`:

- `auth: true` ⇒ `authenticate` maps the `Authorization` header through `fixture.auth.tokens` (identity =
  the mapped string); anything else fails. `wwwAuthenticate` returns `fixture.auth.wwwAuthenticate`.
- `listTools` ⇒ every fixture tool, minus those whose `visibleTo` does not contain the identity.
- `beforeCall` ⇒ the tool's `decision` (`kind`, `message`, `result` as a `ToolResult`), else allow.
- `onCall` ⇒ record `{name, isError, decision}` (for `expectOnCall`).
- `name`, `title`, `version`, `instructions`, `stateless`, `statelessSessionId`, `protocolVersions` ⇒ the
  same-named handler option; absent ⇒ the option is not set.
- `skills: true` ⇒ `listSkills` = `loadMcpSkills(<repo>/examples/skills)` (§7C.2).
- `serverAuth: true` ⇒ an `MCPServerAuth` from `fixture.serverAuth` (`issuer`, `audience`, `scopes`) whose
  `verify(ctx, token, audience)` returns `Principal{subject, scopes}` from `fixture.serverAuth.principals[token]`
  (unknown token ⇒ failure); the handler's `authenticate` is that auth's `authenticate`, and steps with a
  `target` are sent to that auth's `metadata()` handler.

**Requests.** Each step sends one HTTP request to the handler:

- `method`; the body is `rawBody` verbatim, else `body` JSON-encoded, else empty.
- `Content-Type`: `contentType`, default `application/json` (POST only).
- `Accept`: `accept`, default `application/json, text/event-stream`.
- `Authorization`: the step's `token`, else the scenario's `token`, else none.
- `Mcp-Session-Id`: `sessionId` verbatim, or the captured id when `session: true`.
- `MCP-Protocol-Version`: `version` when present.
- `capture: "sid"` stores the response `Mcp-Session-Id` for later `session: true` steps.
- `mcp2026: true` (§7C.2 sugar): set `params._meta["io.modelcontextprotocol/protocolVersion"] = "2026-07-28"`
  in the body unless that key is already present (adding `params` / `_meta` when absent), and send
  `MCP-Protocol-Version: 2026-07-28`, `Mcp-Method: <body.method>` and, for `tools/call` / `prompts/get`
  (`params.name`) and `resources/read` (`params.uri`), `Mcp-Name: <value>` verbatim.
- `headers`: applied last, verbatim; an empty value **removes** that header.
- `target`: the step goes to the `serverAuth` metadata handler at this path instead of the MCP handler.

**Assertions** (a key absent from `expect` is not asserted):

- `status` — exact HTTP status.
- `headers` — exact values (case-insensitive names); `headersPresent` non-empty; `headersAbsent` absent or empty.
- `json` — the response body parsed as JSON deep-equals this (object key order ignored).
- `jsonPath` — each dotted path (`result.tools.2`; numeric segments index arrays) deep-equals its value.
- Scenario `expectExecuted` — exact ordered list of executed tool names.
- Scenario `expectOnCall` — exact ordered list of `{name, isError, decision}`.

Elicitation (confirm over the POST's SSE stream), `notifyToolsChanged` on a GET stream, resources and
prompts are covered by each port's own tests, mirroring `golang/mcphandler_test.go`.
