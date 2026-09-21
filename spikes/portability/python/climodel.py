"""A CLI-backed `generate` for toolnexus's Python `create_in_process_client`
(ADR 0026 shape), wired into the REAL `python/` port unmodified.

This is deliberately the smallest slice needed to answer the portability
question the task set: does Python's forced-SYNCHRONOUS `generate` contract
(see python/src/toolnexus/client.py `_InProcessTransport.post` — a coroutine
is explicitly rejected there, "it runs on the client's worker thread") make a
one-shot subprocess-backed model source awkward? Answer: no — `subprocess.run`
is exactly the shape `generate` wants: call in, block, return. No threads,
no asyncio bridging, no event loop reentrancy question, unlike what an ACP
bidirectional-session adapter would need. That asymmetry (one-shot CLI: easy
in the sync seam; ACP long-lived session: would need real thought) is itself
the portability finding worth recording.

Envelope: `<openai_request>{body}</openai_request>` written to a prompt FILE
(never argv, matching ADR 0026's stated preference and the Go spike's argv
E2BIG measurement), CLI writes `<openai_response>{...}</openai_response>` to
an --out FILE. Response is strictly parsed: dispatch on whether
`message.tool_calls` is populated, never on `finish_reason`.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from typing import Any, Callable

_ENVELOPE_RE = re.compile(r"<openai_response[^>]*>(.*?)</openai_response>", re.S)


class CLIModelError(RuntimeError):
    pass


def make_cli_generate(
    *,
    fakecli_path: str,
    python_exe: str | None = None,
) -> Callable[[dict[str, Any]], dict[str, Any]]:
    """Returns a synchronous `generate(req) -> dict` for create_in_process_client.

    `req` is the dict toolnexus hands `generate`: {"messages", "tools", "model", "body"}.
    `body` is EVERY key the client assembled — handed through byte-for-byte inside the
    envelope, so a key no adapter has ever heard of still reaches the CLI.
    """
    python_exe = python_exe or sys.executable

    def generate(req: dict[str, Any]) -> dict[str, Any]:
        body = req.get("body") or {}
        envelope = "<openai_request endpoint=\"/v1/chat/completions\">" + json.dumps(body) + "</openai_request>"

        with tempfile.TemporaryDirectory() as td:
            prompt_path = os.path.join(td, "prompt.txt")
            out_path = os.path.join(td, "out.txt")
            with open(prompt_path, "w", encoding="utf-8") as f:
                f.write(envelope)

            proc = subprocess.run(
                [python_exe, fakecli_path, "--prompt-file", prompt_path, "--out", out_path, "--model", str(body.get("model") or "")],
                capture_output=True,
                text=True,
                timeout=10,
            )
            if proc.returncode != 0:
                raise CLIModelError(f"climodel: CLI exited {proc.returncode}: {proc.stderr}")

            with open(out_path, "r", encoding="utf-8") as f:
                raw_out = f.read()

        m = _ENVELOPE_RE.search(raw_out)
        if not m:
            raise CLIModelError(f"climodel: no <openai_response> envelope in CLI output: {raw_out!r}")

        parsed = json.loads(m.group(1))
        choice = (parsed.get("choices") or [{}])[0]
        message = choice.get("message") or {}

        # Strict: dispatch on WHAT THE MESSAGE CONTAINS, never on finish_reason/kind
        # (ADR 0026, "what the message contains must win").
        tool_calls = message.get("tool_calls") or []
        if tool_calls:
            out_calls = []
            for c in tool_calls:
                fn = c.get("function") or {}
                args = fn.get("arguments")
                if not isinstance(args, str):
                    raise CLIModelError("climodel: arguments must be a JSON-encoded string")
                out_calls.append({"id": c.get("id"), "name": fn.get("name"), "arguments": args})
            return {"tool_calls": out_calls}

        return {"content": message.get("content") or ""}

    return generate
