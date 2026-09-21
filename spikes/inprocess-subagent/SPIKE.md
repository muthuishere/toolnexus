# Spike: ADR 0024 — the in-process seam stops at the top-level client

Spike for issue #95 / `docs/adr/0024-the-in-process-seam-stops-at-the-top-level-client.md`.
Attacks the ADR's own Gate. Verdict per item below; recommendation at the end.

Everything here lives under `spikes/inprocess-subagent/`. The repo is otherwise
unmodified by this spike (a temporary patch to `golang/inprocess.go` was applied,
run, captured, and reverted — see "What I ran", step 2).

## Gate item 1 — PARITY AUDIT (the most important item)

**Question:** does the sub-agent / agent-runtime configuration in js/python/java/csharp/elixir/clojure
already accept a SEMANTIC generate function (not an HTTP transport), the way the ADR asks?

**Method:** for each port, found the agent-runtime Options/RuntimeOptions equivalent of
`golang/agents/runtime.go:258-290`, and found how that runtime actually builds each
handle's per-turn client (the wiring point equivalent to `runtime.go:1151`).

| Port | Options field a host must configure the model through | file:line | Shape | In-process adapter that would need re-implementing |
|---|---|---|---|---|
| **Go** | `Options.Transport http.RoundTripper` | `golang/agents/runtime.go:272` | HTTP (`http.RoundTripper`) | `inProcessRoundTripper` (unexported), `golang/inprocess.go:82-153` |
| **JS** | `RuntimeOptions.fetch?: typeof fetch` | `js/src/agents/runtime.ts:274` | HTTP (`fetch`) | inline fetch-shaped closure inside `createInProcessClient`, `js/src/client.ts:1174-1220ish` (not a separately named/exported function) |
| **Python** | `AgentRuntime.__init__(transport: Optional[HttpTransport] = None)` | `python/src/toolnexus/agents/runtime.py:349` (doc at :340-341: `"transport"` injects **the LLM HTTP transport**) | Wire (`HttpTransport` protocol: `.post`/`.open`) | `_InProcessTransport` (private, leading underscore), `python/src/toolnexus/client.py:2176` |
| **Java** | No `Transport`-equivalent field at all in `RuntimeOptions.java` (only `baseUrl`/`style`/`apiKey`/`defaultModel`, all wire-shaped strings) — the runtime wires a fixed internal `HttpClient` | `java/.../agents/RuntimeOptions.java:16-19`; wiring at `java/.../agents/AgentRuntime.java:104` (`private final HttpClient gated = new GatedHttpClient();`) and `:545` (`.httpClient(gated)`) | HTTP (`java.net.http.HttpClient`, subclassed) | `GenerateBackedHttpClient extends HttpClient` — package-private (no `public`), `java/src/main/java/io/github/muthuishere/toolnexus/InProcess.java:170`. Java's own doc calls this "the real tax in this port — a 94-line `HttpClient` subclass" |
| **C#** | `AgentTypes.cs` → `HttpMessageHandler? Handler { get; set; }` | `csharp/src/Toolnexus/Agents/AgentTypes.cs:124` | HTTP (`HttpMessageHandler`, subclassed) | `GenerateBackedHandler : HttpMessageHandler` — `private sealed`, `csharp/src/Toolnexus/InProcess.cs:115` |
| **Elixir** | `create_runtime(opts)` → `opts[:transport]`, doc'd as "the first-class injectable **HTTP transport** for the LLM path" | `elixir/lib/toolnexus/agents/runtime.ex:71` (doc at client.ex:324) | Wire (`fn req -> response`, but the `req`/`response` shapes are `%{url, headers, body}` wire maps, not messages/tools) | `in_process_transport/1` — `defp` (private), `elixir/lib/toolnexus/client.ex:731` |
| **Clojure** | `create-runtime` → `:http-client`, doc'd as "the LLM transport (fn [url headers body] response)" | `clojure/src/toolnexus/agents/runtime.cljc:358`; wiring at `:763` | Wire (same shape as Elixir: `[url headers body] -> response`) | `in-process-http-client` — `defn-` (private), `clojure/src/toolnexus/client.cljc:200` |

