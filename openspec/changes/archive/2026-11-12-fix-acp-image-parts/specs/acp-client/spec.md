## ADDED Requirements

### Requirement: ACP content parts
The ACP client SHALL read `agentCapabilities.promptCapabilities` at `initialize` and SHALL send each `session/prompt` as one text block followed by one block per non-text part in message order: `{type:"image", mimeType, data}` only when `image` is advertised, `{type:"audio", mimeType, data}` only when `audio` is advertised, and `{type:"resource", resource:{uri:"attachment:///<name>", mimeType, blob}}` for a file only when `embeddedContext` is advertised. Every non-text part SHALL appear in the text as its §1B description `<type> (<mimeType>, <N> bytes)` and base64 MUST NOT appear in any text block; a part without a block (unadvertised or URL-only) SHALL reach the agent as that description only. Inbound `image`/`audio` ContentBlocks in a completed or failed `tool_call_update` SHALL become the `tool_result` event's `parts` and `RunResult.files` entries with `from` = the tool call's name and `callId` = its `toolCallId`; with no text, the tool output SHALL be the parts' §1B descriptions. An image/audio `agent_message_chunk` SHALL NOT be answer text and SHALL become a `RunResult.files` entry with `from:"acp"` and `callId:""`.

#### Scenario: Image sent as a native block when advertised
- **WHEN** the agent advertises `promptCapabilities.image` and the user message has a text part and a 1x1 PNG image part
- **THEN** `session/prompt` is `[text block, {type:"image", mimeType:"image/png", data:<base64>}]` and no text block contains the base64

#### Scenario: Image described when not advertised
- **WHEN** the agent advertises no prompt capabilities and the same message is sent
- **THEN** `session/prompt` has only the text block, and it contains `image (image/png, 67 bytes)` and not the base64

#### Scenario: Agent tool returns an image
- **WHEN** the agent sends `tool_call {toolCallId:"t1", title:"screenshot"}` then a completed `tool_call_update` whose content is one image block
- **THEN** the `tool_result` event has `output` `image (image/png, 67 bytes)` and one image part, and `RunResult.files[0]` is `{type:"image", mimeType:"image/png", data, from:"screenshot", callId:"t1"}`

#### Scenario: Agent message carries an image
- **WHEN** the agent sends `agent_message_chunk` text `here` then an `agent_message_chunk` image block
- **THEN** the answer text is `here` and `RunResult.files` ends with `{type:"image", mimeType:"image/png", data, from:"acp", callId:""}`, for `run` and for the `stream` `done` result
