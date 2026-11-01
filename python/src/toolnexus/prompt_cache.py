"""Prompt caching (SPEC §8 "Prompt caching", change add-prompt-cache). The engine
keeps the request prefix stable and marks it for the provider's cache. Mirrors
``golang/prompt_cache.go``.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass
class PromptCacheOptions:
    """``create_client(prompt_cache=...)``."""

    # Groups requests that share a prefix (one per session). Sent as openai
    # ``prompt_cache_key``. Ignored by anthropic, which caches by prefix.
    key: str = ""
    # Ask for the provider's longer cache lifetime where one exists (anthropic
    # ttl "1h"; default is the provider's short lifetime).
    long_ttl: bool = False


def _with_cache_control(v: Any, mark: dict[str, Any]) -> Any:
    if not isinstance(v, dict):
        return v
    c = dict(v)
    c["cache_control"] = mark
    return c


def apply_prompt_cache(body: dict[str, Any], style: str, o: PromptCacheOptions) -> None:
    """Mark the cacheable prefix in an assembled body, in place.

    - anthropic: ``cache_control`` breakpoints on the last tool, the system prompt and
      the last content block of the latest message (3 of the 4 allowed). The
      transcript's own dicts are never mutated: touched entries are copied.
    - openai: ``prompt_cache_key = key`` (when set). The prefix is cached automatically.
    - gemini: nothing yet (explicit cachedContent is a separate resource; deferred).
    """
    if style == "anthropic":
        mark: dict[str, Any] = {"type": "ephemeral"}
        if o.long_ttl:
            mark["ttl"] = "1h"
        tools = body.get("tools")
        if isinstance(tools, list) and tools:
            out = list(tools)
            out[-1] = _with_cache_control(out[-1], mark)
            body["tools"] = out
        sys_ = body.get("system")
        if isinstance(sys_, str):
            if sys_:
                body["system"] = [{"type": "text", "text": sys_, "cache_control": mark}]
        elif isinstance(sys_, list) and sys_:
            out = list(sys_)
            out[-1] = _with_cache_control(out[-1], mark)
            body["system"] = out
        msgs = body.get("messages")
        if isinstance(msgs, list) and msgs:
            out = list(msgs)
            last = out[-1]
            if isinstance(last, dict):
                m = dict(last)
                content = m.get("content")
                if isinstance(content, str):
                    m["content"] = [{"type": "text", "text": content, "cache_control": mark}]
                elif isinstance(content, list) and content:
                    blocks = list(content)
                    blocks[-1] = _with_cache_control(blocks[-1], mark)
                    m["content"] = blocks
                out[-1] = m
            body["messages"] = out
    elif style == "openai":
        if o.key:
            body["prompt_cache_key"] = o.key