**Verdict on gate item 1: FALSIFIED as stated — but in the opposite direction the ADR
worried about.** The ADR asked "if most ports already have it, this is a Go bug, not a
contract change." None of the seven ports has it. Every single port's agent runtime accepts
only a wire/HTTP-shaped transport (`Transport`/`fetch`/`transport`/`HttpClient`/`Handler`/
`:transport`/`:http-client`), and every port's own `CreateInProcessClient`-equivalent
privately builds a wire adapter around `generate` to satisfy that same wire-shaped seam
(`inProcessRoundTripper` / the JS closure / `_InProcessTransport` / `GenerateBackedHttpClient`
/ `GenerateBackedHandler` / `in_process_transport/1` / `in-process-http-client`) — and in
**six of seven ports that adapter is private/unexported**, so a host that adopted the
in-process seam for its top-level client and then wants sub-agents has to re-implement it
in **every** port, not just Go. (Java's is merely package-scoped rather than fully
unexported, but a JVM host still cannot import it across packages/modules without help.)

This means: **this is not a Go bug to be sized alone** — it is the same shaped gap,
independently reproduced, in all seven ports. The fix belongs at the spec/contract level
(an OpenSpec change touching all seven `tasks.md` parity checklists), exactly the class of
change `CLAUDE.md` says needs one before code. ADR 0024's own decision (prefer shape 2,
expressed as one semantic field, shape 1 as Go-local convenience) is validated by this
audit, not undermined by it.

## Gate item 2 — smallest thing that works, one `Generate` for both paths, no copied code

**What I ran** (verbatim commands and output below). Summary: I could **not** make this
work using only the code that ships today — `CreateInProcessClient`'s `http.Client` and its
`Transport` field are buried behind unexported fields on `toolnexus.Client` (`http`, `opts`
in `golang/client.go:415-424`), so there is no way to pull the already-built round tripper
back out and hand it to `agents.Options.Transport` without either (a) reflection, or
(b) re-implementing the wire assembly — the exact copy the ADR is about.

So the smallest thing that actually works is genuinely **shape 1**: export the round
tripper as `toolnexus.InProcessTransport(generate) http.RoundTripper`, and make
`CreateInProcessClient` its caller (not a second copy). I applied that as a **temporary,
reverted** patch to prove it end-to-end, then rolled it back — production code is untouched
by this spike; see step 2 and step 5 below.

### 1. Confirm production baseline builds clean

```
cd golang && go build ./...
```
→ no output (clean).

### 2. Apply the minimal shape-1 patch to `golang/inprocess.go`

Diff (saved at `spikes/inprocess-subagent/inprocess-shape1.patch`):

```diff
--- a/golang/inprocess.go
+++ b/golang/inprocess.go
@@ -173,6 +173,15 @@ func encodeArgs(v any) string {
 	}
 }
 
+// InProcessTransport builds the SAME round tripper CreateInProcessClient uses,
+// as a plain http.RoundTripper — for a host that needs to hand a sub-agent
+// runtime (agents.Options.Transport) the identical in-process model a
+// top-level client was built with, without re-implementing the wire assembly.
+// SPIKE (issue #95 / ADR 0024): not yet a committed export.
+func InProcessTransport(generate func(InProcessRequest) (InProcessResponse, error)) http.RoundTripper {
+	return &inProcessRoundTripper{generate: generate}
+}
+
 // CreateInProcessClient builds a client backed by a model running IN THIS PROCESS —
 // no server, no socket, and no HTTP types to construct.
 //
@@ -204,7 +213,7 @@ func CreateInProcessClient(opts InProcessOptions) *Client {
 		// An in-process model has no endpoint to authenticate to, so the host must never need a key — but the client resolves one from the environment and fails when it finds none. A sentinel keeps that resolution from ever running. Caught by CI, which has no OPENROUTER_API_KEY; every local run passed because a developer shell has one.
 		APIKey:        "in-process",
 		Model:         opts.Model,
-		HTTPClient:    &http.Client{Transport: &inProcessRoundTripper{generate: opts.Generate}},
+		HTTPClient:    &http.Client{Transport: InProcessTransport(opts.Generate)},
 		SystemPrompt:  opts.SystemPrompt,
 		MaxTurns:      opts.MaxTurns,
 		Hooks:         opts.Hooks,
```

