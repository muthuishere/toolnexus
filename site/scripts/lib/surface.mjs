// Shared helpers over site/src/data/api-surface.json — the single source of truth for the
// API Reference. Imported by the page generator, the sidebar builder and the coverage gate
// so all three agree on paths, labels and language metadata.

import fs from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const here = path.dirname(fileURLToPath(import.meta.url))
export const repoRoot = path.resolve(here, "../../..")
export const docsRoot = path.join(repoRoot, "site/src/content/docs")

export const manifest = JSON.parse(
	fs.readFileSync(path.join(repoRoot, "site/src/data/api-surface.json"), "utf8"),
)

/** Per-port display metadata. `pkg` is what a reader installs; `src` roots the source links. */
export const LANGS = {
	javascript: { slug: "javascript", label: "JavaScript", pkg: "toolnexus", registry: "npm", src: "js/src", fence: "ts" },
	python: { slug: "python", label: "Python", pkg: "toolnexus", registry: "PyPI", src: "python/src/toolnexus", fence: "python" },
	golang: { slug: "go", label: "Go", pkg: "github.com/muthuishere/toolnexus/golang", registry: "Go modules", src: "golang", fence: "go" },
	java: { slug: "java", label: "Java", pkg: "io.github.muthuishere:toolnexus", registry: "Maven Central", src: "java/src/main/java/io/github/muthuishere/toolnexus", fence: "java" },
	csharp: { slug: "csharp", label: "C#", pkg: "Toolnexus", registry: "NuGet", src: "csharp/src/Toolnexus", fence: "csharp" },
	elixir: { slug: "elixir", label: "Elixir", pkg: "toolnexus", registry: "Hex", src: "elixir/lib", fence: "elixir" },
}

export const GITHUB = "https://github.com/muthuishere/toolnexus"

/** SPEC section → the anchor slug GitHub generates for that heading. */
export const SPEC_GROUPS = [
	{ spec: "§1", label: "Core types", groups: ["types"] },
	{ spec: "§2", label: "MCP servers", groups: ["mcp"] },
	{ spec: "§3", label: "Agent skills", groups: ["skills"] },
	{ spec: "§4", label: "Toolkit & adapters", groups: ["toolkit", "adapters"] },
	{ spec: "§4A", label: "Built-in tools", groups: ["builtins"] },
	{ spec: "§6", label: "Native tools", groups: ["native"] },
	{ spec: "§7", label: "HTTP tools", groups: ["http"] },
	{ spec: "§7A", label: "A2A — outbound", groups: ["agents"] },
	{ spec: "§7B", label: "Serve — inbound", groups: ["serve"] },
	{ spec: "§7D", label: "Sub-agents & runtime", groups: ["runtime"] },
	{ spec: "§7E", label: "Persona agents", groups: ["persona"] },
	{ spec: "§7F", label: "Compaction", groups: ["compaction"] },
	{ spec: "§8", label: "The client & loop", groups: ["client"] },
	{ spec: "§10", label: "Suspension", groups: ["suspension"] },
	{ spec: "§11", label: "Translation", groups: ["translate"] },
]

export const slugFor = (lang, entry) => `api/${LANGS[lang].slug}/${entry.group}/${entry.member}`
export const urlFor = (lang, entry) => `/${slugFor(lang, entry)}/`
export const fileFor = (lang, entry) => path.join(docsRoot, `${slugFor(lang, entry)}.mdx`)

/** The label a page shows in the sidebar: the port's own symbol, or the generic id. */
export function labelFor(lang, entry) {
	const sym = entry.symbols?.[lang]
	if (!sym) return `${entry.group} / ${entry.member}`
	return sym.includes(" ") ? `${entry.group} / ${entry.member}` : sym
}

/** Entries that a given port actually ships (symbol is non-null). */
export const shippedIn = (lang) => manifest.entries.filter((e) => e.symbols?.[lang] != null)

/** Every (lang, entry) pair the docs must cover — including declared parity gaps, which
 *  still get a page whose job is to say the port does not ship it. */
export function allPages() {
	const out = []
	for (const lang of manifest.langs) {
		for (const entry of manifest.entries) out.push({ lang, entry })
	}
	return out
}
