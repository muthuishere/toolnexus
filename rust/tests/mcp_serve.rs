//! MCP inbound (SPEC §7C): mirrors golang/mcpserve_test.go. A toolkit served as
//! a streamable-HTTP MCP server at `/mcp`, driven by the rmcp client and by this
//! port's own MCP client.
#![cfg(all(feature = "serve", feature = "mcp-http"))]

use std::sync::{Arc, Mutex};

use rmcp::model::{CallToolRequestParams, CallToolResult, ContentBlock};
use rmcp::service::RunningService;
use rmcp::transport::StreamableHttpClientTransport;
use rmcp::{RoleClient, ServiceExt};
use serde_json::{Map, Value, json};
use toolnexus::client::{ClientOptions, create_client};
use toolnexus::mcp_serve::*;
use toolnexus::serve::{A2aConfig, ServeHandle, ServeOptions};
use toolnexus::{
    BuiltinsConfig, ContentPart, McpConfigInput, Tool, ToolContext, ToolResult, ToolSource,
    ToolkitOptions, create_toolkit, define_tool,
};

fn echo_schema() -> Value {
    json!({"type": "object", "properties": {"text": {"type": "string"}},
           "required": ["text"], "additionalProperties": false})
}

fn echo() -> Tool {
    define_tool(
        "echo",
        "echo back the text",
        Some(echo_schema()),
        |a: Value, _| async move { Ok::<_, String>(a["text"].as_str().unwrap_or("").to_owned()) },
    )
}

fn boom() -> Tool {
    define_tool("boom", "always explodes", None, |_: Value, _| async move {
        Err::<String, _>("kaboom")
    })
}

fn picture() -> Tool {
    let mut t = echo();
    t.name = "picture".into();
    t.execute = Arc::new(|_, _: ToolContext| {
        Box::pin(async {
            ToolResult {
                output: "a dot".into(),
                parts: vec![ContentPart {
                    kind: "image".into(),
                    mime_type: "image/png".into(),
                    data: "iVBORw0KGgo=".into(),
                    ..Default::default()
                }],
                ..Default::default()
            }
        })
    });
    t
}

async fn tk_with(tools: Vec<Tool>, mcp_config: Option<Value>) -> toolnexus::Toolkit {
    create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: tools,
        mcp_config: mcp_config.map(McpConfigInput::Json),
        ..Default::default()
    })
    .await
    .unwrap()
}

async fn serve_mcp(
    tk: &toolnexus::Toolkit,
    cfg: Option<McpServeConfig>,
    on_call: Option<OnCall>,
) -> ServeHandle {
    tk.serve(
        "127.0.0.1:0",
        ServeOptions {
            mcp: cfg,
            on_call,
            ..Default::default()
        },
    )
    .await
    .unwrap()
}

async fn connect(h: &ServeHandle) -> RunningService<RoleClient, ()> {
    ().serve(StreamableHttpClientTransport::from_uri(format!(
        "{}/mcp",
        h.url
    )))
    .await
    .unwrap()
}

async fn call(c: &RunningService<RoleClient, ()>, name: &str, args: Value) -> CallToolResult {
    let args: Map<String, Value> = serde_json::from_value(args).unwrap();
    c.call_tool(CallToolRequestParams::new(name.to_owned()).with_arguments(args))
        .await
        .unwrap()
}

fn text0(r: &CallToolResult) -> String {
    match &r.content[0] {
        ContentBlock::Text(t) => t.text.clone(),
        other => panic!("first block is not text: {other:?}"),
    }
}

fn names(tools: &[Tool]) -> Vec<String> {
    tools.iter().map(|t| t.name.clone()).collect()
}

#[test]
fn exposed_filter() {
    let tools = vec![echo(), boom()];
    assert_eq!(
        names(&exposed_mcp_tools(tools.clone(), None)),
        ["echo", "boom"]
    );
    let only = McpServeConfig {
        tools: Some(vec!["echo".into()]),
        ..Default::default()
    };
    assert_eq!(
        names(&exposed_mcp_tools(tools.clone(), Some(&only))),
        ["echo"]
    );
    let unknown = McpServeConfig {
        tools: Some(vec!["echo".into(), "nope".into()]),
        ..Default::default()
    };
    assert_eq!(names(&exposed_mcp_tools(tools, Some(&unknown))), ["echo"]);
}

