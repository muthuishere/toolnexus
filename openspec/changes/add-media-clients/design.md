# Design — media clients

The design is ADR 0037 (`docs/adr/0037-media-clients.md`) plus the spike findings in
`spikes/media-clients/SPIKE.md`. SPEC §7G is the byte contract; this file records the decisions
taken while pinning it that the ADR left open, so port agents do not re-decide them.

## Decisions made while writing the contract

1. **Errors carry a status; `unavailable` is typed.** Not string-matched. Every port exposes a way
   to test for it (§7G.2 lists each). Function-face errors keep the ADR 0036 texts.
2. **`unavailable` is a closed list** (§7G.2): 402; 429 whose body says `quota`/`credit`; built-in
   client without a key (no request); CLI that cannot be started; Grok `zero data retention` /
   `usage limit`; Codex `usage limit` / `quota`. A missing key and a missing CLI are `unavailable`
   so that `createMedia([grok, fal])` on a machine without grok, or without `FAL_API_KEY`, just
   works.
3. **Routing wraps every all-unavailable outcome**, even for one client:
   `media: no client available for "<verb>": a: …; b: …`. One rule, no special case.
4. **Arg checks belong to the router**, against the first candidate's `args(verb)`, before any
   request or spawn. Fall-through *drops* args the next client does not accept: the request was
   valid for the first choice, and failing on the fallback would defeat the fallback.
   Style clients keep the ADR 0036 text `not supported by this style` (34 cases depend on it);
   every other client says `not supported by <name>`.
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
9. **Tool parts for local files are `data` parts.** §1B forbids paths in parts; `file://` would
   be a path in disguise. So downloaded/inline/CLI files reach the model as base64 (as `read`
   does today), and an `http(s)` URL is kept as `url` only in `url` mode. A host that does not
   want bytes in its transcript uses `gather.mode: "url"`. *Open question 1.*
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

1. Data parts for downloaded video can be large and are relocated to the model (§8A). Should media
   tool parts for local files be dropped from the LLM-facing message and kept only in
   `RunResult.files`? Contract today: sent, like `read`.
2. `unavailable` keyword list for 429 (`quota`, `credit`) is what the spike saw; providers may say
   `billing` or `balance`. Widening is a contract change (cases).
3. ElevenLabs `lyrics` (needs a composition plan) and OpenAI/fal video, ElevenLabs
   music/transcribe/cloneVoice, OpenAI speech/transcribe, A2E talk are pinned from docs, not live.
   One cheap live call each before release.
4. Grok session-folder lifetime: `gather.mode: "url"` points at a file Grok may clean up.
