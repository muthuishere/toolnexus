# ADR 0024 — One agent, two entry points: `Loop` is a Spec-honouring door, not a second API

- **Status:** **Proposed — the defect half is measured and reproducible; the API-shape half has
  not met its gate.** Everything in *The measurements* is output from
  `spikes/issues/87/`, a hermetic mock-LLM run with no API key, which could have come out the
  other way and did for one of my two predictions. The recommendation in *Decision* is argued from
  that evidence plus a seven-port read; it is not yet spiked in the other six ports.
- **Date:** 2026-09-21
- **Driver:** issue #87 — a consumer building a workflow platform on `golang/agents` found that a
  `Spec.Guardrails` policy denying `bash` **does not run** when the agent is driven with
  `Agent.Loop(...).Run(...)`, and had to move every step onto `Agent.Run(...)`. No error, no
  warning, no doc note. The question this ADR answers is not "fix the bug" — a two-line patch does
  that — but **whether an agent should have two public doors at all**, given that one of them can
  silently drop a security control.
- **Evidence:** `spikes/issues/87/` (Go, runnable: `go run .`) — one `Spec`, three drives, real
  captured output in its `README.md`. Plus a field-by-field read of the seven ports' Loop and
  runtime paths, cited inline.
- **Related:** ADR 0016 (harness / AgentLoop / the third-proposal problem), ADR 0008 (the agent
  runtime must expose the §8 hooks), ADR 0014 (hook composition and the single-slot problem),
  `openspec/changes/add-harness-and-loop`, SPEC §7D.

## Corrections — predictions that did not survive

