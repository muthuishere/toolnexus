//! SERVER runner of the MCP 2026-07-28 interop matrix (interop/mcp2026/README.md).
//! Mirrors golang/internal/interop/mcp2026server. Test tooling, not shipped
//! (excluded from the published crate).
//!
//!     interop_mcp2026_server [--keycloak] <skillsDir>

#[allow(dead_code)]
#[path = "../tests/common_dev_auth/mod.rs"]
mod common_dev_auth;

use std::collections::HashMap;
use std::io::Read as _;
use std::sync::{Arc, Mutex};

use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use serde_json::{Value, json};
use toolnexus::mcp_handler::*;
use toolnexus::tool::pending;
use toolnexus::{
    BuiltinsConfig, Request, Tool, ToolResult, ToolkitOptions, create_toolkit, define_tool,
};

const KEYCLOAK_ISSUER: &str = "http://localhost:8180/realms/toolnexus";
const KEYCLOAK_ADDR: &str = "127.0.0.1:7199";

fn tool(
    name: &str,
    desc: &str,
    schema: Value,
    f: impl Fn(Value, toolnexus::ToolContext) -> ToolResult + Send + Sync + 'static,
) -> Tool {
    let mut t = define_tool(name, desc, Some(schema), |_: Value, _| async {
        Ok::<_, String>(String::new())
    });
    let f = Arc::new(f);
    t.execute = Arc::new(move |a, ctx| {
        let r = f(a, ctx);
        Box::pin(async move { r })
    });
    t
}

