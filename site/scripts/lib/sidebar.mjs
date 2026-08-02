// Build the API Reference sidebar from site/src/data/api-surface.json.
// Imported by astro.config.mjs so the nav can never drift from the manifest:
// add an entry point there and it appears in every port's nav automatically.

import { manifest, LANGS, SPEC_GROUPS, slugFor, labelFor } from "./surface.mjs"

/** Several ports expose distinct entry points through the SAME symbol (e.g. Elixir's
 *  `Client.create/1` covers §8 hooks/resilience via plain options, or JS's `loadMcp` covers
 *  both the plain and cancellable load — no separate function exists). Without disambiguation
 *  the sidebar would show that label twice with no way to tell the entries apart. Append the
 *  entry's own one-line description (the part of its title after the em dash) to every label
 *  that collides with another entry's label for this language. */
function disambiguatedLabels(lang) {
	const counts = new Map()
	for (const entry of manifest.entries) {
		const label = labelFor(lang, entry)
		counts.set(label, (counts.get(label) ?? 0) + 1)
	}
	const labels = new Map()
	for (const entry of manifest.entries) {
		const label = labelFor(lang, entry)
		if ((counts.get(label) ?? 0) <= 1) {
			labels.set(entry.id, label)
			continue
		}
		const detail = entry.title.includes(" — ") ? entry.title.split(" — ")[1] : entry.title
		labels.set(entry.id, `${label} — ${detail}`)
	}
	return labels
}

/** One port's nav: SPEC sections, each holding the entry points in that section. */
function portTree(lang) {
	const labels = disambiguatedLabels(lang)
	const sections = []
	for (const section of SPEC_GROUPS) {
		const entries = manifest.entries.filter((e) => section.groups.includes(e.group))
		if (!entries.length) continue
		sections.push({
			label: `${section.label} (${section.spec})`,
			collapsed: true,
			items: entries.map((entry) => {
				const item = { label: labels.get(entry.id), slug: slugFor(lang, entry) }
				// A port that does not ship this entry point still gets a page; flag it in the nav
				// so a reader can see the gap without clicking through.
				if (entry.symbols?.[lang] === null) {
					item.badge = { text: "n/a", variant: "caution" }
				}
				return item
			}),
		})
	}
	return sections
}

/** One topic per port for `starlight-sidebar-topics`: each language's API reference gets its
 *  own dedicated sidebar (switchable via the topic picker) instead of all six being nested,
 *  collapsed, under one shared "API Reference" tree. */
export function apiTopics() {
	return manifest.langs.map((lang) => ({
		label: LANGS[lang].label,
		link: `api/${LANGS[lang].slug}/`,
		items: portTree(lang),
	}))
}
