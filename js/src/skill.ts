/**
 * Dynamic agent-skill source. Mirrors opencode's skill/index.ts + tool/skill.ts:
 * discover **\/SKILL.md, parse frontmatter, and expose ONE `skill` tool that
 * loads a skill's instructions + sampled resources on demand (progressive
 * disclosure).
 *
 * Beyond on-disk discovery the source also accepts skills supplied as data
 * (`SkillDef`) — SPEC.md §3. Directory-sourced skills keep the exact `file://`
 * base + on-disk sibling sampling (byte-identical); data-sourced skills use a
 * logical `skill://name/` base + a supplied resource list and never touch disk.
 */
import { readFileSync, readdirSync, realpathSync, statSync } from "node:fs"
import path from "node:path"
import { pathToFileURL } from "node:url"
import { parse as parseYaml } from "yaml"
import { compareCodePoints, compareDiscovery, sortEntriesByName } from "./order.js"
import type { Tool, ToolResult } from "./types.js"

export const SKILL_TOOL_DESCRIPTION = `Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

Use this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.

The skill name must match one of the skills listed in your system prompt.`

/**
 * Instruction preamble prepended to skillsPrompt() when ≥1 described skill
 * exists. Byte-identical across all four ports — do not reword. See SPEC.md §3.
 */
export const SKILLS_PROMPT_PREAMBLE =
  "Skills provide specialized instructions and workflows for specific tasks.\n" +
  "Use the skill tool to load a skill when a task matches its description."

export interface SkillInfo {
  name: string
  description?: string
  location: string // absolute path to SKILL.md (fs) or logical base (data)
  content: string // body after frontmatter
  /** Internal origin discriminator; "fs" ⇒ on-disk (default), "logical" ⇒ data. */
  origin?: "fs" | "logical"
  /** Logical resource list (data-sourced skills only). */
  resources?: string[]
  /** Logical base, e.g. skill://name/ (data-sourced skills only). */
  base?: string
}

/** A skill supplied directly as data, bypassing the filesystem (SPEC.md §3, S1). */
export interface SkillDef {
  name: string
  description?: string
  content: string
  /** Optional logical resource names surfaced in the invoke-time <skill_files> block. */
  resources?: string[]
  /** Optional logical base; defaults to `skill://<name>/`. */
  base?: string
}

/** Why a candidate SKILL.md did not become a skill (SPEC.md §3, S3). */
export type SkillSkipReason = "missing-name" | "malformed-frontmatter" | "duplicate-name" | "unreadable"

export interface SkillSkip {
  location: string
  reason: SkillSkipReason
  /**
   * The NATIVE parser's own message ("Tabs are not allowed as indentation at line 3"), which is
   * one keystroke from fixed and used to be discarded. Kept as a SEPARATE field so `reason`
   * stays byte-identical across ports while this stays native — never compare it for parity.
   */
  detail?: string
}

/** Result of a list-only validate pass (SPEC.md §3, S3). */
export interface SkillInventory {
  skills: SkillInfo[]
  skipped: SkillSkip[]
}

/** Options for loadSkills / listSkills (SPEC.md §3, S1/S2/S5). */
export interface LoadSkillsOptions {
  dirs?: string | string[]
  skills?: SkillDef[]
  /** Per-agent allowlist keyed on skill name; same semantics as the MCP tools filter. */
  filter?: Record<string, boolean>
  /** Sibling-file sample cap: 0 ⇒ default 10, n>0 ⇒ cap, -1 ⇒ omit <skill_files>. */
  sampleLimit?: number
}

/**
 * Parse YAML frontmatter (between the leading `---` fences) with a real YAML parser, so folded
 * (`>`)/literal (`|`) block scalars, quoting and multi-line values all resolve correctly.
 * Scalar values are coerced to strings.
 *
 * LENIENT FALLBACK (ADR 0028, issue #93) — **YAML FIRST**. A line-wise `key: rest-of-line` read
 * of `name`/`description` runs ONLY on frontmatter a real YAML parser has ALREADY refused.
 * The order is the whole point: line-wise FIRST misparses legitimate YAML (`description: |`
 * becomes `"|"`, a continued plain scalar truncates to its first line), and running it only
 * after a refusal means it can never see a file whose YAML semantics matter — by construction
 * those files have none left to preserve. No file that parses today changes value.
 *
 * `malformed` is true only when fences are present, YAML refused, AND the rescue found no name.
 */
