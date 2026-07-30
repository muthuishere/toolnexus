# ADR 0011 — Single-turn translation: toolnexus as a pure wire-format translator

**Status:** ACCEPTED (2026-07-30) — Go implemented and green; five ports to follow.
**Date:** 2026-07-30
**Supersedes for the pass-through case:** ADR-0010's relay path (which remains correct for
proxy-managed memory)
**Consumer:** routsi ADR-010 items 1 + 2 (confirmed by the routsi desk, 2026-07-30)
**Affects:** `SPEC.md` §11 (new) · all six ports

## Context

ADR-0010 added relay tools + durable resume so a proxy could pass client-executed function
calling through the agent loop. It works, and Go shipped. But the owner asked the right
question — *can the consumer build this on the primitive as-is instead?* — and pursuing it
found that **the loop was the wrong tool for the consumer's main path**.

The decisive facts, all verified rather than assumed:

1. **The consumer's main path is already stateless.** routsi's own code says so: "the OpenAI
   client resends full history each request, so we pass `req.Messages` as history verbatim
   instead of toolnexus's own conversation store." The OpenAI protocol hands a proxy the
   complete `messages[]` — *including prior tool results* — on every request. There is no
   conversation to hold, no run to park, no suspension to resume.
2. **The in-process trampoline works but is structurally wrong for it.** Measured
   (`golang/relay_test.go`): a host *can* park `waitFor` across HTTP turns and N relay calls
   do produce N concurrent callbacks. But that makes a stateless need stateful — a goroutine
   per conversation, TTL/eviction, death on restart, no multi-instance — and *invents* the
   abandoned-conversation poisoning the consumer was worried about.
3. **A translator needs three translations, and only one shipped.** Declarations existed
   (`adapters.go:41,57,70`). Inbound message translation and outbound response translation
   did **not** — `SPEC.md §0` item 7 pins the adapters as *schema only*, and there was no
   response-side translator anywhere in the library.
4. **The consumer's flattening bug proves the gap is real.** routsi's `split()` built
   `{role, content: m.Text()}` per message, so a `tool`-role result lost its
   `tool_call_id` and an assistant turn's `tool_calls` vanished. Multi-turn tool use could
   not survive that *regardless of what the loop did*.

The routsi desk confirmed all of this, dropped its parking prototype, and asked for this
shape explicitly.

## Is this general, or a consumer-shaped bolt-on?

The owner's condition was explicit: build it **only if it is useful for all**, make the
generality case in the ADR rather than treating the consumer as justification, and treat
"reject as too narrow" as a live option. Taken seriously, here is the assessment.

**Writing it exposed one genuine bolt-on, now fixed.** The first cut accepted only OpenAI
JSON `tools[]` — which serves a proxy holding a client's request body, and *nobody else*. A
toolnexus user whose tools come from MCP servers, skills, native functions or A2A agents
could not use it at all. That is the definition of consumer-shaped. `TranslateRequest` now
also accepts a `*Toolkit`, so the capability reads:

> declare **any** toolkit to **any** supported provider, and get the model's tool calls
> handed back to **you** to dispatch — instead of the agent loop running them.

That is a library-level capability, and it is the one the tests now assert
(`TestTranslateDeclaresAToolkitWithoutExecutingIt`).

**What holds up:**

1. **It completes an asymmetry already shipped in public API.** `SPEC.md §0` item 7 pins the
   adapters as *schema only*: `ToOpenAI`/`ToAnthropic`/`ToGemini` translate declarations
   **outbound**, and nothing reads provider tool calls back **inbound**. Any user of those
   public functions hits the same wall, with or without this consumer. Shipping a one-way
   translator and calling it finished is the actual defect; this is not new scope, it is the
   missing half. **This argument alone is sufficient**, and it is why I did not decline.
2. **It serves the majority posture the library currently ignores.** "I want provider-portable
   tool calling, but *I* execute the tools" is the default stance of every OpenAI SDK user and
   the premise of the whole function-calling protocol. toolnexus serves the opposite posture
   (library executes, in a loop) very well and this one not at all. A tool-unification library
   that only supports delegated execution is narrower than its own thesis.
3. **It is additive and cheap** — no §10, no durable resume, no concurrency contract, no
   four-loops risk. Measurably smaller than the relay work already built.

**What I am NOT claiming.** The consumer also argued this "widens the audience to gateways,
proxies and evaluation harnesses." That may be true but it is a speculative market claim, not
evidence, and it is not load-bearing here. Recorded as unproven rather than used as
justification.

**The strongest argument against, stated fairly.** The library's own pitch is "zero to agent
in three steps" — an agent framework. `Translate` is not agent-building; it is a translation
utility, and there is a real risk of the library drifting into "agent framework *and* LLM
gateway SDK", two products in one repo, six times over. The reason it survives that objection
is point 1: the adapters are *already* public and *already* one-way. Completing a shipped
round trip is maintenance of the existing surface, not a second product. Had the adapters not
existed, the right answer would have been to decline.

