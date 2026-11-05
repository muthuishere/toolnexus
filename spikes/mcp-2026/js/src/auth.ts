// Client-side OAuth for MCP 2026-07-28 (basic/authorization). Node stdlib only.
// Flow: 401/403 -> WWW-Authenticate -> PRM (RFC 9728) -> AS metadata (RFC 8414 / OIDC)
// -> client id (pre-registered | CIMD) -> code + PKCE S256 + resource (RFC 8707)
// -> iss (RFC 9207) + state check -> token -> retry with Bearer.
import { spawn } from "node:child_process"
import { createHash, randomBytes } from "node:crypto"
import http from "node:http"
import type { IncomingMessage, ServerResponse } from "node:http"
import { closeServer } from "./server.ts"
import { defaultHttp, getJSON, Mutex, text, type HttpFn, type Obj } from "./protocol.ts"

const inspect = Symbol.for("nodejs.util.inspect.custom")

/** Bound to the issuer that minted it and the resource it is for. Never log it (it prints redacted). */
export class Token {
  accessToken = ""
  refreshToken = ""
  expiry = 0 // ms since epoch; 0 = no expiry
  scopes: string[] = []
  issuer = ""
  resource = ""
  clientId = ""
  constructor(init: Partial<Token> = {}) {
    Object.assign(this, init)
  }
  toString(): string {
    return `Token{issuer=${this.issuer}, redacted}`
  }
  toJSON(): string {
    return this.toString()
  }
  [inspect](): string {
    return this.toString()
  }
}

/** Persists tokens per resource. Implementations must keep them confidential. Sync or async. */
export interface TokenStore {
  load(resource: string): Token | undefined | Promise<Token | undefined>
  save(resource: string, t: Token): void | Promise<void>
  delete(resource: string): void | Promise<void>
}

export class MemoryTokenStore implements TokenStore {
  private m = new Map<string, Token>()
  load(r: string) { return this.m.get(r) }
  save(r: string, t: Token) { this.m.set(r, t) }
  delete(r: string) { this.m.delete(r) }
}

/** Mirrors toolnexus SPEC §10 Request (kind "authorization"). */
export interface Pending {
  id: string
  kind: "authorization"
  prompt: string
  url: string
  expiresAt: string
}

/** Thrown instead of blocking when the authorizer pauses. Call oauth.resume(id, callbackURL). */
export class PendingError extends Error {
  pending: Pending
  constructor(p: Pending) {
    super("authorization pending: " + p.prompt)
    this.pending = p
  }
}

/** The Pending inside err, or undefined. */
export function pendingOf(err: unknown): Pending | undefined {
  return err instanceof PendingError ? err.pending : undefined
}

/** What a pausing AuthSession.wait throws. */
export const PAUSE = new Error("authorization paused")

/** Decides how the user reaches the authorization URL and how the callback comes back. */
export interface Authorizer {
  begin(): Promise<AuthSession>
}
export interface AuthSession {
  redirectUri: string
  /** Resolves with the full callback URL, or throws PAUSE to hand a Pending to the caller. */
  wait(p: Pending): Promise<string>
  close(): void | Promise<void>
}

/** Never blocks: every authorization becomes a Pending. */
export class PauseAuthorizer implements Authorizer {
  redirect: string
  constructor(o: { redirect: string }) {
    this.redirect = o.redirect
  }
  async begin(): Promise<AuthSession> {
    return { redirectUri: this.redirect, wait: async () => { throw PAUSE }, close: () => {} }
  }
}

/** Opens a browser and listens on 127.0.0.1:<random>/callback (RFC 8252 §7.3). */
export class LoopbackAuthorizer implements Authorizer {
  open: (url: string) => unknown | Promise<unknown> // default = system browser
  timeoutMs = 5 * 60_000
  constructor(o: { open?: (url: string) => unknown | Promise<unknown> } = {}) {
    this.open = o.open ?? openBrowser
  }
  async begin(): Promise<AuthSession> {
    let deliver!: (u: string) => void
    const cb = new Promise<string>((r) => (deliver = r))
    const hs = http.createServer((req, res) => {
      if (new URL(req.url ?? "/", "http://x").pathname !== "/callback") return void res.writeHead(404).end()
      deliver(`http://${req.headers.host}${req.url}`)
      res.end("Authorization received. You can close this window.")
    })
    await new Promise<void>((r, j) => hs.once("error", j).listen(0, "127.0.0.1", () => r()))
    const port = (hs.address() as { port: number }).port
    const timeoutMs = this.timeoutMs
    const open = this.open
    return {
      redirectUri: `http://127.0.0.1:${port}/callback`,
      close: () => closeServer(hs),
      async wait(p) {
        let timer: NodeJS.Timeout | undefined
        const timeout = new Promise<never>((_, j) => (timer = setTimeout(() => j(new Error("authorization timed out")), timeoutMs)))
        // the opener may block (tests follow redirects synchronously); a failed opener must not leave
        // us waiting 5 minutes for a callback that cannot come
        const opened = Promise.resolve().then(() => open(p.url)).then(
          () => cb,
          (e) => { throw new Error(`open authorization URL: ${(e as Error)?.message ?? e}`) },
        )
        try {
          return await Promise.race([cb, opened, timeout])
        } finally {
          clearTimeout(timer)
        }
      },
    }
  }
}

