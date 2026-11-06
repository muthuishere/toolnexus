// Cargo.toml:
//   toolnexus = { version = "0.25", features = ["serve"] }  # "serve" is on by default
//   tokio = { version = "1", features = ["full"] }
//   serde_json = "1"
// run: cargo run -- npx -y @agentclientprotocol/claude-agent-acp@0.100.0
use std::io::Write;
use std::sync::Arc;
use std::time::Duration;

use serde_json::json;
use toolnexus::acp::{AcpClient, AcpOptions};
use toolnexus::client::{InProcessOptions, acp_generate_stream, create_in_process_client};
use toolnexus::{BuiltinsConfig, ToolkitOptions, create_toolkit, define_tool};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let schema = json!({"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}, "required": ["a", "b"]});
    let add = define_tool("add", "Add two numbers.", Some(schema), |a, _ctx| async move {
        println!("\n[tool] add({}, {})", a["a"], a["b"]);
        Ok::<_, String>((a["a"].as_f64().unwrap_or(0.0) + a["b"].as_f64().unwrap_or(0.0)).to_string())
    });

    // 1. Serve YOUR toolkit on loopback, behind a fresh bearer token.
    let off = || Some(BuiltinsConfig::off());
    let toolkit = create_toolkit(ToolkitOptions { builtins: off(), extra_tools: vec![add], ..Default::default() }).await?;
    let server = toolkit.as_mcp_server(None).await?;

    // 2. Start the agent; mcp_servers is sent to session/new exactly as written.
    let argv: Vec<String> = std::env::args().skip(1).collect();
    let (command, args) = argv.split_first().ok_or("usage: main <agent command> [args...]")?;
    let mut opts = AcpOptions::new(command).args(args);
    opts.mcp_servers = vec![json!({"type": "http", "name": "toolnexus", "url": server.url,
        "headers": [{"name": "Authorization", "value": format!("Bearer {}", server.token)}]})];
    opts.permission_timeout = Duration::from_secs(300); // one turn of a real agent can take minutes
    let agent = Arc::new(AcpClient::load(opts).await?);

    // 3. The agent runs the tools itself (over MCP), so the client's own toolkit is empty.
    let client = create_in_process_client(InProcessOptions {
        model: command.clone(),
        generate_stream: Some(acp_generate_stream(agent.clone())),
        ..Default::default()
    });
    let empty = create_toolkit(ToolkitOptions { builtins: off(), ..Default::default() }).await?;
    let mut events = client.stream("Use the add tool to add 17 and 25. Reply with just the sum.", &empty)?;
    while let Some(ev) = events.recv().await {
        match ev.kind.as_str() {
            "text" => { print!("{}", ev.delta); std::io::stdout().flush()?; } // live text
            "tool_call" if ev.via == "acp" => println!("\n[agent tool] {}", ev.name),
            "done" => println!("\nfinal: {}", ev.result.map(|r| r.text).unwrap_or_default()),
            "error" => eprintln!("\nerror: {:?}", ev.error),
            _ => {}
        }
    }
    agent.close().await; // ends the agent's whole process group
    server.close();
    Ok(())
}
