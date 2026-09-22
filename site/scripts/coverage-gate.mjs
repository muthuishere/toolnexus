#!/usr/bin/env node
// The API Reference coverage gate: a page that regressed to a stub, an entry with no page,
// and a page nothing points at all fail the build HERE rather than shipping quietly.
//
//   node site/scripts/coverage-gate.mjs                  # report everything, exit 0
//   node site/scripts/coverage-gate.mjs --strict         # exit 1 on a STRUCTURAL violation
//   node site/scripts/coverage-gate.mjs --strict-content # also exit 1 on a CONTENT violation
//
// Two tiers, because they mean different things:
//
//   STRUCTURAL — a page is missing, is an unfilled scaffold, is an orphan, or is a declared
//   parity gap that does not say so. Each is an ABSENCE: nothing on the page tells a reader,
//   or a reviewer, that anything is wrong. These are clean today and CI blocks on them.
//
//   CONTENT — a shipped page missing "when to use", "why not the alternative", or the third
//   example. Real, but a KNOWN pre-existing backlog concentrated in four entries written
//   before that contract settled (`types/tool-result`, `types/context`, `mcp/parse-config`,
//   `adapters/openai`). Reported every run so the number cannot quietly grow; not blocking
//   until the backlog is cleared, and worth an owner ruling first on whether a *type* page
//   should owe a "why not the alternative" section at all — for a data shape there is often
//   no alternative to name, and forcing the heading produces filler.
//
// Why this exists, concretely. `verify-symbols.mjs` proves every symbol the manifest NAMES
// exists in the port it claims. It says nothing about whether the page describing that symbol
// was ever written. On 2026-09-22 that gap cost us twice in one day: fifteen pages had been
// sitting as unfilled `{/* TODO */}` scaffolds since an earlier generation pass — including
// `client/create` and `toolkit/create`, the two most-visited entry points, in five ports — and
// thirty-three Clojure examples had never been tagged ` test `, so the hermetic runner never
// extracted them and nobody noticed they did not even compile. Both are exactly the kind of
// absence a human review does not see, because there is nothing on the page to react to.
//
// The checks are deliberately structural, not stylistic. They ask "is this page finished",
// never "is this page good".

import fs from "node:fs"
import path from "node:path"
import { manifest, LANGS, docsRoot, allPages, fileFor, slugFor } from "./lib/surface.mjs"

const strict = process.argv.includes("--strict")
const strictContent = process.argv.includes("--strict-content")

/** Which tier each violation kind belongs to — see the header. */
const STRUCTURAL = new Set(["missing-page", "unfilled-stub", "orphan-page", "gap-page-unmarked", "gap-page-no-alternative"])

