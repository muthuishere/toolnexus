"""Media generation (SPEC §7G, source ``"media"``).

One interface, two faces: typed async verbs a host calls (``image``/``video``/``speech``/
``music``/``transcribe``/``clone_voice``/``talk`` + ``generate``) and :class:`Tool` s an LLM
calls (``tools()``). Providers are **media clients** (:class:`MediaClient`): the built-in API
clients (``A2EMediaClient``, ``ElevenLabsMediaClient``, ``OpenAIMediaClient``,
``OpenRouterMediaClient``, ``FalMediaClient``), the CLI clients (``GrokMediaClient``,
``CodexMediaClient``), style clients over the JSON style engine (:func:`style_media_client` +
:func:`load_media_style`), or your own class. :func:`create_media` routes each verb to the first
client that supports it and falls through to the next one **only** on
:class:`MediaUnavailableError`. Byte contract: ``conformance/media/``.

Cancellation is asyncio-native: cancel the task awaiting a verb and the poll loop (or the CLI
process) stops with :class:`asyncio.CancelledError` — on the tool face too.
"""
from __future__ import annotations

import asyncio
import base64
import copy
import inspect
import itertools
import json
import math
import os
import re
import shutil
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from importlib import resources
from typing import Any, Awaitable, Callable, Optional, Protocol, Union, runtime_checkable
from urllib.parse import quote, urljoin, urlsplit

from .types import Tool, ToolContext, ToolResult


def _data_text(name: str) -> str:
    return resources.files("toolnexus").joinpath("media_data", name).read_text(encoding="utf-8")


# --------------------------------------------------------------------------- #
# Canonical verbs — conformance/media/verbs.json, shipped verbatim as package data.
# --------------------------------------------------------------------------- #
VERBS_JSON_TEXT: str = _data_text("verbs.json")
VERBS: list[dict[str, Any]] = json.loads(VERBS_JSON_TEXT)["verbs"]
_VERB_SPEC: dict[str, dict[str, Any]] = {v["verb"]: v for v in VERBS}
#: Python method name of each verb on a :class:`MediaClient` / :class:`Media`.
VERB_METHODS: dict[str, str] = {
    "image": "image", "video": "video", "speech": "speech", "music": "music",
    "transcribe": "transcribe", "cloneVoice": "clone_voice", "talk": "talk",
}
_TEXT_VERBS = ("transcribe", "cloneVoice")

_MIME = {
    "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "webp": "image/webp",
    "gif": "image/gif", "mp4": "video/mp4", "webm": "video/webm", "mov": "video/quicktime",
    "mp3": "audio/mpeg", "wav": "audio/wav", "ogg": "audio/ogg", "m4a": "audio/mp4",
    "flac": "audio/flac",
}
_EXT = {
    "image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp", "image/gif": ".gif",
    "video/mp4": ".mp4", "video/webm": ".webm", "video/quicktime": ".mov", "audio/mpeg": ".mp3",
    "audio/wav": ".wav", "audio/x-wav": ".wav", "audio/wave": ".wav", "audio/ogg": ".ogg",
    "audio/mp4": ".m4a", "audio/flac": ".flac", "audio/aac": ".aac", "text/plain": ".txt",
}
_URL_EXT_RE = re.compile(r"\.([A-Za-z0-9]{1,5})")


def _url_ext(url: str) -> Optional[str]:
    """§7G.12 D22: the URL path's own extension (last segment), lowercased."""
    base = urlsplit(url).path.rsplit("/", 1)[-1]
    if "." not in base:
        return None
    m = _URL_EXT_RE.fullmatch(base[base.rindex("."):])
    return "." + m.group(1).lower() if m else None
_KIND_DEFAULT_MIME = {"image": "image/png", "video": "video/mp4", "audio": "audio/mpeg"}
_KIND_PART_TYPE = {"image": "image", "audio": "audio", "video": "file"}
_PART_WORD = {"image": "image", "audio": "audio", "file": "video"}
_QUOTA_WORDS = ("quota", "credit", "billing", "balance")
_ASCII_LOWER = {c: c + 32 for c in range(ord("A"), ord("Z") + 1)}

def _package_version() -> str:
    try:
        from importlib.metadata import version

        return version("toolnexus")
    except Exception:  # noqa: BLE001 — running from a source tree without metadata
        return "0.0.0"


#: §7G.12 D23: the default ``User-Agent`` on every media HTTP request.
USER_AGENT = f"toolnexus/{_package_version()} (python)"

DEFAULT_TIMEOUT_MS = 600000
DEFAULT_MAX_BYTES = 104857600


def _ascii_lower(s: str) -> str:
    return s.translate(_ASCII_LOWER)


# --------------------------------------------------------------------------- #
# Errors and results
# --------------------------------------------------------------------------- #
class MediaError(Exception):
    """A failed media verb; ``str(e)`` is the exact §7G message. ``status`` is one of
    ``failed`` | ``timeout`` | ``cancelled`` | ``unavailable``; ``job_id`` when known."""

    def __init__(self, message: str, status: str = "failed", job_id: Optional[str] = None) -> None:
        super().__init__(message)
        self.message = message
        self.status = status
        self.job_id = job_id


class MediaUnavailableError(MediaError):
    """The call was valid but this client cannot serve it now (no key, no credits, quota,
    CLI missing, plan limit). :func:`create_media` falls through to the next client."""

    def __init__(self, message: str, job_id: Optional[str] = None) -> None:
        super().__init__(message, "unavailable", job_id)


@dataclass
class MediaFile:
    """One produced file. ``type`` is the §1B part type (``image`` | ``audio`` | ``file``);
    ``url`` an http(s) or ``file://`` URL, ``path`` an absolute local path, ``data`` padded
    base64 — which are set is decided by ``gather`` (§7G.5)."""

    type: str
    mime_type: str
    url: Optional[str] = None
    path: Optional[str] = None
    data: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {"type": self.type, "mimeType": self.mime_type}
        if self.url is not None:
            out["url"] = self.url
        if self.path is not None:
            out["path"] = self.path
        if self.data is not None:
            out["data"] = self.data
        return out


@dataclass
class MediaResult:
    files: list[MediaFile] = field(default_factory=list)
    text: Optional[str] = None
    job_id: Optional[str] = None
    status: str = ""
    #: §7G.12 D21: provider-reported usage only (``inputTokens``/``outputTokens``/``costUsd``/``raw``).
    usage: Optional[dict[str, Any]] = None
    #: Who served the call (§7G.10b): the preset id, an HTTP style's name, else the client name.
    style: Optional[str] = None
    #: The configured model for the verb, when one was set; never guessed (§7G.10b).
    model: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        """The canonical §7G shape (``jobId``/``text`` present only when set)."""
        out: dict[str, Any] = {"files": [f.to_dict() for f in self.files]}
        if self.text is not None:
            out["text"] = self.text
        if self.job_id is not None:
            out["jobId"] = self.job_id
        out["status"] = self.status
        if self.usage:
            out["usage"] = self.usage
        if self.style:
            out["style"] = self.style
        if self.model:
            out["model"] = self.model
        return out


OnProgress = Callable[[dict[str, Any]], Union[None, Awaitable[None]]]


@dataclass
class MediaContext:
    """Per-call context (§7G.2). Cancellation is the awaiting task's cancellation, so there
    is no signal field. ``deadline`` is absolute (``time.time()`` seconds); the effective job
    deadline is the earlier of it and ``loop.timeout_ms`` from the start. ``progress`` replaces
    the client's ``loop.on_progress`` for this call. ``provider_options`` overwrite the request
    body's top-level keys last (function face only)."""

    deadline: Optional[float] = None
    progress: Optional[OnProgress] = None
    provider_options: Optional[dict[str, Any]] = None


