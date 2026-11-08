// DevAuthServer — DEV/TEST ONLY. NEVER USE IN PRODUCTION.
// A one-line OAuth 2.1 authorization server that auto-approves every request as "dev-user"
// (no login, no consent screen). It exists so local dev and tests can exercise the real
// MCP auth flow: RFC 8414 metadata, JWKS, auth code + PKCE S256, resource (RFC 8707),
// iss in the response (RFC 9207), refresh-token rotation, pre-registered + CIMD clients.
//
//   const as = await DevAuthServer.start()   // https://127.0.0.1:<port>/dev
//   server.auth = new ServerAuth({ issuer: as.issuer, scopes: ... })
import { createHash, generateKeyPairSync, sign, type KeyObject } from "node:crypto"
import https from "node:https"
import type { IncomingMessage, ServerResponse } from "node:http"
import { isIP } from "node:net"
import { closeServer } from "./server.ts"
import { randStr } from "./auth.ts"
import { browse, defaultHttp, getJSON, trustDevCert, type HttpFn, type Obj } from "./protocol.ts"

interface DevCode { clientId: string; redirect: string; challenge: string; resource: string; scope: string }

export class DevAuthServer {
  issuer = ""
  /** TLS certificate (PEM) of this server; trusted by defaultHttp in this process. */
  certPem = ""
  clients: Record<string, string[]> = {} // pre-registered client_id -> redirect URIs
  secrets: Record<string, string> = {} // confidential clients: client_id -> secret (basic or post)
  accessTtlSec = 3600
  fetchHttp: HttpFn = defaultHttp // fetches CIMD documents
  // Test knobs: misbehave on purpose.
  issOverride = "" // non-empty: send this iss instead of the real one
  noCimd = false // don't advertise client_id_metadata_document_supported
  rejectRefreshResource = false // Entra-like: invalid_target if a refresh_token grant carries resource
  lastTokenForm = new URLSearchParams()
  lastAuthorizeQuery = new URLSearchParams()
  /** Extra JSON documents served at https://127.0.0.1:<port>/docs/<name> (tests: CIMD client documents). */
  documents: Record<string, Obj> = {}
  // Counters.
  authorizations = 0
  refreshes = 0

  private key!: KeyObject
  private jwk!: Obj
  private srv!: https.Server
  private codes = new Map<string, DevCode>()
  private rts = new Map<string, DevCode>()

  /** URL of a document served by this server (see documents). */
  documentUrl(name: string): string {
    return this.issuer.replace(/\/dev$/, "/docs/" + name)
  }

