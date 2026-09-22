"""§1B ContentPart — the shared multimodal content model.

A part is **bytes-or-URL, never a path**: a path does not survive a persisted and
replayed transcript, nor the MCP / A2A process boundary, so the edge constructors
(:func:`image`, :func:`file`, :func:`audio`) read and base64-encode at construction.

The mime field is spelled ``mimeType`` in every port and on the wire (like
``Request.expiresAt`` in :mod:`toolnexus.types`, this is fixed wire data, not
idiomatic snake_case). Mime types come from the fixed §6 extension table and are
**never** sniffed from content nor resolved through a platform mime database
(``/etc/mime.types`` varies per machine and would break cross-port parity — which is
why this module does not use :mod:`mimetypes`).

This module also owns the §8A provider block mapping, so the client loop and the §11
translator emit parts through one implementation.
"""
from __future__ import annotations

import base64
import os
from dataclasses import dataclass
from typing import Any, Optional, Protocol, Union

PartType = str  # "text" | "image" | "file" | "audio"
ContentStyle = str  # "openai" | "anthropic"

#: The fixed media extension table (SPEC §6 `read`, shared with the edge constructors).
#: ``ext -> (mimeType, part type)``. No sniffing, no platform mime database.
MEDIA_TYPES: dict[str, tuple[str, str]] = {
    "png": ("image/png", "image"),
    "jpg": ("image/jpeg", "image"),
    "jpeg": ("image/jpeg", "image"),
    "gif": ("image/gif", "image"),
    "webp": ("image/webp", "image"),
    "pdf": ("application/pdf", "file"),
    "mp3": ("audio/mpeg", "audio"),
    "wav": ("audio/wav", "audio"),
}


class ContentPartError(ValueError):
    """A part could not be constructed (§1B): both/neither of ``data``/``url``, an
    unknown extension with no explicit mime type, or an oversized part."""


class UnsupportedPartError(ValueError):
    """A part has no block shape in the target provider style (§8A). Carries the part
    type and the style so the message names both."""

    def __init__(self, part_type: str, mime_type: Optional[str], style: str) -> None:
        super().__init__(
            f'no "{style}" block shape for a {part_type} part'
            + (f" ({mime_type})" if mime_type else "")
        )
        self.part_type = part_type
        self.mime_type = mime_type
        self.style = style


@dataclass
class ContentPart:
    """One content part (§1B). A flat dataclass with an open ``type`` discriminator,
    mirroring :class:`toolnexus.types.Request` — not a class hierarchy.

    ``text`` parts carry ``text``; every other type carries ``mimeType`` plus exactly
    one of ``data`` (standard base64, padded, no line breaks) or ``url``.
    """

    type: PartType
    text: Optional[str] = None
    mimeType: Optional[str] = None  # noqa: N815 — wire key, fixed across ports
    data: Optional[str] = None
    url: Optional[str] = None
    name: Optional[str] = None


# --------------------------------------------------------------------------- #
# maxPartBytes — measured in DECODED bytes, enforced in the edge constructors.
# --------------------------------------------------------------------------- #
_max_part_bytes: Optional[int] = None


def set_max_part_bytes(limit: Optional[int]) -> None:
    """Set the process-wide ``maxPartBytes`` ceiling (decoded bytes). ``None`` ⇒ no
    limit. Each edge constructor also takes a per-call ``max_part_bytes`` override."""
    global _max_part_bytes
    _max_part_bytes = limit


def get_max_part_bytes() -> Optional[int]:
    """The current process-wide ``maxPartBytes`` ceiling, or ``None``."""
    return _max_part_bytes


