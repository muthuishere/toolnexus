#!/usr/bin/env python3
"""A fake one-shot CLI: reads the rendered prompt on stdin; asks for add(19, 23) until it sees
a tool result in the transcript, then answers with it."""
import json, re, sys

prompt = sys.stdin.read()
m = re.search(r"^\[tool add\] (.+)$", prompt, re.M)
if m:
    print(json.dumps({"content": f"The sum is {m.group(1).strip()}."}))
else:
    print(json.dumps({"tool_calls": [{"name": "add", "arguments": {"a": 19, "b": 23}}]}))
