#!/usr/bin/env node
// Rust test fixture for the in-house frozen-path stdio MCP client (SPEC §2.F).
// A dependency-free, pre-2026 stdio server: it rejects `server/discover` with
// -32601 (so the probe child continues into `initialize`), lists its tools over
// TWO pages, and records what the client sends so tests can assert it.
//
// Tools:
//   echo        — text echo of `arguments`
//   image       — text + image + resource_link content
//   structured  — structuredContent + an image
//   fail        — isError with two text parts
//   progress    — `n` progress notifications `gapMs` apart, then a reply;
//                 `wrongToken` sends them with a token that is not the call's
//   silent      — never replies
//   ask         — sends elicitation/create (form) and replies with the result
//   reverse     — sends ping, roots/list, sampling/createMessage and foo/bar
//                 to the client and replies with the four answers
//   die         — exits mid-call
//   orphan      — starts a `sleep 60` grandchild (which also keeps this
//                 server alive past stdin EOF) and returns its pid
//   flood       — stops reading stdin, waits `delayMs`, sends a `ping` request,
//                 then writes `mb` MB of notifications; resumes stdin only
//                 once all of it is flushed, then replies (a reader that
//                 waits on the stdin lock to answer the ping deadlocks here)
//   log         — what the client sent: initialize params, cancelled notes
import { createInterface } from "node:readline";
import { spawn } from "node:child_process";

const send = (msg) => process.stdout.write(JSON.stringify(msg) + "\n");
const log = { initialize: null, initialized: false, cancelled: [], listParams: [] };
let nextId = 5000;
const waiting = new Map();
const ask = (method, params) =>
  new Promise((resolve) => {
    const id = nextId++;
    waiting.set(id, resolve);
    send({ jsonrpc: "2.0", id, method, ...(params ? { params } : {}) });
  });

const tool = (name, inputSchema = { type: "object", properties: {} }, extra = {}) => ({
  name,
  description: `${name} tool`,
  inputSchema,
  ...extra,
});
const PAGE1 = [
  tool("echo", { type: "object", properties: { x: { type: "string" } }, required: ["x"] }, {
    annotations: { readOnlyHint: true },
  }),
  tool("image"),
  tool("structured"),
  tool("fail"),
  tool("progress"),
];
const PAGE2 = [tool("silent"), tool("ask"), tool("reverse"), tool("die"), tool("orphan"), tool("log"), tool("flood")];

const text = (t) => ({ type: "text", text: t });
const IMG = { type: "image", mimeType: "image/png", data: "aGVsbG8=" };

async function call(msg) {
  const { name, arguments: args = {} } = msg.params;
  const token = msg.params._meta?.progressToken;
  const reply = (result) => send({ jsonrpc: "2.0", id: msg.id, result });
  switch (name) {
    case "echo":
      return reply({ content: [text(JSON.stringify(args))] });
    case "image":
      return reply({
        content: [
          text("see image"),
          IMG,
          { type: "resource_link", uri: "file:///a.txt", name: "a.txt", mimeType: "text/plain" },
        ],
      });
    case "structured":
      return reply({ content: [text("ignored"), IMG], structuredContent: { b: 1, a: [2] } });
    case "fail":
      return reply({ isError: true, content: [text("first"), text("  "), text("second")] });
    case "progress": {
      const n = args.n ?? 5;
      for (let i = 0; i < n; i++) {
        await new Promise((r) => setTimeout(r, args.gapMs ?? 100));
        send({
          jsonrpc: "2.0",
          method: "notifications/progress",
          params: { progressToken: args.wrongToken ? "not-yours" : token, progress: i + 1 },
        });
      }
      return reply({ content: [text(`done after ${n} with token ${JSON.stringify(token)}`)] });
    }
    case "silent":
      return;
    case "ask": {
      const r = await ask("elicitation/create", {
        mode: "form",
        message: "Your name?",
        requestedSchema: { type: "object", properties: { name: { type: "string" } } },
      });
      return reply({ content: [text(JSON.stringify(r))] });
    }
    case "reverse": {
      const out = [];
      for (const m of ["ping", "roots/list", "sampling/createMessage", "foo/bar"]) out.push(await ask(m));
      return reply({ content: [text(JSON.stringify(out))] });
    }
    case "die":
      process.exit(7);
    case "orphan": {
      const child = spawn("sleep", ["60"], { stdio: "ignore" });
      return reply({ content: [text(String(child.pid))] });
    }
    case "flood": {
      process.stdin.pause();
      await new Promise((r) => setTimeout(r, args.delayMs ?? 200));
      send({ jsonrpc: "2.0", id: nextId++, method: "ping" });
      const note = JSON.stringify({ jsonrpc: "2.0", method: "notifications/message", params: { level: "info", data: "x".repeat(1000) } }) + "\n";
      const chunk = note.repeat(1024);
      for (let i = 0; i < (args.mb ?? 4) - 1; i++) process.stdout.write(chunk);
      await new Promise((r) => process.stdout.write(chunk, r));
      process.stdin.resume();
      return reply({ content: [text("flooded")] });
    }
    case "log":
      return reply({ content: [text(JSON.stringify(log))] });
  }
  send({ jsonrpc: "2.0", id: msg.id, error: { code: -32602, message: `unknown tool ${name}`, data: { name } } });
}

createInterface({ input: process.stdin }).on("line", (line) => {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return;
  }
  if (msg.method === undefined && waiting.has(msg.id)) {
    const done = waiting.get(msg.id);
    waiting.delete(msg.id);
    done(msg.error ? { error: msg.error } : msg.result);
    return;
  }
  if (msg.method === "notifications/initialized") log.initialized = true;
  if (msg.method === "notifications/cancelled") log.cancelled.push(msg.params);
  if (msg.id === undefined) return;
  switch (msg.method) {
    case "initialize":
      log.initialize = msg.params;
      // log noise and an unrelated notification before the reply
      process.stdout.write("not json at all\n");
      send({ jsonrpc: "2.0", method: "notifications/message", params: { level: "info", data: "hi" } });
      return send({
        jsonrpc: "2.0",
        id: msg.id,
        result: { protocolVersion: msg.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: "legacy", version: "1" } },
      });
    case "tools/list": {
      log.listParams.push(msg.params ?? null);
      const second = msg.params?.cursor === "page-2";
      return send({
        jsonrpc: "2.0",
        id: msg.id,
        result: second ? { tools: PAGE2 } : { tools: PAGE1, nextCursor: "page-2" },
      });
    }
    case "tools/call":
      return void call(msg);
    default:
      return send({ jsonrpc: "2.0", id: msg.id, error: { code: -32601, message: "Method not found" } });
  }
});
