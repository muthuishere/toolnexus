# S01 — can one `.cljc` file load and run on BOTH hosts at all?

**Question, asked before koine existed:** is the whole idea viable — does a
single `.cljc` source load under Clojure on the JVM *and* under cljgo?

**Answer: yes.** That was the go/no-go for the entire port.

## Status: SUPERSEDED, kept as the record

This spike predates koine (it has no `run-both.sh` and no dependency at all —
`{:paths ["src"]}`). Its question has since been answered far more strongly by
ten later spikes and by the port itself: **twelve namespaces, 154 tests / 707
assertions, byte-identical across JVM, cljgo AOT and cljgo interpreted.**

It is left in place because it is where the answer came from, not because it
still adds coverage. **Do not extend it** — a new question about dual-host
behaviour belongs in a spike built to `BRIEF.md` (a `-main` printing one JSON
line, a `run-both.sh` that diffs all three modes, and an honest README).

Superseded by: S15 (§0 slice), S16 (client loop), S17 (composition) and the port.
