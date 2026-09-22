#!/usr/bin/env python3
"""issue-93 harness: run all SEVEN ports' skill loaders over the same corpus and
compare which SKILL.md files each one accepts.

  python3 harness.py fixtures            # the fixture matrix
  python3 harness.py ~/.claude/skills    # a real corpus (counts + divergences)

No network, no LLM. Each port is invoked through a tiny runner in runners/<port>/
that calls that port's own ListSkills and prints {skills:[{location}],skipped:[{location,reason}]}.
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
VENV_PY = "/tmp/claude-501/i93venv/bin/python"
JAR = os.path.join(REPO, "java", "build", "libs", "toolnexus-0.18.1.jar")
SNAKE = subprocess.run(
    ["bash", "-c", "find ~/.gradle/caches -name 'snakeyaml-2.3.jar' 2>/dev/null | head -1"],
    capture_output=True, text=True).stdout.strip()

PORTS = ["js", "python", "golang", "java", "csharp", "elixir", "clojure"]


def cmd(port, root):
    if port == "golang":
        return (["go", "run", ".", root], os.path.join(HERE, "runners", "go"))
    if port == "js":
        return (["node", os.path.join(HERE, "runners", "node", "run.mjs"), root], HERE)
    if port == "python":
        py = VENV_PY if os.path.exists(VENV_PY) else sys.executable
        return ([py, os.path.join(HERE, "runners", "python", "run.py"), root], HERE)
    if port == "java":
        cp = ":".join([os.path.join(HERE, "runners", "java", "out"), JAR, SNAKE])
        return (["java", "-cp", cp, "Run", root], HERE)
    if port == "csharp":
        return (["dotnet", "run", "--project", os.path.join(HERE, "runners", "csharp", "Run.csproj"),
                 "-v", "q", "--nologo", "--", root], HERE)
    if port == "elixir":
        return (["mix", "run", os.path.join(HERE, "runners", "elixir", "run.exs"), root],
                os.path.join(REPO, "elixir"))
    if port == "clojure":
        return (["clojure", "-M", os.path.join(HERE, "runners", "clojure", "run.clj"), root],
                os.path.join(REPO, "clojure"))
    raise ValueError(port)


def key(root, location):
    """Normalise a port's reported location to a stable per-skill key."""
    p = os.path.realpath(location)
    r = os.path.realpath(root)
    rel = os.path.relpath(p, r)
    return os.path.dirname(rel) or rel


def run(port, root):
    argv, cwd = cmd(port, root)
    out = subprocess.run(argv, cwd=cwd, capture_output=True, text=True)
    line = ""
    for ln in out.stdout.splitlines():
        if ln.startswith("{"):
            line = ln
    if not line:
        raise SystemExit(f"{port} runner produced no JSON:\n{out.stdout}\n{out.stderr}")
    data = json.loads(line)
    res = {}
    for s in data["skills"]:
        res[key(root, s["location"])] = "ok"
    for s in data["skipped"]:
        res[key(root, s["location"])] = s.get("reason") or "skip"
    return res


def main():
    root = os.path.abspath(os.path.expanduser(sys.argv[1] if len(sys.argv) > 1
                                              else os.path.join(HERE, "fixtures")))
    results = {}
    for p in PORTS:
        sys.stderr.write(f"running {p} ...\n")
        results[p] = run(p, root)

    keys = sorted({k for r in results.values() for k in r})
    verbose = len(keys) <= 40

    print(f"\ncorpus: {root}  ({len(keys)} SKILL.md)\n")
    hdr = ["fixture" if verbose else "skill"] + PORTS
    rows = []
    for k in keys:
        vals = [results[p].get(k, "-") for p in PORTS]
        if verbose or len({v == "ok" for v in vals}) > 1:
            rows.append([k] + vals)

    if not verbose:
        print("Rows shown = files the ports DISAGREE about.\n")
    widths = [max(len(str(r[i])) for r in [hdr] + rows) for i in range(len(hdr))]
    def line(r):
        return " | ".join(str(c).ljust(widths[i]) for i, c in enumerate(r))
    print(line(hdr))
    print("-|-".join("-" * w for w in widths))
    for r in rows:
        print(line(r))

    print("\ncounts (accepted / skipped):")
    for p in PORTS:
        ok = sum(1 for v in results[p].values() if v == "ok")
        sk = len(results[p]) - ok
        reasons = {}
        for v in results[p].values():
            if v != "ok":
                reasons[v] = reasons.get(v, 0) + 1
        print(f"  {p:8} {ok:4} ok / {sk:3} skipped   {reasons or ''}")


if __name__ == "__main__":
    main()
