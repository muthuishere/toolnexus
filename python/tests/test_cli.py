"""Hermetic tests for the CLI client (SPEC §8C): no network, no real agent CLI.

``fixtures/cli_fake.py`` plays a fake ACP agent and a fake one-shot CLI; the stdio
MCP bridge is ``python -m toolnexus.cli_acp mcp-bridge``. Mirrors golang/cli_test.go.
"""
from __future__ import annotations

import dataclasses
import json
import os
import sys
import urllib.error
import urllib.request
from importlib import resources

import pytest

from toolnexus import (
    CLI_PREAMBLE,
    CliACPLaunch,
    CliError,
    CliStyleDef,
    CliStyles,
    CliTransports,
    cli_is_served_call,
    cli_style_def,
    cli_with_system_hint,
    create_cli_client,
    create_toolkit,
    define_tool,
    load_cli_style,
    parse_cli_reply,
    render_cli_prompt,
)
from toolnexus.cli import _normalize_args, _validate

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
FAKE = os.path.join(HERE, "fixtures", "cli_fake.py")
STYLES_DIR = os.path.join(REPO, "conformance", "cli-styles")
CASES = os.path.join(REPO, "conformance", "cli-client", "cases.json")
BRIDGE = [sys.executable, "-m", "toolnexus.cli_acp", "mcp-bridge"]


async def badge_toolkit():
    badge = define_tool(
        lambda: "BADGE-77Q",
        name="get_badge_number",
        description="Returns the badge number.",
        input_schema={"type": "object", "properties": {}},
    )
    return await create_toolkit(
        extra_tools=[badge], builtins=False, skills_dir=[os.path.join(REPO, "examples", "skills")]
    )


def fake_cli_opts(mode: str, *extra, **kw):
    o = dict(
        style=CliStyles.CUSTOM,
        command=sys.executable,
        args=[FAKE, *extra],
        prompt="stdin",
        answer="stdout",
        env={"TN_FAKE_CLI": mode},
        transport=CliTransports.CLI,
    )
    o.update(kw)
    return o


async def run_cli(opts, tk=None, prompt="What is my badge number?"):
    c = create_cli_client(**opts)
    try:
        return await c.run(prompt, tk)
    finally:
        c.close()


# --------------------------------------------------------------------------- #
# Shared data: styles and preamble are byte-identical to conformance/.
# --------------------------------------------------------------------------- #


def test_styles_match_conformance_byte_for_byte():
    names = sorted(n for n in os.listdir(STYLES_DIR) if n != "README.md")
    assert len(names) == 8, names
    embedded = resources.files("toolnexus") / "clistyles"
    for n in names:
        with open(os.path.join(STYLES_DIR, n), "rb") as f:
            want = f.read()
        assert (embedded / n).read_bytes() == want, f"python/src/toolnexus/clistyles/{n} differs (copy it)"
    with open(os.path.join(STYLES_DIR, "preamble.txt"), encoding="utf-8") as f:
        assert CLI_PREAMBLE == f.read()
    for s in CliStyles:
        if s is CliStyles.CUSTOM:
            assert cli_style_def(s) is None
            continue
        d = cli_style_def(s)
        assert d is not None and d.name == s.value
        _validate(d, _normalize_args(d.args), "")
        assert d.verified, d.name
    assert cli_style_def(CliStyles.CODEX).acp is None
    devin = cli_style_def(CliStyles.DEVIN)
    assert devin.acp is not None and devin.acp.project_mcp_file == ".mcp.json"
    assert cli_style_def(CliStyles.GOOSE).verified.startswith("UNVERIFIED")


def test_style_and_transport_are_closed_enums():
    with pytest.raises(TypeError):
        create_cli_client(style="claude", command="x")  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        create_cli_client(style=CliStyles.CLAUDE, command="x", transport="cli")  # type: ignore[arg-type]
    assert [s.name for s in CliStyles] == [
        "CLAUDE", "CODEX", "COPILOT", "OPENCODE", "DEVIN", "GEMINI", "GOOSE", "CUSTOM",
    ]


