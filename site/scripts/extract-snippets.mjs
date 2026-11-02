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
//
// Cookbook recipes are scanned too: a `test`-tagged block on a cookbook page is
// extracted as `cookbook_<page>_<n>` for the language its fence names.
//
//   --out <dir>       write to <dir>/<lang>/ instead (parallel runs, scratch checks)
//   --pages <prefix>  only pages whose slug starts with <prefix> (e.g. api/rust/client)

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
	// The Clojure port has no snippet runner yet, so no Clojure block is tagged `test`.
	clojure: "cljc",
	rust: "rs",
}

const argOf = (flag) => (process.argv.includes(flag) ? process.argv[process.argv.indexOf(flag) + 1] : null)
const outRoot = argOf("--out") ? path.resolve(argOf("--out")) : path.join(repoRoot, "site/tests/snippets")
const pagePrefix = argOf("--pages")
const FENCE_LANG = Object.fromEntries(Object.entries(LANGS).map(([lang, l]) => [l.fence, lang]))

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
		if (pagePrefix && !slug.startsWith(pagePrefix)) continue
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

// Cookbook recipes: one page, many languages — the fence picks the language.
const cookbookDir = path.join(docsRoot, "cookbook")
if (fs.existsSync(cookbookDir)) {
	for (const f of fs.readdirSync(cookbookDir).sort()) {
		if (!f.endsWith(".mdx")) continue
		const slug = `cookbook/${f.replace(/\.mdx$/, "")}`
		if (pagePrefix && !slug.startsWith(pagePrefix)) continue
		const seen = {}
		for (const b of fencedBlocks(fs.readFileSync(path.join(cookbookDir, f), "utf8"))) {
			const lang = FENCE_LANG[b.fence]
			if (!lang || !/\btest\b/.test(b.meta) || (only && lang !== only)) continue
			seen[lang] = (seen[lang] || 0) + 1
			const name = `cookbook_${f.replace(/\.mdx$/, "")}_${seen[lang]}`.replace(/-/g, "_")
			const dir = path.join(outRoot, lang)
			fs.mkdirSync(dir, { recursive: true })
			const dest = path.join(dir, `${name}.${EXT[lang]}`)
			fs.writeFileSync(dest, b.code)
			manifestOut.push({ lang, entry: slug, name, file: path.relative(repoRoot, dest), page: slug })
			counts[lang] = (counts[lang] || 0) + 1
		}
	}
}

fs.mkdirSync(outRoot, { recursive: true })
fs.writeFileSync(
	path.join(outRoot, only ? `index.${only}.json` : "index.json"),
	JSON.stringify(manifestOut, null, 2) + "\n",
)

for (const [lang, n] of Object.entries(counts)) console.log(`${LANGS[lang].label.padEnd(11)} ${n} snippet(s)`)
console.log(`total: ${manifestOut.length}`)
