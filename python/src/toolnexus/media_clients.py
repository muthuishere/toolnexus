"""Built-in media clients (SPEC §7G.9, §7G.10): the API clients (A2E, ElevenLabs, OpenAI,
OpenRouter, fal) on one HTTP base, and the CLI clients (Grok, Codex) on one process base.

Import them from :mod:`toolnexus` or :mod:`toolnexus.media`. Every client takes the shared
config (§7G.4) as a :class:`~toolnexus.media.MediaConfig`, a dict, or keyword arguments::

    OpenAIMediaClient(model="gpt-image-1", gather={"mode": "inline"})
    GrokMediaClient(command="/opt/bin/grok", loop={"timeout_ms": 120_000})
"""
from __future__ import annotations

import asyncio
import copy
import json
import os
import re
import shutil
import signal
import tempfile
import time
from typing import Any, Optional, Union
from urllib.parse import urljoin

from .media import (
    BaseMediaClient,
    MediaConfig,
    MediaContext,
    MediaError,
    MediaFile,
    MediaResult,
    MediaUnavailableError,
    StyleMediaClient,
    _VERB_SPEC,
    _Job,
    _ascii_lower,
    _cap,
    _data_text,
    _ext_mime,
    _num_text,
    _pct,
    _positive,
    _status_text,
    media_config,
)

_VERB_KIND = {
    "image": "image", "video": "video", "talk": "video", "speech": "audio", "music": "audio",
    "transcribe": "text", "cloneVoice": "text",
}

# --------------------------------------------------------------------------- #
# A2E — a style client over the embedded preset (examples/media-styles/a2e.json, verbatim)
# --------------------------------------------------------------------------- #
A2E_STYLE_TEXT: str = _data_text("a2e.json")


class A2EMediaClient(StyleMediaClient):
    """a2e.ai (image, video, speech, talk) — the embedded style preset with built-in defaults:
    key from ``A2E_API_KEY`` (no key ⇒ unavailable, no request), gather ``download``. ``url``
    replaces ``baseUrl``; ``model`` becomes the image body's ``model_type``."""

    _key_env = "A2E_API_KEY"
    _default_mode = "download"

    def __init__(self, config: Union[MediaConfig, dict[str, Any], None] = None, **kw: Any) -> None:
        super().__init__(json.loads(A2E_STYLE_TEXT), config, **kw)

    def _style_for_call(self) -> dict[str, Any]:
        if not self.config.model:
            return self.style
        style = copy.deepcopy(self.style)
        image = style["verbs"]["image"]
        image.setdefault("defaults", {})["model_type"] = self.config.model
        return style


# --------------------------------------------------------------------------- #
# API base
# --------------------------------------------------------------------------- #
def _job_id(v: Any) -> Optional[str]:
    if isinstance(v, str) and v:
        return v
    if isinstance(v, (int, float)) and not isinstance(v, bool) and v == int(v):
        return _num_text(v)
    return None


class ApiMediaClient(BaseMediaClient):
    """Base for the built-in HTTP clients: key check, base URL, shared config, §7G.8 rules.
    Subclasses set the class attributes and implement ``_<verb>(job, args)``."""

    name = "api"
    key_env = ""
    default_url = ""
    primary_verb = ""
    default_models: dict[str, str] = {}
    default_interval_ms: float = 5000
    ARGS: dict[str, list[tuple[str, bool]]] = {}

    def __init__(self, config: Union[MediaConfig, dict[str, Any], None] = None, **kw: Any) -> None:
        self.config = media_config(config, **kw)

    def supports(self, verb: str) -> bool:
        return verb in self.ARGS

    def args(self, verb: str) -> list[dict[str, Any]]:
        return [{"name": n, "required": r} for n, r in self.ARGS.get(verb, [])]

    def model(self, verb: str) -> Optional[str]:
        if self.config.models.get(verb):
            return self.config.models[verb]
        if verb == self.primary_verb and self.config.model:
            return self.config.model
        return self.default_models.get(verb)

    def auth_headers(self, key: str) -> dict[str, str]:
        return {"Authorization": f"Bearer {key}"}

    def interval_ms(self) -> float:
        return _positive(self.config.loop.interval_ms, self.default_interval_ms)

    async def generate(self, verb: str, args: dict[str, Any], ctx: Optional[MediaContext] = None) -> MediaResult:
        if not self.supports(verb):
            raise MediaError(f'media: no client supports "{verb}"')
        args = {k: v for k, v in (args or {}).items() if v is not None}
        key = self.config.key or os.environ.get(self.key_env) or ""
        if not key:
            raise MediaUnavailableError(f"{self.name}: no API key (set {self.key_env})")
        base = (self.config.url or self.default_url).rstrip("/")
        job = _Job(self.name, verb, _VERB_KIND[verb], self.config, ctx,
                   base_url=base, headers=self.auth_headers(key))
        handler = getattr(self, "_" + ("clone_voice" if verb == "cloneVoice" else verb))
        return await handler(job, args)

    @staticmethod
    def _text_result(verb: str, body: Any, key: str) -> MediaResult:
        text = body.get(key) if isinstance(body, dict) else None
        if not isinstance(text, str):
            raise MediaError(f"{verb}: no result at {key}")
        return MediaResult(files=[], text=text, status="completed")


