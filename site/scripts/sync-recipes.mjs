#!/usr/bin/env node
// Inline the in-process-client recipe sources into their docs pages.
//
//   node site/scripts/sync-recipes.mjs           rewrite the pages
//   node site/scripts/sync-recipes.mjs --check   exit 1 if a page is out of date
//
// The recipes live as real files under site/recipes/in-process-client/<lang>/<page>/, the
// files that were compiled and run (against the fake agent/CLI in tools/mock-llm/, and live). A page
// marks where a recipe goes with
//
//     {/* recipe:<page>/<group> */}
//     …generated Tabs, do not edit…
//     {/* /recipe */}
//
// and this script replaces everything between the markers with one tab per port, each holding
// the group's files verbatim. Edit the source file, run this, never edit the page block.

import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const siteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..")
const recipesRoot = path.join(siteRoot, "recipes", "in-process-client")
const docsRoot = path.join(siteRoot, "src", "content", "docs", "in-process-client")

const LANGS = [
	["javascript", "JavaScript", "js"],
	["python", "Python", "python"],
	["golang", "Go", "go"],
	["java", "Java", "java"],
	["csharp", "C#", "csharp"],
	["elixir", "Elixir", "elixir"],
	["clojure", "Clojure", "clojure"],
	["rust", "Rust", "rust"],
]

// Which files of a page directory belong to a group. Helpers sort before the entry point.
const GROUPS = {
	"overview/main": (f) => /^(main|Main|Program)\.[a-z]+$/.test(f),
	"overview/serve": (f) => /^(serve|Serve)\.[a-z]+$/.test(f) || f === "serve/main.go",
	"acp-agent/main": () => true,
	"one-shot-cli/main": () => true,
	"extend/main": () => true,
}
const isEntry = (f) => /(^|\/)(main|Main|Program)\.[a-z]+$/.test(f)

// Short per-port notes shown above the code, keyed by group then language.
const NOTES = {
	"overview/main": {
		clojure: "Clojure has no stream API: the emitted events reach `run`'s `:on-event` as they happen.",
	},
	"acp-agent/main": {
		java: "`AcpClient.generateStream` throws `IOException`, so the lambda wraps it; `generate(agent)` plugs in as-is for `run`.",
		clojure: "Clojure has no stream API: the agent's chunks reach `run`'s `:on-event` live, while the turn runs.",
	},
	"one-shot-cli/main": {
		golang: "The function is passed as `GenerateStream` only to get the run's `ctx`, so cancelling the run kills the process; it emits nothing.",
		elixir: "`System.cmd` cannot feed stdin, so the prompt goes through a temp file and `sh -c '… < file'`. There is no per-turn timeout here; wrap the call in a `Task` with a timeout if you need one.",
		clojure: "koine's JSON writer sorts object keys, so the rendered tool schemas differ in key order from the other ports; the prompt's wording is the same.",
	},
}

function listFiles(dir) {
	const out = []
	for (const e of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => (a.name < b.name ? -1 : 1))) {
		if (e.isDirectory()) for (const f of listFiles(path.join(dir, e.name))) out.push(`${e.name}/${f}`)
		else out.push(e.name)
	}
	return out
}

function render(group) {
	const [page] = group.split("/")
	const tabs = []
	for (const [lang, label, fence] of LANGS) {
		const dir = path.join(recipesRoot, lang, page)
		const files = listFiles(dir)
			.filter(GROUPS[group])
			.filter((f) => group !== "overview/main" || !f.startsWith("serve/"))
			.sort((a, b) => isEntry(a) - isEntry(b) || (a < b ? -1 : 1))
		if (!files.length) throw new Error(`no ${lang} files for ${group} in ${dir}`)
		const parts = [`<TabItem label="${label}">`]
		const note = NOTES[group]?.[lang]
		if (note) parts.push(note, "")
		for (const f of files) {
			const src = fs.readFileSync(path.join(dir, f), "utf8").replace(/\s+$/, "")
			parts.push("```" + fence + ` title="${f}"`, src, "```")
		}
		parts.push("</TabItem>")
		tabs.push(parts.join("\n"))
	}
	return ['<Tabs syncKey="lang">', ...tabs, "</Tabs>"].join("\n")
}

const check = process.argv.includes("--check")
let stale = 0
for (const file of fs.readdirSync(docsRoot).filter((f) => f.endsWith(".mdx"))) {
	const p = path.join(docsRoot, file)
	const before = fs.readFileSync(p, "utf8")
	const after = before.replace(
		/(\{\/\* recipe:([\w/-]+) \*\/\}\n)[\s\S]*?(\{\/\* \/recipe \*\/\})/g,
		(_, open, group, close) => {
			if (!GROUPS[group]) throw new Error(`${file}: unknown recipe group ${group}`)
			return open + render(group) + "\n" + close
		},
	)
	if (after === before) continue
	stale++
	if (check) console.error(`out of date: ${path.relative(siteRoot, p)}`)
	else fs.writeFileSync(p, after)
}
if (check && stale) {
	console.error("run: node site/scripts/sync-recipes.mjs")
	process.exit(1)
}
console.log(check ? "recipes in sync" : `updated ${stale} page(s)`)
