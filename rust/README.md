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
toolnexus = "0.23"
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
| **Serve** | `tk.serve(addr, ..)` publishes the toolkit as an A2A agent and/or an MCP server. |
| **Agents** | Sub-agents, a runtime with budgets and durable resume, personas, conversation compaction, and a judge/classifier for guardrails and routing. |

## Media generation

Generate images, video, speech and music (plus transcription, voice cloning and talking-head
video) through **named media clients**: API clients `A2EMediaClient`, `ElevenLabsMediaClient`,
`OpenAIMediaClient`, `OpenRouterMediaClient`, `FalMediaClient`, and CLI clients `GrokMediaClient`,
`CodexMediaClient`. `create_media` takes them in priority order: each verb goes to the first client
that supports it, and moves on to the next **only** on `MediaError::Unavailable` (no key, no credits,
CLI missing). Keys are read from the client's env var (`FAL_API_KEY`, `OPENAI_API_KEY`, …) at call
time and never logged. SPEC §7G; guide: <https://muthuishere.github.io/toolnexus/media/>.

```rust
use toolnexus::{A2EMediaClient, ElevenLabsMediaClient, FalMediaClient, GrokMediaClient, ImageInput,
    IntoMediaClient, MediaClientConfig, SpeechInput, TalkInput, VideoInput, create_media};

let cfg = MediaClientConfig::default(); // gather: download to <os temp>/toolnexus-media
let media = create_media([
    FalMediaClient::new(cfg.clone()).into_media_client(),        // image, video (FAL_API_KEY)
    GrokMediaClient::new(cfg.clone()).into_media_client(),       // fallback when fal is unavailable (prompt only)
    ElevenLabsMediaClient::new(cfg.clone()).into_media_client(), // speech, music, transcribe, cloneVoice
    A2EMediaClient::new(cfg).into_media_client(),                // talk
])?;
let img = match media.image(ImageInput { prompt: "a red fox in fresh snow".into(), ..Default::default() }).await {
    Err(e) if e.is_unavailable() => return Err(format!("every image client is unavailable: {e}").into()),
    r => r?,
};
let face = img.files[0].path.clone();
let vid = media.video(VideoInput { prompt: "the fox blinks".into(), image: Some(face.clone()),
    seconds: Some(5), ..Default::default() }).await?;
let voice = media.speech(SpeechInput { text: "Hello from the fox.".into(), ..Default::default() }).await?;
let talk = media.talk(TalkInput { image: face, audio: Some(voice.files[0].path.clone()), ..Default::default() }).await?;
println!("{} {}", vid.files[0].path, talk.files[0].path);
```

The typed verbs are `image`, `video`, `speech`, `music`, `transcribe` (→ `MediaResult.text`),
`clone_voice` (voice id in `text`) and `talk`; each has a `*_with(input, &CancellationToken)` twin.
Optional fields are `Option<_>`; `generate(verb, args)` is the raw call.

**Config** — every built-in client takes one `MediaClientConfig { model, models, key, url, command,
r#loop, gather, http_client }`. `gather.mode` is `GatherMode::Url` (keep provider URLs),
`Download` (default; save under `dir`, capped by `max_bytes`) or `Inline` (base64 `data`).
`MediaLoop { interval_ms, timeout_ms, on_progress }` bounds async jobs.

**As agent tools** — put the router on the toolkit and each supported verb becomes a tool
(`generate_image`, `generate_video`, `generate_speech`, …). Whatever they produce lands in
`RunResult.files`; only image parts are shown to the model.

```rust
let tk = create_toolkit(ToolkitOptions { media: Some(media), ..Default::default() }).await?;
let res = agent.run("Draw a fox, then animate it.", &tk).await?;
for f in &res.files { println!("{} {} {} {}", f.from, f.kind, f.mime_type, f.url); }
```

**Your own provider** — implement `MediaClient` (`name`, `supports`, and only the verbs you serve; the
rest default to failing). Return `MediaError::unavailable("…")` to let the router fall through.

```rust
struct Stock;
impl MediaClient for Stock {
    fn name(&self) -> &str { "stock" }
    fn supports(&self, verb: &str) -> bool { verb == "image" }
    fn image<'a>(&'a self, _args: MediaArgs, _ctx: &'a MediaCtx) -> MediaFuture<'a> {
        Box::pin(async { Ok(MediaResult { status: "completed".into(), files: vec![MediaFile {
            kind: "image".into(), mime_type: "image/png".into(),
            url: "https://example.com/fox.png".into(), ..Default::default() }], ..Default::default() }) })
    }
}
```

Advanced: `load_media_style` + `StyleMediaClient::new` drive any HTTP provider from a JSON style.
Run the example: `cargo run --example media`. It uses, in order, the `grok` / `codex` CLIs when on
`PATH`, then `ELEVENLABS_API_KEY`, `OPENAI_API_KEY`, `FAL_API_KEY`, `A2E_API_KEY` when set: an image,
ElevenLabs speech (`MEDIA_VOICE` or the default voice), and the `generate_image` tool; Grok video
only with `MEDIA_VIDEO=1`, paid video/talk only with `MEDIA_PAID=1`. It prints client, verb,
path/URL, mime type, size and provider-reported `usage`, exits non-zero if any step fails, and exits
0 with a hint when no client is available.

## Cargo features

| feature | default | what it adds |
|---|---|---|
| `mcp-http` | on | MCP over streamable HTTP. Without it you get stdio MCP servers only. |
| `serve` | on | `Toolkit::serve`: the A2A agent and the MCP server. |

For a lean build, use `toolnexus = { version = "0.23", default-features = false }`.

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

## Status

The Rust crate passes the same conformance suite as the other seven languages and covers all
of their options. Known gaps: there is no Gemini *client* style yet (Gemini schemas are
supported), and the Windows code paths compile but have not been tested on Windows.

## License

MIT. See [LICENSE](https://github.com/muthuishere/toolnexus/blob/main/LICENSE).
