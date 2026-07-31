# toolnexus, in Clojure — one source, two runtimes

`src/toolnexus/demo.cljc` is **one file with no reader conditional in it**. It
runs unmodified on Clojure (JVM) and on [cljgo](https://github.com/muthuishere/cljgo),
which compiles Clojure to a native Go binary. Same bytes, same output.

```bash
./run-both.sh
```

That runs it three ways — JVM, cljgo AOT binary, cljgo interpreted — prints all
three reports, and **fails if they differ**.

```
== diff
  jvm == cljgo/aot     (identical, 1529 bytes)
  jvm == cljgo/interp  (identical, 1529 bytes)
```

## What the demo actually does

It is not a mock. It starts a real MCP server as a child process, reads real
files off disk, and calls three real tools:

```
1. MCP server "everything"  <- examples/mcp.json
   npx -y @modelcontextprotocol/server-everything
   13 tools discovered
2. Agent skills  <- examples/skills/**/SKILL.md
   1 skill(s): hello-world
3. Native tool   <- a plain Clojure fn
   shout
---------------------------------------------------------------
unified toolkit — 15 tools, one namespace:
   everything_echo   (mcp)
   ...
   shout   (native)
   skill   (skill)
---------------------------------------------------------------
one call per source:
  [mcp] everything_echo {"message":"hello from Clojure"}
      -> Echo: hello from Clojure
  [skill] skill {"name":"hello-world"}
      -> <skill_content name="hello-world">  … 1003 bytes of instructions injected
  [native] shout {"text":"same file, both runtimes"}
      -> SAME FILE, BOTH RUNTIMES
```

The fixtures are the repo's **shared** `examples/` directory at the root — the
same `mcp.json` and the same `skills/hello-world/` that the JS, Python, Go,
Java, C# and Elixir ports run against. Nothing here is Clojure-specific.

## Run it yourself

```bash
cd clojure-app && clojure -M -m toolnexus.demo          # Clojure, JVM

cd cljgo-app   && cljgo build && ./demo                 # cljgo, native binary
                  cljgo run src/run_interpreted.cljc    # cljgo, interpreted
```

Set `TN_EXAMPLES` to point somewhere else for the fixtures; it defaults to the
repo's shared `examples/`.

You need `clojure`, `cljgo`, and `npx` on PATH. Nothing else — **no API key and
no internet beyond the one `npx` fetch of the MCP server.** There is no live
LLM in this demo.

## What to look at in the source

`src/toolnexus/demo.cljc`, in reading order:

| lines | what |
|---|---|
| `rpc!` / `connect-mcp!` | the MCP handshake and one JSON-RPC round trip over a child process's stdin/stdout |
| `mcp-tools` | `tools/list` becomes a list of `{:name :description :source :execute}` maps |
| `discover-skills` / `skill-tool` | every `**/SKILL.md` behind **one** `skill` tool — the model pays for a skill's instructions only when it asks for them (progressive disclosure) |
| `native-tool` | your own function, wearing the same map |
| `toolkit` / `call-tool` | the three merge into one flat namespace, and a failing tool returns a result rather than crashing the caller |

The thing worth noticing is how little there is. **An MCP tool, a skill and a
local function are the same shape** — a name, a description, and something you
can call. That equivalence is the whole library; everything else is transport.

## The layout, and why

```
src/toolnexus/demo.cljc      THE source — one copy
clojure-app/
  deps.edn                   koine from Clojars, Clojure 1.12.5
  src/toolnexus/demo.cljc -> ../../../src/toolnexus/demo.cljc
cljgo-app/
  build.cljgo                koine from Clojars — the same artifact, same version
  src/toolnexus/demo.cljc -> ../../../src/toolnexus/demo.cljc
  src/run_interpreted.cljc   two lines, see below
```

The symlinks are the honest version of "one source": there is no second copy
that can drift, and both projects read the exact same bytes.

Every host-shaped thing goes through
[koine](https://clojars.org/net.clojars.muthuishere/koine) — `koine.process`
for the child, `koine.fs` for the files, `koine.json` for the wire, `koine.env`,
`koine.host`. That is the **only** dependency: no HTTP client, no JSON library,
no process library. While resolving it, cljgo prints

```
cljgo deps: net.clojars.muthuishere/koine 0.4.2 — 11 namespace(s) with no Java interop
```

which is the portability claim being machine-checked at dependency time.

`src/run_interpreted.cljc` exists because **`cljgo run <file>` does not call
`-main`** — it evaluates top-level forms and exits 0 having printed nothing,
which is indistinguishable from success. Those two lines make the interpreted
mode run the same program the binary runs. For the same reason `run-both.sh`
asserts on **output**, never on the exit code.

## Honest limits

- **This is a teaching artifact, not the conformance suite.** It shows three
  tool sources and one call each. It does *not* cover remote MCP over
  streamable-HTTP, A2A inbound/outbound, `serve()`, the OpenAI/Anthropic/Gemini
  adapters, suspension/resume, or the retry policy. `clojure/spikes/s17-composition`
  drives all of that end to end; start there if you want coverage.
- **There is no LLM.** The demo calls tools directly. The client loop that lets
  a model choose them is exercised in `s16-client-loop` and `s17-composition`
  against a scripted LLM over `127.0.0.1`.
- **The tool count is whatever `@modelcontextprotocol/server-everything`
  currently ships** (13 at the time of writing). If npm publishes a new version
  the number in this README moves; the *identity* between runtimes does not.
- **`run-both.sh` needs both toolchains.** It does not skip a missing one — if
  `cljgo` is not installed it fails, because "one source, two runtimes" is the
  claim being tested.
- Three of the ~15 tools are actually called. The rest of the MCP server's
  surface is discovered and listed, never invoked.
