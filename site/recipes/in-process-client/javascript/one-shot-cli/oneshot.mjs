import { spawnSync } from "node:child_process"
import { createInProcessClient } from "toolnexus"

// Render the whole request as one prompt: tools, transcript, and the reply contract.
function render({ messages, tools = [] }) {
  const lines = ["You are the model inside a tool-calling loop. Tools you may call:"]
  for (const t of tools) lines.push(`- ${t.function.name}: ${t.function.description} args=${JSON.stringify(t.function.parameters)}`)
  lines.push("", "Conversation:")
  const called = {} // tool_call_id -> tool name, so results read "[tool add] 5"
  for (const m of messages) {
    if (m.role === "tool") lines.push(`[tool ${called[m.tool_call_id] ?? m.tool_call_id}] ${m.content}`)
    else if (m.tool_calls) {
      for (const c of m.tool_calls) called[c.id] = c.function.name
      lines.push(`[assistant called] ${JSON.stringify(m.tool_calls.map((c) => c.function))}`)
    }
    else lines.push(`[${m.role}] ${typeof m.content === "string" ? m.content : JSON.stringify(m.content)}`)
  }
  lines.push("", 'Reply with ONLY one JSON object: {"tool_calls":[{"name":"...","arguments":{...}}]} to call tools,',
    'or {"content":"..."} with the final answer. No prose, no code fences.')
  return lines.join("\n")
}

// Pull the first {...} out of the reply, tolerating fences or chatter around it.
function parse(out) {
  const json = out.slice(out.indexOf("{"), out.lastIndexOf("}") + 1)
  try {
    const r = JSON.parse(json)
    if (Array.isArray(r.tool_calls) && r.tool_calls.length) return { toolCalls: r.tool_calls.map((c) => ({ name: c.name, arguments: c.arguments ?? {} })) }
    if (typeof r.content === "string") return { content: r.content }
  } catch {}
  return { content: out.trim() } // not our contract: treat it as the answer
}

// One process per turn. The prompt goes in on stdin; the reply comes back on stdout.
export function oneShotClient({ command, args = [], model = command, timeoutMs = 300_000 }) {
  return createInProcessClient({
    model,
    generate: (req) => {
      const r = spawnSync(command, args, { input: render(req), encoding: "utf8", timeout: timeoutMs, maxBuffer: 64 << 20 })
      if (r.status !== 0) throw new Error(`${command} exited ${r.status}: ${r.stderr || r.error}`)
      return parse(r.stdout)
    },
  })
}
