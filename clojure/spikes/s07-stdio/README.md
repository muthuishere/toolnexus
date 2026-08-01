# S07 — can a Clojure program talk to a child process over stdio?

**Question, asked before koine existed:** MCP's stdio transport needs to spawn a
child, write a line, and read a line back. Is that reachable at all from
portable Clojure?

**Answer: yes on the JVM** — and the file name says the limit out loud:
`src/stdio_jvm.clj`, a `.clj`, JVM-only. It never answered the cljgo half.

## Status: SUPERSEDED, kept as the record

The real answer is `koine.process` (`spawn`, `send-line!`, `read-line!`,
`kill!`, `:timeout-ms`), and the real proof is elsewhere:

- **S15/S17** drive a genuine MCP stdio child (`@modelcontextprotocol/server-everything`)
  on both hosts, byte-identically.
- **`toolnexus.mcp`** ships a dedicated reader loop with a pending map and a
  `compare-and-set!` spin lock, exercised by the port's suite on both hosts.

Two things this spike could not have found, both discovered later and both
upstream fixes: koine's `alive?` meant different things per host (koine 0.7.0),
and cljgo's exec timeout *narrated* a timeout instead of enforcing one — 5008ms
against a 300ms deadline (koine 0.7.1 / cljgo #175).

**Do not extend it.** Superseded by: S15, S17, S18 and `toolnexus.mcp`.
