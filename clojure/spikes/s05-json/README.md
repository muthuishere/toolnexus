# S05 — will two hosts' JSON encoders produce the same bytes?

**Question, asked before koine existed:** §0 conformance is defined by byte
comparison. If the JVM and Go encoders disagree about key order, float
formatting or escaping, the whole premise of the port collapses.

**Answer: they disagree, and that is why the encoder had to be ours.** This spike
is three hand-written probes — `jvmjson.clj`, `gojson.go`,
`encoder_contract_cljgo.clj` — comparing host encoders directly.

## Status: SUPERSEDED, kept as the record

The resolution is `koine.json`, whose `write-str` **sorts keys**, which is the
single property that makes byte-comparison possible across hosts and across
runs. Every later spike depends on it; that they diff to zero on all three modes
is the standing proof.

Two encoder questions this spike raised are *still* not settled from inside
koine, and they are recorded here rather than lost:

1. **Non-ASCII key sort order** — undefined by anything either host guarantees.
2. **Whether an integral float emits `1.0` or `1`** — this changes byte length,
   and it crosses the wire into LLM request bodies, so it is not academic.

Both were re-raised with koine on 2026-08-01 as the limit of deriving an expected
byte count by arithmetic from the encoder's own rules: a hand computation
inherits any error in the rules it derives from.

**Do not extend it.** Superseded by: `koine.json` and S15/S19's byte assertions.
