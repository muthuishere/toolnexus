"""Example 07 — a web app that calls a protected MCP server for its users (pause/resume mode).

Run: python examples/07-oauth-web-pause-resume/main.py   (hermetic: everything runs on 127.0.0.1)

A server-side app must never block a request waiting for a login. With PauseAuthorizer the call raises
PendingError instead (.pending = {id, kind: "authorization", prompt, url, expires_at}, the shape of
toolnexus SPEC §10 Request). The app sends the browser to pending.url; the authorization server redirects
back; the app resumes and gets the ORIGINAL call's result.

web_handler packages that whole dance as one WSGI app. The manual version is shown too.
"""
from urllib.parse import parse_qs

import mcp2026 as mcp

# ---- demo harness: dev authorization server + protected MCP server ----
as_ = mcp.DevAuthServer.start()  # DEV/TEST ONLY
as_.clients["my-web-app"] = ["http://127.0.0.1/tool", "http://127.0.0.1/callback"]
srv = mcp.fixture_server()
srv.auth = mcp.ServerAuth(as_.issuer)
mcp_ts = mcp.serve(srv)
# ---- end harness ----


class Lazy:  # the app's own URL is known only once it listens
    app = None

    def __call__(self, environ, start_response):
        return self.app(environ, start_response)


lazy = Lazy()
web = mcp.serve(lazy)
app = web.url

# ---- the web app ----
c = mcp.Client(mcp_ts.url + "/mcp")
c.auth = mcp.OAuth(client_id="my-web-app", authorizer=mcp.PauseAuthorizer(redirect=app + "/tool"))

# (a) One line: /tool runs the call; if it pauses, 302 to the AS; the AS returns to /tool
# with ?code&state, web_handler resumes, and the tool's text is written to the response.
lazy.app = c.auth.web_handler(
    lambda req: c.call_tool("echo", {"text": parse_qs(req.query).get("text", [""])[0]}))
# ---- end web app ----

# A person's browser opens /tool?text=... (as_.client() follows redirects and trusts the dev TLS).
browser = as_.client()
resp = browser.get(app + "/tool?text=resumed+after+consent")
print("(a) web_handler:", resp.status, resp.body.decode())


# approve_and_capture_callback stands in for a person logging in: it opens the authorization URL
# and returns the URL the authorization server redirected back to (without following it).
def approve_and_capture_callback(auth_url: str) -> str:
    return mcp.HttpClient().get(auth_url).header("Location")


# (b) The same flow by hand, for apps that are not a plain handler (chat bots, job queues).
c2 = mcp.Client(mcp_ts.url + "/mcp")
c2.auth = mcp.OAuth(client_id="my-web-app", authorizer=mcp.PauseAuthorizer(redirect="http://127.0.0.1/callback"))
try:
    c2.call_tool("echo", {"text": "resumed by hand"})
    raise SystemExit("expected a pending authorization")
except mcp.PendingError as e:
    p = e.pending
print("(b) paused:", p.kind, "-", str(p.prompt != "").lower(), "- has URL:", str(p.url != "").lower())

callback_url = approve_and_capture_callback(p.url)  # the user logs in; you receive the callback URL
res = c2.auth.resume(p.id, callback_url)
print("(b) resumed:", mcp.text(res))

web.close()
mcp_ts.close()
as_.close()
