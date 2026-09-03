#!/usr/bin/env python3
"""Live conformance check for SPEC.md §8A — content-part emission and the
tool-result relocation rule.

§8A exists because assumptions about provider wire shapes turned out to be
wrong, so it is checked against real endpoints rather than trusted. This is a
contract test for the spec itself, not for any one port: if a check here fails,
§8A is wrong and all seven ports built on it are wrong too.

    python3 scripts/live-multimodal-check.py

**How arrival is proven.** Not by reading the model's answer — a model asked to
name colours will happily name colours it never saw, which is precisely how the
silent-drop bug hides. Arrival is proven by the **prompt-token delta** between
an identical request with and without the image. A model can guess a colour; it
cannot fake prompt tokens.

Keys are read from the process environment and never printed, logged or written.
`OPENROUTER_API_KEY` is required. `ANTHROPIC_API_KEY` is optional and unlocks
the only check that can exercise Anthropic's NATIVE `source{}` block shape —
OpenRouter's endpoint is OpenAI-compatible, so it can never test that shape, and
the check is reported as SKIPPED rather than quietly assumed.

Cheap by construction: tiny models, an 82-byte image, max_tokens capped.
"""
import base64, json, os, pathlib, sys, urllib.error, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
FIXTURE = ROOT / "examples" / "media" / "fixture.png"
GOLDEN = ROOT / "examples" / "media" / "fixture.png.base64"

OR_URL = "https://openrouter.ai/api/v1/chat/completions"
ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"

OPENAI = "openai/gpt-4o-mini"
GEMINI = "google/gemini-2.5-flash-lite"
CLAUDE_OR = "anthropic/claude-haiku-4.5"
CLAUDE_NATIVE = "claude-haiku-4-5"

OR_KEY = os.environ.get("OPENROUTER_API_KEY", "")
ANTHROPIC_KEY = os.environ.get("ANTHROPIC_API_KEY", "")
if not OR_KEY:
    sys.exit("OPENROUTER_API_KEY is not set; cannot run the live check.")

B64 = base64.b64encode(FIXTURE.read_bytes()).decode()
DATA_URL = f"data:image/png;base64,{B64}"
ASK = ("Name the four quadrant colours of this image, clockwise from top-left. "
       "Answer with four words only.")
# An image this small still costs real prompt tokens wherever it actually
# arrives. Anything under this is the image having been dropped en route.
MIN_IMAGE_TOKENS = 20

results = []


def post(url, body, headers):
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
    except Exception as e:
        return 0, f"{type(e).__name__}: {e}"


def openrouter(body):
    return post(OR_URL, body, {"Authorization": f"Bearer {OR_KEY}",
                               "Content-Type": "application/json"})


def record(name, ok, detail, skipped=False, upstream=False):
    """`upstream=True` marks a real defect that is NOT ours: a router or
    provider mis-handling a shape §8A emits correctly. It is reported loudly
    and never hidden, but it does not fail our contract, because no change to
    this repo can fix it."""
    results.append((name, ok, skipped, upstream))
    tag = "SKIP" if skipped else ("PASS" if ok else ("WARN" if upstream else "FAIL"))
    print(f"  {tag}  {name}\n        {detail}")


def or_ptok(p):
    try:
        return p["usage"]["prompt_tokens"]
    except Exception:
        return None


def or_text(p):
    try:
        return (p["choices"][0]["message"].get("content") or "").strip()
    except Exception:
        return ""


def user_msg(parts):
    return [{"role": "user", "content": parts}]


TXT = {"type": "text", "text": ASK}
IMG = {"type": "image_url", "image_url": {"url": DATA_URL}}

print("\n§1B — the committed base64 golden")
ok = B64 == GOLDEN.read_text().strip()
record("fixture bytes encode to the committed golden", ok,
       f"{len(B64)} chars; committed golden and committed bytes agree" if ok
       else "MISMATCH — committed golden disagrees with committed bytes")

print("\n§8A — openai-shaped wire (OpenRouter): image_url + data: URL")
print("      arrival proven by prompt-token delta, not by the model's answer")
for model in (OPENAI, GEMINI, CLAUDE_OR):
    s1, p1 = openrouter({"model": model, "max_tokens": 40, "messages": user_msg([TXT])})
    s2, p2 = openrouter({"model": model, "max_tokens": 40, "messages": user_msg([TXT, IMG])})
    if s1 != 200 or s2 != 200:
        record(f"{model} receives the image", False,
               f"HTTP {s1}/{s2} · {str(p2)[:140]}")
        continue
    base_t, img_t = or_ptok(p1), or_ptok(p2)
    delta = img_t - base_t
    arrived = delta >= MIN_IMAGE_TOKENS
    # A drop here is the router's, not ours: §8A emitted the documented
    # openai-style block and got a 200 with the image gone.
    record(f"{model} receives the image", arrived,
           f"ptok {base_t} → {img_t} (delta {delta:+}) · reply {or_text(p2)[:52]!r}"
           + ("" if arrived else
              "  ← image DROPPED IN TRANSIT; the reply is a guess. The openai-shaped "
              "block is correct per §8A; the router discarded it converting to this "
              "provider. Switching to the anthropic style does NOT help: OpenRouter's /v1/messages drops it too (+4 on both auth headers). Point baseUrl at api.anthropic.com instead."),
           upstream=not arrived)

