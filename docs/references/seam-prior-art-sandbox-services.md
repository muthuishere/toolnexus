# Prior art for the builtin execution seam — sandbox/exec-as-a-service and agent runtimes

- **Status:** RESEARCH ONLY. No code, no ADR, no OpenSpec change. Input to the ADR and
  spikes for `docs/references/builtin-exec-seam-2026-09-22.md`.
- **Date:** 2026-09-22
- **Scope:** the "run this command over there" API shapes that already exist, so the seam
  in the draft (`BuiltinExec(ctx, BuiltinCommand{Tool, Argv, Workdir, Stdin, Env})
  (BuiltinResult, error)`) is checked against what a host would actually have to implement
  it on top of.
- **Surveyed:** E2B, Modal, Daytona, Fly.io Machines, **Fly Sprites**, Cloudflare Sandbox SDK
  (Containers / Workers), Vercel Sandbox, Northflank, AWS (Bedrock AgentCore Code Interpreter,
  ECS/Fargate, Lambda), Replit, and AX / agent-substrate.
- **Method note:** where docs pages and published source disagreed, source won. Two entries
  below were corrected that way (Cloudflare *does* have `listFiles`, the docs page omits it;
  Fly's `exec` *does* take an argv array, the narrative docs still show the deprecated string).
  Anything read from source is marked as such.

---

## 0. The headline, up front

Three findings decide things for the ADR.

1. **Argv-vs-shell-string is a real split, not a detail.** Modal, Fly Machines, Fly Sprites and
   AX take an **argv array**. E2B, Daytona, Cloudflare and AgentCore take a **shell string**.
   Northflank and Vercel accept both. `Argv []string` is the *safe* direction — joining argv
   into a string is mechanical; splitting a string back into argv is not. So `Argv` survives.
2. **Directory enumeration is the weak assumption.** The draft says the sandbox
   *enumerates* and the library *orders and truncates*. Enumeration is a first-class API call
   on E2B, Modal, Daytona, Vercel, Cloudflare, Sprites and AgentCore — but **AX's guest
   `FileSystemService` has only `ReadFile` and `WriteFile`**, and **Fly Machines and Northflank
   have no filesystem API at all**. On those, "enumerate" degrades to `exec(["find", …])` —
   i.e. it collapses into the `bash` shape. See §2 and §8.
3. **Nobody publishes an ordering guarantee on a listing, anywhere.** Not E2B's
   `files.list(path, depth=)`, not Modal's `filesystem.list_files`, not Daytona's
   `list_files`/`search_files`, not Vercel's `fs.readdir`, not Cloudflare's
   `listFiles(path, {recursive})`, not Sprites' `GET /fs/list`. No cursor, no page token, no
   documented cap either. This is a strong argument *for* the draft's "library sorts"
   decision: there is no ordering to inherit, so if the library does not sort, nothing does.
4. **One backend cannot give you stdout and stderr separately at all.** Daytona's one-shot
   `exec` merges both into a single `result` string. That is structural data loss against a
   `BuiltinResult{Stdout, Stderr}`, not an inconvenience. See §1.

---

## 1. The exact API shape for executing a command

| service | signature (as documented) | argv or string | workdir | env | stdin | result |
|---|---|---|---|---|---|---|
| **E2B** | `commands.run(cmd: str, background=None, envs=None, user="user", cwd=None, on_stdout=None, on_stderr=None, stdin=None, timeout=60, request_timeout=None) -> CommandResult` | **shell string** | `cwd` | `envs` | **no** on the foreground call — `background=True` then `commands.send_stdin(pid, data)` | `CommandResult{stdout, stderr, exit_code, error}` |
| **Modal** | `sb.exec(*args, stdout=, stderr=, timeout=None, workdir=None, env=None, secrets=None, text=True, bufsize=-1, pty=False) -> ContainerProcess` | **argv** (`sb.exec("bash","-c",…)`) | `workdir` | `env` (+ `secrets`) | `p.stdin` writer | **no result object** — drain `p.stdout`/`p.stderr`, then `p.wait()`, read `p.returncode` |
| **Daytona** | `process.exec(command: str, cwd=None, env=None, timeout=None) -> ExecuteResponse` (source: `libs/sdk-python/src/daytona/_sync/process.py` @ v0.190.0) | **shell string** (argv only on `code_run(CodeRunParams(argv=[…]))`) | `cwd` | `env` (wire: `envs`) | session API only | `ExecuteResponse{exit_code, result, artifacts}` — **`result` is stdout+stderr MERGED; there is no stderr field** |
| **Fly Machines** | `POST /v1/apps/{app}/machines/{id}/exec`, `MachineExecRequest{cmd: string (DEPRECATED), command: string[], container, machine: bool, stdin: string, timeout: int}` (source: `docs.machines.dev/spec/openapi3.json`) | **argv array** — `cmd` the string form is explicitly deprecated | **none** | **none** | `stdin` (whole string, not a stream) | `flydv1.ExecResponse{stdout, stderr, exit_code, exit_signal}` |
| **Fly Sprites** | `POST /v1/sprites/{name}/exec`, query params `cmd` (repeatable ⇒ argv), `path`, `dir`, `env` (repeatable `K=V`), `stdin` (bool, body carries it) (source: `api.sprites.dev/openapi.json`) | **argv** | `dir` | `env` | `stdin` | **binary frame stream**, exit code in a trailing `0x03` frame; over WS a typed `exit{exit_code}` message |
| **Cloudflare Sandbox SDK** | `exec(command: string, options?: ExecOptions{timeout /*ms*/, env, cwd, encoding, stream?, onOutput?, onComplete?, onError?}) -> ExecResult` (source: `packages/shared/src/types.ts`) | **shell string** — no argv form exists; the SDK ships `shell-escape.ts` for exactly that reason | `cwd` | `env` (per-invocation, non-persisting) | **not on `exec`** — `startProcess` only, which returns a `Process` handle instead of an `ExecResult` | `ExecResult{success, exitCode, stdout, stderr}` |
| **Vercel Sandbox** | `runCommand({cmd, args?, cwd?, env?, sudo?, detached?, stdout?, stderr?, signal?}) -> CommandFinished \| Command` | **both** — `cmd` + `args[]` | `cwd` | `env` | — (write a file, or `sh -c`) | `CommandFinished{exitCode, durationMs, cmdId, cwd, startedAt}` + `stdout()`/`stderr()`/`output()` |
| **Northflank** | `execServiceCommand({projectId, serviceId}, {command: string \| string[], containerName?, shell?, user?, group?})` | **both** | — (via `shell`/`cd`) | — (none documented) | session variant exposes `stdIn` stream | `{commandResult: {exitCode, status, message?}, stdOut, stdErr}` |
| **AWS Bedrock AgentCore** | `InvokeCodeInterpreter` `POST /code-interpreters/{id}/tools/invoke`, `{name: "executeCommand", arguments: {command, …}}` | **shell string** | — (`directoryPath` on file ops only) | — (none) | — | `result.structuredContent{exitCode, stdout, stderr, executionTime, taskId, taskStatus}` + `result.isError` |
| **AX / agent-substrate** | `ProcessService.StartProcess(StartProcessRequest{repeated string command = 1; string cwd = 2; map<string,string> env = 3}) -> StartProcessResponse{process_id}` | **argv** | `cwd` | `env` | **no stdin field anywhere** | async — `GetProcess -> Process{status, exit_code}` |

### How close is `BuiltinCommand{Tool, Argv, Workdir, Stdin, Env}`?

**Very close, and closest to the ones that matter most.** `Argv`/`Workdir`/`Env` is
*literally* AX's `StartProcessRequest` field-for-field (`command`/`cwd`/`env`), and is a
direct match for Modal and Vercel. Where it is awkward:

- **`Stdin` is the odd field out.** E2B has no stdin on the one-shot call (background +
  `send_stdin(pid)`), AX's proto has **no stdin field at all**, Daytona needs the session API,
  Cloudflare has none on `exec` (only `startProcess`), and Northflank exposes it only on the
  session variant. Fly Machines and Sprites *do* take it as a request field. **`Stdin` is the
  field of the five that most backends cannot accept without going async.** Keep it (we need
  it for `apply_patch`-style input), but the ADR should state the degradation: the adapter
  writes it to a temp file and rewrites the command.
- **Fly Machines has no `cwd` and no `env` on `exec`.** Confirmed against the OpenAPI spec —
  they are machine-level config, not per-call. An adapter must synthesise `Workdir`/`Env` by
  wrapping in `["sh","-c","cd … && env … && …"]`, **which throws away the argv safety the
  endpoint just gave it.** This is the ugliest single mismatch in the survey.
- **Daytona cannot fill `BuiltinResult.Stderr` at all** from one-shot `exec` — `result` is the
  merged stream. The only recoveries are (a) duplicate `result` into `Stdout` and leave
  `Stderr` empty, which is wrong for every tool whose contract reads stderr, or (b) route
  *every* call through `create_session` + `execute_session_command`, which does return
  `{stdout, stderr, exit_code}`. **The ADR should state that `BuiltinResult` requires a
  separated stdout/stderr and that merging is not an acceptable adapter shortcut** — otherwise
  a host silently hands the model interleaved bytes.
- **Fly Sprites is the single best fit in the survey**: `cmd` (repeatable) / `dir` / `env`
  (repeatable) / `stdin` map 1:1 onto `Argv` / `Workdir` / `Env` / `Stdin`, with nothing left
  over. If the ADR wants to name one backend that the proposed struct was, in effect, already
  designed for, it is this one.
- **No backend has anything corresponding to `Tool`.** Every one of these APIs is
  tool-agnostic: it runs a command, full stop. `Tool` is purely our own routing/telemetry
  field, and a host will use it to *decide which shape to serve*, not to pass through. That is
  fine, but it means `Tool` is carrying the two-shape split — see §8.
- **`(BuiltinResult, error)` with non-zero exit as a RESULT matches everyone except E2B.**
  Modal (`returncode`), Fly (`exit_code`), Cloudflare (`exitCode`), Vercel (`exitCode`),
  Northflank (`commandResult.exitCode`), Daytona (`exit_code`), AgentCore
  (`structuredContent.exitCode`), AX (`Process.exit_code`) all return it. **E2B raises
  `CommandExitException` on non-zero exit** — and that class subclasses `SandboxException`, so
  a naive `except SandboxException` in an E2B adapter silently converts "the model ran a
  failing command" into "the infrastructure broke". This is precisely failure-kind #1 vs #2
  from the draft, and E2B is the live counterexample that the mistake is easy to make.

---

## 2. Filesystem operations — first-class, or only "run a command"?

| service | read | write | **list / enumerate** | recursive? | glob | grep |
|---|---|---|---|---|---|---|
| **E2B** | `files.read(path, format="text"\|"bytes"\|"stream")` | `files.write(path, data)`, batch `write([WriteEntry])` | `files.list(path, depth=1) -> List[EntryInfo]` | **yes** — `depth=N` in one call | no | no |
| **Modal** | `filesystem.read_text/read_bytes` | `write_text/write_bytes`, `copy_from_local` | `filesystem.list_files(path) -> list[FileInfo{name,type,size}]` | **no** — single level only | no | no |
| **Daytona** | `fs.download_file` | `fs.upload_file` | `fs.list_files(path) -> [FileInfo]` (TS adds `{depth}`) | via `search_files` | **yes — `fs.search_files(path, pattern) -> {files: [str]}`, recursive** | **yes — `fs.find_files(path, pattern) -> [Match{file, line, content}]`** |
| **Vercel** | `fs.readFile`, `readFileToBuffer` | `fs.writeFile`, `writeFiles([{path,content,mode}])` | `fs.readdir(path, {withFileTypes}) -> string[] \| Dirent[]` | no | no | no |
| **Cloudflare** | `readFile`, `readFileStream` | `writeFile`, `mkdir`, `deleteFile`, `renameFile`, `moveFile`, `exists`, `watch` | **`listFiles(path, {recursive?, includeHidden?}) -> {files: FileInfo[], count, exitCode?}`** — richest entries in the survey (`name`, `absolutePath`, `relativePath`, `type`, `size`, `modifiedAt`, `mode`, `permissions{…}`). *Source-confirmed; the public docs page omits it.* | **yes** | no | no |
| **Fly Sprites** | `GET /fs/read` | `PUT /fs/write`, `DELETE /fs/delete`, `POST /fs/copy\|rename\|chmod\|chown`, watch WS | `GET /fs/list` (`path` + required `workingDir`) -> `{path, entries[], count}` | **no** | no | no |
| **AgentCore** | `readFiles` (`paths[]`) | `writeFiles` (`content[]`) | `listFiles` (`directoryPath`) | — | no | no |
| **AX guest** | `FileSystemService.ReadFile -> stream FileChunk` | `FileSystemService.WriteFile (client-stream)` | **NONE — read and write only** | — | no | no |
| **Northflank** | — | — | **none documented** | — | no | no |
| **Fly Machines** | — | — | **none** — the OpenAPI path list is `exec`, `ps`, `start/stop/suspend/restart/signal`, `lease`, `metadata`, `memory`, `events`, `wait`, `cordon`. Files go via `fly sftp` (a CLI, not an API). | — | no | no |

**Verdict on "the sandbox enumerates and returns the set".**

- It is a **natural, one-call operation** on E2B (recursive via `depth`), Cloudflare
  (recursive via `{recursive: true}`, and the richest entry type in the survey), Daytona,
  Vercel, Modal (single-level), Sprites (single-level) and AgentCore.
- **Daytona is the closest existing analogue of our own split**: `search_files(path, pattern)`
  for glob and `find_files(path, pattern) -> [{file, line, content}]` for grep. That second
  return type is almost exactly the draft's "per-hit data for grep". If anyone doubts the two
  listing tools can be expressed as enumeration, this is the existence proof.
- It is **not available at all** on AX, Fly Machines and Northflank. On those, enumeration
  *is* `exec(["find", "."])` or `exec(["rg", …])`, so the "enumerate only" shape provides no
  isolation from the `bash` shape — the host is running a command either way.
- **No service documents an ordering guarantee, a cursor, a page token, or an implicit cap**
  on any listing call. E2B's `list` has no `limit`; Modal's `list_files` has none; Daytona's
  docs state no pagination or caps; Vercel's `readdir` is Node `fs.readdir` semantics (which
  are explicitly unordered); Cloudflare returns a bare `files[]` + `count` and its
  `exitCode?` field is a tell that it shells out underneath — which is exactly where a silent
  truncation or a readdir-order difference would come from. This is the single strongest
  external support for the draft's A25/A26 decision: **the ordering does not exist upstream,
  so the library must supply it.**

