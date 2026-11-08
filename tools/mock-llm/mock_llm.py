#!/usr/bin/env python3
"""toolnexus mock model server: a deterministic, key-free, cost-free fake model.

Point any toolnexus client or classifier at it and get plausible answers with no
network and no API key. Python 3.9+ standard library only; copy the file anywhere.

    python3 tools/mock-llm/mock_llm.py [--host 127.0.0.1] [--port 8787] [--script replies.json]

It prints its base URL (``http://127.0.0.1:8787/v1``) on start. Every route is
served both with and without the ``/v1`` prefix, so any baseUrl shape works.

Routes
  POST /v1/chat/completions   OpenAI chat. Tools offered and no tool result yet ->
                              one tool call to the first tool (arguments built from its
                              JSON schema); otherwise a final text. ``stream: true`` -> SSE.
  POST /v1/messages           Anthropic messages, same behaviour, SSE when streaming.
  POST /v1/systemone          The System One classifier wire (backends typesafe,
                              openrouter, vercel, custom): {model, questions, state}.
  POST .../ai/run[/<model>]   The Cloudflare wire: {model, input:{questions,state}},
                              answered inside a {success, result} envelope.
  POST /v1/decisions          The OpenAI Decisions wire (backend openai):
                              {model, input, questions:[...]} answered by position.
  GET  /v1/models, /health

Answers are a pure function of the request:
  noul    0.95 (fixed)
  choice  option index = sha256(state + instructions) mod n over the option ids in
          ASCII order; the pick gets 0.7, the rest share 0.3; sums to exactly 1
  score   level = the same hash mod the level count; 0.7 there, the rest spread to
          the neighbours; score = the expected level, always within the rubric
State is the string as sent, or an object as compact sorted-key JSON, so the
systemone and decisions wires pick the same option for the same state.

--script file.json overrides replies (every key optional):
  {
    "text": "final chat text",
    "toolCall": {"name": "add", "arguments": {"a": 2, "b": 2}},
    "noul": 0.95,
    "answers": {"<question key or instructions>": {"noul": 0.1} | {"choice": "billing"} | {"score": 2}}
  }

    "faults": [{"path": "/chat/completions", "status": 429, "times": 1, "retryAfter": "0"}]

Tool sources: GET /.well-known/agent-card.json + POST /a2a is an A2A agent (SPEC 7A;
card name "mockagent", skill "summarize", SendMessage then GetTask: one "working" poll,
then "completed" with the text "A2A mock done: <task>"). POST /v1/tools/add {a, b} ->
{"sum": a+b} and GET /v1/tools/echo?text=x -> {"echo": "x"} are HTTP tool endpoints.
The MCP server, the one-shot CLI and the ACP agent are sibling entry points:
mock_mcp.py (stdio / --http), fake_cli.py, fake_acp_agent.py; or `mock_llm.py --mcp|--cli|--acp`.

Media (SPEC 7G): GET /v1/_mock/media-style.json returns a style whose baseUrl is this
server; it declares all six verbs. image, video and talking are async (one
"processing" poll, then "completed"; a body containing "[fail]" fails the job);
speech, transcribe and cloneVoice are sync. Result URLs are served under /v1/files/
(the shared 8x8 fixture PNG, a 10 ms WAV, a stub MP4).

Attachments (SPEC 1B/8A): when the chat request carries images, the final text is
"Received N image(s): image/png 82 bytes sha256=<12 hex>." so a test can prove the
bytes arrived intact.

Faults: header X-Mock-Fault: <status> (+ X-Mock-Retry-After) fails that request;
POST /v1/_mock/faults {path, status, times, retryAfter?, body?} queues faults;
GET /v1/_mock/requests returns per-route request counts; POST /v1/_mock/reset clears.

As a module: srv, base = mock_llm.start(port=0, script=None); ...; srv.shutdown()

Any bearer key is accepted. Headers are never logged; one line per request
(method, path, status) goes to stderr unless --quiet.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import struct
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPT: dict = {}
QUIET = False
_LOCK = threading.Lock()
FAULTS: list = []      # [{path, status, times, retryAfter?, body?}] consumed in order
COUNTS: dict = {}      # route -> requests seen
JOBS: dict = {}        # media job id -> polls so far
DEFAULT_TEXT = "This is a reply from the toolnexus mock model."
USAGE_IN, USAGE_OUT = 20, 8


# ---------- deterministic decision core ----------

def _state_text(state) -> str:
    if isinstance(state, str):
        return state
    return json.dumps(state, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _h(state_text: str, instructions: str) -> int:
    return int(hashlib.sha256((state_text + "\n" + (instructions or "")).encode()).hexdigest(), 16)


def _split(n: int, top: int) -> list:
    """Probabilities over n slots: 0.7 at `top`, 0.3 spread over the rest, two
    decimals, summing to exactly 1 (the remainder lands on `top`)."""
    if n == 1:
        return [1.0]
    rest = round(0.3 / (n - 1), 2)
    ps = [rest] * n
    ps[top] = 0
    ps[top] = round(1 - sum(ps), 2)
    return ps


def _override(key, instructions):
    a = SCRIPT.get("answers") or {}
    return a.get(key) if key in a else a.get(instructions)


def decide_noul(key, instructions):
    o = _override(key, instructions) or {}
    return float(o.get("noul", SCRIPT.get("noul", 0.95)))


def decide_choice(key, instructions, ids, state_text):
    ids = sorted(ids)
    o = _override(key, instructions) or {}
    top = ids.index(o["choice"]) if o.get("choice") in ids else _h(state_text, instructions) % len(ids)
    ps = _split(len(ids), top)
    return ids[top], dict(zip(ids, ps)), (0.8 if len(ids) > 1 else 1.0)


def decide_score(key, instructions, n, state_text):
    o = _override(key, instructions) or {}
    lvl = int(o["score"]) if "score" in o else _h(state_text, instructions) % n
    lvl = max(0, min(n - 1, lvl))
    ps = [0.0] * n
    nbrs = [i for i in (lvl - 1, lvl + 1) if 0 <= i < n]
    for i in nbrs:
        ps[i] = round(0.3 / len(nbrs), 2)
    ps[lvl] = round(1 - sum(ps), 2)
    score = round(sum(i * p for i, p in enumerate(ps)), 2)
    return score, ps, 0.6


def systemone_answers(body) -> dict:
    st = _state_text(body.get("state", ""))
    out = {}
    for key, q in (body.get("questions") or {}).items():
        ins, t, crit = q.get("instructions", ""), q.get("type"), q.get("criteria")
        if t == "choice":
            c, probs, conf = decide_choice(key, ins, list((crit or {"yes": ""}).keys()), st)
            out[key] = {"type": "choice", "choice": c, "probabilities": probs, "confidence": conf}
        elif t == "score":
            levels = crit or ["low", "high"]
            s, ps, conf = decide_score(key, ins, len(levels), st)
            out[key] = {"type": "score", "score": s,
                        "legend": {str(i): d for i, d in enumerate(levels)},
                        "probabilities": {str(i): p for i, p in enumerate(ps)}, "confidence": conf}
        else:
            out[key] = {"type": "noul", "noul": decide_noul(key, ins)}
    return {"model": body.get("model") or "mock-jev",
            "answers": out, "usage": {"input_tokens": USAGE_IN, "output_tokens": len(out)}}


def _decisions_state(inp) -> str:
    if isinstance(inp, str):
        return inp
    # [{role:user, content:[{type:input_text,text}, {type:input_image,...}]}]
    try:
        return "".join(p.get("text", "") for m in inp for p in m.get("content", [])
                       if p.get("type") == "input_text")
    except Exception:
        return _state_text(inp)


def decisions_reply(body) -> dict:
    st = _decisions_state(body.get("input", ""))
    answers = []
    for q in body.get("questions") or []:
        ins, t = q.get("instructions", ""), q.get("type")
        if t == "choice":
            ids = [c.get("value") for c in q.get("choices") or []] or ["yes"]
            c, probs, conf = decide_choice(None, ins, ids, st)
            answers.append({"type": "choice", "name": None, "choice": c, "confidence": conf,
                            "probabilities": [{"value": v, "probability": probs[v]} for v in ids]})
        elif t == "score":
            labels = [lv.get("label") for lv in q.get("levels") or []] or ["low", "high"]
            s, ps, conf = decide_score(None, ins, len(labels), st)
            answers.append({"type": "score", "name": None, "score": s, "confidence": conf,
                            "probabilities": [{"value": i, "label": lab, "probability": ps[i]}
                                              for i, lab in enumerate(labels)]})
        else:
            answers.append({"type": "predicate", "name": None, "probability": decide_noul(None, ins)})
    return {"model": body.get("model") or "gpt-6-luna", "answers": answers,
            "usage": {"input_tokens": USAGE_IN,
                      "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
                      "output_tokens": 0, "output_tokens_details": {"reasoning_tokens": 0},
                      "total_tokens": USAGE_IN}}


# ---------- chat (OpenAI / Anthropic) ----------

def _fake_value(schema):
    t = (schema or {}).get("type")
    if isinstance(t, list):
        t = next((x for x in t if x != "null"), "string")
    if (schema or {}).get("enum"):
        return schema["enum"][0]
    return {"string": "mock", "integer": 1, "number": 1, "boolean": True,
            "array": [], "object": {}}.get(t, "mock")


def _tool_call(tools):
    """(name, args) for the first offered tool, or the scripted one."""
    if SCRIPT.get("toolCall"):
        tc = SCRIPT["toolCall"]
        return tc["name"], tc.get("arguments") or {}
    t = tools[0]
    fn = t.get("function", t)
    schema = fn.get("parameters") or fn.get("input_schema") or {}
    props = schema.get("properties") or {}
    req = schema.get("required") or list(props)
    return fn.get("name"), {k: _fake_value(props.get(k)) for k in req}


def _text_of(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return " ".join(b.get("text", "") for b in content
                        if isinstance(b, dict) and isinstance(b.get("text"), str))
    return ""


def _tool_results(messages):
    res = []
    for m in messages:
        c = m.get("content")
        if m.get("role") == "tool":
            res.append(_text_of(c))
        elif isinstance(c, list):
            for b in c:
                if isinstance(b, dict) and b.get("type") == "tool_result":
                    res.append(_text_of(b.get("content")))
    return res


def _final_text(messages):
    if SCRIPT.get("text"):
        return SCRIPT["text"]
    imgs = _images(messages)
    if imgs:
        return _describe_images(imgs)
    res = _tool_results(messages)
    if res:
        return "Tool result: " + " | ".join(r.strip() for r in res)
    return DEFAULT_TEXT


def chat_plan(body):
    """('tool', name, args) or ('text', text)."""
    msgs = body.get("messages") or []
    tools = body.get("tools") or []
    if tools and not _tool_results(msgs):
        name, args = _tool_call(tools)
        return ("tool", name, args)
    return ("text", _final_text(msgs))


def openai_reply(body):
    plan, model = chat_plan(body), body.get("model") or "mock-model"
    usage = {"prompt_tokens": USAGE_IN, "completion_tokens": USAGE_OUT, "total_tokens": USAGE_IN + USAGE_OUT}
    if plan[0] == "tool":
        tc = {"id": "call_mock_1", "type": "function",
              "function": {"name": plan[1], "arguments": json.dumps(plan[2])}}
        msg, finish = {"role": "assistant", "content": None, "tool_calls": [tc]}, "tool_calls"
    else:
        msg, finish = {"role": "assistant", "content": plan[1]}, "stop"
    if not body.get("stream"):
        return {"id": "chatcmpl-mock", "object": "chat.completion", "created": 0, "model": model,
                "choices": [{"index": 0, "message": msg, "finish_reason": finish}], "usage": usage}
    base = {"id": "chatcmpl-mock", "object": "chat.completion.chunk", "created": 0, "model": model}
    if plan[0] == "tool":
        delta = {"role": "assistant", "tool_calls": [dict(msg["tool_calls"][0], index=0)]}
        chunks = [dict(base, choices=[{"index": 0, "delta": delta, "finish_reason": None}])]
    else:
        words = plan[1].split(" ")
        chunks = [dict(base, choices=[{"index": 0, "delta": {"role": "assistant", "content": ""}, "finish_reason": None}])]
        for i, w in enumerate(words):
            chunks.append(dict(base, choices=[{"index": 0, "delta": {"content": w if i == 0 else " " + w},
                                               "finish_reason": None}]))
    chunks.append(dict(base, choices=[{"index": 0, "delta": {}, "finish_reason": finish}]))
    chunks.append(dict(base, choices=[], usage=usage))
    return [("", c) for c in chunks] + [("", "[DONE]")]


def anthropic_reply(body):
    plan, model = chat_plan(body), body.get("model") or "mock-model"
    if plan[0] == "tool":
        block, stop = {"type": "tool_use", "id": "toolu_mock_1", "name": plan[1], "input": plan[2]}, "tool_use"
    else:
        block, stop = {"type": "text", "text": plan[1]}, "end_turn"
    usage = {"input_tokens": USAGE_IN, "output_tokens": USAGE_OUT}
    if not body.get("stream"):
        return {"id": "msg_mock", "type": "message", "role": "assistant", "model": model,
                "content": [block], "stop_reason": stop, "stop_sequence": None, "usage": usage}
    ev = [("message_start", {"type": "message_start", "message": {
        "id": "msg_mock", "type": "message", "role": "assistant", "model": model, "content": [],
        "stop_reason": None, "usage": {"input_tokens": USAGE_IN, "output_tokens": 0}}})]
    if block["type"] == "tool_use":
        ev.append(("content_block_start", {"type": "content_block_start", "index": 0,
                                           "content_block": dict(block, input={})}))
        ev.append(("content_block_delta", {"type": "content_block_delta", "index": 0, "delta": {
            "type": "input_json_delta", "partial_json": json.dumps(block["input"])}}))
    else:
        ev.append(("content_block_start", {"type": "content_block_start", "index": 0,
                                           "content_block": {"type": "text", "text": ""}}))
        ev.append(("content_block_delta", {"type": "content_block_delta", "index": 0,
                                           "delta": {"type": "text_delta", "text": block["text"]}}))
    ev.append(("content_block_stop", {"type": "content_block_stop", "index": 0}))
    ev.append(("message_delta", {"type": "message_delta", "delta": {"stop_reason": stop, "stop_sequence": None},
                                 "usage": {"output_tokens": USAGE_OUT}}))
    ev.append(("message_stop", {"type": "message_stop"}))
    return ev



# ---------- multimodal attachments (SPEC 1B / 8A) ----------

def _images(messages):
    """Every image part the request carried, as (mimeType, bytes|None, url|None)."""
    out = []
    def visit(parts):
        for b in parts if isinstance(parts, list) else []:
            if not isinstance(b, dict):
                continue
            if b.get("type") == "image_url":  # OpenAI
                u = (b.get("image_url") or {}).get("url", "")
                out.append(_from_url(u))
            elif b.get("type") == "image":  # Anthropic
                src = b.get("source") or {}
                if src.get("type") == "base64":
                    out.append((src.get("media_type"), base64.b64decode(src.get("data", "")), None))
                else:
                    out.append((None, None, src.get("url")))
            elif b.get("type") == "tool_result":
                visit(b.get("content"))
    for m in messages:
        visit(m.get("content"))
    return out


def _from_url(u):
    if u.startswith("data:") and ";base64," in u:
        head, data = u[5:].split(";base64,", 1)
        return (head, base64.b64decode(data), None)
    return (None, None, u)


def _describe_images(imgs):
    parts = []
    for mime, raw, url in imgs:
        if raw is not None:
            parts.append(f"{mime} {len(raw)} bytes sha256={hashlib.sha256(raw).hexdigest()[:12]}")
        else:
            parts.append(f"url {url}")
    return f"Received {len(imgs)} image(s): " + "; ".join(parts) + "."


# ---------- media generation (SPEC 7G), served for tools/mock-llm/media-style.json ----------

PNG = base64.b64decode(  # examples/media/fixture.png, 8x8, 82 bytes
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAIAAABLbSncAAAAGUlEQVR42mP4z8AARwyrkBAVJZClkAEVJQBm3VpRJDtqMQAAAABJRU5ErkJggg==")


def _wav():
    pcm = b"\x00\x00" * 80  # 10 ms of 16-bit mono silence at 8 kHz
    return (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " +
            struct.pack("<IHHIIHH", 16, 1, 1, 8000, 16000, 2, 16) + b"data" + struct.pack("<I", len(pcm)) + pcm)


MP4 = struct.pack(">I", 24) + b"ftypisom" + struct.pack(">I", 512) + b"isomiso2"  # ftyp box only (stub)
FILES = {"image.png": ("image/png", PNG), "speech.wav": ("audio/wav", _wav()),
         "video.mp4": ("video/mp4", MP4), "talking.mp4": ("video/mp4", MP4)}
ASYNC_VERBS = {"image": "image.png", "video": "video.mp4", "talking": "talking.mp4"}


def media_style(base):
    """The style JSON (SPEC 7G) that drives every verb against this server."""
    poll = lambda: {"method": "GET", "path": "/media/jobs/{id}", "statusPath": "data.status",
                    "done": ["completed"], "failed": ["failed"], "errorPath": "data.error",
                    "intervalMs": 20, "timeoutMs": 30000}
    a = lambda verb, kind, args, result: {"mode": "async", "kind": kind,
                                          "start": {"method": "POST", "path": f"/media/{verb}"},
                                          "args": args, "idPath": "data.id", "poll": poll(),
                                          "resultPath": result}
    y = lambda verb, kind, args, result: {"mode": "sync", "kind": kind,
                                         "start": {"method": "POST", "path": f"/media/{verb}"},
                                         "args": args, "resultPath": result}
    return {"baseUrl": base, "headers": {"Authorization": "Bearer ${MOCK_LLM_KEY}"},
            "errorEnvelope": {"codePath": "code", "okValues": [0], "messagePath": "message"},
            "verbs": {
                "image": a("image", "image", {"prompt": "prompt", "width": "size.width", "height": "size.height",
                                              "aspectRatio": "aspect_ratio", "seed": "seed"}, "data.urls"),
                "video": a("video", "video", {"prompt": "prompt", "image": "image_url", "seconds": "duration",
                                              "aspectRatio": "aspect_ratio"}, "data.url"),
                "speech": y("speech", "audio", {"text": "text", "voice": "voice"}, "data.url"),
                "transcribe": y("transcribe", "text", {"audio": "audio_url"}, "data.text"),
                "cloneVoice": y("cloneVoice", "text", {"name": "name", "samples": "samples"}, "data.voice_id"),
                "talking": a("talking", "video", {"image": "image_url", "audio": "audio_url"}, "data.url"),
            },
            "_doc": "toolnexus mock model server (tools/mock-llm). Image/video/talking are async (one "
                    "'processing' poll, then 'completed'); a prompt containing [fail] fails the job."}


def media_start(verb, body, base):
    if verb in ASYNC_VERBS:
        with _LOCK:
            jid = f"{verb}-{len(JOBS) + 1}"
            JOBS[jid] = {"polls": 0, "verb": verb, "fail": "[fail]" in json.dumps(body)}
        return {"code": 0, "data": {"id": jid, "status": "queued"}}
    if verb == "speech":
        return {"code": 0, "data": {"url": f"{base}/files/speech.wav"}}
    if verb == "transcribe":
        return {"code": 0, "data": {"text": f"Mock transcript of {body.get('audio_url', '')}."}}
    if verb == "cloneVoice":
        name = str(body.get("name", ""))
        return {"code": 0, "data": {"voice_id": "voice-" + hashlib.sha256(name.encode()).hexdigest()[:8]}}
    return None


def media_poll(jid, base):
    with _LOCK:
        j = JOBS.get(jid)
        if j is None:
            return None
        j["polls"] += 1
        n = j["polls"]
    if n < 2:
        return {"code": 0, "data": {"id": jid, "status": "processing"}}
    if j["fail"]:
        return {"code": 0, "data": {"id": jid, "status": "failed", "error": "mock: job asked to fail"}}
    url = f"{base}/files/{ASYNC_VERBS[j['verb']]}"
    return {"code": 0, "data": {"id": jid, "status": "completed",
                                **({"urls": [url]} if j["verb"] == "image" else {"url": url})}}


# ---------- tool sources: A2A agent (SPEC 7A) and HTTP tool endpoints ----------

TASKS: dict = {}  # a2a task id -> {"text", "polls"}


def a2a_card(origin):
    return {"name": "mockagent", "description": "Deterministic A2A agent from the toolnexus mock.",
            "version": "1.0.0", "protocolVersion": "0.3.0", "url": origin + "/a2a",
            "capabilities": {"streaming": False, "pushNotifications": False},
            "defaultInputModes": ["text/plain"], "defaultOutputModes": ["text/plain"],
            "skills": [{"id": "summarize", "name": "summarize", "description": "Summarise a task in one line."}]}


def a2a_rpc(msg):
    mid, method, p = msg.get("id"), msg.get("method"), msg.get("params") or {}
    if method == "SendMessage":
        parts = ((p.get("message") or {}).get("parts")) or []
        text = " ".join(x.get("text", "") for x in parts if x.get("kind", x.get("type")) == "text")
        with _LOCK:
            tid = f"task-{len(TASKS) + 1}"
            TASKS[tid] = {"text": text, "polls": 0}
        return {"jsonrpc": "2.0", "id": mid, "result": {"id": tid, "kind": "task", "status": {"state": "submitted"}}}
    if method == "GetTask":
        tid = p.get("id")
        with _LOCK:
            t = TASKS.get(tid)
            if t is not None:
                t["polls"] += 1
        if t is None:
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32001, "message": "Task not found"}}
        if t["polls"] < 2:
            return {"jsonrpc": "2.0", "id": mid, "result": {"id": tid, "kind": "task", "status": {"state": "working"}}}
        return {"jsonrpc": "2.0", "id": mid, "result": {
            "id": tid, "kind": "task", "status": {"state": "completed"},
            "artifacts": [{"artifactId": "a1", "parts": [{"kind": "text", "text": "A2A mock done: " + t["text"]}]}]}}
    return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"Method not found: {method}"}}


# ---------- fault injection ----------

def _take_fault(route, headers):
    """A fault for this request: the X-Mock-Fault header (every request that sends it),
    else the first queued fault whose path matches, consumed `times` times."""
    h = headers.get("X-Mock-Fault")
    if h:
        return {"status": int(h), "retryAfter": headers.get("X-Mock-Retry-After")}
    with _LOCK:
        for f in FAULTS:
            if f.get("times", 1) > 0 and f.get("path", route) in (route, "*"):
                f["times"] = f.get("times", 1) - 1
                return f
    return None


# ---------- HTTP ----------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "toolnexus-mock-llm"

    def log_message(self, *args):  # default logger is replaced: never print headers
        pass

    def _log(self, code):
        if not QUIET:
            sys.stderr.write(f"{self.command} {self.path.split('?')[0]} {code}\n")

    def _base(self):
        return f"http://{self.headers.get('Host') or '%s:%d' % self.server.server_address[:2]}/v1"

    def _body(self):
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            buf = bytearray()
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                buf += self.rfile.read(size)
                self.rfile.readline()
            raw = bytes(buf)
        else:
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n) if n else b""
        try:
            return json.loads(raw or b"{}")
        except ValueError:
            return None

    def _raw(self, data, ctype, code=200, extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)
        self._log(code)

    def _send(self, obj, code=200, extra=None):
        self._raw(json.dumps(obj).encode(), "application/json", code, extra)

    def _send_sse(self, events):
        out = b""
        for name, payload in events:
            if name:
                out += f"event: {name}\n".encode()
            out += b"data: " + (payload if isinstance(payload, str) else json.dumps(payload)).encode() + b"\n\n"
        self._raw(out, "text/event-stream")

    def _route(self):
        p = self.path.split("?")[0].rstrip("/")
        return p[3:] if p.startswith("/v1/") else p

    def _fault(self, r):
        if r.startswith("/_mock") or r.startswith("/files") or r in ("/health", "/healthz"):
            return False
        with _LOCK:
            COUNTS[r] = COUNTS.get(r, 0) + 1
        f = _take_fault(r, self.headers)
        if not f:
            return False
        extra = {"Retry-After": str(f["retryAfter"])} if f.get("retryAfter") is not None else None
        self._send(f.get("body") or {"error": {"type": "mock_fault", "message": f"mock fault {f['status']}"}},
                   int(f["status"]), extra)
        return True

    def do_GET(self):
        r = self._route()
        if r in ("/health", "/healthz"):
            return self._send({"status": "ok"})
        if r == "/_mock/requests":
            with _LOCK:
                return self._send(dict(COUNTS))
        if r == "/.well-known/agent-card.json":
            return self._send(a2a_card(self._base()[:-3]))
        if r == "/_mock/media-style.json":
            return self._send(media_style(self._base()))
        if r.startswith("/files/") and r[7:] in FILES:
            mime, data = FILES[r[7:]]
            return self._raw(data, mime)
        if self._fault(r):
            return
        if r == "/models":
            ids = ["mock-model", "jev-latest", "gpt-6-luna"]
            return self._send({"object": "list", "data": [
                {"id": i, "object": "model", "created": 0, "owned_by": "toolnexus-mock"} for i in ids]})
        if r == "/tools/echo":
            from urllib.parse import parse_qs, urlparse
            return self._send({"echo": (parse_qs(urlparse(self.path).query).get("text") or [""])[0]})
        if r.startswith("/media/jobs/"):
            out = media_poll(r[len("/media/jobs/"):], self._base())
            return self._send(out) if out else self._send({"code": 404, "message": "no such job"}, 404)
        self._send({"error": {"message": f"no route {r}"}}, 404)

    do_HEAD = do_GET

    def do_POST(self):
        r, body = self._route(), self._body()
        if body is None:
            return self._send({"error": {"message": "invalid JSON body"}}, 400)
        if r == "/_mock/reset":
            reset()
            return self._send({"ok": True})
        if r == "/_mock/faults":
            with _LOCK:
                FAULTS.extend(body if isinstance(body, list) else [body])
            return self._send({"ok": True})
        if self._fault(r):
            return
        if r == "/chat/completions":
            out = openai_reply(body)
            return self._send_sse(out) if isinstance(out, list) else self._send(out)
        if r == "/messages":
            out = anthropic_reply(body)
            return self._send_sse(out) if isinstance(out, list) else self._send(out)
        if r == "/systemone":
            return self._send(systemone_answers(body))
        if r == "/decisions":
            return self._send(decisions_reply(body))
        if "/ai/run" in r:  # Cloudflare Workers AI envelope
            inner = dict(body.get("input") or {}, model=body.get("model"))
            return self._send({"success": True, "errors": [], "messages": [],
                               "result": systemone_answers(inner)})
        if r == "/a2a":
            return self._send(a2a_rpc(body))
        if r == "/tools/add":
            return self._send({"sum": body.get("a", 0) + body.get("b", 0)})
        if r.startswith("/media/"):
            out = media_start(r[len("/media/"):], body, self._base())
            if out is not None:
                return self._send(out)
        self._send({"error": {"message": f"no route {r}"}}, 404)


def reset():
    """Forget queued faults, request counts and media jobs (the script stays)."""
    with _LOCK:
        FAULTS.clear()
        COUNTS.clear()
        JOBS.clear()
        TASKS.clear()
        FAULTS.extend(json.loads(json.dumps(SCRIPT.get("faults") or [])))


def start(host="127.0.0.1", port=0, script=None, quiet=True):
    """Start the server on a background thread. Returns (server, base_url);
    call server.shutdown() to stop it. `script` is a dict or a path."""
    global SCRIPT, QUIET
    if isinstance(script, str):
        with open(script, encoding="utf-8") as f:
            script = json.load(f)
    SCRIPT, QUIET = script or {}, quiet
    reset()
    srv = ThreadingHTTPServer((host, port), Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, f"http://{host}:{srv.server_address[1]}/v1"


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    for flag, mod in (("--mcp", "mock_mcp"), ("--cli", "fake_cli"), ("--acp", "fake_acp_agent")):
        if argv[:1] == [flag]:
            import importlib
            sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
            return importlib.import_module(mod).main(argv[1:])
    ap = argparse.ArgumentParser(description="Deterministic mock model server for toolnexus.")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8787, help="0 picks a free port")
    ap.add_argument("--script", help="JSON file overriding replies")
    ap.add_argument("--quiet", action="store_true", help="no per-request log lines")
    a = ap.parse_args(argv)
    srv, url = start(a.host, a.port, a.script, a.quiet)
    print(f"toolnexus mock model at {url}", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        srv.shutdown()


if __name__ == "__main__":
    main()
