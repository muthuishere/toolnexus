# Design — fix-consumer-issues-86-93

The binding record is `DECISIONS.md` in this folder (owner-approved 2026-09-21). This file says
**how**, and — the part that matters for review — **what was rejected and why**, so the same five
attractive alternatives are not re-proposed in three months.

## The shape of the batch

Eight issues, six ADRs, one property: **the failure path and the second entry point were never
held to the contract the success path and the first entry point are held to.** A toolkit-less
call, a Loop-driven agent, a resumed run, an unrecognised answer, a timed-out request, an
unparseable skill — six places where the library had an answer for the happy case and an
accident for the other one. Grouping them is not convenience: they share a review argument and,
in four cases, the same lines of code.

## D1 — the null-guard, not a new type

The crash in JS, Python, Java and C# is **one bug at one site**: building the §0.10 system message
(`system = systemPrompt + "\n\n" + skillsPrompt()`) dereferences the toolkit before anything
touches tools. Go and Elixir already nil-guard at their accessors and work. So the fix is a guard
at that site per port, plus the idiomatic call shape.

Deliberately **not one shape in seven costumes**: a nullable parameter is idiomatic C# and
un-idiomatic Java; an extra arity is idiomatic Elixir and meaningless in Python.

| port | shape |
|---|---|
| golang | none — `nil *Toolkit` already documented; tests only |
| clojure | none — `(run client p {})` already works; tests + a docstring clause |
| elixir | default args giving `run/2`, `ask/2`, `stream/2` |
| python | `toolkit: Toolkit \| None = None` on `run`/`ask`/`stream` |
| js | `ctx?: { toolkit?: Toolkit; … }` |
| csharp | `Toolkit?` — nullable, not overloads |
| java | overloads for exactly three shapes: prompt, prompt+id, prompt+onText |

Java is the only port where this is a design decision rather than a keystroke. `LlmClient`
already exposes ~15 `run`/`ask`/`stream` signatures across two prompt shapes; a blanket
toolkit-less twin of each doubles that. Only the shapes a completion actually uses get one.

The wire assertion — no `tools` key, no `tool_choice` key, **not** an empty array — is already
true in all seven ports (§8 Gap 5, shipped 0.9.0). It is untested everywhere, which is why nobody
noticed the call shape had drifted around it. Every port pins it here.

## D2 — apply the Spec in `clientFor`; name the residue

Six of seven ports already apply the soul and the compiled guardrails inside their Loop's
client-options builder. Go's `clientFor` does neither. That makes #87 a **Go parity defect**, not
a design question — which is what decides the option below.

Precedence, one sentence for SPEC §7D: **a caller-supplied system prompt wins over
`Spec.Soul`.** Python and C# already do this; JS does `soul ?? options.systemPrompt`, the
opposite, and is brought to the rule.

The residue splits cleanly and must be read as two clusters, not one list:

- `Soul`, `Guardrails`, `Hooks` — ignored **only in Go**. A bug.
- `Tools`, `Team`, `WaitFor`, `OnMetric` — ignored in **all seven**, and follow from what a Loop
  *is*: a driver over a client and a toolkit the caller already built. A boundary.

The boundary still has to be visible: a `Team` on a Spec today means a Loop-driven agent cannot
delegate at all, silently. `loopUnsupported(spec) -> [field names]` makes that inspectable
without changing a signature.

The regression test asserts **the denied tool's `execute` is never entered** — not the model's
text. Asserting on text is what made this survivable for a release: the model dutifully reported
`denied: …` in one scenario and `EXECUTED: ls` in the other, and only the side-effect slice told
the truth.

## D3 — one meaning per field, and a resume that returns something

`TotalTokens` is the tree total on **every** status. The decisive argument is not the fourteen
doc sites (though one of them instructs the reader to *measure* with it); it is that the field
**already** returns the tree total on four of seven status branches. Today its meaning depends on
which status came back, which is worse than either candidate meaning. `OwnTokens` needs a new
per-handle counter incremented outside the ancestor walk in the rollup.

`Limit` is populated from `RunResult.Limit` **and** for budget stops, where the exhausted pool
name is already computed and discarded. Vocabulary identical in seven ports:
`"maxTurns"`, `"completion"`, `"maxTokens"`, `"maxToolCalls"`, `"maxWall"`, empty otherwise.