const LENIENT_KEYS = ["name", "description"] as const
/** A value opening one of these is a construct the rescue does not implement — refused, never
 *  guessed, so a half-broken block scalar degrades to "no description", not to `"|"`. */
const LENIENT_REFUSED_OPENERS = new Set(["|", ">", "&", "*", "[", "{", "!"])
/** Column 0, ordinary key characters, one `:`; the value is the rest of the line. */
const LENIENT_LINE = /^([A-Za-z0-9_][A-Za-z0-9_.-]*):[ \t]*(.*)$/

function unquoteScalar(v: string): string {
  const t = v.trim()
  if (t.length > 1 && (t[0] === '"' || t[0] === "'") && t[t.length - 1] === t[0]) return t.slice(1, -1)
  return t
}

/** Rescue `name`/`description` from a frontmatter block YAML refused. First-wins, column 0. */
function lineWiseFrontmatter(block: string): Record<string, string> {
  const data: Record<string, string> = {}
  for (const raw of block.split(/\r?\n/)) {
    const head = raw.slice(0, 1)
    // indented continuation, comment, or blank — not a top-level key
    if (head === " " || head === "\t" || head === "#" || head === "") continue
    const m = LENIENT_LINE.exec(raw)
    if (!m) continue
    const key = m[1]
    const value = m[2].trim()
    if (!(LENIENT_KEYS as readonly string[]).includes(key) || key in data) continue
    if (!value || LENIENT_REFUSED_OPENERS.has(value[0])) continue
    data[key] = unquoteScalar(value)
  }
  return data
}

function parseFrontmatter(text: string): { data: Record<string, string>; content: string; malformed: boolean; detail?: string } {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text)
  if (!match) return { data: {}, content: text, malformed: false }
  const data: Record<string, string> = {}
  let parsed: unknown
  let detail: string | undefined
  try {
    parsed = parseYaml(match[1])
  } catch (e) {
    parsed = null
    detail = e instanceof Error ? e.message : String(e)
  }
  if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      // Trim so block-scalar trailing newlines (chomping differs subtly between
      // YAML libs) don't leak — keeps the ports byte-identical.
      if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
        data[key] = String(value).trim()
      }
    }
    return { data, content: match[2], malformed: false }
  }
  // YAML refused (threw, or produced something that is not a mapping): rescue the two scalars.
  const rescued = lineWiseFrontmatter(match[1])
  return {
    data: rescued,
    content: match[2],
    malformed: !rescued.name,
    detail: detail ?? (rescued.name ? undefined : "frontmatter is not a YAML mapping"),
  }
}

function walkSkillFiles(root: string): string[] {
  const out: string[] = []
  const stack = [root]
  // Follow symlinked directories (like opencode's `symlink: true` glob); guard
  // against symlink cycles by tracking resolved real paths already visited.
  const seen = new Set<string>()
  while (stack.length) {
    const dir = stack.pop()!
    let entries
    try {
      // EXPLICIT (A24): `readdirSync` is sorted on most platforms, but Node guarantees
      // nothing. An absent sort over a directory read is the same defect class as a wrong one,
      // and it is invisible to a grep for comparators.
      entries = sortEntriesByName(readdirSync(dir, { withFileTypes: true }))
    } catch {
      continue
    }
    // Subdirectories are pushed REVERSED so the LIFO stack pops them in name order.
    for (const entry of [...entries].reverse().filter((e) => e.isDirectory() || e.isSymbolicLink()).reverse().concat([])) void entry
    for (const entry of entries) {
      const full = path.join(dir, entry.name)
      let isDir = entry.isDirectory()
      let isFile = entry.isFile()
      if (entry.isSymbolicLink()) {
        // Resolve the link target to decide whether to descend / collect it.
        try {
          const st = statSync(full)
          isDir = st.isDirectory()
          isFile = st.isFile()
        } catch {
          continue
        }
      }
      if (isDir) {
        if (entry.name === "node_modules" || entry.name === ".git") continue
        let real
        try {
          real = realpathSync(full)
        } catch {
          continue
        }
        if (seen.has(real)) continue
        seen.add(real)
        stack.push(full)
      } else if (isFile && entry.name === "SKILL.md") {
        out.push(full)
      }
    }
  }
  return out
}

