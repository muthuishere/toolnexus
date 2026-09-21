# ADR 0023 — Call-shape parity: a toolkit-less completion, and the gate that would have caught it

- **Status:** **Proposed.** Every per-port claim below was measured, not read off a signature:
  seven probes ran against an offline mock LLM and three of the issue's five predictions did not
  survive (recorded in *Corrections*, not quietly dropped).
- **Date:** 2026-09-21
- **Driver:** Issue #86. `client.ask(prompt)` with no toolkit is a *completion* — ask a model to
  write something and use the text elsewhere. It works in Go. The issue reports it crashing in JS
  and claims the other five ports make the toolkit mandatory, and asks the sharper question:
  the options manifest gates *option* parity, so should the conformance suite also gate
  **what calls are legal**?
- **Evidence:** `spikes/issues/86/` — an offline mock LLM (no network, no API key, no cost) plus a
  probe in each of the seven ports. Every number below is reproducible with
  `spikes/issues/86/run.sh`; the run's output is pasted verbatim in that README.
- **Related:** ADR 0001 (§8 Gap 5 — omit the `tools` key when empty), `conformance/README.md`
  (the options-parity gate), SPEC §0.10, §8.

## Context

Issue #86 is filed as five ports missing a capability that two have. The measurement says
something more interesting: it is **three different situations wearing one label**, and one half
of the reported bug does not exist at all.

## The measurement

Seven probes, one offline mock LLM, one question ("write me a haiku"), asked with no toolkit.

| port | toolkit-less call | what happens | file:line |
|---|---|---|---|
| **golang** | `c.Ask(ctx, p, nil, "")` | **works** — `Tools()` explicitly nil-guards, documented as "callers may pass nil to Run/Ask to mean no tools" | `golang/toolkit.go:326-330`; sigs `golang/client.go:639,700,1427` |
| **clojure** | `(run client p {})` | **works** — `:toolkit` is a destructured map key; `system-message` and `adapter/to-openai` nil-pun through | `clojure/src/toolnexus/client.cljc:901,336-344`; `clojure/src/toolnexus/adapter.cljc:24-32` |
| **elixir** | `run(client, p, nil, [])` **works**; `run(client, p)` does not exist | loop is nil-tolerant via catch-all clauses; only the short arity is missing | `elixir/lib/toolnexus/client.ex:386-389`; `tools_of/1` `:814-816`, `skills_prompt_of/1` `:818-822` |
| **js** | `client.ask(p, {})` | `TypeError: Cannot read properties of undefined (reading 'skillsPrompt')`; bare `ask(p)` → `reading 'on_text'` | `js/src/client.ts:446,612,640`; deref `:653` |
| **python** | `c.run(p)` | `TypeError: Client.run() missing 1 required positional argument: 'toolkit'`; explicit `None` → `AttributeError: 'NoneType' object has no attribute 'skills_prompt'` | `python/src/toolnexus/client.py:1339,1365,1411`; deref `:845` |
| **java** | `client.run(p, (Toolkit) null)` | compiles; `NullPointerException: Cannot invoke Toolkit.skillsPrompt() because "toolkit" is null` | `java/src/main/.../LlmClient.java:890,1122,1232`; deref `:1283` |
| **csharp** | `RunAsync(p, null!)` | compiles (CS8600-class warning, not an error); `NullReferenceException` | `csharp/src/Toolnexus/LlmClient.cs:502,659,693`; deref `:735` |

And the wire, for all nine requests the run produced — every port, toolkit-less *and*
empty-toolkit:

```
tools_key=False  tool_choice_key=False  keys=['messages', 'model']
```

## Corrections — three of the issue's claims did not survive

1. **"Fixed in JS on the `jev` branch: a synchronous `Toolkit.empty()`."** No such symbol exists.
   `git log --all -S"Toolkit.empty"` returns nothing; `jev` is nine commits *behind* `main` with
   zero commits ahead; the probe prints `typeof Toolkit.empty = undefined` and reproduces the
   exact `TypeError` the issue quotes as already fixed. **JS is broken on `main` today.** The
   issue's title — "works in Go and JS" — is wrong: it works in Go. It is six ports, not five.
2. **"`clojure`: `:toolkit` mandatory."** It is not. `(run client prompt {})` and
   `(ask client prompt {})` both return text today. Clojure needs a test and a docstring line;
   it needs no code.
3. **"`elixir`: needs an arity that omits the toolkit."** Half right. A `nil` toolkit already
   works through the whole loop — `tools_of(_) -> []` and `skills_prompt_of(_) -> ""` are
   catch-alls. Only the sugar arity is absent.

A fourth, smaller one: **"`createToolkit()` … turns the 10 builtins on by default, so it cannot
express 'no tools'."** It expresses it exactly — `builtins:false` yields `tools=0` in all four
crashing ports, verified in the probe. The real objection is that the one-liner is a
two-line `await`, which is a fair ergonomic complaint and a false capability claim.

And the correction that matters most for scoping: **the wire half of the issue is already
fixed in all seven ports.** "No toolkit ⇒ no `tools` key at all, not an empty array" is §8 Gap 5
from ADR-0001, shipped in 0.9.0, and it holds — `golang/client.go:966`,
`python/.../client.py:1485`, `java/.../LlmClient.java:1368`, `csharp/.../LlmClient.cs:968`,
`elixir/.../client.ex:888`, `clojure/.../client.cljc:404-406`. Only the **call shape** differs.

## The one bug under three labels

Java, Python and JS fail on the *same line of the same function*: building the system message
(SPEC §0.10, `system = systemPrompt + "\n\n" + skillsPrompt()`) dereferences the toolkit
**before** anything touches tools. C# is the same crash with a less informative message.
That is why "no tools" and "no toolkit" came apart: the loop stopped needing tools from an
empty toolkit years ago, and never stopped needing the *object*.

