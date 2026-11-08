# Design — add-memory-hierarchy

## Shape: a three-tier hierarchy on existing seams

| tier | file(s) | injected? | written by | read by |
|------|---------|-----------|------------|---------|
| index | `MEMORY.md` (+ `USER.md`) | yes, at session start | `memory` target `self`/`user` | the soul |
| notes | `<home>/memory/<slug>.md` | **never** | `memory` target `note:<slug>` | `memory read` / `memory list` |
| ground truth | `ConversationStore` | never | the client loop (unchanged) | `transcriptSearchTool` |

No new runtime behavior: the index cap is a composition detail of `fromDir`, notes are more
targets on the existing `memory` tool, transcript search is a plain `Tool` a host hands to an
agent's `uses.tools`.

## G1 — index cap

- Option on `fromDir`: `memoryIndexCap: {maxLines?: int, maxBytes?: int}`; a boolean/`true`
  spelling (per-port idiom) means the preset `{maxLines: 200, maxBytes: 25600}`.
- Applies to `MEMORY.md` only, **after** the 2 MB read cap. Truncation keeps whole lines: take
  the first `maxLines` lines, then drop trailing whole lines until the UTF-8 byte length is
  `<= maxBytes`. (Unlike the 2 MB cap, never splits a character — this is the cap the model
  sees every turn, so it must be clean.) If one line alone exceeds `maxBytes`, it is cut at the
  last UTF-8 boundary `<= maxBytes`.
- On truncation append exactly:
  `\n\n[MEMORY.md index truncated at <N> lines / <B> bytes. Keep MEMORY.md a short index; move detail into notes (memory target "note:<slug>").]`
  (When notes are not enabled the parenthetical reads `(keep entries short)` — fixture-pinned.)
- Byte-identity: unchanged when the option is absent, and also when `MEMORY.md` is within the cap.

## G2 — notes

