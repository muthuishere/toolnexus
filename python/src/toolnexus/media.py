"""Media generation (SPEC §7G, source ``"media"``).

One engine, two faces: typed async verbs a host calls (``image``/``video``/``speech``/
``transcribe``/``clone_voice``/``talking`` + ``generate``) and :class:`Tool` s an LLM calls
(``tools()``). Providers are **styles** — JSON data, never code; the library names no
vendor. Byte contract: ``conformance/media/`` (verbs, error strings, schemas, tool results).

Cancellation is asyncio-native: cancel the task awaiting a verb and the poll loop stops
with :class:`asyncio.CancelledError`.
"""
from __future__ import annotations

import asyncio
import copy
import inspect
import json
import math
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional, Protocol, Union
from urllib.parse import quote, urljoin, urlsplit

from .types import Tool, ToolContext, ToolResult

# --------------------------------------------------------------------------- #
# Canonical verbs — conformance/media/verbs.json, embedded verbatim (byte contract).
# --------------------------------------------------------------------------- #
VERBS: list[dict[str, Any]] = [
    {"verb": "image", "tool": "generate_image", "description": "Generate an image from a text prompt.",
     "args": [
         {"name": "prompt", "type": "string", "description": "What to draw.", "required": True},
         {"name": "width", "type": "integer", "description": "Width in pixels."},
         {"name": "height", "type": "integer", "description": "Height in pixels."},
         {"name": "aspectRatio", "type": "string", "description": "Aspect ratio, e.g. 16:9."},
         {"name": "seed", "type": "integer", "description": "Seed for reproducible output."}]},
    {"verb": "video", "tool": "generate_video",
     "description": "Generate a short video from a prompt, optionally starting from an image URL. Can take minutes.",
     "args": [
         {"name": "prompt", "type": "string", "description": "What should happen in the video.", "required": True},
         {"name": "image", "type": "string", "description": "URL of the starting image."},
         {"name": "seconds", "type": "integer", "description": "Video length in seconds."},
         {"name": "aspectRatio", "type": "string", "description": "Aspect ratio, e.g. 9:16."}]},
    {"verb": "speech", "tool": "generate_speech", "description": "Speak text aloud and return the audio.",
     "args": [
         {"name": "text", "type": "string", "description": "The text to speak.", "required": True},
         {"name": "voice", "type": "string", "description": "Voice or speaker id."}]},
    {"verb": "transcribe", "tool": "transcribe_audio", "description": "Transcribe speech in an audio URL to text.",
     "args": [
         {"name": "audio", "type": "string", "description": "URL of the audio.", "required": True}]},
    {"verb": "cloneVoice", "tool": "clone_voice",
     "description": "Create a cloned voice from audio sample URLs and return its voice id.",
     "args": [
         {"name": "name", "type": "string", "description": "Name for the new voice.", "required": True},
         {"name": "samples", "type": "array", "items": "string",
          "description": "URLs of audio samples of the voice.", "required": True}]},
    {"verb": "talking", "tool": "talking_video",
     "description": "Make the person in an image speak the given audio (lip-synced video).",
     "args": [
         {"name": "image", "type": "string", "description": "URL of the face image.", "required": True},
         {"name": "audio", "type": "string", "description": "URL of the speech audio.", "required": True}]},
]

_MIME = {
    "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "webp": "image/webp",
    "gif": "image/gif", "mp4": "video/mp4", "webm": "video/webm", "mov": "video/quicktime",
    "mp3": "audio/mpeg", "wav": "audio/wav", "ogg": "audio/ogg", "m4a": "audio/mp4",
    "flac": "audio/flac",
}
_KIND_DEFAULT_MIME = {"image": "image/png", "video": "video/mp4", "audio": "audio/mpeg"}
_KIND_PART_TYPE = {"image": "image", "audio": "audio", "video": "file"}
_KIND_WORD = {"image": "image", "audio": "audio", "video": "video"}


class MediaError(Exception):
    """An engine error; ``str(e)`` is the exact §7G message."""


@dataclass
class MediaFile:
    type: str
    mime_type: str
    url: str

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.type, "mimeType": self.mime_type, "url": self.url}


