# Prior art: how other agent/LLM SDKs let a host interpose on TOOL EXECUTION

- **Status:** research note. No code, no ADR, no OpenSpec change. Input to the eventual ADR for
  the builtin-execution seam described in `docs/references/builtin-exec-seam-2026-09-22.md`.
- **Date:** 2026-09-22
- **Question asked:** toolnexus already has two seams — the transport seam for the LLM call
  (ADR 0019) and the in-process seam for sub-agents (ADR 0030). ADR 0019's standard is that a
  seam must *earn its surface*; it was cut down to near-nothing once seven spikes showed every
  port could already express the thing. Does a **third** seam, for built-in tool execution, meet
  that bar — or does an existing mechanism (an interception hook, or MCP) already solve it?

Everything below is from vendor documentation or source, cited inline. Where docs are ambiguous
it says so.

---

## TL;DR of the findings

1. **A generic "between the tool call and the tool run" hook is now standard** — but only five of
   the ten surveyed let it *redirect* execution (LangChain v1, Semantic Kernel, Pydantic AI,
   Mastra, Google ADK). The rest observe / approve / deny / edit input. **Anthropic's own SDK — the closest thing to
   our builtins — cannot redirect at all.**
2. **A redirect-capable hook is always ONE opaque shape**: you get `{name, args}` and you owe the
   framework a finished result. Using it to sandbox a builtin means reimplementing that builtin's
   result shaping in host code — the exact "hand-copy" outcome we are trying to avoid. The hook
   gives you *interception*, not *backend substitution*.
3. **Backend substitution for a built-in tool does exist, and the OpenAI Agents SDK has already
   shipped BOTH of our shapes** — `ShellTool(executor=…)` opaque, and `ApplyPatchTool(editor=…)` /
   `ComputerTool(computer=…)` structured, in the same SDK, split on exactly our reasoning (who owns
   the composition). Google ADK's `BaseCodeExecutor` and Mastra's sandbox providers are the other
   two, both opaque. **The two-shape split is no longer first-of-kind.** That is the single most
   design-relevant finding here.
4. **MCP does not solve this — but Anthropic explicitly says it is their answer**, which is the
   sharpest contradiction in the survey: "The search backend is not configurable. To search with a
   different provider, add an MCP server that exposes a search tool." The ADR must answer that
   sentence directly. Roots remain advisory ("Servers **SHOULD** … Respect root boundaries"), and
   swapping to an MCP server is the all-or-nothing reimplementation, not a backend swap.
5. **Nobody guarantees byte-identical capped listings across backends, and the inversion is
   striking**: the SDK with the best-documented listing behaviour (Claude Code — Glob mtime-sorted,
   cap 100, `truncated` flag) has no backend seam, and the SDK with real injectable backends
   (OpenAI) documents no determinism at all. Nobody ships both. Our §4A guarantee plus a seam would
   be genuinely new — which is the strongest case for the seam and the reason to be careful.
6. **We are missing a truncation flag.** Claude Code's `GlobOutput` carries `truncated`,
   `totalMatches`, `countIsComplete`; Grep carries `appliedLimit`/`appliedOffset`. §4A truncates
   silently. That is a defect worth fixing whether or not the seam ships.

---

## 1. Is there a hook between "the model asked for tool X" and "tool X runs"?

| SDK | Hook | Can it REDIRECT execution? |
|---|---|---|
| Claude Agent SDK (TS/Py) | `canUseTool(toolName, input, options) -> PermissionResult \| null` | **No** — `{behavior:"allow", updatedInput?}` or `{behavior:"deny", message}` only. Fires only when the permission flow falls through to a prompt |
| Claude Code hooks | `PreToolUse` → `permissionDecision: "allow"\|"deny"\|"ask"\|"defer"`, `updatedInput?` | **No**, and the docs say so outright (quoted below). `PostToolUse.updatedToolOutput` can rewrite a result *after* execution |
| Anthropic Tool Runner | none. `generateToolResponse()` is a **post**-execution result hook | **No** — only by starving the runner (inspect `tool_use`, `pushMessages()` a fabricated result before calling it) |
| LangChain v1 middleware | `wrap_tool_call(request: ToolCallRequest, handler) -> ToolMessage \| Command` | **Yes** — skip `handler`, return your own `ToolMessage` |
| Semantic Kernel | `IFunctionInvocationFilter.OnFunctionInvocationAsync(FunctionInvocationContext context, Func<FunctionInvocationContext, Task> next)` | **Yes** — don't call `next`, set `context.Result` |
| Pydantic AI | `WrapperToolset.call_tool(name, tool_args, ctx, tool)` | **Yes** — don't call `super().call_tool(...)` |
| Mastra | agent hook `beforeToolCall` → `{proceed:false, output:<matches outputSchema>}` | **Yes**, and explicitly framed as returning a *pre-defined result* |
| Vercel AI SDK | no tool-execution middleware; `wrapGenerate`/`wrapStream` are **model** middleware. Tool control is `needsApproval` on `tool()`/`dynamicTool()` + `onStepFinish` | **No** (people hand-wrap `execute`); a `GuardrailProvider` for tool-execution control is an open proposal, issue #13434 |
| LlamaIndex | no native pre-execution intercept layer; callbacks are effectively post-execution | **No** — open feature request #20386 asks for exactly this |
| Google ADK | `before_tool_callback(tool, args, tool_context)` — returning a dict **skips** the tool and uses that dict as the result | **Yes** |
| OpenAI Agents SDK | `tool_input_guardrails` run **before** invoking the tool; `ToolGuardrailFunctionOutput.reject_content(message)` / JS `{type:'rejectContent', message}` short-circuits with that string as the tool output. `on_tool_start` returns `None` (observe-only) | **Partly** — it can skip the tool and substitute a **string**, not a structured result. Plus genuine per-tool injected backends (§2) |

Exact signatures worth pinning:

**Claude Agent SDK** ([permissions docs](https://platform.claude.com/docs/en/agent-sdk/permissions)):

```ts
canUseTool: async (toolName: string, input: Record<string, unknown>) => PermissionResult
// PermissionResult = { behavior: "allow", updatedInput?: object }
//                  | { behavior: "deny",  message: string }
```

There is no `behavior: "result"`. The host can rewrite the *arguments* of a `Bash` call —
which is how sandbox wrappers get retrofitted in practice, e.g. rewriting `cmd` to
`docker exec … cmd` — but it cannot take over execution. **This is the single most relevant
negative datapoint in the whole survey**: the vendor whose builtins ours are modelled on
deliberately stopped at approve/deny/edit-input.

**Claude Code `PreToolUse`** ([hooks reference](https://code.claude.com/docs/en/hooks)):

```typescript
hookSpecificOutput?: {
  hookEventName: "PreToolUse";
  permissionDecision?: "allow" | "deny" | "ask" | "defer";
  permissionDecisionReason?: string;
  updatedInput?: Record<string, unknown>;
  additionalContext?: string;
}
```

The hook reference states the rule for us, verbatim:

> "You **cannot** supply a replacement tool result that short-circuits execution—the hook can only
> block, allow, or modify inputs."

Two adjacent mechanisms are close but are not it, and both are informative:

- **`"defer"`** — the tool does not execute; the process exits with `stop_reason:"tool_deferred"`
  and `deferred_tool_use:{id,name,input}` preserved, the host resolves it out of band, and on
  resume the hook must still answer `allow` or `deny`. **This is toolnexus §10 suspension, arrived
  at independently** — and note that even a durable out-of-band round trip does not earn the right
  to hand back a result. It also only works when the turn has a single tool call.
- **`PostToolUse.updatedToolOutput?: unknown`** — the one place a host may replace a tool's output,
  and it runs *after* execution. Rewrite-after, never redirect-instead.

**Anthropic Messages API Tool Runner** (`client.beta.messages.tool_runner`): no pre-execution hook
at all. `generateToolResponse()` runs the tools and returns the built `tool_result` for editing —
post-execution. The docs' own guidance
([tool runner](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-runner)):
"When you need human-in-the-loop approval, custom logging, or conditional execution, use the manual
loop instead." Worth noting for our own API docs: when a vendor's answer to interposition is
"drop to the manual loop", that is a gap, not a design.

**LangChain v1** ([reference](https://reference.langchain.com/python/langchain/agents/middleware/types/AgentMiddleware/wrap_tool_call)):

```python
def wrap_tool_call(self, request: ToolCallRequest,
                   handler: Callable[[ToolCallRequest], ToolMessage | Command]
                  ) -> ToolMessage | Command
```

Docs state middleware "can call the handler multiple times for retry logic, skip calling it to
short-circuit, or modify the request/response." That is a complete, generic redirect seam — and
it is the strongest "you don't need a new seam" argument available. See §7 for why it is not
sufficient for us.

**Semantic Kernel** ([filters docs](https://learn.microsoft.com/en-us/semantic-kernel/concepts/enterprise-readiness/filters)):
the docs say a function-invocation filter allows "Overriding of the function result, either before
(for instance for caching scenario's) or after execution", and "Without calling `next`, the
operation will not be executed." There is also a separate `IAutoFunctionInvocationFilter` scoped to
automatic function calling. **Note the two-filter split** — SK found one filter insufficient once
the call happened inside an agent loop; that is a small precedent for "one seam per meaningfully
different position", though not for two *shapes* of the same seam.

**Google ADK** `before_tool_callback` returning a dict short-circuits the tool. Same shape as
LangChain's.

---

## 2. Do any ship BUILT-IN shell/file tools with a swappable execution backend?

This is the question that matters, and the answer is: **three do — and one of them ships both of
our proposed shapes.**

### OpenAI Agents SDK — the match, including BOTH shapes

From `src/agents/tool.py` / `src/agents/editor.py` / `src/agents/computer.py`
([tool reference](https://openai.github.io/openai-agents-python/ref/tool/)):

```python
# OPAQUE shape
ShellExecutor = Callable[[ShellCommandRequest], MaybeAwaitable[str | ShellResult]]

@dataclass
class ShellTool:
    """Next-generation shell tool. LocalShellTool will be deprecated in favor of this."""
    executor: ShellExecutor | None = None
    name: str = "shell"
    needs_approval: bool | ShellApprovalFunction = False
    on_approval: ShellOnApprovalFunction | None = None
    environment: ShellToolEnvironment | None = None   # {"type":"local"} | container auto | reference

    def __post_init__(self):
        if environment_type == "local" and self.executor is None:
            raise UserError("ShellTool with local environment requires an executor.")
        if hosted and self.executor is not None:
            raise UserError("ShellTool with hosted environment does not accept an executor.")

# STRUCTURED shape
@runtime_checkable
class ApplyPatchEditor(Protocol):
    """Host-defined editor that applies diffs on disk."""
    def create_file(self, operation: ApplyPatchOperation) -> MaybeAwaitable[ApplyPatchResult | str | None]: ...
    def update_file(self, operation: ApplyPatchOperation) -> MaybeAwaitable[ApplyPatchResult | str | None]: ...
    def delete_file(self, operation: ApplyPatchOperation) -> MaybeAwaitable[ApplyPatchResult | str | None]: ...
```

Also `ComputerTool(computer=…)` taking a `Computer`/`AsyncComputer` ABC (`screenshot`, `click`,
`double_click`, `scroll`, `type`, `wait`, `move`, `keypress`, `drag`, …), and the legacy
`LocalShellTool(executor: LocalShellExecutor)` where
`LocalShellExecutor = Callable[[LocalShellCommandRequest], MaybeAwaitable[str]]`.

**Read that carefully, because it is our design already shipped.**

- The **tool name and wire schema are fixed by the SDK** (`shell`, `apply_patch`,
  `computer_use_preview`). Only execution is the host's. That is a backend swap, not
  disable-and-reimplement — the thing Anthropic does not offer and we want.
- **The seam's shape is chosen per tool, on exactly our criterion.** Shell is opaque because the
  library cannot interpret a command. `apply_patch` is structured because **the SDK parses the
  unified diff into `ApplyPatchOperation{type, path, diff, move_to}` and normalises the reply to
  `ApplyPatchResult{status, output}`** — the backend performs primitive operations and the library
  keeps the parsing and result shaping it is responsible for. Computer-use likewise. This is the
  same sentence as our design note's table, written by someone else, shipped.
- **Approval and backend are separate fields on the same tool** (`needs_approval`/`on_approval` vs
  `executor`/`environment`), and the constructor *enforces* that exactly one of executor-or-hosted
  is supplied. Both are directly applicable to our API: keep the two concerns apart, and validate
  the mutually-exclusive configuration at construction — which is also ADR 0030's ruling.
- **Their result type already carries our failure-kind distinction**: `ShellResult` →
  `ShellCommandOutput{stdout, stderr, outcome: ShellCallOutcome{type: "exit"|"timeout", exit_code}}`.
  A typed outcome discriminator, not an exit code overloaded to mean everything. Our three kinds
  (could-not-run / ran-and-failed / host error) are a superset; theirs is evidence the distinction
  is real and belongs in the result type rather than in an error channel.
- `ShellActionRequest.max_output_length` is handed to the backend as a **cap the backend is expected
  to honour**, with no statement about what gets dropped or in what order. That is precisely the
  hand-off our §4A rule forbids for listings — and it is what one-shape looks like when the library
  owns a limit but not the truncation.

**Consequence for the ADR:** the "nobody splits the seam" objection is dead, and the citation is
strong. The remaining originality in our proposal is narrower and more honest: OpenAI splits by
tool, one seam each, and never has to reconcile two shapes under one contract. Seriously consider
their spelling — `BuiltinExec` for `bash`, and a separate structured contract for the enumerating
and point-operation tools — rather than one seam with a mode flag. Two narrow, independently
droppable contracts beat one contract with a discriminated union, and each can be argued, spiked
and shipped on its own evidence.

### Google ADK — `BaseCodeExecutor` (a clean strategy-object precedent)

[`base_code_executor.py`](https://github.com/google/adk-python/blob/main/src/google/adk/code_executors/base_code_executor.py):

```python
def execute_code(self, invocation_context: InvocationContext,
                 code_execution_input: CodeExecutionInput) -> CodeExecutionResult:
```

Implementations: `BuiltInCodeExecutor` (model-side, Gemini's own), `UnsafeLocalCodeExecutor`,
`ContainerCodeExecutor` (Docker), `VertexAiCodeExecutor` (managed). Selection is one field:
`LlmAgent(code_executor=...)`.

This is **exactly our proposed `BuiltinExec`**, one level up: the agent-facing capability
(run code) is constant, the backend is a strategy object, `nil`/default ⇒ local. Community
pressure runs toward *more* backends, not fewer — open issues ask for GKE
([#2170](https://github.com/google/adk-python/issues/2170)) and QEMU microVM
([#4643](https://github.com/google/adk-python/issues/4643)) executors. A strategy interface is
what makes those additions possible without touching the agent contract. A strong second precedent for the seam
existing at all, after OpenAI's.

Two caveats we must not gloss:
- It is **one opaque shape**: code in, stdout/stderr/artifacts out. ADK has no listing tool whose
  ordering it owns, so it never faced our problem.
- `BuiltInCodeExecutor` is not really a backend — it delegates to the *model provider*, so the
  set of executors is not uniform. Worth avoiding: our `BuiltinExec` should not have a member that
  silently changes who runs the loop.

### Mastra — sandbox provider interface

[Sandboxes overview](https://mastra.ai/docs/sandbox/overview): 12 remote providers (AgentCore,
Apple Container, Blaxel, Cloudflare Sandbox, Daytona, Docker, E2B, E2B Desktop, Mastra, Modal,
Railway, Vercel) plus `LocalSandbox`, behind one implementable provider interface. Agents get
`execute_command`, `get_process_output`, `kill_process`. "Tool schemas remain consistent" while
the backend varies — the stated goal is custom providers "without modifying core tool definitions."

**But**: "Agents receive tools for the capabilities supported by the sandbox backend", and calling
an unsupported one fails with `SandboxFeatureNotSupportedError`. So the *tool set* varies with the
backend. That is precisely the drift our design forbids, and it is a concrete, shipped example of
what happens when the seam is allowed to change the surface the model sees. **Our ADR should cite
this as the thing not to do**: the seam must not be able to add, remove or reshape a tool.

### Anthropic Messages API client tools — identity kept, executor entirely yours

The one clean "our schema + your executor" case in Anthropic's stack, and it exists by accident of
history rather than design. Client tools with Anthropic-defined schemas (`bash`, `text_editor`)
"run in your application", and the text editor "is implemented as a schema-less tool… the schema is
built into Claude's model and can't be modified". You keep the tool's identity and supply 100% of
the implementation — **because they never shipped one**. Instructive for us in the negative: the
moment a vendor *does* ship an implementation (Claude Code's Bash, Glob, Grep), the option to
replace its backend disappears. We are in the second position, which is exactly why the seam has to
be deliberate.

### Everyone else

- **Claude Agent SDK / Claude Code**: builtins exist (`Bash`, `Read`, `Write`, `Edit`, `Glob`,
  `Grep`, `WebSearch`, …) and **there is no backend interface at all**. The vendor states the
  alternative explicitly, for WebSearch: *"The search backend is not configurable. To search with a
  different provider, add an MCP server that exposes a search tool."* So the supported path is
  `disallowedTools: ["Bash"]` plus your own tool via `createSdkMcpServer`/`tool()` — different name,
  different schema, different result formatting. All-or-nothing, exactly the hand-copying we are
  trying to avoid, and **exactly the thing our ADR must argue is not good enough**.
- **Claude Code sandboxing** is OS-level confinement of the *same* executor — `sandbox.enabled`,
  `sandbox.filesystem.{allowWrite,denyWrite,allowRead,denyRead}`, `sandbox.network`,
  `excludedCommands`, `allowUnsandboxedCommands`, macOS Seatbelt / Linux `bubblewrap`+`socat`
  ([sandboxing](https://code.claude.com/docs/en/sandboxing)). **This is the serious alternative to
  our seam and the ADR must address it**: if the library spawned its children inside a
  platform sandbox it configured itself, no host seam would be needed. We do not do this because
  seven ports cannot each carry a Seatbelt/bubblewrap/Job-Object implementation — but "we chose not
  to build the sandbox ourselves" is a much better stated reason than "there was no other way".
- **Anthropic Managed Agents — self-hosted sandboxes** are the closest thing anyone ships to our
  goal: `POST /v1/environments` with `config:{"type":"self_hosted"}` runs `bash`, `read`, `write`,
  `edit`, `glob`, `grep` plus custom/MCP tools **on your infrastructure** while Anthropic keeps
  inference and orchestration
  ([docs](https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes)). Note what it
  is and is not: it relocates *where the whole toolset runs*, chosen once per environment; it does
  not let you override one built-in's implementation while keeping its identity, and
  `web_search`/`web_fetch` still run on Anthropic's servers either way. Per-tool
  `permission_policy: always_allow|always_ask|auto`; `always_ask` pauses with
  `stop_reason.type:"requires_action"` and the reply is `result: allow|deny` + `deny_message` —
  **allow/deny, no result substitution**, consistent with everything else Anthropic ships.
- **LangChain**: community `ShellTool` / `FileManagementToolkit(root_dir=...)`. `root_dir` is
  enforced inside that toolkit's own implementation only; it is not a backend seam, and it does
  nothing for a shell.
- **Vercel AI SDK**: ships no builtins. Vercel Sandbox is a separate product you call from your
  own `execute`.
- **Semantic Kernel, Pydantic AI, LlamaIndex**: no builtin shell/file toolset at all, so the
  question does not arise. Their filter/toolset hooks are the general redirect discussed above.

### Summary table

| | pre-exec hook | synthetic result | builtin backend swap | determinism documented |
|---|---|---|---|---|
| Claude Agent SDK `canUseTool` | yes (fall-through only) | **no** | no | — |
| Claude Code `PreToolUse` | yes (every call) | **no — stated explicitly** | no (`disallowedTools` + MCP) | **yes: Glob mtime-sorted, cap 100, `truncated`** |
| Anthropic tool runner | no (post-exec result hook) | only by starving the runner | client tools = your executor by construction | no |
| Anthropic Managed Agents | `always_ask` → allow/deny | no | self-hosted sandbox (relocates the whole toolset) | 100k-char spill-to-file only |
| OpenAI Agents SDK | tool input guardrails | **yes — string only** | **yes — `executor=` / `editor=` / `computer=`** | **none** |
| LangChain v1 | `wrap_tool_call` | **yes** | n/a (no builtins) | no |
| Semantic Kernel | function-invocation filter | **yes** | n/a | no |
| Pydantic AI | `WrapperToolset.call_tool` | **yes** | n/a | no |
| Google ADK | `before_tool_callback` | **yes** | **yes — `BaseCodeExecutor`** | no |
| Mastra | `beforeToolCall` | **yes** | **yes — sandbox providers (tool set varies!)** | no |
| Vercel AI SDK / LlamaIndex | no | no | n/a | no |

**toolnexus would be the only row with a tick in both of the last two columns.**

---

## 3. MCP — does it already solve this?

**Taken seriously, because a major vendor says yes.** Anthropic's documented answer to "I want a
different backend for a built-in tool" is: *"The search backend is not configurable. To search with
a different provider, add an MCP server that exposes a search tool."* If that generalises, our seam
is unnecessary and the ADR should be a rejection. It does not generalise, for three reasons — and
the third is the only one that actually matters.

**(a) Roots are advisory by design.** The spec
([client/roots](https://modelcontextprotocol.io/specification/2025-06-18/client/roots)) says
"Servers **SHOULD**: … Respect root boundaries during operations; Validate all paths against
provided roots." SHOULD, not MUST — and the client cannot verify compliance, because the server is
code the client does not control. Roots are *context*, not a sandbox. They would not have stopped
the wfnexus escape: the escaping command was `cd <other repo> && git …` inside a shell, and a root
list is a suggestion to a cooperating server, not a mount namespace.

**(b) MCP has no execution-delegation primitive.** `tools/call` names a tool on a server; where
that server's computation runs is entirely outside the protocol. The client-side features that
*look* like inversion — `sampling` (server asks client for an LLM completion), `elicitation`
(server asks the user for input), `roots` (server asks the client for context) — all invert
**information**, never **execution**. There is no "run my tool in your sandbox" request. The
ecosystem's answer to isolation is a separate proxy/gateway/sandbox layer sitting beside MCP
(e.g. [tool-sandbox-mcp](https://github.com/domdomegg/tool-sandbox-mcp),
[awesome-mcp-gateways](https://github.com/e2b-dev/awesome-mcp-gateways)), which is a statement that
the protocol does not cover it.

**(c) "Just use a containerised MCP server instead of the builtins" is the all-or-nothing path we
already rejected.** Concretely, the official filesystem server
([README](https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem)) exposes
`read_text_file`, `write_file`, `edit_file`, `list_directory`, `search_files`, `directory_tree`, …
— **different names, different schemas, different result shaping** from our §4A ten, and no
documented sort order or truncation rule for `search_files`/`list_directory` at all. Swapping to it
means: the model's prompt changes, every conformance golden changes, and the capped-listing
guarantee disappears entirely. It is the definition of "disable and reimplement".

**The decisive point, and the one to put in the ADR.** Anthropic's advice works for `WebSearch`
because *nothing depends on that tool's exact identity*. Swap the provider and you get different
results, which is the entire point of swapping. It fails for our case because the thing we are
protecting is not the capability but the **contract**: the tool's name, schema, prompt wording and
— uniquely for us — §4A's byte-identical capped listing across seven ports. "Add an MCP server"
means changing all four. It is not a backend swap; it is a different toolset wearing the same job
title. The one-line version: *MCP lets you replace a tool. It does not let you replace a tool's
implementation.* That distinction is the whole proposal.

It is worth noting what MCP *does* prove: the filesystem server's access control is
allowed-directories enforced **inside the server process**, and roots, when supplied, "completely
replace any server-side Allowed directories." Enforcement lives with whoever executes. That is an
argument *for* our design — the party that runs the command is the only party that can constrain
it — and against any scheme where the library tries to police paths before handing them on.

**Conclusion for the ADR:** unlike ADR 0019, there is no existing mechanism here that makes the
seam unnecessary. MCP is adjacent, not overlapping. Say this explicitly, with the SHOULD quote,
because "why not just use an MCP server" is the first question a reviewer will ask.

---

## 4. Precedent for a seam with TWO shapes (opaque + structured)

**One real precedent, and it is OpenAI's — and it is much stronger than expected.** Everything
else is uniformly opaque:

- LangChain / Pydantic AI / SK / ADK / Mastra hooks: `(name, args) -> result`. Opaque.
- ADK code executors: `code -> {stdout, stderr, artifacts}`. Opaque.
- Mastra sandbox providers: `execute_command` and friends. Opaque.
- **OpenAI Agents SDK: both, and on our exact criterion.** `ShellTool.executor` is opaque
  (command in, `str | ShellResult` out, library does not interpret). `ApplyPatchTool.editor` is
  structured: the SDK parses the diff into `ApplyPatchOperation`s, the host performs primitive
  create/update/delete, the SDK normalises `ApplyPatchResult{status, output}`. `ComputerTool`'s
  `Computer` protocol is the same pattern with ten verbs. Different tools, different shapes, chosen
  by who owns the composition — spelled as **two independent seams on two tools**, not one seam
  with two modes.

**What the opaque-only ones lose** is not visible in their docs because none of them has a
guarantee to lose. OpenAI's `ShellActionRequest.max_output_length` is the visible tip: a cap handed
to the backend with no statement of what is dropped or in what order — a one-shape seam silently
delegating a truncation policy. The loss is conditional on owning a rule like §4A's capped listing:

> COLLECT every candidate → SORT by path relative to the walk root in code-point order →
> TRUNCATE to the cap.

If the backend also enumerates, sorts and truncates, then **every sandbox image becomes part of the
conformance surface**. Under a one-shape seam, `glob` in a container built on musl and `glob` on
the host can legitimately return different file *sets* for the same directory once the cap bites —
the clojure two-host divergence already measured this
(`{alpha-b.txt, mß.txt}` vs `{a-dir/zz.txt, alpha/f.txt}`). Nobody else measures it because nobody
else promises it.

The nearest outside-family precedent for the split is **content-addressed remote execution**
(Bazel's REAPI and similar): the client enumerates and canonicalises the input set, the worker only
executes, and determinism is the client's property precisely because enumeration never crosses the
boundary. That is the same instinct as our structured shape, arrived at for the same reason
(reproducibility), from a completely different domain. It is a real argument, but it is an analogy,
not prior art in this family — and the ADR should present it as such.

**The honest framing for the ADR:** both the principle *and* a working spelling of it are shipped
prior art. That removes the "first-of-kind" risk from the two-shape decision and moves the open
question to something smaller and more answerable: **one seam with two shapes, or two seams?**
OpenAI's evidence favours two. Two narrow contracts are independently arguable, independently
spikeable, independently droppable, and neither needs a discriminated union that every one of seven
ports must model identically. If the ADR keeps a single seam, it owes a reason why. The second shape is justified only by the §4A
guarantee, so the ADR's case must stand or fall on that guarantee alone. If a reviewer can show
that a one-shape seam plus a documented ordering *requirement on the backend* is adequate, the
second shape dies — and spike 1 in the design note ("a deliberately hostile enumeration order from
the seam must not change the output") is exactly the experiment that decides it. Keep it. It is the
only falsifiable claim in the proposal.

---

## 5. Determinism across execution backends

**No SDK surveyed makes this guarantee. One actively guarantees the opposite.**

Claude Code is the only SDK that documents listing behaviour at all
([tools reference](https://code.claude.com/docs/en/tools-reference)):

> "Results are sorted by modification time and capped at 100 files. If the cap is hit, Claude sees
> a truncation flag in the result and can narrow the pattern."

```typescript
type GlobOutput = {
  durationMs: number; numFiles: number; filenames: string[];
  truncated: boolean; totalMatches?: number; countIsComplete?: boolean;
};
```

with `totalMatches` "the number of matching files before truncation" and `countIsComplete: false`
meaning even that is a lower bound because the underlying search truncated its own output. Grep
(ripgrep-backed) carries `totalFiles`, `totalLines`, `appliedLimit`, `appliedOffset`, and
`files_with_matches` mode also sorts by modification time.

Sorting by mtime and capping is **non-deterministic across backends by construction** — a fresh
`git clone`, a `COPY` into an image, or a restored cache gives every file the same-ish mtime in
arbitrary order. The vendor chose relevance over reproducibility and did not treat the
difference as a defect.

Three things follow.

**The inversion worth naming in the ADR:** the SDK with the best-documented listing behaviour
(Claude Code) has no backend seam at all, and the SDK with real injectable backends (OpenAI)
documents no determinism whatsoever. **Nobody ships both.** Our proposal is to be the first, and
that is simultaneously the best argument for it (a genuinely new guarantee) and the reason spike 1
is mandatory (nobody has demonstrated the combination is achievable).

**And a defect of our own, found by comparison:** §4A truncates *silently*. Claude Code tells the
model `truncated`, `totalMatches`, `countIsComplete`; `Grep` reports `appliedLimit`/`appliedOffset`.
A model that cannot tell a complete listing from a capped one will confidently conclude a file does
not exist. This is independent of the seam, should be fixed regardless, and would go in `metadata`
so `output` stays byte-identical — but it becomes *more* urgent with a seam, because a backend is
one more place a cap can bite.

The remaining two cut in opposite directions:

- **For us:** this is the clearest evidence that our §4A rule is a real, differentiating property
  rather than a restatement of common practice, and that no one will hand us a backend that
  preserves it by accident. If the ordering decision crosses the seam, it is gone.
- **Against us:** it is also evidence that the industry does not consider listing determinism
  worth a guarantee. A reviewer can fairly ask whether §4A is a guarantee anyone has asked for
  outside this repo. The honest answer is that §4A already exists and is already enforced in CI
  across seven ports, so the question is not whether to acquire the guarantee but whether to
  *keep* it once execution moves — and quietly losing an enforced guarantee is worse than never
  having had it.

---

## 6. Is "route it elsewhere" ever a PERMISSION decision rather than a new seam?

Partly, and the pattern is instructive.

- **Claude Code sandboxing is the one real case, and it is instructive.** The docs frame
  *where a command ran* as *whether it needs approval*: "When a command **can** be sandboxed,
  Claude Code runs it inside the sandbox and approves it automatically, without asking your
  permission. Commands that **cannot** be sandboxed … fall back to the regular permission flow."
  `sandbox.autoAllowBashIfSandboxed` (default `true`) is literally that trade, and the escape hatch
  is an input parameter you can write a permission rule against: `Bash(dangerouslyDisableSandbox:true)`.
  So routing is not *expressed* as a permission decision — the routing is decided elsewhere, and the
  permission decision is *derived* from it. That ordering is the right one and we should copy it: a
  host that has installed a sandbox seam has thereby earned a weaker approval posture.
- **Anthropic's docs do list "redirect entirely"** among the possible responses to a tool
  request — and define it as *"use streaming input to send Claude a completely new instruction."*
  Redirection at the conversation level, never the tool level. Worth quoting: the only vendor to use
  our word for it means something else by it.
- **OpenAI**: `ToolGuardrailFunctionOutput.reject_content(message)` and approval
  `state.reject(i, {message})` both substitute a **string** for the tool result. A rejection channel
  that happens to short-circuit — not routing, and unable to carry `parts`, `isError` shaping, or
  our failure kinds.
- **Claude Agent SDK**: `{behavior:"allow", updatedInput}` lets a host *rewrite the tool's
  arguments*. In practice this is how people retrofit a sandbox — rewrite `Bash`'s `command` to
  wrap it in `docker exec`. It routes execution elsewhere **through the data**, not through a
  seam. It is the cheapest possible version of what we want, and it is exactly the guardrail class
  wfnexus ADR 0006 already ships and already labels as *not a sandbox*: string rewriting cannot
  contain `env`, a symlink, or a python one-liner. Worth naming in the ADR as the alternative we
  have already tried and measured.
- **Mastra**: `beforeToolCall → {proceed:false, output}` is literally an approval hook used as a
  result-substitution seam. Two mechanisms fused into one — which is convenient and also why
  Mastra's tool surface ends up backend-dependent.
- **LangGraph / HITL middleware**: interrupts support accept / edit / reject / respond. "Respond"
  substitutes a result, so a determined host can route through it — but it is a *human* gate, with
  checkpointer-backed persistence and resumption semantics, and using it for machine routing means
  paying for durable state we do not need.
- **Pydantic AI**: deferred/approval-required tools hand the call back to the caller to execute.
  That is arguably the cleanest "approval as routing" in the survey.

**Recommendation:** do not express our seam as a permission decision. Three reasons, all evidenced
above: (1) Anthropic, having built the biggest builtin toolset, deliberately did not allow a
permission decision to substitute a result; (2) Mastra shows the fusion leaking into the tool
surface; (3) our seam must carry the design note's **three failure kinds** (could-not-run vs
ran-and-failed vs host error), and a permission verdict has nowhere to put that distinction —
which is the very distinction wfnexus ADR 0012's retry policy depends on.

---

## 7. Things that CONTRADICT our design — stated plainly

1. **A generic tool-call middleware would cover the `bash` case completely.** LangChain, SK,
   Pydantic AI, ADK and Mastra all sandbox any tool with one generic interceptor and no
   per-tool-family API. A reviewer will ask why toolnexus does not simply ship
   `wrapToolCall(request, next)` — one seam, uniform, covers MCP tools, native tools and HTTP tools
   too, not just builtins. **This is the strongest alternative and the ADR must rebut it
   explicitly, not ignore it.** The rebuttal is narrow and must be stated narrowly: a generic
   interceptor hands the host `{name, args}` and demands a finished `ToolResult`, so for `glob`
   and `grep` the host must reimplement §4A's collect/sort/truncate — meaning the generic seam
   gives you sandboxing *at the cost of* the guarantee, while the proposed seam is shaped to keep
   it. If §4A did not exist, the generic interceptor would clearly win.
   - Note also: a generic interceptor is *strictly more useful* for the other tool sources. The
     ADR should say whether it is proposing the narrow seam **instead of** or **before** a generic
     one, and should not let the narrow seam quietly foreclose the general one.
2. **The vendor closest to our builtins refused this capability.** `canUseTool` and `PreToolUse`
   stop at allow/deny/edit-input. Either they have a reason we have not found, or they simply
   solve sandboxing vendor-side (their own sandbox modes) because they control the runtime and we
   do not. The latter is the likelier reading — we ship a library, not a runtime, so our hosts have
   nowhere else to put the sandbox — but the ADR should acknowledge the divergence rather than
   assume it is an oversight.
3. **The two-shape spelling now has a competitor with precedent behind it** (§4). OpenAI reached
   the same principle but spelled it as two independent seams on two tools. A reviewer preferring
   that spelling has shipped prior art on their side; we would have none for the single-seam
   version. This is no longer "is the second shape justified" but "one seam or two", which is a
   better question and should be decided in the ADR, not deferred.
4. **Anthropic's documented answer to our exact question is "add an MCP server"** (§3). It is wrong
   for our case, for a reason that can be stated in one line, but it is a vendor statement from the
   vendor whose builtins we ported, and a reviewer will find it. Quote it and answer it.
5. **Claude Code's platform sandbox is a strictly better fix where it is available** (§2). Seatbelt
   / bubblewrap confinement of our own child processes would need no host API at all. The reason we
   cannot is economic (seven ports, three OS families), not architectural — say that plainly rather
   than implying no alternative exists.
6. **Anthropic Managed Agents already relocates `bash`/`read`/`write`/`edit`/`glob`/`grep` to
   customer infrastructure** — chosen once per environment, not per tool. A reviewer can fairly ask
   whether a single "run the builtins over there" switch, rather than a per-call seam, is the
   right granularity. It is a genuinely simpler design and the ADR should say why per-call wins
   (it does not need to: our `BuiltinExec` is set once at construction, which is the same
   granularity — so this one is answerable, and cheaply).
7. **The determinism guarantee is idiosyncratic.** §5. Claude Code went the other way on purpose.
8. **Mastra proves a sandbox seam can corrupt the tool surface** (`SandboxFeatureNotSupportedError`,
   capability-conditional tool sets). Our seam must be provably incapable of that; a conformance
   test that the tool list and schemas are byte-identical with and without the seam belongs in the
   spike list.
9. **No evidence is offered anywhere that a second consumer wants this.** Every citation above is
   about *capability*; the demand side is one consumer (wfnexus). ADR 0019 was cut down precisely
   when its justification turned out to be "uniformity and one cookbook page" rather than
   capability. This proposal is the mirror image — it *is* a capability change (no port can express
   it today) — but a single consumer is a thin base for a seven-port contract.
10. **OpenAI's layering contradicts any urge to fold approval into the seam**: `ShellTool` keeps
   `environment` (where it runs) and `needs_approval`/`on_approval` (whether it may) as separate
   fields. If our design ever drifts toward one callback doing both, that is the counterexample.

---

## 8. Does it earn its surface by ADR 0019's standard?

ADR 0019's standard, as it actually resolved: **a seam must be a capability change, not a
uniformity change; the claimed justification must survive spikes; and if the ports can already
express it, the seam is a cookbook page, not an API.**

**By that standard the seam passes the first test and has not yet taken the others.**

- **Capability, not uniformity — passes, and this is the real difference from 0019.** No port can
  express sandboxed builtin execution today. `CreateBuiltinTools()` closes over `exec.Command` and
  `os.ReadFile`; the only host-side move is disable-and-reimplement-ten-tools, which discards the
  prompts, schemas and §4A ordering. ADR 0019 failed its gate because Java turned out to be 94 ugly
  lines away from the capability; here the distance is not 94 lines, it is all ten tools plus a
  conformance guarantee. ADR 0030 is the better analogy: it found a genuine cross-language contract
  gap and shipped, and this is the same species of gap.
- **The problem is evidenced, not hypothetical.** A real agent ran `git checkout -b` / `git stash`
  / `git reset` in the platform's own repository. `workdir` is an initial directory, not a
  boundary. Nothing in the survey offers a fix from outside the tool: argument rewriting is a
  guardrail (Anthropic's `updatedInput`, wfnexus ADR 0006), roots are advisory, MCP is a different
  toolset.
- **Not yet spiked — so by 0019's own rule it is not ready.** ADR 0019's headline justification,
  signature and shape all died to spikes, and a revision's correction was then half-falsified by a
  later spike. The three spikes in the design note are well chosen; spike 1 in particular is
  falsifiable and load-bearing. **Do not fix an API before spike 1 runs.**
- **The second shape now has precedent, and the open question changed.** The opaque shape has three
  shipped precedents (OpenAI `ShellTool`, ADK `BaseCodeExecutor`, Mastra sandbox providers); the
  structured shape has two (OpenAI `ApplyPatchEditor`, `Computer`), drawn on our exact criterion.
  The survey therefore does **not** support "drop the second shape". It supports asking whether the
  two shapes should be **two separate seams**, as OpenAI spells it, rather than one. Spike 1 is
  still the deciding experiment for whether the listing tools need a structured contract at all —
  if a hostile enumeration order from the seam does not change the output, the listing seam is
  unnecessary and only `BuiltinExec` ships.

### The strongest argument against building it at all

**"Anthropic sandboxes the same builtins by confining their own child processes with Seatbelt and
bubblewrap, and tells anyone who wants a different backend to add an MCP server. You are proposing
a new seven-port API to reach an outcome the vendor reaches with no API at all — for one consumer,
to preserve a listing guarantee no other SDK makes, in a case (`bash`) where that guarantee does not
apply. Ship a platform sandbox, or ship nothing."**

Unpacked, it has three independent legs, and each must be answered:

1. **"Confine your own children instead."** The strongest leg. Claude Code adds zero API surface and
   gets a real boundary. Our answer is not architectural, it is economic: seven ports across macOS,
   Linux and Windows would each need a platform-confinement implementation, and every one of them
   becomes a new source of cross-port divergence in exactly the repo whose purpose is preventing
   divergence. A seam pushes that variance to the host, where it is one implementation per
   deployment rather than twenty-one per library. **That is a legitimate answer, but the ADR must
   make it and own it, not skip it.** It also concedes something real: for a single-language host,
   the vendor's approach is better.
2. **"Use MCP."** Answered in §3: MCP replaces a tool, not a tool's implementation. One line,
   quotable, decisive.
3. **"Then at most you need `bash`."** Five SDKs sandbox arbitrary tools with one generic hook, and
   `bash` is the escape vector while `glob`/`grep` are read-only — so the minimum is one opaque
   seam for `bash`, leaving the listing tools on the host where §4A is already proven. The rebuttal:
   **a sandbox that contains `bash` but leaves `read`/`write`/`edit`/`apply_patch` on the host is
   not a sandbox** — the model writes a script to the host filesystem and runs it. Once the mutating
   file tools cross the seam, `glob`/`grep` must too, because listing the host tree inside a
   sandboxed run is incoherent. So the seam cannot be reduced to `bash` — but this must be *argued*
   in the ADR, as the answer to this objection, not assumed in a table.

**Verdict:** the seam is worth an ADR and worth the three spikes. On this evidence the **opaque
exec seam is safe to commit to** — three shipped precedents, no alternative in this library's
constraints, a real measured escape behind it. The **structured listing seam is the open question**,
and spike 1 decides it; it also now has precedent (OpenAI's `ApplyPatchEditor`), so the likelier
error is under-building it, not over-building it. The ADR should additionally decide *one seam or
two*, and should not fix any signature before spike 1 runs — that is the discipline ADR 0019 paid
for the hard way, where the headline justification, the signature and the shape all died to spikes
and a revision's own correction was then half-falsified.

The thing this repo brings that nobody in the survey has is the measurement: we are the only
project here that has *observed* the divergence it is trying to prevent (clojure's two hosts,
different file sets, same directory, cap-before-sort). That is what makes the guarantee defensible
and what should carry the ADR.

---

## Sources

- Claude Agent SDK permissions — https://platform.claude.com/docs/en/agent-sdk/permissions
- Claude Code hooks reference — https://code.claude.com/docs/en/hooks
- Claude Code tools reference (Glob/Grep sorting + limits) — https://code.claude.com/docs/en/tools-reference
- LangChain `wrap_tool_call` — https://reference.langchain.com/python/langchain/agents/middleware/types/AgentMiddleware/wrap_tool_call
- LangChain custom middleware — https://docs.langchain.com/oss/python/langchain/middleware/custom
- Semantic Kernel filters — https://learn.microsoft.com/en-us/semantic-kernel/concepts/enterprise-readiness/filters
- Pydantic AI toolsets / `WrapperToolset` — https://ai.pydantic.dev/toolsets/
- Google ADK `BaseCodeExecutor` — https://github.com/google/adk-python/blob/main/src/google/adk/code_executors/base_code_executor.py
- Google ADK built-in tools — https://adk.dev/tools/built-in-tools/
- ADK executor backend requests — https://github.com/google/adk-python/issues/2170 · https://github.com/google/adk-python/issues/4643
- Mastra sandboxes — https://mastra.ai/docs/sandbox/overview
- Mastra tool hooks — https://mastra.ai/blog/introducing-tool-hooks
- Vercel AI SDK tool calling — https://ai-sdk.dev/v5/docs/ai-sdk-core/tools-and-tool-calling
- Vercel AI SDK GuardrailProvider proposal — https://github.com/vercel/ai/issues/13434
- LlamaIndex tool middleware feature request — https://github.com/run-llama/llama_index/issues/20386
- MCP roots specification — https://modelcontextprotocol.io/specification/2025-06-18/client/roots
- MCP filesystem server — https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem
- MCP sandbox/gateway ecosystem — https://github.com/domdomegg/tool-sandbox-mcp · https://github.com/e2b-dev/awesome-mcp-gateways
- OpenAI Agents SDK tool reference (`LocalShellExecutor`, `LocalShellTool`, `ShellTool`, `ComputerTool`, tool guardrails, `is_enabled`) — https://openai.github.io/openai-agents-python/ref/tool/
- OpenAI shell tool guide — https://developers.openai.com/api/docs/guides/tools-local-shell
- OpenAI Agents SDK tools doc — https://github.com/openai/openai-agents-python/blob/main/docs/tools.md
- OpenAI Agents SDK tool guardrails (source) — https://github.com/openai/openai-agents-python/blob/main/src/agents/tool_guardrails.py
- OpenAI Agents SDK `ApplyPatchEditor` (source) — https://github.com/openai/openai-agents-python/blob/main/src/agents/editor.py
- OpenAI Agents SDK `Computer` (source) — https://github.com/openai/openai-agents-python/blob/main/src/agents/computer.py
- OpenAI Agents JS human-in-the-loop — https://openai.github.io/openai-agents-js/guides/human-in-the-loop/
- Claude Agent SDK TypeScript reference (`CanUseTool`, `PermissionResult`, `GlobOutput`) — https://code.claude.com/docs/en/agent-sdk/typescript
- Claude Agent SDK approvals / user input — https://code.claude.com/docs/en/agent-sdk/user-input
- Claude Code sandboxing — https://code.claude.com/docs/en/sandboxing
- Anthropic tool runner — https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-runner
- Anthropic tool use overview (client vs server tools) — https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview
- Anthropic text editor tool (schema fixed, executor yours) — https://platform.claude.com/docs/en/agents-and-tools/tool-use/text-editor-tool
- Anthropic Managed Agents self-hosted sandboxes — https://platform.claude.com/docs/en/managed-agents/self-hosted-sandboxes
- Anthropic SDK tool-runner helpers — https://github.com/anthropics/anthropic-sdk-typescript/blob/main/helpers.md · https://github.com/anthropics/anthropic-sdk-python/blob/main/tools.md