#[tokio::test]
async fn initialize_server_info() {
    let tk = tk_with(vec![echo()], None).await;
    let h = serve_mcp(
        &tk,
        Some(McpServeConfig {
            name: "gateway".into(),
            version: "2.0.0".into(),
            tools: None,
        }),
        None,
    )
    .await;
    let c = connect(&h).await;
    let info = c.peer_info().unwrap();
    assert_eq!(info.server_info.as_ref().unwrap().name, "gateway");
    assert_eq!(info.server_info.as_ref().unwrap().version, "2.0.0");
    c.cancel().await.ok();
}

#[tokio::test]
async fn defaults_and_tools_list_schema() {
    let tk = tk_with(vec![echo(), boom()], None).await;
    let h = serve_mcp(&tk, Some(McpServeConfig::default()), None).await;
    let c = connect(&h).await;
    let info = c.peer_info().unwrap();
    assert_eq!(info.server_info.as_ref().unwrap().name, "toolnexus");
    assert_eq!(info.server_info.as_ref().unwrap().version, "0.1.0");
    let list = c.list_all_tools().await.unwrap();
    let got: Vec<&str> = list.iter().map(|t| t.name.as_ref()).collect();
    assert_eq!(got, ["echo", "boom"]);
    let e = list.iter().find(|t| t.name == "echo").unwrap();
    assert_eq!(Value::Object((*e.input_schema).clone()), echo_schema());
    assert_eq!(e.description.as_deref(), Some("echo back the text"));
    c.cancel().await.ok();
}

#[tokio::test]
async fn tools_filter_narrows() {
    let tk = tk_with(vec![echo(), boom()], None).await;
    let cfg = McpServeConfig {
        tools: Some(vec!["echo".into()]),
        ..Default::default()
    };
    let h = serve_mcp(&tk, Some(cfg), None).await;
    let c = connect(&h).await;
    let list = c.list_all_tools().await.unwrap();
    assert_eq!(list.len(), 1);
    assert_eq!(list[0].name, "echo");
    c.cancel().await.ok();
}

#[tokio::test]
async fn call_dispatch_and_on_call() {
    let calls: Arc<Mutex<Vec<OnCallEvent>>> = Arc::default();
    let c2 = calls.clone();
    let tk = tk_with(vec![echo()], None).await;
    let h = serve_mcp(
        &tk,
        Some(McpServeConfig::default()),
        Some(Arc::new(move |ev| c2.lock().unwrap().push(ev))),
    )
    .await;
    let c = connect(&h).await;
    let r = call(&c, "echo", json!({"text": "hi"})).await;
    assert_eq!(r.is_error, Some(false));
    assert_eq!(text0(&r), "hi");
    let evs = calls.lock().unwrap().clone();
    assert_eq!(evs.len(), 1);
    assert_eq!(evs[0].name, "echo");
    assert_eq!(evs[0].source, ToolSource::Native);
    assert!(!evs[0].is_error);
    c.cancel().await.ok();
}

#[tokio::test]
async fn error_result_survives() {
    let tk = tk_with(vec![echo(), boom()], None).await;
    let h = serve_mcp(&tk, Some(McpServeConfig::default()), None).await;
    let c = connect(&h).await;
    let bad = call(&c, "boom", json!({})).await;
    assert_eq!(bad.is_error, Some(true));
    assert!(text0(&bad).contains("kaboom"));
    let ok = call(&c, "echo", json!({"text": "still up"})).await;
    assert_eq!(ok.is_error, Some(false));
    assert_eq!(text0(&ok), "still up");
    c.cancel().await.ok();
}

#[tokio::test]
async fn parts_leave_as_native_content_after_the_text() {
    let tk = tk_with(vec![picture()], None).await;
    let h = serve_mcp(&tk, Some(McpServeConfig::default()), None).await;
    let c = connect(&h).await;
    let r = call(&c, "picture", json!({"text": ""})).await;
    assert_eq!(text0(&r), "a dot");
    match &r.content[1] {
        ContentBlock::Image(i) => {
            assert_eq!(i.data, "iVBORw0KGgo=");
            assert_eq!(i.mime_type, "image/png");
        }
        other => panic!("want image, got {other:?}"),
    }
    c.cancel().await.ok();
}