def test_load_cli_style_from_json_and_path(tmp_path):
    d = load_cli_style('{"name":"mine","args":["{{prompt}}"],"prompt":"arg","answer":"stdout"}')
    assert d.name == "mine" and d.prompt == "arg"
    p = tmp_path / "s.json"
    p.write_text('{"name":"f","args":[],"prompt":"stdin","answer":"stdout","acp":{"args":["acp"]}}')
    d2 = load_cli_style(str(p))
    assert d2.acp is not None and d2.acp.args == ["acp"]
    with pytest.raises(CliError, match="^cli style: "):
        load_cli_style(str(tmp_path / "missing.json"))


# --------------------------------------------------------------------------- #
# Construction errors.
# --------------------------------------------------------------------------- #

CONSTRUCTION = [
    (dict(style=CliStyles.CLAUDE), "cli: Command is required (path or name of the CLI binary)"),
    (dict(command="x"), "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)"),
    (dict(style=CliStyles.GOOSE, command="goose", model="m", transport=CliTransports.CLI), 'cli: style "goose" takes no model'),
    (dict(style=CliStyles.CODEX, command="codex", transport=CliTransports.ACP),
     'cli: style "codex" has no ACP launch; set ACPCommand or use CliTransports.CLI'),
    (dict(style=CliStyles.CUSTOM, command="x", args=["{{model}}"], prompt="stdin", answer="stdout"),
     "cli: the style needs a model: {{model}} is outside an optional group"),
    (dict(style=CliStyles.CUSTOM, command="x", args=["{{nope}}"], prompt="stdin", answer="stdout"), "cli: unknown placeholder {{nope}}"),
    (dict(style=CliStyles.CUSTOM, command="x", prompt="arg", answer="stdout"), 'cli: prompt "arg" needs {{prompt}} in args'),
    (dict(style=CliStyles.CUSTOM, command="x", prompt="file", answer="stdout"), 'cli: prompt "file" needs {{file}} in args'),
    (dict(style=CliStyles.CUSTOM, command="x", prompt="stdin", answer="out"), 'cli: answer "out" needs {{out}} in args'),
    (dict(style=CliStyles.CUSTOM, command="x", prompt="pipe", answer="stdout"), 'cli: prompt must be stdin, arg or file, not "pipe"'),
    (dict(style=CliStyles.CUSTOM, command="x", prompt="stdin", answer="json"), 'cli: answer must be stdout or out, not "json"'),
]


@pytest.mark.parametrize("opts,want", CONSTRUCTION)
def test_construction_errors(opts, want):
    with pytest.raises(CliError) as ei:
        create_cli_client(**opts)
    assert str(ei.value) == want


# --------------------------------------------------------------------------- #
# The envelope parser.
# --------------------------------------------------------------------------- #


def test_parse_cli_reply():
    cases = [
        ('{"content":"hi"}', "hi", None),
        ('```json\n{"content":"fenced"}\n```\n\n', "fenced", None),
        ('Sure! {"content":"wrapped"} done', "wrapped", None),
        ('{"choices":[{"message":{"content":"openai"}}]}', "openai", None),
        ('{"message":{"content":"msg"}}', "msg", None),
        ('{"tool_calls":[{"id":"a","function":{"name":"t","arguments":"{\\"x\\":1}"}}]}', None, "t"),
        ('{"tool_calls":[{"name":"flat","arguments":{"x":1}}]}', None, "flat"),
        ("plain text answer", "plain text answer", None),
        ('{"content":"x"} trailing {', "x", None),
        ('{"answer":1}', '{"answer":1}', None),
        ('{"content":"x"} {"content":"y"}', '{"content":"x"} {"content":"y"}', None),
    ]
    for text, content, tool in cases:
        r = parse_cli_reply(text)
        if tool:
            assert [c["name"] for c in r.get("tool_calls", [])] == [tool], text
        else:
            assert r == {"content": content}, text
    assert render_cli_prompt(None, None) == CLI_PREAMBLE + '\nREQUEST:\n{"messages":[],"tools":[]}'


# --------------------------------------------------------------------------- #
# One-shot transport.
# --------------------------------------------------------------------------- #


async def test_one_shot_tool_round_trip(tmp_path):
    rec = tmp_path / "rec"
    tk = await badge_toolkit()
    try:
        o = fake_cli_opts("tool", system_prompt="You are a badge clerk.")
        o["env"]["TN_FAKE_REC"] = str(rec)
        res = await run_cli(o, tk)
    finally:
        await tk.close()
    assert "BADGE-77Q" in res.text
    assert [c["name"] for c in res.tool_calls] == ["get_badge_number"]
    first = rec.read_text().split("\n=====\n")[0]
    assert first.startswith(CLI_PREAMBLE + "\nREQUEST:\n")
    req = json.loads(first.split("\nREQUEST:\n", 1)[1])
    assert req["messages"][0]["role"] == "system" and "badge clerk" in req["messages"][0]["content"]
    assert req["tools"]


