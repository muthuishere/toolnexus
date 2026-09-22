/**
 * The host boundary (ADR 0034, issues #100/#101/#102): which interpreter runs,
 * what a relative path means, and what a timeout kills.
 *
 * Every assertion carries its control. The spike that produced these fixes
 * twice reported a clean kill from a broken probe, so "no orphan" is only
 * evidence next to a run proving the command can write the marker at all
 * (spikes/builtin-host-boundary/SPIKE.md §1).
 */
import { strict as assert } from "node:assert"
import test from "node:test"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { createBuiltinTools, builtinShell, selectBuiltins } from "../dist/index.js"
import type { Tool, ToolResult } from "../dist/index.js"

const isWindows = process.platform === "win32"
const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), "tn-boundary-"))
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

function toolNamed(cfg: Parameters<typeof createBuiltinTools>[0], name: string): Tool {
  const tool = createBuiltinTools(cfg).find((t) => t.name === name)
  assert.ok(tool, `builtin ${name} not found`)
  return tool
}

const run = (tool: Tool, args: Record<string, unknown>, ctx?: { signal?: AbortSignal }) =>
  tool.execute(args, ctx as never) as Promise<ToolResult>

// ---------------------------------------------------------------------------
// #102 — a timeout kills the job, not the shell
// ---------------------------------------------------------------------------

// The GRANDCHILD writes the marker, and `sleep 0.2` in front stops the shell
// exec-optimising the single command away — the difference between measuring an
// orphan and measuring nothing.
const orphanCommand = (marker: string) => `sleep 0.2; sh -c 'sleep 1; touch ${marker}'`

test("bash: a timeout kills the whole job, not just the interpreter", { skip: isWindows }, async () => {
  const dir = tmp()
  const marker = path.join(dir, "orphan.marker")
  const res = await run(toolNamed(undefined, "bash"), { command: orphanCommand(marker), timeout: 300 })

  assert.equal(res.isError, true)
  assert.match(res.output, /timed out/)
  assert.equal(res.metadata?.timedOut, true)
  assert.equal(res.metadata?.killedTree, true)

  await sleep(2000)
  assert.equal(fs.existsSync(marker), false, "the grandchild outlived the kill")
})

test("bash: control — the probe command can actually write the marker", { skip: isWindows }, async () => {
  const dir = tmp()
  const marker = path.join(dir, "control.marker")
  const res = await run(toolNamed(undefined, "bash"), { command: orphanCommand(marker), timeout: 20_000 })
  assert.equal(res.isError, false, res.output)
  assert.ok(fs.existsSync(marker), "the command cannot write the marker at all — the orphan test proves nothing")
})

test("bash: aborting the surrounding call stops the work", { skip: isWindows }, async () => {
  const dir = tmp()
  const marker = path.join(dir, "cancelled.marker")
  const controller = new AbortController()
  setTimeout(() => controller.abort(), 300)

  const res = await run(toolNamed(undefined, "bash"), { command: orphanCommand(marker), timeout: 60_000 }, { signal: controller.signal })
  assert.equal(res.isError, true)
  assert.match(res.output, /cancelled/)
  assert.equal(res.metadata?.timedOut, false, "a cancellation is not a timeout")

  await sleep(2000)
  assert.equal(fs.existsSync(marker), false, "cancellation left the job running")
})

// ---------------------------------------------------------------------------
// #100 — the interpreter is chosen, and reported
// ---------------------------------------------------------------------------

test("bash: the resolved interpreter is reported on every result", async () => {
  const res = await run(toolNamed(undefined, "bash"), { command: isWindows ? "echo hi" : "echo hi" })
  assert.ok(res.metadata?.shell, "metadata.shell must name the interpreter that ran")
  if (!isWindows) assert.equal(res.metadata?.shell, "sh -c")
})

test("bash: a host-supplied shell is used verbatim", { skip: isWindows }, async () => {
  const res = await run(toolNamed({ shell: ["sh", "-c"] }, "bash"), { command: "echo verbatim" })
  assert.equal(res.isError, false, res.output)
  assert.match(res.output, /verbatim/)
  assert.equal(res.metadata?.shell, "sh -c")
})

test("builtinShell reports the detection, and disabling bash makes a shell unnecessary", async () => {
  assert.ok(builtinShell().length > 0)
  const names = selectBuiltins({ tools: { bash: false } }).map((t) => t.name)
  assert.equal(names.includes("bash"), false)
})

