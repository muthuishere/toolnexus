//! toolnexus Rust port benchmark runner. Same mock LLM + shared stdio MCP server
//! as every other runner; prints the raw contract line (latencies_ms + final_text).
//!
//!   cold init   = create_toolkit (stdio MCP connect + tools/list, skills glob) + create_client
//!   per-request = client.run(question, &tk)
use std::time::Instant;

use serde_json::{Value, json};
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{BuiltinsConfig, McpConfigInput, ToolkitOptions, create_toolkit, define_tool};

const QUESTION: &str = "What's the weather in Paris and what is 2+2?";

fn arg(args: &[String], name: &str, default: &str) -> String {
    args.iter()
        .position(|a| a == &format!("-{name}") || a == &format!("--{name}"))
        .and_then(|i| args.get(i + 1).cloned())
        .unwrap_or_else(|| default.to_string())
}

fn int(v: &Value, k: &str) -> i64 {
    v.get(k).and_then(|x| x.as_f64()).unwrap_or(0.0) as i64
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let config = arg(&args, "config", "mcp");
    let runs: usize = arg(&args, "runs", "30").parse().unwrap();
    let warmup: usize = arg(&args, "warmup", "5").parse().unwrap();
    let repo = std::env::var("BENCH_REPO").unwrap_or_default();
    let mcp_py = std::env::var("MCP_PYTHON").unwrap_or_else(|_| "python3".into());
    let mock = std::env::var("MOCK_URL").unwrap_or_else(|_| "http://127.0.0.1:8900".into());

    let int_schema = json!({"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]});
    let off = Some(BuiltinsConfig { enabled: Some(false), ..Default::default() });

    let t0 = Instant::now();
    let mut opts = ToolkitOptions { builtins: off, ..Default::default() };
    if config == "native" {
        opts.extra_tools = vec![
            define_tool("get_weather", "Get the current weather for a city.",
                Some(json!({"type":"object","properties":{"city":{"type":"string"}},"required":["city"]})),
                |a: Value, _| async move {
                    Ok::<_, String>(format!("The weather in {} is Sunny, 22C.", a["city"].as_str().unwrap_or("")))
                }),
            define_tool("add", "Add two integers.", Some(int_schema.clone()),
                |a: Value, _| async move { Ok::<_, String>((int(&a, "a") + int(&a, "b")).to_string()) }),
        ];
    } else {
        opts.mcp_config = Some(McpConfigInput::Json(json!({"mcpServers":{"bench-tools":{
            "type":"local","command":[mcp_py, format!("{repo}/benchmarks/mcp_server.py")],
            "enabled":true,"timeout":30000}}})));
        if config == "full" {
            opts.skills_dir = vec![format!("{repo}/examples/skills").into()];
            opts.extra_tools = vec![define_tool("multiply", "Multiply two integers locally.",
                Some(int_schema.clone()),
                |a: Value, _| async move { Ok::<_, String>((int(&a, "a") * int(&a, "b")).to_string()) })];
        }
    }
    let tk = create_toolkit(opts).await.expect("toolkit");
    let client = create_client(ClientOptions {
        base_url: mock, style: Style::OpenAI, model: "mock-model".into(),
        api_key: "sk-mock-not-a-real-key".into(), ..Default::default()
    });
    let init_ms = t0.elapsed().as_secs_f64() * 1000.0;
    let tools = tk.tools();
    if std::env::var_os("BENCH_DEBUG").is_some() {
        for t in &tools { eprintln!("tool: {}", t.name); }
    }

    for _ in 0..warmup { client.run(QUESTION.to_string(), &tk).await.expect("warmup"); }
    let mut lat = Vec::with_capacity(runs);
    let mut final_text = String::new();
    for _ in 0..runs {
        let s = Instant::now();
        let r = client.run(QUESTION.to_string(), &tk).await.expect("run");
        lat.push(s.elapsed().as_secs_f64() * 1000.0);
        final_text = r.text;
    }
    tk.close().await;
    println!("{}", json!({"framework": format!("toolnexus-rust-{config}"), "language":"rust",
        "init_ms": init_ms, "tool_count": tools.len(), "latencies_ms": lat, "final_text": final_text}));
}