async def test_prompt_modes_and_out_file():
    tk = await badge_toolkit()
    try:
        for args, prompt, answer in [
            (["--p", "{{prompt}}"], "arg", "stdout"),
            (["--f", "{{file}}"], "file", "stdout"),
            (["--o", "{{out}}"], "stdin", "out"),
        ]:
            o = fake_cli_opts("tool", *args, prompt=prompt, answer=answer)
            res = await run_cli(o, tk)
            assert "BADGE-77Q" in res.text, (prompt, answer)
    finally:
        await tk.close()


async def test_model_group():
    o = fake_cli_opts("x", "--argv", "-q")
    o["args"].append(["--model", "{{model}}"])
    res = await run_cli(o)
    assert "--model" not in res.text
    res = await run_cli({**o, "model": "prov/m-1"})
    assert "--model prov/m-1" in res.text


async def test_temp_files_removed_on_every_path(monkeypatch, tmp_path):
    import tempfile

    monkeypatch.setattr(tempfile, "tempdir", str(tmp_path))
    o = fake_cli_opts("exit", "--f", "{{file}}", prompt="file")
    with pytest.raises(CliError):
        await run_cli(o)
    o = fake_cli_opts("tool", "--o", "{{out}}", answer="out")
    await run_cli(o)
    assert list(tmp_path.iterdir()) == []


@pytest.mark.parametrize(
    "opts,want",
    [
        (fake_cli_opts("exit"), "cli: exit 3: boom: bad login"),
        (fake_cli_opts("empty"), "cli: empty reply"),
        (fake_cli_opts("nooutfile", "--x", "{{out}}", answer="out"), "cli: no output file"),
        (fake_cli_opts("sleep", call_timeout_ms=300), "cli: timed out after 300ms"),
        (fake_cli_opts("x", command="definitely-not-a-cli-xyz"), "cli: command not found: definitely-not-a-cli-xyz"),
    ],
)
async def test_pinned_errors(opts, want):
    with pytest.raises(CliError) as ei:
        await run_cli(opts)
    assert str(ei.value) == want


async def test_exit_stderr_truncated_to_1000_code_points():
    from toolnexus.cli import _truncate

    assert _truncate("é" * 1200) == "é" * 1000 + "…"


async def test_env_expands_from_process_env(monkeypatch):
    monkeypatch.setenv("TN_TEST_EXPANDED", "exit")
    with pytest.raises(CliError, match="^cli: exit 3: boom: bad login$"):
        await run_cli(fake_cli_opts("${TN_TEST_EXPANDED}"))


async def test_one_shot_refuses_streaming():
    c = create_cli_client(**fake_cli_opts("x"))
    with pytest.raises(Exception) as ei:
        async for _ in c.stream("hi"):
            pass
    assert "cli: streaming is not supported by the one-shot CLI transport; use Run" in str(ei.value)


# --------------------------------------------------------------------------- #
# ACP transport.
# --------------------------------------------------------------------------- #


async def fake_acp_opts(tmp_path, **env):
    rec = tmp_path / "rec"
    e = {"TN_FAKE_ACP": "1", "TN_FAKE_REC": str(rec), **env}
    d = CliStyleDef(name="fake", args=[], prompt="stdin", answer="stdout", acp=CliACPLaunch(args=[FAKE, "acp"]))
    tk = await badge_toolkit()
    return dict(style_def=d, command=sys.executable, env=e, toolkit=tk, bridge_command=BRIDGE), rec, tk


def assert_fake_acp_run(c, res, entry):
    assert c.transport() == "acp", c.fallback_reason()
    for want in ["perm ours=yes own=no fake=no again=yes", "entry=" + entry, "BADGE-77Q", "Hello"]:
        assert want in res.text, (want, res.text)
    assert '"name":"load_skill"' in res.text.replace(" ", "") and '"name":"skill"' not in res.text.replace(" ", "")
    info = c.acp_session()
    assert info is not None and info["served_calls"] == ["get_badge_number", "load_skill"]


