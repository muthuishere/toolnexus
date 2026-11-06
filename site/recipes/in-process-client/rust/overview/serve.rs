// Cargo.toml:
//   toolnexus = { version = "0.25", features = ["serve"] }  # "serve" is on by default
//   tokio = { version = "1", features = ["full"] }
//   serde_json = "1"
// run: cargo run
use serde_json::json;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use toolnexus::{BuiltinsConfig, ToolkitOptions, create_toolkit, define_tool};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let schema = json!({"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}, "required": ["a", "b"]});
    let add = define_tool("add", "Add two numbers.", Some(schema), |a, _ctx| async move {
        Ok::<_, String>((a["a"].as_f64().unwrap_or(0.0) + a["b"].as_f64().unwrap_or(0.0)).to_string())
    });

    // Any toolkit (MCP servers, skills, native, HTTP, builtins) can be served to a local agent.
    let toolkit = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: vec![add],
        ..Default::default()
    })
    .await?;
    let server = toolkit.as_mcp_server(None).await?; // Some(McpServeConfig { tools, .. }) narrows it
    println!("MCP endpoint: {}", server.url); // http://127.0.0.1:<port>/mcp, POST only

    // Every request needs the bearer token; this is the entry an ACP agent takes in session/new.
    let entry = json!({"type": "http", "name": "toolnexus", "url": server.url,
                       "headers": [{"name": "Authorization", "value": format!("Bearer {}", server.token)}]});

    // A bare HTTP/1.1 POST, so the recipe needs no HTTP client crate.
    let body = json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}).to_string();
    let host = server.url.trim_start_matches("http://").split('/').next().unwrap_or_default();
    let request = format!(
        "POST /mcp HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\n\
         Accept: application/json, text/event-stream\r\nAuthorization: {}\r\n\
         Content-Length: {}\r\nConnection: close\r\n\r\n{body}",
        entry["headers"][0]["value"].as_str().unwrap_or_default(),
        body.len()
    );
    let mut conn = TcpStream::connect(host).await?;
    conn.write_all(request.as_bytes()).await?;
    let mut reply = String::new();
    conn.read_to_string(&mut reply).await?;
    let status = reply.split_whitespace().nth(1).unwrap_or("?");
    println!("tools/list: {status}");
    server.close();
    Ok(())
}
