"""Unit/integration tests (no network, no LLM).

Mirrors the JS reference suite at ``js/test/unit.test.ts``. Run:
    .venv/bin/python -m pytest -q

The HTTP test spins up a hermetic local ``http.server`` on an ephemeral port; no
external calls are ever made. The toolkit test is built with ``skills_dir`` only
(no MCP), so nothing connects to the network.
"""
from __future__ import annotations

import http.server
import json
import os
import socketserver
import tempfile
import threading
from pathlib import Path

import pytest

from toolnexus import (
    SKILLS_PROMPT_PREAMBLE,
    builtins_enabled,
    create_builtin_tools,
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


# --------------------------------------------------------------------------- #
# builtin tools
# --------------------------------------------------------------------------- #
BUILTIN_NAMES = [
    "bash", "read", "write", "edit", "grep", "glob",
    "webfetch", "question", "apply_patch", "todowrite",
]


def _builtin(name: str):
    return next(t for t in create_builtin_tools() if t.name == name)


def test_builtins_enabled_default_on_and_precedence():
    assert builtins_enabled(None) is True
    assert builtins_enabled(True) is True
    assert builtins_enabled(False) is False
    assert builtins_enabled({}) is True
    assert builtins_enabled({"enabled": False}) is False
    assert builtins_enabled({"disabled": True}) is False
    # disabled=True wins regardless of enabled
    assert builtins_enabled({"enabled": True, "disabled": True}) is False


async def test_toolkit_default_includes_all_10_builtins():
    tk = await create_toolkit()
    try:
        for name in BUILTIN_NAMES:
            t = tk.get(name)
            assert t is not None, f"builtin {name} present"
            assert t.source == "builtin", f"{name} source"
        names = [f["function"]["name"] for f in tk.to_openai()]
        for name in BUILTIN_NAMES:
            assert name in names, f"{name} in to_openai"
    finally:
        await tk.close()


@pytest.mark.parametrize("off", [False, {"disabled": True}, {"enabled": False}])
async def test_toolkit_builtins_toggle_off_removes_all_10(off):
    extra = define_tool(lambda: "x", name="myextra", description="")
    tk = await create_toolkit(
        skills_dir=SKILLS_DIR, extra_tools=[extra], builtins=off
    )
    try:
        for name in BUILTIN_NAMES:
            assert tk.get(name) is None, f"{name} absent when off={off}"
        # skill + extras unaffected
        assert tk.get("skill") is not None
        assert tk.get("myextra") is not None
    finally:
        await tk.close()


async def test_toolkit_extra_tool_shadows_builtin():
    mine = define_tool(lambda: "HOST_READ", name="read", description="host read")
    tk = await create_toolkit(extra_tools=[mine])
    try:
        r = tk.get("read")
        assert r is not None
        assert r.source == "native", "host tool wins the collision"
        out = await tk.execute("read", {})
        assert out.output == "HOST_READ"
    finally:
        await tk.close()


async def test_builtins_toggle_via_top_level_config_key():
    # A parsed config dict carrying a top-level `builtins` key drives the toggle.
    cfg = {"mcpServers": {}, "builtins": False}
    tk = await create_toolkit(mcp_config=cfg)
    try:
        for name in BUILTIN_NAMES:
            assert tk.get(name) is None
    finally:
        await tk.close()


async def test_toolkit_builtins_tools_map_disables_named_only():
    # A `tools` map drops only the tools mapped to False; unknown names ignored.
    tk = await create_toolkit(
        builtins={"tools": {"bash": False, "write": False, "nope": False}}
    )
    try:
        assert tk.get("bash") is None, "bash disabled"
        assert tk.get("write") is None, "write disabled"
        # the other nine remain
        for name in BUILTIN_NAMES:
            if name in ("bash", "write"):
                continue
            assert tk.get(name) is not None, f"{name} still present"
    finally:
        await tk.close()


async def test_toolkit_whole_source_off_overrides_per_tool_map():
    # Whole-source-off short-circuits: the per-tool map is not consulted.
    tk = await create_toolkit(builtins={"disabled": True, "tools": {"read": True}})
    try:
        for name in BUILTIN_NAMES:
            assert tk.get(name) is None, f"{name} absent (source off wins)"
    finally:
        await tk.close()


async def test_builtin_read_write_edit_roundtrip():
    with tempfile.TemporaryDirectory() as dir_:
        file = os.path.join(dir_, "note.txt")
        w = await _builtin("write").execute({"path": file, "content": "alpha\nbeta\nalpha\n"})
        assert w.is_error is False
        assert "Wrote" in w.output and "bytes" in w.output

        r = await _builtin("read").execute({"path": file})
        assert r.output == "alpha\nbeta\nalpha\n"

        # offset/limit window (1-based)
        win = await _builtin("read").execute({"path": file, "offset": 2, "limit": 1})
        assert win.output == "beta"

        # edit non-unique without replaceAll => error, no write
        dup = await _builtin("edit").execute({"path": file, "oldString": "alpha", "newString": "X"})
        assert dup.is_error is True
        assert "not unique" in dup.output
        with open(file, encoding="utf-8") as f:
            assert f.read() == "alpha\nbeta\nalpha\n", "no partial write"

        # edit not-found => error
        nf = await _builtin("edit").execute({"path": file, "oldString": "zzz", "newString": "X"})
        assert nf.is_error is True
        assert "not found" in nf.output

        # edit replaceAll
        ra = await _builtin("edit").execute(
            {"path": file, "oldString": "alpha", "newString": "OMEGA", "replaceAll": True}
        )
        assert ra.is_error is False
        with open(file, encoding="utf-8") as f:
            assert f.read() == "OMEGA\nbeta\nOMEGA\n"

        # edit single unique replace
        one = await _builtin("edit").execute({"path": file, "oldString": "beta", "newString": "gamma"})
        assert one.is_error is False
        with open(file, encoding="utf-8") as f:
            assert f.read() == "OMEGA\ngamma\nOMEGA\n"

        # read missing => error
        miss = await _builtin("read").execute({"path": os.path.join(dir_, "nope.txt")})
        assert miss.is_error is True


async def test_builtin_grep_and_glob_counts():
    with tempfile.TemporaryDirectory() as dir_:
        with open(os.path.join(dir_, "a.txt"), "w") as f:
            f.write("needle here\nplain\n")
        with open(os.path.join(dir_, "b.txt"), "w") as f:
            f.write("no match\nneedle again\n")
        os.mkdir(os.path.join(dir_, "sub"))
        with open(os.path.join(dir_, "sub", "c.md"), "w") as f:
            f.write("needle in md\n")

        g = await _builtin("grep").execute({"pattern": "needle", "path": dir_})
        assert g.is_error is False
        assert g.metadata["count"] == 3, "three needle lines across files"

        g_inc = await _builtin("grep").execute({"pattern": "needle", "path": dir_, "include": "*.txt"})
        assert g_inc.metadata["count"] == 2, "include filters to .txt"

        gl = await _builtin("glob").execute({"pattern": "*.txt", "path": dir_})
        assert gl.metadata["count"] == 2, "two .txt files"
        assert sorted(gl.output.split("\n")) == ["a.txt", "b.txt"]

        gl_md = await _builtin("glob").execute({"pattern": "*.md", "path": dir_})
        assert gl_md.metadata["count"] == 1


async def test_builtin_bash_success_and_nonzero_exit():
    okr = await _builtin("bash").execute({"command": "printf 'hello-bash'"})
    assert okr.is_error is False
    assert "hello-bash" in okr.output

    bad = await _builtin("bash").execute({"command": "exit 3"})
    assert bad.is_error is True
    assert "code 3" in bad.output
    assert bad.metadata["exitCode"] == 3

    with tempfile.TemporaryDirectory() as dir_:
        cwd = await _builtin("bash").execute({"command": "pwd", "workdir": dir_})
        assert cwd.is_error is False
        assert os.path.basename(dir_) in cwd.output


class _WebHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/ok":
            body = b"fetched-body-content"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            body = b"boom"
            self.send_response(500)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)