@runtime_checkable
class MediaClient(Protocol):
    """A media provider (§7G.2). Only ``name`` and ``supports`` are required. Optional:

    - ``args(verb) -> [{"name", "required"}]`` — the canonical args it accepts (absent ⇒ every
      canonical arg of the verb, canonical required);
    - one ``async`` method per verb it supports — ``image``, ``video``, ``speech``, ``music``,
      ``transcribe``, ``clone_voice``, ``talk`` — each ``(args: dict, ctx: MediaContext) ->
      MediaResult``; or one ``async generate(verb, args, ctx)`` serving them all.

    A failure raises :class:`MediaError`; "cannot serve it now" raises
    :class:`MediaUnavailableError`.
    """

    name: str

    def supports(self, verb: str) -> bool: ...


class BaseMediaClient:
    """Convenience base: implement ``supports`` and ``generate``; the seven verb methods
    delegate to ``generate``."""

    name: str = "custom"

    def supports(self, verb: str) -> bool:  # pragma: no cover - overridden
        return False

    async def generate(self, verb: str, args: dict[str, Any], ctx: MediaContext) -> MediaResult:
        raise MediaError(f'media: no client supports "{verb}"')

    async def image(self, args: dict[str, Any], ctx: MediaContext) -> MediaResult:
        return await self.generate("image", args, ctx)

    async def video(self, args: dict[str, Any], ctx: MediaContext) -> MediaResult:
        return await self.generate("video", args, ctx)

    async def speech(self, args: dict[str, Any], ctx: MediaContext) -> MediaResult:
        return await self.generate("speech", args, ctx)

    async def music(self, args: dict[str, Any], ctx: MediaContext) -> MediaResult:
        return await self.generate("music", args, ctx)

    async def transcribe(self, args: dict[str, Any], ctx: MediaContext) -> MediaResult:
        return await self.generate("transcribe", args, ctx)

    async def clone_voice(self, args: dict[str, Any], ctx: MediaContext) -> MediaResult:
        return await self.generate("cloneVoice", args, ctx)

    async def talk(self, args: dict[str, Any], ctx: MediaContext) -> MediaResult:
        return await self.generate("talk", args, ctx)


# --------------------------------------------------------------------------- #
# Shared client config (§7G.4)
# --------------------------------------------------------------------------- #
@dataclass
class LoopConfig:
    interval_ms: Optional[float] = None
    timeout_ms: Optional[float] = None
    on_progress: Optional[OnProgress] = None


@dataclass
class GatherConfig:
    mode: Optional[str] = None  # "url" | "download" | "inline"
    dir: Optional[str] = None
    #: When set, saved files are ``<slug><ext>``, ``<slug>-2<ext>``… (first free name); the slug
    #: is the name with every byte outside ``[A-Za-z0-9._-]`` made ``-``, leading ``.``/``-``
    #: dropped; empty ⇒ the default naming (§7G.10b).
    name: Optional[str] = None
    max_bytes: Optional[int] = None
    keep: Optional[bool] = None


class MediaTransport(Protocol):
    """HTTP seam for media clients. ``request`` is blocking (run in a worker thread) and
    returns ``(status, body, headers)`` (``body`` bytes, or text) for every status — the client
    owns error mapping and redirects. A transport must NOT follow redirects itself; it reports
    the 3xx with its ``Location`` header and the client follows it under the §7G.8 rules. A
    2-tuple ``(status, body)`` carries no headers."""

    def request(
        self, method: str, url: str, headers: dict[str, str], body: Optional[bytes], timeout: float
    ) -> Union[tuple[int, Union[bytes, str]], tuple[int, Union[bytes, str], dict[str, str]]]: ...


@dataclass
class MediaConfig:
    """One config shape for every built-in client (§7G.4). Fields a client does not use are
    ignored. ``loop`` / ``gather`` take a :class:`LoopConfig` / :class:`GatherConfig` or a dict
    (snake_case or the spec's camelCase keys); ``http_client`` is a :class:`MediaTransport`."""

    model: Optional[str] = None
    models: dict[str, str] = field(default_factory=dict)
    key: Optional[str] = None
    url: Optional[str] = None
    command: Optional[Union[str, list[str]]] = None
    loop: LoopConfig = field(default_factory=LoopConfig)
    gather: GatherConfig = field(default_factory=GatherConfig)
    http_client: Optional[MediaTransport] = None


_ALIASES = {
    "intervalMs": "interval_ms", "timeoutMs": "timeout_ms", "onProgress": "on_progress",
    "maxBytes": "max_bytes", "httpClient": "http_client", "http_transport": "http_client",
}


def _norm_keys(d: dict[str, Any]) -> dict[str, Any]:
    return {_ALIASES.get(k, k): v for k, v in d.items()}


def media_config(config: Union[MediaConfig, dict[str, Any], None] = None, **kw: Any) -> MediaConfig:
    """Normalise a config (a :class:`MediaConfig`, a dict, keyword args, or a mix — keywords
    win) into a :class:`MediaConfig`."""
    if isinstance(config, MediaConfig):
        base = {f: getattr(config, f) for f in config.__dataclass_fields__}
    else:
        base = _norm_keys(dict(config or {}))
    base.update(_norm_keys(kw))
    loop = base.get("loop")
    if isinstance(loop, dict):
        loop = LoopConfig(**{k: v for k, v in _norm_keys(loop).items() if k in LoopConfig.__dataclass_fields__})
    gather = base.get("gather")
    if isinstance(gather, dict):
        gather = GatherConfig(**{k: v for k, v in _norm_keys(gather).items() if k in GatherConfig.__dataclass_fields__})
    return MediaConfig(
        model=base.get("model"),
        models=dict(base.get("models") or {}),
        key=base.get("key"),
        url=base.get("url"),
        command=base.get("command"),
        loop=loop if isinstance(loop, LoopConfig) else LoopConfig(),
        gather=gather if isinstance(gather, GatherConfig) else GatherConfig(),
        http_client=base.get("http_client"),
    )


def _positive(v: Any, default: float) -> float:
    """§7G: an interval/timeout that is absent, not a number or <= 0 takes the default."""
    if isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0:
        return v
    return default


def _pos_or_none(v: Any) -> Optional[float]:
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0 else None


# --------------------------------------------------------------------------- #
# Small renderers
# --------------------------------------------------------------------------- #
def _num_text(v: Any) -> str:
    """Integral numbers render as integer text; other numbers in shortest round-trip form."""
    if isinstance(v, bool):
        return json.dumps(v)
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        if math.isfinite(v) and v == int(v):
            return str(int(v))
        return repr(v)
    return str(v)


def _status_text(v: Any) -> str:
    """Poll status (§7G.8): string as-is, a number rendered like the envelope code, else ""."""
    if isinstance(v, str):
        return v
    if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v):
        return _num_text(v)
    return ""


def _cap(text: str, n: int) -> str:
    """First ``n`` code points, then "…" when truncated."""
    return text if len(text) <= n else text[:n] + "…"


def _cap_body(text: str) -> str:
    """Echoed error bodies keep their first 1000 code points, then "…" (§7G.8)."""
    return _cap(text, 1000)


def _pct(s: str) -> str:
    """RFC 3986 percent-encoding: keep A-Z a-z 0-9 - . _ ~ (§7G.8)."""
    return quote(s, safe="")