async def test_acp_http_entry_with_bearer_token(tmp_path):
    o, rec, tk = await fake_acp_opts(tmp_path, FAKE_HTTP="1")
    c = create_cli_client(**o)
    try:
        res = await c.run("badge?")
        assert_fake_acp_run(c, res, "http")
        assert "noauth=401" in res.text
        log = rec.read_text()
        assert '"headers":[{"name":"Authorization","value":"Bearer ' in log
        assert '"terminal":false' in log
        assert 'fs-reply {"code": -32601' in log
        assert "Bearer " not in json.dumps(c.acp_session())
        # Stateless server: a GET (standalone SSE stream) is refused at once with
        # 405, never left open (an open GET stalled opencode's MCP client live).
        tok = c._acp.token
        req = urllib.request.Request(c.acp_session()["url"], headers={"Authorization": "Bearer " + tok})
        with pytest.raises(urllib.error.HTTPError) as ei:
            urllib.request.urlopen(req, timeout=5)
        assert ei.value.code == 405
    finally:
        c.close()
        await tk.close()


async def test_acp_stdio_bridge_when_agent_lacks_http(tmp_path):
    o, rec, tk = await fake_acp_opts(tmp_path, FAKE_HTTP="0")
    c = create_cli_client(**o)
    try:
        res = await c.run("badge?")
        assert_fake_acp_run(c, res, "<nil>")
        log = rec.read_text()
        assert '"env":[{"name":"TOOLNEXUS_MCP_TOKEN"' in log and '"type":"stdio"' not in log
    finally:
        c.close()
        await tk.close()


async def test_acp_project_mcp_file(tmp_path):
    o, _, tk = await fake_acp_opts(tmp_path, FAKE_HTTP="0", FAKE_PROJECT="1")
    o["style_def"].acp.project_mcp_file = ".mcp.json"
    c = create_cli_client(**o)
    try:
        res = await c.run("badge?")
        assert_fake_acp_run(c, res, "http")
        info = c.acp_session()
        assert info["mcp_servers_sent"] == [] and info["session_cwd"]
        f = os.path.join(info["session_cwd"], ".mcp.json")
        assert os.stat(f).st_mode & 0o777 == 0o600
        c.close()
        assert not os.path.exists(info["session_cwd"])
        with pytest.raises((urllib.error.URLError, ConnectionError, OSError)):
            urllib.request.urlopen(urllib.request.Request(info["url"], data=b"{}", method="POST"), timeout=2)
    finally:
        c.close()
        await tk.close()


async def test_acp_model_pass_through(tmp_path):
    o, rec, tk = await fake_acp_opts(tmp_path, FAKE_HTTP="1", FAKE_NO_CONFIG="1")
    c = create_cli_client(**o, model="anthropic/claude-x", transport=CliTransports.ACP)
    try:
        await c.run("badge?")
        log = rec.read_text()
        assert 'session/set_config_option {"configId":"model","sessionId":"s1","value":"anthropic/claude-x"}' in log
        assert 'session/set_model {"modelId":"anthropic/claude-x","sessionId":"s1"}' in log
    finally:
        c.close()
        await tk.close()


async def test_acp_falls_back_to_one_shot(tmp_path):
    o, _, tk = await fake_acp_opts(tmp_path, FAKE_FAIL_INIT="1", TN_FAKE_CLI="tool")
    o["style_def"] = dataclasses.replace(o["style_def"], args=[FAKE])
    c = create_cli_client(**o)
    try:
        assert c.transport() == "auto"
        res = await c.run("badge?", tk)
        assert c.transport() == "cli" and c.fallback_reason() and "BADGE-77Q" in res.text
        assert "unknown flag: acp" in c.fallback_reason()
    finally:
        c.close()
    o2, _, tk2 = await fake_acp_opts(tmp_path, FAKE_FAIL_INIT="1")
    c2 = create_cli_client(**o2, transport=CliTransports.ACP)
    try:
        with pytest.raises(CliError, match="^cli: acp: "):
            await c2.run("badge?")
    finally:
        c2.close()
        await tk.close()
        await tk2.close()


