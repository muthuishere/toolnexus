"""The 8 cross-port conformance checks, and the shared fixture server."""
from __future__ import annotations

import os
from typing import Callable

from .client import Client
from .protocol import CODE_HEADER_MISMATCH, CODE_UNSUPPORTED_PROTOCOL, PROTOCOL_VERSION, SKILLS_EXT, RpcError, text
from .server import Server

_HERE = os.path.dirname(os.path.abspath(__file__))


def fixture_server() -> Server:
    """The shared spike server (tools echo + greet, skill hello-world) from ../fixture.json, or $FIXTURE."""
    return Server.load(os.environ.get("FIXTURE") or os.path.join(_HERE, "..", "..", "fixture.json"))


class _Skip(Exception):
    pass


def run_checks(url: str, *setup: Callable[[Client], Client | None], out=print) -> bool:
    """Runs the checks against url, printing PASS/FAIL/SKIP lines. Each setup may configure (or replace)
    every client the checks create (e.g. OAuth for a protected server, or a stdio client)."""
    ok = True

    def mk() -> Client:
        c = Client(url)
        for f in setup:
            c = f(c) or c
        return c

    def step(name, fn):
        nonlocal ok
        try:
            fn()
            out(f"PASS {name}")
        except _Skip:
            out(f"SKIP {name} (not applicable over stdio)")
        except Exception as e:
            ok = False
            out(f"FAIL {name}: {e!r}")

    def expect_code(fn, code):
        try:
            fn()
        except RpcError as e:
            if e.code == code:
                return
            raise AssertionError(f"want error {code}, got {e.code} {e}") from None
        raise AssertionError(f"want error {code}, got success")

    c = mk()

    def discover():
        r = c.call("server/discover")
        if SKILLS_EXT not in ((r.get("capabilities") or {}).get("extensions") or {}):
            raise AssertionError(f"skills extension not advertised: {r.get('capabilities')}")
        if PROTOCOL_VERSION not in (r.get("supportedVersions") or []):
            raise AssertionError(f"supportedVersions lacks {PROTOCOL_VERSION}: {r.get('supportedVersions')}")

    def bad_version():
        bad = mk()
        bad.version = "1900-01-01"
        expect_code(lambda: bad.call("server/discover"), CODE_UNSUPPORTED_PROTOCOL)

    def mismatch():
        bad = mk()
        if bad._stdio is not None:
            raise _Skip()  # stdio has no HTTP headers to mismatch
        bad._header_override = {"Mcp-Name": "greet"}
        expect_code(lambda: bad.call("tools/call", {"name": "echo", "arguments": {"text": "x"}}), CODE_HEADER_MISMATCH)

    def tools_list():
        r = c.call("tools/list")
        names = [t.get("name") for t in r.get("tools") or []]
        if "echo" not in names or "greet" not in names:
            raise AssertionError(f"tools: {names}")
        if r.get("ttlMs") is None or r.get("cacheScope") is None or r.get("resultType") != "complete":
            raise AssertionError(f"missing ttlMs/cacheScope/resultType: {r}")

    def echo():
        r = c.call_tool("echo", {"text": "hi toolnexus"})
        if text(r) != "hi toolnexus":
            raise AssertionError(f"got {r}")

    def greet():
        asked = []

        def respond(key, req):
            asked.append(key)
            if req.get("method") != "elicitation/create":
                raise AssertionError(f"unexpected input request {req.get('method')}")
            return {"action": "accept", "content": {"name": "toolnexus"}}

        r = c.call_tool("greet", {}, respond)
        if len(asked) != 1 or text(r) != "Hello, toolnexus!":
            raise AssertionError(f"asked={len(asked)} result={r}")

    uri = "skill://hello-world/SKILL.md"

    def skills_list():
        if not any(s.get("uri") == uri for s in c.call("skills/list").get("skills") or []):
            raise AssertionError("hello-world not listed")

    def skills_get():
        sk = c.call("skills/get", {"uri": uri}).get("skill") or {}
        if (sk.get("frontmatter") or {}).get("name") != "hello-world" or not sk.get("resources"):
            raise AssertionError(f"got {sk}")

    step("server/discover", discover)
    step("unsupported version -> -32022", bad_version)
    step("header mismatch -> -32020", mismatch)
    step("tools/list", tools_list)
    step("tools/call echo", echo)
    step("tools/call greet (MRTR input_required round trip)", greet)
    step("skills/list", skills_list)
    step("skills/get hello-world", skills_get)
    return ok