`inProcessRoundTripper` itself is untouched — this is purely an export, zero
duplicated logic. `go build ./...` after the patch: clean.

### 3. Spike module + test, wiring ONE `generate` into both paths

`spikes/inprocess-subagent/go.mod` uses a `replace` directive onto `../../golang`
(the patched tree) so it is a real, separately-built consumer, not code smuggled into
the `golang` module itself:

```
module toolnexus/spikes/inprocess-subagent
go 1.23
require github.com/muthuishere/toolnexus/golang v0.0.0
replace github.com/muthuishere/toolnexus/golang => ../../golang
```

`generate_shared_test.go` defines one `scriptedModel.generate` (a fake model —
`InProcessRequest -> InProcessResponse`, no HTTP) and drives it through:

- `TestSharedGenerate_TopLevelClient` — `tn.CreateInProcessClient(tn.InProcessOptions{Generate: m.generate})`, the reporter's existing top-level case.
- `TestSharedGenerate_SubAgentRuntime` — `agents.NewRuntime(agents.Options{Transport: tn.InProcessTransport(m.generate)})`, the reporter's missing case — using the exported round tripper, **not** a re-implementation.

### 4. Run it

```
cd spikes/inprocess-subagent && go mod tidy && go vet ./... && go test ./... -race -count=1 -v
```

Verbatim output:

```
=== RUN   TestSharedGenerate_TopLevelClient
    generate_shared_test.go:77: top-level client result: {Text:ok model=spike-model turn-done Messages:[map[content:hello role:user] map[content:ok model=spike-model turn-done role:assistant]] ToolCalls:[] ToolCallCount:0 Turns:1 Usage:{PromptTokens:0 CompletionTokens:0 TotalTokens:0} Model:spike-model Status:done Limit: Pending:<nil>}
--- PASS: TestSharedGenerate_TopLevelClient (0.00s)
=== RUN   TestSharedGenerate_SubAgentRuntime
    generate_shared_test.go:101: sub-agent result: {Text:ok model= turn-done IsError:false Status:done Pending:<nil> Turns:1 TotalTokens:0}, calls=1
--- PASS: TestSharedGenerate_SubAgentRuntime (0.00s)
=== RUN   TestGateHolds_ConcurrencyOne
    generate_shared_test.go:145: gate holds: calls=5 maxSeen=1 overlaps=0 rt.MaxObservedConcurrentTurns=1
--- PASS: TestGateHolds_ConcurrencyOne (0.08s)
=== RUN   TestGateControl_WouldCatchABypass
    generate_shared_test.go:184: control confirms detector is live: calls=5 maxSeen=5 overlaps=4
--- PASS: TestGateControl_WouldCatchABypass (0.02s)
PASS
ok  	toolnexus/spikes/inprocess-subagent	1.611s
```

(`go vet ./...` and the `-race` run were both silent/clean apart from the PASS lines above.)

### 5. Revert the production patch

```
git checkout -- golang/inprocess.go
git status --short golang/     # → no output, clean
```

`golang/inprocess.go` is back to its shipped state. `generate_shared_test.go` and this
`SPIKE.md` are the only durable artifacts, both under `spikes/inprocess-subagent/`. The
test file will **not build** against the unpatched tree (`InProcessTransport` doesn't
exist there) — that failure to compile is itself the falsification evidence: absent the
export, there genuinely is no way to share one `generate` across both paths without
copying `inProcessRoundTripper`. `spikes/inprocess-subagent/inprocess-shape1.patch`
re-applies the exact 10-line change if someone wants to re-run this.

