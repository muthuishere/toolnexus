## 1. The contract (SPEC.md) — the porting obligation, land it first

- [x] 1.1 `ContentPart` union in §0/§1: field names, `mimeType` spelling, base64 rule (standard,
      padded, unwrapped), the exactly-one-of `data`/`url` rule, and "never a path"
- [x] 1.2 Edge-constructor contract: path / native bytes / `data:` URL / `https:` URL
- [x] 1.3 `SPEC.md:44` — `ToolResult` gains optional `parts`; `output` stays required
- [x] 1.4 `SPEC.md:218` — MCP mapping rewritten: **five** content types (incl. `resource_link`),
      parts collected on **every** branch incl. `structuredContent` and `isError` (**BREAKING**)
- [x] 1.5 `SPEC.md:466` — `read` media table + the "returns an error result, never raises" rule
- [x] 1.6 §5 — the two-style block table (`openai`, `anthropic`) with `anthropic × audio` as a
      named refusal, the `data:<mime>;base64,` prefix requirement for `file_data`, and the
      positive-allowlist rule. **State that Gemini emission is out of scope and why**
- [x] 1.7 §7 — `run()` takes a string or parts, first-positional; string path byte-identical
- [x] 1.8 §11 — the tool-result relocation rule, verbatim from design D7 (native for anthropic,
      one synthetic `user` message for openai, never persisted)
- [x] 1.9 §11 — replace the `:1612` flatten sentence with the preserve rule
- [x] 1.10 §7B — served `tools/call` emits non-text parts as MCP content blocks
- [x] 1.11 §9 — parts render `{type, mimeType, bytes}`; `data` never logged; token estimate is
      byte-derived; `maxPartBytes` is **decoded** bytes
- [x] 1.12 The provenance rule for unsupported parts (attached ⇒ error, tool-derived ⇒ placeholder
      + warn-once, `onUnsupportedPart` overrides)

## 2. Shared fixture — DONE

- [x] 2.1 `examples/media/fixture.png` (8x8, 82 bytes, four quadrants) + `.base64` + `.sha256`
      goldens + `README.md` explaining why the bytes are committed rather than generated
- [x] 2.2 Fixture MCP tool in `examples/` returning that image (and one returning `resource_link`)
- [x] 2.3 Fixture tool returning a `file` part, for the `ToolResult.parts` path

## 3. ContentPart model + edge constructors — per port

Mirror each port's **existing** §10 `Request` shape (flat discriminator + optional fields); do NOT
introduce the port's only polymorphic type.

- [x] 3.1 js — `type ContentPart = …` mirroring `StreamEvent` (`client.ts:201`); `attach()` async
- [x] 3.2 python — `@dataclass` mirroring `Request` (`types.py:29`); `image()/file()/audio()`
- [x] 3.3 golang — struct + `Type` discriminator mirroring `A2APart` (`a2a.go:48`); **deferred-error
      `File(path)`** carrying an unexported `err` surfaced by `RunParts`
- [x] 3.4 java — `@JsonInclude(NON_NULL) record` mirroring `Request.java:24`; `ofFile` throws
      `UncheckedIOException`, `ofFileChecked` for the strict caller
- [x] 3.5 csharp — `sealed record` mirroring `Suspension.cs:11`; `FromFile` sync + `FromFileAsync`.
      Verify a record leaf survives `Json.Normalize` (`Json.cs:28`)
- [x] 3.6 elixir — struct + `defimpl Jason.Encoder` + `from_map/1`, mirroring `Request`
      (`types.ex:1`); `image/1` and `image!/1`; `type` a **string**, never an atom
- [x] 3.7 clojure — plain map, plain keywords, camelCase wire keys (`:mimeType` like `:isError`);
      `content/image-file` over `koine/fs read-bytes` + `koine/codec encode`
- [x] 3.8 Cross-port: every port encodes `examples/media/fixture.png` to the committed golden
- [x] 3.9 Cross-port: both-`data`-and-`url`, neither-set, and unknown-extension are typed errors
- [x] 3.10 Cross-port: a `data:` URL normalises to `{mimeType, data}` identically