## Decision

### D1 — The absent toolkit is a first-class call, pinned in SPEC §0.10

Add to §0.10: *the toolkit is optional; its absence is a completion — the system message is the
system prompt alone, and the request carries no `tools` and no `tool_choice` key.* Everything
after that is per-port spelling.

### D2 — Nil-tolerance in the loop, not a new public type

The fix is **one null-guard at the system-message site per port**, matching what Go and Elixir
already do at their accessors. Do **not** add a public `Toolkit.empty()` to six ports: it is a
new exported symbol in every API, a new row in every doc, and a second way to say a thing the
`builtins:false` toolkit already says. An empty toolkit and no toolkit must be observably
identical — the probe already shows they are, on the wire.

### D3 — The minimum call shape per language, in that language's idiom

Deliberately **not** one shape in seven costumes. A nullable parameter is idiomatic C# and
un-idiomatic Java; an extra arity is idiomatic Elixir and meaningless in Python.

| port | shape | why this one | size |
|---|---|---|---|
| **golang** | none | `nil *Toolkit` is the Go spelling and already documented | — |
| **clojure** | none; add two tests + a docstring clause | already works; the risk is a future refactor silently taking it away | **S** (test only) |
| **elixir** | `def run(client, prompt, toolkit \\ nil, opts \\ [])` — two defaults, giving `run/2` | the loop is already nil-clean; this is sugar and nothing else. Same for `ask`, `stream` | **S** |
| **python** | `toolkit: Optional[Toolkit] = None` on `run`/`ask`/`stream` + guard the `skills_prompt` deref | a defaulted keyword is *the* Python idiom; an overload would be a `@overload` lie | **S** |
| **js** | `ctx` optional and `ctx.toolkit` optional (`ctx?: { toolkit?: Toolkit; … }`) + guard the deref | JS already passes an options bag; making one key optional costs no new surface | **S** |
| **csharp** | `Toolkit? toolkit` — nullable, **not** an overload | `RunAsync`/`AskAsync`/`StreamAsync` already carry 2–3 optional params each, over `string` and `IReadOnlyList<ContentPart>` prompt shapes. Overloads multiply that; `Toolkit?` does not, and the compiler starts telling callers the truth instead of warning after the fact | **S/M** |
| **java** | overloads — `run(String)`, `ask(String, String)`, `stream(String, Consumer)` etc. — plus the null guards | Java has no default arguments and no nullable types, so an overload is the only honest spelling. But `LlmClient` already exposes ~15 `run`/`ask`/`stream` signatures across two prompt shapes, history, cancel and id; a blanket toolkit-less twin of each doubles that. **Add overloads only for the shapes a completion actually uses** (prompt, prompt+id, prompt+onText) and let the rest keep taking an explicit toolkit | **M** |

Java is the only port where the fix is a design decision rather than a keystroke, and it is the
port the issue's table forgot.

### D4 — Yes, build a call-shape gate — and it cannot be the options gate

`conformance/check_options_parity.py` works by **reading source files** and normalising
identifiers. That is exactly why it could never have seen #86: every port *has* a `run`, every
port *has* a `toolkit`. The drift is in arity, nullability and defaults — properties of a call,
not of a name. No amount of grepping finds it.

So the gate must be **executed**, and the spike is already its prototype. Shape:

- `conformance/callshape_manifest.json` — a list of *legal calls*, each an intent plus a
  per-port spelling and an expected wire assertion. Row one is this issue:
  `{"id": "completion.no-toolkit", "tier": "core", "wire": {"absent": ["tools", "tool_choice"]}}`.
- `conformance/callshape/<port>/` — one probe per port, each printing one NDJSON line per row:
  `{"id": …, "ok": true|false, "error": …}`. The probes in `spikes/issues/86/` are these files
  with the prose removed.
- The same offline mock LLM, so the gate is hermetic and free and fits CI's no-network rule.
- A port may declare a row **`unsupported` by name**, exactly as the options manifest permits
  absences by tier. An omission that stops being printed is indistinguishable from something
  that was finished.

Cost is real — seven probe harnesses is the same tax the ports themselves pay — so it is worth
paying only if it keeps earning. It will: the same class of bug is every call that is legal in
one port and not another. Candidate rows already visible while measuring #86: `ask` without an
`id`, `stream` without a toolkit, a `ContentPart[]` prompt where a `string` is accepted, `run`
with history and no cancel. None of those is covered by anything today.

**Recommended sequencing:** land D1–D3 first (the bug), then D4 seeded with the single row that
proves it (the gate). A gate introduced together with its first green row is a gate; a gate
introduced with a backlog of red rows becomes a `continue-on-error` line in CI.

## Consequences

- Seven ports gain one call; four of them stop crashing on it. No behaviour changes for anyone
  who passes a toolkit — the empty-toolkit and no-toolkit wire bodies are already identical.
- `Toolkit.empty()` is **not** added anywhere, and #86's JS section should be corrected rather
  than treated as a landed fix.
- CI grows a second conformance axis. The options gate answers "does every port have this
  knob"; the call-shape gate answers "can every port be called this way". Neither subsumes the
  other, and #86 exists because only the first one was ever built.

## What is not decided here

- Whether `ask` should also drop its `id` argument, and the rest of the candidate rows above.
  They belong in the same OpenSpec change as D4's manifest, not in this ADR.
- Whether the Java overload set should be pruned generally. Noted, not fixed: that is a separate
  API-surface argument and folding it into a bug fix would hide it.