def _mime_for(url: str, kind: str) -> str:
    """mimeType of a URL: its path's extension, else the kind default (§7G.7 step 8)."""
    path = urlsplit(url).path
    base = path.rsplit("/", 1)[-1]
    if "." in base:
        ext = base.rsplit(".", 1)[-1].lower()
        if ext in _MIME:
            return _MIME[ext]
    return _KIND_DEFAULT_MIME.get(kind, "application/octet-stream")


def _ext_mime(name: str) -> Optional[str]:
    base = name.rsplit("/", 1)[-1]
    if "." not in base:
        return None
    return _MIME.get(base.rsplit(".", 1)[-1].lower())


def _json_eq(a: Any, b: Any) -> bool:
    # JSON equality: bool is not a number; 0 == 0.0.
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool) and a == b
    return a == b


_ENV_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _expand(value: str, key: Optional[str] = None) -> str:
    """``${VAR}`` from the process env at call time (unset ⇒ ""), or ``key`` for every one."""
    if key is not None:
        return _ENV_RE.sub(lambda m: key, value)
    return _ENV_RE.sub(lambda m: os.environ.get(m.group(1), ""), value)


# --------------------------------------------------------------------------- #
# dotPath
# --------------------------------------------------------------------------- #
_MISSING = object()


def _get(obj: Any, path: str) -> Any:
    if path == "":
        return obj  # §7G.8: an empty dotPath is the whole value
    cur = obj
    for seg in path.split("."):
        if seg.isdigit() and isinstance(cur, list):
            i = int(seg)
            if i >= len(cur):
                return _MISSING
            cur = cur[i]
        elif isinstance(cur, dict) and seg in cur:
            cur = cur[seg]
        else:
            return _MISSING
    return cur


def _set(obj: dict[str, Any], path: str, value: Any) -> None:
    segs = path.split(".")
    cur: Any = obj
    for i, seg in enumerate(segs):
        last = i == len(segs) - 1
        nxt_is_index = not last and segs[i + 1].isdigit()
        if isinstance(cur, list) and seg.isdigit():
            idx = int(seg)
            while len(cur) <= idx:
                cur.append(None)
            if last:
                cur[idx] = value
                return
            if not _fits(cur[idx], nxt_is_index):
                cur[idx] = [] if nxt_is_index else {}
            cur = cur[idx]
        else:
            if last:
                cur[seg] = value
                return
            if not _fits(cur.get(seg), nxt_is_index):
                cur[seg] = [] if nxt_is_index else {}
            cur = cur[seg]


def _fits(child: Any, nxt_is_index: bool) -> bool:
    """An object takes any segment; an array only a numeric one (else it is replaced, §7G.8)."""
    return isinstance(child, dict) or (isinstance(child, list) and nxt_is_index)


# --------------------------------------------------------------------------- #
# HTTP: default transport, redirects, deadlines (§7G.8)
# --------------------------------------------------------------------------- #
def _origin(url: str) -> tuple[str, str, Optional[int]]:
    u = urlsplit(url)
    scheme = u.scheme.lower()
    return scheme, (u.hostname or "").lower(), u.port or {"http": 80, "https": 443}.get(scheme)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Hand every 3xx back to the client, which follows redirects itself (§7G.8)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[override]
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


class UrllibMediaTransport:
    """Default media transport — stdlib ``urllib`` with automatic redirects disabled. Reads
    at most ``limit`` bytes of a body when given (gather's ``maxBytes + 1``)."""

    accepts_limit = True

    def request(
        self, method: str, url: str, headers: dict[str, str], body: Optional[bytes], timeout: float,
        limit: Optional[int] = None,
    ) -> tuple[int, bytes, dict[str, str]]:
        req = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with _OPENER.open(req, timeout=timeout) as resp:
                data = resp.read(limit) if limit is not None else resp.read()
                return resp.status, data, dict(resp.headers.items())
        except urllib.error.HTTPError as e:
            data = e.read(limit) if limit is not None else e.read()
            return e.code, data, dict(e.headers.items()) if e.headers else {}


_REDIRECTS = (301, 302, 303, 307, 308)
_MAX_REDIRECTS = 10


def _header(headers: dict[str, str], name: str) -> Optional[str]:
    low = name.lower()
    for k, v in headers.items():
        if k.lower() == low and v:
            return v
    return None


class _Deadline(Exception):
    """A request cut short by the job deadline; callers raise the §7G timed-out message."""


async def _http(
    transport: Any,
    verb: str,
    method: str,
    url: str,
    deadline: float,
    *,
    headers: Optional[dict[str, str]] = None,
    base_origin: Optional[tuple[Any, ...]] = None,
    body: Optional[bytes] = None,
    content_type: Optional[str] = None,
    limit: Optional[int] = None,
) -> tuple[int, bytes, dict[str, str]]:
    """One request with the §7G.8 redirect, origin and per-request-deadline rules.
    ``headers`` go only to ``base_origin`` (None ⇒ never: downloads and input fetches)."""
    redirects = 0
    while True:
        hdrs = dict(headers or {}) if base_origin is not None and _origin(url) == base_origin else {}
        if body is not None and content_type:
            hdrs["Content-Type"] = content_type
        if not any(k.lower() == "user-agent" for k in hdrs):  # §7G.12 D23
            hdrs["User-Agent"] = USER_AGENT
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise _Deadline()
        kw = {"limit": limit} if limit is not None and getattr(transport, "accepts_limit", False) else {}
        try:
            out = await asyncio.wait_for(
                asyncio.to_thread(transport.request, method, url, hdrs, body, remaining + 1.0, **kw),
                remaining,
            )
        except asyncio.CancelledError:
            raise
        except (asyncio.TimeoutError, TimeoutError):
            raise _Deadline() from None
        except Exception as e:  # transport error: not byte-fixed (§7G.8)
            if time.monotonic() >= deadline:
                raise _Deadline() from None
            raise MediaError(f"{verb}: {e}") from e
        status = out[0]
        data = out[1]
        if isinstance(data, str):
            data = data.encode("utf-8")
        resp_headers = dict(out[2] or {}) if len(out) > 2 else {}
        loc = _header(resp_headers, "Location")
        if status not in _REDIRECTS or loc is None:
            return status, data or b"", resp_headers
        if redirects >= _MAX_REDIRECTS:
            raise MediaError(f"{verb}: too many redirects")
        redirects += 1
        url = urljoin(url, loc)
        if status in (301, 302, 303) and method.upper() not in ("GET", "HEAD"):
            method, body = "GET", None


def _check_status(name: str, status: int, data: bytes) -> None:
    """§7G.2 unavailable mapping, then any other non-2xx ⇒ ``HTTP <status>: <body>``."""
    text = data.decode("utf-8", errors="replace")
    if status == 402 or (status == 429 and any(w in _ascii_lower(text) for w in _QUOTA_WORDS)):
        raise MediaUnavailableError(f"{name}: HTTP {status}: {_cap_body(text)}")
    if status < 200 or status >= 300:
        raise MediaError(f"HTTP {status}: {_cap_body(text)}")


def _parse_json(verb: str, data: bytes) -> Any:
    try:
        return json.loads(data.decode("utf-8"))
    except (ValueError, TypeError, UnicodeDecodeError):
        raise MediaError(f"{verb}: response is not JSON") from None


def _multipart(fields: list[tuple[Any, ...]]) -> tuple[bytes, str]:
    """``(name, value)`` or ``(name, filename, content_type, bytes)`` ⇒ (body, content type)."""
    boundary = "toolnexus-" + uuid.uuid4().hex
    out = bytearray()
    for f in fields:
        out += f"--{boundary}\r\n".encode()
        if len(f) == 2:
            out += f'Content-Disposition: form-data; name="{f[0]}"\r\n\r\n'.encode()
            out += str(f[1]).encode("utf-8")
        else:
            out += f'Content-Disposition: form-data; name="{f[0]}"; filename="{f[1]}"\r\n'.encode()
            out += f"Content-Type: {f[2]}\r\n\r\n".encode()
            out += f[3]
        out += b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


