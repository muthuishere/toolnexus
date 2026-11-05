//! TEST HARNESS, not something an app needs: it plays the person at the browser. It follows the
//! authorization URL, submits Keycloak's login form (and a consent page if shown), stops at the
//! 127.0.0.1 redirect and delivers it to the client's loopback listener.

use std::sync::Arc;
use std::time::Duration;

use mcp2026::{HttpServer, IntoHandler, Opener, opener};

/// Binds addr, retrying for up to 3 minutes: other ports' Keycloak suites need the same
/// fixed 127.0.0.1:7199 (the realm's audience), so a concurrent run must wait its turn.
pub async fn bind_retry(addr: &str, h: impl IntoHandler) -> HttpServer {
    let h = h.into_handler();
    for _ in 0..360 {
        if let Ok(s) = HttpServer::bind(addr, h.clone()).await {
            return s;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    panic!("{addr} stayed busy");
}

pub fn login(user: &'static str, password: &'static str) -> Opener {
    let hc = Arc::new(
        reqwest::Client::builder()
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
            .unwrap(),
    );
    opener(move |auth_url| {
        let hc = hc.clone();
        async move {
            let e = |e: reqwest::Error| e.to_string();
            let mut resp = hc.get(&auth_url).send().await.map_err(e)?;
            for _ in 0..3 {
                if resp.status().as_u16() != 200 {
                    break;
                }
                let base = resp.url().clone();
                let body = resp.text().await.map_err(e)?;
                let (action, form): (String, Vec<(&str, &str)>) =
                    if let Some(a) = form_action(&body, r#"id="kc-form-login""#) {
                        (
                            a,
                            vec![
                                ("username", user),
                                ("password", password),
                                ("credentialId", ""),
                            ],
                        )
                    } else if let (Some(a), true) = (
                        form_action(&body, "<form"),
                        body.contains(r#"name="accept""#),
                    ) {
                        (a, vec![("accept", "Yes")])
                    } else {
                        return Err("login page not recognised".into());
                    };
                let target = base
                    .join(&action.replace("&amp;", "&"))
                    .map_err(|e| e.to_string())?;
                resp = hc.post(target).form(&form).send().await.map_err(e)?;
            }
            if resp.status().as_u16() != 302 {
                return Err(format!(
                    "login did not redirect back (HTTP {})",
                    resp.status().as_u16()
                ));
            }
            let cb = resp.headers()["location"]
                .to_str()
                .map_err(|e| e.to_string())?
                .to_string();
            reqwest::get(cb).await.map(|_| ()).map_err(e) // hand the callback to the loopback listener
        }
    })
}

/// The `action` of the first `<form` tag containing `marker`.
fn form_action(body: &str, marker: &str) -> Option<String> {
    let mut rest = body;
    while let Some(i) = rest.find("<form") {
        let tag_end = rest[i..].find('>')? + i;
        let tag = &rest[i..tag_end];
        if tag.contains(marker) || marker == "<form" {
            let a = tag.find("action=\"")? + 8;
            let end = tag[a..].find('"')? + a;
            return Some(tag[a..end].to_string());
        }
        rest = &rest[tag_end..];
    }
    None
}
