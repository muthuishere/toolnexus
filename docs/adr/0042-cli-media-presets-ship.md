# ADR 0042 — CLI media presets ship, as styles

- Status: **ACCEPTED** (owner, 2026-11-08)
- Supersedes: the media part of [ADR 0037](0037-media-clients.md) — the CLI clients stay, but the
  public entry point is a style preset, not a named client constructor.
- Keeps: [ADR 0041](0041-in-process-client-is-the-seam-vendor-agents-are-recipes.md) for chat and
  coding agents. Nothing here brings vendor CLI *agents* back into the library.

## Context

Grok, Codex and Google Antigravity each ship an official CLI with a documented headless mode
(`grok -p`, `codex exec`, `agy -p`) that can make images (and, on Grok, video) for a user who is
signed in to it. The `media-clients`
branch drove `grok` and `codex` headless and pinned their output shapes live. A consumer (Spot) hit
two problems: `aspectRatio` raised "argument not supported" on the CLI clients, and Grok's output
carries a watermark nobody had mentioned.

ADR 0041 removed vendor CLI *agents* because their flags rot faster than we release. Media is
narrower: one prompt in, one file out, three small parsers, all pinned by a fake CLI in
conformance.

## Decision

1. CLI media ships: a preset drives the vendor's official CLI in its documented headless mode,
   signed in by the user. toolnexus holds no credential for it and never edits the CLI's
   settings. The provider's terms of service apply to how the user uses its CLI; the docs say so.
2. The entry point is the **style loader**: `loadMediaStyle(MediaStyle.XaiGrokCli | OpenAICodexCli |
   GoogleAgyCli)` (spelled per port), accepted by `createMedia` like an HTTP style. One-shot:
   `generateMedia(style, verb, args)`. Shell: `toolnexus media <verb> --style xai-grok-cli …`.
   The Grok/Codex client classes stay underneath (and stay public, undocumented).
3. **Vendor-named preset constants are an explicit owner-accepted exception** to the rule "no
   vendor names in the public API". It holds for these media presets only.
4. Verb support comes from one declaration per style (`supports(verb)`): Grok image + video,
   Codex image, agy image. A verb no style declares fails with `media: no style supports "<verb>"`
   before any process starts; the tool face does not list it.
5. Size hints (`width`, `height`, `seconds`, `aspectRatio`) are **folded into the prompt** with a
   byte-pinned rule (SPEC §7G.10) instead of being rejected.
6. No fallback chain is advertised: docs and examples use one style per `createMedia`.
7. Each preset has a `-mock` twin (no process, a fixed file) so code written against a preset
   can be tested without a CLI.
8. Watermarks are documented, never removed.

## Consequences

- Three parsers to keep current. A CLI output change breaks one preset; the fake-CLI cases show
  exactly which line moved.
- agy reports an exhausted image quota inside a successful result (live-captured 2026-11-08); the preset maps it to `unavailable`.
- Permissions: Grok `--yolo` and agy `--dangerously-skip-permissions` run with no prompt; Codex
  stays sandboxed (`-s workspace-write`) because that is enough to save its image.
