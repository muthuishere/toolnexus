"""Gate item 1, Python: does `retries: int = 2` (python/src/toolnexus/client.py:545,2126)
already distinguish "unset" from "explicit 0"? Python keyword defaults are not a
sentinel comparison at all -- the parameter default IS 2, so passing retries=0
sets self.retries=0 directly (client.py:567 `self.retries = retries`,
:1043 `range(self.retries + 1)`). Prove it with a real Client, a transport that
always raises the retryable HTTP error, and counting invocations.
"""
import asyncio
from toolnexus.client import create_client, _HttpError
from toolnexus.toolkit import create_toolkit


class FailingTransport:
    def __init__(self):
        self.calls = 0

    def post(self, url, headers, payload, timeout):
        self.calls += 1
        raise _HttpError(500, "boom", None)

    def open(self, url, headers, payload, timeout):
        raise NotImplementedError


async def main():
    toolkit = await create_toolkit()

    # explicit zero
    t0 = FailingTransport()
    client0 = create_client(
        base_url="https://example.invalid",
        style="openai",
        model="test-model",
        api_key="x",
        retries=0,
        http_transport=t0,
    )
    try:
        await client0.run("hi", toolkit=toolkit)
    except Exception:
        pass
    print(f"calls with retries=0 -> {t0.calls}")
    assert t0.calls == 1, f"expected 1 call, got {t0.calls}"

    # unset -> documented default of 2 retries = 3 total attempts
    t1 = FailingTransport()
    client1 = create_client(
        base_url="https://example.invalid",
        style="openai",
        model="test-model",
        api_key="x",
        http_transport=t1,
    )
    try:
        await client1.run("hi", toolkit=toolkit)
    except Exception:
        pass
    print(f"calls with retries UNSET -> {t1.calls}")
    assert t1.calls == 3, f"expected 3 calls, got {t1.calls}"

    print("PYTHON VERDICT: retries=0 != unset. Keyword default (not a 0-sentinel) already distinguishes them. No -1 sentinel needed.")


asyncio.run(main())
