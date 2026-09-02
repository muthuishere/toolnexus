"""Multimodal content tests (§1B ContentPart, §7F emission, §2 MCP mapping, §6 read,
§11 translate, §7C serve).

All hermetic: a mock LLM runs in-process on an ephemeral port, and the MCP inbound path
uses the in-memory linked transport. Base64 is asserted against the COMMITTED golden
``examples/media/fixture.png.base64`` — never against this port's own re-encoding.
"""
from __future__ import annotations

import base64
import gzip
import http.server
import io
import json
import os
import pathlib
import threading
from contextlib import asynccontextmanager

import anyio
import pytest
from mcp import ClientSession
from mcp.shared.memory import create_client_server_memory_streams

import toolnexus
from toolnexus import (
    ContentPartError,
    build_mcp_server,
    create_client,
    create_toolkit,
    define_tool,
)
from toolnexus.content import UnsupportedPartError, encode_part, set_max_part_bytes
from toolnexus.mcp_source import _collect_parts, _describe_parts, _join_text_content
from toolnexus.translate import openai_messages_to_anthropic
from toolnexus.types import ToolResult

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FIXTURE = os.path.join(REPO, "examples", "media", "fixture.png")
GOLDEN = open(os.path.join(REPO, "examples", "media", "fixture.png.base64")).read().strip()


# --------------------------------------------------------------------------- #
# Mock LLM (records every request body it is handed).
# --------------------------------------------------------------------------- #
def _start_server(handler_fn):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):  # silence
            pass

        def do_POST(self):  # noqa: N802
            length = int(self.headers.get("Content-Length", 0) or 0)
            raw = self.rfile.read(length) if length else b"{}"
            body = json.dumps(handler_fn(json.loads(raw.decode("utf-8")))).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"http://127.0.0.1:{server.server_address[1]}"


def _openai_text(text: str) -> dict:
    return {"choices": [{"message": {"role": "assistant", "content": text}}], "usage": {}}


def _openai_calls(*calls) -> dict:
    return {
        "choices": [
            {
                "message": {
                    "role": "assistant",
                    "content": None,
                    "tool_calls": [
                        {
                            "id": cid,
                            "type": "function",
                            "function": {"name": name, "arguments": "{}"},
                        }
                        for cid, name in calls
                    ],
                }
            }
        ],
        "usage": {},
    }


def _anthropic_uses(*uses) -> dict:
    return {
        "content": [
            {"type": "tool_use", "id": uid, "name": name, "input": {}} for uid, name in uses
        ],
        "usage": {},
    }


def _anthropic_text(text: str) -> dict:
    return {"content": [{"type": "text", "text": text}], "usage": {}}


def _shot_tool(name: str, part_type: str = "image", mime: str = "image/png"):
    def run() -> ToolResult:
        return ToolResult(
            output=f"{name} output",
            is_error=False,
            parts=[{"type": part_type, "mimeType": mime, "data": GOLDEN}],
        )

    return define_tool(run, name=name, description=name)


# --------------------------------------------------------------------------- #
# §1B — the model and the edge constructors
# --------------------------------------------------------------------------- #
def test_part_with_both_data_and_url_is_rejected():
    with pytest.raises(ContentPartError) as e:
        toolnexus.part(type="image", mimeType="image/png", data=GOLDEN, url="https://x/y.png")
    assert "both data and url" in str(e.value)


def test_part_with_neither_data_nor_url_is_rejected():
    with pytest.raises(ContentPartError):
        toolnexus.part(type="image", mimeType="image/png")


def test_base64_matches_the_committed_golden():
    p = toolnexus.image(FIXTURE)
    assert p.data == GOLDEN
    assert base64.b64decode(p.data) == open(FIXTURE, "rb").read()


def test_a_path_is_read_at_the_edge_and_never_stored():
    p = toolnexus.image(FIXTURE)
    assert p.mimeType == "image/png"
    assert p.data
    assert p.url is None
    assert FIXTURE not in json.dumps(toolnexus.content.to_dict(p))


