"""Single-turn translation — the translator path (SPEC.md §11, ADR-0011).

``client.translate(request)`` is toolnexus used as a pure wire-format translator:
OpenAI shapes in, exactly ONE provider call, OpenAI shapes out. No agent loop, no tool
execution, no conversation state — so a caller can run it statelessly.

It is the INBOUND half of the §5 adapters: ``to_openai``/``to_anthropic``/``to_gemini``
send declarations out, this reads the provider's tool calls back in.

Use it when the CALLER owns the conversation and executes tools itself (the standard
OpenAI function-calling posture). When toolnexus owns the conversation, use the agent
loop with relay tools + ``run_with_answer`` (§10) instead.

This module holds the pure translation functions; the ``translate`` entry point itself
lives on :class:`toolnexus.client.Client`.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Optional


@dataclass
class TranslatedToolCall:
    """One tool call the model asked for, in OpenAI shape (§11)."""

    #: The tool-call id the caller must echo on its ``tool`` result message.
    id: str
    #: The function name.
    name: str
    #: Arguments as a JSON **string** — the OpenAI wire form, echoable byte-for-byte.
    arguments: str


@dataclass
class TranslateResult:
    """An OpenAI-shaped single-turn result (§11)."""

    #: Assistant text ("" when the model only called tools).
    text: str = ""
    #: The tool calls the model emitted, in provider order. None is ever dropped.
    tool_calls: list[TranslatedToolCall] = field(default_factory=list)
    #: The OpenAI finish reason. A turn with any tool call is always ``"tool_calls"``.
    finish_reason: str = "stop"
    #: This single call's token usage.
    usage: dict[str, int] = field(default_factory=lambda: {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0})
    #: The model that answered.
    model: str = ""
    #: The provider's decoded response, for fields this type does not model.
    raw: Optional[dict[str, Any]] = None

    def tool_calls_json(self) -> list[dict[str, Any]]:
        """Render the tool calls as an OpenAI ``tool_calls`` array, ready to put on an
        assistant message. Convenience for assembling a response envelope."""
        return [
            {"id": tc.id, "type": "function", "function": {"name": tc.name, "arguments": tc.arguments}}
            for tc in self.tool_calls
        ]


def finish_reason_for(has_tool_calls: bool, provider_stop: Optional[str] = None) -> str:
    """Map a provider stop reason onto an OpenAI finish reason. Tool calls win: a turn
    that emitted any tool call is always ``"tool_calls"`` to a conforming client."""
    if has_tool_calls:
        return "tool_calls"
    if provider_stop in ("max_tokens", "length"):
        return "length"
    if provider_stop in ("refusal", "content_filter"):
        return "content_filter"
    return "stop"


def content_text(content: Any) -> str:
    """Flatten an OpenAI ``content`` value to text: the string form and the parts-array
    form. Non-text parts are ignored."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for p in content:
            if isinstance(p, dict) and isinstance(p.get("text"), str):
                parts.append(p["text"])
        return "".join(parts)
    return ""


def args_object(args: Any) -> dict[str, Any]:
    """Parse a tool-call arguments value into an object, tolerating both wire forms —
    some clients send ``arguments`` as an object rather than a JSON string."""
    if isinstance(args, dict):
        return args
    if isinstance(args, str) and args.strip():
        try:
            parsed = json.loads(args)
        except (ValueError, TypeError):
            return {}
        if isinstance(parsed, dict):
            return parsed
    return {}


def args_string(args: Any) -> str:
    """Render a tool-call arguments value as the JSON string the OpenAI wire format uses."""
    if isinstance(args, str):
        return args
    if isinstance(args, dict):
        return json.dumps(args, separators=(",", ":"))
    return "{}"


def tool_calls_of(message: dict[str, Any]) -> list[TranslatedToolCall]:
    """Read an assistant message's OpenAI ``tool_calls``."""
    raw = message.get("tool_calls")
    if not isinstance(raw, list):
        return []
    out: list[TranslatedToolCall] = []
    for tc in raw:
        if not isinstance(tc, dict):
            continue
        fn = tc.get("function")
        if not isinstance(fn, dict):
            continue
        out.append(
            TranslatedToolCall(
                id=str(tc.get("id") or ""),
                name=str(fn.get("name") or ""),
                arguments=args_string(fn.get("arguments")),
            )
        )
    return out