---

## 3. Error taxonomy — infrastructure failure vs a non-zero exit

This is where the survey is most useful, because it shows both the good pattern and the
failure the draft is trying to avoid.

**The clean two-channel pattern — AWS Bedrock AgentCore.** The `InvokeCodeInterpreter`
response body carries *both* a result and a set of exception slots, side by side:

```
{
  "result": { "isError": bool,
              "structuredContent": { "exitCode": n, "stdout": "", "stderr": "",
                                     "executionTime": n, "taskId": "", "taskStatus": "" } },
  "accessDeniedException": {},        // 403
  "conflictException": {},            // 409
  "internalServerException": {},      // 500
  "resourceNotFoundException": {},    // 404
  "serviceQuotaExceededException": {},// 402
  "throttlingException": {},          // 429
  "validationException": {}           // 400
}
```

That is exactly the draft's split: infra failure is a *different field* from `exitCode`, not a
different value of it. **If the ADR wants an existing shape to point at as precedent, this is
it.** (AgentCore also has `isError` as a third, MCP-flavoured channel — "the tool result is an
error the model should see", which maps onto failure kind #2.)

**The same split by transport — Fly Machines.** Fly's docs are blunt about it: *the exec call
returns HTTP 200 even when the command inside failed, so check the response body's `exit_code`,
not the API status.* Infrastructure failure is a non-200; command failure is a 200 with a
non-zero `exit_code`. Clean, and free.

**Modal.** Infra → typed exception: `SandboxTimeoutError` (subclasses builtin `TimeoutError`),
`SandboxTerminatedError` ("terminated for an internal reason"), `ExecTimeoutError`,
`ExecutionError`, `RemoteError`, `ResourceExhaustedError`, `InvalidError`, `OutputExpiredError`.
Command failure → `p.returncode`. Clean split — **except that OOM has no exception class** and
surfaces as exit code `137` (128+SIGKILL), i.e. it lands on the *command* channel and is only
inferable from the number. That is exactly failure kind #1 masquerading as kind #2.

**Cloudflare — the best error model in the survey, and the one to copy.** Non-zero exit is not
an error at all: `ExecResult{success: false, exitCode: N}`. Infra failure throws a typed
`SandboxError` subclass carrying `code`, `httpStatus`, `operation`, `context`, `suggestion`,
`documentation` and `toJSON()`, over ~70 `ErrorCode` constants with an explicit code→status
map (source: `packages/sandbox/src/errors/classes.ts`, `packages/shared/src/errors/codes.ts`,
`.../status-map.ts`). The ones that matter to us:

- `ContainerUnavailableError` (`CONTAINER_UNAVAILABLE` → 503) carries **`.reason` and
  `.retryAfterMs`** and is documented as *always retryable* — that is failure kind #1, machine-
  readable, with the retry hint attached. This is exactly what wfnexus ADR 0012 needs.
- `RPCTransportError` (503) has a **`.kind` enum**, so a caller branches on a code rather than
  substring-matching a message.
- `OperationInterruptedError` (409, `context.retryable`), `ProcessReadyTimeoutError` (408),
  `SessionDestroyedError`/`SessionTerminatedError` (410).
- Command-side: `CommandNotFoundError` (404), `CommandError`, `PermissionDeniedError` (403).
- **No dedicated OOM or quota code** — those fall into the 500 bucket. Same gap as everyone.

`execStream()` emits a distinct `error` event alongside `complete`, the streaming form of the
same split.

**Fly Sprites.** Thin: plain 400/404/500 on the exec endpoint, with the exit code arriving in
the frame stream. Better transport than Machines, no better taxonomy.

**Daytona.** Structurally clean, weakly typed: non-zero exit is `ExecuteResponse.exit_code`,
infra raises `DaytonaError{message, status_code, error_code, headers}` with a
`STATUS_CODE_TO_ERROR` map (`DaytonaValidationError` 400, `DaytonaAuthenticationError` 401,
`DaytonaAuthorizationError` 403, `DaytonaNotFoundError` 404, `DaytonaConflictError` 409,
`DaytonaRateLimitError` 429, plus `DaytonaTimeoutError` / `DaytonaConnectionError`). **No
distinct class for OOM, image-pull, container-failed-to-start or quota** — all generic
`DaytonaError`, discriminated only by an optional `error_code` string.

**Northflank.** `{commandResult: {exitCode, status, message?}, stdOut, stdErr}` — `status` and
`message` are the infra channel beside `exitCode`. Northflank also documents a trap worth
copying into our spikes: *when stdout is not a TTY, the remote command's stdout and stderr can
be silently discarded.* A seam that returns empty output and exit 0 is indistinguishable from a
command that printed nothing.

**E2B — the counterexample.** `CommandExitException` (non-zero exit) subclasses
`SandboxException` (infra), so the two channels are nested rather than parallel.
`TimeoutException` is overloaded across sandbox-lifetime expiry, request timeout, per-process
deadline and an unknown gRPC case, with **no discriminating field** — a long-standing open
complaint ([e2b-dev/E2B#463](https://github.com/e2b-dev/E2B/issues/463)). An E2B-backed
implementation of our seam has to actively un-nest these.

**AX — the other counterexample, and worse.** Infrastructure failure is a bare gRPC status
error with no error enum, no code field, no message field in either proto; AX's client wraps
it as a Go error and returns **exit code 1** — so an infra failure is indistinguishable from
`exit 1` at the AX API level. And `ProcessStatus.PROCESS_STATUS_FAILED` explicitly covers
*both* "exited non-zero" and "failed to run". Worse still, `ax.proto`'s `TaskStatus` carries no
exit code at all; [google/ax#346](https://github.com/google/ax/issues/346) reports a task that
ran `exit 42` still showing `phase: Running, Ready: True`, and a crashed Substrate actor still
showing `Running`. The reporter's line is the cleanest statement of the boundary problem:
*"Substrate had the correct state and the `Task` resource did not reflect it."*

**AWS ECS/Fargate.** Two levels, and they are genuinely separate: `RunTask` returns
`failures[]` with `reason` (e.g. `RESOURCE:MEMORY`) for "could not start at all", while a task
that started and died reports `stoppedReason` / container `reason`
(`"OutOfMemoryError: Container killed due to memory usage"`) with exit code `137`.

> **For the ADR:** the honest read is that *most* services get the split right at the API
> boundary, and *every* one of them leaks OOM onto the command channel as exit 137. Our
> `BuiltinResult` should therefore carry the exit code **and** an explicit infra-failure
> discriminator, and the ADR should state that 137/143 is not automatically kind #1 — the
> model's own command can also be OOM-killed, and only the host knows which envelope it set.

---

## 4. Session / warm-start model — does per-tool-call `podman run` match anything real?

**No. Not one surveyed service uses a per-call container for agent tool execution.** Every
one of them is create-a-sandbox-then-exec-into-it-many-times:

| service | model | published latency |
|---|---|---|
| E2B | `Sandbox.create()` → many `commands.run`; `Sandbox.connect(id)` reattaches; v2 adds pause/auto-pause. Max lifetime 1 h Hobby / 24 h Pro | **~150 ms** startup, claimed by E2B via VM snapshot restore |
| Modal | `Sandbox.create(timeout=300 default, up to 24 h)`; idle timeout; `from_id`/`from_name` reattach; readiness probes | markets **sub-second**; engineering claim **< 0.5 s median** create→runnable. Their own startup-latency post publishes **no numbers**, arguing boot-time quotes mislead — treat as marketing-grade |
| Daytona | long-lived sandbox; `exec` default timeout **10 s**; sessions for long-running work; `auto_stop_interval` **15 min**, `auto_archive_interval` 7 days | **~90 ms** is Daytona's own README claim; third parties measure ~71 ms create / ~67 ms exec and call it explicitly a **warm-pool best case**. ⚠️ `daytonaio/daytona` has been **unmaintained since June 2026** (core moved private; last public tag v0.190.0) |
| Fly Machines | long-lived Machine; `exec` into it; warm-pool blueprint is an official pattern; suspend uses Firecracker snapshots | docs say a suspended resume takes **"hundreds of milliseconds instead of multiple seconds"**. I could not find an official "250 ms" figure — treat that number as folklore |
| **Fly Sprites** | persistent Firecracker microVM, 100 GB NVMe, two-stage pause; **exec sessions survive disconnection** and replay buffered output on reattach | warm (VM suspended, process state preserved) → **100–500 ms**; cold (in-memory state dropped) → **1–2 s**; checkpoint creation "milliseconds", restore under 1 s |
| Cloudflare | Durable-Object-backed container; `getSandbox(…, {sleepAfter: '30m'})`, class default **10 min**; timed-out processes keep running until the session is deleted; a `warm-pool.ts` exists in-tree | **"Container cold starts can often be in the 1–3 second range"** — an order of magnitude worse than Daytona's or Sprites' claims, and the number most relevant to a per-call model |
| Vercel Sandbox | Firecracker microVM; "start in milliseconds"; persistent-by-default with auto-save on stop, snapshots, drives | "milliseconds" (no number published) |
| AgentCore | explicit **session**: `StartCodeInterpreterSession` → N × `InvokeCodeInterpreter` → `StopCodeInterpreterSession` | session-scoped |
| AX | Task = one container for its whole life; `ax-task-runner` is PID 1; exec only via the `debug` gate | Substrate claims sub-500 ms resume, >500 suspend/resume activations/s |
| ECS/Fargate | task start measured in seconds to tens of seconds | — |

**This contradicts the draft's stated target** (`podman run --rm … <image>` per call) *as a
default*. It does not invalidate the seam — the seam is a function, and a host is free to
implement it with a fresh container per call — but the ADR should stop describing per-call
`podman run` as the shape and describe it as *one* implementation, because:

- Per-call `podman run --rm` gives every call a **fresh filesystem**. `write` then `read`,
  `apply_patch` then `bash git diff`, `cd` (even legitimately) — none of it persists unless
  the bind-mounted worktree is the only state, which the draft does assume (`-v
  <run-worktree>:/workspace:rw`). That assumption must be stated explicitly, because it means
  **anything a tool writes outside `/workspace` silently vanishes between calls**, and no
  surveyed service has that property.
- Warm-sandbox is what everyone ships because a 40-turn run pays the start cost 40 times
  otherwise. Even at Vercel/E2B's claimed ~150 ms, that is 6 s of pure overhead per run.
  Cloudflare's published **1–3 s container cold start** puts the same 40-turn run at 40–120 s
  of nothing but container starts, and `podman run` on a cold image is in that class. Note too
  that every fast number in the table above is a *warm-pool or snapshot-resume* number, not a
  cold container start — which is what `--rm` gives you every time.

---

## 5. Streaming

Every surveyed service can stream incremental output. Only the *spelling* differs, and the
spellings do not unify cleanly into one Go signature.

| service | mechanism |
|---|---|
| E2B | **callbacks** — `on_stdout` / `on_stderr` `Callable[[str], None]` on `run`; file read supports `format="stream"` |
| Modal | **iterators** — `for line in p.stdout:` / `async for`; `StreamType.PIPE \| STDOUT \| DEVNULL`; bidirectional via `p.stdin` |
| Cloudflare | **both** — `exec({stream: true, onOutput, onComplete, onError})` callbacks, or `execStream() -> ReadableStream<Uint8Array>` of SSE `ExecEvent{type: 'start'\|'stdout'\|'stderr'\|'complete'\|'error', timestamp, data?, exitCode?, result?, pid?}`. Plain `exec()` without `stream: true` is buffered-only |
| Fly Sprites | **WebSocket with a binary protocol** multiplexing stdin/stdout/stderr on one connection, **buffered replay on reattach**; `--http-post` is the non-streaming fallback |
| Vercel | **async generator** — `cmd.logs(): AsyncGenerator<{stream: "stdout"\|"stderr", data: string}>`; `detached: true` + `wait()`; `logs()` may throw `StreamError` if the sandbox stops mid-stream |
| Daytona | **sessions only, over a WebSocket** — `get_session_command_logs_async(session_id, command_id, on_stdout, on_stderr)`; one-shot `exec` and `code_run` do **not** stream. Streams are demuxed from `\x01\x01\x01`/`\x02\x02\x02` prefixes, and the exit code needs a separate `get_session_command` poll |
| Northflank | `execServiceSession()` returns Node-compatible `stdOut`/`stdErr`/`stdIn` streams + `waitForCommandResult()` |
| AX / substrate | **gRPC server-streaming** — `StreamProcessOutputs -> stream OutputChunk{OutputSource source, bytes data}` with `stdout_offset`, `stderr_offset`, `follow`; resumable by byte offset |
| AgentCore | response is a stream of events |
| Fly Machines | **complete output only** — the `exec` endpoint returns `{stdout, stderr, exit_code}` in one body |

Two things to take:

- **Fly is the one that cannot stream**, so a seam that *requires* streaming excludes it. A
  single-shot `(BuiltinResult, error)` is the lowest common denominator, and streaming should
  be an **optional second interface** a host may also implement (Go: a separate optional
  interface assertion, not a second required field), so Fly-style backends stay implementable.
- **AX's resumable-by-offset streaming (`stdout_offset` / `follow`) is the most robust design
  in the survey** and is worth noting in the ADR as prior art if we ever need reconnection.

---

## 6. AX specifically — what it does about tool execution, and what it left out

**What it is.** `google/ax` ("AX: Google's open agentic orchestrator", Apache-2.0) is a
declarative Kubernetes-style orchestrator, layered on `agent-substrate/substrate` (the actual
sandbox runtime: gVisor + microVM, checkpoint/restore, suspend/resume; CNCF sandbox proposal
`cncf/sandbox#523`). Four primitives in `pkg/apis/v1alpha1/ax.proto`, `package ax.v1alpha1`:
Task, Workspace, Gateway, Model.

**The PID-1 claim is confirmed verbatim.** `docs/sandbox.md`: *"Every task container starts with
`ax-task-runner` as PID 1."* `docs/runner.md`: *"A runner is the program that AX starts as PID 1
inside every task container."* Note the nuance: **the runner is PID 1, and the agent's command
is its child** — not the other way round.

**The "orchestrator cannot reach across the boundary" claim is confirmed — and AX states it as
a limitation, not a boast.** `docs/runner.md`: *"The runner is PID 1, and the container lives as
long as it does. … The control plane does not currently read the command's exit status back
from the container."*

**Tool execution.** Two layers, and this is the important structural fact:

- **`ax.proto` has no exec RPC at all.** It is CRUD + lifecycle:
  `GetTask/ListTasks/UpdateTask/DeleteTask/SuspendTask/ResumeTask/WatchTask`, same for
  Gateway/Workspace/Model. You run a command by *declaring* it in `TaskSpec.command`
  (`repeated string command = 4`). Independently confirmed on the substrate side:
  `internal/proto/ateompb/ateom.proto` service `Ateom` exposes only
  `RunWorkload/CheckpointWorkload/RestoreWorkload/GetWorkloadStats/TerminateWorkload`, and
  `internal/proto/ateletpb/atelet.proto` service `AteomHerder` only
  `Run/Checkpoint/Restore/UploadPausedCheckpoint/Terminate`. **There is no "run a command in an
  existing sandbox" RPC anywhere in the control plane.** That is *how* the orchestrator cannot
  reach across the boundary: by not having the verb.
- **Exec lives in a different repo, behind a flag**: `agent-substrate/env`,
  `proto/ateenv/v1alpha/guest.proto`, `package ateenv.v1alpha`, reached over gRPC on port 80
  inside the actor. AX consumes it in `internal/guest/client.go`, gated on
  `TaskSpec.debug = 10` (`bool`), **off by default**; `ax ssh` refuses without it.

```protobuf
service ProcessService {
  rpc StartProcess(StartProcessRequest) returns (StartProcessResponse);
  rpc GetProcess(GetProcessRequest) returns (Process);
  rpc StreamProcessOutputs(StreamProcessOutputsRequest) returns (stream OutputChunk);
  rpc KillProcess(KillProcessRequest) returns (KillProcessResponse);
}
message StartProcessRequest {
  repeated string command = 1;   // ["pytest","tests/"] or ["sh","-c","ls -la"]
  string cwd = 2;
  map<string, string> env = 3;
}
service FileSystemService {
  rpc ReadFile(ReadFileRequest) returns (stream FileChunk);
  rpc WriteFile(stream WriteFileRequest) returns (WriteFileResponse);
}
```

`StartProcessRequest` is `BuiltinCommand{Argv, Workdir, Env}` field-for-field. **There is no
stdin field, and `FileSystemService` has no list or stat RPC** — verified twice against the
published `main` proto. Everything else goes through `StartProcess(["ls","-la"])`.

**`reserved 9 "policies"` — confirmed.** In `TaskSpec`, `pkg/apis/v1alpha1/ax.proto`:

```protobuf
message TaskSpec {
  // Field 1 was `goal`, removed; the workspace goal lives on WorkspaceRef.
  reserved 1;
  reserved "goal";
  bool suspend = 2;
  string image = 3;
  repeated string command = 4;
  repeated EnvVar env = 5;
  ResourceReqs resources = 6;
  repeated WorkspaceRef workspaces = 7;
  GatewayRef gateway = 8;
  // Field 9 was `policies` (budget and approval config), removed for now.
  reserved 9;
  reserved "policies";
  // debug enables the in-container guest services (process execution and file
  // access) that back `ax ssh`. Off by default.
  bool debug = 10;
}
```

**What was deliberately left out: per-task spend budgets and human-approval gates on tool
invocation.** The comment's "for now" says deferred, not rejected. There is no commit message
explaining it — `ax.proto`'s whole history is one squashed commit, `dc4f36cd "Restructure AX
into a general-purpose orchestration layer for agentic tasks"`. The corroborating traces are
closed issue [google/ax#111](https://github.com/google/ax/issues/111) ("Verify Tool Policy
Approvals Configuration — Confirm tool invocation policies and user-approval prompts can be
configured and enforced") and the **orphaned read-side messages still in the proto**:
`TaskStatus.pending_approval -> PendingApproval{id, action, requested_at}` and
`UsageStats{prompt_tokens, completion_tokens}`. The status surface for policies survived; the
spec surface to configure them was pulled.

> **Why this matters to us.** AX tried to put policy (budget + approval) *in the same message
> as the command*, and pulled it out. That is a direct data point for keeping `BuiltinCommand`
> a pure description of the work — `Tool`, `Argv`, `Workdir`, `Stdin`, `Env` — and putting
> policy on the host side of the seam, where the draft already puts it. **Do not add a
> `Policy`/`Limits` field to `BuiltinCommand`.** The one project that tried it reserved the
> field number instead.

**One drift wrinkle worth knowing:** `google/ax`'s `internal/guest/client.go` calls
`StreamProcessOutput` (singular) / `ProcessState_PROCESS_STATE_RUNNING`, while the published
`agent-substrate/env` proto on `main` says `StreamProcessOutputs` (plural) / `ProcessStatus`.
AX also expects an `out.GetExit()` variant carrying an exit code that is **not** in the
published proto, and has a polling fallback for exactly that case. The two repos are pinned to
different proto versions — a small live example of the drift this repo's parity rule exists to
prevent.

---

## 7. Replit and Northflank — thin public surface, noted for completeness

- **Replit** has no documented public "exec in an agent sandbox" API. The only public artefact
  is [`replit/replit-code-exec`](https://github.com/replit/replit-code-exec) — a *stateless*
  API for AI agents to evaluate generated Python, returning whatever was printed to
  stdout/stderr, run in an ephemeral unprivileged container using `omegajail`. Replit Agent
  itself runs inside a Replit VM/container with restricted outbound access, but there is no
  published seam. **Replit is the one surveyed system that actually matches the per-call
  ephemeral-container model** — and it does so for stateless numeric evaluation, not for a
  coding agent's file-editing loop.
- **Northflank** is a real fit: `execServiceCommand` / `execServiceSession` /
  `execJobCommand` / `execJobSession`, `command: string | string[]`, `shell`, `user`, `group`,
  and `{commandResult: {exitCode, status, message?}, stdOut, stdErr}`. **No filesystem API is
  documented**, so all file work is exec.

---

## 8. What CONTRADICTS the draft

Flagged plainly, strongest first.

**C1 — "The sandbox enumerates" is not universally available, and where it is missing it
collapses the two-shape split.** AX's `FileSystemService` has **only `ReadFile` and
`WriteFile`**; Fly Machines' OpenAPI spec has **no file endpoints at all**; Northflank
documents none. On those three, implementing `glob`/`grep`/`<skill_files>` as "enumerate only"
means the adapter issues `exec(["find", ".", "-type", "f"])` — i.e. the *same* call it would
issue for `bash`. The split then exists only in our type system, not in the backend. And even
where a listing API exists, **nobody offers glob or content-grep except Daytona**, and only
E2B, Cloudflare and Daytona can recurse in one call — so on Modal, Vercel, Sprites and
AgentCore a recursive glob is *also* either N calls or a `find`.

*This does not invalidate the split's reasoning* — the reasoning is about who sorts, and it
still holds (indeed `find` output ordering is filesystem-dependent, which is the exact bug
A25/A26 fixed). But it does invalidate the framing that "enumerate" is a *different kind of
operation* the sandbox natively offers. **The honest shape is: one transport (run a command),
two contracts about who owns ordering.** The ADR should make `BuiltinExec` a single function
and express the split as *what the library does with the bytes*, not as two seam methods.

**C2 — per-call `podman run --rm` matches nothing real except Replit's stateless evaluator.**
Every agent-oriented service in the survey keeps a warm sandbox and execs into it repeatedly,
and several (Modal readiness probes, E2B `connect`, Vercel persistent sandboxes, Cloudflare
session-scoped processes, AgentCore sessions) build substantial API surface around that fact.
See §4 for the concrete consequence: with `--rm`, only the bind-mounted worktree survives
between calls, so any tool writing outside `/workspace` is silently lossy. **The seam is
compatible with warm sandboxes — that is an argument for it — but the draft should not present
per-call `podman run` as the shape.**

**C3 — `Stdin` as a request field is not accepted by most backends.** E2B (foreground), AX (no
field at all), Daytona (session only), Cloudflare (`startProcess` only), and Northflank's
short-lived variant cannot take it. Only Fly Machines, Fly Sprites and Vercel-via-`sh -c` can.
Keep the field; state the degradation (temp file + rewritten argv).

**C3b — `Workdir` and `Env` are not universal either.** Fly Machines' `exec` has neither.
Every adapter for such a backend must wrap in `sh -c`, which means *some* hosts will be running
a shell even for the "argv-safe" path. The ADR should not claim the seam eliminates shell
interpretation; it claims the *library* stops interpreting, which is a different and defensible
statement.

**C3c — `BuiltinResult{Stdout, Stderr}` is not fillable from Daytona's one-shot `exec`.** See
§1. Name this in the ADR as a hard requirement on adapters rather than letting a host silently
merge the streams.

**C4 — nobody's listing has an ordering guarantee, including ours-to-be.** This *supports*
"library sorts", but it also means the spike in the draft ("a deliberately hostile enumeration
order from the seam must not change the output") is not a hypothetical robustness test — it is
the expected behaviour of every real backend. Run it first, as the draft says.

**C5 — OOM lands on the command channel everywhere.** Modal has no OOM exception; ECS reports
`OutOfMemoryError` + exit `137`; nobody surfaces "the kernel killed you" as a distinct infra
error on the exec path. The draft's three-failure-kind requirement is right, but the ADR must
say that exit `137`/`143` is ambiguous by construction and the *host* — which set the memory
envelope — is the only party who can classify it.

**C6 — E2B inverts the error contract.** A backend where non-zero exit *raises*, and the raised
class subclasses the infra-error base class, is not a straw man; it is the most popular sandbox
SDK in this space. The ADR should include "non-zero exit is a RESULT, never an `error`" as a
normative sentence, not an implication.

---

## 9. Conclusions

### The API shape most likely to be implementable against all of them

```go
// One required method. Non-zero exit is a RESULT. Infra failure is the error.
type BuiltinExec interface {
    Exec(ctx context.Context, cmd BuiltinCommand) (BuiltinResult, error)
}

type BuiltinCommand struct {
    Tool    string            // ours only; no backend has an equivalent. Routing/telemetry.
    Argv    []string          // argv, never a shell string. Join for string-only backends.
    Workdir string            // may be empty; Fly-style backends synthesise it
    Env     map[string]string // may be empty; Fly-style backends synthesise it
    Stdin   []byte            // may be empty; stdin-less backends write a temp file
}

type BuiltinResult struct {
    Stdout   []byte // MUST be separated from Stderr; merging is not an acceptable adapter
    Stderr   []byte // shortcut (Daytona one-shot exec cannot do this — use its session API)
    ExitCode int    // 0..255; 137/143 are AMBIGUOUS — see C5
    // no policy, no limits, no budget — see the AX `reserved 9 "policies"` lesson
}

// OPTIONAL, asserted at runtime. Fly Machines cannot implement it; that must stay legal.
type BuiltinExecStreamer interface {
    ExecStream(ctx context.Context, cmd BuiltinCommand, onChunk func(stream Stream, b []byte)) (BuiltinResult, error)
}
```

Rationale, all of it earned above:

- **Argv, not a shell string** — joining is mechanical, splitting is not; and it is the exact
  shape of AX, Modal, Fly and Vercel.
- **One method, not two** — C1. The `glob`/`grep`/`<skill_files>` distinction lives in what the
  library does with the returned bytes (collect → sort by path in code point → truncate), which
  is where the draft's own reasoning puts the guarantee anyway. `Tool` is what lets a host
  serve a stricter shape if its backend offers one (Daytona's `search_files`/`find_files`,
  E2B's `files.list(depth=)`), without the library depending on that.
- **Non-zero exit is a `BuiltinResult`, an infra failure is the `error`** — matches every
  surveyed backend except E2B. AgentCore's `result`/`*Exception` split and Fly's "200 with a
  non-zero `exit_code`" are the precedents to cite; **Cloudflare's `SandboxError` hierarchy,
  with `ContainerUnavailableError{reason, retryAfterMs}` documented as always-retryable, is
  the model for what the `error` side should carry** if we ever want the host's retry policy
  to be machine-driven rather than string-matched.
- **Streaming as an optional interface assertion** — keeps Fly implementable and matches the
  fact that every streaming spelling in the survey is different.
- **No policy field on the command** — `google/ax` shipped `reserved 9 "policies"` after trying it.

### The one assumption in the draft least likely to survive contact

> **"The sandbox ENUMERATES and the library orders and truncates" — as a *distinct seam shape*.**

The *conclusion* (the library owns ordering and truncation) is right and is, if anything,
better supported by this survey than the draft claims: **not one of the twelve surveyed
services documents an ordering guarantee, a cursor, or a cap on any listing call**, so there is
no ordering to inherit. What will not survive is the **two-shape split as an API fact**. On
AX, Fly Machines and Northflank there is no enumeration primitive at all; on Modal, Vercel,
Sprites and AgentCore there is one but it cannot recurse or glob; only Daytona ships anything
resembling our glob/grep pair. In every gap the adapter must run `find`/`rg`, which is the
`bash` shape wearing a different name. Building two seam methods around a distinction most
backends cannot express will push hosts into writing a second adapter that just calls the
first — and the moment a host does that, the ordering guarantee is back inside an image, which
is the exact failure the draft exists to prevent.

**Concretely: keep the guarantee, drop the second method.** One `BuiltinExec`; `Tool` tells the
host what is being asked; the library always collects the full set, sorts by path relative to
the walk root in plain code point, and *then* truncates — unconditionally, whether the bytes
came from `find`, from `files.list(depth=)`, or from the host process. Spike #1 in the draft
already tests exactly this, and it is the spike to run first.

---

## Sources

E2B: [docs.e2b.dev/commands](https://docs.e2b.dev/commands) ·
[python sdk reference v1.4.0](https://docs.e2b.dev/sdk-reference/python-sdk/v1.4.0/sandbox_sync) ·
[exceptions](https://docs.e2b.dev/sdk-reference/python-sdk/v1.4.0/exceptions) ·
[github.com/e2b-dev/E2B](https://github.com/e2b-dev/E2B) · [E2B#463](https://github.com/e2b-dev/E2B/issues/463)
· source read directly: `packages/python-sdk/e2b/sandbox_sync/commands/command.py`,
`.../filesystem/filesystem.py`

Modal: [modal.Sandbox reference](https://modal.com/docs/reference/modal.Sandbox) ·
[guide/sandbox](https://modal.com/docs/guide/sandbox) ·
[guide/sandbox-files](https://modal.com/docs/guide/sandbox-files) ·
[modal.exception](https://modal.com/docs/reference/modal.exception) ·
[scaling to 1M sandboxes](https://modal.com/blog/scaling-to-1-million-concurrent-sandboxes-in-seconds) ·
[unpacking sandbox startup latency](https://modal.com/blog/unpacking-sandbox-startup-latency)

Daytona: [process & code execution](https://www.daytona.io/docs/en/process-code-execution/) ·
[file system operations](https://www.daytona.io/docs/en/file-system-operations/) ·
[TS FileSystem](https://www.daytona.io/docs/en/typescript-sdk/file-system/) ·
[PTY](https://www.daytona.io/docs/pty/) · source at
[daytonaio/daytona v0.190.0](https://github.com/daytonaio/daytona/tree/v0.190.0):
`libs/sdk-python/src/daytona/_sync/process.py`, `.../_sync/filesystem.py`,
`.../common/process.py`, `.../common/errors.py`, `.../common/daytona.py` ·
[#4309](https://github.com/daytonaio/daytona/issues/4309) · third-party latency:
[pixeljets](https://pixeljets.com/blog/ai-sandboxes-daytona-vs-microsandbox),
[Blaxel](https://blaxel.ai/blog/code-execution-sandboxes-for-ai-agents)

Fly.io Machines: [OpenAPI spec](https://docs.machines.dev/spec/openapi3.json) ·
[machines resource](https://fly.io/docs/machines/api/machines-resource/) ·
[docs.machines.dev](https://docs.machines.dev/) ·
[warm pools of user machines](https://fly.io/docs/blueprints/warm-pool-user-machines/) ·
[suspend/resume](https://fly.io/docs/reference/suspend-resume/) ·
[fly machine exec](https://fly.io/docs/flyctl/machine-exec/) ·
[fly sftp](https://fly.io/docs/flyctl/sftp/) ·
[superfly/fly-go machine_types.go](https://github.com/superfly/fly-go/blob/main/machine_types.go)
(note: the Go client still lacks the `command []string` field)

Fly Sprites: [OpenAPI spec](https://api.sprites.dev/openapi.json) ·
[docs.sprites.dev](https://docs.sprites.dev/) ·
[llms-full.txt](https://docs.sprites.dev/llms-full.txt) ·
[cli/commands](https://docs.sprites.dev/cli/commands.md) ·
[Simon Willison on Sprites](https://simonwillison.net/2026/Jan/9/sprites-dev/)

Cloudflare: [sandbox/api/commands](https://developers.cloudflare.com/sandbox/api/commands/) ·
[sandbox/api/files](https://developers.cloudflare.com/sandbox/api/files/) ·
[containers platform details](https://developers.cloudflare.com/containers/platform-details/) ·
source at [cloudflare/sandbox-sdk](https://github.com/cloudflare/sandbox-sdk):
`packages/shared/src/types.ts`, `packages/shared/src/shell-escape.ts`,
`packages/sandbox/src/clients/command-client.ts`, `packages/sandbox/src/errors/classes.ts`,
`packages/shared/src/errors/codes.ts`, `packages/shared/src/errors/status-map.ts`
(**Beta — "APIs may change before v1.0"**)

Vercel: [vercel.com/docs/sandbox](https://vercel.com/docs/sandbox) ·
[sdk-reference](https://vercel.com/docs/sandbox/sdk-reference) ·
[github.com/vercel/sandbox](https://github.com/vercel/sandbox)

Northflank: [execute commands](https://northflank.com/docs/v1/api/execute-command) ·
[sandboxes on northflank](https://northflank.com/docs/v1/application/sandboxes/sandboxes-on-northflank)

AWS: [InvokeCodeInterpreter](https://docs.aws.amazon.com/bedrock-agentcore/latest/APIReference/API_InvokeCodeInterpreter.html) ·
[code-interpreter file operations](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/code-interpreter-file-operations.html) ·
[ECS OOM troubleshooting](https://www.repost.aws/knowledge-center/ecs-resolve-outofmemory-errors)

Replit: [replit/replit-code-exec](https://github.com/replit/replit-code-exec)

AX / agent-substrate: [github.com/google/ax](https://github.com/google/ax) ·
[ax.proto](https://raw.githubusercontent.com/google/ax/main/pkg/apis/v1alpha1/ax.proto) ·
[guest.proto](https://raw.githubusercontent.com/agent-substrate/env/main/proto/ateenv/v1alpha/guest.proto) ·
[internal/guest/client.go](https://github.com/google/ax/blob/main/internal/guest/client.go) ·
[docs/sandbox.md](https://github.com/google/ax/blob/main/docs/sandbox.md) ·
[docs/runner.md](https://github.com/google/ax/blob/main/docs/runner.md) ·
[google/ax#344](https://github.com/google/ax/issues/344) ·
[#346](https://github.com/google/ax/issues/346) ·
[#111](https://github.com/google/ax/issues/111) ·
[agent-substrate/substrate](https://github.com/agent-substrate/substrate) ·
[cncf/sandbox#523](https://github.com/cncf/sandbox/issues/523) ·
protos read directly from `agent-substrate/substrate`: `pkg/proto/ateapipb/ateapi.proto`,
`internal/proto/ateompb/ateom.proto`, `internal/proto/ateletpb/atelet.proto`
