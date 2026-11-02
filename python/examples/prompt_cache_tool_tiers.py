"""Prompt caching + tool tiers, hermetic (no network, no key).

    python examples/prompt_cache_tool_tiers.py

A two-turn anthropic-style session against a scripted provider that reports cache
writes on the first request and cache reads afterwards. ``tool_tiers`` sends only the
core tool schema; the model finds ``deploy`` with ``tool_search`` and runs it through
``call_tool``. The tools array stays identical on every request, which is what keeps
the cached prefix alive. Swap the transport for a real ``base_url`` (and ``api_key`` /
``api_key_env``) to run it live.
"""
from __future__ import annotations

import asyncio
import json

from toolnexus import (
    InMemoryConversationStore,
    PromptCacheOptions,
    ToolTierOptions,
    create_client,
    create_toolkit,
    define_tool,
)


@define_tool
def status() -> str:
    """Report the service status."""
    return "green"


@define_tool
def deploy(env: str) -> str:
    """Deploy the service to an environment."""
    return f"deployed to {env}"


@define_tool
def rollback(env: str) -> str:
    """Roll the service back in an environment."""
    return f"rolled back {env}"


class ScriptedAnthropic:
    """Plays the model's side, and simulates a provider prefix cache."""

    def __init__(self) -> None:
        self.step = 0
        self.tool_arrays: list[str] = []
        self.marked: list[bool] = []

    def post(self, url, headers, payload, timeout):
        self.step += 1
        self.tool_arrays.append(json.dumps([t["name"] for t in payload["tools"]]))
        self.marked.append("cache_control" in payload["tools"][-1])
        usage = {"input_tokens": 12, "output_tokens": 3}
        usage["cache_creation_input_tokens" if self.step == 1 else "cache_read_input_tokens"] = 2048
        content = {
            1: [{"type": "tool_use", "id": "s1", "name": "tool_search", "input": {"query": "select:deploy"}}],
            2: [{"type": "tool_use", "id": "c1", "name": "call_tool",
                 "input": {"name": "deploy", "arguments": {"env": "staging"}}}],
        }.get(self.step, [{"type": "text", "text": f"reply {self.step}"}])
        stop = "tool_use" if content[0]["type"] == "tool_use" else "end_turn"
        return {"content": content, "stop_reason": stop, "usage": usage}


async def main() -> None:
    tk = await create_toolkit(builtins=False, extra_tools=[status, deploy, rollback])
    provider = ScriptedAnthropic()
    client = create_client(
        base_url="http://localhost:8080",  # a local URL needs api_key / api_key_env (0.22.0)
        style="anthropic",
        model="claude-test",
        api_key="YOUR_KEY_HERE",
        system_prompt="You operate one service.",
        prompt_cache=PromptCacheOptions(long_ttl=True),
        tool_tiers=ToolTierOptions(core=["status"], deferred=["rollback"]),
        store=InMemoryConversationStore(),
        http_transport=provider,
    )

    print("plan:", client.tool_tier_plan(tk))  # core / listed / deferred, frozen once built
    first = await client.ask("deploy to staging", tk, id="session-1")
    print("plan:", client.tool_tier_plan(tk))
    print("turn 1:", first.text, "| ran:", [c["name"] for c in first.tool_calls])
    print("        usage:", first.usage)
    second = await client.ask("anything else?", tk, id="session-1")
    print("turn 2:", second.text, "| usage:", second.usage)

    assert [c["name"] for c in first.tool_calls] == ["tool_search", "call_tool"]
    assert first.tool_calls[-1]["output"] == "deployed to staging"
    assert second.usage.get("cache_read_tokens") == 2048
    assert len(set(provider.tool_arrays)) == 1, "the tools array must never change"
    assert all(provider.marked), "every request marks the last tool for the cache"
    print("tools sent every request:", provider.tool_arrays[0])
    client.reset_tool_tiers(tk)  # at a session boundary: rebuild the plan
    print("ok")


if __name__ == "__main__":
    asyncio.run(main())