/**
 * The `<skill_files>` SAMPLE LIST: sibling resources shown to the model when a skill is loaded.
 *
 * This is SHIPPED OUTPUT, so its order is part of the cross-port contract (addendum A22). It
 * used to be raw `readdirSync` order over a LIFO `stack.pop()` walk with the cap applied MID
 * TRAVERSAL — so the filesystem decided not merely how the sample was ordered but WHICH files
 * appeared in it at all. Now: collect everything, then order by DEPTH ascending then Unicode
 * CODE POINT over the path relative to the skill dir (the same `compareDiscovery` rule as
 * discovery), and only then apply the cap. The sample is therefore a function of the tree and
 * the cap alone — never of readdir order, and stable across machines and platforms. The
 * ordering is PLAIN Unicode code point over the relative path (A25).
 */
function sampleSiblingFiles(dir: string, limit = 10): string[] {
  const found: string[] = []
  const stack = [dir]
  const seen = new Set<string>()
  while (stack.length) {
    const cur = stack.pop()!
    let entries
    try {
      entries = readdirSync(cur, { withFileTypes: true })
    } catch {
      continue
    }
    for (const entry of entries) {
      const full = path.join(cur, entry.name)
      let isDir = entry.isDirectory()
      let isFile = entry.isFile()
      if (entry.isSymbolicLink()) {
        try {
          const st = statSync(full)
          isDir = st.isDirectory()
          isFile = st.isFile()
        } catch {
          continue
        }
      }
      if (isDir) {
        if (entry.name === "node_modules" || entry.name === ".git") continue
        let real
        try {
          real = realpathSync(full)
        } catch {
          continue
        }
        if (seen.has(real)) continue
        seen.add(real)
        stack.push(full)
      } else if (isFile && entry.name !== "SKILL.md") {
        found.push(full)
      }
    }
  }
  // Order FIRST, cap SECOND: capping during the walk let readdir order pick the sample.
  // PLAIN code point over the path relative to the skill dir (A25) — NOT the discovery rule.
  // A15's depth rule exists to decide WHICH FILE a duplicated skill NAME resolves to; it has no
  // business ordering a flat listing, where it would interleave `a-root.txt, z-root.txt,
  // alpha/f.txt` instead of `a-root.txt, alpha/f.txt, z-root.txt`.
  found.sort((a, b) => compareCodePoints(path.relative(dir, a), path.relative(dir, b)))
  return found.slice(0, limit)
}

/** One raw candidate before cross-source dedupe: a parsed skill OR a typed skip. */
interface RawCandidate {
  info?: SkillInfo
  skip?: SkillSkip
}

/** Discover candidates under one on-disk root (order = discovery order). */
function candidatesFromDir(root: string): RawCandidate[] {
  const out: RawCandidate[] = []
  let isDir = false
  try {
    isDir = statSync(root).isDirectory()
  } catch {
    isDir = false
  }
  if (!isDir) {
    console.warn(`[toolnexus] skills dir not found: ${root}`)
    return out
  }
  // DISCOVERY ORDER (addendum A1/A1a/A15): dirs in the order the CALLER passed them; within a
  // dir, by DEPTH ASCENDING, then Unicode CODE-POINT comparison of the path relative to that dir.
  // A symlink sorts at its DISCOVERED path, never its target.
  //
  // Depth comes FIRST, and that is the whole rule: code point alone makes the winner depend on
  // the skill's own first letter. `docx`/`pdf`/`pptx` beat `synced/<uuid>/…` because d/p < s —
  // but `xlsx` LOSES to it, because x > s. Depth-first makes "a shallower path beats a nested
  // copy of the same name" true for EVERY name, which is what SPEC §3 already claims.
  const files = walkSkillFiles(root).sort((a, b) => compareDiscovery(path.relative(root, a), path.relative(root, b), path.sep))
  for (const file of files) {
    let text: string
    try {
      text = readFileSync(file, "utf8")
    } catch (e) {
      out.push({ skip: { location: file, reason: "unreadable", detail: e instanceof Error ? e.message : String(e) } })
      continue
    }
    const { data, content, malformed, detail } = parseFrontmatter(text)
    if (malformed) {
      out.push({ skip: { location: file, reason: "malformed-frontmatter", ...(detail ? { detail } : {}) } })
      continue
    }
    if (!data.name) {
      out.push({ skip: { location: file, reason: "missing-name" } })
      continue
    }
    out.push({ info: { name: data.name, description: data.description, location: file, content, origin: "fs" } })
  }
  return out
}

