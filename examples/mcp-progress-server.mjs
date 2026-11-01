#!/usr/bin/env node
// Shared fixture for the MCP idle-timeout contract (SPEC.md §2, change add-mcp-progress-timeout).
//
// A dependency-free stdio MCP server (newline-delimited JSON-RPC) exposing one tool, `slow`:
//   args: { totalMs: number, intervalMs: number, wrongToken?: boolean }
// It sleeps `totalMs`, sending `notifications/progress` every `intervalMs` (0 = never) for the
// call's progressToken (or for a token that matches nothing when wrongToken is true), then returns
// text JSON: {"progressTokenReceived":bool,"notificationsSent":n}.
// It also offers one text resource, fixture://hello ("hello resource"), for the
// MCP resource tools (change add-go-host-asks).
// Plain `node examples/mcp-progress-server.mjs` — no SDK, no network.
import { createInterface } from "node:readline";

const send = (msg) => process.stdout.write(JSON.stringify(msg) + "\n");

const rl = createInterface({ input: process.stdin });
rl.on("line", (line) => {
  let req;
  try {
    req = JSON.parse(line);
  } catch {
    return;
  }
  if (req.id === undefined) return; // notifications (initialized, cancelled) need no answer
  switch (req.method) {
    case "initialize":
      send({
        jsonrpc: "2.0",
        id: req.id,
        result: {
          protocolVersion: req.params?.protocolVersion ?? "2025-03-26",
          capabilities: { tools: {}, resources: {} },
          serverInfo: { name: "progress-fixture", version: "1.0.0" },
        },
      });
      break;
    case "tools/list":
      send({
        jsonrpc: "2.0",
        id: req.id,
        result: {
          tools: [
            {
              name: "slow",
              description: "Sleeps totalMs, reporting progress every intervalMs.",
              inputSchema: {
                type: "object",
                properties: {
                  totalMs: { type: "number" },
                  intervalMs: { type: "number" },
                  wrongToken: { type: "boolean" },
                },
                required: ["totalMs", "intervalMs"],
              },
            },
          ],
        },
      });
      break;
    case "tools/call": {
      const args = req.params?.arguments ?? {};
      const token = req.params?._meta?.progressToken;
      const totalMs = Number(args.totalMs ?? 0);
      const intervalMs = Number(args.intervalMs ?? 0);
      let sent = 0;
      let timer;
      if (intervalMs > 0 && token !== undefined) {
        const reportAs = args.wrongToken ? `not-${token}` : token;
        timer = setInterval(() => {
          sent++;
          send({
            jsonrpc: "2.0",
            method: "notifications/progress",
            params: { progressToken: reportAs, progress: sent },
          });
        }, intervalMs);
      }
      setTimeout(() => {
        if (timer) clearInterval(timer);
        send({
          jsonrpc: "2.0",
          id: req.id,
          result: {
            content: [
              {
                type: "text",
                text: JSON.stringify({ progressTokenReceived: token !== undefined, notificationsSent: sent }),
              },
            ],
          },
        });
      }, totalMs);
      break;
    }
    case "resources/list":
      send({
        jsonrpc: "2.0",
        id: req.id,
        result: { resources: [{ uri: "fixture://hello", name: "hello", mimeType: "text/plain", description: "A fixed text resource." }] },
      });
      break;
    case "resources/read":
      if (req.params?.uri !== "fixture://hello") {
        send({ jsonrpc: "2.0", id: req.id, error: { code: -32002, message: `resource not found: ${req.params?.uri}` } });
        break;
      }
      send({
        jsonrpc: "2.0",
        id: req.id,
        result: { contents: [{ uri: "fixture://hello", mimeType: "text/plain", text: "hello resource" }] },
      });
      break;
    default:
      send({ jsonrpc: "2.0", id: req.id, error: { code: -32601, message: `method not found: ${req.method}` } });
  }
});
