// The default Node LLM transport (src/nodefetch.ts): redirects are followed from the target,
// never by re-sending to the original URL, and a host-installed undici dispatcher is honoured.
import { test } from "node:test"
import assert from "node:assert/strict"
import http from "node:http"
import type { AddressInfo } from "node:net"
import { nodeFetch } from "../dist/nodefetch.js"

type Hit = { path: string; method: string; body: string }

async function server(handle: (req: http.IncomingMessage, res: http.ServerResponse, body: string) => void) {
  const hits: Hit[] = []
  const srv = http.createServer((req, res) => {
    let body = ""
    req.on("data", (c) => (body += c))
    req.on("end", () => {
      hits.push({ path: req.url ?? "", method: req.method ?? "", body })
      handle(req, res, body)
    })
  })
  await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r))
  const base = `http://127.0.0.1:${(srv.address() as AddressInfo).port}`
  return { hits, base, close: () => new Promise<void>((r) => { srv.closeAllConnections(); srv.close(() => r()) }) }
}

function redirecting(status: number) {
  return (req: http.IncomingMessage, res: http.ServerResponse) => {
    if (req.url === "/start") {
      res.writeHead(status, { location: "/target" })
      res.end()
      return
    }
    res.writeHead(200, { "content-type": "application/json" })
    res.end(JSON.stringify({ ok: true }))
  }
}

for (const status of [307, 308]) {
  test(`${status}: the POST and its body go to the target, the origin is hit once`, async () => {
    const s = await server(redirecting(status))
    try {
      const res = await nodeFetch(`${s.base}/start`, { method: "POST", body: '{"q":1}', headers: { "content-type": "application/json" } })
      assert.deepEqual(await res.json(), { ok: true })
      assert.deepEqual(s.hits, [
        { path: "/start", method: "POST", body: '{"q":1}' },
        { path: "/target", method: "POST", body: '{"q":1}' },
      ])
    } finally {
      await s.close()
    }
  })
}

for (const status of [301, 302, 303]) {
  test(`${status} after a POST: a bodiless GET to the target, the origin is hit once`, async () => {
    const s = await server(redirecting(status))
    try {
      const res = await nodeFetch(`${s.base}/start`, { method: "POST", body: '{"q":1}', headers: { "content-type": "application/json" } })
      assert.equal(res.status, 200)
      assert.deepEqual(s.hits, [
        { path: "/start", method: "POST", body: '{"q":1}' },
        { path: "/target", method: "GET", body: "" },
      ])
    } finally {
      await s.close()
    }
  })
}

test("a host-installed undici dispatcher is used instead of the node:http path", async () => {
  const s = await server(redirecting(200))
  const slot = Symbol.for("undici.globalDispatcher.1")
  const g = globalThis as Record<symbol, unknown>
  const saved = g[slot]
  let dispatched = 0
  // A minimal dispatcher that refuses: proves the request went through the platform fetch.
  g[slot] = {
    dispatch(_opts: unknown, handler: { onError?: (e: Error) => void }) {
      dispatched++
      handler.onError?.(new Error("custom dispatcher"))
      return true
    },
  }
  try {
    await assert.rejects(nodeFetch(`${s.base}/x`, { method: "POST", body: "{}" }))
    assert.equal(dispatched, 1)
    assert.equal(s.hits.length, 0)
  } finally {
    g[slot] = saved
    await s.close()
  }
})