`Resume` returns the settled result of the **topmost handle the cascade re-ran** — what the host
would have got from `Agent.Run` had the suspension never happened. Only Go breaks compilation;
the other six are source-compatible. This also removes the reason the `*Handle` is currently
unreachable, so `Agent.Run` does **not** need to start returning one.

Java's `"continue"` replay is a §0 break and is fixed independently of the rest: restore the real
prompt and the drained inbox text.

## D4 — error to the host, never a fabricated result to the model

`RunWithAnswer` already refuses a mismatched id, on the reasoning that "a stale or misrouted
answer cannot corrupt a conversation". An unrecognised payload is the same class of caller
mistake with a strictly worse outcome — a fabricated `isError` tool result spliced into the
transcript, the model apologising or guessing off it, and `status: "done"` returned to a host
whose logs look healthy. **The asymmetry is the bug.**

The fabricated filler survives only for what it was written for: a multi-call relay turn where
the host deliberately answered some calls and not others and the transcript must stay balanced —
and only when the host supplied *some* recognised payload. It is a partial-answer filler, never
the response to a wholly unrecognised map.

The key hazard is separate and is fixed here because it lands on the same line of host code:
Elixir's atom-keyed `Answer` raises a `FunctionClauseError` on a JSON round-trip, and Clojure's
keyword-keyed one reads a string-keyed map's `ok` as `nil` and treats the answer as **declined**.
§10 says `Request`/`Answer` keys are fixed across all ports because they serialize; a durable
path that cannot survive JSON is not a durable path.

## D5 — the pairing is the unit; the failure is a value

`jev-latest` stays. It is the only id `DefaultClassifierBaseURL` serves, verified live. The
defect is that `baseUrl`, `model` and `apiKeyEnv` are three independently-settable options valid
in exactly two combinations, and the docs present the two routes side by side as six choosable
cells. A `backend` preset sets all three together; the individual options stay for a self-hosted
origin; and the known mismatch fails **before the wire** — a wrong-endpoint 400 arriving 700ms
later as "Unknown model" is the worst possible form of this news.

Go's timeout answer settles ADR 0027's open Q2 the cheap way: **`Status: "incomplete"` with
`Limit: "timeout"`**, reusing the mechanism `MaxTurns` already uses and inventing no new §8 status
value. Turns and Usage accumulated before the deadline are preserved; they exist at every return
site today and are discarded into a zero value.

Redaction and the cap are **independent and both needed**. The spike's leaking body is 96 bytes,
so the 200-character cap passes `user_2FAKE…` through untouched. A cap is not redaction. The keys
— `user_id`, `account_id`, `org_id`, `organization` — are replaced with `«redacted»` rather than
dropped, so the body's shape survives for a host reading it.

Two behaviours must not regress and are asserted, not assumed: fail-fast on 4xx through the
**enumerated** retryable set `{429, 500, 502, 503, 504, 529}` (never "any 5xx"), and
`ClassifierUsage.Cost` as an optional where absent ≠ zero (ADR 0022). Both sit in the code D5
touches.

## D6 — YAML first, rescue second

The inversion is the whole decision. The issue proposes line-wise `key: rest-of-line` with YAML
as the fallback; in that order it misparses legitimate YAML, and the Clojure port is the
existence proof — rest-of-line for `description: |` is `"|"`, and a plain scalar continued on the
next line silently truncates.

> **YAML first. Line-wise only for `name`/`description`, only on frontmatter a real YAML parser
> has already refused.**

A file YAML parses keeps YAML semantics byte for byte. A file YAML refuses has no semantics left
to misparse. Guards: column 0 only, first-wins, and a value that is empty or opens
`| > & * [ { !` is **refused, not guessed**, so a half-broken block scalar degrades to "no
description" rather than to `"|"`.

Measured on `~/.claude/skills`: 81 → 87 parsed, the six rescued descriptions byte-identical to
what Claude Code displays, block scalars unchanged, `~/.agents/skills` 36/36 unchanged, and
`broken-flow` still refused with no invented description.

Clojure's replacement is the expensive part and the highest-value fix in the batch: a real YAML
parse behind one `.cljc` seam across both hosts (SnakeYAML on the JVM, `yaml` on the JS host).
ADR 0009 explains *why* the hand-rolled subset exists — koine declines to own YAML — but that
explains it, it does not license it to be the contract.

`LoadSkills` surfacing skips is the only one of the three that survives the parser fix, because
it is about the class rather than the instance: the next tab-indented file still vanishes. The
hook shape is checked against ADR 0014 (hook composition, the single-slot problem) before it is
written.

