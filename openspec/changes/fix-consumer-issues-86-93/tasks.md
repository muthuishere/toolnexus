Every behaviour change carries a seven-port parity checklist. A port left unchecked is a port
left behind, and it is named in `CHANGELOG.md` rather than left silent. Ports:
**js · python · golang · java · csharp · elixir · clojure**.

Binding decisions: `DECISIONS.md` in this folder, **including its ADDENDUM A1–A7** (seven
otherwise-unspecifiable points, closed 2026-09-21). Evidence: `docs/adr/0023`–`0028`,
`spikes/issues/{86,87,88-90,89,91-92,93}`. Tests are hermetic — mock LLM, no network, no key.

## 0. Pin the contract first

- [ ] 0.1 `SPEC.md §0.10` — the toolkit is optional; its absence is a completion; the system
      message is the system prompt alone; the request carries no `tools` and no `tool_choice` key.
- [ ] 0.2 `SPEC.md §7D` — the Loop applies the Spec; a caller-supplied system prompt wins over the
      soul, in one sentence so a port cannot satisfy the prose and disagree; **(A8)** the Spec's
      model applies on absence OR the `"inherit"` sentinel.
- [ ] 0.3 `SPEC.md §7D` / `§8` — the two status vocabularies documented side by side as two
      distinct closed sets that happen to share a field name, and **(A7)** a note that the
      collision's invariant is not yet gated.
- [ ] 0.4 `SPEC.md §10` — the durable-resume payload keys **(A3: `results`, then `output` with
      `isError`)** and their precedence named in the contract; the "`golang` only" / "Every port provides:" contradiction at `:1966`/`:2020`
      resolved; Clojure added to the not-implemented list at `:1969`.
- [ ] 0.5 `SPEC.md §10` / `§7D` — what a resume rewinds, stated as plainly as what it grows;
      reattachment scoped to delegation calls.
- [ ] 0.6 `SPEC.md §3` — YAML-first with a guarded line-wise rescue and its three trigger
      conditions **(A10)**; discovery order specified **(A1)** with its code-point tie-break and
      symlink rule **(A1a)**; **(A1d)** one line stating WHICH order wins, so it is decided in the
      contract rather than re-derived per port — **docs owner's task, not a port's**; skips returned as data **(A2)**;
      compatibility defined against the writing tool and pinned by fixtures.
- [ ] 0.7 `SPEC.md` credentials guarantee extended to cover error messages and credential-adjacent
      data.
- [ ] 0.8 `openspec validate fix-consumer-issues-86-93 --strict` green.

## D1 — toolkit-less completion (#86, ADR 0023)

- [ ] D1.1 Null-guard the §0.10 system-message site so it never dereferences the toolkit.
- [ ] D1.2 Add the idiomatic toolkit-less call shape.
- [ ] D1.3 Test: the shortest `ask` form with no toolkit returns text.
- [ ] D1.4 Test (wire, **every port, including the two that already work**): a toolkit-less request
      body has no `tools` key and no `tool_choice` key — not an empty array.
- [ ] D1.5 Test: no toolkit and a `builtins:false` toolkit produce byte-identical request bytes.
- [ ] D1.6 No port adds a public empty-toolkit constructor.

Parity — shape per port, from DECISIONS D1:

- [ ] js — `ctx?` and `ctx.toolkit?` optional; guard the deref
- [ ] python — `toolkit: Toolkit | None = None` on `run`/`ask`/`stream`; guard the deref
- [ ] golang — already works; **tests only** (D1.3–D1.5)
- [ ] java — overloads for exactly three shapes (prompt / prompt+id / prompt+onText) plus guards
- [ ] csharp — `Toolkit?` nullable, **not** overloads
- [ ] elixir — default args giving `run/2`, `ask/2`, `stream/2`
- [ ] clojure — already works; tests + a docstring clause

## D2 — the Loop honours the Spec (#87, ADR 0024)

- [ ] D2.1 golang: `clientFor` applies `Spec.Soul` and `guardedHooks(spec)`.
- [ ] D2.2 js: align soul precedence to **caller-wins** (`soul ?? options.systemPrompt` is the
      wrong way round); python and csharp are already correct and gain the test.
- [ ] D2.3 `Spec.Model` and `Spec.Budget.MaxTurns` become Loop defaults, caller overrides winning.
- [ ] D2.3a **(A8)** `Spec.Model` applies when the caller's model is **absent OR equal to the
      sentinel `"inherit"`** — both spellings, in every port, including ports whose model field is
      optional and could express absence alone. Tests cover all three cases: absent, `"inherit"`,
      and a real caller model that still wins.
- [ ] D2.4 Additive `loopUnsupported(spec) -> [field names]` for `Tools`/`Team`/`WaitFor`/
      `OnMetric`. **No signature change, no construction-time error.**
- [ ] D2.4a **(A6)** The returned names are the closed vocabulary `"tools"`, `"team"`,
      `"waitFor"`, `"onMetric"` — the same strings in all seven ports, like the limit strings,
      never the language's own spelling. Test compares the returned names across ports.
- [ ] D2.5 Docs + the ADR 0024 table name the limitation.
- [ ] D2.6 Regression test asserting **the denied tool's executor is never entered** on the Loop
      path — never the model's text.
