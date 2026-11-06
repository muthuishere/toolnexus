//! CLIENT runner of the MCP 2026-07-28 interop matrix (interop/mcp2026/README.md).
//! Mirrors golang/internal/interop/mcp2026client: one line per scenario,
//! `PASS <id>` / `FAIL <id>: <reason>`, exit 0 iff all passed. Test tooling,
//! not shipped (excluded from the published crate).
//!
//!     interop_mcp2026_client <base> <skillsDir>
//!     interop_mcp2026_client --keycloak <mcpURL> <skillsDir>

use std::collections::HashMap;
use std::future::Future;
use std::sync::{Arc, Mutex};

use serde_json::{Value, json};
use toolnexus::client::WaitForFn;
use toolnexus::mcp::{
    McpLoadOptions, McpSource, McpStatus, MemoryTokenStore, OpenUrlFn, TokenStore, load_mcp_with,
    parse_mcp_config,
};
use toolnexus::{
    Answer, McpConfigInput, Request, ToolContext, ToolResult, ToolkitOptions, create_toolkit,
    pending_of,
};

type R = Result<(), String>;

static FAILED: Mutex<bool> = Mutex::new(false);

async fn scenario<F: Future<Output = R>>(id: &str, f: F) {
    match f.await {
        Ok(()) => println!("PASS {id}"),
        Err(e) => {
            *FAILED.lock().unwrap() = true;
            println!("FAIL {id}: {}", e.replace('\n', " "));
        }
    }
}

fn answers() -> WaitForFn {
    Arc::new(|r: Request| {
        Box::pin(async move {
            let data = match r.prompt.as_str() {
                "What is your name?" => json!({"name": "toolnexus"}),
                "Really wipe?" => json!({"confirm": true}),
                p => return Err(format!("unexpected request {p:?}")),
            };
            Ok(Answer {
                id: r.id,
                ok: true,
                data: data.as_object().cloned(),
                reason: String::new(),
            })
        })
    })
}

async fn load_raw(cfg: Value, o: McpLoadOptions) -> Result<McpSource, String> {
    let c = parse_mcp_config(&json!({"mcpServers": {"srv": cfg}})).map_err(|e| e.to_string())?;
    Ok(load_mcp_with(&c, o).await)
}

async fn load(cfg: Value, o: McpLoadOptions) -> Result<McpSource, String> {
    let src = load_raw(cfg, o).await?;
    if src.status.get("srv") != Some(&McpStatus::Connected) {
        let e = format!("not connected: {:?} {:?}", src.status, src.errors);
        src.close().await;
        return Err(e);
    }
    Ok(src)
}

async fn run(src: &McpSource, name: &str, args: Value) -> Result<ToolResult, String> {
    let t = src
        .tools
        .iter()
        .find(|t| t.name == name)
        .ok_or_else(|| format!("no tool {name}"))?;
    Ok(t.call(args, ToolContext::default()).await)
}

async fn expect(src: &McpSource, name: &str, args: Value, want: &str) -> R {
    let r = run(src, name, args).await?;
    if r.is_error || r.output != want {
        return Err(format!(
            "{name}: got {:?} (isError={}), want {want:?}",
            r.output, r.is_error
        ));
    }
    Ok(())
}

fn get_opener() -> OpenUrlFn {
    Arc::new(|u: String| {
        Box::pin(async move {
            reqwest::get(&u)
                .await
                .map(|_| ())
                .map_err(|e| e.to_string())
        })
    })
}

fn has_scope(store: &MemoryTokenStore, resource: &str, scope: &str) -> R {
    match store.load(resource) {
        Some(t) if t.scopes.iter().any(|s| s == scope) => Ok(()),
        t => Err(format!("stored token scopes lack {scope}: {t:?}")),
    }
}

#[tokio::main]
async fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    match a.as_slice() {
        [k, url, _] if k == "--keycloak" => keycloak(url).await,
        [base, skills] => hermetic(base, skills).await,
        _ => {
            eprintln!(
                "usage: interop_mcp2026_client <base> <skillsDir> | --keycloak <mcpURL> <skillsDir>"
            );
            std::process::exit(2);
        }
    }
    std::process::exit(if *FAILED.lock().unwrap() { 1 } else { 0 });
}

fn with_wait() -> McpLoadOptions {
    McpLoadOptions {
        wait_for: Some(answers()),
        ..Default::default()
    }
}

