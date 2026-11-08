## Why

Issue #150. With `2026-07-28` listed next to legacy versions, a POST carrying only
`MCP-Protocol-Version: 2026-07-28` (no `Mcp-Method`, no `_meta` version) was routed to the 2026 wire and
rejected with HTTP 400 `-32020`. A real 2026 client always sends `Mcp-Method`, so such a request comes
from a client that pinned the version string without speaking the 2026 wire; the host had to rewrite the
header to get it served. Separately, `initialize` never echoing `2026-07-28` was undocumented.

## What Changes

- Routing (SPEC §7C.2): on a mixed mount, the `MCP-Protocol-Version` header alone no longer selects the
  2026 wire. A POST is a 2026 request iff its method is not `initialize`, its header is not a listed
  legacy version, and (method is `server/discover`, or `Mcp-Method` is present, or `params._meta` carries
  the protocol version). Everything else goes to §7C.1.
- The §7C.1 wire accepts `MCP-Protocol-Version: 2026-07-28` when that version is listed (it was a 400).
- `initialize` behaviour is unchanged (newest listed legacy version) and is now documented as intended.
- 2026-only mounts are unchanged. All eight ports.

## Impact

`conformance/mcp_handler/cases.json` (`opt_in_2025_11_25` step 5 flips from 400 to 200;
`mcp2026_mixed_with_legacy` gains four steps). SPEC §7C.2.
