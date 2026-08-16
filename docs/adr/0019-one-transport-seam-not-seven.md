# ADR 0019 — one transport seam, not seven: an interface whose **default implementation is today's client**

- **Status:** **Proposed — REVISED 2026-08-16 after four spikes.** The first draft was not spiked and
  said so. It has now been attacked by an analyst, an adversarial reviewer, and two implementation
  spikes (Go, and js+python). **Its headline justification did not survive, its signature did not
  survive, and its shape did not survive.** What survives is the underlying problem and a smaller,
  additive fix. Everything below is rewritten on that evidence; the spikes are re-runnable and cited
  inline.
- **Date:** 2026-08-16
- **Driver:** a consumer (modelnexus) wanting toolnexus to talk to an **in-process** model with no
  socket.
- **Supersedes:** the `invoke(request_body) -> response_body` draft — **partially UN-rejected**, see
  correction 3. The evidence now favours body-in/body-out over bytes-in/bytes-out.

## Corrections to the previous draft

Each of these is falsifiable, and each was falsified.

**1. The testing justification is dead, and was inverted.** The draft claimed hermetic in-memory
testing exists only in Go and Java. Five of seven ports ship in-memory transport fakes **in their own
suites today**: `js/test/unit.test.ts:1898` (a test literally named *"gap2: injectable fetch receives
the LLM call"*), `python/tests/_agent_mocks.py:94`, `csharp/…/AgentTestSupport.cs:65`,
`elixir/test/support/agent_mock.ex:26`, `clojure/…/runtime_test.cljc:60`. Meanwhile **Java — listed
as a have — is the one port that genuinely cannot**: its own Gap-2 test proxies to a live server
(`java/…/RagConsumerTest.java:183-190`), and every Java LLM stub is a loopback `HttpServer`
(`java/…/agents/MockLlm.java:46`). The draft's strongest-sounding paragraph was the one a reviewer
could disprove in one grep. **Deleted, not softened.**

**2. The repo already ruled on the divergence, in CI, and the draft never cited it.**
`conformance/options_manifest.json:2` states plainly: *"Aliases cover legitimate per-port idioms (JS
fetch = Python http_transport = Go HTTPClient = the one logical 'http client' option)"*, enforced by
`check_options_parity.py`. `SPEC.md §0`'s conformance contract does not mention the transport at all.
Seven spellings are therefore **not** a parity breach by this repo's own definition — they are the
design. Any proposal to change that owes the manifest an argument.

This also kills the draft's shape: shipping `Transport` **alongside** the natives forces either a
second manifest row (two logical options for one job — the drift the manifest exists to prevent) or a
sixth alias, under which **a port shipping only one of the two still passes CI**. See the Decision
for the shape that avoids this.

**3. Bytes-in/bytes-out is the wrong signature — it makes the driving use case worse.** Measured
(`scratchpad/adr19-jspy/`): Python's shipped seam hands the transport an **already-parsed dict** and
takes a dict back — zero serialization. A bytes-shaped `Transport` would force a JSON round-trip
*introduced purely to satisfy a shape borrowed from HTTP*, for a model that has no wire. Elixir is the
same (`client.ex:890-903`, un-marshalled map). **The draft would have made the two most ergonomic
ports worse in order to make Go's tidier.**

**4. `ctx` is missing from the signature, and its absence is not cosmetic.** `Transport :=
(TransportRequest) -> TransportResponse` makes cancellation *impossible* — there is nothing to cancel
through. Demonstrated in the Go spike: a transport that ignores cancellation defeats `Timeout`
entirely — **601 ms elapsed against a 100 ms deadline, no error**. The concrete `*http.Client`
enforced that for you; an interface cannot unless the client races `ctx.Done()` itself.

**5. `headers: map<string,string>` is lossy exactly where retries live.** Go's `http.Header.Get`
canonicalises; a plain map does not. The Go spike's transport returned `retry-after` lowercase and
**`Retry-After` honouring silently stopped working** — no existing test catches it. Multi-value
headers (`Set-Cookie`) are also unrepresentable.

**6. The JS row is wrong.** `TransportResponse.body` cannot be an `AsyncIterable<Uint8Array>` in JS:
`sseLines` calls `body.getReader()` (`js/src/client.ts:1097`), so it must be a real `ReadableStream`.
`ReadableStream.from(gen)` bridges it in one line on Node ≥ 20.

