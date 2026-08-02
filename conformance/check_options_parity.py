#!/usr/bin/env python3
"""Cross-port option-parity check.

Every logical option in options_manifest.json must appear in every port's
options-definition files (matched by a normalized alias). This guards against the
class of silent drift where an option ships in five ports but not the sixth — the
JS `disableTools`/`disableSkills` gap that sat undetected until found by hand.

Normalization: lowercase, strip every non-alphanumeric character. So `onError`,
`on_error`, and `OnError` all normalize to `onerror`; the `http client` option is
`fetch` (JS) / `http_transport` (Python) / `HTTPClient` (Go) / `HttpClient` (C#) /
`http_options` (Elixir) — all listed as aliases of one logical option.

TIERS. A port is held to the tier declared in the manifest's `portTiers`, never
one it declares about itself — a self-graded exam is not a gate. `full` = every
option; `core` = the SPEC §0 conformance contract only, with full-tier options
recorded as DEBT and printed on every run, pass or fail. A permitted absence
that stops being mentioned is indistinguishable from one that was implemented.
Missing a CORE option fails for every port regardless of tier (verified: forcing
`hooks` to core made the core-tier port fail, exit 1).

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
# Clojure identifiers are kebab-case, and `-` is a legal name character rather
# than an operator. Tokenizing `.cljc` with the rule above splits `:base-url`
# into `base` and `url`, so the port could never match `baseUrl` no matter what
# it implements — every option would report MISSING. Applied by extension, not
# globally: in a C-family language `a-b` really is subtraction.
_IDENT_LISP = re.compile(r"[A-Za-z_*][A-Za-z0-9_*!?<>=-]*")


def normalize(token: str) -> str:
    return re.sub(r"[^a-z0-9]", "", token.lower())


def normalized_tokens(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    pattern = _IDENT_LISP if path.suffix in (".cljc", ".clj", ".cljs") else _IDENT
    return {normalize(t) for t in pattern.findall(text)}


def check_group(name: str, group: dict, tiers: dict) -> tuple[list[str], list[str]]:
    """Returns (failures, debt). A port held to tier 'core' may be missing a
    'full' option — that is recorded as DEBT and printed, never as a pass.
    Missing a 'core' option is a failure for every port."""
    failures: list[str] = []
    debt: list[str] = []
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
            if aliases & tokens:
                continue
            msg = (f"[{name}] {port}: MISSING option '{opt['name']}' "
                   f"(no alias {sorted(opt['aliases'])} in {files[port]})")
            if opt.get("tier", "full") == "full" and tiers.get(port, "full") == "core":
                debt.append(f"[{name}] {port}: {opt['name']}")
            else:
                failures.append(msg)
    return failures, debt


def main() -> int:
    manifest = json.loads(MANIFEST.read_text())
    tiers = {k: v for k, v in manifest.get("portTiers", {}).items() if not k.startswith("_")}
    failures: list[str] = []
    debt: list[str] = []
    for group_name in ("clientOptions", "toolkitOptions"):
        f, d = check_group(group_name, manifest[group_name], tiers)
        failures += f
        debt += d

    # Printed on EVERY run, pass or fail. A permitted absence that stops being
    # mentioned is indistinguishable from one that was implemented.
    if debt:
        by_port: dict[str, list[str]] = {}
        for row in debt:
            port = row.split(": ")[0].split()[-1]
            by_port.setdefault(port, []).append(row.split(": ", 1)[1])
        print("Tier debt (permitted absences, NOT passes):")
        for port, opts in sorted(by_port.items()):
            print(f"  {port} [tier={tiers.get(port)}]: {len(opts)} absent — {', '.join(sorted(opts))}")
        print()

    if failures:
        print("Option-parity FAILURES (an option is missing in a port):\n")
        for f in failures:
            print("  " + f)
        print(f"\n{len(failures)} problem(s). Add the option to the port, or fix the manifest.")
        return 1

    n_client = len(manifest["clientOptions"]["options"])
    n_toolkit = len(manifest["toolkitOptions"]["options"])
    n_ports = len(manifest["clientOptions"]["files"])
    full = sorted(p for p, t in tiers.items() if t == "full")
    core = sorted(p for p, t in tiers.items() if t == "core")
    print(f"Option parity OK: {n_client} client + {n_toolkit} toolkit options "
          f"across {n_ports} ports "
          f"({len(full)} at tier full, {len(core)} at tier core: {', '.join(core) or 'none'}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
