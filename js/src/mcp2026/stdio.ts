/**
 * stdio transport for the 2026 path (§2.3): one JSON-RPC message per line, responses demultiplexed
 * by id (concurrent calls allowed), stderr passed through and never parsed, a dead child fails every
 * pending call instead of hanging. No headers, no auth.
 */
import { spawn, type ChildProcess } from "node:child_process"
import { createInterface } from "node:readline"
import type { Readable } from "node:stream"
import type { Transport } from "./client.js"
import type { Obj } from "./protocol.js"

/** The child exited / closed stdout before answering. */
export class StdioClosedError extends Error {}

export class StdioTransport implements Transport {
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
    this.done = new Promise((r) =>
      rl.once("close", () => {
        this.err ??= new StdioClosedError("stdio server closed its stdout")
        for (const f of this.pending.values()) f({ __closed: true })
        this.pending.clear()
        r()
      }),
    )
    child.stdin?.on("error", () => {}) // EPIPE after the server died surfaces as a closed stdout
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
