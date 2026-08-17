# An in-process model is a first-class client, not an HTTP transport trick

## Why

Running a model inside the process is possible today, and it makes you lie three times:

```js
createClient({
  baseUrl: "http://in-process.invalid",   // there is no URL
  style: "openai",                        // there is no wire to be neutral about
  apiKey: "unused",                       // there is no key
  fetch: localFetch(new LocalModel()),
})
```

Two of those are already optional and were only ever noise. **`baseUrl` is genuinely required**, and
omitting it does not produce an error — it crashes with
`Cannot read properties of undefined (reading 'replace')`.

Worse than the fake URL is the boilerplate behind it. The seam is HTTP-shaped, so a host that just
wants to answer a question must first *build an HTTP response*: a `Response` in JS, an
`*http.Response` in Go, an `HttpResponseMessage` in C#, and in Java a **94-line `HttpClient`
subclass** whose only real method is `send`. Every example in our own cookbook opens by defining the
same `LocalModel.generate(messages, tools)` wrapper to hide this. **When every example needs the
same adapter, the adapter belongs in the library.**

## What the ecosystem does

Checked, not recalled:

| framework | the seam you implement | requires baseURL / apiKey? |
|---|---|---|
| Vercel AI SDK | `LanguageModelV4` — `doGenerate(options) -> {content, finishReason, usage}` | **No** — provider implementation detail, not spec |
| Microsoft.Extensions.AI | `IChatClient` — messages in, response out | **No** |
| Pydantic AI | subclass `Model`; ships `TestModel` / `FunctionModel` | **No** — "provider-specific configuration options" |

No framework-level library makes you fabricate a URL; that is an *SDK*-level pattern. All three also
ship a **built-in fake model**, treating exactly this case as first-class rather than as a transport
trick.

## What changes

A second constructor — **not** a second seam:

```
createInProcessClient({ model, generate, ...every other client option })

generate(request) -> { content }            // a final answer
                   | { toolCalls: [...] }   // ask for tools
                   (+ optional usage)
```

- **No `baseUrl`, no `apiKey`, no `style`** — there is no wire, so nothing to configure about one.
- **No HTTP envelope.** No `Response`, no `RoundTripper`, no `HttpMessageHandler`, no `HttpClient`
  subclass. `finish_reason` is derived from whether tool calls are present; `choices` and the usage
  envelope are the library's job.
- **Tool calls are flat** — `{id, name, arguments}` — because the nested `function:{}` wrapper is a
  wire detail. `arguments` accepts an object (encoded for you) or a pre-encoded string.
- **Everything else is unchanged**: it is an ordinary client. `systemPrompt`, `maxTurns`, `hooks`,
  `onMetric`, `store`, `waitFor`, MCP servers, skills, sub-agents and the completion gate all work,
  because it is built on the shipped transport seam rather than beside it.

## Impact

- **Affected spec:** `client-request-shaping`; a new row in `conformance/options_manifest.json`.
- **Affected code:** the client in all seven ports.
- **Purely additive.** The existing transport seam is untouched and remains the answer for proxy,
  mTLS, credential injection and record-replay. `createClient` behaves exactly as before.
- **Streaming is refused loudly.** An in-process `generate` returns a whole answer, so the streaming
  path raises rather than buffering and pretending — a buffered body is indistinguishable from a
  real stream by content or delta count, which is how a silent degradation would survive.

## The prior rejection, and why this is not it

`docs/adr/0019` rejected "a semantic callback — messages and tools in, a message out" outright:
*"duplicates the adapter layer, forces every host to reimplement per-provider mapping, makes `Style`
meaningless."*

That is right about **replacing** the transport seam and wrong as an objection to **layering on it**.
Vercel, Microsoft and Pydantic all ship both. `Style` stays meaningful for HTTP providers; for a
model in your own process it is meaningless *by construction*. ADR 0019 is amended in this change
rather than quietly contradicted.
