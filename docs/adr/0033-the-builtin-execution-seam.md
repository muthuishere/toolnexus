# ADR 0033 — the builtin execution seam: two peer seams, the library always sorts, and it fails closed

- **Status:** **Proposed — evidence gathered, spikes not yet run. No code.**
- **Date:** 2026-09-22
- **Driver:** `CreateBuiltinTools()` closes over the host process — `exec.Command`, `os.ReadFile`
  and their six equivalents. A host cannot interpose. The only way to sandbox a toolnexus agent
  today is to disable all ten builtins and reimplement them, which discards our names, schemas,
  prompt wording, result shaping and §4A's capped-listing guarantee. The consumer forcing the
  question is **wfnexus** (`github.com/muthuishere/wfnexus`), which has a real sandbox requirement
  and a **real, observed escape**: on a live run an agent executed `cd <platform repo> && git log
  --all --grep=…`, then `git checkout -b`, `git stash`, `git reset` — in the platform's own
  repository. `workdir` sets the *initial* directory only; a `cd` in the command string leaves it,
  bash reports success, nothing errors.
- **Evidence:** five research documents written 2026-09-22, ~2750 lines, cited inline —
  `docs/references/builtin-exec-seam-2026-09-22.md` (the design agreed with the consumer),
  `seam-prior-art-coding-agents.md` (ten coding agents, source + advisories),
  `seam-prior-art-sdk-seams.md` (ten agent SDKs, interposition surfaces),
  `seam-prior-art-sandbox-services.md` (twelve sandbox/exec services, API shapes),
  `seam-prior-art-isolation.md` (the isolation primitives, **with primary measurement on this
  machine**). Where a claim below is a measurement it says *measured here* and points at the file.
- **Related:** ADR 0019 (one transport seam — the bar a new seam must clear), ADR 0030 (the
  in-process seam stops at the top-level client — the precedent for a real cross-language contract
  gap), `SPEC.md §4A` (the ten builtins and the capped-listing rule), wfnexus ADR 0006 (their
  `cd`/`git -C` guardrail, labelled by them as *not* a sandbox) and wfnexus ADR 0012 (their retry
  policy, which is what the failure taxonomy has to serve).

---

## Context

`SPEC.md §4A` ships ten builtins with identical names and input schemas in seven ports, and §4A's
capped-listing rule — **COLLECT every candidate → SORT by the path relative to the walk root in
plain Unicode code point → TRUNCATE to the cap** — is enforced in CI across all seven. That rule
exists because we measured the alternative: under a cap-before-sort mutation, clojure's two hosts
returned different file *sets* for the same directory (`{alpha-b.txt, mß.txt}` on the JVM vs
`{a-dir/zz.txt, alpha/f.txt}` on cljgo).

The escape wfnexus hit is not fixable from outside the tool, and this is now demonstrated rather
than asserted. *Measured here* (isolation §6), on macOS 26.4:

```
$ (cd work; /bin/sh -c 'cd ../outside && cat secret.txt')
secret                                    # exit 0 — the escape

$ sandbox-exec -f p.sb -D WORK=$PWD/work /bin/sh -c 'cd ../outside && cat secret.txt'
cat: secret.txt: Operation not permitted  # blocked
```

String-level guardrails are theatre and wfnexus's own ADR 0006 says so. One entry ends the
argument: **`env -C dir cmd`**, in coreutils on every box, whose manual advertises the property as
a feature. Beyond it, git exposes the same capability through three independent channels
(`--work-tree`, `GIT_WORK_TREE`, `core.worktree`), then `tar -C`, `make -C`, `find -execdir`,
`install -D`, a symlink, `python -c "import os; os.chdir(…)"`, or simply an absolute path and never
changing directory at all.

---

## Decisions

### D1 — Build it. It earns its surface by ADR 0019's standard.

ADR 0019's standard, as it actually resolved: **a seam must be a capability change, not a
uniformity change.** 0019 failed that gate because every port turned out to be able to express an
in-process transport already (Java, the claimed blocker, was 94 ugly lines). Here the distance is
not 94 lines — it is all ten tools plus a conformance guarantee. ADR 0030 is the better analogy: a
genuine cross-language contract gap, which shipped.

Three legs carry it:

1. **The half-seam is exploitable, not theoretical.** **CVE-2026-39861 / GHSA-vp62-r36r-9xqp
   (High 7.7)** in Claude Code: sandboxed Bash creates a symlink out of the workspace, the
   *unsandboxed file tool* writes through it, no prompt. The advisory's own sentence: *"Neither
   component could independently write outside the sandbox, but their combination could."*
   **CVE-2025-62353 (CVSS 9.8)** in Windsurf/Cascade: path traversal in `codebase_search` and
   `write_to_file` — arbitrary read *and write* anywhere on the filesystem, reachable by indirect
   prompt injection from a poisoned `README.md`, and **effective with Auto Execution OFF and
   `write_to_file` explicitly on the deny list**. The user had taken every deliberate step the
   product offers and none of them was on the path the tool took. That is what a half-seam costs.
