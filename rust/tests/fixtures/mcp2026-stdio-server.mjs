#!/usr/bin/env node
// Rust test fixture: a dependency-free MCP 2026-07-28 stdio server
// (newline-delimited JSON-RPC, no initialize, `_meta` protocol version).
// Tools: echo, greet (MRTR input_required), slow (replies after `ms`, so
// concurrent calls come back out of order), crash (exits without replying).
// `--ignore-discover` makes it an old-style server that never answers a
// pre-`initialize` request (the client must fall back after its timeout).
import { createInterface } from "node:readline";

const V = "2026-07-28";
const send = (msg) => process.stdout.write(JSON.stringify(msg) + "\n");
const ignoreDiscover = process.argv.includes("--ignore-discover");
const execs = {};

const rl = createInterface({ input: process.stdin });
rl.on("line", (line) => {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return;
  }
  if (msg.id === undefined || msg.id === null) return;
  const { id, method } = msg;
  const params = msg.params || {};
  if (ignoreDiscover) return;
  const ok = (result) => send({ jsonrpc: "2.0", id, result });
  const fail = (code, message, data) =>
    send({ jsonrpc: "2.0", id, error: { code, message, ...(data ? { data } : {}) } });
  const mv = params._meta && params._meta["io.modelcontextprotocol/protocolVersion"];
  if (mv !== V) return fail(-32022, "Unsupported protocol version", { supported: [V], requested: mv || "" });
  process.stderr.write(`[fixture] ${method}\n`); // stderr is never parsed
  switch (method) {
    case "server/discover":
      return ok({
        supportedVersions: [V],
        capabilities: { tools: {} },
        serverInfo: { name: "stdio-fixture", version: "0.0.1" },
      });
    case "tools/list":
      return ok({
        tools: [
          { name: "echo", description: "Echo the given text back.", inputSchema: { type: "object", properties: { text: { type: "string" } }, required: ["text"] } },
          { name: "greet", description: "Greet the user; asks for their name via input_required.", inputSchema: { type: "object", properties: {} } },
          { name: "slow", description: "Reply after ms.", inputSchema: { type: "object", properties: { ms: { type: "number" }, text: { type: "string" } } } },
          { name: "crash", description: "Exit without replying.", inputSchema: { type: "object", properties: {} } },
        ],
      });
    case "tools/call": {
      const a = params.arguments || {};
      execs[params.name] = (execs[params.name] || 0) + 1;
      if (params.name === "echo") return ok({ content: [{ type: "text", text: a.text }], structuredContent: { text: a.text }, isError: false });
      if (params.name === "slow") {
        setTimeout(() => ok({ content: [{ type: "text", text: a.text }] }), a.ms || 0);
        return;
      }
      if (params.name === "crash") process.exit(3);
      if (params.name === "greet") {
        const name = params.inputResponses && params.inputResponses.name;
        if (name && name.action === "accept" && params.requestState === "greet-1") {
          return ok({ content: [{ type: "text", text: `Hello, ${name.content.name}!` }], isError: false });
        }
        return ok({
          resultType: "input_required",
          inputRequests: {
            name: { method: "elicitation/create", params: { mode: "form", message: "What is your name?", requestedSchema: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } } },
          },
          requestState: "greet-1",
        });
      }
      return fail(-32602, `Unknown tool: ${params.name}`);
    }
    default:
      return fail(-32601, "Method not found");
  }
});
