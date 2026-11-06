import json
import subprocess
from toolnexus import create_in_process_client


def render(req):
    """Render the whole request as one prompt: tools, transcript, and the reply contract."""
    lines = ["You are the model inside a tool-calling loop. Tools you may call:"]
    for t in req.get("tools") or []:
        f = t["function"]
        lines.append(f"- {f['name']}: {f.get('description', '')} args={json.dumps(f.get('parameters', {}))}")
    lines += ["", "Conversation:"]
    called = {}  # tool_call_id -> tool name, so results read "[tool add] 5"
    for m in req["messages"]:
        if m["role"] == "tool":
            lines.append(f"[tool {called.get(m.get('tool_call_id'), m.get('tool_call_id'))}] {m['content']}")
        elif m.get("tool_calls"):
            for c in m["tool_calls"]:
                called[c["id"]] = c["function"]["name"]
            lines.append(f"[assistant called] {json.dumps([c['function'] for c in m['tool_calls']])}")
        else:
            c = m.get("content")
            lines.append(f"[{m['role']}] {c if isinstance(c, str) else json.dumps(c)}")
    lines += ["", 'Reply with ONLY one JSON object: {"tool_calls":[{"name":"...","arguments":{...}}]} to call tools,',
              'or {"content":"..."} with the final answer. No prose, no code fences.']
    return "\n".join(lines)


def parse(out):
    """Pull the first {...} out of the reply, tolerating fences or chatter around it."""
    try:
        r = json.loads(out[out.index("{"): out.rindex("}") + 1])
        if r.get("tool_calls"):
            return {"tool_calls": [{"name": c["name"], "arguments": c.get("arguments") or {}} for c in r["tool_calls"]]}
        if isinstance(r.get("content"), str):
            return {"content": r["content"]}
    except ValueError:
        pass
    return {"content": out.strip()}  # not our contract: treat it as the answer


def one_shot_client(command, *, model=None, timeout=300):
    """One process per turn. The prompt goes in on stdin; the reply comes back on stdout."""
    def generate(req):  # sync: toolnexus calls it on a worker thread
        r = subprocess.run(command, input=render(req), capture_output=True, text=True, timeout=timeout)
        if r.returncode != 0:
            raise RuntimeError(f"{command[0]} exited {r.returncode}: {r.stderr}")
        return parse(r.stdout)

    return create_in_process_client(model=model or command[0], generate=generate)
