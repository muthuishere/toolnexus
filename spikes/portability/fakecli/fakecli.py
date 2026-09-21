#!/usr/bin/env python3
"""Shared hermetic fake CLI-backed model, used by BOTH the python/ and clojure/
portability spikes so the same subprocess contract is exercised from two
different host languages.

Shape matches ADR 0032's CLI-backed model source: one-shot process, prompt
delivered via a FILE (never argv, per the ADR's own file-channel preference),
response delivered via a FILE (the codex `--output-last-message` shape) so the
caller never has to scrape stdout.

Usage:
    fakecli.py --prompt-file IN --out OUT [--model M]

Behavior:
    Reads the <openai_request>{json}</openai_request> envelope from IN,
    proves it actually parsed the body (not just echoed bytes) by lifting an
    "unknown to any adapter" key out of the body and mirroring its value back
    inside the tool call arguments it returns. Writes a
    <openai_response>{json}</openai_response> envelope to OUT containing ONE
    tool call, in the OpenAI wire shape (arguments as a JSON-ENCODED STRING).
"""
import argparse
import json
import re
import sys


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--prompt-file", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--model", default=None)
    args = p.parse_args()

    with open(args.prompt_file, "r", encoding="utf-8") as f:
        prompt = f.read()

    m = re.search(r"<openai_request[^>]*>(.*?)</openai_request>", prompt, re.S)
    if not m:
        print("fakecli: no <openai_request> envelope found in prompt file", file=sys.stderr)
        return 2
    body = json.loads(m.group(1))

    # Prove verbatim passthrough: mirror a key back that no adapter in this repo
    # has ever heard of, and that only survives if the CLI-backed Generate
    # handed the body through byte-for-byte rather than re-rendering it.
    marker = body.get("x_portability_marker_never_seen_by_adapter")

    # A one-shot CLI is invoked once per LOOP TURN (the seam calls `generate`
    # again after the tool result is fed back in) — behave like a real agent
    # CLI: call the tool exactly once, then answer from the result already in
    # the transcript on the next turn, instead of looping forever.
    messages = body.get("messages") or []
    already_has_tool_result = any(m.get("role") == "tool" for m in messages if isinstance(m, dict))

    if already_has_tool_result:
        response = {
            "choices": [
                {
                    "index": 0,
                    "finish_reason": "stop",
                    "message": {
                        "role": "assistant",
                        "content": f"final answer: marker was {marker}",
                    },
                }
            ],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }
    else:
        tool_call = {
            "id": "call_1",
            "type": "function",
            "function": {
                "name": "echo_marker",
                # OpenAI wire contract: arguments is a JSON-ENCODED STRING, not a
                # bare object (this is the ADR 0032 gate-1c drift the Go spike
                # flagged as ambiguous; this fakecli emits the real wire shape).
                "arguments": json.dumps({"received_marker": marker, "model": body.get("model")}),
            },
        }
        response = {
            "choices": [
                {
                    "index": 0,
                    "finish_reason": "tool_calls",
                    "message": {
                        "role": "assistant",
                        "content": None,
                        "tool_calls": [tool_call],
                    },
                }
            ],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("<openai_response>")
        f.write(json.dumps(response))
        f.write("</openai_response>")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
