// Lazy tool search: with client.toolSearch the LLM sees two small tools instead of all N:
// search_tools(query) -> ranked {name, description, inputSchema, server}, and
// call_tool(name, arguments) to run one it found. Ranking is BM25 over name (x2) + description.
import type { Obj } from "./protocol.js"

/** BM25 (k1 1.2, b 0.75) over name tokens twice + description tokens; best first, ties keep list order. */
export function rank(tools: Obj[], query: string, limit: number): Obj[] {
  const docs = tools.map((t) => {
    const nt = tokens(String(t.name ?? ""))
    return [...nt, ...nt, ...tokens(String(t.description ?? ""))]
  })
  const df = new Map<string, number>()
  let total = 0
  for (const d of docs) {
    total += d.length
    for (const w of new Set(d)) df.set(w, (df.get(w) ?? 0) + 1)
  }
  const avg = total / Math.max(1, tools.length)
  const qs = tokens(query)
  const hits: { i: number; score: number }[] = []
  docs.forEach((d, i) => {
    const tf = new Map<string, number>()
    for (const w of d) tf.set(w, (tf.get(w) ?? 0) + 1)
    let s = 0
    for (const q of qs) {
      const f = tf.get(q) ?? 0
      if (f === 0) continue
      const n = df.get(q) ?? 0
      const idf = Math.log(1 + (tools.length - n + 0.5) / (n + 0.5))
      s += (idf * f * 2.2) / (f + 1.2 * (0.25 + (0.75 * d.length) / avg))
    }
    if (s > 0) hits.push({ i, score: s })
  })
  hits.sort((a, b) => b.score - a.score) // stable
  return hits.slice(0, Math.max(0, limit)).map((h) => tools[h.i])
}

/** Lowercases and splits on non-alphanumerics and camelCase boundaries. */
export function tokens(s: string): string[] {
  const out: string[] = []
  let cur = ""
  let prevLower = false
  for (const r of s) {
    if (/\p{Lu}/u.test(r) && prevLower) {
      if (cur) out.push(cur.toLowerCase())
      cur = r
    } else if (/[\p{L}\p{N}]/u.test(r)) cur += r
    else if (cur) {
      out.push(cur.toLowerCase())
      cur = ""
    }
    prevLower = /\p{Ll}/u.test(r)
  }
  if (cur) out.push(cur.toLowerCase())
  return out
}
