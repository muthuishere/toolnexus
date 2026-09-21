# ACP spike — ADR 0031 gate

Ran against a **fake ACP server**, a Go binary (`spikes/acp/fakeagent/`) spoken to
over real OS pipes (`exec.Cmd.StdinPipe`/`StdoutPipe`) with JSON-RPC 2.0, one object
per line — plus, for the "confirm no new SPEC surface" gate, a `session/prompt`
against the **real** `devin acp` binary. Everything here is Go, `spikes/acp/`, and
runs with `go run . <scenario>` from that directory (see `main.go` header for the
scenario list).

## What I ran

```
cd spikes/acp
go run . all      # gates 1–4 against the fake server (deterministic, ~1s)
go run . live     # gate 5's live sanity check against real `devin acp`
```

`go run . all` builds `fakeagent/` to a temp binary, drives it through the minimal
client in `client/`, and prints verbatim per-scenario transcripts. Full output below,
unedited.

## Verdict summary

| # | Gate item | Verdict |
|---|---|---|
| 1 | Reproduce stale-answer, then supersedes-marker fix | **HOLDS** (reproduced + fix confirmed) |
| 2 | Turn hangs on unanswered permission; completes when answered | **HOLDS** |
| 3 | `agent_thought_chunk`/tool narration corrupts unfiltered output; filtered output is clean | **HOLDS** |
| 4 | Warm-session win against a fast fake server | **PARTIAL / HONEST NO** — the win is real but tiny (~6x on a **~5ms** cold call), i.e. it is process-spawn overhead only. Against a fake server with no real model latency, "warm" does not model the ADR's actual 15s claim at all. |
| 5 | No new SPEC §0 surface needed | **HOLDS** (reasoning below) |

---

## Gate 1 — stale answer + supersedes marker

```
========== GATE: stale ==========
turn 1 prompt: "What is the capital of France?"
turn 1 answer: "STALE-ANSWER-TO:What is the capital of France?"
turn 2 full-request (no marker): "What is the capital of France?\nWhat is the capital of Japan?"
turn 2 answer (EXPECT stale/wrong): "STALE-ANSWER-TO:What is the capital of France?"
=> REPRODUCED: agent answered the FIRST (stale) question, not the new one.
turn 3 full-request WITH supersedes marker: "What is the capital of France?\nWhat is the capital of Japan?\nSUPERSEDES-ALL-PRIOR: What is the capital of Japan?"
turn 3 answer: "FRESH-ANSWER-TO:What is the capital of Japan?"
=> FIX HOLDS: supersedes marker made the agent answer the fresh question.
```

**How this was built to be a fair test, not a rigged one.** `fakeagent -scenario=stale`
keeps a real session transcript (`history []string`) across `session/prompt` calls,
mirroring a real ACP session's statefulness. Its answer rule
(`handleStale` in `fakeagent/main.go`) is deliberately the simplest plausible
behavior a stateful agent could have: on a fresh prompt, scan the session's history
oldest-first and answer whichever remembered question is a *substring* of what just
arrived — which is exactly what happens when toolnexus sends the **full accumulated
request** every turn (turn 2's text contains turn 1's question verbatim as a prefix,
so a substring/prefix-keyed agent matches turn 1 first). This is not a strawman: it's
the same shape of bug the ADR's reporter observed live against `devin`.

The fix — an explicit `SUPERSEDES-ALL-PRIOR:` marker naming the truly-current
question — flips the answer to the fresh one, deterministically, every run.

**Reading on the decision.** This *reproduces* the failure mode the ADR describes and
*confirms* the proposed mitigation works — but only against an agent whose staleness
bug has this exact shape (match against remembered history). A real agent's actual
matching behavior is a black box; the marker convention is a **prompt-level
workaround for a protocol-level design choice** (full-request-every-turn against a
stateful session), not a guarantee. It held here because the fake agent's bug and the
marker's fix are the same shape by construction — that's what "reproduce + show the
fix" means for a scripted spike, not proof the marker is sufficient against arbitrary
real agents. See the recommendation at the bottom.

## Gate 2 — permission hang and unblock

