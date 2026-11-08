// Cargo.toml:
//   toolnexus = { version = "0.25", features = ["serve"] }  # "serve" is on by default
//   tokio = { version = "1", features = ["full"] }
//   serde_json = "1"
// run: cargo run -- npx -y @agentclientprotocol/claude-agent-acp@0.100.0
use std::sync::Arc;
use std::time::Duration;

use serde_json::json;
use toolnexus::acp::{AcpClient, AcpOptions, PermissionHandler, PermissionOutcome, permission_handler};
use toolnexus::client::{InProcessOptions, acp_generate, create_in_process_client};
use toolnexus::{BuiltinsConfig, ToolkitOptions, create_toolkit, define_tool};

// Permission policy: allow tool calls whose title names one of your tools, reject the rest.
// Reject answers with the agent's first reject option.
fn allow_only(names: Vec<String>) -> PermissionHandler {
    permission_handler(move |req| {
        let title = req.tool_call["title"].as_str().unwrap_or_default().to_string();
        let ok = names.iter().any(|n| title.contains(n.as_str()));
        let allow = req.first_allow().map(|o| o.option_id.clone());
        async move {
            match allow {
                Some(id) if ok => PermissionOutcome::Selected(id),
                _ => PermissionOutcome::Reject,
            }
        }
    })
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let schema = json!({"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}, "required": ["a", "b"]});
    let add = define_tool("add", "Add two numbers.", Some(schema), |a, _ctx| async move {
        Ok::<_, String>((a["a"].as_f64().unwrap_or(0.0) + a["b"].as_f64().unwrap_or(0.0)).to_string())
    });
    let off = || Some(BuiltinsConfig::off());
    let toolkit = create_toolkit(ToolkitOptions { builtins: off(), extra_tools: vec![add], ..Default::default() }).await?;
    let server = toolkit.as_mcp_server(None).await?;

    let argv: Vec<String> = std::env::args().skip(1).collect();
    let (command, args) = argv.split_first().ok_or("usage: main <agent command> [args...]")?;
    let names = toolkit.tools().into_iter().map(|t| t.name).collect();
    let mut opts = AcpOptions::new(command)
        .args(args)
        .on_permission(allow_only(names))
        .on_update(|u| {
            if u["update"]["sessionUpdate"] == "usage_update" {
                eprintln!("[usage] {}", u["update"]);
            }
        });
    opts.mcp_servers = vec![json!({"type": "http", "name": "toolnexus", "url": server.url,
        "headers": [{"name": "Authorization", "value": format!("Bearer {}", server.token)}]})];
    // Agent-specific knobs are opaque data: this one isolates Claude Code (see the per-agent notes).
    opts.session_meta = Some(json!({"claudeCode": {"options": {
        "settingSources": [], "strictMcpConfig": true, "plugins": [], "skills": [], "tools": []}}}));
    opts.model = std::env::var("ACP_MODEL").ok(); // e.g. "haiku"; passed through, never validated
    opts.permission_timeout = Duration::from_secs(300);
    let agent = Arc::new(AcpClient::load(opts).await?);

    let client = create_in_process_client(InProcessOptions {
        model: command.clone(),
        generate: Some(acp_generate(agent.clone())),
        ..Default::default()
    });
    let empty = create_toolkit(ToolkitOptions { builtins: off(), ..Default::default() }).await?;
    let result = client.run("Use the add tool to add 17 and 25. Reply with just the sum.", &empty).await;
    agent.close().await;
    server.close();
    println!("{}", result?.text);
    Ok(())
}
