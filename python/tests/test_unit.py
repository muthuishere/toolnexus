"""Unit/integration tests (no network, no LLM).

Mirrors the JS reference suite at ``js/test/unit.test.ts``. Run:
    .venv/bin/python -m pytest -q

The HTTP test spins up a hermetic local ``http.server`` on an ephemeral port; no
external calls are ever made. The toolkit test is built with ``skills_dir`` only
(no MCP), so nothing connects to the network.
"""
from __future__ import annotations

import http.server
import os
import socketserver
import threading
from pathlib import Path

import pytest

from toolnexus import (
    create_toolkit,
    define_tool,
    expand_env_headers,
    http_tool,
    load_skills,
    parse_mcp_config,
    sanitize,
    to_anthropic,
    to_gemini,
    to_openai,
)
from toolnexus.types import ToolResult

# examples/skills lives at <repo>/examples/skills; this file is at
# <repo>/python/tests/test_unit.py
SKILLS_DIR = str(
    (Path(__file__).resolve().parents[2] / "examples" / "skills").resolve()
)


# --------------------------------------------------------------------------- #
# sanitize
# --------------------------------------------------------------------------- #
def test_sanitize_replaces_non_word_chars():
    assert sanitize("a b/c.d:e") == "a_b_c_d_e"
    assert sanitize("keep-_OK1") == "keep-_OK1"


# --------------------------------------------------------------------------- #
# parse_mcp_config
# --------------------------------------------------------------------------- #
def test_parse_mcp_config_accepts_wrappers_and_raw():
    s = {"foo": {"command": ["x"]}}
    assert parse_mcp_config({"mcpServers": s}) == s
    assert parse_mcp_config({"servers": s}) == s
    assert parse_mcp_config({"mcp": s}) == s
    assert parse_mcp_config(s) == s


# --------------------------------------------------------------------------- #
# expand_env_headers
# --------------------------------------------------------------------------- #
def test_expand_env_headers_expands_from_environ():
    os.environ["TN_TEST_TOKEN"] = "secret123"
    out = expand_env_headers(
        {"Authorization": "Bearer ${TN_TEST_TOKEN}", "X": "plain"}
    )
    assert out is not None
    assert out["Authorization"] == "Bearer secret123"
    assert out["X"] == "plain"
    assert expand_env_headers(None) is None


def test_expand_env_headers_missing_env_becomes_empty():
    os.environ.pop("TN_MISSING_VAR", None)
    out = expand_env_headers({"A": "x${TN_MISSING_VAR}y"})
    assert out == {"A": "xy"}


# --------------------------------------------------------------------------- #
# skills
# --------------------------------------------------------------------------- #
async def test_skills_discovery_prompt_and_block():
    src = load_skills(SKILLS_DIR)
    assert "hello-world" in src.skills, "hello-world discovered"
    assert "## Available Skills" in src.prompt()
    assert "hello-world" in src.prompt()

    res = await src.tool.execute({"name": "hello-world"})
    assert res.is_error is False
    assert res.output.startswith('<skill_content name="hello-world">')
    assert "# Skill: hello-world" in res.output
    assert "Base directory for this skill: file://" in res.output
    assert "<skill_files>" in res.output
    assert res.output.endswith("</skill_content>")

    miss = await src.tool.execute({"name": "nope"})
    assert miss.is_error is True
    assert "not found" in miss.output


# --------------------------------------------------------------------------- #
# native define_tool / @tool
# --------------------------------------------------------------------------- #
async def test_define_tool_string_result():
    s = define_tool(lambda: "hi", name="s", description="")
    res = await s.execute({})
    assert res.output == "hi"
    assert res.is_error is False
    assert s.source == "native"


async def test_define_tool_toolresult_passthrough():
    r = define_tool(
        lambda: ToolResult(output="x", is_error=True), name="r", description=""
    )
    res = await r.execute({})
    assert res.output == "x"
    assert res.is_error is True


async def test_define_tool_thrown_error():
    def boom():
        raise ValueError("boom")

    e = define_tool(boom, name="e", description="")
    res = await e.execute({})
    assert res.is_error is True
    assert "boom" in res.output


