#!/usr/bin/env node
// Rust test fixture for the MCP elicitation bridge (SPEC §2 → §10). A
// dependency-free stdio MCP server (newline-delimited JSON-RPC) with two tools:
//   ask_name  — sends a form-mode `elicitation/create` back to the client
//   login     — sends a url-mode `elicitation/create`
// Each returns text JSON {advertised, action, content} — `advertised` is whether
// the client declared the `elicitation` capability at initialize. A client that
// did not advertise it is never elicited (the tool returns action "skipped").
import { createInterface } from "node:readline";

const send = (msg) => process.stdout.write(JSON.stringify(msg) + "\n");
let advertised = false;
let nextId = 1000;
const waiting = new Map();

const rl = createInterface({ input: process.stdin });
rl.on("line", (line) => {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return;
  }
  if (msg.method === undefined && waiting.has(msg.id)) {
    const done = waiting.get(msg.id);
    waiting.delete(msg.id);
    done(msg.result ?? { action: "error", error: msg.error });
    return;
  }
  if (msg.id === undefined) return;
  switch (msg.method) {
    case "initialize":
      advertised = !!msg.params?.capabilities?.elicitation;
      send({
        jsonrpc: "2.0",
        id: msg.id,
        result: {
          protocolVersion: msg.params?.protocolVersion ?? "2025-06-18",
          capabilities: { tools: {} },
          serverInfo: { name: "elicit-fixture", version: "1.0.0" },
        },
      });
      break;
    case "tools/list":
      send({
        jsonrpc: "2.0",
        id: msg.id,
        result: {
          tools: [
            { name: "ask_name", description: "asks the user for a name", inputSchema: { type: "object", properties: {} } },
            { name: "login", description: "asks the user to log in", inputSchema: { type: "object", properties: {} } },
          ],
        },
      });
      break;
    case "tools/call": {
      const reply = (body) =>
        send({ jsonrpc: "2.0", id: msg.id, result: { content: [{ type: "text", text: JSON.stringify(body) }] } });
      if (!advertised) {
        reply({ advertised, action: "skipped" });
        break;
      }
      const id = nextId++;
      const params =
        msg.params.name === "login"
          ? { mode: "url", message: "Log in", url: "https://x/auth", elicitationId: "e1" }
          : {
              mode: "form",
              message: "Your name?",
              requestedSchema: { type: "object", properties: { name: { type: "string" } }, required: ["name"] },
            };
      waiting.set(id, (result) => reply({ advertised, action: result.action, content: result.content ?? null }));
      send({ jsonrpc: "2.0", id, method: "elicitation/create", params });
      break;
    }
    default:
      send({ jsonrpc: "2.0", id: msg.id, error: { code: -32601, message: "method not found" } });
  }
});
