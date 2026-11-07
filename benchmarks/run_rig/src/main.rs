//! rig (0xPlaygrounds/rig, rig 0.46) benchmark runner. Same mock LLM + shared
//! stdio MCP server as every other runner.
//!
//!   cold init   = spawn stdio MCP server (rmcp child process) + initialize +
//!                 tools/list (rig-rmcp McpConnection::tools) + OpenAI Chat
//!                 Completions model (base url -> mock) + AgentBuilder
//!   per-request = agent.prompt(question).await
use std::time::Instant;

use rig::prelude::*;
use rig::providers::openai::OpenAIConfig;
use rig::tool::rmcp::McpConnection;
use rmcp::ServiceExt;
use rmcp::model::ClientConfig;
use rmcp::transport::TokioChildProcess;
use serde_json::json;

const QUESTION: &str = "What's the weather in Paris and what is 2+2?";

fn arg(args: &[String], name: &str, default: &str) -> String {
    args.iter()
        .position(|a| a == &format!("-{name}") || a == &format!("--{name}"))
        .and_then(|i| args.get(i + 1).cloned())
        .unwrap_or_else(|| default.to_string())
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    let runs: usize = arg(&args, "runs", "30").parse()?;
    let warmup: usize = arg(&args, "warmup", "5").parse()?;
    let repo = std::env::var("BENCH_REPO").unwrap_or_default();
    let mcp_py = std::env::var("MCP_PYTHON").unwrap_or_else(|_| "python3".into());
    let mock = std::env::var("MOCK_URL").unwrap_or_else(|_| "http://127.0.0.1:8900".into());

    let t0 = Instant::now();
    let mut cmd = tokio::process::Command::new(&mcp_py);
    cmd.arg(format!("{repo}/benchmarks/mcp_server.py"));
    let conn = McpConnection::new(ClientConfig::default().serve(TokioChildProcess::new(cmd)?).await?);
    let mut tools = Vec::new();
    for t in conn.tools().await? {
        tools.push(rig::tool::DynamicTool::try_from(t)?);
    }
    let tool_count = tools.len();
    let provider = OpenAIConfig::new("sk-mock-not-a-real-key").with_base_url(format!("{mock}/v1")).client();
    let model = provider.chat("mock-model");
    let agent = AgentBuilder::new(model).dynamic_tools(tools).default_max_turns(5).build();
    let init_ms = t0.elapsed().as_secs_f64() * 1000.0;

    for _ in 0..warmup { agent.prompt(QUESTION).await?; }
    let mut lat = Vec::with_capacity(runs);
    let mut final_text = String::new();
    for _ in 0..runs {
        let s = Instant::now();
        let r = agent.prompt(QUESTION).await?;
        lat.push(s.elapsed().as_secs_f64() * 1000.0);
        final_text = r.output().to_string();
    }
    conn.close().await;
    println!("{}", json!({"framework":"rig-mcp","language":"rust","init_ms":init_ms,
        "tool_count":tool_count,"latencies_ms":lat,"final_text":final_text}));
    Ok(())
}
