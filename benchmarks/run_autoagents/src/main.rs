//! AutoAgents (liquidos-ai/AutoAgents 0.4) benchmark runner. Same mock LLM +
//! shared stdio MCP server as every other runner.
//!
//!   cold init   = autoagents-toolkit McpTools::from_config_object (spawn stdio
//!                 MCP server + initialize + tools/list) + OpenAI LLM (base_url ->
//!                 mock, api_mode ChatCompletions) + ReActAgent DirectAgent build
//!   per-request = agent.run(Task::new(question));
//!                 memory is cleared (untimed) before each run so every question
//!                 starts from a clean conversation like every other runner.
use std::sync::Arc;
use std::time::Instant;

use async_trait::async_trait;
use autoagents::core::agent::memory::SlidingWindowMemory;
use autoagents::core::agent::prebuilt::executor::ReActAgent;
use autoagents::core::agent::task::Task;
use autoagents::core::agent::{AgentBuilder, AgentDeriveT, AgentHooks, DirectAgent};
use autoagents::core::tool::ToolT;
use autoagents::llm::backends::openai::{OpenAI, OpenAIApiMode};
use autoagents::llm::builder::LLMBuilder;
use autoagents_toolkit::mcp::{McpConfig, McpProcessPolicy, McpServerConfig, McpToolWrapper, McpTools};
use serde_json::{Value, json};

const QUESTION: &str = "What's the weather in Paris and what is 2+2?";

#[derive(Debug, Clone)]
struct BenchAgent {
    tools: Vec<Arc<dyn ToolT>>,
}

impl AgentDeriveT for BenchAgent {
    type Output = String;
    fn description(&self) -> &str { "You are a helpful assistant." }
    fn output_schema(&self) -> Option<Value> { None }
    fn name(&self) -> &str { "bench_agent" }
    fn tools(&self) -> Vec<Box<dyn ToolT>> {
        self.tools.iter().map(|t| Box::new(McpToolWrapper::new(t.clone())) as Box<dyn ToolT>).collect()
    }
}

#[async_trait]
impl AgentHooks for BenchAgent {}

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
    let mut cfg = McpConfig::new();
    cfg.add_server(McpServerConfig::local("bench-tools",
        vec![mcp_py.clone(), format!("{repo}/benchmarks/mcp_server.py")]));
    // AutoAgents denies spawning local MCP processes by default; allow exactly ours.
    let policy = McpProcessPolicy::allow_commands([mcp_py.as_str()])?;
    let mcp = McpTools::from_config_object_with_process_policy(&cfg, policy).await?;
    let tools = mcp.get_tools().await;
    let tool_count = tools.len();
    let llm: Arc<OpenAI> = LLMBuilder::<OpenAI>::new()
        .api_key("sk-mock-not-a-real-key")
        .base_url(format!("{mock}/v1/"))
        .model("mock-model")
        .api_mode(OpenAIApiMode::ChatCompletions)
        .build()?;
    let handle = AgentBuilder::<_, DirectAgent>::new(ReActAgent::new(BenchAgent { tools }))
        .llm(llm)
        .memory(Box::new(SlidingWindowMemory::new(20)))
        .build()
        .await?;
    let agent = handle.agent;
    let init_ms = t0.elapsed().as_secs_f64() * 1000.0;

    // The ReAct loop carries tool results between turns through memory, so memory
    // is required; it is cleared before every run so each question starts clean.
    let memory = agent.memory().expect("memory");
    for _ in 0..warmup {
        memory.lock().await.clear().await?;
        agent.run(Task::new(QUESTION)).await?;
    }
    let mut lat = Vec::with_capacity(runs);
    let mut final_text = String::new();
    for _ in 0..runs {
        memory.lock().await.clear().await?;
        let s = Instant::now();
        let out = agent.run(Task::new(QUESTION)).await?;
        lat.push(s.elapsed().as_secs_f64() * 1000.0);
        final_text = out;
    }
    println!("{}", json!({"framework":"autoagents-mcp","language":"rust","init_ms":init_ms,
        "tool_count":tool_count,"latencies_ms":lat,"final_text":final_text}));
    Ok(())
}
