#!/usr/bin/env node
// Short-form release announcements, DERIVED from CHANGELOG.md.
//
//   node site/scripts/release-notes.mjs --body 0.20.0   # the GitHub Release body, to stdout
//   node site/scripts/release-notes.mjs --page          # write the docs site /releases page
//
// Why derived and not written. CHANGELOG.md stays the record: it is the one place a user
// learns what changed, it says what is NOT done, and CLAUDE.md requires it in the same PR as
// the change. But the record is not an announcement — v0.19.0's section is 267 lines, which is
// the right amount of detail to look something up in and far too much to open a release to.
//
// The fix is a SHORTER VIEW of the same text, never a second account of it. Everything below is
// extracted from CHANGELOG.md, so an announcement cannot say something the changelog does not.
// That is the same rule the old "the release body IS the changelog section" convention was
// protecting; this keeps the rule and drops the verbosity.
//
// Extraction: a version's highlights are its `### ` subheadings when it has them (0.19.0 and
// earlier), otherwise the bold lead sentence of each paragraph (0.20.0 onward). Both shapes are
// in the file and both are load-bearing, so the generator reads both rather than asking anyone
// to go back and reformat five releases.

import fs from "node:fs"
import path from "node:path"
import { execFileSync } from "node:child_process"
import { fileURLToPath } from "node:url"

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, "../..")
const REPO = "muthuishere/toolnexus"
const SITE = "https://muthuishere.github.io/toolnexus"

const changelog = fs.readFileSync(path.join(repoRoot, "CHANGELOG.md"), "utf8")

/** Strip inline markdown down to something that reads as a plain sentence. */
const plain = (s) =>
  s
    .replace(/\*\*?/g, "")
    .replace(/`/g, "")
    .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
    .replace(/\s+/g, " ")
    .trim()

/** A scannable lead: whole sentences up to a budget, never a mid-sentence cut.
 *  One sentence is often the abstract half ("the builtins no longer close over the host
 *  process") and the next is the part a reader can act on ("bash runs on native Windows…"),
 *  so the budget buys the second one when it is short enough to still scan. */
const LEAD_BUDGET = 220
function leadSummary(s) {
  const text = plain(s)

  // Slice on sentence boundaries rather than matching sentences. A match-based split silently
  // DROPS whatever precedes a period it cannot pair with — `mcp.json` turned
  // "Java can now parse an mcp.json without connecting to anything." into
  // "json without connecting to anything.", losing the subject. Slicing cannot lose text.
  // A boundary is terminal punctuation followed by whitespace or end-of-string, so an
  // intra-word period (mcp.json, 0.20.0, SPEC.md) is not one.
  const sentences = []
  const boundary = /[.!?]+(?=\s|$)/g
  let start = 0
  let m
  while ((m = boundary.exec(text)) !== null) {
    sentences.push(text.slice(start, m.index + m[0].length))
    start = m.index + m[0].length
  }
  if (start < text.length) sentences.push(text.slice(start))
  if (!sentences.length) return text

  let out = ""
  for (const sentence of sentences) {
    if (out && (out + sentence).trim().length > LEAD_BUDGET) break
    out += sentence
  }
  return (out || sentences[0]).trim()
}

/** CHANGELOG.md -> [{ version, date, highlights[], breaking, anchor }], newest first. */
export function parseChangelog(src = changelog) {
  const versions = []
  let cur = null

  // Paragraph-wise, not line-wise: a bold lead routinely wraps across lines, so `^\*\*(.+?)\*\*`
  // against a single line silently drops exactly the longest — which is to say the most
  // important — entries. That bug shipped once here; the blank-line split is what fixes it.
  for (const block of src.split(/\n\s*\n/)) {
    const head = block.match(/^## (\d+\.\d+\.\d+)\s+—\s+(\S+)/)
    if (head) {
      cur = { version: head[1], date: head[2], subheads: [], boldLeads: [], body: [] }
      versions.push(cur)
      continue
    }
    if (/^## /.test(block)) {
      cur = null // `## Unreleased`, or the file header
      continue
    }
    if (!cur) continue
    cur.body.push(block)

    const sub = block.match(/^### (.+)$/m)
    if (sub) {
      cur.subheads.push(plain(sub[1]))
      continue
    }
    // One highlight per paragraph that OPENS in bold. A paragraph that merely contains bold
    // text is continuation prose, not a headline.
    const bold = block.replace(/\n/g, " ").match(/^\*\*(.+?)\*\*/)
    if (bold) cur.boldLeads.push(leadSummary(bold[1]))
  }

  return versions.map((v) => {
    const body = v.body.join("\n")
    return {
      version: v.version,
      date: v.date,
      // Subheadings win when a release has them: they were written as section titles, so they
      // already read as summaries. Bold leads are the newer shape.
      highlights: v.subheads.length ? v.subheads : v.boldLeads,
      // A breaking change must never be something a reader discovers after upgrading.
      breaking: /\bbreaking\b/i.test(body),
      anchor: `https://github.com/${REPO}/blob/main/CHANGELOG.md#${v.version.replace(/\./g, "")}--${v.date}`,
    }
  })
}

