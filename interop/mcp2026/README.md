# MCP 2026-07-28 interop matrix

Every port's MCP **client** against every port's MCP **server** (task 1.4 / 4.2 of
`openspec/changes/upgrade-mcp-2026-07-28`). Test tooling only — nothing here ships.

```sh
interop/mcp2026/matrix.sh                                  # hermetic 8 x 8, loopback only
CLIENTS="golang js" SERVERS="rust" interop/mcp2026/matrix.sh
KEYCLOAK=1 interop/mcp2026/keycloak.sh                     # LIVE: docker Keycloak, never in default CI
```

## Runner contract (per port: `interop/mcp2026/<port>/{build,server,client}`)

- `build` — compile the runners once (cached artifacts under `interop/mcp2026/.build/<port>/` or the port's own build dir).
- `server [--keycloak] <skillsDir>` — print ONE JSON line `{"base":"http://127.0.0.1:<port>","issuer":"<AS issuer>"}`
  on stdout, then serve until **stdin closes**. Mounts:
  - `/open/mcp` — `MCPHandler`, `protocolVersions = [2026-07-28]`, no auth. Tools, in order:
    `echo` (`{text}` → output `text`, structured `{"text": text}`), `greet` (MRTR: pends
    `Request{id:"name", kind:"input", prompt:"What is your name?", data.schema}`; with an ok answer → `Hello, <name>!`),
    `wipe` (`beforeCall` confirm `"Really wipe?"`, then `wiped`), `whoami` (the principal's subject).
    Skills from `<skillsDir>` via the skills extension.
  - `/mcp` — the same, protected by `MCPServerAuth{issuer, scopes {"*": ["mcp"], "whoami": ["mcp:whoami"]}}`
    against the port's **test-only dev authorization server** (pre-registered `interop-client` with redirect
    `http://127.0.0.1/callback`, CIMD on, subject `dev-user`).
  - `/legacy/mcp` — a legacy-only (pre-2026, stateless) handler with the same tools.
  - `/.well-known/oauth-protected-resource[/mcp]` — the auth's PRM.
  - `/log` — `{"seen": ["<mount> <MCP-Protocol-Version header> <Mcp-Method header>", ...]}`, mount ∈ open|auth|legacy.
  - `--keycloak` (Go server only): bind `127.0.0.1:7199`, issuer `http://localhost:8180/realms/toolnexus`,
    audience `http://127.0.0.1:7199/mcp`, scopes `{"*": ["mcp:tools"], "whoami": ["mcp:greet"]}`.
- `client <base> <skillsDir>` — run the scenarios below, print `PASS <id>` / `FAIL <id>: <reason>` per scenario,
  exit 0 iff all pass. Answers: `What is your name?` → `{name:"toolnexus"}`, `Really wipe?` → `{confirm:true}`.
- `client --keycloak <mcpURL> <skillsDir>` — live scenarios `kc_login`, `kc_stepup` with a headless login
  (cookie jar, post Keycloak's `kc-form-login` as `alice` / `alice-test-pw`, accept consent, deliver the
  `127.0.0.1` callback) as the client's `openUrl`, pre-registered client id `toolnexus-mcp`.

## Scenarios (11, ids are fixed)

| id | what |
|---|---|
| `discover` | `{url: base/open/mcp}` connects; tools exactly `srv_echo,srv_greet,srv_whoami,srv_wipe`; `srv_echo{text:"hi"}` → `{"text":"hi"}` (SPEC §2: structuredContent ⇒ JSON); every `open` line in `/log` carries `2026-07-28` |
| `mrtr_input` | `srv_greet` with `waitFor` → `Hello, toolnexus!` |
| `mrtr_confirm` | `srv_wipe` with `waitFor` → `wiped` |
| `mrtr_pending` | `srv_greet` without `waitFor` → pending `kind:"input"`, prompt `What is your name?` |
| `skills` | a toolkit over `mcpServers.srv = base/open/mcp`; `skill{name:"hello-world"}` output contains the local SKILL.md body (frontmatter stripped, trimmed) |
| `tool_search` | `toolSearch:true, description:"interop"` → exactly 2 tools; search `{query:"echo text"}` output starts with `[{"name":"echo"`; call `{name:"greet"}` → `Hello, toolnexus!` |
| `oauth_preregistered` | `auth{clientId:"interop-client", allowHttp:true}`, `openUrl` = HTTP GET of the authorize URL, in-memory token store; `srv_echo{text:"authed"}` → `{"text":"authed"}` |
| `oauth_stepup` | same source: `srv_whoami` → 403 insufficient_scope → re-authorize → `dev-user`; stored token scopes include `mcp:whoami` |
| `oauth_cimd` | client serves its own metadata document on loopback; `auth{clientMetadataUrl, allowHttp:true}`; authorize URL carries `client_id=<doc url>`; `srv_echo{text:"cimd"}` → `{"text":"cimd"}` |
| `oauth_no_identity` | `auth{allowHttp:true}` with no client id or metadata URL → server status `failed` |
| `legacy_fallback` | `{url: base/legacy/mcp}` → discover fails, frozen fallback; `srv_echo{text:"old"}` → `{"text":"old"}` |