## Rejected alternatives

**A public `Toolkit.empty()` in six ports (D1).** It is a new exported symbol in every API, a new
row in every doc, and a second way to say what a `builtins:false` toolkit already says. The issue
reports it as already landed in JS; `git log --all -S"Toolkit.empty"` returns nothing and the
probe prints `typeof Toolkit.empty = undefined`. An empty toolkit and no toolkit must be
observably identical, and on the wire they already are — so the second spelling buys nothing and
costs a permanent surface.

**A construction-time error on a Loop whose Spec carries unhonourable fields (D2).** This was the
*better* option on the security criterion alone — it makes the state detectable earlier and
louder than applying the Spec does. It loses to the prime directive: in six of seven ports the
Loop already honours soul and guardrails and has since `add-harness-and-loop` shipped. Choosing
it means changing six correct ports to match one incorrect one, and telling every existing
JS/Python/Java/C#/Elixir/Clojure caller that a guardrail they rely on is now a compile error. The
loud thing to be loud about is the Go omission, not the design. `loopUnsupported(spec)` keeps the
detectability without the signature change.

**Correcting the fourteen doc sites instead of the token field (D3).** Cheaper, and rejected: it
leaves `TotalTokens`'s meaning status-dependent, which no doc sentence can rescue. The docs were
right; the field was not.

**Kind-aware `Answer` defaulting — "for `kind:"input"`, a single-valued `Data` *is* the payload"
(D4).** Reads as the most generous option; it is the most dangerous. `{"value":"staging"}`,
`{"token":"…"}` and `{"approved":false}` are all single-valued maps with entirely different
meanings, and coercing whichever arrives into a tool-result string turns a loud caller error back
into a quiet wrong answer — the exact class this change exists to close. It also cements the
deeper defect by making transcript-splicing feel *more* correct for `kind:"input"`. Its valid
intuition becomes the deferred kind-aware **re-execution** change, which is a different thing.

**Renaming the public `status` field on either `TaskResult` or `RunResult` (D5).** The name
collision is the real defect behind #92 — two closed vocabularies, seven values and three, on two
fields both called `status` — and renaming one would settle it. Neither rename is free: the agent
vocabulary is the one §7D calls closed, the client one has more callers, and both are public on
every port. Named constants for **both** sets in all seven ports, plus SPEC §8/§7D documenting
them as two distinct vocabularies, closes the reading error without a rename. Clojure already
holds its seven-value set as a first-class value; the other six carry it as prose plus inline
literals, which is precisely how a reader ends up applying the wrong one.

**Line-wise-first frontmatter parsing (D6).** Forbidden, with a measured existence proof: the one
port that already implements it loads 33 of 87 files where the others load 69, misparses block
scalars in both directions, and silently truncates continued plain scalars. Line-wise runs only
after YAML has refused.

**Keeping the parser strict and documenting it (D6).** Defensible in the abstract — the file *is*
invalid YAML — and it does not survive §3. The tool that wrote the file accepts it, the user has
no signal, and toolnexus is the only party in the loop that thinks a standard is being enforced.
The cost of leniency is bounded to files that are already broken; the cost of strictness is a
skill that exists and cannot be seen.

**Re-pointing `DefaultClassifierModel` away from `jev-latest` (D5).** The reported dead alias
serves: one live call on 2026-09-21 with entirely zero-value options returned no error. Repointing
would break the default pairing to fix a mismatch that only appears when a host overrides
`baseUrl`.

**"Document that `err` must be checked before `Status`" (D5).** True of Go and vacuous everywhere
else, and it asks every host to remember what the type could enforce.

**Documenting the error-body leak rather than redacting it (D5).** The reporter's own words are
the argument: *"we scrub it on our side, but every host has to know to."* A guarantee each host
must re-implement is not a guarantee.

## Deferred — each its own change, named here so the omission stays visible

