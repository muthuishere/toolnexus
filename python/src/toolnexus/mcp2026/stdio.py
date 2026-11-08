"""stdio transport (§2.3): one JSON-RPC message per line, responses demultiplexed by ``id``.

stderr is passed through and never parsed; a dead child fails every pending call."""
from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator, Awaitable, Callable, Optional


class StdioClosed(ConnectionError):
    """The child closed its stdout (exited) before answering."""


class StdioConn:
    def __init__(
        self,
        argv: list[str],
        env: Optional[dict[str, str]] = None,
        cwd: Optional[str] = None,
        probe: bool = False,
    ):
        # probe=True: the reader thread exits right after delivering the first response, BEFORE it
        # issues another read — so the pipe can be handed to the frozen SDK path (§2.2a reuse) with
        # no reader left blocked that could steal the ``initialize`` reply. ``resume()`` restarts it.
        self._probe = probe
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
                if self._probe:
                    return  # stop consuming stdout; resume() or sdk_streams() takes over
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

    def resume(self) -> None:
        """Leaves probe mode: restarts the in-house reader (the 2026 path keeps this conn)."""
        self._probe = False
        threading.Thread(target=self._read, daemon=True).start()

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


def _loop_pipes() -> bool:
    """True when pipes can be awaited on the running event loop: POSIX asyncio only."""
    if sys.platform == "win32":
        return False
    import asyncio

    try:
        asyncio.get_running_loop()  # absent under trio: keep the thread path there
    except RuntimeError:
        return False
    return True


def _pipe_lines(out: Any) -> Callable[[], Awaitable[bytes]]:
    """Returns an async ``readline`` for a child's stdout. On POSIX the pipe is made
    non-blocking and awaited with the event loop's ``add_reader``, so a line costs no worker
    thread round-trip (two thread hand-offs per MCP call were most of the per-request
    overhead). Bytes the probe reader already buffered inside ``out`` are consumed first.
    On Windows the selector cannot watch pipes, so it falls back to a worker thread."""
    import anyio

    if not _loop_pipes():
        return lambda: anyio.to_thread.run_sync(out.readline, abandon_on_cancel=True)
    fd = out.fileno()
    os.set_blocking(fd, False)
    buf = bytearray()
    try:
        pending = out.peek(1)  # whatever the probe reader left buffered (b"" if none)
    except BlockingIOError:
        pending = b""
    if pending:
        buf.extend(out.read(len(pending)))
    eof = False

    async def readline() -> bytes:
        nonlocal eof
        while True:
            i = buf.find(b"\n")
            if i >= 0:
                line = bytes(buf[: i + 1])
                del buf[: i + 1]
                return line
            if eof:
                line = bytes(buf)
                buf.clear()
                return line
            try:
                chunk: Optional[bytes] = os.read(fd, 65536)
            except BlockingIOError:
                chunk = None
            if chunk is None:
                await _wait_fd(fd, readable=True)
                continue
            if not chunk:
                eof = True
                continue
            buf.extend(chunk)

    return readline


def _pipe_writer(stdin: Any, blocking_write: Callable[[bytes], None]) -> Callable[[bytes], Awaitable[None]]:
    """Returns an async writer for a child's stdin: a direct non-blocking ``os.write`` that
    waits on ``add_writer`` only when the pipe is full. Windows keeps the worker thread."""
    import anyio

    if not _loop_pipes():
        return lambda data: anyio.to_thread.run_sync(blocking_write, data)
    stdin.flush()
    fd = stdin.fileno()
    os.set_blocking(fd, False)

    async def write(data: bytes) -> None:
        view = memoryview(data)
        while view:
            try:
                n = os.write(fd, view)
            except BlockingIOError:
                await _wait_fd(fd, readable=False)
                continue
            view = view[n:]

    return write


async def _wait_fd(fd: int, *, readable: bool) -> None:
    import asyncio

    loop = asyncio.get_running_loop()
    fut = loop.create_future()
    add, remove = (loop.add_reader, loop.remove_reader) if readable else (loop.add_writer, loop.remove_writer)
    add(fd, lambda: fut.done() or fut.set_result(None))
    try:
        await fut
    finally:
        remove(fd)


@asynccontextmanager
async def sdk_streams(sc: StdioConn) -> AsyncIterator[tuple[Any, Any]]:
    """Hands a probed child to the ``mcp`` SDK: the ``(read, write)`` anyio memory streams
    ``mcp.ClientSession`` takes, fed from the child's already-open pipes (mirrors
    ``mcp.client.stdio.stdio_client``, minus the spawn). The probe reader thread must already have
    stopped; any bytes it buffered stay inside ``proc.stdout`` and are read here first. On exit the
    child is shut down like the SDK does: close stdin, wait, then kill."""
    import anyio
    import mcp.types as types

    try:
        from mcp.shared.message import SessionMessage
    except ImportError:  # pragma: no cover - very old mcp sent bare JSONRPCMessage
        SessionMessage = None  # type: ignore[assignment]

    proc = sc.proc
    assert proc.stdout is not None and proc.stdin is not None
    out, stdin = proc.stdout, proc.stdin
    read_writer, read_stream = anyio.create_memory_object_stream(0)
    write_stream, write_reader = anyio.create_memory_object_stream(0)

    lines = _pipe_lines(out)

    async def reader() -> None:
        try:
            async with read_writer:
                while True:
                    line = await lines()
                    if not line:
                        return
                    if not line.strip():
                        continue
                    try:
                        msg = types.JSONRPCMessage.model_validate_json(line)
                    except Exception as exc:  # noqa: BLE001 - surfaced to the session like the SDK
                        await read_writer.send(exc)
                        continue
                    await read_writer.send(SessionMessage(msg) if SessionMessage else msg)
        except (anyio.ClosedResourceError, anyio.BrokenResourceError):
            await anyio.lowlevel.checkpoint()

    def _write(data: bytes) -> None:
        stdin.write(data)
        stdin.flush()

    write = _pipe_writer(stdin, _write)

    async def writer() -> None:
        try:
            async with write_reader:
                async for sm in write_reader:
                    m = sm.message if SessionMessage else sm
                    data = (m.model_dump_json(by_alias=True, exclude_none=True) + "\n").encode()
                    try:
                        await write(data)
                    except OSError:
                        return
        except (anyio.ClosedResourceError, anyio.BrokenResourceError):
            await anyio.lowlevel.checkpoint()

    async with anyio.create_task_group() as tg:
        tg.start_soon(reader)
        tg.start_soon(writer)
        try:
            yield read_stream, write_stream
        finally:
            with anyio.CancelScope(shield=True):
                await anyio.to_thread.run_sync(sc.close)  # stdin close -> wait 2 s -> kill
            tg.cancel_scope.cancel()
            await read_stream.aclose()
            await write_stream.aclose()
