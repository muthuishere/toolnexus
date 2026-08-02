#!/usr/bin/env node
// Extract runnable examples out of the API Reference pages into real per-language
// source files, so they can be compiled and executed.
//
//   node site/scripts/extract-snippets.mjs
//   node site/scripts/extract-snippets.mjs --lang javascript
//
// A fenced block is extracted when its meta line contains the word `test`:
//
//     ```ts test
//     import { pending } from "toolnexus"
//     …
//     ```
//
// Blocks WITHOUT `test` are display-only — fragments, pseudo-code, option tables. That
// distinction is deliberate: a docs page sometimes needs to show a fragment that cannot
// stand alone, and forcing everything to be runnable would make the prose worse.
//
// Each extracted snippet must be a COMPLETE runnable program. Output lands in
// site/tests/snippets/<lang>/, which the runners compile and execute.

import fs from "node:fs"
import path from "node:path"
import { manifest, LANGS, docsRoot, repoRoot, slugFor } from "./lib/surface.mjs"

const only = process.argv.includes("--lang") ? process.argv[process.argv.indexOf("--lang") + 1] : null

const EXT = {
	javascript: "ts",
	python: "py",
	golang: "go",
	java: "java",
	csharp: "cs",
	elixir: "exs",
}

const outRoot = path.join(repoRoot, "site/tests/snippets")

/** Pull ```<fence> <meta>\n…``` blocks out of MDX. */
function fencedBlocks(src) {
	const out = []
	const re = /^```([a-zA-Z0-9#+]*)([^\n]*)\n([\s\S]*?)^```/gm
	let m
	while ((m = re.exec(src)) !== null) {
		out.push({ fence: m[1], meta: (m[2] || "").trim(), code: m[3] })
	}
	return out
}

// Start clean so a deleted example never lingers as a passing test. With --lang,
// wipe ONLY that port's tree so several language runs can proceed in parallel
// without clobbering each other.
if (only) {
	const dir = path.join(outRoot, only)
	if (fs.existsSync(dir)) fs.rmSync(dir, { recursive: true })
} else if (fs.existsSync(outRoot)) {
	fs.rmSync(outRoot, { recursive: true })
}

const counts = {}
const manifestOut = []

for (const lang of manifest.langs) {
	if (only && lang !== only) continue
	counts[lang] = 0
	for (const entry of manifest.entries) {
		const slug = slugFor(lang, entry)
		const file = path.join(docsRoot, `${slug}.mdx`)
		if (!fs.existsSync(file)) continue
		const blocks = fencedBlocks(fs.readFileSync(file, "utf8")).filter((b) => /\btest\b/.test(b.meta))
		blocks.forEach((b, i) => {
			const name = `${entry.group}_${entry.member}_${i + 1}`.replace(/-/g, "_")
			const dir = path.join(outRoot, lang)
			fs.mkdirSync(dir, { recursive: true })
			const dest = path.join(dir, `${name}.${EXT[lang]}`)
			fs.writeFileSync(dest, b.code)
			manifestOut.push({ lang, entry: entry.id, name, file: path.relative(repoRoot, dest), page: slug })
			counts[lang]++
		})
	}
}

fs.mkdirSync(outRoot, { recursive: true })
fs.writeFileSync(
	path.join(outRoot, only ? `index.${only}.json` : "index.json"),
	JSON.stringify(manifestOut, null, 2) + "\n",
)

for (const [lang, n] of Object.entries(counts)) console.log(`${LANGS[lang].label.padEnd(11)} ${n} snippet(s)`)
console.log(`total: ${manifestOut.length}`)