  /** Starts a TLS dev AS on 127.0.0.1 with issuer https://127.0.0.1:<port>/dev. */
  static async start(): Promise<DevAuthServer> {
    const d = new DevAuthServer()
    const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 })
    d.key = privateKey
    d.jwk = publicKey.export({ format: "jwk" })
    d.certPem = selfSignedCert(privateKey, publicKey)
    d.srv = https.createServer({ key: privateKey.export({ type: "pkcs8", format: "pem" }), cert: d.certPem }, (q, s) => void d.serve(q, s))
    await new Promise<void>((r) => d.srv.listen(0, "127.0.0.1", () => r()))
    d.issuer = `https://127.0.0.1:${(d.srv.address() as { port: number }).port}/dev`
    trustDevCert(d.certPem) // dev only: default clients in this process now trust it
    return d
  }

  close(): Promise<void> {
    return closeServer(this.srv)
  }

  stats(): { authorizations: number; refreshes: number } {
    return { authorizations: this.authorizations, refreshes: this.refreshes }
  }

  /** A headless "browser" for dev/tests: visits an authorization URL and follows redirects back to the callback. */
  approve = async (url: string): Promise<void> => {
    await browse(url)
  }

  /** Visits an authorization URL and returns where the AS redirects to, without following it. */
  async visit(url: string): Promise<string> {
    const r = await defaultHttp({ method: "GET", url })
    if (r.status !== 302) throw new Error(`authorize: HTTP ${r.status}`)
    return r.headers["location"] ?? ""
  }

  /** Signs an access token directly (tests: forge wrong audience / expired tokens). */
  mint(claims: Obj): string {
    const seg = (v: Obj) => Buffer.from(JSON.stringify(v)).toString("base64url")
    const input = seg({ alg: "RS256", typ: "JWT", kid: "dev" }) + "." + seg(claims)
    return input + "." + sign("sha256", Buffer.from(input), this.key).toString("base64url")
  }

  private async serve(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const u = new URL(req.url ?? "/", this.issuer)
    const js = (status: number, v: unknown): void => void res.writeHead(status, { "Content-Type": "application/json" }).end(JSON.stringify(v) + "\n")
    const doc = u.pathname.startsWith("/docs/") ? this.documents[u.pathname.slice(6)] : undefined
    if (doc) return js(200, doc)
    switch (u.pathname) {
      case "/.well-known/oauth-authorization-server/dev":
        return js(200, {
          issuer: this.issuer, authorization_endpoint: this.issuer + "/authorize", token_endpoint: this.issuer + "/token",
          jwks_uri: this.issuer + "/jwks", response_types_supported: ["code"], grant_types_supported: ["authorization_code", "refresh_token"],
          code_challenge_methods_supported: ["S256"], token_endpoint_auth_methods_supported: ["none", "client_secret_basic", "client_secret_post"],
          authorization_response_iss_parameter_supported: true, client_id_metadata_document_supported: !this.noCimd,
        })
      case "/dev/jwks":
        return js(200, { keys: [{ kty: "RSA", kid: "dev", alg: "RS256", use: "sig", n: this.jwk.n, e: this.jwk.e }] })
      case "/dev/authorize":
        return this.authorize(u.searchParams, res)
      case "/dev/token": {
        const chunks: Buffer[] = []
        for await (const c of req) chunks.push(c as Buffer)
        return this.token(req, new URLSearchParams(Buffer.concat(chunks).toString("utf8")), js)
      }
    }
    res.writeHead(404).end("404 page not found\n")
  }

  private async authorize(q: URLSearchParams, res: ServerResponse): Promise<void> {
    this.authorizations++
    this.lastAuthorizeQuery = q
    const cid = q.get("client_id") ?? ""
    const redirect = q.get("redirect_uri") ?? ""
    if (!(await this.redirectAllowed(cid, redirect))) {
      return void res.writeHead(400).end("invalid_client or redirect_uri\n") // never redirect to an unverified URI
    }
    const back = (p: Record<string, string>): void => void res.writeHead(302, { Location: redirect + "?" + new URLSearchParams(p).toString() }).end()
    if (q.get("response_type") !== "code" || q.get("code_challenge_method") !== "S256" || !q.get("code_challenge"))
      return back({ error: "invalid_request", state: q.get("state") ?? "", iss: this.issuer })
    if (!q.get("resource")) return back({ error: "invalid_target", state: q.get("state") ?? "", iss: this.issuer })
    const code = randStr(16)
    this.codes.set(code, { clientId: cid, redirect, challenge: q.get("code_challenge") ?? "", resource: q.get("resource") ?? "", scope: q.get("scope") ?? "" })
    back({ code, state: q.get("state") ?? "", iss: this.issOverride || this.issuer })
  }

  /** Exact match, except loopback IPs where the port is ignored (RFC 8252 §7.3). */
  private async redirectAllowed(cid: string, redirect: string): Promise<boolean> {
    let allowed = this.clients[cid] ?? []
    if (cid.startsWith("https://")) {
      // Client ID Metadata Document
      try {
        const doc = await getJSON(this.fetchHttp, cid)
        if (doc.client_id !== cid || !doc.client_name) return false
        allowed = Array.isArray(doc.redirect_uris) ? doc.redirect_uris : []
      } catch {
        return false
      }
    }
    let ru: URL
    try {
      ru = new URL(redirect)
    } catch {
      return false
    }
    const loopback = (h: string) => (isIP(h) === 4 && h.startsWith("127.")) || h === "[::1]"
    for (const a of allowed) {
      if (a === redirect) return true
      try {
        const au = new URL(a)
        if (loopback(ru.hostname) && au.hostname === ru.hostname && au.protocol === ru.protocol && au.pathname === ru.pathname) return true
      } catch {
        /* skip */
      }
    }
    return false
  }

  private token(req: IncomingMessage, f: URLSearchParams, js: (s: number, v: unknown) => void): void {
    const bad = (e: string) => js(400, { error: e })
    this.lastTokenForm = new URLSearchParams(f)
    const auth = req.headers.authorization ?? ""
    if (auth.startsWith("Basic ")) {
      const dec = Buffer.from(auth.slice(6), "base64").toString("utf8")
      const i = dec.indexOf(":")
      const un = (s: string) => decodeURIComponent(s.replaceAll("+", " "))
      const id = un(dec.slice(0, i))
      const sec = un(dec.slice(i + 1))
      if ((f.get("client_id") && f.get("client_id") !== id) || !this.secrets[id] || this.secrets[id] !== sec) return js(401, { error: "invalid_client" })
      f.set("client_id", id)
    } else {
      const want = this.secrets[f.get("client_id") ?? ""]
      if (want && f.get("client_secret") !== want) return js(401, { error: "invalid_client" })
    }
    let c: DevCode | undefined
    switch (f.get("grant_type")) {
      case "authorization_code": {
        const code = f.get("code") ?? ""
        c = this.codes.get(code)
        if (!c) return bad("invalid_grant")
        this.codes.delete(code) // single use
        if (createHash("sha256").update(f.get("code_verifier") ?? "").digest("base64url") !== c.challenge) return bad("invalid_grant") // PKCE
        if (f.get("client_id") !== c.clientId || f.get("redirect_uri") !== c.redirect || f.get("resource") !== c.resource) return bad("invalid_grant")
        break
      }
      case "refresh_token": {
        const rt = f.get("refresh_token") ?? ""
        c = this.rts.get(rt)
        if (!c || f.get("client_id") !== c.clientId) return bad("invalid_grant")
        this.rts.delete(rt) // rotation (required for public clients)
        const res = f.get("resource")
        if (res && (res !== c.resource || this.rejectRefreshResource)) return bad("invalid_target")
        this.refreshes++
        break
      }
      default:
        return bad("unsupported_grant_type")
    }
    const rt = randStr(24)
    this.rts.set(rt, c)
    const now = Math.floor(Date.now() / 1000)
    const at = this.mint({ iss: this.issuer, sub: "dev-user", aud: c.resource, scope: c.scope, client_id: c.clientId, jti: randStr(8), exp: now + this.accessTtlSec, iat: now })
    js(200, { access_token: at, token_type: "Bearer", expires_in: this.accessTtlSec, refresh_token: rt, scope: c.scope })
  }
}