def test_define_tool_schema_inference_from_hints():
    def add(a: int, b: int) -> str:
        return str(a + b)

    t = define_tool(add, name="add", description="")
    schema = t.input_schema
    assert schema["type"] == "object"
    assert schema["required"] == ["a", "b"]
    assert schema["properties"]["a"] == {"type": "number"}
    assert schema["properties"]["b"] == {"type": "number"}


# --------------------------------------------------------------------------- #
# http_tool (hermetic local server)
# --------------------------------------------------------------------------- #
class _RecordingHandler(http.server.BaseHTTPRequestHandler):
    # set per-server instance below
    seen: dict = {}

    def log_message(self, *args):  # silence stderr access logs
        pass

    def do_GET(self):
        type(self).seen["url"] = self.path
        type(self).seen["auth"] = self.headers.get("Authorization")
        if self.path.startswith("/posts/5"):
            body = b'{"id": 5, "title": "hello"}'
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            body = b"nope"
            self.send_response(404)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)


@pytest.fixture()
def local_server():
    handler = type("H", (_RecordingHandler,), {"seen": {}})
    server = socketserver.TCPServer(("127.0.0.1", 0), handler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield port, handler.seen
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


async def test_http_tool_placeholder_env_header_query(local_server):
    port, seen = local_server
    os.environ["TN_HTTP_TOKEN"] = "tok"

    ok = http_tool(
        name="get_post",
        description="",
        method="GET",
        url=f"http://127.0.0.1:{port}/posts/{{id}}",
        headers={"Authorization": "Bearer ${TN_HTTP_TOKEN}"},
        input_schema={
            "type": "object",
            "properties": {"id": {"type": "number"}, "q": {"type": "string"}},
            "required": ["id"],
        },
    )
    r1 = await ok.execute({"id": 5, "q": "x"})
    assert r1.is_error is False
    assert "hello" in r1.output
    assert seen["auth"] == "Bearer tok", "env header expanded and reached server"
    assert seen["url"] == "/posts/5?q=x", "placeholder + querystring"
    assert ok.source == "http"


async def test_http_tool_non_2xx_is_error(local_server):
    port, _seen = local_server
    bad = http_tool(
        name="b",
        description="",
        method="GET",
        url=f"http://127.0.0.1:{port}/posts/{{id}}",
        input_schema={
            "type": "object",
            "properties": {"id": {"type": "number"}},
            "required": ["id"],
        },
    )
    r2 = await bad.execute({"id": 99})
    assert r2.is_error is True
    assert r2.output.startswith("HTTP 404:")


# --------------------------------------------------------------------------- #
# adapters
# --------------------------------------------------------------------------- #
def test_adapters_produce_documented_shapes():
    t = define_tool(
        lambda: "",
        name="t",
        description="d",
        input_schema={"type": "object", "properties": {}},
    )
    assert to_openai([t])[0] == {
        "type": "function",
        "function": {"name": "t", "description": "d", "parameters": t.input_schema},
    }
    assert to_anthropic([t])[0] == {
        "name": "t",
        "description": "d",
        "input_schema": t.input_schema,
    }
    g = to_gemini([t])
    assert g[0]["functionDeclarations"][0]["name"] == "t"


# --------------------------------------------------------------------------- #
# toolkit (skills only — no MCP, no network)
# --------------------------------------------------------------------------- #
async def test_toolkit_register_get_execute_duplicate():
    tk = await create_toolkit(skills_dir=SKILLS_DIR)
    try:
        tk.register(
            define_tool(
                lambda a, b: str(int(a) + int(b)), name="add", description=""
            )
        )
        tk.register(define_tool(lambda: "SHOULD_NOT_WIN", name="add", description="dup"))
        assert tk.get("add") is not None
        assert tk.get("skill") is not None

        r = await tk.execute("add", {"a": 2, "b": 3})
        assert r.output == "5"

        miss = await tk.execute("ghost", {})
        assert miss.is_error is True
    finally:
        await tk.close()