def decoded_len(b64: Optional[str]) -> int:
    """Decoded byte length of a standard-base64 string, without decoding it."""
    if not b64:
        return 0
    n = len(b64)
    pad = 0
    if b64.endswith("=="):
        pad = 2
    elif b64.endswith("="):
        pad = 1
    return max(0, (n // 4) * 3 - pad)


def _check_limit(size: int, limit: Optional[int]) -> None:
    eff = limit if limit is not None else _max_part_bytes
    if eff is not None and size > eff:
        raise ContentPartError(
            f"content part is {size} decoded bytes, over the maxPartBytes limit of {eff}"
        )


def summarize(part: Any) -> dict[str, Any]:
    """Render a part for a log line or a §9 event: ``{type, mimeType, bytes}``.
    A part's ``data`` is NEVER logged, emitted, or put in an error message."""
    p = _as_part(part)
    return {
        "type": p.type,
        "mimeType": p.mimeType,
        "bytes": decoded_len(p.data) if p.data else 0,
    }


def estimate_tokens(part: Any) -> int:
    """A byte-derived per-part token estimate (§1B): ``bytes/750``, floored at 85, so a
    large image is not free to the compactor. Never the ``mimeType`` string's length."""
    p = _as_part(part)
    if p.type == "text":
        return max(1, len(p.text or "") // 4)
    return max(85, decoded_len(p.data) // 750)


# --------------------------------------------------------------------------- #
# Edge constructors — normalise path / bytes / data: URL / https: URL at the edge.
# --------------------------------------------------------------------------- #
BytesLike = Union[bytes, bytearray, memoryview]


class BinaryReadable(Protocol):
    """A binary file-like object: anything whose ``read()`` returns ``bytes``."""

    def read(self, *args: Any) -> bytes: ...


#: What an edge constructor accepts (§1B "a port accepts the file and byte objects its
#: users already hold"): a path string, an :class:`os.PathLike`, native bytes, or any
#: **binary file-like** object exposing ``.read()`` — ``io.BytesIO``, an open ``rb``
#: file, a ``gzip`` / ``tempfile`` handle — plus a ``data:`` or ``https:`` URL string.
#: Whatever comes in, only ``mimeType`` + base64 ``data`` is stored: accept broadly,
#: store narrowly.
PartSource = Union[str, "os.PathLike[str]", BytesLike, BinaryReadable]


def text(value: str) -> ContentPart:
    """A text part."""
    return ContentPart(type="text", text=value)


def _ext_of(path: str) -> str:
    return os.path.splitext(path)[1].lstrip(".").lower()


def _mime_for_path(path: str) -> tuple[str, str]:
    ext = _ext_of(path)
    entry = MEDIA_TYPES.get(ext)
    if entry is None:
        raise ContentPartError(
            f'unknown file extension ".{ext}" — pass an explicit mime_type'
            if ext
            else "file has no extension — pass an explicit mime_type"
        )
    return entry


def _parse_data_url(url: str) -> tuple[str, str]:
    """``data:<mime>;base64,<b64>`` -> ``(mime, b64)``."""
    head, _, payload = url[len("data:") :].partition(",")
    if not payload or ";base64" not in head:
        raise ContentPartError("only base64 data: URLs are supported")
    mime = head.split(";", 1)[0]
    if not mime:
        raise ContentPartError("data: URL carries no mime type")
    return mime, payload


def _read_file_like(source: Any) -> tuple[bytes, Optional[str]]:
    """Consume a binary file-like object **eagerly**: read it fully now, so the part
    never holds a half-read stream. The handle is the caller's — we do NOT close it.

    Returns ``(raw, name)`` where ``name`` is the handle's ``.name`` when it exposes a
    filesystem-ish one (``io.BytesIO`` has none; an open file does)."""
    raw = source.read()
    if isinstance(raw, (bytearray, memoryview)):
        raw = bytes(raw)
    if not isinstance(raw, bytes):
        raise ContentPartError(
            "a file-like content part source must be opened in binary mode — "
            f'its read() returned {type(raw).__name__}, not bytes'
        )
    handle_name = getattr(source, "name", None)
    if isinstance(handle_name, os.PathLike):
        handle_name = os.fspath(handle_name)
    if not isinstance(handle_name, str):
        handle_name = None  # e.g. BytesIO (no name) or a raw fd number
    return raw, handle_name


def _build(
    part_type: str,
    source: PartSource,
    mime_type: Optional[str],
    name: Optional[str],
    max_part_bytes: Optional[int],
) -> ContentPart:
    if isinstance(source, os.PathLike):
        # Anything with __fspath__ (pathlib.Path, a custom PathLike) is a path.
        source = os.fspath(source)
    if not isinstance(source, (str, bytes, bytearray, memoryview)) and callable(
        getattr(source, "read", None)
    ):
        raw, handle_name = _read_file_like(source)
        mime = mime_type
        if not mime:
            if handle_name is None:
                raise ContentPartError(
                    "mime_type is required for a file-like source with no name"
                )
            mime = _mime_for_path(handle_name)[0]
        _check_limit(len(raw), max_part_bytes)
        return ContentPart(
            type=part_type,
            mimeType=mime,
            data=base64.b64encode(raw).decode("ascii"),
            name=name or (os.path.basename(handle_name) if handle_name else None),
        )
    if isinstance(source, (bytes, bytearray, memoryview)):
        raw = bytes(source)
        if not mime_type:
            raise ContentPartError("mime_type is required when building a part from bytes")
        _check_limit(len(raw), max_part_bytes)
        return ContentPart(
            type=part_type,
            mimeType=mime_type,
            data=base64.b64encode(raw).decode("ascii"),
            name=name,
        )
    if not isinstance(source, str) or not source:
        raise ContentPartError("a content part needs a path, bytes, or a URL")
    if source.startswith("data:"):
        mime, b64 = _parse_data_url(source)
        _check_limit(decoded_len(b64), max_part_bytes)
        return ContentPart(type=part_type, mimeType=mime_type or mime, data=b64, name=name)
    if source.startswith("http://") or source.startswith("https://"):
        if not mime_type:
            # A URL carries no bytes to read; fall back to the extension table so the
            # common case (…/shot.png) needs no explicit mime type.
            try:
                mime_type = _mime_for_path(source)[0]
            except ContentPartError:
                raise ContentPartError(
                    "mime_type is required for a URL whose extension is not in the media table"
                ) from None
        return ContentPart(type=part_type, mimeType=mime_type, url=source, name=name)
    # Anything else is a filesystem path: read now, base64 now, keep no path.
    mime = mime_type or _mime_for_path(source)[0]
    with open(source, "rb") as f:
        raw = f.read()
    _check_limit(len(raw), max_part_bytes)
    return ContentPart(
        type=part_type,
        mimeType=mime,
        data=base64.b64encode(raw).decode("ascii"),
        name=name or os.path.basename(source),
    )


def image(
    source: PartSource,
    *,
    mime_type: Optional[str] = None,
    name: Optional[str] = None,
    max_part_bytes: Optional[int] = None,
) -> ContentPart:
    """An image part from a filesystem path (``str`` or any :class:`os.PathLike`), raw
    bytes (``bytes`` / ``bytearray`` / ``memoryview``), a **binary file-like object**
    with a ``read()`` returning bytes (``io.BytesIO``, an open ``rb`` file, a ``gzip``
    or ``tempfile`` handle), a ``data:`` URL, or an ``https:`` URL.

    Everything but an ``https:`` URL is read and base64-encoded here, so the part never
    holds a path, a handle or an unread stream. A file-like source is consumed eagerly
    and is **not** closed — closing it stays the caller's business. Its ``mimeType``
    comes from the handle's ``.name`` via the fixed §6 extension table when it has one;
    otherwise pass ``mime_type`` explicitly or a :class:`ContentPartError` is raised."""
    return _build("image", source, mime_type, name, max_part_bytes)


def file(  # noqa: A001 — the part type is named "file"
    source: PartSource,
    *,
    mime_type: Optional[str] = None,
    name: Optional[str] = None,
    max_part_bytes: Optional[int] = None,
) -> ContentPart:
    """A file (document) part — see :func:`image` for the accepted sources."""
    return _build("file", source, mime_type, name, max_part_bytes)


def audio(
    source: PartSource,
    *,
    mime_type: Optional[str] = None,
    name: Optional[str] = None,
    max_part_bytes: Optional[int] = None,
) -> ContentPart:
    """An audio part — see :func:`image` for the accepted sources."""
    return _build("audio", source, mime_type, name, max_part_bytes)


def part(
    *,
    type: str,  # noqa: A002 — the field is named "type"
    mimeType: Optional[str] = None,  # noqa: N803 — wire key
    data: Optional[str] = None,
    url: Optional[str] = None,
    name: Optional[str] = None,
    text: Optional[str] = None,  # noqa: A002
) -> ContentPart:
    """Build a part from already-normalised fields, validating §1B: a non-text part
    carries a ``mimeType`` and **exactly one** of ``data`` / ``url``."""
    p = ContentPart(type=type, mimeType=mimeType, data=data, url=url, name=name, text=text)
    validate(p)
    return p


def validate(p: ContentPart) -> ContentPart:
    """Enforce §1B, raising :class:`ContentPartError`. Returns the part for chaining."""
    if p.type == "text":
        if p.text is None:
            raise ContentPartError("a text part needs text")
        return p
    if p.type not in ("image", "file", "audio"):
        raise ContentPartError(f'unknown content part type "{p.type}"')
    if not p.mimeType:
        raise ContentPartError(f"a {p.type} part needs a mimeType")
    if p.data and p.url:
        raise ContentPartError(
            f"a {p.type} part carries both data and url — exactly one is allowed"
        )
    if not p.data and not p.url:
        raise ContentPartError(
            f"a {p.type} part carries neither data nor url — exactly one is required"
        )
    return p


# --------------------------------------------------------------------------- #
# Canonical dict form (what a transcript / ConversationStore holds).
# --------------------------------------------------------------------------- #
def to_dict(p: Any) -> dict[str, Any]:
    """The canonical wire dict for a part — only the fields it actually carries."""
    part_ = _as_part(p)
    out: dict[str, Any] = {"type": part_.type}
    if part_.type == "text":
        out["text"] = part_.text or ""
        return out
    out["mimeType"] = part_.mimeType
    if part_.data is not None:
        out["data"] = part_.data
    if part_.url is not None:
        out["url"] = part_.url
    if part_.name:
        out["name"] = part_.name
    return out


def from_dict(d: dict[str, Any]) -> ContentPart:
    """Read a canonical part dict back into a :class:`ContentPart`."""
    return ContentPart(
        type=str(d.get("type") or ""),
        text=d.get("text"),
        mimeType=d.get("mimeType"),
        data=d.get("data"),
        url=d.get("url"),
        name=d.get("name"),
    )


def is_part_dict(d: Any) -> bool:
    """True for a canonical §1B part dict (a provider-native block is not one)."""
    if not isinstance(d, dict):
        return False
    t = d.get("type")
    if t == "text":
        return isinstance(d.get("text"), str)
    return t in ("image", "file", "audio") and isinstance(d.get("mimeType"), str)


def _as_part(p: Any) -> ContentPart:
    if isinstance(p, ContentPart):
        return p
    if isinstance(p, dict):
        return from_dict(p)
    if isinstance(p, str):
        return ContentPart(type="text", text=p)
    raise ContentPartError(f"not a content part: {type(p).__name__}")


def normalize_parts(parts: Any) -> list[dict[str, Any]]:
    """Normalise a caller-supplied list (parts, dicts, bare strings) into canonical
    part dicts, validating each. Ordering — which is semantic to a model — is kept."""
    out: list[dict[str, Any]] = []
    for item in parts:
        p = _as_part(item)
        validate(p)
        out.append(to_dict(p))
    return out


# --------------------------------------------------------------------------- #
# §8A emission: the provider block mapping, behind a POSITIVE allowlist.
# --------------------------------------------------------------------------- #
#
# For each (style, part type) there is either a defined block shape or an explicit
# refusal, and the encoded block is asserted against this allowlist BEFORE the request
# is sent. A part producing no allowlisted block never reaches the wire: map-and-hope
# reproduces the very bug this rule removes, because an unknown block type sent
# upstream returns HTTP 200 with the content silently discarded, not an error.
_ALLOWLIST: dict[tuple[str, str], set[str]] = {
    ("openai", "text"): {"text"},
    ("openai", "image"): {"image_url"},
    ("openai", "file"): {"file"},
    ("openai", "audio"): {"input_audio"},
    ("anthropic", "text"): {"text"},
    ("anthropic", "image"): {"image"},
    ("anthropic", "file"): {"document"},
    # ("anthropic", "audio") is a NAMED REFUSAL — Anthropic defines no audio block.
}

#: mimeType -> the OpenAI ``input_audio`` format token.
_AUDIO_FORMATS = {"audio/mpeg": "mp3", "audio/mp3": "mp3", "audio/wav": "wav", "audio/x-wav": "wav"}


def _data_url(p: ContentPart) -> str:
    return f"data:{p.mimeType};base64,{p.data}"


def _encode_openai(p: ContentPart) -> dict[str, Any]:
    if p.type == "text":
        return {"type": "text", "text": p.text or ""}
    if p.type == "image":
        url = p.url if p.url else _data_url(p)
        return {"type": "image_url", "image_url": {"url": url}}
    if p.type == "file":
        if not p.data:
            # Chat Completions has no URL form for a file part.
            raise UnsupportedPartError("file", p.mimeType, "openai")
        # file_data REQUIRES the data:<mime>;base64, prefix — a bare base64 string 400s.
        return {
            "type": "file",
            "file": {"filename": p.name or "file", "file_data": _data_url(p)},
        }
    if p.type == "audio":
        fmt = _AUDIO_FORMATS.get(p.mimeType or "")
        if not p.data or fmt is None:
            raise UnsupportedPartError("audio", p.mimeType, "openai")
        return {"type": "input_audio", "input_audio": {"data": p.data, "format": fmt}}
    raise UnsupportedPartError(p.type, p.mimeType, "openai")


def _encode_anthropic(p: ContentPart) -> dict[str, Any]:
    if p.type == "text":
        return {"type": "text", "text": p.text or ""}
    if p.type in ("image", "file"):
        block_type = "image" if p.type == "image" else "document"
        if p.url:
            source: dict[str, Any] = {"type": "url", "url": p.url}
        else:
            source = {"type": "base64", "media_type": p.mimeType, "data": p.data}
        return {"type": block_type, "source": source}
    # Anthropic defines no audio block.
    raise UnsupportedPartError(p.type, p.mimeType, "anthropic")


def encode_part(p: Any, style: ContentStyle) -> dict[str, Any]:
    """Encode one part into its provider block (§8A), asserted against the positive
    allowlist. Raises :class:`UnsupportedPartError` when the style has no shape for it."""
    part_ = _as_part(p)
    allowed = _ALLOWLIST.get((style, part_.type))
    if allowed is None:
        raise UnsupportedPartError(part_.type, part_.mimeType, style)
    block = _encode_openai(part_) if style == "openai" else _encode_anthropic(part_)
    if block.get("type") not in allowed:
        # Belt and braces: an encoder that drifts from the table cannot reach the wire.
        raise UnsupportedPartError(part_.type, part_.mimeType, style)
    return block


def describe_part(p: Any) -> str:
    """§8A: ``<type> (<mimeType>, <bytes> bytes)`` — a part described in text rather
    than emitted as its provider block. Byte-identical across all seven ports. A part
    carrying a ``url`` instead of ``data`` renders ``<bytes>`` as ``0``."""
    part_ = _as_part(p)
    return f"{part_.type} ({part_.mimeType}, {decoded_len(part_.data)} bytes)"


def placeholder_text(p: Any, style: ContentStyle) -> str:
    """The text a tool/MCP-derived part degrades to when the style cannot carry it
    (§8A provenance rule). Byte-identical across all seven ports."""
    part_ = _as_part(p)
    return f"[unsupported {part_.type} part ({part_.mimeType}, {decoded_len(part_.data)} bytes)]"