# --------------------------------------------------------------------------- #
# A job: one verb call on one client — deadline, progress, requests, gather
# --------------------------------------------------------------------------- #
_SEQ = itertools.count(1)


def _media_slug(name: str) -> str:
    b = name.encode("utf-8")
    out = "".join(chr(c) if (chr(c).isascii() and (chr(c).isalnum() or chr(c) in "._-")) else "-" for c in b)
    return out.lstrip(".-")


def _free_name(d: str, slug: str, ext: str) -> str:
    """The first of ``<slug><ext>``, ``<slug>-2<ext>``… not in ``d``, created exclusively."""
    n = 1
    while True:
        p = os.path.join(d, slug + ext if n == 1 else f"{slug}-{n}{ext}")
        try:
            os.close(os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644))
            return p
        except FileExistsError:
            n += 1


class _Job:
    def __init__(
        self,
        client_name: str,
        verb: str,
        kind: str,
        cfg: MediaConfig,
        ctx: Optional[MediaContext],
        *,
        timeout_ms: Optional[float] = None,
        default_mode: str = "download",
        base_url: str = "",
        headers: Optional[dict[str, str]] = None,
    ) -> None:
        self.name = client_name
        self.verb = verb
        self.kind = kind
        self.cfg = cfg
        self.ctx = ctx or MediaContext()
        self.start = time.monotonic()
        self.timeout_ms = timeout_ms if timeout_ms is not None else _positive(cfg.loop.timeout_ms, DEFAULT_TIMEOUT_MS)
        self.deadline = self.start + self.timeout_ms / 1000.0
        if self.ctx.deadline is not None:
            self.deadline = min(self.deadline, self.start + (self.ctx.deadline - time.time()))
        self.transport = cfg.http_client if cfg.http_client is not None else UrllibMediaTransport()
        self.mode = cfg.gather.mode or default_mode
        self.dir = cfg.gather.dir or os.path.join(tempfile.gettempdir(), "toolnexus-media")
        self.max_bytes = int(_positive(cfg.gather.max_bytes, DEFAULT_MAX_BYTES))
        self.keep = cfg.gather.keep is not False
        self.base_url = base_url
        self.base_origin = _origin(base_url) if base_url else None
        self.headers = headers or {}
        self.job_id: Optional[str] = None

    # ---- errors ----
    def timeout_error(self) -> MediaError:
        ms = _num_text(self.timeout_ms)
        if self.job_id is not None:
            return MediaError(f"job {self.job_id} timed out after {ms}ms", "timeout", self.job_id)
        return MediaError(f"{self.verb}: timed out after {ms}ms", "timeout")

    def expired(self) -> bool:
        return time.monotonic() >= self.deadline

    # ---- progress ----
    async def progress(self, status: str) -> None:
        cb = self.ctx.progress if self.ctx.progress is not None else self.cfg.loop.on_progress
        if cb is None:
            return
        ev: dict[str, Any] = {"verb": self.verb, "client": self.name}
        if self.job_id is not None:
            ev["jobId"] = self.job_id
        ev["status"] = status
        ev["elapsedMs"] = int((time.monotonic() - self.start) * 1000)
        r = cb(ev)
        if inspect.isawaitable(r):
            await r

    async def sleep(self, interval_ms: float) -> None:
        await asyncio.sleep(interval_ms / 1000.0)

    # ---- requests to the provider ----
    def url(self, path: str) -> str:
        return self.base_url.rstrip("/") + path

    def body(self, base: dict[str, Any]) -> dict[str, Any]:
        for k, v in (self.ctx.provider_options or {}).items():
            base[k] = v
        return base

    def fields(self, base: list[tuple[Any, ...]]) -> list[tuple[Any, ...]]:
        for k, v in (self.ctx.provider_options or {}).items():
            for i, f in enumerate(base):
                if len(f) == 2 and f[0] == k:
                    base[i] = (k, v)
                    break
            else:
                base.append((k, v))
        return base

    async def raw(
        self, method: str, url: str, *, json_body: Any = None, multipart: Optional[list[tuple[Any, ...]]] = None,
    ) -> tuple[bytes, dict[str, str]]:
        """A provider request: client headers (same origin only), status mapping."""
        body: Optional[bytes] = None
        ctype: Optional[str] = None
        if multipart is not None:
            body, ctype = _multipart(multipart)
        elif json_body is not None:
            body, ctype = json.dumps(json_body).encode("utf-8"), "application/json"
        try:
            status, data, hdrs = await _http(
                self.transport, self.verb, method, url, self.deadline,
                headers=self.headers, base_origin=self.base_origin, body=body, content_type=ctype,
            )
        except _Deadline:
            raise self.timeout_error() from None
        _check_status(self.name, status, data)
        return data, hdrs

    async def json(self, method: str, url: str, **kw: Any) -> Any:
        data, _ = await self.raw(method, url, **kw)
        return _parse_json(self.verb, data)

    # ---- input references (§7G.1) ----
    async def read_input(self, value: str) -> tuple[bytes, str, str]:
        """Resolve an input reference ⇒ (bytes, upload filename, part Content-Type)."""
        u = urlsplit(value)
        seg = u.path.rsplit("/", 1)[-1] if u.scheme in ("http", "https", "file") else os.path.basename(value)
        fname = seg or "input"
        ctype = _ext_mime(fname) or "application/octet-stream"
        try:
            if u.scheme in ("http", "https"):
                try:
                    status, data, _ = await _http(
                        self.transport, self.verb, "GET", value, self.deadline, limit=self.max_bytes + 1)
                except _Deadline:
                    raise self.timeout_error() from None
                if status < 200 or status >= 300:
                    raise OSError(f"HTTP {status}")
            else:
                path = _file_url_path(value) if u.scheme == "file" else value
                if os.path.getsize(path) > self.max_bytes:
                    raise OSError(self._too_big())
                with open(path, "rb") as f:
                    data = f.read(self.max_bytes + 1)
            if len(data) > self.max_bytes:  # D14
                raise OSError(self._too_big())
        except MediaError as e:
            if e.status == "timeout":
                raise
            raise MediaError(f'{self.verb}: cannot read "{value}": {e}') from None
        except Exception as e:  # noqa: BLE001
            raise MediaError(f'{self.verb}: cannot read "{value}": {e}') from None
        self.check_size(len(data))
        return data, fname, ctype

    # ---- gather (§7G.5) ----
    def _too_big(self) -> str:
        return f"file exceeds gather.maxBytes ({self.max_bytes} bytes)"

    def check_size(self, n: int) -> None:
        if n > self.max_bytes:
            raise MediaError(f"{self.verb}: file exceeds gather.maxBytes ({self.max_bytes} bytes)")

    def _ptype(self) -> str:
        return _KIND_PART_TYPE.get(self.kind, "file")

    def _save(self, data: bytes, mime: str, ext: Optional[str] = None) -> MediaFile:
        d = os.path.abspath(self.dir)
        os.makedirs(d, exist_ok=True)
        ext = ext or _EXT.get(mime, ".bin")
        slug = _media_slug(self.cfg.gather.name or "")
        if slug:
            path = _free_name(d, slug, ext)
        else:
            path = os.path.join(d, f"{self.name}-{self.verb}-{int(time.time() * 1000)}-{next(_SEQ)}{ext}")
        with open(path, "wb") as f:
            f.write(data)
        return MediaFile(self._ptype(), mime, url="file://" + path, path=path)

    def gather_bytes(self, data: bytes, mime: str, ext: Optional[str] = None) -> MediaFile:
        self.check_size(len(data))
        if self.mode == "inline":
            return MediaFile(self._ptype(), mime, data=base64.b64encode(data).decode("ascii"))
        return self._save(data, mime, ext)

    def gather_base64(self, b64: str, mime: str) -> MediaFile:
        try:
            data = base64.b64decode(b64, validate=False)
        except Exception:  # noqa: BLE001
            raise MediaError(f"{self.verb}: invalid base64 result") from None
        return self.gather_bytes(data, mime)

    def gather_data_url(self, url: str) -> MediaFile:
        head, _, payload = url.partition(",")
        meta = head[len("data:"):]
        parts = meta.split(";")
        mime = parts[0] or _KIND_DEFAULT_MIME.get(self.kind, "application/octet-stream")
        if "base64" in parts[1:]:
            return self.gather_base64(payload, mime)
        from urllib.parse import unquote_to_bytes

        return self.gather_bytes(unquote_to_bytes(payload), mime)

    async def gather_url(self, url: str) -> MediaFile:
        if url.startswith("data:"):
            return self.gather_data_url(url)
        if self.mode == "url":
            return MediaFile(self._ptype(), _mime_for(url, self.kind), url=url)
        try:
            status, data, hdrs = await _http(
                self.transport, self.verb, "GET", url, self.deadline, limit=self.max_bytes + 1)
        except _Deadline:
            raise self.timeout_error() from None
        if status < 200 or status >= 300:
            raise MediaError(f"{self.verb}: download failed: HTTP {status}")
        if len(data) > self.max_bytes:  # D14
            raise MediaError(f"{self.verb}: download failed: {self._too_big()}")
        ct = (_header(hdrs, "Content-Type") or "").split(";", 1)[0].strip().lower()
        mime = ct if ct and ct != "application/octet-stream" else _mime_for(url, self.kind)
        return self.gather_bytes(data, mime, _url_ext(url))

    def gather_local(self, path: str) -> MediaFile:
        mime = _ext_mime(path) or _KIND_DEFAULT_MIME.get(self.kind, "application/octet-stream")
        ptype = _KIND_PART_TYPE.get(mime.split("/", 1)[0], "file")
        if self.mode == "url":
            return MediaFile(ptype, mime, url="file://" + path, path=path)
        self.check_size(os.path.getsize(path))
        with open(path, "rb") as f:
            data = f.read(self.max_bytes + 1)
        self.check_size(len(data))
        if self.mode == "inline":
            return MediaFile(ptype, mime, data=base64.b64encode(data).decode("ascii"))
        saved = self._save(data, mime)
        saved.type = ptype
        if not self.keep:
            try:
                os.remove(path)
            except OSError:
                pass
        return saved


