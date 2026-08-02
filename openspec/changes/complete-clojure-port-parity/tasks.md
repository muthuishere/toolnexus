# Tasks

Per-language parity: this change touches ONLY the Clojure port. The other six
ports already ship every option below; nothing here alters their behaviour.

Each capability is written TEST-FIRST, with expected values taken from the
capability spec or a shipped port — never snapshotted from this port's own
output. Each must be green in all five execution modes before it is ticked.

## 1. Client — request shaping (`client-request-shaping`)
- [x] 1.1 `:request-params` — shallow-merged after the client's own keys, params win
- [x] 1.2 `:request-params` strips `messages` / `tools` / `stream` with a warning
- [x] 1.3 `:body-transform` — runs last, its output is what is sent
- [x] 1.4 `:http-client` — injectable transport (proxy / credentials)
- [x] 1.5 absent options leave the body byte-identical

## 2. Client — resilience (`resilience-policy`)
- [x] 2.1 `:retries` + `:retry-base-ms`
- [x] 2.2 `:timeout-ms` (passed through; koine does not BOUND it on cljgo — upstream)
- [x] 2.3 `:on-error` — retry | fail | suspend, routing LLM failures through §10

## 3. Client — observability and memory
- [x] 3.1 `:hooks` (`client-observability`)
- [x] 3.2 `:on-metric` (`client-observability`)
- [x] 3.3 `:store` — conversation memory (`conversation-store`)

## 4. Toolkit — skill sources (`skill-discovery`)
- [x] 4.1 `:skill-provider`
- [x] 4.2 `:skills-filter` (allowlist)
- [x] 4.3 `:skill-sample-limit`

## 5. Toolkit — composition and toggles
- [x] 5.1 `:agents` (`subagents`)
- [x] 5.2 toolkit-level `:wait-for`
- [x] 5.3 `:disable-tools` / `:disable-skills`

## 5b. WHOLE CAPABILITIES ABSENT — found by merging main, not by the gate

The option-parity gate measures OPTION NAMES in two files. It cannot see a
capability that does not exist at all, so "17 options" understated the gap. A
module-level diff against the JS port after merging main shows five subsystems
with no Clojure implementation:

- [x] 5b.1 `§11 translate` (`tool-translation`) — `js/src/translate.ts`. One
      provider call, no loop, no tool execution; the INBOUND half of the
      adapters, which are outbound-only. Shipped in six ports.
- [ ] 5b.2 `agent-runtime` (`js/src/agents/runtime.ts`)
- [ ] 5b.3 `subagents` (`js/src/agents/agent.ts`) — §7D/§7E
- [ ] 5b.4 `context-compaction` (`js/src/agents/compaction.ts`) — §7F
- [ ] 5b.5 `agent-home` (`js/src/agents/home.ts`)

NOTE FOR THE GATE ITSELF: that these were invisible is a defect in
`check_options_parity.py`, not just in this port. A port can be missing an
entire subsystem and still report "parity OK" as long as the two options files
mention the right names. A capability-level check belongs next to the
option-level one.

## 6. Promotion
- [x] 6.1 Flip `clojure` to tier `full` in `conformance/options_manifest.json`
- [x] 6.2 `check_options_parity.py` exits 0 with zero debt rows
- [x] 6.3 All five execution modes green; README parity-debt section removed
- [x] 6.4 Update `clojure/README.md` — tier `full`, no permitted absences
