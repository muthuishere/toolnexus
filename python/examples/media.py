"""Media generation (SPEC §7G) — the shared E1 example, identical in every port.

    python examples/media.py                      # image + speech + tool face
    MEDIA_VOICE=Rachel python examples/media.py   # pick the ElevenLabs voice
    MEDIA_VIDEO=1 python examples/media.py        # + a Grok video (subscription)
    MEDIA_PAID=1 python examples/media.py         # + paid video / talking video

Clients, in order, each only when its CLI is on PATH or its key is set: Grok CLI, Codex CLI,
ElevenLabs (ELEVENLABS_API_KEY), then the paid fallbacks OpenAI (OPENAI_API_KEY), fal
(FAL_API_KEY), A2E (A2E_API_KEY). A verb goes to the first client that can serve it; an
unavailable one (no key, no credits, an arg it does not take) falls through to the next.
Paid image clients run at their smallest size / lowest quality defaults. Keys are read from
the environment, never printed. Exit code: 1 if any attempted step failed, 0 otherwise (also
0, with a hint, when no client is available at all).
"""
import asyncio
import base64
import os
import shutil
import sys

from toolnexus import (
    A2EMediaClient,
    CodexMediaClient,
    ElevenLabsMediaClient,
    FalMediaClient,
    GrokMediaClient,
    OpenAIMediaClient,
    create_media,
)

PROMPT = "a red apple on a white table"


def _has(env: str) -> bool:
    return bool(os.environ.get(env))


def show(client: str, verb: str, res) -> None:
    for f in res.files:
        where = f.path or f.url or "inline"
        if f.path and os.path.isfile(f.path):
            size = str(os.path.getsize(f.path))
        elif f.data is not None:
            size = str(len(base64.b64decode(f.data)))
        else:
            size = "-"
        line = f"  {client} {verb}: {where} {f.mime_type} {size} bytes"
        if res.usage:
            line += f" usage={res.usage}"
        print(line)


async def main() -> int:
    clients = []
    if shutil.which("grok"):
        clients.append(GrokMediaClient())
    if shutil.which("codex"):
        clients.append(CodexMediaClient())
    eleven = ElevenLabsMediaClient() if _has("ELEVENLABS_API_KEY") else None
    if eleven:
        clients.append(eleven)
    if _has("OPENAI_API_KEY"):
        clients.append(OpenAIMediaClient())  # gpt-image-1-mini, quality low, 1024x1024
    if _has("FAL_API_KEY"):
        clients.append(FalMediaClient())  # flux/schnell
    if _has("A2E_API_KEY"):
        clients.append(A2EMediaClient())
    if not clients:
        print("no media client available: install the grok or codex CLI, or set "
              "ELEVENLABS_API_KEY, OPENAI_API_KEY, FAL_API_KEY or A2E_API_KEY")
        return 0
    print("clients:", ", ".join(c.name for c in clients))
    media = create_media(clients)
    failed = 0

    async def step(label, coro):
        nonlocal failed
        try:
            return await coro
        except Exception as e:  # noqa: BLE001 — report and keep going
            failed += 1
            print(f"  {label} FAILED: {e}")
            return None

    seen: dict = {}

    def track(p):  # progress events name the client working on the call
        seen["client"] = p["client"]

    def served_by(res, verb):
        return seen.pop("client", None) or next((c.name for c in clients if c.supports(verb)), "?")

    # 1. image
    print("1. image")
    img = await step("image", media.image(PROMPT, progress=track))
    if img:
        show(served_by(img, "image"), "image", img)

    # 2. speech through ElevenLabs
    speech = None
    if eleven:
        print("2. speech (elevenlabs)")
        speech = await step("speech", create_media(eleven).speech(
            "Hello from toolnexus.", voice=os.environ.get("MEDIA_VOICE") or None))
        if speech:
            show("elevenlabs", "speech", speech)
    else:
        print("2. speech skipped (set ELEVENLABS_API_KEY)")

    # 3. tool face
    tools = media.tools()
    print("3. tools:", [t.name for t in tools])
    gen = next(t for t in tools if t.name == "generate_image")
    r = await gen.execute({"prompt": PROMPT})
    meta = r.metadata or {}
    if r.is_error:
        failed += 1
        print("  generate_image FAILED:", r.output)
    else:
        usage = f" usage={meta['usage']}" if meta.get("usage") else ""
        print(f"  {meta.get('client')} generate_image: {r.output}{usage}")

    # 4. Grok video (subscription) only on request
    grok = next((c for c in clients if c.name == "grok"), None)
    if os.environ.get("MEDIA_VIDEO") == "1" and grok:
        print("4. video (grok)")
        vid = await step("video", create_media(grok).video(PROMPT))
        if vid:
            show("grok", "video", vid)

    # 5. paid video / talking video only on request
    if os.environ.get("MEDIA_PAID") == "1":
        paid = [c for c in clients if c.name in ("openai", "fal", "a2e")]
        if any(c.supports("video") for c in paid):
            print("5. paid video")
            vid = await step("paid video", create_media(paid).video(PROMPT, progress=track))
            if vid:
                show(served_by(vid, "video"), "video", vid)
        if img and speech and any(c.supports("talk") for c in paid):
            face = img.files[0].path or img.files[0].url
            audio = speech.files[0].path or speech.files[0].url
            print("5. paid talk")
            clip = await step("talk", create_media(paid).talk(face, audio=audio, progress=track))
            if clip:
                show(served_by(clip, "talk"), "talk", clip)

    print("done" if not failed else f"{failed} step(s) failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
