"""Provider adapters — turn the uniform tool list into each LLM's tool schema.

Execution is identical for every provider: read the tool name + args the model
returned, call ``toolkit.execute(name, args)``, feed ``output`` back.
Mirrors the JS reference (``js/src/adapters.ts``) and SPEC §4.
"""
from __future__ import annotations

from typing import Any

from .types import Tool


def to_openai(tools: list[Tool]) -> list[dict[str, Any]]:
    return [
        {
            "type": "function",
            "function": {
                "name": t.name,
                "description": t.description,
                "parameters": t.input_schema,
            },
        }
        for t in tools
    ]


def to_anthropic(tools: list[Tool]) -> list[dict[str, Any]]:
    return [
        {
            "name": t.name,
            "description": t.description,
            "input_schema": t.input_schema,
        }
        for t in tools
    ]


def to_gemini(tools: list[Tool]) -> list[dict[str, Any]]:
    return [
        {
            "functionDeclarations": [
                {
                    "name": t.name,
                    "description": t.description,
                    "parameters": t.input_schema,
                }
                for t in tools
            ]
        }
    ]