**Verdict on gate item 2: HOLDS, but only after adding the export** — confirming the ADR's
premise (shape 1, at minimum, is necessary) and confirming it is genuinely the *smallest*
fix: 10 lines, zero duplicated logic, `CreateInProcessClient` becomes a one-line caller of
the new export.

## Gate item 3 — does the global turn gate still apply on the new path?

`gatedTransport` (`golang/agents/runtime.go:872-901`) wraps `rt.opts.Transport` — and
`agents.Options.Transport` is exactly the field `tn.InProcessTransport(generate)` was handed
to in step 3 above, so the gate wraps it by construction, with **no new code path**. The
spike proves this isn't just "should," with a real concurrency assertion:

- `TestGateHolds_ConcurrencyOne`: `MaxConcurrentTurns: 1`, 5 workers spawned and woken
  concurrently, each call to `generate` holds for 15ms and records (via an atomic
  in-flight counter) whether it ever observed a second call in flight. Result:
  `overlaps=0`, `rt.MaxObservedConcurrentTurns()==1` — the gate held.
- `TestGateControl_WouldCatchABypass` is the **negative control** the gate item asked
  for ("write a test that would FAIL if the gate were bypassed"): same 5 workers, same
  15ms hold, but `MaxConcurrentTurns: 5` (i.e. the gate effectively open). Result:
  `overlaps=4` — the detector genuinely fires when concurrency isn't constrained, which
  is what makes `TestGateHolds_ConcurrencyOne`'s `overlaps=0` meaningful evidence rather
  than a tautology.

**Verdict on gate item 3: HOLDS.** The global turn gate applies to the in-process path with
zero changes to `runtime.go`, because `gatedTransport` gates *whatever* `Options.Transport`
is — it has no idea, and needs no idea, that the underlying round tripper is in-process. The
control test rules out "the assertion would pass no matter what."

## Gate item 4 — are the reporter's 3 shapes expressible in the other six ports?

| Shape | Go | JS | Python | Java | C# | Elixir | Clojure |
|---|---|---|---|---|---|---|---|
| **(1) Export the round tripper** — a named, importable wire-adapter built from `generate` | Yes — `http.RoundTripper` (this spike) | Yes — a `typeof fetch`-shaped function, no class needed | Yes — an `HttpTransport` (already a Protocol with `.post`/`.open`) | Yes — Java **already builds** `GenerateBackedHttpClient extends HttpClient` privately; exporting it is the same move | Yes — C# **already builds** `GenerateBackedHandler : HttpMessageHandler` privately; same move | Yes, and *more* trivially — `:transport` is already a bare `fn req -> response`, no wrapper type to design | Yes, same as Elixir — `:http-client` is a bare `(fn [url headers body] response)` |
| **(2) `agents.Options.InProcess`** — a third, semantic, mutually-exclusive field | Yes (proposed) | Yes — `RuntimeOptions.inProcess?` | Yes — a new constructor kwarg | Yes — `RuntimeOptions.inProcess(...)` builder method, same pattern as the other setters | Yes — a new `AgentTypes` property | Yes, and cheaper than the other 6 — keyword lists don't need a new type, just a new key + a runtime-construction validation | Yes, same as Elixir — a new map key |
| **(3) `client.Transport()` getter off the built client** | Technically yes, but `Client.http`/`Client.opts` are unexported today (`golang/client.go:415-424`) — needs a new accessor either way, so no cheaper than (1) | Client doesn't retain its `fetch` as a queryable property today either | Same — `Client` doesn't expose `transport` as a public attribute today | Same — `LlmClient` doesn't expose its `HttpClient` | Same — `LlmClient` doesn't expose its `HttpMessageHandler` | Feasible (client is a map internally) but exposes an internal implementation key | Feasible (client is a map internally), same caveat |