2. **Four independent products ship the same omission, and it is always the listing tool.** Cline:
   a full `ToolExecutors` seam *except* `listFiles`. Goose: full ACP interception *except* `tree`
   and `read_image`, which fall through to the host. Continue: `throwIfFileIsSecurityConcern` on
   read/write/createNewFile but **not** on `grepSearch`/`globSearch`/`lsTool` — and
   **CVE-2026-8770**, where `dirPath: "/"` correctly prompted while `dirPath: "."` and `"../../"`
   did not and listed the filesystem root. opencode: `realPath` containment on read/edit, bare
   `path.resolve` on glob and grep. *The gap is always the tool nobody thought of as execution.*
3. **There is no alternative within this library's constraints.** MCP does not solve it: roots are
   **SHOULD**, not MUST, and the client cannot verify compliance; MCP has no execution-delegation
   primitive at all (sampling, elicitation and roots invert *information*, never *execution*); and
   the official filesystem server has different names, different schemas, different result shaping
   and no documented sort order. *MCP lets you replace a tool. It does not let you replace a tool's
   implementation.* A generic `wrapToolCall(request, next)` middleware — which LangChain v1,
   Semantic Kernel, Pydantic AI, Google ADK and Mastra all ship — hands the host `{name, args}` and
   demands a finished `ToolResult`, so for `glob` and `grep` the host must reimplement §4A's
   collect/sort/truncate. **The generic seam buys sandboxing at the cost of the guarantee.**

**The strongest counter-argument, stated honestly, and the half of it that is valid.**

> *"Anthropic sandboxes the same builtins by confining their own child processes with Seatbelt and
> bubblewrap, and tells anyone who wants a different backend to add an MCP server. You are
> proposing a new seven-port API to reach an outcome the vendor reaches with no API at all — for
> one consumer, to preserve a listing guarantee no other SDK makes."*

**The valid half is real and we concede it: for a single-language host, confining your own children
is the better answer.** It adds zero API surface and produces an OS-enforced boundary rather than an
interposition point. If toolnexus were one library in one language, this ADR would be a rejection
and the work would be a Seatbelt/bubblewrap/Job-Object implementation.

**Our answer is economic, not architectural.** Seven ports across macOS, Linux and Windows is
**twenty-one platform-confinement implementations**, in the repo that exists to prevent divergence —
and the isolation research shows exactly how divergent they would be: Seatbelt is deprecated since
~10.8, its profile language has never been documented for third-party use, it **cannot nest** inside
an already-sandboxed host (`sandbox_apply: Operation not permitted`), and macOS 26 is actively
breaking consumers. Landlock is kernel-5.13+, must be in `CONFIG_LSM` or `lsm=`, is thread-scoped
before ABI 8, and **Ubuntu's enablement request expired unresolved in April 2026**. Unprivileged
user namespaces are restricted by default on Ubuntu 24.04 and forbidden outright by the RHEL 9 STIG.
Windows has no in-process confinement call at all — restricted tokens, integrity levels, AppContainer
and LPAC are *spawn-time* attributes, and the one in-process API,
`SetProcessMitigationPolicy`, is one-way and would break the host (`DynamicCode` kills every JIT,
i.e. .NET, Node and the JVM — three of our ports). WASI is the only portable in-process sandbox and
**cannot spawn a process at any phase**, so a `bash` builtin cannot exist inside it.

A seam pushes that variance to the host: **one implementation per deployment, instead of twenty-one
per library.** The honest framing is *"we chose not to build the sandbox ourselves"*, which is a far
better stated reason than *"there was no other way"*.

**We are not first, and should stop implying we are.** The OpenAI Agents SDK already ships
`ShellTool(executor=…)` and `ApplyPatchTool(editor=…)`; Google ADK ships `BaseCodeExecutor` with
local / Docker / Vertex implementations selected by one field; Mastra ships thirteen sandbox
providers behind one interface. What nobody ships is a backend seam **together with** a determinism
guarantee — the SDK with the best-documented listing behaviour (Claude Code) has no backend seam,
and the SDK with real injectable backends (OpenAI) documents no determinism at all.

### D2 — Two peer seams, not one seam with two modes.

An **exec seam** (opaque: argv in, bytes + exit out; the library does not interpret the command) and
a **filesystem seam** (structured operations, including ENUMERATE). Peers, each independently
arguable, spikeable and droppable — not one contract with a discriminated union that seven ports
must model identically.

The precedent is two products that arrived here independently:

- **Codex CLI** — `ExecBackend` and `ExecutorFileSystem` are **sibling traits**
  (`codex-rs/exec-server/src/process.rs:199,223` and `codex-rs/file-system/src/lib.rs:627`), each
  with local / sandboxed / remote implementations, and **every file method takes an explicit
  `sandbox` parameter** — the sandbox context is an argument of the operation, not an ambient
  property of the process.