def _file_url_path(url: str) -> str:
    from urllib.parse import unquote

    return unquote(urlsplit(url).path)


# --------------------------------------------------------------------------- #
# Style clients — the JSON style engine (§7G.7)
# --------------------------------------------------------------------------- #
def _validate_style(style: dict[str, Any]) -> None:
    verbs = style.get("verbs") or {}
    for verb in verbs:
        if verb not in _VERB_SPEC:
            raise MediaError(f'style: unknown verb "{verb}"')
    for verb in sorted(verbs):
        mapping = (verbs[verb] or {}).get("args") or {}
        for k in sorted(mapping):
            if mapping[k] == "":
                raise MediaError(f'style: empty path for argument "{k}"')


class MediaStyle(dict):
    """A loaded style (SPEC §7G.7) — what :func:`load_media_style` returns, and itself a
    :class:`MediaClient`, so ``create_media(style)`` works.

    For a JSON style file it is the parsed style ``dict`` (so it still compares equal to the
    JSON); for a built-in CLI preset it is empty and ``cli`` names the preset. The config
    given to :func:`load_media_style` configures the client behind it (model, command, loop,
    gather).

    Built-in CLI presets (ADR 0042) — media through a locally installed, logged-in CLI on the
    user's own subscription, no API key:

    - ``MediaStyle.XAI_GROK_CLI`` (``"xai-grok-cli"``): the ``grok`` CLI, image and video;
    - ``MediaStyle.OPENAI_CODEX_CLI`` (``"openai-codex-cli"``): the ``codex`` CLI, image;
    - ``MediaStyle.GOOGLE_AGY_CLI`` (``"google-agy-cli"``): Google's Antigravity ``agy``, image;
    - the ``*_MOCK`` twins: same name, verbs, args, prompt folding and errors, but no process
      is started and a fixed file is returned (mock kit).
    """

    XAI_GROK_CLI = "xai-grok-cli"
    OPENAI_CODEX_CLI = "openai-codex-cli"
    GOOGLE_AGY_CLI = "google-agy-cli"
    XAI_GROK_CLI_MOCK = "xai-grok-cli-mock"
    OPENAI_CODEX_CLI_MOCK = "openai-codex-cli-mock"
    GOOGLE_AGY_CLI_MOCK = "google-agy-cli-mock"

    def __init__(
        self, style: Optional[dict[str, Any]] = None, *, cli: Optional[str] = None,
        config: Union["MediaConfig", dict[str, Any], None] = None, **kw: Any,
    ) -> None:
        super().__init__(style or {})
        self.cli = cli
        self._config = media_config(config, **kw)

    @property
    def name(self) -> str:  # type: ignore[override]
        if self.cli:
            return _CLI_PRESETS[self.cli]
        return self.get("name") or "style"

    def client(self) -> Any:
        """The :class:`MediaClient` behind this style."""
        if self.cli:
            from .media_clients import CodexMediaClient, GrokMediaClient, _AgyMediaClient

            cls = {"grok": GrokMediaClient, "codex": CodexMediaClient, "agy": _AgyMediaClient}[_CLI_PRESETS[self.cli]]
            c = cls(self._config)
            c.mock = self.cli.endswith("-mock")
            return c
        return StyleMediaClient(dict(self), self._config)

    def supports(self, verb: str) -> bool:
        """Whether the style declares ``verb`` (the one verb-support mechanism, HTTP or CLI)."""
        return self.client().supports(verb)

    def args(self, verb: str) -> list[dict[str, Any]]:
        return _client_args(self.client(), verb)

    async def generate(self, verb: str, args: dict[str, Any], ctx: Optional[MediaContext] = None) -> MediaResult:
        return await self.client().generate(verb, args, ctx)

    def available(self) -> dict[str, Any]:
        """Cheap pre-flight (§7G.10b): ``{installed, loggedIn, reason}``. Generates nothing and
        reads no secret: a CLI preset's login is the *existence* of the CLI's own auth file."""
        cfg = self._config
        if not self.cli:
            sid = self.get("name") or "style"
            if cfg.key:
                return _avail(True, True, f"{sid}: key from config")
            names = sorted({m for v in (self.get("headers") or {}).values()
                            for m in _STYLE_ENV_RE.findall(str(v))})
            for n in names:
                if not os.environ.get(n):
                    return _avail(True, False, f"{sid}: {n} is not set")
            return _avail(True, True, f"{sid}: env present")
        label = _CLI_PRESETS[self.cli]
        if self.cli.endswith("-mock"):
            if os.environ.get(MEDIA_MOCK_AVAILABLE_ENV) == "0":
                return _avail(False, False, f"{label}: mock set unavailable by {MEDIA_MOCK_AVAILABLE_ENV}=0")
            return _avail(True, True, f"{label}: mock")
        cmd = cfg.command
        bin_ = (cmd if isinstance(cmd, str) else cmd[0]) if cmd else label
        if os.sep in bin_ or (os.altsep and os.altsep in bin_):
            found = os.path.isfile(bin_) and os.access(bin_, os.X_OK)
        else:
            found = shutil.which(bin_) is not None
        if not found:
            return _avail(False, False, f'{label}: {json.dumps(bin_, ensure_ascii=False)} not found on PATH')
        auth = _cli_auth_file(label)
        if not os.path.isfile(auth):
            return _avail(True, False, f"{label}: installed, not logged in ({auth} missing)")
        return _avail(True, True, f"{label}: installed, logged in ({auth} present)")

    def _source_id(self) -> str:
        return self.cli or self.get("name") or "style"

    def _source_model(self, verb: str) -> Optional[str]:
        return (self._config.models or {}).get(verb) or self._config.model or None

    def __eq__(self, other: object) -> bool:
        if isinstance(other, MediaStyle) and (self.cli or other.cli):
            return self.cli == other.cli
        return dict.__eq__(self, other)

    __hash__ = None  # type: ignore[assignment]

    def __repr__(self) -> str:
        return f"MediaStyle({self.cli!r})" if self.cli else f"MediaStyle({dict.__repr__(self)})"


