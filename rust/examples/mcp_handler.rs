//! Embeddable MCP server (SPEC §7C.1): mount `tk.mcp_handler(..)` on your own
//! hyper server, authenticate with a bearer token, give each caller its own
//! tool list, and gate a destructive tool behind a confirmation (elicitation
//! when the client supports it, a host-shaped "re-call with a nonce" result
//! otherwise).
//!
//!     cargo run --example mcp_handler
//!
//! Self-contained: it starts the server on an ephemeral port and drives it with
//! three MCP clients (alice with elicitation, alice without, bob).

use std::sync::Arc;

use hyper::body::Incoming;
use rmcp::model::{
    CallToolRequestParams, ClientCapabilities, ClientConfig, ContentBlock, ElicitRequestParams,
    ElicitResult, ElicitationAction, ElicitationCapability, FormElicitationCapability,
};
use rmcp::service::{RequestContext, RunningService};
use rmcp::transport::StreamableHttpClientTransport;
use rmcp::transport::streamable_http_client::StreamableHttpClientTransportConfig;
use rmcp::{ClientHandler, RoleClient, ServiceExt};
use serde_json::{Value, json};
use tokio::net::TcpListener;
use toolnexus::mcp_handler::{McpDecision, McpHandlerConfig, McpIdentity, mcp_request_context};
use toolnexus::{
    BuiltinsConfig, Tool, ToolAnnotations, ToolResult, ToolkitOptions, create_toolkit, define_tool,
};

#[derive(Debug, Clone)]
struct User {
    name: String,
    role: String,
}

/// Demo tokens only. A real host verifies a JWT/OAuth token here.
fn lookup(token: &str) -> Option<User> {
    let (name, role) = match token {
        "token-alice" => ("alice", "admin"),
        "token-bob" => ("bob", "viewer"),
        _ => return None,
    };
    Some(User {
        name: name.into(),
        role: role.into(),
    })
}

fn tools_for(u: &User) -> Vec<Tool> {
    let mut list = vec![
        define_tool(
            "list_records",
            "List records",
            Some(json!({"type": "object"})),
            |_: Value, ctx| async move {
                let who = mcp_request_context(&ctx)
                    .and_then(|rc| rc.identity_as::<User>())
                    .map(|u| u.name.clone())
                    .unwrap_or_default();
                Ok::<_, String>(format!("records for {who}: [1, 2]"))
            },
        )
        .with_annotations(ToolAnnotations {
            read_only_hint: Some(true),
            ..Default::default()
        }),
    ];
    if u.role == "admin" {
        list.push(
            define_tool(
                "delete_record",
                "Delete a record",
                Some(json!({"type": "object", "properties": {
                    "id": {"type": "string"}, "nonce": {"type": "string"}}})),
                |args: Value, _| async move {
                    Ok::<_, String>(format!("deleted {}", args["id"].as_str().unwrap_or("")))
                },
            )
            .with_annotations(ToolAnnotations {
                title: "Delete record".into(),
                destructive_hint: Some(true),
                ..Default::default()
            }),
        );
    }
    list
}

