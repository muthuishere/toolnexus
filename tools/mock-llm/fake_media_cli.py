#!/usr/bin/env python3
"""A fake media-generating coding CLI: stands in for `grok` and `codex` (part of the toolnexus
mock kit; stdlib only, deterministic).

    python3 tools/mock-llm/fake_media_cli.py --as grok  -p "<prompt>" --cwd <dir> --output-format streaming-json --yolo --no-auto-update [-m <model>]
    python3 tools/mock-llm/fake_media_cli.py --as codex exec --json --skip-git-repo-check -s workspace-write [-m <model>] "<prompt>"

Everything after `--as <cli>` is the real CLI's argv, so a client configured with
command = ["python3", ".../fake_media_cli.py", "--as", "grok"] runs unchanged. The output is the
stdout shape the real CLIs print (captured live 2026-11-03, pinned in conformance/media/cli.json
on the media-clients branch, ADR 0037 / SPEC §7G.11):

grok (`--output-format streaming-json`): a non-JSON banner line, available_commands, text,
  tool_call (image_gen or image_to_video), tool_call_update completed with rawOutput.path, a text
  line naming the file, then the end event with usage {input_tokens, output_tokens},
  total_cost_usd and total_cost_usd_ticks. Files go to <cwd>/sessions/<id>/images/1.png and,
  for video, also <cwd>/sessions/<id>/videos/1.mp4. Exit 0.
codex (`exec --json`): thread.started, turn.started, item.completed agent_message whose text is
  the absolute path of the image, turn.completed with usage. The image is written to
  $CODEX_HOME/generated_images/<thread>/exec-1.png (CODEX_HOME defaults to <cwd>/.codex). Exit 0.

A verb is read from the prompt the clients build: "video" in it means video (grok only), else
image. A prompt containing "[fail]" fails the way the real CLI fails at its usage limit:
grok prints {"type":"error","message":"You have reached your usage limit for today"},
codex prints thread.started then {"type":"turn.failed","error":{"message":"You've hit your usage
limit. Try again later."}}; both exit 1. Session/thread ids are a hash of the prompt.
"""
import base64
import hashlib
import json
import os
import struct
import sys

# examples/media/fixture.png (8x8, 82 bytes)
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAGUlEQVR42mP4z8AARwyrkBAVJZClkAEVJQBm3VpRJDtqMQAAAABJRU5ErkJggg==")
MP4 = struct.pack(">I", 24) + b"ftypisom" + struct.pack(">I", 512) + b"isomiso2"


def out(obj):
    sys.stdout.write((obj if isinstance(obj, str) else json.dumps(obj)) + "\n")
    sys.stdout.flush()


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return path


def flag(argv, name, default=None):
    return argv[argv.index(name) + 1] if name in argv and argv.index(name) + 1 < len(argv) else default


def grok(argv):
    prompt = flag(argv, "-p", "")
    cwd = os.path.realpath(flag(argv, "--cwd", os.getcwd()))
    sid = "s-" + hashlib.sha256(prompt.encode()).hexdigest()[:8]
    out("grok fake starting (not JSON, ignored)")
    if "[fail]" in prompt:
        out({"type": "error", "message": "You have reached your usage limit for today"})
        return 1
    out({"type": "available_commands", "tools": ["image_gen", "image_to_video"], "commands": ["compact"]})
    out({"type": "text", "data": "Generating"})
    img = write(os.path.join(cwd, "sessions", sid, "images", "1.png"), PNG)
    out({"type": "tool_call", "toolCallId": "c0", "title": "image_gen", "kind": "image_gen", "status": "pending",
         "toolName": "image_gen", "rawInput": {"prompt": prompt, "aspect_ratio": "1:1"}, "content": [], "locations": []})
    out({"type": "tool_call_update", "toolCallId": "c0", "status": "completed",
         "rawOutput": {"type": "ImageGen", "path": img, "filename": "1.png", "session_folder": "images"},
         "locations": []})
    final = img
    if "video" in prompt.lower():
        final = write(os.path.join(cwd, "sessions", sid, "videos", "1.mp4"), MP4)
        out({"type": "tool_call", "toolCallId": "c1", "title": "image_to_video", "kind": "image_to_video",
             "status": "pending", "toolName": "image_to_video", "rawInput": {"prompt": prompt, "duration": 6},
             "content": [], "locations": []})
        out({"type": "tool_call_update", "toolCallId": "c1", "status": "completed",
             "rawOutput": {"type": "ImageToVideo", "path": final, "filename": "1.mp4", "session_folder": "videos"},
             "locations": []})
    out({"type": "text", "data": " " + final})
    out({"type": "end", "stopReason": "end_turn", "sessionId": sid, "usage": {"input_tokens": 1000, "output_tokens": 50},
         "num_turns": 1, "total_cost_usd": 0.0125, "total_cost_usd_ticks": 125000000})
    return 0


def codex(argv):
    prompt = argv[-1] if argv else ""
    tid = "t-" + hashlib.sha256(prompt.encode()).hexdigest()[:8]
    out({"type": "thread.started", "thread_id": tid})
    if "[fail]" in prompt:
        out({"type": "turn.failed", "error": {"message": "You've hit your usage limit. Try again later."}})
        return 1
    out({"type": "turn.started"})
    home = os.environ.get("CODEX_HOME") or os.path.join(os.getcwd(), ".codex")
    img = write(os.path.abspath(os.path.join(home, "generated_images", tid, "exec-1.png")), PNG)
    out({"type": "item.completed", "item": {"id": "item_0", "type": "agent_message", "text": img}})
    out({"type": "turn.completed", "usage": {"input_tokens": 1000, "output_tokens": 50}})
    return 0


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv[:1] != ["--as"] or len(argv) < 2 or argv[1] not in ("grok", "codex"):
        sys.stderr.write("usage: fake_media_cli.py --as grok|codex <the real CLI's argv>\n")
        return 2
    return (grok if argv[1] == "grok" else codex)(argv[2:])


if __name__ == "__main__":
    sys.exit(main())