## 4. MCP passthrough — the actual bug; ships independently

- [x] 4.1 js `mcp.ts:130` — filter becomes a mapper; collect parts **before** the
      `structuredContent` return at `:208` and on the error path at `:206`
- [x] 4.2 python `mcp_source.py:172` — same, before `:255`
- [x] 4.3 golang `mcp.go:252` — same, before `:319`; `ResourceLink` has **no `As…` helper**, match
      the concrete type
- [x] 4.4 java `McpSource.java:662` — same, before `:650`; `Content` is **not sealed**, a `default`
      arm is mandatory
- [x] 4.5 csharp `McpSource.cs:513` — same; take `.Data`/`.Blob` verbatim, never `.DecodedData`
- [x] 4.6 elixir `mcp.ex:390` — same
- [x] 4.7 clojure `mcp.cljc:652` — `(keep :text …)` becomes a mapper
- [x] 4.8 Per port: text-only result byte-identical (regression pin)
- [x] 4.9 Per port: image-only result yields a part and a non-empty `output`
- [x] 4.10 Per port: `structuredContent` + image keeps the image; `isError` + image keeps the image
- [x] 4.11 Per port: `resource_link` becomes a `file` part with `url`

## 5. ToolResult.parts through the loop

- [x] 5.1 js (optional field, 67 sites unaffected)
- [x] 5.2 python (defaulted field after `metadata`)
- [x] 5.3 golang (`Parts []ContentPart` + `omitempty`)
- [x] 5.4 java (new 4-arg ctor; 3-arg delegates — it is a class, not a record)
- [x] 5.5 csharp (**append 4th optional positional AFTER `Metadata`** — inserting breaks 19 sites)
- [x] 5.6 elixir (`parts: nil` on the struct)
- [x] 5.7 clojure (`assoc :parts` in `tool/success`)
- [x] 5.8 Per port: `parts` + `metadata.pending` still suspends per §10

## 6. Loop input accepts parts

- [x] 6.1 js — widen `run`/`ask`/`stream`/`send`/`Agent.run`/`Loop.run`; one `toMessageContent()`
      at `client.ts:742/840/916/1010`
- [x] 6.2 python — widen annotation + one normaliser at `client.py:1220/1332/1449/1615`
- [x] 6.3 golang — `RunParts` / `RunPartsWithHistory` siblings; sites `client.go:829/1097/1400/1656`
      (+ `relay.go:359`)
- [x] 6.4 java — overload on arg 1 via `msg(String, Object)` (`LlmClient.java:1791`); note
      `run(null, toolkit)` becomes ambiguous
- [x] 6.5 csharp — overload `RunAsync`/`AskAsync`/`StreamAsync`; `Msg()` at `LlmClient.cs:653`
- [x] 6.6 elixir — split `run/4`'s inline default into a bodiless head + guard clauses (per
      `ask/4` `client.ex:511`) or it will not compile
- [x] 6.7 clojure — value dispatch at the single seed site `client.cljc:820`; no new arity
- [x] 6.8 Per port: string path produces byte-identical messages (regression pin)
- [x] 6.9 Per port: `[text, image, text]` ordering preserved

## 7. Provider emission + the allowlist + the relocation rule

Lands in `client.*` message assembly — **not** `adapters.*`, which is schema-only everywhere.

- [x] 7.1 js  · 7.2 python · 7.3 golang · 7.4 java · 7.5 csharp · 7.6 elixir · 7.7 clojure
- [x] 7.8 Per port: openai inline image = `image_url` + `data:` URL; file = `file.file_data` **with
      the `data:` prefix** (bare base64 is a 400)
- [x] 7.9 Per port: anthropic image = `source{type:"base64"|"url"}`; `audio` is a named refusal
- [x] 7.10 Per port: the positive allowlist rejects an unmapped block **before** any HTTP call
- [x] 7.11 Per port: attached-unsupported ⇒ error; tool-derived-unsupported ⇒ placeholder +
      warn-once; `onUnsupportedPart` overrides both
