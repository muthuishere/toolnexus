/**
 * stdio transport for the 2026 path (§2.3): one JSON-RPC message per line, responses demultiplexed
 * by id (concurrent calls allowed), stderr passed through and never parsed, a dead child fails every
 * pending call instead of hanging. No headers, no auth.
 */
import { spawn, type ChildProcess } from "node:child_process"
import type { Readable } from "node:stream"
import type { Transport } from "./client.js"
import type { Obj } from "./protocol.js"
import type { Transport as SdkTransport } from "@modelcontextprotocol/sdk/shared/transport.js"
import { ReadBuffer, serializeMessage } from "@modelcontextprotocol/sdk/shared/stdio.js"
import type { JSONRPCMessage } from "@modelcontextprotocol/sdk/types.js"

/** The child exited / closed stdout before answering. */
export class StdioClosedError extends Error {}

export class StdioTransport implements Transport {
  private pending = new Map<string, (m: Obj) => void>()
  private err?: Error
  private done: Promise<void>
  private child: ChildProcess

  private buf: Buffer = Buffer.alloc(0)
  private onData: (chunk: Buffer) => void
  private onEnd: () => void
  private detached = false
  private hold = false
  private held = false

  constructor(child: ChildProcess) {
    this.child = child
    // Line-exact own reader (no readline): detach() can stop it and hand over unread bytes.
    let closed!: () => void
    this.done = new Promise<void>((r) => (closed = r))
    this.onData = (chunk: Buffer) => {
      this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk
      this.drain()
    }
    this.onEnd = () => {
      this.err ??= new StdioClosedError("stdio server closed its stdout")
      for (const f of this.pending.values()) f({ __closed: true })
      this.pending.clear()
      closed()
    }
    const out = child.stdout as Readable
    out.on("data", this.onData)
    out.once("end", this.onEnd)
    out.once("close", this.onEnd)
    child.stdin?.on("error", () => {}) // EPIPE after the server died surfaces as a closed stdout
  }

  private drain(): void {
    let nl: number
    while (!this.detached && !this.held && (nl = this.buf.indexOf(10)) >= 0) {
      const line = this.buf.subarray(0, nl).toString("utf8").replace(/\r$/, "")
      this.buf = this.buf.subarray(nl + 1)
      this.onLine(line)
    }
  }

  /** Probe mode: after the next delivered response, stop parsing (bytes stay buffered for detach). */
  holdAfterNextResponse(): void {
    this.hold = true
  }

  /** Leave probe mode and parse whatever was held. */
  release(): void {
    this.hold = false
    this.held = false
    this.drain()
  }

  private onLine(line: string): void {
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
    if (f && this.hold) this.held = true
    f?.(m)
  }

  /**
   * §2.2a handoff: stop reading stdout (listeners removed, stream paused — no read left pending)
   * and give the live child plus any bytes already read past the last delivered line to the
   * frozen path. The transport is unusable afterwards; the child is NOT killed.
   */
  detach(): { child: ChildProcess; leftover: Buffer } {
    this.detached = true
    const out = this.child.stdout as Readable
    out.off("data", this.onData)
    out.off("end", this.onEnd)
    out.off("close", this.onEnd)
    out.pause()
    this.err ??= new StdioClosedError("stdio transport detached")
    const leftover = this.buf
    this.buf = Buffer.alloc(0)
    return { child: this.child, leftover }
  }

  /** Writes one line and waits for the response with the same id. Never retried. */
  roundTrip(id: number, body: string, timeoutMs: number, signal?: AbortSignal): Promise<Obj> {
    if (this.err) return Promise.reject(this.err)
    const key = JSON.stringify(id)
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        clearTimeout(timer)
        signal?.removeEventListener("abort", onAbort)
        this.pending.delete(key)
      }
      const onAbort = () => {
        cleanup()
        reject(signal?.reason ?? new Error("aborted"))
      }
      const timer = setTimeout(() => {
        cleanup()
        reject(new Error("stdio request timed out"))
      }, timeoutMs)
      if (signal) {
        if (signal.aborted) return onAbort()
        signal.addEventListener("abort", onAbort, { once: true })
      }
      this.pending.set(key, (m) => {
        cleanup()
        if (m.__closed) return reject(this.err)
        if (m.result == null && m.error == null) return reject(new Error("response has neither result nor error"))
        resolve(m)
      })
      this.child.stdin?.write(body + "\n")
    })
  }

  /** Close stdin, then SIGKILL after 2 s if the child is still alive. */
  async close(): Promise<void> {
    if (this.child.exitCode === null && this.child.signalCode === null) {
      this.child.stdin?.end()
      const exited = new Promise<void>((r) => this.child.once("exit", () => r()))
      const t = setTimeout(() => this.child.kill("SIGKILL"), 2000)
      await exited
      clearTimeout(t)
    }
    this.child.stdout?.destroy()
    await this.done
  }
}

/** Spawns argv (no shell) and returns its transport; rejects if the spawn itself fails. */
export function spawnStdio(argv: string[], env: NodeJS.ProcessEnv, cwd?: string): Promise<StdioTransport> {
  const [cmd, ...args] = argv
  const child = spawn(cmd, args, { env, cwd, stdio: ["pipe", "pipe", "inherit"] })
  return new Promise((resolve, reject) => {
    child.once("error", reject)
    child.once("spawn", () => resolve(new StdioTransport(child)))
  })
}

/**
 * §2.2a: the frozen SDK path over a child the probe already started (StdioClientTransport always
 * spawns). Takes the detached probe's stdin/stdout plus any bytes it had read but not parsed.
 */
export class AdoptedStdioTransport implements SdkTransport {
  onclose?: () => void
  onerror?: (error: Error) => void
  onmessage?: (message: JSONRPCMessage) => void
  private rb = new ReadBuffer()
  private closed = false

  constructor(private child: ChildProcess, private leftover: Buffer) {}

  async start(): Promise<void> {
    const out = this.child.stdout as Readable
    out.on("data", (chunk: Buffer) => this.feed(chunk))
    out.on("error", (e) => this.onerror?.(e))
    this.child.stdin?.on("error", (e) => this.onerror?.(e))
    this.child.once("close", () => this.finish())
    if (this.leftover.length) this.feed(this.leftover)
    this.leftover = Buffer.alloc(0)
    out.resume()
  }

  private feed(chunk: Buffer): void {
    this.rb.append(chunk)
    for (;;) {
      let m: JSONRPCMessage | null
      try {
        m = this.rb.readMessage()
      } catch (e) {
        this.onerror?.(e as Error)
        continue
      }
      if (m === null) break
      this.onmessage?.(m)
    }
  }

  send(message: JSONRPCMessage): Promise<void> {
    return new Promise((resolve) => {
      if (!this.child.stdin?.write(serializeMessage(message))) this.child.stdin?.once("drain", resolve)
      else resolve()
    })
  }

  private finish(): void {
    if (this.closed) return
    this.closed = true
    this.rb.clear()
    this.onclose?.()
  }

  /** Close stdin, then SIGKILL after 2 s if the child is still alive. */
  async close(): Promise<void> {
    if (this.child.exitCode === null && this.child.signalCode === null) {
      const exited = new Promise<void>((r) => this.child.once("exit", () => r()))
      this.child.stdin?.end()
      const t = setTimeout(() => this.child.kill("SIGKILL"), 2000)
      await exited
      clearTimeout(t)
    }
    this.child.stdout?.destroy()
    this.finish()
  }
}