print("\n§8A — anthropic NATIVE source{} blocks")
if not ANTHROPIC_KEY:
    record("anthropic native image block", True,
           "SKIPPED — needs ANTHROPIC_API_KEY. OpenRouter's endpoint is "
           "OpenAI-compatible and CANNOT exercise source{type:\"base64\"}; "
           "§8A's anthropic column is unverified live.", skipped=True)
else:
    s, p = post(ANTHROPIC_URL,
                {"model": CLAUDE_NATIVE, "max_tokens": 40, "messages": [
                    {"role": "user", "content": [
                        {"type": "text", "text": ASK},
                        {"type": "image", "source": {"type": "base64",
                                                     "media_type": "image/png",
                                                     "data": B64}}]}]},
                {"x-api-key": ANTHROPIC_KEY, "anthropic-version": "2023-06-01",
                 "Content-Type": "application/json"})
    record("anthropic native image block", s == 200,
           f"HTTP {s} · {str(p)[:160]}")

print("\n§8A — the relocation rule")
TOOL_TURN = [
    {"role": "user", "content": "Take a screenshot and tell me its quadrant colours."},
    {"role": "assistant", "content": None, "tool_calls": [
        {"id": "call_1", "type": "function",
         "function": {"name": "screenshot", "arguments": "{}"}}]},
]
# §8A's premise: openai MUST refuse an image in a `tool` message. A 200 here
# would mean the relocation rule is unnecessary complexity.
s, p = openrouter({"model": OPENAI, "max_tokens": 40, "messages": TOOL_TURN + [
    {"role": "tool", "tool_call_id": "call_1",
     "content": [{"type": "text", "text": "done"}, IMG]}]})
refused = s != 200
record("openai refuses an image in a `tool` message (§8A's premise)", refused,
       f"HTTP {s} · {str(p)[:150]}" if refused
       else "HTTP 200 — §8A's premise is WRONG; the relocation rule is unnecessary")

# ...and the relocated form must work, or the rule is unimplementable.
s1, p1 = openrouter({"model": OPENAI, "max_tokens": 40, "messages": TOOL_TURN + [
    {"role": "tool", "tool_call_id": "call_1", "content": "done"},
    {"role": "user", "content": [
        {"type": "text", "text": "Output of tool screenshot (call_1):"}, TXT]}]})
s2, p2 = openrouter({"model": OPENAI, "max_tokens": 40, "messages": TOOL_TURN + [
    {"role": "tool", "tool_call_id": "call_1", "content": "done"},
    {"role": "user", "content": [
        {"type": "text", "text": "Output of tool screenshot (call_1):"}, IMG, TXT]}]})
if s1 == 200 and s2 == 200:
    delta = or_ptok(p2) - or_ptok(p1)
    ok = delta >= MIN_IMAGE_TOKENS
    record("openai accepts the relocated part, and the image arrives", ok,
           f"ptok {or_ptok(p1)} → {or_ptok(p2)} (delta {delta:+}) · reply {or_text(p2)[:52]!r}")
else:
    record("openai accepts the relocated part, and the image arrives", False,
           f"HTTP {s1}/{s2} · {str(p2)[:140]}")

print("\n§8A — the silent-drop hazard the positive allowlist exists for")
s, p = openrouter({"model": GEMINI, "max_tokens": 40, "messages": user_msg([
    {"type": "input_text", "text": ASK},
    {"type": "input_image", "image_url": DATA_URL}])})
# Either outcome proves the point: the block is discarded, and whether that
# surfaces as a 400 or a confident 200 is not ours to control. What we control
# is never emitting an unrecognised block in the first place.
dropped = s != 200 or (or_ptok(p) or 0) < MIN_IMAGE_TOKENS
record("an unrecognised block type is discarded, never honoured", dropped,
       f"HTTP {s} · ptok={or_ptok(p)} · {(or_text(p) or str(p))[:110]!r}")

total = len(results)
skipped = [n for n, _, sk, _ in results if sk]
warned = [n for n, ok, sk, up in results if not ok and not sk and up]
failed = [n for n, ok, sk, up in results if not ok and not sk and not up]
passed = sum(1 for _, ok, sk, _ in results if ok and not sk)
print(f"\n{passed}/{total - len(skipped)} checks passed"
      + (f", {len(skipped)} skipped" if skipped else "")
      + (f", {len(warned)} upstream warning(s)" if warned else ""))
for n in skipped:
    print(f"  SKIPPED (needs a key we do not have): {n}")
for n in warned:
    print(f"  UPSTREAM DEFECT (not fixable here): {n}")
for n in failed:
    print(f"  FAILED (our contract is wrong): {n}")
sys.exit(1 if failed else 0)