- Option `notes: true` on `fromDir` / `memoryTool(dir, {notes:true})`. Absent ⇒ the tool's JSON
  schema and description are byte-identical to today, and `note:` targets / `read` / `list`
  are rejected as unknown (today's error path).
- `target: "note:<slug>"` with `add`/`replace`/`remove` — same semantics as `self`. `add` to a
  missing note creates it (and `<home>/memory/`).
- `read` (`target` required): returns the file content (2 MB byte cap, notice on truncation —
  the read is never written back, see G4). Works for `self`, `user`, and notes. Missing note ⇒
  `isError`.
- `list`: returns one line per note, `<slug>\t<bytes>`, sorted by slug ascending (explicit sort —
  never rely on directory order; see memory note on K1). Empty ⇒ `(no notes)`.
- Slug: `^[a-z0-9][a-z0-9._-]{0,63}$`, not containing `..`. Anything else ⇒ `isError`, no I/O.
  Lowercase-only avoids case-insensitive-filesystem collisions (macOS/Windows).
- Decision taken: **dedicated `read`/`list` actions rather than relying on file builtins.** A
  persona with memory need not have the §4A file tools (read-only/locked-down personas), and
  file tools would need path allowlisting to stay inside `memory/`. Two actions on an existing
  tool cost less surface than one more tool.
- The description (when notes are on) says what memory is *for* — "MEMORY.md is a short index
  loaded every session; put detail in notes and point to them from the index; record what will
  change a future decision" — not rules about what to record (the −0.234 finding).

## G3 — transcript search, and why it is not a violation of ADR 0016/0017

ADRs 0016/0017 reject new *runtime/orchestration* surface (Harness, AgentLoop, Graph) because
§7D verbs already carry it. G3 is different in kind:

1. It is a **`Tool`**, the library's unit of everything — same family as `memoryTool(dir)`, not a
   new verb, type, or loop. It plugs into `uses.tools` and runs through the normal loop.
2. It cannot be composed today from shipped pieces without every host re-implementing it in
   eight languages: a host *can* write a `defineTool` over `store.list()`/`get()`, but message
   content is dialect-shaped (openai/anthropic, multimodal parts), so "what text is in this
   message" is exactly the cross-port parity logic the library exists to own.
3. It is read-only and stateless — no lifecycle, no scheduling, no new store method (uses the
   already-shipped `list()`/`get()`).

Contract:
- `transcriptSearchTool(store, {maxResults?=20, snippetChars?=200, name?="transcript_search"})`.
  Construction fails if the store does not provide `list()`.
- Input: `{query: string, conversationId?: string, limit?: int}`. Case-insensitive substring match
  over the text of each message (string content, text parts, tool-call arguments as JSON, tool
  results' output text). Empty query ⇒ `isError`.
- Order: conversations as `list()` returns them (newest first), messages ascending by index.
- Output: one block per hit
  `<conversationId>#<index> <role>: <snippet>` where snippet is centered on the first match,
  `snippetChars` characters (not bytes), `…` marking elision. No hits ⇒ `no matches`. Hits beyond
  the limit ⇒ trailing `(+<k> more)`.
- Never writes. Excludes nothing by default; a host that wants to hide the current conversation
  passes a filtered store.

## G4 — write safety

- Every `memory` write: write temp file in the same dir, fsync, rename over the target
  (FileConversationStore's rule; durability mode not exposed — `full` is irrelevant for small
  notes, plain fsync).
- Read-modify-write (`add`/`replace`/`remove`) is serialized per **resolved absolute path** within
  a runtime/process (a keyed mutex map). Two agents sharing one home dir in one runtime no longer
  lose writes. Across processes: last rename wins (documented, not fixed).
- RMW reads the **full** file, never the 2 MB-capped view. This is the bug fix landing separately
  (the memory tool wrote the capped read back, truncating > 2 MB files); this change's scenario
  pins it so the fix cannot regress.
- G4 applies always (it is a bug-fix-grade safety property, not an option); observable output is
  unchanged for a single writer, so byte-identity holds.
- The article's "one page per writer" result is a **recipe** concern: give each writer its own
  note slug. No per-agent namespacing is added.

## G5 — dreaming as a feature (owner, 2026-11-08)

Owner: dreaming must not need hand-written `HEARTBEAT.md` + `startAgent` wiring. So the recipe
below becomes a library-owned preset:

```ts
// one pass — for cron, CI, a button, or tests
const report = await agents.dreamOnce("./personas/ava", { client, store, model?, budget? })
// report: { changes: [{ file, kind: "fixed"|"removed"|"demoted"|"merged"|"linked", summary }], tokens }

// on a clock — sugar over startAgent({ everyMs })
const dreaming = agents.startDreaming("./personas/ava", { client, store, everyMs: 24 * 3600_000 })
dreaming.stop()
```

- The **dream prompt is a SPEC constant** (byte-identical across ports, like `HEARTBEAT_PROMPT`):
  it states what memory is *for* and the pass order below — not rigid write rules.
- The dreamer is a **separate, scoped agent**: tools are exactly `memory` (notes on) and
  `transcript_search`. It never inherits the persona's shell/file tools (blast radius).
- `opts.instructions` (optional) appends host prose to the prompt — the escape hatch for
  goal-focused dreaming (the article's contract-terms case). A `DREAM.md` in the home dir, if
  present, is appended the same way.
- Overlap: a pass already running ⇒ ticks coalesce (§7E inbox rule), never parallel passes.
- `DreamReport` is parsed from `memory/log.md`'s new entry — the log is the single record.

ADR 0016/0017 tension: this IS new surface (two functions). Justified because the user-facing
need is "turn on dreaming", the wiring is identical every time, and the prompt must be parity-
tested to be trustworthy — a recipe each host copies drifts. It adds no loop: it is `agent()` +
`startAgent` + two tools. SPEC §7E's "Composition, not API" dream line is amended accordingly.

## Dreaming — what each pass does

Dreaming is not API. It is a §7E heartbeat persona (`startAgent(…, { everyMs })`) whose
`HEARTBEAT.md` says what to do. G1–G4 exist so that a dream pass can do its job well; the
recipe is the product of this change. Its memory layout follows Karpathy's LLM-wiki pattern,
with transcripts in the role of the raw sources:

| Layer | File | Loaded | Written by |
|---|---|---|---|
| Index | `MEMORY.md` (one line per page, capped by G1) | every session | session + dream |
| Pages | `memory/<slug>.md`, linked as `[[slug]]` | on demand (G2 `read`) | session + dream |
| Log | `memory/log.md` (append-only: what each dream changed and why) | never | dream |
| Ground truth | transcripts via `transcriptSearchTool` (G3) | on demand | nobody (read-only) |

One dream pass, in this order (the `HEARTBEAT.md` the recipe ships):

1. **Verify** — for each index line and recently changed page, search the transcripts for the
   claim. Contradicted ⇒ fix it; unsupported and stale ⇒ remove it. (The article's Mt. Moon case.)
2. **Demote** — move detail out of `MEMORY.md` into a page, leave a one-line `[[slug]]` link.
   The index stays under the cap.
3. **Merge** — fold duplicate or contradicting pages into one.
4. **Repair links** — a `[[slug]]` with no page is repointed to an existing page or the page is
   created; orphan pages get an index line or are deleted.
5. **Log** — append one entry to `memory/log.md` naming every change.
6. Nothing to do ⇒ reply `HEARTBEAT_OK` (silent, §7E).

The library does not enforce `[[link]]` syntax, page layout or the log — those are recipe prose.
Consistent with the article: tell the model what memory is *for*, not rigid write rules.

### When dreaming is worth running

| Run it when | Skip it when |
|---|---|
| The persona lives for weeks and `MEMORY.md` keeps growing (approaching the G1 cap) | One-shot or short-lived agents — nothing accumulates |
| The environment changes, so notes go stale (repos, prices, people, configs) | Facts are stable; write them into `SOUL.md` once |
| A wrong note has caused a repeated mistake (the same failure twice) | The agent can re-read the truth cheaply every turn (it is on screen / in a tool result) |
| Several agents write to one home dir and notes duplicate or conflict | Memory is small (index well under the cap) — a dream pass costs more than it saves |
| Questions need facts from several sessions combined | Answers come from one lookup — transcript search alone is enough |

Cadence: start nightly (`everyMs: 24h`) on a cheap model with a hard budget; dream more often
only when `log.md` shows most passes changing something, less when most are `HEARTBEAT_OK`.

## Open decisions (owner)

- **D9** Dreaming as library API (`dreamOnce` / `startDreaming`) vs recipe only. [API — owner
  asked 2026-11-08; this supersedes "Composition, not API" for dreaming only.]
- **D10** Dreamer model default: same client as the persona, or required explicit `model`?
  [same client; recommend a cheap model in docs]


- **D1 — G1 default.** Default-on would change the soul for any `MEMORY.md` > 25 KB or > 200 lines
  — a silent behavior change and a §0 byte-identity break. **Recommend: opt-in**, preset values as
  above; revisit default-on at a minor bump with a CHANGELOG "changed default" entry.
- **D2 — Cap units.** Lines AND bytes (Claude Code's pair) vs bytes only. **Recommend: both**,
  whichever hits first.
- **D3 — Notes reads.** `read`/`list` actions on `memory` vs relying on file builtins.
  **Recommend: actions** (decided in design; owner may overrule).
- **D4 — Should `read`/`list` also exist without `notes`?** **Recommend: no** — keep the
  notes-off schema byte-identical; one flag turns on the whole tier.
- **D5 — Transcript search matching.** Substring vs regex vs token AND. **Recommend:
  case-insensitive substring**; regex dialects differ across eight ports (parity hazard).
- **D6 — Transcript search placement.** Standalone `transcriptSearchTool(store)` vs auto-wired by
  `fromDir`. **Recommend: standalone**, opt-in per agent — most agents must not read all history.
- **D7 — Index cap also for `USER.md`?** **Recommend: no** for now; the article's evidence is about
  the memory index. Easy to add as `userCap` later.
- **D8 — Cross-process locking.** **Recommend: out of scope** (same as FileConversationStore).
