// Server-side authorization: the MCP server as an OAuth 2.1 protected resource
// (MCP 2026-07-28 basic/authorization). One object, set server.auth, done.
import { createPublicKey, verify as cryptoVerify, type KeyObject } from "node:crypto"
import type { IncomingMessage, ServerResponse } from "node:http"
import { defaultHttp, getJSON, Mutex, type HttpFn, type Obj } from "./protocol.ts"
import { discoverAS } from "./protocol.ts"

/** The authenticated caller, available to tool handlers as ctx.principal. */
export interface Principal {
  subject: string
  scopes: string[]
  claims?: Obj
}

export interface ServerAuthOptions {
  issuer: string // the authorization server's issuer identifier
  audience?: string // canonical resource URI; "" = derived from the request (scheme://host/mcp)
  scopes?: Record<string, string[]> // "*" = every request (and scopes_supported); tool name = that tool
  /** Replaces the default JWT/JWKS verifier (opaque tokens, introspection, ...). Throw to reject. */
  verify?: (token: string) => Principal | Promise<Principal>
  http?: HttpFn // for AS metadata + JWKS fetches
}

export const PRM_PATH = "/.well-known/oauth-protected-resource"

export class ServerAuth {
  issuer: string
  audience: string
  scopes: Record<string, string[]>
  verify?: (token: string) => Principal | Promise<Principal>
  http: HttpFn
  private keys?: Map<string, KeyObject> // JWKS cache by kid
  private fetched = 0 // last JWKS fetch: unknown kids refetch at most every 30s
  private mu = new Mutex()

  constructor(o: ServerAuthOptions) {
    this.issuer = o.issuer
    this.audience = o.audience ?? ""
    this.scopes = o.scopes ?? {}
    this.verify = o.verify
    this.http = o.http ?? defaultHttp
  }

  private base(req: IncomingMessage): string {
    const scheme = (req.socket as { encrypted?: boolean }).encrypted ? "https" : "http"
    return `${scheme}://${req.headers.host}`
  }

  private aud(req: IncomingMessage): string {
    return this.audience || this.base(req) + "/mcp"
  }

  /** Answers RFC 9728 PRM at both the path-inserted and root well-known URIs. */
  serveMetadata(req: IncomingMessage, res: ServerResponse, pathname: string): boolean {
    if (pathname !== PRM_PATH && pathname !== PRM_PATH + "/mcp") return false
    res.writeHead(200, { "Content-Type": "application/json" })
    res.end(JSON.stringify({
      resource: this.aud(req), authorization_servers: [this.issuer],
      scopes_supported: this.scopes["*"] ?? null, bearer_methods_supported: ["header"],
    }) + "\n")
    return true
  }

  /** Authenticates the request; on failure writes 401/403 and returns undefined. */
  async check(req: IncomingMessage, res: ServerResponse, method: string, p: Obj): Promise<Principal | undefined> {
    const challenge = (status: number, extra: string, scopes: string[] | undefined) => {
      let v = `Bearer resource_metadata="${this.base(req)}${PRM_PATH}/mcp"`
      if (scopes?.length) v += `, scope="${scopes.join(" ")}"`
      res.writeHead(status, { "WWW-Authenticate": v + extra, "Content-Type": "application/json" })
      res.end(`{"error":"unauthorized"}`)
    }
    const h = req.headers.authorization ?? ""
    const tok = h.startsWith("Bearer ") ? h.slice(7) : ""
    if (!tok) {
      challenge(401, "", this.scopes["*"])
      return undefined
    }
    let pr: Principal
    try {
      pr = this.verify ? await this.verify(tok) : await this.verifyJWT(tok, this.aud(req))
    } catch {
      // never echo the token or the detailed reason
      challenge(401, `, error="invalid_token"`, this.scopes["*"])
      return undefined
    }
    const need = [...(this.scopes["*"] ?? [])]
    if (method === "tools/call") need.push(...(this.scopes[String(p.name ?? "")] ?? []))
    if (need.some((s) => !pr.scopes.includes(s))) {
      challenge(403, `, error="insufficient_scope"`, need)
      return undefined
    }
    return pr
  }

  // --- default verifier: RS256 JWT, keys from the issuer's discovered jwks_uri ---

  async verifyJWT(tok: string, aud: string): Promise<Principal> {
    const parts = tok.split(".")
    if (parts.length !== 3) throw new Error("malformed jwt")
    const hdr = decodeSeg(parts[0])
    const claims = decodeSeg(parts[1])
    if (hdr.alg !== "RS256") throw new Error("bad jwt header/claims")
    const key = await this.key(String(hdr.kid ?? ""))
    const sig = Buffer.from(parts[2], "base64url")
    if (sig.toString("base64url") !== parts[2]) throw new Error("bad signature encoding")
    if (!cryptoVerify("sha256", Buffer.from(parts[0] + "." + parts[1]), key, sig)) throw new Error("bad signature")
    if (claims.iss !== this.issuer) throw new Error("wrong issuer")
    const a = claims.aud
    if (!(a === aud || (Array.isArray(a) && a.includes(aud)))) throw new Error("wrong audience")
    if (typeof claims.exp !== "number" || Math.trunc(claims.exp) <= Math.floor(Date.now() / 1000)) throw new Error("expired")
    const sc = typeof claims.scope === "string" ? claims.scope : ""
    return { subject: typeof claims.sub === "string" ? claims.sub : "", scopes: sc.split(/\s+/).filter(Boolean), claims }
  }

  private key(kid: string): Promise<KeyObject> {
    return this.mu.lock(async () => {
      const k = this.keys?.get(kid)
      if (k) return k
      if (this.keys && Date.now() - this.fetched < 30_000) throw new Error("unknown kid") // garbage kids must not hammer the AS
      const md = await discoverAS(this.http, this.issuer) // same RFC 8414 / OIDC order the client uses
      const jwks = await getJSON(this.http, String(md.jwks_uri ?? ""))
      this.keys = new Map()
      this.fetched = Date.now()
      for (const j of Array.isArray(jwks.keys) ? jwks.keys : []) {
        if (j?.kty !== "RSA") continue
        try {
          this.keys.set(String(j.kid ?? ""), createPublicKey({ key: { kty: "RSA", n: j.n, e: j.e }, format: "jwk" }))
        } catch {
          /* skip malformed key */
        }
      }
      const found = this.keys.get(kid)
      if (!found) throw new Error("unknown kid")
      return found
    })
  }
}

export function decodeSeg(s: string): Obj {
  const v = JSON.parse(Buffer.from(s, "base64url").toString("utf8"))
  if (!v || typeof v !== "object") throw new Error("bad segment")
  return v
}
