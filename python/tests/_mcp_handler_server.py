"""Test helper: run an ASGI app under uvicorn (a dependency of the ``mcp`` SDK) on an
ephemeral local port, in a background thread, for real-HTTP MCP handler tests."""
from __future__ import annotations

import threading
import time
from contextlib import contextmanager
from typing import Any, Iterator

import uvicorn


@contextmanager
def running(app: Any) -> Iterator[str]:
    config = uvicorn.Config(app, host="127.0.0.1", port=0, log_level="error", lifespan="off")
    server = uvicorn.Server(config)
    t = threading.Thread(target=server.run, daemon=True)
    t.start()
    deadline = time.time() + 10
    while not server.started:
        if time.time() > deadline:
            raise RuntimeError("uvicorn did not start")
        time.sleep(0.01)
    port = server.servers[0].sockets[0].getsockname()[1]
    try:
        yield f"http://127.0.0.1:{port}/"
    finally:
        server.should_exit = True
        server.force_exit = True
        t.join(timeout=5)