- [x] 7.12 Per port: **the relocation rule** — anthropic native in `tool_result.content`; openai one
      synthetic `user` message after the last tool message, tool-call order, `Output of tool …`
      prefixes
- [x] 7.13 Per port: the synthetic message is absent from `RunResult.messages` and the store
- [x] 7.14 Cross-port: the fixture image produces an identical JSON body per style

## 8. §11 translate

- [x] 8.1 js `translate.ts:192` · 8.2 python `translate.py:189` · 8.3 golang `translate.go:333`
      · 8.4 java `Translate.java:262` · 8.5 csharp `Translate.cs:263` · 8.6 elixir
      `translate.ex:238` · 8.7 clojure `translate.cljc:152`
- [x] 8.8 Per port: text-only parts array still concatenates (regression pin)
- [x] 8.9 csharp: update `TranslateTests.cs:404 ContentParts_AreFlattened` — it guards the old rule

## 9. Builtin `read`

- [x] 9.1 js · 9.2 python · 9.3 golang · 9.4 java · 9.5 csharp · 9.6 elixir · 9.7 clojure
- [x] 9.8 **python only**: fix `builtin.py:218` — `UnicodeDecodeError` is a `ValueError`, escapes
      `execute`, and `toolkit.py:283` does not wrap it. Today a raw exception reaches the loop
- [x] 9.9 Per port: `.md` with `offset`/`limit` unchanged; `.bin` returns `isError`, never raises

## 10. `serve()` / MCP inbound

- [x] 10.1 js · 10.2 python `mcp_serve.py:119` · 10.3 golang `mcpserve.go:143` · 10.4 java
      · 10.5 csharp `McpServe.cs:127` · 10.6 elixir `mcp_serve.ex:134` · 10.7 clojure `serve.cljc:306`
- [x] 10.8 Round-trip: serve a part-returning tool, consume it as an MCP source, part survives
      both directions byte-identically
- [x] 10.9 Decide the A2A Agent Card `defaultOutputModes ["text"]` question (`serve.cljc:121`) —
      update the card or scope inbound parts behind it. A card that lies is worse than a gap

## 11. Safety, tokens, limits

- [x] 11.1 Per port: §9 events and every log path render `{type, mimeType, bytes}`, never `data`
- [x] 11.2 Per port: token estimate is byte-derived, NOT the `mimeType` string (the first design
      had this backwards — it made a 5 MB image ~3 tokens and uncompactable)
- [x] 11.3 **python only**: `countTokens` does not exist in this port; either add it or name the
      gap in `CHANGELOG.md`. Do not leave it silently unmitigated
- [x] 11.4 Per port: `maxPartBytes` in **decoded** bytes, enforced in the edge factories

## 12. Live verification against OpenRouter

> `scripts/live-multimodal-check.py` verifies the **contract** (SPEC §8A) live: 6/7 checks pass,
> 1 skipped (anthropic-native `source{}` needs `ANTHROPIC_API_KEY`; OpenRouter's endpoint is
> OpenAI-compatible and structurally cannot exercise it), 1 upstream WARN (OpenRouter drops an
> openai-shaped image en route to an Anthropic model — +4 prompt tokens vs +8500, not fixable
> here). Per-**port** live runs (12.1-12.3) are still outstanding.

- [ ] 12.1 Extend `examples/agent.*` (or a sibling runner) per port to send the fixture image and
      assert the model names the quadrant colours — proof the image arrived, not that it guessed
- [ ] 12.2 Run it for all seven ports and record prompt-token counts per style (the fixture cost
      8 512 ptok on gpt-4o-mini vs 263 on gemini-2.5-flash-lite — the asymmetry is worth recording)
- [ ] 12.3 Live-verify the relocation rule end to end: a tool returning an image, on both styles
- [x] 12.4 Never echo `OPENROUTER_API_KEY`; reference it only where curl/the client consumes it

## 13. Docs and release hygiene

- [x] 13.1 `CHANGELOG.md` `## Unreleased` — lead with what a user gets; **name both behaviour
      changes** (MCP silent drop, §11 rule) and every deferral by name (Gemini emission, A2A parts,
      skill resources, `fileId`, python `countTokens`) with this change folder as the tracker
