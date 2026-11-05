/**
 * McpDevAuthServer — DEVELOPMENT AND TESTS ONLY. NEVER USE IN PRODUCTION (SPEC §7C.2).
 *
 * An in-process OAuth 2.1 authorization server that auto-approves every request as "dev-user"
 * (no login, no consent): RFC 8414 metadata, JWKS, authorization code + PKCE S256, resource
 * indicators (RFC 8707), `iss` in the response (RFC 9207), refresh-token rotation, pre-registered
 * clients and Client ID Metadata Documents. No dynamic client registration. Plain HTTP on
 * 127.0.0.1, so a toolnexus client needs `auth.allowHttp: true` against it.
 *
 *   const as = await startMcpDevAuthServer({ clients: { "my-app": ["http://127.0.0.1/callback"] } })
 *   const auth = new McpServerAuth({ issuer: as.issuer, scopes: { "*": ["mcp"] } })
 *   ...
 *   await as.close()
 */
import { createHash, generateKeyPairSync, randomBytes, sign as cryptoSign } from "node:crypto"
import http from "node:http"
import type { AddressInfo } from "node:net"
import { getJson } from "./mcpheader.js"

export interface McpDevAuthOptions {
  /** Pre-registered client_id → allowed redirect URIs (a loopback redirect matches on any port). */
  clients?: Record<string, string[]>
  /** Confidential clients: client_id → secret (client_secret_basic or client_secret_post). */
  secrets?: Record<string, string>
  /** Access-token lifetime in ms. Default 1 h. */
  accessTtl?: number
  /** Stop advertising (and accepting) Client ID Metadata Documents. */
  noCimd?: boolean
  /** Answer invalid_target when a refresh carries `resource` (an Entra-like provider, for client tests). */
  rejectRefreshResource?: boolean
  /** Fetches Client ID Metadata Documents. Default: global fetch with a 10 s timeout. */
  fetch?: typeof fetch
}

/** The running dev authorization server. DEV ONLY. */
export interface McpDevAuthServer {
  /** http://127.0.0.1:<port> */
  readonly issuer: string
  /** Signs an access token with the server's key (tests: forge expired / wrong-audience tokens). */
  mint(claims: Record<string, unknown>): string
  /** How many /authorize requests were served. */
  authorizations(): number
  close(): Promise<void>
}

interface Grant {
  clientId: string
  redirect: string
  challenge: string
  resource: string
  scope: string
}

const rand = (n: number) => randomBytes(n).toString("hex")

function isLoopbackHost(h: string): boolean {
  const x = h.replace(/^\[|\]$/g, "")
  return /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(x) || x === "::1" || x === "0:0:0:0:0:0:0:1"
}

