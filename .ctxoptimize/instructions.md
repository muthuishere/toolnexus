<!-- ctx-optimize:instructions:begin v0.11.0-13-gc2ae906 -->
# ctx-optimize — the usage card for this repo's knowledge store

**ctx-optimize is a SHELL COMMAND (a CLI on PATH), not a callable tool: run
every verb through your shell/bash/exec tool — e.g. shell → `ctx-optimize
query "invoice tax" --json`. Never emit a tool call named `ctx_optimize`;
no such tool exists. First tool call for any code question here = a
ctx-optimize verb; grep/read before a store verb is a routing failure.**

A pre-built knowledge graph of this codebase lives at `~/ctxoptimize/<name>/`
(the name is in `.ctxoptimize/config.json`; default: the repo basename).
Answer questions FROM the store instead of grep-and-read chains. The binary
is deterministic — no LLM, no API key, no database; never prompt for one.
Command missing? `npm install -g @muthuishere/ctx-optimize` — or ignore this
file and read the code normally; the store is an optimization, not a
requirement.

## The front door — `ctx-optimize up`

One idempotent verb from ANY state to a store that answers: no config →
bootstrap + gather · fresh clone with a declared `remote.pull` → pull the
team's prebuilt store (gather fallback) · stale vs git HEAD → fast re-gather
· fresh → no-op. Recorded sources re-capture after the gather (24h TTL —
`--sources=always|never`; `--strict` fails on unset vars). Run it whenever;
CI gate: `up && fresh`.

## Pick by intent — the verb table

| Intent | The ONE verb |
|---|---|
| **Find** — you have words, want locations | `ctx-optimize query "<2-4 terms>" --json` |
| **Inspect** a known symbol — signature/doc/callers, no file read | `ctx-optimize card <symbol> --json` |
| **About to EDIT** — what to touch, what breaks, WHICH TESTS TO RUN | `ctx-optimize change-plan <symbol> --json` (one call replaces query+card+affected+test-grep) |
| **Blast radius** — is it safe to change | `ctx-optimize affected <symbol> --depth 2 --json` |
| **Connection** — how are A and B related | `ctx-optimize path "A" "B" --json` |
| **Orient** — where do I start | `ctx-optimize hubs --top 10 --json` |
| **List / filter** — every node of a kind, edges of a relation, deps by scope ("all k8s services", "which files use react", "our dev deps") | `ctx-optimize nodes --kind K` / `edges --relation R` / `deps --scope dev [--importers]` — native, portable, **never `export \| jq`** |
| **Need the actual code body inline** — not just the pointer | add `--include-content` to `query`/`card` — verbatim source hydrated from the file at answer time (nothing stored) |
| **The answer looks short — where are the rest of the callers?** | add `--include-ambiguous` to `card`/`explain`/`affected`/`path`/`hubs`/`change-plan`. These verbs answer with FACTS ONLY by default, so a **method's blast radius is a floor**: call sites the store refused to attribute are held back as a shortlist. The flag walks them, and marks every widened row (`?`, or a `MAYBE` heading) — candidates to verify, never callers |
| **Code changed** — bring the store current | `ctx-optimize sync` — incremental resync of THIS repo (0-change ≈ ms); `--adapters` adds adapter scripts, `--all` adds native sources (dials), `--no-wiki` graph-only. Opt-in autosync: `"autosync": "lazy"` in config.json (stale reads resync themselves in the background) |

Query with 2–4 terms, not sentences; `card` wants the exact label (query the
short name first if unsure). Output is parsed fact with exact `file:line` —
cite it directly, do NOT re-verify in source.

## Verify discipline

Before a human acts on a citation: `ctx-optimize verify "<node-id |
exact-label | file:L10-L20>"` — node exists (exact only, never fuzzy), file
exists, range in bounds, drift vs gather-time git HEAD. Exit 0 only when ALL
claims hold. A failed verify means re-query or `ctx-optimize sync` — NEVER
rephrase the claim. Fuzzy resolution announces itself (`resolved_via`) and
refuses ties with ranked candidates — pick one, don't pass `--fuzzy` on a
user's behalf.

## When the store says "I don't know"

`card` may print `unattributed callers: N`. That means `called by` is
**incomplete by design** — the store refused to guess, and the line says which
kind of refusal it was:

- *"the name is defined more than once"* — several declarations share the name.
  Settle it with `grep -rn '\b<Name>\b' .`
- *"on a receiver whose type this store never established"* — a **method**. The
  store holds only this repo's declarations, so it can never tell `err.Error()`
  from a call to your own `Error`. Settle it with `grep -rn '\.<Method>(' .`
  and check each receiver's type.

Consequence: for a method, `affected` / `change-plan` give a **floor**, not the
full set. To see the held-back shortlist without leaving the tool, re-run the
same verb with `--include-ambiguous`; widened rows are marked and are
candidates to verify, never callers. Flat list: `edges --relation calls
--confidence AMBIGUOUS --to <id>`.

Never present `called by` as the complete caller set while that line is printed.

## Tool choice — store vs grep (two-sided; wrong in either direction is the failure)

| Question shape | Tool |
|---|---|
| symbols, structure, callers, impact, architecture, "how does X work" | store verbs (table above) |
| exact literal strings, every occurrence, config VALUES, comments, member fields, build files | **grep directly — the store does not index these; say so and grep** |

The ladder: right-tool store verb first → verify before a human acts → READ
the cited range when behavior matters (that is the point of the location, not
a violation) → two store misses = switch tools, not words (`hubs`, `explain`
a neighbor, or declare the grep lane) → still nothing: abstain, naming what's
missing. The one forbidden move is stopping silently or padding from priors.

## Sources — databases, buckets, queues, external APIs

