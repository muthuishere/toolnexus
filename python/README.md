# toolnexus

[![PyPI](https://img.shields.io/pypi/v/toolnexus?logo=pypi&logoColor=white&label=PyPI)](https://pypi.org/project/toolnexus/)
[![license](https://img.shields.io/badge/license-MIT-green)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Build an agent in a few lines.** Point at an `mcp.json` and a `skills/` folder, call `run()`,
and you have a working agent — MCP servers, agent skills, your own functions, and HTTP endpoints
unified as one tool set, driving any LLM.

> **Right-sized.** Not a framework (no builders, no config to wade through), not a toy that falls
> over the moment you need streaming or a retry. Everything a real agent needs — the loop, hooks,
> streaming, retries, memory — and nothing it doesn't.

The Python port of [toolnexus](https://github.com/muthuishere/toolnexus) — the same library,
byte-identical, also in **JavaScript, Go, Java, C#, Elixir, Clojure and Rust**. Built on the official
MCP Python SDK (the `mcp` package). Python ≥ 3.11.

## Install

```sh
pip install toolnexus
```

## Quick start

The **10 built-in tools** (`bash`, `read`, `grep`, `webfetch`, …) are on by default, so the model
can act with no `mcp.json` and no skills folder:

```python
import asyncio
from toolnexus import create_toolkit, create_client

async def main():
    tk = await create_toolkit()                          # built-in tools, on by default
    agent = create_client(
        base_url="https://openrouter.ai/api/v1", style="openai",
        model="deepseek/deepseek-chat",                  # any OpenRouter/OpenAI/Anthropic model
    )
    res = await agent.run("List the files here, then count them.", tk)
    print(res.text)
    await tk.close()

asyncio.run(main())
```

Add real tool sources by pointing at them:

```python
tk = await create_toolkit(mcp_config="mcp.json", skills_dir=["skills"])
```

- `mcp.json` is the standard Claude-desktop-style config (`mcpServers` / `servers` / `mcp` keys all accepted).
- `skills/` is a folder of `**/SKILL.md` files, loaded on demand through one `skill` tool.
- Remote MCP `headers` values expand `${ENV_VAR}` at call time and are never logged.

## API key by host (breaking in 0.22.0)

With no `api_key`, the client reads only the env var that belongs to the `base_url` host, over
https: `api.openai.com` → `OPENAI_API_KEY`, `api.anthropic.com` → `ANTHROPIC_API_KEY`,
`openrouter.ai` → `OPENROUTER_API_KEY`. Any other host — a local server, a proxy, plain `http://`
— needs `api_key` or `api_key_env` (the **name** of the env var to read); otherwise the first
request raises `No API key`. There is no cross-provider fallback any more, so a key is never sent
to a host it does not belong to.

```python
local = create_client(base_url="http://localhost:11434/v1", style="openai",
                      model="llama3", api_key="YOUR_KEY_HERE")
proxy = create_client(base_url="https://llm-proxy.internal/v1", style="openai",
                      model="gpt-4o-mini", api_key_env="MY_PROXY_KEY")
```

`list_models(base_url=..., api_key_env=...)` follows the same rule and returns `ModelInfo(id,
name, context_length)` sorted by id.

## Prompt caching and tool tiers

```python
from toolnexus import PromptCacheOptions, ToolTierOptions

client = create_client(
    base_url="https://api.anthropic.com", style="anthropic", model="claude-sonnet-4-5",
    prompt_cache=PromptCacheOptions(long_ttl=True),        # openai: PromptCacheOptions(key="session-1")
    tool_tiers=ToolTierOptions(core=["read", "grep"], deferred=["deploy"]),
)
res = await client.run("…", tk)
res.usage.get("cache_read_tokens"), res.usage.get("cache_write_tokens")
client.tool_tier_plan(tk)      # {"read": "core", "bash": "listed", "deploy": "deferred", …}
client.reset_tool_tiers(tk)    # rebuild the frozen plan at a session boundary
```

`prompt_cache` marks the stable prefix (anthropic `cache_control` breakpoints on the last tool,
the system prompt and the latest message; openai `prompt_cache_key`). `tool_tiers` sends only
the core schemas plus `tool_search` and `call_tool`; the model loads the rest on demand, and the
tools array (so the cached prefix) never changes. Under anthropic the real context size is
`prompt_tokens + cache_read_tokens + cache_write_tokens`. Runnable, hermetic:
[`examples/prompt_cache_tool_tiers.py`](examples/prompt_cache_tool_tiers.py).

## Host hooks (0.22.0)

- `load_mcp(cfg, on_server=fn)` reports each MCP server the moment it connects, fails or is
  disabled; `source.resource_tools()` adds read-only `list_mcp_resources` / `read_mcp_resource`.
- `create_client(wait_for_ctx=fn(cancel, request))` — `wait_for` that ends with the turn.
- `before_tool` events carry `hints` (`ToolHints(read_only, destructive, open_world)`), so a
  permission gate can allow read-only tools and deny or ask (`pending(...)`) for the rest.
- Opt-in builtins: `builtins={"background": BackgroundShells(), "web_search": fn,
  "notebook_edit": True}` adds `bash_output` / `kill_shell`, `websearch`, `notebook_edit`
  (call `BackgroundShells.close_all()` on shutdown). `grep` takes `context` / `before` / `after`;
  `read` and `grep` report truncation in `metadata`.
- `compactor(..., on_compact=fn)` gets a `CompactInfo` after each compaction.
- `agent.as_tool_with(lambda ctx: {"hooks": ..., "wait_for": ...})` runs a sub-agent under the
  parent's gates; `ctx.call_id` tells concurrent tool calls apart.

## Simple judgments

A thin layer over any `Classifier` (SPEC §8B); the wire request is unchanged.

```python
from toolnexus import ask, gate, noul, choice, state, Rule, Policy

st = state("You are Donkey Kong, you want to win.", {"message_received": msg})
a = await ask(classifier, st, [
    noul("is_appropriate", "Does message_received contain inappropriate language?"),
    noul("does_this_help", "Does message_received help donkey kong win?"),
])
a["is_appropriate"].band      # "yes" | "no" | "uncertain"   (Bands(0.30, 0.70), exclusive)
a["does_this_help"].value()   # the probability

out = await gate(classifier, st, qs, Policy([Rule("fixable", "fail", below=0.3),
                                             Rule("component", "skip_to", is_="pricing", target="fix-pricing")]))
out.escalated, out.request    # unsure / missing -> a §10 Request(kind="input")
```

Also: `context(ctx, message, extra)`, `score(...)`, `Tape` (record live, replay offline by call
name), `static_classifier((state, questions, response), ...)`, `questions_wire(...)`, and
`classifier.evaluate_batch(states, questions)` (state order, fail-closed, 16 in flight).

## Media generation

Generate images, video and speech (plus transcription, voice cloning and talking-head video)
from a **JSON style** instead of provider code. Async jobs are started and polled for you. The same
verbs become agent tools (`generate_image`, `generate_video`, `generate_speech`, …), and whatever
they produce lands in `RunResult.files`. `examples/media-styles/a2e.json` ships first and reads
`A2E_API_KEY` at call time. Its result URLs expire in 3–30 days, so save what you keep. SPEC §7G;
guide: <https://muthuishere.github.io/toolnexus/media/>.

```python
from toolnexus import load_media_style, create_media, create_toolkit

media = create_media(load_media_style("examples/media-styles/a2e.json"),
                     on_progress=lambda p: print(p["verb"], p["status"]))
img = await media.image("a red fox in fresh snow", width=1024, height=768)
vid = await media.video("the fox blinks", image=img.files[0].url, seconds=5)

tk = await create_toolkit(media=media)               # the agent gets generate_image, …
res = await agent.run("Draw a fox, then animate it.", tk)
for f in res.files:
    print(f["from"], f["mimeType"], f["url"])
```

## Agents via ACP

A local coding agent that speaks ACP can be the model behind an ordinary client, calling
your toolkit as an MCP server. Three vendor-neutral pieces (SPEC §8C); which binary, flags
and permissions to use is yours.

```python
tk = await create_toolkit(skills_dir="skills")
srv = tk.as_mcp_server()          # 127.0.0.1:<ephemeral>/mcp behind a bearer token
acp = load_acp(ACPOptions(
    command="my-agent", args=["acp"],
    mcp_servers=[{"type": "http", "name": "toolnexus", "url": srv.url,
                  "headers": [{"name": "Authorization", "value": "Bearer " + srv.token}]}],
))
client = create_in_process_client(model="agent", generate_stream=acp.generate_stream)
async for ev in client.stream("What is in the skill?", toolkit=tk):
    ...                           # text deltas, plus thought / tool_call / tool_result via "acp"
acp.close(); srv.close()
```

`mcp_servers` entries, `session_meta` (session/new `_meta`), `client_caps_meta` and `model`
are passed through to the agent unchanged.

## Documentation

Everything else — the full surface, with runnable examples — lives on the docs site:

| | |
|---|---|
| **Start here** | [Quickstart](https://muthuishere.github.io/toolnexus/quickstart/) · [Concepts](https://muthuishere.github.io/toolnexus/concepts/) · [Install](https://muthuishere.github.io/toolnexus/install/) |
| **Tool sources** | [MCP](https://muthuishere.github.io/toolnexus/mcp/) · [Skills](https://muthuishere.github.io/toolnexus/skills/) · [Native](https://muthuishere.github.io/toolnexus/native/) · [HTTP](https://muthuishere.github.io/toolnexus/http/) · [Built-ins](https://muthuishere.github.io/toolnexus/builtins/) · [A2A](https://muthuishere.github.io/toolnexus/a2a/) |
| **The loop** | [Streaming](https://muthuishere.github.io/toolnexus/streaming/) · [Memory](https://muthuishere.github.io/toolnexus/memory/) · [Suspension](https://muthuishere.github.io/toolnexus/suspension/) · [Resilience](https://muthuishere.github.io/toolnexus/resilience/) · [Observability](https://muthuishere.github.io/toolnexus/observability/) |
| **Agents** | [Sub-agents & teams](https://muthuishere.github.io/toolnexus/subagents/) · [Personas](https://muthuishere.github.io/toolnexus/persona-agents/) · [Typed decisions](https://muthuishere.github.io/toolnexus/judge/) |
| **API reference** | [Python](https://muthuishere.github.io/toolnexus/api/python/) |
| **Cookbook** | [Zero to agent](https://muthuishere.github.io/toolnexus/cookbook/zero-to-agent/) · [MCP servers](https://muthuishere.github.io/toolnexus/cookbook/mcp-servers/) · [Agent skills](https://muthuishere.github.io/toolnexus/cookbook/agent-skills/) · [Judge](https://muthuishere.github.io/toolnexus/cookbook/judge/) |

Contract across all eight ports: [`SPEC.md`](https://github.com/muthuishere/toolnexus/blob/main/SPEC.md).