- **OpenAI Agents SDK** — `ShellTool(executor=)` is opaque
  (`ShellExecutor = Callable[[ShellCommandRequest], str | ShellResult]`), `ApplyPatchTool(editor=)`
  is structured (the SDK parses the unified diff into `ApplyPatchOperation{type, path, diff,
  move_to}`, the host performs primitive create/update/delete, the SDK normalises
  `ApplyPatchResult{status, output}`), and `ComputerTool(computer=)` is the same pattern with ten
  verbs. **Split on our exact criterion — who owns the composition — and spelled as two independent
  seams, not one seam with a mode flag.**

**Head-on: most sandbox services have no enumeration primitive.** This is true and it is the
sharpest objection to D2. AX's guest `FileSystemService` has **only `ReadFile` and `WriteFile`**;
Fly Machines' OpenAPI has **no file endpoints at all** (files go via `fly sftp`, a CLI); Northflank
documents none. Even where a listing call exists, only E2B, Cloudflare and Daytona can recurse in
one call, and **only Daytona ships anything resembling our glob/grep pair**
(`search_files(path, pattern)` and `find_files(path, pattern) -> [{file, line, content}]` — which is
almost exactly our "per-hit data for grep", and is the existence proof that the two listing tools
*can* be expressed as enumeration).

**That is the adapter's business, and it does not touch the guarantee.** An adapter with only `bash`
implements ENUMERATE by running `find` — one method calling the other, in the host's tree, where it
is visible and testable. What matters is that **the ordering still happens library-side regardless**:
whether the bytes came from `find`, from `files.list(depth=)`, or from the host process, the library
collects the full set, sorts by path relative to the walk root in code point, and *then* truncates.
The seam's shape decides where the adapter's work sits; D3 decides where the guarantee sits, and D3
is unconditional.

Two things the seam must **not** carry, both earned:

- **No policy, limits or budget field on the command.** `google/ax` tried putting budget and
  approval config in the same message as the command and pulled it back out —
  `TaskSpec` carries `reserved 9; reserved "policies";` with the comment *"removed for now"*, while
  the orphaned read-side messages (`TaskStatus.pending_approval`, `UsageStats`) are still in the
  proto. The one project that tried it reserved the field number instead.
- **Where it runs and whether it may run stay separate fields.** OpenAI keeps `environment` /
  `executor` apart from `needs_approval` / `on_approval`, and its constructor *enforces* that
  exactly one of executor-or-hosted is supplied. Mastra shows the fusion leaking: `beforeToolCall →
  {proceed:false, output}` is an approval hook used as a result-substitution seam, and Mastra's tool
  surface ends up backend-dependent (see spike 2).

### D3 — The library always sorts and truncates. Never the sandbox, never the adapter.

Unconditional, for every listing that reaches the model: `glob`, `grep`, the `<skill_files>` sample,
and any future one.

**The evidence is a measurement, not an argument.** *Measured here* (isolation §4): 300 files,
`f001.txt … f300.txt`, created in **identical order** on four backing stores; raw `readdir` order,
first 10:

| backing store | first 10 entries |
|---|---|
| host **APFS** (macOS 26.4) | `f277 f263 f288 f049 f075 f061 f129 f101 f115 f114` |
| same dir **via virtiofs bind mount** into the Linux VM | `f277 f263 f288 f049 f075 f061 f129 f101 f115 f114` |
| container **overlayfs** (image layer) | `f001 f002 f003 f004 f005 f006 f007 f008 f009 f010` |
| container **tmpfs** | `f300 f299 f298 f297 f296 f295 f294 f293 f292 f291` |

**Three mutually disjoint answers to "the first 10 files", from the same 300 names.** A cap of 10
shows the model a completely different file set depending on where the bytes happen to live. On a
10-name tree with mixed case and non-ASCII, tmpfs returned the **exact reverse** of the host order.

The transport is not the variable — virtiofs relayed APFS's order byte-for-byte, because a bind
mount is the same superblock and the same inodes. **The backing store is the variable, and crossing
into a sandbox is precisely the act of changing the backing store**, because everything not
bind-mounted is the image's overlayfs or a tmpfs.

And it is structural, not incidental: POSIX defines `readdir()` only as "an ordered sequence";
ext4 with `dir_index` traverses in **hash order salted per-filesystem** by `s_hash_seed`, randomized
by `mke2fs` (this caused a documented production outage — a classloader picked the wrong Bouncy
Castle jar, proven by hex-editing the seed until the orders matched); overlayfs re-orders by
construction, its in-tree comment reading *"Insert lowest layer entries before upper ones, this
allows offsets to be reasonably constant"*; APFS deliberately stopped being sorted (rdar://32799008,
*"`readdir` on APFS is not sorted"*); NTFS looks alphabetical and Microsoft disclaims it.

**And gVisor is worse than different — it is non-deterministic.**
`pkg/sentry/fsimpl/gofer/directory.go` emits remote children, then *synthetic* children by ranging
**a Go map**, whose iteration order Go deliberately randomizes, with no sort applied. Under gVisor
the same directory can enumerate differently **run to run on one machine**.

