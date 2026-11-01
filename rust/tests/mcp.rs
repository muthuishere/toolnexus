//! Phase 3: MCP source. Config parsing (SPEC §0 items 2–3, §2) is pure; the
//! live tests run the shared examples/mcp-progress-server.mjs (skip without node).

use std::path::Path;
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use toolnexus::ToolContext;
use toolnexus::mcp::*;

#[test]
fn wrapper_keys_bare_map_and_reserved_siblings() {
    for key in ["mcpServers", "servers", "mcp"] {
        let c = parse_mcp_config(&json!({key: {"a": {"command": "x"}}})).unwrap();
        assert_eq!(c.keys().collect::<Vec<_>>(), ["a"], "{key}");
    }
    let bare = parse_mcp_config(&json!({"a": {"command": "x"}, "builtins": false, "agents": {}, "a2a": {}, "mcpServer": {}})).unwrap();
    assert_eq!(bare.keys().collect::<Vec<_>>(), ["a"]);
}

#[test]
fn shared_example_config_parses() {
    let c =
        parse_mcp_config_file(Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/mcp.json"))
            .unwrap();
    let e = &c["everything"];
    assert_eq!(
        e.command,
        ["npx", "-y", "@modelcontextprotocol/server-everything"]
    );
    assert!(!e.is_remote() && e.is_enabled() && e.timeout() == Duration::from_secs(30));
    let r = &c["example-remote"];
    assert!(r.is_remote() && !r.is_enabled());
    assert_eq!(r.headers["Authorization"], "Bearer YOUR_TOKEN_HERE");
}

#[test]
fn command_string_plus_args_normalization() {
    let c = parse_mcp_config(&json!({"mcpServers": {
        "s": {"command": "node", "args": ["a.js", "--x"]},
        "arr": {"command": ["node", "a.js"], "args": ["--y"]},
        "remote": {"url": "https://h/mcp", "command": "ignored", "args": ["dropped"]},
        "url-infers-remote": {"url": "https://h/mcp"},
        "envs": {"command": "x", "env": {"A": "1"}},
        "environment-wins": {"command": "x", "environment": {"B": "2"}, "env": {"A": "1"}},
    }}))
    .unwrap();
    assert_eq!(c["s"].command, ["node", "a.js", "--x"]);
    assert_eq!(c["arr"].command, ["node", "a.js", "--y"]);
    assert_eq!(c["remote"].command, ["ignored"]);
    assert!(c["url-infers-remote"].is_remote());
    assert_eq!(c["envs"].env["A"], "1");
    assert_eq!(c["environment-wins"].env.keys().collect::<Vec<_>>(), ["B"]);
}

#[test]
fn malformed_command_fails_the_parse_naming_the_server() {
    for (cmd, want) in [
        (json!(""), "empty string"),
        (json!([]), "empty array"),
        (json!(["a", 1]), "array elements must be strings"),
        (json!(42), "must be a string or an array"),
    ] {
        let err = parse_mcp_config(&json!({"mcpServers": {"bad": {"command": cmd}}}))
            .unwrap_err()
            .to_string();
        assert!(err.contains("\"bad\"") && err.contains(want), "{err}");
    }
    let err = parse_mcp_config(&json!({"mcpServers": {"bad": {"command": "x", "args": "nope"}}}))
        .unwrap_err()
        .to_string();
    assert!(err.contains("invalid args"), "{err}");
}

#[test]
fn env_headers_expand_and_unset_is_empty() {
    // SAFETY: test-local env var names
    unsafe { std::env::set_var("TN_RUST_TEST_TOKEN", "fake-token") };
    let h = expand_env_headers(
        &[
            (
                "Authorization".to_string(),
                "Bearer ${TN_RUST_TEST_TOKEN}".to_string(),
            ),
            ("X".into(), "${TN_UNSET_XYZ}-${not valid}".into()),
        ]
        .into_iter()
        .collect(),
    );
    assert_eq!(h["Authorization"], "Bearer fake-token");
    assert_eq!(h["X"], "-${not valid}");
}

#[test]
fn sanitize_names() {
    assert_eq!(toolnexus::sanitize("my server.v2"), "my_server_v2");
    assert_eq!(toolnexus::sanitize("ok-name_1"), "ok-name_1");
}

fn fixture(timeout_ms: u64) -> Option<McpConfig> {
    if std::process::Command::new("node")
        .arg("--version")
        .output()
        .is_err()
    {
        eprintln!("node not on PATH; skipping");
        return None;
    }
    let f = Path::new(env!("CARGO_MANIFEST_DIR")).join("../examples/mcp-progress-server.mjs");
    Some(
        parse_mcp_config(&json!({"mcpServers": {
            "progress": {"command": ["node", f.to_string_lossy()], "timeout": timeout_ms},
            "broken": {"command": "/nonexistent/toolnexus-no-such-binary"},
            "off": {"command": "node", "enabled": false},
        }}))
        .unwrap(),
    )
}

async fn slow(src: &McpSource, args: Value, ctx: ToolContext) -> toolnexus::ToolResult {
    let t = src
        .tools
        .iter()
        .find(|t| t.name == "progress_slow")
        .expect("progress_slow loaded");
    t.call(args, ctx).await
}

#[tokio::test]
async fn load_isolates_failures_and_names_tools() {
    let Some(cfg) = fixture(2000) else { return };
    let src = load_mcp(&cfg).await;
    assert_eq!(src.status["progress"], McpStatus::Connected);
    assert_eq!(src.status["broken"], McpStatus::Failed);
    assert_eq!(src.status["off"], McpStatus::Disabled);
    assert!(src.errors["broken"].contains("toolnexus-no-such-binary"));
    let t = &src.tools[0];
    assert_eq!(t.name, "progress_slow");
    assert_eq!(t.input_schema["type"], "object");
    assert_eq!(t.input_schema["additionalProperties"], false);
    assert_eq!(t.input_schema["required"], json!(["totalMs", "intervalMs"]));
    src.close().await;
}

#[tokio::test]
async fn idle_timeout_contract() {
    let Some(cfg) = fixture(300) else { return };
    let src = load_mcp(&cfg).await;
    // 1. progress keeps a 1200ms call alive under a 300ms idle timeout
    let r = slow(
        &src,
        json!({"totalMs": 1200, "intervalMs": 100}),
        ToolContext::default(),
    )
    .await;
    assert!(!r.is_error, "{}", r.output);
    let body: Value = serde_json::from_str(&r.output).unwrap();
    assert_eq!(body["progressTokenReceived"], true);
    assert!(body["notificationsSent"].as_u64().unwrap() >= 5);
    assert_eq!(r.metadata.as_ref().unwrap()["server"], "progress");
    // 2. silence times out
    let r = slow(
        &src,
        json!({"totalMs": 1200, "intervalMs": 0}),
        ToolContext::default(),
    )
    .await;
    assert!(r.is_error && r.output.contains("timeout"), "{}", r.output);
    // 3. progress for another token does not reset it
    let r = slow(
        &src,
        json!({"totalMs": 1200, "intervalMs": 100, "wrongToken": true}),
        ToolContext::default(),
    )
    .await;
    assert!(r.is_error && r.output.contains("timeout"), "{}", r.output);
    src.close().await;
}

#[tokio::test]
async fn caller_cancellation_ends_the_call_promptly() {
    let Some(cfg) = fixture(5000) else { return };
    let src = load_mcp(&cfg).await;
    let ctx = ToolContext::default();
    let cancel = ctx.cancel.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(100)).await;
        cancel.cancel();
    });
    let start = Instant::now();
    let r = slow(&src, json!({"totalMs": 3000, "intervalMs": 0}), ctx).await;
    assert!(
        start.elapsed() < Duration::from_secs(1),
        "took {:?}",
        start.elapsed()
    );
    assert!(
        r.is_error && r.output == "cancelled" && !r.output.contains("timeout"),
        "{}",
        r.output
    );
    src.close().await;
}

#[tokio::test]
async fn tools_allowlist_on_original_names() {
    let Some(mut cfg) = fixture(2000) else { return };
    cfg.get_mut("progress").unwrap().tools = [("slow".to_string(), false)].into_iter().collect();
    let src = load_mcp(&cfg).await;
    assert!(src.tools.is_empty(), "drop-list removed slow");
    src.close().await;
}
