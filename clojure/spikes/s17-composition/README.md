# S17 — everything at once: do the sources COMPOSE?

S15 proved the tool sources port. S16 proved the loop ports. Neither answers the
only question that finally matters, because toolnexus is a composition library:
**do they work together?**

**Answer: yes, end to end, on both hosts.** Verified 2026-07-31 on koine 0.4.2.

```
jvm == cljgo-aot  (byte-identical, 641 bytes)
jvm == cljgo-run  (byte-identical, 641 bytes)
```

Reproduce: `TN_EXAMPLES=<repo>/examples ./run-both.sh`.

## The chain

```
A2A peer  --JSON-RPC-->  serve() §7B
                           |
                           +-- client loop §0.10  (scripted LLM over HTTP)
                                 |
                                 +-- toolkit T2, built from
                                       MCP streamable-HTTP §2  ------+
                                                                     |
serve() §7C  <------------------------------------------------------ +
  re-exposes toolkit T1 =
      MCP stdio      (a real child: @modelcontextprotocol/server-everything)
    + agent skills   (examples/skills/**/SKILL.md, §0.5/§0.6)
    + a native tool  (§0.8)
```

One A2A message reaches **a stdio child process, a skill on disk and a native
fn — through an MCP-over-HTTP hop.** Every leg is real. The only network is
`127.0.0.1` and the LLM is scripted, so it is hermetic and free.

## What the run reports

- **T1: 15 tools, 3 sources** (`mcp`, `skill`, `native`) — 13 from the live MCP
  child plus the `skill` tool plus one native.
- **T2: 15 tools, `names-match: true`** — every T1 tool round-tripped out
  through `/mcp` and back in as `gateway_<name>`, proving §7C exposes toolkit
  names **verbatim** while §7A/§7B re-sanitize skill ids.
- **Agent Card**: `protocolVersion 0.3.0`, `skills: ["hello-world"]` — from the
  SkillSource, never raw tools, exactly as §7B requires.
- **A2A task**: `submitted` → `completed`, with an artifact.
- **Three tool calls executed in parallel through the HTTP hop**:
  `gateway_everything_echo` (14 bytes), `gateway_skill` (**1127 bytes**),
  `gateway_now` (9 bytes).

That 1127 is the number worth staring at: it is the **same byte count S15
measured** for the `skill` tool output read straight off disk. The skill output
survived being produced by a skill source, re-exposed as an MCP tool, fetched
over HTTP by an MCP client, executed through a client loop, and returned as an
A2A artifact — without a byte changing.

## Findings

1. **A transport is a two-key map.** `{:rpc! fn :close! fn}` is the entire
   abstraction; stdio and streamable-HTTP differ only in how `:rpc!` is
   implemented, and *everything above §2 is written once*. In a language with
   interfaces this would be a protocol; a map of closures is the portable form
   and needs nothing but `fn` and `get`.
2. **§7C's "verbatim, not re-sanitized" is load-bearing and easy to get wrong.**
   Re-sanitizing at the gateway would double-prefix names on every hop, so a
   two-hop chain would silently rename every tool.
3. **Async fulfilment via `future` never crashed the server on either host**, as
   §7B requires — the same primitive S16 proved for parallel tool calls.
4. `koine.server` is one path and one handler by design, so §7B and §7C are
   co-mounted by dispatching on `(:path req)` in plain Clojure. That is less
   machinery than a router and it ports for free.

## Not covered

Streaming, push notifications and auth (all explicitly out of core per §7B), the
TaskStore abstraction (this uses an in-memory atom, not the pluggable
`file:<dir>` store), `a2a.skills` filtering, `mcp.tools` filtering, suspension
crossing A2A (§10 `input-required`), and the profile-absent cases (no A2A routes
⇒ 404). S21 covers those.
