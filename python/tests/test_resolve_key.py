"""add-go-host-asks ask #8/#9 (SPEC §4C): an API key is only ever sent to the host it
belongs to; list_models. Mirrors golang/resolve_key_test.go."""
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit

import pytest

from toolnexus import list_models
from toolnexus.client import _resolve_key


@pytest.fixture
def keys(monkeypatch):
    monkeypatch.setenv("OPENROUTER_API_KEY", "fake-or")
    monkeypatch.setenv("OPENAI_API_KEY", "fake-oa")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "fake-an")
    monkeypatch.setenv("TN_TEST_KEY", "fake-custom")
    return monkeypatch


@pytest.mark.parametrize(
    "base,env_name,explicit,want",
    [
        ("https://api.openai.com/v1", None, None, "fake-oa"),
        ("https://api.anthropic.com", None, None, "fake-an"),
        ("https://openrouter.ai/api/v1", None, None, "fake-or"),
        ("https://API.OpenAI.com/v1", None, None, "fake-oa"),
        ("https://example.com/v1", None, "fake-explicit", "fake-explicit"),
        ("https://api.openai.com/v1", None, "fake-explicit", "fake-explicit"),
        ("https://example.com/v1", "TN_TEST_KEY", None, "fake-custom"),
    ],
)
def test_resolve_key_by_host(keys, base, env_name, explicit, want):
    assert _resolve_key(explicit, base, env_name) == want


@pytest.mark.parametrize(
    "base",
    [
        "https://example.com/v1",  # unknown host
        "http://api.openai.com/v1",  # plain http
        "https://api.openai.com.evil.test/",  # lookalike
        "https://evil-openrouter.ai/v1",  # not a subdomain
        "http://localhost:11434/v1",
    ],
)
def test_never_a_cross_provider_fallback(keys, base):
    with pytest.raises(RuntimeError) as ei:
        _resolve_key(None, base)
    assert "fake-" not in str(ei.value)


def test_right_env_var_unset_names_it(keys):
    keys.setenv("OPENAI_API_KEY", "")
    with pytest.raises(RuntimeError, match="OPENAI_API_KEY"):
        _resolve_key(None, "https://api.openai.com/v1")


class _Models(BaseHTTPRequestHandler):
    seen: dict = {}

    def log_message(self, *a):  # quiet
        pass

    def do_GET(self):  # noqa: N802
        u = urlsplit(self.path)
        _Models.seen["path"] = u.path + "?" + u.query
        if self.headers.get("x-api-key"):
            _Models.seen["auth"] = "x-api-key"
            if not parse_qs(u.query).get("after_id"):
                body = {"data": [{"id": "claude-b", "display_name": "B"}], "has_more": True, "last_id": "claude-b"}
            else:
                body = {"data": [{"id": "claude-a", "display_name": "A"}], "has_more": False}
        else:
            _Models.seen["auth"] = self.headers.get("Authorization")
            body = {"data": [{"id": "z/model", "name": "Z", "context_length": 200000}, {"id": "a/model"}]}
        raw = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


async def test_list_models():
    srv = HTTPServer(("127.0.0.1", 0), _Models)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    try:
        ms = await list_models(base_url=base + "/api/v1", api_key="fake")
        assert [m.id for m in ms] == ["a/model", "z/model"]
        assert ms[1].context_length == 200000 and ms[1].name == "Z"
        assert _Models.seen["auth"] == "Bearer fake" and _Models.seen["path"] == "/api/v1/models?"
        ms = await list_models(base_url=base, style="anthropic", api_key="fake")
        assert [m.id for m in ms] == ["claude-a", "claude-b"] and ms[1].name == "B"
        assert _Models.seen["auth"] == "x-api-key"
        with pytest.raises(RuntimeError):
            await list_models(base_url=base)  # unknown host without api_key
    finally:
        srv.shutdown()