def test_data_url_is_normalised_at_construction():
    p = toolnexus.image(f"data:image/png;base64,{GOLDEN}")
    assert p.mimeType == "image/png"
    assert p.data == GOLDEN
    assert p.url is None


def test_https_url_is_retained():
    p = toolnexus.image("https://example.test/shot.png")
    assert p.url == "https://example.test/shot.png"
    assert p.data is None
    assert p.mimeType == "image/png"


def test_unknown_extension_is_refused_by_name(tmp_path):
    f = tmp_path / "thing.xyz"
    f.write_bytes(b"...")
    with pytest.raises(ContentPartError) as e:
        toolnexus.image(str(f))
    assert ".xyz" in str(e.value)


def test_bytes_need_an_explicit_mime_type():
    with pytest.raises(ContentPartError):
        toolnexus.image(b"\x89PNG")
    assert toolnexus.image(b"\x89PNG", mime_type="image/png").data == base64.b64encode(
        b"\x89PNG"
    ).decode()


def test_oversized_part_is_rejected_at_the_edge():
    with pytest.raises(ContentPartError) as e:
        toolnexus.image(FIXTURE, max_part_bytes=10)
    assert "10" in str(e.value) and "82" in str(e.value)


def test_process_wide_max_part_bytes_is_enforced_in_decoded_bytes():
    set_max_part_bytes(81)  # 82 decoded bytes; the base64 string is longer
    try:
        with pytest.raises(ContentPartError):
            toolnexus.image(FIXTURE)
        set_max_part_bytes(82)
        assert toolnexus.image(FIXTURE).data == GOLDEN
    finally:
        set_max_part_bytes(None)


# --- the native file/byte objects a Python caller already holds (§1B) --------- #
def test_a_pathlib_path_is_accepted():
    p = toolnexus.image(pathlib.Path(FIXTURE))
    assert p.data == GOLDEN
    assert p.mimeType == "image/png"
    assert p.name == "fixture.png"


def test_an_os_pathlike_object_is_accepted():
    class Handle:
        def __fspath__(self) -> str:
            return FIXTURE

    p = toolnexus.image(Handle())
    assert p.data == GOLDEN
    assert p.mimeType == "image/png"
    assert p.name == "fixture.png"


def test_a_bytesio_is_read_eagerly_and_needs_an_explicit_mime_type():
    raw = open(FIXTURE, "rb").read()
    with pytest.raises(ContentPartError) as e:
        toolnexus.image(io.BytesIO(raw))
    assert "mime_type is required" in str(e.value)

    buf = io.BytesIO(raw)
    p = toolnexus.image(buf, mime_type="image/png")
    assert p.data == GOLDEN
    assert p.url is None
    assert buf.closed is False  # the handle is the caller's to close
    assert buf.read() == b""  # consumed eagerly at construction, not lazily


def test_an_open_rb_handle_takes_its_mime_from_its_name():
    with open(FIXTURE, "rb") as fh:
        p = toolnexus.image(fh)
        assert fh.closed is False  # the constructor must not close it
    assert p.data == GOLDEN
    assert p.mimeType == "image/png"
    assert p.name == "fixture.png"


def test_a_handle_with_an_unknown_extension_is_refused_by_name(tmp_path):
    f = tmp_path / "thing.xyz"
    f.write_bytes(b"...")
    with open(f, "rb") as fh, pytest.raises(ContentPartError) as e:
        toolnexus.image(fh)
    assert ".xyz" in str(e.value)


def test_a_text_mode_handle_is_a_typed_error():
    with open(FIXTURE, "r", errors="ignore") as fh, pytest.raises(ContentPartError) as e:
        toolnexus.image(fh, mime_type="image/png")
    assert "binary mode" in str(e.value)


