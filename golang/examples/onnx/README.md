# In-process ONNX model (Go)

A real ONNX model running **inside this process** as the LLM — no server, no
socket. The point is not that a local model exists; it is that toolnexus needs
**one option changed** to use one:

```go
client := tn.CreateClient(tn.ClientOptions{
    BaseURL: "http://in-process.invalid", Style: tn.StyleOpenAI,
    Model: "qwen2.5-1.5b-instruct-onnx", APIKey: "unused",
    HTTPClient: &http.Client{Transport: &localRoundTripper{model: model}}, // ← this line
})
```

Everything else — the agent loop, tool calling, MCP servers, skills — is
unchanged and does not know a model moved.

## Verified output

```
loaded: 28 layers, 2 kv heads, head dim 128

tool call : get_weather(map[city:Chennai]) -> {"city":"Chennai","sky":"clear","tempC":31}
answer    : The weather in Chennai is currently clear with a temperature of 31°C.
turns     : 2 | tokens: 2245 | sockets opened: 0
```

That is `Qwen/Qwen2.5-1.5B-Instruct` quantized to int8 (1.7 GB), ~7.5 s wall
clock on an M-series Mac including model load.

## Why this is a separate Go module

An in-process ONNX model needs **two native artifacts**, and toolnexus itself
must not acquire them — the library's small dependency footprint is one of its
measured claims. The nested `go.mod` keeps `go build ./...` in the parent module
free of both, which is why this example is not compiled by CI.

## Setup

### 1. A tool-calling model, exported to ONNX

Use a model actually **trained for tool calling**. Measured, not guessed:
`SmolLM2-135M-Instruct` cannot do it — it hallucinates an answer instead of
calling, and even constrained scoring picks the wrong tool. Qwen2.5-1.5B works.

```bash
uv run --no-project --python 3.12 \
  --with "optimum[onnxruntime]==1.24.0" --with "transformers==4.48.3" \
  --with "torch==2.5.1" --with "onnx==1.17.0" --with "onnxruntime==1.20.1" \
  optimum-cli export onnx --model Qwen/Qwen2.5-1.5B-Instruct \
  --task text-generation-with-past ./qwen-fp32
```

`--task text-generation-with-past` is **required**: it is what emits the
`past_key_values`/`present` pairs. Without the KV cache, generation is quadratic.

Then quantize to int8 (7.1 GB → 1.7 GB). The quantized `model.onnx` is
self-contained, so **delete the `model.onnx_data` left over from the fp32
export** — it is 6.6 GB of weights nothing reads.

### 2. onnxruntime

The Go binding needs a matching shared library. `onnxruntime_go v1.33` wants ORT
API 29, i.e. **onnxruntime ≥ 1.29** — an older dylib fails with
`The requested API version [29] is not available`.

```bash
curl -L -O https://github.com/microsoft/onnxruntime/releases/download/v1.29.0/onnxruntime-osx-arm64-1.29.0.tgz
tar xzf onnxruntime-osx-arm64-1.29.0.tgz
```

### 3. A tokenizer

**Pure-Go tokenizers do not work here.** Qwen's pre-tokenizer regex uses a
negative lookahead (`\s+(?!\S)`), which Go's RE2 engine cannot compile — with
`sugarme/tokenizer` this panics at load. So this example uses
`daulet/tokenizers`, a CGO binding to HuggingFace's Rust tokenizers, which needs
a prebuilt static library:

```bash
curl -L -O https://github.com/daulet/tokenizers/releases/download/v1.27.0/libtokenizers.darwin-arm64.tar.gz
tar xzf libtokenizers.darwin-arm64.tar.gz    # -> libtokenizers.a
```

### 4. Run

```bash
CGO_LDFLAGS="-L/path/to/dir-containing-libtokenizers.a" \
ORT_DYLIB_PATH="/path/to/onnxruntime-osx-arm64-1.29.0/lib/libonnxruntime.dylib" \
ONNX_MODEL_DIR="/path/to/qwen-int8" \
go run .
```

With `ONNX_MODEL_DIR` unset the example prints a note and exits 0, so it is safe
to invoke unconditionally.

## What the example actually contains

Nothing is hidden in a helper library, because this is the code you own when a
model moves in-process:

| part | what it does |
|---|---|
| `OnnxModel.Generate` | greedy decode, KV cache threaded by hand across 28 layers |
| `toChatML` | OpenAI `messages` + `tools` → ChatML, including Qwen's `<tools>` block and `<tool_response>` for results |
| `toOpenAIMessage` | Qwen's `<tool_call>` output → OpenAI `tool_calls` |
| `localRoundTripper` | the seam — an `http.RoundTripper` that never opens a socket |

Layer count, KV-head count and head dim are read from `config.json`, so another
decoder-only export should work without code changes.

## Limits, stated

- **Non-streaming only.** `RunStream` needs an incremental body; this returns a
  complete response.
- **Greedy decoding.** No sampling, temperature or top-p.
- **The LLM path only.** MCP servers, HTTP tools and A2A keep their own clients
  and still open real connections — which is the point: a local model still
  needs real tools.
