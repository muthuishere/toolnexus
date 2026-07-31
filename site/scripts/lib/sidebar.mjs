// Build the API Reference sidebar from site/src/data/api-surface.json.
// Imported by astro.config.mjs so the nav can never drift from the manifest:
// add an entry point there and it appears in every port's nav automatically.

import { manifest, LANGS, SPEC_GROUPS, slugFor, labelFor } from "./surface.mjs"

/** One port's nav: SPEC sections, each holding the entry points in that section. */
function portTree(lang) {
	const sections = []
	for (const section of SPEC_GROUPS) {
		const entries = manifest.entries.filter((e) => section.groups.includes(e.group))
		if (!entries.length) continue
		sections.push({
			label: `${section.label} (${section.spec})`,
			collapsed: true,
			items: entries.map((entry) => {
				const item = { label: labelFor(lang, entry), slug: slugFor(lang, entry) }
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

export function apiReferenceSidebar() {
	return {
		label: "API Reference",
		items: [
			{ label: "All languages — the surface", slug: "api" },
			...manifest.langs.map((lang) => ({
				label: LANGS[lang].label,
				collapsed: true,
				items: portTree(lang),
			})),
		],
	}
}