def test_a_gzip_handle_is_accepted(tmp_path):
    raw = open(FIXTURE, "rb").read()
    gz = tmp_path / "fixture.png.gz"
    with gzip.open(gz, "wb") as out:
        out.write(raw)
    with gzip.open(gz, "rb") as fh:
        p = toolnexus.image(fh, mime_type="image/png")
    assert p.data == GOLDEN


def test_bytearray_and_memoryview_are_accepted():
    raw = open(FIXTURE, "rb").read()
    assert toolnexus.image(bytearray(raw), mime_type="image/png").data == GOLDEN
    assert toolnexus.image(memoryview(raw), mime_type="image/png").data == GOLDEN


def test_a_file_like_part_carries_no_handle_or_path():
    with open(FIXTURE, "rb") as fh:
        p = toolnexus.file(fh, mime_type="application/pdf")
    d = toolnexus.content.to_dict(p)
    assert set(d) <= {"type", "mimeType", "data", "url", "name"}
    assert FIXTURE not in json.dumps(d)
    assert all(isinstance(v, str) for v in d.values())


def test_max_part_bytes_still_fast_fails_for_a_file_like_source():
    with open(FIXTURE, "rb") as fh, pytest.raises(ContentPartError) as e:
        toolnexus.audio(fh, mime_type="audio/wav", max_part_bytes=10)
    assert "10" in str(e.value) and "82" in str(e.value)


def test_summarize_never_carries_bytes():
    s = toolnexus.summarize(toolnexus.image(FIXTURE))
    assert s == {"type": "image", "mimeType": "image/png", "bytes": 82}


def test_token_estimate_is_byte_derived_not_mime_derived():
    big = toolnexus.image(b"\x00" * 2_000_000, mime_type="image/png")
    small = toolnexus.image(FIXTURE)
    assert toolnexus.estimate_tokens(big) > toolnexus.estimate_tokens(small)
    assert toolnexus.estimate_tokens(big) >= 2_000_000 // 750


# --------------------------------------------------------------------------- #
# §7F — the positive allowlist
# --------------------------------------------------------------------------- #
def test_allowlist_block_shapes():
    img = toolnexus.image(FIXTURE)
    assert encode_part(img, "openai") == {
        "type": "image_url",
        "image_url": {"url": f"data:image/png;base64,{GOLDEN}"},
    }
    assert encode_part(img, "anthropic") == {
        "type": "image",
        "source": {"type": "base64", "media_type": "image/png", "data": GOLDEN},
    }
    doc = toolnexus.file(b"%PDF", mime_type="application/pdf", name="a.pdf")
    assert encode_part(doc, "openai")["file"]["file_data"].startswith(
        "data:application/pdf;base64,"
    )
    assert encode_part(doc, "anthropic")["type"] == "document"


def test_named_refusals():
    aud = toolnexus.audio(b"ID3", mime_type="audio/mpeg")
    assert encode_part(aud, "openai")["type"] == "input_audio"
    with pytest.raises(UnsupportedPartError) as e:
        encode_part(aud, "anthropic")
    assert "audio" in str(e.value) and "anthropic" in str(e.value)
    # Chat Completions has no URL form for a file part.
    with pytest.raises(UnsupportedPartError):
        encode_part(toolnexus.file("https://example.test/a.pdf"), "openai")


# --------------------------------------------------------------------------- #
# §7 — the loop accepts parts
# --------------------------------------------------------------------------- #
async def test_string_prompt_path_is_byte_identical():
    seen = []
    srv, base = _start_server(lambda m: (seen.append(m), _openai_text("ok"))[1])
    try:
        c = create_client(base_url=base, style="openai", model="m", api_key="k")
        r = await c.run("hello", await create_toolkit())
        assert seen[0]["messages"][-1] == {"role": "user", "content": "hello"}
        assert r.messages[0] == {"role": "user", "content": "hello"}
    finally:
        srv.shutdown()


