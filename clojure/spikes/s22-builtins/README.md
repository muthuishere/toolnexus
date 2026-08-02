# S22 — the built-in coding toolset (SPEC §0.11 + §4A) on both hosts

**Question:** can the ten default builtins — `bash, read, write, edit, grep,
glob, webfetch, question, apply_patch, todowrite` — be implemented in portable
Clojure over koine, with **no reader conditional in toolnexus' own source**, and
behave byte-identically on Clojure (JVM) and cljgo?

**Answer: yes for nine and a half of ten.** Verified 2026-07-31.

```
== Clojure (JVM)
== cljgo (AOT binary)
== cljgo (interpreted)
== diff
  jvm == cljgo-aot  (byte-identical, 11085 bytes)
  jvm == cljgo-run  (byte-identical, 11085 bytes)
```

Reproduce: `./run-both.sh` (needs `clojure` and `cljgo`; no network beyond
`127.0.0.1`, no LLM, no key). One file — `src/toolnexus/builtins.cljc`, 884
lines, **zero reader conditionals, zero `java.*`, zero Go interop, zero
`clojure.core` shadowing** (all ten implementations are named `t-*`, because
`read` is `clojure.core/read` and cljgo's static interop scan rejects the whole
namespace over one shadowed symbol).

The half that is missing is `bash`'s **timeout**. See FINDING 1.

## The measured numbers

| what | number |
|---|---|
| report, all three modes | **11085 bytes, byte-identical** |
| builtins implemented | 10 / 10 names + schemas, 9.5 / 10 behaviours |
| §4A schemas emitted | 2910 bytes of JSON (sorted keys) |
| tool executions in the report | 25 fs/shell + 10 apply_patch + 4 webfetch + 3 question |
| §0.11 toggle cases proven | **16** |
| assembly cases proven | 5 |
| system prompt | 319 bytes with builtins ON, 319 with them OFF, `identical: true` |

## The schemas (the contract — diff this)

Emitted verbatim into the report under `schemas`, so drift shows up in the byte
diff rather than in a review. Required fields per §4A:

| name | properties | required |
|---|---|---|
| `bash` | `command:string`, `workdir:string`, `timeout:number(default 60000)`, `description:string` | `["command"]` |
| `read` | `path:string`, `offset:number`, `limit:number` | `["path"]` |
| `write` | `path:string`, `content:string` | `["path","content"]` |
| `edit` | `path:string`, `oldString:string`, `newString:string`, `replaceAll:boolean` | `["path","oldString","newString"]` |
| `grep` | `pattern:string`, `path:string`, `include:string`, `limit:number(default 100)` | `["pattern"]` |
| `glob` | `pattern:string`, `path:string`, `limit:number(default 100)` | `["pattern"]` |
| `webfetch` | `url:string`, `format:string enum["text","markdown","html"] default "markdown"`, `timeout:number(default 30)` | `["url"]` |
| `question` | `questions:array` of `{question:string, header:string, options:array<string>, multiple:boolean}` (item required `["question"]`) | `["questions"]` |
| `apply_patch` | `patchText:string` | `["patchText"]` |
| `todowrite` | `todos:array` of `{id:string, text:string, completed:boolean}` (item required `["id","text","completed"]`) | `["todos"]` |

Every tool carries `source: "builtin"` — the report's `sources` field is
`["builtin"]`, i.e. all ten and nothing else.

## What was actually executed (not asserted — run)

- **bash** — `printf` (exit 0), `basename "$(pwd)"` under `workdir` (proves the
  working directory is honoured, and is the only bash probe whose output is
  deterministic), and `echo … 1>&2; exit 7` ⇒ `isError:true`, output
  `"oops\nexit code 7"` (stdout+stderr combined, exit code appended).
- **write** — `Wrote 17 bytes to …`; a second write into `work/sub/deep.txt`
  proves **parent-directory creation** (see FINDING 2 for how).
- **read** — whole file; `offset:2 limit:1` ⇒ `"beta"`; missing file ⇒
  `isError:true`.
