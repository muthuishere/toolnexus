# toolnexus

[![crates.io](https://img.shields.io/crates/v/toolnexus.svg)](https://crates.io/crates/toolnexus)
[![docs.rs](https://img.shields.io/docsrs/toolnexus)](https://docs.rs/toolnexus)
[![license](https://img.shields.io/crates/l/toolnexus.svg)](https://github.com/muthuishere/toolnexus/blob/main/LICENSE)

**Give any LLM the tools a coding agent has: MCP servers, agent skills, your own functions,
HTTP endpoints, remote agents and built-in shell/file tools, all behind one `Tool` type.**

To a model, an MCP tool, a skill, a Rust function and a remote agent are the same thing: a
name, a description and a JSON schema. toolnexus loads them all into one toolkit, emits the
schema for **OpenAI, Anthropic or Gemini**, and runs the tool-calling loop for you.

It is vendor-neutral, async on tokio, and behaves the same as the toolnexus libraries for
JavaScript, Python, Go, Java, C#, Elixir and Clojure. The same config gives the same tools and
the same output in every language.

📖 **[Documentation](https://muthuishere.github.io/toolnexus/)** ·
**[Rust API guide](https://muthuishere.github.io/toolnexus/api/rust/)** ·
**[Examples](https://github.com/muthuishere/toolnexus/tree/main/rust/examples)**

## Install

```toml
[dependencies]
toolnexus = "0.25"
tokio = { version = "1", features = ["full"] }
serde_json = "1"
```

## Quick start

An agent with the MCP servers from your `mcp.json`, the skills in `./skills`, and one Rust
function as a tool:

```rust
use serde_json::json;
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{McpConfigInput, ToolkitOptions, create_toolkit, define_tool};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let weather = define_tool(
        "get_weather",
        "Current weather for a city",
        Some(json!({"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]})),
        |args, _ctx| async move { Ok::<_, String>(format!("Sunny in {}", args["city"].as_str().unwrap_or("?"))) },
    );

    let tk = create_toolkit(ToolkitOptions {
        mcp_config: Some(McpConfigInput::Path("mcp.json".into())),
        skills_dir: vec!["skills".into()],
        extra_tools: vec![weather],
        ..Default::default()
    })
    .await?;

    let client = create_client(ClientOptions {
        base_url: "https://openrouter.ai/api/v1".into(), // reads OPENROUTER_API_KEY
        style: Style::OpenAI,
        model: "openai/gpt-4o-mini".into(),
        ..Default::default()
    });

    let result = client.run("What's the weather in Chennai?", &tk).await?;
    println!("{}", result.text);
    tk.close().await;
    Ok(())
}
```

An API key from the environment is sent only to the host it belongs to: `OPENAI_API_KEY` to
`api.openai.com`, `ANTHROPIC_API_KEY` to `api.anthropic.com`, `OPENROUTER_API_KEY` to
`openrouter.ai`. For any other `base_url` (Ollama, vLLM, a gateway), set `api_key` or
`api_key_env`.

## What's in the box

| | |
|---|---|
| **MCP servers** | Reads `mcp.json` (`mcpServers` / `servers` / `mcp`) and connects to stdio and streamable-HTTP servers on the official `rmcp` SDK. A failing server is isolated rather than failing the load. Idle timeouts are reset by progress, and MCP elicitation is supported. |
| **Agent skills** | Finds every `SKILL.md` and exposes one `skill` tool that loads a skill's instructions and files when the model needs them, following the open [Agent Skills](https://agentskills.io) format. |
| **Your functions** | `define_tool` / `define_typed_tool`: `Ok` becomes the output, `Err` becomes a tool error. |
| **HTTP tools** | `http_tool`: URL placeholders and `${ENV}` headers that are never logged. |
| **Built-in tools** | `bash`, `read`, `write`, `edit`, `grep`, `glob`, `webfetch`, `apply_patch`, `todowrite`, `question`, each with a toggle. Optional extras: background shells, web search and notebook editing. |
| **Remote agents** | Calls remote A2A agents as tools. Drives local coding agents (Claude Code, Gemini CLI, Codex, goose) over the Agent Client Protocol, with permission and update hooks. |
| **Client** | OpenAI and Anthropic styles. Parallel tool calls, hooks, streaming, retries that honour `Retry-After`, conversation memory, prompt caching, tool tiers, usage metrics, and model listing. |
| **Human in the loop** | A tool can pause the run and ask for input or authorization. With a `wait_for` handler the answer comes back inline; without one the run returns `pending` and you resume it later. |
| **Serve** | `tk.serve(addr, ..)` publishes the toolkit as an A2A agent and/or an MCP server; `tk.mcp_handler(..)` is an MCP server to mount on your own router, with per-request hooks. |
| **Agents** | Sub-agents, a runtime with budgets and durable resume, personas, conversation compaction, and a judge/classifier for guardrails and routing. |

## Classifier backends, images, cache and helpers

Every default below is a **proposed default, owner to confirm**
(`openspec/changes/add-classifier-backends`).

```rust
use std::{sync::Arc, time::Duration};
use serde_json::json;
use toolnexus::classifier::*;
use toolnexus::judge::noul;

let c = create_classifier(ClassifierOptions {
    backend: ClassifierBackend::OpenRouter,
    cache: Some(Arc::new(MemoryCache::new(Duration::from_secs(3600), 1000))),
    ..Default::default()
})?;
let photo = serde_json::to_value(toolnexus::ContentPart::from_path("mug.png")?)?;
let d = c.state(json!({"note": "listing", "photo": photo}))
    .evaluate(&[noul("red", "Is the object in `photo` red?")]).await?; // fails closed on an invalid reply
let rs = c.states(vec![json!("a"), json!("b")])
    .evaluate(&[noul("q", "…")]).await?;                                // Vec<Result<Decision, _>>, in order
```

- `backend`: `TypeSafe` (default) · `OpenRouter` (`typesafe/jev-1.14`) · `Cloudflare` (`/ai/run`
  envelope; `${CLOUDFLARE_ACCOUNT_ID}` in the URL expands at call time, errors name it unexpanded)
  · `Vercel` · `Custom` (requires `base_url` and `supports`). The table is data:
  `CLASSIFIER_BACKENDS`; `supports: Some(ClassifierSupports { images })` overrides it per
  classifier. `ClassifierBackend::parse` takes the wire spelling.
- Images (style `SystemOne` only): image parts in the state are lifted into `assets` (Cloudflare:
  data URLs). PNG/JPEG, 280–1568 px a side, ≤ 16 MiB each and 32 MiB per request — never resized.
  A backend without image support fails before any request with an error whose
  `.unsupported()` is the typed `ClassifierUnsupported`. A state with no content part is sent
  byte-identically to before. The state is a `serde_json::Value`, so **any** object shaped like a
  serialised `ContentPart` (`{"type":"text","text"}`, `{"type":"image","mimeType",…}`) inside a
  `SystemOne` state is treated as a part.
- `cache: Option<Arc<dyn ClassifierCache>>` (`get`/`set`, string values) — off when `None`; only
  valid replies are stored; keys follow the served model. A hit sets `decision.cached` and the
  `classifier.evaluate` event's `cached`. Every such event also carries `questions` and
  `questions_hash`.
- Helpers: `validate_decision(&questions, &decision)`; `judge::Thresholds` with
  `DEFAULT_THRESHOLDS` 0.8 / 0.2 / 0.6 (inclusive; `Bands` stays the `ask`/`gate` default);
  `chunk_questions(&questions, size)`.
- Open union: `judge::raw(name, kind, instructions, criteria)`; an answer of an unmodelled type
  decodes to `DecisionAnswer::Raw(RawAnswer { kind, value })`.
- `ClassifierStyle::Llm` is **deprecated** (`#[deprecated]`) — it still works unchanged, with no
  runtime warning.

## Embeddable MCP server

`tk.mcp_handler(cfg)` is an MCP server you mount on **your own** hyper / axum router, behind your
own TLS, CORS and OAuth (SPEC §7C.1). Every policy is a hook: `authenticate` (401 +
`www_authenticate`), `list_tools` per request (a tool outside it is never executed), `before_call`
(allow / deny / confirm — confirm asks the client over MCP elicitation, or returns your shaped
fallback), `on_call`, `server_info`, resources and prompts, pluggable session ids or `stateless`.
`handler.notify_tools_changed()` tells connected clients to re-list. Tools and hooks read the
caller with `mcp_request_context(&ctx)` (identity, headers, session id, `params._meta`).

```rust
use toolnexus::mcp_handler::{McpDecision, McpHandlerConfig, McpIdentity};

let handler = tk.mcp_handler(McpHandlerConfig {
    authenticate: Some(Arc::new(|rc| { let ok = rc.header("authorization") == "Bearer s3cret";
        Box::pin(async move { if ok { Ok(Arc::new("alice".to_string()) as McpIdentity) } else { Err("no".into()) } }) })),
    before_call: Some(Arc::new(|_, tool, _| Box::pin(async move {
        Ok(if tool.name == "delete_record" { McpDecision::confirm("Really delete?") } else { McpDecision::allow() }) }))),
    ..Default::default()
});
// hyper: `handler` is a hyper::service::Service; any framework: `handler.handle(req).await`.
```

Run `cargo run --example mcp_handler` for a self-contained demo (bearer auth, per-user tools,
confirm via elicitation and via a nonce fallback).

### MCP 2026-07-28 (opt-in)

List `"2026-07-28"` in `protocol_versions` and the same handler also speaks the stateless
2026-07-28 wire (SPEC §7C.2): `server/discover`, header validation, `resultType` + serverInfo
`_meta`, and MRTR — a confirm or a §10 tool suspension comes back as `input_required` and the
client's retry carries the answer (the tool sees it as `ctx.answer`). `["2026-07-28"]` serves only
that wire; `["2026-07-28", "2025-06-18"]` serves both on one mount. It is never a default, and
`initialize` never negotiates it. `list_skills` publishes skills through the skills extension
(`skills/list`, `skills/get`, `resources/read` of `skill://` URIs); `load_mcp_skills(dir)` builds
the list from a skills folder.

`McpServerAuth` makes the handler an OAuth protected resource: `auth.authenticate()` is the
`authenticate` hook (bearer RS256 JWT checked against the issuer's JWKS, or your own `verify`;
401 `invalid_token` / 403 `insufficient_scope` with the exact challenge, per-tool scopes on the
2026 wire) and `auth.metadata()` serves `/.well-known/oauth-protected-resource`. The caller is
`rc.identity_as::<McpPrincipal>()`. Any `authenticate` hook may fail with an `McpAuthError`
(status + challenge); other errors stay a plain 401.

```rust
use toolnexus::mcp_handler::{load_mcp_skills, McpHandlerConfig, McpServerAuth};

let auth = McpServerAuth { issuer: "https://login.example".into(),
    scopes: [("*".into(), vec!["mcp".into()])].into(), ..Default::default() };
let handler = tk.mcp_handler(McpHandlerConfig {
    protocol_versions: vec!["2026-07-28".into(), "2025-06-18".into()],
    authenticate: Some(auth.authenticate()),
    list_skills: Some(Arc::new(|_| Box::pin(async {
        load_mcp_skills("skills").map_err(|e| e.to_string()) }))),
    ..Default::default()
});
let prm = auth.metadata(); // mount at /.well-known/oauth-protected-resource[/mcp]
```

Bring your own authorization server: `McpServerAuth` verifies its tokens. The auto-approving dev authorization
server the suite uses is test tooling (`tests/common_dev_auth`), not part of the library.

## Media generation

Generate images, video and speech (plus transcription, voice cloning and talking-head video)
from a **JSON style** instead of provider code. Async jobs are started and polled for you. The same
verbs become agent tools (`generate_image`, `generate_video`, `generate_speech`, …), and whatever
they produce lands in `RunResult.files`. `examples/media-styles/a2e.json` ships first and reads
`A2E_API_KEY` at call time. Its result URLs expire in 3–30 days, so save what you keep. SPEC §7G;
guide: <https://muthuishere.github.io/toolnexus/media/>.

```rust
use toolnexus::{ImageInput, MediaOptions, ToolkitOptions, VideoInput, create_media, create_toolkit, load_media_style};

let media = create_media(load_media_style("examples/media-styles/a2e.json")?, MediaOptions::default());
let img = media.image(ImageInput { prompt: "a red fox in fresh snow".into(),
    width: Some(1024), height: Some(768), ..Default::default() }).await?;
let vid = media.video(VideoInput { prompt: "the fox blinks".into(),
    image: Some(img.files[0].url.clone()), seconds: Some(5), ..Default::default() }).await?;

let tk = create_toolkit(ToolkitOptions { media: Some(media), ..Default::default() }).await?; // generate_image, …
let res = agent.run("Draw a fox, then animate it.", &tk).await?;
for f in &res.files { println!("{} {} {}", f.from, f.mime_type, f.url); }
```

## MCP 2026-07-28 + OAuth

Every MCP server is probed with `server/discover` first (SPEC §2.2). A server that speaks
`2026-07-28` is used through toolnexus's own client (no SDK): stdio or stateless HTTP, MRTR
`input_required` rounds through §10 `wait_for` (or a resumable `pending` result without one), the
skills extension (remote skills join the `skill` tool), and opt-in lazy tool search
(`"toolSearch": true` ⇒ `<server>_search_tools` + `<server>_call_tool`). Anything else falls back to
the frozen rmcp path, unchanged.

Remote servers get OAuth 2.1 (§2A): PRM → AS metadata → pre-registered `clientId` / `clientIds` or a
Client ID Metadata Document (never DCR) → PKCE S256 + `resource` → token, refresh, step-up.

```json
{"mcpServers": {"gh": {"url": "https://mcp.example.com/mcp",
  "auth": {"clientId": "YOUR_CLIENT_ID", "mode": "auto"}}}}
```

`McpLoadOptions` / `ToolkitOptions` take `token_store` (default: in memory) and `open_url` (default:
the OS opener). `"mode": "pause"` (+ `redirectUri`) raises a §10 `authorization` request instead;
answer it with `Answer { ok: true, data: {"callbackUrl": "<redirected URL>"} }`.

Gaps: on the frozen fallback the token is attached as a **static** `Authorization` header at connect
(rmcp takes headers only then), so a fallback server's token is not refreshed mid-session. The 2026
HTTP path does not need the `mcp-http` feature (it uses `reqwest`, always present); only the remote
fallback does. `resource_tools()` covers fallback servers only.

## Agents via ACP

toolnexus ships no per-agent client (SPEC §8C, ADR 0041). Three vendor-neutral pieces compose
into one: `toolkit.as_mcp_server(None)` serves your tools on `127.0.0.1:<ephemeral>/mcp`
behind a fresh bearer token (`{url, token}`, `close()`); `AcpClient::load(AcpOptions)` spawns
any ACP agent, passing `mcp_servers` (raw JSON, verbatim), `session_meta`, `client_caps_meta`
and `model` straight through; and `acp_generate` / `acp_generate_stream` plug it into
`create_in_process_client`, so `stream` yields `text` deltas as they arrive plus `thought` /
`tool_call` / `tool_result` events `via: "acp"`.

```rust
let srv = tk.as_mcp_server(None).await?;
let mut o = AcpOptions::new("my-acp-agent");
o.mcp_servers = vec![json!({"type": "http", "name": "toolnexus", "url": srv.url,
    "headers": [{"name": "Authorization", "value": format!("Bearer {}", srv.token)}]})];
let acp = Arc::new(AcpClient::load(o).await?);
let client = create_in_process_client(InProcessOptions {
    generate_stream: Some(acp_generate_stream(acp.clone())), ..Default::default() });
```

## Cargo features

| feature | default | what it adds |
|---|---|---|
| `mcp-http` | on | MCP over streamable HTTP. Without it you get stdio MCP servers only. |
| `serve` | on | `Toolkit::serve` (the A2A agent and the MCP server) and `Toolkit::mcp_handler`. |

For a lean build, use `toolnexus = { version = "0.25", default-features = false }`.

## More examples

The [`examples/`](https://github.com/muthuishere/toolnexus/tree/main/rust/examples)
directory has runnable programs. Run them with `cargo run --example <name>` from a clone:

- `basic`: load MCP servers and skills, print the tools and their OpenAI schema.
- `agent`: a real model using skills, the built-in tools and a native tool.
- `streaming`: stream text and tool calls.
- `pending`: pause for a human answer, then resume.
- `acp`: use a local coding agent as the model.
- `agents`: a coordinator fanning out to sub-agents.
- `prompt_cache`: prompt caching and tool tiers.
- `mcp_handler`: the embeddable MCP server mounted on your own hyper server.

## Status

The Rust crate passes the same conformance suite as the other seven languages and covers all
of their options. Known gaps: there is no Gemini *client* style yet (Gemini schemas are
supported), and the Windows code paths compile but have not been tested on Windows.

## License

MIT. See [LICENSE](https://github.com/muthuishere/toolnexus/blob/main/LICENSE).