async def test_parts_ordering_is_preserved():
    seen = []
    srv, base = _start_server(lambda m: (seen.append(m), _openai_text("ok"))[1])
    try:
        c = create_client(base_url=base, style="openai", model="m", api_key="k")
        await c.run(
            [toolnexus.text("before"), toolnexus.image(FIXTURE), toolnexus.text("after")],
            await create_toolkit(),
        )
        blocks = seen[0]["messages"][-1]["content"]
        assert [b["type"] for b in blocks] == ["text", "image_url", "text"]
        assert blocks[0]["text"] == "before" and blocks[2]["text"] == "after"
    finally:
        srv.shutdown()


async def test_text_only_parts_array_still_concatenates_for_anthropic_translate():
    msgs, _ = openai_messages_to_anthropic(
        [{"role": "user", "content": [{"type": "text", "text": "a"}, {"type": "text", "text": "b"}]}]
    )
    assert msgs == [{"role": "user", "content": "ab"}]


async def test_attached_audio_to_anthropic_errors_before_any_http_call():
    seen = []
    srv, base = _start_server(lambda m: (seen.append(m), _anthropic_text("ok"))[1])
    try:
        c = create_client(base_url=base, style="anthropic", model="m", api_key="k")
        with pytest.raises(UnsupportedPartError):
            await c.run([toolnexus.audio(b"ID3", mime_type="audio/mpeg")], await create_toolkit())
        assert seen == []
    finally:
        srv.shutdown()


# --------------------------------------------------------------------------- #
# §7F — tool-result emission: native (anthropic) vs relocation (openai)
# --------------------------------------------------------------------------- #
async def test_openai_relocates_tool_result_parts_into_one_synthetic_user_message():
    seen = []

    def handler(m):
        seen.append(m)
        if len(seen) == 1:
            return _openai_calls(("c1", "shotA"), ("c2", "shotB"))
        return _openai_text("done")

    srv, base = _start_server(handler)
    try:
        tk = await create_toolkit(extra_tools=[_shot_tool("shotA"), _shot_tool("shotB")])
        c = create_client(base_url=base, style="openai", model="m", api_key="k")
        r = await c.run("go", tk)
        wire = seen[1]["messages"]
        tools = [m for m in wire if m.get("role") == "tool"]
        assert [m["content"] for m in tools] == ["shotA output", "shotB output"]
        assert all("parts" not in m and "name" not in m for m in tools)
        # exactly ONE synthetic user message, immediately after the last tool message
        synth = wire[-1]
        assert wire.index(tools[-1]) == len(wire) - 2
        assert synth["role"] == "user"
        assert [b["type"] for b in synth["content"]] == [
            "text",
            "image_url",
            "text",
            "image_url",
        ]
        assert synth["content"][0]["text"] == "Output of tool shotA (c1):"
        assert synth["content"][2]["text"] == "Output of tool shotB (c2):"
        # ...and it NEVER lands in the canonical transcript
        assert not any(
            m.get("role") == "user" and isinstance(m.get("content"), list) for m in r.messages
        )
        assert GOLDEN not in json.dumps(
            [m for m in r.messages if m.get("role") == "user"]
        )
    finally:
        srv.shutdown()


async def test_anthropic_emits_the_image_inside_the_tool_result():
    seen = []

    def handler(m):
        seen.append(m)
        if len(seen) == 1:
            return _anthropic_uses(("u1", "shotA"))
        return _anthropic_text("done")

    srv, base = _start_server(handler)
    try:
        tk = await create_toolkit(extra_tools=[_shot_tool("shotA")])
        c = create_client(base_url=base, style="anthropic", model="m", api_key="k")
        await c.run("go", tk)
        wire = seen[1]["messages"]
        result_turn = wire[-1]
        block = result_turn["content"][0]
        assert block["type"] == "tool_result" and block["tool_use_id"] == "u1"
        assert block["content"][0] == {"type": "text", "text": "shotA output"}
        assert block["content"][1]["type"] == "image"
        assert block["content"][1]["source"]["data"] == GOLDEN
        # no synthetic user message: the last turn IS the tool-result turn
        assert sum(1 for m in wire if m.get("role") == "user") == 2  # prompt + results
    finally:
        srv.shutdown()


