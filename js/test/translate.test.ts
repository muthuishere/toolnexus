/**
 * Single-turn translation tests (SPEC.md §11, ADR-0011). Ports golang/translate_test.go —
 * Go's assertions are the cross-port oracle. No network, no LLM: a local http server
 * stands in for the provider and records what it was actually sent.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import { createClient, createToolkit, defineTool } from "../dist/index.js"

/** A provider stand-in that records the request body and replies with a canned response. */
async function upstream(reply: unknown): Promise<{ url: string; sent: () => any; close: () => void }> {
  let body: any
  const srv = http.createServer((req, res) => {
    let raw = ""
    req.on("data", (c) => (raw += c))
    req.on("end", () => {
      try {
        body = JSON.parse(raw)
      } catch {
        body = {}
      }
      res.writeHead(200, { "content-type": "application/json" })
      res.end(JSON.stringify(reply))
    })
  })
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r))
  const port = (srv.address() as any).port
  return {
    url: `http://127.0.0.1:${port}`,
    sent: () => body,
    close: () => srv.close(),
  }
}

/** The OpenAI `tools` array a client sends, verbatim. */
const openAITools = () => [
  {
    type: "function",
    function: {
      name: "get_weather",
      description: "Get the weather",
      parameters: { type: "object", properties: { city: { type: "string" } }, required: ["city"] },
    },
  },
]

// ---- Anthropic upstream: the real translation ----

test("translate: an Anthropic tool_use turn comes back as an OpenAI tool call", async () => {
  const up = await upstream({
    content: [{ type: "tool_use", id: "toolu_1", name: "get_weather", input: { city: "Chennai" } }],
    stop_reason: "tool_use",
    usage: { input_tokens: 10, output_tokens: 5 },
  })
  try {
    const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
    const res = await c.translate({
      messages: [{ role: "user", content: "weather in Chennai?" }],
      tools: openAITools(),
    })
    assert.equal(res.finishReason, "tool_calls")
    assert.equal(res.toolCalls.length, 1)
    assert.equal(res.toolCalls[0].id, "toolu_1")
    assert.equal(res.toolCalls[0].name, "get_weather")
    // arguments must be a JSON STRING (the OpenAI wire shape), not an object
    assert.equal(typeof res.toolCalls[0].arguments, "string")
    assert.equal(JSON.parse(res.toolCalls[0].arguments).city, "Chennai")
    assert.ok(res.usage.totalTokens > 0, "usage not reported")
    // declared to the provider in Anthropic's native shape
    const sent = JSON.stringify(up.sent())
    assert.match(sent, /input_schema/)
    assert.match(sent, /get_weather/)
    assert.doesNotMatch(sent, /"parameters"/, "OpenAI-shaped 'parameters' leaked upstream")
  } finally {
    up.close()
  }
})

test("translate: a multi-turn tool exchange survives (the flattening bug)", async () => {
  const up = await upstream({
    content: [{ type: "text", text: "It is 31C in Chennai." }],
    stop_reason: "end_turn",
    usage: { input_tokens: 20, output_tokens: 8 },
  })
  try {
    const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
    const res = await c.translate({
      tools: openAITools(),
      messages: [
        { role: "system", content: "Be terse." },
        { role: "user", content: "weather in Chennai?" },
        {
          role: "assistant",
          content: null,
          tool_calls: [
            { id: "call_abc", type: "function", function: { name: "get_weather", arguments: '{"city":"Chennai"}' } },
          ],
        },
        { role: "tool", tool_call_id: "call_abc", content: "31C, clear" },
      ],
    })
    assert.equal(res.finishReason, "stop")
    assert.equal(res.text, "It is 31C in Chennai.")

    const sent = up.sent()
    assert.equal(sent.system, "Be terse.", "system not hoisted out of messages")
    const raw = JSON.stringify(sent)
    for (const want of ["tool_use", "tool_result", "call_abc", "31C, clear"]) {
      assert.ok(raw.includes(want), `multi-turn structure lost ${want}: ${raw}`)
    }
    // the tool_use's input is an OBJECT upstream, re-parsed from the JSON string
    const use = sent.messages
      .flatMap((m: any) => (Array.isArray(m.content) ? m.content : []))
      .find((b: any) => b.type === "tool_use")
    assert.ok(use, "no tool_use block reached the provider")
    assert.equal(use.input.city, "Chennai", "tool_use input not re-parsed to an object")
  } finally {
    up.close()
  }
})