fn tools() -> Vec<Tool> {
    let obj = json!({"type": "object"});
    vec![
        tool(
            "echo",
            "Echo the given text back.",
            json!({"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}),
            |a, _| {
                let s = a["text"].as_str().unwrap_or("").to_owned();
                ToolResult::ok(s.clone()).with_structured(json!({"text": s}))
            },
        ),
        tool(
            "greet",
            "Greet the user; asks for their name.",
            obj.clone(),
            |_, ctx| {
                if let Some(a) = ctx.answer.as_ref().filter(|a| a.ok) {
                    let n = a
                        .data
                        .as_ref()
                        .and_then(|d| d.get("name"))
                        .and_then(Value::as_str)
                        .unwrap_or("");
                    return ToolResult::ok(format!("Hello, {n}!"));
                }
                let data = json!({"schema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}});
                pending(Request {
                    id: "name".into(),
                    kind: "input".into(),
                    prompt: "What is your name?".into(),
                    data: data.as_object().cloned(),
                    ..Default::default()
                })
            },
        ),
        tool(
            "wipe",
            "Destructive; asks for confirmation first.",
            obj.clone(),
            |_, _| ToolResult::ok("wiped"),
        ),
        tool("whoami", "Who is calling.", obj, |_, ctx| {
            let sub = mcp_request_context(&ctx)
                .and_then(|rc| rc.identity_as::<McpPrincipal>())
                .map(|p| p.subject.clone())
                .unwrap_or_default();
            ToolResult::ok(sub)
        }),
    ]
}

fn body(s: String) -> McpBody {
    Full::new(Bytes::from(s)).map_err(|e| match e {}).boxed()
}

#[tokio::main]
async fn main() {
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    let keycloak = args.first().is_some_and(|a| a == "--keycloak");
    if keycloak {
        args.remove(0);
    }
    let skills_dir = args
        .first()
        .cloned()
        .unwrap_or_else(|| "../examples/skills".into());

    let mut _dev = None;
    let issuer = if keycloak {
        KEYCLOAK_ISSUER.to_owned()
    } else {
        let d = common_dev_auth::start_mcp_dev_auth_server(common_dev_auth::McpDevAuthOptions {
            clients: HashMap::from([(
                "interop-client".into(),
                vec!["http://127.0.0.1/callback".into()],
            )]),
            ..Default::default()
        })
        .await
        .expect("dev auth server");
        let i = d.issuer.clone();
        _dev = Some(d);
        i
    };

    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .expect("toolkit");

    let list: ListToolsFn = Arc::new(|_| Box::pin(async { Ok(tools()) }));
    let cfg = || McpHandlerConfig {
        name: "interop".into(),
        version: "1.0.0".into(),
        protocol_versions: vec![MCP_PROTOCOL_2026.into()],
        list_tools: Some(list.clone()),
        list_skills: Some({
            let dir = skills_dir.clone();
            Arc::new(move |_| {
                let dir = dir.clone();
                Box::pin(async move { load_mcp_skills(&dir).map_err(|e| e.to_string()) })
            })
        }),
        before_call: Some(Arc::new(|_, t: Tool, _| {
            Box::pin(async move {
                Ok(if t.name == "wipe" {
                    McpDecision::confirm("Really wipe?")
                } else {
                    McpDecision::allow()
                })
            })
        })),
        ..Default::default()
    };
    let mut auth = McpServerAuth {
        issuer: issuer.clone(),
        scopes: HashMap::from([
            ("*".into(), vec!["mcp".into()]),
            ("whoami".into(), vec!["mcp:whoami".into()]),
        ]),
        ..Default::default()
    };
    let mut addr = "127.0.0.1:0";
    if keycloak {
        // The realm's audience mapper pins aud = http://127.0.0.1:7199/mcp.
        addr = KEYCLOAK_ADDR;
        auth.audience = format!("http://{KEYCLOAK_ADDR}/mcp");
        auth.scopes = HashMap::from([
            ("*".into(), vec!["mcp:tools".into()]),
            ("whoami".into(), vec!["mcp:greet".into()]),
        ]);
    }
    let open = tk.mcp_handler(cfg());
    let protected = tk.mcp_handler(McpHandlerConfig {
        authenticate: Some(auth.authenticate()),
        ..cfg()
    });
    let legacy = tk.mcp_handler(McpHandlerConfig {
        stateless: true,
        list_tools: Some(list.clone()),
        ..Default::default()
    });
    let meta = auth.metadata();
    let seen: Arc<Mutex<Vec<String>>> = Arc::default();

    let ln = tokio::net::TcpListener::bind(addr).await.expect("listen");
    let base = format!("http://{}", ln.local_addr().unwrap());
    tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = ln.accept().await else {
                continue;
            };
            let (open, protected, legacy, meta, seen) = (
                open.clone(),
                protected.clone(),
                legacy.clone(),
                meta.clone(),
                seen.clone(),
            );
            tokio::spawn(async move {
                let svc = hyper::service::service_fn(move |req: hyper::Request<Incoming>| {
                    let (open, protected, legacy, meta, seen) = (
                        open.clone(),
                        protected.clone(),
                        legacy.clone(),
                        meta.clone(),
                        seen.clone(),
                    );
                    async move {
                        let path = req.uri().path().to_owned();
                        let h = |n: &str| {
                            req.headers()
                                .get(n)
                                .and_then(|v| v.to_str().ok())
                                .unwrap_or("")
                                .to_owned()
                        };
                        let log = |m: &str| {
                            seen.lock().unwrap().push(format!(
                                "{m} {} {}",
                                h("mcp-protocol-version"),
                                h("mcp-method")
                            ))
                        };
                        let r = match path.as_str() {
                            "/open/mcp" => {
                                log("open");
                                open.handle(req).await
                            }
                            "/mcp" => {
                                log("auth");
                                protected.handle(req).await
                            }
                            "/legacy/mcp" => {
                                log("legacy");
                                legacy.handle(req).await
                            }
                            p if p.starts_with("/.well-known/oauth-protected-resource") => {
                                meta.handle(req).await
                            }
                            "/log" => {
                                let s = seen.lock().unwrap().clone();
                                let mut r =
                                    hyper::Response::new(body(json!({"seen": s}).to_string()));
                                r.headers_mut().insert(
                                    "content-type",
                                    hyper::header::HeaderValue::from_static("application/json"),
                                );
                                r
                            }
                            _ => {
                                let mut r = hyper::Response::new(body("not found".into()));
                                *r.status_mut() = hyper::StatusCode::NOT_FOUND;
                                r
                            }
                        };
                        Ok::<_, std::convert::Infallible>(r)
                    }
                });
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), svc)
                    .await;
            });
        }
    });
    println!("{}", json!({"base": base, "issuer": issuer}));
    // Serve until stdin closes (the runner owns our lifetime).
    tokio::task::spawn_blocking(|| {
        let _ = std::io::stdin().read_to_end(&mut Vec::new());
    })
    .await
    .ok();
    std::process::exit(0);
}