async def test_mcp_derived_audio_degrades_instead_of_failing_the_run():
    seen = []

    def handler(m):
        seen.append(m)
        if len(seen) == 1:
            return _anthropic_uses(("u1", "clip"))
        return _anthropic_text("done")

    srv, base = _start_server(handler)
    try:
        tk = await create_toolkit(extra_tools=[_shot_tool("clip", "audio", "audio/mpeg")])
        c = create_client(base_url=base, style="anthropic", model="m", api_key="k")
        r = await c.run("go", tk)
        assert r.text == "done"
        block = seen[1]["messages"][-1]["content"][0]
        placeholder = block["content"][1]
        assert placeholder["type"] == "text"
        assert placeholder["text"] == "[unsupported audio part (audio/mpeg, 82 bytes)]"
        assert GOLDEN not in placeholder["text"]
    finally:
        srv.shutdown()


# --------------------------------------------------------------------------- #
# §1B — maxPartBytes enforced at request assembly, over every part regardless of
# provenance (a part that arrived from an MCP server never passed through an edge
# constructor, so a limit checked only there is not a limit).
# --------------------------------------------------------------------------- #
async def test_attached_oversize_part_errors_before_any_http_call():
    seen = []
    srv, base = _start_server(lambda m: (seen.append(m), _anthropic_text("ok"))[1])
    try:
        c = create_client(base_url=base, style="anthropic", model="m", api_key="k", max_part_bytes=10)
        with pytest.raises(ContentPartError):
            await c.run([toolnexus.image(FIXTURE)], await create_toolkit())
        assert seen == []
    finally:
        srv.shutdown()


async def test_tool_derived_oversize_part_degrades_and_the_run_completes():
    seen = []

    def handler(m):
        seen.append(m)
        if len(seen) == 1:
            return _anthropic_uses(("u1", "clip"))
        return _anthropic_text("done")

    srv, base = _start_server(handler)
    try:
        tk = await create_toolkit(extra_tools=[_shot_tool("clip")])
        c = create_client(base_url=base, style="anthropic", model="m", api_key="k", max_part_bytes=10)
        r = await c.run("go", tk)
        assert r.text == "done"
        block = seen[1]["messages"][-1]["content"][0]
        placeholder = block["content"][1]
        assert placeholder["type"] == "text"
        assert placeholder["text"] == "[unsupported image part (image/png, 82 bytes)]"
        assert GOLDEN not in placeholder["text"]
    finally:
        srv.shutdown()


async def test_tool_derived_oversize_part_warns_once(capsys):
    seen = []

    def handler(m):
        seen.append(m)
        if len(seen) == 1:
            return _anthropic_uses(("u1", "shotA"), ("u2", "shotB"))
        return _anthropic_text("done")

    srv, base = _start_server(handler)
    try:
        tk = await create_toolkit(extra_tools=[_shot_tool("shotA"), _shot_tool("shotB")])
        c = create_client(base_url=base, style="anthropic", model="m", api_key="k", max_part_bytes=10)
        r = await c.run("go", tk)
        assert r.text == "done"
        stderr = capsys.readouterr().err
        assert stderr.count("over the maxPartBytes limit of 10") == 1, stderr
    finally:
        srv.shutdown()


async def test_on_unsupported_part_error_forces_uniform_strictness():
    seen = []

    def handler(m):
        seen.append(m)
        return _anthropic_uses(("u1", "clip"))

    srv, base = _start_server(handler)
    try:
        tk = await create_toolkit(extra_tools=[_shot_tool("clip", "audio", "audio/mpeg")])
        c = create_client(
            base_url=base, style="anthropic", model="m", api_key="k", on_unsupported_part="error"
        )
        with pytest.raises(UnsupportedPartError):
            await c.run("go", tk)
    finally:
        srv.shutdown()