async fn hermetic(base: &str, skills_dir: &str) {
    let open = json!({"url": format!("{base}/open/mcp")});

    scenario("discover", async {
        let src = load(open.clone(), with_wait()).await?;
        let names: Vec<&str> = src.tools.iter().map(|t| t.name.as_str()).collect();
        let r = async {
            if names.join(",") != "srv_echo,srv_greet,srv_whoami,srv_wipe" {
                return Err(format!("tools {}", names.join(",")));
            }
            expect(&src, "srv_echo", json!({"text": "hi"}), r#"{"text":"hi"}"#).await?;
            let l: Value = reqwest::get(format!("{base}/log"))
                .await
                .map_err(|e| e.to_string())?
                .json()
                .await
                .map_err(|e| e.to_string())?;
            for s in l["seen"].as_array().into_iter().flatten() {
                let s = s.as_str().unwrap_or("");
                if s.starts_with("open ") && !s.starts_with("open 2026-07-28 ") {
                    return Err(format!("non-2026 request on the 2026 mount: {s:?}"));
                }
            }
            Ok(())
        }
        .await;
        src.close().await;
        r
    })
    .await;

    scenario("mrtr_input", async {
        let src = load(open.clone(), with_wait()).await?;
        let r = expect(&src, "srv_greet", json!({}), "Hello, toolnexus!").await;
        src.close().await;
        r
    })
    .await;

    scenario("mrtr_confirm", async {
        let src = load(open.clone(), with_wait()).await?;
        let r = expect(&src, "srv_wipe", json!({}), "wiped").await;
        src.close().await;
        r
    })
    .await;

    scenario("mrtr_pending", async {
        let src = load(open.clone(), McpLoadOptions::default()).await?;
        let r = run(&src, "srv_greet", json!({})).await;
        src.close().await;
        let r = r?;
        match pending_of(&r) {
            Some(q) if q.kind == "input" && q.prompt == "What is your name?" => Ok(()),
            _ => Err(format!("not pending input: {r:?}")),
        }
    })
    .await;

    scenario("skills", async {
        let tk = create_toolkit(ToolkitOptions {
            mcp_config: Some(McpConfigInput::Json(json!({"mcpServers": {"srv": open}}))),
            ..Default::default()
        })
        .await
        .map_err(|e| e.to_string())?;
        let r = async {
            let skill = tk.get("skill").ok_or("no skill tool")?;
            let r = skill
                .call(json!({"name": "hello-world"}), ToolContext::default())
                .await;
            let body = skill_body(skills_dir)?;
            if r.is_error || !r.output.contains(&body) {
                return Err(format!(
                    "remote skill output lacks the SKILL.md body: {:.200}",
                    r.output
                ));
            }
            Ok(())
        }
        .await;
        tk.close().await;
        r
    })
    .await;

    scenario("tool_search", async {
        let src = load(
            json!({"url": format!("{base}/open/mcp"), "toolSearch": true, "description": "interop"}),
            with_wait(),
        )
        .await?;
        let r = async {
            if src.tools.len() != 2 {
                return Err(format!(
                    "tool search exposes {} tools, want 2",
                    src.tools.len()
                ));
            }
            let r = src.tools[0]
                .call(json!({"query": "echo text"}), ToolContext::default())
                .await;
            if !r.output.starts_with(r#"[{"name":"echo""#) {
                return Err(format!("search output {:.200}", r.output));
            }
            let r = src.tools[1]
                .call(json!({"name": "greet"}), ToolContext::default())
                .await;
            if r.output != "Hello, toolnexus!" {
                return Err(format!("call_tool greet {:?}", r.output));
            }
            Ok(())
        }
        .await;
        src.close().await;
        r
    })
    .await;

    let auth_url = format!("{base}/mcp");
    let auth_cfg =
        json!({"url": auth_url, "auth": {"clientId": "interop-client", "allowHttp": true}});
    let store = MemoryTokenStore::new();
    let authed: Mutex<Option<McpSource>> = Mutex::new(None);
    scenario("oauth_preregistered", async {
        let src = load(
            auth_cfg,
            McpLoadOptions {
                open_url: Some(get_opener()),
                token_store: Some(store.clone() as Arc<dyn TokenStore>),
                ..with_wait()
            },
        )
        .await?;
        let r = expect(
            &src,
            "srv_echo",
            json!({"text": "authed"}),
            r#"{"text":"authed"}"#,
        )
        .await;
        *authed.lock().unwrap() = Some(src);
        r
    })
    .await;

    scenario("oauth_stepup", async {
        let src = authed
            .lock()
            .unwrap()
            .take()
            .ok_or("needs oauth_preregistered")?;
        let r = async {
            expect(&src, "srv_whoami", json!({}), "dev-user").await?;
            has_scope(&store, &auth_url, "mcp:whoami")
        }
        .await;
        src.close().await;
        r
    })
    .await;

    scenario("oauth_cimd", async {
        let ln = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .map_err(|e| e.to_string())?;
        let doc_url = format!("http://{}/client.json", ln.local_addr().unwrap());
        let doc = json!({"client_id": doc_url, "client_name": "interop CIMD",
            "redirect_uris": ["http://127.0.0.1/callback"]})
        .to_string();
        let server = tokio::spawn(async move {
            loop {
                let Ok((stream, _)) = ln.accept().await else {
                    continue;
                };
                let doc = doc.clone();
                tokio::spawn(async move {
                    let svc = hyper::service::service_fn(move |_: hyper::Request<hyper::body::Incoming>| {
                        let doc = doc.clone();
                        async move {
                            let mut r = hyper::Response::new(http_body_util::Full::new(
                                hyper::body::Bytes::from(doc),
                            ));
                            r.headers_mut().insert(
                                "content-type",
                                hyper::header::HeaderValue::from_static("application/json"),
                            );
                            Ok::<_, std::convert::Infallible>(r)
                        }
                    });
                    let _ = hyper::server::conn::http1::Builder::new()
                        .serve_connection(hyper_util::rt::TokioIo::new(stream), svc)
                        .await;
                });
            }
        });
        let bad: Arc<Mutex<Option<String>>> = Arc::default();
        let want: String = url::form_urlencoded::Serializer::new(String::new())
            .append_pair("client_id", &doc_url)
            .finish();
        let (b2, w2) = (bad.clone(), want.clone());
        let follow: OpenUrlFn = Arc::new(move |u: String| {
            if !u.contains(&w2) {
                *b2.lock().unwrap() =
                    Some(format!("authorize URL lacks the CIMD client_id: {u}"));
            }
            Box::pin(async move {
                reqwest::get(&u)
                    .await
                    .map(|_| ())
                    .map_err(|e| e.to_string())
            })
        });
        let res = async {
            let src = load(
                json!({"url": format!("{base}/mcp"), "auth": {"clientMetadataUrl": doc_url, "allowHttp": true}}),
                McpLoadOptions {
                    open_url: Some(follow),
                    ..Default::default()
                },
            )
            .await?;
            let r = expect(&src, "srv_echo", json!({"text": "cimd"}), r#"{"text":"cimd"}"#).await;
            src.close().await;
            r?;
            match bad.lock().unwrap().take() {
                Some(e) => Err(e),
                None => Ok(()),
            }
        }
        .await;
        server.abort();
        res
    })
    .await;

    scenario("oauth_no_identity", async {
        let noop: OpenUrlFn = Arc::new(|_| Box::pin(async { Ok(()) }));
        let src = load_raw(
            json!({"url": format!("{base}/mcp"), "auth": {"allowHttp": true}}),
            McpLoadOptions {
                open_url: Some(noop),
                ..Default::default()
            },
        )
        .await?;
        let st = src.status.get("srv").cloned();
        src.close().await;
        if st != Some(McpStatus::Failed) {
            return Err(format!("status {st:?}, want failed"));
        }
        Ok(())
    })
    .await;

    scenario("legacy_fallback", async {
        let src = load(
            json!({"url": format!("{base}/legacy/mcp")}),
            McpLoadOptions::default(),
        )
        .await?;
        let r = expect(
            &src,
            "srv_echo",
            json!({"text": "old"}),
            r#"{"text":"old"}"#,
        )
        .await;
        src.close().await;
        r
    })
    .await;
}

/// hello-world's SKILL.md without its frontmatter, trimmed.
fn skill_body(dir: &str) -> Result<String, String> {
    let s = std::fs::read_to_string(std::path::Path::new(dir).join("hello-world/SKILL.md"))
        .map_err(|e| e.to_string())?
        .replace("\r\n", "\n");
    let mut s = s.as_str();
    if let Some(rest) = s.strip_prefix("---\n")
        && let Some(i) = rest.find("\n---")
    {
        s = &rest[i + 4..];
    }
    Ok(s.trim().to_owned())
}

// ---- live Keycloak (interop/mcp2026/keycloak) ----

const KC_CLIENT_ID: &str = "toolnexus-mcp";
const KC_USER: &str = "alice";
const KC_PASSWORD: &str = "alice-test-pw"; // throwaway user of the local-only realm-toolnexus.json

/// A minimal headless browser: manual redirects + a name=value cookie jar.
struct Browser {
    hc: reqwest::Client,
    jar: Mutex<HashMap<String, String>>,
}

struct Page {
    status: u16,
    url: url::Url,
    location: Option<url::Url>,
    body: String,
}

impl Browser {
    fn new() -> Self {
        Browser {
            hc: reqwest::Client::builder()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(std::time::Duration::from_secs(15))
                .build()
                .unwrap(),
            jar: Mutex::default(),
        }
    }

    /// One request; then follow redirects unless they target 127.0.0.1.
    async fn go(&self, mut u: url::Url, mut form: Option<String>) -> Result<Page, String> {
        for _ in 0..10 {
            let cookie = self
                .jar
                .lock()
                .unwrap()
                .iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join("; ");
            let mut b = match form.take() {
                Some(f) => self
                    .hc
                    .post(u.clone())
                    .header("content-type", "application/x-www-form-urlencoded")
                    .body(f),
                None => self.hc.get(u.clone()),
            };
            if !cookie.is_empty() {
                b = b.header("cookie", cookie);
            }
            let resp = b.send().await.map_err(|e| e.to_string())?;
            for c in resp.headers().get_all("set-cookie") {
                let c = c.to_str().unwrap_or("");
                if let Some((k, v)) = c.split(';').next().and_then(|p| p.split_once('=')) {
                    self.jar.lock().unwrap().insert(k.trim().into(), v.into());
                }
            }
            let status = resp.status().as_u16();
            let location = resp
                .headers()
                .get("location")
                .and_then(|v| v.to_str().ok())
                .and_then(|l| u.join(l).ok());
            match location {
                Some(l) if (300..400).contains(&status) && l.host_str() != Some("127.0.0.1") => {
                    u = l;
                }
                _ => {
                    let body = resp.text().await.unwrap_or_default();
                    return Ok(Page {
                        status,
                        url: u,
                        location,
                        body,
                    });
                }
            }
        }
        Err("too many redirects".into())
    }
}

fn form_action(body: &str, login: bool) -> Option<String> {
    let re = if login {
        regex::Regex::new(r#"<form[^>]*id="kc-form-login"[^>]*action="([^"]+)""#).unwrap()
    } else {
        regex::Regex::new(r#"<form[^>]*action="([^"]+)""#).unwrap()
    };
    re.captures(body)
        .map(|c| c[1].replace("&amp;", "&").replace("&#x3D;", "="))
}

fn enc(pairs: &[(&str, &str)]) -> String {
    let mut s = url::form_urlencoded::Serializer::new(String::new());
    for (k, v) in pairs {
        s.append_pair(k, v);
    }
    s.finish()
}

async fn kc_login(auth_url: String) -> R {
    let b = Browser::new();
    let mut p = b
        .go(url::Url::parse(&auth_url).map_err(|e| e.to_string())?, None)
        .await?;
    for _ in 0..3 {
        if p.status != 200 {
            break;
        }
        let (action, form) = if let Some(a) = form_action(&p.body, true) {
            (
                a,
                enc(&[
                    ("username", KC_USER),
                    ("password", KC_PASSWORD),
                    ("credentialId", ""),
                ]),
            )
        } else if let Some(a) =
            form_action(&p.body, false).filter(|_| p.body.contains(r#"name="accept""#))
        {
            (a, enc(&[("accept", "Yes")]))
        } else {
            break;
        };
        let target = p.url.join(&action).map_err(|e| e.to_string())?;
        p = b.go(target, Some(form)).await?;
    }
    match (p.status, p.location) {
        (302, Some(l)) => reqwest::get(l).await.map(|_| ()).map_err(|e| e.to_string()),
        (s, _) => Err(format!("keycloak login: HTTP {s}")),
    }
}

async fn keycloak(mcp_url: &str) {
    let store = MemoryTokenStore::new();
    let opener: OpenUrlFn = Arc::new(|u: String| Box::pin(kc_login(u)));
    let held: Mutex<Option<McpSource>> = Mutex::new(None);
    scenario("kc_login", async {
        let src = load(
            json!({"url": mcp_url, "auth": {"clientId": KC_CLIENT_ID, "allowHttp": true}}),
            McpLoadOptions {
                open_url: Some(opener),
                token_store: Some(store.clone() as Arc<dyn TokenStore>),
                ..with_wait()
            },
        )
        .await?;
        let r = expect(
            &src,
            "srv_echo",
            json!({"text": "keycloak"}),
            r#"{"text":"keycloak"}"#,
        )
        .await;
        *held.lock().unwrap() = Some(src);
        r
    })
    .await;
    scenario("kc_stepup", async {
        let src = held.lock().unwrap().take().ok_or("needs kc_login")?;
        let r = async {
            let r = run(&src, "srv_whoami", json!({})).await?;
            if r.is_error || r.output.is_empty() {
                return Err(format!("whoami {r:?}"));
            }
            has_scope(&store, mcp_url, "mcp:greet")
        }
        .await;
        src.close().await;
        r
    })
    .await;
}