1. **Predicted: "Loop is deliberately Spec-less, and all seven ports agree."** The comment block at
   `golang/agents/loop.go:9-14` reads like a considered placement law ("Loop takes no options: it
   is read, not configured"), so I expected to find the same omission everywhere and to be arguing
   about a design stance. **False.** Six of seven ports apply the Spec's soul *and* the compiled
   guardrails inside their Loop's client-options builder:
   `js/src/agents/loop.ts:238-240`, `python/src/toolnexus/agents/loop.py:290-295`,
   `csharp/src/Toolnexus/Agents/Loop.cs:125-128`, `java/.../agents/Loop.java:201-205`,
   `elixir/lib/toolnexus/agents/loop.ex:125-131`,
   `clojure/src/toolnexus/agents/loop.cljc:160-164`. Go's `clientFor`
   (`golang/agents/loop.go:90-102`) is the only one that does neither. **This is not a design
   question in six ports. It is a Go parity defect** — the prime directive's exact failure mode,
   and it shifts the weight of the decision below substantially.
2. **Predicted: the issue's field list is exactly right.** **Partly false, in both directions.**
   `Model` *is* honoured on the Loop path — as a per-call override (`loop.go:90-102`), which is the
   documented placement; what is dropped is `Spec.Model` as a default. And `Tools`, `Team`,
   `Budget`, `WaitFor` and `OnMetric` are ignored by **every** port's Loop, not just Go's — so
   those are a deliberate boundary (the Loop is handed a caller-built toolkit and a caller-built
   client), while `Guardrails`/`Hooks`/`Soul` are a Go-only bug. Collapsing the two into one list,
   as the issue does, would push a reader toward the wrong fix.

## The measurements

`spikes/issues/87/` declares **one** `Spec` — `Soul: "You are careful."`, a guardrail denying
`bash`, `Budget{MaxTurns: 2}`, one `bash` tool whose `Execute` appends to a package-level slice —
and drives it three ways against a scripted in-process `http.RoundTripper` that always asks for
`bash` once. Real output:

```
== A. runtime path — ag.Run(...) ==
  text          : tool said: denied: bash is denied in this harness
  bash executed : []   <- guardrail FIRED (tool never ran)
  system prompt : "You are careful."

== B. loop path — ag.Loop(clientOptions, tk).Run(...) ==
  text          : tool said: EXECUTED: ls
  bash executed : [ls]   <- guardrail DID NOT RUN (tool executed)
  system prompt : ""

== C. loop path + the Spec applied by hand (proposed option (a)) ==
  text          : tool said: denied: bash is denied in this harness
  bash executed : []   <- guardrail FIRED (tool never ran)
  system prompt : "You are careful."
```

The issue is **correct as filed**, and understated: the soul goes too. Path C is the same `Loop`
with `SystemPrompt`, `Hooks` (a spike-local copy of the unexported `guardedHooks`) and `MaxTurns`
set by hand — i.e. exactly the assignments `clientFor` declines to make. Three lines turn B into A.

### The honoured/ignored matrix (Go)

| `Spec` field | `Agent.Run` (runtime/`Def`) | `Agent.Loop(...).Run` |
|---|---|---|
| `Does` | routing text on the `task` tool, `runtime.go:1254-1285` | n/a — nothing delegates to a Loop |
| `Soul` (+`SoulFile`) | **honoured** — `agent.go:92-97` → `Def.Soul` → `SystemPrompt`, `runtime.go:1149` | **IGNORED** — `clientFor` never sets `SystemPrompt`, `loop.go:90-102` |
| `Model` | **honoured** as default, `agent.go:98-101` → `runtime.go:1121-1124` | **IGNORED as a default**; per-call `RunOpts.Model` honoured, `loop.go:95-101` |
| `Completion` | **honoured** — `agent.go:118` → `runtime.go:1159` | **honoured** — `loop.go:129` |
| `Guardrails` | **honoured** — `guardedHooks`, `agent.go:117` → `runtime.go:1136-1139,1153` | **IGNORED** — `guardedHooks` (`loop.go:26`) has exactly one caller, and it is not in this file |
| `Hooks` | **honoured** — same line | **IGNORED** — unless the caller happens to pass the same hooks in `ClientOptions` |
| `Budget` | **honoured** — `agent.go:113`, carve at `runtime.go:515`, live ancestor walk | **IGNORED** — `MaxTurns` comes from the caller's `ClientOptions` |
| `WaitFor` | **honoured** — `agent.go:114`, escalator `runtime.go:829-858` | **IGNORED** (all ports) |
| `OnMetric` | **honoured** — `agent.go:119` → `runtime.go:1140-1142,1154` | **IGNORED** (all ports) |
| `Tools` | **honoured** — `agent.go:110` → `runtime.go:1126,1131` | **IGNORED** — the caller hands `Loop` a built `*tn.Toolkit` |
| `Team` | **honoured** — closure at `agent.go:121-123`, `task` tool at `runtime.go:1127-1129` | **IGNORED** — no `task` tool, so a Loop-driven agent **cannot delegate at all** |
| `OnSpawn`/`OnClose` | **honoured** — `agent.go:115-116` | n/a — a Loop has no handle lifecycle |

Read the two "IGNORED" clusters separately. The bottom five rows (`Tools`, `Team`, `Budget`,
`WaitFor`, `OnMetric`) are ignored in **all seven** ports and follow from what a `Loop` *is*: a
driver over a client and toolkit the caller already built. The top cluster
(`Soul`, `Guardrails`, `Hooks`) is ignored **only in Go**.

### Seven-port parity

| port | Loop entry | one-shot entry | soul on Loop | guardrails on Loop | verdict |
|---|---|---|---|---|---|
| **go** | `agents/loop.go:83` | `agents/agent.go:131` | **no** | **no** | **defect** |
| js | `src/agents/agent.ts:103` | `agent.ts:117` | yes, `loop.ts:238` | yes, `loop.ts:240` | correct |
| python | `agents/surface.py:188` | `surface.py:256` | yes, `loop.py:290` | yes, `loop.py:293` | correct |
| java | `agents/Agents.java:128` | `Agents.java:176` | yes, `Loop.java:201` | yes, `Loop.java:205` | correct |
| csharp | `Agents/Agent.cs:79` | `Agent.cs:116` | yes, `Loop.cs:125` | yes, `Loop.cs:128` | correct |
| elixir | `agents.ex:111` | `agents.ex:124` | yes, `loop.ex:125` | yes, `loop.ex:131` | correct |
| clojure | `agents/loop.cljc:147` | `agents/runtime.cljc:1475` | yes, `loop.cljc:160` | yes, `loop.cljc:164` | correct |

No port is safe by construction — all seven have two doors. Clojure is closest: `loop/create`
takes the same **AgentDef** map the registry produces (`loop.cljc:147-152`), so there is one
source of spec truth even though the client-build code is still duplicated. The other six pass a
`Spec` (or, in Java, four loose fields — `Agents.java:131-132`) into a second client-building site,
which is exactly the duplication that let Go drift.

A secondary drift, worth a line in whatever change lands: **JS lets the soul win over the caller's
`systemPrompt`** (`soul ?? options.systemPrompt`, `loop.ts:238`) while **Python and C# let the
caller win** (`if soul and not opts.get("system_prompt")`, `loop.py:289`; the same guard at
`Loop.cs:125`). That is a real behavioural fork on a path all six claim to honour.

## The decision to argue

Three options were on the table.

**(a) `Loop` honours the full Spec.** Apply `guardedHooks(l.agent.Spec)` and `Spec.Soul` in
`clientFor`, and decide explicitly for `Model`/`Budget`.

**(b) Demote `Loop`** to an internal detail, deprecate it, make `Agent.Run` the only public door.

**(c) Document `Loop` as a Spec-less driver** and make `Agent.Loop()` fail loudly when the Spec
carries fields it will discard.

### Why the security angle settles it rather than merely informs it

A guardrail that silently does not run is strictly worse than no guardrail API. With no API, a
caller who needs to deny `bash` must build the denial themselves and will know whether they did.
With a silent one, they write the policy, read it back in code review, see it in the `Spec`
literal, and ship a harness with **no policy at all** — and the only signal is the absence of a
denial they were never going to look for. The consumer in #87 found it by accident while
extending a platform; the failure mode is a reviewed, approved, zero-policy deployment.

This is not a tie broken on taste. Of the three options, only (a) and (c) make the state
detectable at all, and (c) makes it detectable **at construction time**, which is earlier and
louder than (a) makes it. On that criterion alone (c) wins. What decides against it is correction
1: in six of seven ports, `Loop` already honours soul and guardrails and has done so since
`add-harness-and-loop` shipped. Choosing (c) means changing six correct ports to match one
incorrect one, and telling every existing JS/Python/Java/C#/Elixir/Clojure caller that a guardrail
they have been relying on is now a construction error. The security argument favours loudness;
the parity argument — the prime directive — says the loud thing to be loud about is the Go
omission, not the design.

**(b)** is rejected on cost and on ADR 0016's finding: `Loop` is the documented ordinary way to
drive a harness (`site/src/content/docs/harness/index.mdx`, `harness/completion-gate.mdx` — which
shows `shipper.Loop(...)` in Go *and* C#), and it exists precisely because a caller who already
has a client and a toolkit should not have to stand up a runtime, a handle tree, a budget carve
and a conversation store to run one prompt. Removing it moves that cost onto every such caller and
buys a property — one door — that (a) buys for two lines.

### Decision

**Option (a), with (c)'s loudness applied to the residue.** Concretely:

1. **Go `clientFor` applies the Spec** — `SystemPrompt: l.agent.Spec.Soul` (caller's
   `ClientOptions.SystemPrompt` wins when set, matching Python/C#, not JS) and
   `Hooks: guardedHooks(l.agent.Spec)`. Path C in the spike is this change.
2. **Resolve the JS soul-precedence fork** the same way, so the rule is one sentence in SPEC §7D
   rather than six implementations.
3. **`Spec.Model` and `Spec.Budget.MaxTurns` become Loop defaults too** — both map cleanly onto
   `ClientOptions`, both are on the Spec because they are meant to travel with the agent, and
   leaving them out is the same silence in a milder key.
4. **The residue — `Tools`, `Team`, `WaitFor`, `OnMetric` — gets (c)'s treatment**: `Agent.Loop()`
   returns an error (Go: `(*Loop, error)` on a new constructor, or a documented panic in the
   existing one; the other ports' idiomatic equivalent) when the Spec declares any of them, naming
   the field. These genuinely cannot be honoured by a driver over a caller-built toolkit and
   client, and today a `Team` declared on a Spec means a Loop-driven agent cannot delegate — a
   capability silently absent, which is the same bug class as the guardrail.
5. **A regression test per port**, asserting the thing the spike asserts: *a denied tool's
   `execute` is never entered on the Loop path.* Assert on execution, not on the model's text —
   the text is what made this survivable for a release.

Item 4 is the part that is still **Proposed**: it changes a signature.

## What would earn this an "Accepted"

1. The spike's assertion lands as a real test in **all seven ports** and fails before the fix.
2. Items 1–3 shipped seven-port, with SPEC §7D stating the precedence rule (spec vs caller) in one
   sentence, so a port cannot satisfy the prose and disagree.
3. Item 4 prototyped in **two** ports with different error idioms (Go and Elixir, or Go and C#),
   with a demonstration that an existing caller who declares only honourable fields is unaffected.
4. A statement of what breaks: Go callers driving a Loop with a Spec carrying `Guardrails`/`Soul`
   **change behaviour** — that is the point, but a harness that relied on the guardrail *not*
   firing (e.g. a test fixture) will now deny. Item 4 breaks compilation for Loop callers whose
   Specs carry `Team`/`Tools`/`WaitFor`/`OnMetric`, in every port. Items 1–3 are a no-op for any
   Spec that declares none of the affected fields — the "empty ⇒ byte-identical" rule the `Spec`
   doc comments already promise.

Until 1–3 exist in seven ports, this stays **Proposed**.
