import json

import pytest

import mcp2026 as mcp
from mcp2026.http import serve
from mcp2026.protocol import dumps


@pytest.fixture
def hundred(tmp_path):
    """10 services x 10 verbs, realistic descriptions + schemas."""
    services = [("github", "issue"), ("slack", "message"), ("jira", "ticket"), ("gmail", "email"), ("calendar", "event"),
                ("drive", "file"), ("stripe", "invoice"), ("postgres", "row"), ("s3", "object"), ("notion", "page")]
    verbs = ["create", "get", "list", "update", "delete", "search", "archive", "export", "share", "comment"]
    tools = [{"name": f"{s}_{v}_{o}",
              "description": f"{v} a {o} in {s}. Requires the workspace to be connected; returns the {o} as JSON with its id, timestamps and owner.",
              "inputSchema": {"type": "object", "properties": {
                  "id": {"type": "string", "description": f"The {o} id (omit when creating)."},
                  "query": {"type": "string", "description": "Free-text filter applied server-side."},
                  "body": {"type": "object", "description": f"Fields of the {o} to write."},
                  "options": {"type": "object", "properties": {"dryRun": {"type": "boolean"}, "limit": {"type": "integer"}}},
              }, "required": ["query"]}} for s, o in services for v in verbs]
    f = tmp_path / "f.json"
    f.write_text(json.dumps({"server": {"name": "big", "version": "1"}, "tools": tools, "skillsDir": "."}))
    with serve(mcp.Server.load(str(f))) as s:
        yield s.url + "/mcp"


def test_ranking_and_size(hundred):
    eager, lazy = mcp.Client(hundred), mcp.Client(hundred)
    lazy.tool_search, lazy.description = True, "SaaS workspace (GitHub, Slack, Jira, ...)"
    all_tools = eager.tools()
    assert len(all_tools) == 100
    meta = lazy.tools()
    for q, want in {"create slack message": "slack_create_message", "search stripe invoices": "stripe_search_invoice",
                    "archive a notion page": "notion_archive_page", "s3 export": "s3_export_object"}.items():
        hits = lazy.search_tools(q, 5)
        assert hits and hits[0]["name"] == want and hits[0]["server"] == lazy.description, q
    r = lazy.invoke("search_tools", {"query": "create slack message"})
    eb, lb = len(dumps(all_tools)), len(dumps(meta))
    print(f"tool JSON bytes: eager(100 tools)={eb} lazy(2 meta-tools)={lb} + one search result={len(dumps(r['content']))}")
    assert eb == 56421 and lb == 519  # same bytes as the Go spike
    assert lb * 10 < eb


def test_invoke_calls_found_tool(plain):
    c = mcp.Client(plain.url + "/mcp")
    c.tool_search = True
    hits = c.search_tools("echo text back", 3)
    assert hits and hits[0]["name"] == "echo"
    assert mcp.text(c.invoke("call_tool", {"name": "echo", "arguments": {"text": "found"}})) == "found"
    with pytest.raises(ValueError):
        c.invoke("echo", {})  # direct tool names are hidden in tool_search mode


def test_tokens():
    from mcp2026.client import tokens
    assert tokens("getUserID_v2 thing") == ["get", "user", "id", "v2", "thing"]