/** A page the generator scaffolded but nobody filled in. */
const TODO_RE = /\{\/\*\s*TODO|_TODO:/

/** `### 1. …` headings under `## Examples` — the generator emits three, and three is the bar. */
const MIN_EXAMPLES = 3

const violations = []
const add = (kind, where, detail) => violations.push({ kind, where, detail })

// ---------------------------------------------------------------------------
// 1 + 2 + 3: every (entry × port) page exists, is filled in, and is complete
// ---------------------------------------------------------------------------

const expected = new Set()

for (const { lang, entry } of allPages()) {
	const file = fileFor(lang, entry)
	expected.add(path.resolve(file))
	const where = slugFor(lang, entry)

	if (!fs.existsSync(file)) {
		add("missing-page", where, `the manifest declares this entry point but no page exists`)
		continue
	}

	const src = fs.readFileSync(file, "utf8")

	if (TODO_RE.test(src)) {
		add("unfilled-stub", where, `still carries a generator TODO marker — scaffolded, never written`)
	}

	const shipped = entry.symbols?.[lang] != null

	if (!shipped) {
		// A declared parity gap. The one thing it MUST do is say, in a caution aside, that this
		// port does not ship the entry point — otherwise a reader has no way to tell a gap from
		// a page someone forgot to finish, which is the whole point of the check.
		//
		// The wording is deliberately not pinned. Ports say it differently and more precisely
		// than a template would ("Not available as a constructible symbol in Go", "No
		// constructible symbol in C# — it's behavior, not a type"), and that is an improvement,
		// not a violation.
		if (!/<Aside[^>]*type="caution"[^>]*title="[^"]*(not available|no constructible|only .* exposes)/i.test(src)) {
			add("gap-page-unmarked", where, `symbol is null in the manifest, but no caution aside marks the absence`)
		}
		// It must then point somewhere useful — either the short form ("what to use instead"),
		// or, where the behaviour still exists without a named symbol, a fully written page.
		const hasAlternative = src.includes("## What to use instead")
		const hasFullBody = src.includes("## When to use it") && src.includes("## Examples")
		if (!hasAlternative && !hasFullBody) {
			add("gap-page-no-alternative", where, `a gap page must say what to reach for instead, or document the behaviour in full`)
		}
		continue
	}

	// A shipped entry point. The generator's contract is signature + when + why + 3 examples.
	if (!src.includes("## When to use it")) {
		add("no-when", where, `missing "## When to use it" — a signature dump is not a reference page`)
	}
	if (!/## Why this and not the alternative/.test(src)) {
		add("no-why", where, `missing "## Why this and not the alternative" — the section that names the thing NOT to use`)
	}

	const examplesIdx = src.indexOf("## Examples")
	if (examplesIdx === -1) {
		add("no-examples-section", where, `missing "## Examples"`)
	} else {
		const body = src.slice(examplesIdx)
		const numbered = body.match(/^### \d+\./gm)?.length ?? 0
		if (numbered < MIN_EXAMPLES) {
			add("too-few-examples", where, `${numbered} example(s) under "## Examples"; the contract is ${MIN_EXAMPLES}`)
		}
	}
}

// ---------------------------------------------------------------------------
// 4: no orphans — a page under api/<lang>/ that no manifest entry points at
// ---------------------------------------------------------------------------

const langDirs = manifest.langs.map((l) => path.join(docsRoot, "api", LANGS[l].slug))

function walk(dir, out = []) {
	if (!fs.existsSync(dir)) return out
	for (const name of fs.readdirSync(dir).sort()) {
		const full = path.join(dir, name)
		const st = fs.statSync(full)
		if (st.isDirectory()) walk(full, out)
		else if (name.endsWith(".mdx")) out.push(full)
	}
	return out
}

for (const dir of langDirs) {
	for (const file of walk(dir)) {
		if (!expected.has(path.resolve(file))) {
			add("orphan-page", path.relative(docsRoot, file), `no manifest entry points at this page — add the entry, or delete the page`)
		}
	}
}

// ---------------------------------------------------------------------------
// report
// ---------------------------------------------------------------------------

const byKind = new Map()
for (const v of violations) byKind.set(v.kind, [...(byKind.get(v.kind) ?? []), v])

const pageCount = manifest.entries.length * manifest.langs.length
console.log(`checked ${pageCount} pages (${manifest.entries.length} entries × ${manifest.langs.length} ports)`)

const structural = violations.filter((v) => STRUCTURAL.has(v.kind))
const content = violations.filter((v) => !STRUCTURAL.has(v.kind))

for (const tier of [
	{ label: "STRUCTURAL", list: structural, blocking: true },
	{ label: "CONTENT", list: content, blocking: strictContent },
]) {
	if (!tier.list.length) {
		console.log(`\n${tier.label}: clean`)
		continue
	}
	console.log(`\n${tier.label}: ${tier.list.length} violation(s)${tier.blocking ? "" : " — reported, not blocking"}`)
	for (const [kind, list] of [...byKind].sort()) {
		const inTier = list.filter((v) => tier.list.includes(v))
		if (!inTier.length) continue
		console.log(`\n  ${kind} (${inTier.length})`)
		for (const v of inTier) console.log(`    ${v.where}\n        ${v.detail}`)
	}
}

if (structural.length) {
	console.log(`\ncoverage gate FAILED: ${structural.length} structural violation(s)`)
	if (strict) process.exit(1)
} else {
	console.log(`\ncoverage gate: structurally clean${content.length ? ` (${content.length} content violation(s) outstanding — see above)` : ""}`)
}

if (content.length && strictContent) process.exit(1)
