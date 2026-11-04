#!/usr/bin/env python3
"""Fakes for the hermetic CLI client tests (SPEC §8C), stdlib only — mirrors the
helpers in golang/cli_test.go.

* ``TN_FAKE_ACP=1`` and argv ``acp``: a fake ACP agent. ``FAKE_HTTP=1`` advertises
  mcpCapabilities.http; ``FAKE_FAIL_INIT=1`` exits before answering initialize;
  ``FAKE_NO_CONFIG=1`` rejects session/set_config_option; ``FAKE_PROJECT=1`` reads
  the MCP server from ``<cwd>/.mcp.json`` instead of session/new. ``TN_FAKE_REC``
  records every method.
* ``TN_FAKE_CLI=<mode>``: a fake one-shot CLI. Prompt from stdin, or the arg after
  ``--p``, or the file after ``--f``; answer to stdout, or to the file after ``--o``.
  Style-agnostic too: an arg starting with the preamble is the prompt, a
  ``toolnexus-cli-*`` path is the prompt file if it exists, else the out file.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request


def _j(v) -> str:
    return json.dumps(v, separators=(",", ":"))


_PREAMBLE20 = open(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "toolnexus", "clistyles", "preamble.txt"),
    encoding="utf-8",
).read()[:20]


def fake_cli(mode: str) -> None:
    args = sys.argv[1:]
    prompt, out_file = "", ""
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--p":
            i += 1
            prompt = args[i]
        elif a == "--f":
            i += 1
            with open(args[i], encoding="utf-8") as f:
                prompt = f.read()
        elif a == "--o":
            i += 1
            out_file = args[i]
        elif a == "--argv":
            sys.stdout.write('{"content":' + _j(" ".join(args)) + "}")
            return
        elif a.startswith(_PREAMBLE20):
            # Style-agnostic detection, so every built-in style's real argv works:
            # the prompt as an arg, the {{file}} temp file, the {{out}} temp path.
            prompt = a
        elif "toolnexus-cli-" in os.path.basename(a):
            if os.path.exists(a):
                with open(a, encoding="utf-8") as f:
                    prompt = f.read()
            else:
                out_file = a
        i += 1
    if not prompt:
        prompt = sys.stdin.read()
    rec = os.environ.get("TN_FAKE_REC")
    if rec:
        with open(rec, "a", encoding="utf-8") as f:
            f.write(prompt + "\n=====\n")
    if mode == "exit":
        sys.stderr.write("boom: bad login")
        sys.exit(3)
    if mode == "empty":
        return
    if mode == "sleep":
        time.sleep(10)
    if mode == "nooutfile":
        sys.stdout.write("progress only")
        return
    if mode == "tool":
        if "BADGE-77Q" in prompt:
            reply = '{"content": ' + _j("Your badge is BADGE-77Q.") + "}"
        else:
            reply = (
                "```json\n"
                '{"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_badge_number","arguments":"{}"}}]}'
                "\n```\n\n"
            )
    else:
        reply = '{"content":"hello"}'
    if out_file:
        sys.stdout.write("progress on stdout\n")
        with open(out_file, "w", encoding="utf-8") as f:
            f.write(reply)
        return
    sys.stdout.write(reply)


def _call_served(entry) -> str:
    reqs = [
        '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"fake","version":"0"}}}',
        '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}',
        '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_badge_number","arguments":{}}}',
        '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"load_skill","arguments":{"name":"hello-world"}}}',
    ]
    replies: list[str] = []
    noauth = 0
    if entry is not None and entry.get("type") == "http":
        hdr = {h["name"]: str(h["value"]) for h in entry.get("headers") or []}
        for r in reqs:
            req = urllib.request.Request(entry["url"], data=r.encode(), method="POST")
            req.add_header("Content-Type", "application/json")
            req.add_header("Accept", "application/json, text/event-stream")
            for k, v in hdr.items():
                req.add_header(k, v)
            try:
                with urllib.request.urlopen(req) as resp:
                    replies.append(resp.read().decode())
            except Exception:  # noqa: BLE001
                return "http error"
        req = urllib.request.Request(entry["url"], data=reqs[0].encode(), method="POST")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req) as resp:
                noauth = resp.status
        except urllib.error.HTTPError as e:
            noauth = e.code
    elif entry is not None:
        env = dict(os.environ)
        for e in entry.get("env") or []:
            env[e["name"]] = str(e["value"])
        p = subprocess.run(
            [entry["command"], *entry.get("args", [])],
            input="\n".join(reqs) + "\n",
            capture_output=True,
            text=True,
            env=env,
        )
        if p.returncode != 0:
            return "bridge error: " + p.stderr
        replies = p.stdout.strip().split("\n")
    kind = entry.get("type") if entry is not None else None
    return f"entry={kind if kind is not None else '<nil>'} noauth={noauth} replies={' | '.join(replies)}"


def fake_acp() -> None:
    if os.environ.get("FAKE_FAIL_INIT") == "1":
        sys.stderr.write("unknown flag: acp")
        sys.exit(2)
    out = sys.stdout
    rec_path = os.environ.get("TN_FAKE_REC")

    def send(o) -> None:
        out.write(json.dumps(o) + "\n")
        out.flush()

    def rec(line: str) -> None:
        if rec_path:
            with open(rec_path, "a", encoding="utf-8") as f:
                f.write(line + "\n")

    state = {"entry": None, "cwd": "", "id": 1000}
    lines = iter(sys.stdin.readline, "")

    def ask(call_id: str, title: str) -> str:
        state["id"] += 1
        rid = state["id"]
        send(
            {
                "jsonrpc": "2.0",
                "id": rid,
                "method": "session/request_permission",
                "params": {
                    "sessionId": "s1",
                    "toolCall": {"toolCallId": call_id, "title": title},
                    "options": [{"optionId": "yes", "kind": "allow_once"}, {"optionId": "no", "kind": "reject_once"}],
                },
            }
        )
        for raw in lines:
            try:
                r = json.loads(raw)
            except ValueError:
                continue
            if r.get("id") == rid:
                return ((r.get("result") or {}).get("outcome") or {}).get("optionId", "")
        return ""

    def update(u) -> None:
        send({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": "s1", "update": u}})

    for raw in lines:
        try:
            m = json.loads(raw)
        except ValueError:
            continue
        method, mid, params = m.get("method"), m.get("id"), m.get("params") or {}
        rec(f"{method} {json.dumps(params, separators=(',', ':'), sort_keys=True)}")
        if method == "initialize":
            send(
                {
                    "jsonrpc": "2.0",
                    "id": mid,
                    "result": {
                        "protocolVersion": 1,
                        "agentCapabilities": {"mcpCapabilities": {"http": os.environ.get("FAKE_HTTP") == "1"}},
                    },
                }
            )
        elif method == "session/new":
            state["cwd"] = params.get("cwd") or ""
            servers = params.get("mcpServers") or []
            if len(servers) == 1:
                state["entry"] = servers[0]
            send({"jsonrpc": "2.0", "id": mid, "result": {"sessionId": "s1"}})
        elif method == "session/set_config_option":
            if os.environ.get("FAKE_NO_CONFIG") == "1":
                send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "nope"}})
            else:
                send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/set_model":
            send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "session/prompt":
            if os.environ.get("FAKE_PROJECT") == "1":
                with open(os.path.join(state["cwd"], ".mcp.json"), encoding="utf-8") as f:
                    e = json.load(f)["mcpServers"]["toolnexus"]
                hdr = [{"name": k, "value": v} for k, v in (e.get("headers") or {}).items()]
                state["entry"] = {"type": "http", "url": e["url"], "headers": hdr}
            # devin-like: the name is only on the tool_call update; the permission
            # request has an empty title.
            update({"sessionUpdate": "tool_call", "toolCallId": "tc1", "title": "Calling get_badge_number from toolnexus"})
            ours = ask("tc1", "")
            update({"sessionUpdate": "tool_call", "toolCallId": "tc2", "title": "Bash"})
            own = ask("tc2", "Bash")
            fake = ask("tc3", "my_get_badge_number_wrapper")  # a lookalike on a new id
            again = ask("tc1", "Bash")  # the decision is per id
            # an agent->client request the client does not implement
            send({"jsonrpc": "2.0", "id": 9999, "method": "fs/read_text_file", "params": {"path": "/etc/hosts"}})
            for raw2 in lines:
                try:
                    r = json.loads(raw2)
                except ValueError:
                    continue
                if r.get("id") == 9999:
                    rec("fs-reply " + json.dumps(r.get("error") or {}, sort_keys=True))
                    break
            text = f"perm ours={ours} own={own} fake={fake} again={again}; " + _call_served(state["entry"])
            update({"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": text}})
            send({"jsonrpc": "2.0", "id": mid, "result": {"stopReason": "end_turn"}})


if __name__ == "__main__":
    if os.environ.get("TN_FAKE_ACP") == "1" and sys.argv[1:2] == ["acp"]:
        fake_acp()
    elif os.environ.get("TN_FAKE_CLI"):
        fake_cli(os.environ["TN_FAKE_CLI"])
