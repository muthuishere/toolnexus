# DECISIONS — fix-consumer-issues-86-93 (AUTHORITATIVE, owner-approved 2026-09-21)

Every port implements exactly this. Do not re-litigate; if a decision is impossible in your
language, STOP and report rather than inventing a variant. Evidence for each sits in
`docs/adr/0023`–`0028` and `spikes/issues/`.

Ports: js, python, golang, java, csharp, elixir, clojure. A change lands in all seven or it is not done.

## D1 — toolkit-less completion (#86, ADR 0023)
The bug is ONE bug: building the §0.10 system message dereferences the toolkit for
`skillsPrompt()` before anything touches tools. Fix = null-guard at that site, per port.
- No new public `Toolkit.empty()` in any port. `builtins:false` already expresses "no tools".
- Spellings: python `toolkit: Toolkit | None = None`; js `ctx?` + `ctx.toolkit?`;
  csharp `Toolkit?` nullable (NOT overloads); java overloads for exactly three shapes
  (prompt / prompt+id / prompt+onText); elixir default args giving `run/2`,`ask/2`,`stream/2`;
  golang + clojure already work — add tests only.
- Wire assertion (already true, must be pinned by test everywhere): a toolkit-less request body
  has NO `tools` and NO `tool_choice` key. Not an empty array.

## D2 — Loop honours the Spec (#87, ADR 0024)
- golang `clientFor` applies `Spec.Soul` and `guardedHooks(spec)`. Caller-supplied
  `SystemPrompt` WINS over the soul (python/csharp precedence is correct; js currently
  lets the soul override — align js to caller-wins).
- `Spec.Model` and `Spec.Budget.MaxTurns` become Loop defaults in every port.
- `Tools/Team/WaitFor/OnMetric` remain genuinely unhonourable by a driver. NO signature change,
  NO construction-time error (breaking). Instead every port ships additive
  `loopUnsupported(spec) -> [field names]` and names the limitation in docs + the ADR table.
- Regression test per port asserts the DENIED TOOL'S EXECUTE IS NEVER ENTERED, never the text.

## D3 — runtime legibility (#88/#90, ADR 0025)
- `TaskResult.TotalTokens` = cumulative tree total on EVERY status (today it is already the tree
  total on error/closed/timeout/settled — this unifies a field with two meanings). Add `OwnTokens`
  for the per-agent figure. Do NOT "fix" the 14 doc sites; they were right.
- Add `Limit` to `TaskResult` in the six ports lacking it, populated from `RunResult.Limit` AND
  set for budget stops. Elixir already has it — align spelling only.
- `Runtime.Resume` returns `(TaskResult, error)`.
- java: replaying the literal `"continue"` is a §0 break — fix independently (restore prompt + inbox).
- csharp/clojure: stop hardcoding maxTurns wording; report the real limit.
- DEFERRED to its own change: transcript replay on resume. Rewind-to-checkpoint is SPEC'd by name,
  so this is a §0 decision, not a bugfix. Instead: state the idempotency contract loudly in docs
  and in `WaitFor` comments, and say resumed turns re-run a leaf's own tools.

## D4 — the Answer payload contract (#89, ADR 0026)
- On `Ok == true` with no determinable result for an outstanding call, ERROR TO THE HOST.
  Do not hand the model a fabricated result. (`RunWithAnswer` already errors on a mismatched id;
  the asymmetry is the bug.) Keep fabricated filler ONLY for genuine partial relay answers.
- Add `AnswerOutput(id, output)` constructors in all seven ports.
- Kind-aware defaulting is REJECTED (it guesses, and re-hides the failure).
- elixir `Answer` must accept string keys (atom-only raises on JSON round-trip);
  clojure must accept string keys (keyword-only silently reads as DECLINED). §10 says keys are fixed.
- Fix the failure-silent type assertions: a non-string `output` must error, not degrade to "".
- DEFERRED to its own change: non-relay resumes re-executing the tool with `ctx.answer`.

## D5 — what we hand back when we fail (#91/#92, ADR 0027)
- Classifier: KEEP `jev-latest` (it is servable on TypeSafe). Add a `backend` preset
  (`typesafe` | `openrouter`) setting baseUrl+model+apiKeyEnv AS A UNIT, and fail at construction
  on the known mismatch: `model "jev-latest" is TypeSafe's spelling; on openrouter.ai use "typesafe/jev-1.13"`.
- The `status` NAME COLLISION (agents' 7-value set contains `timeout`, client's 3-value set does not)
  is the real defect behind #92.1. Do NOT rename public fields. Ship named constants for BOTH sets
  in every port and document them as two distinct vocabularies in SPEC §8 / §7D.
- golang timeout: stop returning a zero-value result beside a non-nil error. Return
  `Status: "incomplete"`, `Limit: "timeout"` — invents no new §8 status value — and PRESERVE the
  Turns/Usage accumulated before the deadline. Other ports keep throwing, but their messages must
  name the budget; elixir replaces its bare `RuntimeError` with a typed timeout error.
- clojure has NO run-level deadline and RETRIES timeouts — an unreported §8 break. Add the
  run-level deadline; a timeout is not retryable.
- Error bodies: typed error carrying `status` / `body` / `retryAfter` in every port; redact
  `user_id`, `account_id`, `org_id`, `organization` to `«redacted»`; lift the classifier's
  cap + 401/403-blanking policy to the §8 path. A CAP IS NOT REDACTION — the leaking body is 96 bytes.
- Extend the SPEC credentials guarantee to cover error messages.
- MUST NOT REGRESS (assert in tests): fail-fast on 4xx via the enumerated retryable set
  {429,500,502,503,504,529}; `ClassifierUsage.Cost` as an optional where absent != zero.

