//! swiftide (bosun-ai/swiftide 0.34, swiftide-agents) benchmark runner. Same mock
//! LLM + shared stdio MCP server as every other runner.
//!
//!   cold init   = spawn stdio MCP server (rmcp child process) + initialize
//!                 (McpToolbox::try_from_transport) + OpenAI client (api_base -> mock,
//!                 Chat Completions) + first tools/list
//!   per-request = build a fresh Agent (swiftide agents are stateful: history
//!                 accumulates, so one agent per question is the idiomatic shape) +
//!                 agent.query(question). The toolbox is shared (one MCP connection);
//!                 swiftide re-lists the toolbox's tools when each agent starts.
use std::time::Instant;

use rmcp::transport::TokioChildProcess;
use serde_json::json;
use swiftide::agents::Agent;
use swiftide::agents::tools::mcp::McpToolbox;
use swiftide::chat_completion::{ChatMessage, ToolBox};
use swiftide::integrations::openai::OpenAI;

const QUESTION: &str = "What's the weather in Paris and what is 2+2?";

fn arg(args: &[String], name: &str, default: &str) -> String {
    args.iter()
        .position(|a| a == &format!("-{name}") || a == &format!("--{name}"))
        .and_then(|i| args.get(i + 1).cloned())
        .unwrap_or_else(|| default.to_string())
}

async fn ask(llm: &OpenAI, tb: &McpToolbox) -> anyhow::Result<String> {
    let mut agent = Agent::builder()
        .llm(llm)
        .add_toolbox(tb.clone())
        .without_default_stop_tool()
        .build()?;
    agent.query(QUESTION).await?;
    let hist = agent.history().await?;
    Ok(hist.iter().rev().find_map(|m| match m {
        ChatMessage::Assistant(Some(t), _) => Some(t.clone()),
        _ => None,
    }).unwrap_or_default())
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let runs: usize = arg(&args, "runs", "30").parse()?;
    let warmup: usize = arg(&args, "warmup", "5").parse()?;
    let repo = std::env::var("BENCH_REPO").unwrap_or_default();
    let mcp_py = std::env::var("MCP_PYTHON").unwrap_or_else(|_| "python3".into());
    let mock = std::env::var("MOCK_URL").unwrap_or_else(|_| "http://127.0.0.1:8900".into());

    let t0 = Instant::now();
    let mut cmd = tokio::process::Command::new(&mcp_py);
    cmd.arg(format!("{repo}/benchmarks/mcp_server.py"));
    let tb = McpToolbox::try_from_transport(TokioChildProcess::new(cmd)?).await?;
    let tool_count = tb.available_tools().await?.len();
    let llm = OpenAI::builder()
        .api_base(format!("{mock}/v1"))
        .api_key("sk-mock-not-a-real-key")
        .default_prompt_model("mock-model")
        .build()?;
    let init_ms = t0.elapsed().as_secs_f64() * 1000.0;

    for _ in 0..warmup { ask(&llm, &tb).await?; }
    let mut lat = Vec::with_capacity(runs);
    let mut final_text = String::new();
    for _ in 0..runs {
        let s = Instant::now();
        final_text = ask(&llm, &tb).await?;
        lat.push(s.elapsed().as_secs_f64() * 1000.0);
    }
    println!("{}", json!({"framework":"swiftide-mcp","language":"rust","init_ms":init_ms,
        "tool_count":tool_count,"latencies_ms":lat,"final_text":final_text}));
    let mut tb = tb;
    let _ = tb.cancel().await;
    Ok(())
}
