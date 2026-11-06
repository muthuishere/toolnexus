import json
import os

import pytest

import mcp2026 as mcp
from mcp2026.http import serve

OK = {
    "skill://hello-world/SKILL.md": ("hello-world", "SKILL.md"),
    "skill://a/refs/doc.md": ("a", "refs/doc.md"),
    "skill://a/with%20space.md": ("a", "with space.md"),
    "skill://a/..dots-in-name..x.md": ("a", "..dots-in-name..x.md"),
}
BAD = ["skill://a/../b", "skill://a/%2e%2e/b", "skill://a/%2E%2E%2Fetc", "skill://%2e%2e/x",
       "skill://a/..%2F..%2Fetc%2Fpasswd", "skill://a/%252e%252e/x", "skill://a/%252F", "skill://a/x%5C..%5Cy",
       "skill://a//etc", "skill:///etc/passwd", "skill://a/%2Fetc", "skill://a/%00", "skill://a/C:/x",
       "skill://a/.", "skill://a", "skill://a/%zz", "file:///etc/passwd"]


@pytest.mark.parametrize("uri", OK)
def test_parse_ok(uri):
    assert mcp.parse_skill_uri(uri) == OK[uri]


@pytest.mark.parametrize("uri", BAD)
def test_parse_rejects(uri):
    with pytest.raises(ValueError):
        mcp.parse_skill_uri(uri)


@pytest.mark.parametrize("method", ["resources/read", "skills/get"])
def test_server_rejects_traversal(plain, method):
    with pytest.raises(mcp.RpcError) as ei:
        mcp.Client(plain.url + "/mcp").call(method, {"uri": "skill://hello-world/%2e%2e/%2e%2e/fixture.json"})
    assert ei.value.code == mcp.CODE_INVALID_PARAMS


def test_download_skill(plain, tmp_path):
    mcp.Client(plain.url + "/mcp").download_skill("skill://hello-world/SKILL.md", str(tmp_path))
    assert (tmp_path / "hello-world" / "SKILL.md").read_text()


@pytest.mark.parametrize("evil", ["skill://hello-world/%2e%2e%2f%2e%2e%2fpwned", "skill://hello-world/..%2F..%2Fpwned",
                                  "skill://hello-world/%252e%252e/pwned", "skill://other-skill/pwned"])
def test_download_refuses_hostile_resource(tmp_path, evil):
    """A hostile server lists a resource that decodes to ../../pwned: nothing may be written."""
    def app(environ, start_response):
        req = json.loads(environ["wsgi.input"].read(int(environ.get("CONTENT_LENGTH") or 0)))
        res = {"contents": [{"uri": evil, "text": "pwned"}]}
        if req["method"] == "skills/get":
            res = {"skill": {"uri": "skill://hello-world/SKILL.md",
                             "resources": [{"uri": "skill://hello-world/SKILL.md"}, {"uri": evil}]}}
        start_response("200 OK", [("Content-Type", "application/json")])
        return [json.dumps({"jsonrpc": "2.0", "id": req["id"], "result": res}).encode()]

    with serve(app) as s:
        with pytest.raises(ValueError):
            mcp.Client(s.url + "/mcp").download_skill("skill://hello-world/SKILL.md", str(tmp_path / "a" / "b"))
    assert [f for _, _, fs in os.walk(tmp_path) for f in fs] == []
