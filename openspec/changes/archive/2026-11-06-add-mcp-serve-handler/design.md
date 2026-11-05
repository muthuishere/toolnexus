See `docs/adr/0039-mcp-serve-embeddable-handler.md` for the decision, the host/toolnexus split, and why the
Go transport is implemented over JSON-RPC instead of mcp-go's `server` (tools not resolvable per
request; elicitation only dequeued on a GET stream). Ports whose SDK server CAN resolve per request and
send a server→client request on the POST stream may use their SDK; the observable contract is SPEC §7C.1.
