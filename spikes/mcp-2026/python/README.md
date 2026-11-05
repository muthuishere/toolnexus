# python — in-house MCP 2026-07-28 spike

Python ≥ 3.11. Stdlib (`http.client`, `wsgiref`, `json`, `hashlib`, `ssl`) plus **one** dependency,
`cryptography` (RS256 verify/sign and the dev TLS certificate — the stdlib has no RSA). No MCP SDK.
Port of the Go reference in `../golang/`; same behaviour, Pythonic shape. Package `mcp2026` (`import mcp2026 as mcp`).

## Commands

```sh
./run.sh server                            # listens on 127.0.0.1:${PORT:-7102}/mcp (creates .venv on first run)
./run.sh client http://127.0.0.1:7103/mcp  # the 8 cross-port checks against ANY port's server
./run.sh selftest                          # own client vs own in-process server
./run.sh test                              # pytest: unit + OAuth + examples + docs (hermetic: 127.0.0.1 only)
.venv/bin/python examples/01-connect-http/main.py   # 10 runnable user-story examples, see docs/

# gated live suites
(cd ../golang/keycloak && docker compose up -d)
KEYCLOAK=1 ./run.sh test -k keycloak
CHROME_AGENT_ID=toolnexus-ghoauth GITHUB_OAUTH=1 sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ./run.sh test -k github -s
```

`.venv/` lives in this directory and is gitignored; `run.sh` installs `-e .[test]` into it.

## Layout and LOC (`wc -l`, all lines)

| part | file | LOC |
|---|---|---|
| shared constants, `RpcError`, `text` | protocol.py | 45 |
| HTTP client + threaded WSGI server + dev TLS | http.py | 282 |
| server (fixture + code tools, skills, headers, MRTR `greet`) | server.py | 281 |
| client (HTTP/stdio `call`, MRTR loop, tool search, `download_skill`) | client.py | 288 |
| stdio transport (client + test/example server) | stdio.py | 112 |
| skill URI safety | skills.py | 33 |
| **client OAuth** | auth.py | 532 |
| **server auth (protected resource)** | serverauth.py | 117 |
| **DevAuthServer (dev/test only)** | devauth.py | 202 |
| cross-port checks + fixture loader | checks.py | 120 |
| CLI / package exports | `__main__.py`, `__init__.py` | 42 |
| **library total** | | **2054** |
| tests | tests/*.py | 831 |
| examples | examples/*/*.py | 512 |

Go comparison (non-test): Go ≈ 2,380 lines in the same split; Python ≈ 2,050, of which ~280 is the HTTP layer
Go gets from `net/http` / `httptest`.

## Tests (`./run.sh test`)

79 hermetic tests, ≈ 50 s: the 8 checks unprotected / OAuth-protected / over stdio; client OAuth (pause/resume,
iss + state mismatch, PKCE verified by the AS, refresh on expiry, step-up union, PRM fallback, no-identity error,
opener error surfaces, 10 concurrent 401s → one login); server auth (JWT via JWKS + principal, 5 rejection cases,
per-tool 403, custom `verify`); exactly-once (`test_retry.py`: pause→resume = 1, refresh after 401 = 1, step-up = 1,
mid-MRTR pause resumes the same round, dropped connection / 502 after execution = 1 and an error);
`omit_refresh_resource`; confidential client basic/post; skill URI table (same 21 cases as Go) and hostile
download; tool search ranking and **byte-identical sizes to Go** (eager 56,421 / lazy 519); HTTP edge statuses;
SSE response parsing. `test_examples.py` runs 01–08 and asserts exact output; `test_docs.py` fails if a doc snippet
drifts. Keycloak: 7 live tests + example 09 (`KEYCLOAK=1`). GitHub: example 10 (`GITHUB_OAUTH=1`).

## API (the Python shape)

```python
c = mcp.Client(url)                                   # or mcp.Client.stdio(argv, env=..., stderr=...)
c.call(method, params) / c.call_tool(name, args, respond) / c.tools() / c.invoke(...) / c.download_skill(uri, dir)
c.auth = mcp.OAuth(client_id=..., authorizer=mcp.LoopbackAuthorizer() | mcp.PauseAuthorizer(redirect), store=...)
try: c.call_tool(...)
except mcp.PendingError as e: ...; c.auth.resume(e.pending.id, callback_url)
srv = mcp.Server().tool(name, desc, lambda args, ctx: ...)   # ctx.principal when protected
srv.auth = mcp.ServerAuth(issuer, scopes={"*": [...], "tool": [...]}, verify=None)
mcp.serve(srv, port=7102)                              # srv is a plain WSGI app: mount it anywhere
```

## Deviations from Go, and why

1. **Errors are exceptions**, not `(value, err)`: `RpcError(code, message, data)`, `PendingError(.pending)`,
   `ValueError` / `PermissionError` / `ConnectionError` for client-side failures. `pending_of(err)` exists for parity.
2. **Synchronous API on threads**, like Go — not asyncio (toolnexus' `python/` port is async). Porting into the
   product means an `async` facade or an `asyncio.to_thread` wrapper; the wire logic is unchanged.
3. **Server is a WSGI app** (`Server.__call__`), and `web_handler` returns a WSGI app — the stdlib's idiomatic
   seam instead of `http.Handler`. `mcp.serve()` (threaded `wsgiref`, optional self-signed TLS) plays `httptest`.
4. **Principal via `ctx.principal`** (a `ToolContext` passed to every code tool) instead of `PrincipalFrom(ctx)`.
5. **Own `HttpClient`** (`http.client`) rather than `urllib`: needed per-request redirect control, extra static
   headers (example 08's service key) and an in-process dev-cert trust store (Go's `devTransport`).
6. Example output uses Python names (`(a) web_handler:` vs Go's `(a) WebHandler:`); every other line is identical
   to Go's. Example dirs/numbers match Go's (`examples/NN-name/main.py`).
7. stdio test server is `python -c "... serve_stdio(...)"` instead of Go's re-exec'd test binary.
8. The Keycloak headless browser (`examples/09-keycloak/headless.py`, test harness only) needs a cookie policy that
   sends Keycloak's `Secure` cookies to `http://localhost` (as browsers do); Python's `CookieJar` refuses by default.
9. **GitHub example 10 live: NOT verified (needs_human).** On 2026-11-04 GitHub showed a "Reauthorization required
   — unusually high number of requests" consent page for the `toolnexus-mcp-dev` app (all ports hit it the same
   day); per the rules nothing was clicked. The flow reached GitHub's authorize page with PKCE + `resource`.
10. Not ported: Go's `stress_test.go` / fuzz target (`run.sh stress|fuzz`) and the token-only `TestGitHubLive`.

Spec ambiguities and choices are the Go reference's (see `../golang/README.md`); none new surfaced in the port.

## Verdict

**Own it.** The wire layer (client + server + shared, without OAuth) is ~720 lines of stdlib Python; OAuth adds
~850. The only real dependency is `cryptography` for RS256 — acceptable (the product already ships `pyyaml` and
the `mcp` SDK, which itself depends on far more).