#: Set to ``"0"`` to make every mock preset report unavailable.
MEDIA_MOCK_AVAILABLE_ENV = "TOOLNEXUS_MEDIA_MOCK_AVAILABLE"
_STYLE_ENV_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _avail(installed: bool, logged_in: bool, reason: str) -> dict[str, Any]:
    return {"installed": installed, "loggedIn": logged_in, "reason": reason}


def _home() -> str:
    return os.environ.get("HOME") or os.path.expanduser("~")


def _cli_auth_file(label: str) -> str:
    if label == "grok":
        return os.path.join(_home(), ".grok", "auth.json")
    if label == "codex":
        h = os.environ.get("CODEX_HOME")
        return os.path.join(h, "auth.json") if h else os.path.join(_home(), ".codex", "auth.json")
    return os.path.join(_home(), ".gemini", "antigravity-cli", "antigravity-oauth-token")


#: Built-in CLI preset ⇒ client label.
_CLI_PRESETS: dict[str, str] = {
    MediaStyle.XAI_GROK_CLI: "grok", MediaStyle.OPENAI_CODEX_CLI: "codex", MediaStyle.GOOGLE_AGY_CLI: "agy",
    MediaStyle.XAI_GROK_CLI_MOCK: "grok", MediaStyle.OPENAI_CODEX_CLI_MOCK: "codex", MediaStyle.GOOGLE_AGY_CLI_MOCK: "agy",
}


def load_media_style(
    path: str, config: Union["MediaConfig", dict[str, Any], None] = None, **kw: Any
) -> MediaStyle:
    """Load a style (SPEC §7G.7): a built-in CLI preset name (``MediaStyle.XAI_GROK_CLI`` …), else
    read and validate one style JSON file. ``config`` (or keyword arguments) configures the
    client behind the style (model, command, loop, gather)."""
    if path in _CLI_PRESETS:
        return MediaStyle(cli=path, config=config, **kw)
    with open(path, encoding="utf-8") as f:
        style = json.load(f)
    _validate_style(style)
    return MediaStyle(style, config=config, **kw)


class StyleMediaClient(BaseMediaClient):
    """A :class:`MediaClient` over a parsed JSON style (§7G.7). Uses ``url``, ``loop``,
    ``gather`` (default mode ``url``) and ``http_client`` from the config; ``key``, when set,
    is what every ``${…}`` in the style's headers expands to."""

    _key_env: Optional[str] = None  # built-in style clients check their key (A2E)
    _default_mode = "url"

    def __init__(self, style: dict[str, Any], config: Union[MediaConfig, dict[str, Any], None] = None, **kw: Any):
        _validate_style(style)
        self.style = style
        self.config = media_config(config, **kw)
        self.name = style.get("name") or "style"

    def supports(self, verb: str) -> bool:
        return verb in (self.style.get("verbs") or {})

    def args(self, verb: str) -> list[dict[str, Any]]:
        v = (self.style.get("verbs") or {}).get(verb) or {}
        mapping = v.get("args") or {}
        canon_req = {a["name"] for a in _VERB_SPEC[verb]["args"] if a.get("required")} if verb in _VERB_SPEC else set()
        extra = set(v.get("required") or [])
        order = [a["name"] for a in _VERB_SPEC.get(verb, {"args": []})["args"]]
        names = [n for n in order if n in mapping]  # D6: canonical names only
        return [{"name": n, "required": n in canon_req or n in extra} for n in names]

    def _style_for_call(self) -> dict[str, Any]:
        return self.style

    def _check_key(self) -> Optional[str]:
        if self._key_env is None:
            return self.config.key
        key = self.config.key or os.environ.get(self._key_env) or ""
        if not key:
            raise MediaUnavailableError(f"{self.name}: no API key (set {self._key_env})")
        return key

    async def generate(self, verb: str, args: dict[str, Any], ctx: Optional[MediaContext] = None) -> MediaResult:
        style = self._style_for_call()
        v = (style.get("verbs") or {}).get(verb)
        if v is None:
            raise MediaError(f'media: no client supports "{verb}"')
        args = {k: val for k, val in (args or {}).items() if val is not None}
        mapping = v.get("args") or {}
        canon = {a["name"] for a in _VERB_SPEC.get(verb, {"args": []})["args"]}
        for k in sorted(args):
            if k not in mapping or k not in canon:  # D6/D7
                raise MediaError(f'{verb}: argument "{k}" not supported by this style')
        key = self._check_key()
        cfg = self.config
        poll = v.get("poll") or {}
        timeout_ms = _pos_or_none(cfg.loop.timeout_ms) or _positive(poll.get("timeoutMs"), DEFAULT_TIMEOUT_MS)
        interval_ms = _pos_or_none(cfg.loop.interval_ms) or _positive(poll.get("intervalMs"), 5000)
        base = (cfg.url or str(style.get("baseUrl", ""))).rstrip("/")
        headers = {k: _expand(str(val), key) for k, val in (style.get("headers") or {}).items()}
        kind = v.get("kind", "")
        job = _Job(self.name, verb, kind, cfg, ctx, timeout_ms=timeout_ms, default_mode=self._default_mode,
                   base_url=base, headers=headers)

        body = copy.deepcopy(v.get("defaults") or {})
        for k, val in args.items():
            _set(body, mapping[k], val)
        job.body(body)
        start = v.get("start") or {}
        resp = await self._call(job, style, start.get("method") or "POST", start.get("path", ""), body)

        status = "completed"
        if v.get("mode") == "async":
            id_path = v.get("idPath", "")
            raw = _get(resp, id_path)
            if not ((isinstance(raw, str) and raw != "") or (
                isinstance(raw, (int, float)) and not isinstance(raw, bool)
                and math.isfinite(raw) and raw == int(raw)
            )):
                raise MediaError(f"{verb}: no job id at {id_path}")
            job.job_id = _num_text(raw)
            resp, status = await self._poll(job, style, poll, interval_ms)

        rp = v.get("resultPath", "")
        val = _get(resp, rp)
        if kind == "text":
            if isinstance(val, str):
                return MediaResult(files=[], text=val, job_id=job.job_id, status=status)
            if isinstance(val, (int, float)) and not isinstance(val, bool):
                return MediaResult(files=[], text=_num_text(val), job_id=job.job_id, status=status)
            raise MediaError(f"{verb}: no result at {rp}", job_id=job.job_id)
        urls: list[str] = []

        def flat(x: Any) -> None:
            if isinstance(x, str):
                if x:
                    urls.append(x)
            elif isinstance(x, list):
                for y in x:
                    flat(y)

        if val is not _MISSING:
            flat(val)
        if not urls:
            raise MediaError(f"{verb}: no result at {rp}", job_id=job.job_id)
        files = [await job.gather_url(u) for u in urls]
        return MediaResult(files=files, job_id=job.job_id, status=status)

    async def _call(self, job: _Job, style: dict[str, Any], method: str, path: str, body: Any) -> Any:
        parsed = await job.json(method, job.url(path), json_body=body)
        env = style.get("errorEnvelope")
        if env:
            code = _get(parsed, env.get("codePath", ""))
            if code is not _MISSING and not any(_json_eq(code, ok) for ok in env.get("okValues") or []):
                msg = _get(parsed, env["messagePath"]) if env.get("messagePath") else _MISSING
                msg_s = msg if isinstance(msg, str) else ""
                if isinstance(code, str):
                    code_s = code
                elif isinstance(code, (int, float)) and not isinstance(code, bool) and math.isfinite(code) and code == int(code):
                    code_s = str(int(code))
                else:
                    code_s = json.dumps(code)
                raise MediaError(f"API error {code_s}: {msg_s}", job_id=job.job_id)
        return parsed

    async def _poll(self, job: _Job, style: dict[str, Any], p: dict[str, Any], interval_ms: float) -> tuple[Any, str]:
        path = p.get("path", "").replace("{id}", _pct(job.job_id or ""))
        done = p.get("done") or []
        failed = p.get("failed") or []
        while True:
            await job.sleep(interval_ms)
            resp = await self._call(job, style, p.get("method") or "GET", path, None)
            st = _status_text(_get(resp, p.get("statusPath", "")))
            await job.progress(st)
            if st in done:
                return resp, st
            if st in failed:
                msg = _get(resp, p["errorPath"]) if p.get("errorPath") else _MISSING
                if isinstance(msg, str) and msg:
                    raise MediaError(f"job {job.job_id} {st}: {msg}", job_id=job.job_id)
                raise MediaError(f"job {job.job_id} {st}", job_id=job.job_id)
            if job.expired():
                raise job.timeout_error()