function openBrowser(u: string): Promise<void> {
  const [cmd, args] = process.platform === "darwin" ? ["open", [u]] : process.platform === "win32" ? ["rundll32", ["url.dll,FileProtocolHandler", u]] : ["xdg-open", [u]]
  return new Promise((r, j) => spawn(cmd as string, args as string[], { stdio: "ignore", detached: true }).once("error", j).once("spawn", r).unref())
}

export interface OAuthOptions {
  authorizer?: Authorizer // default = new LoopbackAuthorizer() (system browser + loopback callback)
  clientId?: string // pre-registered client_id for any issuer not in clientIds
  clientSecret?: string // optional: makes the pre-registered client confidential. Never logged.
  tokenAuthMethod?: "" | "none" | "client_secret_basic" | "client_secret_post" // "" = basic if a secret else none
  /** Drops the RFC 8707 `resource` from refresh_token requests (Entra rejects it; python-sdk #2578). */
  omitRefreshResource?: boolean
  store?: TokenStore // default = in-memory
  clientIds?: Record<string, string> // issuer -> pre-registered client_id (bound to that issuer)
  clientMetadataUrl?: string // CIMD client_id (HTTPS URL), used when the AS advertises support
  http?: HttpFn // for PRM / AS / token calls
  allowHttp?: boolean // allow non-HTTPS AS endpoints (dev only; the spec requires HTTPS)
}

interface AuthFlow {
  resource: string; issuer: string; clientId: string; redirect: string; verifier: string; state: string
  tokenEndpoint: string; issSupported: boolean; scopes: string[]; expires: number
  retry: () => Promise<Obj>
}

/** The client-side auth engine; attach it as client.auth. */
export class OAuth {
  authorizer: Authorizer
  clientId: string
  clientSecret: string
  tokenAuthMethod: string
  omitRefreshResource: boolean
  store: TokenStore
  clientIds: Record<string, string>
  clientMetadataUrl: string
  http: HttpFn
  allowHttp: boolean
  private authMu = new Mutex() // serialises refresh + interactive authorization
  private issuer = new Map<string, string>() // resource -> discovered issuer (only then do we send a stored token)
  /** @internal paused flows by pending id (exposed for tests) */
  flows = new Map<string, AuthFlow>()

  constructor(o: OAuthOptions = {}) {
    this.authorizer = o.authorizer ?? new LoopbackAuthorizer()
    this.clientId = o.clientId ?? ""
    this.clientSecret = o.clientSecret ?? ""
    this.tokenAuthMethod = o.tokenAuthMethod ?? ""
    this.omitRefreshResource = o.omitRefreshResource ?? false
    this.store = o.store ?? new MemoryTokenStore()
    this.clientIds = o.clientIds ?? {}
    this.clientMetadataUrl = o.clientMetadataUrl ?? ""
    this.http = o.http ?? defaultHttp
    this.allowHttp = o.allowHttp ?? false
  }

  /** The token to send for resource, refreshing if expired. "" = send none. */
  async bearer(resource: string): Promise<string> {
    const iss = this.issuer.get(resource)
    let t = await this.store.load(resource)
    if (!t || !iss || t.issuer !== iss) return "" // only tokens from this server's AS (spec: Token Handling)
    if (expired(t)) {
      // one refresh for N concurrent callers (refresh tokens rotate: a 2nd refresh would fail)
      return this.authMu.lock(async () => {
        let cur = await this.store.load(resource)
        if (!cur) return ""
        if (expired(cur)) {
          try {
            await this.refresh(cur)
          } catch {
            return ""
          }
          cur = await this.store.load(resource)
        }
        return cur?.accessToken ?? ""
      })
    }
    return t.accessToken
  }