// --- a self-signed X.509 certificate in ~40 lines of DER (node:crypto cannot create certificates) ---

function der(tag: number, ...parts: Buffer[]): Buffer {
  const body = Buffer.concat(parts)
  const n = body.length
  const len = n < 0x80 ? Buffer.from([n]) : n < 0x100 ? Buffer.from([0x81, n]) : Buffer.from([0x82, n >> 8, n & 0xff])
  return Buffer.concat([Buffer.from([tag]), len, body])
}
const seq = (...p: Buffer[]) => der(0x30, ...p)
const oid = (hex: string) => der(0x06, Buffer.from(hex, "hex"))
const utc = (d: Date) => der(0x17, Buffer.from(d.toISOString().replace(/[-:T]/g, "").slice(2, 14) + "Z"))

function selfSignedCert(priv: KeyObject, pub: KeyObject): string {
  const sha256WithRSA = seq(oid("2a864886f70d01010b"), der(0x05))
  const name = seq(der(0x31, seq(oid("550403"), der(0x0c, Buffer.from("toolnexus dev auth server " + randStr(9))))))
  const now = Date.now()
  const san = seq(der(0x87, Buffer.from([127, 0, 0, 1])), der(0x82, Buffer.from("localhost")))
  const exts = der(0xa3, seq(
    seq(oid("551d13"), der(0x01, Buffer.from([0xff])), der(0x04, seq(der(0x01, Buffer.from([0xff]))))), // basicConstraints CA (critical)
    seq(oid("551d11"), der(0x04, san)), // subjectAltName 127.0.0.1, localhost
  ))
  const tbs = seq(
    der(0xa0, der(0x02, Buffer.from([2]))), // v3
    der(0x02, Buffer.concat([Buffer.from([0x01]), Buffer.from(randStr(12), "utf8").subarray(0, 15)])),
    sha256WithRSA, name, seq(utc(new Date(now - 3600_000)), utc(new Date(now + 24 * 3600_000))), name,
    pub.export({ type: "spki", format: "der" }), exts,
  )
  const cert = seq(tbs, sha256WithRSA, der(0x03, Buffer.from([0]), sign("sha256", tbs, priv)))
  return `-----BEGIN CERTIFICATE-----\n${cert.toString("base64").match(/.{1,64}/g)!.join("\n")}\n-----END CERTIFICATE-----\n`
}