```
========== GATE: hang ==========
prompt returned after 2.00108625s, err=acp: turn hung waiting on session/request_permission for 2s
=> REPRODUCED: turn hung until the client's own timeout fired (would hang forever without one).

========== GATE: permission ==========
prompt completed in 277.292µs
answer: "PERMITTED:delete the database"
=> HOLDS: answering session/request_permission with first allow-kind option unblocked the turn.
```

`fakeagent -scenario=hang` sends `session/request_permission` and then goes
permanently silent (never replies to `session/prompt`). The client's own
`PermissionTimeout` (set to 2s for the spike so it terminates) is the ONLY thing that
stops the call — with `PermissionTimeout: 0` (unbounded) the call from gate 2's first
run genuinely blocks forever; this is the "costs the next implementer a day" trap the
ADR names. `-scenario=permission` is the same setup but the client answers with the
first `allow`-kind option, and the turn completes in under 1ms once answered — proof
the hang is caused specifically by an unanswered `request_permission`, not something
else in the plumbing.

## Gate 3 — thought/tool narration vs clean output

```
========== GATE: noisy ==========
clean (agent_message_chunk only): {"answer":"42"}
dirty (every chunk kind accumulated): Let me think about this... {"answer":now double-checking the number... "42"}
=> clean output parses as JSON: HOLDS
=> dirty output does NOT parse as JSON: REPRODUCED corruption
```

`fakeagent -scenario=noisy` interleaves `agent_thought_chunk` and `tool_call`/
`tool_call_update` notifications around a JSON payload split across two
`agent_message_chunk` notifications (a realistic streaming shape — the model doesn't
finish its whole JSON blob in one chunk). The client (`client/client.go`) keeps two
accumulators side by side purely to make the comparison visible: `clean` (only
`agent_message_chunk`) and `dirty` (every chunk kind, for the spike's own
demonstration — a real client never needs the dirty accumulator). `clean` parses as
valid JSON; `dirty` does not. This is the exact corruption the ADR warns about, shown
mechanically rather than asserted.

## Gate 4 — warm-session win against a fake server (honesty check)

```
========== GATE: warm ==========
N=6 turns
cold (spawn per turn):  total=25.549543ms  avg/turn=4.258257ms
warm (one process):     total=3.366457ms  avg/turn=561.076µs  (init=2.916208ms once, then avg prompt=75.041µs)
=> Against a FAKE server with no real startup cost, the fixed cost being amortised
   is process-spawn + exec.Cmd bookkeeping only (microseconds-to-low-ms), NOT the
   15s devin binary startup the ADR's real numbers show. Report this honestly below.
```

**Said plainly, as the gate demands:** yes, "warm" beats "cold" here too (≈6x,
4.26ms/turn cold vs 0.56ms/turn warm over 6 turns) — but the entire cold-path cost in
this measurement is `exec.Command` fork/exec plus two JSON-RPC round trips
(`initialize` + `session/new`) against a Go binary that does nothing. That is
**µs-to-low-ms** overhead. It is not, and cannot be, evidence of anything close to the
ADR's real 15.3s → 1.6s numbers, because the fake server has no model inference, no
tool execution, no real startup cost to amortize. **The warm-session win the ADR
reports is a property of `devin`'s real startup latency, not of the ACP protocol
itself.** Swapping in a fast backing model/agent (or, as the live check below shows, a
genuinely warm real session) would shrink the win to roughly what this fake-server run
shows — single-digit milliseconds of process bookkeeping — because ACP's win **is**
process-startup amortization, exactly as ADR 0031 already says ("the entire per-turn
cost is process startup"). This spike does not discover a *second*, protocol-level
speedup; it confirms there isn't one to find against a fast backend.

## Gate 5 — new SPEC §0 conformance surface?

**No.** Read `SPEC.md §0` (`sed -n '38,108p' SPEC.md`) end to end. Its 13 points cover:
`Tool`/`ContentPart` shape, MCP tool naming/config/execute mapping, skill discovery
and the `skill` tool's byte-exact output, the three provider adapters (schema
mapping), `native`/`http` tool sources, the unified client's system-prompt assembly
and loop, built-in tools, suspension (`Pending`/`waitFor`), and single-turn
`translate`. ACP, per ADR 0031's own proposed decision, ships as a `Generate` — the
exact seam `InProcessOptions.Generate` already defines in `golang/inprocess.go`
(`func(InProcessRequest) (InProcessResponse, error)`, used by
`CreateInProcessClient`). Everything ACP-specific (the JSON-RPC session, chunk
filtering, permission handling, the supersedes marker) lives entirely **inside** that
one `Generate` closure; from the loop's perspective it is indistinguishable from any
other in-process model. None of §0's 13 points describe a model source's internals —
`onnx-in-process` (a local model) and a CLI-backed `Generate` (ADR 0032) don't touch
§0 either, for the same reason. **This confirms ADR 0031's own framing** ("the
smallest framing: ACP is a model source, not a new tool source and not a new
client") — a `LoadACP(...) -> acp.Generate` helper is Go-local, ships in `golang/`
only or as an `examples/` recipe, and needs zero cross-port work unless/until another
port wants the same convenience function (which would be a parity nice-to-have, not a
conformance requirement).