@dataclass
class MediaResult:
    files: list[MediaFile] = field(default_factory=list)
    text: Optional[str] = None
    job_id: Optional[str] = None
    status: str = ""

    def to_dict(self) -> dict[str, Any]:
        """The canonical §7G shape (``jobId``/``text`` present only when set)."""
        out: dict[str, Any] = {"files": [f.to_dict() for f in self.files]}
        if self.text is not None:
            out["text"] = self.text
        if self.job_id is not None:
            out["jobId"] = self.job_id
        out["status"] = self.status
        return out


class MediaTransport(Protocol):
    """HTTP seam for the media engine. ``request`` is blocking (run in a worker thread)
    and returns ``(status, body_text)`` or ``(status, body_text, headers)`` for every
    status — the engine owns error mapping and redirects. A transport must NOT follow
    redirects itself; it reports the 3xx (with its ``Location`` header in ``headers``) and
    the engine follows it under the §7G Redirects rules. A 2-tuple carries no headers, so
    a 3xx from such a transport is a non-2xx error."""

    def request(
        self, method: str, url: str, headers: dict[str, str], body: Optional[bytes], timeout: float
    ) -> Union[tuple[int, str], tuple[int, str, dict[str, str]]]: ...


def _origin(url: str) -> tuple[str, str, Optional[int]]:
    u = urlsplit(url)
    scheme = u.scheme.lower()
    return scheme, (u.hostname or "").lower(), u.port or {"http": 80, "https": 443}.get(scheme)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Hand every 3xx back to the engine, which follows redirects itself (§7G Redirects)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[override]
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


class UrllibMediaTransport:
    """Default media transport — stdlib ``urllib`` with automatic redirects disabled."""

    def request(
        self, method: str, url: str, headers: dict[str, str], body: Optional[bytes], timeout: float
    ) -> tuple[int, str, dict[str, str]]:
        req = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with _OPENER.open(req, timeout=timeout) as resp:
                return resp.status, resp.read().decode("utf-8", errors="replace"), dict(resp.headers.items())
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", errors="replace"), dict(e.headers.items()) if e.headers else {}


_REDIRECTS = (301, 302, 303, 307, 308)
_MAX_REDIRECTS = 10


def _location(headers: dict[str, str]) -> Optional[str]:
    for k, v in headers.items():
        if k.lower() == "location" and v:
            return v
    return None


OnProgress = Callable[[dict[str, Any]], Union[None, Awaitable[None]]]

# --------------------------------------------------------------------------- #
# dotPath
# --------------------------------------------------------------------------- #
_MISSING = object()


def _get(obj: Any, path: str) -> Any:
    if path == "":
        return obj  # §7G: an empty dotPath is the whole value
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
    """An object takes any segment; an array only a numeric one (else it is replaced, §7G)."""
    return isinstance(child, dict) or (isinstance(child, list) and nxt_is_index)


def _positive(v: Any, default: float) -> float:
    """§7G: an interval/timeout that is absent, not a number or <= 0 takes the default."""
    if isinstance(v, (int, float)) and not isinstance(v, bool) and v > 0:
        return v
    return default


def _status_text(v: Any) -> str:
    """Poll status (§7G): string as-is, a number rendered like the envelope code, else ""."""
    if isinstance(v, str):
        return v
    if isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v):
        return _num_text(v)
    return ""


def _cap_body(text: str) -> str:
    """Echoed error bodies keep their first 1000 code points, then "…" (§7G)."""
    return text if len(text) <= 1000 else text[:1000] + "\u2026"


def _validate_style(style: dict[str, Any]) -> None:
    """§7G Empty dotPath: an args entry mapped to "" is a style error (sorted ⇒ deterministic)."""
    verbs = style.get("verbs") or {}
    for verb in sorted(verbs):
        mapping = (verbs[verb] or {}).get("args") or {}
        for k in sorted(mapping):
            if mapping[k] == "":
                raise MediaError(f'style: empty path for argument "{k}"')


class _Deadline(Exception):
    """A request cut short by the job deadline; callers raise the §7G timed-out message."""


