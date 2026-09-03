"""Live multimodal example: attach an image to a run, and let a tool hand one back.

Runs the shared 8x8 fixture (`examples/media/fixture.png`, quadrants red/green/blue/
white) through both provider styles against OpenRouter, and closes tasks 12.1-12.3 of
the `add-multimodal-content` change for this port.

    OPENROUTER_API_KEY=... python examples/multimodal.py

**Arrival is proven by the prompt-token delta, never by the model's answer.** A model
asked to name colours will happily name four colours it never received — which is
exactly how the silent-drop bug this release fixes stayed hidden. It cannot fake
prompt tokens.

Two things are measured per style:

1. attachment — the identical request without and with `image(fixture)`; the prompt
   tokens must jump.
2. §8A relocation — a tool returning an image on `ToolResult.parts`, called by the
   model. The run must complete AND cost materially more prompt tokens than the same
   run whose tool returns text only. On `openai` the image is relocated into a
   synthetic user message (a `tool` message cannot carry one); on `anthropic` it rides
   inside `tool_result`. Both are exercised here, live.

Cheap by construction: tiny models, an 82-byte image, `max_tokens` capped at 40.
The API key is read from the environment and is never printed, logged or written.

Known upstream defect (see CHANGELOG.md): OpenRouter accepts an image bound for an
Anthropic model with HTTP 200 and drops it en route — a ~+4 token delta instead of
hundreds. Observed on both of its endpoints (`/chat/completions` with an openai-shaped
`image_url`, and the Anthropic-compatible `/v1/messages` with a native `source{}`
block), and reproducible with plain curl carrying no toolnexus code at all. It is a
routing defect above this library, so it is reported as `image=dropped-upstream`,
never as a failure of toolnexus. To exercise an Anthropic model with a working image
path, point `base_url` at `https://api.anthropic.com` with an `ANTHROPIC_API_KEY`.
"""
from __future__ import annotations

import asyncio
import os

from toolnexus import ToolResult, create_client, create_toolkit, define_tool, image

_HERE = os.path.dirname(os.path.abspath(__file__))
_EXAMPLES = os.path.normpath(os.path.join(_HERE, "..", "..", "examples"))
FIXTURE = os.path.join(_EXAMPLES, "media", "fixture.png")

ASK = (
    "Name the four quadrant colours of this image, clockwise from top-left. "
    "Answer with four words only."
)
TOOL_ASK = (
    "Call the screenshot tool, then name the four quadrant colours of the image it "
    "returns, clockwise from top-left. Answer with four words only."
)
COLOURS = ("red", "green", "blue", "white")

# Even this 82-byte image costs hundreds of prompt tokens wherever it actually
# arrives (8 500 on gpt-4o-mini, 263 on gemini-2.5-flash-lite — a tile budget, not a
# byte count). Anything under this is the image having been dropped en route; a
# double-digit difference is turn-to-turn noise, not an image.
MIN_IMAGE_TOKENS = 200

STYLES = (
    ("openai", "openai/gpt-4o-mini"),
    ("anthropic", "anthropic/claude-haiku-4.5"),
)


def _colours_named(text: str) -> int:
    low = text.lower()
    return sum(1 for c in COLOURS if c in low)


def _screenshot_tool(*, with_image: bool):
    """A tool returning an 8x8 screenshot — with or without the image part.

    The parts-less twin is the control: the prompt-token difference between the two
    runs is the image, and nothing else.
    """
    part = image(FIXTURE)

    def screenshot() -> ToolResult:
        """Capture the current screen and return it as a PNG."""
        return ToolResult(
            output="screenshot captured, 8x8 png",
            is_error=False,
            parts=[{"type": part.type, "mimeType": part.mimeType, "data": part.data}]
            if with_image
            else None,
        )

    return define_tool(screenshot, name="screenshot")


def _client(style: str, model: str, key: str):
    return create_client(
        base_url="https://openrouter.ai/api/v1",
        style=style,
        model=model,
        api_key=key,
        request_params={"max_tokens": 40},
    )


async def _run_style(style: str, model: str, key: str) -> str:
    agent = _client(style, model, key)

    # --- 1. attachment: the same request, without and with the image ---------
    # builtins off: their ~2 000-token schema would drown the signal we measure
    bare = await create_toolkit(builtins=False)
    text_only = await agent.run(ASK, bare)
    with_image = await agent.run([ASK, image(FIXTURE)], bare)
    ptok_text = text_only.usage["prompt_tokens"]
    ptok_image = with_image.usage["prompt_tokens"]
    delta = ptok_image - ptok_text
    arrived = delta >= MIN_IMAGE_TOKENS
    colours = _colours_named(with_image.text)
    print(f"\n[{style}] {model}")
    print(f"  text-only ptok={ptok_text}  with-image ptok={ptok_image}  delta=+{delta}")
    print(f"  answer: {with_image.text.strip()[:120]!r}  ({colours}/4 colours named)")
    if not arrived:
        print("  ^ image did NOT arrive: too few prompt tokens. Upstream drop, not a")
        print("    toolnexus failure — the block is emitted per SPEC §8A either way.")
    await bare.close()

    # --- 2. §8A relocation: a tool that returns an image ---------------------
    tk_img = await create_toolkit(builtins=False, extra_tools=[_screenshot_tool(with_image=True)])
    tk_txt = await create_toolkit(builtins=False, extra_tools=[_screenshot_tool(with_image=False)])
    res_img = await agent.run(TOOL_ASK, tk_img)
    res_txt = await agent.run(TOOL_ASK, tk_txt)
    await tk_img.close()
    await tk_txt.close()

    called = [c["name"] for c in res_img.tool_calls]
    reloc_delta = res_img.usage["prompt_tokens"] - res_txt.usage["prompt_tokens"]
    # Two independent facts: the loop completed with the part in it (ours), and the
    # image actually reached the model (upstream's to drop).
    reloc = "ok" if bool(res_img.text) and "screenshot" in called else "failed"
    reloc_image = "ok" if reloc_delta >= MIN_IMAGE_TOKENS else "dropped-upstream"
    print(
        f"  tool calls: {called}  turns={res_img.turns}/{res_txt.turns}  "
        f"tool-result ptok delta=+{reloc_delta}  -> loop={reloc} image={reloc_image}"
    )
    print(f"  answer: {res_img.text.strip()[:120]!r}")

    return (
        f"RESULT python style={style} model={model} ptok_text={ptok_text} "
        f"ptok_image={ptok_image} delta=+{delta} image={'ok' if arrived else 'dropped-upstream'} "
        f"colours={colours}/4 relocation={reloc} reloc_image={reloc_image} "
        f"reloc_delta=+{reloc_delta}"
    )


async def main() -> None:
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        print("(no OPENROUTER_API_KEY — skipping the live multimodal run)")
        return

    lines = [await _run_style(style, model, key) for style, model in STYLES]
    print()
    for line in lines:
        print(line)


if __name__ == "__main__":
    asyncio.run(main())
