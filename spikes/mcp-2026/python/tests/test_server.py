import json

import mcp2026 as mcp
from mcp2026.client import encode_header
from mcp2026.http import HttpClient, serve

H = {"Content-Type": "application/json", "MCP-Protocol-Version": "2026-07-28"}


def post(url, method, params=None, rid=1, headers=None):
    p = {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}, **(params or {})}
    body = json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": p})
    r = HttpClient().request("POST", url, body, {**H, "Mcp-Method": method, **(headers or {})})
    return r.status, (json.loads(r.body) if r.body else None)


def test_http_edges(plain):
    u = plain.url + "/mcp"
    assert HttpClient().get(u).status == 405
    assert HttpClient().get(plain.url + "/nope").status == 404
    assert HttpClient().request("POST", u, "{bad", H).status == 400
    assert HttpClient().request("POST", u, b"{}", {**H, "Content-Length": str(5 << 20)}).status == 413  # refused unread
    assert post(u, "tools/list", rid=None)[0] == 202  # notification
    st, msg = post(u, "nope/nope")
    assert st == 404 and msg["error"]["code"] == mcp.CODE_METHOD_NOT_FOUND
    st, msg = post(u, "tools/list", headers={"MCP-Protocol-Version": ""})
    assert st == 400 and msg["error"]["code"] == mcp.CODE_HEADER_MISMATCH


def test_server_info_in_meta_and_cache_hints(plain):
    st, msg = post(plain.url + "/mcp", "server/discover")
    r = msg["result"]
    assert r["_meta"]["io.modelcontextprotocol/serverInfo"]["name"] == "toolnexus-spike"
    assert r["ttlMs"] and r["cacheScope"] == "public" and r["resultType"] == "complete"


def test_base64_mcp_name_and_code_tools():
    srv = mcp.Server().tool("héllo tool", "unicode name", lambda a, c: {"n": 1})
    assert encode_header("héllo tool").startswith("=?base64?")
    assert encode_header(" padded") != " padded" and encode_header("plain") == "plain"
    with serve(srv) as s:
        r = mcp.Client(s.url + "/mcp").call_tool("héllo tool")
        assert r["structuredContent"] == {"n": 1} and mcp.text(r) == '{"n":1}'


def test_tool_error_is_a_result():
    def boom(a, c):
        raise RuntimeError("boom")

    with serve(mcp.Server().tool("boom", "fails", boom)) as s:
        r = mcp.Client(s.url + "/mcp").call_tool("boom")
        assert r["isError"] is True and mcp.text(r) == "boom"


def test_missing_result_type_is_complete_and_sse():
    def app(environ, start_response):
        req = json.loads(environ["wsgi.input"].read(int(environ["CONTENT_LENGTH"])))
        start_response("200 OK", [("Content-Type", "text/event-stream")])
        return [b"event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n",
                f'data: {{"jsonrpc":"2.0","id":{req["id"]},"result":{{"tools":[]}}}}\n\n'.encode()]

    with serve(app) as s:
        assert mcp.Client(s.url + "/mcp").call("tools/list") == {"tools": [], "resultType": "complete"}