def openai_messages_to_anthropic(messages: list[Any]) -> tuple[list[dict[str, Any]], str]:
    """Convert an OpenAI ``messages`` array into Anthropic-native messages plus the
    extracted system prompt, preserving the tool structure a text flattening destroys:

    * an assistant turn's ``tool_calls`` become ``tool_use`` blocks, with ``arguments``
      parsed back from its JSON string into an object;
    * a ``tool``-role result becomes a ``tool_result`` block keyed by ``tool_call_id``,
      **merged into a single user turn** when consecutive (providers expect one
      result-bearing turn answering the preceding assistant turn);
    * ``system``/``developer`` messages are hoisted out, since Anthropic takes system
      separately.

    Returns ``(messages, system)``.
    """
    out: list[dict[str, Any]] = []
    system_parts: list[str] = []
    pending_results: list[dict[str, Any]] = []

    def flush() -> None:
        nonlocal pending_results
        if pending_results:
            out.append({"role": "user", "content": pending_results})
            pending_results = []

    for m in messages or []:
        if not isinstance(m, dict):
            continue
        role = m.get("role")
        if role in ("system", "developer"):
            flush()
            s = content_text(m.get("content"))
            if s:
                system_parts.append(s)
        elif role in ("tool", "function"):
            block: dict[str, Any] = {"type": "tool_result", "content": content_text(m.get("content"))}
            if m.get("tool_call_id"):
                block["tool_use_id"] = str(m["tool_call_id"])
            pending_results.append(block)
        elif role == "assistant":
            flush()
            blocks: list[dict[str, Any]] = []
            s = content_text(m.get("content"))
            if s:
                blocks.append({"type": "text", "text": s})
            for tc in tool_calls_of(m):
                blocks.append({"type": "tool_use", "id": tc.id, "name": tc.name, "input": args_object(tc.arguments)})
            if not blocks:
                continue  # an empty assistant turn would be rejected
            out.append({"role": "assistant", "content": blocks})
        else:
            flush()
            s = content_text(m.get("content"))
            if s:
                out.append({"role": "user", "content": s})
            elif isinstance(m.get("content"), list) and m["content"]:
                out.append({"role": "user", "content": m["content"]})

    flush()
    return out, "\n\n".join(system_parts)


def openai_tools_to_anthropic(tools: Optional[list[Any]]) -> list[dict[str, Any]]:
    """Convert an OpenAI ``tools`` array into Anthropic tool declarations. Entries that
    are already provider-native pass through; anything unrecognized is skipped."""
    out: list[dict[str, Any]] = []
    for t in tools or []:
        if not isinstance(t, dict):
            continue
        fn = t.get("function")
        if not isinstance(fn, dict):
            if t.get("name"):
                out.append(t)  # already native
            continue
        name = fn.get("name")
        if not name:
            continue
        decl: dict[str, Any] = {"name": name}
        if fn.get("description"):
            decl["description"] = fn["description"]
        params = fn.get("parameters")
        decl["input_schema"] = params if isinstance(params, dict) else {"type": "object", "properties": {}}
        out.append(decl)
    return out


def openai_tool_choice_to_anthropic(choice: Any) -> Optional[dict[str, Any]]:
    """Map OpenAI ``tool_choice`` onto Anthropic's shape. Returns ``None`` for
    absent/``"auto"`` (the provider default) and for anything unrecognized."""
    if isinstance(choice, str):
        if choice in ("required", "any"):
            return {"type": "any"}
        if choice == "none":
            return {"type": "none"}
        return None
    if isinstance(choice, dict):
        fn = choice.get("function")
        if isinstance(fn, dict) and fn.get("name"):
            return {"type": "tool", "name": fn["name"]}
    return None


def has_system_message(messages: list[Any]) -> bool:
    """True when the message list already carries a system-ish message."""
    return any(isinstance(m, dict) and m.get("role") in ("system", "developer") for m in messages or [])