/** Everyone whose commits are in <prev>..<tag>. */
function contributors(version) {
  const tag = `v${version}`
  let range = tag
  try {
    const prev = execFileSync("git", ["describe", "--tags", "--abbrev=0", `${tag}^`], {
      cwd: repoRoot,
      encoding: "utf8",
    }).trim()
    range = `${prev}..${tag}`
  } catch {
    /* first release, or the tag has no predecessor — fall back to everything up to the tag */
  }
  let out = ""
  try {
    out = execFileSync("git", ["log", range, "--format=%an%x00%ae"], { cwd: repoRoot, encoding: "utf8" })
  } catch {
    return { people: [], range: null }
  }
  const seen = new Map()
  for (const line of out.split("\n").filter(Boolean)) {
    const [name, email] = line.split("\0")
    // Authorship only. The Co-Authored-By trailers this repo adds for the assistant are
    // deliberately NOT counted as contributors — they are on every commit, so listing them
    // would say nothing about who worked on a given release.
    if (/noreply@anthropic\.com$/i.test(email) || /\[bot\]/i.test(name)) continue
    if (!seen.has(name)) seen.set(name, email)
  }
  return { people: [...seen.keys()], range }
}

/** The GitHub Release body: what changed, what breaks, who did it, where the detail is. */
export function releaseBody(version) {
  const v = parseChangelog().find((x) => x.version === version)
  if (!v) throw new Error(`no '## ${version} — <date>' section in CHANGELOG.md`)

  const { people } = contributors(version)
  const lines = []

  for (const h of v.highlights) lines.push(`- ${h}`)

  const out = [lines.join("\n")]
  if (v.breaking) {
    out.push(`> **This release contains a breaking change.** The changelog says which ports and what moves.`)
  }
  out.push(`**Full detail:** [CHANGELOG ${version}](${v.anchor}) · **All releases:** ${SITE}/releases/`)
  if (people.length) out.push(`**Contributors:** ${people.map((p) => `${p}`).join(", ")} — thank you.`)
  out.push(
    `_Install coordinates for all seven ports: ${SITE}/install/_`,
  )
  return out.join("\n\n") + "\n"
}

/** MDX reads a bare `{` as the start of a JS expression, so changelog prose containing one
 *  (`{"todos":[…]}`) fails the build with a ReferenceError rather than a parse error, which is
 *  a confusing way to find out. Escaping is only needed for the MDX page — the GitHub release
 *  body is plain markdown and must keep the braces verbatim. */
const mdxEscape = (s) => s.replace(/[{}]/g, (c) => `\\${c}`)

/** The docs site page: every release, newest first, as scannable highlights. */
export function releasesPage() {
  const versions = parseChangelog()
  const body = versions
    .map((v) => {
      const bullets = v.highlights.map((h) => `- ${mdxEscape(h)}`).join("\n")
      const breaking = v.breaking ? `\n<Aside type="caution" title="Contains a breaking change">\nThe [full changelog entry](${v.anchor}) names the ports affected and what moves.\n</Aside>\n` : ""
      return `## ${v.version} — ${v.date}\n${breaking}
${bullets || "_See the full entry._"}

[Full changelog](${v.anchor}) · [GitHub release](https://github.com/${REPO}/releases/tag/v${v.version})
`
    })
    .join("\n")

  return `---
title: Releases
description: Every toolnexus release, newest first — what changed, in one line each, with a link to the full entry.
---

import { Aside } from '@astrojs/starlight/components';

What changed in each release, one line per item. The [CHANGELOG](https://github.com/${REPO}/blob/main/CHANGELOG.md)
is the full record — it carries the reasoning, the per-port detail, and what each release
deliberately does **not** do.

${body}`
}

// --- cli ---------------------------------------------------------------------

const args = process.argv.slice(2)
if (args.includes("--body")) {
  const version = args[args.indexOf("--body") + 1]
  if (!version) {
    console.error("usage: release-notes.mjs --body <version>")
    process.exit(2)
  }
  process.stdout.write(releaseBody(version))
} else if (args.includes("--page")) {
  const file = path.join(repoRoot, "site/src/content/docs/releases.mdx")
  fs.writeFileSync(file, releasesPage())
  const n = parseChangelog().length
  console.log(`wrote ${path.relative(repoRoot, file)} — ${n} release(s)`)
} else {
  console.error("usage: release-notes.mjs --body <version> | --page")
  process.exit(2)
}