**1. Transcript replay on resume.** Rewind-to-checkpoint is pinned by name at `SPEC.md:912-914`
with its reason (a persisted placeholder would make the resumed parent skip re-invoking `task`),
and four ports go out of their way to write the rewound snapshot back. It is not an oversight to
repair in an afternoon. Replaying the leaf's stored transcript needs two transcript policies in
one runtime, makes the pending placeholder's shape a §0 byte-identical obligation across seven
ports, and **does not remove the idempotency requirement anyway** — §10 resolves by re-executing
the suspended tool with `ctx.answer`, so at least one tool is always called twice across a
resume. Sized **L** with a conformance change inside it, against an **S** that stops hosts being
surprised today. It should carry with it a per-turn replay token on `ToolContext`: `Pending` mints
a fresh `Request.ID` on every call, so a tool cannot today tell its own replay from a new call,
which makes the idempotency contract *stated* but not *satisfiable*.

**2. Non-relay resumes re-executing the tool with `ctx.answer`.** The two §10 exits currently
deliver the answer to different recipients — inline to the tool, durable to the transcript — so a
host that develops against `waitFor` and then goes durable finds neither its tool's post-resume
branch nor its data key does anything. The key name is a symptom; the recipient mismatch is the
disease. Fixing it is also what would let the six ports without a durable path implement it
correctly the first time rather than porting Go's splice. Sized **M** in Go and **L** in each of
the other six, and it must not start until its spec delta exists.

## The addendum — seven decisions that would otherwise have produced seven variants

Writing the spec deltas surfaced seven places where `DECISIONS.md` as first written could not be
turned into a requirement a port could fail. All seven were closed by the owner's addendum
(A1–A7) and are folded into the deltas above. They are listed here because each is a decision a
reader will otherwise assume fell out of the code:

- **A1 — discovery order.** Directories in the order the **caller** supplied them; within a
  directory, lexicographic by path relative to that directory's logical base; first-wins. No
  filesystem-order dependency: Node's listing is already sorted, every other port sorts
  explicitly. Without this, "first-wins" is a different file in each ecosystem, which is exactly
  the `docx`/`pdf`/`pptx` defect.
- **A1a — the tie-break is ordinal.** Unicode code-point comparison of the whole path relative to
  the root's logical base; symlinks sort at their discovered path, not their target; no locale
  collation, no case folding, no segment-aware comparison. Spelled out because the *natural*
  string comparator is culture-sensitive on the JVM and in .NET, so "lexicographic" alone would
  have produced two ports that diverge on non-ASCII paths only, and only on some hosts — the
  hardest possible drift to find.
- **A1b — and it flips three ports.** The rule makes `docx/SKILL.md` beat
  `synced/<hash>/docx/SKILL.md`, so python/java/csharp's current winner becomes the contract and
  js, golang and elixir change which file supplies a duplicate name's content. The names agree
  either way, which is exactly why nothing else in this change would catch a regression; a shared
  fixture carrying both shapes pins it, and the changelog names it as a behaviour change.
- **A2 — the skip surface is returned data, not a hook.** A hook shape differs per port and
  therefore cannot be a parity gate; conformance compares the skips returned on the
  `LoadSkills` result. A port that already owns a warn slot may also call it, but may not rely
  on it. This overrides the "a `Warn` hook or returned skips" disjunction.
- **A3 — recognised payload keys.** `results`, then `output` (with its optional `isError`), in
  that precedence, pinned in a scenario rather than only in SPEC prose. "Genuine partial relay
  answer" is defined as *at least one recognised key present*, which is the only case that keeps
  the fabricated filler alive.
- **A4 — `Resume` returns the topmost handle the cascade re-ran.** Confirms the reading taken
  from ADR 0025 rather than leaving it an inference.
- **A5 — redaction and the cap are scoped differently.** Redaction applies to **both** the typed
  `body` field and the message. The 200-character cap is **message-only**: a host that reached
  for the typed error asked for the whole body, redacted. This corrects the first draft of this
  change, which left the typed field raw.
- **A6 — `loopUnsupported` returns a closed vocabulary.** `"tools"`, `"team"`, `"waitFor"`,
  `"onMetric"` — the same strings everywhere, like the limit strings, never the language's own
  spelling of the field. A query whose return values differ per port cannot be compared, which
  would have made the parity guard decorative.
- **A7 — the status collision's invariant stays open.** See below.

A second round, raised by the js port while implementing:

- **A8 — `Spec.Model` applies on absence *or* the `"inherit"` sentinel.** Both spellings, in every
  port. A port whose model field is optional can express absence alone and would have implemented
  only that, which diverges for a caller who passes a real model alongside a Spec model — the
  commonest shape there is.
- **A9 — `answerDeclined(id, reason)` ships in all seven** alongside `answerOutput`. A human who
  says no is not an error, and it is the natural pair; a constructor that exists in one port only
  is the drift this change exists to end.
