# S18 — MCP streamable-HTTP (the remote transport) on both hosts

**Question:** S15 proved MCP **stdio** against a real child process. The other
half of SPEC §2 is the **remote** transport — JSON-RPC over streamable-HTTP.
Does it work in portable Clojure, in ONE `.cljc` with **no reader conditional**,
producing byte-identical output on Clojure (JVM) and cljgo?

**Answer: yes, for the transport as toolnexus uses it.** Verified 2026-07-31.
The honest caveats are in *What it does NOT cover* — they are about the MCP
*protocol* (sessions, resumability), not about Clojure or koine.

```
== Clojure (JVM)
== cljgo (AOT binary)
== cljgo (interpreted)
== diff
  jvm == cljgo-aot  (byte-identical, 2275 bytes)
  jvm == cljgo-run  (byte-identical, 2275 bytes)
```

Stable across three consecutive `./run-both.sh` runs. Reproduce: `./run-both.sh`
(needs `clojure` and `cljgo`; **no** `npx`, no network beyond `127.0.0.1`, no
key, no LLM).

## The numbers

| | |
|---|---|
| source | `src/toolnexus/mcphttp.cljc`, 511 lines |
| reader conditionals | **0** |
| `java.*` / `Thread/` / host interop | **0** (one mention, in a comment) |
| third-party deps | **1** — `net.clojars.muthuishere/koine 0.4.2` |
| modes run | 3 (JVM · cljgo AOT binary · cljgo interpreted) |
| report | 2275 bytes, byte-identical across all three (`:host` stripped) |
| tools advertised by the remote | 4, in scrambled wire order |
| §0.4 branches proved | 3/3 |
| named failure modes proved | 4 (`transport` · `http-status` · `malformed-body` · `sse-unsupported` path) |

## What it covers

The remote server is a `koine.server` we stand up ourselves on `127.0.0.1:0`
speaking MCP JSON-RPC. It advertises four tools — `zebra note`, `alpha/stats`,
`mid.boom`, `echo` — in **scrambled** wire order, so the client's sort is a
measurement rather than a coincidence.

### §2 lifecycle over HTTP

`initialize` → `tools/list` → `tools/call`, one JSON-RPC message per POST.
`serverInfo` comes back as `toolnexus-fake-remote/1.0.0`; four tools are listed
and sorted.

### §0.2 naming

Tool names are `sanitize(server)_sanitize(tool)`, measured on names that
actually need sanitizing:

```
"remote api" + "zebra note"  -> remote_api_zebra_note
"remote api" + "alpha/stats" -> remote_api_alpha_stats
"remote api" + "mid.boom"    -> remote_api_mid_boom
```

### §0.4 result shaping — all three branches, one tool each

| tool | server returns | client produces | branch |
|---|---|---|---|
| `zebra note` | two `text` content parts | `"line one\nline two"` | `text` |
| `alpha/stats` | `structuredContent` **and** a text part | `{"alpha":2,"nested":{"a":true,"b":false},"zulu":1}` | `structuredContent` |
| `mid.boom` | `isError: true` + text | `"boom: the tool failed"`, `isError` | `isError` |

`structuredContent` wins over the text part, which is what makes the second row
a real test and not a restatement of the third. The JSON string is
`koine.json/write-str`'s **sorted-key** encoding — this is the only reason the
byte-comparison across hosts means anything.

### §0.3 `${ENV}` header expansion, never logged

The spike's own config carries
`"Authorization": "Bearer ${TN_FAKE_TOKEN}"`, plus `X-TN-Static` (no template)
and `X-TN-Missing: ${TN_DEFINITELY_UNSET_VAR}` (unset ⇒ empty string).
`run-both.sh` exports `TN_FAKE_TOKEN=not-a-real-secret` — an obvious non-secret
on purpose.

**No header value is in the report, on either side of the wire.** What is
reported is shape only:

- client side: `{"keys":["Authorization","X-TN-Missing","X-TN-Static"],"expanded":true}`
  — expansion **changed** the value.
