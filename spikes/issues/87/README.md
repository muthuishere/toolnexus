# Spike — issue #87: `Agent.Loop` silently ignores `Spec.Guardrails` (Go)

Hermetic. **No network, no API key, no cost** — the LLM is an injected
`http.RoundTripper` (`mock.go`) that always asks for `bash` once, then quotes
back whatever the tool result was.

```
go run .
```

## What it does

One `agents.Spec` (`main.go:spec`) — soul `"You are careful."`, a guardrail
denying `bash`, `Budget{MaxTurns: 2}`, and one `bash` tool whose `Execute`
appends to a package-level `executed` slice. The assertion is on **execution**,
not on the model's text: a guardrail that fires means `Execute` is never entered.

The same Spec is driven three ways:

- **A** — the runtime / `Def` path: `ag.Run(agents.Options{…}, "run ls")`
- **B** — the standalone loop: `ag.Loop(clientOptions, toolkit).Run(ctx, "run ls", …)`
- **C** — the loop again, with the Spec applied **by hand** in `ClientOptions`
  (`SystemPrompt`, `Hooks`, `MaxTurns`). `fix.go` holds a spike-local copy of the
  unexported `guardedHooks` (`golang/agents/loop.go:26`), since the spike does
  **not** modify library source. Path C is what option (a) of ADR 0024 would make
  `Loop.clientFor` do.

## Captured output

```
== A. runtime path — ag.Run(...) ==
  text          : tool said: denied: bash is denied in this harness
  status        : done
  bash executed : []   <- guardrail FIRED (tool never ran)
  system prompt : "You are careful."

== B. loop path — ag.Loop(clientOptions, tk).Run(...) ==
  text          : tool said: EXECUTED: ls
  status        : done
  bash executed : [ls]   <- guardrail DID NOT RUN (tool executed)
  system prompt : ""

== C. loop path + the Spec applied by hand (proposed option (a)) ==
  text          : tool said: denied: bash is denied in this harness
  status        : done
  bash executed : []   <- guardrail FIRED (tool never ran)
  system prompt : "You are careful."
```

## What it shows

1. Issue #87 is **correct as filed**, and understated — `Spec.Soul` is dropped on
   the Loop path too (`system prompt : ""` in B), so the agent loses both its
   policy and its identity.
2. Both runs report `status: done`. Nothing in the `Outcome` distinguishes the
   harness that enforced its policy from the one that did not.
3. The fix is three assignments in `clientFor` (path C), and is a **Go-only**
   change: the other six ports already apply soul + guardrails inside their
   Loop's client-options builder — see the parity table in
   `docs/adr/0024-one-agent-two-entry-points.md`.

No library source is modified by this spike.
