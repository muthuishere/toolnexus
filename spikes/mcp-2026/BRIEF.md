# Spike: in-house MCP 2026-07-28 client + server, all 7 ports

## Question the spike answers
Can toolnexus own its MCP layer (no SDK) at the 2026-07-28 spec in every port, at a size we can
maintain, with the skills extension (SEP-2640), and interoperate across ports?
Spike answer per port: LOC, what worked, what the spec left ambiguous, verdict (own it / use SDK).

## Ground rules
- Read the spec yourself, do not work from memory:
  https://modelcontextprotocol.io/specification/2026-07-28 (+ /changelog, /basic, /server, /client)
  and https://modelcontextprotocol.io/seps/2640-skills-extension.md
- Code lives ONLY in `spikes/mcp-2026/<lang>/`. Do not touch the port directories or SPEC.md.
- **No MCP SDK.** Language stdlib + a minimal HTTP/JSON lib is fine.
- Hermetic: no live LLM, no network beyond localhost (spec reading excepted).
- Idiomatic for the language; spike quality, but it must run.

## Scope (minimum, both sides)
Transport: Streamable HTTP, stateless, POST-only (`POST /mcp`). stdio is optional stretch.

Server must:
1. `server/discover` → serverInfo, capabilities, supported versions, `extensions` incl.
   skills ext id. Wrong `_meta` protocolVersion → `UnsupportedProtocolVersionError` (-32022).
2. `tools/list` (deterministic order, `ttlMs`/`cacheScope`, `resultType:"complete"`).
3. `tools/call` for two tools from `spikes/mcp-2026/fixture.json`:
   - `echo` → returns its text (plus `structuredContent`).
   - `greet` → first call returns `resultType:"input_required"` with one form elicitation
     inputRequest asking for `name`; retry carrying `inputResponses` returns `Hello, <name>!`.
4. Skills extension: `skills/list` and `skills/get`, serving `examples/skills/hello-world`.
5. Validate `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` headers against the body (mismatch → -32020).

Client must:
1. Call `server/discover`, then send `_meta` (protocolVersion, clientCapabilities, clientInfo)
   and required headers on every request.
2. List tools, call `echo`, run the `greet` MRTR round trip (answer `name`="toolnexus" via a
   callback standing in for toolnexus §10 waitFor).
3. `skills/list` + `skills/get` hello-world.

## Deliverables in `spikes/mcp-2026/<lang>/`
- `server` and `client` runnable via one command each (document in README.md).
- A self-test: own client vs own server prints `PASS` lines for each step above.
- **Cross-port check:** `run.sh client <url>` must be usable against any other port's server
  (servers listen on the port in `PORT`, default per lang below).
- `README.md`: commands, LOC (client / server, excluding tests), spec ambiguities hit, verdict.

Default ports: js 7101, python 7102, golang 7103, java 7104, csharp 7105, elixir 7106, clojure 7107.
