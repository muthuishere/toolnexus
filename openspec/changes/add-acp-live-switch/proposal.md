## Why

Karya (issue #197) restarts the whole ACP agent when the user changes mode (shift+tab), which is
slow and throws the session away. ACP already has `session/set_mode` and
`session/set_config_option`; `loadACP` sends them once at load (`mode`, `model` options) but gives
no way to send them again on a running client.

## What Changes

- Every port's ACP client gains `setMode(mode)` and `setModel(model)` (idiomatic spelling per port).
- `setMode` sends `session/set_mode {sessionId, modeId}`.
- `setModel` reuses the load-time `model` logic exactly: `session/set_config_option
  {configId:"model"}`, falling back to `session/set_model {modelId}` (the #110 spike finding).
- No restart, same session; values pass through unvalidated; no vendor names (SPEC §8C).
- An agent rejection fails only that call; the client stays usable.
- SPEC §8C gains the contract; conformance `acp-client/cases.json` gains `liveSwitch`; each port's
  fake ACP agent learns `session/set_mode` and `TN_FAKE_REJECT_MODE`.

## Impact

Additive in all eight ports. No change for callers that do not use the new methods.