**7. The streaming risk is worse than stated, and is now reproduced twice.** A buffered transport
produced **the same delta count and the same final text** as a streaming one — Go spike: 0 ms spread
vs 287 ms; python spike: `arrivalMs=[204,204,204,204,204]` vs `[45,86,131,172,213]`. A conformance
test asserting on content or count **cannot** detect a buffered-body regression. Two ports are
already in that state: Elixir buffers the whole SSE body (`client.ex:974`) and Clojure has no
streaming loop at all (`client.cljc:673`).

## What survives, and is the real case

- **The seven signatures genuinely are incompatible** — concrete struct, concrete JDK class, two
  concrete types, a Web-API function, a two-method Protocol, a 1-arg function, a 3-arg function. No
  single portable fake, and **no single cookbook page**, can be written. That is the honest case.
- **Java cannot do this cleanly** — the one port with a real capability gap.
- **The in-process model** — the case that prompted the ADR.
- **TypeScript hosts must fabricate HTTP semantics.** Confirmed, not argued: a duck-typed response
  runs fine but fails to typecheck — `TS2322: missing redirected, statusText, type, url, and 7 more`.
  Priced at **10 µs and one line per call**, so it is a correctness-of-API complaint, not a
  performance one.

## Context

### The seam is not missing. It is shipped seven times.

`SPEC.md §8 Gap 2` says the LLM path takes an injectable HTTP transport. Every port implements it.
No two implement the same thing:

| port | how §8 Gap 2 is spelled | what the host must supply |
|---|---|---|
| Go | `HTTPClient *http.Client` (`golang/client.go:91`) | a **concrete stdlib struct** |
| Java | `public HttpClient httpClient` (`java/…/LlmClient.java:96`) | a **concrete JDK class** |
| C# | `HttpClient` *or* `HttpMessageHandler` (`csharp/src/Toolnexus/LlmClient.cs:197,204`) | either of two concrete types |
| JS | `fetch?: typeof fetch` (`js/src/client.ts:59`) | a **Web-API function** |
| Python | `HttpTransport` Protocol — `post() -> dict`, `open() -> stream` (`python/src/toolnexus/client.py:231`) | a **two-method object** |
| Elixir | `:transport` — `(request -> {:ok, response} \| {:error, e})` (`elixir/lib/toolnexus/client.ex:284`) | a **one-arg function**, body as an *un-marshalled map* |
| Clojure | `:http-client` — `(url headers body) -> response` (`clojure/src/toolnexus/client.cljc:313`) | a **three-arg function**, koine-shaped |

Each is individually defensible and idiomatic — the parity rule has always been *same behaviour,
native shape*. But this is not native shape. **This is seven different behaviours**, and the
divergence is load-bearing:

- **Go, Java and C# cannot express an in-process transport without lying.** All three demand a
  concrete class whose job is to open sockets. You get there by fabricating an `http.Response` /
  `HttpResponse<T>` / subclassing `HttpMessageHandler`. Go is the least bad — `http.RoundTripper`
  is a real interface, and `golang/rag_consumer_test.go:147` already exploits it — but a JDK
  `HttpClient` has no interceptor at all.
- **Elixir hands the transport an un-marshalled body map; everyone else hands it bytes.** One port
  JSON-encodes inside the seam, six encode outside it. That is not a naming difference.
- **Python's seam is two methods** because streaming and non-streaming were split at the port level
  rather than at the response.
- **The cookbook cannot be written.** Any page documenting "route toolnexus somewhere else" reads
  *in Go build a RoundTripper, in Java subclass a final-ish class, in JS pass a fetch, in Python
  implement two methods, in Elixir a function taking a map, in Clojure a function taking three
  positionals.* Seven recipes for one idea is the tell that the seam is in the wrong place.

### What the host is actually trying to say

In every one of those seven spellings, the host writes the same sentence: **take this request, give
me back that response.** Everything else is the language's HTTP furniture.

That sentence is portable. The furniture is not.

## Decision

**One logical option. The interface is the seam; today's native client is its DEFAULT
IMPLEMENTATION.** Not a second option beside it.

