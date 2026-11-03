//! Skill URI safety + download (port of skills_test.go).
use mcp2026::skills::parse_skill_uri;
use mcp2026::{CODE_INVALID_PARAMS, Client, HttpServer, Response, fixture_server, handler};
use serde_json::{Value, json};

#[test]
fn parse_skill_uri_accepts_and_rejects() {
    for (u, n, r) in [
        ("skill://hello-world/SKILL.md", "hello-world", "SKILL.md"),
        ("skill://a/refs/doc.md", "a", "refs/doc.md"),
        ("skill://a/with%20space.md", "a", "with space.md"),
        (
            "skill://a/..dots-in-name..x.md",
            "a",
            "..dots-in-name..x.md",
        ),
    ] {
        assert_eq!(
            parse_skill_uri(u),
            Ok((n.to_string(), r.to_string())),
            "{u}"
        );
    }
    for u in [
        "skill://a/../b",
        "skill://a/%2e%2e/b",
        "skill://a/%2E%2E%2Fetc",
        "skill://%2e%2e/x",
        "skill://a/..%2F..%2Fetc%2Fpasswd",
        "skill://a/%252e%252e/x",
        "skill://a/%252F",
        "skill://a/x%5C..%5Cy",
        "skill://a//etc",
        "skill:///etc/passwd",
        "skill://a/%2Fetc",
        "skill://a/%00",
        "skill://a/C:/x",
        "skill://a/.",
        "skill://a",
        "skill://a/%zz",
        "file:///etc/passwd",
    ] {
        assert!(parse_skill_uri(u).is_err(), "{u}: accepted");
    }
}

#[tokio::test]
async fn server_rejects_traversal_uris() {
    let ts = HttpServer::start(fixture_server()).await;
    let c = Client::new(format!("{}/mcp", ts.url));
    for m in ["resources/read", "skills/get"] {
        let err = c
            .call(
                m,
                json!({"uri": "skill://hello-world/%2e%2e/%2e%2e/fixture.json"}),
            )
            .await
            .unwrap_err();
        assert_eq!(err.code(), Some(CODE_INVALID_PARAMS), "{m}: {err}");
    }
}

#[tokio::test]
async fn download_skill() {
    let ts = HttpServer::start(fixture_server()).await;
    let dir = tempdir("dl");
    Client::new(format!("{}/mcp", ts.url))
        .download_skill("skill://hello-world/SKILL.md", &dir)
        .await
        .unwrap();
    assert!(
        !std::fs::read(dir.join("hello-world/SKILL.md"))
            .unwrap()
            .is_empty()
    );
    let _ = std::fs::remove_dir_all(dir);
}

/// A hostile server lists a resource that decodes to ../../pwned: nothing may be written.
#[tokio::test]
async fn download_skill_refuses_hostile_resource() {
    for evil in [
        "skill://hello-world/%2e%2e%2f%2e%2e%2fpwned",
        "skill://hello-world/..%2F..%2Fpwned",
        "skill://hello-world/%252e%252e/pwned",
        "skill://other-skill/pwned",
    ] {
        let ts = HttpServer::start(handler(move |r| async move {
            let req: Value = serde_json::from_slice(&r.body).unwrap();
            let res = if req["method"] == "skills/get" {
                json!({"skill": {"uri": "skill://hello-world/SKILL.md", "resources": [{"uri": "skill://hello-world/SKILL.md"}, {"uri": evil}]}})
            } else {
                json!({"contents": [{"uri": evil, "text": "pwned"}]})
            };
            Response::json(200, &json!({"jsonrpc": "2.0", "id": req["id"], "result": res}))
        }))
        .await;
        let root = tempdir("evil");
        let r = Client::new(format!("{}/mcp", ts.url))
            .download_skill("skill://hello-world/SKILL.md", root.join("a/b"))
            .await;
        assert!(r.is_err(), "{evil}: accepted");
        assert!(
            !root.join("a").exists() && !root.join("pwned").exists(),
            "{evil}: wrote files"
        );
        let _ = std::fs::remove_dir_all(root);
    }
}

fn tempdir(tag: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!(
        "mcp2026-{tag}-{}-{}",
        std::process::id(),
        rand_suffix()
    ));
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn rand_suffix() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos()
}
