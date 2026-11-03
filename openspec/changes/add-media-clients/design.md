# Design — media clients

The design is ADR 0037 (`docs/adr/0037-media-clients.md`) plus the spike findings in
`spikes/media-clients/SPIKE.md`. SPEC §7G is the byte contract; this file records the decisions
taken while pinning it that the ADR left open, so port agents do not re-decide them.

## Decisions made while writing the contract

1. **Errors carry a status; `unavailable` is typed.** Not string-matched. Every port exposes a way
   to test for it (§7G.2 lists each). Function-face errors keep the ADR 0036 texts.
2. **`unavailable` is a closed list** (§7G.2): 402; 429 whose body says `quota`/`credit`/`billing`/`balance`; built-in
   client without a key (no request); CLI that cannot be started; Grok `zero data retention` /
   `usage limit`; Codex `usage limit` / `quota`. A missing key and a missing CLI are `unavailable`
   so that `createMedia([grok, fal])` on a machine without grok, or without `FAL_API_KEY`, just
   works.
3. **Routing wraps every all-unavailable outcome**, even for one client:
   `media: no client available for "<verb>": a: …; b: …`. One rule, no special case.
4. **Arg checks belong to the router**, per candidate, before any request or spawn. *Superseded
   by D19 (2026-11-04)*: the first-client check, fall-through arg dropping and the `not supported
   by <name>` / `by this style` texts are gone.
5. **Tool schema = the first supporting client's args.** It is the client a call tries first;
   union schemas would invite args the primary rejects.
6. **`models` map** added beside `model`: `model` is the primary verb's model (the one the spike
   used), `models[verb]` overrides per verb. Needed because OpenAI/fal serve several verbs with
   different model ids and the ADR's single `model` cannot express that.
7. **Gather defaults**: built-in clients `download` (closes ADR 0036's expiring-URL gap; the spike's
   default), style clients `url` (the ADR 0036 behaviour, so `cases.json` keeps its semantics).
   `dir` defaults to `<os temp>/toolnexus-media`, `maxBytes` to 100 MiB.
8. **`gather.keep`** (the ADR named it without semantics) = keep the CLI's original file after
   copying it out; default `true`, `false` deletes it. Only local-path results are affected.
9. **Tool parts for local files are `data` parts; only images reach the model** (coordinator,
   2026-11-03). §1B forbids paths in parts, so downloaded/inline/CLI files are base64 parts. Only
   `image` parts of a media tool result are emitted to the model (§8A); video and audio parts go
   only to `toolCalls[].parts` / `RunResult.files`, and the model sees the `<kind> ready: …` line
   (URL in url mode, saved path in download mode). A base64 video in context is huge and most
   models cannot read it. Pinned per case as `modelParts`.
