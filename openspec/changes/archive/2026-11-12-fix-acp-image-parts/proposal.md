## Why

The ACP model source dropped multimodal content in both directions. Outbound, a user image was
JSON-encoded (base64 and all) into the single text block of `session/prompt`, so the agent never
saw an image and the prompt ballooned. Inbound, an image the agent sent back — from its own tool
(`tool_call_update` content) or as an `agent_message_chunk` — was read as text, found to have none,
and silently lost: no part, no `RunResult.files` entry.

## What Changes

- `initialize` reads `agentCapabilities.promptCapabilities`; `session/prompt` is one text block
  then one native `image` / `audio` / `resource` block per part the agent advertised. Unsupported
  and URL-only parts are named in the text by their §1B description only. Base64 never enters text.
- Inbound `image` / `audio` ContentBlocks become §1B parts: on the `tool_result` stream event
  (`parts`) and in `RunResult.files` (`from` = tool call name, `callId` = `toolCallId`; message
  chunks use `from:"acp"`, `callId:""`), appended after the client's own tool files.
- `SPEC.md` §8C gains "ACP content parts", the byte-level contract.

## Impact

- Capability: `acp-client` (one ADDED requirement).
- Code: the ACP client and the in-process seam in all eight ports. Go done first (reference).
- Absent media ⇒ byte-identical prompts and results.
