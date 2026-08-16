# ADR 0019 — §8 Gap 2 already exists **seven times, in seven shapes**; converge it into one `Transport`

- **Status:** **Proposed** (2026-08-16) — **not spiked.** ADR 0017 earned its status with four
  runnable spikes; this one has none, and says so. The gate it should pass is at the bottom.
- **Date:** 2026-08-16
- **Driver:** a consumer (modelnexus) wanting toolnexus to talk to an **in-process** model with no
  socket. Investigating that turned up a larger, pre-existing problem: the seam it needs is already
  shipped in all seven ports and no two ports agree on its shape.
- **Supersedes:** the first draft of this ADR, which proposed an `invoke(request_body) ->
  response_body` callback. That draft is rejected here, by me, on the evidence below.

## The short version

Replace the concrete HTTP client with an **interface anyone can implement — including a mock**.

That sentence is the whole change, and the mock is the part that matters most. One seam buys
five things, and today five of the seven ports have none of them:

| | who has it today |
|---|---|
| **hermetic tests — no network, no mock server, no fixture process** | Go, Java |
| corporate proxy / mTLS | Go, Java |
| record–replay fixtures | Go, Java |
| per-request credential refresh | Go, Java |
| an in-process model — no socket at all | nobody, cleanly |

The in-process case is what prompted this. The testing case is what justifies it: a library
owes its users the ability to test against it offline, and five of seven ports cannot.


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

**Introduce one toolnexus-owned `Transport` type, identical in shape across all seven ports, as the
normative spelling of §8 Gap 2.** Not the language's HTTP client — ours.

```
Transport := (TransportRequest) -> TransportResponse

TransportRequest  { url, method, headers: map<string,string>, body: bytes }
TransportResponse { status: int, headers: map<string,string>, body: <byte stream> }
```

Per-language spelling stays idiomatic — an interface in Go/Java/C#, a function in
Elixir/Clojure/JS, a Protocol in Python — but the **fields and their meanings are fixed by the
spec**, and the request body is bytes in every port.

### The response body MUST be a stream

This is the part that a casual reading gets wrong, so it is normative.

`TransportResponse.body` is a **stream**, never a byte array. Every LLM call in Go funnels through
`c.http.Do(req)` (`golang/client.go:708`), and the streaming paths then read that response
incrementally — `scanSSE(ctx, resp.Body, …)` at `golang/client.go:1437` and `:1696`. Hand those a
buffered `[]byte` and streaming does not fail; it **silently stops streaming**. Deltas arrive as
one blob at the end, no test goes red, and the regression ships.

Per port: `io.ReadCloser` · `InputStream` · `Stream` · `AsyncIterable<Uint8Array>` ·
`Iterator[bytes]` · a streaming binary · a reducible. A non-streaming provider wraps its bytes in a
one-shot reader — one line — and can start emitting real deltas later without an interface change.

### What does not change

- **The loop.** `Style` still selects the adapter, so a host that speaks OpenAI shape reuses every
  existing mapper and parser. `RequestParams`, `BodyTransform`, `beforeLLM`/`afterLLM`, `Retries`,
  backoff, `Retry-After`, `OnError` and metrics all run **around** the transport, untouched. This
  replaces the wire, not the loop. Elixir's docs already state exactly this contract, correctly.
- **Scope.** LLM path only. MCP transports remain their own seam and are explicitly **not** routed
  through this (already true in every port).
- **`status` stays.** I initially called it surplus for in-process use. Wrong: `status` is what
  feeds `OnError` and the retry classifier. An in-process provider that is out of VRAM or missing a
  model can report a status and inherit the whole resilience layer instead of inventing an error
  path.
- **Purity.** toolnexus gains no knowledge of any model runtime and no dependency in either
  direction. It hands out a request and takes back a response. `deps-purity-check.sh` is unaffected.

### The existing spellings stay

`HTTPClient`, `httpClient`, `fetch`, `http_transport`, `:transport`, `:http-client`,
`HttpMessageHandler` are **shipped public API**. They keep working, unchanged, and are documented as
the port-native convenience over `Transport`. Setting both a native client and a `Transport` is
**rejected at construction** rather than resolved by precedence — a silent winner between two
transports is a debugging session nobody should have.

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
