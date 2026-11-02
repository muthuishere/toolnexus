# toolnexus (Rust)

The Rust port of [toolnexus](../README.md), async on tokio: MCP servers, agent skills, native
and HTTP tools, the built-in coding tools and remote ACP agents behind one `Tool` interface,
schema adapters for OpenAI / Anthropic / Gemini, and a unified client with the tool-calling
loop. It is held to conformance tier **`core`** (`conformance/options_manifest.json`): every
SPEC §0 obligation, with the gaps listed at the end of this page.

```toml
[dependencies]
toolnexus = "0.22.0"
# toolnexus = { version = "0.22.0", default-features = false }   # MCP over stdio only, no `serve` (A2A / MCP server)
tokio = { version = "1", features = ["full"] }
```

## Zero to agent

```rust
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{McpConfigInput, ToolkitOptions, create_toolkit};

let tk = create_toolkit(ToolkitOptions {
    mcp_config: Some(McpConfigInput::Path("mcp.json".into())),
    skills_dir: vec!["skills".into()],
    ..Default::default()
}).await?;
let client = create_client(ClientOptions {
    base_url: "https://openrouter.ai/api/v1".into(),   // key: OPENROUTER_API_KEY (by host)
    style: Style::OpenAI,
    model: "openai/gpt-4o-mini".into(),
    ..Default::default()
});
let result = client.run("Say hello using the hello-world skill", &tk).await?;
println!("{}", result.text);
tk.close().await;
```

The environment key is used only for the host it belongs to (`api.openai.com` →
`OPENAI_API_KEY`, `api.anthropic.com` → `ANTHROPIC_API_KEY`, `openrouter.ai` →
`OPENROUTER_API_KEY`). Any other `base_url` needs `api_key` or `api_key_env`.

## Examples

All under `rust/examples/`, run with `cargo run --example <name>`:

| example | what | needs |
|---|---|---|
| `basic` | toolkit over the shared `examples/` fixtures: MCP status, tools, skill catalog, OpenAI schema, a `skill` call. Same output as `golang/examples/basic` (JSON key order aside) | `npx` for the MCP server |
| `agent` | a real model running skills + builtins + a native tool | `OPENROUTER_API_KEY` |
| `native_http` | `define_tool` and `http_tool` through the toolkit | network only if you pass a URL |
| `streaming` | `client.stream` with tool calls, offline (in-process model) | nothing |
| `pending` | the builtin `question` suspending, with and without `wait_for` (§10) | nothing |
| `acp` | an ACP coding agent as the client's model, streaming its own tool calls | an agent command, or the bundled fake |
| `agents` | the §7D runtime: a coordinator fans out to sub-agents via `task`, then a child parks durably and is resumed | nothing |
| `prompt_cache` | prompt cache + tool tiers against a local mock Anthropic: a stable tools array, `call_tool` unwrapped for hooks, cache-read usage. Mirrors `golang/examples/promptcache` | nothing |

## Toolkit (§4)

`create_toolkit(ToolkitOptions { mcp_config, skills_dir, skills, skill_provider, skills_filter,
skill_sample_limit, extra_tools, builtins, agents, disable_tools, disable_skills, wait_for })` assembles
MCP → skill → builtin → A2A agents → extra tools, first name wins, and an extra tool shadows a builtin of the
same name. `tools()`, `get`, `execute`, `skills_prompt`, `mcp_status`, `to_openai` /
`to_anthropic` / `to_gemini`, `register` (safe mid-turn) and `close`.

## Built-in tools (§4A)

On by default: `bash, read, write, edit, grep, glob, webfetch, question, apply_patch,
todowrite`, with the same names, schemas and outputs as the other ports. `BuiltinsConfig`
carries the toggles (`enabled` / `disabled`, per-tool `tools`), the host boundary (`shell`,
`base_dir`, `confine_to_base_dir`, `kill_grace_ms`) and the opt-in extras: `background`
(`bash` `run_in_background` + `bash_output` / `kill_shell`), `web_search` (your provider) and
`notebook_edit`. `bash` kills its whole process group on timeout or cancel (TERM, grace, KILL).
Every builtin carries `ToolHints`. `grep` takes `context` / `before` / `after`; `read` and
`grep` report `truncated` in metadata.

