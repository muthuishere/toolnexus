// stdio client transport (MCP 2026-07-28 basic/transports): spawn the server, one JSON-RPC
// message per line on stdin/stdout. stderr is the server's log stream: passed through
// (or captured), never parsed. No headers, no auth — the _meta carries the version.
import { spawn, type ChildProcess } from "node:child_process"
import { createInterface } from "node:readline"
import type { Readable, Writable } from "node:stream"
import { Client, type Transport } from "./client.ts"
import { CODE_UNSUPPORTED_PROTOCOL, DEFAULT_TIMEOUT_MS, META_VERSION, PROTOCOL_VERSION, RpcError, type Obj } from "./protocol.ts"
import type { Server } from "./server.ts"

export interface StdioOptions {
  args?: string[]
  env?: NodeJS.ProcessEnv
  cwd?: string
  /** "inherit" (default: the server's log goes to our stderr), "ignore", or a stream to capture it. */
  stderr?: "inherit" | "ignore" | Writable
}

class StdioTransport implements Transport {
  private pending = new Map<string, (m: Obj) => void>()
  private err?: Error
  private done: Promise<void>
  private child: ChildProcess
  constructor(child: ChildProcess) {
    this.child = child
    const rl = createInterface({ input: child.stdout as Readable, crlfDelay: Infinity })
    rl.on("line", (line) => {
      let m: any
      try {
        m = JSON.parse(line)
      } catch {
        return // noise
      }
      if (!m || typeof m !== "object" || m.method !== undefined || m.id === undefined || m.id === null) return // not a response
      const key = JSON.stringify(m.id)
      const f = this.pending.get(key)
      this.pending.delete(key)
      f?.(m)
    })
    this.done = new Promise((r) => rl.once("close", () => {
      this.err ??= new Error("stdio server closed its stdout")
      for (const f of this.pending.values()) f({ __closed: true })
      this.pending.clear()
      r()
    }))
    child.stdin?.on("error", () => {}) // EPIPE after the server died surfaces as a closed stdout
  }

  /** Writes one line and waits for the response with the same id. Never retried. */
  roundTrip(id: number, body: string): Promise<Obj> {
    if (this.err) return Promise.reject(this.err)
    const key = JSON.stringify(id)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(key)
        reject(new Error("stdio request timed out"))
      }, DEFAULT_TIMEOUT_MS)
      this.pending.set(key, (m) => {
        clearTimeout(timer)
        if (m.__closed) return reject(this.err)
        if (m.result == null && m.error == null) return reject(new Error("response has neither result nor error"))
        resolve(m)
      })
      this.child.stdin?.write(body + "\n") // JSON.stringify never emits a raw newline
    })
  }

  async close(): Promise<void> {
    this.child.stdin?.end()
    const exited = new Promise<void>((r) => (this.child.exitCode !== null || this.child.signalCode !== null ? r() : this.child.once("exit", () => r())))
    const t = setTimeout(() => this.child.kill("SIGKILL"), 2000)
    await Promise.all([exited, this.done])
    clearTimeout(t)
  }
}

/** Starts command and returns a Client with the same API as new Client(url). Call close() to stop it. */
export function connectStdio(command: string, o: StdioOptions = {}): Promise<Client> {
  const stderr = o.stderr ?? "inherit"
  const child = spawn(command, o.args ?? [], {
    env: o.env, cwd: o.cwd, stdio: ["pipe", "pipe", typeof stderr === "string" ? stderr : "pipe"],
  })
  if (typeof stderr !== "string") child.stderr?.pipe(stderr, { end: false })
  return new Promise((resolve, reject) => {
    child.once("error", reject)
    child.once("spawn", () => {
      const c = new Client("")
      c.transport = new StdioTransport(child)
      resolve(c)
    })
  })
}

/**
 * Serves s as newline-delimited JSON-RPC over input/output until input closes. No auth and no HTTP
 * headers exist on stdio; only the _meta protocol version is checked. (Tests and examples only.)
 */
export function serveStdio(s: Server, input: Readable = process.stdin, output: Writable = process.stdout): Promise<void> {
  const rl = createInterface({ input, crlfDelay: Infinity })
  const inflight = new Set<Promise<void>>()
  rl.on("line", (line) => {
    let req: any
    try {
      req = JSON.parse(line)
    } catch {
      output.write(JSON.stringify({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }) + "\n")
      return
    }
    if (!req || req.id === undefined || req.id === null) return
    const p = (async () => {
      const params: Obj = req.params && typeof req.params === "object" ? req.params : {}
      const mv = params._meta?.[META_VERSION] ?? ""
      const msg: Obj = { jsonrpc: "2.0", id: req.id }
      try {
        if (mv !== PROTOCOL_VERSION)
          throw new RpcError(CODE_UNSUPPORTED_PROTOCOL, "Unsupported protocol version", { supported: [PROTOCOL_VERSION], requested: mv })
        msg.result = await s.dispatch(String(req.method ?? ""), params)
      } catch (e) {
        msg.error = e instanceof RpcError ? e.toJSON() : { code: -32603, message: String((e as Error)?.message ?? e) }
      }
      output.write(JSON.stringify(msg) + "\n")
    })()
    inflight.add(p)
    void p.finally(() => inflight.delete(p))
  })
  return new Promise((r) => rl.once("close", () => void Promise.all(inflight).then(() => r())))
}