10. **Downloads and input fetches send no client headers** — a provider key never leaves its
    origin, even when the file host is the same origin. Fal's `status_url`/`response_url` follow
    the §7G.8 origin rule (headers only on the base URL's origin).
11. **A2E stays a style**: the built-in `A2EMediaClient` is a style client over the embedded
    `examples/media-styles/a2e.json` (byte source, embedded verbatim) with built-in defaults.
    Every other API client is code on the shared API base (multipart, binary bodies, voice
    lookup and fal's absolute poll URLs do not fit the style format, and growing the style format
    to fit them would recreate the ADR 0036 weight the owner rejected).
12. **Style format additions**: `name` (client name in messages) and `Verb.required` (extra
    required args — keeps a2e's talk requiring audio although canonical talk requires only image).
    A style declaring an unknown verb (incl. the old `talking`) is a load error.
13. **CLI clients take `prompt` only.** Their templates use only the prompt; other args are
    rejected rather than silently ignored.
14. **Fake CLI = one POSIX `sh` script** run as `/bin/sh fake-cli.sh <fixture> <out> …`. Every port
    can spawn `/bin/sh` on ubuntu CI and macOS; the fixture path and out dir travel as leading argv,
    so no env plumbing is needed; `#hang` uses `exec sleep` so killing the direct child is enough.
    Windows is not run (owner rule).
15. **Codex folder reads `CODEX_HOME`** (default `~/.codex`) — Codex honours it, and it makes the
    folder finder testable hermetically.

## Decisions pinned after the first ports (SPEC §7G.12)

Seven ports implemented §7G.1–§7G.11 and disagreed where the text was silent. The owner delegated
the calls (2026-11-03); each is now a §7G.12 rule with conformance cases. Why each went this way:

- **D1 fal: `COMPLETED` before `error`.** A finished job with a stray warning is still finished;
  the result fetch is the truth. Error is only fatal while the job is not done.
- **D2/D3 kind from the verb.** The caller asked for a video; the word and `MediaFile.type` follow
  the request, not whatever extension a provider returned. `mimeType` stays honest (from the file).
- **D4 `…` only when truncated, counted in code points** — one rule for every cap (200/300/400/1000).
- **D5 Downloads/input fetches are never `unavailable`.** 402/quota from a CDN or a user's file host
  says nothing about the provider account; falling through to another client would re-bill.
- **D6/D7 Canonical args only.** A style may map a provider-only name, but the router, `args()` and
  schemas speak the seven verbs' canonical args; any other key is rejected for every client — a
  client without `args()` gets every canonical arg, not every key.
- **D8/D9 Only image parts reach the model; no image ⇒ plain text content.** No empty arrays or
  placeholders, so Anthropic and every other style see the same `output` text.
- **D10 fal URL fallback** `<url>/<model>/requests/<id>[/status]` when the submit omits them.
- **D11 `models.<verb>` everywhere.** The table in §7G.4 already says it wins; the ports that ignored
  it for ElevenLabs transcribe / OpenAI speech+transcribe were the bug.
- **D12 Deadline ⇒ `timeout`, cancel ⇒ `cancelled`.** A caller's deadline is a timeout whoever set it.
- **D13 Missing-result texts** follow the style engine's `no result at <path>` / `no job id at <path>`.
- **D14 maxBytes texts and enforcement** independent of an injected HTTP client (read ≤ maxBytes+1).
- **D15 Grok raw JSON sorted** — the only portable byte form of a provider object.
- **D16 Fall-through re-checks required args** — *superseded by D19*: the first candidate is no
  longer special.
- **D17 CLI cwd is a realpath** — macOS `/var` ⇒ `/private/var`; argv equals `pwd -P`.
- **D18 tasks.md**: a port's 2.x is ticked only when its code **and** README section are done.

## Decisions from the live example runs (round 3, 2026-11-04)

- **D19 Skip, don't fail, on args a candidate cannot take.** Live: `createMedia([grok, openai])`
  with `video({prompt, seconds})` failed because Grok (prompt only) was first, although OpenAI could
  serve it. An arg the first client does not take is the same kind of fact as a missing key: this
  client cannot serve this call. So every candidate, the first included, is skipped with a reason
  and the joined `unavailable` error lists them all. Dropping args on fall-through is gone too — a
  silently ignored `seconds` is a different video. A key that is not canonical for the verb can
  never be served by anyone, so it stays an immediate plain `failed` error (D7).
- **D20 `path` on run files.** Hosts want the saved file, not a base64 copy; Go and Rust dropped
  it between `MediaFile` and `RunResult.files`. The path is carried port-internally; parts stay
  path-free (§1B).
- **D21 Usage only as reported.** OpenAI image tokens and OpenRouter's USD cost come back on the
  response; Grok's ACP `costUsdTicks` has no documented unit, so it goes in `raw` unconverted. No
  estimates, and no run-level sum yet (a named gap): `RunResult.usage` stays LLM tokens.
- **D22 Extension: URL first, then mime, `.bin` last.** JS saved an A2E `audio/x-wav` as `.bin`.
- **D23 Default User-Agent.** Rust's A2E download got a CloudFront 403 with no UA. One value for
  every request: `toolnexus/<version> (<port>)`; a client/style `User-Agent` header wins.

## Example contract (E1)

Every port ships one runnable media example with identical behaviour:

- **Client order:** `GrokMediaClient`, `CodexMediaClient`, `ElevenLabsMediaClient`, then
  `OpenAIMediaClient`, `FalMediaClient`, `A2EMediaClient` as fallbacks — each added only when its
  key (env var) or CLI (on `PATH`) is present.
- **Steps:** (1) image, prompt `a red apple on a white table`; (2) speech through ElevenLabs, voice
  from env `MEDIA_VOICE`, else the ElevenLabs default; (3) tool face: list `media.tools()` and call
  `generate_image`; (4) video through Grok only when `MEDIA_VIDEO=1`; (5) any paid video/talk only
  when `MEDIA_PAID=1`. Paid image clients use the smallest size and lowest quality.
- **Output per result:** client, verb, file path or URL, mime type, size in bytes, and usage when
  present.
- **Exit code:** non-zero if any attempted step errors; `0` with a hint (which env vars/CLIs to
  set) when no client is available at all.
- **Clojure:** runnable from the documented location; `EXAMPLES.md` fixed to match.

## Options manifest

`conformance/check_options_parity.py` fails on any group whose options file lacks an option, and
has no grace flag. A `mediaClientOptions` group therefore cannot be registered before the ports
implement it. The **last port PR** of this change adds it (task 10.2):

```json
"mediaClientOptions": {
  "_doc": "Media client config (SPEC §7G.4).",
  "files": {
    "js": "js/src/media.ts", "python": "python/src/toolnexus/media.py", "go": "golang/media.go",
    "java": "java/src/main/java/io/github/muthuishere/toolnexus/Media.java",
    "csharp": "csharp/src/Toolnexus/Media.cs", "elixir": "elixir/lib/toolnexus/media.ex",
    "clojure": "clojure/src/toolnexus/media.cljc", "rust": "rust/src/media.rs"
  },
  "options": [
    {"name": "model", "aliases": ["model"], "tier": "full"},
    {"name": "models", "aliases": ["models"], "tier": "full"},
    {"name": "key", "aliases": ["key", "apikey"], "tier": "full"},
    {"name": "url", "aliases": ["url", "baseurl"], "tier": "full"},
    {"name": "command", "aliases": ["command"], "tier": "full"},
    {"name": "loop", "aliases": ["loop"], "tier": "full"},
    {"name": "gather", "aliases": ["gather"], "tier": "full"},
    {"name": "httpClient", "aliases": ["httpclient", "fetch", "httptransport", "httpoptions"], "tier": "full"}
  ]
}
```

(If a port splits clients into several files, point `files` at the one holding the config type.)
The toolkit option `media` is already registered and unchanged.

## Open questions for the owner (do not block the ports)

1. (closed) Video/audio parts are kept out of the model context; images go in.
2. (closed) 429 keywords are `quota`, `credit`, `billing`, `balance`.
3. ElevenLabs `lyrics` (needs a composition plan) and OpenAI/fal video, ElevenLabs
   music/transcribe/cloneVoice, OpenAI speech/transcribe, A2E talk are pinned from docs, not live.
   One cheap live call each before release.
4. Grok session-folder lifetime: `gather.mode: "url"` points at a file Grok may clean up.
