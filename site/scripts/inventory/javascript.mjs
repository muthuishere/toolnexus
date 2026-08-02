#!/usr/bin/env node
// Emit the JavaScript port's public API inventory by reflecting over the built
// type declarations. Reflection (not regex) so the inventory cannot drift from
// what `toolnexus` actually exports.
//
//   node site/scripts/inventory/javascript.mjs > site/src/data/api/javascript.json
//
// Requires `cd js && npm install && npm run build` first (reads js/dist/index.d.ts).

import { createRequire } from "node:module"
import { fileURLToPath } from "node:url"
import path from "node:path"
import fs from "node:fs"

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, "../../..")
const jsRoot = path.join(repoRoot, "js")
const entry = path.join(jsRoot, "dist/index.d.ts")

if (!fs.existsSync(entry)) {
	console.error(`missing ${entry} — run: cd js && npm install && npm run build`)
	process.exit(1)
}

const require = createRequire(path.join(jsRoot, "package.json"))
const ts = require("typescript")

const program = ts.createProgram([entry], {
	target: ts.ScriptTarget.ES2022,
	module: ts.ModuleKind.ESNext,
	moduleResolution: ts.ModuleResolutionKind.Bundler,
	declaration: true,
	skipLibCheck: true,
})
const checker = program.getTypeChecker()
const sourceFile = program.getSourceFile(entry)
if (!sourceFile) {
	console.error(`typescript could not load ${entry}`)
	process.exit(1)
}

const moduleSymbol = checker.getSymbolAtLocation(sourceFile)
if (!moduleSymbol) {
	console.error("entry file is not a module")
	process.exit(1)
}

/** Where a declaration physically lives, as a repo-relative `file:line`. */
function originOf(decl) {
	const sf = decl.getSourceFile()
	const { line } = sf.getLineAndCharacterOfPosition(decl.getStart())
	// dist/*.d.ts maps 1:1 onto src/*.ts by filename; prefer the src path when present.
	const base = path.basename(sf.fileName).replace(/\.d\.ts$/, ".ts")
	const rel = path.relative(path.join(jsRoot, "dist"), sf.fileName).replace(/\.d\.ts$/, ".ts")
	const srcCandidate = path.join(jsRoot, "src", rel)
	if (fs.existsSync(srcCandidate)) return `js/src/${rel}`
	return `js/dist/${path.relative(path.join(jsRoot, "dist"), sf.fileName)}#${base}:${line + 1}`
}

/** The `src/*.ts` module a declaration came from, e.g. "toolkit", "agents/agent". */
function moduleOf(decl) {
	const sf = decl.getSourceFile()
	const rel = path.relative(path.join(jsRoot, "dist"), sf.fileName).replace(/\.d\.ts$/, "")
	return rel
}

function sigOf(sym, decl) {
	const type = checker.getTypeOfSymbolAtLocation(sym, decl)
	const calls = type.getCallSignatures()
	if (calls.length > 0) {
		return `${sym.getName()}${checker.signatureToString(calls[0], decl, ts.TypeFormatFlags.NoTruncation)}`
	}
	return `${sym.getName()}: ${checker.typeToString(type, decl, ts.TypeFormatFlags.NoTruncation)}`
}

function docOf(sym) {
	const parts = sym.getDocumentationComment(checker)
	return ts.displayPartsToString(parts).trim() || null
}

function kindOf(sym) {
	const f = sym.getFlags()
	if (f & ts.SymbolFlags.Class) return "class"
	if (f & ts.SymbolFlags.Interface) return "interface"
	if (f & ts.SymbolFlags.TypeAlias) return "type"
	if (f & ts.SymbolFlags.Enum) return "enum"
	if (f & ts.SymbolFlags.Function) return "function"
	if (f & ts.SymbolFlags.Namespace || f & ts.SymbolFlags.ValueModule) return "namespace"
	if (f & ts.SymbolFlags.Variable) {
		const decl = sym.getDeclarations()?.[0]
		if (decl) {
			const t = checker.getTypeOfSymbolAtLocation(sym, decl)
			if (t.getCallSignatures().length > 0) return "function"
		}
		return "const"
	}
	return "unknown"
}

/** Public instance + static members of a class, excluding the constructor and privates. */
function membersOf(sym) {
	const decl = sym.getDeclarations()?.find((d) => ts.isClassDeclaration(d))
	if (!decl) return []
	const out = []
	for (const m of decl.members) {
		if (ts.isConstructorDeclaration(m)) continue
		if (!m.name) continue
		const mods = ts.getCombinedModifierFlags(m)
		if (mods & ts.ModifierFlags.Private) continue
		if (mods & ts.ModifierFlags.Protected) continue
		const name = m.name.getText()
		if (name.startsWith("#") || name.startsWith("_")) continue
		const msym = checker.getSymbolAtLocation(m.name)
		if (!msym) continue
		const isStatic = Boolean(mods & ts.ModifierFlags.Static)
		let mkind = "property"
		if (ts.isMethodDeclaration(m) || ts.isMethodSignature(m)) mkind = "method"
		else if (ts.isGetAccessor(m) || ts.isSetAccessor(m)) mkind = "accessor"
		out.push({
			name,
			kind: mkind,
			static: isStatic,
			signature: sigOf(msym, m),
			doc: docOf(msym),
			origin: originOf(m),
		})
	}
	return out
}

function resolve(sym) {
	return sym.getFlags() & ts.SymbolFlags.Alias ? checker.getAliasedSymbol(sym) : sym
}

const symbols = []
for (const exported of checker.getExportsOfModule(moduleSymbol)) {
	const sym = resolve(exported)
	const decl = sym.getDeclarations()?.[0]
	if (!decl) continue
	const kind = kindOf(sym)
	const record = {
		name: exported.getName(),
		kind,
		module: moduleOf(decl),
		signature: kind === "class" || kind === "interface" || kind === "type" ? null : sigOf(sym, decl),
		doc: docOf(sym),
		origin: originOf(decl),
	}
	if (kind === "class") record.members = membersOf(sym)
	if (kind === "namespace") {
		record.members = checker
			.getExportsOfModule(sym)
			.map((e) => {
				const s = resolve(e)
				const d = s.getDeclarations()?.[0]
				if (!d) return null
				return {
					name: e.getName(),
					kind: kindOf(s),
					static: true,
					signature: kindOf(s) === "class" ? null : sigOf(s, d),
					doc: docOf(s),
					origin: originOf(d),
				}
			})
			.filter(Boolean)
	}
	symbols.push(record)
}

symbols.sort((a, b) => a.name.localeCompare(b.name))

process.stdout.write(
	JSON.stringify({ lang: "javascript", package: "toolnexus", registry: "npm", symbols }, null, 2) + "\n",
)