test("translate: three consecutive tool results merge into ONE user turn", async () => {
  const up = await upstream({ content: [{ type: "text", text: "done" }], stop_reason: "end_turn" })
  try {
    const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
    await c.translate({
      messages: [
        { role: "user", content: "do three things" },
        {
          role: "assistant",
          tool_calls: [
            { id: "a", function: { name: "f", arguments: "{}" } },
            { id: "b", function: { name: "f", arguments: "{}" } },
            { id: "c", function: { name: "f", arguments: "{}" } },
          ],
        },
        { role: "tool", tool_call_id: "a", content: "ra" },
        { role: "tool", tool_call_id: "b", content: "rb" },
        { role: "tool", tool_call_id: "c", content: "rc" },
      ],
    })
    const msgs: any[] = up.sent().messages
    const resultTurns = msgs.filter(
      (m) => Array.isArray(m.content) && m.content.some((b: any) => b.type === "tool_result"),
    )
    assert.equal(resultTurns.length, 1, "tool results spread over more than one user turn")
    assert.equal(
      resultTurns[0].content.filter((b: any) => b.type === "tool_result").length,
      3,
      "merged turn does not carry all three results",
    )
    const uses = msgs
      .flatMap((m) => (Array.isArray(m.content) ? m.content : []))
      .filter((b: any) => b.type === "tool_use")
    assert.equal(uses.length, 3, "want 3 tool_use blocks upstream")
  } finally {
    up.close()
  }
})

test("translate: parallel tool calls are all returned, in provider order", async () => {
  const up = await upstream({
    content: [
      { type: "text", text: "calling three" },
      { type: "tool_use", id: "t1", name: "alpha", input: { n: 1 } },
      { type: "tool_use", id: "t2", name: "beta", input: { n: 2 } },
      { type: "tool_use", id: "t3", name: "gamma", input: { n: 3 } },
    ],
    stop_reason: "tool_use",
  })
  try {
    const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
    const res = await c.translate({
      messages: [{ role: "user", content: "go" }],
      tools: openAITools(),
    })
    assert.equal(res.toolCalls.length, 3)
    assert.deepEqual(
      res.toolCalls.map((t) => t.name),
      ["alpha", "beta", "gamma"],
    )
    assert.equal(res.text, "calling three", "text alongside tool calls was lost")
    assert.equal(res.finishReason, "tool_calls")
  } finally {
    up.close()
  }
})

test("translate: executes nothing and keeps no state across calls", async () => {
  let ran = 0
  const up = await upstream({
    content: [{ type: "tool_use", id: "t1", name: "danger", input: {} }],
    stop_reason: "tool_use",
  })
  try {
    const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
    const tk = await createToolkit({
      builtins: false,
      extraTools: [defineTool({ name: "danger", description: "must not run" }, async () => { ran++; return "RAN" })],
    })
    for (let i = 0; i < 3; i++) {
      const res = await c.translate({ messages: [{ role: "user", content: "go" }], toolkit: tk })
      assert.equal(res.toolCalls.length, 1)
      assert.equal(res.toolCalls[0].name, "danger")
    }
    assert.equal(ran, 0, "translate EXECUTED a tool — it must never execute anything")
    // no history accumulated between the three independent calls
    assert.equal(up.sent().messages.length, 1, "state leaked between translate calls")
    await tk.close()
  } finally {
    up.close()
  }
})

test("translate: a toolkit is declared natively but never executed", async () => {
  let ran = false
  const up = await upstream({
    content: [{ type: "tool_use", id: "tu_9", name: "my_native_tool", input: { x: 1 } }],
    stop_reason: "tool_use",
  })
  try {
    const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
    const tk = await createToolkit({
      builtins: false,
      extraTools: [
        defineTool(
          {
            name: "my_native_tool",
            description: "an ordinary executable tool",
            inputSchema: { type: "object", properties: { x: { type: "number" } } },
          },
          async () => {
            ran = true
            return "SHOULD NOT RUN"
          },
        ),
      ],
    })
    const res = await c.translate({ messages: [{ role: "user", content: "use the tool" }], toolkit: tk })
    assert.equal(ran, false, "translate executed a toolkit tool")
    assert.equal(res.toolCalls.length, 1)
    assert.equal(res.toolCalls[0].name, "my_native_tool")
    assert.equal(res.toolCalls[0].id, "tu_9")
    const sent = JSON.stringify(up.sent())
    assert.match(sent, /input_schema/)
    assert.match(sent, /my_native_tool/)
    await tk.close()
  } finally {
    up.close()
  }
})

test("translate: a toolkit and an OpenAI tools array compose", async () => {
  const up = await upstream({ content: [{ type: "text", text: "ok" }], stop_reason: "end_turn" })
  try {
    const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
    const tk = await createToolkit({
      builtins: false,
      extraTools: [defineTool({ name: "server_side_tool", description: "gateway's own" }, async () => "x")],
    })
    await c.translate({ messages: [{ role: "user", content: "go" }], toolkit: tk, tools: openAITools() })
    const sent = JSON.stringify(up.sent())
    for (const want of ["server_side_tool", "get_weather"]) {
      assert.ok(sent.includes(want), `composed declaration missing ${want}`)
    }
    await tk.close()
  } finally {
    up.close()
  }
})