---

## `devin` check (issue driver — real ACP agent)

`devin` was found at `/opt/homebrew/bin/devin`, authenticated (`devin auth status` →
`Logged in`, team `devin-team$account-597557f994014b028f9f4b743bab96de`). Per
instructions, ran exactly **one** cheap live sanity call — `initialize` →
`session/new` → one trivial `session/prompt` — through the SAME minimal client used
for the fake-server gates, nothing devin-specific added beyond the binary path:

```
$ go run . live
devin found at: /opt/homebrew/bin/devin
live devin acp answered in 14.353563459s: "pong"
```

(Prompt sent: `"Reply with exactly the single word: pong"`.) One fixup was needed to
talk to the real binary that the fake server didn't require: `session/new` must be
sent with an **absolute** `cwd` and an (empty is fine) `mcpServers` array or devin
replies `-32602 Invalid params` — the fake agent doesn't validate params at all, so
this only surfaced against the real thing (`client/client.go`, `Start`). The 14.35s
first-turn latency is consistent with the ADR's own reported ~15–17s first-call cost
— i.e. this single call corroborates the ADR's core premise (all cost is process/model
startup) without spending more than one call's worth of usage.

---

## Recommendation on the real question: full-request-every-turn vs delta-mode

The gate item that could have falsified the ADR's proposed default did not falsify
it — but it also didn't clear it as cleanly as "HOLDS" suggests standing alone. Two
things are true at once:

1. **The stale-answer failure is real and reproducible**, and the supersedes marker
   *does* fix it against an agent whose staleness bug is "matches earlier history it
   still remembers" — plausible, and consistent with what the ADR's reporter saw
   live against `devin`.
2. **The marker is a prompt-level patch over a protocol-level mismatch, verified only
   against one scripted agent behavior.** It cannot be shown to generalize to every
   real ACP agent's internal handling of a growing, near-duplicate transcript,
   because that logic is opaque and agent-specific (this is exactly the ADR's own
   framing: "a prompt-level hack holding a protocol-level mismatch together").

Given that, and given that ADR 0031 itself already prefers full-request-every-turn for
a real, independent reason — **matching every other toolnexus model source's
stateless contract**, and avoiding a shadow transcript that can drift from
`ConversationStore` — my recommendation is: **keep full-request-every-turn as the
default, ship the supersedes marker as part of the default `Generate`'s prompt
assembly (not left to the caller), and treat delta-mode as the documented opt-in for
callers who've verified their specific agent handles growing transcripts cleanly
without it.** The spike didn't falsify the default; it showed the mitigation load-bears
exactly as much as the ADR already assumed, no more, no less. That is a "ship with the
marker on by default" result, not an "the default is proven safe, skip the marker"
result — don't let gate item 1 passing be read as license to drop the marker.

## Files

- `spikes/acp/fakeagent/main.go` — scripted fake ACP server (5 scenarios: `stale`,
  `hang`, `permission`, `noisy`, `warm`).
- `spikes/acp/client/client.go` — minimal ACP client (JSON-RPC demux by id,
  `agent_message_chunk`-only accumulation, first-allow-kind permission answering,
  bounded permission timeout).
- `spikes/acp/main.go` — driver: `go run . {stale|hang|permission|noisy|warm|live|all}`.
- `spikes/acp/go.mod` — standalone module (`toolnexus/spikes/acp`), not wired into
  `golang/`'s module — this is throwaway spike code, not a shipped package.