def style_media_client(
    style: dict[str, Any], config: Union[MediaConfig, dict[str, Any], None] = None, **kw: Any
) -> StyleMediaClient:
    """Make a :class:`MediaClient` named ``style.name ?? "style"`` from a parsed style (§7G.7)."""
    return StyleMediaClient(style, config, **kw)


# --------------------------------------------------------------------------- #
# createMedia — routing (§7G.3) and the tool face (§7G.6)
# --------------------------------------------------------------------------- #
def _client_args(client: Any, verb: str) -> list[dict[str, Any]]:
    fn = getattr(client, "args", None)
    declared = fn(verb) if callable(fn) else None
    if declared is None:
        return [{"name": a["name"], "required": bool(a.get("required"))} for a in _VERB_SPEC[verb]["args"]]
    out = []
    for a in declared:
        if isinstance(a, str):
            out.append({"name": a, "required": False})
        elif isinstance(a, dict):
            out.append({"name": a["name"], "required": bool(a.get("required"))})
        else:  # an object with .name / .required
            out.append({"name": a.name, "required": bool(getattr(a, "required", False))})
    return out


def _is_unavailable(e: BaseException) -> bool:
    return isinstance(e, MediaUnavailableError) or (isinstance(e, MediaError) and e.status == "unavailable")


_KW = {"aspect_ratio": "aspectRatio"}


class Media:
    """The §7G media router. Build with :func:`create_media`."""

    def __init__(self, clients: list[Any]) -> None:
        if not clients:
            raise MediaError("media: no clients")
        for c in clients:
            if isinstance(c, MediaStyle):
                continue
            if isinstance(c, dict) or not callable(getattr(c, "supports", None)):
                raise TypeError(
                    "create_media takes MediaClient(s); wrap a JSON style with style_media_client(style)")
        self.clients = list(clients)

    def supports(self, verb: str) -> bool:
        return any(c.supports(verb) for c in self.clients)

    def verbs(self) -> list[str]:
        """Every verb some source supports, in canonical (``verbs.json``) order."""
        return [v["verb"] for v in VERBS if self.supports(v["verb"])]

    def _noun(self) -> str:
        """``style`` when every source is a loaded style, else ``client``."""
        return "style" if all(isinstance(c, MediaStyle) for c in self.clients) else "client"

    async def _route(
        self, verb: str, args: Optional[dict[str, Any]], ctx: Optional[MediaContext]
    ) -> tuple[MediaResult, Any]:
        args = {_KW.get(k, k): v for k, v in (args or {}).items() if v is not None}
        ctx = ctx or MediaContext()
        cands = [c for c in self.clients if c.supports(verb)]
        if not cands:
            raise MediaError(f'media: no {self._noun()} supports "{verb}"')
        order = [a["name"] for a in _VERB_SPEC.get(verb, {"args": []})["args"]]
        for k in sorted(args):  # D7/D19: a non-canonical key fails at once
            if k not in order:
                raise MediaError(f'{verb}: argument "{k}" not supported')
        messages: list[str] = []
        for c in cands:
            call_args = args
            c_acc = _client_args(c, verb)
            c_names = {a["name"] for a in c_acc}
            bad = [k for k in sorted(args) if k not in c_names]
            if bad:  # D19: skipped like unavailable, every candidate
                messages.append(f'{c.name}: argument "{bad[0]}" not supported')
                continue
            c_req = {a["name"] for a in c_acc if a["required"]}
            missing = [n for n in order if n in c_req and n not in args] + sorted(
                n for n in c_req if n not in order and n not in args)
            if missing:
                messages.append(f'{c.name}: missing required argument "{missing[0]}"')
                continue
            method = getattr(c, VERB_METHODS.get(verb, verb), None)
            try:
                if callable(method):
                    res = await method(dict(call_args), ctx)
                else:
                    res = await c.generate(verb, dict(call_args), ctx)
            except MediaError as e:
                if _is_unavailable(e):
                    messages.append(str(e))
                    continue
                raise
            if not res.style:
                res.style = c._source_id() if isinstance(c, MediaStyle) else c.name
            if not res.model and isinstance(c, MediaStyle):
                res.model = c._source_model(verb)
            return res, c
        raise MediaUnavailableError(f'media: no client available for "{verb}": ' + "; ".join(messages))

    async def generate(
        self,
        verb: str,
        args: Optional[dict[str, Any]] = None,
        provider_options: Optional[dict[str, Any]] = None,
        *,
        deadline: Optional[float] = None,
        progress: Optional[OnProgress] = None,
        ctx: Optional[MediaContext] = None,
    ) -> MediaResult:
        """Run ``verb`` with canonical ``args`` (SPEC §7G.3). Raises :class:`MediaError`."""
        if ctx is None:
            ctx = MediaContext(deadline=deadline, progress=progress, provider_options=provider_options)
        res, _ = await self._route(verb, args, ctx)
        return res

    # ------------------------------ typed verbs ------------------------------ #
    async def image(
        self, prompt: str, *, width: Optional[int] = None, height: Optional[int] = None,
        aspect_ratio: Optional[str] = None, seed: Optional[int] = None, **kw: Any,
    ) -> MediaResult:
        return await self.generate("image", {"prompt": prompt, "width": width, "height": height,
                                             "aspectRatio": aspect_ratio, "seed": seed}, **kw)

    async def video(
        self, prompt: str, *, image: Optional[str] = None, seconds: Optional[int] = None,
        aspect_ratio: Optional[str] = None, **kw: Any,
    ) -> MediaResult:
        return await self.generate("video", {"prompt": prompt, "image": image, "seconds": seconds,
                                             "aspectRatio": aspect_ratio}, **kw)

    async def speech(self, text: str, *, voice: Optional[str] = None, **kw: Any) -> MediaResult:
        return await self.generate("speech", {"text": text, "voice": voice}, **kw)

    async def music(
        self, prompt: str, *, seconds: Optional[int] = None, instrumental: Optional[bool] = None,
        lyrics: Optional[str] = None, **kw: Any,
    ) -> MediaResult:
        return await self.generate("music", {"prompt": prompt, "seconds": seconds,
                                             "instrumental": instrumental, "lyrics": lyrics}, **kw)

    async def transcribe(self, audio: str, *, language: Optional[str] = None, **kw: Any) -> MediaResult:
        return await self.generate("transcribe", {"audio": audio, "language": language}, **kw)

    async def clone_voice(self, name: str, samples: list[str], **kw: Any) -> MediaResult:
        return await self.generate("cloneVoice", {"name": name, "samples": list(samples)}, **kw)

    async def talk(
        self, image: str, *, audio: Optional[str] = None, text: Optional[str] = None,
        voice: Optional[str] = None, **kw: Any,
    ) -> MediaResult:
        return await self.generate("talk", {"image": image, "audio": audio, "text": text, "voice": voice}, **kw)

    # ------------------------------ tool face ------------------------------ #
    def tools(self) -> list[Tool]:
        """One ``Tool`` (source ``"media"``) per verb any client supports, in ``verbs.json``
        order; the schema comes from the first supporting client (§7G.6)."""
        out: list[Tool] = []
        for spec in VERBS:
            verb = spec["verb"]
            first = next((c for c in self.clients if c.supports(verb)), None)
            if first is None:
                continue
            accepted = {a["name"]: a["required"] for a in _client_args(first, verb)}
            props: dict[str, Any] = {}
            required: list[str] = []
            for a in spec["args"]:
                if a["name"] not in accepted:
                    continue
                if a["type"] == "array":
                    props[a["name"]] = {"type": "array", "items": {"type": a["items"]}, "description": a["description"]}
                else:
                    props[a["name"]] = {"type": a["type"], "description": a["description"]}
                if accepted[a["name"]]:
                    required.append(a["name"])
            out.append(Tool(
                name=spec["tool"],
                description=spec["description"],
                input_schema={"type": "object", "properties": props, "required": required},
                source="media",
                # Each call is an independent remote job: always concurrency-safe (§7G.6).
                concurrency_safe=lambda _args: True,
                execute=self._executor(verb),
            ))
        return out

    def _executor(self, verb: str):
        async def execute(args: dict[str, Any], ctx: Optional[ToolContext] = None) -> ToolResult:
            try:
                res, client = await self._route(verb, dict(args or {}), MediaContext())
                return tool_result(verb, client.name, res)
            except asyncio.CancelledError:
                raise  # §7G.8 (Python): cancellation propagates
            except Exception as e:  # noqa: BLE001 — the tool face never throws
                return ToolResult(output=str(e), is_error=True)

        return execute


