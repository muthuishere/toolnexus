//! The 8 cross-port conformance checks (`run.sh client <url>`), and the shared fixture server.

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use serde_json::{Value, json};

use crate::{
    CODE_HEADER_MISMATCH, CODE_UNSUPPORTED_PROTOCOL, Client, Error, PROTOCOL_VERSION, SKILLS_EXT,
    Server, responder, text,
};

/// The shared spike server (tools echo + greet, skill hello-world) loaded from ../fixture.json,
/// or from $FIXTURE when set. Panics if the fixture cannot be loaded.
pub fn fixture_server() -> Server {
    let fx = std::env::var("FIXTURE")
        .unwrap_or_else(|_| concat!(env!("CARGO_MANIFEST_DIR"), "/../fixture.json").to_string());
    Server::load(&fx).unwrap_or_else(|e| panic!("load fixture: {e}"))
}

/// Runs the 8 checks against url, printing PASS/FAIL/SKIP lines.
pub async fn run_checks(url: &str) -> bool {
    let url = url.to_string();
    run_checks_with(move || Client::new(&url)).await
}

/// Like `run_checks`, with a factory for every client the checks create (TLS + OAuth for a
/// protected server, or a stdio client).
pub async fn run_checks_with(mk: impl Fn() -> Client) -> bool {
    let mut ok = true;
    let mut step = |name: &str, r: Result<(), Error>| match r {
        Err(Error::Other(m)) if m == "skip" => println!("SKIP {name} (not applicable over stdio)"),
        Err(e) => {
            ok = false;
            println!("FAIL {name}: {e}")
        }
        Ok(()) => println!("PASS {name}"),
    };
    let fail = |m: String| Err(Error::msg(m));
    let expect_code = |r: Result<crate::Obj, Error>, code: i64| match r {
        Err(e) if e.code() == Some(code) => Ok(()),
        Err(e) => Err(Error::msg(format!("want error {code}, got {e}"))),
        Ok(v) => Err(Error::msg(format!(
            "want error {code}, got result {}",
            Value::Object(v)
        ))),
    };
    let c = mk();

    step(
        "server/discover",
        async {
            let r = c.call("server/discover", Value::Null).await?;
            let caps = r.get("capabilities").cloned().unwrap_or(Value::Null);
            if caps
                .get("extensions")
                .and_then(|e| e.get(SKILLS_EXT))
                .is_none()
            {
                return fail(format!("skills extension not advertised: {caps}"));
            }
            let vs = r
                .get("supportedVersions")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            if !vs.contains(&json!(PROTOCOL_VERSION)) {
                return fail(format!(
                    "supportedVersions lacks {PROTOCOL_VERSION}: {vs:?}"
                ));
            }
            Ok(())
        }
        .await,
    );
    step("unsupported version -> -32022", {
        let mut bad = mk();
        bad.version = "1900-01-01".into();
        expect_code(
            bad.call("server/discover", Value::Null).await,
            CODE_UNSUPPORTED_PROTOCOL,
        )
    });
    step("header mismatch -> -32020", {
        let mut bad = mk();
        if bad.stdio.is_some() {
            Err(Error::msg("skip")) // stdio has no HTTP headers to mismatch
        } else {
            bad.header_override = vec![("Mcp-Name".into(), "greet".into())];
            expect_code(
                bad.call(
                    "tools/call",
                    json!({"name": "echo", "arguments": {"text": "x"}}),
                )
                .await,
                CODE_HEADER_MISMATCH,
            )
        }
    });
    step(
        "tools/list",
        async {
            let r = c.call("tools/list", Value::Null).await?;
            let names: Vec<Value> = r
                .get("tools")
                .and_then(Value::as_array)
                .map(|a| a.iter().map(|t| t["name"].clone()).collect())
                .unwrap_or_default();
            if !names.contains(&json!("echo")) || !names.contains(&json!("greet")) {
                return fail(format!("tools: {names:?}"));
            }
            if r.get("ttlMs").is_none()
                || r.get("cacheScope").is_none()
                || r.get("resultType") != Some(&json!("complete"))
            {
                return fail(format!(
                    "missing ttlMs/cacheScope/resultType: {}",
                    Value::Object(r)
                ));
            }
            Ok(())
        }
        .await,
    );
    step(
        "tools/call echo",
        async {
            let r = c
                .call_tool("echo", json!({"text": "hi toolnexus"}), None)
                .await?;
            if text(&r) != "hi toolnexus" {
                return fail(format!("got {}", Value::Object(r)));
            }
            Ok(())
        }
        .await,
    );
    step(
        "tools/call greet (MRTR input_required round trip)",
        async {
            let asked = Arc::new(AtomicUsize::new(0));
            let a = asked.clone();
            let r = c
                .call_tool(
                    "greet",
                    json!({}),
                    responder(move |_, req| {
                        a.fetch_add(1, Ordering::SeqCst);
                        if req.get("method") != Some(&json!("elicitation/create")) {
                            return Err(Error::msg(format!(
                                "unexpected input request {:?}",
                                req.get("method")
                            )));
                        }
                        Ok(crate::obj!({"action": "accept", "content": {"name": "toolnexus"}}))
                    }),
                )
                .await?;
            let n = asked.load(Ordering::SeqCst);
            if n != 1 || text(&r) != "Hello, toolnexus!" {
                return fail(format!("asked={n} result={}", Value::Object(r)));
            }
            Ok(())
        }
        .await,
    );
    const URI: &str = "skill://hello-world/SKILL.md";
    step(
        "skills/list",
        async {
            let r = c.call("skills/list", Value::Null).await?;
            let found = r
                .get("skills")
                .and_then(Value::as_array)
                .is_some_and(|a| a.iter().any(|s| s["uri"] == URI));
            if !found {
                return fail(format!("hello-world not listed: {}", Value::Object(r)));
            }
            Ok(())
        }
        .await,
    );
    step(
        "skills/get hello-world",
        async {
            let r = c.call("skills/get", json!({"uri": URI})).await?;
            let sk = r.get("skill").cloned().unwrap_or(Value::Null);
            let n = sk["resources"].as_array().map_or(0, Vec::len);
            if sk["frontmatter"]["name"] != "hello-world" || n == 0 {
                return fail(format!("got {}", Value::Object(r)));
            }
            Ok(())
        }
        .await,
    );
    ok
}