## Client (§8)

`ClientOptions` has every option the other ports have: `base_url`, `style` (`OpenAI` |
`Anthropic`), `model`, `api_key`, `api_key_env`, `headers`, `system_prompt`, `max_turns`,
`hooks` (`before_llm`, `after_llm`, `before_tool`, `after_tool`), `retries`, `retry_base_ms`,
`retryable_statuses` (Retry-After is honoured), `timeout_ms`, `store`, `on_metric`, `wait_for` /
`wait_for_ctx`, `request_params`, `body_transform`, `http_client`, `on_error`, `prompt_cache`,
`tool_tiers`. `run`, `ask` (conversation memory by id), `stream` (an
`mpsc::UnboundedReceiver<StreamEvent>`), `list_models`, and `create_in_process_client` for a
model you run yourself. One message's tool calls run concurrently, each with its
`ToolContext.call_id`. `Usage` reports `cache_read_tokens` / `cache_write_tokens`.

## Suspension (§10)

A tool returns `pending(Request { kind, prompt, .. })` (or `auth_required(url, prompt)`). With
`wait_for`, the loop asks the host, then re-runs the tool once with `ToolContext.answer`. Without
it, `run` returns `status: "pending"` with the request, and a stream emits a `pending` event.

### Relay tools and durable resume

`toolnexus::relay::relay_tool(name, description, schema)` declares a tool the HOST executes: the
call surfaces as a `kind: "tool_call"` Request (every relay call of the turn rides one Request, in
call order, with the provider call id). With `wait_for` the host's `data.output` becomes the tool
result inline; without it the run halts `pending` and `client.run_with_answer(cancel, tk,
history, &pending, &answer)` (or `ask_with_answer` by conversation id) resumes it, replacing the
halt's placeholders so every call has one result. `answer_output`, `answer_declined` and
`relay_answer` build the Answer. A relay tool may not share a builtin's name.

## Agents (§7D–§7F)

`toolnexus::agents`: `agents::new(name, Spec { does, tools, soul, soul_file, team, budget, model,
wait_for, hooks, on_metric, guardrails, completion, .. })`, `agent.run(opts, prompt)`,
`agent.as_tool(opts)` / `as_tool_with(per_call)`, and `agent.loop_(client_opts, toolkit)` with
the completion gate (`all_todos_done`) and first-deny-wins guardrails. `Runtime::new(Options {
http_client + llm | in_process, registry, inbox_cap, max_concurrent_turns, shutdown, store,
clock, hooks, on_metric, wait_for })` gives the six verbs (`spawn`, `post`, `wake`, `wait`,
`interrupt`, `close`) plus `run_turn`, `resume`, `list`, `inspect`, `trace`: bounded inboxes,
per-parent concurrency with FIFO slot transfer, a global turn gate around only the provider call,
hierarchical budgets with a named `limit` on every stop, nearest-interpreter escalation, and
durable pending with `data.path` and task reattachment on resume. Personas: `from_dir`,
`compose_soul`, `memory_tool`, `start_agent` (heartbeat on the runtime `Clock`). Compaction:
`compactor(CompactorOptions { max_tokens, keep_tail, summarize, count_tokens, flush_to_memory,
on_compact })` as a `before_llm` hook. Tests mirror `golang/agents` (trace strings and the
unset-run golden are byte-identical).

## HTTP tools (§7)

`toolnexus::http::http_tool(HttpToolOptions { name, url, method, headers, query, body,
input_schema, .. })`: `{placeholder}` URL segments, `${ENV}` headers that are never logged, and
`HTTP <status>: <body>` errors.

## A2A agents (§7A)