- **A10 — the YAML-recovery fork, and the invariant that resolves it.** Ports' libraries disagree
  about `description: [unterminated`: some throw, so the rescue runs; some recover it into a
  sequence, so the rescue never runs and the description becomes a list. "YAML refused it" is
  therefore not a portable trigger. The rescue fires when the parse **throws**, yields a
  **non-mapping**, or yields a mapping whose `name`/`description` is present but **not a string**
  — three conditions chosen to make one invariant hold everywhere: *a file never gains an
  invented description, and never silently keeps a structurally wrong one.* Because this is a
  divergence no port could detect from inside itself, `spikes/issues/93/fixtures/` is named as
  the arbiter and every port must produce an identical accept/skip/detail table over it.

A third round, raised by the csharp port:

- **A1c — ordinal is not the same as code-point.** "Code-point comparison" was under-specified
  for UTF-16 hosts, where the *natural* comparator orders by code **unit** and a surrogate pair
  therefore sorts before U+E000..U+FFFF instead of after. `string.CompareOrdinal`,
  `String.compareTo` and `<` on JS strings all disagree with code-point order above U+FFFF, so
  the earlier instruction to java — "select an ordinal comparator" — was necessary and not
  sufficient, and is corrected here. Code-point order is normative; it is identical to code-unit
  order for every ASCII and BMP path, so no real corpus moves. The requirement exists so that a
  divergence which would fire only on an astral-plane filename, only on some hosts, is not
  planted now. A dual-host port must have both its hosts agree.
- **A1d — SPEC §3 states which order wins, in one line**, so it is settled in the contract rather
  than re-derived per port. Recorded as a task for the docs owner; this change does not edit
  `SPEC.md` itself.

A fourth round, once the fixture table had a reference implementation:

- **A11 — compare the string, not the verdict.** csharp and the `lenient.py` prototype agree
  exactly over the 17 fixtures (14 ok / 3 skip), and that agreement is now the reference table in
  `DECISIONS.md`. The row that will actually break a port is `hash-inline`, and it is **not**
  decided by the rescue at all: ` #` opens a YAML comment, so `Tag things with #stockloop` parses
  as `Tag things with`. A port whose library keeps the tail still reports `ok` — same verdict,
  different description. So conformance compares the parsed **description string** and the typed
  **skip reason**; the detail stays native and uncompared.
- **A12 — `broken-flow` is the proof that A10 is implemented.** It must be accepted with its name
  and no description, never skipped. A library that throws and a library that recovers `[` into a
  sequence land on that same row only because of A10's non-string trigger, so the row doubles as
  the acceptance check for the whole rescue design: if it skips, the non-string guard is missing.

A fifth round, raised by the golang port and verified as live drift before deciding:

- **A13 — `Turns` has the same status-dependence defect as `TotalTokens`, and only that.**
  `DECISIONS.md` D3 named only `TotalTokens`, `OwnTokens` and `Limit`, and the ports implemented
  what they were handed. The omission produced measurable drift — js returns the same figure on
  every branch, golang a per-run figure on done/pending/incomplete and a cumulative one on
  error/closed/timeout. That is issue #88's exact shape, one field over: a field whose meaning
  depends on which status came back.
- **A13a corrects A13's remedy, which was wrong in two ways.** "Cumulative TREE total, exactly
  like TotalTokens" does not describe any port: the runtime's rollup walks the ancestor chain for
  **tokens and tool calls only**, and `turnsTotal` is a per-handle accumulator everywhere. So
  `Turns` becomes the handle's **own** cumulative round trips, reported identically on every
  status, and is explicitly **not** rolled up — a roll-up would be new behaviour introduced under
  cover of a parity fix, which is the thing this change exists to prevent. The golang port flagged
  it rather than implementing it, and the spec now says "do not roll up" out loud so that a port
  which already acted on the withdrawn wording reverts.
- **A13b deletes a clause that was simply false.** "A parent never reports fewer turns than its
  child" holds for tokens, which roll up, and not for turns: a parent can delegate in one turn to
  a child that takes five. It survives only as an assertion inside the specific one-delegation
  fixture, where the parent spends a turn delegating and a turn answering — and the fixture says
  so, so nobody promotes it back into a guarantee.
- **Still no `OwnTurns`, for a corrected reason.** Not "turns aren't billed" but: with no roll-up,
  `Turns` already **is** the own-figure, so a second field would carry the same number twice.
