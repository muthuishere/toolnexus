/**
 * MCP server authorization (SPEC §2A.2, §7C.2): an `mcpHandler` as an OAuth 2.1 protected
 * resource. `McpServerAuth` is a plain helper producing the handler's `authenticate` hook and the
 * RFC 9728 metadata listener; the host mounts both. Identity, tenants and token storage stay the
 * host's.
 *
 *   const auth = new McpServerAuth({ issuer: "https://login.example", scopes: { "*": ["mcp"] } })
 *   const mcp = toolkit.mcpHandler({ protocolVersions: ["2026-07-28"], authenticate: auth.authenticate })
 *   const meta = auth.metadata()  // mount at /.well-known/oauth-protected-resource[/mcp]
 */
import { createPublicKey, verify as cryptoVerify, type KeyObject } from "node:crypto"
import type { IncomingMessage, ServerResponse } from "node:http"
import { McpAuthError } from "./mcphandler.js"
import { decodeHeaderValue, getJson } from "./mcpheader.js"

/** The verified caller; under McpServerAuth, `identityFrom(ctx)` returns it. */
export interface McpPrincipal {
  subject: string
  scopes: string[]
  claims?: Record<string, unknown>
}

export interface McpServerAuthOptions {
  /** The authorization server's issuer (advertised in the PRM, and the JWT `iss` required). */
  issuer: string
  /** Canonical resource URI (PRM `resource`, JWT `aud`). Omit ⇒ `<scheme>://<Host><resourcePath>` per request. */
  audience?: string
  /** Where the handler is mounted. Default "/mcp". */
  resourcePath?: string
  /** "*" is required on every request (and advertised); a tool name adds scopes for that tools/call. */
  scopes?: Record<string, string[]>
  /** Replaces the default RS256/JWKS verifier (opaque tokens, introspection). Throw ⇒ invalid_token. */
  verify?: (req: IncomingMessage, token: string, audience: string) => Promise<McpPrincipal> | McpPrincipal
  /** Fetches AS metadata and JWKS. Default: global fetch with a 10 s timeout. */
  fetch?: typeof fetch
}

const PRM_PATH = "/.well-known/oauth-protected-resource"

function header(req: IncomingMessage, name: string): string {
  const v = req.headers[name.toLowerCase()]
  return Array.isArray(v) ? (v[0] ?? "") : (v ?? "")
}

function origin(req: IncomingMessage): string {
  const scheme = (req.socket as { encrypted?: boolean }).encrypted ? "https" : "http"
  return `${scheme}://${header(req, "Host")}`
}

/** Protects an `mcpHandler` with bearer tokens (SPEC §2A.2). */
export class McpServerAuth {
  readonly issuer: string
  readonly audience?: string
  readonly resourcePath: string
  readonly scopes: Record<string, string[]>
  private readonly verifyHook?: McpServerAuthOptions["verify"]
  private readonly fetchImpl: typeof fetch
  private keys?: Map<string, KeyObject>
  private fetched = 0

  constructor(opts: McpServerAuthOptions) {
    this.issuer = opts.issuer
    this.audience = opts.audience || undefined
    this.resourcePath = opts.resourcePath || "/mcp"
    this.scopes = opts.scopes ?? {}
    this.verifyHook = opts.verify
    this.fetchImpl = opts.fetch ?? ((u, init) => fetch(u, { ...init, signal: AbortSignal.timeout(10_000) }))
  }

  private audienceFor(req: IncomingMessage): string {
    return this.audience ?? origin(req) + this.resourcePath
  }