def _num_text(v: Any) -> str:
    """Integral numbers render as integer text; strings as-is."""
    if isinstance(v, bool):
        return json.dumps(v)
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        if math.isfinite(v) and v == int(v):
            return str(int(v))
        return repr(v)
    return str(v)


def _mime_for(url: str, kind: str) -> str:
    path = urlsplit(url).path
    base = path.rsplit("/", 1)[-1]
    if "." in base:
        ext = base.rsplit(".", 1)[-1].lower()
        if ext in _MIME:
            return _MIME[ext]
    return _KIND_DEFAULT_MIME.get(kind, "application/octet-stream")


_ENV_RE = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}")


def _expand(value: str) -> str:
    return _ENV_RE.sub(lambda m: os.environ.get(m.group(1), ""), value)


# --------------------------------------------------------------------------- #
# Style loading
# --------------------------------------------------------------------------- #
def load_media_style(path: str) -> dict[str, Any]:
    """Read one style JSON file (SPEC §7G)."""
    with open(path, encoding="utf-8") as f:
        style = json.load(f)
    _validate_style(style)
    return style


# --------------------------------------------------------------------------- #
# Engine
# --------------------------------------------------------------------------- #
# Python keyword → canonical arg name (the byte contract is the canonical name).
_KW = {"aspect_ratio": "aspectRatio"}


