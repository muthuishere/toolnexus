# Spike report — Clojure (`spikes/classifier/clojure/`)

Date 2026-09-20 · `clojure` CLI 1.12.5 (JVM) · `cljgo` (AOT binary + interpreted) · koine 0.11.0.
Source: one `src/toolnexus/classifier.cljc`, **0 reader conditionals**, **0 host interop**,
dependencies = `clojure.core` + `koine` — the port's existing deps.edn, nothing added.

## 1. Verdict

**Feasible, no friction worth naming.** All four gate items pass, and they pass **byte-identically
on all three modes** (JVM · cljgo AOT · cljgo interpreted) from the same source. The live call
works on both hosts. The dual-host constraint cost nothing here: everything this seam needs
(sorted-key JSON, raw HTTP, env-by-name) was already solved in koine for the shipped port.

## 2. The four gate items

| # | gate | result | proof |
|---|---|---|---|
| 1 | byte-exact request | **PASS** | `clojure -M -e '…(spit "/tmp/built.json" (request-body …))'` → `shasum -a 256` = `d5fa5c11…5daf` = `fixture/request.sha256`; `cmp /tmp/built.json ../fixture/request.json` identical; 514/514 bytes |
| 2 | parse | **PASS** | `./run-both.sh` → `gate2`: `noul` 0.98, `choice` "shipping", `choice_probs` 3 keys, `score` 1.21, `legend.2` "urgent"; asking a choice answer for `noul` throws |
| 3 | one judge, wired | **PASS** | `./run-both.sh` → `gate3`: allow/deny/ask = `allow` / `refused: destructive or irreversible` / `needs your say-so…`; the three states also re-emit `fixture/guard-{allow,deny,ask}-request.json` byte-for-byte (`request_matches_fixture: true`) |
| 4 | invariant | **PASS** | `gate4`: `judge_ran: 0`, result `denied: blocked by policy`. Also run against the **real shipped composer**: `clojure -Sdeps '{:paths ["src" "jvmonly" "../../../clojure/src"]}' -M -m toolnexus.realloop` → `{:real-fn toolnexus.agents.loop/guarded-hooks … :pass true}` |
| 5 | live (optional) | **RAN** | JVM **537 ms**, cljgo interpreted **1326 ms**, both `typesafe/jev-1.13-20260917`, `choice` "shipping", answer keys exactly the three asked. Shape matched with no adaptation. |

Dual-host gate (the thing that is actually at risk for this port):

```
== diff
  jvm == cljgo-aot  (byte-identical)
  jvm == cljgo-run  (byte-identical)
```

The diffed report contains the gate-2 values, so float formatting is inside the byte comparison,
not asserted separately.

## 3. LOC (code lines, blank + comment excluded)

| unit | LOC |
|---|---|
| `Classifier` — 3 question constructors, `request-body`, `parse-decision` + 7 accessors | **44** |
| backends — `static-classifier` (5) + `systemone-classifier`, raw HTTP (19) | **24** |
| `judge` — `bands` + `judge` + `as-guardrail` | **25** |
| first-deny-wins replica (the real one is 13 lines in `agents/loop.cljc`) | 13 |
| gate harness / fixtures / `-main` (throwaway) | 120 |
| file total | 336 lines incl. comments |

## 4. The union problem — the finding

**It is not a problem in Clojure, and that is measurable, not rhetorical: the three `criteria`
shapes and the discriminated `answers` map together cost zero lines beyond the three
constructors.**

- `Question` is a map with a `"type"` key. The three `criteria` shapes are *an absent key*, *a map
  value*, *a vector value* — which is exactly how JSON spells them. `koine.json/write-str`
  dispatches on the runtime value (`map?` → object, `sequential?` → array), so no wrapper type,
  no marshaller, no `oneOf` codec, no `omitempty` equivalent exists anywhere in the file.
- The array/object distinction is **structural**: the encoder sorts map keys and never reorders a
  sequential, so "score criteria order is the level numbering" is enforced by the data type rather
  than remembered by a rule. A port that modelled score criteria as a map would have to fight this.
- `answers` is a heterogeneous map; reading is `(get-in d ["answers" "urgency" "score"])`. No cast,
  no visitor, no type switch.
- **What is lost** is compile-time safety, and it is the only cost. Recovered at 4 lines: `expect`
  checks `"type"` and throws with a useful message, so `(noul-value <choice answer>)` fails loudly
  (asserted in gate 2). A static port gets that from the compiler; this port gets it at runtime for
  4 lines. No spec/schema library was needed (and none could be used — ADR 0009 measurement 2: every
  Clojars library carries Java interop, so any dependency would make the port JVM-only).
- Question keys are kept as **strings**, not keywords, so a caller's key may be a tool/skill name
  verbatim (`"git.push"`). Decoding uses `{:key-fn str}` — koine's `read-str` keywordises by
  default, which would have mangled `legend` keys `"0"/"1"/"2"`. That is the one trap.

## 5. Canonical JSON

- **Sorted natively — nothing hand-rolled.** `koine.json/write-str` sorts object keys recursively
  **by code point** (not by the host's string order, which diverges above the BMP), emits compact
  `,`/`:`, no trailing newline, non-ASCII literal. Byte-identical output across hosts is koine's
  stated reason to exist; this spike is one more datum for it.
- **Float formatting: verified on both hosts, no risk found.** `1.21` re-emits as `1.21` and the
  integral probability `0` re-emits as `0` (it parses as a long, so it does not become `0.0`) —
  both values are inside the byte-identical three-mode diff. koine's rule: integers print as-is,
  a float always keeps a fraction (`1.0` never collapses to `1`). The number parser is koine's own
  (regex-validated token → `read-string`), so neither host's decoder is in the path.
- The fixture request contains no floats, so gate 1 does not exercise this; gate 2 does.

## 6. Friction

Nothing blocking. Three notes for the seven-port plan:

1. **`:ask` has no shipped spelling.** `Guardrail` is `"allow"` | reason-string
   (`clojure/src/toolnexus/agents/loop.cljc:33-52`) — two-valued. A judge's natural third verdict
   has to degrade to a deny with an asking reason (what `as-guardrail` does here). If `judge` ships,
   either §10 suspension carries "ask" or the guardrail contract gains a third value — **and that
   is a cross-port SPEC decision, not a Clojure one.**
2. **No SDK, and none needed.** `koine.http/request` + a bearer header is 19 lines including error
   handling; it returns transport failures as data, so the retry/`onError` reuse ADR 0020 D3 wants
   is already expressible.
3. **`Classifier` is a function, not a protocol.** `(fn [state questions] -> decision)`, matching
   how `tool/tool` returns a map with an `:execute` fn. `defprotocol` was deliberately avoided —
   it is one of the constructs most likely to differ between hosts, and it buys dispatch `evaluate`
   does not need.

## 7. Reproduce

```
cd spikes/classifier/clojure
./run-both.sh                                  # gates 1-4 on JVM + cljgo AOT + cljgo interpreted
clojure -Sdeps '{:paths ["src" "jvmonly" "../../../clojure/src"]}' -M -m toolnexus.realloop
clojure -M -e "(require 'toolnexus.classifier)(prn (toolnexus.classifier/gate-5-live))"   # needs OPENROUTER_API_KEY
```