## D6 — a skill the writing tool accepts (#93, ADR 0028)
- Lenient frontmatter, INVERTED: strict YAML FIRST; the line-wise `key: rest-of-line` read runs
  ONLY on frontmatter YAML has already refused. Line-wise-first misparses block scalars and is
  forbidden. Guards: column 0, first-wins, refuse any value opening with | > & * [ { !
  Measured target: `~/.claude/skills` 81 -> 87 parse, block scalars byte-identical, no invented
  descriptions, genuinely-malformed files still refused.
- Skip records gain a `detail` field carrying the native parser error. `reason` stays byte-identical.
- `LoadSkills` exposes skips (a `Warn` hook or returned skips) — hosts must not need `ListSkills`
  to learn that 6 of 87 vanished. Check against ADR 0014 before choosing the hook shape.
- clojure's hand-rolled `frontmatter.cljc` is replaced with real YAML across both `.cljc` hosts.
  It loads 33 of 87 where the others load 69. This is the single highest-value fix in the batch.
- §3 must specify DISCOVERY ORDER, not just first-wins: `docx`/`pdf`/`pptx` currently resolve to
  DIFFERENT FILES per port.

## Standing rules for this batch
- Seven-port parity is the gate. A port left behind is named in CHANGELOG + tasks.md, never silent.
- Tests are hermetic: mock LLM, no network, no API key. Reuse `spikes/issues/*` harnesses.
- One `## Unreleased` CHANGELOG entry, user-facing wording, naming what is DEFERRED by name.
- Conventional commits, no `Co-authored-by`. Do not tag, do not publish, do not release.

## ADDENDUM — 7 gaps closed 2026-09-21 (owner-approved, same authority as above)
Raised by the OpenSpec agent as unspecifiable-as-written. Each would have produced seven variants.

- **A1 (D6 discovery order).** Dirs in the order the CALLER passed them; within a dir,
  lexicographic by path relative to that dir's logical base; first-wins. Deterministic, no
  filesystem-order dependency (Node's readdirSync is already sorted; others must sort explicitly).
- **A2 (D6 skip surface).** RETURNED SKIPS on the `LoadSkills` result — data, not a hook. A hook
  shape differs per port and cannot be a parity gate. A port that already has a warn slot may
  ALSO call it; the returned data is what conformance compares.
- **A3 (D4 keys + precedence).** Recognised keys are `results`, then `output` (+`isError`), in
  that precedence. "Genuine partial relay answer" = at least one RECOGNISED key present; that is
  the only case keeping the fabricated filler. Pin the keys in a scenario, not only in SPEC prose.
- **A4 (D3 Resume semantics).** Returns the result of the TOPMOST handle the cascade re-ran.
- **A5 (D5 cap vs typed field).** Redaction applies to BOTH the typed `body` field and the
  message. The 200-char cap applies to the MESSAGE ONLY — the typed field carries the full
  redacted body, because a host that opted into a typed error asked for the whole thing.
- **A6 (D2.4 loopUnsupported names).** Fixed canonical vocabulary, identical in all seven ports,
  exactly as the limit strings are: `"tools"`, `"team"`, `"waitFor"`, `"onMetric"`. Not the
  language's own spelling of the field.
- **A7 (D5b status collision).** Accepted as a real residual gap: named constants pin the values,
  nothing pins the INVARIANT that a third vocabulary can't land on a field called `status`.
  Tracked as a follow-up conformance row, NOT solved by this change. Say so in the changelog.

## ADDENDUM 2 — A1 tie-break pinned + its blast radius (owner-approved 2026-09-21)
- **A1a.** Tie-break is Unicode CODE-POINT comparison of the whole path relative to the root's
  logical base. Symlinks sort at their DISCOVERED path, not their target. No locale collation,
  no case folding, no path-segment-aware comparison.
- **A1b (behaviour change, must be in the changelog).** This makes `docx/SKILL.md` beat
  `synced/<hash>/docx/SKILL.md`, so the python/java/csharp winner becomes the contract and the
  duplicate winner FLIPS in js, golang and elixir — same skill name, different file, different
  `content`. That is a user-visible change in three ports, not a no-op refactor.

## ADDENDUM 3 — three forks raised by the js port, closed for all seven (owner-approved 2026-09-21)
- **A8 (`Spec.Model` as a Loop default).** The spec's model applies when the caller's model is
  ABSENT **or** equal to the sentinel `"inherit"`. Ports whose `model` field is optional must
  STILL honour the `"inherit"` sentinel, not only absence — otherwise a caller who passes a real
  model plus a spec model gets different behaviour per port. One rule, both spellings.
- **A9 (`answerDeclined`).** ADOPTED in all seven, alongside `answerOutput`. It is the natural
  pair (a human who says no is not an error), and a constructor shipped in one port only is
  exactly the drift this change exists to end. Same canonical shape: `answerDeclined(id, reason)`.
- **A10 (YAML-recovery divergence — the subtle one).** Ports' YAML libraries disagree about
  `description: [unterminated`: some THROW (rescue runs), some RECOVER it into a sequence
  (rescue never runs). Both paths must reach the SAME observable outcome, so the rescue triggers
  when the YAML parse throws, OR yields a non-mapping, OR yields a mapping whose `name`/
  `description` is present but NOT a string. The invariant that decides every case: a file
  never gains an INVENTED description, and never silently keeps a structurally-wrong one.
  The shared fixture table in `spikes/issues/93/fixtures/` is the arbiter — every port must
  produce an identical accept/skip/detail table over it.

## ADDENDUM 4 — A1a was under-specified for UTF-16 hosts (owner-approved 2026-09-21)
Raised by the csharp port. A1a says "code-point comparison". The natural comparator on every
UTF-16 host is CODE-UNIT order, which disagrees with code-point order for any character above
U+FFFF (surrogate pairs sort before U+E000..U+FFFF instead of after).

- **A1c.** CODE-POINT order is normative. It is identical to code-unit order for all ASCII and
  BMP paths, so nothing in the real corpora changes — this is about not planting a divergence
  that only fires on an astral-plane filename, on some hosts, years from now.
- **UTF-16 hosts must compare explicitly**, not with the platform default:
  - **csharp**: `string.CompareOrdinal` is code-UNIT order — NOT sufficient. Add a code-point comparer.
  - **java**: `String.compareTo` is code-UNIT order — NOT sufficient (this corrects my earlier
    instruction to that port). Use `String.codePoints()` / `Character.codePointAt` comparison.
  - **clojure (JVM host)**: same as java; the JS host must use a code-point compare, never
    `localeCompare`. Both hosts must agree.
  - **js**: already correct — `compareCodePoints`, not `<` on UTF-16 units and not `localeCompare`.
  - **golang / python / elixir**: iterate runes/code points natively; still sort explicitly.
- **A1d.** SPEC §3 must state WHICH order wins in one line, so this is decided in the contract
  rather than re-derived per port.

## ADDENDUM 5 — accepted port-local deviations (do NOT "fix" these into parity)
- **csharp names the 7-value agent vocabulary `AgentStatus`, not `TaskStatus`** —
  `System.Threading.Tasks.TaskStatus` is in scope in every file there. The VALUES conform; the
  type name is local. No other port should rename on account of this.
- **`answerDeclined` (A9) is canonical**, so js adding it was not drift — all seven ship it.

## ADDENDUM 6 — the shared fixture table is now PINNED (owner-approved 2026-09-21)
csharp and the `lenient.py` prototype agree exactly over `spikes/issues/93/fixtures/` (17 files).
That agreement is the reference every port must reproduce — 14 ok / 3 skip:

| fixture | result | description |
|---|---|---|
| anchors | ok | `A skill using a YAML anchor and alias.` |
| block-folded | ok | `A folded description that runs across two source lines.` |
| block-literal | ok | `First line of the description.\nSecond line, with a colon: still fine inside a block scalar.` |
| broken-flow | **ok** | NONE — name kept, description absent (the rescue DECLINES a `[` value) |
| broken-tab | ok | `tab-indented continuation` |
| broken-unclosed | skip | reason `missing-name` |
| colon-space | ok | the full line, via the rescue |
| colon-space-quoted | ok | strict YAML |
| colon-space-single | ok | strict YAML |
| hash-inline | ok | `Tag things with` — ` #` opens a YAML COMMENT |
| list-block, list-value, nested-map, plain, url-colon | ok | strict YAML |
| no-frontmatter | skip | reason `missing-name` |
| no-name | skip | reason `missing-name` |

- **A11.** `hash-inline` is the row most likely to diverge, and it is NOT decided by the rescue —
  it is decided by the YAML library's comment handling on `Tag things with #stockloop`. A port
  whose parser keeps the `#…` tail still reports `ok` while carrying a DIFFERENT description:
  a silent table mismatch. Assert the description STRING, never just the ok/skip verdict.
- **A12.** `broken-flow` must yield name-WITHOUT-description, NOT a skip. Libraries that throw
  and libraries that recover into a sequence only land on the same row because of A10's
  non-string guard. This row is the proof A10 works; if it skips, A10 is not implemented.

## ADDENDUM 7 — A13 — ⚠️ SUPERSEDED BY ADDENDUM 8 (A13a/A13b). READ THAT FIRST.
> **Do not implement this section as written.** Its phrase "cumulative TREE total, exactly
> like `TotalTokens`" is WRONG: turns are PER-HANDLE and are not rolled up. Two ports acted
> on this wording before it was corrected. The corrected rule is A13a/A13b in ADDENDUM 8,
> and `examples/subagent-fanout/fixture.json` is the arbiter (ADDENDUM 9).

### Original text, kept for the record
Raised by the golang port; VERIFIED as live drift before deciding — js already returns the
cumulative `turnsTotal` on every branch, golang still returns a per-run figure on
done/pending/incomplete and a cumulative one on error/closed/timeout.

- **A13.** `TaskResult.Turns` is the CUMULATIVE TREE TOTAL on EVERY status, exactly like
  `TotalTokens`. This was in ADR 0025 D1; my D3 named only `TotalTokens`/`OwnTokens`/`Limit`,
  and that omission is mine, not the ports'. No `OwnTurns` — tokens get an own-figure because
  they are billed; turns do not.
- Rationale: `Turns` meaning one thing on three statuses and another on the other three IS
  issue #88, one field over. Shipping the fix for tokens while leaving the identical defect in
  the neighbouring field would be indefensible in the changelog.
- Every port re-checks this one line before reporting done. js is already correct; golang,
  csharp and any port that mirrored D3 literally are the likely misses.

## ADDENDUM 8 — A13a CORRECTS A13's wording (owner-approved 2026-09-21)
Raised by the golang port, which flagged instead of guessing. VERIFIED in source before deciding:
`rollupLocked` walks the ancestor chain for TOKENS and tool calls only; `turnsTotal` is a
PER-HANDLE accumulator in every port. So A13's "cumulative TREE total, exactly like TotalTokens"
was wrong in two ways, and both are mine:

- **A13a (the rule).** `Turns` is the handle's OWN cumulative round trips, reported identically
  on EVERY status. It is NOT rolled up the ancestor chain. The defect being fixed is the
  field meaning one thing on three statuses and another on the other three — nothing more.
  Do NOT add a turns roll-up: that is new behaviour, not a parity fix, and no port does it today.
- **A13b (delete the false clause).** "A parent never reports fewer turns than its child" is
  FALSE and must not be spec'd: a parent can delegate in ONE turn to a child that takes five.
  It is true for tokens (they roll up) and not for turns. The `parent >= child` assertion is
  valid ONLY in the specific one-delegation fixture where the parent spends a turn delegating
  and a turn answering — keep it as a fixture assertion, never as a general guarantee.
- Still no `OwnTurns`: with no roll-up, `Turns` already IS the own-figure.

## ADDENDUM 9 — the shared fixture already proved A13a (arbitration, 2026-09-21)
python escalated that `examples/subagent-fanout/fixture.json` contradicts A13. It does not —
it contradicts A13's WRONG first wording, and it is independent evidence for A13a. The fixture
pins BOTH of these, side by side, and has done since long before this change:

    "parentTurns": 2,          <- turns are PER-HANDLE; children's turns never enter the parent
    "parentUsageTotal": 240,   <- tokens DO roll up the ancestor chain

- **Ruling: `examples/` is NOT edited.** No port bumps `parentTurns`. A shared fixture that four
  ports read is the contract; a decision doc written today does not outrank it, and when the two
  disagree the fixture is the thing that was measured.
- Any port that implemented the roll-up reading must REVERT it and restore the fixture-driven
  assertion. csharp hit the same wall (its fanout usage-rollup test failed) and reverted; python
  must do the same.
- This is the second time the fixture caught a wording error of mine within an hour. That is the
  argument for A1's shared-fixture arbiter, restated: the fixtures outrank the prose.

## ADDENDUM 10 — A14: the `limit` vocabulary is CLOSED and canonical (owner-approved 2026-09-21)
Raised by python. Verified: FOUR ports already disagree on the strings they put in the field we
added so hosts could BRANCH on it — golang `maxWall`, csharp `tokens`/`wallMs`, python `tokens`,
js `maxTokens`/`maxToolCalls`. A limit value that is not portable defeats the entire purpose of
D3's `Limit`; this is #90's own complaint re-created inside its own fix.

- **A14.** The value of `limit` NAMES THE BUDGET FIELD THAT STOPPED THE RUN, spelled exactly as
  `SPEC.md` spells it in `Budget { maxTurns, maxTokens, maxToolCalls, maxWallMs, maxChildren,
  maxConcurrent, maxDepth }`, plus the two non-budget stops `completion` and `timeout`.
  CLOSED vocabulary, identical in all seven ports, like the `loopUnsupported` strings:

      maxTurns · maxTokens · maxToolCalls · maxWallMs · maxChildren · maxConcurrent · maxDepth
      completion · timeout

- **A port MUST MAP its internal pool/dimension name onto these.** An internal name is an
  implementation detail and may not leak into the field. Named misses to fix:
  golang `maxWall` -> `maxWallMs`; csharp `tokens` -> `maxTokens`, `wallMs` -> `maxWallMs`;
  python `tokens` -> `maxTokens` (and every other dimension); js: verify the full set, not just
  the two it has.
- Rationale: "which limit stopped me" is only answerable if the answer is the same word
  everywhere. Go already proved the mapping is cheap — it maps `tokens/toolCalls/wallMs` at the
  boundary without changing its internal names or any existing `Text`.

## ADDENDUM 11 — A15: sort by DEPTH first. A1a alone was wrong. (owner-approved 2026-09-21)
Found by the external consumer (bug-fixer-platform) verifying against this branch, and PROVEN
here before deciding. They reported it as a symlink bug. It is not — the symlink is a red herring:

    pure code-point sort          depth-then-code-point
    docx -> docx/          TOP    docx -> docx/          TOP
    pdf  -> pdf/           TOP    pdf  -> pdf/           TOP
    pptx -> pptx/          TOP    pptx -> pptx/          TOP
    xlsx -> synced/../xlsx NESTED xlsx -> xlsx/          TOP   <-- the whole defect

`docx`/`pdf`/`pptx` begin with letters BEFORE `s`, so they beat `synced/…`; `xlsx` begins with
`x`, so it LOSES to `synced/…`. The winner depends on the skill's first letter relative to a
sibling directory's name. That is indefensible, and it is not what anyone intended.

- **A15.** Discovery sorts by **DEPTH (number of path segments) ASCENDING, THEN by Unicode
  code point** over the path relative to the root's logical base; first-wins. A1a's comparison
  rule is unchanged — it becomes the TIE-BREAK WITHIN a depth, not the whole order.
- **This makes the rule everyone already wrote down actually true**: a top-level skill beats a
  nested copy of the same name, uniformly, for every name. Today SPEC §3 and the OpenSpec
  scenario *A shallower path beats a nested one carrying the same name* assert exactly this —
  and pure code-point sorting does NOT deliver it. The scenario passes for `docx` and would
  fail for `xlsx`. I wrote that claim; the implementation never honoured it.
- **Every port re-sorts and re-tests**, including the four already reporting done. The fixture
  must carry a name that sorts AFTER the nested directory's first segment (e.g. `xlsx` vs
  `synced/`), or it proves nothing — a `docx`-only fixture passes under both rules, which is
  exactly why six ports and one spec review all missed this.
- Symlinks still sort at their DISCOVERED path (A1a), unchanged. Resolution order is not the bug.

## ADDENDUM 12 — A16: A10's "not a string" means "not a SCALAR" (owner-approved 2026-09-21)
Raised by elixir, which read A10 correctly and flagged the ambiguity rather than guessing.
Checked against the reference `spikes/issues/93/prototype/lenient.py:69-70`, which is the arbiter:

    data = {str(k): str(v).strip() for k, v in parsed.items()
            if isinstance(v, (str, int, float, bool)) or v is None}

- **A16.** A YAML SCALAR value COERCES to its string form: `name: 123` -> `"123"`,
  `description: true` -> `"true"`. Only a NON-SCALAR — a mapping or a sequence — is
  structurally wrong and triggers the A10 rescue. My A10 wording said "not a string", which is
  looser than the reference it pointed at; the reference wins.
- This is already pinned by existing cross-port tests, so reading A10 literally would have
  broken parity in the other direction — which is exactly what elixir said when it asked.

## ADDENDUM 13 — A17: admission refusals are NOT in scope (owner-approved 2026-09-21)
Raised by js while auditing A14's full set. Three of the nine canonical limit strings —
`maxChildren`, `maxConcurrent`, `maxDepth` — are SPAWN/ADMISSION refusals. In js they return a
verb error and never settle a `TaskResult`, so there is no `limit` field to populate.

- **A17.** This batch does NOT change where an admission refusal surfaces. A port keeps whatever
  it does today: a verb error in some, possibly a settled result in others.
  **No port invents a new settle path to make the string appear.**
- The nine strings remain the closed vocabulary (A14) so the spelling is pinned WHEREVER a port
  reports such a stop. A port that never reports one simply never emits those three.
- **Recorded as a follow-up, not solved here**: whether an admission refusal should be a verb
  error or a settled `TaskResult` is a real cross-port asymmetry, and it lives in the VERB'S
  RETURN TYPE, not in the limit vocabulary. Fixing it means changing a signature in some port,
  which is its own change. Name it in the changelog's "does NOT do" list.
- Also found by the same audit and FIXED: js's wait-deadline settled `status: "timeout"` with no
  `limit` at all — the agent status and the limit field contradicted each other. Every port
  checks that a timeout settle sets BOTH.

## ADDENDUM 14 — A18: test the INVARIANT, not the instance (owner-approved 2026-09-22)
The wait-deadline bug (status says "timeout", `limit` empty — the two fields contradicting each
other inside the very feature #90 asked for) was present in THREE of five finished ports. js and
elixir each found it independently in their own code; a sweep caught golang and python. csharp was
correct only because it happened to pass its Timeout constant explicitly.

- **A18.** Every port ships an INVARIANT test, not only the one-site fix:
  **a limit stop MUST name its limit; a non-limit stop MUST leave it empty.** Drive it over at
  least a `done`, a budget-`incomplete` and a `closed`/settled result, and assert each value is a
  member of its own closed vocabulary (the 7-value task-status set, the 9-value A14 limit set).
- Golang's audit of all 13 of its `Status:` construction sites found a LATENT SECOND INSTANCE
  the reported bug never touched: a branch forwarding `r.Limit` straight through would reproduce
  the contradiction for any `incomplete` result arriving with an empty limit. An instance test
  would not have found that. This is why A18 is the invariant.
- Use the named constants at every construction site, never string literals, so a rename cannot
  silently desync status from limit.
- **Test-shape warning from golang, for ports writing this test:** `Wait(handle, 0)` after a
  close returns the handle's SETTLED LAST result (e.g. the earlier budget stop), not a fresh
  `closed` result. That is correct behaviour, not a bug — drive the closed branch explicitly
  instead, or the test asserts something other than what it claims.

## ADDENDUM 15 — A19: the vocabularies are PUBLIC, the invariant predicate is NOT
Raised by js, which exported `S`, `L` and a `limitInvariant(r)` helper and asked whether that is
drift. Split ruling:

- **A19a — the two vocabularies are PUBLIC API in all seven ports.** A host must be able to
  branch on `limit` without hard-coding strings; that is the entire point of D3 and A14, and
  SPEC already documents both sets. Ports that kept them module-private should export them.
  Naming stays port-local (ADDENDUM 5): the VALUES conform, the holder's name does not.
- **A19b — `limitInvariant` stays TEST-ONLY and is NOT exported in any port, including js.**
  Please demote it. A predicate that exists so our own tests can assert a rule is not something
  seven public APIs should carry forever; shipping it would make every future port owe an
  implementation of it, and ADR 0019 already rejected adding surface to express something the
  tests can hold. Copy the PREDICATE between ports, not the export.
- js's stronger move is the one to copy where a language allows it: close the latent site BY
  CONSTRUCTION (its `poolLimit`/`turnCap` now return `TaskLimit | undefined`, so the compiler,
  not a mapper, guarantees no internal pool name reaches the field). A type beats a test.

## ADDENDUM 16 — A20: "the suite compiles" is NOT evidence of public visibility
Raised by csharp, which noticed `Toolnexus.csproj` carries `InternalsVisibleTo(Toolnexus.Tests)`
— so a vocabulary constant demoted to `internal` would keep compiling in the suite while breaking
every real consumer. The same escape hatch exists in other ports:

- **golang** — `go test` inside the SAME package sees unexported identifiers. (Checked: the
  constants are capitalised, so exported by spelling; visible in the name itself, no test needed.)
- **java** — tests in the same package see package-private members.
- **csharp** — `InternalsVisibleTo`, as above.
- **python / elixir / clojure** — visibility is convention or a macro, not enforcement.

- **A20.** A19a is verified from OUTSIDE the module boundary, not from the suite's ambient access:
  by reflection (csharp's `Type.IsPublic` + `BindingFlags.Public|Static`), by an out-of-package
  test, or — where the language encodes visibility in the NAME (go) — by the spelling itself.
  Assert that every value in the enumerable set is ALSO reachable as a named public constant, so
  a host can write `StopLimit.MaxWallMs` rather than a string literal.
- The internal pool-name MAPPER stays internal in every port. It is an implementation detail of
  the budget walk, and exporting it would leak exactly the internal names A14 exists to keep out.
- csharp also added a guard that no public `bool`-returning method appears on the limit vocabulary,
  so a future "just expose the check" cannot quietly undo A19b. Copy that where it is cheap.

## ADDENDUM 17 — A21: prefer a STRUCTURAL guarantee over per-site correctness
Three shapes of the A18 fix emerged. They are not equivalent, and the ranking is worth recording:

1. **Structural — a single chokepoint (BEST).** elixir routes every result through one
   `task_result/1` normaliser: a limit stop gets its limit canonicalised, a non-limit stop gets an
   EXPLICITLY emptied one. A fourteenth construction site cannot reintroduce the contradiction,
   including by forwarding. js achieves the same by TYPE (`poolLimit`/`turnCap` return
   `TaskLimit | undefined`; `TaskResult.limit` is typed, so no site can put an arbitrary string
   there). A type or a chokepoint beats a test, because it constrains code not yet written.
2. **Per-site fix + invariant test (ACCEPTABLE).** golang, csharp, python. The invariant test
   catches a regression after the fact; it does not prevent one.
3. **Per-site fix + instance test (INSUFFICIENT).** What the reported bug alone would have bought.
   It would have missed all four latent instances the audits found.

- **A21.** Ports still writing this — java, clojure — take shape 1 where the language allows it.
  Ports already at shape 2 are NOT reopened for a refactor: this is internal structure, not
  observable behaviour, and parity is defined on behaviour. Their invariant tests hold the line.
- Recorded so a later reader does not "harmonise" the three shapes into the weakest one.

## ADDENDUM 18 — A22: the §0.10 SKILLS PROMPT is sorted three different ways (owner-found)
Found by sweeping for withdrawn A1c comparators and noticing the hit was in a DIFFERENT sort.
A1c fixed the DISCOVERY order. The §0.10 skills prompt has its own, separate ordering, and it
was never audited — even though byte-identity of that prompt is the repo's prime directive:

    js       a.name.localeCompare(b.name)      <- LOCALE-DEPENDENT. Same code, different output
                                                  on a different machine's ICU data.
    csharp   StringComparer.Ordinal            <- UTF-16 CODE UNIT; diverges above U+FFFF
    golang   described[i].Name < described[j]  <- code point (byte-wise over UTF-8)
    python   sorted(...)                       <- code point
    elixir   Enum.sort_by(& &1.name)           <- code point
    clojure  (to verify)
    java     (to verify)

- **A22.** The skills-prompt ordering is **Unicode CODE POINT over the skill NAME**, in all seven
  ports — the same rule as A1c, applied to the second place it was needed. js MUST drop
  `localeCompare`; csharp MUST use its existing `CompareCodePoints` rather than
  `StringComparer.Ordinal`; java and clojure verify.
- **Why this is not out of scope**: `SPEC §0.10` pins the skills prompt as byte-identical across
  ports, and js's ordering is not even stable across MACHINES. Two ports emitting a differently
  ordered prompt for the same `skills/` directory is the exact drift this repo exists to prevent,
  and we are already in the loader.
- **The lesson for the conformance gate**: A1c was fixed where a defect had been REPORTED.
  Nobody asked "where else does this codebase sort user-visible data?" — and the answer was one
  function away, in the more important place. Every port greps its own skill module for every
  sort/compare and reports what each one orders and by what rule.

## ADDENDUM 19 — A23: where A22 STOPS (owner-approved 2026-09-22)
js swept the rest of its source after A22 and surfaced every other `sort`. Ruling on each, so the
scope of this batch is decided rather than drifting:

- **IN SCOPE, fixed:** the §0.10 skills prompt (A22) and the `skill` tool's not-found
  "Available skills: …" list. Both are user- and model-visible text from the skill loader, and
  §0.10 pins the first byte-identical. js fixed both; all seven check both.
- **DEFERRED, recorded, NOT touched:** the classifier's `canonicalJson` / `canonicalRequest` key
  ordering. js orders by UTF-16 code unit, golang byte-wise over UTF-8 — IDENTICAL for every
  ASCII and BMP key, and canonical-request keys are schema names, so there is no observable
  divergence today. It is nonetheless a real cross-port byte-identity path (it is the canonical
  form ports must agree on), so it needs a COORDINATED seven-port ruling, not a unilateral edit.
  Changing it in one port would CREATE the drift it is meant to prevent. js was right to flag
  rather than fix. Tracked as its own follow-up.
- **OUT OF SCOPE:** Prometheus label ordering, `glob` results, the `unknown agent (known: …)` and
  `task` team-list error texts. Not pinned by §0, not locale-dependent, no cross-port contract.
- **The rule this establishes:** a sort is in scope when SPEC pins its output byte-identical, or
  when it is LOCALE-dependent (unstable across machines, which is a defect in any port). Otherwise
  it is a follow-up. Expanding further would be scope creep dressed as thoroughness.

### A23 addendum — unmatched-filter warnings (csharp's find)
csharp flagged that `ApplyFilter` iterates `filter.Keys` in dictionary order to emit
`skill filter name "X" matched no skill` warnings: the SET is deterministic, the ORDER is not.
Applying A23's own rule: not pinned by §0, not locale-dependent, goes to stderr — so it is a
FOLLOW-UP, not this batch. The one caveat worth carrying into that follow-up: if any port has
made those warnings part of a RETURNED value rather than stderr, ordering becomes comparable
data and would need the same code-point rule. Two ports independently found the "second
instance in the same file" (js `:458`, csharp `:288`), which is the strongest argument that the
audit instruction — not the individual fix — was the right thing to broadcast.

## ADDENDUM 20 — A24: an ABSENT sort is invisible to a search for sorts (owner-approved 2026-09-22)
csharp found that `SampleSiblingFiles` had NO ordering at all — it iterated raw
`Directory.EnumerateFileSystemEntries`, i.e. filesystem order, which promises nothing and is not
stable across runs on some filesystems. Its own A22 audit had missed it, because it audited
COMPARATORS and this site had none.

- **A24.** Every port greps for DIRECTORY READS over shipped output — `readdir`, `ls`, `File.ls`,
  `Files.list`, `EnumerateFileSystemEntries`, `os.listdir`, `os.walk`, `filepath.WalkDir` — and
  confirms each one that feeds user- or model-visible output is EXPLICITLY sorted by code point.
  An audit for `sort`/`OrderBy`/`compare` cannot see this class.
- **It is a CONTENT bug, not a cosmetic one.** The sample walk stops at the cap, so an unsorted
  read changes WHICH files land in `<skill_files>`, not just their order. csharp's test pins both
  halves: the full order, and the capped order at `SampleLimit = 2`.
- **Sort by NAME at each directory level, not by full path** — that is what makes a port agree
  with a sorted-`readdir` port walking the same tree with the same stack. csharp verified its
  traversal shape against js line by line (LIFO stack, node_modules/.git skip, realpath cycle
  set, SKILL.md excluded) and found name-ordering at each level was the only missing piece.
- Ports should also route DISCOVERY's directory reads through the same sorted helper. A15
  re-sorts candidates explicitly so it is not load-bearing today, but an unsorted read there is
  one refactor away from mattering and costs nothing to fix.

## ADDENDUM 21 — A25: ONE rule for the <skill_files> sample (owner-approved 2026-09-22)
A24 made five ports fix the sample list, and they fixed it FIVE DIFFERENT WAYS. golang and python
each flagged the divergence and asked rather than assuming; js, csharp and elixir each shipped a
different rule. This would have been a byte-identity break introduced BY the fix for a
byte-identity break.

    golang   flatten, sort by relative path, plain code point      <- CORRECT
    python   flatten, sort by relative path, plain code point      <- CORRECT
    js       flatten, sort by DEPTH then code point                <- wrong: interleaves differently
    csharp   sort per-directory entries by NAME during traversal   <- wrong: traversal-dependent
    elixir   sort per-directory entries during traversal           <- wrong: traversal-dependent

- **A25.** The `<skill_files>` sample is: **COLLECT every candidate, SORT by the path RELATIVE to
  the skill directory in plain Unicode CODE POINT order, THEN truncate to the cap.**
  No depth rule. No per-directory sorting. No cap applied mid-traversal.
- **Why plain code point, not A15's depth rule** (golang's argument, and it is right): the depth
  rule exists to resolve duplicate NAMES in discovery — deciding WHICH file a name resolves to.
  It has no business ordering a flat file listing, and applying it interleaves
  `a-root.txt, z-root.txt, alpha/f.txt` instead of `a-root.txt, alpha/f.txt, z-root.txt`.
- **Why global, not per-directory** (python's argument): a per-directory sort is a function of the
  TRAVERSAL, so every port must reproduce the same stack discipline to agree. A global sort over
  relative paths is a function of the FILE SET and the cap alone — nothing left to reproduce.
- **SORT BEFORE CAP is the load-bearing half.** All five ports capped mid-traversal, so the
  filesystem decided WHICH files the model saw, not merely their order — different sample on a
  different machine, and on some filesystems between two runs. This is ADR-0004's K1
  "sort-before-sample parity bug", open since the memory recorded it, now closed in all ports.
- js: drop `compareDiscovery` here, use plain code point. csharp, elixir: switch from
  per-directory to a global relative-path sort. Every port pins BOTH the full order AND the
  capped prefix.
- **Fixture note from js, worth copying:** use `ß` (U+00DF), NOT an accented letter. `café` is a
  useless probe — the accent is mid-word so both rules compare at `c`. `ß` collates as `ss`
  (before `z`) but is 0xDF by code point (after every ASCII letter), AND it has no NFD
  decomposition, so macOS filename normalisation cannot silently make the test vacuous.

### A25 UPHELD against the `alpha/` vs `alpha-b.txt` case (2026-09-22)
golang moved to PER-LEVEL name ordering before A25 reached it, and surfaced the case that
genuinely separates the two rules:

    directory `alpha/` beside file `alpha-b.txt`
      flat relative path : alpha-b.txt first   ('-' 0x2D < '/' 0x2F)
      per-level name     : alpha/f.txt first   ("alpha" is a prefix of "alpha-b.txt")

**A25 STANDS: the flat global relative-path sort wins.** Not because per-level is incoherent —
it is the nicer rule for a human reading a tree — but because of what it COSTS across seven
languages. golang's own note is the argument against it: per-level ordering is only expressible
with a RECURSIVE walk, "a stack-based version has to push a continuation to get the same
interleaving", and "a port that keeps a plain LIFO stack and merely sorts each ReadDir will emit
subdirectories in reverse and look sorted while being wrong."

That is a rule whose correctness depends on every port reproducing the same traversal shape —
the exact class of coupling this batch exists to delete. The flat sort is a pure function of the
file set: no stack discipline, no continuation, nothing for six other languages to get subtly
wrong. A prefix-ordering nicety is not worth re-introducing traversal coupling into the one
artifact we just finished proving was traversal-coupled.

golang reverts to the flat sort and KEEPS the `alpha/` vs `alpha-b.txt` fixture — it is now the
regression test that pins WHICH rule is in force, which no other port had. Its directory-read
audit (three sites, all ordered, each commented with an explicit "Readdirnames is NOT sorted"
warning) stands as-is and is the model for the remaining ports.

## ADDENDUM 22 — A26: SORT-BEFORE-CAP applies to EVERY capped listing (owner-approved 2026-09-22)
js found the same defect OUTSIDE the skill loader: the `glob` BUILTIN breaks its walk at the cap
UPSTREAM of its sort, so the filesystem chose which files the model saw and the sort merely
ordered the survivors. js's own earlier audit had judged that `found.sort()` benign because it
looked at the comparator and not at what happened before it. python had ALREADY fixed the same
thing in its `glob`+`grep` builtins, with the reasoning written out in place. Nobody had told
either of them to look there.

- **A26.** A25's rule is not about skills. It governs **every capped listing of filesystem
  entries that reaches the model**: the `<skill_files>` sample, the `glob` builtin, the `grep`
  builtin, and anything else that walks a tree and truncates. In each: COLLECT, SORT by the path
  relative to the walk root in plain Unicode code point, THEN truncate. Never `break` at the cap
  mid-walk; never sort only what survived the break.
- **Every port audits its builtins**, not just its skill module. python is the reference — its
  helper is shared by `glob` and `grep` and its docstring already states the rule and the reason.
  elixir currently sorts per-directory there (A25 supersedes that). csharp's builtins did not
  match the grep at all and must be located and checked.
- **The recurring lesson, now four times over:** the bug is not the wrong comparator, it is the
  ABSENT or MIS-SEQUENCED one, and it is invisible to a search for comparators. Look at every
  site that produces a capped or ordered listing for the model, then ask what decides its
  contents — not what sorts it.

## ADDENDUM 23 — A27: what an A25 fixture must DISCRIMINATE (owner-approved 2026-09-22)
csharp's FIRST A25 fixture passed under the wrong rule and it found out only by mutating the
implementation. Its nested files were `inner-a/b.txt` — which sort the same by bare name and by
relative path, so the test asserted only that *a* sort had happened. This is the third vacuous
fixture in the batch (the `docx`-only A15 fixture; js's `café`; this), and all three were caught
by mutation, never by review.

- **A27.** An A25 sample fixture MUST discriminate all three wrong rules, or it is not a test:
  1. **bare name vs relative path** — needs a directory whose NAME orders differently from its
     CONTENTS' names. csharp's `b-nested/zz-a.txt`: by relative path it follows `alpha.txt`
     (the `b-` prefix); by bare name `zz-a.txt` sorts last.
  2. **flat vs per-level** — needs a directory beside a file sharing its prefix. golang's
     `alpha/` vs `alpha-b.txt`: flat gives `alpha-b.txt` first (`-` 0x2D < `/` 0x2F), per-level
     gives `alpha/f.txt` first.
  3. **cap-after vs cap-during** — assert the capped prefix AND that a file which sorts late is
     ABSENT under the cap. That is the content half of ADR-0004 K1.
  Plus `ß` (U+00DF) for locale-vs-code-point, per js: no NFD decomposition, so macOS filename
  normalisation cannot hollow the test out the way `café` does.
- **A27b — MUTATION IS THE ACCEPTANCE TEST.** Before reporting a fixture green, BREAK the
  implementation each way the rule could be wrong and confirm the test FAILS each time. csharp
  did it for both halves and published the table; golang did it for A15's depth rule; js did it
  for localeCompare. Every port does this for A25 and states what it mutated and what failed.
  A green fixture that has never failed is an untested assertion.

## ADDENDUM 24 — A28: listings emit `/` as the path separator (owner-approved 2026-09-22)
Raised by golang while fixing A26: its `glob` now emits `filepath.ToSlash(rel)`. No-op on
Linux/macOS; on Windows it turns `a\b.txt` into `a/b.txt`, matching what js and python emit.

- **A28.** Every capped/ordered listing that reaches the model emits relative paths with `/` as
  the separator, on every platform, and SORTS on that same string. Two reasons: the output is
  cross-port byte-identical only if the separator is; and if the sort key and the emitted string
  differ, a port is ordering by one thing and showing another — the same class of mismatch
  golang just found between WalkDir's per-directory order and its full-relative-path sort.
- Ports on POSIX-only assumptions get this for free; java, csharp and clojure's JVM host must do
  it explicitly. Worth one changelog line for anyone consuming listings on Windows.
- **This is the last scope addition.** Everything else found from here is recorded as a
  follow-up, not fixed in this batch.

### A27c — a cap fixture must beat the port's ACTUAL walk order (csharp, 2026-09-22)
csharp's first A26 fixture passed under the BROKEN glob. Its walk yielded
`mß.txt | a-dir/zz.txt | z-dir/aa.txt`, so a cap of 2 selected the same SET either way — the old
code was correct by filesystem accident, which is the exact property A26 exists to remove.

- **A27c.** Before trusting a cap-vs-sort test, PROBE the port's real walk order, then build the
  fixture so first-N-by-WALK and first-N-by-SORT are different SETS, not merely different orders.
  Minimal shape: TWO root files that fill the cap before the walk reaches a nested file which
  sorts FIRST. csharp's is `mß.txt` + `n.txt` + `a-dir/zz.txt` with cap 2.
- This is the fourth vacuous fixture in the batch, and the fourth caught by mutation rather than
  by review.

### FOLLOW-UP (not fixed here) — `<skill_files>` emits ABSOLUTE paths
csharp found the sort key is the relative `/`-path while the emitted entry is absolute
(`Path.GetFullPath`), i.e. the same sort-key-≠-emitted-string split it fixed in `grep`. It did
NOT fix it, correctly: js emits absolute there too (`js/src/skill.ts:278`), so changing one port
alone would BREAK §3 byte-identity on a listing that is already machine-specific. Needs a
seven-port decision — either all ports emit relative there, or A28 explicitly carves out the
skill sample. Recorded per the scope cut-off; not fixed in this batch.

### A27d — the SELF-PROVING fixture (python, 2026-09-22) — preferred over A27c's manual probe
python's cap test reproduces the PRE-FIX walk inside the test, computes first-N-by-WALK and
first-N-by-SORT, and asserts AS A PRECONDITION that the two are different SETS — failing with
"fixture is vacuous on this filesystem" if they ever coincide. Only then does it assert the
capped output. This beats A27c's manual probe: a probe is correct on the machine where it was
run, a self-proving fixture is correct everywhere and says so when it stops being.

Ports writing a cap fixture should prefer this shape. It is also the general answer to the four
vacuous fixtures this batch produced: make the test assert its own discriminating power.

### grep emitted an ABSOLUTE path in THREE ports (golang, csharp, python)
All three sorted on the relative path and emitted the absolute one — ordering by one thing and
displaying another, and leaking machine paths into model-visible output. All three fixed to emit
the same `/`-separated relative string they sort on.

python's distinction is the one to keep for `<skill_files>`: that listing KEEPS its absolute
path because §3 pins the shape, and there is NO mismatch there — every entry shares the skill
directory prefix, so ordering by relative path and by the emitted absolute path are the SAME
order. That reasoning belongs in the code, or someone will "fix" it into a divergence.

## ADDENDUM 25 — the MUTATION EVIDENCE LEDGER (compiled by the coordinator, 2026-09-22)
The openspec agent's closing read named only 2 mutation-proven fixtures because it reads
`tasks.md`, not the port reports. Reconciled against what the ports actually reported:

| port | mutations run, each confirmed FAILING | vacuous fixture caught |
|---|---|---|
| golang | deleted the depth comparison (A15) — failed with the xlsx message | — |
| csharp | reverted `StopLimit.Timeout` (A18); `StringComparer.Ordinal` (A22); sort-by-bare-name and cap-during (A25) | **yes** — `inner-a/b.txt` proved nothing; also its first A26 fixture passed under the broken glob |
| js | M1-M6 on builtin.ts (grep cap-during, no sort, lexical line sort, glob cap-during, no sort, absolute emit) + 3 on the skill sample | **yes** — its grep cap fixture was vacuous, caught by A27d |
| python | C1-C3 (cap-during, sorted-rendered-strings, absolute emit) + M1-M4 (bare name, cap-during, depth rule, casefold); byte-compared sources after restore | **yes** — flat-only fixture could not discriminate |
| elixir | 7 (bare name, no global sort, cap-before-sort, cap-during, glob cap-before-sort, grep absolute emit) | **yes** — hand-probe was wrong; File.ls! returns creation order here |

So **five of five finished ports** mutation-proved their ordering fixtures, and **four of five**
caught a vacuous fixture doing it. The base rate the openspec agent flagged is real — but so is
the mitigation, and it worked every time it was applied.

**Genuinely unproven and outstanding:** java and clojure (not yet reported), and the A11
17-file frontmatter table, which every port reproduces row-for-row but which no port has
mutation-tested (its non-vacuity rests on the table being compared on DESCRIPTION STRINGS rather
than verdicts — A11 — not on a mutation).

**The review gate that matters, in one line:** no port marks an ordering task done without
stating what it mutated and what failed.

## ADDENDUM 26 — clojure's three cross-port claims, adjudicated (2026-09-22)
- **`contentPart` as a tenth limit string — REAL, but it is a CLOJURE-ONLY divergence, not a hole
  in A14.** Verified: `:limit "contentPart"` appears ONLY in `clojure/src/toolnexus/client.cljc:1035`.
  js throws a `ContentPartError` instead (`js/src/content.ts:140`); no other port routes an
  oversized content part through `limit`. So A14's nine is not incomplete — clojure surfaces a
  §8A failure as a limit stop where the others raise. That is a PRE-EXISTING §8A behaviour split,
  not something this batch introduced. **FOLLOW-UP, not fixed**: it needs a seven-port ruling on
  whether an oversized part is a raise or a limit stop. clojure was right to flag and not rename.
- **js sorting `${rel}:${line}` as a string — STALE, no action.** js is correct:
  `hits.sort((a, b) => compareCodePoints(a.rel, b.rel) || a.line - b.line)` at `builtin.ts:357`
  is numeric on the line. js fixed this in the same pass clojure was reading. Checked before
  propagating, which is the point of checking.
- **Team names sorted with a bare `sort` into the `task` tool's description
  (`runtime.cljc:1517/1543`) — REAL, and A22's class one namespace over.** Model-visible §7D
  surface, and agent names are not sanitised. **FOLLOW-UP per the A28 scope cut-off**, not fixed:
  it needs the same seven-port sweep A22 got. clojure correctly did not change it unilaterally.
- **macOS folds `ss` and `ß` to one filename** — a fixture containing both silently SHRINKS
  rather than failing. Any port using ADDENDUM 23's `ß` probe beside an `ss` sibling is testing
  less than it thinks. clojure added a landed-file-count precondition; other ports should too if
  their fixture has both.
- **Escaped surrogate pairs are not portable Clojure source**, and a bare `sort` is code-point
  order on cljgo but UTF-16 code-unit order on the JVM — so "assert the default comparator
  disagrees" is a host-specific claim a dual-host suite cannot make. Recorded so nobody demands
  that assertion of clojure.

## ADDENDUM 27 — A29: `ß` and an astral char test DIFFERENT rules (clojure's correction, 2026-09-22)
My A27 guidance conflated two probes. clojure caught it when its first fixture PASSED a
bare-`sort` mutation:

- **`ß` (U+00DF) discriminates LOCALE vs code point.** It collates as `ss` (before `z`) but is
  0xDF by code point (after every ASCII letter). It is BMP, so it does NOT separate code-point
  order from UTF-16 code-UNIT order — both rank it identically.
- **A BMP char above U+E000 vs an ASTRAL char discriminates CODE POINT vs CODE UNIT.**
  U+FFFD vs U+1D51E (golang), or U+FB00 vs U+1F600 (csharp). A surrogate pair's lead unit
  (0xD800-0xDBFF) sorts BELOW U+E000-U+FFFF, so the two rules disagree only here.
- **A29.** A fixture needs BOTH probes to defend both rules. `ß` alone leaves a bare `sort`
  undetected; an astral pair alone leaves `localeCompare` undetected. golang and csharp have
  both; other ports should check theirs.
- clojure adds a third caution: **"a bare sort disagrees" is not an assertion a dual-host suite
  can make** — true on the JVM (UTF-16) and false on cljgo (UTF-8 bytes = code point). Pin the
  ORDER itself, never the comparator's disagreement.
- And a fixture hazard: **macOS folds `ss` and `ß` to one filename**, so a fixture containing
  both silently SHRINKS rather than failing. Assert the landed file count as a precondition.

### clojure's js-grep claim — RE-CHECKED, still STALE
clojure repeated it in its final report. Verified again at `js/src/builtin.ts:357`:
`hits.sort((a, b) => compareCodePoints(a.rel, b.rel) || a.line - b.line)` — numeric on line.
js is correct; the claim reads an earlier state. No action.
