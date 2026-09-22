// O3 / js — canonicalise BOTH sides and refuse escapes.
//   (a) a symlink out of the base
//   (b) a path that does not exist yet (the `write` case)
//   (c) case normalisation — does fs.realpathSync.native normalise case?
//   (d) what it raises when the path is missing entirely
//
// Every "refused" row has a control: an INSIDE path that must be allowed by the
// same function. A check that refuses everything refuses escapes too, and
// measures nothing.
import fs from "node:fs"
import path from "node:path"
import os from "node:os"

// --- the candidate ----------------------------------------------------------
/** realpath the deepest EXISTING ancestor, re-attach the tail. */
function canonical(p) {
  let cur = path.resolve(p)
  const tail = []
  for (;;) {
    try {
      return path.join(fs.realpathSync.native(cur), ...tail)
    } catch (e) {
      if (e.code !== "ENOENT") throw e
      const parent = path.dirname(cur)
      if (parent === cur) return path.join(cur, ...tail) // hit the root
      tail.unshift(path.basename(cur))
      cur = parent
    }
  }
}
function contained(base, p) {
  const cb = canonical(base)
  const cp = canonical(path.isAbsolute(p) ? p : path.join(base, p))
  const rel = path.relative(cb, cp)
  return rel === "" || (!rel.startsWith("..") && !path.isAbsolute(rel))
}

// --- fixture ----------------------------------------------------------------
const root = fs.mkdtempSync(path.join(os.tmpdir(), "tn-o3-"))
const base = path.join(root, "base")
const outside = path.join(root, "outside")
fs.mkdirSync(path.join(base, "sub"), { recursive: true })
fs.mkdirSync(outside)
fs.writeFileSync(path.join(base, "sub", "in.txt"), "in")
fs.writeFileSync(path.join(outside, "secret.txt"), "secret")
fs.symlinkSync(outside, path.join(base, "link")) // (a)

console.log("== O3 js ==")
const rows = [
  ["CONTROL inside file", "sub/in.txt", true],
  ["CONTROL the base itself", ".", true],
  ["CONTROL empty string", "", true],
  ["(b) nonexistent file (write)", "sub/brand/new.txt", true],
  ["(b) nonexistent ESCAPE", "../outside/brand/new.txt", false],
  ["relative escape", "../outside/secret.txt", false],
  ["absolute escape", path.join(outside, "secret.txt"), false],
  ["(a) via symlink out of base", "link/secret.txt", false],
  ["(a) CONTROL symlink dir itself", "link", false],
]
let wrong = 0
for (const [label, p, want] of rows) {
  let got, note = ""
  try {
    got = contained(base, p)
  } catch (e) {
    got = `THREW:${e.code ?? e.message}`
  }
  const ok = got === want
  if (!ok) wrong++
  console.log(`${label.padEnd(30)} contained=${String(got).padEnd(5)} want=${want}  ${ok ? "ok" : "MISMATCH"} ${note}`)
}
console.log(`ROWS_WRONG=${wrong}`)

// --- (c) case normalisation --------------------------------------------------
const mixed = path.join(base.slice(0, -4) + "BASE", "sub", "in.txt") // …/BASE/sub/in.txt
const upperTail = path.join(base, "SUB", "in.txt")
console.log(`FS_CASE_INSENSITIVE=${fs.existsSync(upperTail)}`)
console.log(`REALPATH_NATIVE_OF_MIXED_CASE=${JSON.stringify(safe(() => fs.realpathSync.native(upperTail)))}`)
console.log(`REALPATH_PLAIN_OF_MIXED_CASE=${JSON.stringify(safe(() => fs.realpathSync(upperTail)))}`)
// Compare against the realpath of the true-cased path, NOT against path.join(base,…):
// on macOS `base` is /var/... and its realpath is /private/var/..., so comparing to the
// lexical join reports NORMALISES_CASE=false for the wrong reason. That is the broken-probe
// trap; the line below is the fixed comparison and the line above it is the evidence.
const trueCased = fs.realpathSync.native(path.join(base, "sub", "in.txt"))
console.log(`TRUE_CASED_REALPATH=${JSON.stringify(trueCased)}`)
console.log(`NATIVE_NORMALISES_CASE=${safe(() => fs.realpathSync.native(upperTail)) === trueCased}`)
console.log(`PLAIN_NORMALISES_CASE=${safe(() => fs.realpathSync(upperTail)) === trueCased}`)
console.log(`MIXED_CASE_CONTAINED=${safe(() => contained(base, upperTail))}`)

// --- (d) what is raised when the path is missing entirely --------------------
const missing = path.join(base, "nope", "deeper", "x.txt")
console.log(`RAW_REALPATH_NATIVE_ON_MISSING=${JSON.stringify(safe(() => fs.realpathSync.native(missing)))}`)
console.log(`CANONICAL_ON_MISSING=${JSON.stringify(safe(() => canonical(missing)))}`)
console.log(`CANONICAL_MISSING_EQUALS_LEXICAL=${safe(() => canonical(missing)) === missing ? "not-necessarily-see-above" : "differs(base itself was a symlink)"}`)

function safe(f) {
  try {
    return f()
  } catch (e) {
    return `THREW:${e.code ?? e.message}`
  }
}

// --- (c) continued: the mixed-cased BASE, the shape python cannot fix -------
const baseMixed = path.join(path.dirname(base), "BASE")
console.log(`\nBASE_MIXED_EXISTS=${fs.existsSync(baseMixed)}`)
console.log(`CANONICAL_BASE      =${JSON.stringify(canonical(base))}`)
console.log(`CANONICAL_BASE_MIXED=${JSON.stringify(canonical(baseMixed))}`)
console.log(`CANONICAL_AGREE=${canonical(base) === canonical(baseMixed)}`)
console.log(`CONTROL_STRINGS_DIFFER=${base !== baseMixed}`)
console.log(`INSIDE_FILE_UNDER_MIXED_BASE_CONTAINED=${safe(() => contained(baseMixed, "sub/in.txt"))}`)
console.log(`REAL_ESCAPE_UNDER_MIXED_BASE_REFUSED=${safe(() => !contained(baseMixed, "../outside/secret.txt"))}`)
