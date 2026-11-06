"""stdio transport (§2.3): one JSON-RPC message per line, responses demultiplexed by ``id``.

stderr is passed through and never parsed; a dead child fails every pending call."""
from __future__ import annotations

import json
import queue
import subprocess
import threading
from typing import Any, Optional


class StdioClosed(ConnectionError):
    """The child closed its stdout (exited) before answering."""


class StdioConn:
    def __init__(self, argv: list[str], env: Optional[dict[str, str]] = None, cwd: Optional[str] = None):
        self.proc = subprocess.Popen(
            argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=None, env=env, cwd=cwd
        )
        self._wlock = threading.Lock()
        self._lock = threading.Lock()
        self._pending: dict[str, queue.Queue[Any]] = {}
        self._err: Optional[Exception] = None
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            try:
                m = json.loads(line)
            except ValueError:
                continue  # noise on stdout
            if not isinstance(m, dict) or m.get("method") or m.get("id") is None:
                continue  # notifications / server requests: not used on the 2026 path
            with self._lock:
                q = self._pending.pop(json.dumps(m["id"]), None)
            if q is not None:
                q.put(m)
        with self._lock:
            self._err = StdioClosed("stdio server closed its stdout")
            waiting, self._pending = list(self._pending.values()), {}
        for q in waiting:
            q.put(None)

    def round_trip(self, rid: int, body: bytes, timeout: float) -> dict[str, Any]:
        """Writes one line and waits for the response with the same id. Never retried."""
        key = json.dumps(rid)
        q: queue.Queue[Any] = queue.Queue(1)
        with self._lock:
            if self._err is not None:
                raise self._err
            self._pending[key] = q
        try:
            with self._wlock:
                assert self.proc.stdin is not None
                self.proc.stdin.write(body + b"\n")  # json.dumps never emits a raw newline
                self.proc.stdin.flush()
        except OSError as e:
            with self._lock:
                self._pending.pop(key, None)
            raise StdioClosed(f"stdio write: {e}") from None
        try:
            m = q.get(timeout=timeout)
        except queue.Empty:
            with self._lock:
                self._pending.pop(key, None)
            raise TimeoutError("stdio request timed out") from None
        if m is None:
            raise self._err or StdioClosed("stdio server closed")
        return m

    def close(self) -> None:
        try:
            if self.proc.stdin is not None:
                self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(2)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait()

    def kill(self) -> None:
        """Terminates the probe child at once (fallback path starts its own process)."""
        try:
            self.proc.kill()
        except OSError:
            pass
        try:
            self.proc.wait(2)
        except subprocess.TimeoutExpired:
            pass
        # stdout is left to the reader thread: closing a pipe another thread is blocked
        # reading would wait for every holder of its write end (e.g. a grandchild).
        try:
            if self.proc.stdin is not None:
                self.proc.stdin.close()
        except OSError:
            pass
