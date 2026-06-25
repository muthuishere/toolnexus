"""HTTP / REST tools — declare a remote endpoint as a uniform Tool.

See ../../SPEC.md §7. Mirrors the JS reference (``js/src/http.ts``). Uses only the
standard library (``urllib``) so no new dependency is added; the blocking request
runs in a thread via ``asyncio.to_thread`` so it cooperates with the async toolkit.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional

from .types import JSONSchema, Tool, ToolContext, ToolResult

DEFAULT_TIMEOUT = 30.0  # seconds (JS uses 30000 ms)

_EMPTY_SCHEMA: JSONSchema = {
    "type": "object",
    "properties": {},
    "additionalProperties": False,
}

_PLACEHOLDER_RE = re.compile(r"\{(\w+)\}")
_ENV_RE = re.compile(r"\$\{([A-Za-z0-9_]+)\}")


def _expand_headers(headers: Optional[dict[str, str]]) -> dict[str, str]:
    """Expand ${ENV_VAR} in header values from os.environ. Never logged."""
    out: dict[str, str] = {}
    for k, v in (headers or {}).items():
        out[k] = _ENV_RE.sub(lambda m: os.environ.get(m.group(1), ""), v)
    return out


def _safe_parse(text: str) -> Any:
    try:
        return json.loads(text)
    except Exception:
        return text


def http_tool(
    *,
    name: str,
    description: str,
    method: str,
    url: str,
    headers: Optional[dict[str, str]] = None,
    query: Optional[list[str]] = None,
    body: str = "json",  # "json" | "form" | "raw"
    input_schema: Optional[JSONSchema] = None,
    timeout: Optional[float] = None,
    result_mode: str = "text",  # "text" | "json" | "status+text"
) -> Tool:
    """Wrap an HTTP endpoint as a uniform :class:`Tool` (``source="http"``)."""
    upper_method = method.upper()
    query_set = set(query or [])
    schema = input_schema if input_schema is not None else _EMPTY_SCHEMA

    def _do_request(args: dict[str, Any], req_timeout: float) -> ToolResult:
        a = dict(args)

        # 1. substitute {placeholders} in the URL from args (consumed afterwards).
        consumed: set[str] = set()

        def _sub(m: "re.Match[str]") -> str:
            key = m.group(1)
            consumed.add(key)
            val = a.get(key)
            return urllib.parse.quote(str(val if val is not None else ""), safe="")

        final_url = _PLACEHOLDER_RE.sub(_sub, url)
        for key in consumed:
            a.pop(key, None)

        # 2. querystring args (named in `query`, or everything for GET/HEAD).
        params: list[tuple[str, str]] = []
        for key in list(a.keys()):
            if key in query_set or upper_method == "GET":
                params.append((key, str(a[key])))
                a.pop(key, None)
        if params:
            qs = urllib.parse.urlencode(params)
            final_url += ("&" if "?" in final_url else "?") + qs

        # 3. body
        req_headers = _expand_headers(headers)
        data: Optional[bytes] = None
        if upper_method not in ("GET", "HEAD") and a:
            if body == "json":
                req_headers.setdefault("Content-Type", "application/json")
                data = json.dumps(a).encode("utf-8")
            elif body == "form":
                req_headers.setdefault(
                    "Content-Type", "application/x-www-form-urlencoded"
                )
                data = urllib.parse.urlencode(a).encode("utf-8")
            else:  # raw
                data = str(a.get("body", "")).encode("utf-8")

        req = urllib.request.Request(
            final_url, data=data, method=upper_method, headers=req_headers
        )
        try:
            with urllib.request.urlopen(req, timeout=req_timeout) as resp:
                status = resp.status
                text = resp.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            status = e.code
            text = e.read().decode("utf-8", errors="replace")
            return ToolResult(
                output=f"HTTP {status}: {text}",
                is_error=True,
                metadata={"status": status},
            )
        except Exception as e:  # noqa: BLE001 — surfaced as a tool error
            return ToolResult(output=str(e), is_error=True)

        if not (200 <= status < 300):
            return ToolResult(
                output=f"HTTP {status}: {text}",
                is_error=True,
                metadata={"status": status},
            )

        if result_mode == "status+text":
            output = f"{status}\n{text}"
        elif result_mode == "json":
            output = json.dumps(_safe_parse(text))
        else:
            output = text
        return ToolResult(output=output, is_error=False, metadata={"status": status})

    async def execute(
        args: Optional[dict[str, Any]] = None,
        ctx: Optional[ToolContext] = None,
    ) -> ToolResult:
        req_timeout = (
            ctx.timeout if (ctx is not None and ctx.timeout is not None) else timeout
        )
        if req_timeout is None:
            req_timeout = DEFAULT_TIMEOUT
        return await asyncio.to_thread(_do_request, args or {}, req_timeout)

    return Tool(
        name=name,
        description=description,
        input_schema=schema,
        source="http",  # type: ignore[arg-type]
        execute=execute,
    )
