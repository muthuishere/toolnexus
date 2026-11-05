"""Example 03 — a tool that asks the user for input mid-call (MRTR `input_required`).

Run: python examples/03-tool-input/main.py

In MCP 2026-07-28 a server never sends requests to the client. Instead a tools/call can RETURN
resultType "input_required" with a set of inputRequests (e.g. an elicitation form). The client answers
them and re-sends the same call with inputResponses (+ the opaque requestState). call_tool runs those
rounds for you: you only supply a responder callback.
"""
import mcp2026 as mcp

with mcp.serve(mcp.fixture_server()) as ts:  # `greet` asks for the user's name
    c = mcp.Client(ts.url + "/mcp")

    # The responder is called once per input request. key identifies the request inside the
    # round; req is the request itself ({"method": "elicitation/create", "params": {...}}).
    # In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
    def ask_user(key: str, req: dict) -> dict:
        print(f"server asks ({req['method']}): {req['params']['message']}")
        # Answer like a person who filled the form. "decline" / "cancel" are the other actions.
        return {"action": "accept", "content": {"name": "Ada"}}

    res = c.call_tool("greet", {}, ask_user)
    print("result:", mcp.text(res))