- The scenario that pins this asserts `Turns` across two statuses. The existing token scenarios
  did not catch the turn drift, which is why it survived to be found by a port agent.

A sixth round — two of these are corrections to things this change had already asserted:

- **A14 — the `limit` vocabulary is closed and canonical.** Four ports were already emitting four
  different strings in the field D3 added *so that hosts could branch on it* — golang `maxWall`,
  csharp `tokens`/`wallMs`, python `tokens`, js `maxTokens`/`maxToolCalls`. That is issue #90's
  own complaint re-created inside its own fix: "which limit stopped me" is only answerable if the
  answer is the same word everywhere. The value now names the `Budget` field that stopped the
  run, spelled as `SPEC.md` spells it, from a closed set of nine, and a port maps its internal
  pool name at the boundary. Go already proved the mapping is cheap — it maps
  `tokens`/`toolCalls`/`wallMs` at the boundary without renaming internals or touching any
  existing `Text`.
- **A15 — sort by depth first; A1a alone was wrong, and this change was asserting something it
  could not deliver.** The external consumer found it on the real corpus and reported it as a
  symlink bug; the symlink is a red herring. Under pure code-point sorting the duplicate winner
  depends on the skill's own first letter relative to a sibling directory's name: `docx`, `pdf`
  and `pptx` begin with letters before `s` and beat `synced/<uuid>/…`, while `xlsx` begins with
  `x` and **loses** to it. The scenario this change already carried — *a shallower path beats a
  nested one* — was therefore true for three of the four names in the reported corpus and false
  for the fourth. Discovery now sorts by **depth (segment count) ascending, then code point**,
  with A1a/A1c demoted to the tie-break *within* a depth. The fixture matters as much as the
  rule: a `docx`-only fixture passes under **both** orderings, which is precisely why six ports
  and a spec review all missed this, so the fixture must use a name that sorts after the nested
  directory's first segment.
- **A16 — "not a string" meant "not a scalar".** A10's wording was looser than the reference it
  pointed at: `spikes/issues/93/prototype/lenient.py:69-70` coerces `str`/`int`/`float`/`bool`
  (and `None`) to their string form and excludes only mappings and sequences. So `name: 123`
  loads as `123` and `description: true` as `true`, and neither triggers the rescue; only a
  non-scalar is structurally wrong. The literal reading would have broken parity in the *opposite*
  direction, against behaviour existing cross-port tests already pin. Elixir asked rather than
  guessing, which is how it was caught before seven ports implemented it two different ways.

A seventh round, all of it found by the ports while implementing, and three of the four describe
defects this change never knew about:

- **A17 — admission refusals were never in scope, and saying so is the fix.** Three of A14's nine
  strings (`maxChildren`, `maxConcurrent`, `maxDepth`) name spawn/admission refusals, which in
  js, csharp, elixir and python return an error from the verb and never settle a `TaskResult` at
  all. A vocabulary that names a field some ports never populate invites a port to invent a settle
  path so the string can appear — which would be new behaviour smuggled in under a spelling fix.
  So the spelling is pinned *wherever* a port reports such a stop, and nothing requires it to
  report one. The genuine asymmetry — verb error versus settled result — lives in the **verb's
  return type**, not in the vocabulary, and changing it changes a signature; it is a named
  follow-up and belongs in the changelog's "does NOT do" list.
