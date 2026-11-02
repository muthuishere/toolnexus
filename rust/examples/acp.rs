//! Drive a local coding agent over ACP as the client's model, streaming its text
//! and its own tool calls (marked via "acp"). Mirrors golang/examples/acp.
//!
//!     cargo run --example acp -- npx -y @zed-industries/claude-code-acp
//!     cargo run --example acp                       # uses the bundled fake agent
//!
//! Permission requests are answered with the first allow option (the default).

use std::sync::Arc;

use toolnexus::acp::{AcpClient, AcpOptions};
use toolnexus::client::{
    InProcessOptions, acp_generate, acp_generate_stream, create_in_process_client,
};
use toolnexus::{BuiltinsConfig, ToolkitOptions, create_toolkit};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut argv: Vec<String> = std::env::args().skip(1).collect();
    if argv.is_empty() {
        // The scripted agent cargo builds next to this example.
        let mut p = std::env::current_exe()?;
        p.pop();
        p.push(if cfg!(windows) {
            "fake_acp_agent.exe"
        } else {
            "fake_acp_agent"
        });
        if !p.exists() {
            println!("build the fake agent first: cargo build --example fake_acp_agent");
            return Ok(());
        }
        argv = vec![p.to_string_lossy().into_owned(), "toolstream".into()];
    }
    let opts = AcpOptions::new(argv[0].clone())
        .args(argv[1..].to_vec())
        .cwd(std::env::current_dir()?.canonicalize()?);
    let agent = Arc::new(AcpClient::load(opts).await?);

    let client = create_in_process_client(InProcessOptions {
        generate: Some(acp_generate(agent.clone())),
        generate_stream: Some(acp_generate_stream(agent.clone())),
        ..Default::default()
    });
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await?;
    let mut rx = client.stream("List the files in this directory.", &tk)?;
    while let Some(ev) = rx.recv().await {
        match ev.kind.as_str() {
            "text" => print!("{}", ev.delta),
            "tool_call" => println!("[agent tool_call {} via {}]", ev.name, ev.via),
            "tool_result" => println!("[agent tool_result {} error={}]", ev.name, ev.is_error),
            "error" => eprintln!("[error {:?}]", ev.error),
            _ => {}
        }
    }
    println!();
    agent.close().await;
    Ok(())
}