async def test_llm_event_describes_parts_without_their_bytes():
    events = []
    srv, base = _start_server(lambda m: _openai_text("ok"))
    try:
        c = create_client(
            base_url=base,
            style="openai",
            model="m",
            api_key="k",
            on_metric=lambda ev: events.append(ev),
        )
        await c.run([toolnexus.image(FIXTURE)], await create_toolkit())
        llm = [e for e in events if e["event"] == "llm"][0]
        assert llm["parts"] == [{"type": "image", "mimeType": "image/png", "bytes": 82}]
        assert GOLDEN not in json.dumps(events)
    finally:
        srv.shutdown()


# --------------------------------------------------------------------------- #
# §2 — MCP tool results preserve non-text content
# --------------------------------------------------------------------------- #
class _Item:
    def __init__(self, **kw):
        self.__dict__.update(kw)


def _text_item(t):
    return _Item(type="text", text=t)


def test_text_only_mcp_result_is_byte_identical():
    content = [_text_item("a"), _text_item("b")]
    assert _join_text_content(content) == "a\nb"
    assert _collect_parts(content) is None


def test_mcp_image_entry_becomes_an_image_part():
    content = [_text_item("shot"), _Item(type="image", data=GOLDEN, mimeType="image/png")]
    assert _join_text_content(content) == "shot"
    assert _collect_parts(content) == [
        {"type": "image", "mimeType": "image/png", "data": GOLDEN}
    ]


def test_mcp_resource_link_becomes_a_file_part_with_url():
    content = [_Item(type="resource_link", uri="https://x/y.pdf", name="y", mimeType="application/pdf")]
    parts = _collect_parts(content)
    assert parts == [
        {"type": "file", "mimeType": "application/pdf", "url": "https://x/y.pdf", "name": "y"}
    ]


def test_mcp_embedded_resource_blob_and_text():
    blob = _Item(type="resource", resource=_Item(uri="file:///a.pdf", mimeType="application/pdf", blob=GOLDEN))
    txt = _Item(type="resource", resource=_Item(uri="file:///a.md", mimeType="text/markdown", text="hi"))
    assert _collect_parts([blob, txt]) == [
        {"type": "file", "mimeType": "application/pdf", "data": GOLDEN, "name": "file:///a.pdf"}
    ]
    # an embedded TEXT resource is appended to output, never made a part
    assert _join_text_content([_text_item("a"), txt]) == "a\nhi"


def test_mcp_audio_entry_becomes_an_audio_part():
    assert _collect_parts([_Item(type="audio", data=GOLDEN, mimeType="audio/mpeg")]) == [
        {"type": "audio", "mimeType": "audio/mpeg", "data": GOLDEN}
    ]


def test_describe_parts_uses_the_canonical_form():
    parts = [{"type": "image", "mimeType": "image/png", "data": GOLDEN}]
    assert _describe_parts(parts) == "image (image/png, 82 bytes)"


def test_describe_parts_renders_zero_bytes_for_a_url_part():
    parts = [{"type": "file", "mimeType": "application/pdf", "url": "https://x/y.pdf"}]
    assert _describe_parts(parts) == "file (application/pdf, 0 bytes)"


# --------------------------------------------------------------------------- #
# §6 — read
# --------------------------------------------------------------------------- #
def _read_tool():
    return {t.name: t for t in toolnexus.create_builtin_tools()}["read"]


async def test_read_png_yields_an_image_part():
    res = await _read_tool().execute({"path": FIXTURE})
    assert res.is_error is False
    assert res.output == f"{FIXTURE} (image/png, 82 bytes)"
    assert res.parts == [
        {"type": "image", "mimeType": "image/png", "data": GOLDEN, "name": "fixture.png"}
    ]


