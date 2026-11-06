"""MCP 2026-07-28 interop CLIENT runner (interop/mcp2026/README.md) for the Python port.
Mirrors golang/internal/interop/mcp2026client. Prints ``PASS <id>`` / ``FAIL <id>: <reason>``
per scenario; exit 0 iff all pass. Test tooling only — not shipped.

    python client.py <base> <skillsDir>
    python client.py --keycloak <mcpURL> <skillsDir>
"""
from __future__ import annotations

import asyncio
import html
import http.cookiejar
import json
import os
import re
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Awaitable, Callable, Optional

from toolnexus import Answer, MemoryTokenStore, ToolContext, create_toolkit, load_mcp, pending_of

failed = False


async def scenario(sid: str, fn: Callable[[], Awaitable[None]]) -> None:
    global failed
    try:
        await fn()
    except Exception as e:  # noqa: BLE001
        failed = True
        msg = (str(e) or type(e).__name__).replace("\n", " ")
        print(f"FAIL {sid}: {msg}", flush=True)
        return
    print(f"PASS {sid}", flush=True)


def answers(r: Any) -> Answer:
    if r.prompt == "What is your name?":
        return Answer(id=r.id, ok=True, data={"name": "toolnexus"})
    if r.prompt == "Really wipe?":
        return Answer(id=r.id, ok=True, data={"confirm": True})
    raise RuntimeError(f"unexpected request {r.prompt!r}")


async def load(cfg: dict[str, Any], **opts: Any) -> Any:
    src = await load_mcp({"mcpServers": {"srv": cfg}}, **opts)
    if src.status.get("srv") != "connected":
        await src.close()
        raise RuntimeError(f"not connected: {src.status}")
    return src


def tool(src: Any, name: str) -> Any:
    for t in src.tools:
        if t.name == name:
            return t
    raise RuntimeError(f"no tool {name}")


async def run(src: Any, name: str, args: Optional[dict[str, Any]] = None) -> Any:
    return await tool(src, name).execute(args or {}, ToolContext())


async def expect(src: Any, name: str, args: Optional[dict[str, Any]], want: str) -> None:
    r = await run(src, name, args)
    if r.is_error or r.output != want:
        raise RuntimeError(f"{name}: got {r.output!r} (is_error={r.is_error}), want {want!r}")


def get(u: str) -> None:
    try:
        urllib.request.urlopen(u, timeout=15).read()
    except urllib.error.HTTPError:
        pass


def skill_body(d: str) -> str:
    s = open(os.path.join(d, "hello-world", "SKILL.md"), encoding="utf-8").read().replace("\r\n", "\n")
    if s.startswith("---\n"):
        i = s[4:].find("\n---")
        if i >= 0:
            s = s[4 + i + 4:]
    return s.strip()


def scopes_have(store: MemoryTokenStore, resource: str, scope: str) -> bool:
    tok = store.load(resource)
    return tok is not None and scope in (tok.scopes or [])


