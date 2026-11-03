## 1. Contract (this commit)
- [x] 1.1 ADR 0037 accepted; spike `spikes/media-clients/` (Go) live-verified per SPIKE.md
- [x] 1.2 SPEC §0 item 14 and §7G.1–§7G.13 rewritten
- [x] 1.3 `conformance/media/`: `verbs.json` (7 verbs), `schemas.json` (talk), `cases.json` (47),
      `clients.json` (69), `cli.json` (23), `routing.json` (15 + schema + empty), `fake-cli/`
- [x] 1.4 `examples/media-styles/a2e.json`: `name`, `talk` (unverified)
- [x] 1.5 CHANGELOG `## Unreleased` entry (gaps and unverified verbs named)
- [x] 1.6 `openspec validate add-media-clients`
- [x] 1.7 §7G.12 edge rules D1–D17 pinned after the first ports (SPEC, spec delta, design, cases)
- [x] 1.8 §7G.12 D19–D23 pinned from the live example runs (SPEC §7G.3/§7G.5/§7G.6/§7G.8/§8,
      spec delta, design, cases): now `cases.json` (49), `clients.json` (76), `cli.json` (24),
      `routing.json` (19 + schema + empty); E1 example contract in design.md

## 2. Per-port parity checklist
Each port: verbs.json + a2e.json embedded verbatim (asserted); `MediaClient` + typed media error
with `unavailable`; `createMedia(client|[client])` routing; config + gather; `styleMediaClient` +
`loadMediaStyle`; A2E, ElevenLabs, OpenAI, OpenRouter, fal; `CliMediaClient`, Grok, Codex;
`media.tools()` with `client` metadata and data parts; replay of ALL six conformance files;
runnable `media` example updated (`talk`, a named client); README media section; the §7G.12 edge
rules D1–D17 and their cases. Tick a port only when its code AND its README section are done (D18).
- [ ] 2.1 golang (reference: `spikes/media-clients/`) — code done, README done, edge rules pending
- [ ] 2.2 js — README done
- [ ] 2.3 python — README done
- [ ] 2.4 java — README done
- [ ] 2.5 csharp — code done, README done, edge rules pending
- [ ] 2.6 elixir (coverage gate ≥ 95% still holds) — README done
- [x] 2.7 clojure (JVM and cljgo; note cljgo redirect gap stays) — README done, incl. the koine binary-body caveat
- [ ] 2.8 rust (`cargo test` and `--no-default-features`) — code done, README done, edge rules pending

Round 3 (D19–D23), per port — router skip + joined error (D19), `RunFile.path` (D20), `usage`
(D21), extension table (D22), default User-Agent (D23):
- [ ] 2.9 golang  - [ ] 2.10 js  - [ ] 2.11 python  - [ ] 2.12 java
- [ ] 2.13 csharp  - [ ] 2.14 elixir  - [ ] 2.15 clojure  - [ ] 2.16 rust

## 5. Examples (E1 — design.md "Example contract"), one per port
Each: client order Grok, Codex, ElevenLabs, then OpenAI, fal, A2E (only those with key/CLI);
image `a red apple on a white table`; ElevenLabs speech (`MEDIA_VOICE` or default); tools list +
`generate_image`; Grok video only with `MEDIA_VIDEO=1`; paid video/talk only with `MEDIA_PAID=1`;
smallest/lowest-quality paid images; print client, verb, path/url, mime, size, usage; non-zero exit
on any failed step, exit 0 with a hint when no client is available.
- [ ] 5.1 golang
- [ ] 5.2 js
- [ ] 5.3 python
- [ ] 5.4 java
- [ ] 5.5 csharp
- [ ] 5.6 elixir (also E3: drop the `:preferred_cli_env` deprecation in mix.exs)
- [ ] 5.7 clojure (runnable from the documented location; fix `examples/EXAMPLES.md`; E2: check
      ElevenLabs voice-name resolution — output ~2x the other ports for the same sentence)
- [ ] 5.8 rust (must exit non-zero on failure — it exited 0)

## 3. Docs
- [x] 3.1 Docs site media guide: clients, config, gather, routing, custom client — 8 language tabs
- [x] 3.2 Docs site section `grok-video-zdr` with the exact `/privacy` → Opt in steps (ADR 0037)
- [x] 3.3 Per-port API reference: media clients section
- [x] 3.4 Cookbook recipe: "fall back from Grok to fal for video"

## 4. Verify
- [ ] 4.1 Every port's suite green incl. all six conformance files
- [ ] 4.2 `python3 conformance/check_options_parity.py` green after adding `mediaClientOptions`
      (design.md block) in the last port PR
- [ ] 4.3 One live call per unverified verb (owner keys, manual): OpenAI speech/transcribe/video,
      fal video, ElevenLabs music/transcribe/cloneVoice, A2E talk — update `liveVerified` and the
      CHANGELOG list
- [ ] 4.4 Archive after `add-media-generation` (its MODIFIED requirements build on it)
- [ ] 4.5 Release runbook: the media User-Agent version literal — golang `mediaVersionFallback`
      (`golang/media_engine.go`) and clojure `version` (`clojure/src/toolnexus/media.cljc`, plus its
      `examples/src` mirror) — is bumped with every release alongside the manifests
- [x] 4.6 `release.yml` preflight fails the run when either literal differs from the tag (same
      check as the manifests; it goes green once the port code carrying the literals is committed)
