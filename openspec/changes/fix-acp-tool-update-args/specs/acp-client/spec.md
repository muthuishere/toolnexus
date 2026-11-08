## MODIFIED Requirements

### Requirement: ACP streaming
The ACP client's streaming generate SHALL emit each `agent_message_chunk` as a text event when it is read, `agent_thought_chunk` as a `thought` event with `via:"acp"`, and agent tool calls/results as `tool_call`/`tool_result` with `via:"acp"`. A `tool_call_update` carrying a non-empty `rawInput` object SHALL emit `{type:"tool_call_args", id, name, args, via:"acp"}` before any `tool_result` from that same update, and a non-empty `title` on a `tool_call_update` SHALL become the call's name for every later event of that id.

#### Scenario: Drip
- **WHEN** the agent sends three chunks 200 ms apart
- **THEN** the stream yields three text deltas and the first arrives at least 300 ms before `done`

#### Scenario: Arguments arrive on an update
- **WHEN** the agent sends `tool_call` titled `Write` without `rawInput`, then an in-progress `tool_call_update` with title `Write /tmp/a.txt` and `rawInput`, then a bare in-progress update, then a completed update
- **THEN** the stream yields `tool_call` named `Write`, then `tool_call_args` named `Write /tmp/a.txt` with the rawInput as args, then `tool_result` named `Write /tmp/a.txt`, and nothing for the bare update