  /** The RFC 9728 metadata URL the challenge names. */
  metadataUrl(req: IncomingMessage): string {
    if (this.audience) {
      try {
        const u = new URL(this.audience)
        if (u.host) {
          const path = /^[^:]+:\/\/[^/?#]*\//.test(this.audience) ? u.pathname : ""
          return `${u.protocol}//${u.host}${PRM_PATH}${path}`
        }
      } catch {
        // fall through to the request-derived URL
      }
    }
    return origin(req) + PRM_PATH + this.resourcePath
  }

  /** Request listener serving the protected resource metadata at the two PRM paths; anything else 404. */
  metadata(): (req: IncomingMessage, res: ServerResponse) => void {
    return (req, res) => {
      const path = (req.url ?? "/").split("?")[0]
      if (req.method !== "GET" || (path !== PRM_PATH && path !== PRM_PATH + this.resourcePath)) {
        res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" }).end("404 page not found\n")
        return
      }
      res.writeHead(200, { "Content-Type": "application/json" })
      res.end(
        JSON.stringify({
          resource: this.audienceFor(req),
          authorization_servers: [this.issuer],
          scopes_supported: this.scopes["*"] ?? [],
          bearer_methods_supported: ["header"],
        }) + "\n",
      )
    }
  }

  private challenge(req: IncomingMessage, status: number, scopes: string[] | undefined, err: string): McpAuthError {
    let v = `Bearer resource_metadata="${this.metadataUrl(req)}"`
    if (scopes && scopes.length > 0) v += `, scope="${scopes.join(" ")}"`
    if (err) v += `, error="${err}"`
    return new McpAuthError(status, v, err)
  }

  /**
   * The `mcpHandler` `authenticate` hook (bound). Missing token ⇒ 401; invalid ⇒ 401
   * error="invalid_token"; a missing scope ⇒ 403 error="insufficient_scope" naming the full needed
   * set. Per-tool scopes come from the Mcp-Method / Mcp-Name headers (the 2026-07-28 wire).
   */
  readonly authenticate = async (req: IncomingMessage): Promise<McpPrincipal> => {
    const authz = header(req, "Authorization")
    const tok = authz.startsWith("Bearer ") ? authz.slice(7) : ""
    if (tok.trim() === "") throw this.challenge(req, 401, this.scopes["*"], "")
    let p: McpPrincipal
    try {
      const aud = this.audienceFor(req)
      p = this.verifyHook ? await this.verifyHook(req, tok, aud) : await this.verifyJwt(tok, aud)
    } catch {
      throw this.challenge(req, 401, this.scopes["*"], "invalid_token")
    }
    const need = [...(this.scopes["*"] ?? [])]
    if (header(req, "Mcp-Method") === "tools/call") {
      const [name, ok] = decodeHeaderValue(header(req, "Mcp-Name"))
      if (ok) for (const s of this.scopes[name] ?? []) if (!need.includes(s)) need.push(s)
    }
    const have = p.scopes ?? []
    if (need.some((s) => !have.includes(s))) throw this.challenge(req, 403, need, "insufficient_scope")
    return p
  }

  private async verifyJwt(tok: string, aud: string): Promise<McpPrincipal> {
    const parts = tok.split(".")
    if (parts.length !== 3) throw new Error("malformed jwt")
    const hdr = decodeSeg(parts[0]!)
    const claims = decodeSeg(parts[1]!)
    if (!hdr || !claims || hdr.alg !== "RS256") throw new Error("bad jwt header or claims")
    const key = await this.key(typeof hdr.kid === "string" ? hdr.kid : "")
    if (!/^[A-Za-z0-9_-]*$/.test(parts[2]!)) throw new Error("bad signature encoding")
    const sig = Buffer.from(parts[2]!, "base64url")
    if (!cryptoVerify("RSA-SHA256", Buffer.from(parts[0] + "." + parts[1]), key, sig)) throw new Error("bad signature")
    if (claims.iss !== this.issuer) throw new Error("wrong issuer")
    const a = claims.aud
    if (!(typeof a === "string" ? a === aud : Array.isArray(a) && a.includes(aud))) throw new Error("wrong audience")
    const exp = typeof claims.exp === "number" ? Math.trunc(claims.exp) : 0
    if (exp <= Math.floor(Date.now() / 1000)) throw new Error("expired")
    const scope = typeof claims.scope === "string" ? claims.scope : ""
    return {
      subject: typeof claims.sub === "string" ? claims.sub : "",
      scopes: scope.split(/\s+/).filter(Boolean),
      claims,
    }
  }

  /** The JWKS key for kid; an unknown kid refetches at most every 30 s. */
  private async key(kid: string): Promise<KeyObject> {
    const k = this.keys?.get(kid)
    if (k) return k
    if (this.keys && Date.now() - this.fetched < 30_000) throw new Error("unknown kid")
    const md = await discoverAs(this.fetchImpl, this.issuer)
    const jwks = await getJson(this.fetchImpl, String(md.jwks_uri ?? ""))
    const keys = new Map<string, KeyObject>()
    for (const j of Array.isArray(jwks?.keys) ? jwks.keys : []) {
      if (j?.kty !== "RSA" || typeof j.n !== "string" || typeof j.e !== "string") continue
      try {
        keys.set(String(j.kid ?? ""), createPublicKey({ key: { kty: "RSA", n: j.n, e: j.e }, format: "jwk" }))
      } catch {
        // skip a malformed key
      }
    }
    this.keys = keys
    this.fetched = Date.now()
    const got = keys.get(kid)
    if (!got) throw new Error("unknown kid")
    return got
  }
}

function decodeSeg(s: string): Record<string, any> | undefined {
  if (!/^[A-Za-z0-9_-]+$/.test(s)) return undefined
  try {
    const v = JSON.parse(Buffer.from(s, "base64url").toString("utf8"))
    return v && typeof v === "object" && !Array.isArray(v) ? v : undefined
  } catch {
    return undefined
  }
}

/** AS metadata in SPEC §2A.1 step 3 order; the metadata `issuer` must equal the issuer. */
async function discoverAs(f: typeof fetch, issuer: string): Promise<Record<string, unknown>> {
  const u = new URL(issuer)
  const org = `${u.protocol}//${u.host}`
  const path = (/^[^:]+:\/\/[^/?#]*\//.test(issuer) ? u.pathname : "").replace(/\/$/, "")
  const cands = [`${org}/.well-known/oauth-authorization-server${path}`, `${org}/.well-known/openid-configuration${path}`]
  if (path) cands.push(`${org}${path}/.well-known/openid-configuration`)
  for (const c of cands) {
    try {
      const md = await getJson(f, c)
      if (md && md.issuer === issuer) return md
    } catch {
      // next candidate
    }
  }
  throw new Error("authorization server metadata not found for " + issuer)
}
