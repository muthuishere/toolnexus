# js — in-house MCP 2026-07-28 spike (TypeScript on Node)

Port of `../golang` (the reference). **No MCP SDK and no dependencies**: `node:http`/`node:https`, `node:crypto`,
`node:child_process`. TypeScript runs directly through Node's type stripping (Node ≥ 22.18; tested on 24.18), so
there is no build step. Erasable syntax only (`erasableSyntaxOnly`), ESM, `.ts` import specifiers.

## Commands

```sh
./run.sh server                            # listens on 127.0.0.1:${PORT:-7101}/mcp
./run.sh client http://127.0.0.1:7103/mcp  # the 8 cross-port checks against ANY port's server
./run.sh selftest                          # own client vs own in-process server
./run.sh test                              # full suite (node --test); hermetic, 127.0.0.1 only
node examples/01-connect-http/main.ts      # 10 runnable user-story examples, see docs/
tsc -p tsconfig.json                       # optional typecheck (needs typescript + @types/node on the path)

# gated live suites
(cd ../golang/keycloak && docker compose up -d)
KEYCLOAK=1 node --test --test-concurrency=1 test/keycloak.test.ts test/examples.test.ts
CHROME_AGENT_ID=toolnexus-ghoauth GITHUB_OAUTH=1 sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- \
  node --test --test-concurrency=1 test/github.test.ts test/examples.test.ts
```

`examples/NN-*/main.ts` are the same 10 user stories as Go, with **byte-identical output** (`test/examples.test.ts`
holds Go's expected strings verbatim; 05's `9721 / 519` bytes and the 100-tool `56,421 / 519` match too).
`docs/` has the same 6 pages; `test/docs.test.ts` fails if a code block stops matching its example.

## API in one screen

```ts
import * as mcp from "./src/index.ts"
const c = new mcp.Client(url, { auth?, http?, headers?, toolSearch?, description? })
await c.call(method, params)                 // any method; _meta + headers added; throws RpcError {code,data}
await c.callTool(name, args, respond?)       // MRTR rounds via respond(key, req) => answer
await c.tools() / c.searchTools(q, n) / c.invoke(name, args, respond?)   // eager or lazy (search_tools/call_tool)
await c.downloadSkill(uri, dir)
const s = await mcp.connectStdio(cmd, { args, env, stderr })            // same Client API over stdio
c.auth = new mcp.OAuth({ authorizer, clientId, clientSecret, clientIds, clientMetadataUrl, store, omitRefreshResource, allowHttp })
//  -> throws PendingError (pause mode); await c.auth.resume(pending.id, callbackUrl) => the ORIGINAL call's result
const srv = mcp.fixtureServer().tool(name, desc, (args, ctx) => ...)   // ctx.principal when protected
srv.auth = new mcp.ServerAuth({ issuer, audience?, scopes: { "*": [...], tool: [...] }, verify? })
await srv.listen(port)  /  http.createServer(srv.listener)
const as = await mcp.DevAuthServer.start()  // DEV/TEST ONLY
```

## LOC (`wc -l`, all lines)

| part | file | LOC |
|---|---|---|
| shared constants, HTTP seam, mutex | protocol.ts | 133 |
| server | server.ts | 328 |
| client (HTTP, MRTR, tool-list cache, downloadSkill) | client.ts | 273 |
| stdio client + test server | stdio.ts | 127 |
| lazy tool search (BM25) | toolsearch.ts | 56 |
| skill URI safety | skills.ts | 22 |
| **client OAuth** | auth.ts | 496 |
| **server auth (protected resource)** | serverauth.ts | 143 |
| **DevAuthServer (dev/test only, incl. self-signed X.509 in DER)** | devauth.ts | 243 |
| CLI + 8 checks + index | cli.ts, checks.ts, index.ts | 120 |
| **non-test total** | src/ | **~1,940** (Go: 2,382) |
| tests | test/*.ts | 955 |
| examples | examples/ | 528 |

## Proof

- `./run.sh test`: 55 pass, 12 skipped (the gated Keycloak/GitHub ones), 0 fail.
- Interop: JS client → Go server (7103) 8/8 PASS; Go client → JS server (7101) 8/8 PASS.
- Keycloak 26.8.1 (`KEYCLOAK=1`): 8/8 live tests + example 09 pass (end to end, the 8 checks through Keycloak,
  refresh with and without `resource`, step-up via SSO, pause/resume, wrong audience → 401, CIMD).
- GitHub hosted MCP: **not verified live** (2026-11-04). The flow reaches the authorize step (POST 401 -> PRM 200 ->
  GitHub AS metadata 200 -> `chrome-agent goto` exits 0) but no callback reaches the loopback listener; the Go
  reference example 10 hangs the same way at the same time, so the blocker is the browser step (consent page or
  shared chrome-agent profile), not this port.

## Deviations from Go, and why

1. **Async everywhere.** `Responder`, `TokenStore` (sync or async), opener, `verify` may return promises.
   Go's `sync.Mutex` single-flight becomes a promise-chain `Mutex` (one refresh / one login for N concurrent
   callers; tested with 10 concurrent refreshes and an 8-call cold-login herd).
2. **`PendingError` is thrown** (a rejected promise), not returned; `pendingOf(err)` mirrors `PendingOf`.
3. **HTTP seam is a function** (`HttpFn = (req) => Promise<{status, headers, body}>`) instead of `*http.Client`;
   Go's custom-transport example (static bearer) becomes `new Client(url, { headers })`. No `fetch` in the
   library: undici cannot take a per-request CA, which the DevAuthServer trust needs.
4. **DevAuthServer TLS**: Node cannot create X.509 certificates, so `devauth.ts` writes a self-signed cert in
   ~40 lines of DER (unique subject per server, or OpenSSL picks the wrong trust anchor when several run in one
   process). Trust is in-process only (`defaultHttp`), like Go's `trustDevCert`.
5. **Tool handler signature** is `(args, ctx)` with `ctx.principal`, not `(ctx, args)` + `PrincipalFrom(ctx)`.
6. **Test-only helpers on DevAuthServer**: `visit(url)` (the AS redirect without following it; Go does this with a
   custom `CheckRedirect` client in example 07) and `documents` (serves the CIMD client document over the same
   TLS server; Go starts a second `httptest` TLS server).
7. **MCP test servers are plain HTTP** (the AS is HTTPS); Go's auth tests run the MCP server over TLS too.
   Audience derivation still follows the socket (`https` when TLS).
8. **Stricter on a malformed error body**: a non-object JSON-RPC `error` (e.g. a proxy's `{"error":"unauthorized"}`)
   is reported as `HTTP <status>: malformed JSON-RPC error`, like Go's decode failure.
9. **Not ported**: Go's `stress_test.go` throughput tables and the fuzz target (`run.sh stress|fuzz`); the
   concurrency properties they guard (refresh race, login herd, MRTR no cross-talk) have smaller tests here;
   hung-server / hostile-bearer cases are not ported. `TestGitHubLive` (static `GH_TOKEN`) is not ported; the OAuth
   GitHub tests are.

Spec ambiguities and choices are identical to Go's (see `../golang/README.md`); the JS port made no new ones.