`ToolkitOptions.agents` (or a top-level `agents` block in the MCP config, or
`tk.add_agent(card_url)` at runtime) turns every skill on a remote Agent Card into a tool named
`<card name>_<skill id>` with `source: ToolSource::A2a`. A call sends one non-blocking
`SendMessage`, polls `GetTask` every `poll_every_ms` until the Task is terminal or the budget
(`timeout_ms`, or `ToolContext.timeout_ms`) runs out, and returns the artifact text; `metadata`
carries `agent`, `taskId`, `state`, `polls`, `ms`. `${ENV}` headers are expanded at call time and
never logged. Cancelling the call's token stops it, even mid-request (`A2A task <id> canceled`).

```rust
use toolnexus::a2a::Agent;
let tk = create_toolkit(ToolkitOptions {
    agents: vec![Agent::new("https://reviewer.example/.well-known/agent-card.json")],
    ..Default::default()
}).await?;
```

## Serve: A2A agent and MCP server (§7B, §7C; `serve` feature, on by default)

```rust
use toolnexus::mcp_serve::McpServeConfig;
use toolnexus::serve::{A2aConfig, ServeOptions};

let handle = tk.serve("127.0.0.1:0", ServeOptions {
    client: Some(client),                                         // fulfils A2A Tasks
    a2a: Some(A2aConfig { name: "video-desk".into(), ..Default::default() }),
    mcp: Some(McpServeConfig::default()),                         // POST /mcp
    ..Default::default()
}).await?;
println!("{}", handle.url);   // dropping the handle (or handle.stop()) stops the server
```

With `a2a`, `GET /.well-known/agent-card.json` publishes a card built from the toolkit's skills
(never raw tools) and a JSON-RPC `POST` answers `SendMessage` / `GetTask`. Tasks run in the
background through `client.run`, or `client.ask` keyed by the message's `contextId`. A suspended
run is `input-required` with the prompt. `A2aConfig.store` is `"memory"` (default),
`"file:<dir>"` or a custom `TaskStore`. With `mcp`, a stateless streamable-HTTP MCP server (rmcp)
at `/mcp` exposes every toolkit tool, filtered by `McpServeConfig.tools`; §1B parts follow the text
block as native MCP content. Neither profile ⇒ every request is a 404. Inline options win over the
config's top-level `a2a` / `mcpServer` blocks. `on_task` / `on_call` observe each Task and call.

## ACP client

Drive a local coding agent (Claude Code via `claude-code-acp`, `gemini --experimental-acp`,
`codex-acp`, goose, ...) over the Agent Client Protocol: one warm session per agent process.

```rust
use toolnexus::acp::{AcpClient, AcpOptions, PermissionOutcome, permission_handler};

let opts = AcpOptions::new("npx")
    .args(["-y", "@zed-industries/claude-code-acp"])
    .cwd("/abs/project")
    .on_permission(permission_handler(|req| async move {
        // show req.tool_call and req.options to the user; answer one option id
        match req.first_allow() {
            Some(o) => PermissionOutcome::Selected(o.option_id.clone()),
            None => PermissionOutcome::Cancelled,
        }
    }));
let agent = AcpClient::load(opts).await?;

let mut turn = agent.prompt("Explain src/main.rs").await?;
while let Some(update) = turn.next().await {
    // update.kind: agent_message_chunk | agent_thought_chunk | tool_call |
    //              tool_call_update | plan | ...; update.update is the raw object
}
let result = turn.finish().await?; // result.text = message chunks only; result.stop_reason
agent.close().await;              // SIGTERM the process group, then SIGKILL after kill_grace
```

- `initialize` waits up to 120 s by default (`initialize_timeout`), because adapters run
  through `npx` start slowly the first time.
- Without `on_permission`, a permission request is answered with the first `allow*`
  option, as in the Go port. A handler slower than `permission_timeout` (30 s) is answered
  `cancelled`, and the turn fails with `AcpError::PermissionTimeout`.
