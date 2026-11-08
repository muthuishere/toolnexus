# Changelog

All ports are versioned and released together; entries here apply to every port unless a port
is named. All eight ports are at tier `full` (js / python / golang / java / csharp / elixir /
clojure / rust) — see `conformance/options_manifest.json`, which is where a
port's tier is declared, never by the port about itself. Releases are cut as
GitHub Releases `vX.Y.Z` via `release.yml` (see `PUBLISHING.md`).

## Unreleased

### Round-boundary hook: append a message between rounds, or keep a stopped loop going (#192)

A new optional hook, `afterRound({ turn, final, text, messages })`, fires at the end of every loop
round — after the round's tool results are in the transcript and before the next request. Return
`{ inject: ["…"] }` and each string is appended as a user message: todo reminders, sub-agent
notices, budget warnings, without rebuilding the transcript in `beforeLLM`.

It also fires when the model **stops** (no tool call) as long as a turn remains: a non-empty
injection there continues the loop ("you still have 2 open todos"). `maxTurns` still bounds it.
Without the hook nothing changes. Run and stream, OpenAI and Anthropic styles, and the §7D agent
path (hooks are forwarded verbatim). It never fires on a pending halt, an error, `translate`, or an
ACP model source. Spec: SPEC.md §8 Hooks; change `openspec/changes/add-round-boundary-hook`.

