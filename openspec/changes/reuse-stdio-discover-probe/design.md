# Design — reuse-stdio-discover-probe

## The handoff invariant

After a stdio discover **error reply** the old server has written exactly one line and is now
blocked reading stdin. So at the moment the in-house reader has delivered that reply, there are no
unread bytes owed by the server. The handoff is safe iff the in-house probe **stops consuming
stdout before the SDK's reader starts**, and does not leave a reader blocked in `read()` that could
steal the `initialize` reply.

Two shapes satisfy that:

1. **Synchronous probe read** — the probe writes `server/discover` and reads lines itself (with a
   deadline) until it sees its id; no background reader exists.
2. **Self-terminating reader** — the reader loop, in probe mode, exits immediately after delivering
   the discover response, before issuing another `read()`.

Any byte-buffer the probe reader filled beyond the reply line (none for a conforming old server)
must be handed to the SDK transport too, or the port must read unbuffered/line-exact.

## Per-port handoff

| port | frozen path | handoff |
|---|---|---|
| js | `@modelcontextprotocol/sdk` | SDK `Transport` interface: a small adapter transport over the child's `stdin`/`stdout` (StdioClientTransport always spawns) |
| python | `mcp` | `ClientSession(read_stream, write_stream)` takes anyio memory streams: feed them from the existing process pipes |
| golang | `mark3labs/mcp-go` | `transport.NewIO(reader, writer, logging)` over the existing pipes |
| java | official `mcp` SDK | `McpClientTransport` adapter over the Process streams (`StdioClientTransport` always spawns) |
| csharp | `ModelContextProtocol` | `StreamClientTransport(serverInput, serverOutput)` over the Process streams |
| rust | `rmcp` | `(ChildStdout, ChildStdin)` tuple / `async_rw` transport over the existing child |
| elixir | in-house | continue on the same Port |
| clojure | in-house | continue on the same process conn |

## Silence still respawns

A server that never answered discover may be mid-wedge or may answer late (a late `-32601` would
then collide with `initialize` id space in some SDKs). Killing and respawning keeps the current
proven behaviour for that rare case. The cost lands only on servers that ignore unknown methods.
