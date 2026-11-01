# toolnexus (Rust) — in progress

The Rust port of [toolnexus](../README.md), async on tokio. It is being built in phases
(`openspec/changes/add-rust-port`). Done: the **ACP client**, **skills**, the **MCP
client** and the **core `Tool` types** with native tools and schema adapters. Next: the
toolkit, builtins and the client loop. It is not published yet.

```toml
toolnexus = { path = "../toolnexus/rust" }                                   # everything
toolnexus = { path = "../toolnexus/rust", default-features = false }         # no HTTP/TLS: stdio MCP only
```

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
ends a call at once. Gaps compared with Go: no legacy-SSE fallback for remote servers, and no
images or other non-text content parts.

## Tools and adapters

`define_tool` / `define_typed_tool` (native tools: `Ok` ⇒ output, `Err` ⇒ `isError`), and
`to_openai` / `to_anthropic` / `to_gemini`.

## Tests

`cargo test` is hermetic. It runs against a scripted fake agent (`examples/fake_acp_agent.rs`);
there is no network and no real agent CLI.
