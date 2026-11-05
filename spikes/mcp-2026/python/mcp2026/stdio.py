"""stdio transport (MCP 2026-07-28 basic/transports): one JSON-RPC message per line on stdin/stdout.
stderr is the server's log stream: passed through (or captured), never parsed. No headers, no auth."""
from __future__ import annotations

import json
import queue
import subprocess
import sys
import threading
from typing import IO

from .http import DEFAULT_TIMEOUT
from .protocol import CODE_UNSUPPORTED_PROTOCOL, META_VERSION, PROTOCOL_VERSION, RpcError


class StdioConn:
    def __init__(self, argv: list[str], env=None, cwd=None, stderr=None):
        self.proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=stderr, env=env, cwd=cwd)
        self._wlock = threading.Lock()
        self._lock = threading.Lock()
        self._pending: dict[str, queue.Queue] = {}
        self._err: Exception | None = None
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        for line in self.proc.stdout:
            try:
                m = json.loads(line)
            except ValueError:
                continue  # noise
            if not isinstance(m, dict) or m.get("method") or m.get("id") is None:
                continue  # not a response (notification / server request): ignored in this spike
            with self._lock:
                q = self._pending.pop(json.dumps(m["id"]), None)
            if q:
                q.put(m)
        with self._lock:
            self._err = ConnectionError("stdio server closed its stdout")
            waiting, self._pending = list(self._pending.values()), {}
        for q in waiting:
            q.put(None)

    def round_trip(self, rid: int, body: bytes) -> dict:
        """Writes one line, waits for the response with the same id. Never retried."""
        key = json.dumps(rid)
        q: queue.Queue = queue.Queue(1)
        with self._lock:
            if self._err:
                raise self._err
            self._pending[key] = q
        try:
            with self._wlock:
                self.proc.stdin.write(body + b"\n")  # json.dumps never emits a raw newline
                self.proc.stdin.flush()
        except OSError as e:
            with self._lock:
                self._pending.pop(key, None)
            raise ConnectionError(f"stdio write: {e}") from None
        try:
            m = q.get(timeout=DEFAULT_TIMEOUT)
        except queue.Empty:
            with self._lock:
                self._pending.pop(key, None)
            raise TimeoutError("stdio request timed out") from None
        if m is None:
            raise self._err or ConnectionError("stdio server closed")
        return m

    def close(self) -> None:
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(2)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()


def serve_stdio(server, inp: IO[bytes] | None = None, out: IO[bytes] | None = None) -> None:
    """Serves server as newline-delimited JSON-RPC until inp closes. Only the _meta version is checked."""
    inp = inp or sys.stdin.buffer
    out = out or sys.stdout.buffer
    for line in inp:
        try:
            req = json.loads(line)
            if not isinstance(req, dict):
                raise ValueError
        except ValueError:
            _emit(out, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})
            continue
        if req.get("id") is None:
            continue
        params = req.get("params") if isinstance(req.get("params"), dict) else {}
        meta = params.get("_meta") if isinstance(params.get("_meta"), dict) else {}
        mv = meta.get(META_VERSION) if isinstance(meta.get(META_VERSION), str) else ""
        msg: dict = {"jsonrpc": "2.0", "id": req["id"]}
        try:
            if mv != PROTOCOL_VERSION:
                raise RpcError(CODE_UNSUPPORTED_PROTOCOL, "Unsupported protocol version",
                               {"supported": [PROTOCOL_VERSION], "requested": mv})
            msg["result"] = server.dispatch(str(req.get("method") or ""), params)
        except RpcError as e:
            msg["error"] = e.to_json()
        _emit(out, msg)


def _emit(out, msg: dict) -> None:
    out.write(json.dumps(msg).encode() + b"\n")
    out.flush()
