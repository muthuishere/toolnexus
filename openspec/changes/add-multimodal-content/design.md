## Context

toolnexus assembles every message as `{role, content: <string>}` and every tool answer as
`ToolResult{ output: string, isError, metadata? }` (`SPEC.md:44`). Four places actively destroy
non-text data today, in all seven ports.

**This document was rewritten after a seven-port recon plus a live OpenRouter wire probe.**
Four claims in its first draft were false; they are corrected inline and listed in §Corrections
so the record shows what the research changed.

| # | Seam | Behaviour | Evidence |
|---|------|-----------|----------|
| 1 | Loop entry | `run(prompt: string)` → `{role:"user", content: prompt}` | js `client.ts:430`; py `client.py:1101`; go `client.go:574`; java `LlmClient.java:632`; c# `LlmClient.cs:443`; ex `client.ex:334`; clj `client.cljc:768` |
| 2 | Tool answer | `output` is a string; no other channel | `SPEC.md:44` |
| 3 | MCP result | non-text `content[]` filtered out, silently | js `mcp.ts:130`; py `mcp_source.py:172`; go `mcp.go:252`; java `McpSource.java:662`; c# `McpSource.cs:513`; ex `mcp.ex:390`; clj `mcp.cljc:652` |
| 4 | §11 translate | array `content` flattened to text | `SPEC.md:1612` |

The constraint shaping every decision is the prime directive — same behaviour, seven languages,
verified against shared `examples/` fixtures. Anything needing a per-language image library, a
MIME-sniffing database, or a format decoder is disqualified before its merits are weighed.

## Corrections to the first draft