**Classifier: an `openai` backend on OpenAI's Decisions API** (issue #157). `createClassifier({backend:
"openai"})` sends the same noul / choice / score questions to `POST https://api.openai.com/v1/decisions`
(`gpt-6-luna`) and returns the same `Decision` as every other backend. A front end such as jevx can now
offer an `openai` profile type without its own adapter. Every shared option applies unchanged (baseUrl,
headers, timeout, httpClient, retries, onError, requestParams, bodyTransform, cache, onMetric).
`OPENAI_API_KEY` goes only to `api.openai.com` over https (the #155 rule). Images go as `input_image`
data URLs. Caller keys are still never sent: questions travel in key order and answers are read back by
position. A `refusal` answer is an error, never a "no". Three things this wire cannot take fail before
any request: raw questions such as `box`, a one-option choice, and more than 400 questions.
**Not done:** decisions from this backend report `calibrated: false` until calibration is measured, so
thresholds tuned on Jev do not transfer. The Decisions API has no batch call, so `states().evaluate`
still fans out one request per state. There is no model catalogue (#158). OpenRouter's and Vercel's
Decisions routes have no preset yet; they work today with a `baseUrl` override and a named `apiKeyEnv`.
The wire was checked live against `gpt-6-luna` from Go, Python and Rust (yes/no, pick-one and
score). Every port's mock-kit suite also covers it through `tools/mock-llm`'s `/v1/decisions`.
Tracked in `openspec/changes/add-classifier-openai-decisions`. Shared fixture
`examples/judge/backends/openai-decisions.json`. Docs: an "OpenAI (Decisions API)" section on the judge
backends page and an openai recipe (yes/no, pick-one, score) in the judge cookbook, in all eight languages.

### Memory hierarchy and dreaming

A persona's memory can now stay small and stay true. **Everything is opt-in; a persona built
without the new options is byte-identical to before.**

- **`memoryIndexCap`** on `fromDir`: injects at most 200 lines / 25600 bytes of `MEMORY.md`
  (whole lines, a fixed notice when cut) so a diligent persona no longer pays for an ever-growing
  index on every turn. Disk is untouched.
- **`notes: true`**: the `memory` tool gains `note:<slug>` targets (`memory/<slug>.md`, never
  injected), `read` and a sorted `list`. Bad slugs (`../SOUL`, uppercase) are refused with no I/O.
- **`transcriptSearchTool(store)`**: a read-only tool that searches stored conversations
  (case-insensitive substring), so an agent can check what it believes against what happened.
- **`dreamer(dir, { store })`** and **`fromDir(dir, { dream: { everyMs, store } })`**: a built-in
  dream pass that verifies `MEMORY.md` against the transcripts, fixes or prunes claims, and
  records every change in `memory/log.md`. No `HEARTBEAT.md` to write.
- **A dream cannot delete correct memory without a trace.** The prompt removes a claim only when
  the transcripts contradict it; a claim they merely do not mention stays, because transcripts get
  pruned and silence is not evidence. The dreamer's `memory` tool refuses every action on
  `USER.md` (`dreamer: USER.md is off-limits`) and `replace`/`remove` on `note:log`
  (`dreamer: memory/log.md is append-only`), so a dream never edits the user model and can only
  add to its own audit trail. Not done: no snapshot is taken before a pass — keep the home in git.
- Transcript search folds case for ASCII letters only, now pinned by a fixture case: `É` in a
  message is not found by `é`. A port using Unicode lowercasing fails it.
- Memory writes are fsynced and serialised per file within a runtime, so concurrent agents no
  longer lose each other's entries.
- **`replace`/`remove` now change only the first match in every port.** python and java used to
  change every occurrence; a `remove "x"` there deleted every "x" in the file.
- The notes-off `memory` tool's description and schema are now identical in all eight ports.
  python, go, rust, csharp and elixir had drifted ("next session" vs "NEXT session", a missing
  `(with)`, no `target` description), so the text their models see changes slightly.
- **`replace` inserts `with` literally everywhere.** js expanded `$&`, `$1`, `$$` in the
  replacement (so a model writing "costs $&" got the matched text pasted in); all eight ports now
  insert the text exactly, pinned by a fixture case.
- Hardening from review, all eight ports: a slug with a trailing newline (`note:abc\n`) is
  refused (elixir and csharp accepted it); `list` skips symlinks and directories and no longer
  throws on a dangling symlink (js threw; most ports listed links); the write lock keys on the real
  path, so a home reached through a symlink shares one lock with its real path; `limit: null`
  is an error, not "no limit"; `tool_use` input keeps U+2028/U+2029 as-is (go and csharp escaped
  them); `startAgent` throws `startAgent: dreamer name collision: <name>-dreamer is already
  registered` instead of letting one agent silently shadow the other, and never leaves the
  persona's heartbeat running when the dreamer fails to start.

All eight ports; fixtures `examples/memory-hierarchy/` and `examples/dreaming/` (the "Mt. Moon"
case: a dream pass fixes a wrong note and the next session answers differently). Docs: new
"Dreaming" page. Not done: no cross-process write locking; no semantic search; clojure writes
are not atomic (koine has no rename) and its lock is in-process only — SPEC §7E now lists this as a
permitted per-port difference; clojure's note `list` uses a
real-path check instead of lstat (koine has none), so a FIFO or socket named `x.md` would be listed; on
a case-insensitive filesystem two spellings of one path may get two locks; a go `IndexCap` field of 0
means "use the preset" rather than an error; rust custom conversation stores must override
`supports_list()` to be searchable. Change: `openspec/changes/add-memory-hierarchy`.

**Fix: the persona `memory` tool no longer truncates a `MEMORY.md` / `USER.md` larger than 2 MB.**
The tool read the file through the same 2 MB cap used to build the system prompt, then wrote that
capped text back. So once the file went over 2 MB, a single `add`, `replace` or `remove` cut it down
to 2 MB on disk and wrote the truncation notice into it, silently losing everything after the cap.
SPEC §7E always said the cap leaves the on-disk file untouched. Affected: **js, java and clojure**;
the other five already edited the full file. Those three now do too, and seven ports (all but
clojure) now write atomically (temp file + rename), so a crash mid-write cannot leave a half-written
file; an existing file keeps its permission bits, a new one gets the usual `0666 & ~umask`. The cap still applies only when building the system prompt. Pinned by fixture key
`H3_memoryWriteOver2MBPreservesDisk` in `examples/persona-agent/fixture.json`. Not done here:
a `MEMORY.md` that is a symlink is now replaced by a regular file on the first write (the rename
replaces the link), so point the home dir at the real directory rather than linking the file;
clojure's write is not atomic yet (koine has no rename/move, so it needs adding to koine first);
concurrent writers are addressed by the memory hierarchy entry above.

**A mock model server: run any toolnexus client, classifier or media job with no key and no cost.**
`tools/mock-llm/mock_llm.py` is one stdlib-only Python file (`python3 tools/mock-llm/mock_llm.py
--port 8787`, base URL `http://127.0.0.1:8787/v1`, any key). It answers OpenAI chat and Anthropic
messages (tool calls when tools are offered, then a final text; SSE streaming), echoes attachments
back with their mime type, size and sha256 so a test can prove an image arrived, serves the System
One, Cloudflare and OpenAI Decisions classifier wires with deterministic answers (noul 0.95, choice
by hash, score within the rubric), serves a §7G media style covering all six verbs (async jobs with
a poll, tiny real PNG/WAV/MP4 files), and injects 429/5xx with `Retry-After` on request. `--script`
overrides replies. Sibling fakes cover every other source: an MCP server (stdio and HTTP, on both
the `server/discover` path and the frozen one), an A2A agent, HTTP tool endpoints, a one-shot CLI
for `createInProcessClient` and an ACP agent with permission, tool-call updates and
`session/cancel`, and a fake Grok/Codex media CLI (`fake_media_cli.py`; nothing on main drives a
media CLI yet, so it is validated against the unreleased `media-clients` branch). The in-process recipe fakes moved here from `site/recipes/in-process-client/fakes/`.
Every port now has an integration suite that runs its real client, classifier
and media engine against it, in its normal test command. Docs: cookbook "Run without a model".

- **Not done:** the ports' suites test the System One and Cloudflare classifier wires only; the
  OpenAI Decisions wire is served and self-tested, but the `openai` backend lives on the #157
  branch, so port-level Decisions cases land with that change. The existing in-process unit fakes
  stay as they are. ACP cancel is not exercised in elixir (#169) or clojure (#170), which lack it;
  clojure has no network streaming, so its suite has no streaming case. The benchmark's own mock (`benchmarks/mock_llm.py`) is unchanged.

**Faster requests and cold starts in python, js, java, csharp and rust** (`openspec/changes/speed-up-request-path`).
Measured against a no-framework floor, most of our per-request time was waiting, not computing: a new
TCP connection per LLM call, a thread hand-off per MCP stdio line, an OS proxy lookup per call, and SDK
code loaded only after the MCP child was up. Fixing those, against the shared benchmark (median p50
per request, before → after, interleaved with a clean HEAD build on one machine): python 1.35 → 0.96 ms,
js 3.28 → ~0.55 ms, java 2.89 → 2.54 ms (0.88 ms once the JIT is warm) with init 358 → 180 ms, csharp
init 93 → 62 ms. These numbers are from a busy machine; the standing page changes only after a quiet re-run.

- **python, js:** the default LLM transport keeps the connection open. A connection the server dropped
  while idle is replaced before use; a request that failed after it was sent is never re-sent, so a
  model call cannot be billed twice. js follows redirects from the target and leaves a host-installed
  undici dispatcher in charge. It steps aside, exactly as before, when a proxy env var applies or you pass
  your own transport/`fetch`. js uses it on Node only; Bun, Deno and browsers keep the platform `fetch`.
- **python, csharp behaviour change:** the proxy decision is cached per scheme and host, so a system
  proxy changed while the process runs is not picked up. Python's `urllib` already read it once per process.
- **java:** drops `jackson-databind` 2.x; Jackson 3, which the MCP SDK already needs, is the only JSON
  library. JSON output is byte-identical (new golden test). If you used the Jackson 2 that toolnexus
  brought in transitively, declare it yourself.
- **rust:** stdio MCP servers now run on our own client instead of rmcp: callers write straight to the
  child's pipe and one reader routes replies by id. A `tools/call` fell from ~30 to ~17 µs; steady-state
  p50 0.265 → 0.22 ms, ahead of AutoAgents and level with golang (golang still leads by ~10 µs under
  heavy load). `Client::run_ctx` now runs the turn on a tokio worker thread. Wire changes on the
  pre-2026 stdio path, all bringing rust in line with golang and SPEC §2: `initialize` offers
  `2025-11-25` (rmcp sent `2026-07-28`) with `clientInfo` `toolnexus`; caller cancellation now sends
  `notifications/cancelled`; a freshly spawned server's stderr passes through (§2.3); close kills the
  whole process group after 3 s; a tool with a non-object `inputSchema` no longer fails the whole list.
  rmcp remains for remote (HTTP/SSE) servers and the serve side; the `rmcp/transport-child-process`
  feature is dropped.
- **Not done:** golang needed nothing: it measured within ~8 µs of the floor. elixir and clojure are out
  of scope (#168 tracks clojure).

## 0.27.0 — 2026-11-08

**Rust: an ACP turn the agent never ends now fails after `permission_timeout`** (rust only, #124).
Every other port bounds the whole `session/prompt` turn by the permission timeout, as SPEC §7 says
("the turn's permission timeout still applies"). Rust bounded only the host's `on_permission`
callback, so an agent that stalled mid-turn, or never answered, hung the turn forever. It now fails
with `AcpError::PermissionTimeout` after `permission_timeout` (default 30 s) from the prompt being sent.
A turn that legitimately runs longer than that needs a larger `permission_timeout`, same as in Go.

**ACP: cancel a running turn in js, python, java and csharp** (#124). Before, only golang and rust could
stop an ACP turn; the other ports either ignored cancellation or dropped the turn without telling the
agent, which kept working. Now a cancelled turn sends the `session/cancel` notification, waits up to the
request timeout for the agent to end the turn, and raises the cancellation error; the process and
session stay warm for the next turn. Per port: js `generate(request, {signal})` /
`generateStream(request, emit, {signal})` rejects with an `AbortError`; python
`generate(request, cancel=event)` (a `threading.Event`) raises the new `ACPCancelledError`; csharp
honours the `CancellationToken` it already took; java reacts to the thread interrupt that
`LlmClient` cancellation already uses.

- **java behaviour change:** an interrupted turn now throws `LlmClient.CancelledException` instead of
  `IOException("...interrupted")`.
- **Not done:** elixir (#169) and clojure (#170). In js and python, cancelling a run of
  `createInProcessClient` does not yet reach the ACP turn — pass the signal/event to the ACP client
  yourself; wiring it through changes the in-process seam in every port and needs an OpenSpec change.

**Tool-scheduling tests are now mutation-checked in every port** (#129). Each scheduler was broken on
purpose (unsafe calls overlapping, the cap ignored, everything serial, results in completion order) and
the tests had to fail. Results-in-completion-order went unnoticed outside golang and csharp, so new tests
finish calls out of order and check each output stays in emitted order. No library behaviour changed.
Gap: in js the streaming modes are not covered by the reordering mutation.

- **`mcpHandler` with the 2026-07-28 opt-in no longer rejects legacy clients that send the 2026 version
  header** (issue #150, all eight ports). On a mount listing `2026-07-28` next to legacy versions, a request
  carrying `MCP-Protocol-Version: 2026-07-28` but no `Mcp-Method` header and no `_meta` version was routed to
  the 2026 wire and failed with HTTP 400 `-32020`; hosts had to rewrite the header. Such a request is now
  served by the legacy wire, which accepts that header value when `2026-07-28` is listed. A real 2026 client
  always sends `Mcp-Method`, so it is unaffected; 2026-only mounts are unchanged. `initialize` still answers
  the newest listed legacy version when asked for `2026-07-28` (that wire has no `initialize`; use
  `server/discover`), and SPEC §7C.2 and the Go `ProtocolVersions` doc now say so. No hint is added to the
  `initialize` result: the `io.modelcontextprotocol/` `_meta` prefix is reserved by MCP.

**Faster cold start for MCP in Java and C#, with no change in behaviour** (Java and C# only). Java no
longer builds the MCP SDK's default JSON-schema validator (about 220 networknt classes) for every
client. It is now resolved the first time a tool result is actually validated against an output
schema. The SDK's Jackson 3 mapper is built on first use of the legacy SDK path, not when `McpSource`
loads, and a config with only stdio servers no longer builds an OAuth token store. C# now creates
the shared MCP `HttpClient` on the first remote request, so a host with only stdio servers does not
load the HTTP/TLS stack at startup. Validation, auth and the wire format are unchanged. The second
process that the Java and C# stdio probe used to spawn for older servers (the larger cost at startup)
is gone too: #167 now reuses the probe child (see the entry below). **Not done:** Java still loads two
Jackson stacks.

- **Benchmarks (harness only, no published numbers changed):** the fake model server
  (`benchmarks/mock_llm.py`) now speaks HTTP/1.1 keep-alive, so frameworks that pool connections
  reuse them across LLM calls as they would against a real provider, instead of paying a TCP connect
  per call. It also lets two frameworks that previously could not run do so: smolagents (tool results
  arrive as role `user` and the agent stops only on a `final_answer` tool call; the mock now handles
  both) and AutoAgents (the mock sent `input_tokens` alongside `prompt_tokens`, which AutoAgents rejects
  as a duplicate field; usage now carries the OpenAI-standard keys only). The standing tables were
  re-run against it on 2026-11-08 (see the docs entry below).
**Faster load of pre-2026 stdio MCP servers: one process start instead of two.** Until now, when a
stdio server answered the `server/discover` probe with an error (every server older than MCP
2026-07-28 does), the client killed that process and started a second one for the old protocol. A JVM
server paid that twice (two spawns 152 ms apart in a Java profile; 35–55 ms in C#, ~20 ms in Clojure).
Now the same process carries on into `initialize`. A server that never answers the probe is still
killed and restarted after `min(timeout, 5 s)`, as before. Discover-first, the 2026 path and HTTP are
unchanged; SPEC §2.2a amended. Shared fixture `examples/mcp-2026/legacy-stdio/` counts process starts.
If a port cannot hand the open process over, it respawns as before (Rust logs a warning on stderr when this happens). Port coverage and any gap: `openspec/changes/reuse-stdio-discover-probe/tasks.md`.

- **Docs:** the docs-site hero video is re-cut for 0.26.0 (80 s, new narrator). It now covers eight
  languages including Rust, typed decisions (`createClassifier`), media generation, and serving the
  toolkit over MCP, A2A and ACP. Its benchmark claims come only from the Nov 2026 standing page: lowest
  in Python, Rust and Elixir, level in JavaScript and .NET, and behind in Go and on the JVM.

- **Faster start in JavaScript, fewer allocations per turn in Go** (javascript and golang only; no
  behaviour change). Importing `toolnexus` in JavaScript no longer loads code a host may never use: the
  MCP server transport and `@hono/node-server` load on the first `serve()` with an MCP profile or `asMcpServer()`, the remote MCP
  transports (streamable-HTTP, SSE) only when a remote server falls back to the SDK path, the
  MCP 2026 client on the first MCP load, and `yaml` on the first skill parsed. A bare import drops
  from 377 to 284 modules. Every public signature and export is unchanged (the built `.d.ts` files are
  byte-identical); `yaml` is loaded with a synchronous `require`, so `listSkills`/`loadSkills` stay
  synchronous. Not done: `buildMcpServer` is synchronous and returns the SDK `Server`, so the SDK
  server core still loads at import. In Go, the non-streaming OpenAI and Anthropic loops marshal the
  tools array once per `Run` instead of once per turn, when no `BeforeLLM` hook or `BodyTransform` can
  see or change it, and the MCP progress token is built without `fmt`. A test pins the request-body
  bytes, and they are unchanged. Not done in Go: the request body is still a `map[string]any`, because
  `BodyTransform` receives that map; the per-call `Metadata` map, the idle timer and the progress token
  stay, because each is observable (returned to the caller, the §2 idle timeout, and sent on the wire).
- **Docs / benchmarks:** the Nov 2026 standing page gains native-tool rows for toolnexus in Python,
  JavaScript, Java and C#, so .NET is finally compared like for like with Microsoft.Extensions.AI and
  Semantic Kernel (which only run native tools): toolnexus native is lowest at 0.59 ms p50 with the lowest
  init and RSS, a tie with Semantic Kernel inside noise. It also adds a skills row against Spring AI 2's
  `SkillsTool` (spring-ai-agent-utils 0.13.0) over the shared `hello-world` skill: a latency tie with our
  `full` config, at about 30% of Spring's memory. Measured 2026-11-08 on a busy machine and kept apart from
  the 2026-11-07 rows; the C# MCP figure from that sitting is too noisy to claim. No Python or Java
  competitor has a native row in the harness yet.
- **Docs / benchmarks: quiet re-run of the Nov 2026 standing page** (2026-11-08, every row started at
  1-minute load < 4, keep-alive mock). All per-language tables now come from one sitting of the full suite,
  with toolnexus `main` (the 0.27.0 candidate) and smolagents and AutoAgents, which run for the first time. A
  new table puts 0.26.0 and `main` side by side per port: MCP cold init drops 14–20 ms in Python, JS, Go,
  Elixir, Clojure and Rust and 14 ms in C# (the stdio probe child is reused, #167; JS/Go lazy loading, #166;
  C# lazy `HttpClient`, #171), and per-request latency is unchanged. Where we still lose: Eino in Go,
  LangChain4j on the JVM, AutoAgents in Rust (new, by 8%), and C# MCP cold init against Microsoft Agent
  Framework. Java did not show #171's cold-start gain in this run (init 462 vs 413 ms, one sample per
  process), so the page does not claim it. .NET MCP is now ahead of Microsoft Agent Framework (0.52 vs
  0.62 ms). Clojure remains the slowest port; its fix is deferred to issue #168. Raw rows are in
  `benchmarks/results-2026-11.json` under `run: 2026-11-08-quiet`; earlier sittings are kept.

**Security: the classifier no longer sends a default API key to a host that is not its own** (issue #155).
Until now the classifier read `TYPESAFE_API_KEY` (or the backend's variable) and sent it as a bearer
token to whatever `baseUrl` pointed at, so a `custom` backend, or any backend with a `baseUrl` override,
leaked the key to a self-hosted or third-party server whenever the variable was set. It now follows the
§8 client rule: a caller-named `apiKeyEnv` goes where the caller says; the backend's own variable goes
only to that backend's own https host (`api.typesafe.ai`, `openrouter.ai` and subdomains,
`ai-gateway.vercel.sh`, `api.cloudflare.com`); `custom` has no default key. Anything else sends no
`Authorization` (no error: keyless servers stay legal, and `headers` still work). All eight ports
carried the same flaw. **Upgrade note:** a self-hosted endpoint that relied on the implicit
`TYPESAFE_API_KEY` must now name `apiKeyEnv` (or send a header). Shared fixture
`examples/judge/backends/key-routing.json`.

**Cloudflare classifier errors now say why** (issue #156). A failed Workers AI envelope reports
`classifier: cloudflare: envelope not successful: 2021 Insufficient balance` (`errors[0]` code and
message, redacted and capped like any backend cause) instead of the fixed text, so a missing AI Gateway
balance is distinguishable from a bad model id. All eight ports; fixture `cloudflare-replies.json`.

Change: `openspec/changes/fix-classifier-key-and-errors`.

**A declined durable resume now tells the model why** (issue #125). Resuming a halted run with
`answerDeclined(id, reason)` through `runWithAnswer` / `askWithAnswer` used to fill every call's slot
with "no result supplied on resume for <name>", so the reason the human gave never reached the
model. Each outstanding call now gets the error result `relay declined for <name>: <reason>` (reason
defaults to `declined`), the same text the inline `waitFor` path already produced, so an answer means
the same thing whether the host waited inline or durably. SPEC §10; fixture
`conformance/resume_decline/cases.json`. Only golang and rust ship the durable resume entry point, so
only they were affected and fixed; the other six ports have no durable resume yet and must replay the
same fixture when they add one. Change: `openspec/changes/archive/2026-11-07-fix-resume-decline-reason`.

**Oversized skill descriptions are now reported** (issue #84). The skills catalog sends each
`description` in full on every turn, and nothing told the author when one was huge: a 4,443-character
description is cut to ~1,536 by Claude Code but paid in full here. The loader result and the
`listSkills` inventory now carry `warnings`, next to `skipped`: one
`{ location, reason: "oversized-description", chars }` per skill whose description is longer than
1,024 Unicode code points (the published Agent Skills limit; exactly 1,024 does not warn). Each port
also logs it through the channel it already uses for duplicate skill names, never stdout. Nothing is
truncated: the catalog and the `skill` tool output are byte-identical to before. All eight ports;
fixture `conformance/skill_description/`. Not done: the threshold is fixed, not configurable.
Change: `openspec/changes/archive/2026-11-07-add-skill-description-length-warning`.

**Docs: a new "Where toolnexus stands — Nov 2026" page** (Benchmarks & comparison). It has a feature
matrix re-checked against official docs on 2026-11-07, covering MCP client/server, MCP 2026-07-28, A2A,
skills, ACP, judges, media and languages. Cells we could not confirm say *unverified*. It also has new
fake-model overhead benchmarks with pinned versions for 53 framework configurations in eight languages,
including Rust (vs rig and swiftide) and new rows for Microsoft Agent Framework, Agno, OpenAI Agents
JS, LangGraph.js, Google ADK Go/Java, Genkit Go and Koog. The page says where we lose: Eino in Go,
LangChain4j on the JVM, a tie in .NET, and Clojure as our slowest port. It ends with a ranked list of
gaps. Raw results are in `benchmarks/results-2026-11.json`, and the August `results.json` is kept as
it was. The page also lists what we could not run and why: smolagents and AutoAgents fail on the
shared mock's wire format, and Google ADK TS has no OpenAI-compatible model. The July comparison page
now points to the new one.

## 0.26.0 — 2026-11-06

**Classifier backends: one System One classifier for TypeSafe, OpenRouter, Cloudflare, Vercel or
your own server, with images, a cache and reply validation** (change `add-classifier-backends`,
ADR 0038; every default is proposed and awaits the owner's confirmation).

- **`backend`** is now `typesafe | openrouter | cloudflare | vercel | custom`. Cloudflare's `/ai/run`
  envelope is built in (no more hand-written `custom` evaluator); `custom` is a self-hosted System One
  endpoint and must declare `baseUrl` and `supports`. The table is data you can read and override.
  `${ENV}` in `baseUrl` expands at call time and never appears in an error.
- **Images in the state.** Put an image content part anywhere in the state and it travels as a
  TypeSafe `assets` entry (Cloudflare Clef: `input.images`), with the
  `TypeSafe-Danger-Zone-Images` header only where the backend needs it. PNG/JPEG, 16 MiB, 280–1568 px
  and 32 MiB per request are checked before sending; a backend that cannot take images fails with
  `ClassifierUnsupported` before the network. Images are never resized or described as text.
- **`classifier.state(v).evaluate(qs…)`** validates the reply fail-closed; **`states(…).evaluate`**
  returns one decision-or-error per state, so one bad item no longer loses the rest.
- **Opt-in cache** (`cache` + `MemoryCache`): off by default, stores only valid replies, keyed per
  request on the model the server actually served, with key bytes specified so a cache written by
  one port is readable by another. Per-question splitting is not done: whether System One calibrates
  questions jointly is unproven (test plan in the change's design).
- **Helpers:** `validateDecision`, inclusive `Thresholds` (jevx's 0.8/0.2/0.6, alongside the
  unchanged `Bands`), `chunkQuestions`. `classifier.evaluate` events now carry `questions`,
  `questionsHash` and `cached`.
- **Raw questions/answers:** send a System One question type the library does not model yet (e.g.
  Jev 1.14 `box`); an unknown answer type now decodes to a raw answer instead of failing the whole
  decision.
- **Changed defaults:** `openrouter` now defaults to `typesafe/jev-1.14` (was `typesafe/jev-1.13`).
  **`style: "llm"` is deprecated** — it keeps working unchanged for at least one minor release.
  Source-level notes: rust `Decision` gains a `cached` field (struct literals need `cached: false`) and
  `ClassifierError` gains a private second field (build it with `ClassifierError::new`); java code
  that switches exhaustively over `DecisionAnswer` needs a `RawAnswer` case.

Not done: the Decisions API (second wire), multipart image upload, Vercel and Cloudflare-Jev
images (not proven live, so refused), per-question caching, and the jevx cutover gate (ADR 0038
D8). Tracked in `openspec/changes/add-classifier-backends`.

**Docs: the in-process client recipes now cover all eight ports and use the 0.25.0 ACP seam.** The
ACP agent recipe is now one short file per language on `toolkit.asMcpServer()` + the ACP client
(`loadACP` / `load_acp` / `LoadACP` / `AcpClient.start` / `AcpClient.StartAsync` /
`Toolnexus.Acp.connect` / `toolnexus.acp/connect` / `AcpClient::load`) + a streaming
`createInProcessClient`, instead of a hand-written JSON-RPC driver; the toolkit is reached with a
bearer token instead of an open port. Every recipe (overview, `asMcpServer`, ACP agent, one-shot
CLI, and a new "Extend it" recipe with isolation `_meta`, `model` passthrough and an allow-only
`onPermission` policy) now has JavaScript, Python, Go, Java, C#, Elixir, Clojure and Rust tabs.
The tabs are generated from the files that were run (`site/recipes/in-process-client/`, synced by
`site/scripts/sync-recipes.mjs`, `--check` fails on drift), each run on 0.25.0 against a bundled fake
ACP agent and fake CLI, and the ACP recipe live against `@agentclientprotocol/claude-agent-acp@0.100.0`
in JavaScript, Python, Go, Java, C# and Rust. The pages also document the per-port default turn
timeouts (Python 5 s, Java and C# 10 s, others 30 s), which are too short for a real agent; the
recipes set 300 s. The Java and C# README "Agents via ACP" snippets now compile (missing
`acp` package import / `using Toolnexus.Acp;`; Java also needed the checked `IOException` wrapped
and a turn timeout). Not done: Elixir and Clojure were verified against the fake agent only, not live.

- **MCP 2026-07-28 interop matrix: every port's client against every port's server.** `interop/mcp2026/matrix.sh` builds a small client runner and server runner per port (js, python, golang, java, csharp, elixir, clojure, rust) that speak one shared 11-scenario list (discover, MRTR input/confirm/pending, skills, tool search, OAuth with a pre-registered id, scope step-up, CIMD, no-identity error, legacy fallback) against each port's test-only dev authorization server. 64/64 pairs pass; a new CI job runs it hermetically. `interop/mcp2026/keycloak.sh` (`KEYCLOAK=1`, docker, local only) runs the OAuth login and step-up for all eight clients against a real Keycloak 26.8.1; it never runs in CI. Contract: `interop/mcp2026/README.md`. This replaces the Go-only `run.sh`.
- **fix(python):** a remote MCP tool result carrying `structuredContent` was rendered with `json.dumps` defaults (`{"text": "hi"}`, non-ASCII escaped), where SPEC section 2 requires `JSON.stringify` (`{"text":"hi"}`). It now matches the other ports, on both the 2026 and fallback paths.
- **fix(java):** the pre-2026 fallback posted to `<origin>/mcp` whatever the configured URL, so a remote server mounted at any other path (for example `/legacy/mcp`) could never be reached once discovery fell back. It now uses the configured path. The SSE transport builds its URL the same way and is probably affected; not changed here.

**Not done:** live GitHub login and tool call per port (needs an interactive browser consent on the OAuth App, owner-run). Clojure's loopback callback and dev authorization server cannot answer a bare GET with a query string (koine does not expose it), so the Clojure runner performs that step itself. Tracked in `openspec/changes/upgrade-mcp-2026-07-28`.

**Fixed: `GET /mcp` on `toolkit.serve` is refused with 405 and `Allow: POST` in every port.** The
serve MCP profile is stateless and has no server-initiated SSE stream, but Go and JavaScript passed a
`GET` to their SDK, which answered `200`, and Clojure answered it as a JSON-RPC call (`200` with an
error body). Python, Java, C#, Elixir and Rust already
answered 405; Java, C# and Elixir did so without the `Allow: POST` header the status requires, now
added. The rule is pinned in `SPEC.md §7C` and in a new shared case file,
`conformance/serve_mcp/cases.json`, which all eight suites replay, so the next drift fails a test
instead of hanging a client. `POST /mcp` behaviour is unchanged.

**Docs: removed the stale "Python serve gap" warnings** from the In-process client overview and the
ACP agent recipe. Both gaps (`mcp={}` exposing no tools, `GET /mcp` hanging) were fixed in 0.25.0
by #147; the Python serve snippet now uses `mcp={}` and was re-run (tools listed, `add` called,
`GET /mcp` ⇒ 405). Not done: the live Claude ACP adapter run in Python has not been repeated.

**Tests: six timing-flaky tests made deterministic (test-only, no behaviour change).** JS media
conformance `async_timeout`: a poll aborted at the deadline could reach the fake server late and
shift the next pass's script; the fake now drops requests from closed connections. C#
`LoadMcpOnServerReportsFastServerFirst` and Elixir "MCP on_server reports the fast server first": the
slow server raced a `sleep 1`; it now waits for a gate file `on_server` writes after reporting the fast
one. C# `ShortGraceKillsTermIgnoringJobAndDescendantFast` could read a half-written pid line; it now
waits for complete lines (its bound was also widened 1000→1500 ms, still under the 2000 ms grace).
Python `test_idle_timeout_progress_extends_call` now has a 40x margin between progress ticks and the
idle budget. Each passed 20/20 runs under parallel load. Java `CliClientTest` is not on main (only on
the unmerged `feat/cli-acp-client` branch), so nothing changed there.

**Go: one name for the MCP 2026-07-28 version constant.** 0.25.0 shipped the same string twice,
`McpProtocolVersion` (client, #142) and `MCPProtocol2026` (handler, #143). `MCPProtocol2026` is the
canonical name; `McpProtocolVersion` stays as a `// Deprecated:` alias of it, so nothing breaks. golang
only: no other port exports two names for it in the same namespace (Python, Java, Rust and Clojure
keep a client-wire constant in their MCP client module beside the handler's, which is module scoping,
not a duplicate public name; JS and C# keep the client one internal; Elixir's are private).

## 0.25.0 — 2026-11-06

**Docs: an "In-process client" section with copy-pasteable recipes for driving coding agents and
CLIs yourself.** Driving a vendor CLI or ACP agent is not something toolnexus maintains as library
surface; agents change their flags too often. Instead the new section shows how to do it on the
public API that already exists: `createInProcessClient` (`generate`, or `GenerateStream` in Go)
plus `toolkit.serve` with the MCP profile. Four pages: an overview, a complete ACP agent recipe
(spawn, `initialize`, `session/new` with your toolkit as an MCP server, `session/prompt`, streamed
`agent_message_chunk`s), a one-shot CLI recipe (`claude -p`, `codex exec`: render the request, run
the process, parse text or tool calls), and "Extend it" (your own agent, isolation, model selection
over `configOptions`, permission policies). Recipes are JavaScript, Python and Go, each run against
a fake agent/CLI and live against Claude Code; per-agent isolation and model notes are labelled
with the version and date they were verified on. The section is also published as plain markdown
for agents at `/_llms-txt/in-process-client.txt` (listed in `llms.txt`). The research behind the
notes is in `docs/references/acp-agent-isolation-2026-11-05.md`. Not done: two Python serve gaps found while verifying are
documented, not fixed (`mcp={}` exposes no tools; `GET /mcp` gets no response, which hangs the
Claude ACP adapter).

### MCP 2026-07-28 client: hosted servers, OAuth login, remote skills and tool search (all eight ports)

**toolnexus now speaks MCP 2026-07-28 to every server first, and can log in to OAuth-protected ones.**
Until now each port drove its MCP SDK (or, in Elixir and Clojure, our own client) on the 2025 protocol,
and an OAuth-protected server meant pasting a bearer token into `headers`. Hosted servers such as
GitHub's already speak 2026-07-28, and most of them ask for OAuth.

- **Discover first, nothing to configure.** Each server is asked `server/discover` before anything else.
  A 2026 server is used through toolnexus's own client (no SDK) over stdio or Streamable HTTP. An older
  server answers that with an error, an HTTP 400/404/405/5xx or silence on stdio (5 s), and gets
  **exactly the client you had before**, frozen. `mcp.json` files keep working unchanged, and tool names,
  result mapping, filters, timeouts and status are the same on both paths.
- **Questions a tool asks mid-call** (2026 `input_required`) arrive through your `waitFor` as §10 requests:
  form → `input`, URL → `authorization`. With no `waitFor` the call returns `{status:"pending"}` and resumes
  at the round it paused on. A `tools/call` is never re-sent after a timeout, 5xx or dropped connection,
  because the server may already have run it.
- **OAuth login** (new optional `auth` per server): protected-resource and authorization-server
  discovery, PKCE S256 with `resource`, `state`/`iss` checks, refresh (one request shared by concurrent
  callers) and step-up on `insufficient_scope`. The client identity is a pre-registered `clientId` or a
  Client ID Metadata Document. **Dynamic Client Registration is deliberately not supported**; without an
  identity you get an error naming the issuer and `auth.clientId`. Two modes: `auto` opens the browser
  and takes the callback on `127.0.0.1`; `pause` hands your host a §10 `authorization` request and you
  answer it with `data.callbackUrl`. New load/toolkit options `tokenStore` (default in-memory) and
  `openUrl`. Tokens, codes and secrets never reach logs or error text.
- **Skills over MCP** (SEP-2640): a server that offers skills adds them to the same `skill` tool as your
  local ones, with digest and path-containment checks.
- **Lazy tool search** (`"toolSearch": true`, optional `"description"`): the toolkit exposes
  `<server>_search_tools` and `<server>_call_tool` instead of every tool, for servers with hundreds of
  tools. Your `tools` filter and `disableTools` apply first.

Contract: SPEC §2 (2.2a fallback rule and resolved questions), §2A.1/§2A.1a, fixtures `examples/mcp-2026/`;
change `openspec/changes/upgrade-mcp-2026-07-28`.

**Not done yet** (tracked in that change and #134):
- **The server side.** `serve /mcp` still speaks the pre-2026 profile; the 2026 server, its `auth`
  (PRM, 401/403, JWT via JWKS, principal) and the in-process dev authorization server come in a follow-up
  on top of the MCP handler from PR #140. When that lands it is **BREAKING**: old-era clients will no
  longer be able to call `serve /mcp`. The MCP SDK dependencies stay for now because they power the frozen
  fallback; golang keeps `mark3labs/mcp-go`.
- **OAuth on the frozen fallback**: JS, Python, Java and C# attach the bearer per request and re-run the
  flow on a 401. Rust (rmcp), Elixir and Clojure send the token as a fixed header at connect, so a token
  that expires mid-session is not refreshed on an old server.
- **`resourceTools()`** (`list_mcp_resources` / `read_mcp_resource`) sees fallback servers only.
- No progress notifications or `nextCursor` pagination on the 2026 path; roots, sampling, logging and the
  Tasks extension are out of scope.
- Elixir and Clojure re-encode a search hit's `inputSchema` (keys sorted) instead of passing the server's
  bytes through. Clojure resumes only one input request per MRTR round without a `waitFor`, and its
  `auto` callback page needs a browser (koine gives a server no query string).
- Not yet verified: the 8×8 client×server interop matrix on the shipped code, and live Keycloak/GitHub
  logins (the Go spike passed both).

**MCP 2026-07-28 on the server side: `MCPHandler` speaks the new wire when you opt in, with OAuth metadata and skills over MCP, in all eight ports.** The 2026-07-28 client (change `upgrade-mcp-2026-07-28`) could talk to GitHub's hosted server but not to a toolnexus server: `MCPHandler` refused 2026-07-28, so a toolnexus client always fell back to the old wire. Now add `"2026-07-28"` to `ProtocolVersions` and the same handler, with the same hooks, serves it:

- **The stateless wire.** `server/discover`, header validation (`-32020` header mismatch, `-32022` unsupported version, checked before auth), `resultType` and `_meta` serverInfo on every result, `tools/list` sorted by name with `ttlMs`/`cacheScope`. No `initialize`, no session id, no GET stream. List both `"2026-07-28"` and a 2025 version to serve old and new clients on one mount; without the opt-in nothing changes (an old-only handler answers a 2026 `server/discover` with HTTP 400, which the 2026 client takes as its cue to fall back).
- **Confirm and suspensions without elicitation streams.** A `BeforeCall` confirm becomes an `input_required` round the client answers through its `waitFor`; a tool that returns a §10 `Pending` request gets the answer back as `ToolContext.Answer` on the retry. `BeforeCall` still runs on every round, so a forged `requestState` cannot get past a deny.
- **Skills over MCP (SEP-2640).** `ListSkills(ctx)` publishes skills (`skills/list`, `skills/get`, `resources/read` of `skill://` URIs with sha256 digests and traversal-safe URIs); `LoadMCPSkills(dir)` reads a skills folder. A toolnexus client loads them into its `skill` tool byte-identical to the local copy.
- **OAuth for the server.** `MCPServerAuth{Issuer, Scopes, Verify?}` gives you an `Authenticate` hook (bearer token, RS256 JWT checked against the issuer's JWKS — signature, `iss`, `aud`, `exp` — or your own `Verify`; per-tool scopes; exact 401 / 403 `insufficient_scope` challenges) and the RFC 9728 metadata handler to mount at `/.well-known/oauth-protected-resource`. The verified `Principal` is what `IdentityFrom(ctx)` returns. Any `Authenticate` hook can now return an `MCPAuthError` to pick 401 or 403 and the challenge.
- **Bring your own authorization server.** The auto-approving dev authorization server the suites and the interop run use (pre-registered and Client ID Metadata Document clients, PKCE, refresh, no dynamic client registration) is test tooling in every port's test tree, not library API: nothing named `StartMCPDevAuthServer` / `McpDevAuthServer` / `start_mcp_dev_auth_server` ships, so a server that approves everyone cannot end up in production by import.
- Proven end to end by `interop/mcp2026/run.sh`: the Go 2026 client against the Go server — discover, MRTR input, confirm, skills, tool search, OAuth with a pre-registered client id, with a CIMD URL and with a scope step-up, and the fallback against a handler without the opt-in. Shared wire cases: `conformance/mcp_handler/cases.json` (`mcp2026_*`).

Not done: `Serve(addr, {mcp})` still serves the pre-2026 profile (switching it is the BREAKING step change `upgrade-mcp-2026-07-28` describes, left for a decision); per-tool scopes are enforced only on the 2026 wire (older wires check `"*"` only); a multi-round tool sees only the latest round's answer; the cross-port interop matrix (every port's client × every port's server) and the Keycloak live run against the server are not run here; the site API pages for the new symbols are not written. Tracked in `openspec/changes/add-mcp-2026-server-handler`.

**MCP serve you can mount yourself, with auth, per-request tools and a confirm gate, in all eight ports.** `Serve(addr, {mcp})` owns its port and shows everyone the same tools, so a host that wanted
an MCP endpoint behind its own login had to write its own MCP server (#139). Now
`tk.MCPHandler(cfg)` returns an `http.Handler` to mount on your own mux, behind your own TLS, CORS
and OAuth, with plain hooks:

- `Authenticate(r)` runs on every request; an error is HTTP 401 with your `WWW-Authenticate` header.
  What it returns reaches every hook and tool via `IdentityFrom(ctx)`.
- `ListTools(ctx)` decides the tools per request (they can be built at runtime). A `tools/call` for
  a name not in that list is "tool not found" and never runs. `NotifyToolsChanged()` tells connected
  clients to re-list.
- `BeforeCall(ctx, tool, args)` returns allow, deny or confirm. Confirm asks the user through MCP
  elicitation when the client supports it; otherwise the caller gets your own result (for example
  "re-call with this nonce") or a plain "confirmation required" error.
- `Tool.Annotations` (`title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`)
  go out unchanged; `OnCall` reports identity, duration, error and result size for metering;
  `ServerInfo(ctx)` brands `initialize` (now with `title`); resources and prompts hooks; your own
  `SessionIDs`, or `Stateless` (optionally with a fixed `StatelessSessionID` for clients that expect one).
- **Structured results.** A tool can return `ToolResult.Structured` and declare `Tool.OutputSchema`;
  MCP serve sends them as `structuredContent` and `outputSchema`, with a JSON text block when the
  tool sets no text. `ToolResult.Meta` goes out as the result's `_meta`, and a request's
  `params._meta` is readable in hooks and tools via `MCPRequestMetaFrom(ctx)`. All optional:
  tools that do not set them produce exactly the same output as before, on `Serve` too.
- **Protocol versions you can trust.** `ProtocolVersions` per handler; the default is 2025-06-18 only.
  2025-11-25 can be turned on (the handler implements its one server-side rule change: bad tool
  arguments come back as an error result, not a protocol error). 2025-03-26 is not accepted (it allows
  batches, which the handler rejects) and 2026-07-28 waits for #134 — listing them does nothing.

Every port ships it in its own native handler shape: Go `http.Handler`, JS Node request listener
(`toolkit.mcpHandler`), Python ASGI app (`toolkit.mcp_handler`), Java JDK `HttpHandler`
(`toolkit.mcpHandler`; give the `HttpServer` a multi-threaded executor), C# `McpHttpHandler` over
`HttpListenerContext` or a host-supplied `McpHttpExchange` (`toolkit.McpHandler`), Elixir Plug
(`Toolnexus.mcp_handler/2`), Clojure Ring handler (`toolnexus.mcp-handler/mcp-handler`; SSE bodies are
lazy seqs, so the Ring adapter must stream seq bodies), Rust hyper `Service` (`toolkit.mcp_handler`,
`serve` feature). A shared fixture, `conformance/mcp_handler/cases.json`, pins the wire and every
port's suite replays it.

Port notes: outside Go, `beforeCall` receives the parsed arguments object rather than raw JSON bytes.
The Java, Elixir and Clojure GET streams send a `: keepalive` comment every 15 s (Java and Elixir) or
end on session DELETE (Clojure), because those servers cannot see a client disconnect. In Elixir a
confirm timeout reads "confirmation failed: timeout"; the other ports use Go's "context deadline
exceeded". The official MCP Java SDK 2.0.0 client sends `MCP-Protocol-Version: 2025-11-25` on later
requests even after it negotiated 2025-06-18, so it needs `supportedProtocolVersions(List.of("2025-06-18"))`
against a default handler.

**Rust: a source change if you build `Tool` with a struct literal.** `Tool` has two new public fields,
`output_schema` and `annotations`. A literal that names every field no longer compiles until you add
`output_schema: None, annotations: None` (or end it with `..Default::default()`). The builders
(`with_output_schema`, `with_annotations`) are unaffected.

toolnexus has no tenant, role or storage concepts here: those stay in your hooks (ADR 0039).
`Serve(addr, {mcp})` is unchanged apart from the optional structured fields. **Not done:** when you
run several replicas, elicitation answers need sticky routing. The docs-page examples are run in CI
for Go, Python and Java only; the JS, C#, Elixir, Clojure and Rust pages are not run yet.

**Let any coding agent call your toolkit, through three vendor-neutral pieces (all eight ports).**
toolnexus does not ship a client per coding agent: which binary, flags, login and quirks an agent
needs changes on the vendor's schedule, so those live as **recipes in the docs**, written on top of
what the library now provides (ADR 0041, SPEC §8C):

- **Your in-process model can stream.** Give `createInProcessClient` a streaming generate —
  `generateStream(req, emit)` (Go `GenerateStream`, python `generate_stream`, elixir
  `:generate_stream`, clojure `:generate-stream`, …) — and `stream` yields each text you `emit` as it
  happens. Before, only Go had this and every other port refused `stream` on an in-process client.
  A new **`thought`** stream event carries model reasoning; `tool_call`/`tool_result` events with
  `via: "acp"` report tools an agent ran itself — passed to you, never executed by your loop. A plain
  `generate` still refuses to stream, because one chunk pretending to be a stream is the bug this
  prevents. Clojure has no stream API; there the events go to `run`'s `:on-event` as emitted.
- **The ACP client passes your configuration through untouched.** `loadACP` now sends the
  `mcpServers` you give it verbatim (it always sent `[]` before), `sessionMeta` as `session/new`
  `_meta`, `clientCapsMeta` as `initialize` `clientCapabilities._meta`, and passes `model` through
  (`session/set_config_option`, then `session/set_model`). It streams every `agent_message_chunk` the
  moment it arrives in every port (was Go only), fails a request as soon as the agent exits instead of
  waiting out the timeout, answers agent requests it does not implement with `-32601` so the agent
  never hangs, declares `terminal:false`, and tears down the agent's whole process group on `close`.
- **`toolkit.asMcpServer()` → `{url, token, close}`** serves your toolkit (MCP servers, skills,
  your own tools) on a loopback port behind a fresh bearer token, so an agent you launch can call it
  and other local processes cannot. You put `url` and the token into the agent's `mcpServers` entry.

Pinned without any vendor binary in `conformance/acp-client/cases.json` (streaming that is really
incremental, verbatim passthrough, agent exit, unknown requests, the server's 401/404/405 rules).

Not in the library, by decision: per-vendor styles or enums, a one-shot CLI transport for agents
without ACP (write a `generate` that runs your CLI — that is what the seam is for), vendor
permission allow-lists or prompt hints, a stdio-to-HTTP MCP bridge, model lists. The default
permission policy is unchanged (first allow option); a docs recipe shows how to allow only your
toolkit's tools.

**Fixed (python only): `toolkit.serve(mcp={})` now exposes the toolkit's tools, and `GET /mcp` answers
`405` instead of hanging.** An empty MCP serve profile was treated as absent, so `/mcp` was mounted
but advertised no tools; it now means "present with defaults" (`name: "toolnexus"`, every tool), as
in every other port and `SPEC.md §7C`. `GET /mcp` used to hold the connection open forever, which
hung clients that probe it for a server stream (e.g. the Claude ACP adapter, whose `session/new`
waited on it); the stateless profile offers no stream, so it now returns `405` (`Allow: POST`),
matching Java, Elixir, C# and Rust. Other ports were not affected by either bug.

## 0.24.0 — 2026-11-04

**Media generation: images, video, speech, transcription, voice cloning and talking-head video,
from a JSON style instead of provider code.** Until now toolnexus could carry an image or audio
part but not make one; every provider (a2e, Replicate, fal, OpenAI images/TTS, ComfyUI…) made you
hand-write a request and a polling loop, per endpoint, per language. Now one engine does it:

- **Typed verbs for your code** — `createMedia(style)` gives `image`, `video`, `speech`,
  `transcribe`, `cloneVoice` and `talking`, with normalised arguments (`prompt`, `seconds`,
  `voice`, …) and a result of `{files, text?, jobId?, status}`.
- **Tools for your agent** — the same verbs become `generate_image`, `generate_video`,
  `generate_speech`, `transcribe_audio`, `clone_voice` and `talking_video`; `createToolkit({ media })`
  registers them. Tool names never change when you switch provider, so prompts and skills keep working.
  Media tools are concurrency-safe: two media calls in one response run side by side (up to
  `maxToolConcurrency`) instead of waiting for each other like unlabelled tools.
- **Long jobs just work** — async styles start the job and poll for you (default every 5 s, up to
  10 min), report progress, honour cancellation, and keep polling through statuses a provider
  never documented.
- **Providers are data** — a style is a JSON file (endpoints, dot-paths, `${ENV}` headers, an
  optional error envelope). `examples/media-styles/a2e.json` ships first, live-verified for image,
  video and speech. The library names no vendor.
- **`RunResult.files`** — every image, video or audio a run's tools produced, in order, tagged with
  the tool name and call id; `toolCalls[].parts` carries them per call. The answer text never gets
  markup — you decide how to render. An agent with a completion gate keeps the files of every
  attempt, exactly as it keeps their tool calls.
  It is not media-only: image or file parts returned by MCP tools and by built-in file reads land
  in `RunResult.files` too, and each entry keeps the part's `name` when it had one.
- **Bounded, safe requests** — every request (start and each poll) is bounded by the time left
  until the job's deadline (`timeoutMs`, counted from the start; sync verbs default to 10 min), so
  a server that accepts and never answers can no longer hang a call. Style headers (your API key)
  are never sent to a different origin than `baseUrl`'s, even across a redirect. Redirects follow
  one rule set in every port: 301/302/303/307/308 with a `Location`, at most 10 hops (then
  `<verb>: too many redirects`), 301/302/303 after a POST become a body-less GET, 307/308 keep the
  method and body (Elixir now follows same-origin redirects too instead of refusing them). Error bodies
  echoed in `HTTP <status>: …` are capped at 1000 characters. Python has no separate `timeout`
  option on `create_media`; the style's `timeoutMs` is the one knob.

Every port ships a runnable `media` example (an image through the typed call, speech through the
`generate_speech` tool; without `A2E_API_KEY` it prints a hint and exits), and each language's API
reference page has a Media generation section.

Contract: SPEC §7G, ADR 0036, `conformance/media/` (every port replays the same 34 cases and schema set).

**Not done yet** (tracked in `openspec/changes/add-media-generation`): downloading expiring result
URLs (a2e's last 3–30 days — save them yourself for now), cancelling the job at the provider
(cancel stops polling; the provider's job keeps running), uploading local files, ComfyUI workflow
injection, webhooks, price quotes, pausing a long job through `waitFor`, and streaming `file`
events. Realtime/live voice conversation is deliberately out of scope.

Known redirect gaps: on Clojure's cljgo host the Go HTTP client follows redirects itself, because
koine has no no-redirect option yet: custom style headers reach another origin (and so does
`Authorization` when only the port differs), and a redirect loop stops after 10 requests instead
of 11 (same `too many redirects` error); tracked in #130. The JVM build follows the §7G rules. An HTTP client or transport you inject yourself
(Java, C#, Rust, Python, and JS's `opts.fetch`, which may ignore `redirect: "manual"`) may follow
redirects before the engine sees them, so it must not forward headers cross-origin.

### Tool scheduling: reads run together, writes run alone (all eight ports)

The tool calls of one model response no longer all run at once. Before, every call started
immediately with no limit, so two `write` / `edit` calls on one file could race and a response
with forty reads opened forty at once. Now each call is **safe** or **unsafe**: a tool's own
`concurrencySafe(args)` predicate decides, otherwise only a tool whose hints say `readOnly` is
safe. Calls run in emitted order: a run of safe calls overlaps, an unsafe call runs alone after
everything before it and before anything after it. Results and the transcript keep emitted
order, as before. New client option **`maxToolConcurrency`** (default 10; env
`TOOLNEXUS_MAX_TOOL_CONCURRENCY` overrides the default, the option overrides both) caps how many
safe calls run at once. It applies to run and stream, both styles, resume paths and agent runs,
and to the in-process client. Relay tools and the subagent `task` tool always run concurrently.
SPEC §8 "Tool scheduling"; change `openspec/changes/add-tool-concurrency`; recipe page
`cookbook/tool-scheduling`.

- **Behaviour change:** a tool **without hints** (most `defineTool` / HTTP tools, MCP tools
  whose server sends no `readOnlyHint`) is now unsafe and runs alone. Mark it read-only or give
  it a predicate to keep it parallel.
- **rust only, source break:** `Tool` gained a `concurrency_safe` field, so a `Tool { .. }`
  struct literal must add `concurrency_safe: None` (or use `..` from another tool).
  `define_tool` callers are unaffected.
- **Not done:** the cap is per response, not per tool; `todowrite` keeps its `readOnly` hint, so
  two `todowrite` calls in one response may still overlap (last writer wins, as before). On Clojure, when
  the cap is full a new safe call waits for the oldest running call rather than the first to finish
  (#128). Only Go's tests are mutation-checked so far (#129).

### Background subagents: a documented recipe (all eight ports)

Running a subagent in the background — an id back at once, a callback when it finishes — needs
no new API: `spawn` returns the id, `wake` returns before the turn ends, and a `wait` in a
background task delivers the result. SPEC §7D "Background runs" documents it and each port now
pins it with a test.

## 0.23.0 — 2026-11-02

**Docs: Rust is on every page, and the docs now say eight languages.** Every tabbed guide,
cookbook recipe, scenario and the combined API page now has a Rust tab next to the other seven
(76 tab groups across 31 pages), and all 76 were compile-checked against `rust/`. There is a new
Rust API overview page (`/api/rust/`), which the sidebar already linked to. The READMEs,
`PUBLISHING.md`, `SPEC.md`, the CI job names and the release runbook now count eight ports and
cover crates.io (`ENABLE_CRATES`, `CARGO_REGISTRY_TOKEN`). The ACP host-hooks recipe now covers
Rust too: like Go, Rust has `on_permission`, `on_update`, cancel and `acp_generate_stream`. The
other six ports still lack the hooks (`add-go-host-asks` 2.6d). Not done: Rust has **no
benchmark row** on the performance page. That page says so instead of guessing a number.
Install snippets mark crates.io as "from the next release", because the crate is not
published yet.

**Rust port (`rust/`, crate `toolnexus` on crates.io) is complete: tier `full`, ready to publish.**
A Rust host can now build a toolkit and run the whole tool-calling loop natively on tokio, with
no Node/Python/Go sidecar. New since 0.22.0, on top of the ACP client, skills, MCP client and
core types that were already there:
- **Toolkit** (`create_toolkit`): MCP → skill → builtin → extra tools, first name wins, an extra
  tool shadows a builtin, `disable_tools` / `disable_skills`, `register` safe mid-turn.
- **The ten builtins** with the same names, schemas and outputs as the other ports, the host
  boundary (`shell`, `base_dir`, `confine_to_base_dir`, `kill_grace_ms`; a timeout or cancel
  kills the whole process group), hints on every builtin, `grep` context lines and truncation
  metadata, edit diffs, and the opt-in extras (background shells, `websearch` over a provider
  you supply, `notebook_edit`).
- **HTTP tools** (`http_tool`).
- **The unified client**: OpenAI and Anthropic styles, concurrent tool calls with
  `ToolContext.call_id`, hooks, streaming, retries with Retry-After and `on_error`,
  conversation stores (memory and file), metrics, `request_params` / `body_transform`, prompt
  cache, tool tiers, `list_models`, in-process models, Usage cache tokens, and the API-key-by-host
  rule from 0.22.0 (a custom `base_url` needs `api_key` or `api_key_env`).
- **§10 suspension**: `wait_for` / `wait_for_ctx`, or a durable `status: "pending"` result.
- **MCP**: non-text content now becomes `ToolResult.parts` (images were dropped before),
  annotation hints, `resource_tools()`, `load_mcp_with(on_server)`, and a legacy-SSE fallback
  for remote servers (rmcp has no SSE client, so this one is in-house).
- **ACP host hooks** (Go's 0.22.0 set): `on_update`, a reject answer from `on_permission`, a
  cancellable `generate`, `generate_stream`, and `acp_generate` / `acp_generate_stream` to use
  an ACP agent as the client's model.
- **A2A agents, both directions, and the MCP server profile** (SPEC §7A–§7C, ported from Go
  with its tests mirrored). Outbound: `ToolkitOptions.agents`, a top-level `agents` config
  block, or `Toolkit::add_agent` turn each skill on a remote Agent Card into a `source: "a2a"`
  tool that sends `SendMessage` and polls `GetTask`; a cancel, even mid-request, ends it as
  `A2A task <id> canceled`, and an unreachable agent is skipped instead of failing the toolkit.
  Inbound: `Toolkit::serve(addr, ServeOptions { client, a2a, on_task, mcp, on_call })` publishes
  an Agent Card built from the toolkit's skills (same bytes and key order as Go) and answers
  JSON-RPC over the client loop, with `contextId` remembered through `client.ask`, a suspended
  run reported as `input-required`, and memory / `file:<dir>` / custom `TaskStore`s. With `mcp`
  (or a `mcpServer` block) the same server is a stateless streamable-HTTP MCP server at
  `POST /mcp` exposing every tool, §1B parts included. The server side is the new `serve` cargo
  feature (hyper + rmcp's server, on by default); `--no-default-features` builds without it.
  Rust only: a served Task with no `client` configured fails with a message saying so (Go's
  fulfilment would crash and leave the Task `working`).
- **`translate` (§11)**: `Client::translate` / `translate_ctx`, one provider call with no loop,
  including the OpenAI → Anthropic inbound conversion and non-text parts. On `timeout_ms` it
  returns a `Transport("translate timeout after Nms")` error where Go returns its context error.
- **The classifier (§8B)**: `create_classifier` with the `systemone`, `llm`, `custom` and `static`
  styles, the OpenRouter backend preset, client-side limits, the degenerate-criteria warning
  (`MetricEvent` gains `question` / `warning`), `near_uniform`, `evaluate_batch`, and the
  byte-identical canonical request. All seven shared `examples/judge/` fixtures pass, hashes included.
- **Judge adapters and batteries**: `ask` / `gate` / `Policy` / `Tape`, and all eight batteries
  including the opt-in `ModelRouter`, with their hooks, against the shared battery fixtures.
- **Agents (§7D–§7F)**: `toolnexus::agents` — the runtime with the six verbs, the three gates,
  hierarchical budgets with a named `limit` on every stop, nearest-interpreter escalation and the
  runtime-level `wait_for`, durable pending + `resume` with task reattachment; `Agent` / `Spec`
  with team wiring, `run`, `as_tool` / `as_tool_with` (the child runs under the calling turn's
  cancellation, hooks gate and ask bridge); the `Loop` door with the completion gate and
  guardrails; personas (`from_dir`, `compose_soul`, `memory_tool`, `start_agent`); and
  `compactor` with 0.22.0's `on_compact` / `CompactInfo`. Ported from golang/agents with its tests
  (92, hermetic); the trace strings and the recorded unset-run golden are byte-identical to Go.
- **Relay tools and durable resume**: `relay_tool`, `run_with_answer` / `ask_with_answer`,
  `answer_output` / `answer_declined` / `relay_answer`, and the relay-vs-builtin collision guard.
- **Relay tests from Go** now mirrored too: streaming relay, `wait_for` parked across turns, and a
  run deadline breaking the parking. Rust differs from Go there, on purpose: `timeout_ms` wraps
  the whole run, so a parked `wait_for` is dropped at the deadline (Go lets it finish and fails at
  the next step). Either way a park outliving `timeout_ms` fails the run. `start_agent`'s `stop`
  now waits for a beat that is still running, as Go's does.
- **§1B edge constructors**: `ContentPart::text`, `from_path`, `from_bytes`, `from_reader`,
  `from_url` (a `data:` URL decodes to the same part as its bytes), with a typed `PartError`.
- **The post-0.22 fixes apply to Rust as well**: `RunResult.tool_calls` names the real tool
  behind `call_tool` with its inner args (SPEC §4C), and the Anthropic last-tool `cache_control`
  breakpoint is checked against the request body that is actually sent.
- **MCP elicitation**: `ToolkitOptions.wait_for` (and `McpLoadOptions.wait_for`) bridges a
  server's `elicitation/create` onto §10, form → `input`, URL → `authorization`. Without it the
  capability is not advertised.
- Runnable examples (`cargo run --example basic|agent|native_http|streaming|pending|acp|agents|prompt_cache`), all run in CI.
- Release: a `crates` leg in `release.yml`, gated by `ENABLE_CRATES` and using the
  `CARGO_REGISTRY_TOKEN` secret in `prod` (a crate's first publish cannot use Trusted Publishing;
  switch once it exists). It skips a version that is already on crates.io. `preflight` and the
  readiness script now check `rust/Cargo.toml`.
- Conformance: rust is held to tier `full`. All 22 client, 12 toolkit and 17 classifier options
  are present, with no permitted absences and no `unported` entries left.
- Docs: every Rust example on the API reference and cookbook pages is now a complete program that
  CI compiles and runs (218 of them, through a new Rust runner in `site/tests/run-all.sh`), and Rust
  has the 0.22.0 pages (API key by host, prompt cache, tool tiers, list_models, ACP hooks,
  `resource_tools`, background shells) plus shipped pages for translate, the classifier and attach.

**Not done in Rust** (`openspec/changes/add-rust-port`): a Gemini client style (Gemini is a
schema adapter only, as in Go). The Windows code paths compile but have not been run. A few io
and network error strings come from Rust's libraries and differ from Go's wording; the
conformance outputs (skill tool, prompt, tool names and schemas) are byte-identical. Found while
writing the docs, and true of Go as well: on the durable resume path an `answer_declined(id,
reason)` loses its reason (the slot reads "no result supplied on resume"); the inline `wait_for`
path reports it correctly. Not fixed here, since it is a cross-port change.
**Docs and examples for 0.22.0, in all seven ports.** The 0.22.0 features had code but no
reference pages, so a reader could only learn them from this changelog. Now each port's API
reference has pages for API key by host, `promptCache`, `toolTiers`, `listModels`, MCP
`resourceTools` and `BackgroundShells`, each with runnable examples that CI compiles and
executes. The existing pages cover `waitForCtx`, `onServer`, tool hints, `onCompact`,
`asToolWith`, `callId` and the new builtins. There are four new cookbook recipes (API key by
host, prompt cache and tool tiers, host hooks, ACP hooks for Go), README sections in every
port, and a hermetic prompt-cache and tool-tiers example per port. js, python and java examples
now also run in CI through each port's test suite, and the clojure one runs on both hosts.
Not done: the ACP hooks page for the six ports other than Go only says what to use instead,
because those ports do not have the hooks yet (`openspec/changes/add-go-host-asks`). CI compiles the C#
and Go examples but does not run them, and the Elixir example is run only by hand.

### Fixed

- **Go prompt cache now caches the tools array (golang only).** In 0.22.0, `PromptCache` with
  anthropic marked the system prompt and the latest message but never the last tool, because the
  adapter emits typed structs and the marker only touched maps. The cache breakpoint promised by
  SPEC §4C was missing, so the tools prefix was never cached. The last tool now carries
  `cache_control: {type: "ephemeral"}` (plus `ttl: "1h"` with `LongTTL`). Every other port
  already did this; each now has a test that checks the request body actually sent.
- **`createToolkit()` with no argument works in js.** It threw a TypeError; it now builds
  the default toolkit, as python and elixir already did. In java, `Toolkit.create(null)` now
  means default options instead of throwing a NullPointerException. Go, C# and clojure take a required
  options value, so they had nothing to fix.
- **The run result names the real tool behind `call_tool`, in all seven ports.** With tool tiers,
  a call the model made through `call_tool {name, arguments}` was recorded in the run result's
  `toolCalls` as `call_tool` with the wrapper arguments. Hooks and stream events already showed
  the real tool, so the three views disagreed. The run result now records the real tool and its
  inner arguments, after any `beforeTool` rewrite. The transcript still keeps the wrapper, and
  `tool_search` is still recorded as `tool_search`. If you matched on `"call_tool"` in
  `toolCalls`, match on the real tool name instead (SPEC §4C).
- Removed stale "golang only for now" comments from Go source for features every port has, and
  corrected the example count in `clojure/examples/clj/deps.edn`.

## 0.22.0 — 2026-11-02

**ACP host hooks: decide permissions, watch every update, cancel a turn, and stream (golang first;
the other ports are pending).** Before, the Go ACP source always picked the first allow option on a
permission request, showed the host nothing but the final text, could not be cancelled mid-turn,
and a client built on it refused to stream. Now, all opt-in: `ACPOptions.OnPermission` lets the
host choose the option (an error or empty answer picks a reject option, else cancels), so a plan
mode or a human approval prompt is possible; `ACPOptions.OnUpdate` receives every `session/update`
(message and thought chunks, tool calls, plans); `ACPClient.GenerateCtx` sends `session/cancel`
when its context is cancelled; and `ACPClient.GenerateStream`, passed as the new
`InProcessOptions.GenerateStream`, makes `Client.Stream` work, with message chunks as text deltas
and the agent's own tool calls as `tool_call`/`tool_result` events (marked `Via: "acp"`, never run
by the loop). With no hook set, behaviour is byte-identical. Requested by go-agent-cli/Karya and
deemwar-spotlight. Not done: js, python, java, csharp, elixir and clojure
(`openspec/changes/add-go-host-asks`, task 2.6d). The permission timeout still bounds the whole
turn, so a slow human approval can time out.

**New, in progress: a Rust port (`rust/`, crate `toolnexus`, not published).** It is being built
for a native Rust desktop host, in that host's priority order. The first piece is an ACP client:
spawn a coding agent over stdio, keep one warm session, stream every `session/update` (message
and thought chunks, tool calls and their updates, plans) to the host, answer
`session/request_permission` through a host callback (default: the first allow option, as in
Go), cancel a running turn, and end the turn cleanly when the agent exits mid-stream. Closing
stops the agent's whole process group on POSIX; Windows kills only the direct child for now.
Also done: skills, byte-identical to Go (checked by running both over the same corpus and
comparing every output); an MCP client on the official `rmcp` SDK (stdio and streamable HTTP,
`${ENV}` headers, failure isolation, the tools allowlist, and an idle timeout that progress
resets); and the core `Tool` types, native tools and OpenAI/Anthropic/Gemini schema adapters. The
ACP agent and stdio MCP servers run in their own process group (POSIX) or Job Object (Windows),
so closing stops everything they started; the Windows path compiles but has not been run. The
HTTP/TLS stack is an optional feature (`mcp-http`, on by default). Not done yet: the toolkit,
builtins, client loop and suspension; for MCP, the legacy-SSE fallback and non-text content parts
(`openspec/changes/add-rust-port`). Rust is not in the option-parity check until it has a client.

**BREAKING, all seven ports (golang, js, python, java, csharp, elixir, clojure): a local or
custom `baseUrl` without `apiKey` now needs `apiKey` or `apiKeyEnv`.** This is the API-key-by-host
security fix below; a host that relied on the old environment fallback for a local server or a
proxy must set one of the two (Go `APIKey`/`APIKeyEnv`, js `apiKey`/`apiKeyEnv`, python
`api_key`/`api_key_env`, Java `Options.apiKey`/`Options.apiKeyEnv`, C# `ApiKey`/`ApiKeyEnv`,
elixir `api_key`/`api_key_env`, clojure `:api-key`/`:api-key-env`).

**Security: an API key is no longer sent to a host it does not belong to (all seven ports).**
Before, with no `APIKey` set, the client took the first of `OPENROUTER_API_KEY`,
`OPENAI_API_KEY`, `ANTHROPIC_API_KEY` that was set and sent it to whatever `BaseURL` was
configured, so with several keys in the environment an OpenRouter key could go to
api.openai.com, or any key to a local or third-party server. Now the environment is used only
for the env var that matches the host (`api.openai.com` → `OPENAI_API_KEY`, `api.anthropic.com`
→ `ANTHROPIC_API_KEY`, `openrouter.ai` → `OPENROUTER_API_KEY`, https only); any other host needs
`APIKey` or the new `APIKeyEnv` (the name of the env var to read). **This can break a host that
relied on the fallback** for a custom or local `BaseURL`: set `APIKey` or `APIKeyEnv`.

**All seven ports (golang, js, python, java, csharp, elixir, clojure): hosts can add tools
mid-turn, use each MCP server as soon as it is ready, cancel a pending question with its turn,
hear about compactions, and gate tools by what they do.** Asked for by go-agent-cli, which builds
on the Go port directly; Go names are used below, per-port spellings follow the list. Not done:
the ACP host hooks (above) are Go only, other ports tracked in `openspec/changes/add-go-host-asks`
task 2.6d; Gemini explicit `cachedContent` is deferred (`openspec/changes/add-prompt-cache`
2.3); the Rust port is not published and has none of this yet.

- `Toolkit.Register` is now safe while a turn is running. Before, a host had to rebuild the
  whole toolkit to add tools. A tool added mid-turn is offered from the next model call.
- `LoadMcpWithOptions(ctx, cfg, McpLoadOptions{OnServer: ...})` reports each MCP server the
  moment it connects, fails or is found disabled, so one slow server no longer holds up the
  tools of the rest.
- `ClientOptions.WaitForCtx(ctx, request)` gets the turn's context, so a pending question ends
  when the turn is cancelled. It is used instead of `WaitFor` when both are set.
- `agents.CompactorOptions.OnCompact` is called after each compaction with token and message
  counts before and after, and the summary — enough to show "context compacted" in a UI.
- Compactor fixes: it no longer summarizes away the user's prompt when a turn
  is one prompt followed by a long run of tool calls (it keeps the prompt and summarizes the work
  after it); it does nothing when the result would not be smaller (it could grow, e.g. 206 → 211
  tokens); and `CompactInfo.Folded` hands the host the summarized messages verbatim, because the
  loop saves only the compacted transcript.
- Tools carry `Hints{ReadOnly, Destructive, OpenWorld}`: set on every builtin, read from MCP
  tool annotations, and passed to `BeforeTool`. A host can now let read-only tools run and ask the user before anything else.
  Permission gates use `BeforeTool` (allow / deny with an error result / ask via `Pending`);
  there is no separate permit option. Hints are advisory and never sent to the model.
- New opt-in builtins, off unless the host turns them on: background shells (`bash`
  with `run_in_background`, then `bash_output` to read new output and `kill_shell` to stop the
  whole process group; the host owns a `BackgroundShells` and calls `CloseAll` on shutdown),
  `websearch` over a search provider the host supplies (the library ships none),
  `notebook_edit` for `.ipynb` cells, and `McpSource.ResourceTools()` for listing and reading
  MCP resources.
- `grep` takes `context` / `before` / `after` lines. `read` and `grep` now say in metadata when
  output was cut (`truncated`, plus where to continue); the text itself is unchanged.
- Subagents: `Agent.AsTool` now stops the child run when the calling turn is cancelled, and
  `Agent.AsToolWith` lets the host give each call the same permission hook and question bridge
  as the parent turn (new runtime `Options.WaitFor`), so a child's `bash` cannot skip the gate.
  The child's tool calls and replies reach those hooks, which is enough for a nested UI row;
  there is no token-by-token stream of the child's text.
- Prompt caching, first step: usage now reports `CacheReadTokens` and `CacheWriteTokens`, so a
  drop in cache hits is visible. `ClientOptions.PromptCache` marks the stable prefix for the
  provider: Anthropic gets cache breakpoints after the tools, after the system prompt and on the
  latest message (optionally the 1-hour lifetime); OpenAI gets `prompt_cache_key`. Off unless set.
  A replay test with a simulated cache checks that hits start on the second request and never
  drop. Under Anthropic the real context size is `PromptTokens + CacheReadTokens +
  CacheWriteTokens` (Anthropic's input count excludes cached tokens); under OpenAI
  `PromptTokens` already includes them.
- Tool tiers (`ClientOptions.ToolTiers`): send only the core tool schemas, and let the model
  load the rest with a `tool_search` tool whose description lists them (name and one line, or
  name only). Loaded schemas arrive as a tool result, so the tools array, and the cached
  prefix, never change mid-session. Loaded tools are called by name or through `call_tool`,
  which the loop unwraps so permission hooks still see the real tool. An optional token budget
  (default 5% of the context window) moves the least-used tools down a tier when the plan is
  built; the plan is then frozen until `ResetToolTiers`. Streamed `tool_call` / `tool_result`
  events name the real tool for a `call_tool` call (with `Via: "call_tool"`). Not done: Gemini explicit cached
  content (`openspec/changes/add-prompt-cache`).
- `ToolContext.CallID` is the model's id for the tool call. The loop runs one message's tool calls
  concurrently, so a host (for example one tracking which subagent call is the parent) can tell
  sibling calls apart.
- `ListModels(ctx, ClientOptions)` lists a provider's models (id, display name, and context
  length when the provider reports it, as OpenRouter does), for model switching.
- Fixed: loading MCP config with a disabled server could race with the other servers' loading
  (`go test -race` found it). Java and C# already wrote that status under their lock; js,
  python, elixir and clojure cannot have it (one thread, or an immutable toolkit).
- **js now has all of the above too**, including the API-key-by-host security fix (with the
  same break for hosts that relied on the fallback: set `apiKey` or `apiKeyEnv`). JS shapes:
  `waitFor(request, { signal })` takes the turn's abort signal as a second argument (there is
  no separate `waitForCtx`); `loadMcp(cfg, { onServer })`; `mcpSource.resourceTools()`;
  builtins `background: new BackgroundShells()` / `webSearch` / `notebookEdit`;
  `agent.asToolWith(ctx => opts)` and runtime `waitFor`; `compactor({ onCompact })`;
  `listModels(opts)`; `promptCache` and `toolTiers` on the client with `toolTierPlan` /
  `resetToolTiers`. Not needed in js: a lock on `Toolkit.register` (one thread) and the
  disabled-status race (it cannot happen there). Also in js: the loop now passes the turn's
  abort signal to every tool it runs, so a cancelled turn reaches the tool (Go already did).
- **python spellings:** `load_mcp(cfg, on_server=...)`, `wait_for_ctx`, `api_key_env`,
  compactor `on_compact`, `BackgroundShells`, `resource_tools()`, `as_tool_with`, `list_models`,
  usage `cache_read_tokens` / `cache_write_tokens`, `prompt_cache` and `tool_tiers` with
  `tool_tier_plan` / `reset_tool_tiers`. MCP servers load concurrently only when `on_server` is
  given; the disabled-status race does not apply (one asyncio loop).
- **Java spellings of the above:** `Toolkit.register` (read/write lock), `McpSource.load(cfg,
  new McpSource.LoadOptions().onServer(...))` with `McpSource.ServerResult`,
  `LlmClient.Options.waitForCtx((request, cancelToken) -> answer)` (the token is the run's
  `CancelToken`, and a cancel also interrupts the thread running it), `Compaction.Options.onCompact`
  with `Compaction.CompactInfo`, `Tool.hints()` / `Tool.withHints(tool, hints)` and
  `BeforeToolEvent.hints()`, the builtins config keys `background` (a `BackgroundShells`),
  `webSearch` (a `WebSearch`) and `notebookEdit`, `McpSource.resourceTools()` (also reachable as
  `Toolkit.mcpSource()`), `ToolContext.callId()`, `Agent.asToolWith(ctx -> runtimeOptions)` and
  `RuntimeOptions.waitFor`, `LlmClient.listModels(options)`, `Usage.cacheReadTokens` /
  `cacheWriteTokens`, `Options.promptCache(new PromptCache())`, `Options.toolTiers(new ToolTiers())`
  with `toolTierPlan` / `resetToolTiers`, and `StreamEvent.via()`. Java has no context object, so
  a `WebSearch` provider and `asTool` see cancellation as a thread interrupt (`asTool` also honours
  `ToolContext.cancel()`).
- **csharp spellings:** the Go names in PascalCase (`OnServer`, `WaitForCtx`, `ApiKeyEnv`,
  `OnCompact`, `BackgroundShells`, `ResourceTools`, `AsToolWith`, `ListModels`,
  `CacheReadTokens` / `CacheWriteTokens`, `PromptCache`, `ToolTiers` with `ToolTierPlan` /
  `ResetToolTiers`). Not applicable: `OnCompact` gets no cancellation token (the C# `BeforeLLM`
  hook carries none).

**Elixir has the same host additions now, starting with the API-key fix.** With no `api_key`,
the Elixir client no longer falls back across `OPENROUTER_API_KEY` / `OPENAI_API_KEY` /
`ANTHROPIC_API_KEY`: it reads only the env var of the `base_url` host (https only), or the one
named by the new `api_key_env`. **A host on a custom or local `base_url` that relied on the
fallback must now set `api_key` or `api_key_env`.** Also in Elixir: `Mcp.load(cfg, on_server:)`,
`wait_for_ctx: fn ctx, request -> … end` (`ctx.signal` is the pid running the turn, so a
resolver waiting elsewhere can monitor it), compactor `on_compact` plus the three compactor
fixes, `Tool.hints` (`%Toolnexus.ToolHints{}`) on builtins, MCP annotations and the
`before_tool` event, opt-in `background` / `web_search` / `notebook_edit` builtins
(`Toolnexus.Builtin.BackgroundShells`), `Mcp.resource_tools/1`, grep context and the
truncation metadata, `Agents.as_tool_with/2` and runtime `:wait_for`, `Client.list_models/1`,
usage `cache_read_tokens` / `cache_write_tokens` (present only when the provider reports a
non-zero count, so uncached usage maps are unchanged), `prompt_cache` and `tool_tiers`
(`Client.tool_tier_plan/2`, `Client.reset_tool_tiers/2`). `ToolContext.call_id` was already
set by the Elixir loop. What differs, by design: Elixir's `Toolkit` is an immutable value, so
"register while a turn runs" does not apply — a running turn keeps the toolkit it started with,
and the tool-tier plan is keyed by the toolkit's id, which survives `register`. The
disabled-status race does not exist there (nothing is shared). A turn is cancelled by stopping
its process, and an agent run as a tool now stops with it: a child run used to outlive its
handle when the runtime was killed, and now it does not. `web_search` gets `(query, limit)`
without a context. Not done: Gemini explicit cached content (as in Go).

**Clojure now has all of the above, including the API-key security fix.** A clojure client with
no `:api-key` reads only the env var of its `:base-url` host (or the one `:api-key-env` names)
and throws for any other host or plain http, where it used to send `OPENAI_API_KEY` /
`ANTHROPIC_API_KEY` / `OPENROUTER_API_KEY` to whatever URL was configured, or an empty bearer
token. A clojure host on a local or proxy URL must now set `:api-key` or `:api-key-env`. The
rest, by its clojure name: `mcp/from-config` `:on-server` (servers then connect concurrently;
the returned source is unchanged), `:wait-for-ctx` plus a `:cancel` token on `run`/`ask`,
compactor `:on-compact` and the three compactor fixes, `:hints` on tools and on `:before-tool`
events, opt-in `:background` / `:web-search` / `:notebook-edit` builtins, `mcp/resource-tools`,
grep `context`/`before`/`after` and read/grep truncation metadata (text unchanged),
`agents.runtime/as-tool` / `as-tool-with` and runtime `:wait-for`, `:call-id` on the tool
context, `client/list-models`, `:cache-read-tokens` / `:cache-write-tokens` in usage,
`:prompt-cache`, and `:tool-tiers` (with `tool-tier-plan` / `reset-tool-tiers!`; `:on-event`
tool events carry `:via "call_tool"`). Differences, by design: a toolkit is an immutable value,
so "register while a turn runs" has no meaning (`tool/add-tools` returns a new toolkit; the
tier plan is frozen per toolkit identity, which `add-tools` keeps), and there was no
disabled-status race to fix. `:call-id` reaches a tool only when it has the two-argument arity
and says so (`:context? true`, which every `native-tool` sets); a one-argument tool is called
exactly as before. Cancellation is cooperative: checked before each model call and after each
batch of tool calls, so an HTTP call already in flight is not aborted. Background shells are
POSIX only. `list-models` uses koine's HTTP client, not `:http-client` (which only posts).

**Fixed: three cross-port byte differences in the new host tools (golang is the reference).**
- `tool_search` output is now the same bytes in every port: keys sorted at every level and
  `<`, `>`, `&`, U+2028, U+2029 escaped as Go's json does (`\u003c`...). js, python, elixir and
  csharp did not escape them, and csharp did not sort nested schema keys.
- The 120-byte cut of a listed tool's description line now keeps the longest valid UTF-8 prefix
  in every port. Go cut at a raw byte and could emit a broken character; python, csharp and
  clojure cut at 120 characters, so a non-ASCII description gave a different line.
- A background shell killed by a signal it did not get from `kill_shell` now reports
  `exited with code -1` everywhere. java, csharp, elixir and clojure reported 128+signal (143
  for SIGTERM). Those runtimes cannot tell a signal from a real exit status, so there a command
  that itself exits 129..159 also reads as -1.

**A cancelled turn no longer forgets what it did, cancel can kill a stubborn command in about 100 ms,
and file edits come back with a diff.** Found by go-agent-cli's real server integration; all seven
ports (`openspec/changes/add-host-turn-durability`).

- **Interrupted turns are saved.** `ask` with an id and streaming with an id used to save only on
  success, so a cancelled or failed turn lost the user's prompt and every tool call that had already
  finished. They now save on error, cancellation and run timeout too. A tool call that never
  returned gets a synthetic error result saying its side effects are unknown and that it must not be
  re-run automatically, so the saved transcript stays valid in both dialects. The original error is
  still returned. Nothing claims a rollback, and nothing re-executes the call. Under openai-style
  transcripts the error flag is not carried (that dialect has none), so the text is the marker.
  Edge: a tool finishing within milliseconds of the cancel may be recorded as interrupted.
- **`killGraceMs`** (builtin option, default 2000, unchanged) is the wait between SIGTERM and
  SIGKILL. Measured with 100, from cancel until a job that ignores SIGTERM and its TERM-ignoring
  background child were all gone, 10 runs on macOS: golang 101–102 ms, js 101–104, python 102–107,
  java 103–111, elixir 137–141, csharp 165–188, clojure 175–228 (median 204). With the default,
  elixir measured 2036 ms.
- **Edit diffs.** `edit`, `write` and `apply_patch` results carry `metadata.files`
  (`{path, status, additions, deletions}`) and a unified `metadata.diff`; the streaming
  `tool_result` event now carries the result's `metadata`. java: adding the field changes the
  `StreamEvent` record constructor.
- **Fixed along the way:** js `edit` with `baseDir` set wrote back to the unresolved path (wrong
  file); java `bash` ignored thread interruption, leaving the job running; csharp tools never
  received the run's cancellation; js/java/csharp now stop waiting on running tools when a turn is
  cancelled.

Not done: java streaming has no cancel entry point (only errors interrupt it; clojure now has a
cooperative `:cancel` token, see above); elixir
saves nothing early if the process reading a stream is killed, a leak older than this change.
The follow-ups named here (a lock on `Toolkit.register`, per-server MCP readiness, a
cancellable `waitFor`) have since shipped in all seven ports (see the host additions above).

**Claude Code's MCP configs now load, conversations can live on disk, and an MCP tool that reports
progress is no longer killed by its timeout.** All three were found by go-agent-cli, a per-folder
agent server built on the Go port, and land in all seven ports
(`openspec/changes/add-host-engine-needs`, `openspec/changes/add-mcp-progress-timeout`).

- **`command` + `args`.** Claude writes `{"command":"node","args":["server.mjs"]}`; every port typed
  `command` as an argv array, so the Go port failed the *whole* config on it. A local `command` may
  now be a string or an array, with `args` appended, spawned with no shell. A malformed command or
  args fails the parse with an error naming the server.
- **`FileConversationStore(root)`.** One `<root>/<id>.json` per conversation, atomic
  temp-fsync-rename on save, ids validated against traversal, corrupt files reported as errors
  rather than silently empty. Both shipped stores gain `list()` (newest first) and `delete(id)`;
  `ConversationStore` itself is still `get`/`save`, so custom stores are unaffected. A `sync`
  option (`full` default, `fsync`, `none`) trades power-loss durability for speed: on macOS a 200 KB
  save is ~7.9 ms under `full` (F_FULLFSYNC) and ~0.4 ms under `fsync`. `fsync` is a true plain fsync only in
  golang, python and csharp; in js, java, elixir and clojure the runtime offers nothing weaker, so
  `fsync` flushes like `full` minus the directory fsync.
- **MCP idle timeout (#63).** Every `tools/call` now sends a progress token, and the per-server
  `timeout` counts silence, not total time. A server that never reports progress behaves as before
  — except in C#, which had no per-call deadline at all and now enforces one. The timeout error
  text changed in golang and java.
- **golang only:** stdio MCP clients never started their notification handler, so progress *and*
  server-initiated elicitation over stdio never arrived. Both now do.

Not done: elixir and clojure still cannot cancel an in-flight MCP call (clojure: disconnect only);
clojure's file store needs POSIX tools and does not run on Windows; elixir lists saves made within
the same second in id order; there is no context-window field yet. A python shutdown error when a
server answers after its call timed out predates this change and is not fixed here.

## 0.21.0 — 2026-09-28

### Judge batteries: eight ready-made judgments, wired into the agent loop, in all seven ports

`ask` / `gate` made one judgment short; a host still hand-wrote the same hook glue around it every
time — rate a tool call before it runs, trim the tools offered to the model, drop irrelevant tool
output, screen what a user typed, route a task. Those now ship as eight **batteries**: each takes
any `Classifier` (so the same battery runs on `systemone`, `llm`, or a recorded `static` corpus in
CI), has a standalone method that returns a typed verdict, and where the loop has a seam, an
`asHook(next)` that composes with your own hook instead of replacing it.

- **`ToolGuardClassifier`** rates a call on a 4-level risk rubric and returns `allow`, `ask` or
  `deny`. As a `beforeTool` hook, `ask` suspends the run with a §10 `approval` Request
  (`toolguard:<call id>`) so a human decides and the tool is never reached until they do; `deny`
  short-circuits with `denied by tool guard: <reason>`. An unsure rating always asks.
- **`ToolRelevanceClassifier`** (`beforeLLM`, swaps `tools`) and **`SkillRelevanceClassifier`**
  drop only what the classifier is *confident* is not needed; uncertain keeps it.
- **`ToolResultFilterClassifier`** (`afterTool`) keeps the paragraphs of a tool's text output that
  are relevant to the call.
- **`IsCompleteClassifier`** checks a final answer against its task (standalone: the client loop
  has no completion seam).
- **`AgentRouterClassifier`** picks an agent, walking a host-supplied tree past 255 options.
- **`ContentGuardClassifier`** screens text on `harmful` and `prompt_injection` (your own
  dimensions if you prefer) into `allow` / `review` / `block`; as a `beforeLLM` hook a block stops
  the run before any model request.
- **`ModelRouterClassifier`** — **opt-in** per-query model routing. You give it your models, each
  described in a sentence ("short factual answers; cheapest"); attached as a `beforeLLM` hook it
  sends a turn to the model it is *sure* of and otherwise leaves your configured model alone.
  Nothing changes unless you attach one: SPEC §8 *Right-size routing* still transmits the
  configured model verbatim, and its conformance test is unchanged (owner decision, ADR 0035 D6).

To make the router possible, a **`beforeLLM` hook may now return `model`** for that turn (request
body and `afterLLM` event). Absent or empty, the configured model is sent exactly as before, in
every client style and in streaming.

When a hook does route a turn, **everything that reports a model now reports the one that was
sent**: that turn's `llm` metric event, `RunResult.model` and the `run` metric event (the last
model call's model), and `translate`'s `result.model`. Without an override they report the
configured model, as before. Before this, a routed run was billed and graphed under a model it
never called.

**A failing `beforeLLM` hook now stops the call in every port and every entry point.** Go's
`Translate` ignored the hook's error and sent the request anyway; each port's run, stream, agent
and translate loops are now tested to return the hook's error with no provider request sent. This
is what makes a blocking ContentGuard actually block under `translate`.

Two battery edge cases now agree across ports, each pinned by a shared fixture case:
**AgentRouter with duplicate names at one level uses the first node** (its description, its
subtree; Go, Java, C# and Clojure took the last node, js, Python and Elixir the last
description), and **a provider tool entry whose name or description is missing or not a string
reads as `""`** in the ToolRelevance hook (Clojure read nil; Python and Elixir stringified `5` to
`"5"`). Elixir: a `before_llm` hook returning `{:error, reason}` was treated as "no override" and
now raises `Toolnexus.HookError`. Clojure: a pending run now emits its `run` metric event.

**On a §7D agent, a failing hook now has one pinned shape in all seven ports** (SPEC §8 + §7D
*Errors*, fixture `examples/agent-hooks` H7): the agent's level-1 **loop run throws** the hook's
error, and a **runtime handle turn** (`runTurn`, `wake` + `wait`, the one-shot agent run) resolves
the §7D boundary result — `isError: true`, `status: "error"`, the hook's message — so a hook
failing inside a child agent reaches the parent's model like any other failure. No request is
sent either way. This was already the behaviour everywhere; what differed was which entry point
each port tested (C# and Python covered only the handle turn, Go none, js/Java/Clojure only the
loop), so it could have drifted unseen. Every port now tests both. Clojure has no streaming loop,
so its streaming rows do not apply.

**Clojure: a per-call model is serialised once.** A string key in `:request-params` (including
the `"model"` the agent Loop sets for `(run lp prompt {:model …})`) sat beside the body's own
keyword key, so the request JSON carried `"model"` twice; which one a provider honoured was up to
its parser. Param keys are now canonicalised before the merge, so a param replaces the built-in
field. The same fix makes a string `"messages"`/`"tools"`/`"stream"` param as forbidden as the
keyword spelling (before, it silently replaced the conversation).

Every battery requires you to say what a classifier error means — `onError: "open"` or `"closed"`,
no default — because failing open or closed is your policy, not ours; the routers fall back to your
fallback instead. Verdicts carry `calibrated`, since a threshold tuned on one backend does not
transfer to another. They are advisory: ToolGuard's `deny` is a policy aid, not a security control,
and allowlists stay in your code. The default role and question wording is part of the contract
(ADR 0021: the sentence is the product), pinned byte-for-byte by the shared fixtures in
`examples/judge/batteries/`, which every port runs against a recorded `static` classifier.

**What is NOT done.** `SkillRelevance`, `IsComplete` and `AgentRouter` have no hook: skills live in
the system prompt (outside `messages` in the anthropic style), there is no completion seam, and
there is no uniform subagent seam. Question text can be overridden only through `role` (and
`dimensions` for ContentGuard). Hooks re-judge every turn (one classifier call per attached battery
per turn). None of
the thresholds has been tuned against a live backend; ADR 0035's live evidence items are still open.

### Simple judgments: `ask`, `gate`, `State`, `Policy`, `Tape` and `evaluateBatch`, in all seven ports

Asking a classifier two yes/no questions used to take ~16 lines of nested question-type maps
(Go), and every caller re-derived the same 0.30/0.70 cut-offs by hand — the first real consumer's
gates had no uncertain band at all, so a 0.62 reading failed a run. Now it is five lines: a state,
an ordered list of named questions, and `ask`, which returns each answer by name with a `band`
(`yes`/`no`/`uncertain`, noul) or `sure` (choice/score) and a `value()` — one number, no type
inspection. The §8B wire request is byte-identical to hand-written maps (tested in every port).

- **`gate` declines to decide.** Rules (`below` / `at_least` / `is`) are first-match; an uncertain,
  unsure or missing answer returns `needs_input` with a §10 `input` Request (question, reason,
  answers; the reason is exactly `missing answer "<key>"` or `uncertain answer "<key>"` in every
  port — before this, seven ports used six different wordings for uncertainty and only two named
  the question — with the key verbatim between the quotes, never escaped (golang `%q` and js
  `JSON.stringify` used to escape a `"` or `\` in the key, and in the Tape miss name) — and the Request id is `gate:<i>:<key>`, `i` being the rule's index as given) your host routes through `waitFor` or its own queue — it never acts on a guess.
  Cut-points default to 0.30/0.70, exclusive (exactly 0.70 is uncertain), overridable per call.
- **`Policy{rules, default, bands, skipUncertain}`** declares the fall-through: an empty `default`
  escalates with `no rule fired`; `skipUncertain` skips an uncertain rule but a *missing* answer
  still escalates. Checks run missing → uncertain → rule fit, so `skipUncertain` skips an uncertain answer
  even under a mis-typed rule, and a mis-typed rule (`is` on a noul, `below` on a choice) escalates
  instead of silently not firing (js and elixir used to evaluate it to false). golang's
  `SkipUncertain` used to renumber the rules, so a later escalation carried the wrong `gate:<i>` id.
- **`State(role, data)`** puts the role next to your data — the wire has no system prompt. And
  **each question names the state field it judges**. Measured live on TypeSafe `jev-1.13.0`: with
  the role in state and a vague question an insult read 0.55 (inside the uncertain band); naming
  `message_received` in the question gave 0.96 / 0.02 on the insult and 0.02 / 0.79 on the good
  message. Copying the role into the question instead was rejected.
- **`Tape`** records live decisions by call name and replays them offline, so a hermetic test runs
  on real answers. Getting a replayer for an unrecorded name never fails; evaluating it fails with
  `tape: no recorded decision for call "<name>"` and sends nothing — the same in every port (js
  used to throw when the replayer was built). **`evaluateBatch(states, questions)`** asks
  the same questions of many states: state order, at most 16 in flight, fail-closed on the lowest
  failing index, empty list is an error. Plus a one-line static classifier from a recorded decision.

Per-port names: golang `tn.Ask` / `tn.State` / `tn.JudgeAnswer` (`Answer` is already §10),
`Tape.Recording` / `Replayer` with `WithCallName(ctx)`; js named builders are `judge.noul/choice/score`
(bare `noul` stays the §8B wire builder) and the picked option is `pick()`; python `state`, `Rule(is_=…)`,
`skip_uncertain`, `evaluate_batch`; java `Judge.*` with `State.of`, `Rule.atLeast`; csharp
`Judge.AskAsync` / `GateAsync`, `EvaluateBatchAsync`, and `Decision.FromJson` is now public;
elixir `Toolnexus.Judge` (bands as atoms, `evaluate_batch`); clojure `toolnexus.judge` with `:at-least`,
`j/picked`, `jev/evaluate-batch`. All seven pass every shared case in `examples/judge/` and `examples/judge/adapters/`, which now pin the escalation reason, id and question, each answer's value/band/sure, Policy cases and `State(role, data)` as data. The clojure suite now finds `examples/` on its own (no `TN_EXAMPLES` needed).

**What is NOT done.** (The batteries, including an opt-in ModelRouter, now ship: see the entry
above.) No port attempts a native batch wire
call (`evaluateBatch` is per-state `evaluate`). Tape replays by call name through a `custom`-style
classifier, not `static`. The Policy entry point, the picked-option accessor, the static
one-liner and the Tape method names keep per-language spellings (behaviour identical); they are
listed in the SPEC §8B idiom table. The on-disk tape file format is per-port and not portable.

## 0.20.0 — 2026-09-22

**The built-in tools no longer close over the host process. `bash` runs on native Windows, relative
paths can be scoped to a directory, and a timeout actually kills the job.** Three issues (#100,
#101, #102) arrived as three bugs in one file; they measured as one defect in three places, in all
seven ports — every builtin inherited an ambient property of the host process that no host could
inspect or set. Three new options on the builtin source close that, each defaulting to exactly
today's behaviour:

- **`shell`** — the argv prefix `bash` runs a command with (`["sh","-c"]`,
  `["cmd","/d","/s","/c"]`, `["powershell","-NoProfile","-Command"]`, `["bash","-lc"]`). Given, it
  is used verbatim; absent, it is detected when the toolkit is built — `sh -c` on POSIX, and on
  Windows `%COMSPEC%`, then `pwsh`, then `powershell`, then a POSIX `bash` if one resolves —
  and the interpreter that won is reported. Construction now fails, naming what it tried, rather
  than producing a toolkit whose `bash` cannot run. This was worse than reported: on Windows
  go/java/csharp/elixir/clojure hard-failed with `"sh": executable file not found`, while **js and
  python quietly got `cmd.exe`** — `echo $HOME` printed the literal `$HOME` and exited 0. One call,
  two different wrong answers.
- **`baseDir`** — the directory relative paths resolve against, honoured by `read`, `write`,
  `edit`, `glob`, `grep`, the paths *inside* `apply_patch`'s patch text, and as the default
  `workdir` for `bash`. `""` keeps the process cwd, i.e. today. Without it a host that gives each
  run its own directory had no way to say so: the reporting consumer had an 18 KB test file written
  into their platform's own repository instead of the run's worktree, and the run reported success.
- **`confineToBaseDir`** — opt-in refusal of any path whose *canonical* form lands outside
  `baseDir`, through symlinks and through Windows directory junctions (`mklink /J` needs no
  privilege and defeats lexical canonicalisation), plus outright refusal of Windows reserved device
  names — `CON`, `NUL`, `COM1`… pass a containment check and never write into the directory at all.

**Two behaviour changes come with it, deliberately.** A timeout or cancellation now kills the whole
job — process group on POSIX, Job Object / `taskkill /T /F` on Windows, SIGTERM then a grace window
then SIGKILL — where before it killed only the interpreter and orphaned the real command, in all
seven ports. And **Go's `grep` now emits the walk-root-relative `/`-separated path** that `SPEC.md`
§4A has always required, instead of the joined host path; Go sorted on one string and printed
another, which is the exact failure §4A's own note warns about. `output` shape is unchanged — which
interpreter ran, and whether the tree was killed, are reported in `metadata`, so no conformance
golden moves.

**What this does NOT do, named rather than implied.** Java, C#, Elixir and Clojure are **unverified
on Windows** — those runtimes are not installed on the Windows machine available to us, so their
implementations are written against documented platform APIs and tested everywhere else. And
nothing here confines `bash` *itself*: `cd ..`, `env -C` and an absolute path all still leave
`baseDir`, measured, not assumed. `confineToBaseDir` bounds the file builtins, not the shell — a
real sandbox is ADR 0033's subject, not this change's.

**`retryAfter` on the typed provider error now means the same thing in all seven ports — the raw
`Retry-After` header, verbatim.** *Breaking for python, elixir, csharp and clojure hosts that read
this field.* 0.19.0 shipped the field without pinning its representation, and the seven ports
promptly picked three: js, golang and java carried the raw header string; python and elixir carried
parsed seconds; csharp and clojure carried milliseconds — csharp under a different name again
(`RetryAfterMs`). A host porting retry-handling between ports got a number that was off by 1000, or
a string where it expected a float. Now every port carries the header exactly as the provider sent
it, on a field spelled `retryAfter` / `RetryAfter` / `retry_after` / `:retry-after`, and the port's
natural absent (`undefined` / `null` / `nil` / `None`) when the response sent no such header.

What changed per port: **python** `retry_after: float | None` → `str | None`; **elixir**
`retry_after: non_neg_integer() | nil` → `String.t() | nil`; **csharp** `RetryAfterMs: long?` →
`RetryAfter: string?` (the member is renamed, deliberately); **clojure** `:retry-after` was
milliseconds, now the raw header. js, golang and java are unchanged — they were already right.

The raw header wins because it is **lossless**. `Retry-After` may legitimately be an HTTP-date, or
fractional, signed, out-of-range or unparseable; a numeric field has to null all of those out,
throwing away something the response really did supply and that a host may well want to log or act
on itself. The field is for the host, not for the library. **The library's own retry/backoff
behaviour is untouched**: internally every port still honours only the `delay-seconds` form (whole
seconds, `0` means "retry now") and falls back to exponential backoff for anything else — so
`Retry-After: Wed, 21 Oct 2026 07:28:00 GMT` still produces a backoff wait *and* now still reaches
you verbatim on the field. Each changed port has a test pinning exactly that case. `SPEC.md` §8 now
states the representation outright so it cannot drift again, and `docs/adr/0027` carries a note on
what D3 left unsaid.

**Java can now parse an `mcp.json` without connecting to anything.**
`McpSource.parseConfig(Object)` is public, closing the last availability gap in the seven-port MCP
config parser — Java was the only port where validating config meant either calling `load` (which
spawns processes and opens connections) or reimplementing the wrapper-key rules yourself. It takes a
file path, a raw JSON string, or an already-parsed `Map`, unwraps `mcpServers` / `servers` / `mcp`,
and returns server name → server config. Use it to fail fast on a typo at startup, to filter or
rewrite config before handing it to `load`, or to accept config from somewhere that is not a file.
The change is purely additive: the method's behaviour is unchanged, and it is the same one `load`
and `listMcpTools` have always called. As in the JS and Go references the returned map is not a
defensive copy — copy it before mutating. One difference survives and is documented: C# and Java
spell it `McpSource.ParseConfig` / `McpSource.parseConfig`, where the other five ports expose a free
function (`parseMcpConfig`, `parse_mcp_config`, `ParseMcpConfig`, `parse_config`, `parse-config`).

**Four API-reference pages documented signatures that do not exist, and one documented the wrong
rule.** No library behaviour changed here — but if you copied these, you got code that would not
compile, or a wrong mental model, so they are worth naming. `resume` was written up as returning
nothing in Go (`suspension/resume`, `runtime/handle`) and Python (`suspension/resume`); it returns
the result of the topmost handle the cascade re-ran (`SPEC.md` §7D) — `(TaskResult, error)` in Go, a
`TaskResult` in Python, a result map in Elixir. Go's page also claimed `Resume` errors on an
`answer.ID` mismatch; it never inspects `answer.ID`, and errors only when no handle in the tree is
suspended. Python's `suspension/resume` claimed a resumed `TaskResult`'s counters are per-call; they
are the handle's **own cumulative** totals and grow across a resume, never reset (`SPEC.md`:1078).
Elixir's `client/resilience` still rescued `RuntimeError`, which 0.19.0's typed provider errors
replaced with `Toolnexus.ProviderError`. And the Go and C# `skills/list` pages said a skill is
`malformed-frontmatter` when YAML fails to parse — it is malformed only when YAML refuses it **and**
the lenient line-wise rescue also recovers no `name` (`SPEC.md`:409-416, ADR 0028); both pages
illustrated the rule with a fixture the rescue happily recovers, so they documented the opposite of
what the ports do.

**The API reference now has a coverage gate, and it blocks CI.** `site/scripts/coverage-gate.mjs`
(new `docs-coverage` job) fails the build when a manifest entry has no page in some port, when a
page is still an unfilled generator scaffold, when a page is an orphan nothing points at, or when a
declared parity gap does not say it is one. It also runs `verify-symbols.mjs --strict`, which
existed but ran nowhere, so a renamed export used to surface as a broken page rather than a failed
build. This is the check that was missing when fifteen pages — `client/create` and `toolkit/create`
among them, in five ports — sat as empty `TODO` scaffolds without anything noticing. Page *depth*
(when-to-use / why / three examples) is reported but not yet blocking; the gate prints the
outstanding count on every run.

## 0.19.0 — 2026-09-22

### Eight things that went wrong for people building on 0.18.x, fixed in all seven ports

Eight consumer-reported issues (#86–#93), every one of them found by someone shipping on the
library rather than reading it. The evidence for each — a runnable, hermetic spike and a decision
record — is in `docs/adr/0023`–`0028`; the batch is `openspec/changes/fix-consumer-issues-86-93`.

**You can run a completion with no toolkit at all.** `run`/`ask`/`stream` now accept no toolkit
(and a null one), instead of failing while assembling the system message. Point the client at a
model, send a prompt, get text — no empty `Toolkit` to construct, no `builtins: false` ceremony.
The request body carries no `tools` and no `tool_choice` key at all, not an empty array, so a
provider that treats the two differently sees a plain completion.

**A guardrail on a harness now actually runs when the agent is driven by a loop.** In Go, an
agent's `soul`, `guardrails` and `hooks` were silently dropped on the `Loop` path — you could
write a policy denying `bash`, read it back in code review, and ship a harness with no policy.
Go now applies them, as the other six ports already did. Two more fields join them everywhere:
the harness's `model` and `budget.maxTurns` become loop defaults. Where both the harness and your
client options set a system prompt, **the caller wins** in every port now (JS previously let the
soul override yours). What a loop still cannot honour — `tools`, `team`, `waitFor`, `onMetric` —
is no longer silent: `loopUnsupported(spec)` returns those exact names, the same strings in all
seven ports, and the harness docs table says so. Note in particular that a loop-driven agent has
**no `task` tool and cannot delegate at all**, whatever its `team` declares.

**A delegated run now tells you what it cost and why it stopped.** `TaskResult.totalTokens` **and
`TaskResult.turns`** now mean the same thing on *every* status; each previously meant one thing on
three statuses and something else on the other three, which is unreadable whichever half you were
holding. `totalTokens` is the **rolled-up subtree** total — a parent never reports fewer tokens
than a child it delegated to — and the new `OwnTokens` gives you the per-agent figure when you need
to attribute spend. `turns` is the handle's **own** cumulative round trips, reported identically on
every status; it is **not** rolled up, so a parent that delegates in one turn to a child that takes
five legitimately reports fewer turns than its child. There is deliberately **no `OwnTurns`** —
with no roll-up, `turns` already is the own-figure. **This changes a value you may already be
reading:** on the runtime path, `turns` on `done`/`pending`/`incomplete` becomes the handle's full
cumulative count in the one port that reported a per-run figure there — **Go**. JS and C# already
reported it consistently, so nothing moves for their callers.
`TaskResult` gains `limit`, populated in six more ports and set for
budget stops as well as run limits, so "it stopped" comes with which ceiling. **`limit`'s values are
now a closed vocabulary you can branch on** — `maxTurns`, `maxTokens`, `maxToolCalls`, `maxWallMs`,
`maxChildren`, `maxConcurrent`, `maxDepth`, `completion`, `timeout` — each naming the budget field
that stopped the run, identical in all seven ports. Four ports were emitting their own internal pool
names into that field (`maxWall`, `tokens`, `wallMs`), which defeats the point of a field whose only
job is to be branched on. **If you already branch on one of the old strings, that branch breaks** —
they map onto the canonical spelling above. And the field is now filled consistently: **a run
could previously stop with `status: "timeout"` and no `limit` at all** — the two fields
contradicting each other inside the very feature added so you could branch on a stop. That was
live in three of the finished ports, and port-by-port audits then turned up further construction
sites with the same shape that no bug report had reached. The rule is now a contract, asserted as
an invariant rather than case by case: **a limit stop names its limit; a non-limit stop leaves it
empty.** Both vocabularies are **public API in every port**, so you branch on
`StopLimit.MaxWallMs`, not on a string literal. `Runtime.Resume`
returns the result of the topmost handle it re-ran instead of nothing. Java no longer replays the
literal string `"continue"` as the resumed prompt; C# and Clojure no longer hard-code "maxTurns"
in the stop message when a different limit stopped the run.

**A human's answer can no longer be dropped on a durable resume.** Go's answer-carrying entry point
read exactly two keys out of `Answer.data` — `results`, then `output` — and quietly fabricated a
tool error for anything else while handing you `status: "done"`. Hand it `{"value": "staging"}` and
the reply vanished, the model was shown an invented failure, and your logs looked healthy. Now:
`ok: true` with no determinable result for an outstanding call is an **error to you**, the keys are
named in SPEC §10 for the first time, and two constructors — `answerOutput(id, output)` and its
natural pair `answerDeclined(id, reason)`, for the human who says no — exist in all seven ports so
the map is not hand-built. A non-string `output` errors rather than degrading to `""`. Elixir and
Clojure now accept **string-keyed** answers — §10 says the keys are fixed across ports, but a
JSON-round-tripped `Answer` previously crashed in Elixir and read as a silent *decline* in Clojure,
which is precisely the path the durable posture exists for.

**The documented durable-resume example was wrong, in all seven tabs, and is rewritten.** It showed
resuming by calling `ask` again on the same conversation id. That is not a resume: the stored
transcript already holds the halted call's placeholder, so the tool never re-runs, your `waitFor` is
never called, and the run returns `"done"` with the model answering off the placeholder. The
suspension page now shows two honest postures — a **deferred `waitFor`** that parks the run on a
promise/future/channel and works in all seven ports, and the **answer-carrying resume**, which
exists in `golang` only and is labelled as the preview it is. SPEC §10 no longer says "every port
provides" two lines under its own "golang only" banner, and that banner now names Clojure too.

**Provider error messages no longer carry your account id.** A non-2xx from the LLM endpoint rode
out verbatim in every port — including, in the reported case, a `user_id` — as a bare string a host
had to parse and scrub itself. Failures are now typed values carrying `status`, `body` and
`retryAfter` as fields; `user_id`, `account_id`, `org_id` and `organization` are replaced with
`«redacted»` in **both** the message and the typed `body`; `401`/`403` bodies are blanked; and the
message's excerpt is capped at 200 characters while the typed field keeps the whole redacted body.
A length cap was never the protection — the leaking body in the report was 96 bytes. The SPEC
credentials guarantee now covers error messages, not only headers and `apiKeyEnv`.

**Two different closed sets were both called `status`, and one of them contains `timeout`.** The
agent runtime's `TaskResult.status` has seven values including `timeout`; the client's
`RunResult.status` has three and does not. Branching on the documented set from the wrong section
is what one report was actually about. No public field is renamed — that would break every host to
fix a documentation failure. Instead both vocabularies ship as **named constants** in every port
and are documented as distinct in SPEC §8 and §7D. Relatedly, a Go run that hit its own deadline
returned a zero-value result beside an error; it now returns `status: "incomplete"` with
`limit: "timeout"` and keeps the turns and usage it accumulated. Clojure gains a run-level deadline
it never had, and no longer *retries* a timeout. Elixir's bare `RuntimeError` on timeout becomes a
typed error. The classifier keeps `jev-latest` and gains a `backend` preset (`typesafe` |
`openrouter`) that sets base URL, model and key-env name as a unit, failing at construction on the
known model/backend mismatch rather than at the first call.

**Six more of your skills load.** Frontmatter that real skill-writing tools emit — an unquoted
`description` containing a `:` — was refused outright. Parsing now tries **strict YAML first** and
falls back to a guarded line-wise read only on frontmatter YAML has already refused (column-0 keys,
first wins, any value opening with `|`, `>`, `&`, `*`, `[`, `{` or `!` refused rather than
mis-read), so block scalars stay byte-identical and genuinely broken files are still skipped with
no invented description. On a 87-skill corpus that is 81 → 87. Clojure's hand-rolled frontmatter
reader is replaced with real YAML on both hosts: it loaded 33 of those 87 where the other ports
loaded 69. **Skips now come back as data** on the load result (`location`, `reason`, and a new
`detail` carrying the native parser's error) — you no longer need the inventory surface to discover
that six skills vanished. And **discovery order is now specified**, not left to each ecosystem's
directory walk: roots in the order you passed them, then lexicographic by path within each root.
Duplicate names still resolve first-wins, but "first" now means the same file in every port, and
the rule is the one everyone assumed was already true: **a top-level skill beats a nested copy of
the same name — uniformly, for every name.** Sorting is by **depth first**, then Unicode code point
within a depth. Depth has to be its own key: a pure alphabetical sort silently made the winner
depend on the skill's first letter, so `docx`, `pdf` and `pptx` beat `synced/<uuid>/…` while
`xlsx` lost to it. **Every nested duplicate now loses**, so a duplicated skill's `content` and
`location` can change under you: in `js` that is every duplicated name, and `golang` and `elixir`
change wherever they kept a nested copy. If you rely on a duplicate resolving a particular way,
check which file wins now. (The code-point tie-break, rather than the UTF-16 code-unit default,
matters only for filenames above U+FFFF.)

**The model now sees the same files on your machine and on CI.** Two defects, both in the same
place — what a listing contains, not merely how it is ordered:

- **`glob`, `grep` and the `<skill_files>` sample now sort BEFORE they cap.** They previously broke
  the walk at the limit, so the **filesystem** chose which results the model was shown: a different
  set on a different machine, and on some filesystems a different set between two runs of the same
  code. The rule is now one rule everywhere — collect everything, sort by the path relative to the
  walk root in code-point order, then truncate. This is ADR-0004's `K1` "sort-before-sample parity
  bug", open since that triage, closed in every port. **It changes which files appear, not just
  their order**, so a prompt that depended on a truncated listing may now carry different content.
- **`grep` emits a relative, `/`-separated path**: `rel:line:text` where it used to print an
  absolute one. Five ports had the identical defect — they **sorted on the relative path and
  emitted the absolute one**, ordering by one thing while displaying another, and leaking machine
  paths into model-visible output. **If you parse `grep` output, the string has changed.** Windows
  listings now use `/` like everywhere else.

**The skills prompt is ordered the same way on every machine.** The `## Available Skills` catalog —
which `SPEC §0.10` pins byte-identical across ports — was sorted three different ways, and JS's was
locale-dependent: the same code, the same `skills/` directory, a different order on a machine with
different ICU data. All seven ports now sort by Unicode code point over the skill name, as does the
`skill` tool's "Available: …" list when a skill is not found.

#### What this batch does NOT do

- **Transcript replay on resume is deferred** (`openspec/changes/fix-consumer-issues-86-93`, D3).
  A durable resume still rewinds the suspended turn to its pre-turn checkpoint and replays it, so
  **every tool that ran in that turn runs again**; reattachment-by-task-key protects `task` calls
  and nothing else. A leaf's own `git push` is yours to guard. That is now stated loudly in SPEC
  §7D, on the sub-agents and orchestrator pages, and in the `waitFor` doc comments — because it is
  a conformance decision, not a bug, and changing it needs its own spec delta.
- **Non-relay durable resumes still splice, they do not re-execute** (D4). On the golang durable
  path the recognised payload is spliced into the transcript as the call's result; the tool is not
  re-run and never sees `ctx.answer`, unlike the inline `waitFor` path, which does. Making
  `kind: "input"`/`"approval"`/`"authorization"` resume by re-execution is deferred to its own
  change and is the higher-value of the two.
- **Durable resume is still `golang` only.** `js`, `python`, `java`, `csharp`, `elixir` and
  `clojure` can raise a durable halt but have no answer-carrying way back in
  (`openspec/changes/add-tool-relay-mode/tasks.md`). Use the deferred-`waitFor` posture, which is
  at parity.
- **Where an admission refusal surfaces is unchanged.** Three of the nine `limit` values —
  `maxChildren`, `maxConcurrent`, `maxDepth` — are spawn/admission refusals, and in `js` and
  `csharp` they come back as a **verb error**, never a settled `TaskResult` with a `limit` on it.
  This batch pins the *spelling* wherever a port reports such a stop; it does not move any of them
  onto a settled result, and no port invented a settle path to make the string appear. Whether an
  admission refusal should be an error or a result is a real cross-port asymmetry that lives in the
  verb's **return type**, not in the vocabulary — so it needs its own change, and is tracked as a
  follow-up.
- **The `<skill_files>` sample still emits ABSOLUTE paths**, on every port. It is the one listing
  exempted from the relative-path rule above, and deliberately so for now: `SPEC §3` pins the
  block's shape byte-identical, so a port that switched to relative paths alone would break that
  identity — and unlike `grep`, there is no sort-key/emitted-string mismatch here, because every
  entry shares the skill-directory prefix and both orderings agree. Making it relative needs a
  seven-port decision, not a port-local fix.
- **The classifier's canonical-JSON key ordering is not unified.** `js` orders keys by UTF-16 code
  unit and `golang` byte-wise over UTF-8 — identical for every realistic key, since these are
  schema names, so nothing observable differs today. It is still a genuine cross-port byte-identity
  path, and changing it in one port would *create* the drift it exists to prevent, so it needs a
  coordinated ruling of its own.
- **Nothing stops a *third* vocabulary landing on a field named `status`.** The named constants pin
  today's two sets; no conformance row pins the invariant. Tracked as a follow-up.
### `retries: 0` now reliably means zero retries, in every port, pinned by a test

A consumer building a CLI-backed model (issue #94) asked for 3 attempts and measured 9 backend
invocations — each of their 3 attempts retried twice, at ~15s per process launch, because
`retries: 0` had never meant "no retries" anywhere it wasn't already spelled that way. A spike
(`spikes/retries-zero/SPIKE.md`) found the contract already held in five ports (js/python/java/
csharp/elixir all distinguish an unset field from an explicit `0` through their own idiom) and
was broken in two, in opposite directions:

- **golang** — `Retries` is a bare `int`; its zero value was indistinguishable from "unset", so
  `Retries: 0` silently became the default of 2 retries. Fixed by adding `Retries: -1` as the
  explicit "no retries" spelling; `0` is unchanged and still means the default. `CreateInProcessClient`
  now passes `Retries: -1` directly instead of forcing a private `OnError: TierFail` workaround.
- **clojure** — the opposite bug: this port's shipped default was **0 retries**, not 2, so an
  unset `:retries` client made 1/3 the LLM calls on a transient failure that every other port
  made. Fixed to default to 2, matching the other six ports.

All seven ports now carry two tests each pinning the contract: an explicit "no retries" makes
exactly one backend invocation, and the default (unset) makes exactly three total attempts.

### One model function now serves a top-level client *and* its sub-agents

`createInProcessClient` (0.16.0) let you hand the library a `generate` function instead of an
HTTP endpoint. Sub-agents never got the same option: a runtime accepted only a wire-shaped
transport, so a host whose model was a function had exactly one way to use sub-agents — copy the
library's *private* request/response adapter into its own tree and track an unexported file it
could not import (issue #95). That copy was guaranteed to drift the first time the shape changed
upstream, which is the single failure this repo exists to prevent.

An audit found the gap in **all seven ports**, not just the one that reported it: every port
accepted only a transport, and every port already built an equivalent generate-backed adapter
privately. So two things change everywhere:

- the agent/sub-agent runtime accepts a semantic `generate` (`InProcess` / `in_process` /
  `:in-process`, per port idiom) as an alternative to a transport or provider config. Supplying
  both fails at construction, naming the conflict — never resolved by a silent precedence rule.
- each port's generate-backed adapter is now **public** (Go `InProcessTransport`, Python
  `InProcessTransport`, Java `InProcess.GenerateBackedHttpClient`, C# `InProcess.GenerateBackedHandler`,
  Elixir `in_process_transport/1`, Clojure `in-process-http-client`), and each port's in-process
  client is a *caller* of it. One adapter, no duplicates.

Concurrency is unaffected: every port's turn gate already wrapped whichever transport resolved, so
the in-process path is gated by construction. Each port ships a gate test with a negative control.

Fixed in passing, **csharp only**: `GenerateBackedHandler.SendAsync` never actually yielded, so
in-process turns ran fully synchronously inside the runtime lock — shipped behaviour since 0.16.0.

### ACP: talk to devin, Gemini CLI or Zed's agents as a model, over one warm session

MCP is how toolnexus consumes *tools*; ACP (Agent Client Protocol) is the equivalent for consuming
a whole *agent* — JSON-RPC 2.0, one object per line, over a child process's stdin/stdout. New in
every port: connect to an ACP agent and use it as the model behind a client (issue #96), so the
tool-calling loop, skills, MCP tools and sub-agents work through it unchanged.

The point is process startup. Driving an agent CLI one-shot pays its launch cost on *every* turn
of the loop; a warm ACP session pays it once. Against `devin` (SWE-1.6 Slow) a turn went from
~15s to ~1.6s by the third prompt, and prompt size turned out to be free — a 12 KB prompt cost no
more than a 17-byte one. **Stated plainly, because it is easy to over-read: that win is the CLI's
startup cost, not a protocol-level speedup.** Against a server with no startup cost the same path
gains almost nothing.

A stateful session collides with how toolnexus assembles a complete request every turn — the
session accumulates near-duplicate histories and the agent will sometimes answer the stale one.
The library sends the full request and marks it as superseding everything earlier; you do not have
to write that line yourself. Sending only the delta is faster still but makes the client own a
shadow transcript that can desynchronise from your `ConversationStore`, so it is not offered.

Also handled, because each one silently breaks a run otherwise: only `agent_message_chunk` forms
the reply (thoughts and tool narration are dropped, or they wrap prose around structured output);
`session/request_permission` is answered rather than awaited (unanswered, a turn hangs forever,
even in bypass mode); turns on one session are serialised; the process outlives any single turn's
cancellation; `close` is idempotent. `session/new` sends an absolute `cwd` and an `mcpServers`
array — real `devin acp` rejects anything else with `-32602`.

**python only**: its in-process seam is synchronous by contract, so the ACP client runs a
background reader thread and a queue rather than widening that seam — `generate` stays an ordinary
synchronous function and nothing async crosses the boundary.

### Not done

- The one-shot CLI-backed model source (issue #97, `docs/adr/0032`) is designed and spiked but
  **not implemented in any port**. ACP covers agents that speak the protocol; a CLI offering only
  `-p` still needs a host-written adapter.
- ACP **delta mode** is not shipped; every turn sends the full request.
- A `generate` that internally relaunches a CLI or repairs a malformed reply reports **one** LLM
  metric event for N underlying calls, so that cost is currently invisible to client metrics.

## 0.18.1 — 2026-09-21

Documentation only. No code changed in any port; the published packages differ from 0.18.0
solely in the README each registry shows you.

### A documentation release no longer pays for a live model call

Every registry leg `needs:` the `live-scenarios` job — a real, paid call to a provider that
proves the harness mechanisms work before anything publishes. That is the right gate for a
release that changes code. For a release that changes only a README or a docs page it re-proves
exactly what the previous release proved, costs money, and adds one more way for a publish to
fail for no reason.

`release.yml` now decides. A new `scope` job diffs the tag against the previous one and sets
`code_changed`; `live-scenarios` runs only when it is true, and every registry leg accepts a
**skipped** live proof while still refusing a **failed** one.

**All seven registries publish either way.** The gate decides whether to re-prove the library,
never whether to ship it — the ports move in lockstep or their version numbers drift apart, which
is the failure `preflight` exists to catch.

Two details that decide whether a gate like this is honest:

- **The version manifests are excluded from the code set, and then put back.** Every release
  bumps all six by definition, so counting them as code would make `code_changed` permanently
  true and the gate would never fire. So each manifest's own diff is re-read, and a manifest that
  changed for anything other than its version string — a new dependency, a changed target
  framework, an edited script — is promoted back to code and the live proof runs.
- **Anything the filter has not seen counts as code.** The doc paths are enumerated; everything
  else, including workflow files, defaults to running the proof. A gate that guesses "probably
  docs" about an unfamiliar path is a gate that eventually skips a real release.

Verified in both directions before shipping: this release classifies as documentation-only, and
`v0.17.0..v0.18.0` — 340 changed files — classifies as code.

### The READMEs are a front page again, not a second copy of the manual

Eight READMEs went from **3249 lines to 594**. Each one now answers what it is, how to install
it, one quick start, and the single thing that port does differently — then points at the
documentation site and that language's API reference for everything else.

Nothing was lost. Every section removed already had its own page: suspension, memory,
observability, native and HTTP tools, attachments, built-ins, A2A, serve-as-MCP,
bring-your-own-loop, sub-agents, streaming. What went was a duplicate that drifted — and the
duplicate nothing checked, since no gate reads a README while the site's 968 documented snippets
are compiled and executed on every change.

Two things that had already drifted, now correct:

- **Every port advertised parity across five languages.** "Also in JavaScript, Python, Go, Java,
  and C#" — Java said "five languages", Elixir said six. There are **seven**; Elixir and Clojure
  had been shipping for releases without appearing in the claim.
- **Clojure's README undersold its own test suite**, quoting "291 tests / 1100 assertions" across
  "all five execution modes" when it is **495 / 2051**, and listing three modes where
  `all-modes-check.sh` runs five — both REPL evaluators were missing.

### Jev, on the typed-decisions page

Jev is the model behind the default `systemone` backend and most of the public interest in it is
game demos, so the page now says plainly what toolnexus does with it: **Jev is one backend behind
a vendor-neutral seam, not an integration.** `llm` runs the same questions on any chat model,
`custom` is your own function, `static` replays a recorded corpus offline — swap the backend and
your questions do not change. Links to the wire documentation, the gateway, the live
measurements and the encoding rules, and names both public game repos, including the one that
sends `criteria` with no per-option description — the exact mistake ADR 0021 measured at 17
apples against 0.

### A real call you can watch

[Measured on a live backend](https://muthuishere.github.io/toolnexus/harness/judge-live/) was
170 lines of generated tables and nothing to watch. It now opens with a 12-second recording of
`js/examples/judge.ts`, unmodified, making one real POST to the systemone wire over OpenRouter —
three question types in a single round trip, for $0.000022.

It also demonstrates the page's own argument. The take that shipped answered `urgency` **0.53 —
"level 1: the customer is inconvenienced"**; the take before it, identical bytes and identical
model, answered **0.47 — "level 0: the customer is working normally"**. A score sits between
levels and it moves, which is why no test asserts a live number and why `static` is the backend
CI runs.

Reproduce it with `OPENROUTER_API_KEY=… vhs site/scripts/judge-run.tape`. The tape is in the repo
and holds no credential: the library reads the key at the point of use and it appears in no
output, which is the only reason a recording of a credentialed call can be published.

## 0.18.0 — 2026-09-21

### Fixed — a classifier retry backoff no longer lets Node exit out from under it (javascript)

`Classifier`'s backoff timer was created and then `unref`'d. An unref'd timer does not keep
Node's event loop alive, so when a retry backoff was the only pending work, the loop could
resolve before the retry ever happened — the process exiting mid-retry rather than completing it.
The timer is awaited, so it is real pending work and must hold the loop open; the §8 `Client`'s
own `delay` has never unref'd, for exactly this reason. The request timeout watchdog still
unrefs, which is correct: a watchdog should never be the reason a process stays alive.

JavaScript only — the other six ports use blocking sleeps with no equivalent notion.

Found by CI rather than by reading: Node 22 cancelled every test after the first one to exercise
a classifier retry ("Promise resolution is still pending but the event loop has already
resolved"), reporting `237 passed, 0 failed, 8 cancelled` and exiting 1. Node 24, which the work
was written on, hid it completely. The suite is now 245/245 on both.

### Four quiet disagreements between the ports, closed

Each of these produced a green build and a valid-looking request, which is why they went
unnoticed. Two are contract that was never written down; two are Clojure behaving unlike the
other six.

**`decisions` is now gated.** The `static` backend's recorded corpus is what CI runs on — no
network, no credential, and the only backend a test may assert a number against. Every port had
it; the options manifest had no row for it and `SPEC.md §8B` named the `"static"` style without
naming the field that feeds it. The single option the whole test strategy rests on was the one
option parity could not see. It is now a **core**-tier row (classifier options: 16 → 17), so a
port that drops it fails the check rather than losing the ability to run the shared fixtures
quietly. No port code changed.

**An absent `calibrated` means `true`, and now says so.** All seven ports already decoded a
missing or `null` `calibrated` as `true`, treating only the literal `false` as false — seven
ports agreeing by inspection rather than by contract. `SPEC.md §8B` states it and each port pins
all four cases in a test. Nothing changes today; what changes is that a port cannot later default
it to `false` and silently invert every threshold you tuned, on a field your backend never sent.

**Clojure: `choice-over` no longer ships the colon.** A keyword key reached the wire as
`":billing"` rather than `billing`, because the port stringified ids with `str` instead of their
name. That is schema-valid, returns HTTP 200 and a well-formed distribution — with option ids
that differ from every other port (Elixir's atom `:billing` has always sent `billing`) and from
the string you then compare `(:choice answer)` against. Keys may now be strings, keywords or
symbols, and each travels as its plain name; a qualifier is kept (`:desk/billing` ⇒
`desk/billing`) so two distinct options cannot collide into one id. **If you keyed a downstream
branch on the `":billing"` form, drop the colon.** If you passed strings — which the port's own
docs told you to — nothing changes.

**Clojure: the client's retry backoff matches the other six.** `:retry-base-ms` defaulted to
`250` where every other port defaults to `500`, and the backoff omitted the `+ jitter(0–99 ms)`
term the others add. The jitter is not cosmetic: without it, a fleet of hosts that failed against
the same upstream at the same moment retries against it at the same moment, so the retry turns one
spike into several. Clojure now waits `base * 2^attempt + jitter(0–99 ms)` from a default of
`500`, and a usable `Retry-After` still wins outright. **A Clojure host that never set
`:retry-base-ms` now waits ~500 ms before its first retry instead of ~250 ms.**

Tracked in `openspec/changes/close-typed-decision-parity-gaps`.

**Clojure's `Classifier` example now runs on both hosts.** `examples.judge` was in the JVM
runner and nowhere in the cljgo one — no `run_judge.cljc` entry, no `ex-judge` build target — so
the port's whole claim, one source tree behaving identically on two hosts, went unchecked for its
newest subsystem. It is now the seventh example in both runners, AOT and interpreted, and CI runs
both. All seven pass on both hosts.

### The classifier's retry backoff is yours to set, in every port

`Classifier` retried a transient failure on a backoff base nobody could change: a hardcoded
`500 ms`, so two retries cost `1.5 s` of real waiting. Only JavaScript exposed a
`retryBaseMs` for it — one port with an option the other six lacked, on a seam `SPEC.md §8B`
says mirrors `ClientOptions` field-for-field.

`ClassifierOptions` now carries it everywhere, spelled natively: `retryBaseMs` (JavaScript, Java),
`RetryBaseMs` (Go, C#), `retry_base_ms` (Python, Elixir), `:retry-base-ms` (Clojure). The default
is `500`, the delay is still `base * 2^attempt` with no jitter, and a `Retry-After` header still
wins over it — so if you do not set the option, nothing about your timing changes. Set it to `1`
and a test that exercises the retry path stops sleeping: our own classifier retry tests dropped
from 1.51 s to 0.01 s (Go) and 2.56 s to 0.05 s (Java), and the C#, Elixir and Clojure suites no
longer fake a `Retry-After: 0` header to stay off the clock.

The option is registered in `conformance/options_manifest.json` (classifier options: 15 → 16), so
a port that forgets it now fails the parity check instead of drifting quietly.

**Follow-up, now shipped:** this entry originally reported two unfixed differences in the
*client's* backoff on Clojure. Both are closed below, under "Four quiet disagreements".

### TypeSafe's own API is a documented way to run a `Classifier` — and two defects it exposed

`style: "systemone"` already reached TypeSafe's first-party API by default
(`https://api.typesafe.ai/v1`, model `jev-latest`, `TYPESAFE_API_KEY`), but everything written down
pointed at OpenRouter's gateway. Both are now documented side by side on
[Backends & configuration](https://muthuishere.github.io/toolnexus/judge/backends/), and every
port's `examples/judge.*` picks its backend from the environment: `TYPESAFE_API_KEY` first,
`OPENROUTER_API_KEY` second, and with no key at all the existing offline `static` replay, exactly
as before. No default changed, and no new API was needed — `baseUrl`, `model` and `apiKeyEnv` were
already enough.

They are **equivalent in latency** (339 ms / 449 ms p50 / p95 against 351 ms / 400 ms, warm and
interleaved — a tie). The reason to use the first-party key is one fewer party in the path, not
speed. The one functional difference: **TypeSafe returns no `usage.cost`**, so a cost-based budget
only works through the gateway.

Pointing at it turned up two real defects, fixed in all seven ports:

- **`529 Overloaded` was not retryable, anywhere.** The default retryable set was
  `429/500/502/503/504`, so TypeSafe's documented "retry with backoff" status failed hard on the
  first attempt. `529` is now in the set — on the client path and the classifier path alike — and
  that is the whole behaviour change: **no status other than `529` changed classification.** The
  set stays an exhaustive enumeration rather than "any 5xx", because sweeping in permanently-broken
  statuses like `501 Not Implemented` would change the retry behaviour of every existing host
  without asking.
- **`retryableStatuses`, a new option on both `ClientOptions` and `ClassifierOptions`** (named per
  port: `RetryableStatuses`, `retryable_statuses`, `:retryable-statuses`). It is how a backend with
  its own transient status opts in — the case it exists for is a Cloudflare-fronted origin
  answering `520`–`527`:

  ```js
  createClient({ retryableStatuses: [520, 521, 522, 523, 524, 525, 526, 527] })
  ```

  It is **additive and cannot subtract**: listing statuses never removes `429` from the set, so you
  cannot accidentally lose `Retry-After` handling by using it. It decides the *default*
  classification only — `onError` still runs on every failed attempt and has the final say, so
  `onError` returning `"fail"` overrides a status you listed yourself. Registered in
  `conformance/options_manifest.json`, so the parity check covers it in all seven ports.
- **The docs were wrong about the retry set, and had been all along.** `SPEC.md` §8, §8B's option
  table and every port's `retries` doc comment said "429/5xx/network" while the code enumerated
  five statuses. The prose now states the real set (`429`/`500`/`502`/`503`/`504`/`529`, plus `408`
  on the classifier path) and points at `retryableStatuses` for anything beyond it. **No behaviour
  changed to make that true** — the documentation was corrected to the code, not the reverse.
- **"cost absent" was not representable in Go or C#.** `ClassifierUsage.Cost` was a bare `float64`
  / `double`, so against a backend that reports no cost both said **$0.00, as though the call were
  free**, when the truth was "unknown". Go now exposes `*float64` and C# `double?`, matching
  js/python/java/elixir/clojure, and a real `0` is still a real `0`. **This is a source-breaking
  change for Go and C# hosts that read `Usage.Cost`** — dereference or null-check it.

### Typed decisions — a `Classifier`, in all seven ports

Half the decisions in an agent are not actions, they are **judgments**: is this command risky, does
this turn need the billing skill, how urgent is this ticket. Until now you asked a chat model and
parsed prose back, with a round self-reported confidence and no distribution. `Tool` is the
contract for an action; `Classifier` is the contract for a judgment.

You declare typed questions once and get calibrated numbers back — no free text, no tool calling,
no loop:

- **`noul`** — one probability in `0..1`. It reports no confidence; the number *is* the answer.
- **`choice`** — one of your named options (up to 255), with a probability for **every** offered
  option and a confidence.
- **`score`** — a rating against an ordered rubric of 2–10 levels, and `1.21` is a real answer.

The keys you address the questions by are never transmitted, so a key may be a tool, skill or agent
name verbatim. Limits are enforced client-side before the request, and the error names the question
key rather than making you read the backend's 400. Four backends: `systemone` (the wire),
`llm` (the same questions as one structured-output call on the §8 client you already have — the
exit for a host with no System One credential), `custom`, and `static`, which is what CI runs: no
network, no credential. `ClassifierOptions` mirrors `ClientOptions` field-for-field, reusing the §8
`onError`/`Retry-After` policy verbatim rather than growing a second one — with two deliberate
differences: `apiKeyEnv` takes the **name** of an environment variable rather than a value, and
`timeout` bounds one request rather than a run.

**Two things in it exist because they were measured, not designed.** Describing your options is not
style advice: options described by consequence scored 17, 17, 17 apples, and the identical state
with each option replaced by its own id scored 0, 1, 0 — the floor of a shuffle control. That shape
is schema-valid and returns HTTP 200, so nothing would have told you. Now something does: a `choice`
whose criteria are all empty, all equal to their own keys, or all identical emits **one** warning
per question key through your existing metric sink, naming the key, and sends the request
byte-unchanged — detection, never repair. That warning arrives as a `classifier.warning` event
carrying its text in a **`warning`** field, never in `error`: it is advisory, not a failure, so a
consumer already counting failures off the same sink does not start counting warnings as outages. And every choice answer carries a derived `nearUniform`
(`max|p − 1/n| ≤ 0.05`), the one encoding health check that needs no ground truth and can run on
live traffic.

Both are **advisory, and neither is a correctness signal**. `nearUniform` cannot separate a good
encoding from a subtly wrong one, and confidence is no help either — the worst *working* encoding
measured carried the highest median confidence. More bluntly: a classifier **interprets, it never
authorises**. "Cannot hallucinate" means only that the value is inside the declared schema. Numeric
limits, permission checks and allowlists stay in your code, and a threshold tuned against one
backend does not transfer to another — which is what `Decision.calibrated` is for, and why you read
it before you compare a number to a threshold.

Same behaviour in js, python, golang, java, csharp, elixir and clojure, pinned by seven shared
fixtures in `examples/judge/` that every port asserts byte-for-byte. A host that constructs no
`Classifier` behaves byte-identically to a build without any of this, proven by a test rather than
asserted. New docs: **Cookbook → Typed decisions (judge)** and **Harness → Judge, measured live**.
Contract: `SPEC.md` §8B; encoding decisions: `docs/adr/0021`.

**What is NOT done**, and where it is tracked:

- **No adapters and no batteries.** There is no `SkillRelevance`, `ToolGuard` or `Verified` built on
  this seam yet, and no model routing — you write the questions yourself. Tracked in
  `openspec/changes/add-judge` as the follow-up `add-judge-adapters`.
- **The live measurements on the judge page are recorded, not regenerated.** There is no
  `judge-live` harness runner wired into the suite the way `harness/live` has one, so re-running
  them is a manual step. The page says so, per table, with its source named.
- **The `llm` backend is a compatibility exit, not an equivalent.** It reports
  `calibrated: false`, returns no real distribution, and on one measured fixture disagreed outright
  with the calibrated backend.
- The parity gate's temporary `landing` flag — which let `conformance/check_options_parity.py`
  report a not-yet-written port file as something other than a failure while these seven ports were
  being written — **is deleted with this change**, along with the code path that honoured it. A
  missing options file is a failure again, for every group.

### Spec — a `beforeTool` hook can raise a suspension, and now the contract says so

`SPEC.md` §10 defined a suspension by the *result* — a `ToolResult` whose `metadata.pending` is a
`Request` — but only ever described a tool producing one. A `beforeTool` hook short-circuiting
with that same result suspends the run identically, and the guarded tool never executes. Five
ports already implemented and tested it; it was simply never written down, so nothing said it was
guaranteed rather than incidental.

§10 now names both paths (A: the tool returns it, B: a hook short-circuits with it) and requires
every port to resolve a hook-raised suspension through `waitFor` exactly as it resolves a
tool-raised one, and to halt with the hook's own `Request` when no `waitFor` is set. This is what
a three-state policy gate needs: a guardrail returns a string and has only allow and deny, so
"ask a human first" belongs on `beforeTool`. Two coverage gaps closed with it — Go lacked the
hook-raised-plus-no-`waitFor` corner (the one a durable approval queue actually ships on), and
Elixir had no path-B test at all.

### Fixed — a guardrail that is not a plain string is refused, loudly, in every port

A `Guardrail` returns a verdict string synchronously: `""` or `"allow"` to permit, any other
string to deny. Handing it something else — most naturally an `async` function — did not report
an error anywhere. It silently did the wrong thing, and it did **three different wrong things**
depending on the port:

- **JS and Python** took the deny branch on every call. A `Promise`/coroutine is truthy and is not
  `"allow"`, so an async guardrail **denied every tool call in the run**, with the reason rendered
  as `denied: [object Promise]` / `denied: <coroutine object …>` (Python additionally leaked an
  un-awaited coroutine). An agent wired this way could not call a single tool, and nothing said why.
- **Elixir and Clojure** did the opposite: a non-string verdict failed the `is_binary` / `string?`
  test and fell through as an **allow**, so a policy check silently widened — the one direction a
  guardrail must never fail.
- **Go, Java and C#** were never reachable; their `Guardrail` type returns a `string`.

All four dynamic ports now raise immediately, naming what came back and where asynchronous work
belongs (a `beforeTool` hook, which is awaited). The contract is unchanged and stays synchronous
in all seven ports, so every guardrail that was already correct keeps its exact behaviour; only
the previously-silent mistake is now loud. If you need a judgement that takes I/O — a policy
service, a classifier — put it in `beforeTool` rather than in a guardrail.

### Fixed — `Run`/`Ask` accept a nil toolkit as "no tools" (golang)

`client.Run(ctx, prompt, nil)` panicked with a nil-pointer dereference, although §8 already
defines what an empty tool list does (the `tools` key, and `tool_choice` on the openai style, are
omitted from the request entirely). Passing `nil` is the obvious way to say "this call needs no
tools", so it now means exactly that: `Tools`, `ToOpenAI`, `ToAnthropic`, `ToGemini`,
`SkillsPrompt`, `Get`, `Execute` and `McpStatus` are all nil-safe on the receiver. Building an
empty `Toolkit` still works and is unchanged.

### Docs — the coding-agent scenario no longer says the agent runtime hides the hooks

The page claimed the runtime "does not surface" `beforeTool`/`afterTool`, and listed that under
its honest limits. That stopped being true when the harness and loop shipped: an agent spec takes
`hooks`, which the runtime forwards verbatim, and `guardrails`, which compile into one
`beforeTool`. The page now explains when to wrap a tool and when to use a hook — wrap when the
rule belongs to one tool and should travel with it, hook when the policy spans many — and the
limits list carries the guardrail's synchronous contract instead.

## 0.17.0 — 2026-09-02

### Fixed — an aborted A2A call reports a cancel, not a transport error (all ports)

`SPEC.md` §7A pins the contract: a `ctx` abort means `"A2A task <id> canceled"` with
`metadata.state == "canceled"`. That held when the abort landed *between* polls. When it landed
while a `SendMessage`/`GetTask` request was in flight, four of seven ports reported whatever the
transport happened to raise instead — Java surfaced the literal string
`"java.lang.InterruptedException"`, Go returned `context canceled` with the state still
`submitted`, and Elixir had no abort semantics at all: `Context.signal` existed in the struct and
nothing in the port ever read it.

Every error and exit path of an agent tool's `execute` now re-checks the abort signal before
reporting a transport error, so an abort observed anywhere — between polls, mid-`SendMessage`,
mid-`GetTask` — produces the same result. Each port gained tests for both in-flight cases.

Worth naming because it explains a symptom you may have seen: in the JS port this defect class had
already shown up as a "flaky" test that failed roughly twice in twelve runs. It was not flaky. It
was intermittently catching a real defect, which is what prompted the cross-port audit.

Closes #64. Tracked in `openspec/changes/audit-a2a-cancel-mid-request`.

### Fixed — MCP tools no longer lose their images (all ports)

Every port filtered an MCP `CallToolResult`'s `content[]` down to text parts and **silently
discarded the rest**. Point toolnexus at a screenshot tool, a chart tool, or Playwright MCP and
you got an empty string — no error, no log line, no way to distinguish "the tool returned
nothing" from "your picture was thrown away". It was specified that way (`SPEC.md:218`), so it
was a contract bug, not a slip, and it was identical in js, python, golang, java, csharp, elixir
and clojure.

Non-text content now becomes `ToolResult.parts`: image, audio, embedded resource, and
**`resource_link`** — a fifth block type that every SDK exposes and that we were dropping too.
Parts are collected on **every** branch, including the `structuredContent` and `isError`
short-circuits that returned before the content list was ever read; a server sending structured
content *and* an image kept neither before, and keeps both now. A text-only tool result is
byte-identical to before, down to the absent `parts` key.

**This is a behaviour change.** If you relied on non-text MCP content vanishing, it no longer
does — see the unsupported-part rule below, which is designed so that this cannot break a run
that works today.

### Added — attachments: images, PDFs and audio, in and out (all ports)

You could not hand the loop an image, and a tool could not return one. Both now work.

```js
await client.run(["What is broken in this screenshot?", await attach("./shot.png")], { toolkit })
```

Parts go in the **first** argument, alongside your text, because the order of text and image is
semantic to a model and an `{attachments}` option throws it away. Passing a plain string is
unchanged and byte-identical — no existing call site moves.

A `ContentPart` is `text`, `image`, `file` or `audio`, carrying base64 `data` or a `url` plus a
`mimeType`. **It never holds a filesystem path**: a path does not survive a transcript being
persisted and replayed, or crossing into a subagent, a served toolkit, or an A2A peer. The
convenience lives at the edge instead — the constructors take a path, native bytes, or a `data:`
URL and normalise immediately, so the disk touch is one legible call and the part that reaches
your transcript is portable. Mime type comes from a fixed extension table shared with `read`;
it is never sniffed and never resolved through the platform mime database, whose contents differ
per machine and would quietly break cross-port parity.

**The edge takes what you already have.** A caller holding an `InputStream`, a `FileInfo`, a
`Blob`, an `io.Reader` or an open file handle should not have to convert it by hand — that tax is
paid in every calling program instead of once in the library, and it is the same tax as making
you base64 things yourself. So each port accepts its own native sources: `File`/`Blob`/
`ArrayBuffer` in js, `os.PathLike` and any binary file-like object in python, `io.Reader`/
`fs.File`/`fs.FS` (so an `embed.FS` works) in golang, `java.io.File` and `InputStream` in java,
`FileInfo` and `Stream` in csharp, iodata and `File.Stream` in elixir. Clojure takes a path or
bytes and **says so** — `java.io.File` is JVM-only and cannot appear in dual-host `.cljc`, so
rather than fake it that port gives you a named error telling you what to pass.

The rule is *accept broadly, store narrowly*: whatever goes in, what lands in your transcript is
bytes and a mime type. Streams are read **eagerly at construction** — a part holding a half-read
stream would survive a replay no better than a path does — and a handle you supplied is read, not
closed; disposing it stays yours.

Tools can return parts too — `ToolResult` gains an optional `parts` alongside its still-required
`output`, so the transcript, compaction and any text-only provider keep seeing text.

`read` now returns an image part for a recognised media file. **golang only**: `File(path)`
carries its read error until `RunParts` surfaces it, so attaching a file adds no second `err` to
handle. **python only**: reading a binary file used to raise an unwrapped `UnicodeDecodeError`
straight into the client loop rather than returning an error result; it now returns one.

### Added — a tool that returns an image reaches the model on every provider

The two provider styles disagree, and the disagreement is load-bearing. Anthropic accepts image
blocks inside `tool_result.content`. OpenAI rejects them outright — `Image URLs are only allowed
for messages with role 'user', but this message with role 'tool' contains an image URL`, a hard
400, verified live rather than assumed.

So parts ride natively where the style has a shape for them, and only for `openai` are they
relocated into a single synthetic `user` message after the last tool message, in tool-call order,
each labelled with the tool it came from. Relocating on *every* style would have been simpler to
describe and worse to use: it discards the `tool_use_id` association, breaks cache breakpoints,
and makes the model read tool output as user input. The synthetic message is an adapter artifact
and is never written to `RunResult.messages` or the `ConversationStore`, so switching provider
mid-conversation leaves no OpenAI-shaped residue.

### Changed — an unsupported part is never dropped, and never breaks a working run

Silence is what this whole change exists to remove, so nothing is discarded quietly. But
erroring on everything would have been its own regression: an MCP server that volunteers an audio
clip would start failing runs that succeed today. The rule therefore follows **intent**. A part
*you attached* that the provider cannot represent is a typed error before any HTTP call — you
asked for something specific and silently changing it is the betrayal. A part that merely *arrived
from a tool* degrades to a named placeholder with a warn-once. `onUnsupportedPart: "error" |
"text"` overrides both.

The guard is a positive allowlist over the encoded block, not a mapping that hopes for the best.
That is not caution for its own sake: sending an unrecognised block type upstream returns **HTTP
200 with the content silently discarded** — the same failure this release fixes, one layer up.

`anthropic` names `audio` as a refusal, because the provider defines no audio block.

**clojure only**: that "typed error" is a **value**, not a throw — the port's rule is that nothing
crosses a source boundary as an exception. An unsendable part (attached-and-unsupported, both
`data` and `url`, an unknown extension, over `:max-part-bytes`) comes back as a `RunResult` with
`:status "incomplete"`, `:limit "contentPart"` and an `:error {:code :message}`, and no HTTP
request is made. The edge constructors follow the same rule: they return an error part carrying
the reason rather than raising, the way the Go port's `File(path)` defers its read error.

### Changed — content parts survive translation (`SPEC.md` §11)

The spec said an array `content` is flattened to text. Six of seven ports actually passed a
text-empty array through raw and undocumented — one of them with a comment explaining it. Both
the spec and the code now carry one rule: text parts concatenate, non-text parts translate. The
seven ports agree by specification rather than by coincidence.

### Known upstream hazard — OpenRouter drops images to Anthropic models

Verified live, and worth knowing before it wastes your afternoon: an image sent to
`openrouter.ai` for an **Anthropic** model arrives as **HTTP 200 with the image silently
discarded**. Measured with the 82-byte `examples/media/fixture.png`, as a prompt-token delta:

| model, via OpenRouter | delta | |
|---|---|---|
| `anthropic/claude-haiku-4.5` | **+4** | dropped (5/5 trials) |
| `anthropic/claude-sonnet-4.5` | **+4** | dropped |
| `openai/gpt-4o-mini` | +8500 | delivered |
| `google/gemini-2.5-flash-lite` | +258 | delivered |

**Switching to `style: "anthropic"` does not help** — OpenRouter's Anthropic-compatible
`/v1/messages` drops it the same way (14 → 18 input tokens for a hand-written native
`source{}` block with no toolnexus in the path). The route is the problem, not the block
shape. To send images to an Anthropic model, point `baseUrl` at `api.anthropic.com`
directly.

Nothing in toolnexus can fix this; the block we emit is the documented one, and the loop
completes correctly. The danger is that the model then *answers anyway* — ours replied
"Blue, Red, Yellow, Green" about an image it never received. That is why
`scripts/live-multimodal-check.py` and every port's `examples/multimodal.*` prove arrival by
**prompt-token delta** and never by reading the reply.

### Not done, and where it is tracked

Tracked in `openspec/changes/add-multimodal-content`:

- **Gemini request emission.** No port has a Gemini request path — `ClientStyle` is
  `openai | anthropic`, and `toGemini` only emits tool declarations for a caller's own client.
  Attachments work on both implemented styles; Gemini needs a client first.
- **A2A message parts** and **skill resources as parts** — both still text-only.
- **Provider `fileId`** — the answer to sending the same 5 MB PDF on twenty turns. Deferred.
- **Model image *output*.** Input only.
- **python only**: `countTokens` does not exist in that port, so the compaction mitigation that
  charges for part bytes has nothing to hook into there yet.

## 0.16.0 — 2026-08-17

### Fixed — an in-process client no longer needs an API key

`createInProcessClient` advertised "no `apiKey`" and then failed without one: the client resolves a
key from the environment and errors when it finds none, so an in-process model — which has no
endpoint to authenticate to — still demanded one. Every local run passed because a developer shell
has `OPENROUTER_API_KEY` set; CI, which has none, failed the in-process tests in **all seven ports**
at once. The constructor now supplies its own sentinel so that resolution never runs, and js/python
pin it with a test that strips the variables from the environment first.

### Added — `createInProcessClient`: a model in your process, with no wire to configure (all ports)

Running a model inside your own process was possible, and it made you lie three times: a `baseUrl`
that is never dialled, an `apiKey` for an endpoint with no auth, and a `style` for a wire that does
not exist. Two of those were already optional and were only ever noise in our own docs. `baseUrl`
was genuinely required — and omitting it did not error, it crashed with
`Cannot read properties of undefined (reading 'replace')`.

The bigger tax was the envelope. The seam is HTTP-shaped, so a host that only wanted to answer a
question had to **build an HTTP response first** — a `Response` in JS, an `*http.Response` in Go, an
`HttpResponseMessage` in C#, and in Java a **94-line `HttpClient` subclass**. Every example in our
own cookbook opened by defining the same `LocalModel.generate` wrapper to hide it. When every
example needs the same adapter, the adapter belongs in the library.

```js
createInProcessClient({ model: "my-local", generate: (req) => ({ content: "…" }) })
```

`generate` returns **one assistant message** — `{content}` to finish, `{toolCalls}` to call tools —
plus optional `usage`. The library derives `finish_reason`, builds the `choices` envelope, and
encodes tool arguments unless they are already a string. Tool calls are **flat**
(`{id, name, arguments}`): the nested `function:{}` wrapper is a wire detail, not something a model
author should have to type.

It is an ordinary client. MCP servers, agent skills, sub-agents, hooks, metrics, conversation memory
and the completion gate all behave exactly as against a hosted model, because this is a second
**constructor** built on the shipped transport — not a second seam. That transport is unchanged and
remains the answer for proxy, mTLS, credential injection and record-replay.

Shaped against the ecosystem rather than invented: Vercel's `LanguageModelV4`
(`doGenerate → content/finishReason/usage`), Microsoft.Extensions.AI's `IChatClient` and Pydantic
AI's `Model` all take messages in and a response out, and **none requires a base URL or key** — that
is an SDK-level pattern, not a framework one. All three also ship a built-in fake model, treating
this case as first-class.

**Failures are final, not retried.** A network client rides out transient failures; an in-process
one has no wire, so there is nothing transient — whatever `generate` throws will throw again, and
retrying only buys backoff before you see your own bug. Measured: the streaming refusal took **3.7s**
to surface before this default and **1ms** after. A genuinely flaky model can opt back in with
`retries` — or, in golang, with `OnError`, because that port documents `Retries: 0 ⇒ 2` and zero
cannot mean zero there without changing shipped semantics for every network client.

**Streaming is refused, not faked.** A `generate` returns a whole answer, and a single-chunk stream
is indistinguishable from a real one by content or delta count, so the streaming path raises with a
message naming the limitation. The exception is clojure, which has no streaming entry point at all,
so there is nothing there to refuse — stated rather than papered over.

`docs/adr/0019` rejected a semantic callback as a **replacement** for the transport seam; layering
one on top is a different claim, and the ADR is amended rather than quietly contradicted. Tracked in
`openspec/changes/add-in-process-client`.

## 0.15.0 — 2026-08-17

### Added — a harness, a loop, and a completion gate that survives delegation (all ports)

An agent framework has two things you must be able to name: **the harness** (everything the agent
*may* do — tools, identity, team, ceilings, policy) and **the loop** (a live execution of it).
toolnexus already shipped both and named neither, so every user invented their own vocabulary.
`harness()` now puts the word in the API without adding a type — a spec built through it and one
written inline are indistinguishable, so there is nothing to migrate. `loop()` opens a live
execution that reports `status`, `turns` and an `Outcome` whose stop reason is **always named**.

The loop deliberately takes **no configuration**: capability belongs to the harness, per-call
choices to the run options, and the loop only reports what happened. That is why `model` sits on
the run options — one conversation may change model between turns.

**The part that is genuinely new is the gate.** An agent could report `done` while its own declared
plan was still unfinished. You could bolt a retry loop on the outside, but a host-side loop
**cannot follow a delegation** — when agent A hands work to B via `task`, B runs to completion
inside the runtime and A's caller never sees it. Putting `completion = {verify, maxAttempts}` on
the harness is what makes the check travel with the agent. A built-in verifier, `allTodosDone`,
reads the shipped `todowrite` builtin and requires every declared item to be checked; it is
structural, never learns what a todo *means*, and passes when no plan was declared.

Six rules, each found by prototyping rather than design, and each tested in all seven ports:

- it judges **accumulated** work, or an agent escapes by not re-declaring its plan on the retry
- a non-`done` run is never re-judged, so the gate cannot turn a `pending` into an `incomplete`
- `maxAttempts` is **required** — an unbounded verify loop is a DoS on your own bill
- a failed gate stops **loudly**: `incomplete` plus a structured `limit: "completion"`
- when another limit fires mid-verification, the caller learns **both** reasons
- it reaches delegated children, because it is compiled in at the registry boundary

**Guardrails** ship alongside: policy-only checks on tool calls composed into one `beforeTool` with
**first-deny-wins**, so a later guardrail can never widen an earlier denial.

**Absent ⇒ byte-identical.** No guardrails and no completion is the existing path, unchanged. No new
status strings were minted — `SPEC.md` pins `TaskStatus` across ports, so the gate reuses
`incomplete` and distinguishes itself via `limit`.

### Fixed — `todowrite` did not return its `{todos}` metadata (clojure)

Six ports attach the todo list as result metadata; clojure returned only the rendered text. Nothing
had ever read the plan back, so nothing caught it — until the completion gate, which uses exactly
that metadata to see which items are still open. In clojure the gate therefore passed an unfinished
plan silently. Fixed, with the parity note recorded in the builtin itself.

Tracked in `openspec/changes/add-harness-and-loop`.

### Fixed — `Retry-After` meant seven different things in seven ports (all ports)

`SPEC.md` says the LLM request retries "honoring `Retry-After`". Every port implemented that
sentence, and no two of them agreed on what a header value means. Handed the same `429`, the ports
waited for different lengths of time — and three of them could be made to misbehave outright:

- **Fractional values split three ways.** `Retry-After: 5.9` waited 5.9s in python and js, **5s in
  elixir** (`Integer.parse` stops at the first non-digit, so it silently truncated), and fell back
  to backoff in golang, java, csharp and clojure.
- **A negative value produced an immediate retry** in js and csharp — a client hot-looping against
  a server that had just asked it to slow down — and a **negative sleep** in python.
- **An absurd value crashed the run** in java (`NumberFormatException` thrown from inside the retry
  path) and in clojure (`parse-long` answers nil past a long, and `(* 1000 nil)` throws), and
  parked python, js and elixir on a sleep measured in millennia.
- **`Retry-After: 0`** — the server saying "retry now" — was discarded as "no opinion" by python,
  js and elixir, which then applied backoff anyway.

All seven ports now implement one rule: honour `Retry-After` **only** in its `delay-seconds` form,
a run of ASCII digits (RFC 9110 §10.2.3) in `0 … 2147483647`, waited as exactly that many whole
seconds, including `0`. Everything else — fractional, signed, the HTTP-date form, out of range,
empty, unparseable — falls back to exponential backoff, and can no longer raise, wait a negative
duration, or retry without delay. The HTTP-date form stays deliberately unsupported, uniformly:
it buys one avoided backoff in exchange for portable date parsing in seven languages, and a server
that sends it now gets our backoff rather than a wrong answer in some ports and a crash in others.

**What you may notice:** if a provider sends a fractional `Retry-After`, python and js now back off
instead of waiting the fraction, and elixir no longer truncates it. Every port gains a regression
test pinning the same table of inputs — there was previously **no test for this in any port**, which
is why the drift survived seven ports and a conformance suite. Tracked in
`openspec/changes/fix-retry-after-parity`.

**Not done:** an honoured delay is interruptible by the whole-run deadline in five ports (golang
`sleep(ctx, …)`, python `_sleep(…, deadline)`, js `delay(ms, signal)`, java and csharp
`sleep(ms, deadline)`) but **not in elixir (`Process.sleep`) or clojure (`ktime/sleep!`)**, which
sleep the full delay and only notice the deadline on the next attempt. With the range now capped at
~68 years that is a long-tail annoyance rather than a hang, but it is a real remaining divergence.
It is a bounding question rather than a parsing one, so it is deliberately left out of this change
and not yet tracked by its own proposal.

## 0.14.0 — 2026-08-15

### Fixed — cancelling an A2A call reports `canceled` reliably (js)

Aborting an in-flight A2A tool call reported one of three different states depending on when the
signal landed. Between polls it correctly returned `canceled`; if the signal fired while a
`SendMessage`/`GetTask` request was in flight, the aborted fetch threw and the result carried the
last known state (`submitted` or `working`) plus a raw transport error message instead. `SPEC.md`
§7A pins abort ⇒ `"A2A task <id> canceled"`, and the golang port already honored it on that path —
this was **js drifting from the shared contract**, so a host could not treat the metadata state as
meaningful for a cancelled call.

Fixed in `js` only. The other five ports are **not audited** for the same hole; golang is known
correct. If you rely on this, check your port.

### Fixed — compaction no longer orphans a tool result under the Anthropic style

A long-running agent on `style:"anthropic"` could, the first time compaction fired, produce a
transcript the Anthropic API rejects. Compaction keeps the most recent tail and requires that tail
to begin at a `user` turn, which guarantees tool-pair safety in the OpenAI dialect — a tool result
there is a `tool` message. Under Anthropic a tool result *is* a `user` message carrying
`tool_result` blocks, so the tail could begin on the tool result itself while the `assistant`
message holding the matching `tool_use` was summarized away. The provider rejects a `tool_result`
with no `tool_use`, so the agent broke at exactly the point its context filled up.

The tail boundary is now dialect-neutral: a `user` turn carrying tool results is not a boundary, and
the tail extends back to a genuine one (the same "safety over size" fallback the rule already used).
**OpenAI-style transcripts are unaffected** — every `user` message remains a boundary and output is
byte-identical. Fixed in all seven ports.

Not done: the underlying reason the rule could be dialect-bound at all — conversation history is
stored in whichever provider dialect produced it — is tracked in
`openspec/changes/add-canonical-transcript`, which makes a tool result a first-class message kind so
"a user turn" can no longer mean "a tool result". Regression tests currently ship in golang, js and
python; java, csharp, elixir and clojure carry the fix and pass their existing suites, with the
shared fixture tracked in `openspec/changes/fix-compaction-tool-pair-dialect` (task 1.3).

### Changed — performance benchmarks re-run in full, and Clojure joins the table

The whole benchmark suite was re-measured in **one sitting on 2 August 2026** — 39 framework
configurations across seven languages — so no cell on the results page is a splice of two
different days or two different toolchains. `benchmarks/results.json`,
`docs/performance-benchmarks.md` and the site's [Performance](https://muthuishere.github.io/toolnexus/performance/)
page all carry the same run. Nothing was skipped; `results.json` now records a `skipped` list
so that a framework that fails to stand up in a future run is named rather than quietly missing.

- **A Clojure runner exists** (`benchmarks/run_toolnexus_clojure/`) and measures **both hosts**
  from one `.cljc` file — `toolnexus-clojure-jvm` via `clojure -M`, `toolnexus-clojure-cljgo` via
  a `cljgo build` AOT binary. The performance page had deliberately stayed six-language because
  no seventh runner existed; it is seven-language now for exactly that reason and no other.
- **What the Clojure numbers say, plainly: it is the slowest port on the page.** ~5.5 ms p50 over
  MCP on both hosts, against 1.0 ms for Python and 0.49 ms for Go. The loop is not the problem —
  with native tools the same port runs the scenario in 1.7 ms; the shared stdio MCP server adds
  ~2 ms *per tool call*, where Go pays 0.07 ms for identical wire traffic. That points at the
  port's stdio JSON-RPC path (blocking line-read round trip plus the pure-Clojure JSON codec) and
  is the biggest single optimisation target the benchmark has surfaced. Published, not massaged.
- **The two hosts agree to within 0.2 ms** from the same source, which is the port's whole claim
  measured under load. Where they diverge is startup and memory, both favouring cljgo: process
  cold start ~1.13 s (JVM) vs ~0.02 s (binary), peak RSS ~500 MB vs ~31 MB. The JVM figure is the
  default heap on a 48 GB machine, not memory the port needs — said so on the page rather than
  dropping the column.
- **Clojure p95 is not measured the same way as the other ports**, and the page says so: koine's
  portable monotonic clock is millisecond-resolution on both hosts, so a sample is a batch of 10
  runs divided by 10. Mean is unaffected; p50/p95 are percentiles over batch means and are
  therefore smoother than a per-run percentile.
- **Go publishes a real MCP row again.** The 0.9.0 stdio-MCP bug that forced July's table to show
  a native-tools-only Go number is fixed, so Go now discovers over a live stdio session: 0.49 ms
  p50, the fastest cell on the page, in a 10 MB binary at 17 MB RSS.
- Two competitor edges remain and stay published: **LangChain4j** is 0.04 ms ahead in Java (down
  from 0.78 ms), and **Semantic Kernel**'s native path is 0.03 ms ahead in C# while toolnexus is
  doing live MCP.
- `run_all.py` now registers every runner that exists (JS, Elixir, the Go competitors, Clojure)
  instead of a subset, accepts both runner output shapes, and can run a runner in its own working
  directory. `benchmarks/README.md` documents the full set, plus two install traps found on the
  way: LangGraph and Google ADK need `mcp<2` pinned (2.0.0 removed symbols their adapters
  import), and CrewAI needs the `crewai-tools[mcp]` extra or its MCP adapter aborts on a prompt.

### Fixed

- `js/package-lock.json` had been left at 0.10.0 while `package.json` moved to 0.13.0; running
  `npm install` re-syncs it. Nothing user-visible changed, but a lockfile that disagrees with its
  manifest is the kind of drift a release should not carry.


## 0.13.0 — 2026-08-02

### Added — Clojure, the 7th language (tier `full`, not yet published)

One `.cljc` source tree on **two runtimes**: Clojure on the JVM and
[cljgo](https://github.com/muthuishere/cljgo) (Clojure hosted on Go). Not two implementations
that agree — *one* implementation, byte-identical on both, with **zero reader conditionals**;
every host difference lives behind [koine](https://github.com/muthuishere/koine), which is the
port's only third-party dependency.

Implemented: SPEC §0.1–0.2 (Tool/ToolResult, sanitize), §0.3+§2 (MCP over stdio **and**
streamable-HTTP, per-source isolation), §0.5/§0.6/§3 (agent skills, byte-exact `skill` output),
§0.7 (OpenAI/Anthropic/Gemini adapters), §0.8/§0.9 (native + HTTP tools), §0.10/§8 (the client
loop, both provider styles, parallel tool calls), §0.11/§4A (builtins and MCP precedence),
§0.12/§10 (suspension), §7A/§7B/§7C (A2A out, `serve` in, MCP server in).

Also landed from the shared capability specs: `:request-params`, `:body-transform`,
`:http-client`, `:retries`/`:retry-base-ms`, `:timeout-ms`, `:on-error` (retry|fail — no
failure-originated suspend tier), `:on-metric`, `:store` + conversation memory.

**The port is at tier `full`** — every logical client and toolkit option present, zero
permitted absences, the same bar as the six shipped ports.

Since landed: **client hooks** (`:before-llm` / `:after-llm` / `:before-tool` / `:after-tool`),
**§11 single-turn translation**, the **MCP elicitation bridge** (§2/§10, on **both**
transports — see below), and all twelve toolkit options (`:skill-provider`, `:skills-filter`,
`:skill-sample-limit`, data skills, `:agents`, toolkit `:wait-for`, `:disable-tools` /
`:disable-skills`).

**Still absent, and the option gate structurally cannot see any of it** (it compares option
NAMES in two files, so a missing subsystem has no names to compare): the agent runtime (§7D) and
sub-agents, plus the two §7E entry points that need them (`from-dir`, `start-agent`). Context
compaction (§7F) and the rest of agent home shipped — see the next entry. Those remaining gaps
are what hold this port back from Clojars: not a tier downgrade, a subsystem not yet written.
Note `:agents` is the **A2A** option (remote agents behind an Agent Card),
which is a different capability from `openspec/specs/subagents` — that one remains unshipped
here and is not satisfied by it. `clojure.core/agent` exists on both hosts, so a future
subagents entry point cannot be named `agent`.

**MCP elicitation now works on streamable-HTTP too, not just stdio** — the gap reported here
previously is closed. It was koine's, not §2's: `koine.http/request` buffers the whole body (so a
server→client reverse request arriving mid-`tools/call` can never be seen in time) while
`koine.stream/sse-post` streamed but exposed no response headers — and MCP carries session
identity in the `Mcp-Session-Id` RESPONSE header that the reply must echo, so a consumer had to
choose between streaming and the session id. koine 0.10.0 added `{:on-open f}` to `sse-post`,
applied once to `{:status :headers}` while the stream is still open. The HTTP transport now
switches to the streaming leg as soon as a server answers in `text/event-stream`, maps an
`elicitation/create` onto the same one §10 `waitFor` as stdio (form ⇒ `kind:"input"` with
`requestedSchema` in `data.schema`; URL ⇒ `kind:"authorization"`), and posts the Answer back on
its own request carrying the session id — **inline**, so the in-flight `tools/call` resumes and
the tool is not re-executed. A JSON-only peer keeps the buffered leg unchanged.

**A silent cross-host bug fixed with it: response header CASING.** The two runtimes' HTTP clients
disagreed about the case of the names they hand back — `java.net.http` lowercases, Go's
`http.Header` canonicalises — so `(get (:headers res) "Mcp-Session-Id")` found the value on cljgo
and nil on the JVM, and the lowercase spelling did the exact reverse. No portable spelling
existed, and it failed silently, because a missing header and a mis-cased one are both nil: the
client would simply stop echoing the session id and the server would start a new session per
request. koine 0.10.0 lowercases response header names on every host and adds
`koine.http/header` for a case-insensitive read; every response-header read in the port (MCP
session id, MCP content type, the client loop's `Retry-After`) now goes through it, and the
port's own private copy of that normalisation is gone. Regression-tested against a real
loopback peer that issues the SAME session id under two different spellings of the header name —
either spelling alone is a state where a correct and a broken client coincide on one of the two
hosts.

**§11 divergence, recorded not resolved.** SPEC §11 says any tool call ⇒ `finishReason`
`"tool_calls"`. js, go, python and elixir all prefer the provider's own `finish_reason` when
present, so an OpenAI-style provider returning `"stop"` alongside tool calls yields `"stop"` —
the prose is violated in four shipped ports. The Clojure port matches the five ports, not the
prose. Correcting it is a cross-port change.

**Verified in five execution modes**, not two: `jvm-main`, `jvm-repl`, `cljgo-aot`, `cljgo-run`,
`cljgo-repl` — a REPL is where a human meets a library, and cljgo's own ADR 0007 calls a
REPL-vs-binary divergence unforgivable.

### Added — §7F context compaction and §7E agent home, in Clojure

`toolnexus.agents.compaction/compactor` returns a `:before-llm` hook that summarises the older
transcript and keeps a recent tail, so a long-lived agent stays inside the model's context
window. Below `:max-tokens` it is a no-op and the run is byte-identical to one with no
compactor. `toolnexus.agents.home/compose-soul` composes a persona's bootstrap files into one
system prompt — the directory is the agent — and `home/memory-tool` gives it durable notes it
edits itself, with a write landing on disk immediately but loading only at the start of the next
session.

`from-dir` and `start-agent` (the heartbeat) are the two §7E entry points still missing here:
both compose an agent definition, so both need the §7D runtime this port does not ship yet.

Two upstream defects were found by writing these:

- **cljgo: a descending `range` was an inconsistent seq** — `(range 6 1 -1)` counted 5 and
  mapped to five elements while `seq`/`vec`/`doall`/`some`/`filter` traversed it as `(6)`, and
  the 2-arity `(reduce + coll)` returned `6`, the first element. Nothing threw, so any code
  walking a collection backwards was silently wrong on cljgo and right on the JVM. Root-caused to
  three ascending-only comparisons in `LongRange`, fixed in cljgo v0.9.0 (PR #194).

  One correction, since the first version of this entry got it wrong and koine caught it: the
  **seeded 3-arity `(reduce f init coll)` was CORRECT** at the Clojure level — `clojure.core`
  does not route it through the broken method for this type. The broken Go method
  (`LongRange.ReduceInit`) is real, and our Go-level test measured it returning `init`; the
  Clojure surface simply never reached it. Both measurements were right about different layers,
  and only the Clojure one is what a user could hit — so an audit grepping for a seed-returning
  `reduce` would clear code that is actually broken and miss `(reduce f coll)`, the form that
  failed.
- **The documented Clojure examples did not all run.** They do now: `site/tests/runners/clojure.sh`
  executes every one of them four ways — JVM main, JVM REPL, cljgo interpreted, cljgo AOT —
  and caught a call to a function that does not exist, an invented `build.cljgo` verb, and a
  broken success contract, all in freshly written documentation.

`clojure/examples/clj/` and `clojure/examples/cljgo/` are two projects over one symlinked source
tree with five runnable examples each (MCP + skills + native, native/HTTP tools, progressive
disclosure, persona memory, compaction), verified in CI on both hosts.

### Added — Clojure: the §7D runtime completed, and an adversarial audit paid for itself

**The agent layer is now whole.** `from-dir` (the directory is the agent) and `start-agent`
(the heartbeat, on the runtime's injectable clock — deterministic under a virtual clock) landed
on the §7D runtime, plus `:on-budget` — the §7D host budget callback
(`stop | extend | suspend`, "suspend" parking on a §10 approval). js and golang already ship
`onBudget`; **python, java, csharp and elixir do not** — a pre-existing gap now named here so it
cannot go quiet. The runtime's previously-untested edges (maxWallMs, the tool-call pool, forced
close, wake-on-closed, model inherit, def-level on-metric) are each covered by a test that was
watched to fail.

**An adversarial audit found three shipped defects**, each proven by mutation before fixing:

- **A throwing §8 tool hook hung the consumer forever, on both hosts** — the try/catch covered
  `tool/execute` only, and both hooks ran outside it, so `deliver` never fired and the `deref`
  never returned. Now every exit delivers, and a hook's throw is rethrown on the calling thread,
  matching the shipped ports.
- **The §4A builtins toggle failed OPEN for string-keyed config** — `{:tools {"bash" false}}`
  and any JSON-read config left all ten builtins armed. Keys are normalised now, the same rule
  the §3 skills filter always had.
- **`write` reported a different byte count on each host, and both were wrong** — `utf8-count`
  folded code units, so one file was "8 bytes" on the JVM, "5" on cljgo, and 6 in truth.

**A whole class fell with them: nine host-dependent sorts.** `sort` orders by UTF-16 unit on
the JVM and UTF-8 byte on cljgo, so every sorted output surface — the §0.6 `<skill_files>`
block, the §3 catalog and not-found list, glob, the §7B Agent Card `skills[]`, and `mcp.json`
server order, where **which server wins a name collision** could depend on the host — now goes
through a code-point comparator. Three sorts were deliberately left: their inputs are
ASCII-by-construction, and a change that cannot be made to fail is not a fix.

**The long-standing "cljgo-only flake" was ours.** A fixture pinned at a fixed relative path let
concurrent suite runs trample each other — one run's delete mid-rebuild while another read,
which also produced our historical short-count aborts. Process-unique temp dirs; proven at
5-concurrent red before, 6-concurrent green after, on the JVM. The load-sensitivity hypothesis
this had fed upstream was withdrawn the same day.

**And the gates that let all of this ship green got teeth**: the suite registry is counted and
cross-checked (a dropped suite now fails by name, not by a floor 3× too loose), the five
execution modes must agree with each other to the assertion, the §0.11 test that asserted an
unreachable collision now drives a real MCP peer, and a new `env-chain-check.sh` proves the
API-key fallback chain from outside with fake keys — the one §8 behaviour no in-process test
can reach. Verified live end-to-end against a real provider on both hosts (2 turns, 1 tool
call, identical output) — the port's first live-LLM run.

393 tests / 1608 assertions, five execution modes, both hosts in exact agreement.

### Changed — cross-port conformance gate

- `conformance/check_options_parity.py` now tokenizes **kebab-case**. It previously split on
  `-`, so a Lisp port could never match an option name and reported all 23 as missing when only
  20 were. Applied by file extension: widening it for C-family languages would glue unrelated
  tokens together and manufacture false PASSES, which is the worse direction for a gate.
- **Port tiers.** A port is held to the tier declared in `conformance/options_manifest.json` —
  `full` (every option) or `core` (the §0 conformance contract). A full-tier option missing from
  a core-tier port is **debt, printed by name on every run**, never a pass: a permitted absence
  that stops being reported is indistinguishable from one that was implemented. The tier lives
  in the shared manifest so lowering the bar is a visible diff the other ports review.

### Changed — docs

- The launch explainer video on the docs site now says **seven** languages, and names Elixir and
  Clojure in the parity scene and Hex and Clojars in the closing registry line. It had been
  recorded when there were five, so the one place a first-time visitor hears the parity claim
  out loud was under-counting the ports by two.

### Known gaps

- **The Clojure port's per-request MCP cost is ~5× the Python port's**, isolated to its stdio
  JSON-RPC path (see the benchmark entry above). No OpenSpec change tracks it yet.
- The option gate compares option **names in two files**, so a port can be missing an entire
  subsystem and still report parity OK. A capability-level check belongs beside it.
- **Sorted output is not seven-port identical above the BMP.** python, go, elixir and now
  clojure order strings by code point; js, java and c# by UTF-16 code unit — so any sorted
  byte-exact surface (the §0.6 `<skill_files>` block, §3 catalogs, adapter order) diverges
  between the two camps for a non-BMP tool or skill name. Harmless for ASCII names, which is
  every name in the shared fixtures. Fixing it means SPEC.md pinning one order and three ports
  moving — a cross-port change, tracked here until an OpenSpec change picks it up. (cljgo
  aligning its `compare` with the JVM, requested upstream, would not close this: it would only
  move clojure between camps.)

## 0.12.0 — 2026-07-30

Adds **single-turn translation** (`SPEC.md` §11, ADR-0011) — the inbound half of the format
adapters. Additive: nothing existing changes, and no port behaves differently unless you call
the new entry point.

`SPEC.md §0` item 7 pinned the adapters as *schema only*: `toOpenAI`/`toAnthropic`/`toGemini`
translate tool declarations **outbound**, and nothing read a provider's tool calls back
**inbound**. Every user of those public functions hit the same wall — they could tell a
provider about their tools but not receive the calls it made. So the library served one
posture well ("the library executes tools in a loop") and the majority posture — *"I want
provider-portable tool calling, but **I** execute the tools"*, the premise of the entire
OpenAI function-calling protocol — not at all.

**New: `translate`** (idiomatic naming per port). Exactly **one** provider call, returned in
OpenAI shape. No agent loop, no tool execution, no conversation state — every call is
self-contained, so it can be run statelessly and scaled horizontally.

- **Request** takes the OpenAI `messages`, `tools` and `tool_choice` **verbatim**, so a caller
  never builds provider-native payloads. It also accepts an ordinary **toolkit** — MCP tools,
  skills, native functions, A2A agents, builtins — which is **declared and never executed**.
  The two tool sources compose.
- **Inbound translation preserves tool structure** that a text flattening destroys: an
  assistant turn's `tool_calls` become native tool-use blocks with `arguments` re-parsed from
  its JSON string into an object; a `tool`-role result becomes a tool-result block keyed by
  `tool_call_id`, **merged into one user turn** when consecutive; `system`/`developer` messages
  are hoisted into the provider's separate field; content-parts arrays are flattened. Both
  `arguments` wire forms (JSON string *and* object) are accepted.
- **Outbound** returns `text`, `toolCalls` with `arguments` as a JSON **string** (the wire
  form, echoable byte-for-byte), a mapped `finishReason` — **any tool call wins, giving
  `"tool_calls"`** — plus `usage`, `model` and the raw response. No tool call is dropped or
  truncated.
- **Shares the loop's infrastructure**: retries/backoff, request-param merging and the `llm`
  observability event. `beforeLLM`/`afterLLM` fire **once**; tool hooks never fire, because no
  tool runs.

**Parity verified by byte-diff, not assumed** (spike 0003). One adversarial fixture hitting
every §11 rule at once was run through all six ports and diffed: `js`, `python`, `java`,
`csharp` and `elixir` are **byte-identical to `golang`**, first diff, no corrections needed.
The agreed output is committed at `docs/spikes/0003-translation-parity-fixture.json` so a
future port or refactor can be checked against it directly.

Test counts: js 13 · python 20 · golang 12 · java 13 · csharp 20 · elixir 27. Every port's
full suite green; Elixir coverage 96.9% (gate 95).

Also adds `golang/examples/translator` — a stateless OpenAI-compatible proxy in ~60 lines.

### Relay tools + durable resume — `golang` ONLY, a preview

`golang/` also gains **relay (declaration-only) tools** and an **answer-carrying durable resume**
(`RelayTool`, `RunWithAnswer`/`AskWithAnswer`) built on the §10 suspension primitive — ADR-0010,
issue #37. **The other five ports do not implement this yet**, so it is deliberately **not** part of
the `SPEC.md §0` conformance contract; the §10 subsections carry a status banner and the remaining
ports are tracked as unchecked tasks in `openspec/changes/add-tool-relay-mode/tasks.md`. Saying so
out loud rather than letting parity drift silently is the point.

If you want cross-port behaviour today, use §11 translation. ADR-0011 explains the split: translation
is the right mechanism when the **caller** owns the conversation (the pass-through posture, ~95% of
proxy traffic), and relay is for **proxy-managed memory**, where toolnexus owns the conversation and
the caller sends only the new message. Two postures, two mechanisms.

`golang` relay is green — 24 tests, `-race` clean, and the pre-existing hardened §10 concurrency
tests pass unmodified.

**Fixed:** `python` pinned `mcp>=1.0.0,<2.0.0`. `mcp` 2.x renamed `streamablehttp_client` to
`streamable_http_client`, which broke `mcp_source.py` at **import** time — the whole package failed
to load for anyone resolving a fresh 2.x. Pinned until the rename is adopted.

## 0.11.0 — 2026-07-26

Makes §7F compaction actually reachable from a §7E persona agent. `SPEC.md §7F` defined
compaction *as* a use of the §8 `beforeLLM` hook, but the §7D agent runtime built each
handle's client internally and forwarded no hooks — so the spec promised a capability its
own runtime could not deliver. All six ports; nothing changes unless you opt in.

### Added

- **The §8 seams on a §7D agent run** (`SPEC.md §7D` "The §8 seams on an agent run",
  OpenSpec change `expose-agent-runtime-hooks`, driven by `docs/adr/0008`). `hooks` and
  `onMetric` are now optional on **both** the agent runtime and an **individual agent
  definition** — spelled as each port already spells them (`hooks`/`onMetric` in js,
  `hooks`/`on_metric` in python and elixir, `Hooks`/`OnMetric` in golang and csharp,
  `hooks(...)`/`onMetric(...)` on the java builder). Four rules hold identically everywhere:
  resolved **def-over-runtime, replace never merge**, each field independently (so an agent
  may override `hooks` and still inherit the runtime's `onMetric`); **forwarded verbatim**,
  never composed, wrapped, reordered, defaulted or read; **not a route** to alter the
  handle's composed soul, its §10 escalating `waitFor`, its turn-gated HTTP seam or the
  runtime-wide store (which is why it is two typed fields and not a `configureClient` escape
  hatch); and **unset ⇒ byte-identical** to a runtime without the fields.

  Per-agent is the point: two agents in one runtime can now carry **different compaction
  budgets**, and a metric sink can attribute events to the agent that produced them. Ships a
  shared `examples/agent-hooks/fixture.json` conformance fixture (scenarios H1–H6 plus four
  invariants) cited by every port's test file.

- **golang: `Runtime.ConversationStore()`.** The other five ports already exposed the
  runtime-wide conversation store (`store` in js, `conversation_store` in python and elixir,
  `conversationStore()` in java, `ConversationStore` in csharp); Go had no accessor, so a
  caller had to inject its own `Options.Store` just to read a handle's transcript. Returns the
  injected store itself when one was supplied. Read handle only — the store is still chosen at
  construction. The obligation is now stated in `SPEC.md §7D` for all six.

### Specified (behavior was already correct, but unpinned)

- **Compaction × §10 suspension.** A turn that compacts and *then* suspends is rewound with
  the rest of the turn: the stored transcript returns to its **full pre-turn** state, the
  compaction is discarded, and the resumed replay compacts again. Every port already behaved
  this way by accident; it is now a requirement with a scenario and a per-port test, so a port
  cannot "optimize" by persisting the compacted head.

### Fixed

- **elixir: a wrong-arity `before_llm` hook was silently ignored.** The client guarded on
  `is_function(f, 1)` and otherwise fell through to the no-op branch — the hook simply never
  ran, with no error. It now raises `ArgumentError`. Much easier to hit now that hooks can
  arrive from two places.
- **java / csharp: hooks could be silently dropped on spawn.** Both ports rebuild defs and
  options field-by-field in two places each (`withBudget` + `copyWithRegistry`; `CloneWith` +
  `CloneWithRegistry`), all on the spawn / Level-1 path. Both now carry the new fields, pinned
  by a clone test in csharp. Not a risk in the other four (golang copies by value, js spreads,
  python uses `dataclasses.replace`, elixir uses `Map.put`).
- **elixir: de-flaked the parallel-tool-call test.** It proved concurrency by wall clock
  (`elapsed < 280` over two 150ms sleeps) and hit 524ms on a loaded CI runner. Now asserts the
  **peak** number of tools in flight simultaneously, which serialized execution can never
  reach; mutation-verified by forcing `max_concurrency: 1`.

### Not included

Both deferrals from `docs/adr/0008` stand: a `preCompact` hook able to **abort** a compaction
(new control flow across six ports, awaiting downstream evidence) and `cache_control`
breakpoints (a provider-payload change). Each wants its own ADR.

## 0.10.0 — 2026-07-19

The agent release: both agent archetypes — coding (sub-agents) and persona (agent home) —
ship on a shared actor-model runtime, with compaction to keep either alive over long
sessions. All six ports, byte-parity against the shared `examples/` fixtures.

### Added

- **Context compaction in all six ports** (`SPEC.md §7F`, OpenSpec change `add-compaction`).
  An opt-in `beforeLLM` helper that keeps a long-lived or high-tool-volume agent under its
  context window — **additive, no core loop change**. In the `agents` surface:
  `compactor({ maxTokens, keepTail, summarize, countTokens, flushToMemory })` returns a
  `beforeLLM` hook that, once the transcript estimate exceeds `maxTokens`, replaces the older
  body with one summary system message and keeps a recent tail; below budget it is a **no-op,
  byte-identical** to no compactor. Two invariants: the retained tail begins at a `user` turn
  (**tool-pair safety** — a `tool` result is never orphaned from its `tool_call_id`), and a
  leading `system` prompt is preserved verbatim. `summarize(older)` is pluggable and MAY call an
  LLM; `countTokens` defaults to `ceil(chars/4)` (`estimateTokens`, an estimator not a
  tokenizer); `flushToMemory` injects a pre-compact reminder to persist durable facts via the
  §7E `memory` tool before summarizing. Ships a shared `examples/compaction/` conformance
  fixture and a "keep a persona alive for weeks" recipe (compactor + `flushToMemory` + the
  memory builtin) on the persona-agents docs page.

- **Persona agents (agent home) in all six ports** (`SPEC.md §7E`, OpenSpec change
  `add-agent-home`). The persona archetype over the §7D runtime — additive and opt-in, no
  runtime change. In the `agents` namespace: `fromDir(dir)` (Python `agent_from_dir`, Java
  `agentFromDir`) composes the bootstrap files
  `AGENTS/SOUL/IDENTITY/USER/TOOLS/HEARTBEAT/MEMORY.md` (in that order, 2 MB/file cap) into a
  frozen soul snapshot at session start; a file-backed `memory` builtin (`memoryTool(dir)`,
  actions `add`/`replace`/`remove` over `MEMORY.md`/`USER.md`) that writes to **disk** and loads
  at the START of the next session — never mutating the live prompt, keeping a long-lived persona
  cache-stable (a missing substring is a loud `isError`; opt out with `memory: false`); and
  `startAgent(agent, …, { everyMs })` — a heartbeat that posts a coalescing tick to the agent's
  own inbox and wakes it to read `HEARTBEAT.md`, where a `HEARTBEAT_OK` reply stays silent.
  Channels stay the host's job (wire inbound to `post`/`wake`). Ships with a runnable
  `examples/persona-agent/` ("Ava") + JS/Python/Go entrypoints, a "when to use which surface"
  guide, and dream/consolidation + channel-assistant recipes (composition, no new API).

- **Agent runtime + sub-agents in all six ports** (`SPEC.md §7D`, OpenSpec change
  `add-subagents`). A new `agents` namespace per port (never colliding with the A2A
  `Agent`): `agent(name, { does, uses, soul/soulFile, team, budget, model, waitFor,
  onSpawn, onClose })` with `.run(prompt)` and `.asTool()` — an Agent IS a Tool. Delegation
  runs through a built-in `task { agent, prompt }` tool (team-scoped, opt-in per
  definition): isolated child transcript, one tool message back, usage roll-up, parallel
  task calls. Underneath: a Handle state machine with six host verbs
  (`spawn/post/wake/wait/interrupt/close`), two delivery rails, three loud backpressure
  gates, hierarchical live-enforced budgets, §10 suspension escalation with durable resume
  by task-key reattachment, and a per-port cancellation contract.

### Changed — action may be required

- **`RunResult.status` gains `"incomplete"`** (QG5). A `maxTurns` stop that still had tool
  calls in flight — on the plain client `run`/`ask`/`stream` loops as well as agent runs —
  now returns `status: "incomplete"` plus `limit: "maxTurns"` (idiomatic casing per port)
  instead of a silent `"done"`. Any limit stop (turns, tokens, tool calls, wall clock) is
  loud and names its limit; partial work and the transcript are preserved. **Code that
  matches `status === "done"` after hitting `maxTurns` must update** to handle
  `"incomplete"`. The full closed status vocabulary is now
  `"done" | "pending" | "incomplete" | "interrupted" | "closed" | "timeout" | "error"`,
  identical strings in all six ports.