/** Turn data-supplied skill defs into candidates (logical origin). */
function candidatesFromDefs(defs: SkillDef[]): RawCandidate[] {
  return defs.map((d): RawCandidate => {
    if (!d.name) return { skip: { location: d.base ?? "skill://", reason: "missing-name" } }
    const base = d.base && d.base.length > 0 ? d.base : `skill://${d.name}/`
    return {
      info: {
        name: d.name,
        description: d.description,
        location: base,
        content: d.content ?? "",
        origin: "logical",
        resources: d.resources ?? [],
        base,
      },
    }
  })
}

/** Collect ordered candidates from dirs then data defs. */
function collectCandidates(opts: LoadSkillsOptions): RawCandidate[] {
  const roots = opts.dirs === undefined ? [] : Array.isArray(opts.dirs) ? opts.dirs : [opts.dirs]
  const cands: RawCandidate[] = []
  for (const root of roots) cands.push(...candidatesFromDir(root))
  if (opts.skills) cands.push(...candidatesFromDefs(opts.skills))
  return cands
}

/** Dedupe candidates by name (first-wins); later duplicates become skips. */
function mergeCandidates(cands: RawCandidate[]): { skills: Record<string, SkillInfo>; skipped: SkillSkip[] } {
  const skills: Record<string, SkillInfo> = {}
  const skipped: SkillSkip[] = []
  for (const c of cands) {
    if (c.skip) {
      skipped.push(c.skip)
      continue
    }
    const info = c.info!
    if (skills[info.name]) {
      console.warn(`[toolnexus] duplicate skill name "${info.name}" (${info.location}) — keeping first`)
      skipped.push({ location: info.location, reason: "duplicate-name" })
      continue
    }
    skills[info.name] = info
  }
  return { skills, skipped }
}

/**
 * Apply the per-agent skill allowlist (S2), semantics identical to the MCP
 * per-server tools filter and builtins: nil/empty ⇒ all; ≥1 true ⇒ allowlist
 * (only true-mapped names); only-false ⇒ drop-list over all-on; unknown names
 * are ignored and warned once.
 */
function applySkillsFilter(
  skills: Record<string, SkillInfo>,
  filter: Record<string, boolean> | undefined,
): Record<string, SkillInfo> {
  if (!filter) return skills
  const keys = Object.keys(filter)
  if (keys.length === 0) return skills
  const hasTrue = keys.some((k) => filter[k] === true)
  for (const k of keys) {
    if (!(k in skills)) console.warn(`[toolnexus] skill filter name "${k}" matched no skill`)
  }
  const out: Record<string, SkillInfo> = {}
  for (const [name, info] of Object.entries(skills)) {
    if (hasTrue ? filter[name] === true : filter[name] !== false) out[name] = info
  }
  return out
}

export interface SkillSource {
  skills: Record<string, SkillInfo>
  tool: Tool
  /** Markdown catalog for the system prompt (mirrors opencode Skill.fmt). */
  prompt(): string
  /**
   * Candidate SKILL.md files that did NOT become skills, with the typed reason and the native
   * parser `detail` (ADR 0028). Previously only `listSkills` reported these, so the path every
   * README and example uses — `loadSkills` — could load 69 of 75 and say nothing. An omission
   * that stops being reported is indistinguishable from something that was finished.
   */
  skipped: SkillSkip[]
}