def test_is_served_call_exact_forms():
    served = {"get_badge_number", "load_skill"}
    for title, want in {
        "get_badge_number": True,
        "mcp__toolnexus__get_badge_number": True,
        "toolnexus/get_badge_number": True,
        "get_badge_number (toolnexus MCP Server)": True,
        "Calling get_badge_number from toolnexus": True,
        "load_skill(name: secret-recipe)": True,
        "skill": False,
        "Bash": False,
        "": False,
        "mcp__other__get_badge_number": False,
        "run get_badge_number and then rm -rf": False,
        "Calling get_badge_number from toolnexus2": False,
        "get_badge_number (evil MCP Server)": False,
        "mcp__toolnexus__get_badge_number_and_more": False,
    }.items():
        assert cli_is_served_call(title, served) is want, title


async def test_acp_system_hint_from_style(tmp_path):
    devin = cli_style_def(CliStyles.DEVIN)
    assert devin.acp is not None and '"toolnexus"' in devin.acp.system_hint
    o, rec, tk = await fake_acp_opts(tmp_path, FAKE_HTTP="1")
    o["style_def"].acp.system_hint = "HINT-USE-TOOLNEXUS"
    c = create_cli_client(**o, system_prompt="You are terse.")
    try:
        await c.run("badge?")
        assert "You are terse.\\n\\nHINT-USE-TOOLNEXUS" in rec.read_text()
    finally:
        c.close()
        await tk.close()
    got = cli_with_system_hint([{"role": "user", "content": "x"}], "H")
    assert len(got) == 2 and got[0] == {"role": "system", "content": "H"}
    msgs = [{"role": "system", "content": "S"}]
    cli_with_system_hint(msgs, "H")
    assert msgs[0]["content"] == "S"


async def test_acp_hooks_run_at_the_server_boundary(tmp_path):
    seen = []
    hooks = {
        "before_tool": lambda ev: seen.append(("before", ev["name"])),
        "after_tool": lambda ev: seen.append(("after", ev["name"])),
    }
    o, _, tk = await fake_acp_opts(tmp_path, FAKE_HTTP="1")
    c = create_cli_client(**o, hooks=hooks)
    try:
        res = await c.run("badge?")
        assert res.usage["total_tokens"] == 0
    finally:
        c.close()
        await tk.close()
    assert ("before", "get_badge_number") in seen and ("after", "skill") in seen


# --------------------------------------------------------------------------- #
# conformance/cli-client/cases.json — the fixture every port replays.
# --------------------------------------------------------------------------- #


def test_conformance_cases():
    with open(CASES, encoding="utf-8") as f:
        fx = json.load(f)
    by_name = {s.value: s for s in CliStyles}
    for case in fx["argv"]:
        c = create_cli_client(
            style=by_name[case["style"]], command=case["command"], model=case["model"] or None,
            transport=CliTransports.CLI,
        )
        argv, out, tmp = c._argv(case["prompt"])
        for t in tmp:
            if os.path.exists(t):
                os.remove(t)
        argv = ["<out>" if out and a == out else "<file>" if tmp and a == tmp[0] and a != out else a for a in argv]
        assert argv == case["argv"], case
        assert (c._style.prompt == "stdin") == case["stdin"], case

    def norm(v):
        if isinstance(v, str):
            try:
                return json.loads(v)
            except ValueError:
                return v
        return v

    for case in fx["parse"]:
        r = parse_cli_reply(case["in"])
        if "toolCall" in case:
            want = case["toolCall"]
            calls = r.get("tool_calls") or []
            assert len(calls) == 1, case
            assert calls[0]["name"] == want["name"] and calls[0].get("id", "") == want.get("id", "")
            assert norm(calls[0]["arguments"]) == want["arguments"]
        else:
            assert r == {"content": case["content"]}, case

    for case in fx["constructionErrors"]:
        o = dict(case["options"])
        kw = {}
        if "style" in o:
            kw["style"] = by_name[o["style"]]
        for k in ("command", "model", "prompt", "answer", "args"):
            if k in o:
                kw[k] = o[k]
        if "transport" in o:
            kw["transport"] = {"cli": CliTransports.CLI, "acp": CliTransports.ACP}[o["transport"]]
        with pytest.raises(CliError) as ei:
            create_cli_client(**kw)
        assert str(ei.value) == case["error"], case

    assert render_cli_prompt(None, None) == fx["renderEmpty"].replace("<PREAMBLE>", CLI_PREAMBLE, 1)