- server side (computed in-process, only booleans escape):
  `{"authorization":true,"still-template":false,"matches-env":true,"tn-keys":["x-tn-missing","x-tn-static"]}`
  — the header arrived, it is **not** still `${…}`, and it equals what the
  environment says it should. Not even a length is reported (a length leaks a
  secret's size).

The shared fixture `examples/mcp.json` is also parsed unchanged: its
`example-remote` entry comes out `{kind: remote, enabled: false}` — which is
exactly why S15 could not exercise this transport at all.

### §0.3 failure isolation — the dead server

A second remote server is pointed at a port nothing listens on (bound with
`{:port 0}`, read back, then `stop!`ed — a hardcoded port number is a coin
flip). Result, **identical on both hosts**:

```json
{"status":"failed","phase":"initialize",
 "failure":{"error":"transport","status":null,"transport-error":"connect-failed"}}
```

`koine.http/failed?` hands back `:connect-failed` as **data**, and the toolkit
carries on: statuses are `[{dead remote, failed}, {remote api, ready}]`, all
four live tools are still present, and `remote_api_echo` still returns
`Echo: toolnexus`. One bad server never breaks the toolkit — measured, not
asserted.

That the two hosts agree on `connect-failed` is itself the point: the JVM throws
`java.net.ConnectException` and Go a `*fmt.wrapError`, and no portable `catch`
can tell them apart. koine classifies once, centrally, and both hosts get the
same keyword.

### The two degradations

Both go through a real socket on the same live server, and both become a
**named** failure rather than a crash:

| endpoint | answer | client result |
|---|---|---|
| `/badstatus` | `503` + `text/plain` | `{"error":"http-status","status":503}` |
| `/garbage` | `200` + `<html>not json at all</html>` | `{"error":"malformed-body","status":200}` |

Nothing in `http-rpc!` throws, deliberately: an exception crossing that boundary
is precisely how "isolated" turns into "fatal".

### SSE — the *streamable* in streamable-HTTP

A client that only does JSON-over-POST is not doing streamable-HTTP. The same
MCP endpoint is also mounted at `/mcp-sse`, answering with `text/event-stream`:
a `: keep-alive` comment frame followed by `event: message` / `data: <json-rpc>`.
`koine.stream/sse-post` drives it, the comment frame is skipped by koine's
parser, and both `tools/list` (4 tools) and `tools/call` (`Echo: over-sse`,
branch `text`) come back over SSE — on all three modes, byte-identical.
`(host/supports? :stream/sse)` is `true` on both hosts.

## Findings

1. **Nothing here needed a reader conditional.** Zero `#?` in the spike's own
   source; the whole remote transport is `koine.http`, `koine.stream`,
   `koine.server`, `koine.json`, `koine.env` and `clojure.core`. This was the
   riskiest remaining §2 surface and it came out clean on the first cljgo build.
2. **No host divergence at all.** JVM, cljgo AOT and cljgo interpreted produced
   the same 2275 bytes, including the failure classifications — which are the
   values most likely to differ, since they originate in two completely
   different runtimes' network stacks. koine's central `classify` is carrying
   that weight.
3. **No koine gap found for this spike.** Everything needed already exists:
   transport failures as data (`failed?`), a `{:port 0}` server that reports its
   bound port, SSE via `sse-post`, `${ENV}` expansion in `env/expand`.
4. **`cljgo which <name>` does not exist** (the s15/s17 `run-both.sh` inherited
   it). `cljgo build` installs the binary as `./<name>` next to `build.cljgo`;
   the s18 script calls `./mcphttp` directly. The old line failed *silently* —
   `cljgo which` printed nothing and the fallback never fired — which is trap 2
   in the brief wearing a different hat.
5. **koine.server's cljgo backend prints a `bri: listening on http://localhost:N`
   banner to stdout.** It is host-specific *and* carries a port, so a spike that
   captures raw stdout can never be byte-diffed. `run-both.sh` takes `tail -1`
   on both cljgo modes. Worth knowing before the real port ships anything that
   parses its own stdout.
6. **Client-sent header sets are not comparable across hosts.** The JVM and Go
   clients disagree on the incidental headers (`user-agent`, `accept-encoding`,
   `host`, `content-length`), so the server reports only the `x-tn-*` keys plus
   the presence of `authorization`. Not a bug — but any future conformance test
   that asserts on "the headers the server received" must allowlist, not compare
   whole sets.

## What it does NOT cover

- **MCP session management.** Real streamable-HTTP servers return an
  `Mcp-Session-Id` on `initialize` and require it on every later request; some
  also support `GET` for a server→client stream and `Last-Event-ID` resumption.
  None of that is exercised — the fake server does not demand a session. This is
  the biggest gap between this spike and a production remote server, and it is a
  *protocol* gap, not a Clojure or koine one.
- **The SSE fallback path** (`text/event-stream` when the server rejects
  streamable-HTTP outright) and content-type negotiation. The spike calls the
  SSE endpoint explicitly rather than discovering it from a response.
- **Timeouts.** `SPEC §2` bounds each phase (connect / initialize / tools/list)
  by the server `timeout` with a fresh budget per phase. Nothing here is
  time-bounded, and no hung-server case is measured. `koine.http` takes a
  `:timeout-ms`, and `(host/supports? :http/timeout)` is true on both hosts —
  but "supported" is not "measured".
- **Concurrency.** Every call is serial. The id counter is an atom precisely so
  parallel calls are safe (§8), but two in-flight calls are never actually run.
- **Real remote servers.** The peer is our own `koine.server`, so its wire
  behaviour is exactly what we wrote. It is a conformance harness, not evidence
  about anyone else's implementation.
- **OAuth.** Out of scope for v1 per §2 — bearer token via `headers` only, which
  is what is measured.
- **`notifications/initialized`.** The stdio transport sends it (S15); this one
  does not, because the HTTP transport has nowhere to put a fire-and-forget
  notification without a session. See the session gap above.