async def test_read_text_file_is_unchanged(tmp_path):
    f = tmp_path / "a.md"
    f.write_text("one\ntwo\nthree\n")
    res = await _read_tool().execute({"path": str(f), "offset": 2, "limit": 1})
    assert res.output == "two"
    assert res.parts is None


async def test_read_undecodable_bytes_is_an_error_result_not_an_exception(tmp_path):
    f = tmp_path / "blob.bin"
    f.write_bytes(b"\xff\xfe\x00\x01binary")
    res = await _read_tool().execute({"path": str(f)})
    assert res.is_error is True
    assert str(f) in res.output


async def test_read_undecodable_bytes_through_the_toolkit_does_not_raise(tmp_path):
    f = tmp_path / "blob.bin"
    f.write_bytes(b"\xff\xfe\x00\x01binary")
    tk = await create_toolkit(builtins=True)
    res = await tk.execute("read", {"path": str(f)})
    assert res.is_error is True


# --------------------------------------------------------------------------- #
# §11 — inbound translation
# --------------------------------------------------------------------------- #
def test_image_part_in_content_survives_translation():
    msgs, _ = openai_messages_to_anthropic(
        [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "what is this"},
                    {"type": "image", "mimeType": "image/png", "data": GOLDEN},
                ],
            }
        ]
    )
    assert msgs[0]["content"][0] == {"type": "text", "text": "what is this"}
    assert msgs[0]["content"][1]["type"] == "image"
    assert msgs[0]["content"][1]["source"]["data"] == GOLDEN


def test_tool_exchange_still_survives_translation():
    msgs, system = openai_messages_to_anthropic(
        [
            {"role": "system", "content": "sys"},
            {"role": "user", "content": "hi"},
            {
                "role": "assistant",
                "tool_calls": [
                    {"id": "c1", "type": "function", "function": {"name": "t", "arguments": '{"a":1}'}}
                ],
            },
            {"role": "tool", "tool_call_id": "c1", "content": "out"},
        ]
    )
    assert system == "sys"
    assert msgs[2]["content"][0] == {
        "type": "tool_result",
        "content": "out",
        "tool_use_id": "c1",
    }


# --------------------------------------------------------------------------- #
# §7C — serve emits the parts as MCP content blocks
# --------------------------------------------------------------------------- #
@asynccontextmanager
async def _connect_in_proc(server):
    async with create_client_server_memory_streams() as (client_streams, server_streams):
        client_read, client_write = client_streams
        server_read, server_write = server_streams
        async with anyio.create_task_group() as tg:
            tg.start_soon(
                lambda: server.run(
                    server_read, server_write, server.create_initialization_options()
                )
            )
            async with ClientSession(client_read, client_write) as session:
                await session.initialize()
                yield session
            tg.cancel_scope.cancel()


async def test_served_tool_image_part_becomes_an_mcp_image_block():
    server = build_mcp_server([_shot_tool("shotA")])
    async with _connect_in_proc(server) as session:
        res = await session.call_tool("shotA", {})
        assert res.isError is False
        assert res.content[0].type == "text" and res.content[0].text == "shotA output"
        assert res.content[1].type == "image"
        assert res.content[1].data == GOLDEN
        assert res.content[1].mimeType == "image/png"


async def test_part_survives_a_serve_then_consume_round_trip():
    """Serve a part-returning tool, then read the served result back through the MCP
    source mapping: the base64 is byte-identical in both directions."""
    server = build_mcp_server([_shot_tool("shotA")])
    async with _connect_in_proc(server) as session:
        res = await session.call_tool("shotA", {})
        assert _join_text_content(res.content) == "shotA output"
        assert _collect_parts(res.content) == [
            {"type": "image", "mimeType": "image/png", "data": GOLDEN}
        ]