A source is an ENV VAR NAME whose value is a URL; the scheme picks the
connector (postgres, mysql, mongodb, redis, kafka, nats, s3, http(s) →
openapi, no scheme → a spec file path). The flow:

```sh
ctx-optimize adapters help postgres   # setup card: value format, credential params, paste-ready command
export BILLING_DB_URL='postgres://user:$PG_PASS@db.internal:5432/billing'
ctx-optimize add BILLING_DB_URL       # resolve → dial → capture → merge → recorded in config sources
```

- **Names only on argv** — never a raw URL with credentials on the command
  line or in committed config; literal passwords in an entry are a hard
  error. Values resolve process env → root `.env` →
  `~/.config/ctx-optimize/.env` (the machine-global file is for URLs shared
  across every repo on this machine — a read-only replica, a local MinIO —
  and lives outside the repo, so it can never be committed).
- **Skips are normal**: a teammate without the credentials still runs `up`
  cleanly — that source reports one skip line naming the unset var, prior
  nodes stay, and they get the nodes via `remote pull`. `--strict` turns
  unset-var skips into failures (CI). `status` shows per-source staleness.
- `ctx-optimize capture <NAME>` prints one connector's Batch JSON to stdout
  without touching the store — the composition/debug primitive.
- `ctx-optimize adapters list` shows recorded sources + supported schemes +
  custom adapter scripts.
- Captures are logical shape only: system schemas skipped, partitions
  collapsed to a count on the parent, bounded samples — any cap that
  truncates is reported. Connectors live in the `ctx-optimize-adapters`
  companion binary installed beside the main one; if it's missing the error
  says so — reinstall the package, don't debug the URL.
- Exotic sources (vault-minted certs, tunnels): a script in
  `.ctxoptimize/adapters/` sets the env var in its own process and calls
  `ctx-optimize capture <NAME>` back, teardown in a `finally`.

**Querying a captured source** — read it from the graph, never re-`add` to
answer a question. Kinds: `database` `schema` `table` `view` `column`
(postgres/mssql; mysql has no `schema` node) · `collection` · `key_prefix` ·
`cluster` `topic` `consumer_group` · `server` `stream` · `bucket` `prefix` ·
`api` `path` `operation` `schema` `securityScheme`. Relations: `contains`
(whole hierarchy), `references` (FK → referenced table), `uses` (operation →
component schema).

```sh
ctx-optimize nodes --kind table --where label~public.   # labels are `schema.table`
ctx-optimize edges --relation references                # the FK graph
ctx-optimize card public.users                          # columns, types, indexes
```

**Source subgraphs are ISLANDS.** A connector only ever sees a URL, and the
only cross-lane linker bridges code imports → `dep:` nodes. There is NO
code↔table, code↔topic, code↔config_key or code↔endpoint edge: "which code
writes this table / implements this endpoint" is NOT answerable from the
store — say so, then grep the name. Spec routes and code routes are separate
`route` nodes with identical `METHOD /path` labels and no edge (0% measured
join rate). The openapi connector parses JSON specs only; the in-repo route
lane reads YAML specs only.

**`deps` ecosystems**: npm · go · maven · gradle · nuget · pypi · crates.
Ruby/PHP are not covered (adapter door) and pip-compile locks are skipped on
purpose (transitive pins, not declarations) — so `(0 dependencies)` means
"nothing recognized", never "none declared".

## Sharing — remote push/pull

`remote push` / `remote pull` run the commands declared in
`.ctxoptimize/config.json` (`{"remote": {"push": "<cmd>", "pull": "<cmd>"}}`)
— the transport is the team's committed script; the binary ships none.
Scripts get `CTX_STORE_DIR` / `CTX_STORE_KEY` / `CTX_SCOPE_PREFIX` /
`CTX_DIRECTION` in env. Secrets stay env-var NAMES everywhere.

## Honesty rules

Never claim a node/edge/path the CLI didn't output; report counts as
printed; say EXTRACTED (parsed fact) vs INFERRED (name-matched) when it
matters; if the store can't answer, say what's missing and which gather lane
would fix it.

## Small models & custom runtimes — pin this protocol

Any agent runtime can use this store (toolnexus, custom loops, any LLM).
Small models (gpt-4o-mini class) skip the store unless the protocol is
pinned in the SYSTEM PROMPT — measured 2026-07-17: 23/80 without, 54/80
with, on a judged codebase-Q&A bench (frontier agents: 72–80/80).
Copy-paste verbatim:

```
You are a codebase Q&A agent in a repo with a prebuilt ctx-optimize
knowledge store. MANDATORY PROTOCOL for every question, no exceptions:
(1) Your FIRST action is always a shell/bash call:
    ctx-optimize query "<2-4 terms>" --json — or
    ctx-optimize card <symbol> --json when the question names a symbol.
    ctx-optimize is a CLI on PATH; bash is the only way to run it.
(2) You answer ONLY from command output. Prior knowledge about how tools
    'typically' work is FORBIDDEN in answers.
(3) If the question asks how something works or what happens in a case,
    you MUST read the cited range before answering:
    bash: sed -n 'START,ENDp' <file> on the file:line the store returned.
(4) Every claim in your answer carries a file:line citation taken from
    tool output.
(5) If the store returns nothing after 2 differently-worded queries,
    answer exactly: 'not found in this codebase' — do not describe it.
(6) Minimum 2 tool calls per answer unless the store says not-found.
```

Pass API keys via environment, never argv. Known limits at small-model
class: weaker query rephrasing when the first hit is noise, and
fabrication risk on plausible-but-absent symbols — keep `verify` in the
loop before humans act on citations.
<!-- ctx-optimize:instructions:end -->