#[tokio::main]
async fn main() {
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .expect("toolkit");

    let handler = tk.mcp_handler(McpHandlerConfig {
        name: "records".into(),
        version: "1.0.0".into(),
        authenticate: Some(Arc::new(|rc| {
            let tok = rc.header("authorization").trim_start_matches("Bearer ").to_owned();
            Box::pin(async move {
                lookup(&tok)
                    .map(|u| Arc::new(u) as McpIdentity)
                    .ok_or_else(|| "invalid token".into())
            })
        })),
        www_authenticate: Some(Arc::new(|_, _| {
            r#"Bearer resource_metadata="https://example.test/.well-known/oauth-protected-resource""#
                .into()
        })),
        list_tools: Some(Arc::new(|rc| {
            let tools = tools_for(rc.identity_as::<User>().expect("authenticated"));
            Box::pin(async move { Ok(tools) })
        })),
        before_call: Some(Arc::new(|_, tool, args| {
            Box::pin(async move {
                let destructive = tool
                    .annotations
                    .as_ref()
                    .and_then(|a| a.destructive_hint)
                    .unwrap_or(false);
                if !destructive || args["nonce"] == "n-123" {
                    return Ok(McpDecision::allow()); // the host's own nonce check
                }
                Ok(McpDecision::confirm("Really delete this record?").with_result(
                    ToolResult::ok(
                        r#"Confirmation needed: call delete_record again with "nonce":"n-123"."#,
                    ),
                ))
            })
        })),
        on_call: Some(Arc::new(|ev| {
            let who = ev.identity_as::<User>().map(|u| u.name.as_str()).unwrap_or("");
            println!(
                "  [on_call] {} by {who} decision={} is_error={} {}B",
                ev.name,
                ev.decision.as_str(),
                ev.is_error,
                ev.result_bytes
            );
        })),
        ..Default::default()
    });

    // Mount it on your own server: here plain hyper, routing /mcp to the handler.
    let ln = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let url = format!("http://{}/mcp", ln.local_addr().expect("addr"));
    tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = ln.accept().await else {
                return;
            };
            let h = handler.clone();
            tokio::spawn(async move {
                let svc = hyper::service::service_fn(move |req: hyper::Request<Incoming>| {
                    let h = h.clone();
                    async move { Ok::<_, std::convert::Infallible>(h.handle(req).await) }
                });
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), svc)
                    .await;
            });
        }
    });

    let resp = reqwest::Client::new()
        .post(&url)
        .header("content-type", "application/json")
        .body(r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#)
        .send()
        .await
        .expect("post");
    println!(
        "no token -> {}, WWW-Authenticate: {}",
        resp.status().as_u16(),
        resp.headers()["www-authenticate"].to_str().unwrap_or("")
    );

    println!("alice (admin, client supports elicitation):");
    let alice = connect(&url, "token-alice", true).await;
    show(&alice).await;
    println!(
        "  delete_record -> {}",
        call(&alice, "delete_record", json!({"id": "7"})).await
    );

    println!("alice (admin, client WITHOUT elicitation):");
    let alice2 = connect(&url, "token-alice", false).await;
    println!(
        "  delete_record -> {}",
        call(&alice2, "delete_record", json!({"id": "7"})).await
    );
    println!(
        "  delete_record+nonce -> {}",
        call(
            &alice2,
            "delete_record",
            json!({"id": "7", "nonce": "n-123"})
        )
        .await
    );

    println!("bob (viewer):");
    let bob = connect(&url, "token-bob", false).await;
    show(&bob).await;
    println!(
        "  delete_record -> {}",
        call(&bob, "delete_record", json!({"id": "7"})).await
    );
}

/// A client that accepts every confirmation when `elicit` is set.
#[derive(Clone)]
struct Yes {
    elicit: bool,
}

impl ClientHandler for Yes {
    fn get_info(&self) -> ClientConfig {
        let mut cfg = ClientConfig::default();
        if self.elicit {
            let mut el = ElicitationCapability::new();
            el.form = Some(FormElicitationCapability::new());
            let mut caps: ClientCapabilities = cfg.capabilities.clone();
            caps.elicitation = Some(el);
            cfg.capabilities = caps;
        }
        cfg
    }
    async fn create_elicitation(
        &self,
        request: ElicitRequestParams,
        _: RequestContext<RoleClient>,
    ) -> Result<ElicitResult, rmcp::ErrorData> {
        let msg = serde_json::to_value(&request).unwrap_or_default()["message"]
            .as_str()
            .unwrap_or("")
            .to_owned();
        println!("  [client asked] {msg} -> accept");
        let mut r = ElicitResult::new(ElicitationAction::Accept);
        r.content = Some(json!({"confirm": true}));
        Ok(r)
    }
}

async fn connect(url: &str, token: &str, elicit: bool) -> RunningService<RoleClient, Yes> {
    let cfg = StreamableHttpClientTransportConfig::with_uri(url.to_owned()).auth_header(token);
    Yes { elicit }
        .serve(StreamableHttpClientTransport::from_config(cfg))
        .await
        .expect("connect")
}

async fn show(c: &RunningService<RoleClient, Yes>) {
    let names: Vec<String> = c
        .list_all_tools()
        .await
        .expect("tools/list")
        .iter()
        .map(|t| t.name.to_string())
        .collect();
    println!("  tools: {names:?}");
}

async fn call(c: &RunningService<RoleClient, Yes>, name: &str, args: Value) -> String {
    let mut p = CallToolRequestParams::new(name.to_owned());
    if let Value::Object(m) = args {
        p = p.with_arguments(m);
    }
    match c.call_tool(p).await {
        Err(e) => format!("error: {e}"),
        Ok(r) => match r.content.first() {
            Some(ContentBlock::Text(t)) => t.text.clone(),
            _ => String::new(),
        },
    }
}