  private async refresh(t: Token): Promise<void> {
    if (!t.refreshToken) throw new Error("no refresh token")
    const md = await discoverAS(this.http, t.issuer)
    const form = new URLSearchParams({ grant_type: "refresh_token", refresh_token: t.refreshToken, client_id: t.clientId })
    if (!this.omitRefreshResource) form.set("resource", t.resource)
    let nt: Token
    try {
      nt = await this.tokenRequest(String(md.token_endpoint ?? ""), form, t.issuer, t.resource, t.clientId, t.scopes)
    } catch (e) {
      await this.store.delete(t.resource)
      throw e
    }
    if (!nt.refreshToken) nt.refreshToken = t.refreshToken
    await this.store.save(t.resource, nt)
  }

  /** @internal Handles a 401/403. Resolves = retry the request now; throws PendingError = paused. */
  challenge(resource: string, status: number, www: string, sent: string, retry: () => Promise<Obj>): Promise<void> {
    const params = parseChallenge(www)
    // N concurrent 401s -> one authorization, the rest retry with its token
    return this.authMu.lock(async () => {
      let cur = await this.store.load(resource)
      const known = this.issuer.get(resource)
      if (cur && known && cur.issuer === known && cur.accessToken !== sent && !expired(cur)) return // someone else refreshed/authorized meanwhile
      if (status === 401 && cur?.refreshToken && params.error === "invalid_token") {
        try {
          await this.refresh(cur)
          return
        } catch {
          cur = undefined
        }
      }
      if (status === 403 && params.error !== "insufficient_scope") throw new Error(`forbidden: ${params.error ?? ""}`)
      const prm = await this.fetchPRM(resource, params.resource_metadata ?? "")
      const servers = Array.isArray(prm.authorization_servers) ? prm.authorization_servers : []
      if (servers.length === 0) throw new Error("PRM lists no authorization_servers")
      const issuer = String(servers[0]) // RFC 9728 §7.6: client's choice; we take the first
      if (!this.allowHttp && !issuer.startsWith("https://")) throw new Error("authorization server must use https")
      const md = await discoverAS(this.http, issuer)
      const methods = md.code_challenge_methods_supported
      if (!Array.isArray(methods) || !methods.includes("S256")) throw new Error("authorization server does not advertise PKCE S256; refusing")
      this.issuer.set(resource, issuer)
      if (status === 401 && cur && cur.issuer === issuer && !params.error) return // first request after restart: issuer now known
      // Scope selection: challenge scope, else scopes_supported; on step-up union with previous.
      let scopes = (params.scope ?? "").split(/\s+/).filter(Boolean)
      if (scopes.length === 0) scopes = strings(prm.scopes_supported)
      if (cur && status === 403) for (const s of cur.scopes) if (!scopes.includes(s)) scopes.push(s)
      let clientId = this.clientIds[issuer] || this.clientId
      const cimd = md.client_id_metadata_document_supported === true
      if (!clientId && this.clientMetadataUrl && cimd) clientId = this.clientMetadataUrl
      if (!clientId) {
        // no DCR, ever: the user registers an app once and configures its id
        const why = cimd ? "OAuth.clientMetadataUrl is not set" : "it does not support Client ID Metadata Documents"
        throw new Error(`no client identity for ${issuer}: ${why} and no client id is configured — register an app with ${issuer} and set OAuth.clientId (dynamic client registration is not supported)`)
      }
      const sess = await this.authorizer.begin()
      const f: AuthFlow = {
        resource, issuer, clientId, redirect: sess.redirectUri, verifier: randStr(32), state: randStr(16),
        tokenEndpoint: String(md.token_endpoint ?? ""), scopes, retry,
        issSupported: md.authorization_response_iss_parameter_supported === true, expires: Date.now() + 10 * 60_000,
      }
      const q = new URLSearchParams({
        response_type: "code", client_id: clientId, redirect_uri: f.redirect, state: f.state,
        code_challenge: createHash("sha256").update(f.verifier).digest("base64url"), code_challenge_method: "S256", resource,
      })
      if (scopes.length) q.set("scope", scopes.join(" "))
      const p: Pending = {
        id: "auth-" + randStr(12), kind: "authorization", url: String(md.authorization_endpoint ?? "") + "?" + q.toString(),
        prompt: "Authorize access to " + resource, expiresAt: new Date(f.expires).toISOString().replace(/\.\d+Z$/, "Z"),
      }
      let cb: string
      try {
        cb = await sess.wait(p)
      } catch (e) {
        if (e === PAUSE) {
          const now = Date.now()
          for (const [id, old] of this.flows) if (now > old.expires) this.flows.delete(id) // drop abandoned pendings
          this.flows.set(p.id, f)
          throw new PendingError(p)
        }
        await sess.close()
        throw e
      }
      await sess.close()
      await this.complete(f, cb)
    })
  }