**The ADR's exact objection** — "(1)/(3) hand back an HTTP type … four ports have no such
type to hand back" — **is not quite right as stated**. Every port already has an analogous
wire/transport type (`http.RoundTripper` / `fetch` / `HttpTransport` / `HttpClient` /
`HttpMessageHandler` / `:transport` fn / `:http-client` fn) and **already privately builds
an adapter of exactly that type** for its own in-process client (this is what the gate 1
audit found). So shape 1, generalized as "export whatever your port already builds
privately," is mechanically expressible everywhere — nothing here is Go-only.

What *is* real, and is the substance behind the ADR's objection even though the "four ports
have no such type" framing overstates it: the two ports with the heaviest shape-1 tax are
Java and C#, where the exported type is a genuine platform HTTP class (`java.net.http.HttpClient`,
`System.Net.Http.HttpMessageHandler`) — heavier and more ceremony-laden than Go's one-method
interface, Python's Protocol, JS's bare function, or Elixir/Clojure's bare function. Java's own
doc for `InProcess.java` calls the internal adapter "a 94-line `HttpClient` subclass" — that
tax exists whether or not it's exported; exporting it doesn't add ceremony, but it doesn't
remove any either. Elixir and Clojure are the opposite extreme: their `:transport`/`:http-client`
seam was never HTTP-typed to begin with (plain `fn req -> response`), so shape 1 there is
strictly smaller than in any OOP port.

Shape 3 (`client.Transport()`) is the weakest of the three **everywhere**, not just in four
ports: in all seven, the built client does not retain its transport as a public, queryable
property today, so shape 3 requires the same new-accessor work as shape 1 in every port, for
no reduction in HTTP-typedness — it is strictly dominated by shape 1.

**Verdict on gate item 4: shape 1 and shape 2 are both expressible in all seven ports; shape
3 adds accessor work everywhere for no benefit over shape 1, in any port.** No shape here is a
parity hazard that only Go can express — the ADR's decision to prefer (2) with (1) as a
Go-local convenience is sound, and per this audit (1) is *also* available as a same-shaped
per-port convenience, not a Go-only one.

## Recommended shape

Confirms ADR 0024's proposed decision, refined by this audit:

1. **Cross-language contract (goes in `SPEC.md` / an OpenSpec change):** every port's agent
   runtime options gain a semantic in-process field — Go `Options.InProcess`, JS
   `RuntimeOptions.inProcess`, Python a constructor kwarg, Java/C# a builder
   method/property, Elixir/Clojure a new options key — carrying `generate` (+ `Model`) and
   nothing wire-shaped. Validate the field against the existing wire-shaped one
   (`Transport`/`fetch`/`transport`/`Handler`/`:transport`) at construction time and error
   loudly on both being set — never precedence rules, per the ADR.
2. **Per-port local convenience (not part of the cross-language contract, so it does NOT
   need to land in all seven to ship):** export the wire adapter that each port's
   `CreateInProcessClient` already privately builds, the way this spike exported
   `InProcessTransport` in Go. This is what actually kills the "re-implement 90 lines"
   complaint for hosts that want to reach for the lower-level seam directly (e.g. composing
   the in-process transport with something else that wants a `RoundTripper`/`fetch`/
   `HttpMessageHandler`), and it is close to free in every port because the adapter already
   exists, unexported, everywhere.
3. Do **not** add shape 3 (`client.Transport()`) anywhere — it is dominated by shape 1 in
   every port audited here (same missing-accessor work, no reduction in HTTP-typedness).
4. Because gate item 1 shows this is a **seven-port gap**, this is not a Go-only PR — it
   needs an OpenSpec change with a per-language parity checklist (`js`/`python`/`golang`/
   `java`/`csharp`/`elixir`/`clojure`, per `CLAUDE.md`'s workflow), and `SPEC.md` should gain
   a line under whatever section documents the agent runtime's Options obligations, stating
   that an in-process/semantic model configuration is a required alternative to the wire
   transport, not a Go-specific nicety.