```
        Transport                      ← the seam (ONE logical option)
           |
           +-- default impl per port   ← wraps http.Client / HttpClient / fetch / Req / …
                  |
                  +-- httpClient(x)    ← existing sugar; constructs the default impl
```

This is what keeps `conformance/options_manifest.json` intact: still one logical option, now with two
constructors. No second row, no sixth alias, no port able to ship half of it and pass CI. And nothing
is taken away — `httpClient(proxied)`, `fetch:`, `:transport` and the
[bring-your-own-http-client](https://muthuishere.github.io/toolnexus/cookbook/bring-your-own-http-client/)
cookbook page all keep working verbatim.

It also inverts the direction of travel, correctly: **JS's `fetch` and Elixir's function already ARE
the interface.** The ports with a problem are the three handing you a concrete class. Converge Go,
Java and C# *toward* the other four — not the other four toward HTTP.

### The signature

**Body-in / body-out — not bytes.** This is the draft's rejected `invoke(body) -> body`, un-rejected
on evidence (correction 3).

```
Transport := (ctx, TransportRequest) -> TransportResponse

TransportRequest  = { url, method, headers, body: <parsed request>, stream: bool }
TransportResponse = { status, headers, body: <parsed response> | <byte stream> }
```

Five rules, each traceable to a spike:

1. **`ctx` is a parameter, not optional.** Spelled per language: `context.Context` / `CancellationToken`
   / `AbortSignal` / `asyncio` cancellation. Elixir has no equivalent and needs an explicit answer —
   that is an open question, not a detail (correction 4).
2. **The body crosses parsed, both ways.** An in-process model returns a map/dict/object and pays no
   serialization. A proxy transport that wants bytes marshals them itself — the cost lands on the case
   that actually has a wire.
3. **A streaming response MAY return a byte stream instead**, and `stream: true` in the request says
   which is expected. Python's `post`/`open` split collapses to one method losing nothing: the payload
   **already** carries `stream` (measured: `[('post', None), ('open', True)]`).
4. **Header lookup is case-insensitive**, normatively. Multi-value headers are explicitly out of scope
   and `Set-Cookie` is unrepresentable — say so rather than discover it (correction 5).
5. **Absent ⇒ byte-identical.** The default implementation is today's client; a host that sets nothing
   gets exactly today's bytes.

### Do these regardless of whether the interface ships

Both are bugs found while spiking, and neither depends on this ADR:

- **Export Python's `_HttpError`.** `HttpTransport`'s own docstring instructs hosts to raise it, and it
  is private and absent from `__all__` — a public seam requiring a private import.
- **Add a TIMING-based streaming conformance test to every port.** Content and delta-count assertions
  provably cannot catch a buffered body (correction 7). `scratchpad/adr19-jspy/py/02_streaming.py`
  variant B is a ready-made shape.

### Scope: what this does NOT cover

The seam is the **LLM path only** — verified in all seven ports against `SPEC.md:1221-1222`; MCP
transports are separately constructed everywhere (e.g. `csharp/…/McpSource.cs:448-464`). And in JS,
`a2a.ts:170`, `http.ts:83` and `builtin.ts:402` call global `fetch` directly, so the A2A source, the
`http` tool and the `webfetch` builtin bypass it. **"Test the whole library offline" is not what this
buys**, and the draft implied otherwise.

## The gate this must pass before Accepted

The draft set four criteria. Three are now met, one is not, and one new one is added:

| gate | status |
|---|---|
| a full agent run over a socket-free transport | **met** — Go spike, tripwire armed, `sockets opened: 0` |
| streaming proven incremental, by timing | **met** — Go `spread=287ms`; python `[45,86,131,172,213]` |
| transport-reported 429 indistinguishable from HTTP 429 to the retry classifier | **met** — both spikes |
| the same shape implemented in a second port | **met** — js + python, ~20-line shim each |
| **NEW: a named consumer who tried and failed** | **NOT MET** |

That last one is the honest blocker. Every argument in this ADR is the library talking about itself.
The one thing that would most change its standing is a host that hit a wall the existing seam could
not clear — an Elixir or Python user blocked by the map-vs-bytes body, or a Java user who tried the
subclass and gave up. Until then this is a **uniformity and ergonomics** change, and should be sized
and argued as one.


## Alternatives rejected

### 1. `invoke(request_body) -> response_body` — the first draft of this ADR

A single callback taking the built provider body and returning the provider response body. Smaller
and cleaner *for the in-process case*, and it is what I originally proposed.

Rejected because it solves one case and leaves the divergence table standing. It cannot express a
corporate proxy, mTLS, per-request credential refresh, record-replay tests or offline CI — all of
which are today's `HTTPClient` use cases in Go and Java, and are unavailable in the other five
ports. `Transport` subsumes `invoke`: `invoke` becomes a five-line helper over it, or nothing.

### 2. Do nothing; document the round-tripper trick

Costs zero. In **Go** it genuinely works today: `HTTPClient` accepts any `Transport`, so a
`RoundTripper` that never opens a socket is a supported use of shipped API.

Rejected because Go is the only port where it is clean, and "cheapest for the library" is the wrong
objective when the cost lands on seven cookbook pages and every host that isn't writing Go.

### 3. Generalise each port's own HTTP client to that language's interface

`http.RoundTripper`, `HttpMessageHandler`, an httpx transport, and so on. Idiomatic per port and
requires no new types.

Rejected: it is the status quo with better manners. It keeps HTTP status codes, header multimaps and
response readers in a code path that may contain no HTTP, and it entrenches the divergence rather
than closing it. Java has no such interface to generalise to.

### 4. A semantic callback — messages and tools in, a message out

Rejected outright. It duplicates the adapter layer, forces every host to reimplement per-provider
mapping toolnexus already owns, and makes `Style` meaningless.

## Consequences

**Cost.** Seven ports × (one type + two records + one call-site swap) plus a spec delta modifying
§8 Gap 2. The call site is a single line per port — `golang/client.go:708` is representative.
Elixir and Clojure are nearly there already and mostly need reshaping; Python needs its two methods
collapsed into one; Go, Java and C# need the genuinely new type.

**Benefit, beyond the driver.** Five ports gain proxy support, credential injection, request
logging, record-replay and hermetic offline tests that today only Go and Java have.

**Risk — the one to watch.** Streaming. A port that implements `Transport` with a buffered body
passes every existing test while quietly destroying streaming. Conformance must assert *incremental
arrival*, not final content.

**Migration.** Additive. Absent a `Transport`, every port is byte-identical to today.

## Prior art, checked rather than assumed

Vercel's AI SDK ships a **versioned Language Model Specification** — now v3, and bumping it
forced a major release. With 25+ providers on that spec, interoperating with LangChain still
required a dedicated `@ai-sdk/langchain` adapter package with its own conversion functions.

Two lessons, both taken here:

1. **A small versioned spec beats a rich interface.** Version this contract from the start.
   Vercel did not, and paid with a major bump.
2. **Adapters are a package, not a feature.** Nobody has avoided that, so do not design around
   trying to. The glue that makes a specific provider satisfy this `Transport` belongs in a
   third place — an example, or a package that depends on both — never inside this library.

## Sibling decision

CiteNexus has the same problem in a different vocabulary: its model seam is specified as an
*OpenAI-compatible endpoint*, its embedder exists in six incompatible shapes across three
ports, its Go seam cannot report failure, and its JS seam is synchronous and therefore
un-satisfiable by anything that touches a network. See
`rag-cite-nexus/docs/adr/0014-the-model-seam-is-a-contract-not-an-endpoint.md`.

The two ADRs share one requirement, and it is the load-bearing one: **the seam must not assume
a transport.** Once it does, HTTP stops being one implementation and becomes the shape
everything must fit.

## What would earn this an "Accepted"

The same standard ADR 0017 met — runnable evidence, not argument:

1. **Two ports, deliberately far apart.** Go (new interface, concrete-struct incumbent) and Python
   (two-method Protocol collapsing to one). If the shape survives both, it survives.
2. **A full tool-calling turn with the network unreachable** — not merely unused. The transport
   answers in-process; the test fails if a socket is opened.
3. **A streaming turn proving deltas arrive incrementally**, asserting on arrival *timing or
   count*, not on the concatenated result. This is the half a bytes-shaped interface would have
   shipped broken.
4. **Retries and `OnError` exercised through the transport** — a transport-reported 429 must
   produce the same retry behaviour as an HTTP 429.

Until those exist, this stays **Proposed**.