@pytest.fixture()
def web_server():
    server = socketserver.TCPServer(("127.0.0.1", 0), _WebHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield port
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


async def test_builtin_webfetch(web_server):
    port = web_server
    okr = await _builtin("webfetch").execute(
        {"url": f"http://127.0.0.1:{port}/ok", "format": "text"}
    )
    assert okr.is_error is False
    assert "fetched-body-content" in okr.output
    assert okr.metadata["status"] == 200

    bad = await _builtin("webfetch").execute({"url": f"http://127.0.0.1:{port}/err"})
    assert bad.is_error is True
    assert bad.output.startswith("HTTP 500")


async def test_builtin_apply_patch_roundtrip_and_atomic_abort():
    with tempfile.TemporaryDirectory() as dir_:
        to_delete = os.path.join(dir_, "gone.txt")
        to_update = os.path.join(dir_, "keep.txt")
        to_add = os.path.join(dir_, "new.txt")
        with open(to_delete, "w") as f:
            f.write("bye\n")
        with open(to_update, "w") as f:
            f.write("line one\nline two\nline three\n")

        patch = "\n".join(
            [
                "*** Begin Patch",
                f"*** Add File: {to_add}",
                "+created line 1",
                "+created line 2",
                f"*** Update File: {to_update}",
                "@@",
                " line one",
                "-line two",
                "+line TWO changed",
                " line three",
                f"*** Delete File: {to_delete}",
                "*** End Patch",
                "",
            ]
        )
        res = await _builtin("apply_patch").execute({"patchText": patch})
        assert res.is_error is False, res.output
        with open(to_add, encoding="utf-8") as f:
            assert f.read() == "created line 1\ncreated line 2"
        with open(to_update, encoding="utf-8") as f:
            assert f.read() == "line one\nline TWO changed\nline three\n"
        assert not os.path.exists(to_delete)

        # non-matching hunk => error with no partial write
        add_two = os.path.join(dir_, "should-not-exist.txt")
        bad_patch = "\n".join(
            [
                "*** Begin Patch",
                f"*** Add File: {add_two}",
                "+content",
                f"*** Update File: {to_update}",
                "@@",
                "-DOES NOT MATCH",
                "+whatever",
                "*** End Patch",
            ]
        )
        bad = await _builtin("apply_patch").execute({"patchText": bad_patch})
        assert bad.is_error is True
        assert "does not match" in bad.output
        assert not os.path.exists(add_two), "no partial write when a later hunk fails"
        with open(to_update, encoding="utf-8") as f:
            assert f.read() == "line one\nline TWO changed\nline three\n", "update untouched"


async def test_builtin_question_and_todowrite_roundtrip():
    questions = [{"question": "Pick one?", "header": "Choice", "options": ["a", "b"], "multiple": False}]
    q = await _builtin("question").execute({"questions": questions})
    assert q.is_error is False
    assert json.loads(q.output) == questions
    assert q.metadata["questions"] == questions

    todos = [
        {"id": "1", "text": "write code", "completed": True},
        {"id": "2", "text": "ship it", "completed": False},
    ]
    t = await _builtin("todowrite").execute({"todos": todos})
    assert t.is_error is False
    assert t.metadata["todos"] == todos
    assert "[x] write code" in t.output
    assert "[ ] ship it" in t.output


# --------------------------------------------------------------------------- #
# skills prompt preamble
# --------------------------------------------------------------------------- #
def test_skills_prompt_preamble_present_and_absent():
    with_skills = load_skills(SKILLS_DIR)
    p = with_skills.prompt()
    assert p.startswith(SKILLS_PROMPT_PREAMBLE), "starts with the exact preamble"
    described = sorted(
        (s for s in with_skills.skills.values() if s.description is not None),
        key=lambda s: s.name,
    )
    expected = (
        SKILLS_PROMPT_PREAMBLE
        + "\n\n## Available Skills\n"
        + "\n".join(f"- **{s.name}**: {s.description}" for s in described)
    )
    assert p == expected

    with tempfile.TemporaryDirectory() as empty:
        no_skills = load_skills(empty)
        assert no_skills.prompt() == "No skills are currently available."
        assert not no_skills.prompt().startswith(SKILLS_PROMPT_PREAMBLE)


def test_skill_discovery_follows_symlinked_dirs():
    # Layout: root/ has a real skill (direct/) and a symlink (linked/) → an
    # out-of-tree skill dir. The walker must discover both. (opencode parity)
    with tempfile.TemporaryDirectory() as root, tempfile.TemporaryDirectory() as external:
        direct = os.path.join(root, "direct")
        os.makedirs(direct, exist_ok=True)
        with open(os.path.join(direct, "SKILL.md"), "w", encoding="utf-8") as f:
            f.write("---\nname: direct-skill\ndescription: d\n---\nbody\n")

        target = os.path.join(external, "linked-target")
        os.makedirs(target, exist_ok=True)
        with open(os.path.join(target, "SKILL.md"), "w", encoding="utf-8") as f:
            f.write("---\nname: linked-skill\ndescription: l\n---\nbody\n")
        os.symlink(target, os.path.join(root, "linked"))  # symlinked skill dir

        src = load_skills(root)
        assert "direct-skill" in src.skills, "real skill discovered"
        assert "linked-skill" in src.skills, "symlinked skill directory discovered"


# --------------------------------------------------------------------------- #
# frontmatter YAML block scalars (folded `>`, literal `|`) — SPEC.md §3
# --------------------------------------------------------------------------- #
def _write_skill(root: str, name: str, body: str) -> None:
    d = os.path.join(root, name)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "SKILL.md"), "w", encoding="utf-8") as f:
        f.write(body)