  /** @internal */
  setRetry(id: string, f: () => Promise<Obj>): void {
    const fl = this.flows.get(id)
    if (fl) fl.retry = f
  }

  /** Finishes a paused authorization and re-runs the original operation, returning its result. */
  async resume(id: string, callbackUrl: string): Promise<Obj> {
    const f = this.flows.get(id)
    this.flows.delete(id)
    if (!f) throw new Error(`unknown pending authorization "${id}"`)
    if (Date.now() > f.expires) throw new Error("pending authorization expired")
    await this.complete(f, callbackUrl)
    return f.retry()
  }

  /** resume for web apps: the pending is found by the callback's state. */
  resumeCallback(callbackUrl: string): Promise<Obj> {
    const st = new URL(callbackUrl).searchParams.get("state") ?? ""
    let id = ""
    for (const [k, f] of this.flows) if (st && f.state === st) id = k
    return this.resume(id, callbackUrl)
  }

  /**
   * A node:http listener that serves one tool call to a browser user, pausing for authorization:
   * call runs; if it pauses, the browser is redirected to the AS; when the AS redirects back to this
   * same URL (use it as PauseAuthorizer.redirect), the original call resumes and its text is written.
   */
  webHandler(call: (req: IncomingMessage) => Promise<Obj>): (req: IncomingMessage, res: ServerResponse) => void {
    return (req, res) => {
      void (async () => {
        const u = new URL(req.url ?? "/", `http://${req.headers.host}`)
        const q = u.searchParams
        try {
          const r = q.get("state") && (q.has("code") || q.has("error")) ? await this.resumeCallback(u.toString()) : await call(req)
          res.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" }).end(text(r))
        } catch (e) {
          const p = pendingOf(e)
          if (p) return void res.writeHead(302, { Location: p.url }).end()
          res.writeHead(502, { "Content-Type": "text/plain; charset=utf-8" }).end(String((e as Error)?.message ?? e) + "\n")
        }
      })()
    }
  }

  private async complete(f: AuthFlow, callbackUrl: string): Promise<void> {
    const q = new URL(callbackUrl).searchParams
    // RFC 9207 §2.4 table (spec: Authorization Response Validation) — before anything else, error responses included.
    if (q.has("iss")) {
      if (q.get("iss") !== f.issuer) throw new Error("authorization response iss mismatch (possible mix-up attack)") // no normalization
    } else if (f.issSupported) throw new Error("authorization response missing iss")
    if ((q.get("state") ?? "") !== f.state) throw new Error("authorization response state mismatch")
    const err = q.get("error")
    if (err) throw new Error(`authorization denied: ${err}`)
    const t = await this.tokenRequest(f.tokenEndpoint, new URLSearchParams({
      grant_type: "authorization_code", code: q.get("code") ?? "", redirect_uri: f.redirect,
      client_id: f.clientId, code_verifier: f.verifier, resource: f.resource,
    }), f.issuer, f.resource, f.clientId, f.scopes)
    await this.store.save(f.resource, t)
  }