**Verdict: general. Accepted** — framed as completing the adapter round trip, and explicitly
NOT as a gateway SDK. If it ever starts growing proxy-specific surface (routing, auth
brokering, envelope assembly), that is the signal it has drifted and should be reconsidered.

## Decision

Add a **single-turn translation entry point**: OpenAI shapes in, exactly one provider call,
OpenAI shapes out. No agent loop, no tool execution, no conversation state.

```
translate(request) -> result
  request: { messages, tools?, toolkit?, toolChoice?, system?, maxTokens? }
  result:  { text, toolCalls[{id,name,arguments}], finishReason, usage, model, raw }
```

`messages`/`tools`/`toolChoice` are OpenAI shapes taken verbatim; `toolkit` declares an
ordinary toolkit (MCP, skills, native, A2A, builtins) without executing any of it. The two
tool sources compose.

1. **Nothing executes, ever.** There is no execution path in this entry point at all — a
   `toolkit` passed here is *declared*, never run. That is a property of the design rather
   than of configuration: a proxy needs no `Builtins:false` discipline and has nothing to
   misconfigure, and a toolkit owner can reuse the toolkit they already have without the
   loop touching it.
2. **`arguments` is a JSON *string*,** matching the OpenAI wire format, so a proxy can hand
   it to a conforming client byte-for-byte.
3. **Provider knowledge stays in the library.** The caller hands over OpenAI shapes and
   never builds provider-native payloads. This was the consumer's explicit request, and it
   is why the entry point takes OpenAI `tools[]` rather than a `Toolkit`.
4. **Structure is preserved inbound, not flattened.** Assistant `tool_calls` become
   provider `tool_use` blocks (arguments re-parsed to objects); `tool`-role results become
   `tool_result` blocks keyed by `tool_call_id`, **merged into one user turn** when
   consecutive, as Anthropic requires; `system` messages are hoisted to the provider's
   separate field.
5. **Tool calls win the finish reason.** A turn emitting any tool call reports
   `finish_reason: "tool_calls"`; otherwise the provider stop reason maps onto
   `stop`/`length`/`content_filter`.
6. **Shares the loop's infrastructure.** Retries/backoff, request-param merging, and the LLM
   observability event are reused. `beforeLLM`/`afterLLM` fire once; tool hooks do not,
   because no tool runs.
7. **Additive.** Nothing in §10 or the loop is touched. No existing behavior changes.

## Consequences

- The consumer's pass-through path becomes **truly stateless** — horizontally scalable, no
  TTL, no restart loss, and the abandoned-conversation problem does not exist.
- **Relay is not wasted, it is scoped.** ADR-0010's relay + durable resume is the right
  machinery for *proxy-managed memory* (the consumer's Mode B, where the proxy owns the
  conversation and the client sends only the new message). Two modes, two mechanisms.
- The relay six-port push loses its urgency; this one is smaller and lands faster.
- New `SPEC.md` §11. Six-port obligation applies as always.

## Alternatives

- **Relay + durable resume for the pass-through case** (ADR-0010 as originally scoped).
  Rejected for this path: heavier, needs suspension semantics the path does not want, and
  four loops × six ports of risk for a need that has none of it.
- **In-process parking trampoline** (no library change). Rejected: stateful, restart-fragile,
  single-instance, and structurally wrong for a stateless protocol.
- **Standalone `(b)`/`(c)` helpers the caller assembles.** More composable, but pushes
  provider-shaped payload construction into every caller. The consumer explicitly preferred
  one call with the knowledge in one place. Rejected.
- **Reject as too narrow; let the consumer own the translation.** A live option under the
  owner's condition, and the right call if the only argument were "our consumer needs it."
  Rejected because of the asymmetry argument above: `ToOpenAI`/`ToAnthropic`/`ToGemini` are
  already public and already one-way, so *every* adapter user hits this wall. Declining
  would leave a shipped capability permanently half-built. If that asymmetry did not exist,
  this alternative would have won.
- **An `afterLLM` hook returning a sentinel error to abort the run after one call.** The
  consumer found this and declined it themselves — it abuses an error path for control flow
  and duplicates provider knowledge downstream. Rejected, and worth recording as rejected.

## Open questions

- Streaming: a streamed single-turn translate (SSE in, indexed tool-call deltas out) is the
  obvious follow-up. The consumer's envelope already emits indexed tool-call deltas, so this
  is wanted, but it is not needed for correctness first.
- Gemini: the client's styles are `openai`/`anthropic`; a Gemini-style upstream would need
  its own inbound/outbound mapping. `ToGemini` exists for declarations only.
