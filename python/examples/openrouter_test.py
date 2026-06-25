"""Real OpenRouter tool-calling round trip (OpenAI-compatible API).

Reads OPENROUTER_API_KEY from the environment — NEVER hardcode it, never print it.
Uses only the standard library (urllib + json). Mirrors the shape of the JS
openrouter example: build the toolkit, hand the model `to_openai()` tools, ask it
to echo a string via the `everything_echo` tool, run `toolkit.execute(...)` on the
returned tool_call, feed the result back, print the final assistant message.

Run from a venv where the package is installed:
    OPENROUTER_API_KEY=... python examples/openrouter_test.py
"""
from __future__ import annotations

import asyncio
import json
import os
import urllib.error
import urllib.request

from toolnexus import create_toolkit

_HERE = os.path.dirname(os.path.abspath(__file__))
_EXAMPLES = os.path.normpath(os.path.join(_HERE, "..", "..", "examples"))

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
MODEL = "openai/gpt-4o-mini"


def _post(api_key: str, payload: dict) -> dict:
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        OPENROUTER_URL,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",  # value never logged
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


async def main() -> None:
    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not api_key:
        raise SystemExit("OPENROUTER_API_KEY is not set in the environment.")

    tk = await create_toolkit(
        mcp_config=os.path.join(_EXAMPLES, "mcp.json"),
        skills_dir=os.path.join(_EXAMPLES, "skills"),
    )
    try:
        messages = [
            {
                "role": "system",
                "content": "You are a helpful assistant with tools.\n"
                + tk.skills_prompt(),
            },
            {
                "role": "user",
                "content": 'Use the everything_echo tool to echo the string '
                '"hello from toolnexus" and then tell me what it returned.',
            },
        ]

        first = _post(
            api_key,
            {
                "model": MODEL,
                "messages": messages,
                "tools": tk.to_openai(),
                "tool_choice": "auto",
            },
        )
        choice = first["choices"][0]["message"]
        messages.append(choice)

        tool_calls = choice.get("tool_calls") or []
        if not tool_calls:
            print("Model did not call a tool. Reply:", choice.get("content"))
            return

        for call in tool_calls:
            fn = call["function"]
            name = fn["name"]
            args = json.loads(fn.get("arguments") or "{}")
            result = await tk.execute(name, args)
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": call["id"],
                    "content": result.output,
                }
            )

        second = _post(api_key, {"model": MODEL, "messages": messages})
        final = second["choices"][0]["message"].get("content")
        print("Final assistant message:\n" + (final or ""))
    finally:
        await tk.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except urllib.error.HTTPError as e:
        # Surface status without leaking auth header contents.
        raise SystemExit(f"OpenRouter HTTP error: {e.code} {e.reason}")