- `agent.cancel()` sends `session/cancel`; the turn ends with stop reason `cancelled`.
- An agent that exits mid-turn ends the turn with `AcpError::AgentExited`.
- Requests the client does not serve (for example `fs/*`) get JSON-RPC `-32601`.
- Turns on one session are serialized.
- `agent.generate(&messages)` is Go's `Generate` seam (full transcript rendered, plus
  the supersedes marker).

- The agent runs in its own process group (POSIX) or Job Object (Windows), so `close`
  also stops what an `npx` adapter started. On Windows there is no graceful signal, so
  the job is terminated at once. The Windows path is compile-checked but has not been run.
- `AcpError::AgentExited { code }` carries the exit code when the agent exits.

## Skills

```rust
let src = toolnexus::skills::load_skills(&["./skills"]);
let system_extra = src.prompt();   // the catalog for the system prompt
let tool = src.tool;               // the one `skill` tool
```

The output is byte-identical to the other ports. This was checked by running Go and Rust over
the same 16-skill corpus (Unicode path, CRLF, symlinked dir, nested duplicates, sample cap)
and comparing the prompt, every `skill` tool output and the skip list. Also available:
skills as data (`SkillDef`), an allowlist, `list_skills` with typed skip reasons, and a
sample cap.

## MCP

```rust
let cfg = toolnexus::mcp::parse_mcp_config_file("mcp.json")?;  // mcpServers | servers | mcp
let src = toolnexus::mcp::load_mcp(&cfg).await;                // never fails as a whole
// src.tools: `server_tool` names; src.status: connected | disabled | failed
src.close().await;
```

It is built on the official `rmcp` SDK. It supports stdio servers (own process group / Job
Object) and streamable HTTP (`mcp-http` feature, on by default; `${ENV}` headers, never
logged). A failing server is isolated, and the per-server `tools` allowlist works. Each call's
timeout is an idle timeout: progress for that call resets it. Cancelling `ToolContext.cancel`
ends a call at once. A remote server that does not speak streamable HTTP is retried over legacy
SSE. Non-text content becomes `ToolResult.parts`, tool annotations become `ToolHints`,
`resource_tools()` lists and reads resources, and `load_mcp_with(cfg, McpLoadOptions {
on_server, wait_for })` reports each server as soon as it is ready. With a `wait_for` (or
`ToolkitOptions.wait_for`) the client advertises elicitation and bridges a server's
`elicitation/create` onto §10: form → `kind:"input"` (schema in `data.schema`), URL →
`kind:"authorization"`; `ok` → accept, `reason:"declined"` → decline, else cancel.

## Tools and adapters

`define_tool` / `define_typed_tool` (native tools: `Ok` ⇒ output, `Err` ⇒ `isError`), and
`to_openai` / `to_anthropic` / `to_gemini`.

## Translate, classifier and judge

`Client::translate` (§11) is the one-turn translator: OpenAI-shaped messages and tools in,
exactly one provider call, an OpenAI-shaped result out, no tool executed and no state kept.
`create_classifier` (§8B) is the judgment seam (styles `SystemOne` / `Llm` / `Custom` /
`Static`; noul, choice and score questions; byte-identical canonical requests), with
`toolnexus::judge` for `ask` / `gate` / `Policy` / `Tape` and `toolnexus::judge::batteries` for
`ToolGuard`, `ToolRelevance`, `SkillRelevance`, `ToolResultFilter`, `IsComplete`, `AgentRouter`,
`ContentGuard` and the opt-in `ModelRouter` (hooks via `as_hook`). All run against the shared
`examples/judge/` fixtures.

## Tests

`cargo test` is hermetic: a scripted fake ACP agent (`examples/fake_acp_agent.rs`), local fake
HTTP / MCP / SSE servers, no network and no real model. CI runs `cargo fmt --check`,
`cargo clippy --all-targets -- -D warnings`, `cargo test` and `cargo test --no-default-features`.

## Not done yet (tier `full`)

A Gemini client style
(Gemini is a schema adapter, as in Go). The Windows code paths compile but have not been run.
Tracked in `openspec/changes/add-rust-port`.