- **A18 — the invariant, because the instance was not the bug.** A settle that set a status
  without its limit (`status "timeout"`, `limit` empty — the two fields contradicting each other
  inside the feature #90 asked for) was present in **three of five** finished ports; js and elixir
  each found it in their own code, a sweep caught golang and python, and csharp was correct only
  by accident. The decisive detail is what the audits then found: latent instances no reported bug
  touched (golang 1, csharp 2, js 1 closed by type). An instance test would have passed on every
  one of them. So the requirement is the invariant — a limit stop names its limit, a non-limit
  stop leaves it empty — driven over four settle paths, with named constants at every construction
  site so a rename cannot desync the pair, and a preference for making the contradiction
  unrepresentable in the type where the language allows it. A type beats a test.
- **A19 — public vocabularies, private predicate.** The vocabularies are public API in all seven
  ports: a host that cannot name a limit must hard-code its spelling, which defeats D3 and A14
  entirely. The predicate that asserts A18's invariant is the opposite case — it exists so our own
  suite can assert a rule, and shipping it would oblige every future port to implement it forever,
  which is exactly the surface ADR 0019 declined to add. Ports copy the predicate and not the
  export. The internal pool-name mapper stays private for the same reason the vocabulary exists:
  exporting it would publish the internal names A14 was written to exclude.
- **A20 — "the suite compiles" proves nothing about visibility.** csharp noticed its own project
  grants the test assembly access to internals, so a constant demoted to `internal` would keep the
  suite green while breaking every consumer. The same hatch exists almost everywhere: same-package
  test access in go and java, convention rather than enforcement in python, elixir and clojure. Go
  is the one port where the check is free, because visibility is encoded in the identifier's
  spelling. So A19a is verified from outside the module boundary — reflection, an out-of-module
  test, or the name itself — and every enumerable value must also be reachable as a named
  constant, so a host writes the constant rather than the string.

An eighth round — the ordering sweep, and what it taught:

- **A21 — the three shapes of a fix are ranked, and the ranking is recorded so nobody levels it
  down.** A structural chokepoint (elixir's single `task_result/1` normaliser) or a type (js's
  `TaskLimit | undefined`) constrains **code not yet written**: a fourteenth construction site
  cannot reintroduce the contradiction, including by forwarding. A per-site fix with an invariant
  test catches a regression after the fact. A per-site fix with an instance test would have missed
  all four latent instances. Ports still writing this take the structural shape; ports already at
  the middle shape are **not** reopened, because this is internal structure and parity is defined
  on behaviour — but a later reader must not read that tolerance as an invitation to harmonise the
  three into the weakest.
- **A22 — the second ordering nobody had audited.** A1c fixed the *discovery* order, where a defect
  had been reported. The §0.10 skills prompt has its own, separately implemented ordering, one
  function away, and it was three different rules in three ports — js locale-dependent (the same
  code emits a different prompt on a different machine's ICU data, in a prompt SPEC pins
  byte-identical), csharp UTF-16 code unit, the rest code point. Nobody had asked where *else* this
  codebase orders user-visible data.
- **A23 — the scope rule, so thoroughness does not become creep.** A sort is in scope when SPEC
  pins its output byte-identical, or when it is locale-dependent and therefore unstable across
  machines. Everything else is a follow-up: the classifier's canonical-JSON key ordering (deferred
  because a unilateral fix would *create* the drift), Prometheus labels, error texts, the
  unmatched-filter warnings.
- **A24 — an absent sort is invisible to a search for sorts.** csharp's sibling-file sampler had no
  ordering at all; its own A22 audit missed it because that audit looked for comparators and this
  site had none. So the instruction changed shape: audit the directory **reads**, not the
  comparisons. Two ports then independently found a second instance in the same file, which is the
  strongest evidence that broadcasting the *audit* beat broadcasting the fix.
- **A25/A26 — one rule, because five ports fixed A24 five different ways.** A byte-identity break
  was very nearly introduced by the fix for a byte-identity break. The arbitrated rule is collect →
  sort by relative path in plain code point → truncate, chosen over per-level ordering not because
  per-level is incoherent (it reads better) but because it is only expressible with a recursive
  walk: a stack-based port must push a continuation, and one that merely sorts each read emits
  subdirectories in reverse *while looking sorted*. That is correctness contingent on seven ports
  reproducing one traversal shape — the exact coupling this batch exists to delete. And the rule
  is not about skills: `glob` and `grep` had the same defect, worse, in ports nobody had told to
  look.
- **A27 — mutation is the acceptance test, because review does not catch a vacuous fixture.** Five
  fixtures in this batch asserted nothing, and **every one was caught by mutating the
  implementation, never by reading the test**. The self-proving shape is the general answer:
  reproduce the pre-fix walk inside the test, compute first-N-by-walk against first-N-by-sort, and
  fail as a *precondition* if they are the same set. A probe is correct on the machine it ran on; a
  fixture that asserts its own discriminating power is correct everywhere and says so when it stops
  being.

## The mutation evidence ledger

`tasks.md` records what each port must prove; this is what they **have** proved. It is here so a
reviewer can see which fixtures are demonstrated rather than merely asserted, and which are not.