#[tokio::test]
async fn own_mcp_client_round_trip() {
    let served = tk_with(vec![echo()], None).await;
    let h = serve_mcp(
        &served,
        Some(McpServeConfig {
            name: "http-gw".into(),
            ..Default::default()
        }),
        None,
    )
    .await;
    let consumer = tk_with(
        vec![],
        Some(json!({"mcpServers": {"gw": {"type": "remote", "url": format!("{}/mcp", h.url)}}})),
    )
    .await;
    let r = consumer
        .execute(
            "gw_echo",
            json!({"text": "over http"}),
            ToolContext::default(),
        )
        .await;
    assert!(!r.is_error, "{} / {:?}", r.output, consumer.mcp_status());
    assert_eq!(r.output, "over http");
    consumer.close().await;
}

#[tokio::test]
async fn absent_profile_has_no_mcp_surface() {
    let tk = tk_with(vec![echo()], None).await;
    let h = tk
        .serve(
            "127.0.0.1:0",
            ServeOptions {
                client: Some(create_client(ClientOptions {
                    base_url: "http://unused".into(),
                    model: "x".into(),
                    api_key: "k".into(),
                    ..Default::default()
                })),
                a2a: Some(A2aConfig {
                    name: "only-a2a".into(),
                    ..Default::default()
                }),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    let out: Value = reqwest::Client::new()
        .post(format!("{}/mcp", h.url))
        .json(&json!({"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert!(out.get("error").is_some(), "{out}");
    assert!(out.get("result").is_none());
}

#[tokio::test]
async fn top_level_mcp_server_block() {
    let tk = tk_with(
        vec![echo()],
        Some(json!({"mcpServer": {"name": "from-config"}})),
    )
    .await;
    let h = tk
        .serve("127.0.0.1:0", ServeOptions::default())
        .await
        .unwrap();
    let c = connect(&h).await;
    assert_eq!(
        c.peer_info().unwrap().server_info.as_ref().unwrap().name,
        "from-config"
    );
    c.cancel().await.ok();
}

fn stats() -> Tool {
    let mut t = echo();
    t.name = "stats".into();
    t.output_schema = Some(json!({"type": "object", "properties": {"count": {"type": "integer"}}}));
    t.execute = Arc::new(|_, _: ToolContext| {
        Box::pin(async {
            let mut meta = Map::new();
            meta.insert("usage".into(), json!({"units": 2}));
            ToolResult::ok("")
                .with_structured(json!({"count": 3}))
                .with_meta(meta)
        })
    });
    t
}

#[tokio::test]
async fn structured_meta_and_output_schema_on_the_serve_path() {
    let tk = tk_with(vec![stats(), echo()], None).await;
    let h = serve_mcp(&tk, Some(McpServeConfig::default()), None).await;
    let c = connect(&h).await;
    let list = c.list_all_tools().await.unwrap();
    let s = list.iter().find(|t| t.name == "stats").unwrap();
    assert_eq!(
        Value::Object((**s.output_schema.as_ref().unwrap()).clone()),
        json!({"type": "object", "properties": {"count": {"type": "integer"}}})
    );
    let e = list.iter().find(|t| t.name == "echo").unwrap();
    assert!(e.output_schema.is_none());
    let r = call(&c, "stats", json!({"text": ""})).await;
    assert_eq!(text0(&r), r#"{"count":3}"#);
    assert_eq!(r.structured_content, Some(json!({"count": 3})));
    assert_eq!(
        serde_json::to_value(r.meta.as_ref().unwrap()).unwrap(),
        json!({"usage": {"units": 2}})
    );
    // Absent ⇒ the wire is unchanged: no structuredContent, no _meta.
    let plain = call(&c, "echo", json!({"text": "hi"})).await;
    let wire = serde_json::to_value(&plain).unwrap();
    assert!(wire.get("structuredContent").is_none());
    assert!(wire.get("_meta").is_none());
    c.cancel().await.ok();
}
