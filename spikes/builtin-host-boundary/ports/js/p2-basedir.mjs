// O2 / js — a baseDir option whose EMPTY value must stay byte-identical to
// today's process-cwd behaviour, for read/write/edit/glob/grep AND for the
// paths inside apply_patch's patch text.
//
// The byte-identity claim is not about where the file lands (resolve("",p) and
// p land in the same place); it is about the STRING the port echoes back. The
// `read` tool puts `${p}` in its media output line and `glob`/`grep` emit paths
// relative to `root`, so a resolver that absolutises on an empty base moves a
// conformance golden even though the file is the same file.
import fs from "node:fs/promises"
import path from "node:path"
import os from "node:os"

// candidate resolver — the shape that would land in the port
const resolveA = (base, p) => (base ? (path.isAbsolute(p) ? p : path.join(base, p)) : p)
// the tempting one-liner, measured so the difference is on the record
const resolveB = (base, p) => path.resolve(base || "", p)

console.log("== O2 js: empty base must be byte-identical ==")
for (const p of ["a.txt", "sub/a.txt", "./a.txt", "../a.txt", path.join(os.tmpdir(), "abs.txt"), ".", ""]) {
  const a = resolveA("", p)
  const b = resolveB("", p)
  console.log(
    `${JSON.stringify(p).padEnd(28)} join_guard=${JSON.stringify(a).padEnd(30)} identical=${a === p ? "YES" : "NO"}  path.resolve=${JSON.stringify(b)} identical=${b === p ? "YES" : "NO"}`,
  )
}

// CONTROL: with a base set the resolver must NOT be identity — otherwise the
// "identical=YES" column above would be trivially true for any input.
const base = await fs.mkdtemp(path.join(os.tmpdir(), "tn-o2-"))
console.log(`CONTROL base_set_is_not_identity=${resolveA(base, "a.txt") === "a.txt" ? "IDENTITY(PROBE BROKEN)" : "differs(ok)"}`)

// where does a relative write actually land, with and without a base?
const cwd = await fs.mkdtemp(path.join(os.tmpdir(), "tn-o2-cwd-"))
process.chdir(cwd)
await fs.mkdir(path.join(base, "sub"), { recursive: true })
await fs.writeFile(resolveA(base, "sub/landed.txt"), "x")
await fs.writeFile(resolveA("", "landed.txt"), "x")
console.log(`LANDED_UNDER_BASE=${await exists(path.join(base, "sub/landed.txt"))}`)
console.log(`LANDED_UNDER_CWD=${await exists(path.join(cwd, "landed.txt"))}`)

// ---- apply_patch: the paths are CONTENT, not arguments ----------------------
// Rewriting them needs only the three header lines the grammar defines, so this
// is a line-prefix rewrite, not a parse of the hunk body.
const patch = `*** Begin Patch
*** Add File: sub/new.txt
+hello
*** Update File: sub/landed.txt
@@
-x
+y
*** Delete File: sub/gone.txt
*** End Patch`

const HEADERS = ["*** Add File: ", "*** Update File: ", "*** Delete File: "]
function rebasePatch(text, b) {
  if (!b) return text // empty base ⇒ byte-identical patch text
  return text
    .split("\n")
    .map((line) => {
      for (const h of HEADERS) {
        if (line.startsWith(h)) {
          const p = line.slice(h.length)
          return h + (path.isAbsolute(p) ? p : path.join(b, p))
        }
      }
      return line
    })
    .join("\n")
}
console.log(`PATCH_EMPTY_BASE_BYTE_IDENTICAL=${rebasePatch(patch, "") === patch}`)
const rebased = rebasePatch(patch, base)
const rewritten = rebased.split("\n").filter((l) => HEADERS.some((h) => l.startsWith(h)))
console.log("PATCH_REBASED_HEADERS:")
for (const l of rewritten) console.log(`  ${l}`)
console.log(`PATCH_ALL_HEADERS_ABSOLUTE=${rewritten.every((l) => l.split(": ")[1].startsWith(base))}`)
// CONTROL: a patch with an absolute header must come through untouched.
const absPatch = `*** Add File: ${path.join(os.tmpdir(), "already-abs.txt")}\n`
console.log(`PATCH_ABSOLUTE_UNTOUCHED=${rebasePatch(absPatch, base) === absPatch}`)
// CONTROL: a body line that merely LOOKS like a header (indented) must not move.
const decoy = `*** Begin Patch\n+*** Add File: decoy.txt\n*** End Patch`
console.log(`PATCH_BODY_DECOY_UNTOUCHED=${rebasePatch(decoy, base) === decoy}`)

async function exists(p) {
  try {
    await fs.stat(p)
    return true
  } catch {
    return false
  }
}

// ---- where the rebase actually belongs -------------------------------------
// Both ports already parse the patch into ops with a `path` field
// (js/src/builtin.ts:539 FILE_MARKER + parsePatch; python builtin.py:625).
// So the base does NOT need a text rewrite at all: it applies to `op.path`
// after parse. Confirm the regex the port ships extracts exactly the paths a
// rebase must touch, and nothing else.
const PORT_MARKER = /^\*\*\* (Add|Update|Delete) File: (.+)$/
const extracted = patch.split("\n").map((l) => PORT_MARKER.exec(l)).filter(Boolean).map((m) => [m[1], m[2].trim()])
console.log(`PORT_REGEX_EXTRACTED=${JSON.stringify(extracted)}`)
console.log(`PORT_REGEX_IGNORES_BODY_DECOY=${PORT_MARKER.test("+*** Add File: decoy.txt") === false}`)