# --------------------------------------------------------------------------- #
# ElevenLabs
# --------------------------------------------------------------------------- #
class ElevenLabsMediaClient(ApiMediaClient):
    """ElevenLabs: speech (voice by name or id, incl. cloned voices), music, transcribe,
    cloneVoice. Key ``ELEVENLABS_API_KEY``."""

    name = "elevenlabs"
    key_env = "ELEVENLABS_API_KEY"
    default_url = "https://api.elevenlabs.io"
    primary_verb = "speech"
    default_models = {"speech": "eleven_flash_v2_5", "transcribe": "scribe_v1"}
    DEFAULT_VOICE = "JBFqnCBsd6RMkjVDRZzb"
    ARGS = {
        "speech": [("text", True), ("voice", False)],
        "music": [("prompt", True), ("seconds", False), ("instrumental", False)],
        "transcribe": [("audio", True), ("language", False)],
        "cloneVoice": [("name", True), ("samples", True)],
    }

    def auth_headers(self, key: str) -> dict[str, str]:
        return {"xi-api-key": key}

    async def _voice_id(self, job: _Job, voice: Optional[str]) -> str:
        if voice is None:
            return self.DEFAULT_VOICE
        body = await job.json("GET", job.url(f"/v2/voices?page_size=100&search={_pct(voice)}"))
        for v in (body.get("voices") if isinstance(body, dict) else None) or []:
            if not isinstance(v, dict):
                continue
            name, vid = v.get("name"), v.get("voice_id")
            if (isinstance(name, str) and _ascii_lower(name) == _ascii_lower(voice)) or vid == voice:
                if isinstance(vid, str) and vid:
                    return vid
        if len(voice) == 20 and " " not in voice:
            return voice
        raise MediaError(f'speech: voice "{voice}" not found')

    async def _speech(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        vid = await self._voice_id(job, args.get("voice"))
        data, hdrs = await job.raw(
            "POST", job.url(f"/v1/text-to-speech/{_pct(vid)}?output_format=mp3_22050_32"),
            json_body=job.body({"text": args["text"], "model_id": self.model("speech")}))
        rid = next((v for k, v in hdrs.items() if k.lower() == "request-id" and v), None)
        return MediaResult(files=[job.gather_bytes(data, "audio/mpeg")], job_id=rid, status="completed")

    async def _music(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        secs = args.get("seconds")
        body: dict[str, Any] = {"prompt": args["prompt"], "music_length_ms": secs * 1000 if secs is not None else 10000}
        if args.get("instrumental") is True:
            body["force_instrumental"] = True
        data, _ = await job.raw("POST", job.url("/v1/music?output_format=mp3_44100_128"), json_body=job.body(body))
        return MediaResult(files=[job.gather_bytes(data, "audio/mpeg")], status="completed")

    async def _transcribe(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        data, fname, ctype = await job.read_input(args["audio"])
        fields: list[tuple[Any, ...]] = [("model_id", self.model("transcribe"))]
        if args.get("language") is not None:
            fields.append(("language_code", args["language"]))
        fields.append(("file", fname, ctype, data))
        body = await job.json("POST", job.url("/v1/speech-to-text"), multipart=job.fields(fields))
        return self._text_result("transcribe", body, "text")

    async def _clone_voice(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        fields: list[tuple[Any, ...]] = [("name", args["name"])]
        for s in args["samples"]:
            data, fname, ctype = await job.read_input(s)
            fields.append(("files", fname, ctype, data))
        body = await job.json("POST", job.url("/v1/voices/add"), multipart=job.fields(fields))
        return self._text_result("cloneVoice", body, "voice_id")


# --------------------------------------------------------------------------- #
# OpenAI (and any OpenAI-compatible server via ``url``)
# --------------------------------------------------------------------------- #
_OPENAI_FORMAT = {"png": "image/png", "jpeg": "image/jpeg", "webp": "image/webp"}


class OpenAIMediaClient(ApiMediaClient):
    """OpenAI: image, video (Sora), speech, transcribe. Key ``OPENAI_API_KEY``."""

    name = "openai"
    key_env = "OPENAI_API_KEY"
    default_url = "https://api.openai.com/v1"
    primary_verb = "image"
    default_models = {"image": "gpt-image-1-mini", "speech": "gpt-4o-mini-tts",
                      "transcribe": "gpt-4o-mini-transcribe", "video": "sora-2"}
    default_interval_ms = 5000
    ARGS = {
        "image": [("prompt", True), ("width", False), ("height", False)],
        "video": [("prompt", True), ("seconds", False)],
        "speech": [("text", True), ("voice", False)],
        "transcribe": [("audio", True), ("language", False)],
    }

    async def _image(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        model = self.model("image") or ""
        w, h = args.get("width"), args.get("height")
        size = f"{w if w is not None else h}x{h if h is not None else w}" if (w is not None or h is not None) else "1024x1024"
        body: dict[str, Any] = {"model": model, "prompt": args["prompt"], "n": 1, "size": size}
        if model.startswith("gpt-image"):
            body["quality"] = "low"
        r = await job.json("POST", job.url("/images/generations"), json_body=job.body(body))
        r = r if isinstance(r, dict) else {}
        fmt = r.get("output_format")
        mime = _OPENAI_FORMAT.get(fmt, "image/png") if isinstance(fmt, str) else "image/png"
        files: list[MediaFile] = []
        for e in r.get("data") or []:
            if not isinstance(e, dict):
                continue
            if isinstance(e.get("b64_json"), str) and e["b64_json"]:
                files.append(job.gather_base64(e["b64_json"], mime))
            elif isinstance(e.get("url"), str) and e["url"]:
                files.append(await job.gather_url(e["url"]))
        if not files:
            raise MediaError("image: no result at data")
        return MediaResult(files=files, status="completed",
                           usage=_usage(r.get("usage"), {"input_tokens": "inputTokens", "output_tokens": "outputTokens"}))

    async def _speech(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        body = {"model": self.model("speech"), "input": args["text"], "voice": args.get("voice") or "alloy"}
        data, _ = await job.raw("POST", job.url("/audio/speech"), json_body=job.body(body))
        return MediaResult(files=[job.gather_bytes(data, "audio/mpeg")], status="completed")

    async def _transcribe(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        data, fname, ctype = await job.read_input(args["audio"])
        fields: list[tuple[Any, ...]] = [("model", self.model("transcribe"))]
        if args.get("language") is not None:
            fields.append(("language", args["language"]))
        fields.append(("file", fname, ctype, data))
        body = await job.json("POST", job.url("/audio/transcriptions"), multipart=job.fields(fields))
        return self._text_result("transcribe", body, "text")

    async def _video(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        secs = args.get("seconds")
        body = {"model": self.model("video"), "prompt": args["prompt"], "seconds": str(secs if secs is not None else 4)}
        r = await job.json("POST", job.url("/videos"), json_body=job.body(body))
        vid = _job_id(r.get("id") if isinstance(r, dict) else None)
        if vid is None:
            raise MediaError("video: no job id at id")
        job.job_id = vid
        interval = self.interval_ms()
        while True:
            await job.sleep(interval)
            p = await job.json("GET", job.url(f"/videos/{_pct(vid)}"))
            p = p if isinstance(p, dict) else {}
            st = _status_text(p.get("status"))
            await job.progress(st)
            if st == "completed":
                break
            if st == "failed":
                err = p.get("error")
                msg = err.get("message") if isinstance(err, dict) else None
                if isinstance(msg, str) and msg:
                    raise MediaError(f"job {vid} failed: {msg}", job_id=vid)
                raise MediaError(f"job {vid} failed", job_id=vid)
            if job.expired():
                raise job.timeout_error()
        data, _ = await job.raw("GET", job.url(f"/videos/{_pct(vid)}/content"))
        return MediaResult(files=[job.gather_bytes(data, "video/mp4")], job_id=vid, status="completed")


def _usage(raw: Any, mapping: dict[str, str]) -> Optional[dict[str, Any]]:
    """§7G.12 D21: provider-reported usage only; omitted fields stay absent, none ⇒ None."""
    if not isinstance(raw, dict):
        return None
    out: dict[str, Any] = {}
    for src, dst in mapping.items():
        v = raw.get(src)
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            continue
        out[dst] = v if dst == "costUsd" else int(v)
    return out or None


# --------------------------------------------------------------------------- #
# OpenRouter
# --------------------------------------------------------------------------- #
class OpenRouterMediaClient(ApiMediaClient):
    """OpenRouter: image through chat completions with ``modalities``. Key
    ``OPENROUTER_API_KEY``."""

    name = "openrouter"
    key_env = "OPENROUTER_API_KEY"
    default_url = "https://openrouter.ai/api/v1"
    primary_verb = "image"
    default_models = {"image": "google/gemini-2.5-flash-image"}
    ARGS = {"image": [("prompt", True)]}

    async def _image(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        body = {"model": self.model("image"), "modalities": ["image", "text"],
                "messages": [{"role": "user", "content": args["prompt"]}]}
        r = await job.json("POST", job.url("/chat/completions"), json_body=job.body(body))
        r = r if isinstance(r, dict) else {}
        job.job_id = r.get("id") if isinstance(r.get("id"), str) and r.get("id") else None
        text = ""
        files: list[MediaFile] = []
        for ch in r.get("choices") or []:
            msg = ch.get("message") if isinstance(ch, dict) else None
            if not isinstance(msg, dict):
                continue
            if isinstance(msg.get("content"), str):
                text += msg["content"]
            for img in msg.get("images") or []:
                iu = img.get("image_url") if isinstance(img, dict) else None
                u = iu.get("url") if isinstance(iu, dict) else None
                if isinstance(u, str) and u:
                    files.append(await job.gather_url(u))
        if not files:
            raise MediaError("image: no image in response" + (f": {_cap(text, 200)}" if text else ""), job_id=job.job_id)
        usage = _usage(r.get("usage"), {"prompt_tokens": "inputTokens", "completion_tokens": "outputTokens",
                                        "cost": "costUsd"})
        return MediaResult(files=files, text=text or None, job_id=job.job_id, status="completed", usage=usage)


# --------------------------------------------------------------------------- #
# fal (queue API, any hosted model id)
# --------------------------------------------------------------------------- #
class FalMediaClient(ApiMediaClient):
    """fal.ai queue API: image and video for any hosted model id. Key ``FAL_API_KEY``."""

    name = "fal"
    key_env = "FAL_API_KEY"
    default_url = "https://queue.fal.run"
    primary_verb = "image"
    default_models = {"image": "fal-ai/flux/schnell"}
    default_interval_ms = 500
    I2V = "fal-ai/kling-video/v2.1/standard/image-to-video"
    T2V = "fal-ai/kling-video/v2.1/standard/text-to-video"
    ARGS = {
        "image": [("prompt", True), ("width", False), ("height", False), ("seed", False)],
        "video": [("prompt", True), ("image", False), ("seconds", False), ("aspectRatio", False)],
    }

    def auth_headers(self, key: str) -> dict[str, str]:
        return {"Authorization": f"Key {key}"}

    async def _image(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        model = self.model("image") or ""
        body: dict[str, Any] = {"prompt": args["prompt"], "num_images": 1}
        if "schnell" in model:
            body["image_size"] = "square"
            body["num_inference_steps"] = 4
        w, h = args.get("width"), args.get("height")
        if w is not None or h is not None:
            body["image_size"] = {"width": w if w is not None else h, "height": h if h is not None else w}
        if args.get("seed") is not None:
            body["seed"] = args["seed"]
        return await self._queue(job, model, body)

    async def _video(self, job: _Job, args: dict[str, Any]) -> MediaResult:
        model = self.config.models.get("video") or (self.I2V if args.get("image") is not None else self.T2V)
        secs = args.get("seconds")
        body: dict[str, Any] = {"prompt": args["prompt"], "duration": str(secs if secs is not None else 5)}
        if args.get("image") is not None:
            body["image_url"] = args["image"]
        if args.get("aspectRatio") is not None:
            body["aspect_ratio"] = args["aspectRatio"]
        return await self._queue(job, model, body)

    async def _queue(self, job: _Job, model: str, body: dict[str, Any]) -> MediaResult:
        verb = job.verb
        r = await job.json("POST", job.url("/" + model), json_body=job.body(body))
        r = r if isinstance(r, dict) else {}
        rid = _job_id(r.get("request_id"))
        if rid is None:
            raise MediaError(f"{verb}: no job id at request_id")
        job.job_id = rid
        base = job.base_url.rstrip("/") + "/"
        su = r.get("status_url") if isinstance(r.get("status_url"), str) and r.get("status_url") else None
        ru = r.get("response_url") if isinstance(r.get("response_url"), str) and r.get("response_url") else None
        status_url = urljoin(base, su) if su else job.url(f"/{model}/requests/{_pct(rid)}/status")
        response_url = urljoin(base, ru) if ru else job.url(f"/{model}/requests/{_pct(rid)}")
        interval = self.interval_ms()
        while True:
            await job.sleep(interval)
            s = await job.json("GET", status_url)
            s = s if isinstance(s, dict) else {}
            st = _status_text(s.get("status"))
            await job.progress(st)
            if st == "COMPLETED":
                break
            err = s.get("error")
            if err not in (None, "", [], {}):
                msg = err if isinstance(err, str) else json.dumps(err, separators=(",", ":"), ensure_ascii=False)
                raise MediaError(f"job {rid} failed: {msg}", job_id=rid)
            if job.expired():
                raise job.timeout_error()
        res = await job.json("GET", response_url)
        res = res if isinstance(res, dict) else {}
        urls: list[str] = []

        def take(obj: Any) -> None:
            if isinstance(obj, dict) and isinstance(obj.get("url"), str) and obj["url"]:
                urls.append(obj["url"])

        for img in res.get("images") if isinstance(res.get("images"), list) else []:
            take(img)
        for k in ("image", "video", "audio", "audio_file"):
            take(res.get(k))
        if not urls:
            raise MediaError(f"{verb}: no result in response", job_id=rid)
        files = [await job.gather_url(u) for u in urls]
        return MediaResult(files=files, job_id=rid, status="completed")


# --------------------------------------------------------------------------- #
# CLI base (§7G.10)
# --------------------------------------------------------------------------- #
_PATH_RE = re.compile(r"/[^\s\"'()\[\]]+\.(?:png|jpe?g|webp|gif|mp4|mov|webm|mp3|wav)", re.IGNORECASE)
_LINE_LIMIT = 64 * 1024 * 1024


def _kill(proc: asyncio.subprocess.Process) -> None:
    try:
        os.killpg(proc.pid, signal.SIGKILL)  # the whole process group (start_new_session)
    except Exception:  # noqa: BLE001 — no process groups (Windows) or already gone
        try:
            proc.kill()
        except Exception:  # noqa: BLE001
            pass


_CLI_HINTS = {
    "width": ("width ", " px"), "height": ("height ", " px"),
    "seconds": ("about ", " seconds long"), "aspectRatio": ("aspect ratio ", ""),
}


def _arg_text(v: Any) -> str:
    if isinstance(v, str):
        return v
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return _num_text(v)
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def fold_media_prompt(verb: str, args: dict[str, Any]) -> str:
    """The prompt a CLI style receives: ``prompt``, then the present size hints in canonical
    verb order as `` (h1, h2)`` (§7G CLI hint folding)."""
    args = args or {}
    prompt = _arg_text(args.get("prompt", ""))
    hs = []
    for a in _VERB_SPEC.get(verb, {"args": []})["args"]:
        n = a["name"]
        if n in _CLI_HINTS and args.get(n) is not None:
            pre, post = _CLI_HINTS[n]
            hs.append(pre + _arg_text(args[n]) + post)
    return prompt + (" (" + ", ".join(hs) + ")" if hs else "")


class CliMediaClient(BaseMediaClient):
    """Runs a local subscription CLI **once, headless**, reads its JSON-lines stdout and
    collects the files it made (§7G.10). Subclasses set ``name``, ``default_command``,
    ``templates`` (verb ⇒ prompt template with ``{prompt}``), ``fixed_env`` and implement
    ``argv``, ``parse`` and ``unavailable_message``; ``folder`` is optional."""

    name = "cli"
    default_command = "cli"
    templates: dict[str, str] = {}
    fixed_env: dict[str, str] = {}

    def __init__(self, config: Union[MediaConfig, dict[str, Any], None] = None, **kw: Any) -> None:
        self.config = media_config(config, **kw)

    def supports(self, verb: str) -> bool:
        return verb in self.templates

    #: When true the CLI is never started: a fixed file is written and returned (§7G CLI mock presets).
    mock: bool = False

    def args(self, verb: str) -> list[dict[str, Any]]:
        hints = ("seconds", "aspectRatio") if verb == "video" else ("width", "height", "aspectRatio")
        return [{"name": "prompt", "required": True}] + [{"name": h, "required": False} for h in hints]

    def command(self) -> list[str]:
        c = self.config.command
        if c is None or c == [] or c == "":
            return [self.default_command]
        return [c] if isinstance(c, str) else [str(x) for x in c]

    def argv(self, prompt: str, cwd: str) -> list[str]:  # pragma: no cover - abstract
        raise NotImplementedError

    def parse(self, obj: dict[str, Any], line: str) -> dict[str, Any]:  # pragma: no cover - abstract
        """One JSON line ⇒ ``{session?, text?, paths?, error?, status?}``."""
        return {}

    def unavailable_message(self, verb: str, error: str) -> Optional[str]:
        return None

    def folder(self, session: str) -> Optional[str]:
        return None

    async def generate(self, verb: str, args: dict[str, Any], ctx: Optional[MediaContext] = None) -> MediaResult:
        if not self.supports(verb):
            raise MediaError(f'media: no client supports "{verb}"')
        prompt = self.templates[verb].replace("{prompt}", fold_media_prompt(verb, args))
        job = _Job(self.name, verb, _VERB_KIND[verb], self.config, ctx)
        if self.mock:
            return self._mock_run(job, verb, prompt)
        cmd = self.command()
        cwd = os.path.realpath(tempfile.mkdtemp(prefix="toolnexus-cli-"))  # D17
        try:
            try:
                proc = await asyncio.create_subprocess_exec(
                    *cmd, *self.argv(prompt, cwd),
                    cwd=cwd,
                    env={**os.environ, **self.fixed_env},
                    stdin=asyncio.subprocess.DEVNULL,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL,
                    start_new_session=True,
                    limit=_LINE_LIMIT,
                )
            except OSError:
                raise MediaUnavailableError(f'{self.name}: cannot start "{cmd[0]}"') from None
            text = ""
            paths: list[str] = []
            error: Optional[str] = None
            usage: Optional[dict[str, Any]] = None
            loop = asyncio.get_running_loop()
            when = loop.time() + max(0.0, job.deadline - time.monotonic())
            try:
                async with asyncio.timeout_at(when):
                    assert proc.stdout is not None
                    while True:
                        raw = await proc.stdout.readline()
                        if not raw:
                            break
                        line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                        try:
                            obj = json.loads(line)
                        except ValueError:
                            continue
                        if not isinstance(obj, dict):
                            continue
                        ev = self.parse(obj, line) or {}
                        if ev.get("session"):
                            job.job_id = str(ev["session"])
                        if ev.get("text"):
                            text += ev["text"]
                        paths.extend(p for p in ev.get("paths") or [] if isinstance(p, str) and p)
                        if ev.get("error") is not None:
                            error = ev["error"]
                        if ev.get("usage"):
                            usage = ev["usage"]
                        if ev.get("status"):
                            await job.progress(ev["status"])
                    rc = await proc.wait()
            except TimeoutError:
                _kill(proc)
                try:
                    await asyncio.wait_for(proc.wait(), 5)
                except Exception:  # noqa: BLE001
                    pass
                raise MediaError(f"{self.name}: timed out after {_num_text(job.timeout_ms)}ms", "timeout",
                                 job.job_id) from None
            except asyncio.CancelledError:
                _kill(proc)
                raise
            res = self._collect(job, verb, text, paths, error, rc)
            res.usage = usage
            return res
        finally:
            shutil.rmtree(cwd, ignore_errors=True)

    def _mock_run(self, job: _Job, verb: str, prompt: str) -> MediaResult:
        """The CLI mock: no process. Writes ``<gather.dir>/<client>-mock-<verb>.<png|mp4>``
        (gather.dir default ``<tmp>/toolnexus-media``) holding ``toolnexus mock <verb>``,
        gathers it like a CLI file; text = the exact prompt the CLI would have received."""
        ext = ".mp4" if verb == "video" else ".png"
        d = self.config.gather.dir or os.path.join(tempfile.gettempdir(), "toolnexus-media")
        try:
            os.makedirs(d, exist_ok=True)
            p = os.path.abspath(os.path.join(d, f"{self.name}-mock-{verb}{ext}"))
            with open(p, "wb") as f:
                f.write(f"toolnexus mock {verb}".encode())
        except OSError as e:
            raise MediaError(f"{self.name}: {e}") from None
        job.job_id = "mock"
        return MediaResult(files=[job.gather_local(p)], text=prompt, job_id="mock", status="completed")

    def _collect(self, job: _Job, verb: str, text: str, paths: list[str], error: Optional[str], rc: int) -> MediaResult:
        cands = list(paths) + [m.group(0) for m in _PATH_RE.finditer(text)]
        if job.job_id:
            fd = self.folder(job.job_id)
            if fd and os.path.isdir(fd):
                cands += [os.path.join(fd, n) for n in sorted(os.listdir(fd))]
        seen: set[str] = set()
        prefix = "image/" if verb == "image" else "video/"
        kept: list[str] = []
        for p in cands:
            if p in seen:
                continue
            seen.add(p)
            if os.path.isfile(p) and (_ext_mime(p) or "").startswith(prefix):
                kept.append(p)
        if kept:
            files = [job.gather_local(p) for p in kept]
            return MediaResult(files=files, text=text or None, job_id=job.job_id, status="completed")
        if error is not None:
            msg = self.unavailable_message(verb, error)
            if msg is not None:
                raise MediaUnavailableError(msg, job.job_id)
            raise MediaError(f"{self.name}: no {verb}: {_cap(error, 400)}", job_id=job.job_id)
        raise MediaError(f"{self.name}: no {verb} file in the run (exit code {rc})"
                         + (f": {_cap(text, 300)}" if text else ""), job_id=job.job_id)


class GrokMediaClient(CliMediaClient):
    """Grok CLI (SuperGrok subscription): image and video via ``grok -p … --output-format
    streaming-json``. Never edits your Grok settings; under Zero Data Retention video is
    ``unavailable`` (see the docs, ``grok-video-zdr``)."""

    name = "grok"
    default_command = "grok"
    templates = {
        "image": "Generate exactly one image with your image generation tool: {prompt}. Do not write code. "
                 "Reply only with the file path.",
        "video": "Generate exactly one short video with your video generation tool, shortest duration "
                 "available: {prompt}. Do not write code. Reply only with the file path.",
    }
    fixed_env = {"GROK_CONFIG": '{"features":{"image_gen":true,"video_gen":true}}'}

    def argv(self, prompt: str, cwd: str) -> list[str]:
        out = ["-p", prompt, "--cwd", cwd, "--output-format", "streaming-json", "--yolo", "--no-auto-update"]
        if self.config.model:
            out += ["-m", self.config.model]
        return out

    def parse(self, obj: dict[str, Any], line: str) -> dict[str, Any]:
        t = obj.get("type")
        if t == "text":
            d = obj.get("data")
            return {"text": d} if isinstance(d, str) else {}
        if t == "tool_call":
            tn = obj.get("toolName")
            return {"status": f"tool {tn if isinstance(tn, str) else ''}"}
        if t == "tool_call_update":
            ev: dict[str, Any] = {}
            st = obj.get("status")
            if isinstance(st, str) and st:
                ev["status"] = f"tool {st}"
            ro = obj.get("rawOutput")
            if st == "completed" and isinstance(ro, dict) and isinstance(ro.get("path"), str) and ro["path"]:
                ev["paths"] = [ro["path"]]
            if st == "failed":
                msg = ro.get("message") if isinstance(ro, dict) else None
                ev["error"] = msg if isinstance(msg, str) else json.dumps(ro, separators=(",", ":"), ensure_ascii=False, sort_keys=True)
            return ev
        if t == "end":
            ev = {}
            if isinstance(obj.get("sessionId"), str):
                ev["session"] = obj["sessionId"]
            sr = obj.get("stopReason")
            ev["status"] = f"end {sr if isinstance(sr, str) else ''}"
            u = _usage(obj.get("usage"), {"input_tokens": "inputTokens", "output_tokens": "outputTokens"}) or {}
            c = _usage(obj, {"total_cost_usd": "costUsd"})
            if c:
                u.update(c)
            ticks = obj.get("total_cost_usd_ticks")
            if isinstance(ticks, (int, float)) and not isinstance(ticks, bool):
                u["raw"] = {"total_cost_usd_ticks": ticks}  # §7G.12 D21: unit undocumented, never converted
            if u:
                ev["usage"] = u
            return ev
        if t == "error":
            m = obj.get("message")
            return {"error": m if isinstance(m, str) else line}
        return {}

    def unavailable_message(self, verb: str, error: str) -> Optional[str]:
        low = _ascii_lower(error)
        if "zero data retention" in low:
            return (f"grok: {verb} blocked by Zero Data Retention — run /privacy and opt in, or configure "
                    "ZDR video storage (docs/media#grok-video-zdr)")
        if "usage limit" in low:
            return f"grok: {_cap(error, 400)}"
        return None


class CodexMediaClient(CliMediaClient):
    """Codex CLI (ChatGPT plan): image via ``codex exec --json``. The file comes from the
    reply text or ``$CODEX_HOME/generated_images/<thread>``."""

    name = "codex"
    default_command = "codex"
    templates = {
        "image": "Generate exactly one image with your built-in image generation tool: {prompt}. Do not write "
                 "code or use scripts. Reply only with the absolute path where the image was saved.",
    }

    def argv(self, prompt: str, cwd: str) -> list[str]:
        out = ["exec", "--json", "--skip-git-repo-check", "-s", "workspace-write"]
        if self.config.model:
            out += ["-m", self.config.model]
        return out + [prompt]

    def parse(self, obj: dict[str, Any], line: str) -> dict[str, Any]:
        t = obj.get("type")
        ev: dict[str, Any] = {}
        if isinstance(t, str) and t:
            ev["status"] = t
        if t == "thread.started" and isinstance(obj.get("thread_id"), str):
            ev["session"] = obj["thread_id"]
        elif t == "item.completed":
            item = obj.get("item")
            if isinstance(item, dict) and item.get("type") == "agent_message" and isinstance(item.get("text"), str):
                ev["text"] = item["text"]
        elif t in ("error", "turn.failed"):
            m = obj.get("message")
            if not isinstance(m, str):
                e = obj.get("error")
                m = e.get("message") if isinstance(e, dict) else None
            ev["error"] = m if isinstance(m, str) else line
        return ev

    def folder(self, session: str) -> Optional[str]:
        home = os.environ.get("CODEX_HOME") or os.path.join(os.path.expanduser("~"), ".codex")
        return os.path.join(home, "generated_images", session)

    def unavailable_message(self, verb: str, error: str) -> Optional[str]:
        low = _ascii_lower(error)
        if "usage limit" in low or "quota" in low:
            return f"codex: {_cap(error, 400)}"
        return None


class _AgyMediaClient(CliMediaClient):
    """Google Antigravity CLI (``agy``): image via ``agy -p … --output-format stream-json
    --dangerously-skip-permissions``. The path is in the final result's response text, else
    the newest image in the conversation's brain folder. Reached through the
    ``MediaStyle.GOOGLE_AGY_CLI`` preset."""

    name = "agy"
    default_command = "agy"
    templates = {
        "image": "Generate exactly one image with your image generation tool: {prompt}. Do not write code. "
                 "Reply only with the absolute path where the image was saved.",
    }

    def argv(self, prompt: str, cwd: str) -> list[str]:
        out = ["-p", prompt, "--output-format", "stream-json", "--dangerously-skip-permissions"]
        if self.config.model:
            out += ["--model", self.config.model]
        return out

    def parse(self, obj: dict[str, Any], line: str) -> dict[str, Any]:
        ev: dict[str, Any] = {}
        e = obj.get("event")
        if e == "init":
            if isinstance(obj.get("conversation_id"), str):
                ev["session"] = obj["conversation_id"]
            ev["status"] = "init"
        elif e == "step_update":
            su = obj.get("step_update")
            if isinstance(su, dict):
                t, st = su.get("tool_name"), su.get("state")
                if isinstance(t, str) and t and isinstance(st, str) and st:
                    ev["status"] = f"tool {t} {st}"
        elif e == "result":
            r = obj.get("result")
            r = r if isinstance(r, dict) else {}
            if isinstance(r.get("conversation_id"), str):
                ev["session"] = r["conversation_id"]
            text = r.get("response") if isinstance(r.get("response"), str) else ""
            if text:
                ev["text"] = text
            st = r.get("status") if isinstance(r.get("status"), str) else ""
            ev["status"] = f"result {st}"
            low = _ascii_lower(text)
            if st == "SUCCESS" and ("quota" in low or "resource_exhausted" in low):
                ev["error"] = text  # agy reports quota exhaustion inside a SUCCESS result
            elif st != "SUCCESS":
                err = r.get("error")
                if isinstance(err, str) and err:
                    ev["error"] = err
                elif isinstance(err, dict) and isinstance(err.get("message"), str) and err["message"]:
                    ev["error"] = err["message"]
                elif text:
                    ev["error"] = text
                else:
                    ev["error"] = json.dumps(obj.get("result"), separators=(",", ":"), ensure_ascii=False,
                                             sort_keys=True)
            u = _usage(r.get("usage"), {"input_tokens": "inputTokens", "output_tokens": "outputTokens"})
            if u:
                ev["usage"] = u
        elif e == "error":
            m = obj.get("message")
            if isinstance(m, str):
                ev["error"] = m
        return ev

    def folder(self, session: str) -> Optional[str]:
        return os.path.join(os.path.expanduser("~"), ".gemini", "antigravity-cli", "brain", session)

    def unavailable_message(self, verb: str, error: str) -> Optional[str]:
        low = _ascii_lower(error)
        if "quota" in low or "usage limit" in low:
            return f"agy: {_cap(error, 400)}"
        return None
