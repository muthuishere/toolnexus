import asyncio
from toolnexus import create_in_process_client, create_toolkit, define_tool


def add(a: float, b: float) -> str:
    """Add two numbers."""
    return str(int(a + b))


def generate_stream(req, emit):  # sync: runs on its own thread
    last = req["messages"][-1]
    if last["role"] != "tool":
        return {"tool_calls": [{"name": "add", "arguments": {"a": 2, "b": 3}}]}
    words = ["The ", "sum ", "is ", str(last["content"]), "."]
    for w in words:
        emit({"type": "text", "delta": w})  # deltas must add up to content
    return {"content": "".join(words)}


async def main():
    client = create_in_process_client(model="my-model", generate_stream=generate_stream)
    toolkit = await create_toolkit(builtins=False, extra_tools=[define_tool(add, name="add")])
    async for ev in client.stream("What is 2 + 3?", toolkit):
        if ev["type"] == "text":
            print(ev["delta"], end="", flush=True)
        elif ev["type"] == "tool_call":
            print(f"[calling {ev['name']}]")
    print()  # The sum is 5.


asyncio.run(main())
