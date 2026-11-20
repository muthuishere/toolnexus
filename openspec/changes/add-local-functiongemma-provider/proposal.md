# Proposal — add-local-functiongemma-provider

## Why

Tiny local models are now good enough to pick a tool, but they do not speak the OpenAI tool-call
wire. FunctionGemma (270M) emits its calls as text —
`<start_function_call>call:name{key:<escape>value<escape>,n:3}<end_function_call>` — and
llama-server returns that text as plain `content` without parsing it. Today a host must hand-roll
the developer line, the stop token, the prefill and the parser outside toolnexus, so the same tools
cannot run against a local model and a hosted one through one client.

Separately, a plain OpenAI-compatible local server (llama-server, vLLM, Ollama `/v1`) fails with
`no API key` since 0.22.0 unless the host invents a key.

## What changes

- New constructor `createLocalClient` (Go: `CreateLocalClient(LocalOptions)`), two formats:
  - `openai` (default): a normal client against the endpoint that needs no API key.
  - `functiongemma`: an in-process client (SPEC §8 "In-process models") whose generate pins the
    FunctionGemma developer line as the first line of the developer message, folds any system
    prompt in after it, optionally prefills `<start_function_call>call:`, stops at
    `<end_function_call>`, runs at temperature 0, and parses the raw call text into tool calls.
- New exported parser `parseFunctionGemmaCalls` (Go: `ParseFunctionGemmaCalls`).
- `SPEC.md` §8 gains a "Local models" subsection.

## Scope

Go first. The other seven ports are pending and tracked as unchecked tasks.
