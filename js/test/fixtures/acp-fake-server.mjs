#!/usr/bin/env node
// A minimal, scripted ACP (Agent Client Protocol) server for js/test/acp.test.ts.
// Built-ins only (node:readline over stdin/stdout) — no framework, no deps.
//
// Speaks JSON-RPC 2.0, one object per line, over its own stdin/stdout, the
// same shape a real ACP agent (devin acp, Gemini CLI, Zed's agents) uses.
// It is NOT a general ACP implementation — it is a deterministic script,
// selected with the ACP_SCENARIO env var, mirroring spikes/acp/fakeagent's
// scenario set by DESIGN (not by copying its Go code):
//
//   default    - trivial echo; used for the warm-session-reuse test. Replies
//                with "echo:<turnNumber>:<promptText>".
//   stale      - stateful session; answers based on the SUPERSEDES-ALL-PRIOR
//                marker when present, else a naive substring match against
//                remembered history (the failure the marker exists to avoid).
//   noisy      - emits agent_thought_chunk + tool-call narration interleaved
//                with a JSON payload split across two agent_message_chunks.
//   permission - sends session/request_permission (reject option first, then
//                an allow option) and waits for the reply before finishing
//                the turn, echoing back which optionId it received.
//   hang       - sends session/request_permission and then never answers the
//                session/prompt itself (models an unresponsive agent, so the
//                CLIENT's own safety-net timeout is what has to save it).
//   serialize  - tracks a `busy` flag; if a second session/prompt arrives
//                while busy, replies with an error instead of processing it
//                normally, so a client that fails to serialise turns is
//                caught red-handed.
//
// If ACP_RECORD_FILE is set, every session/new call appends its raw params
// as one JSON line to that file — used to assert the real client sends an
// absolute cwd + an mcpServers array (the -32602 trap real `devin acp`
// enforces).
import readline from "node:readline"
import fs from "node:fs"

const scenario = process.env.ACP_SCENARIO || "default"
const recordFile = process.env.ACP_RECORD_FILE

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + "\n")
}

function sendNotification(method, params) {
  send({ jsonrpc: "2.0", method, params })
}

let nextServerReqId = 0
function sendRequest(method, params) {
  const id = `srv-${++nextServerReqId}`
  send({ jsonrpc: "2.0", id, method, params })
  return id
}

function reply(id, result) {
  send({ jsonrpc: "2.0", id, result })
}

function replyError(id, code, message) {
  send({ jsonrpc: "2.0", id, error: { code, message } })
}

const history = []
let promptN = 0
let busy = false
const permissionReplies = new Map() // id -> resolve()
const pendingPermissionResolvers = []

const rl = readline.createInterface({ input: process.stdin, terminal: false })

rl.on("line", (line) => {
  const trimmed = line.trim()
  if (!trimmed) return
  let msg
  try {
    msg = JSON.parse(trimmed)
  } catch {
    return
  }

  // A reply to a server-initiated request (session/request_permission)
  // arrives with an id we generated and no method.
  if (msg.method === undefined && msg.id !== undefined) {
    const resolver = permissionReplies.get(msg.id)
    if (resolver) {
      permissionReplies.delete(msg.id)
      resolver(msg)
    }
    return
  }

  switch (msg.method) {
    case "initialize":
      reply(msg.id, { protocolVersion: 1, agentCapabilities: { loadSession: false } })
      break

    case "session/new":
      if (recordFile) fs.appendFileSync(recordFile, JSON.stringify(msg.params ?? {}) + "\n")
      reply(msg.id, { sessionId: "sess-1" })
      break

    case "session/set_mode":
      reply(msg.id, {})
      break

    case "session/prompt": {
      const parts = (msg.params?.prompt ?? []).map((p) => p.text ?? "")
      const userText = parts.join("")
      promptN += 1
      history.push(userText)
      handlePrompt(msg.id, msg.params?.sessionId, userText)
      break
    }

    case "session/cancel":
      reply(msg.id, {})
      break

    default:
      if (msg.id !== undefined) reply(msg.id, {})
  }
})

function handlePrompt(id, sessionId, userText) {
  switch (scenario) {
    case "stale":
      handleStale(id, userText)
      return
    case "noisy":
      handleNoisy(id, sessionId, userText)
      return
    case "permission":
      handlePermission(id, sessionId, userText)
      return
    case "hang":
      // Ask for permission (the auto-answering client answers it
      // instantly) but never reply to session/prompt itself — only the
      // client's own safety-net timeout can end this turn.
      sendRequest("session/request_permission", {
        sessionId,
        options: [{ optionId: "allow-once", kind: "allow_once", name: "Allow" }],
      })
      return
    case "serialize":
      handleSerialize(id, sessionId, userText)
      return
    default:
      sendNotification("session/update", {
        sessionId,
        update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: `echo:${promptN}:${userText}` } },
      })
      reply(id, { stopReason: "end_turn" })
  }
}

const MARKER = "SUPERSEDES-ALL-PRIOR:"

function handleStale(id, userText) {
  let answer
  const idx = userText.indexOf(MARKER)
  if (idx >= 0) {
    const fresh = userText.slice(idx + MARKER.length).trim()
    answer = `FRESH-ANSWER-TO:${fresh}`
  } else {
    let matched = history[0]
    for (const h of history) {
      if (userText.includes(h)) {
        matched = h
        break
      }
    }
    answer = `STALE-ANSWER-TO:${matched}`
  }
  sendNotification("session/update", {
    sessionId: "sess-1",
    update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: answer } },
  })
  reply(id, { stopReason: "end_turn" })
}

function handleNoisy(id, sessionId, userText) {
  const chunks = [
    { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "Let me think about this... " } },
    { sessionUpdate: "tool_call", toolCallId: "t1", title: "reading files", status: "in_progress" },
    { sessionUpdate: "agent_message_chunk", content: { type: "text", text: '{"answer":' } },
    { sessionUpdate: "tool_call_update", toolCallId: "t1", status: "completed" },
    { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "now double-checking... " } },
    { sessionUpdate: "agent_message_chunk", content: { type: "text", text: `${JSON.stringify(userText)}}` } },
  ]
  for (const c of chunks) sendNotification("session/update", { sessionId, update: c })
  reply(id, { stopReason: "end_turn" })
}

function handlePermission(id, sessionId, userText) {
  const reqId = sendRequest("session/request_permission", {
    sessionId,
    options: [
      { optionId: "reject", kind: "reject_once", name: "Reject" },
      { optionId: "allow-once", kind: "allow_once", name: "Allow" },
    ],
  })
  permissionReplies.set(reqId, (m) => {
    const optionId = m.result?.outcome?.optionId ?? "none"
    sendNotification("session/update", {
      sessionId,
      update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: `PERMITTED:${optionId}` } },
    })
    reply(id, { stopReason: "end_turn" })
  })
}

function handleSerialize(id, sessionId, userText) {
  if (busy) {
    replyError(id, -32001, "reentrant session/prompt received while a prior turn was still in flight")
    return
  }
  busy = true
  setTimeout(() => {
    sendNotification("session/update", {
      sessionId,
      update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: `done:${userText}` } },
    })
    reply(id, { stopReason: "end_turn" })
    busy = false
  }, 50)
}
