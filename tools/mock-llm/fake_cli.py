#!/usr/bin/env python3
"""A fake one-shot coding CLI for createInProcessClient (part of the toolnexus mock kit).

Reads the rendered prompt on stdin; asks for add(19, 23) until it sees a tool result in the
transcript ("[tool add] <result>"), then answers "The sum is <result>." as {"content": ...}.
Deterministic, stdlib only. Also reachable as `mock_llm.py --cli`.
"""
import json
import re
import sys


def main(argv=None):
    prompt = sys.stdin.read()
    m = re.search(r"^\[tool add\] (.+)$", prompt, re.M)
    if m:
        print(json.dumps({"content": f"The sum is {m.group(1).strip()}."}))
    else:
        print(json.dumps({"tool_calls": [{"name": "add", "arguments": {"a": 19, "b": 23}}]}))


if __name__ == "__main__":
    main()
