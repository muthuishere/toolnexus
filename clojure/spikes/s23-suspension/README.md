# S23 — suspension (§0.12 / §10) on both hosts

**Question:** does the suspension layer — the thing that lets a tool stop and ask
a human — work in portable Clojure, identically on Clojure (JVM) and on cljgo?

**Answer: yes, and it is the *cheapest* section of the spec to port, not the most
expensive.** Verified 2026-07-31.

```
== Clojure (JVM)
== cljgo (AOT binary)
== cljgo (interpreted)
== diff
  jvm == cljgo-aot  (byte-identical, 3259 bytes)
  jvm == cljgo-run  (byte-identical, 3259 bytes)
```

Reproduce: `./run-both.sh` (needs `clojure` and `cljgo`; no network beyond
`127.0.0.1`, no API key, no LLM).

One file, `src/toolnexus/suspension.cljc`, 446 lines, **zero reader conditionals,
zero `java.*`, zero Go interop**. The "LLM" is a `koine.server` on `127.0.0.1`
replaying a canned OpenAI-shaped script; the A2A leg is a second local server
that fulfils an inbound task from a suspended run. Nothing is mocked out.

## What it covers

| SPEC | what | mode in the report |
|---|---|---|
| §0.12 / §10 | `metadata.pending` = `Request` **is** a suspension | all |
| §10 rule 1 | `waitFor` `ok` ⇒ re-execute the tool **once** with `Context.answer` | `waitForOk` |
| §10 rule 1 | `ok == false` ⇒ feed back `"declined/expired: <prompt>"` | `waitForDeclined` |
| §10 rule 1 | retry suspends **again** ⇒ `"unresolved: <prompt>"`, never loop | `doubleSuspension` |
| §10 rule 2 | **no `waitFor`** ⇒ run does not hang, returns `{status:"pending", pending}` | `noWaitFor` |
| §10 | `Request = {id,kind,prompt,url?,data?,expiresAt?}` · `Answer = {id,ok,data?,reason?}` | `spec` + every `*Wire` |
| §10 R1 | `reason` present **only** when `ok == false` (`reasonOnOk:false`) | `spec`, `waitForDeclined` |
| §10 R2 | `data.schema` (flat MCP `requestedSchema` shape) on a `kind:"input"` Request | `spec.inputSchemaKeys` |
| §10 | streaming emits `{type:"pending", request}` **before** `waitFor` runs | `streamEvents` |
| §10 | a suspension is **never a tool error** — the `tool` event is `isError:false, pending:true` | `toolEvents` |
| §10 | concurrent suspensions halt on the **first in tool-call order**; only that one's placeholder enters the transcript | `concurrent` |
| §10 | durable-halt transcript rule: `[system user assistant tool]`, then `pending` | `noWaitFor`, `concurrent` |
| §7B / §10 | a halted run fulfilled as an inbound A2A task surfaces `input-required` carrying `pending.prompt` — never `completed` | `a2a` |
| §4A | the `question` builtin's rendered prompt (`" (options: a, b, c)"`, `header` not rendered) | `spec.renderedQuestions` |
| §10 | the slot is `waitFor`, **never `await`** | `spec.slotName` |