def test_frontmatter_single_line_description():
    # No regression: plain single-line `description: text` still parses.
    with tempfile.TemporaryDirectory() as root:
        _write_skill(root, "s", "---\nname: s\ndescription: just a line\n---\nbody\n")
        src = load_skills(root)
        assert src.skills["s"].description == "just a line"


def test_frontmatter_folded_description():
    # Folded `>` block scalar: newlines fold to spaces, NOT captured as ">".
    with tempfile.TemporaryDirectory() as root:
        _write_skill(
            root,
            "s",
            "---\n"
            "name: s\n"
            "description: >\n"
            "  This is a folded\n"
            "  description across lines.\n"
            "---\n"
            "body\n",
        )
        src = load_skills(root)
        desc = src.skills["s"].description
        assert desc != ">"
        assert desc == "This is a folded description across lines."


def test_frontmatter_literal_description():
    # Literal `|` block scalar: newlines preserved (trailing trimmed).
    with tempfile.TemporaryDirectory() as root:
        _write_skill(
            root,
            "s",
            "---\n"
            "name: s\n"
            "description: |\n"
            "  line one\n"
            "  line two\n"
            "---\n"
            "body\n",
        )
        src = load_skills(root)
        desc = src.skills["s"].description
        assert desc != "|"
        assert desc == "line one\nline two"


