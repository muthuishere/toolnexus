#!/usr/bin/env python3
"""Cross-port option-parity check.

Every logical option in options_manifest.json must appear in all six ports'
options-definition files (matched by a normalized alias). This guards against the
class of silent drift where an option ships in five ports but not the sixth — the
JS `disableTools`/`disableSkills` gap that sat undetected until found by hand.

Normalization: lowercase, strip every non-alphanumeric character. So `onError`,
`on_error`, and `OnError` all normalize to `onerror`; the `http client` option is
`fetch` (JS) / `http_transport` (Python) / `HTTPClient` (Go) / `HttpClient` (C#) /
`http_options` (Elixir) — all listed as aliases of one logical option.

Exit 0 = parity holds; exit 1 = a port is missing an option (printed).
Run from the repo root: `python3 conformance/check_options_parity.py`.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = Path(__file__).resolve().parent / "options_manifest.json"

_IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def normalize(token: str) -> str:
    return re.sub(r"[^a-z0-9]", "", token.lower())


def normalized_tokens(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    return {normalize(t) for t in _IDENT.findall(text)}


def check_group(name: str, group: dict) -> list[str]:
    failures: list[str] = []
    files = group["files"]
    token_sets: dict[str, set[str]] = {}
    for port, rel in files.items():
        p = ROOT / rel
        if not p.exists():
            failures.append(f"[{name}] {port}: options file not found: {rel}")
            token_sets[port] = set()
            continue
        token_sets[port] = normalized_tokens(p)

    for opt in group["options"]:
        aliases = {normalize(a) for a in opt["aliases"]}
        for port, tokens in token_sets.items():
            if not (aliases & tokens):
                failures.append(
                    f"[{name}] {port}: MISSING option '{opt['name']}' "
                    f"(no alias {sorted(opt['aliases'])} in {files[port]})"
                )
    return failures


def main() -> int:
    manifest = json.loads(MANIFEST.read_text())
    failures: list[str] = []
    for group_name in ("clientOptions", "toolkitOptions"):
        failures += check_group(group_name, manifest[group_name])

    if failures:
        print("Option-parity FAILURES (an option is missing in a port):\n")
        for f in failures:
            print("  " + f)
        print(f"\n{len(failures)} problem(s). Add the option to the port, or fix the manifest.")
        return 1

    n_client = len(manifest["clientOptions"]["options"])
    n_toolkit = len(manifest["toolkitOptions"]["options"])
    print(f"Option parity OK: {n_client} client + {n_toolkit} toolkit options present in all 6 ports.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
