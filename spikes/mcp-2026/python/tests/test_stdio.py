"""The stdio server for these tests is a tiny python -c that serves the fixture (TEST ONLY)."""
import io
import subprocess
import sys
import threading

import pytest

import mcp2026 as mcp

SERVER = ("import sys; print('stdio-test-server ready {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}', file=sys.stderr, flush=True);"
          "import mcp2026 as m; m.serve_stdio(m.fixture_server())")


def stdio_client(stderr=subprocess.DEVNULL, code=SERVER):
    return mcp.Client.stdio([sys.executable, "-c", code], stderr=stderr)


def test_same_eight_steps():
    clients = []

    def mk(_):
        clients.append(stdio_client())
        return clients[-1]

    out = []
    try:
        assert mcp.run_checks("", mk, out=out.append), out
    finally:
        for c in clients:
            c.close()
    assert out.count("SKIP header mismatch -> -32020 (not applicable over stdio)") == 1


def test_stderr_captured_not_parsed_and_concurrent(tmp_path):
    log = open(tmp_path / "err", "w+b")
    c = stdio_client(stderr=log)
    errs = []

    def go(i):
        try:
            assert mcp.text(c.call_tool("echo", {"text": f"msg-{i}"})) == f"msg-{i}"
        except Exception as e:  # pragma: no cover
            errs.append(e)

    ts = [threading.Thread(target=go, args=(i,)) for i in range(20)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    c.close()
    assert not errs
    assert "stdio-test-server ready" in (tmp_path / "err").read_text()


def test_server_exit_is_an_error():
    c = stdio_client(code="import sys; sys.exit(3)")
    with pytest.raises(ConnectionError):
        c.call("tools/list")
    c.close()


def test_serve_stdio_unit():
    inp = io.BytesIO(b'not json\n{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{"_meta":{}}}\n')
    out = io.BytesIO()
    mcp.serve_stdio(mcp.fixture_server(), inp, out)
    lines = out.getvalue().decode().splitlines()
    assert '"code": -32700' in lines[0] and '"code": -32022' in lines[1]
