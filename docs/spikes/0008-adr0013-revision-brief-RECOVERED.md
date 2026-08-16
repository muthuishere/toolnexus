<!-- RECOVERED 2026-08-16 from the agentic-nexus session transcript.
     The ADR 0013 revision this brief produced was written to the working tree,
     never committed, and lost during branch operations. The finished prose is
     gone; THIS is the brief plus all measured spike evidence it carried, which
     is what makes the revision reproducible. -->

Revise **toolnexus ADR 0013** using measured spike evidence. Repo: /Users/muthuishere/muthu/gitworkspace/nexus-workspace/toolnexus

File to EDIT (it exists, ~256 lines, untracked): `docs/adr/0013-model-free-tool-result-pruning-as-a-compaction-stage.md`

**Edit ONLY that file. Change no code. Commit nothing.** Sibling agents are working elsewhere.

## Precedent for HOW to revise
Read `docs/adr/0008-agent-runtime-must-expose-the-section-8-hooks.md` — its Status block carries "Two corrections to this document, both made after checking the code rather than reasoning about it", then lists them, then notes it was spiked before acceptance and what the spike turned up. Follow that shape exactly: keep the ADR a discussion document, but make the corrections prominent and unembarrassed. Add a **"Spike (measured)"** subsection near the top summarising the evidence, and correct the body inline so no reader can act on a superseded claim.

## The spike's measured evidence — incorporate all of it

**Setup:** 40 seeds × 4–15 turns, mixed payloads (file reads, Stripe-shaped JSON, MCP `search_files` output, small results) interleaved with normal turns, both dialects, ADR defaults (8192/4096/1024 + 39-code-point marker). Baseline goldens recorded from a pristine clone.

**Savings (over-budget set), consistent ~80% token reduction at every budget:**
| budget | over budget | summarize SKIPPED | tokens | bytes |
|---|---|---|---|---|
| 8 000 | 40/40 | 3/40 = 7.5% | 3 909 985 → 773 170 (−80.2%) | 16.33 → 3.28 MB |
| 20 000 | 38/40 | 23/38 = 60.5% | 3 885 803 → 762 489 (−80.4%) | 16.22 → 3.23 MB |
| 50 000 | 34/40 | 34/34 = 100% | 3 727 767 → 723 622 (−80.6%) | 15.55 → 3.06 MB |
| 100 000 | 20/40 | 20/20 = 100% | 2 744 208 → 509 846 (−81.4%) | 11.42 → 2.16 MB |
End-to-end: 38 summarize calls → 15. Dialect-symmetric to within 0.1%.

**Unset ⇒ byte-identical: MEASURED YES** — six scenarios (both dialects, under/over budget, flush-to-memory, unicode, no-boundary) against a 265 732-byte golden from the unmodified clone.

**Default validation:** 96.9% of all tool-result characters sit in results larger than 8192 code points, while 43.9% of results *by count* are under 2 KB. The 8192 default is well placed on this workload — the ADR should stop apologising for it and cite this.

## The SEVEN corrections to make

1. **STRIKE the "one real defect" / tool-pairing deferral entirely.** It says the anthropic dialect can orphan a `tool_result` and calls it a genuine defect for a future ADR. **It was already fixed** — commit `95d6071` (2026-08-15 22:17 IST), across all seven ports: `golang/agents/compaction.go:167-192` (`isBoundary`/`carriesToolResult`), and `SPEC.md:962-965` now states the rule as explicitly dialect-neutral. The ADR's own cited range (`compaction.go:99-122`) now contains a comment saying the opposite of the ADR. Verify this yourself before writing, then remove the deferral and the claim that the pruner is entangled with a dialect question.

2. **Rule 2 is self-contradicting and must be rewritten.** The ADR says the compactor "returns a no-op override if the transcript is now under budget — never reaching Summarize". In toolnexus a **nil override means the loop keeps the ORIGINAL messages** (`client.go:828-829`; `compaction.go:88-89` calls nil "byte-identical no-op"). Measured: 113 195 tokens → 23 687 after pruning, but returning nil sends all 113 195. The DeepSeek harness's `return null` does not port. Rule must read: return an override carrying the **pruned** transcript.

3. **Correct the "host still has the original" reassurance in rule 4.** A `beforeLLM` override IS `RunResult.Messages` and IS what the `ConversationStore` persists — `compaction.go:3-8` says so in its own header; four override call sites at `client.go:828,1099,1397,1655`. Once the pruner fires the truncated text is gone from the persisted transcript too. The rejection of the "prune in the client loop" alternative is still right on *timing*, but the reassurance is false for the store. Say so plainly.

4. **Add an open design point: the pass truncates the FRESHEST tool result in 20/40 transcripts, both dialects.** The summarizing compactor's design treats the tail as sacred (never summarized); the pruner rewrites the whole transcript including the tail, so the most recent tool result — the one the agent is mid-way through reasoning about — gets truncated whenever it is large. Structural, not probabilistic. Raise a "never prune the last N messages" carve-out as an open question; do not silently decide it.