| port | mutations run, each confirmed FAILING | caught a vacuous fixture |
|---|---|---|
| golang | deleted the depth comparison (A15) — failed with the `xlsx` message | — |
| csharp | reverted `StopLimit.Timeout` (A18); `StringComparer.Ordinal` (A22); sort-by-bare-name and cap-during (A25) | **yes**, twice — `inner-a/b.txt` proved nothing, and its first A26 fixture passed under the broken glob |
| js | M1–M6 on the builtins (grep cap-during, grep no-sort, lexical line sort, glob cap-during, glob no-sort, absolute emit) plus three on the skill sample | **yes** — its grep cap fixture was vacuous, caught by A27d's precondition |
| python | C1–C3 (cap-during, sorted-rendered-strings, absolute emit) plus M1–M4 (bare name, cap-during, depth rule, casefold); byte-compared every source after restoring | **yes** — a flat-only fixture could not discriminate |
| elixir | seven (bare name, no global sort, cap-before-sort, cap-during, glob cap-before-sort, grep absolute emit) | **yes** — its hand-probe was wrong, because `File.ls!` returns creation order on that machine |

**Five of five finished ports mutation-proved their ordering fixtures, and four of five caught a
vacuous fixture doing it.** Both halves of that sentence matter. The base rate is alarming — five
fixtures written by competent ports asserted nothing, and code review caught none of them — and
the mitigation worked every single time it was applied. Elixir's case is the sharpest argument for
A27d over A27c: its manual probe was *wrong about its own filesystem*, which a self-proving
precondition would have reported rather than passed.

**Genuinely unproven, stated rather than smoothed over:**

- **java and clojure have not reported.** Every ordering claim in this change is unproven in those
  two ports. They are also the two ports A21 asks to take the structural shape, so they carry the
  most unwritten work.
- **The A11 17-file frontmatter table has never been mutation-tested in any port.** Every port
  reproduces it row for row, which is strong evidence the ports agree — and is not evidence the
  table can tell a wrong implementation from a right one. Its non-vacuity rests on the argument
  that it compares description **strings** rather than verdicts, and `hash-inline` is the row that
  argument turns on. An argument is not a demonstration, and this batch produced five fixtures
  whose arguments were fine and whose assertions were empty. A mutation task for it is added to
  `tasks.md` (D6.7d): keep the `#…` tail on `hash-inline` and confirm the table fails. It is two
  minutes of work and it is the difference between the arbiter being trusted and being assumed.

## Accepted port-local deviations

These are conformant and must not be "fixed" into uniformity:

- **csharp names the seven-value agent vocabulary `AgentStatus`, not `TaskStatus`.**
  `System.Threading.Tasks.TaskStatus` is in scope in every file in that port, so the obvious name
  is taken. The **values** conform, which is what the spec pins; the holder's name is local, and
  no other port renames on account of it. The agent-runtime delta states this explicitly, so a
  later parity sweep does not read it as drift.
- **`answerDeclined` is canonical in all seven** (A9). The js port shipping it first was not
  drift — the other six adopt it.

## The one gap this change does not close

**Nothing pins the invariant that a third closed vocabulary cannot land on a field named
`status`.** D5 ships named constants for both existing sets and documents them as two
vocabularies, which fixes the reading error behind #92 — a host branching on `TaskResult`'s
seven-value set while holding `RunResult`'s three-value one. It does not prevent the same
collision recurring, because the collision is a property of the *naming*, not of the values, and
the decision not to rename either public field is deliberate (see the rejected alternative above).

This is accepted as a real residual gap rather than argued away. It is tracked as a **follow-up
conformance row** — a gate that enumerates every field named `status` across the seven ports and
fails when a closed vocabulary appears on one without being declared — and it is named in the
changelog so that its absence does not stop being mentioned. An omission that stops being printed
is indistinguishable from something that was finished.

## Noted, not fixed here

- Whether `ask` should also drop its `id` argument, and the rest of the call-shape gate's
  candidate rows (ADR 0023 D4). The gate itself — `conformance/callshape_manifest.json` plus
  executed per-port probes — belongs in its own change, seeded with `completion.no-toolkit` as
  its first green row. A gate introduced with a backlog of red rows becomes a `continue-on-error`
  line in CI.
- Whether redaction should apply to **tool results** too. A tool proxying a provider carries the
  same identifiers into a transcript, which is a wider blast radius than an error string.
- Whether the Java overload set should be pruned generally. Folding an API-surface argument into
  a bug fix would hide it.
- Claude Code's `name` ← directory-name fallback. §3 requires `name`; `missing-name` is an
  already-typed, already-consistent skip.