- [x] 13.2 Per-language READMEs: the attachment example (all 8, incl. correcting the stale python/elixir sections)
- [x] 13.3 Docs-site cookbook page: `cookbook/attachments` + sidebar entry; site builds (422 pages)
- [x] 13.4 `openspec validate add-multimodal-content` clean; every touched port's suite green

## 14. Native source objects at the edge (SPEC §1B)

- [x] 14.1 Contract: the per-port native-source table + "accept broadly, store narrowly", eager
      stream consumption, and "a handle you pass is read, not closed"
- [x] 14.2 js — `Blob`, `File`, `ArrayBuffer`, any `ArrayBufferView`. Fixed a real defect:
      `Buffer.from(dataView)` read a non-`Uint8Array` view as an array of numbers
- [x] 14.3 python — `os.PathLike`, any binary file-like `.read()`, `bytearray`, `memoryview`
- [x] 14.4 golang — `io.Reader`, `fs.File`, `*os.File`, `fs.FS`+name (so `embed.FS`);
      `io.LimitReader` so an oversize stream fast-fails without being read into memory
- [x] 14.5 java — `java.io.File`, `InputStream` (+ `…Checked` siblings)
- [x] 14.6 csharp — `FileInfo`, `Stream` (+`Async`), `ReadOnlySpan`, `ReadOnlyMemory`;
      forward-only streams never seeked
- [x] 14.7 elixir — iodata, `File.Stream`, any `Enumerable` of chunks; `{:bytes, _}` stays
      explicitly tagged rather than guessing (a bare binary is ambiguous)
- [x] 14.8 clojure — host byte arrays (JVM `byte[]` AND Go `[]byte`) and any byte seq. No
      stream type by design; a `java.io.File` now returns a NAMED ERROR instead of throwing a
      cast error, which also fixed a violation of that port's "failures are data" rule
- [x] 14.9 elixir parity gap found by the docs pass: `max_part_bytes` was construction-only,
      so an MCP-derived oversize part bypassed it. Now a client option enforced at assembly,
      with the provenance split and a reason-keyed warn-once

## 15. API reference (`site/src/content/docs/api/`)

The reference is generated from `site/src/data/api-surface.json` and its examples are
EXECUTED in all seven ports by `site/tests/run-all.sh` (clojure four ways), so a docs example
that stops compiling fails CI. That is why this is a code task, not a writing task.

- [x] 15.1 Manifest: new `types/content-part` entry (§1B), inserted after `types/tool-result`
      since a part is what a result may now carry; `types/tool-result` summary corrected
- [x] 15.2 `verify-symbols.mjs --strict` — all 7 ContentPart symbols resolve against real
      source, `unresolved: 0`
- [x] 15.3 Scaffolded 7 pages via `generate-pages.mjs` (never overwrites; leaves TODO markers)
- [x] 15.4 Filled all 7 `types/content-part.mdx` — signature, when-to-use, the why-not-a-path
      aside, and **three executed examples each**. Zero TODO markers remain
- [x] 15.5 Updated all 7 `types/tool-result.mdx` for `parts` (+ the port-specific reason each
      addition was source-compatible: java's 4-arg ctor delegating, c#'s 4th positional AFTER
      `Metadata`, clojure's `cond->`)
- [x] 15.6 Corrected the stale UTF-8-text-only `read` description in every port's
      `builtins/create.mdx`
- [x] 15.7 Per-port snippet suites all pass (js 154, python 148, go 152, java 148, csharp 154,
      elixir 136, clojure 15 x 4 modes)
- [x] 15.8 Clean SERIAL run of the whole docs suite + `astro build`. SUITE PASSED, exit 0:
      js 154 · python 148 · go 152 · java 148 · csharp 154 · elixir 136 · clojure 15 (x4 modes).
      Site builds 429 pages. (The first attempt ran concurrently with an agent and produced 4
      bogus C# failures — `dotnet` build-output contention in `site/tests/.work/csharp/`, not a
      real defect; csharp is 154/0 both in isolation and serially.)
