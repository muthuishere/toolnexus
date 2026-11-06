"""python -m mcp2026 server | client <url> | selftest"""
import os
import sys

from . import fixture_server, run_checks, serve


def main() -> int:
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "server":
        port = int(os.environ.get("PORT") or 7102)
        print(f"listening on http://127.0.0.1:{port}/mcp", flush=True)
        try:
            serve(fixture_server(), port=port, block=True)
        except KeyboardInterrupt:
            pass
        return 0
    if cmd == "client":
        return 0 if run_checks(sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:7102/mcp") else 1
    if cmd == "selftest":
        with serve(fixture_server()) as s:
            return 0 if run_checks(s.url + "/mcp") else 1
    print("usage: run.sh server | client <url> | selftest")
    return 2


sys.exit(main())
