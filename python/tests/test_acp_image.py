"""ACP content parts (SPEC §8C): media parts travel as native ACP blocks outbound
and come back as §1B parts / RunResult.files inbound. Mirrors golang/acp_image_test.go."""
from __future__ import annotations

import asyncio
import base64
import os
import sys

from toolnexus import ACPOptions, create_in_process_client, load_acp

_FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "acp_fake_server.py")

# A 1x1 transparent PNG.
_PNG = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
    "890000000d49444154789c63000100000500010d0a2db40000000049454e44ae"
    "426082"
)
_B64 = base64.b64encode(_PNG).decode()


def _opts(scenario: str) -> ACPOptions:
    return ACPOptions(command=sys.executable, args=[_FIXTURE, "--scenario", scenario], permission_timeout=5.0)


def _image_prompt(scenario: str) -> list[dict]:
    client = load_acp(_opts(scenario))
    try:
        client.generate(
            {
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "what is in this picture?"},
                            {"type": "image_url", "image_url": {"url": "data:image/png;base64," + _B64}},
                        ],
                    }
                ]
            }
        )
        return client._call("diagnostics/get", {})["last_prompt"]
    finally:
        client.close()


def _split(blocks: list[dict]) -> tuple[str, list[dict]]:
    text = "".join(b.get("text", "") for b in blocks if b.get("type") == "text")
    return text, [b for b in blocks if b.get("type") == "image"]


def test_image_part_sent_as_image_block():
    text, images = _split(_image_prompt("image"))
    assert len(images) == 1, text
    assert images[0]["mimeType"] == "image/png" and images[0]["data"] == _B64
    assert _B64 not in text
    assert "what is in this picture?" in text


def test_image_part_placeholder_without_capability():
    text, images = _split(_image_prompt("default"))
    assert images == []
    assert _B64 not in text
    assert f"image (image/png, {len(_PNG)} bytes)" in text


def _want_files(files: list[dict]) -> None:
    assert len(files) == 2, files
    assert files[0]["from"] == "screenshot" and files[0]["callId"] == "t1"
    assert files[0]["type"] == "image" and files[0]["data"] == _B64
    assert files[1]["from"] == "acp" and files[1]["callId"] == ""
    assert files[1]["mimeType"] == "image/png" and files[1]["data"] == _B64


def test_inbound_image_blocks_become_parts():
    client = load_acp(_opts("image_out"))
    try:
        events: list[dict] = []
        out = client.generate_stream({"messages": [{"role": "user", "content": "shoot"}]}, events.append)
        results = [e for e in events if e.get("type") == "tool_result"]
        assert len(results) == 1
        assert results[0]["output"] == "image (image/png, 67 bytes)"
        assert results[0]["parts"] == [{"type": "image", "mimeType": "image/png", "data": _B64}]
        assert out["content"] == "here"
        _want_files(out["files"])
    finally:
        client.close()


def test_inbound_image_files_reach_run_result():
    acp = load_acp(_opts("image_out"))
    try:
        client = create_in_process_client(model="m", generate=acp.generate, generate_stream=acp.generate_stream)

        async def go():
            r = await client.run("shoot")
            assert r.text == "here"
            _want_files(r.files)
            done = None
            async for ev in client.stream("shoot"):
                if ev["type"] == "done":
                    done = ev["result"]
            assert done is not None
            _want_files(done.files)
            assert done.files[0]["callId"] == "t1"

        asyncio.run(go())
    finally:
        acp.close()
