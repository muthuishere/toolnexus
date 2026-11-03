# Spike: where does an ACP agent deliver a generated image? (Grok, step 1 only)

Question: if a local subscription CLI generates media over ACP (`grok agent stdio`), where does the
media come back, and how would that map onto §7G?

Scope (cut by the owner): Grok only, ONE image, observe only. No video, no Codex, no style prototype.

## What's here
- `main.go` is a stdlib-only ACP driver (~150 lines). It runs initialize, then session/new (cwd = a fresh
  temp dir), then one session/prompt. Every raw JSON-RPC line in both directions goes to
  `captures/<name>.jsonl`. It answers `session/request_permission` with the first allow option.
- `captures/grok-image.jsonl` holds 133 messages (720 KB). Run it with `go run . grok-image "<prompt>"`.
- Env used: `GROK_DEFAULT_SELECTED_PERMISSION=allow_always`, `GROK_CONFIG={"features":{"image_gen":true}}`.

## Findings (live, 2026-11-03, Grok CLI 1.0.70)
1. **It works, and the image arrives as a local file PATH, not as bytes or a URL.** No
   `agent_message_chunk` image or resource_link block was sent. Nothing was written to the session cwd.
   The path is in the `image_gen` tool's **`tool_call_update` (status `completed`)**, in two places:
   ```json
   {"sessionUpdate":"tool_call_update","toolCallId":"call-f637…-1","status":"completed",
    "content":[{"type":"content","content":{"type":"text","text":"{\"path\":\"/Users/…/.grok/sessions/%2Fvar%2F…media-acp-491837593/01a1a1bd-…/images/1.jpg\",\"filename\":\"1.jpg\",\"session_folder\":\"images\",\"message\":\"Image generated and saved to …\"}"}}],
    "rawOutput":{"type":"ImageGen","path":"/Users/…/.grok/sessions/<url-encoded cwd>/<sessionId>/images/1.jpg","filename":"1.jpg","session_folder":"images"}}
   ```
   The file is a real JPEG: 1024x1024, 160 KB, mode 0600. It sits under `~/.grok/sessions/<url-encoded
   cwd>/<sessionId>/images/N.jpg`, which is grok-owned storage outside the cwd, so its lifetime is
   grok's to decide.
2. **The final text only gives a relative path** (`![A red apple on a white table](images/1.jpg)`).
   It is relative to the session folder, not the cwd. Text scraping alone would resolve it to the wrong
   place, so it can only be a last-resort fallback.
3. **Identifying the tool.** The `tool_call` carries `_meta["x.ai/tool"].kind = "image_gen"`, and
   `rawOutput.type = "ImageGen"`. Both are vendor extensions. The standard ACP `kind` is just `"other"`.
4. **Permissions.** No `session/request_permission` request arrived (count is 0). The env var put
   the session in `yolo:true`. Grok still emits its own `_x.ai/…pending_interaction` and
   `interaction_resolved` notifications. A generic engine must still answer `request_permission`
   for agents that do send it.
5. **The agent detours.** Before calling `image_gen`, the agent first read its bundled
   `imagine/SKILL.md` (one extra model call). That makes 3 model calls and ~99k input tokens (64k
   cached) for one image. The prompt reply's `_meta.usage` reports `costUsdTicks: 351342400`; the
   unit is undocumented.
6. **Noise.** About 80% of the traffic is `_x.ai/*` vendor notifications (setup phases, models,
   announcements, MCP init: it loaded the user's global MCP servers) plus `agent_thought_chunk`.
   A client must ignore unknown methods. One server-to-client request (`skills-reload`) uses a
   string id.

## Timing
| step | ms from spawn |
|---|---|
| initialize reply | 1,623 |
| session/new reply | 1,826 |
| image_gen tool_call (after the skill-read detour) | 9,840 |
| image_gen completed (path available) | 20,628 (about 10.8 s of actual generation) |
| session/prompt reply (`end_turn`) | 23,480. The prompt turn took 21.7 s. |

## How it maps onto §7G (sketch, not built)
- Add a style with `transport:"acp"`, `command:["grok","agent","stdio"]` and `env`. Each verb gets a
  `prompt` template (`"Generate one image: {prompt}. Use your image tool. Do not write code."`) in
  place of `start`/`poll`. The ACP turn is the async job: `jobId` = sessionId, status = `stopReason`
  mapped to `completed`/`failed`, and cancellation = `session/cancel`.
- Add a result rule in place of `resultPath`. Take completed `tool_call_update`s, read the dotPath
  `rawOutput.path`, and as a fallback parse JSON in `content[].content.text` and read its `.path`.
  Then also accept standard ACP image/resource_link blocks. Because the tool is chosen by matching
  `_meta.x.ai/tool.kind`, a vendor key, that matcher belongs in the style JSON, not the engine.
- Each found path becomes a `File{type:"image", mimeType from extension (.jpg -> image/jpeg), url:"file:///…"}`
  in `MediaResult.files`. That leaves `generate_image` and `RunResult.files` unchanged. The file
  is grok-owned and mode 0600, so the existing `download` idea becomes "copy out" before the
  session ends.

## Open questions
Whether the `~/.grok/sessions` files are ever cleaned up; whether paid image tools cost subscription
credits or count against a quota; parallel caps (`max_parallel_image_gen_calls` default 8); whether
video lands the same way (not tested); and whether codex-acp returns bytes instead of a path (not tested).
