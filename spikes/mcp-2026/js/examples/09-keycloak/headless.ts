// headlessLogin is TEST HARNESS, not something an app needs: it plays the person at the browser.
// It follows the authorization URL, submits Keycloak's login form (and a consent page if shown),
// stops at the 127.0.0.1 redirect and delivers it to the client's loopback listener.
import { defaultHttp, type HttpResponse } from "../../src/index.ts"

const loginForm = /<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"/
const anyForm = /<form[^>]*action="([^"]+)"/

const unescapeHtml = (s: string) => s.replaceAll("&amp;", "&").replaceAll("&quot;", '"').replaceAll("&#39;", "'").replaceAll("&lt;", "<").replaceAll("&gt;", ">")

/** A cookie-keeping "browser" that walks the Keycloak login; returns the loopback callback URL. */
export class KeycloakBrowser {
  forms = 0 // login forms submitted (SSO reuse => 0)
  consents = 0 // consent screens accepted
  callbacks: string[] = []
  private cookies = new Map<string, string>()
  private user: string
  private password: string
  constructor(user: string, password: string) {
    this.user = user
    this.password = password
  }

  private async req(method: string, url: string, body?: string): Promise<HttpResponse> {
    const headers: Record<string, string> = {}
    if (this.cookies.size) headers.Cookie = [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; ")
    if (body) headers["Content-Type"] = "application/x-www-form-urlencoded"
    const r = await defaultHttp({ method, url, headers, body, timeoutMs: 15_000 })
    for (const c of (r.headers["set-cookie"] ?? "").split(/,(?=\s*[A-Za-z0-9_-]+=)/)) {
      const m = /^\s*([^=;\s]+)=([^;]*)/.exec(c)
      if (m) this.cookies.set(m[1], m[2])
    }
    return r
  }

  /** Follows redirects except to 127.0.0.1; returns [final response, its URL]. */
  private async go(method: string, url: string, body?: string): Promise<[HttpResponse, string]> {
    let r = await this.req(method, url, body)
    for (let i = 0; i < 10 && r.status >= 300 && r.status < 400; i++) {
      const next = new URL(r.headers["location"] ?? "", url)
      if (next.hostname === "127.0.0.1") break
      url = next.toString()
      r = await this.req("GET", url)
    }
    return [r, url]
  }

  async login(authUrl: string): Promise<string> {
    let [r, at] = await this.go("GET", authUrl)
    for (let page = 0; r.status === 200 && page < 3; page++) {
      let m = loginForm.exec(r.body)
      let form: URLSearchParams
      if (m) {
        this.forms++
        form = new URLSearchParams({ username: this.user, password: this.password, credentialId: "" })
      } else if ((m = anyForm.exec(r.body)) && r.body.includes('name="accept"')) {
        this.consents++
        form = new URLSearchParams({ accept: "Yes" })
      } else break
      ;[r, at] = await this.go("POST", new URL(unescapeHtml(m[1]), at).toString(), form.toString())
    }
    if (r.status !== 302) throw new Error(`login did not redirect back (HTTP ${r.status})`)
    const cb = new URL(r.headers["location"] ?? "", at).toString()
    this.callbacks.push(cb)
    return cb
  }

  /** LoopbackAuthorizer opener: log in, then hand the callback to the loopback listener. */
  open = async (authUrl: string): Promise<void> => {
    const cb = await this.login(authUrl)
    await defaultHttp({ method: "GET", url: cb })
  }
}

export function headlessLogin(user: string, password: string): (url: string) => Promise<void> {
  return new KeycloakBrowser(user, password).open
}
