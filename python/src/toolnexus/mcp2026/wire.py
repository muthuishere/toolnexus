"""MCP 2026-07-28 wire constants and pure helpers (SPEC.md §2.1, §2.3, §2.7, §2.8)."""
from __future__ import annotations

import base64
import json
import math
import re
from typing import Any

PROTOCOL_VERSION = "2026-07-28"
META_VERSION = "io.modelcontextprotocol/protocolVersion"
META_CLIENT_CAPS = "io.modelcontextprotocol/clientCapabilities"
META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
SKILLS_EXT = "io.modelcontextprotocol/skills"

CODE_METHOD_NOT_FOUND = -32601
CODE_INVALID_PARAMS = -32602
CODE_HEADER_MISMATCH = -32020
CODE_UNSUPPORTED_PROTOCOL = -32022

CLIENT_CAPABILITIES: dict[str, Any] = {"elicitation": {"form": {}}}


class RpcError(Exception):
    """A JSON-RPC error object returned by the server."""

    def __init__(self, code: int, message: str, data: Any = None):
        super().__init__(message)
        self.code, self.message, self.data = code, message, data


def dumps(v: Any) -> str:
    """Compact JSON with sorted keys (the bytes Go's encoding/json produces for maps)."""
    return json.dumps(v, separators=(",", ":"), sort_keys=True, ensure_ascii=False)


def encode_header(v: str) -> str:
    """Mcp-Name value: sent verbatim when safe, else as the ``=?base64?…?=`` sentinel (§2.3)."""
    safe = (
        v == v.strip()
        and not (v.startswith("=?base64?") and v.endswith("?="))
        and all(0x20 <= ord(ch) <= 0x7E for ch in v)
    )
    return v if safe else "=?base64?" + base64.b64encode(v.encode()).decode() + "?="


# ---------------------------------------------------------------- tool search (§2.8)


def tokens(s: str) -> list[str]:
    """Lowercases and splits on non-alphanumerics and camelCase boundaries."""
    out: list[str] = []
    cur: list[str] = []
    prev_lower = False
    for ch in s:
        if ch.isupper() and prev_lower:
            if cur:
                out.append("".join(cur).lower())
            cur = [ch]
        elif ch.isalpha() or ch.isdigit():
            cur.append(ch)
        elif cur:
            out.append("".join(cur).lower())
            cur = []
        prev_lower = ch.islower()
    if cur:
        out.append("".join(cur).lower())
    return out


def bm25_rank(tools: list[tuple[str, str]], query: str) -> list[int]:
    """Indexes of ``(name, description)`` pairs matching ``query``, best first (BM25; name ×2)."""
    docs: list[list[str]] = []
    df: dict[str, int] = {}
    total = 0
    for name, desc in tools:
        nt = tokens(name)
        d = nt + nt + tokens(desc)
        docs.append(d)
        total += len(d)
        for w in set(d):
            df[w] = df.get(w, 0) + 1
    avg = total / max(1, len(tools))
    hits: list[tuple[int, float]] = []
    for i, d in enumerate(docs):
        tf: dict[str, int] = {}
        for w in d:
            tf[w] = tf.get(w, 0) + 1
        s = 0.0
        for q in tokens(query):
            f = float(tf.get(q, 0))
            if f == 0:
                continue
            idf = math.log(1 + (len(tools) - df[q] + 0.5) / (df[q] + 0.5))
            s += idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * len(d) / avg))
        if s > 0:
            hits.append((i, s))
    hits.sort(key=lambda h: -h[1])  # stable
    return [i for i, _ in hits]


# ---------------------------------------------------------------- skill URIs (§2.7)

_PCT = re.compile(r"%([0-9A-Fa-f]{2})")


def _path_unescape(s: str) -> str:
    if re.search(r"%(?![0-9A-Fa-f]{2})", s):
        raise ValueError("bad percent-encoding")
    return _PCT.sub(lambda m: chr(int(m.group(1), 16)), s).encode("latin-1").decode("utf-8")


def parse_skill_uri(uri: str) -> tuple[str, str]:
    """Splits ``skill://<name>/<path>`` into a validated ``(name, relative path)``; raises ValueError.

    Percent-decoded once, then rejected if it still contains ``%`` (double encoding), a backslash,
    NUL, or an empty / ``.`` / ``..`` / ``:`` segment."""
    if not uri.startswith("skill://"):
        raise ValueError("not a skill:// URI")
    try:
        dec = _path_unescape(uri[len("skill://"):].encode("utf-8").decode("latin-1"))
    except (ValueError, UnicodeError) as e:
        raise ValueError(f"bad percent-encoding: {e}") from None
    if any(c in dec for c in "%\\\x00"):
        raise ValueError("skill URI has encoded, backslash or NUL characters")
    segs = dec.split("/")
    if len(segs) < 2:
        raise ValueError("skill URI needs skill://<name>/<path>")
    if any(s in ("", ".", "..") or ":" in s for s in segs):
        raise ValueError("skill URI path escapes the skill (empty, '.', '..' or ':' segment)")
    return segs[0], "/".join(segs[1:])
