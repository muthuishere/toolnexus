import asyncio, sys
from toolnexus import create_toolkit, define_tool
from oneshot import one_shot_client

def add(a: float, b: float) -> str:
    """Add two numbers."""
    return str(int(a + b))

async def main():
    tk = await create_toolkit(builtins=False, extra_tools=[define_tool(add, name="add")])
    r = await one_shot_client(sys.argv[1:]).run("Use the add tool to add 19 and 23, then tell me the sum.", tk)
    print("text:", repr(r.text), "calls:", r.tool_calls)
asyncio.run(main())