test("translate: tool_choice maps onto Anthropic's shape", async () => {
  const cases: Array<[unknown, string | null]> = [
    [undefined, null],
    ["auto", null],
    ["required", '"type":"any"'],
    ["none", '"type":"none"'],
    [{ type: "function", function: { name: "get_weather" } }, '"name":"get_weather"'],
  ]
  for (const [input, want] of cases) {
    const up = await upstream({ content: [{ type: "text", text: "ok" }], stop_reason: "end_turn" })
    try {
      const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
      await c.translate({ messages: [{ role: "user", content: "go" }], tools: openAITools(), toolChoice: input })
      const sent = up.sent()
      if (want === null) {
        assert.equal(sent.tool_choice, undefined, `tool_choice ${String(input)} should be omitted`)
      } else {
        assert.ok(sent.tool_choice, `tool_choice ${String(input)} missing`)
        assert.ok(JSON.stringify(sent.tool_choice).includes(want), `tool_choice did not map to ${want}`)
      }
    } finally {
      up.close()
    }
  }
})

test("translate: finish reason maps from the provider stop reason", async () => {
  const cases: Array<[string, string]> = [
    ["end_turn", "stop"],
    ["max_tokens", "length"],
    ["refusal", "content_filter"],
    ["stop_sequence", "stop"],
  ]
  for (const [stop, want] of cases) {
    const up = await upstream({ content: [{ type: "text", text: "x" }], stop_reason: stop })
    try {
      const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
      const res = await c.translate({ messages: [{ role: "user", content: "go" }] })
      assert.equal(res.finishReason, want, `stop_reason ${stop}`)
    } finally {
      up.close()
    }
  }
})

test("translate: arguments are accepted as an object as well as a string", async () => {
  const up = await upstream({ content: [{ type: "text", text: "ok" }], stop_reason: "end_turn" })
  try {
    const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
    await c.translate({
      messages: [
        { role: "user", content: "go" },
        // some clients send arguments as an object rather than a JSON string
        { role: "assistant", tool_calls: [{ id: "z", function: { name: "f", arguments: { city: "Madurai" } } }] },
        { role: "tool", tool_call_id: "z", content: "done" },
      ],
    })
    const use = up
      .sent()
      .messages.flatMap((m: any) => (Array.isArray(m.content) ? m.content : []))
      .find((b: any) => b.type === "tool_use")
    assert.ok(use, "no tool_use block upstream")
    assert.equal(use.input.city, "Madurai", "object-form arguments were not carried through")
  } finally {
    up.close()
  }
})

test("translate: content given as parts is flattened to text", async () => {
  const up = await upstream({ content: [{ type: "text", text: "ok" }], stop_reason: "end_turn" })
  try {
    const c = createClient({ baseUrl: up.url, style: "anthropic", model: "stub", apiKey: "k" })
    await c.translate({
      messages: [
        {
          role: "user",
          content: [
            { type: "text", text: "part one " },
            { type: "text", text: "part two" },
          ],
        },
      ],
    })
    assert.match(JSON.stringify(up.sent()), /part one part two/)
  } finally {
    up.close()
  }
})

test("translate: LLM hooks fire exactly once and no tool hook fires", async () => {
  const up = await upstream({
    content: [{ type: "tool_use", id: "t1", name: "get_weather", input: {} }],
    stop_reason: "tool_use",
  })
  try {
    let before = 0
    let after = 0
    let toolHooks = 0
    const c = createClient({
      baseUrl: up.url,
      style: "anthropic",
      model: "stub",
      apiKey: "k",
      hooks: {
        beforeLLM: async () => {
          before++
          return undefined
        },
        afterLLM: async () => {
          after++
        },
        beforeTool: async () => {
          toolHooks++
          return undefined
        },
        afterTool: async () => {
          toolHooks++
          return undefined
        },
      },
    })
    await c.translate({ messages: [{ role: "user", content: "go" }], tools: openAITools() })
    assert.equal(before, 1, "beforeLLM did not fire exactly once")
    assert.equal(after, 1, "afterLLM did not fire exactly once")
    assert.equal(toolHooks, 0, "a tool hook fired, but no tool runs in translate")
  } finally {
    up.close()
  }
})

// ---- OpenAI upstream: near-passthrough ----

test("translate: an OpenAI upstream passes tools and arguments through unchanged", async () => {
  const up = await upstream({
    choices: [
      {
        message: {
          content: "",
          tool_calls: [
            { id: "call_1", type: "function", function: { name: "get_weather", arguments: '{"city":"Madurai"}' } },
          ],
        },
        finish_reason: "tool_calls",
      },
    ],
    usage: { prompt_tokens: 3, completion_tokens: 4, total_tokens: 7 },
  })
  try {
    const c = createClient({ baseUrl: up.url, style: "openai", model: "stub", apiKey: "k" })
    const res = await c.translate({
      messages: [{ role: "user", content: "weather?" }],
      tools: openAITools(),
    })
    assert.equal(res.finishReason, "tool_calls")
    assert.equal(res.toolCalls.length, 1)
    assert.equal(res.toolCalls[0].arguments, '{"city":"Madurai"}', "arguments not byte-for-byte")
    assert.equal(res.usage.totalTokens, 7)
    assert.match(JSON.stringify(up.sent()), /"parameters"/, "OpenAI tools were altered on an OpenAI upstream")
  } finally {
    up.close()
  }
})