- **edit** — absent `oldString` ⇒ error; non-unique without `replaceAll` ⇒
  error `"… (5 occurrences)"`; unique ⇒ 1 replacement, re-read confirms
  `alpha\nBETA\ngamma\n`; `replaceAll:true` ⇒ 4 replacements, re-read confirms
  `AlphA\nBETA\ngAmmA\n`; missing file ⇒ error.
- **glob** — `**/*.txt` ⇒ `work/notes.txt\nwork/sub/deep.txt`; `*.txt` (shallow,
  does **not** cross `/`) ⇒ `notes.txt`; no match ⇒ `""`; `limit:1` ⇒ one line.
- **grep** — `TODO:\s*\w+` with `include: **/*.txt` ⇒ two `file:line:text`
  hits; an `include` that matches nothing ⇒ `""`.
- **webfetch** — against a **koine.server on 127.0.0.1:0** serving one HTML
  page: `markdown` ⇒ `# Title\nHello **world**`, `text` ⇒ `TitleHello world`,
  `html` ⇒ the raw 65-byte body, `/missing` ⇒ `isError:true`, `HTTP 404`.
- **question** — first call suspends with
  `metadata.pending = {id, kind:"question", prompt, data:{questions}}`; the
  rendered prompt is `"Pick a colour (options: red, green)\nFree text?"`
  (options appended, `header` **not** rendered, `\n`-joined, no trailing
  newline — §4A's byte-identical clause); re-executed with
  `ctx.answer = {ok:true, data}` ⇒ `ok("{\"answers\":[\"green\",\"hello\"]}")`;
  with `ok:false` ⇒ suspends again rather than inventing an answer.
- **apply_patch** — malformed ⇒ error; `*** Add File:` ⇒ file contains
  `one\ntwo\n`; `*** Update File:` with a `-`/`+`/context hunk ⇒ `one\nTWO\n`;
  a hunk that does **not** match ⇒ `isError:true` **and the file is unchanged**
  (re-read confirms `one\nTWO\n` — atomicity, proven, not claimed); `*** Delete
  File:` ⇒ gone; deleting again ⇒ error.
- **todowrite** — `- [x] spike builtins\n- [ ] write README`; empty list ⇒ `""`.
- unknown tool name ⇒ `isError:true, "unknown tool: nosuchtool"`.

All filesystem work happens in a temp directory the spike creates under
`$TMPDIR` and `rm -rf`s in a `finally`. **The repo is never written to.** Temp
paths are non-deterministic, so every reported output has the temp root replaced
by the literal `<tmp>` and `outputBytes` is measured on the **redacted** text —
otherwise the byte count would be a property of the machine's `$TMPDIR`.

## §0.11 toggling — all 16 cases

| `builtins` | source on? | tools |
|---|---|---|
| absent | ✔ | 10 |
| `true` | ✔ | 10 |
| `false` | ✘ | 0 |
| `{disabled:true}` | ✘ | 0 |
| `{enabled:false}` | ✘ | 0 |
| `{disabled:true, enabled:true}` | ✘ | 0 — **MCP precedence: `disabled:true` wins** |
| `{disabled:false, enabled:false}` | ✘ | 0 — else `enabled:false` disables |
| `{disabled:false}` | ✔ | 10 — else on |
| `{enabled:true}` | ✔ | 10 |
| `{tools:{bash:false}}` | ✔ | 9 (bash gone) |
| `{tools:{bash:true}}` | ✔ | **10** — all-on baseline, **not** an allowlist |
| `{tools:{bash:false, write:false}}` | ✔ | 8 |
| `{tools:{nosuch:false}}` | ✔ | 10 — unknown ignored |
| `{disabled:true, tools:{bash:true}}` | ✘ | 0 — **global-off short-circuits the map** |
| `{enabled:false, tools:{bash:true}}` | ✘ | 0 — same |
| parsed from JSON `{"builtins":{"tools":{"write":false}}}` | ✔ | 9 (write gone) |

The last row matters on its own: it comes out of `koine.json/read-str` with
`{:key-fn keyword}`, so the toggle works on a **parsed config object**, not only
on a hand-built Clojure map.

"MCP precedence" is SPEC §4's phrase for the MCP `isEnabled` rule reused here:
*`disabled:true` wins, else `enabled:false` disables, else on.* Rows 6 and 7 are
the two cases that distinguish it from any simpler reading, and both are run.

## §4 assembly

`MCP → skill → builtin → extraTools`, dedupe by name first-wins, with
`extraTools` shadowing a builtin **before** the concat:

| case | resulting names, in order |
|---|---|
| baseline | `skill, bash, read, write, edit, grep, glob, webfetch, question, apply_patch, todowrite` |
| MCP tool also named `read` | `everything_echo, read(mcp), skill, bash, write, …` — **the builtin `read` is gone** |
| plus `extraTools` `bash` + `deploy` | `… everything_echo(mcp), read(mcp), skill, write, edit, …, bash(native), deploy(native)` — the builtin `bash` is dropped at step 1, and the host's `bash` sits in the **extras** position, not the builtin one |
| `{tools:{bash:false, apply_patch:false}}` | `skill` + 8 builtins |
| `builtins:false` | `everything_echo, read, skill, bash(native), deploy(native)` — **no builtin anywhere** |

## Proof that builtins are NOT in the system prompt

Two halves, both in the report under `prompt`:

1. **Structural** — `system-message` (§0.10: `systemPrompt + "\n\n" +
   skillsPrompt()`) has no builtin parameter. There is nowhere for one to enter.
2. **Measured** — the prompt is built with the builtin source ON and OFF:
   `bytesOn: 319`, `bytesOff: 319`, `identical: true`. And
   `builtinNamesInPrompt: []` — none of the ten names occurs anywhere in the
   319-byte prompt built from the **shared** `examples/skills/hello-world`
   fixture. Meanwhile `schemaArrayCarriesThem` shows all ten names present in
   `toOpenAI()`, `toAnthropic()` and `toGemini()`. That is the §0.11 clause:
   surfaced via the tool-schema array only, like MCP.

## FINDINGS

### 1. `bash`'s `timeout` cannot be implemented over koine. (the real finding)

§4A: *"Timeout kills the child ⇒ `isError:true`."*

- `koine.process/sh` takes `:in`, `:dir`, `:env` — **no timeout** — and runs to
  completion.
- `koine.process` has **no kill**. `close!` closes the child's stdin and then
  **waits**; `spawn`'s handle exposes `send-line!/read-line!/alive?/close!` and
  nothing that terminates a running process.

So there is no portable way to bound a `bash` call. The schema keeps `timeout`
(it is contract), the implementation documents that it is accepted and **not
enforced**, and the report carries `limits.bash-timeout-enforced: false` so a
future change cannot flip it silently. Wrapping the command in `timeout`/
`gtimeout` would be a POSIX-only lie dressed as portability — it is not done.

**Ask of koine:** a `:timeout-ms` option on `sh`, and a `kill!` on the `spawn`
handle. Both are single-host-branch additions in `koine/process.cljc`; without
them a *coding* agent's most dangerous tool has no off switch.

### 2. koine.fs has no `mkdir` and no `delete`.

`koine.fs` is `exists? · directory? · list-tree · find-files · read-file ·
write-file · read-bytes · write-bytes`. Three §4A behaviours need more:

- `write` — *"creating parent dirs"*
- `apply_patch` — `*** Delete File:`
- the spike's own temp directory

All three are done by shelling out through `koine.process/sh`: `mkdir -p`,
`rm -f`, `rm -rf`. That is portable across **hosts** (it ran identically on JVM
and cljgo) but **not across operating systems** — it is POSIX, and a Windows
port of these builtins would fail here, silently, with an exit code nobody
reads. The report records this as `limits.koine-has-mkdir: false`,
`koine-has-delete: false`, `posix-shellouts-used`.

**Ask of koine:** `koine.fs/mkdirs!`, `koine.fs/delete!`, and a
`koine.fs/temp-dir!`.

### 3. SPEC contradicts itself on `builtins.tools` with a `true` value.

- **§4A / §4 assembly** (twice, explicitly): *"a name→bool map applied on the
  all-on baseline: a tool mapped to `false` is dropped, `true` (or absent)
  stays on"*. ⇒ `{tools:{bash:true}}` = **all ten**.
- **§3, S2** (skills allowlist), describing itself as *"identical to the MCP
  per-server `tools` filter and builtins §4A"*: *"nil/empty ⇒ all; **≥1 `true`
  ⇒ allowlist** (only true-mapped names)"*. ⇒ `{tools:{bash:true}}` = **bash
  only**. §2 Gap 7 repeats the allowlist wording, also claiming it is identical
  to §4A.

These are different functions. This spike implements the §4A/§4 wording, which
is the section that owns builtins and states it twice, and which the task brief
restates (`{x:true}` keeps it on). **Six ports are being asked to be
byte-identical against a spec that specifies two behaviours for the same input.**
Whichever way it is resolved, §3-S2 and §2-Gap-7 must stop claiming they are
"identical to builtins §4A" unless they are.

### 4. On cljgo, `koine.server/serve` prints a banner to **stdout**.

`bri: listening on http://localhost:<port>` — on the AOT binary and interpreted
alike; the JVM prints nothing. It carries a random port, so it is precisely the
line a byte diff cannot survive, and it lands on the same stream as the report.
`run-both.sh` therefore reduces **all three** modes to their last stdout line.
Any real toolnexus CLI that emits machine-readable output on stdout will hit
this the moment a `koine.server` is started in-process.

### 5. A Clojure map literal is not a sequencing construct. (self-inflicted, worth recording)

The first cut put the tool calls in a 12-entry map literal. The values were
**not** evaluated in source order: `write` succeeded, `read` succeeded, and an
`edit` between them reported "file not found" while a later `glob` listed the
file. Every effectful step is now a `let` binding. This is a JVM-and-cljgo-alike
trap for a port whose whole output is one deterministic document.

### 6. Two §4A output formats are not pinned, and they must be.

`todowrite`'s *"rendered list"* and `webfetch`'s `text`/`markdown` conversion
have named modes but **no specified bytes**. §4A pins `question`'s rendering to
the byte and is silent on these two. Six independent implementations will not
converge by luck. This spike chose `- [x] text` / `- [ ] text` and a minimal
HTML strip; both are the spike's invention, not the contract.

Lesser notes: `write`'s *"confirmation w/ byte count"* and `edit`/`apply_patch`
success messages are likewise unpinned; and `grep`'s `pattern` is a **regex**
whose dialect differs between hosts (JVM `java.util.regex` vs cljgo Go/RE2), so
only the common subset is portable. `glob` is deliberately matched
**structurally** rather than by translating the pattern to a regex, for exactly
that reason.

## What this spike does NOT cover

- **`bash` timeout/kill** — FINDING 1. Not implemented, not faked.
- **Windows.** Everything here is POSIX-shell-dependent (FINDING 2); it was run
  on macOS/arm64 only.
- **`apply_patch` multi-hunk sections.** One hunk per `*** Update File:`, no
  `@@` context markers. Add/update/delete, single and multi-file, are covered;
  a two-hunk update is not.
- **`grep`/`glob` at scale.** No `.gitignore` handling, no binary-file skipping,
  no symlink-loop guard; `files-under` walks the whole tree. The `limit` caps
  output, not work.
- **`webfetch` for real.** One local `koine.server`, one HTML page, one 404. No
  redirects, no non-2xx body inclusion, no content-type negotiation, no real
  HTML→markdown converter, and the `timeout` argument's expiry is untested.
- **`question` end to end.** The tool's suspend/resume contract is exercised
  directly by handing it a `ctx.answer`; the §10 client loop that would call
  `waitFor` and re-execute is s16's territory, not this spike's.
- **`memory`** (§7E, opt-in) and the `skill` tool (§3, its own source) — neither
  is one of the ten.
- **UTF-8 byte counts for astral characters.** `utf8-count` is computed from
  code units (`String.getBytes` is `java.*`, and `koine.codec` only exposes
  base64), so a surrogate pair counts 6 rather than 4. All fixtures are ASCII.
- **Concurrency.** Every tool call here is serial.
