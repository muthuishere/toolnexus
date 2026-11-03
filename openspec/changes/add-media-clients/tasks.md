## 1. Contract (this commit)
- [x] 1.1 ADR 0037 accepted; spike `spikes/media-clients/` (Go) live-verified per SPIKE.md
- [x] 1.2 SPEC §0 item 14 and §7G.1–§7G.13 rewritten
- [x] 1.3 `conformance/media/`: `verbs.json` (7 verbs), `schemas.json` (talk), `cases.json` (47),
      `clients.json` (69), `cli.json` (23), `routing.json` (15 + schema + empty), `fake-cli/`
- [x] 1.4 `examples/media-styles/a2e.json`: `name`, `talk` (unverified)
- [x] 1.5 CHANGELOG `## Unreleased` entry (gaps and unverified verbs named)
- [x] 1.6 `openspec validate add-media-clients`
- [x] 1.7 §7G.12 edge rules D1–D17 pinned after the first ports (SPEC, spec delta, design, cases)

## 2. Per-port parity checklist
Each port: verbs.json + a2e.json embedded verbatim (asserted); `MediaClient` + typed media error
with `unavailable`; `createMedia(client|[client])` routing; config + gather; `styleMediaClient` +
`loadMediaStyle`; A2E, ElevenLabs, OpenAI, OpenRouter, fal; `CliMediaClient`, Grok, Codex;
`media.tools()` with `client` metadata and data parts; replay of ALL six conformance files;
runnable `media` example updated (`talk`, a named client); README media section; the §7G.12 edge
rules D1–D17 and their cases. Tick a port only when its code AND its README section are done (D18).
- [ ] 2.1 golang (reference: `spikes/media-clients/`) — code done, README + edge rules pending
- [ ] 2.2 js
- [ ] 2.3 python
- [ ] 2.4 java
- [ ] 2.5 csharp — code done, README + edge rules pending
- [ ] 2.6 elixir (coverage gate ≥ 95% still holds)
- [x] 2.7 clojure (JVM and cljgo; note cljgo redirect gap stays)
- [ ] 2.8 rust (`cargo test` and `--no-default-features`) — code done, README + edge rules pending

## 3. Docs
- [ ] 3.1 Docs site media guide: clients, config, gather, routing, custom client — 8 language tabs
- [ ] 3.2 Docs site section `grok-video-zdr` with the exact `/privacy` → Opt in steps (ADR 0037)
- [ ] 3.3 Per-port API reference: media clients section
- [ ] 3.4 Cookbook recipe: "fall back from Grok to fal for video"

## 4. Verify
- [ ] 4.1 Every port's suite green incl. all six conformance files
- [ ] 4.2 `python3 conformance/check_options_parity.py` green after adding `mediaClientOptions`
      (design.md block) in the last port PR
- [ ] 4.3 One live call per unverified verb (owner keys, manual): OpenAI speech/transcribe/video,
      fal video, ElevenLabs music/transcribe/cloneVoice, A2E talk — update `liveVerified` and the
      CHANGELOG list
- [ ] 4.4 Archive after `add-media-generation` (its MODIFIED requirements build on it)
