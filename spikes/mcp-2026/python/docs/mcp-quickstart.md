# MCP quickstart: zero to tool call

> **Python port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/python` (package `mcp2026`, `import mcp2026 as mcp`); the Go spike is the reference.
> Every code block is copied verbatim from a program under `examples/` that `tests/test_examples.py` runs and
> checks; `tests/test_docs.py` fails if a block here stops matching its source.

MCP 2026-07-28 is stateless: no `initialize` handshake, no session. Each request carries its protocol
version, so connecting is constructing a client.

## Over HTTP

<!-- from: examples/01-connect-http/main.py -->
```python
    # 1. Point a client at the server's MCP endpoint. No handshake happens here.
    c = mcp.Client(ts.url + "/mcp")

    # 2. Optional: server/discover tells you which versions and capabilities the server has.
    info = c.call("server/discover")
    print("supported versions:", "[" + " ".join(info["supportedVersions"]) + "]")

    # 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
    tools = c.tools()
    print("tools:", ", ".join(t["name"] for t in tools))

    # 4. Call a tool. mcp.text returns the first text content of the result.
    res = c.call_tool("echo", {"text": "hello over HTTP"})
    print("echo:", mcp.text(res))
```

Output (`python examples/01-connect-http/main.py`):

```text
supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP
```

## Over stdio

Spawn the server and talk JSON-RPC, one message per line. stderr is the server's log and is never parsed.
stdio has no HTTP headers and no OAuth: the server reads its credentials from its own environment.

<!-- from: examples/02-connect-stdio/main.py -->
```python
# 1. Describe the process to spawn (argv, env, cwd: plain subprocess).
argv = [sys.executable, __file__]
env = {**os.environ, "SERVE_STDIO": "1"}

# 2. Start it and get a client. Same API as the HTTP client: call, tools, call_tool.
with mcp.Client.stdio(argv, env=env) as c:  # leaving the block stops the server process
    # 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
    tools = c.tools()
    print("tool count:", len(tools))

    res = c.call_tool("echo", {"text": "hello over stdio"})
    print("echo:", mcp.text(res))
```

## When the tool needs the user (MRTR)

A server never calls the client. A `tools/call` can instead **return** `resultType: "input_required"` with
input requests (for example an elicitation form). `call_tool` answers them with your responder and re-sends
the call with `inputResponses` and the opaque `requestState`. You write only the callback:

<!-- from: examples/03-tool-input/main.py -->
```python
    # The responder is called once per input request. key identifies the request inside the
    # round; req is the request itself ({"method": "elicitation/create", "params": {...}}).
    # In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
    def ask_user(key: str, req: dict) -> dict:
        print(f"server asks ({req['method']}): {req['params']['message']}")
        # Answer like a person who filled the form. "decline" / "cancel" are the other actions.
        return {"action": "accept", "content": {"name": "Ada"}}

    res = c.call_tool("greet", {}, ask_user)
    print("result:", mcp.text(res))
```

```text
server asks (elicitation/create): What is your name?
result: Hello, Ada!
```

## Next

- [Authentication](mcp-authentication.md): protected servers, both client modes, protecting your own server.
- [Skills](mcp-skills.md), [Tool search](mcp-tool-search.md), [Providers](mcp-providers.md),
  [Spec support](mcp-spec-support.md).