Three `kind`s are exercised end to end: `authorization` (tool ignores
`answer.data` — the world changed out-of-band), `input` (the resolution **is**
the payload, returned verbatim), `question` (same, via §4A's producer). The
trust boundary is respected: no credential is ever collected through
`input`/`question`.

### Non-determinism

Request `id`s and `expiresAt` never reach the report as values — the wire shapes
are emitted with `id → "<id>"` and `expiresAt → "<rfc3339>"`, so the *shape*
(including that `ok` is a JSON boolean, not a string) is diffed byte-for-byte
while the values stay out. `id` is genuinely unique per suspension
(`now-ms` + counter), not a fixture.

## Findings

### 1. §10's async wording is the one place the spec assumes a problem Clojure does not have — and Clojure comes out ahead

§10 says: *"Async flavor is idiomatic per port (JS `Promise`, Go blocking +
`ctx`, Python coroutine, Java `CompletableFuture`, C# `Task`)."* Every one of
those is a **colour**. In JS and Python, giving the loop a `waitFor` makes the
loop `async`, which makes `run` `async`, which makes every caller `async`. §10 is
the section that colours §8. That cost is invisible in the spec text because
five of the six shipped ports pay it and so it reads as normal.

In Clojure there is no colour to pay. `waitFor` is `(fn [request] answer)`, a
plain blocking call; `run-loop` stays an ordinary synchronous function; the whole
loop rule is **~30 lines with no `future`, no channel, no `core.async`, no
executor**. This spike starts more servers than S16 and still uses fewer
concurrency primitives than S16 did (S16 needed `future`/`deref` for parallel
tool calls; §10 needs nothing). **Suspension is the cheapest §0 clause to port to
Clojure, and the spec's own framing predicts the opposite.**

The load-bearing reason is that §10 was designed as *data* — `Request` and
`Answer` cross the boundary, and only `waitFor` is behaviour. A design that put
the suspension in the *control flow* (a continuation, a coroutine handle, a
resumable generator) would have been unportable here. It is worth saying out
loud that the spec's data-first choice is what makes a no-async host a
first-class port rather than a special case.

**The flip side, and this spike did NOT measure it:** because a Clojure
`waitFor` is a plain blocking call, a slow host slot pins whatever executes the
run — an OS thread on the JVM, a goroutine on cljgo. Those have very different
cost profiles at scale, and rule 2 (`no waitFor ⇒ return, don't hang`) is the
only thing in the contract that bounds it. No load or blocking-cost measurement
was taken.

### 2. "never name it `await`" is right in Clojure too — for a completely different reason

§10 justifies the name with *"`await` is a reserved word in JS/Python/C#"*. In
Clojure `await` is not reserved. It is `clojure.core/await` (agents), and it
**exists on both hosts** — verified: `#'clojure.core/await` resolves under
`clojure` and under `cljgo`. So `(defn await ...)` would shadow a `clojure.core`
name, which per the spike brief can make cljgo's static Java-interop scan reject
**the entire namespace**, not just that fn. Same rule, sharper teeth, and the
rationale in the spec does not cover it. If §10 ever gets a Clojure-port note,
this is the line to add.

### 3. §10 forces non-idiomatic casing at exactly one point, and Clojure has nowhere to hide it

`Request`/`Answer` keys are pinned *"not idiomatic-cased like `RunResult`"*.
`koine.json/write-str` emits a keyword's name verbatim, so **the map key is the
wire key** — the port must literally carry `:expiresAt` (and `:isError` on
`ToolResult`) in a language whose entire convention is kebab-case. There is no
serializer-level renaming layer in the koine stack to absorb it, and adding one
would be the very drift the ports exist to prevent. This is a real, permanent,
lint-visible wart that the Clojure port must accept deliberately rather than
discover later. It is byte-identical across both hosts, which is what matters.

### 4. cljgo AOT binaries print a **port number** to stdout when a server starts

Each `koine.server/serve` in a `cljgo build` binary emits
`bri: listening on http://localhost:<port>` on **stdout**. That is exactly the
non-determinism the brief forbids in a diffable report, and it is not visible in
the interpreted mode's `tail -1`. `run-both.sh`'s `tail -1` on the AOT branch is
therefore load-bearing, not defensive — the naive `binary > file` capture
produces an 8-line file here and a false `jvm != cljgo-aot`.

Related, and worth fixing repo-wide: the inherited `run-both.sh` line
`"$(cljgo which <name>)"` is dead — `cljgo which` is not a command in cljgo
0.1.0-dev. Every spike copying it has been silently taking the
`cljgo build run | tail -1` fallback, which is why the stdout noise above never
surfaced before. S23's `run-both.sh` runs the installed binary explicitly.

## What it does NOT cover

- **Durable resume across processes.** Rule 2 is proven to *return* a `pending`
  `RunResult`; the second half — persist the `Request`, resolve it elsewhere,
  call `run` again — is not exercised. No serialize/restart round-trip was run.
- **Blocking cost.** See finding 1: no threads/goroutine load measurement.
- **`Request.data.path`** (§7D agent escalation) — no agent boundary exists here,
  so nothing stamps a path.
- **The MCP elicitation bridge (§2)** — R1/R2 are covered as `Request`/`Answer`
  shape, but no MCP server elicitation was mapped onto `waitFor`, and the
  `decline` vs `cancel` mapping is not exercised.
- **The `authorization` kind's OAuth2/OIDC convention** — the `url` is a fixture;
  no redirect/consent/callback happens (correctly, that lives in the host).
- **Real concurrency in resolution.** Tool calls here resolve sequentially in
  call order, which is what makes concurrent suspension deterministic; §10's
  "with a `waitFor`, each concurrent suspension resolves independently inline"
  is not exercised in parallel.
- **`expiresAt` enforcement** — the field is emitted; nothing checks staleness
  (the spec does not require the engine to).
- The other §8 client surface (hooks, memory, retries, metrics) — S16's ground.
