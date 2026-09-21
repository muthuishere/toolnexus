"""Gate: prove a CLI-backed `generate` (ADR 0032 envelope shape) drives the
REAL python/ `create_in_process_client` end to end and returns a tool call.

Run: cd spikes/portability/python && ../.venv/bin/python -m pytest -v test_climodel.py
(venv created at spikes/portability/python/.venv, `pip install -e ../../../python`)
"""
from __future__ import annotations

import os

import pytest

from toolnexus import create_in_process_client, create_toolkit, define_tool

from climodel import make_cli_generate

HERE = os.path.dirname(os.path.abspath(__file__))
FAKECLI = os.path.join(HERE, "..", "fakecli", "fakecli.py")


def echo_marker_fn(received_marker: int, model: str) -> str:
    return f"echoed {received_marker} for model {model}"


ECHO_TOOL = define_tool(echo_marker_fn, name="echo_marker", description="Echo the marker back.")


@pytest.mark.asyncio
async def test_cli_backed_generate_returns_a_tool_call_end_to_end():
    tk = await create_toolkit(builtins=False, extra_tools=[ECHO_TOOL])
    generate = make_cli_generate(fakecli_path=FAKECLI)

    # request_params: inject a key no adapter has ever heard of, prove it reaches
    # the CLI byte-for-byte and comes back inside the tool call the fake CLI issued.
    client = create_in_process_client(
        model="portability-test",
        generate=generate,
        request_params={"x_portability_marker_never_seen_by_adapter": 777777},
    )

    r = await client.run("irrelevant prompt text", toolkit=tk)

    assert r.status == "done"
    assert "777777" in r.text