def test_frontmatter_empty_description():
    # `description:` with no value → empty string or absent, never a crash.
    with tempfile.TemporaryDirectory() as root:
        _write_skill(root, "s", "---\nname: s\ndescription:\n---\nbody\n")
        src = load_skills(root)
        assert "s" in src.skills
        assert (src.skills["s"].description or "") == ""


def test_frontmatter_malformed_yaml_does_not_crash():
    # Unterminated quote → malformed YAML. Discovery must not crash; the skill is
    # skipped (no `name` survives) or has an empty description.
    with tempfile.TemporaryDirectory() as root:
        _write_skill(
            root,
            "s",
            '---\nname: s\ndescription: "unterminated\n---\nbody\n',
        )
        src = load_skills(root)  # must not raise
        assert "s" not in src.skills or (src.skills["s"].description or "") == ""


def test_frontmatter_real_folded_example_huddle():
    # A real-world folded example (mirrors huddle / reqsume-kernel SKILL.md).
    with tempfile.TemporaryDirectory() as root:
        _write_skill(
            root,
            "huddle",
            "---\n"
            "name: huddle\n"
            "description: >\n"
            "  Runs a repo-aware expert huddle for engineering decisions,\n"
            "  planning, research, and verification.\n"
            "---\n"
            "# Huddle\n",
        )
        src = load_skills(root)
        assert "huddle" in src.skills
        desc = src.skills["huddle"].description
        assert desc != ">"
        assert desc == (
            "Runs a repo-aware expert huddle for engineering decisions, "
            "planning, research, and verification."
        )
