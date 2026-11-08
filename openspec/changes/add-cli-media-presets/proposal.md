## Why
Users signed in to the official Grok, Codex or Google Antigravity CLI should be able to drive it in
its documented headless mode (`grok -p`, `codex exec`, `agy -p`) for images and video, from the same
`createMedia` they already use. A consumer
(Spot) reported `aspectRatio` rejected on CLI clients and an undocumented Grok watermark.

## What Changes
- `loadMediaStyle` accepts built-in presets `xai-grok-cli`, `openai-codex-cli`, `google-agy-cli` and `-mock`
  twins; `createMedia` accepts any loaded style; new `verbs()` and `generateMedia(style, verb, args)`.
- Unsupported verb on styles-only media ⇒ `media: no style supports "<verb>"`.
- Size hints folded into the CLI prompt (byte-pinned).
- New Google Antigravity (`agy`) CLI client, image only.
- Go CLI `toolnexus media <verb> --style … --prompt …`.
- ADR 0042; SPEC §7G.3, §7G.10, §7G.10a; conformance `cli.json` (+9 cases, 8 verbCases).

## Impact
All eight ports. Additive except the `no style supports` wording, which only applies when every
source is a loaded style.