This upgrades the design note's claim. The note said *"if a container has to implement our ordering
rule to pass, the seam is in the wrong place."* The evidence says something stronger: **no container
could implement our ordering rule reliably even if asked.** Sorting in the library is not the
cleaner allocation of responsibility; it is the only allocation that can work.

Two independent surveys agree from the other side: **not one of the twelve surveyed sandbox services
documents an ordering guarantee, a cursor, a page token, or a cap on any listing call**, so there is
no ordering to inherit — if the library does not sort, nothing does. And every SDK that lets the
backend pick gets it wrong in a visible way: Continue passes the cap *into* the IDE
(`getSearchResults(query, 100)`), so the **host picks which 100**; opencode truncates at the ripgrep
stream head and **discards the `truncated` flag entirely**, with a v2 default of
`limit: Number.MAX_SAFE_INTEGER`; Cline caps an insertion-ordered `Set` mid-BFS and can return
partial results on a 10 s `Promise.race` with only a `Logger.warn`, so **identical inputs on a slow
disk give different listings**; OpenAI hands `ShellActionRequest.max_output_length` to the backend
with no statement of what is dropped or in what order.

**Correction to our own memory index, recorded here because it is load-bearing.** "Node's
`readdirSync` IS sorted" is mechanically true on POSIX (libuv routes it through `scandir(3)` with
`uv__fs_scandir_sort`, a plain `strcmp` comparator) and **dangerous to rely on**: it is
undocumented, it is **false on Windows** (`NtQueryDirectoryFile`), and it is **false for
`fs.opendir`/`dir.read()`**, so a mechanical refactor to the streaming API silently changes ordering.
It is an accident we do not depend on.

### D4 — Fail closed. If the configured seam cannot start, the run fails.

A configured seam that cannot start is a failed run, never a run that quietly proceeds on the host.

**We explicitly reject Claude Code's default.** `sandbox.failIfUnavailable` defaults to **`false`** —
*"if the sandbox cannot start … Claude Code shows a warning and runs commands without sandboxing."*
opencode's third-party sandbox wrappers are the same inversion, in their own words: *"if anything
goes wrong… commands run normally without sandbox."*

The precedent we follow is Devin's CLI: *"If sandbox resolution fails… the CLI will refuse to start
rather than running unsandboxed"*, so security intent is not *"silently bypassed."*

This is the same rule as §4A's own sorting discipline and for the same reason: **an absent control
looks exactly like a working one.** A fail-open seam is indistinguishable, from the outside, from a
seam that is holding.

### D5 — Never take a trust signal from the model.

Nothing the model writes may influence where, whether, or under what boundary a builtin runs. Three
products, three CVE-class defects, one mistake:

- **CVE-2026-52024** (Cline, Manifold Security): "Safe Commands" does not match a curated allowlist —
  it reads **`requires_approval: true|false`, a parameter the LLM writes on its own tool call**, and
  trusts it, *even after the same agent has been manipulated by attacker content*.
- **Goose `SmartApprove`** auto-allows on `read_only_hint: true` — a **self-declared** MCP
  annotation. Same defect class, second product.
- **GHSA-w5fx-fh39-j5rw (High 8.6)** (Codex): **the model-supplied working directory was accepted as
  the sandbox's writable root** — the seam's *policy input* came from the untrusted side. The fix
  canonicalizes and uses the boundary from where the user started the session.

Corollary, from Codex's **"Overpatch"**: do not derive permissions from the operation's own paths
either. `apply_patch` granted write access to the *parent directory* of each path in the patch, so a
patch entry referencing `/tmp` widened the grant to `/`. Our `apply_patch` routes through the seam;
it must not also compute a policy from the patch body.

The property to copy is Deno's: **permissions can only narrow at runtime, never widen.**
`Deno.permissions` exposes `query`/`request`/`revoke`, and `revoke` downgrades to `prompt`; there is
no API that grants. A host-supplied executor must never be widenable by the model or by the library.

### D6 — The failure discriminator is host-set, and defaults to "ran".

Three kinds, per wfnexus ADR 0012's retry policy: **could-not-run** (image pull failed, OOM kill ⇒
the host should retry the step elsewhere), **ran-and-failed** (non-zero exit ⇒ the *model* should see
the output and react), and **host error**. Collapsing the first two means a consumer cannot tell a
flaky worker from a bad command and will retry things that can never succeed.

**It cannot be derived, and that is measured.** *Measured here* (isolation §5), Docker 29.4.0:

| scenario | exit code |
|---|---|
| no such image | **125** |
| **app itself runs `exit 125`** | **125** |
| command found but not executable | 126 |
| command not found | 127 |
| OOM kill (`-m 16m`, runaway allocation) | 137 (128+SIGKILL) |

**"The sandbox could not run this" and "the command ran and chose to exit 125" are byte-identical.**
The same collision exists at 126 and 127, and 137 is ambiguous between an OOM kill, an external
`kill -9`, and a timeout enforced by SIGKILL. On macOS it is worse: *measured here*, a denied read
under a `deny file-read-data` Seatbelt profile surfaces as plain `EPERM` — `cat` exits 1 and **the
`sandbox-exec` wrapper exits 0**. A policy violation is invisible at the process-exit level.

