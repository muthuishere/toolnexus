#!/usr/bin/env python3
"""A stdio MCP server that ACCEPTS a connection and then NEVER RESPONDS.

Used by resilience scenario R3 (MCP server hangs). It reads and discards every
line on stdin and answers nothing — not even `initialize` — so a connecting
client blocks until its per-server `timeout` fires. A correct port must bound
the connect by that timeout, mark only this server `"failed"`, and continue.

Stdlib only, so it runs under any python (launched with MCP_PYTHON like the
shared benchmark MCP server).
"""
from __future__ import annotations

import sys
import time


def main() -> None:
    # Drain stdin forever without ever writing a response.
    try:
        for _line in sys.stdin:
            pass
    except Exception:  # noqa: BLE001
        pass
    # If stdin closes, just idle so the process stays alive (a hang, not an exit).
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
