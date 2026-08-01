#!/usr/bin/env node
// Generate the API Reference page skeletons from site/src/data/api-surface.json.
//
//   node site/scripts/generate-pages.mjs          # create missing pages
//   node site/scripts/generate-pages.mjs --dry    # report what would be created
//
// NEVER overwrites an existing page: the auto-derivable parts (breadcrumb, cross-language
// table, see-also, parity note) are scaffolded once, and the parts that need a human —
// when-to-use, why-not, and three real examples — are left as TODO markers for the
// coverage gate to count. Re-running is safe and only fills gaps.

import fs from "node:fs"
import path from "node:path"
import {
	manifest,
	LANGS,
	GITHUB,
	fileFor,
	urlFor,
	slugFor,
} from "./lib/surface.mjs"

const dry = process.argv.includes("--dry")

/** MDX parses a bare `{` in body text as a JS expression — escape it for prose. Code
 *  fences and the frontmatter (YAML, not MDX) are unaffected. */
function mdxEscape(text) {
	return text.replace(/[{}]/g, (c) => `\\${c}`)
}

/** Sibling entries in the same group, for the See also block. */
function seeAlso(lang, entry) {
	const siblings = manifest.entries
		.filter((e) => e.group === entry.group && e.id !== entry.id && e.symbols?.[lang] != null)
		.slice(0, 4)
	if (!siblings.length) return "- _TODO: related entry points_"
	return siblings.map((e) => `- [\`${e.symbols[lang]}\`](${urlFor(lang, e)}) — ${e.summary}`).join("\n")
}

function specAnchor(spec) {
	return `${GITHUB}/blob/main/SPEC.md`
}

function scaffold(lang, entry) {
	const meta = LANGS[lang]
	const symbol = entry.symbols?.[lang]
	const shipped = symbol != null
	const title = shipped ? symbol : entry.title

	const head = `---
title: ${JSON.stringify(title)}
description: ${JSON.stringify(`${entry.summary} — ${meta.label}.`)}
sidebar:
  label: ${JSON.stringify(shipped ? symbol : `${entry.group} / ${entry.member}`)}
---

import { Aside } from '@astrojs/starlight/components';

<p class="api-crumb">
<strong>${meta.label}</strong> · package <code>${meta.pkg}</code> · <a href="${specAnchor(entry.spec)}">SPEC ${entry.spec}</a>
</p>
`

	if (!shipped) {
		// A declared parity gap. The page exists so the cross-language table never dead-ends,
		// and its job is to say plainly that this port does not ship it.
		return `${head}
<Aside type="caution" title="Not available in ${meta.label}">
${mdxEscape(entry.parityNote ?? `The ${meta.label} port does not ship this entry point.`)}
</Aside>

${mdxEscape(entry.summary)}

## What to use instead

{/* TODO:why */}
_TODO: the closest thing this port does offer, and how to get the same outcome._

## See also

${seeAlso(lang, entry)}
`
	}

	return `${head}
\`\`\`${meta.fence}
{/* TODO:signature */}
\`\`\`

${mdxEscape(entry.summary)}

## When to use it

{/* TODO:when */}
_TODO: the situation this entry point is for._

## Why this and not the alternative

{/* TODO:why */}
<Aside type="tip" title="TODO: when NOT to use it">
_TODO: name the alternative and when to prefer it._
</Aside>

## Examples

### 1. TODO — the smallest useful call

{/* TODO:example1 */}
\`\`\`${meta.fence}
// TODO
\`\`\`

### 2. TODO — the realistic case

{/* TODO:example2 */}
\`\`\`${meta.fence}
// TODO
\`\`\`

### 3. TODO — the full surface

{/* TODO:example3 */}
\`\`\`${meta.fence}
// TODO
\`\`\`

## See also

${seeAlso(lang, entry)}
`
}

let created = 0
let kept = 0
for (const lang of manifest.langs) {
	for (const entry of manifest.entries) {
		const file = fileFor(lang, entry)
		if (fs.existsSync(file)) {
			kept++
			continue
		}
		created++
		if (dry) {
			console.log(`would create  ${slugFor(lang, entry)}`)
			continue
		}
		fs.mkdirSync(path.dirname(file), { recursive: true })
		fs.writeFileSync(file, scaffold(lang, entry))
	}
}

console.log(`${dry ? "would create" : "created"}: ${created}`)
console.log(`left untouched (already written): ${kept}`)
console.log(`total pages: ${created + kept}`)
