#!/usr/bin/env python3
"""Prototype of issue-93 option (1), in the SPIKE only — never library source.

The issue proposes "line-wise `key: rest-of-line` for the two known scalars, with
YAML fallback". This prototype INVERTS that order, which is the whole finding:

    YAML FIRST. Line-wise only as a fallback, and only for `name`/`description`.

Line-wise first would misparse legitimate YAML that the six YAML ports handle
today — `description: |`, `description: >`, a plain scalar continued on the next
line, `name: !!str x` — because rest-of-line is empty or a block marker there.
Running it only on files a real YAML parser has ALREADY refused means it can
never see a file whose YAML semantics matter: by construction those files have no
YAML semantics to preserve.

The fallback also refuses to take a value that opens a construct it does not
implement (`| > & * [ { !`) or that is empty, so a half-broken block scalar
degrades to "no description", never to garbage.

Usage:  python3 lenient.py <skills-dir>     # prints  <status>\t<reason>\t<dir>
"""
import os
import re
import sys

import yaml

FRONTMATTER = re.compile(r"^---\r?\n(.*?)\r?\n---\r?\n?(.*)$", re.DOTALL)
# Column 0, ordinary key characters, one `:`; the value is the rest of the line.
LINE = re.compile(r"^([A-Za-z0-9_][A-Za-z0-9_.-]*):[ \t]*(.*)$")
LENIENT_KEYS = ("name", "description")
OPENERS = set("|>&*[{!")


def _unquote(v):
    v = v.strip()
    if len(v) > 1 and v[0] == v[-1] and v[0] in "\"'":
        return v[1:-1]
    return v


def _line_wise(block):
    """Rescue `name`/`description` from a frontmatter block YAML refused."""
    data = {}
    for raw in block.splitlines():
        if raw[:1] in (" ", "\t", "#", ""):
            continue  # indented continuation, comment, blank — not a top-level key
        m = LINE.match(raw)
        if not m:
            continue
        key, value = m.group(1), m.group(2).strip()
        if key not in LENIENT_KEYS or key in data:
            continue  # first wins, and only the two known scalars
        if not value or value[0] in OPENERS:
            continue  # a construct we do not implement — take nothing, not garbage
        data[key] = _unquote(value)
    return data


def parse(text):
    """-> (data, content, reason|None). reason is a toolnexus skip reason."""
    m = FRONTMATTER.match(text)
    if not m:
        return {}, text, "missing-name"
    block, body = m.group(1), m.group(2)
    try:
        parsed = yaml.safe_load(block)
        if isinstance(parsed, dict):
            data = {str(k): str(v).strip() for k, v in parsed.items()
                    if isinstance(v, (str, int, float, bool)) or v is None}
            return data, body, None if data.get("name") else "missing-name"
    except yaml.YAMLError:
        pass
    data = _line_wise(block)
    if not data.get("name"):
        return data, body, "malformed-frontmatter"
    return data, body, None


def main():
    root = os.path.abspath(os.path.expanduser(sys.argv[1]))
    for dirpath, _dirs, files in os.walk(root, followlinks=True):
        if "SKILL.md" not in files:
            continue
        p = os.path.join(dirpath, "SKILL.md")
        try:
            text = open(p, encoding="utf-8", errors="replace").read()
        except OSError:
            print(f"skip\tunreadable\t{p}")
            continue
        data, _body, reason = parse(text)
        if reason:
            print(f"skip\t{reason}\t{p}")
        else:
            print(f"ok\t{data.get('name')}\t{p}")


if __name__ == "__main__":
    main()