  private async tokenRequest(ep: string, form: URLSearchParams, issuer: string, resource: string, clientId: string, scopes: string[]): Promise<Token> {
    let method = this.tokenAuthMethod
    if (clientId === this.clientMetadataUrl || !this.clientSecret) method = "none" // CIMD + secretless clients are public
    else if (!method) method = "client_secret_basic"
    if (method === "client_secret_post") form.set("client_secret", this.clientSecret)
    const headers: Record<string, string> = {
      "Content-Type": "application/x-www-form-urlencoded",
      Accept: "application/json", // GitHub answers form-encoded without it
    }
    if (method === "client_secret_basic") {
      form.delete("client_id") // RFC 6749 §2.3.1: authenticate with one method only
      headers.Authorization = "Basic " + Buffer.from(formEscape(clientId) + ":" + formEscape(this.clientSecret)).toString("base64")
    }
    const resp = await this.http({ method: "POST", url: ep, headers, body: form.toString() })
    let tr: Obj | undefined
    try {
      tr = JSON.parse(resp.body)
    } catch {
      if ((resp.headers["content-type"] ?? "").startsWith("application/x-www-form-urlencoded")) {
        // RFC 6749 §5.1 mandates JSON, but some ASes (GitHub) still default to form encoding.
        tr = Object.fromEntries(new URLSearchParams(resp.body))
      }
    }
    if (!tr || resp.status !== 200 || !tr.access_token) {
      throw new Error(`token endpoint: HTTP ${resp.status} ${typeof tr?.error === "string" ? tr.error : ""}`) // never include the body
    }
    const t = new Token({ accessToken: String(tr.access_token), refreshToken: String(tr.refresh_token ?? ""), issuer, resource, clientId, scopes })
    if (typeof tr.scope === "string" && tr.scope) t.scopes = tr.scope.split(/\s+/).filter(Boolean)
    const ei = Number(tr.expires_in)
    if (ei > 0) t.expiry = Date.now() + ei * 1000
    return t
  }

  /** PRM: header URL if present, else path-inserted well-known, else root (spec: PRM Discovery Requirements). */
  async fetchPRM(resource: string, hinted: string): Promise<Obj> {
    let cands = [hinted]
    if (!hinted) {
      const u = new URL(resource)
      const root = `${u.protocol}//${u.host}/.well-known/oauth-protected-resource`
      const p = u.pathname.replace(/\/$/, "")
      cands = p ? [root + p, root] : [root]
    }
    let last: unknown
    for (const c of cands) {
      let m: Obj
      try {
        m = await getJSON(this.http, c)
      } catch (e) {
        last = e
        continue
      }
      if (m.resource !== resource) throw new Error(`PRM resource ${m.resource} does not match ${resource}`) // RFC 9728 §3.3
      return m
    }
    throw new Error(`protected resource metadata: ${(last as Error)?.message ?? last}`)
  }
}

function expired(t: Token): boolean {
  return t.expiry > 0 && Date.now() > t.expiry - 10_000
}

/** RFC 8414 then OIDC endpoints in the spec's order, with an issuer equality check. */
export async function discoverAS(httpFn: HttpFn, issuer: string): Promise<Obj> {
  const u = new URL(issuer)
  const base = `${u.protocol}//${u.host}`
  const p = u.pathname.replace(/\/$/, "")
  const cands = p
    ? [base + "/.well-known/oauth-authorization-server" + p, base + "/.well-known/openid-configuration" + p, base + p + "/.well-known/openid-configuration"]
    : [base + "/.well-known/oauth-authorization-server", base + "/.well-known/openid-configuration"]
  for (const c of cands) {
    let m: Obj
    try {
      m = await getJSON(httpFn, c)
    } catch {
      continue
    }
    if (m.issuer !== issuer) throw new Error(`AS metadata issuer ${m.issuer} != ${issuer}`)
    return m
  }
  throw new Error(`no authorization server metadata for ${issuer}`)
}

/** Parses `Bearer k="v", k2=v2` (RFC 6750 §3 / RFC 9110 auth-param). */
export function parseChallenge(h: string): Record<string, string> {
  const out: Record<string, string> = {}
  h = h.trim()
  if (h.startsWith("Bearer")) h = h.slice(6)
  h = h.trim()
  while (h) {
    const eq = h.indexOf("=")
    if (eq < 0) break
    const k = h.slice(0, eq).trim().toLowerCase()
    h = h.slice(eq + 1).trim()
    let v: string
    if (h.startsWith('"')) {
      let end = 1
      while (end < h.length && h[end] !== '"') {
        if (h[end] === "\\") end++
        end++
      }
      v = h.slice(1, Math.min(end, h.length)).replaceAll('\\"', '"')
      h = h.slice(Math.min(end + 1, h.length))
    } else {
      let end = h.indexOf(",")
      if (end < 0) end = h.length
      v = h.slice(0, end).trim()
      h = h.slice(end)
    }
    out[k] = v
    h = h.trim()
    if (h.startsWith(",")) h = h.slice(1)
    h = h.trim()
  }
  return out
}

function strings(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : []
}

function formEscape(s: string): string {
  return new URLSearchParams({ x: s }).toString().slice(2)
}

export function randStr(n: number): string {
  return randomBytes(n).toString("base64url")
}
