"""Spike for issue #86 (Python): a toolkit-less run/ask."""
import asyncio, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "python", "src"))
from toolnexus import create_client, create_toolkit  # noqa: E402


async def main() -> None:
    base = os.environ["SPIKE86_BASE"]
    c = create_client(base_url=base, style="openai", model="mock", api_key="not-a-real-key")

    # 1. The call a completion user reaches for.
    try:
        r = await c.run("write me a haiku")
        print("py: no-toolkit run OK, text=", repr(r.text))
    except TypeError as e:
        print("py: no-toolkit run FAILED:", type(e).__name__ + ": " + str(e))

    # 2. Passing None explicitly — does the loop survive it?
    try:
        r = await c.run("write me a haiku", None)
        print("py: None-toolkit run OK, text=", repr(r.text))
    except Exception as e:
        print("py: None-toolkit run FAILED:", type(e).__name__ + ": " + str(e))

    # 3. The workaround that exists today.
    tk = await create_toolkit(builtins=False)
    r = await c.run("write me a haiku", tk)
    print("py: create_toolkit(builtins=False) OK, tools=", len(tk.tools()), "text=", repr(r.text))


asyncio.run(main())