def _file_bytes(f: MediaFile) -> bytes:
    path = f.path or (_file_url_path(f.url) if f.url and f.url.startswith("file://") else None)
    if path is None:
        raise MediaError(f"media: file has no data, url or path ({f.mime_type})")
    with open(path, "rb") as fh:
        return fh.read()


def tool_result(verb: str, client_name: str, res: MediaResult) -> ToolResult:
    """The §7G.6 success ``ToolResult`` for a verb's result."""
    meta: dict[str, Any] = {"source": "media", "verb": verb, "client": client_name}
    if res.job_id is not None:
        meta["jobId"] = res.job_id
    meta["status"] = res.status
    if res.usage:
        meta["usage"] = res.usage
    if verb in _TEXT_VERBS:
        return ToolResult(output=res.text or "", is_error=False, metadata=meta)
    lines: list[str] = []
    parts: list[dict[str, Any]] = []
    paths: list[Optional[str]] = []
    for f in res.files:
        paths.append(f.path)
        if f.path:
            lines.append(f.path)
        elif f.url:
            lines.append(f.url)
        else:
            lines.append(f"inline {f.mime_type} {len(base64.b64decode(f.data or ''))} bytes")
        part: dict[str, Any] = {"type": f.type, "mimeType": f.mime_type}
        if f.data is not None:
            part["data"] = f.data
        elif f.url and urlsplit(f.url).scheme in ("http", "https"):
            part["url"] = f.url
        else:
            part["data"] = base64.b64encode(_file_bytes(f)).decode("ascii")
        parts.append(part)
    if res.files:
        word = _PART_WORD.get(res.files[0].type, "video")
    else:
        word = {"image": "image", "speech": "audio", "music": "audio"}.get(verb, "video")
    out = ToolResult(output=f"{word} ready: " + "\n".join(lines), is_error=False, metadata=meta, parts=parts)
    # §7G.12 D20: each part's local path, carried port-internally (never inside a part) so
    # RunResult.files can report it.
    out.part_paths = paths  # type: ignore[attr-defined]
    return out


def model_parts(result: ToolResult) -> list[dict[str, Any]]:
    """The parts of a tool result that message assembly (§8A) emits to the model: for a
    ``source:"media"`` result only image parts (§7G.6); for any other source, all of them."""
    parts = list(result.parts or [])
    if (result.metadata or {}).get("source") == "media":
        return [p for p in parts if isinstance(p, dict) and p.get("type") == "image"]
    return parts


def create_media(clients: Union[Any, list[Any]]) -> Media:
    """Build the media router from one :class:`MediaClient` or a list (§7G.3). A verb goes
    to the first client that supports it; only :class:`MediaUnavailableError` falls through."""
    lst = list(clients) if isinstance(clients, (list, tuple)) else [clients]
    return Media(lst)


async def generate_media(
    style: str, verb: str, args: Optional[dict[str, Any]] = None,
    config: Union["MediaConfig", dict[str, Any], None] = None, **kw: Any,
) -> MediaResult:
    """One-shot sugar: ``create_media(load_media_style(style, config)).generate(verb, args)``."""
    return await create_media(load_media_style(style, config, **kw)).generate(verb, args)


# Built-in clients live in media_clients.py; re-exported here for one import path.
from .media_clients import (  # noqa: E402
    A2E_STYLE_TEXT,
    A2EMediaClient,
    ApiMediaClient,
    CliMediaClient,
    CodexMediaClient,
    ElevenLabsMediaClient,
    fold_media_prompt,
    FalMediaClient,
    GrokMediaClient,
    OpenAIMediaClient,
    OpenRouterMediaClient,
)

__all__ = [
    "VERBS", "VERBS_JSON_TEXT", "MediaError", "MediaUnavailableError", "MediaFile", "MediaResult",
    "MediaContext", "MediaClient", "BaseMediaClient", "MediaConfig", "LoopConfig", "GatherConfig",
    "MediaTransport", "UrllibMediaTransport", "media_config", "load_media_style", "MediaStyle",
    "generate_media", "fold_media_prompt", "MEDIA_MOCK_AVAILABLE_ENV", "StyleMediaClient",
    "style_media_client", "Media", "create_media", "tool_result", "model_parts",
    "A2E_STYLE_TEXT", "A2EMediaClient", "ApiMediaClient", "ElevenLabsMediaClient", "OpenAIMediaClient",
    "OpenRouterMediaClient", "FalMediaClient", "CliMediaClient", "GrokMediaClient", "CodexMediaClient",
]
