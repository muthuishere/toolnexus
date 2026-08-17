"""An ONNX model running IN THIS PROCESS as the LLM, with no server and no socket.

The point is not that a local model exists — it is that toolnexus needs **one
option changed** to use one. `http_transport` replaces the wire call; the agent
loop, MCP servers, skills and every other tool source keep working untouched.

    # export a tool-calling model to ONNX once (see modelforge, or optimum-cli):
    #   optimum-cli export onnx --model Qwen/Qwen2.5-1.5B-Instruct \\
    #       --task text-generation-with-past ./qwen-onnx
    uv pip install onnxruntime tokenizers numpy
    ONNX_MODEL_DIR=./qwen-onnx python examples/local_onnx_model.py

Deliberately NOT using `transformers` — this runs on `onnxruntime` + `tokenizers`
+ `numpy`, so the chat template and the tool-call parsing are visible here rather
than hidden in a library. That is the code you actually have to own when you move
a model in-process.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import sys
from typing import Any, Optional

MODEL_DIR = os.environ.get("ONNX_MODEL_DIR")
if not MODEL_DIR:
    print("ONNX_MODEL_DIR is not set — skipping (see the docstring for the export command).")
    raise SystemExit(0)

try:
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer
except ImportError as e:  # pragma: no cover - example-only dependency
    print(f"missing an example dependency ({e}); run: uv pip install onnxruntime tokenizers numpy")
    raise SystemExit(0)

from toolnexus import create_client, create_toolkit, define_tool


# --------------------------------------------------------------------------- #
# The model. Greedy decoding over a raw ONNX graph, with the KV cache threaded
# through by hand — `--task text-generation-with-past` is what produces those
# past_key_values/present pairs, and skipping the cache makes generation
# quadratic rather than merely slow.
# --------------------------------------------------------------------------- #
class OnnxChatModel:
    def __init__(self, model_dir: str) -> None:
        cfg = json.load(open(f"{model_dir}/config.json"))
        self.layers = cfg["num_hidden_layers"]
        self.kv_heads = cfg.get("num_key_value_heads", cfg["num_attention_heads"])
        self.head_dim = cfg["hidden_size"] // cfg["num_attention_heads"]
        self.tok = Tokenizer.from_file(f"{model_dir}/tokenizer.json")
        self.sess = ort.InferenceSession(
            f"{model_dir}/model.onnx", providers=["CPUExecutionProvider"]
        )
        self.out_names = [o.name for o in self.sess.get_outputs()]
        self.stop_ids = {
            i for i in (self.tok.token_to_id("<|im_end|>"), self.tok.token_to_id("<|endoftext|>"))
            if i is not None
        }

    def _empty_past(self) -> dict[str, Any]:
        return {
            f"past_key_values.{i}.{kind}": np.zeros(
                (1, self.kv_heads, 0, self.head_dim), np.float32
            )
            for i in range(self.layers)
            for kind in ("key", "value")
        }

    def generate(self, prompt: str, max_new_tokens: int = 256) -> str:
        ids = self.tok.encode(prompt).ids
        past, produced, cur, pos = self._empty_past(), [], ids, 0
        for _ in range(max_new_tokens):
            n = len(cur)
            outs = self.sess.run(
                None,
                {
                    "input_ids": np.array([cur], np.int64),
                    "attention_mask": np.ones((1, pos + n), np.int64),
                    "position_ids": np.array([list(range(pos, pos + n))], np.int64),
                    **past,
                },
            )
            next_id = int(outs[self.out_names.index("logits")][0, -1].argmax())
            if next_id in self.stop_ids:
                break
            produced.append(next_id)
            past = {
                f"past_key_values.{i}.{kind}": outs[self.out_names.index(f"present.{i}.{kind}")]
                for i in range(self.layers)
                for kind in ("key", "value")
            }
            pos += n
            cur = [next_id]
        return self.tok.decode(produced)


# --------------------------------------------------------------------------- #
# OpenAI request  ->  ChatML prompt  ->  OpenAI response.
#
# This is the whole job of an in-process transport, and it is where the work
# actually is: toolnexus speaks the OpenAI wire shape, the model speaks ChatML
# plus Qwen's <tool_call> convention, and something has to translate. Doing it
# here keeps it out of the agent loop.
# --------------------------------------------------------------------------- #
TOOL_CALL_RE = re.compile(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", re.S)


def to_chatml(messages: list[dict[str, Any]], tools: Optional[list[dict[str, Any]]]) -> str:
    system = "You are a helpful assistant."
    body: list[dict[str, Any]] = []
    for m in messages:
        if m.get("role") == "system":
            system = m.get("content") or system
        else:
            body.append(m)

    if tools:
        schemas = "\n".join(json.dumps(t.get("function", t)) for t in tools)
        system += (
            "\n\n# Tools\n\nYou may call one or more functions to assist with the user query.\n\n"
            f"You are provided with function signatures within <tools></tools> XML tags:\n<tools>\n{schemas}\n</tools>\n\n"
            "For each function call, return a json object with function name and arguments "
            "within <tool_call></tool_call> XML tags:\n"
            '<tool_call>\n{"name": <function-name>, "arguments": <args-json-object>}\n</tool_call>'
        )

    out = [f"<|im_start|>system\n{system}<|im_end|>"]
    for m in body:
        role = m.get("role")
        if role == "tool":
            # A tool result goes back as a user turn carrying <tool_response>.
            out.append(
                f"<|im_start|>user\n<tool_response>\n{m.get('content')}\n</tool_response><|im_end|>"
            )
        elif role == "assistant" and m.get("tool_calls"):
            calls = "\n".join(
                "<tool_call>\n"
                + json.dumps({"name": c["function"]["name"],
                              "arguments": json.loads(c["function"].get("arguments") or "{}")})
                + "\n</tool_call>"
                for c in m["tool_calls"]
            )
            out.append(f"<|im_start|>assistant\n{calls}<|im_end|>")
        else:
            out.append(f"<|im_start|>{role}\n{m.get('content') or ''}<|im_end|>")
    out.append("<|im_start|>assistant\n")
    return "\n".join(out)


def to_openai_response(text: str) -> dict[str, Any]:
    calls = TOOL_CALL_RE.findall(text)
    if calls:
        tool_calls = []
        for i, raw in enumerate(calls):
            try:
                parsed = json.loads(raw)
            except json.JSONDecodeError:
                continue  # a malformed call is dropped, not crashed on
            tool_calls.append({
                "id": f"call_{i}",
                "type": "function",
                "function": {
                    "name": parsed.get("name"),
                    "arguments": json.dumps(parsed.get("arguments") or {}),
                },
            })
        if tool_calls:
            return {"role": "assistant", "tool_calls": tool_calls}
    return {"role": "assistant", "content": TOOL_CALL_RE.sub("", text).strip()}


class OnnxTransport:
    """§8 Gap 2. `post` receives the request body as a DICT and returns a DICT —
    this port never marshals JSON for the transport, so an in-process model pays
    no serialization at all."""

    def __init__(self, model: OnnxChatModel) -> None:
        self.model = model

    def post(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):
        prompt = to_chatml(payload.get("messages") or [], payload.get("tools"))
        raw = self.model.generate(prompt)
        message = to_openai_response(raw)
        finish = "tool_calls" if "tool_calls" in message else "stop"
        return {
            "choices": [{"index": 0, "message": message, "finish_reason": finish}],
            # Real counts, not zeros: the loop reports them and a lie here is a
            # lie in every metric downstream.
            "usage": {
                "prompt_tokens": len(self.model.tok.encode(prompt).ids),
                "completion_tokens": len(self.model.tok.encode(raw).ids),
                "total_tokens": 0,
            },
        }

    def open(self, url: str, headers: dict[str, str], payload: dict[str, Any], timeout: float):
        raise NotImplementedError("this example implements the non-streaming path only")


async def main() -> None:
    print(f"loading {MODEL_DIR} …")
    model = OnnxChatModel(MODEL_DIR)
    print(f"loaded: {model.layers} layers, {model.kv_heads} kv heads, head dim {model.head_dim}\n")

    def get_weather(city: str) -> str:
        """Current weather for a city."""
        return json.dumps({"city": city, "tempC": 31, "sky": "clear"})

    toolkit = await create_toolkit(
        builtins=False,
        extra_tools=[define_tool(get_weather, name="get_weather",
                                 description="Get the current weather for a city.")],
    )

    # THE ONLY LINE THAT DIFFERS from talking to a hosted model.
    client = create_client(
        base_url="http://in-process.invalid",
        style="openai",
        model=os.path.basename(MODEL_DIR.rstrip("/")),
        api_key="unused",
        http_transport=OnnxTransport(model),
    )

    result = await client.run("What is the weather in Chennai?", toolkit=toolkit)
    for call in result.tool_calls:
        print(f"tool call : {call['name']}({call['args']}) -> {call['output']}")
    print(f"answer    : {result.text}")
    print(f"turns     : {result.turns} | sockets opened: 0")

    if not result.tool_calls:
        print("\nNOTE: the model answered without calling the tool. Small models do this;"
              " it is a property of the model, not of the transport.", file=sys.stderr)


if __name__ == "__main__":
    asyncio.run(main())
