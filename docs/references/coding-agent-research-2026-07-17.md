# Coding-agent research — what toolnexus should steal (2026-07-17)

Four-agent research pass over the ctx-optimize stores of **opencode**, **codex** (OpenAI,
Rust), **vscode-copilot-chat**, and **enterprisewebagent** (our own Java Claude-Code
re-implementation). Goal: pin the design for (a) a **built-in coding-tools pack** and (b)
**composable sub-agents**, feeding the reconciliation of ADR-0005 with the
`add-agent-pipeline` OpenSpec change (branch `propose-agent-pipeline`).

All citations below are file:line inside the respective repo.

---

## A. Where the four agree (convergent design — adopt with confidence)

1. **Subagent = child run of the same loop, fresh transcript, only the final text returns.**
   - opencode: child session with `parentID`, result = last text of final assistant message,
     wrapped `<task_result>` (`tool/task.ts:199,64-79`).
   - copilot: `search_subagent`/`execution_subagent` each spin a dedicated ToolCallingLoop,
     filtered stream, only excerpts return; `subAgentInvocationId` links parent call → child
     trajectory (`executionSubagentTool.ts:57-78`). Subagents exist explicitly as **context
     firewalls**.
   - codex v2: `spawn_agent(task_name, message)` + `wait_agent`; default spawn semantics =
     inherit parent type with full-history fork (`multi_agents_spec.rs` v2 L18).
   - enterprisewebagent: delegation = one scoped TurnEngine turn, context passed = task
     string only (`DefaultWorkerOrchestrator.java:43-90`).
   → Confirms ADR-0005 G1+G3 exactly.

2. **Tool scoping per agent = filter/permission rules, not new machinery.**
   - opencode: no per-agent tool lists at all — one merged `(permission, pattern) →
     allow|ask|deny` ruleset; subagents inherit parent *denies* only, and `task`/`todowrite`
     are denied to subagents unless opted in — recursion control **without a depth counter**
     (`agent/subagent-permissions.ts:14-27`).
   - enterprisewebagent: role = tool subset + prompt key; explore/verify roles are read-only
     (`AgentDefinitionFactory.java:7-31`); depth guard maxDepth 3 (`AgentContext.canNest()`).
   - copilot: per-agent tool budgets + virtual-tool grouping (below).
   → Confirms ADR-0005 G2's "reuse the existing allowlist filters" approach; opencode adds
     the deny-task-by-default recursion rule as an alternative/complement to a depth cap.

3. **Every tool output is budget-truncated, and truncation is visible to the model.**
   - opencode: universal 2000-line/50 KB cap, overflow spooled to disk with an agent-aware
     "delegate to an explore agent" hint (`tool/truncate.ts`).
   - codex: token-denominated budgets, **middle truncation** preserving head+tail, reports
     `original_token_count` so the model can re-request (`utils/output-truncation`).
   - copilot: per-tool-result cap = 0.5 × model window (`agentPrompt.tsx:87`).

4. **Fan-out is free once tool calls run in parallel** (all four; toolnexus already has
   parallel tool execution per SPEC §8) — parent emits N task calls in one turn.

5. **Plan/todo tool is table stakes** (opencode `todowrite`, codex `update_plan`, copilot
   `manage_todo_list`, our `todowrite` §4A — already shipped ✓).

## B. Built-in coding-tools pack v1 (proposal for `builtins`)

Small, dependency-free, byte-identical across 6 ports. Behavioral spec assembled from the
best implementation of each:

| tool | model on | key contract |
|---|---|---|
| `read` | opencode `read.ts` | offset/limit, default 2000 lines · 2000 chars/line · 50 KB; numbered `N: line` output + "use offset=X to continue" footer; not-found suggests ≤3 near-name matches |
| `write` | opencode/ewa | create/overwrite w/ diff in permission ask |
| `edit` | opencode 9-strategy cascade (`edit.ts:244-697`) + copilot's Gemini-derived healing | oldString/newString/replaceAll; unique-match required (ambiguous → error, `FileEditTool.java:44-49`); fuzzy cascade (line-trimmed, block-anchor+Levenshtein≥0.65, whitespace/indent/escape-normalized); CRLF/BOM preserved |
| `bash` | opencode + codex sessions | command/timeout/workdir; default 2 min; merged stderr; exit code in result; tail-ring truncation. **v2**: codex unified-exec (`{output \| session_id}` + `write_stdin`) for interactive/long-running |
| `glob` | opencode | ripgrep-style, limit 100, truncation notice, skip hidden |
| `grep` | opencode | regex + include filter, limit 100, grouped `file:` / `Line N:` output |
| `webfetch` | opencode `webfetch.ts` | url/format(text\|markdown\|html)/timeout; 5 MB cap, 30 s default |
| `patch` (v2) | codex `apply_patch.lark` | grammar-constrained context-diff (no line numbers): Add/Delete/Update/Move File envelopes; parser is one small crate, very portable |
| `task` | see C | the delegate tool |