class Media:
    """The §7G media engine. Build with :func:`create_media`."""

    def __init__(
        self,
        styles: list[dict[str, Any]],
        on_progress: Optional[OnProgress] = None,
        http_transport: Optional[MediaTransport] = None,
    ) -> None:
        for s in styles:
            _validate_style(s)
        self._styles = styles
        self._on_progress = on_progress
        self._transport: MediaTransport = http_transport if http_transport is not None else UrllibMediaTransport()

    def _resolve(self, verb: str) -> Optional[tuple[dict[str, Any], dict[str, Any]]]:
        for s in self._styles:
            v = (s.get("verbs") or {}).get(verb)
            if v is not None:
                return s, v
        return None

    async def _do(
        self, style: dict[str, Any], verb: str, method: str, path: str, body: Any, deadline: float
    ) -> Any:
        url = str(style.get("baseUrl", "")).rstrip("/") + path
        style_headers = {k: _expand(str(v)) for k, v in (style.get("headers") or {}).items()}
        base_origin = _origin(url)
        data: Optional[bytes] = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
        redirects = 0
        while True:
            # §7G Redirects: style headers only ever go to baseUrl's origin.
            headers = dict(style_headers) if _origin(url) == base_origin else {}
            if data is not None:
                headers["Content-Type"] = "application/json"
            # §7G Per-request timeout: every hop bounded by the time left until the job deadline.
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise _Deadline()
            try:
                out = await asyncio.wait_for(
                    asyncio.to_thread(self._transport.request, method, url, headers, data, remaining + 1.0),
                    remaining,
                )
            except asyncio.CancelledError:
                raise
            except Exception:
                if time.monotonic() >= deadline:
                    raise _Deadline() from None
                raise
            status, text = out[0], out[1]
            loc = _location(out[2] or {}) if len(out) > 2 else None
            if status not in _REDIRECTS or loc is None:
                break
            if redirects >= _MAX_REDIRECTS:
                raise MediaError(f"{verb}: too many redirects")
            redirects += 1
            url = urljoin(url, loc)
            if status in (301, 302, 303) and method.upper() not in ("GET", "HEAD"):
                method, data = "GET", None
        if status < 200 or status >= 300:
            raise MediaError(f"HTTP {status}: {_cap_body(text)}")
        try:
            parsed = json.loads(text)
        except (ValueError, TypeError):
            raise MediaError(f"{verb}: response is not JSON") from None
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
                raise MediaError(f"API error {code_s}: {msg_s}")
        return parsed

    async def _progress(self, verb: str, job_id: str, status: str) -> None:
        if self._on_progress is None:
            return
        r = self._on_progress({"verb": verb, "jobId": job_id, "status": status})
        if inspect.isawaitable(r):
            await r

    async def generate(
        self, verb: str, args: Optional[dict[str, Any]] = None, provider_options: Optional[dict[str, Any]] = None
    ) -> MediaResult:
        """Run ``verb`` with canonical ``args`` (SPEC §7G engine order). Raises :class:`MediaError`."""
        args = {k: val for k, val in (args or {}).items() if val is not None}
        found = self._resolve(verb)
        if found is None:
            raise MediaError(f'media: no style supports "{verb}"')
        style, v = found
        mapping = v.get("args") or {}
        for k in sorted(args):
            if k not in mapping:
                raise MediaError(f'{verb}: argument "{k}" not supported by this style')
        body = copy.deepcopy(v.get("defaults") or {})
        for k, val in args.items():
            _set(body, mapping[k], val)
        for k, val in (provider_options or {}).items():
            body[k] = val
        start = v.get("start") or {}
        timeout_ms = _positive((v.get("poll") or {}).get("timeoutMs"), 600000)
        deadline = time.monotonic() + timeout_ms / 1000.0
        try:
            resp = await self._do(style, verb, start.get("method") or "POST", start.get("path", ""), body, deadline)
        except _Deadline:
            raise MediaError(f"{verb}: timed out after {_num_text(timeout_ms)}ms") from None

        job_id: Optional[str] = None
        status = "completed"
        if v.get("mode") == "async":
            id_path = v.get("idPath", "")
            raw = _get(resp, id_path)
            if not ((isinstance(raw, str) and raw != "") or (
                isinstance(raw, (int, float)) and not isinstance(raw, bool)
                and math.isfinite(raw) and raw == int(raw)
            )):
                raise MediaError(f"{verb}: no job id at {id_path}")
            job_id = _num_text(raw)
            resp, status = await self._poll(style, verb, v, job_id, deadline, timeout_ms)

        kind = v.get("kind")
        rp = v.get("resultPath", "")
        val = _get(resp, rp)
        if kind == "text":
            if isinstance(val, str):
                return MediaResult(files=[], text=val, job_id=job_id, status=status)
            if isinstance(val, (int, float)) and not isinstance(val, bool):
                return MediaResult(files=[], text=_num_text(val), job_id=job_id, status=status)
            raise MediaError(f"{verb}: no result at {rp}")
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
            raise MediaError(f"{verb}: no result at {rp}")
        files = [MediaFile(_KIND_PART_TYPE.get(kind, "file"), _mime_for(u, kind), u) for u in urls]
        return MediaResult(files=files, job_id=job_id, status=status)

    async def _poll(
        self, style: dict[str, Any], verb: str, v: dict[str, Any], job_id: str, deadline: float, timeout_ms: float
    ) -> tuple[Any, str]:
        p = v.get("poll") or {}
        interval = _positive(p.get("intervalMs"), 5000) / 1000.0
        path = p.get("path", "").replace("{id}", quote(job_id, safe=""))
        done = p.get("done") or []
        failed = p.get("failed") or []
        timed_out = MediaError(f"job {job_id} timed out after {_num_text(timeout_ms)}ms")
        while True:
            await asyncio.sleep(interval)
            try:
                resp = await self._do(style, verb, p.get("method") or "GET", path, None, deadline)
            except _Deadline:
                raise timed_out from None
            st_s = _status_text(_get(resp, p.get("statusPath", "")))
            await self._progress(verb, job_id, st_s)
            if st_s in done:
                return resp, st_s
            if st_s in failed:
                msg = _get(resp, p["errorPath"]) if p.get("errorPath") else _MISSING
                if isinstance(msg, str) and msg:
                    raise MediaError(f"job {job_id} {st_s}: {msg}")
                raise MediaError(f"job {job_id} {st_s}")
            if time.monotonic() >= deadline:
                raise timed_out

    # ------------------------------ typed verbs ------------------------------ #
    @staticmethod
    def _args(**kw: Any) -> dict[str, Any]:
        return {_KW.get(k, k): val for k, val in kw.items() if val is not None}

    async def image(
        self, prompt: str, *, width: Optional[int] = None, height: Optional[int] = None,
        aspect_ratio: Optional[str] = None, seed: Optional[int] = None,
        provider_options: Optional[dict[str, Any]] = None,
    ) -> MediaResult:
        return await self.generate("image", self._args(
            prompt=prompt, width=width, height=height, aspect_ratio=aspect_ratio, seed=seed), provider_options)

    async def video(
        self, prompt: str, *, image: Optional[str] = None, seconds: Optional[int] = None,
        aspect_ratio: Optional[str] = None, provider_options: Optional[dict[str, Any]] = None,
    ) -> MediaResult:
        return await self.generate("video", self._args(
            prompt=prompt, image=image, seconds=seconds, aspect_ratio=aspect_ratio), provider_options)

    async def speech(
        self, text: str, *, voice: Optional[str] = None, provider_options: Optional[dict[str, Any]] = None,
    ) -> MediaResult:
        return await self.generate("speech", self._args(text=text, voice=voice), provider_options)

    async def transcribe(self, audio: str, *, provider_options: Optional[dict[str, Any]] = None) -> MediaResult:
        return await self.generate("transcribe", self._args(audio=audio), provider_options)

    async def clone_voice(
        self, name: str, samples: list[str], *, provider_options: Optional[dict[str, Any]] = None,
    ) -> MediaResult:
        return await self.generate("cloneVoice", self._args(name=name, samples=list(samples)), provider_options)

    async def talking(
        self, image: str, audio: str, *, provider_options: Optional[dict[str, Any]] = None,
    ) -> MediaResult:
        return await self.generate("talking", self._args(image=image, audio=audio), provider_options)

    # ------------------------------ tool face ------------------------------ #
    def tools(self) -> list[Tool]:
        """One ``Tool`` (source ``"media"``) per supported verb, in canonical order."""
        out: list[Tool] = []
        for spec in VERBS:
            found = self._resolve(spec["verb"])
            if found is None:
                continue
            mapping = found[1].get("args") or {}
            props: dict[str, Any] = {}
            required: list[str] = []
            for a in spec["args"]:
                if a["name"] not in mapping:
                    continue
                if a["type"] == "array":
                    props[a["name"]] = {"type": "array", "items": {"type": a["items"]}, "description": a["description"]}
                else:
                    props[a["name"]] = {"type": a["type"], "description": a["description"]}
                if a.get("required"):
                    required.append(a["name"])
            out.append(Tool(
                name=spec["tool"],
                description=spec["description"],
                input_schema={"type": "object", "properties": props, "required": required},
                source="media",
                # Each call is an independent remote job: always concurrency-safe
                # (§7G, §8 Tool scheduling).
                concurrency_safe=lambda _args: True,
                execute=self._executor(spec["verb"], found[1].get("kind", "")),
            ))
        return out

    def _executor(self, verb: str, kind: str):
        async def execute(args: dict[str, Any], ctx: Optional[ToolContext] = None) -> ToolResult:
            try:
                res = await self.generate(verb, dict(args or {}))
            except asyncio.CancelledError:
                raise  # §7G edge rule (Python): cancellation propagates
            except Exception as e:  # noqa: BLE001 — the tool face never throws
                return ToolResult(output=str(e), is_error=True)
            meta: dict[str, Any] = {"source": "media", "verb": verb}
            if res.job_id is not None:
                meta["jobId"] = res.job_id
            meta["status"] = res.status
            if kind == "text":
                return ToolResult(output=res.text or "", is_error=False, metadata=meta)
            word = _KIND_WORD.get(kind, kind)
            return ToolResult(
                output=f"{word} ready: " + "\n".join(f.url for f in res.files),
                is_error=False,
                metadata=meta,
                parts=[f.to_dict() for f in res.files],
            )

        return execute


def _json_eq(a: Any, b: Any) -> bool:
    # JSON equality: bool is not a number; 0 == 0.0.
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool) and a == b
    return a == b


def create_media(
    styles: Union[dict[str, Any], list[dict[str, Any]]],
    *,
    on_progress: Optional[OnProgress] = None,
    http_transport: Optional[MediaTransport] = None,
) -> Media:
    """Build the media engine from one style or a list (a verb resolves to the FIRST
    style declaring it). ``on_progress({"verb","jobId","status"})`` fires per poll;
    ``http_transport`` replaces the default urllib transport."""
    lst = [styles] if isinstance(styles, dict) else list(styles)
    return Media(lst, on_progress=on_progress, http_transport=http_transport)
