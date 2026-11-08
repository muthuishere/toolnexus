"""Skill URI safety (python-sdk #3685): a skill:// URI is percent-decoded ONCE, then rejected if it still
contains '%' (double encoding), a backslash, NUL, an empty path, or an empty / "." / ".." / ':' segment.
Both the server (before lookup) and the client (before writing files) use it."""
from __future__ import annotations

import re

_PCT = re.compile(r"%([0-9A-Fa-f]{2})")


def _path_unescape(s: str) -> str:
    """Strict percent-decoding (like Go's url.PathUnescape): a bad escape is an error."""
    if re.search(r"%(?![0-9A-Fa-f]{2})", s):
        raise ValueError("bad percent-encoding")
    return _PCT.sub(lambda m: chr(int(m.group(1), 16)), s).encode("latin-1").decode("utf-8")


def parse_skill_uri(uri: str) -> tuple[str, str]:
    """Splits skill://<name>/<path> into a validated (name, slash-separated relative path); raises ValueError."""
    if not uri.startswith("skill://"):
        raise ValueError("not a skill:// URI")
    try:
        dec = _path_unescape(uri[len("skill://"):].encode("utf-8").decode("latin-1"))
    except (ValueError, UnicodeError) as e:
        raise ValueError(f"bad percent-encoding: {e}") from None
    if any(c in dec for c in "%\\\x00"):  # '%' left after one decode = double encoding
        raise ValueError("skill URI has encoded, backslash or NUL characters")
    segs = dec.split("/")
    if len(segs) < 2:
        raise ValueError("skill URI needs skill://<name>/<path>")
    if any(s in ("", ".", "..") or ":" in s for s in segs):
        raise ValueError("skill URI path escapes the skill (empty, '.', '..' or ':' segment)")
    return segs[0], "/".join(segs[1:])