1. **"JS-only drift" was false.** The array-`content` passthrough exists in *six* ports —
   `js/src/translate.ts:192`, `golang/translate.go:333`, `python/src/toolnexus/translate.py:189`,
   `java/.../Translate.java:262`, `csharp/.../Translate.cs:263`,
   `clojure/.../translate.cljc:152` (whose comment *documents* it: "a parts array carrying no
   text (images, say) passes through untouched"). C# even has a guard test
   (`TranslateTests.cs:404`). It is undocumented **consistent behaviour**, not drift. D6 no
   longer "deletes an accidental path"; it specifies an existing one.
2. **There is no adapter layer to change.** `adapters.*` is tool-**schema** only in every port
   (js 33 lines, py 51, java 57, ex 59, clj `adapter.cljc` "nothing here touches a network").
   Request bodies are assembled inline in `client.*`. D3 was written against a layer that does
   not exist.
3. **Gemini emission is unimplementable.** `ClientStyle` is `"openai" | "anthropic"` in all seven
   ports (js `client.ts:20`, ex `client.ex:336`, c# `LlmClient.cs:446`, java `:853`). `toGemini`
   emits *declarations* for a caller's own client. The three-column D3 table was a two-column
   reality; shipping it would have claimed parity while delivering 2/3.
4. **`countTokens` mitigation was backwards.** Reporting a part as its `mimeType` scores a 5 MB
   image at ~3 tokens, so the compactor evicts *text* and keeps every image forever — the
   opposite of the stated goal. Measured: the 8×8 fixture PNG cost **8 512** prompt tokens on
   `gpt-4o-mini` and **263** on `gemini-2.5-flash-lite`.

## Goals / Non-Goals

**Goals**

- One `ContentPart` model, byte-identical across js / python / golang / java / csharp / elixir /
  clojure.
- Images, PDFs and audio can enter the loop, come back from a tool, and survive MCP.
- The existing string path stays **byte-identical** — no caller changes, no test output moves.
- Non-text content is **never lost silently**, at any layer.
- Zero new dependencies in every port.

**Non-Goals**

- Model image *output* / generation. Input only.
- Decoding, resizing, transcoding, validating media. We move bytes and a MIME type.
- MIME sniffing from magic bytes.
- **Gemini request emission** (correction 3) — no port has a Gemini request path.
- A2A message parts, skill resources as parts, provider `file_id` upload — named deferrals.
- Token accounting *accuracy* for images. We charge a defensible estimate, not a true count.

## Decisions

### D1 — `ToolResult` gets a dedicated `parts` field, not `metadata`

`{ output, isError, parts?, metadata? }`. Rejected `metadata` because it already carries §10
suspension (`metadata.pending`, `SPEC.md:82`) — Elixir's `pending?/1` (`types.ex:118`) matches on
`metadata` alone, confirming the collision is real, not theoretical.

`output` stays **required** and remains what the transcript, compaction, `countTokens` and
text-only providers see. Verified non-breaking in every port:

| port | `ToolResult` shape | sites | verdict |
|---|---|---|---|
| js | structural interface `types.ts:15` | 67 literals | optional field, 0 break |
| python | `@dataclass` `types.py:22`, all-kwargs | 40 + 4 test | append defaulted field, 0 break |
| golang | struct `types.go:36`, **0 positional literals** | 76 | add `Parts`, 0 break |
| java | **`final class`, not a record** `ToolResult.java:15` | 23 | new 4-arg ctor delegating, 0 break |
| csharp | **positional record** `ToolResult.cs:11` | 19 | **append 4th optional param AFTER `Metadata`** — inserting before breaks all 19 |
| elixir | struct `types.ex:99`, no `@enforce_keys` | 51 + 54 test | add `parts: nil`, 0 break |
| clojure | plain map, `tool/success` `cond->` | 52 | `assoc :parts`, 0 break |

### D2 — A part is bytes-or-URL, never a path; the edge takes whatever the caller has

```
ContentPart =
  | { type:"text",  text }
  | { type:"image", mimeType, data? (base64), url? }
  | { type:"file",  mimeType, data?, url?, name? }
  | { type:"audio", mimeType, data?, url? }
```

Exactly one of `data`/`url`; both or neither is a construction error. **A path is never a part** —
it does not survive a persisted/replayed transcript or the MCP/A2A process boundary. Surveyed
prior art agrees unanimously: Vercel AI SDK, LangChain v1, PydanticAI, and the OpenAI and
Anthropic SDKs all refuse a filesystem path in a message part; Google accepts one only at
`files.upload`, which is an upload call, not a part.

But the *edge* should take what the caller actually has, which the first draft under-specified:

- **a path** → named constructor `from_file` (per-port spelling), reads + base64s immediately;
- **native bytes** (`[]byte`, `byte[]`, `bytes`, `Uint8Array`, `binary`) → base64d at
  construction. PydanticAI and the AI SDK both refuse to charge the hand-base64 tax; so do we.
  Base64 in the caller's code leaks transport into their program;
- **a `data:` URL** passed as `url` → parsed into `{mimeType, data}` at construction, so two
  spellings of the same bytes do not diverge downstream;
- **an `https:` URL** → kept as `url`.

Field is named `mimeType` everywhere (the field is fragmented four ways across the ecosystem —
`mimeType`/`mime_type`/`media_type`/`mediaType`; we pick one and never mirror). Wire-name casing
follows each port's existing convention for wire keys (clj `:mimeType` like `:isError`
`tool.cljc:34`; c# `[JsonPropertyName]` lower-camel like `Suspension.cs:9`).

Base64 is standard, padded, no line breaks. Every port's MCP SDK already hands us base64
**strings** — never bytes (`ImageContent.data` is `string` in js/py/go/java; C# has
`.DecodedData` but we take `.Data` verbatim so .NET's encoder cannot drift us).

### D3 — Message assembly, not adapters; two styles; a positive allowlist

Corrections 2 and 3 relocate this work: it lands in each port's `client.*` message assembly
(js `client.ts:742/840/916/1010`; go `client.go:829/1097/1400/1656`; py `client.py:1220/1332/1449/1615`;
java `LlmClient.java:1029/1142/1274/1462` via the one `msg()` helper at `:1791`; c#
`LlmClient.cs:731/833/961/1129` via `Msg()` `:653`; ex `client.ex:1346/1421/1518/1686`; clj
`client.cljc:820` — a **single** site, since Clojure has no streaming), plus `translate.*`.

Verified block shapes (live, §Live evidence):

| | `openai` (Chat Completions) | `anthropic` |
|---|---|---|
| image inline | `{type:"image_url", image_url:{url:"data:<mime>;base64,<b64>"}}` | `{type:"image", source:{type:"base64", media_type, data}}` |
| image url | `{type:"image_url", image_url:{url:"https://…"}}` | `{type:"image", source:{type:"url", url}}` |
| file | `{type:"file", file:{filename, file_data:"data:<mime>;base64,<b64>"}}` | `{type:"document", source:{…}}` |
| audio | `{type:"input_audio", input_audio:{data, format}}` | **none — Anthropic has no audio block** |

Two hard-won details: `file_data` **requires** the `data:<mime>;base64,` prefix (a bare base64
string is a 400), and OpenAI's *Responses* API shapes (`input_image`, `input_file`) are a
**different API**, not a replacement — sending `input_image` to a Chat Completions endpoint
returns **200 with the image silently dropped**.

That last fact forces the guard's shape. **The check is a positive allowlist over the encoded
block, not a mapping that hopes.** For each `(style, part.type)` there is either a defined block
shape or an explicit refusal; a part that produced no allowlisted block never reaches the wire.
Mapping-and-hoping reproduces the exact bug this change exists to remove, one layer up.

### D4 — The unsupported-part rule depends on *provenance* (supersedes the first draft)

The first draft defaulted to `"error"`. The JS recon found that this converts a today-succeeding
run into a hard failure: an MCP server returning audio currently succeeds (audio dropped), and
under a blanket `"error"` it would 400 — a regression dressed as a fix. The provider recon argued
the opposite, that silence is the enemy. Both are right about different parts, because they
differ in **whether the caller expressed intent**:

- **A part the caller attached** (loop input) that the style cannot represent ⇒ **error**, at
  assembly, before any HTTP. The caller asked for something specific; silently changing it is
  the betrayal.
- **A part derived from a tool/MCP result** that the style cannot represent ⇒ **text placeholder
  + warn-once**, never a hard failure. The caller did not choose it, and failing their run
  because a server volunteered an audio clip is a regression.

`onUnsupportedPart: "error" | "text"` overrides both, for callers who want uniform strictness or
uniform leniency. Warn-once matches the port's existing convention for "ignored + warned once"
(js `mcp.ts:135`). In Clojure, this is **data, not a throw** — `tool.cljc:106` / `mcp.cljc:19`
establish that nothing throws across the source boundary; the error surfaces as an error
`RunResult`.

Concrete consequence: `anthropic × audio` is a named refusal in the D3 table, and it is the case
the provenance split exists for.

### D5 — MCP passthrough: five content types, and the branch that bypasses it

`joinTextContent` keeps producing `output` exactly as today; non-text entries additionally become
`parts`. Text-only tools stay byte-identical.

The first draft named three content types. There are **five**, and the fifth is also being
dropped today: **`ResourceLink`** (`{uri, name, mimeType?, size?}`) — js `types.d.ts:2007`, py
`types.py:1196`, go `types.go:1123` (which has *no* `As…` helper, unlike the others), java and c#
(`ResourceLinkBlock`) alike. It maps to `file` + `url`.

Mapping: `image`→image, `audio`→audio, `resource_link`→`file{url}`, `resource` with a **blob**
→`file{data}`, `resource` with **text** → appended to `output`.

**The branch that would have defeated the whole fix:** every port short-circuits on
`structuredContent` *before* reading `content[]` — js `mcp.ts:208`, go `mcp.go:319`, py
`mcp_source.py:255`, java `McpSource.java:650`, clj `mcp.cljc:652`. A server returning structured
content *and* an image loses the image even after this change. **Parts must be collected before
that early return, on every branch including the error path.**

### D6 — §11 translate: specify the existing behaviour (see correction 1)

`SPEC.md:1612` says arrays are flattened; six ports actually pass a text-empty array through raw.
Both the spec and the code change to one specified rule: text parts concatenate; non-text parts
translate via the D3 mapping. This *documents and unifies* six ports rather than deleting one.

### D7 — The tool-result asymmetry, settled by experiment (was Open Question 3)

`ToolResult.parts` must reach the model. The live matrix:

| shape | gemini-2.5-flash-lite | gpt-4o-mini | claude-haiku-4.5 |
|---|---|---|---|
| image inside the `tool` role | ✅ | ❌ **400** | ✅ |
| tool result + following `user` message | ✅ | ✅ | ✅ |

OpenAI's Chat Completions `tool` role accepts `content` as a string **or an array of text parts
only**; an image there is a hard 400. Anthropic's `tool_result.content` explicitly permits
`text`, `image`, `document`, `search_result`.

**Decision: native where the style has a shape, relocation only where it does not** — not
uniform relocation. Uniform relocation would discard the `tool_use_id` association on Anthropic,
break cache breakpoints, and let the model read the image as user input rather than tool output:
a real downgrade bought for cosmetic uniformity, and the adapter seam exists precisely to absorb
this.

**§11 wording to land verbatim:**

> Non-text parts on a tool result are emitted **natively where the provider style defines a shape
> for them inside its tool-result element** (`anthropic`: blocks in `tool_result.content`). For
> the `openai` style, the tool message carries `output` plus any **text** parts only; **all**
> non-text parts from **all** tool results answering one assistant turn are relocated, **in
> tool-call order**, into a **single synthetic `user` message emitted immediately after the last
> tool message**, each part preceded by a text part `Output of tool <name> (<tool_call_id>):`.
> The synthetic message is an **adapter artifact only** — it is never written to the canonical
> transcript, `ConversationStore`, or `translate` output, so switching provider mid-conversation
> leaves no OpenAI-shaped residue.

That last clause is what keeps `ConversationStore` replay and provider-switching coherent, and it
is the reason this had to be pinned before any port wrote code.

### D8 — `read` returns a part for a recognised media file

Fixed extension table (`png jpg jpeg gif webp pdf mp3 wav`), no sniffing, **no `mimetypes`**. The
first draft justified excluding `mimetypes` as a dependency concern; the real reason is parity:
`mimetypes.knownfiles` reads `/etc/mime.types` and `/etc/apache2/mime.types`, so the same `.webp`
resolves differently on two machines and breaks the byte-identical fixture. Stdlib-ness was never
the objection.

Python must additionally fix a live bug this touches: `builtin.py:218` opens with
`encoding="utf-8"` inside `except OSError`, but `UnicodeDecodeError` is a `ValueError`, so it
escapes `execute` and `toolkit.py:283` does not wrap it — a raw exception propagates into the
loop today. `read` on a binary file does not "fail"; it **raises**.

### D9 — Token accounting charges for parts (replaces the backwards mitigation)

`countTokens` charges a flat per-part estimate — `bytes/750`, floored at some minimum — not the
`mimeType` string. Note `countTokens` **does not exist in the python port**, so the compaction
risk is unmitigated there until it does; that is named, not hidden.

`maxPartBytes` is measured in **decoded** bytes (not the base64 string, which is +33%), enforced
in the edge factories where the byte count is in hand — not in a record constructor, which is
hostile in C# and Java.

### D10 — Ergonomics, per port, all reaching the same shape

Every port's recon converged independently on: **parts as the first argument** (not a second
positional, not an options bag — text-before-or-after-image ordering is semantic), and a named
edge constructor whose verb makes the disk touch legible in a diff.

```ts   await client.run(["Describe this", await attach("./shot.png")], { toolkit })
```
```python  await client.run(["Describe this", image("shot.png")], toolkit)
```
```csharp await client.RunAsync(["what's in this?", ContentPart.FromFile("shot.png")], toolkit);
```
```java  client.run(List.of(ContentPart.text("What broke?"), ContentPart.ofFile(Path.of("shot.png"))), toolkit);
```
```elixir Client.run(c, [ContentPart.text("What broke?"), ContentPart.image!("shot.png")], tk)
```
```clojure (client/run c [{:type "text" :text "what's this?"} (content/image-file "chart.png")] {:toolkit tk})
```

Per-port shapes forced by the language, from the recon:

- **js** — widen the parameter (`string | ContentPart[]`), do not use overload signatures; the
  return type does not vary so they buy nothing. Same widening for `ask`/`stream`/`send`/`Agent.run`/`Loop.run`.
- **go** — no overloading; the port's own idiom is a named sibling (`RunWithHistory`,
  `StreamWithID`), so `RunParts`. Adopt the **deferred-error part**: `File(path)` carries an
  unexported `err` that `RunParts` surfaces, the `text/template`/`sql.Rows` idiom — one `err`, no
  new failure site, and unexported means it never serialises.
- **java** — overloading works (`String` vs `List<ContentPart>`), except `run(null, toolkit)`
  becomes ambiguous; note it. `ofFile` throws **`UncheckedIOException`** — forcing try/catch
  around a literal argument is hostile, and `Files.lines`/`Files.walk` set the precedent.
- **csharp** — unambiguous overload; collection expressions plus one implicit `string →
  ContentPart.Text` give the one-liner. Sync `FromFile` primary (matching `BuiltinTools.cs:320`),
  `FromFileAsync` alongside.
- **python** — widen the annotation, one private normaliser at the five sites; keep it first
  positional (a second would collide with `toolkit`).
- **elixir** — bodiless head carrying the default then guard clauses, per `ask/4`
  (`client.ex:511`); `run/4`'s inline `opts \\ []` must be split or it is a compile error. Ship
  both `image/1` and `image!/1`, per `create_toolkit`/`create_toolkit!`.
- **clojure** — `run` is fixed 3-arity, so **value dispatch** (`string?` vs `sequential?`) at the
  single seed site `client.cljc:820`. `koine/fs.cljc:208 read-bytes` + `koine/codec.cljc:82
  encode` already exist for exactly this — codec's docstring says it exists for MCP image/blob
  blocks — so the dual-host base64 risk the first draft flagged is already solved.

### D11 — Shared fixture (committed)

`examples/media/fixture.png` — 8×8, **82 bytes**, four solid quadrants (red/green/blue/white),
hand-built from zlib+struct with no image library. Committed alongside `fixture.png.base64` and
`fixture.png.sha256` goldens. Ports assert against the **committed** goldens, never their own
re-encoding: regeneration is not byte-stable across zlib versions, which is why the bytes are
committed rather than produced at test time. Clojure additionally must not read it through
`fs/read-file` in any test — `koine/fs.cljc:194` warns `slurp` is lossy for non-UTF-8 *identically
on both hosts*, which would yield a plausible, self-consistent, wrong base64.

## Risks / Trade-offs

- **Transcript size** — base64 in a `ConversationStore`, replayed every turn. → D9 charges real
  tokens so the compactor can act; `maxPartBytes` rejects at the edge. Measured: the 82-byte
  fixture cost 8 512 prompt tokens on gpt-4o-mini — image cost is not proportional to bytes, and
  the estimate is explicitly an estimate.
- **Secrets in stores and logs** — part `data` is user content now landing in persisted
  transcripts and §9 events. → parts render as `{type, mimeType, bytes}` in every log and event;
  `data` never. Same rule as never-log-headers.
- **Silent loss one layer up** — OpenRouter returned 200 and dropped an unknown block. → D3's
  positive allowlist; a part that produced no allowlisted block cannot reach the wire.
- **A2A card lies** — `clojure/.../serve.cljc:121` advertises `defaultOutputModes ["text"]`; if
  `tools/call` starts emitting image blocks while the Agent Card still says text-only, the
  published contract is false. → either update the card or scope MCP-inbound parts behind it.
  Named because A2A parts are deferred.
- **Elixir coverage gate (95%)** — 4 types × (data|url) × 2 styles × 2 unsupported modes ≈ 30+ new
  branches. → put part→block mapping in **public** functions, unit-testable without an LLM;
  buried in private `client.ex` clauses they are reachable only through mock-LLM plumbing and
  will sink coverage.
- **Scope** — seven ports × seven seams, the largest surface any change here has crossed. → tasks
  sequence *model → MCP → loop → assembly → translate → read → serve*, each independently
  testable, so a partial pass leaves a coherent subset.

## Live evidence

OpenRouter `/chat/completions`, 82-byte fixture PNG and a 580-byte PDF, tiny models, capped
tokens. Confirmed: `image_url` + `data:` URL works on all three families; `file{file_data}`
works **only** with the `data:` prefix; `input_audio` works on Gemini and 404s on gpt-4o-mini
("No endpoints found that support input audio"); the `tool`-role image 400s on OpenAI and
succeeds on Anthropic/Gemini; an unknown block type returns 200 having silently discarded the
image (5 prompt tokens, hallucinated answer).

## Open Questions

1. **`fileId` as a third source kind** — all three providers and all three surveyed frameworks
   model it, and it is the only answer to "the same 5 MB PDF on twenty turns". Deferred, not
   rejected; confirm that deferral.
2. **`force_download` for a `file` part with `url` on the openai style** — Chat Completions has
   no URL form for `file`, so it is unrepresentable and fails loudly by D3. PydanticAI's opt-in
   fetch-and-inline is the alternative. Deferred, named.