/**
 * Discover + validate skills from the same sources loadSkills accepts, returning
 * parsed skills plus typed skip reasons — no toolkit wired (SPEC.md §3, S3). The
 * inventory is UNFILTERED (it exists to author/validate the allowlist).
 */
export function listSkills(input: string | string[] | LoadSkillsOptions): SkillInventory {
  const opts: LoadSkillsOptions =
    typeof input === "string" || Array.isArray(input) ? { dirs: input } : input
  const merged = mergeCandidates(collectCandidates(opts))
  return { skills: Object.values(merged.skills), skipped: merged.skipped }
}

/** Discover skills (dirs and/or data) and build the `skill` loader tool. */
export function loadSkills(input: string | string[] | LoadSkillsOptions): SkillSource {
  const opts: LoadSkillsOptions =
    typeof input === "string" || Array.isArray(input) ? { dirs: input } : input
  const sampleLimit = opts.sampleLimit ?? 0
  const merged = mergeCandidates(collectCandidates(opts))
  const skills = applySkillsFilter(merged.skills, opts.filter)

  const skipped = merged.skipped
  const tool: Tool = {
    name: "skill",
    description: SKILL_TOOL_DESCRIPTION,
    inputSchema: {
      type: "object",
      properties: { name: { type: "string", description: "The name of the skill to load" } },
      required: ["name"],
      additionalProperties: false,
    },
    source: "skill",
    async execute(args: Record<string, unknown>): Promise<ToolResult> {
      const name = String(args?.name ?? "")
      const info = skills[name]
      if (!info) {
        return {
          output: `Skill "${name}" not found. Available skills: ${Object.keys(skills).sort(compareCodePoints).join(", ") || "none"}`,
          isError: true,
        }
      }
      // effLimit: 0 ⇒ default 10 (byte-identical to today), n>0 ⇒ cap, -1 ⇒ omit.
      const effLimit = sampleLimit === 0 ? 10 : sampleLimit
      let base: string
      let files: string[]
      let metaDir: string
      // Emit the <skill_files> block: always for fs skills (byte-identical, even
      // when empty), only when a data skill actually carries resources (an
      // instruction-only data skill omits it). Never when sampling is disabled.
      let emitFiles = effLimit !== -1
      if (info.origin === "logical") {
        base = info.base ?? `skill://${info.name}/`
        const res = info.resources ?? []
        if (res.length === 0) emitFiles = false
        files = effLimit > 0 ? res.slice(0, effLimit) : res
        metaDir = base
      } else {
        const dir = path.dirname(info.location)
        base = pathToFileURL(dir).href
        files = effLimit === -1 ? [] : sampleSiblingFiles(dir, effLimit)
        metaDir = dir
      }
      const lines = [
        `<skill_content name="${info.name}">`,
        `# Skill: ${info.name}`,
        "",
        info.content.trim(),
        "",
        `Base directory for this skill: ${base}`,
        "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
      ]
      if (emitFiles) {
        lines.push(
          "Note: file list is sampled.",
          "",
          "<skill_files>",
          files.map((f) => `<file>${f}</file>`).join("\n"),
          "</skill_files>",
        )
      }
      lines.push("</skill_content>")
      return { output: lines.join("\n"), isError: false, metadata: { name: info.name, dir: metaDir } }
    },
  }

  return {
    skills,
    tool,
    skipped,
    prompt() {
      const described = Object.values(skills)
        .filter((s) => s.description !== undefined)
        // Unicode CODE POINT over the skill name, never `localeCompare` (addendum A22): a
        // locale collation orders by the machine's ICU data, so the same build emitted a
        // DIFFERENTLY ORDERED prompt on two machines. SPEC §0.10 pins this prompt as
        // byte-identical across ports; it was not even stable within js.
        .sort((a, b) => compareCodePoints(a.name, b.name))
      if (described.length === 0) return "No skills are currently available."
      return [
        SKILLS_PROMPT_PREAMBLE,
        "",
        "## Available Skills",
        ...described.map((s) => `- **${s.name}**: ${s.description}`),
      ].join("\n")
    },
  }
}
