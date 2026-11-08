//! Live tests against a real Keycloak (../golang/keycloak/docker-compose.yml). Skipped unless
//! KEYCLOAK=1. All tests share the realm's fixed audience 127.0.0.1:7199, so they run serially
//! (one lock) and bind with a retry (other ports' suites may hold the address).
use std::sync::{Arc, Mutex};
use std::time::Duration;

use mcp2026::serverauth::unverified_claims;
use mcp2026::{
    Authorizer, Client, Error, HttpServer, MemoryTokenStore, OAuth, Opener, ServerAuth, TokenStore,
    fixture_server, obj, opener, responder, run_checks_with, text,
};
use serde_json::json;

const ISSUER: &str = "http://localhost:8180/realms/toolnexus";
const ADDR: &str = "127.0.0.1:7199";
const URL: &str = "http://127.0.0.1:7199/mcp";
static SERIAL: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

fn skip() -> bool {
    if std::env::var("KEYCLOAK").is_err() {
        eprintln!("skip: set KEYCLOAK=1 with keycloak running");
        return true;
    }
    false
}

async fn kc_server(audience: &str, addr: &str) -> HttpServer {
    let mut srv = fixture_server().tool("whoami", "who is calling", |ctx, _| {
        let p = ctx.principal.clone().ok_or("no principal")?;
        Ok(json!(format!("{} {}", p.subject, p.scopes.join(","))))
    });
    srv.auth = Some(ServerAuth {
        audience: audience.into(),
        ..ServerAuth::new(ISSUER).with_scopes(&[("*", &["mcp:tools"]), ("greet", &["mcp:greet"])])
    });
    let h = mcp2026::IntoHandler::into_handler(srv);
    for _ in 0..360 {
        if let Ok(s) = HttpServer::bind(addr, h.clone()).await {
            return s;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    panic!("{addr} stayed busy")
}

/// A headless browser: cookie jar + Keycloak login form; records callbacks and form submissions.
#[derive(Clone)]
struct Browser {
    hc: reqwest::Client,
    callbacks: Arc<Mutex<Vec<String>>>,
    forms: Arc<Mutex<usize>>,
}

impl Browser {
    fn new() -> Browser {
        let hc = reqwest::Client::builder()
            .cookie_store(true)
            .timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::custom(|a| {
                if a.url().host_str() == Some("127.0.0.1") {
                    a.stop()
                } else {
                    a.follow()
                }
            }))
            .build()
            .unwrap();
        Browser {
            hc,
            callbacks: Arc::default(),
            forms: Arc::default(),
        }
    }

    async fn login(&self, auth_url: &str) -> Result<String, String> {
        let e = |e: reqwest::Error| e.to_string();
        let mut resp = self.hc.get(auth_url).send().await.map_err(e)?;
        for _ in 0..3 {
            if resp.status().as_u16() != 200 {
                break;
            }
            let base = resp.url().clone();
            let body = resp.text().await.map_err(e)?;
            let action = |marker: &str| {
                let i = body.find(marker)?;
                let start = body[..i].rfind("<form")?;
                let tag = &body[start..start + body[start..].find('>')?];
                let a = tag.find("action=\"")? + 8;
                Some(tag[a..a + tag[a..].find('"')?].replace("&amp;", "&"))
            };
            let (act, form): (String, Vec<(&str, &str)>) =
                if let Some(a) = action(r#"id="kc-form-login""#) {
                    *self.forms.lock().unwrap() += 1;
                    (
                        a,
                        vec![
                            ("username", "alice"),
                            ("password", "alice-test-pw"),
                            ("credentialId", ""),
                        ],
                    )
                } else if body.contains(r#"name="accept""#) {
                    (
                        action("name=\"accept\"").ok_or("no consent form")?,
                        vec![("accept", "Yes")],
                    )
                } else {
                    return Err("unrecognised Keycloak page".into());
                };
            resp = self
                .hc
                .post(base.join(&act).map_err(|e| e.to_string())?)
                .form(&form)
                .send()
                .await
                .map_err(e)?;
        }
        if resp.status().as_u16() != 302 {
            return Err(format!(
                "expected redirect to loopback, got HTTP {}",
                resp.status()
            ));
        }
        let loc = resp.headers()["location"].to_str().unwrap().to_string();
        self.callbacks.lock().unwrap().push(loc.clone());
        Ok(loc)
    }

    fn opener(&self) -> Opener {
        let b = self.clone();
        opener(move |u| {
            let b = b.clone();
            async move {
                let cb = b.login(&u).await?;
                reqwest::get(cb)
                    .await
                    .map(|_| ())
                    .map_err(|e| e.to_string())
            }
        })
    }
}

fn client(b: &Browser, store: Arc<MemoryTokenStore>, f: impl FnOnce(&mut OAuth)) -> Client {
    let mut o = OAuth {
        allow_http: true,
        client_ids: [(ISSUER.to_string(), "toolnexus-mcp".to_string())].into(),
        store,
        authorizer: Authorizer::loopback(b.opener()),
        ..Default::default()
    };
    f(&mut o);
    Client::new(URL).with_auth(o)
}

#[tokio::test(flavor = "multi_thread")]
async fn keycloak_end_to_end_and_eight_steps() {
    if skip() {
        return;
    }
    let _g = SERIAL.lock().await;
    let _s = kc_server(URL, ADDR).await;
    let (b, store) = (Browser::new(), MemoryTokenStore::new());
    let r = client(&b, store.clone(), |_| {})
        .call_tool("whoami", json!({}), None)
        .await
        .unwrap();
    assert!(text(&r).contains("mcp:tools"), "{}", text(&r));
    let cb = url::Url::parse(&b.callbacks.lock().unwrap()[0]).unwrap();
    assert_eq!(
        cb.query_pairs().find(|(k, _)| k == "iss").unwrap().1,
        ISSUER,
        "RFC 9207 iss"
    );
    let claims = unverified_claims(&store.load(URL).unwrap().access_token).unwrap();
    println!(
        "aud={} scope={} azp={}",
        claims["aud"], claims["scope"], claims["azp"]
    );
    assert!(
        run_checks_with(|| client(&b, store.clone(), |_| {})).await,
        "protected checks against Keycloak"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn keycloak_refresh() {
    if skip() {
        return;
    }
    let _g = SERIAL.lock().await;
    let _s = kc_server(URL, ADDR).await;
    for omit in [false, true] {
        let (b, store) = (Browser::new(), MemoryTokenStore::new());
        let c = client(&b, store.clone(), |o| o.omit_refresh_resource = omit);
        c.call_tool("echo", json!({"text": "a"}), None)
            .await
            .unwrap();
        let mut t = store.load(URL).unwrap();
        let old = t.access_token.clone();
        t.expiry = Some(std::time::SystemTime::now() - Duration::from_secs(60));
        store.save(URL, t);
        assert_eq!(
            text(
                &c.call_tool("echo", json!({"text": "b"}), None)
                    .await
                    .unwrap()
            ),
            "b"
        );
        assert_ne!(
            store.load(URL).unwrap().access_token,
            old,
            "omit={omit}: refreshed"
        );
        assert_eq!(*b.forms.lock().unwrap(), 1, "omit={omit}: no second login");
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn keycloak_step_up() {
    if skip() {
        return;
    }
    let _g = SERIAL.lock().await;
    let _s = kc_server(URL, ADDR).await;
    let (b, store) = (Browser::new(), MemoryTokenStore::new());
    let c = client(&b, store.clone(), |_| {});
    c.call_tool("echo", json!({"text": "a"}), None)
        .await
        .unwrap();
    let r = c
        .call_tool(
            "greet",
            json!({}),
            responder(|_, _| Ok(obj!({"action": "accept", "content": {"name": "Alice"}}))),
        )
        .await
        .unwrap();
    assert_eq!(text(&r), "Hello, Alice!");
    assert_eq!(b.callbacks.lock().unwrap().len(), 2, "login + step-up");
    assert!(
        store
            .load(URL)
            .unwrap()
            .scopes
            .iter()
            .any(|s| s == "mcp:greet")
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn keycloak_pause_resume() {
    if skip() {
        return;
    }
    let _g = SERIAL.lock().await;
    let _s = kc_server(URL, ADDR).await;
    let b = Browser::new();
    let c = client(&b, MemoryTokenStore::new(), |o| {
        o.authorizer = Authorizer::pause("http://127.0.0.1:9/callback")
    });
    let p = match c
        .call_tool("echo", json!({"text": "after login"}), None)
        .await
    {
        Err(Error::Pending(p)) => p,
        other => panic!("want pending, got {other:?}"),
    };
    let cb = b.login(&p.url).await.unwrap();
    assert_eq!(
        text(&c.oauth().resume(&p.id, &cb).await.unwrap()),
        "after login"
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn keycloak_wrong_audience_rejected() {
    if skip() {
        return;
    }
    let _g = SERIAL.lock().await;
    let _s = kc_server(URL, ADDR).await;
    let (b, store) = (Browser::new(), MemoryTokenStore::new());
    client(&b, store.clone(), |_| {})
        .call_tool("echo", json!({"text": "a"}), None)
        .await
        .unwrap();
    let tok = store.load(URL).unwrap().access_token;
    let _other = kc_server("http://127.0.0.1:7200/mcp", "127.0.0.1:7200").await; // canonical URI NOT in the token's aud
    for (u, want) in [("http://127.0.0.1:7200/mcp", 401), (URL, 200)] {
        let mut c = Client::new(u);
        c.header_override = vec![("Authorization".into(), format!("Bearer {tok}"))];
        let r = c.call("tools/list", serde_json::Value::Null).await;
        assert_eq!(r.is_ok(), want == 200, "{u}: {r:?}");
    }
}
