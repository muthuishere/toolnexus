/**
 * Dynamic agent-skill source. Mirrors opencode's skill/index.ts + tool/skill.ts:
 * discover **\/SKILL.md, parse frontmatter, and expose ONE `skill` tool that
 * loads a skill's instructions + sampled resources on demand (progressive
 * disclosure).
 */
import { readFileSync, readdirSync, realpathSync, statSync } from "node:fs"
import path from "node:path"
import { pathToFileURL } from "node:url"
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
  location: string // absolute path to SKILL.md
  content: string // body after frontmatter
}

/** Minimal YAML frontmatter parser — only flat `key: value` pairs are needed. */
function parseFrontmatter(text: string): { data: Record<string, string>; content: string } {
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text)
  if (!match) return { data: {}, content: text }
  const data: Record<string, string> = {}
  for (const line of match[1].split(/\r?\n/)) {
    const idx = line.indexOf(":")
    if (idx === -1) continue
    const key = line.slice(0, idx).trim()
    let value = line.slice(idx + 1).trim()
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1)
    }
    if (key) data[key] = value
  }
  return { data, content: match[2] }
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
      entries = readdirSync(dir, { withFileTypes: true })
    } catch {
      continue
    }
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

function sampleSiblingFiles(dir: string, limit = 10): string[] {
  const out: string[] = []
  const stack = [dir]
  const seen = new Set<string>()
  while (stack.length && out.length < limit) {
    const cur = stack.pop()!
    let entries
    try {
      entries = readdirSync(cur, { withFileTypes: true })
    } catch {
      continue
    }
    for (const entry of entries) {
      if (out.length >= limit) break
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
        out.push(full)
      }
    }
  }
  return out
}

export interface SkillSource {
  skills: Record<string, SkillInfo>
  tool: Tool
  /** Markdown catalog for the system prompt (mirrors opencode Skill.fmt). */
  prompt(): string
}

/** Discover skills under one or more roots and build the `skill` loader tool. */
export function loadSkills(dirs: string | string[]): SkillSource {
  const roots = Array.isArray(dirs) ? dirs : [dirs]
  const skills: Record<string, SkillInfo> = {}

  for (const root of roots) {
    let isDir = false
    try {
      isDir = statSync(root).isDirectory()
    } catch {
      isDir = false
    }
    if (!isDir) {
      console.warn(`[toolnexus] skills dir not found: ${root}`)
      continue
    }
    for (const file of walkSkillFiles(root)) {
      let text: string
      try {
        text = readFileSync(file, "utf8")
      } catch {
        continue
      }
      const { data, content } = parseFrontmatter(text)
      if (!data.name) continue
      if (skills[data.name]) {
        console.warn(`[toolnexus] duplicate skill name "${data.name}" (${file}) — keeping first`)
        continue
      }
      skills[data.name] = {
        name: data.name,
        description: data.description,
        location: file,
        content,
      }
    }
  }

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
          output: `Skill "${name}" not found. Available skills: ${Object.keys(skills).sort().join(", ") || "none"}`,
          isError: true,
        }
      }
      const dir = path.dirname(info.location)
      const base = pathToFileURL(dir).href
      const files = sampleSiblingFiles(dir)
      const output = [
        `<skill_content name="${info.name}">`,
        `# Skill: ${info.name}`,
        "",
        info.content.trim(),
        "",
        `Base directory for this skill: ${base}`,
        "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
        "Note: file list is sampled.",
        "",
        "<skill_files>",
        files.map((f) => `<file>${f}</file>`).join("\n"),
        "</skill_files>",
        "</skill_content>",
      ].join("\n")
      return { output, isError: false, metadata: { name: info.name, dir } }
    },
  }

  return {
    skills,
    tool,
    prompt() {
      const described = Object.values(skills)
        .filter((s) => s.description !== undefined)
        .sort((a, b) => a.name.localeCompare(b.name))
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
