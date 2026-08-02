# toolnexus in Clojure — runnable examples

Everything here runs on **both hosts from the same source**: Clojure on the JVM, and
[cljgo](https://github.com/muthuishere/cljgo), which hosts Clojure on Go. No reader
conditionals anywhere — every host difference lives in
[koine](https://github.com/muthuishere/koine), the port's only third-party dependency.

There are four things in this directory, in the order you probably want them.

| | what | live LLM? | how to run |
|---|---|---|---|
| **`clj-ex1` / `cljgo-ex1`** | an interactive agent: HTTP tool + filesystem MCP server + agent skill, answering your questions in a loop | yes | `task clj-ex1` · `task cljgo-ex1` |
| **`clj` / `cljgo`** (+ `src/examples`) | five focused examples — skills, native+HTTP tools, persona/memory, compaction, and the parity demo | no | `./clj/run.sh` · `./cljgo/run.sh` — see [EXAMPLES.md](EXAMPLES.md) |
| **`clojure-app` / `cljgo-app`** (+ `src/toolnexus`) | the parity demo: three tool sources, three execution modes, byte-identical output | no | `./run-both.sh` |
| **`minimal`** | the smallest honest demonstration of the premise | no | see [minimal/README.md](minimal/README.md) |

---

## 1. The interactive agent — `clj-ex1` / `cljgo-ex1`

The one to start with. It builds a toolkit from **three different sources at once** and then
sits in a loop answering whatever you type:

- an **HTTP tool** — one `http-tool` declaration that fetches the official Clojure events page
- an **MCP server** — `@modelcontextprotocol/server-filesystem`, spawned over stdio; all
  fourteen of its tools join the same registry
- an **agent skill** — `skills/clojure-events/SKILL.md`, loaded on demand rather than stuffed
  into the prompt

```bash
export OPENROUTER_API_KEY=...        # the value is never printed or logged

task clj-ex1                          # Clojure on the JVM
task cljgo-ex1                        # the same source on cljgo — no build step
task clj-ex1 MODEL=qwen/qwen-2.5-72b-instruct   # any tool-capable OpenRouter model
```

On start it prints every tool, MCP tool and skill it discovered plus a few questions worth
trying, then waits at `you>`. Every tool call and result prints as it happens, so you can watch
the loop rather than take its word for it. `client/ask` with an `:id` gives it conversation
memory, so follow-ups work. `exit` or Ctrl-D quits and disconnects the MCP server.

`clj-ex1/src/chat.cljc` and `cljgo-ex1/src/chat.cljc` are the **same file**. The only thing
`cljgo-ex1` adds is `src/run_chat.cljc`, two lines, because `cljgo run` evaluates top-level forms
and does not call `-main`. `cljgo build` will AOT the same source into a self-contained binary —
about 15 MB with the tools and MCP client included — but nothing here requires it.

**This is the only example that needs an API key and the internet.** Everything below is hermetic.

## 2. Five focused examples — `clj/` and `cljgo/`

Skills, native + HTTP tools, persona/memory, compaction, and the parity demo below — each isolated,
each run on both hosts. `./clj/run.sh` and `./cljgo/run.sh` run all five and fail loudly if any of
them does not finish; every example prints `OK` as its last line and is judged on that marker, not
on the exit status. [EXAMPLES.md](EXAMPLES.md) is the detail.

## 3. The parity demo — `clojure-app/` and `cljgo-app/`

`src/toolnexus/demo.cljc` is **one file, symlinked into both projects** — there is no second copy
that can drift. It starts a real MCP server as a child process, reads real files off disk, and
calls one tool from each of three sources.

```bash
./run-both.sh
```

That runs it three ways — JVM, cljgo AOT binary, cljgo interpreted — prints all three reports and
**fails if they differ**:

```
== diff
  jvm == cljgo/aot     (identical, 1529 bytes)
  jvm == cljgo/interp  (identical, 1529 bytes)
```

The fixtures are the repo's **shared** `examples/` directory at the root — the same `mcp.json` and
`skills/hello-world/` that the JS, Python, Go, Java, C# and Elixir ports run against. Set
`TN_EXAMPLES` to point elsewhere. There is no LLM in this demo; it calls the tools directly.

The thing worth noticing in the source is how little there is. **An MCP tool, a skill and a local
function are the same shape** — a name, a description, and something you can call. That
equivalence is the whole library; everything else is transport.

## What you need

| | for what |
|---|---|
| `clojure` | any JVM example |
| `cljgo` | any cljgo example (`go install github.com/muthuishere/cljgo/cmd/cljgo@latest`) |
| `npx` | the examples that spawn an MCP server |
| `task` | the `clj-ex1` / `cljgo-ex1` shortcuts ([Taskfile](https://taskfile.dev)) |
| `OPENROUTER_API_KEY` | **only** `clj-ex1` / `cljgo-ex1` |

## Honest limits

- **These are teaching artifacts, not the conformance suite.** They show the shape of the library;
  they do not cover every capability. Remote MCP over streamable-HTTP, A2A inbound/outbound,
  `serve`, the OpenAI/Anthropic/Gemini adapters, suspension/resume and the retry policy are
  exercised by the test suite, not here.
- **Only `clj-ex1` / `cljgo-ex1` talks to a real model.** The rest use a scripted LLM or call tools
  directly, so they are reproducible and cost nothing.
- **Tool counts move when upstream moves.** The MCP servers ship whatever they ship; a number in
  this README can go stale, but the *identity between the two hosts* does not.
- **`run-both.sh` needs both toolchains** and does not skip a missing one — "one source, two
  runtimes" is the claim being tested, so a half-run is a failure.
- **cljgo is young.** It passes 238 of 242 files of the clojure-test-suite, `clojure.core` is not
  complete, and it is not production-hardened; its
  [Why page](https://muthuishere.github.io/cljgo/why/) says so first. These examples running
  identically on both hosts is evidence, not a guarantee for your workload.