So: **`BuiltinResult` carries a host-set outcome discriminator alongside the exit code**, and the
honest default is **`Ran`** — a library that guesses "125 means infrastructure" will misclassify a
program that exits 125. A host that cannot tell must say `Ran`. **Shape, don't infer.**

The shape to copy is **containerd's**, which never fuses the channels: task-*create* failure is a
gRPC error (`codes.NotFound`, via `errdefs`), while task *exit* is an **event that is never an
error**. That is exactly the `error` vs `BuiltinResult` split the seam signature already has; the
correction is only that the *kind* must be a field the host fills.

- **The anti-pattern is E2B**, and it is not a straw man — it is the most popular sandbox SDK in this
  space. **Non-zero exit RAISES `CommandExitException`, and that class subclasses
  `SandboxException`**, the infra-error base. The two channels are nested rather than parallel, so a
  naive `except SandboxException` converts "the model ran a failing command" into "the
  infrastructure broke" — precisely kind #2 becoming kind #1. Its `TimeoutException` is likewise
  overloaded across sandbox-lifetime expiry, request timeout, per-process deadline and an unknown
  gRPC case with **no discriminating field** (open since e2b-dev/E2B#463).
- **The right shapes are AWS Bedrock AgentCore and Cloudflare.** AgentCore's
  `InvokeCodeInterpreter` response carries `result.structuredContent{exitCode, stdout, stderr, …}`
  *beside* named exception slots (`accessDeniedException`, `conflictException`,
  `internalServerException`, `resourceNotFoundException`, `serviceQuotaExceededException`,
  `throttlingException`, `validationException`) — infra failure is a **different field**, not a
  different value of `exitCode`. Cloudflare's `SandboxError` hierarchy is the model for what the
  error side should carry: **`ContainerUnavailableError{reason, retryAfterMs}`**, documented as
  always retryable, and `RPCTransportError` with a **`.kind` enum** so a caller branches on a code
  rather than substring-matching a message.
- **The cost of getting this wrong is already published.** Codex's old path, verbatim from
  `codex-rs/sandboxing/src/denial.rs:1-70`: *"We don't have a fully deterministic way to tell if our
  command failed because of the sandbox … we conservatively check for well known command failure
  exit codes and also look for common sandbox denial keywords in the command output."* It lowercases
  and substring-matches the child's stdout/stderr against seven keywords including `"sandbox"`. **The
  most serious Rust implementation in the family ended up grepping its own child's stderr for the
  word "sandbox."**

Normative, stated rather than implied: **a non-zero exit is a `BuiltinResult`, never an `error`.**

### D7 — We promise interposability, not safety.

Setting the seam does not make an agent safe. It makes it **interposable**. Whether it is safe is
entirely a property of what the host put behind the seam — and the seam cannot verify that, cannot
detect a host that passed `--privileged`, cannot stop a `bash` tool inside the sandbox doing anything
the sandbox permits, and on macOS cannot even reliably tell that a policy denied something (D6).

**Deno documents the same limit about the same category**, at Deno's volume, from its own security
docs, verbatim:

> "A subprocess runs as a separate program with its own permissions, not the restricted set you
> granted the Deno process, so whatever it does happens outside the sandbox."

> "`--allow-run=deno` is especially dangerous: a script that can start a new deno process can start
> it with `--allow-all`."

Deno is an entire runtime, with control of every syscall entry point, a security team and a CVE
process — and it still voids its own sandbox at the subprocess and FFI boundary, **by documented
design**. A library inside someone else's process has strictly less control than Deno has. Our `bash`
builtin *is* Deno's `--allow-run`: the place where the model ends.

**The unifying line, and it belongs in the docs as well as here:**

> **Every mechanism that fails works by enumerating escapes. Every mechanism that works enumerates
> inclusions.**

That is the whole table. `cd`-rejection, denylists, allowlists over command strings, path-prefix
checks — all enumerate escapes, and all leaked: Cursor deprecated an entire denylist rather than
patch four bypasses, then leaked again through backticks/`$()` (CVE-2025-54131), brace expansion,
and **shell built-ins** (`export`, `unset`, `set` — never separate binaries, never validated, so
injected env state hijacks a *later allowlisted* command: **CVE-2026-22708, CVSS 9.8, five-month
fix**). Mount namespaces, Landlock and Seatbelt deny-by-default enumerate inclusions, and are closed
by construction — under a mount namespace the outside path returns **ENOENT, not EACCES**: it does
not exist.

The corollary we must also own: **interpreting the command string is the failure mode, not passing
it.** The seam does not parse the command, and must never start.

### D8 — Scope: all ten builtins go through together, or not at all.

A half-seam reads as safe and is the industry's most-repeated mistake. It is the whole of §1 leg 2
above, and the argument against the cheaper scope is short:

> *"`bash` is the escape vector; `glob`/`grep` are read-only; so sandbox `bash` and leave the rest."*

**A sandbox that contains `bash` but leaves `read`/`write`/`edit`/`apply_patch` on the host is not a
sandbox** — the model writes a script to the host filesystem and runs it, which is CVE-2026-39861
with the symlink swapped for a file. And once the mutating file tools cross the seam, `glob`/`grep`
must too, because listing the *host* tree inside a sandboxed run is incoherent: the model is told
about files it cannot open.

The invariant this implies is testable, not conventional: **if the seam is set, there must be no
second code path to `exec.Command`/`os.ReadFile` (or each port's equivalent) in any of the seven
ports.** Two products show what the second path costs. Anthropic documents that `toolAliases`
*"only affects name-based lookup of model-emitted `tool_use` blocks, whereas `disallowedTools` also
blocks harness-internal direct calls that hold the tool object without a name lookup"* — a seam a
subset of your own code routes around. And Goose's **CVE-2026-72718**: `goose review` stripped
`core.quotePath` from attacker-controlled git config but **not `core.fsmonitor`**, so `git diff HEAD`
ran the attacker's command on opening a malicious repo — **before any LLM call or approval prompt**.
It bypassed the entire permission model because it was not a tool call. *A seam only helps if every
exec goes through it.*

---

## Consequences

**`podman run --rm` per call is the wrong thing to advertise.** The design note names it as the
target. *Measured here*: `docker run --rm --network=none alpine true` is **307 ms/call**, a warm
`docker exec` is **50.8 ms**, and a bare `fork+exec /bin/sh -c true` is **5.3 ms** — so a per-call
`run` is **~58× a fork+exec**, and on Linux rootless podman measures 407 ms (rootful 993 ms). A
30-call agent run pays 9–30 s of pure overhead. **The hardening flags are not the cost**: an
instrumented decomposition puts namespace creation at **7.94 ms (<1.5% of total)** and
bridge-vs-host networking at **0.04–0.06 ms**, with ~250 ms of the rest being the Docker daemon
round trip alone. So `--network=none --read-only --cap-drop=ALL --pid=private` are essentially free
and every one of them stays. **Not one of the twelve surveyed sandbox services uses a per-call
container for agent tool execution** — every one is create-a-sandbox-then-exec-into-it-many-times,
and every fast published number is a warm-pool or snapshot-resume figure, not a cold start (the one
exception, Replit's `replit-code-exec`, is a *stateless* evaluator, not a file-editing loop). The
docs therefore present a persistent container + `exec` as the recommended production shape and
`run --rm` as the simplest illustration. **The seam is unaffected — it is agnostic — and that is an
argument for it.** One cliff to name in the docs: podman on the **`vfs`** storage driver measured
**2 m 15 s vs docker's 0.636 s** for the same trivial container. And one property of `--rm` a host
must be told: only the bind-mounted worktree survives between calls, so anything a tool writes
outside `/workspace` silently vanishes.

**Seatbelt is a legitimate unprivileged host implementation on macOS, and the ADR must not read as
"container or nothing."** *Measured here*: `sandbox-exec` + `sh` is **12.4 ms**, against 307 ms for
`docker run --rm`, and it **blocked the exact escape** wfnexus hit, for both reads and writes,
unprivileged and daemon-free. A developer on a Mac with no container runtime is not out of options.
It is not the library's job to drive it — but the seam's documentation must name it, because
otherwise "container or nothing" means "nothing" on developer Macs. With its caveats stated: it
cannot nest, its profile language is undocumented and drifts per release, and a unix-domain socket
`connect()` is classified `network-outbound`, so `deny file-write*` on `/var/run/docker.sock` does
not block reaching it.

**"Sandboxed" cannot be a boolean.** The seam's shape (unset ⇒ byte-identical to today, set ⇒
interposed) is right, but what the *host* reports about its own confinement is not a flag: Landlock
may be compiled in yet disabled at boot (`-ENOSYS` vs `-EOPNOTSUPP` — probing is the only correct
move; version-checking is wrong), Seatbelt cannot nest inside an already-sandboxed host, and
rootless podman will not start on a STIG-hardened RHEL 9 or a default Ubuntu 24.04. Whatever a host
reports should be a **named tier with a named degradation reason**, never a boolean.

**Adapter obligations that must be normative, because a host will otherwise get them silently
wrong.** `BuiltinResult` requires **separated stdout and stderr**; merging is not an acceptable
shortcut. Daytona's one-shot `exec` cannot do it — `ExecuteResponse.result` is the merged stream and
there is no stderr field — so a Daytona adapter must route through `create_session` +
`execute_session_command`. (Note the wrinkle for `bash` specifically: §4A already specifies
`output` = combined stdout+stderr, so the separation requirement is about *every other* tool and
about giving the host somewhere honest to put each stream, not about changing `bash`'s output.)
`Argv []string`, never a shell string — joining argv into a string is mechanical, splitting one back
into argv is not, and Cursor/opencode/Continue each paid for the latter. `Workdir`, `Env` and
`Stdin` are all fields some real backend cannot accept (Fly Machines' `exec` has no `cwd` and no
`env`; AX's proto has **no stdin field at all**; E2B, Daytona, Cloudflare and Northflank need their
session/background APIs for stdin), so the ADR states the degradations — wrap in `sh -c`, write a
temp file and rewrite the argv — rather than pretending the seam eliminates shell interpretation. It
does not. **The library stops interpreting; that is the claim, and it is different and defensible.**
Streaming, if it ever ships, is an **optional** interface a host may additionally implement: Fly
Machines' `exec` returns complete output only and must stay implementable.

**OOM lands on the command channel everywhere.** Modal has no OOM exception class; ECS reports
`OutOfMemoryError` with exit 137; Cloudflare has no OOM code; `State.OOMKilled` is unreliable in
both directions and has been for a decade (false negative when the OOM killed a child rather than
PID 1 — moby#15621, closed *not planned*; kernel-version-dependent — moby#38352, same Docker,
`true` on 3.10 and `false` on 4.19; never set on Windows). The dependable signal is cgroup v2's
`memory.events` **`oom_kill`**, snapshotted before and diffed after. `137`/`143` is **ambiguous by
construction**, and only the host — which set the memory envelope — can classify it. Guidance for
host implementations, in the docs: `create` → `start` → **`wait`** rather than `run` (`run` fuses two
channels into one integer, and docker's CLI derives 126/127 by **`strings.Contains`** on the
daemon's error text, defaulting to 125); cgroup `memory.events` rather than `State.OOMKilled`; and
the host's own monotonic deadline rather than a signal number.

---

## Pushback — where the research contradicts a decision

Recorded because the instruction was to surface it, not soften it.

**P1 — The sandbox-services survey argues against D2's two seams, and it is the strongest
objection.** `seam-prior-art-sandbox-services.md` §8 C1 and §9 conclude, in their own words:
*"keep the guarantee, drop the second method … One `BuiltinExec`; `Tool` tells the host what is being
asked"*, and name the two-shape split as **"the one assumption in the draft least likely to survive
contact"**. Their reasoning: on AX, Fly Machines and Northflank there is **no enumeration primitive
at all**, and on Modal, Vercel, Sprites and AgentCore a recursive glob is also either N calls or a
`find` — so in every gap the adapter runs `find`/`rg`, which is the `bash` shape wearing a different
name, and *"building two seam methods around a distinction most backends cannot express will push
hosts into writing a second adapter that just calls the first."*

**Where the decision stands and where the objection survives it.** The objection's *conclusion* —
the library owns ordering — is D3, and D3 is unconditional, so the failure mode the survey fears
(*"the ordering guarantee is back inside an image"*) **cannot occur under D3 regardless of how many
seam methods exist**. That is the load-bearing half, and D2 does not put it at risk. What survives
is a real cost the ADR should not hide: on three surveyed backends the second seam is pure
ceremony — a method whose only implementation delegates to the first — and every port must carry
its type. D2 is chosen against that cost on the strength of the counter-evidence the same survey
family supplies: **`seam-prior-art-sdk-seams.md` §4 and §8 reach the opposite recommendation**
("OpenAI's evidence favours two … two narrow contracts are independently arguable, independently
spikeable, independently droppable"), and Codex ships `ExecBackend`/`ExecutorFileSystem` as siblings.
**Two files disagree; this ADR follows the SDK-seam one, and says so rather than presenting it as
settled.** Spike 1 is the experiment that decides it: `sdk-seams` §8 states plainly that *if a
hostile enumeration order from the seam does not change the output, the listing seam is unnecessary
and only the exec seam ships.* **D2 is therefore the decision most likely to be revised by the
spikes, and it should be.**

**P2 — D1's demand side is one consumer, and ADR 0019's history says that matters.**
`sdk-seams` §7.9: *"No evidence is offered anywhere that a second consumer wants this. Every
citation above is about capability; the demand side is one consumer (wfnexus)."* ADR 0019 was cut
down precisely when its justification turned out to be uniformity rather than capability. The
difference here is real — this *is* a capability change, no port can express it today — but a single
consumer is a thin base for a seven-port contract, and D1 does not fix that. It is recorded, not
answered.

**P3 — Not spiked, so by ADR 0019's own rule this is not ready.** 0019's headline justification, its
signature and its shape all died to spikes, and the revision's own correction was then half-falsified
by the last spike. **No signature is fixed in this ADR, deliberately.** The shapes quoted above are
illustrations from other people's code, not a proposed API. Nothing is decided about spelling until
spike 1 runs.

**P4 — The determinism guarantee is idiosyncratic, and a reviewer can fairly say so.** Claude Code
sorts Glob by **modification time**, capped at 100 — non-deterministic across backends by
construction (a fresh `git clone`, a `COPY` into an image, or a restored cache gives every file the
same-ish mtime in arbitrary order) — and the vendor chose relevance over reproducibility and did not
treat the difference as a defect. The industry does not consider listing determinism worth a
guarantee. The honest answer is not that they are wrong: it is that **§4A already exists and is
already enforced in CI across seven ports**, so the question is not whether to acquire the guarantee
but whether to *keep* it once execution moves — and quietly losing an enforced guarantee is worse
than never having had it.

**Nothing contradicts D3, D4, D5, D6, D7 or D8.** Every one of them is supported by at least two
independent documents, and D3 and D6 are supported by measurements taken for this ADR.

---

## A separate defect, flagged here but NOT part of this decision

**§4A truncates silently.** Everyone else who caps tells the model that it capped. Claude Code's
`GlobOutput` carries `truncated`, `totalMatches`, and `countIsComplete` — the last meaning
*"whether `totalMatches` is the exact total (true) or a **floor** because the underlying search
truncated its own output"* — and its Grep reports `appliedLimit`, `appliedOffset`, `totalFiles`,
`totalLines`. Continue appends an explicit "Truncation warning" context item naming *which* limit
bit (count vs chars). Goose puts the notice in a **separate content block** so the model reads it as
instruction rather than data. Cline places a middle-elision notice in the preserved head/tail so a
later re-truncation cannot eat the recovery guidance. opencode is the counterexample that proves the
cost: v2 maps `result.items` and **discards `truncated` entirely**.

**A model that cannot tell a complete listing from a capped one will confidently conclude a file
does not exist.**

This is independent of the seam and should be fixed regardless — but it becomes *more* urgent with a
seam, because a backend is one more place a cap can bite. **The fix goes in `metadata`, so `output`
stays byte-identical and no conformance golden moves.** Follow-up, not part of this ADR's decision.

---

## Spikes — run before any API is fixed

Each carries an assertion that **can fail**. That is the point; ADR 0019 paid for this discipline
the hard way.

**Spike 1 — Ordering survives the boundary.** Same tree, same cap, **cap bites**. Build the listing
twice: once on the host, once through a fake seam. Assert **byte-identical output**. Then make the
fake seam return its enumeration in **deliberately reversed order** and assert the output does not
change.

*Why reversed:* not a contrived adversary — **tmpfs literally did that** (*measured here*: `f300
f299 f298 …` against the host's `f277 f263 f288 …`, and the exact reverse of the host order on a
10-name mixed-case tree). *How it fails:* if a container has to implement our ordering rule for the
bytes to match, the seam is in the wrong place. *What it decides:* per `sdk-seams` §8, if the hostile
order does not change the output, the structured listing seam is unnecessary and only the exec seam
ships — so this spike decides D2, not merely validates it. **Run it first.**

**Spike 2 — Native/interposed parity.** The interposed path must not change **the tool list, the
schemas, or the result shape**. Run the existing conformance goldens through the seam and assert
nothing moves.

*Why it can fail, with two shipped precedents.* **Mastra** varies the tool set with the backend —
*"Agents receive tools for the capabilities supported by the sandbox backend"*, and calling an
unsupported one raises **`SandboxFeatureNotSupportedError`**. That is a seam reshaping the surface
the model sees, shipped. **Goose** is the failure-taxonomy version: natively, `ShellOutput{stdout,
stderr, exit_code: Option<i32>, timed_out, output_truncated, output_collection_error}` separates
spawn failure from non-zero exit; the ACP-interposed path (`acp/fs.rs:302-317`) rebuilds from exit
status only and turns **`exit_status: None` into `0` via `unwrap_or_default()`** — losing the
taxonomy, **with no test or type forcing parity**. (Goose's interception also has a fall-through:
`tree` and `read_image` still hit the host, and `read` is not even in the base toolset — `AcpTools`
*injects* it — so the intercepted surface and the native surface are not the same surface.) *No
product in this family has a test that forces an interposed executor to reproduce the native one's
behaviour.* Our conformance suite across seven languages is the machinery nobody else has; this
spike is using it.

**Spike 3 — Failed exec as a tool result.** A container hiccup must reach the model as something it
can act on, **not kill a 40-turn run** — and the three failure kinds must be distinguishable by the
host.

Concretely: (a) a non-zero exit arrives as a `ToolResult` the model can read and react to, never as
a raised error (the `skill` tool's not-found path is the prior art — it returns a tool result listing
what *is* available); (b) a host-declared `CouldNotRun` reaches the **host**, not the model, so
wfnexus ADR 0012's retry policy can fire; (c) a host that declares nothing yields `Ran`, and the
run continues. *How it fails:* if a single container hiccup propagates as an exception through the
loop, or if `exit 125` from the model's own command is reported to the host as infrastructure
failure, the taxonomy is not doing its job. This is rule #89 from the #86–#93 batch applied to a new
surface: **when the HOST has provably made a mistake, error to the host; when the MODEL has, hand
the model something it can act on.**

**Also unresolved and worth a fourth spike if the first three pass:** what the seam does to `read`'s
`offset`/`limit` and to the `<skill_files>` sample — the two builtins explicitly ruled out of the
#86–#93 ordering work.

---

## Process

ADR + spikes first. **No API signature is fixed until spike 1 runs.** Then, if it passes: an OpenSpec
change with spec deltas, Go first, then all seven ports, with the per-language parity checklist.
Blocked on owner authorisation.
