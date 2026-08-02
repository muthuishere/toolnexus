#!/usr/bin/env node
// Verify that every symbol named in site/src/data/api-surface.json actually exists in
// the port it claims to. Guards the manifest against transcription drift: a renamed or
// misspelled symbol is caught here rather than surfacing as a broken docs page.
//
//   node site/scripts/verify-symbols.mjs           # report
//   node site/scripts/verify-symbols.mjs --strict  # exit 1 if anything is unresolved
//
// This is a *name presence* check across each port's sources, deliberately loose about
// language syntax. It answers "does this identifier exist in this port at all", which is
// what catches a bad manifest entry. Exact-signature checking is the job of the compiled
// example suite.

import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { execFileSync } from "node:child_process"

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, "../..")
const manifest = JSON.parse(fs.readFileSync(path.join(repoRoot, "site/src/data/api-surface.json"), "utf8"))

const PORTS = {
	javascript: { dir: "js/src", ext: ["ts"] },
	python: { dir: "python/src", ext: ["py"] },
	golang: { dir: "golang", ext: ["go"] },
	java: { dir: "java/src/main/java", ext: ["java"] },
	csharp: { dir: "csharp", ext: ["cs"] },
	elixir: { dir: "elixir/lib", ext: ["ex"] },
}

/** The identifier to look for: last dotted segment, minus prose decoration. */
function identifiersOf(symbol) {
	const cleaned = symbol
		.replace(/^@/, "")
		.replace(/^\[|\]$/g, "")
		.replace(/\s+(annotation|attribute|option|options|tool|task tool|conversation|hooks|budget|relay|task_store)$/i, "")
		.trim()
	const parts = cleaned.split(".").filter(Boolean)
	// ALWAYS require the final segment — the member being claimed. Matching only the
	// owning type (e.g. "McpSource" existing while "parseMcpConfig" does not) is exactly
	// the false positive this check exists to catch.
	return [parts[parts.length - 1]]
}

function grepCount(dir, ext, ident) {
	const abs = path.join(repoRoot, dir)
	if (!fs.existsSync(abs)) return -1
	const includes = ext.flatMap((e) => ["--include", `*.${e}`])
	try {
		const out = execFileSync(
			"grep",
			["-rlw", ...includes, "--exclude-dir=node_modules", "--exclude-dir=obj", "--exclude-dir=bin", "--", ident, abs],
			{ encoding: "utf8" },
		)
		return out.trim() ? out.trim().split("\n").length : 0
	} catch {
		return 0 // grep exits 1 on no match
	}
}

const missing = []
const gaps = []
let checked = 0
for (const entry of manifest.entries) {
	for (const lang of manifest.langs) {
		if (!(lang in (entry.symbols ?? {}))) {
			missing.push({ id: entry.id, lang, symbol: "(no claim in manifest)", hits: 0 })
			continue
		}
		const symbol = entry.symbols[lang]
		if (symbol === null) {
			// Explicitly declared as not shipped in this port — a recorded parity gap,
			// not a manifest error. The page still exists and says so.
			gaps.push({ id: entry.id, lang })
			continue
		}
		checked++
		const port = PORTS[lang]
		const idents = identifiersOf(symbol)
		const hits = Math.max(...idents.map((i) => grepCount(port.dir, port.ext, i)))
		if (hits <= 0) missing.push({ id: entry.id, lang, symbol, idents, hits })
	}
}

const byLang = {}
for (const m of missing) byLang[m.lang] = (byLang[m.lang] || 0) + 1

console.log(`checked ${checked} symbol claims across ${manifest.entries.length} entries × ${manifest.langs.length} ports`)
console.log(`verified: ${checked - missing.length}`)
console.log(`declared parity gaps (port does not ship it): ${gaps.length}`)
for (const g of gaps) console.log(`  gap  ${g.lang.padEnd(11)} ${g.id}`)
console.log(`unresolved: ${missing.length}`)
if (missing.length) {
	console.log(`by port:`, byLang)
	console.log("")
	for (const m of missing) {
		console.log(`  ${m.lang.padEnd(11)} ${String(m.id).padEnd(28)} ${m.symbol}`)
	}
}

if (process.argv.includes("--strict") && missing.length) process.exit(1)
