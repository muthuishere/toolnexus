"""Media generation (SPEC §7G) against the shared a2e.ai preset — both faces: the typed
verb (``media.image``) and the tool face (``media.tools()``, invoked directly).

    A2E_API_KEY=... python examples/media.py

Without A2E_API_KEY it prints a hint and exits 0, so hermetic runs never touch the
network. The key is read by the preset's ``${A2E_API_KEY}`` header and is never printed.
"""
import asyncio
import os
from pathlib import Path

from toolnexus import create_media, load_media_style

PRESET = Path(__file__).resolve().parents[2] / "examples" / "media-styles" / "a2e.json"


async def main() -> None:
    media = create_media(
        load_media_style(str(PRESET)),
        on_progress=lambda p: print(f"  ..{p['verb']} {p['status']}"),
    )

    # 1. Typed verb.
    img = await media.image("a red fox in fresh snow, watercolor", width=768, height=768)
    print("image status:", img.status)
    for f in img.files:
        print("  file:", f.type, f.mime_type, f.url)

    # 2. Tool face — the same engine as plain Tools an LLM would call.
    tools = media.tools()
    for t in tools:
        print("tool:", t.name)
    speech = next(t for t in tools if t.name == "generate_speech")
    res = await speech.execute({"text": "Hello from toolnexus."})
    print("speech output:", res.output, "is_error:", res.is_error)
    for p in res.parts or []:
        print("  part:", p)


if __name__ == "__main__":
    if not os.environ.get("A2E_API_KEY"):
        print("set A2E_API_KEY to run the media example (skipping: no network call made)")
        raise SystemExit(0)
    asyncio.run(main())