5. **Composition changes where the summarizer splits, in 38/38 over-budget cases** — pruning first means more messages fit inside `keepTail`, so the retained tail is longer and the summarized head differs. Not a bug, but it means the composed path cannot be conformance-pinned independently of the pruner's three numbers, and it refutes the ADR's framing of this as a stage that merely runs "before" existing behavior. It *changes* that behavior's output whenever it fires.

6. **REVERSE the parity call — the ADR is wrong on both halves.** Ranking easiest→hardest is **Python → JS → Elixir → Java → Clojure → C#**.
   - **Elixir is near-easiest, not second-worst.** `String.slice/3` IS grapheme-based (confirmed), so the ADR correctly names the trap — but the fix is one stdlib line: `List.to_string(Enum.slice(String.to_charlist(s), 0, head))`. Naming the trap is the whole deliverable; cost is allocation, not correctness.
   - **Clojure is mid-pack, not worst.** `subs` is correct on cljgo (rune-indexed) but **silently wrong on JVM** (`(subs "ab😀cd" 0 3)` emits a lone surrogate). The hard half already ships: `koine.text/code-points` (`koine/src/koine/text.cljc:41-66`) is exactly the surrogate fold, re-exported at `clojure/…/tool.cljc:64-71`. Remaining work is a ~10-line `clojure.core`-only group-fold, run green on JVM Clojure 1.12.5, cljgo and let-go — three of ADR 0009's four hosts, no reader conditional. Glojure unverified (not installed) — state that as the honest gap.
   - **C# is the actual worst and the ADR never mentions it.** .NET ships NO code-point index API: `Substring`/`Length` are UTF-16 (trap one), `StringInfo`/`TextElementEnumerator` is grapheme-based (trap two — and it's the API that *looks* Unicode-correct in review). Correct route is `EnumerateRunes()` accumulating `Utf16SequenceLength` by hand, ~15 lines with unpaired-surrogate edges. Java gets one call (`offsetByCodePoints`).

7. **Fix the estimator-unit table: there are FOUR units, and one port disagrees with itself.** C# counts UTF-16 code units (`Compaction.cs:71`) — currently unlisted. Clojure counts `(count (json/write-str m))` (`compaction.cljc:50`) where koine emits non-ASCII literally, so the unit is **host-dependent**: `(count "ab😀cd")` is **6 on JVM, 5 on cljgo** — measured. That is a live parity defect *inside a single port*, sharper than the cross-port one the ADR describes, and it strengthens the ADR's case for pinning the new stage in code points.

## Also add
- **Unicode guarantees, stated precisely.** Measured: output is always valid UTF-8, never contains U+FFFD, and is always exactly `head + marker + tail` code points (5159 with defaults) while byte length varies 8 572–20 519 for the same 5159 code points — that variance IS the argument against specifying in bytes. **But grapheme clusters DO split**, with this witness: cutting `👨‍👩‍👧‍👦ABCDEFGH` at 4 code points yields a head ending on a **dangling ZWJ (U+200D)**. State it with the example — "clusters may split" understates it; the head can end on a combining character with nothing to combine with.
- **Quantify "often makes summarization unnecessary" with the budget-ratio regime**: ~100% skip at a ~5× budget, 7.5% at a very tight one. The skip rate is a property of the budget-to-transcript ratio, not of the pruner. Below roughly 5× budget it reliably ends compaction; at a budget so tight that even an 80%-smaller transcript doesn't fit, you have paid a full transcript rewrite for nothing.
- **Strengthen the Next gate fixture requirement**: an ASCII-only fixture passes in all seven ports *no matter which unit each picks*, so it is structurally blind to this entire bug class. The fixture MUST carry a supplementary character and a ZWJ cluster, with a cut landing mid-pair.
- Two smaller findings: the pruner is a **fixed point** under repeated application in all four configs tried including pathological ones (`head+tail+marker > threshold`) — so the prefix-cache claim holds, but that is luck, not design, and nothing constrains the three numbers relative to each other; and anthropic's `tool_result.content` is a *list of blocks* in the provider API while Go only ever writes a string (`client.go:1229`), so a string-only pruner is correct today but silently no-ops on a host-built transcript — worth one spec sentence.

## Requirements
- Verify the load-bearing claims yourself (especially #1 — check `95d6071` and the current `compaction.go` and `SPEC.md:962-965`) before writing them as fact.
- Keep Status as a discussion, but note it has now been **spiked and measured**, with the spike's location described as a throwaway clone (the user's checkout was never modified).
- Update the honesty note: the defaults are no longer the weakest part (they are now measured); say what is.

Report back: which corrections you verified vs took on trust, the new line count, and the single most important change a reader would miss if they'd read the old version. Do not paste the ADR.
