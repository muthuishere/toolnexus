## Why

Real ACP agents (e.g. the Claude ACP adapter) send a tool's `rawInput` (file path, command) and its
full `title` only on a later `tool_call_update`, not on the initial `tool_call`. Every port read
`rawInput` only at `tool_call`, so a host UI showed bare rows like `Write` or `Terminal` with no
arguments. Requested by Karya (go-agent-cli), issue #188.

## What Changes

- A `tool_call_update` carrying a non-empty `rawInput` object emits a new stream event
  `{type:"tool_call_args", id, name, args, via:"acp"}`.
- A non-empty `title` on any `tool_call_update` becomes the call's name for later events of that id,
  so `tool_result` carries the full title.
- Updates with neither emit nothing (unchanged). Non-streaming generate unchanged.
- New conformance case `conformance/acp-client/cases.json` `stream.acpToolUpdate`; SPEC §8 / §8C.

## Impact

All eight ports' ACP streaming generate and their fake ACP agents (new `TN_FAKE_TOOLUPDATE` mode).
Additive: a consumer that ignores unknown event types sees no change.
