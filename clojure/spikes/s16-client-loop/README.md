# S16 — the client loop, native + http tools, parallel tool calls

**Question:** S15 proved the tool *sources* port. Does the part that actually
drives an LLM port too — including parallel tool execution, which is where a JVM
and a Go runtime are most likely to differ?

**Answer: yes.** Verified 2026-07-31 on koine 0.4.2.

```
jvm == cljgo-aot  (byte-identical, 405 bytes)
jvm == cljgo-run  (byte-identical, 405 bytes)
```

Reproduce: `./run-both.sh`.

## Covered

| SPEC | what |
|---|---|
| §0.8 | `native` tool — fn→Tool, string return ⇒ output, **throw ⇒ `isError`** |
| §0.9 | `http` tool — `{ph}` URL substitution, `${ENV}` headers, non-2xx ⇒ `HTTP <status>: <body>`, transport failure ⇒ data |
| §0.10 | the client loop — call → execute → feed back → repeat, bounded by `maxTurns` |
| S10 | **parallel tool calls via `future`/`deref`**, results in call order |
| S6 | HTTP POST against a local server, both hosts |

The "LLM" is a `koine.server` on `127.0.0.1:0` replaying a canned OpenAI-shaped
script: turn 1 answers with **two** tool calls so the parallel path is taken
rather than described; turn 2 answers with text, ending the loop. No API key, no
network, no cost — and the loop runs for real rather than being mocked out.

## Findings

1. **`future`/`deref` work on cljgo**, and results come back in call order.
   This was the open risk: a scrambled transcript fed back to the model is a
   silent correctness bug, not a crash.
2. **`(catch Throwable e …)` is portable across JVM and cljgo** and needs no
   reader conditional. `Throwable` is a bare symbol, not a `java.*` class name,
   so cljgo accepts it — which is what makes §0.8's "a throw becomes `isError`"
   writable once for both hosts.
3. **koine's HTTP failure taxonomy is what makes §0.9 portable.** An unreachable
   host returns `{:status nil :error :connect-failed}` as *data* on both hosts.
   Without that, the tool would have to catch a host-specific exception class —
   exactly the thing that cannot be written once.
4. **cljgo's `koine.server` prints `bri: listening on http://localhost:NNNNN` to
   STDOUT.** It carries a random port, so it is non-deterministic, and it
   pollutes any program whose stdout is data. `run-both.sh` works around it by
   taking the last line starting with `{`. It belongs on stderr — reported
   upstream.

## Not covered

Streaming (§0.10 emits deltas; this loop is non-streaming), hooks, retries,
conversation memory, observability metrics, and the Anthropic-style adapter path
(only `style:"openai"` is exercised).
