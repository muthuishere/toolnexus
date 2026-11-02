//! Mirrors golang/resolve_key_test.go: a key is only ever sent to the host it
//! belongs to; and list_models. One test per process-env mutation, in its own
//! binary, so no other test races the environment.

mod common;

use common::*;
use serde_json::json;
use toolnexus::client::*;

fn set(k: &str, v: &str) {
    // SAFETY: this binary's only env-mutating test runs before any thread reads it.
    unsafe { std::env::set_var(k, v) };
}

#[test]
fn resolve_key_by_host() {
    set("OPENROUTER_API_KEY", "fake-or");
    set("OPENAI_API_KEY", "fake-oa");
    set("ANTHROPIC_API_KEY", "fake-an");
    set("TN_TEST_KEY", "fake-custom");
    let cases: &[(&str, &str, &str, &str, Style)] = &[
        // base, envName, explicit, want, style
        (
            "https://api.openai.com/v1",
            "",
            "",
            "fake-oa",
            Style::OpenAI,
        ),
        (
            "https://api.anthropic.com",
            "",
            "",
            "fake-an",
            Style::Anthropic,
        ),
        (
            "https://openrouter.ai/api/v1",
            "",
            "",
            "fake-or",
            Style::Anthropic,
        ),
        (
            "https://API.OpenAI.com/v1",
            "",
            "",
            "fake-oa",
            Style::OpenAI,
        ),
        (
            "https://example.com/v1",
            "",
            "fake-explicit",
            "fake-explicit",
            Style::OpenAI,
        ),
        (
            "https://api.openai.com/v1",
            "",
            "fake-explicit",
            "fake-explicit",
            Style::OpenAI,
        ),
        (
            "https://example.com/v1",
            "TN_TEST_KEY",
            "",
            "fake-custom",
            Style::OpenAI,
        ),
    ];
    for (base, env_name, explicit, want, style) in cases {
        let c = create_client(ClientOptions {
            base_url: (*base).into(),
            style: *style,
            api_key: (*explicit).into(),
            api_key_env: (*env_name).into(),
            ..Default::default()
        });
        assert_eq!(c.resolve_key().unwrap(), *want, "{base}");
    }
    // never a cross-provider fallback
    for base in [
        "https://example.com/v1",
        "http://api.openai.com/v1",
        "https://api.openai.com.evil.test/",
        "https://evil-openrouter.ai/v1",
        "http://localhost:11434/v1",
    ] {
        let c = create_client(ClientOptions {
            base_url: base.into(),
            ..Default::default()
        });
        let err = c.resolve_key().expect_err(base);
        assert!(
            !err.to_string().contains("fake-"),
            "error leaks a key: {err}"
        );
    }
    // the right env var unset ⇒ error naming it, not another provider's key
    set("OPENAI_API_KEY", "");
    let err = create_client(ClientOptions {
        base_url: "https://api.openai.com/v1".into(),
        ..Default::default()
    })
    .resolve_key()
    .unwrap_err();
    assert!(err.to_string().contains("OPENAI_API_KEY"), "{err}");
    assert_eq!(
        key_env_for_base_url("https://sub.openrouter.ai/x"),
        "OPENROUTER_API_KEY"
    );
}

#[tokio::test]
async fn list_models_both_styles() {
    let srv = serve(|r: Req| async move {
        if r.headers.contains_key("x-api-key") {
            if !r.path.contains("after_id") {
                return Resp::json(json!({"data": [{"id": "claude-b", "display_name": "B"}], "has_more": true, "last_id": "claude-b"}));
            }
            return Resp::json(json!({"data": [{"id": "claude-a", "display_name": "A"}], "has_more": false}));
        }
        assert_eq!(r.headers["authorization"], "Bearer fake");
        assert_eq!(r.path, "/api/v1/models");
        Resp::json(json!({"data": [{"id": "z/model", "name": "Z", "context_length": 200000}, {"id": "a/model"}]}))
    })
    .await;
    let ms = list_models(ClientOptions {
        base_url: format!("{}/api/v1", srv.url),
        api_key: "fake".into(),
        ..Default::default()
    })
    .await
    .unwrap();
    assert_eq!(ms.len(), 2);
    assert_eq!(ms[0].id, "a/model");
    assert_eq!(ms[1].context_length, 200000);
    let ms = list_models(ClientOptions {
        base_url: srv.url.clone(),
        style: Style::Anthropic,
        api_key: "fake".into(),
        ..Default::default()
    })
    .await
    .unwrap();
    assert_eq!(ms.len(), 2);
    assert_eq!(ms[0].id, "claude-a");
    assert_eq!(ms[1].name, "B");
    assert!(
        list_models(ClientOptions {
            base_url: srv.url.clone(),
            ..Default::default()
        })
        .await
        .is_err(),
        "unknown host without api_key must not resolve an env key"
    );
}