- [ ] D2.7 Test: a Spec declaring only honourable fields yields an empty `loopUnsupported` and a
      byte-identical Loop.

Parity:

- [ ] js (D2.2 is the behaviour change here) · [ ] python · [ ] golang (D2.1 is the defect)
- [ ] java · [ ] csharp · [ ] elixir · [ ] clojure

## D3 — runtime legibility (#88/#90, ADR 0025)

- [ ] D3.1 `TaskResult.TotalTokens` = cumulative subtree total on **every** status. Do **not**
      "fix" the 14 doc sites — they were right.
- [ ] D3.1a **(A13 as CORRECTED by A13a — verified in source)** `TaskResult.Turns` = that
      handle's **OWN** cumulative round trips, reported identically on **every** status. The
      defect is only that the field meant one thing on three statuses and another on the other
      three. js is already correct; **golang** (per-run on done/pending/incomplete, cumulative on
      error/closed/timeout), **csharp**, and any port that mirrored D3 literally are the likely
      misses. Every port re-checks this one line before reporting done.
- [ ] D3.1a1 **(A13a)** **Do NOT roll turns up the ancestor chain.** `rollupLocked` walks
      ancestors for TOKENS and tool calls only; `turnsTotal` is a per-handle accumulator in every
      port. A roll-up would be NEW BEHAVIOUR, not a parity fix. A13's original "cumulative tree
      total, exactly like TotalTokens" wording was wrong and has been withdrawn — if a port
      implemented a roll-up on the strength of it, revert that.
- [ ] D3.1b **(A13, reason corrected by A13b)** **No `OwnTurns`** — with no roll-up, `Turns`
      already IS the own-figure, so a second field would carry the same number twice.
- [ ] D3.1c **(A13a)** Test asserting `Turns` is the same figure across statuses — the existing
      token scenarios did not catch this, which is why it drifted.
- [ ] D3.2 Add `OwnTokens` (a new per-handle counter, incremented outside the rollup's ancestor
      walk).
- [ ] D3.3 Add `Limit` to `TaskResult` in the six ports lacking it, from `RunResult.Limit` **and**
      set for budget stops.
- [ ] D3.3a **(A14 — CLOSED vocabulary; four ports already disagree)** `limit` names the **Budget
      field that stopped the run**, spelled as `SPEC.md` spells it, from this closed set,
      identical in all seven ports, like the `loopUnsupported` strings:
      `maxTurns` · `maxTokens` · `maxToolCalls` · `maxWallMs` · `maxChildren` · `maxConcurrent` ·
      `maxDepth` · `completion` · `timeout` (empty when no limit stopped the run).
      A non-portable value defeats the field's only purpose — this is **#90's own complaint
      re-created inside its own fix**.
- [ ] D3.3b **(A14)** Each port MAPS its internal pool/dimension name at the boundary; an internal
      spelling MUST NOT leak. Named misses, verified:
      - [ ] golang — `maxWall` → `maxWallMs` (already maps `tokens`/`toolCalls`/`wallMs` at the
            boundary without renaming internals or touching `Text` — the cheap pattern to copy)
      - [ ] csharp — `tokens` → `maxTokens`, `wallMs` → `maxWallMs`
      - [ ] python — `tokens` → `maxTokens`, and every other dimension
      - [ ] js — verify the **full set**, not only the two it emits
      - [ ] java · [ ] elixir · [ ] clojure — verify against the closed set
- [ ] D3.3c **(A14)** Test per port: a budget stop reports the canonical string (wall-clock stop ⇒
      exactly `maxWallMs`), and no port emits a spelling outside the set.
- [ ] D3.3d **(A17 — admission refusals are NOT in scope)** `maxChildren`, `maxConcurrent` and
      `maxDepth` are SPAWN/ADMISSION refusals that surface as a **verb error** in js, csharp,
      elixir and python and never settle a `TaskResult`. This batch does **not** change where they
      surface; the strings stay pinned **wherever a port does report such a stop**, and a port
      that never reports one never emits them. **No port invents a settle path to make the string
      appear.**
- [ ] D3.3e **(A17)** Record as a **follow-up, not solved here**: whether an admission refusal
      should be a verb error or a settled `TaskResult` is a real cross-port asymmetry, and it
      lives in the **verb's RETURN TYPE**, not the limit vocabulary — fixing it changes a
      signature. Name it in the CHANGELOG's "does NOT do" list.

### D3-A18 — status and limit must never contradict (the invariant, not the instance)

- [ ] A18.1 The bug: a settle that sets a status without its limit (`status "timeout"`, `limit`
      empty) — the two fields contradicting each other **inside the very feature #90 asked for**.
      Present in **three of five** finished ports: js and elixir each found it in their own code,
      a sweep caught golang and python, csharp was correct only by accident (it happened to pass
      its timeout constant explicitly).
- [ ] A18.2 **Every port ships an INVARIANT test**, not only the one-site fix: *a limit stop MUST
      name its limit; a non-limit stop MUST leave it empty.* Drive it over at least `done`,
      budget-`incomplete`, `closed` and wait-deadline `timeout`, asserting each value is a member
      of its own closed vocabulary (7-value status set, 9-value limit set).
