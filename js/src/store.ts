import { promises as fsp } from "node:fs"
import path from "node:path"
import { randomBytes } from "node:crypto"
import { sortEntries, type ConversationEntry, type ConversationStore } from "./client.js"

const ID_RE = /^[A-Za-z0-9._-]{1,128}$/
const VERSION = 1

function checkId(id: string): void {
  if (typeof id !== "string" || !ID_RE.test(id) || id === "." || id === "..") {
    throw new Error(`invalid conversation id: ${JSON.stringify(id)}`)
  }
}

/**
 * Durable {@link ConversationStore}: one file `<root>/<id>.json` per conversation holding
 * `{"version":1,"messages":[...]}`. `save` writes a temp file (0600), fsyncs and renames it over
 * the old one, so a reader sees the old or the new transcript, never a torn one (power-loss
 * durability of the rename is best-effort). Same-id saves are serialized in-process; across
 * processes the last rename wins.
 */
/**
 * Durability mode (SPEC §8 "Durability mode"): `full` (default) flushes the file then fsyncs the
 * directory best-effort; `fsync` flushes the file only; `none` skips every flush. Temp-then-rename
 * holds in every mode. (On macOS Node's `fh.sync()` is libuv's fsync, which already issues
 * `F_FULLFSYNC`; Node exposes no plainer flush, so `fsync` differs from `full` only by the dir fsync.)
 */
export type FileStoreSync = "full" | "fsync" | "none"

export interface FileStoreOptions {
  sync?: FileStoreSync
}

export class FileConversationStore implements ConversationStore {
  readonly root: string
  readonly sync: FileStoreSync
  private readonly chains = new Map<string, Promise<void>>()

  constructor(root: string, opts: FileStoreOptions = {}) {
    if (typeof root !== "string" || root === "") throw new Error("FileConversationStore: root is required")
    const mode = opts.sync ?? "full"
    if (mode !== "full" && mode !== "fsync" && mode !== "none") {
      throw new Error(`FileConversationStore: unknown sync mode ${JSON.stringify(opts.sync)}`)
    }
    this.root = root
    this.sync = mode
  }

  private file(id: string): string {
    return path.join(this.root, id + ".json")
  }

  async get(id: string): Promise<any[] | undefined> {
    checkId(id)
    let text: string
    try {
      text = await fsp.readFile(this.file(id), "utf8")
    } catch (e: any) {
      if (e?.code === "ENOENT") return undefined
      throw e
    }
    let body: any
    try {
      body = JSON.parse(text)
    } catch (e) {
      throw new Error(`conversation "${id}": corrupt file: ${e instanceof Error ? e.message : String(e)}`)
    }
    if (body === null || typeof body !== "object" || body.version !== VERSION) {
      throw new Error(`conversation "${id}": unsupported version ${JSON.stringify(body?.version)}`)
    }
    if (!Array.isArray(body.messages)) throw new Error(`conversation "${id}": corrupt file: messages is not an array`)
    return body.messages
  }

  async save(id: string, messages: any[]): Promise<void> {
    checkId(id)
    const prev = this.chains.get(id) ?? Promise.resolve()
    const next = prev.catch(() => {}).then(() => this.write(id, messages))
    this.chains.set(id, next)
    try {
      await next
    } finally {
      if (this.chains.get(id) === next) this.chains.delete(id)
    }
  }

  private async write(id: string, messages: any[]): Promise<void> {
    await fsp.mkdir(this.root, { recursive: true, mode: 0o700 })
    const tmp = path.join(this.root, `.${id}.${randomBytes(6).toString("hex")}.tmp`)
    const data = JSON.stringify({ version: VERSION, messages })
    let fh: Awaited<ReturnType<typeof fsp.open>> | undefined
    try {
      fh = await fsp.open(tmp, "w", 0o600)
      await fh.writeFile(data, "utf8")
      if (this.sync !== "none") await fh.sync()
      await fh.close()
      fh = undefined
      await fsp.rename(tmp, this.file(id))
    } catch (e) {
      await fh?.close().catch(() => {})
      await fsp.rm(tmp, { force: true }).catch(() => {})
      throw e
    }
    // Best-effort parent-dir fsync so the rename itself survives power loss where supported.
    if (this.sync !== "full") return
    try {
      const dir = await fsp.open(this.root, "r")
      await dir.sync().catch(() => {})
      await dir.close()
    } catch {}
  }

  /** Every stored conversation, newest first (mtime; ties by id ascending). Bodies are not parsed. */
  async list(): Promise<ConversationEntry[]> {
    let names: string[]
    try {
      names = await fsp.readdir(this.root)
    } catch (e: any) {
      if (e?.code === "ENOENT") return []
      throw e
    }
    const out: ConversationEntry[] = []
    for (const name of names) {
      if (!name.endsWith(".json")) continue
      const id = name.slice(0, -5)
      if (!ID_RE.test(id) || id === "." || id === "..") continue
      try {
        const st = await fsp.stat(path.join(this.root, name))
        if (st.isFile()) out.push({ id, updatedAt: st.mtime })
      } catch (e: any) {
        if (e?.code !== "ENOENT") throw e
      }
    }
    return sortEntries(out)
  }

  /** Remove a conversation; absent ids are not an error. */
  async delete(id: string): Promise<void> {
    checkId(id)
    await fsp.rm(this.file(id), { force: true })
  }
}