Safety hooks that ride on the existing layers (not new tools):
- **BashSafetyAnalyzer** (ewa `BashSafetyAnalyzer.java:10-40`): tiny SAFE/MODERATE/DANGEROUS
  regex classifier → pre-tool hook → DANGEROUS suspends via §10 `waitFor` (approval as a
  `Request{kind:approval}` — cleaner than every competitor's bespoke approval UI).
- **Codex escalation triple** as the long-term shape: `sandbox_permissions`/`justification`/
  `prefix_rule` params; `Skip|NeedsApproval|Forbidden`; approve-once ⇒ session prefix rule.
- **Doom-loop detection** (opencode `processor.ts:522-545`): 3 identical tool calls
  (same tool + byte-identical args) → suspend/ask. One counter in the client loop.

## C. Composable sub-agents — answers to ADR-0005's open questions

- **QG1 (scope):** ship G1+G2+G3 together. Every reference implementation couples the
  delegate tool to a registry and isolation; G1 alone has no targets.
- **QG2 (config key):** namespace as `subagents` (local role) — distinct from §7A remote
  `agents`. codex/copilot treat local and remote as different surfaces too.
- **QG3 (local vs A2A unify):** keep **two tools** for v1 (`task` local, a2a remote).
  Nobody unifies them; copilot's `switch_agent` is a different concept (handoff).
- **QG4 (classifier):** hook-only (`shouldContinue`). Copilot's autopilot classifier is a
  model call we shouldn't make by default; codex/opencode don't classify either.
- **QG5 (`incomplete` status):** adopt. codex/copilot both surface limit-stops explicitly
  (copilot: `maxToolCallsExceeded` + continue-confirmation at limit×1.5).
- **Recursion control:** adopt opencode's rule — `task` denied to subagents by default,
  opt-in per agent — **plus** the ADR's maxSubAgentDepth as a hard backstop.
- **Registry format:** `AgentDef{name, description, systemPrompt, model?, tools?{mcp,skills,
  builtins}, maxTurns?}` per ADR-0005 G2, **plus markdown agent files** (opencode
  `agents/**/*.md`: frontmatter = config, body = prompt) — rides our existing SKILL.md
  parser machinery.
- **Result contract:** child final text only; `metadata = {agent, turns, toolCalls,
  totalTokens}`; parent usage aggregates child usage (ADR-0005 G3) — matches copilot's
  invocation-id-linked telemetry split.

## D. Distinctive ideas parked for later (own OpenSpec changes, not v1)

1. **Virtual-tool grouping** (copilot `virtualTools/`): collapse at 64 tools, trim at 96,
   hard limit 128, per-source groups + "activate group" meta-tool. toolnexus aggregates
   exactly the multi-source tool piles this solves; no competitor library has it.
   Alternative flavor: codex `tool_search` (BM25 over deferred tool metadata).
2. **Compaction ladder** (copilot 3-tier + codex mid-turn auto-compact, cache-prefix-aware
   `body_after_prefix` scope): context overflow as a typed event → summarize. Pairs with
   codex's model-visible `get_context_remaining`.
3. **Unified exec sessions** (codex): `session_id` + `write_stdin` for interactive procs.
4. **Edit healing telemetry / edit-survival tracking** (copilot) once `edit` ships.
5. **Plan mode as a tool filter** (ewa `PlanModeToolFilter`): planning phase = same loop,
   tools filtered to read-only + question. One-file feature once the pack exists.
6. **CSV fan-out jobs** (codex `spawn_agents_on_csv`, max_concurrency 16) — a batch-mode
   orchestrate; the `add-agent-pipeline` `orchestrate` combinator covers the general case.

## E. Recommended sequencing

1. **Reconcile** ADR-0005 into `add-agent-pipeline` (one OpenSpec change): pipeline layer
   is the substrate; `task` builtin + `subagents` registry + isolation + limits are the
   surface. Kill the standalone ADR path.
2. **Change 1 — `add-builtin-coding-tools`**: read/write/edit/bash/glob/grep/webfetch with
   the §B contracts + truncation service + doom-loop guard. Independent of subagents,
   immediately useful, easy 6-port parity (all behaviors are pure/deterministic).
3. **Change 2 — subagents** (reconciled): G1 task + G2 registry (+ md agents) + G3
   isolation + G4 limits/`incomplete`.
4. Later: virtual tools / compaction / unified exec / patch grammar as separate changes.
