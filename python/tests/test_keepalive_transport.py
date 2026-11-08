"""The default LLM transport reuses one keep-alive connection per thread.

Pins: a connection the server closed while idle is retried once on a fresh one
(not surfaced as an error); HTTP errors still carry status and Retry-After; and
connections really are reused when the server keeps them open.
"""
from __future__ import annotations

import json
import socket
import threading

import pytest

from toolnexus import client as tc


def _serve(handler):
    srv = socket.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(8)
    accepted: list[int] = []

    def loop():
        while True:
            try:
                conn, _ = srv.accept()
            except OSError:
                return
            accepted.append(1)
            threading.Thread(target=handler, args=(conn,), daemon=True).start()

    threading.Thread(target=loop, daemon=True).start()
    return srv, f"http://127.0.0.1:{srv.getsockname()[1]}/v1/chat/completions", accepted


def _read_request(conn) -> bool:
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = conn.recv(65536)
        if not chunk:
            return False
        data += chunk
    head, _, body = data.partition(b"\r\n\r\n")
    length = 0
    for line in head.split(b"\r\n"):
        if line.lower().startswith(b"content-length:"):
            length = int(line.split(b":")[1])
    while len(body) < length:
        body += conn.recv(65536)
    return True


def _reply(conn, status: int, body: dict, extra: str = "") -> None:
    raw = json.dumps(body).encode()
    conn.sendall(
        f"HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {len(raw)}\r\n{extra}\r\n".encode()
        + raw
    )


@pytest.fixture(autouse=True)
def _fresh_pool():
    tc._KEEPALIVE.__dict__.clear()
    yield
    tc._KEEPALIVE.__dict__.clear()


def test_stale_idle_connection_is_retried_once():
    # One response per connection, then a silent close — no "Connection: close"
    # header, so the client believes the socket is still reusable.
    def handler(conn):
        if _read_request(conn):
            _reply(conn, 200, {"ok": True})
        conn.close()

    srv, url, accepted = _serve(handler)
    try:
        assert tc._post(url, {}, {"q": 1}, 5) == {"ok": True}
        assert tc._post(url, {}, {"q": 2}, 5) == {"ok": True}  # stale socket -> retried
        assert len(accepted) == 2
    finally:
        srv.close()


def test_connection_is_reused_when_kept_alive():
    def handler(conn):
        while _read_request(conn):
            _reply(conn, 200, {"ok": True})
        conn.close()

    srv, url, accepted = _serve(handler)
    try:
        for i in range(5):
            assert tc._post(url, {}, {"q": i}, 5) == {"ok": True}
        assert len(accepted) == 1
    finally:
        srv.close()


def test_http_error_keeps_status_and_retry_after():
    def handler(conn):
        while _read_request(conn):
            _reply(conn, 429, {"error": "slow down"}, "Retry-After: 7\r\n")
        conn.close()

    srv, url, _ = _serve(handler)
    try:
        with pytest.raises(tc.ProviderError) as ei:
            tc._post(url, {}, {"q": 1}, 5)
        assert ei.value.status == 429
        assert ei.value.retry_after == "7"
        assert "slow down" in ei.value.text
    finally:
        srv.close()


def test_server_closing_a_fresh_connection_is_an_error_not_a_loop():
    # A brand-new connection that dies is a real failure: no retry.
    def handler(conn):
        _read_request(conn)
        conn.close()

    srv, url, accepted = _serve(handler)
    try:
        with pytest.raises(Exception):
            tc._post(url, {}, {"q": 1}, 5)
        assert len(accepted) == 1
    finally:
        srv.close()