- [ ] A18.3 **Audit every result-construction site and report the table.** An instance test does
      not find latent sites; audits already turned up ones no reported bug touched — **golang 1**
      (a branch forwarding `r.Limit` straight through would reproduce the contradiction for any
      `incomplete` arriving with an empty limit), **csharp 2**, **js 1** (closed by type).
      - [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure
- [ ] A18.4 **Named constants at every construction site**, never string literals, so a rename
      cannot silently desync status from limit.
- [ ] A18.5 Prefer closing the site **BY CONSTRUCTION** where the language allows — js's
      `poolLimit`/`turnCap` now return `TaskLimit | undefined`, so the compiler, not a mapper,
      guarantees no internal pool name reaches the field. **A type beats a test.**
- [ ] A18.6 **Test-shape warning (from golang):** `Wait(handle, 0)` after a close returns the
      handle's **settled last result** (e.g. the earlier budget stop), not a fresh `closed`
      result. That is correct behaviour — drive the closed branch explicitly, or the test asserts
      something other than what it claims.

### D3-A19/A20 — what is public, what is not, and how that is checked

- [ ] A19a.1 **Both vocabularies are PUBLIC API in all seven ports.** A host must branch on
      `limit` without hard-coding strings — that is the entire point of D3 and A14. Ports that
      kept them module-private export them. Naming stays port-local (ADDENDUM 5): the VALUES
      conform, the holder's name does not.
      - [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure
- [ ] A19b.1 **`limitInvariant` (or equivalent) stays TEST-ONLY and is exported NOWHERE**,
      including js, which exported one and demotes it. A predicate that exists so our own tests
      can assert a rule should not oblige every future port to implement it (ADR 0019 already
      rejected adding surface for what tests can hold). **Copy the predicate, not the export.**
- [ ] A19b.2 The internal **pool-name MAPPER stays private in every port** — exporting it would
      leak exactly the internal names A14 exists to exclude.
- [ ] A20.1 **"The suite compiles/passes" is NOT evidence of public visibility.** csharp has
      `InternalsVisibleTo(Toolnexus.Tests)`; golang and java see unexported/package-private
      members from same-package tests; python/elixir/clojure have convention, not enforcement. A
      constant demoted to internal would keep the suite green while breaking every real consumer.
- [ ] A20.2 **Verify A19a from OUTSIDE the module boundary**: reflection (csharp `Type.IsPublic` +
      `BindingFlags.Public|Static`), an out-of-package test, or — where the language encodes
      visibility in the NAME (go: capitalised, exported by spelling) — the spelling itself.
      - [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure
- [ ] A20.3 Assert every value in the enumerable set is **also reachable as a named public
      constant**, so a host writes `StopLimit.MaxWallMs`, not `"maxWallMs"`.
- [ ] A20.4 Copy csharp's guard where cheap: **no public boolean-returning member on the limit
      vocabulary**, so a future "just expose the check" cannot quietly undo A19b.
- [ ] D3.4 `Runtime.Resume` returns `(TaskResult, error)` / the port's result-returning
      equivalent. **(A4)** The value is the result of the **topmost handle the cascade re-ran**.
- [ ] D3.5 java: stop replaying the literal `"continue"` — restore the real prompt and the drained
      inbox. **§0 break; fix independently of the rest of D3.**
- [ ] D3.6 csharp + clojure: stop hardcoding the maxTurns wording; read and report the real limit.
- [ ] D3.7 elixir: `Limit` already present — align spelling only, and join the budget-stop half.
- [ ] D3.8 Test in every suite: `parent.TotalTokens >= child.TotalTokens` after one delegation.
      **(A13b)** `parent.Turns >= child.Turns` may be asserted ONLY inside this specific fixture,
      where the parent spends one turn delegating and one answering — it is **NOT a general
      guarantee** and MUST NOT be spec'd as one: a parent can delegate in one turn to a child
      that takes five. True for tokens (they roll up), false for turns.
- [ ] D3.9 Test: a completion-gate stop reports `limit: "completion"`, not a turn cap.
- [ ] D3.10 Docs + every `WaitFor` doc comment and the `Pending` helper state the idempotency
      contract at the point of use; the four "continues from its checkpoint" passages corrected.
- [ ] D3.11 CHANGELOG names the `TotalTokens` **and `Turns`** behaviour changes with before/after
      figures, and names the Go `Resume` compilation break.

Parity — D3.1/D3.2/D3.3/D3.4/D3.8/D3.10 in all seven:

- [ ] js · [ ] python · [ ] golang · [ ] java (+D3.5) · [ ] csharp (+D3.6) · [ ] elixir (D3.7)
- [ ] clojure (+D3.6)

## D4 — the Answer payload contract (#89, ADR 0026)

- [ ] D4.1 On `ok == true` with no determinable result for an outstanding call: **error to the
      host**. No fabricated result to the model, no `status: "done"`.
- [ ] D4.1a **(A3)** Recognised keys are `results`, then `output` (+`isError`), in that
      precedence, identical in all seven ports. Pinned by a **test**, not only by SPEC prose:
      a payload carrying both resolves through `results`.
- [ ] D4.2 Keep the fabricated filler only for a genuine **partial relay** answer — **(A3)**
      defined as *at least one recognised key present* while some outstanding call is unresolved.
      A payload with no recognised key never gets the filler; it errors.
- [ ] D4.3 `AnswerOutput(id, output)` constructor in all seven ports.
- [ ] D4.3a **(A9, confirmed canonical by ADDENDUM 5)** `AnswerDeclined(id, reason)` **adopted in
      all seven** alongside it — the natural pair, since a human who says no is not an error.
      Same canonical shape. js shipping it first was not drift; the other six follow.
- [ ] D4.4 Fix the failure-silent type assertions: a non-string `output` errors, never degrades
      to `""`.
- [ ] D4.5 elixir: `Answer` accepts string keys (atom-only raises on a JSON round-trip).
- [ ] D4.6 clojure: `Answer` accepts string keys (keyword-only silently reads as **declined**).
- [ ] D4.7 Test: a serialized-and-reloaded string-keyed answer resolves identically to a native one.
- [ ] D4.8 Docs: replace the broken seven-tab durable-resume example
      (`site/.../suspension.mdx:300-341`) — the flow it shows never reaches the resume entry point
      in any port. Show the durable entry point only for the port that has one; show the inline
      `waitFor` posture for the rest.
- [ ] D4.9 Kind-aware defaulting is **rejected** — no port implements it.

Parity — D4.3, D4.4, D4.7 in all seven; D4.1/D4.2 wherever the durable path exists:

- [ ] js · [ ] python · [ ] golang (D4.1/D4.2/D4.4 land here) · [ ] java · [ ] csharp
- [ ] elixir (+D4.5) · [ ] clojure (+D4.6)

## D5 — what we hand back when we fail (#91/#92, ADR 0027)

### D5a — classifier backend pairing

- [ ] D5a.1 Keep `jev-latest` as the default model. It serves; do not re-point it.
- [ ] D5a.2 Add a `backend` preset (`typesafe` | `openrouter`) setting baseUrl + model + apiKeyEnv
      **as a unit**; explicit options still override individually.
- [ ] D5a.3 Fail at construction on the known mismatch, with the message naming the gateway's own
      spelling.
- [ ] D5a.4 `backends.mdx` table reads as two configurations, not six choosable cells.
- [ ] Parity: [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure

### D5b — the two status vocabularies

- [ ] D5b.1 Named constants for **both** sets in every port. **Do not rename either public field.**
- [ ] D5b.1a **(ADDENDUM 5 — accepted deviation, do NOT "fix" into parity)** csharp names the
      seven-value vocabulary `AgentStatus`, not `TaskStatus`, because
      `System.Threading.Tasks.TaskStatus` is in scope in every file there. The **values**
      conform; the type name is port-local. No other port renames on account of it.
- [ ] D5b.2 Document them as two distinct vocabularies in `SPEC.md §8` / `§7D` (task 0.3).
- [ ] D5b.3 Test: no client run result in any port ever carries `"timeout"`.
- [ ] D5b.4 **(A7)** Named constants pin the VALUES; nothing here pins the invariant that a third
      closed vocabulary cannot land on a field named `status`. **Not solved by this change** —
      recorded in `design.md` as a follow-up conformance row and named in the CHANGELOG. Do not
      write a requirement implying it is held.
- [ ] Parity: [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure

### D5c — timeouts

- [ ] D5c.1 golang: stop returning a zero-value result beside a non-nil error. Return
      `Status: "incomplete"`, `Limit: "timeout"` — invents no new §8 status — and preserve the
      Turns/Usage accumulated before the deadline.
- [ ] D5c.2 golang: message becomes `run timeout after <n>ms`, distinguishable from cancellation.
- [ ] D5c.3 elixir: replace the bare `RuntimeError` with a typed timeout error.
- [ ] D5c.4 clojure: add the **run-level** deadline (today `:timeout-ms` bounds one HTTP call), and
      stop retrying a timeout. Unreported §8 break.
- [ ] D5c.5 The other ports keep throwing, but every message names the budget.
- [ ] Parity: [ ] js · [ ] python · [ ] golang (D5c.1/2) · [ ] java · [ ] csharp
      · [ ] elixir (D5c.3) · [ ] clojure (D5c.4)

### D5d — error bodies

- [ ] D5d.1 Typed error carrying `status` / `body` / `retryAfter` in every port.
- [ ] D5d.2 Redact `user_id`, `account_id`, `org_id`, `organization` to `«redacted»` — one shared
      key list, SPEC-pinned. **(A5)** Redaction applies to **BOTH** the typed `body` field and the
      message; the unredacted body is reachable through neither.
- [ ] D5d.3 Lift the classifier's 200-char cap + 401/403-blanking onto the §8 client path.
      **(A5)** The cap is **MESSAGE-ONLY** — the typed `body` field carries the full redacted
      body, because a host that reached for the typed error asked for the whole thing.
- [ ] D5d.4 Test proving **a cap is not redaction**: a 96-byte body carrying `user_id` survives the
      cap untouched and is caught by the key redaction. Both assertions, independently.
- [ ] D5d.4a **(A5)** Test the two scopes apart: a >200-char body is truncated in the message and
      **whole** on the typed field, and redacted in both.
- [ ] D5d.5 CHANGELOG: the typed error breaks anyone matching on message text.
- [ ] Parity: [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure

### D5e — must not regress (assert, do not assume)

- [ ] D5e.1 Per port: a 4xx costs exactly **one** attempt under a retry budget of four, via the
      enumerated retryable set `{429,500,502,503,504,529}` — never "any 5xx".
- [ ] D5e.2 Per port: `ClassifierUsage.Cost` stays an optional where absent ≠ zero (ADR 0022).
- [ ] Parity: [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure

## D6 — a skill the writing tool accepts (#93, ADR 0028)

- [ ] D6.1 Lenient frontmatter, **inverted**: strict YAML first; the line-wise
      `key: rest-of-line` read runs **only** on frontmatter YAML has already refused.
      Line-wise-first is forbidden.
- [ ] D6.2 Guards: column 0 only; first-wins; refuse any value opening `| > & * [ { !`.
- [ ] D6.2a **(A10, as narrowed by A16)** The rescue triggers when the YAML parse **throws**, OR
      yields a **non-mapping**, OR yields a mapping whose `name`/`description` is present but
      **not a SCALAR** — because ports' libraries disagree on `description: [unterminated` (some
      throw, some recover it into a sequence) and both paths must reach the same observable
      outcome. Deciding invariant: a file never gains an **invented** description and never
      silently keeps a **structurally wrong** one.
- [ ] D6.2a1 **(A16 — narrower than A10's wording; the reference wins)** "Not a string" means
      **"not a scalar"**. Arbiter: `spikes/issues/93/prototype/lenient.py:69-70`, which coerces
      `str/int/float/bool` (and `None`) to their string form and excludes **only mappings and
      sequences**. So `name: 123` → `"123"` and `description: true` → `"true"`, and neither
      triggers the rescue. Reading A10 literally would have broken parity **in the other
      direction**, against behaviour existing cross-port tests already pin. elixir flagged the
      ambiguity rather than guessing — if a port implemented the literal reading, revert it.
- [ ] D6.2a2 **(A16)** Test: a non-string scalar coerces and loads; only a mapping or sequence
      value is structurally wrong.
- [ ] D6.3 Skip records gain `detail` carrying the native parser error. `reason` stays
      byte-identical and is the only field parity compares.
- [ ] D6.4 **(A2)** `LoadSkills` returns its skips as **DATA on the result** — not a hook. A hook
      shape differs per port and cannot be a parity gate; the returned data is what conformance
      compares. A port that already owns a warn slot MAY also call it (check that slot against
      ADR 0014), but MUST NOT rely on it in place of the returned data.
- [ ] D6.5 clojure: replace the hand-rolled `frontmatter.cljc` with a real YAML parse across both
      `.cljc` hosts. It loads 33 of 87 where the others load 69 — the highest-value fix in the
      batch, and a prerequisite for D6.1 in that port, not a follow-up.
- [ ] D6.6 §3 specifies **discovery order**, not just first-wins: `docx`/`pdf`/`pptx` currently
      resolve to different files per port. **(A1, CORRECTED by A15)** The order is: directories in
      the order the **caller** passed them; within a directory, **DEPTH (segment count) ASCENDING,
      THEN Unicode code point** over the path relative to that directory's logical base;
      first-wins.
- [ ] D6.6-A15 **(A15 — corrects A1a; every port re-sorts and re-tests, INCLUDING the four already
      reporting done)** Depth is compared **before** code point; A1a/A1c become the tie-break
      **within** a depth. Pure code-point sorting does **not** deliver "a top-level skill beats a
      nested copy": `docx`/`pdf`/`pptx` begin with letters before `s` so they beat `synced/…`,
      but `xlsx` begins with `x` so it **loses** to `synced/<uuid>/xlsx`. The winner depended on
      the skill's first letter relative to a sibling directory's name. Found by the external
      consumer on the real corpus; six ports and a spec review all missed it. Symlinks still sort
      at their **discovered** path — resolution order is not the bug.
- [ ] D6.6-A15a **(A15 — the fixture is the whole point)** The duplicate fixture MUST carry a name
      that sorts **AFTER** the nested directory's first segment — `xlsx` vs `synced/`. **A
      `docx`-only fixture passes under BOTH rules and therefore proves nothing**; that is exactly
      why this survived six ports. Assert both directions: `xlsx` (sorts after) and `docx` (sorts
      before) must both resolve to the top-level copy.
- [ ] D6.6a **(A1)** Every port sorts explicitly (Node's `readdirSync` is already sorted; the
      others are not). Test: an arbitrary listing order does not change the winner, and reversing
      the caller's directory list reverses it.
- [ ] D6.6b **(A1a)** The tie-break is a **Unicode code-point** comparison of the whole path
      relative to the root's logical base: no locale collation, no case folding, no
      segment-aware comparison. Symlinks sort at their **discovered** path, never at their
      target's.
- [ ] D6.6b1 **(A1c — corrects the earlier java instruction)** Ordinal is NOT sufficient on a
      UTF-16 host: the platform default is code-UNIT order, which disagrees with code-point order
      above U+FFFF. Per port:
      - [ ] csharp — `string.CompareOrdinal` is code-unit order; **add a code-point comparer**
      - [ ] java — `String.compareTo` is code-unit order; use `String.codePoints()` /
            `Character.codePointAt` comparison
      - [ ] clojure — JVM host as java; JS host a code-point compare, never `localeCompare`;
            **both hosts must agree**
      - [ ] js — already correct: `compareCodePoints`, never `<` on UTF-16 units, never
            `localeCompare`
      - [ ] golang · [ ] python · [ ] elixir — iterate runes/code points natively; still sort
            explicitly
- [ ] D6.6b2 **(A1c)** Test with an astral-plane path (e.g. a filename containing U+1F600)
      against a U+E000..U+FFFF sibling: the winner matches code-point order and does not vary by
      host. ASCII/BMP corpora are unchanged either way, so this fixture is the only thing that
      catches a code-unit comparator.
- [ ] D6.6c **(A1b — behaviour change)** A shared fixture carrying both `docx/SKILL.md` and
      `synced/<hash>/docx/SKILL.md` pins the winner as the shallower path, in all seven ports.
      The winner **FLIPS in js, golang and elixir** (python/java/csharp's winner becomes the
      contract): same skill name, different file, different `content`. Nothing else in this
      change would catch a regression here.
- [ ] D6.6d **(A1b)** CHANGELOG names the flip as a user-visible behaviour change in those three
      ports, not a refactor.
- [ ] D6.7 **(A10)** `spikes/issues/93/fixtures/` is the named **arbiter**: it lands as a shared
      fixture set and every port reproduces the pinned table over it in CI. This is the only
      thing that catches a YAML-library divergence no port would notice alone — run it per port
      before calling D6 done.
- [ ] D6.7a **(A11/A12 — the table is PINNED)** csharp and the `lenient.py` prototype agree
      exactly over the 17 fixtures: **14 ok / 3 skip**, the full table in `DECISIONS.md`
      ADDENDUM 6. That agreement is the reference.
- [ ] D6.7b **(A11)** Conformance asserts the **description STRING** and the **typed skip
      REASON**, never just the ok/skip verdict. `hash-inline` is the row most likely to diverge
      and is decided by the YAML library's comment handling, not by the rescue: expected
      description is exactly `Tag things with`, because ` #` opens a YAML comment. A port whose
      parser keeps the `#…` tail reports **ok** with a different description — a verdict-only
      check would call that conformant. Details stay native and are not compared.
- [ ] D6.7c **(A12)** `broken-flow` must yield **name-without-description, NOT a skip**. Throwing
      libraries and recovering-into-a-sequence libraries converge on that row only through A10's
      non-string guard — **if this row skips, A10 is not implemented.** Treat it as the D6
      acceptance check, per port.
- [ ] D6.7d **(A11 — the arbiter itself has never been mutation-tested)** Every port reproduces
      the 17-file table row for row, which shows the ports **agree**; it does not show the table
      can tell a wrong implementation from a right one. Its non-vacuity rests on the *argument*
      that it compares description STRINGS rather than verdicts — and this batch produced five
      fixtures whose arguments were fine and whose assertions were empty. **Mutate it: keep the
      `#…` tail on `hash-inline` (so the description becomes `Tag things with #stockloop`) and
      confirm the table FAILS.** A verdict-only comparison passes that mutation; a
      string-comparing one cannot. Two minutes of work, and the difference between the arbiter
      being trusted and being assumed.
      - [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure
- [ ] D6.8 Measured targets, per port: `~/.claude/skills` 81 → 87 parsed with 0
      malformed-frontmatter; block scalars byte-identical; no invented descriptions; `broken-flow`
      still refused; `~/.agents/skills` 36/36 unchanged.
- [ ] Parity: [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir
      · [ ] clojure (D6.5 is the whole cost here)

## D6-ORDERING — every place this codebase orders user- or model-visible data (A21–A28)

The recurring lesson, now five times over: **the bug is rarely a wrong comparator — it is an
absent or mis-sequenced one, and neither is visible to a search for comparators.** Look at every
site that produces an ordered or capped listing for the model, then ask what decides its
*contents*, not what sorts them.

### A21 — prefer a structural guarantee (ranking, do NOT harmonise downward)

- [ ] A21.1 Three shapes of the A18 fix exist and are **ranked, not equivalent**:
      1. **Structural chokepoint (BEST)** — elixir routes every result through one `task_result/1`
         normaliser (limit stops canonicalised, non-limit stops explicitly emptied); js does it by
         **type** (`poolLimit`/`turnCap` return `TaskLimit | undefined`, `TaskResult.limit` typed).
         A fourteenth construction site cannot reintroduce the contradiction, including by
         forwarding. **A type or a chokepoint constrains code not yet written.**
      2. **Per-site fix + invariant test (ACCEPTABLE)** — golang, csharp, python. Catches a
         regression after the fact; does not prevent one.
      3. **Per-site fix + instance test (INSUFFICIENT)** — what the reported bug alone would have
         bought; would have missed all four latent instances.
- [ ] A21.2 **java and clojure take shape 1** where the language allows. Ports already at shape 2
      are **NOT reopened** — this is internal structure, not observable behaviour, and parity is
      defined on behaviour. Recorded so a later reader does not harmonise the three into the
      weakest.

### A22 — the §0.10 skills prompt (a SECOND, separately implemented ordering)

- [ ] A22.1 Skills-prompt order = **Unicode code point over the skill NAME**, all seven ports.
      Named misses: **js `localeCompare`** (locale-dependent — different output on a different
      machine's ICU data, in a prompt §0.10 pins byte-identical); **csharp `StringComparer.Ordinal`**
      (UTF-16 code unit, diverges above U+FFFF → use its existing `CompareCodePoints`).
      golang/python/elixir already code-point; **java and clojure verify**.
- [ ] A22.2 The `skill` tool's not-found "available skills" list takes the **same rule** — both
      sites, all seven ports.
- [ ] A22.3 **Every port greps its own skill module for every sort/compare** and reports what each
      one orders and by what rule. A1c was fixed where a defect had been *reported*; nobody asked
      where else this codebase sorts user-visible data, and the answer was one function away in
      the more important place.
      - [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure

### A23 — where the sweep STOPS (scope decided, not drifting)

- [ ] A23.1 **In scope, fixed:** the §0.10 skills prompt and the `skill` tool's not-found list.
- [ ] A23.2 **Deferred, recorded, NOT touched:** the classifier's `canonicalJson`/`canonicalRequest`
      key ordering (js UTF-16 code unit vs golang byte-wise UTF-8 — identical for every ASCII and
      BMP key, so no observable divergence today, but it IS a cross-port byte-identity path).
      Needs a **coordinated seven-port ruling**; changing one port alone would CREATE the drift.
- [ ] A23.3 **Out of scope:** Prometheus label ordering, `unknown agent (known: …)` and `task`
      team-list error texts, and the unmatched-skill-filter warnings (deterministic set,
      non-deterministic order, stderr). Caveat to carry into that follow-up: if any port has made
      those warnings a **returned value** rather than stderr, ordering becomes comparable data and
      needs the code-point rule.
- [ ] A23.4 **The rule this establishes:** a sort is in scope when SPEC pins its output
      byte-identical, or when it is **locale-dependent** (unstable across machines, a defect in any
      port). Otherwise it is a follow-up. Expanding further is scope creep dressed as thoroughness.

### A24 — audit directory READS, not comparators

- [ ] A24.1 Every port greps for **directory reads** over shipped output — `readdir`, `ls`,
      `File.ls`, `Files.list`, `EnumerateFileSystemEntries`, `os.listdir`, `os.walk`,
      `filepath.WalkDir` — and confirms each one feeding user- or model-visible output is
      **explicitly sorted**. An audit for `sort`/`OrderBy`/`compare` **cannot see this class**:
      csharp's `SampleSiblingFiles` had no ordering at all and its own A22 audit missed it.
      - [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure
- [ ] A24.2 It is a **content** bug: the sample walk stops at the cap, so an unsorted read changes
      WHICH files land in `<skill_files>`, not just their order.
- [ ] A24.3 Route discovery's directory reads through the same sorted helper. A15 re-sorts
      candidates explicitly so it is not load-bearing today, but an unsorted read there is one
      refactor away from mattering and costs nothing to fix.
- [ ] A24.4 golang's audit is the model: three sites, all ordered, each commented with an explicit
      "Readdirnames is NOT sorted" warning.

### A25 — ONE rule for the `<skill_files>` sample

- [ ] A25.1 **COLLECT → SORT by path relative to the skill dir in PLAIN code point → TRUNCATE.**
      No depth rule, no per-directory sorting, no cap mid-traversal. Five ports fixed A24 **five
      different ways** before this was arbitrated — a byte-identity break introduced *by* the fix
      for a byte-identity break. golang and python were correct and flagged the divergence rather
      than assuming; js used depth-then-code-point, csharp and elixir sorted per-directory.
- [ ] A25.2 **Not A15's depth rule**: depth exists to resolve duplicate *names* in discovery and
      has no business ordering a flat listing — it interleaves `a-root, z-root, alpha/f` instead of
      `a-root, alpha/f, z-root`.
- [ ] A25.3 **Not per-directory**: that makes the order a function of the TRAVERSAL, so every port
      must reproduce the same stack discipline. Per-level ordering is only expressible with a
      recursive walk — a stack-based port must push a continuation, and one that merely sorts each
      read emits subdirectories in reverse **while looking sorted**. A global flat sort is a pure
      function of the file set and the cap.
- [ ] A25.4 **SORT BEFORE CAP is the load-bearing half** — this closes ADR-0004 K1
      ("sort-before-sample parity bug"), open since the memory recorded it.
- [ ] A25.5 Per port: js drop `compareDiscovery` here; csharp and elixir switch per-directory →
      global relative-path sort; golang reverts its per-level move and **keeps** the
      `alpha/` vs `alpha-b.txt` fixture as the regression test pinning which rule is in force.
      - [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure
- [ ] A25.6 Every port pins **BOTH** the full order AND the capped prefix.

### A26 — sort-before-cap governs EVERY capped listing

- [ ] A26.1 A25's rule is not about skills: it governs the `<skill_files>` sample, the **`glob`**
      builtin, the **`grep`** builtin, and anything else that walks a tree and truncates. COLLECT,
      SORT by path relative to the walk root in plain code point, THEN truncate. Never `break` at
      the cap mid-walk; never sort only what survived the break.
- [ ] A26.2 **Every port audits its builtins**, not only its skill module. All five ports that
      looked had at least one defective; **golang's and csharp's `grep` both capped mid-walk AND
      never sorted at all**. python is the reference — one helper shared by `glob` and `grep`, with
      the rule and its reason in the docstring. elixir sorts per-directory there (A25 supersedes).
      - [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure

### A27 — a fixture that does not discriminate is not a test

- [ ] A27.1 An A25 sample fixture MUST discriminate **all three** wrong rules:
      1. **bare name vs relative path** — a directory whose NAME orders differently from its
         contents' names (csharp's `b-nested/zz-a.txt`: by relative path it follows `alpha.txt`;
         by bare name `zz-a.txt` sorts last)
      2. **flat vs per-level** — a directory beside a file sharing its prefix (golang's `alpha/`
         vs `alpha-b.txt`)
      3. **cap-after vs cap-during** — assert the capped prefix AND that a late-sorting file is
         **absent** under the cap
- [ ] A27.2 Use **`ß` (U+00DF)** for the locale-vs-code-point probe, never an accented letter:
      `café` compares at `c` under both rules and is useless, and `ß` has **no NFD decomposition**,
      so macOS filename normalisation cannot silently hollow the test out.
- [ ] **A27.0 — REVIEW GATE (acceptance criterion for this whole section): no port marks an
      ordering task done without stating WHAT IT MUTATED and WHAT FAILED.** A task ticked without
      that statement is not done. This is the single most load-bearing check in the batch: five
      vacuous fixtures were written here, code review caught none of them, and mutation caught
      every one.
- [ ] A27.3 **A27b — MUTATION IS THE ACCEPTANCE TEST.** Before reporting a fixture green, BREAK the
      implementation each way the rule could be wrong and confirm the test FAILS each time; state
      what was mutated and what failed. **A green fixture that has never failed is an untested
      assertion.** FIVE fixtures in this batch were vacuous and **every one was caught by mutation,
      never by review** (the `docx`-only A15 fixture, js's `café`, csharp's `inner-a/b.txt`,
      csharp's first A26 cap fixture, and one more).
      - [ ] js · [ ] python · [ ] golang · [ ] java · [ ] csharp · [ ] elixir · [ ] clojure
- [ ] A27.4 **A27c** — a cap fixture must beat the port's ACTUAL walk order: probe the real walk,
      then build the fixture so first-N-by-WALK and first-N-by-SORT are different **SETS**, not
      merely different orders. Minimal shape: two root files filling the cap before the walk reaches
      a nested file that sorts first (csharp's `mß.txt` + `n.txt` + `a-dir/zz.txt`, cap 2).
- [ ] A27.5 **A27d — PREFER the SELF-PROVING fixture** (python's shape, better than A27c's manual
      probe): reproduce the **pre-fix walk inside the test**, compute first-N-by-walk and
      first-N-by-sort, and assert **as a PRECONDITION** that they are different sets — failing with
      "fixture is vacuous on this filesystem" if they ever coincide — and only then assert the
      capped output. A probe is correct on the machine it ran on; a self-proving fixture is correct
      everywhere and **says so when it stops being**. This is the general answer to all five vacuous
      fixtures: make the test assert its own discriminating power.

### A28 — `/` on every platform, and sort on what you emit

- [ ] A28.1 Every capped/ordered listing reaching the model emits relative paths with `/` as the
      separator on every platform, and **sorts on that same string**. A port that sorts one string
      and emits another orders by one thing and shows another.
- [ ] A28.2 POSIX-only ports get this free; **java, csharp and clojure's JVM host must do it
      explicitly** (golang uses `filepath.ToSlash`). One changelog line for Windows consumers.
- [ ] A28.3 **grep emitted an ABSOLUTE path in three ports** (golang, csharp, python) while sorting
      on the relative one — ordering by one string, displaying another, and leaking machine paths
      into model-visible output. All three now emit the same `/`-separated relative string they sort
      on.
- [ ] A28.4 `<skill_files>` **keeps** its absolute path in this batch (see Deferred): §3 pins the
      shape, and there is **no** sort-key/emitted-string mismatch there because every entry shares
      the skill-directory prefix, so ordering by relative path and by the emitted absolute path are
      the same order. **Put that reasoning in the code**, or someone will "fix" it into a divergence.
- [ ] A28.5 **This is the last scope addition.** Anything found from here is recorded as a
      follow-up, not fixed in this batch.

## 7. Verify

- [ ] 7.1 `js/`: `npm test` · `python/`: `python -m pytest -q` · `golang/`: `go test -race ./...`
      · `java/`: `./gradlew test --no-daemon` · `csharp/`: `dotnet test` · `elixir/`:
      `mix test` + `mix coveralls` (≥95) · `clojure/`: both `.cljc` hosts.
- [ ] 7.2 Shared `examples/` re-verified in all seven — outputs still match.
- [ ] 7.3 `conformance/` gates green.
- [ ] 7.4 `openspec validate fix-consumer-issues-86-93 --strict`.
- [ ] 7.5 One `## Unreleased` CHANGELOG entry, user-facing wording, naming every behaviour change
      **and** naming the two deferred items (transcript replay on resume; non-relay resumes
      re-executing with `ctx.answer`) by name — **plus (A7)** the residual `status`-collision
      invariant, **(A17)** the admission-refusal verb-error-vs-settled-result asymmetry,
      **(A23.2)** the classifier `canonicalJson` key ordering and **(A28.4)** `<skill_files>`'s
      absolute paths — all stated as known gaps this change does not close. Plus the two
      behaviour changes: **grep's output moves from absolute to relative paths**, and
      **sort-before-cap changes WHICH results appear**, not only their order (ADR-0004 K1).
- [ ] 7.6 Conventional commits, no `Co-authored-by`. Do not tag, do not publish, do not release.
