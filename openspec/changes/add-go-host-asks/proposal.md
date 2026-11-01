## Why

go-agent-cli builds directly on the toolnexus Go port and works around five gaps:
it rebuilds a whole toolkit per MCP arrival (Register races a running turn),
loads each MCP server separately so a slow one does not hold up the rest, closes
over its own turn context inside WaitFor, cannot see when the Compactor replaced
the transcript, and has no way to tell read-only tools from mutating ones for its
permission modes.

Owner directive (2026-11-01): these land in **golang first**; the other six
ports follow before the next release. This change records the behavior now so the
port is mechanical later.

## What Changes

- `Toolkit.Register` is safe concurrently with reads and with other Register calls.
- `LoadMcpWithOptions(ctx, cfg, McpLoadOptions{WaitFor, OnServer})` reports each server as it finishes.
- `ClientOptions.WaitForCtx(ctx, Request)` gets the turn context; wins over WaitFor.
- `CompactorOptions.OnCompact(ctx, CompactInfo)` after each compaction.
- `Tool.Hints{ReadOnly, Destructive, OpenWorld}` on builtins and from MCP annotations, passed on `BeforeToolEvent.Hints`.
- Permission gates (ask #5) are built on the existing `BeforeTool` hook — no `Permit` option (see design note in tasks).
- Bug fix found on the way: a disabled server's status was written without the lock while other servers loaded (data race in LoadMcpWithContext and ListMcpTools).

## Impact

golang only today. Absent options are byte-identical. SPEC.md §4C.