// ---------------------------------------------------------------------------
// #101 — one base directory, and optional confinement
// ---------------------------------------------------------------------------

test("baseDir: a relative write lands under it, not in the process cwd", async () => {
  const base = tmp()
  const res = await run(toolNamed({ baseDir: base }, "write"), { path: "sub/nested.txt", content: "landed" })
  assert.equal(res.isError, false, res.output)
  assert.ok(fs.existsSync(path.join(base, "sub", "nested.txt")))
  assert.equal(fs.existsSync(path.join(process.cwd(), "sub", "nested.txt")), false, "relative write leaked into the process cwd")

  const read = await run(toolNamed({ baseDir: base }, "read"), { path: "sub/nested.txt" })
  assert.equal(read.output, "landed")
})

test("baseDir: unset means the process cwd — today's behaviour, byte for byte", async () => {
  const dir = tmp()
  const target = path.join(dir, "absolute.txt")
  const res = await run(toolNamed(undefined, "write"), { path: target, content: "x" })
  assert.equal(res.isError, false, res.output)
  assert.ok(fs.existsSync(target))
})

test("baseDir: apply_patch resolves the paths inside the patch text", async () => {
  const base = tmp()
  const patch = "*** Begin Patch\n*** Add File: pkg/new.txt\n+hello\n*** End Patch"
  const res = await run(toolNamed({ baseDir: base }, "apply_patch"), { patchText: patch })
  assert.equal(res.isError, false, res.output)
  assert.ok(fs.existsSync(path.join(base, "pkg", "new.txt")), "the path inside the patch text was not resolved")
})

test("baseDir: bash defaults its workdir to it", { skip: isWindows }, async () => {
  const base = tmp()
  fs.writeFileSync(path.join(base, "marker.txt"), "x")
  const res = await run(toolNamed({ baseDir: base }, "bash"), { command: "ls marker.txt" })
  assert.equal(res.isError, false, res.output)
  assert.match(res.output, /marker\.txt/)
})

test("confinement: escapes are refused, and paths inside are still served", async () => {
  const base = tmp()
  const outside = tmp()
  fs.writeFileSync(path.join(outside, "secret.txt"), "secret")
  const read = toolNamed({ baseDir: base, confineToBaseDir: true }, "read")

  for (const p of ["../escape.txt", path.join(outside, "secret.txt")]) {
    const res = await run(read, { path: p })
    assert.equal(res.isError, true, `${p} should be refused`)
    assert.match(res.output, /outside baseDir/)
  }

  fs.writeFileSync(path.join(base, "ok.txt"), "fine")
  const inside = await run(read, { path: "ok.txt" })
  assert.equal(inside.isError, false, "a path inside baseDir must still be read")
})

test("confinement: a symlink out of the base is refused", { skip: isWindows }, async () => {
  const base = tmp()
  const outside = tmp()
  fs.writeFileSync(path.join(outside, "secret.txt"), "secret")
  fs.symlinkSync(outside, path.join(base, "link"))
  const res = await run(toolNamed({ baseDir: base, confineToBaseDir: true }, "read"), { path: "link/secret.txt" })
  assert.equal(res.isError, true, "a symlink out of baseDir must be refused")
})

test("confinement: covers paths that do not exist yet — or it is not a check for write", async () => {
  const base = tmp()
  const write = toolNamed({ baseDir: base, confineToBaseDir: true }, "write")
  const refused = await run(write, { path: "../escape.txt", content: "x" })
  assert.equal(refused.isError, true)
  const allowed = await run(write, { path: "deep/new/file.txt", content: "x" })
  assert.equal(allowed.isError, false, allowed.output)
})

// ---------------------------------------------------------------------------
// §4A — grep emits the string it sorted by
// ---------------------------------------------------------------------------

test("grep: the match line carries the /-separated walk-root-relative path", async () => {
  const base = tmp()
  fs.mkdirSync(path.join(base, "tree", "sub"), { recursive: true })
  fs.writeFileSync(path.join(base, "tree", "sub", "a.txt"), "needle\n")

  const res = await run(toolNamed({ baseDir: base }, "grep"), { pattern: "needle", path: "tree" })
  assert.equal(res.isError, false, res.output)
  assert.equal(res.output, "sub/a.txt:1:needle")
  assert.equal(res.output.includes("\\"), false, "§4A requires `/` on every platform")
})