async def hermetic(base: str, skills_dir: str) -> None:
    open_ = {"url": base + "/open/mcp"}

    async def discover() -> None:
        src = await load(open_, wait_for=answers)
        try:
            got = ",".join(t.name for t in src.tools)
            if got != "srv_echo,srv_greet,srv_whoami,srv_wipe":
                raise RuntimeError(f"tools {got}")
            await expect(src, "srv_echo", {"text": "hi"}, '{"text":"hi"}')
            seen = json.loads(urllib.request.urlopen(base + "/log", timeout=10).read()).get("seen") or []
            for s in seen:
                if s.startswith("open ") and not s.startswith("open 2026-07-28 "):
                    raise RuntimeError(f"non-2026 request on the 2026 mount: {s!r}")
        finally:
            await src.close()
    await scenario("discover", discover)

    async def mrtr_input() -> None:
        src = await load(open_, wait_for=answers)
        try:
            await expect(src, "srv_greet", None, "Hello, toolnexus!")
        finally:
            await src.close()
    await scenario("mrtr_input", mrtr_input)

    async def mrtr_confirm() -> None:
        src = await load(open_, wait_for=answers)
        try:
            await expect(src, "srv_wipe", None, "wiped")
        finally:
            await src.close()
    await scenario("mrtr_confirm", mrtr_confirm)

    async def mrtr_pending() -> None:
        src = await load(open_)
        try:
            r = await run(src, "srv_greet")
            req = pending_of(r)
            if req is None or req.kind != "input" or req.prompt != "What is your name?":
                raise RuntimeError(f"not pending input: {r!r}")
        finally:
            await src.close()
    await scenario("mrtr_pending", mrtr_pending)

    async def skills() -> None:
        tk = await create_toolkit({"mcpServers": {"srv": open_}}, builtins=False)
        try:
            r = await tk.execute("skill", {"name": "hello-world"})
            body = skill_body(skills_dir)
            if r.is_error or body not in r.output:
                raise RuntimeError(f"remote skill output lacks the SKILL.md body: {r.output[:200]!r}")
        finally:
            await tk.close()
    await scenario("skills", skills)

    async def tool_search() -> None:
        src = await load({"url": base + "/open/mcp", "toolSearch": True, "description": "interop"}, wait_for=answers)
        try:
            if len(src.tools) != 2:
                raise RuntimeError(f"tool search exposes {len(src.tools)} tools, want 2")
            r = await src.tools[0].execute({"query": "echo text"}, ToolContext())
            if not r.output.startswith('[{"name":"echo"'):
                raise RuntimeError(f"search output {r.output[:200]!r}")
            r = await src.tools[1].execute({"name": "greet"}, ToolContext())
            if r.output != "Hello, toolnexus!":
                raise RuntimeError(f"call_tool greet {r.output!r}")
        finally:
            await src.close()
    await scenario("tool_search", tool_search)

    auth_cfg = {"url": base + "/mcp", "auth": {"clientId": "interop-client", "allowHttp": True}}
    store = MemoryTokenStore()
    authed: list[Any] = []

    async def oauth_preregistered() -> None:
        src = await load(auth_cfg, open_url=get, token_store=store, wait_for=answers)
        authed.append(src)
        await expect(src, "srv_echo", {"text": "authed"}, '{"text":"authed"}')
    await scenario("oauth_preregistered", oauth_preregistered)

    async def oauth_stepup() -> None:
        if not authed:
            raise RuntimeError("needs oauth_preregistered")
        src = authed[0]
        try:
            await expect(src, "srv_whoami", None, "dev-user")
            if not scopes_have(store, base + "/mcp", "mcp:whoami"):
                raise RuntimeError(f"stored token scopes lack mcp:whoami: {store.load(base + '/mcp')!r}")
        finally:
            await src.close()
    await scenario("oauth_stepup", oauth_stepup)

    async def oauth_cimd() -> None:
        holder: dict[str, str] = {}

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a: Any) -> None:
                pass

            def do_GET(self) -> None:  # noqa: N802
                b = json.dumps({"client_id": holder["url"], "client_name": "interop CIMD",
                                "redirect_uris": ["http://127.0.0.1/callback"]}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

        hs = ThreadingHTTPServer(("127.0.0.1", 0), H)
        holder["url"] = doc = f"http://127.0.0.1:{hs.server_address[1]}/client.json"
        threading.Thread(target=hs.serve_forever, daemon=True).start()
        bad: list[str] = []

        def follow(u: str) -> None:
            if "client_id=" + urllib.parse.quote(doc, safe="") not in u and "client_id=" + urllib.parse.quote_plus(doc) not in u:
                bad.append(f"authorize URL lacks the CIMD client_id: {u}")
            get(u)
        try:
            src = await load({"url": base + "/mcp", "auth": {"clientMetadataUrl": doc, "allowHttp": True}}, open_url=follow)
            try:
                await expect(src, "srv_echo", {"text": "cimd"}, '{"text":"cimd"}')
            finally:
                await src.close()
            if bad:
                raise RuntimeError(bad[0])
        finally:
            hs.shutdown()
    await scenario("oauth_cimd", oauth_cimd)

    async def oauth_no_identity() -> None:
        src = await load_mcp({"mcpServers": {"srv": {"url": base + "/mcp", "auth": {"allowHttp": True}}}}, open_url=lambda u: None)
        try:
            if src.status.get("srv") != "failed":
                raise RuntimeError(f"status {src.status}, want failed")
        finally:
            await src.close()
    await scenario("oauth_no_identity", oauth_no_identity)

    async def legacy_fallback() -> None:
        src = await load({"url": base + "/legacy/mcp"})
        try:
            await expect(src, "srv_echo", {"text": "old"}, '{"text":"old"}')
        finally:
            await src.close()
    await scenario("legacy_fallback", legacy_fallback)


# ---- live Keycloak --------------------------------------------------------

KC_CLIENT_ID = "toolnexus-mcp"
KC_USER = "alice"
KC_PASSWORD = "alice-test-pw"  # throwaway user of the local-only realm-toolnexus.json
KC_LOGIN_FORM = re.compile(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"')
KC_ANY_FORM = re.compile(r'<form[^>]*action="([^"]+)"')


class _NoLoopbackRedirect(urllib.request.HTTPRedirectHandler):
    """Follow Keycloak's redirects but stop at the 127.0.0.1 callback."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[override]
        if urllib.parse.urlsplit(newurl).hostname == "127.0.0.1":
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def kc_opener() -> Callable[[str], None]:
    class _LocalhostSecure(http.cookiejar.DefaultCookiePolicy):
        # Keycloak marks its session cookies Secure; browsers treat http://localhost as a
        # secure context and send them, Python's jar does not.
        def return_ok_secure(self, cookie: Any, request: Any) -> bool:
            return True

    jar = http.cookiejar.CookieJar(_LocalhostSecure())
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), _NoLoopbackRedirect())

    def fetch(u: str, form: Optional[dict[str, str]] = None) -> tuple[int, str, str, str]:
        data = urllib.parse.urlencode(form).encode() if form is not None else None
        try:
            resp = op.open(u, data=data, timeout=15)
            return resp.status, resp.geturl(), resp.read().decode("utf-8", "replace"), resp.headers.get("Location", "")
        except urllib.error.HTTPError as e:
            return e.code, e.geturl(), e.read().decode("utf-8", "replace"), e.headers.get("Location", "")

    def open_url(auth_url: str) -> None:
        status, url, body, loc = fetch(auth_url)
        for _ in range(3):
            if status != 200:
                break
            m = KC_LOGIN_FORM.search(body)
            if m:
                action, form = m.group(1), {"username": KC_USER, "password": KC_PASSWORD, "credentialId": ""}
            else:
                m = KC_ANY_FORM.search(body)
                if not (m and 'name="accept"' in body):
                    break
                action, form = m.group(1), {"accept": "Yes"}
            status, url, body, loc = fetch(urllib.parse.urljoin(url, html.unescape(action)), form)
        if status != 302:
            raise RuntimeError(f"keycloak login: HTTP {status}")
        get(loc)

    return open_url


async def keycloak(mcp_url: str) -> None:
    store = MemoryTokenStore()
    holder: list[Any] = []

    async def kc_login() -> None:
        src = await load({"url": mcp_url, "auth": {"clientId": KC_CLIENT_ID, "allowHttp": True}},
                         open_url=kc_opener(), token_store=store, wait_for=answers)
        holder.append(src)
        await expect(src, "srv_echo", {"text": "keycloak"}, '{"text":"keycloak"}')
    await scenario("kc_login", kc_login)

    async def kc_stepup() -> None:
        if not holder:
            raise RuntimeError("needs kc_login")
        src = holder[0]
        try:
            r = await run(src, "srv_whoami")
            if r.is_error or not r.output:
                raise RuntimeError(f"whoami {r!r}")
            if not scopes_have(store, mcp_url, "mcp:greet"):
                raise RuntimeError(f"stored token scopes lack mcp:greet: {store.load(mcp_url)!r}")
        finally:
            await src.close()
    await scenario("kc_stepup", kc_stepup)


def main() -> None:
    a = sys.argv[1:]
    if len(a) == 3 and a[0] == "--keycloak":
        asyncio.run(keycloak(a[1]))
    elif len(a) == 2:
        asyncio.run(hermetic(a[0], a[1]))
    else:
        print("usage: client.py <base> <skillsDir> | --keycloak <mcpURL> <skillsDir>", file=sys.stderr)
        sys.exit(2)
    sys.stdout.flush()
    os._exit(1 if failed else 0)


if __name__ == "__main__":
    main()