/** Starts the dev AS on 127.0.0.1:<random>. DEVELOPMENT AND TESTS ONLY. */
export async function startMcpDevAuthServer(opts: McpDevAuthOptions = {}): Promise<McpDevAuthServer> {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 })
  const jwk = publicKey.export({ format: "jwk" }) as { n: string; e: string }
  const fetchImpl: typeof fetch = opts.fetch ?? ((u, init) => fetch(u, { ...init, signal: AbortSignal.timeout(10_000) }))
  const codes = new Map<string, Grant>()
  const rts = new Map<string, Grant>()
  let auths = 0
  let issuer = ""

  const mint = (claims: Record<string, unknown>): string => {
    const enc = (v: unknown) => Buffer.from(JSON.stringify(v)).toString("base64url")
    const input = enc({ alg: "RS256", typ: "JWT", kid: "dev" }) + "." + enc(claims)
    return input + "." + cryptoSign("RSA-SHA256", Buffer.from(input), privateKey).toString("base64url")
  }

  const json = (res: http.ServerResponse, status: number, v: unknown) => {
    res.writeHead(status, { "Content-Type": "application/json" })
    res.end(JSON.stringify(v) + "\n")
  }

  async function redirectAllowed(cid: string, redirect: string): Promise<boolean> {
    let allowed = opts.clients?.[cid] ?? []
    if (cid.startsWith("https://") || cid.startsWith("http://")) {
      if (opts.noCimd) return false
      let doc: any
      try {
        doc = await getJson(fetchImpl, cid)
      } catch {
        return false
      }
      if (!doc || doc.client_id !== cid || typeof doc.client_name !== "string" || doc.client_name === "") return false
      allowed = Array.isArray(doc.redirect_uris) ? doc.redirect_uris.filter((x: unknown) => typeof x === "string") : []
    }
    let ru: URL
    try {
      ru = new URL(redirect)
    } catch {
      return false
    }
    for (const a of allowed) {
      if (a === redirect) return true
      let au: URL
      try {
        au = new URL(a)
      } catch {
        continue
      }
      if (isLoopbackHost(ru.hostname) && au.hostname === ru.hostname && au.protocol === ru.protocol && au.pathname === ru.pathname) {
        return true
      }
    }
    return false
  }

  async function authorize(req: http.IncomingMessage, res: http.ServerResponse, q: URLSearchParams) {
    auths++
    const cid = q.get("client_id") ?? ""
    const redirect = q.get("redirect_uri") ?? ""
    if (!(await redirectAllowed(cid, redirect))) {
      // never redirect to an unverified URI
      res.writeHead(400, { "Content-Type": "text/plain; charset=utf-8" }).end("invalid_client or redirect_uri\n")
      return
    }
    const back = (v: Record<string, string>) => {
      v.state = q.get("state") ?? ""
      v.iss = issuer
      const sp = new URLSearchParams()
      for (const k of Object.keys(v).sort()) sp.set(k, v[k]!)
      res.writeHead(302, { Location: redirect + (redirect.includes("?") ? "&" : "?") + sp.toString() }).end()
    }
    if (q.get("response_type") !== "code" || q.get("code_challenge_method") !== "S256" || !q.get("code_challenge")) {
      return back({ error: "invalid_request" })
    }
    if (!q.get("resource")) return back({ error: "invalid_target" })
    const code = rand(16)
    codes.set(code, { clientId: cid, redirect, challenge: q.get("code_challenge")!, resource: q.get("resource")!, scope: q.get("scope") ?? "" })
    back({ code })
  }

  async function token(req: http.IncomingMessage, res: http.ServerResponse) {
    const chunks: Buffer[] = []
    for await (const c of req) chunks.push(c as Buffer)
    const f = new URLSearchParams(Buffer.concat(chunks).toString("utf8"))
    const g0 = (k: string) => f.get(k) ?? ""
    const bad = (e: string) => json(res, 400, { error: e })
    const secrets = opts.secrets ?? {}
    const authz = req.headers.authorization ?? ""
    if (authz.startsWith("Basic ")) {
      const raw = Buffer.from(authz.slice(6), "base64").toString("utf8")
      const i = raw.indexOf(":")
      if (i >= 0) {
        let id = raw.slice(0, i)
        let sec = raw.slice(i + 1)
        try {
          id = decodeURIComponent(id.replace(/\+/g, " "))
          sec = decodeURIComponent(sec.replace(/\+/g, " "))
        } catch {
          // keep raw
        }
        if ((g0("client_id") !== "" && g0("client_id") !== id) || !secrets[id] || secrets[id] !== sec) {
          return json(res, 401, { error: "invalid_client" })
        }
        f.set("client_id", id)
      }
    } else {
      const want = secrets[g0("client_id")]
      if (want && g0("client_secret") !== want) return json(res, 401, { error: "invalid_client" })
    }
    let g: Grant | undefined
    switch (g0("grant_type")) {
      case "authorization_code": {
        g = codes.get(g0("code"))
        if (!g) return bad("invalid_grant")
        codes.delete(g0("code"))
        const sum = createHash("sha256").update(g0("code_verifier")).digest("base64url")
        if (sum !== g.challenge || g0("client_id") !== g.clientId || g0("redirect_uri") !== g.redirect || g0("resource") !== g.resource) {
          return bad("invalid_grant")
        }
        break
      }
      case "refresh_token": {
        g = rts.get(g0("refresh_token"))
        if (!g || g0("client_id") !== g.clientId) return bad("invalid_grant")
        rts.delete(g0("refresh_token"))
        const r = g0("resource")
        if (r !== "" && (r !== g.resource || opts.rejectRefreshResource)) return bad("invalid_target")
        break
      }
      default:
        return bad("unsupported_grant_type")
    }
    const ttl = opts.accessTtl && opts.accessTtl > 0 ? opts.accessTtl : 3600_000
    const rt = rand(24)
    rts.set(rt, g)
    const now = Math.floor(Date.now() / 1000)
    const at = mint({
      iss: issuer,
      sub: "dev-user",
      aud: g.resource,
      scope: g.scope,
      client_id: g.clientId,
      jti: rand(8),
      exp: now + Math.floor(ttl / 1000),
      iat: now,
    })
    json(res, 200, { access_token: at, token_type: "Bearer", expires_in: Math.floor(ttl / 1000), refresh_token: rt, scope: g.scope })
  }

  const server = http.createServer((req, res) => {
    const u = new URL(req.url ?? "/", "http://127.0.0.1")
    const done = (p: Promise<void> | void) =>
      Promise.resolve(p).catch(() => {
        if (!res.headersSent) res.writeHead(500).end()
      })
    switch (u.pathname) {
      case "/.well-known/oauth-authorization-server":
        return json(res, 200, {
          issuer,
          authorization_endpoint: issuer + "/authorize",
          token_endpoint: issuer + "/token",
          jwks_uri: issuer + "/jwks",
          response_types_supported: ["code"],
          grant_types_supported: ["authorization_code", "refresh_token"],
          code_challenge_methods_supported: ["S256"],
          token_endpoint_auth_methods_supported: ["none", "client_secret_basic", "client_secret_post"],
          authorization_response_iss_parameter_supported: true,
          client_id_metadata_document_supported: !opts.noCimd,
        })
      case "/jwks":
        return json(res, 200, { keys: [{ kty: "RSA", kid: "dev", alg: "RS256", use: "sig", n: jwk.n, e: jwk.e }] })
      case "/authorize":
        return void done(authorize(req, res, u.searchParams))
      case "/token":
        return void done(token(req, res))
      default:
        res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" }).end("404 page not found\n")
    }
  })
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject)
    server.listen(0, "127.0.0.1", () => resolve())
  })
  issuer = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  return {
    issuer,
    mint,
    authorizations: () => auths,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections()
        server.close(() => resolve())
      }),
  }
}
