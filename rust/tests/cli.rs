//! Hermetic tests for the CLI client (SPEC §8C): no network, no real agent CLI.
//! Mirrors golang/cli_test.go; the fake CLI / ACP agent / MCP bridge is
//! examples/fake_cli.rs.

use std::collections::BTreeMap;
use std::path::PathBuf;

use serde_json::{Value, json};
use toolnexus::cli::*;
use toolnexus::client::StreamEvent;
use toolnexus::{BuiltinsConfig, Toolkit, ToolkitOptions, create_toolkit, define_tool};

fn fake() -> String {
    let mut p = std::env::current_exe().unwrap();
    p.pop();
    if p.ends_with("deps") {
        p.pop();
    }
    p.push("examples");
    p.push(if cfg!(windows) {
        "fake_cli.exe"
    } else {
        "fake_cli"
    });
    assert!(p.exists(), "fake cli not built at {p:?}");
    p.to_string_lossy().into_owned()
}

async fn badge_toolkit() -> Toolkit {
    let badge = define_tool(
        "get_badge_number",
        "Returns the badge number.",
        None,
        |_, _| async { Ok::<_, String>("BADGE-77Q".to_owned()) },
    );
    create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: vec![badge],
        skills_dir: vec![PathBuf::from("../examples/skills")],
        ..Default::default()
    })
    .await
    .unwrap()
}

async fn empty_toolkit() -> Toolkit {
    create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap()
}

fn args(a: &[&str]) -> Vec<CliArg> {
    a.iter().map(|s| CliArg::from(*s)).collect()
}

fn fake_cli_opts(mode: &str, extra: &[&str]) -> CliOptions {
    CliOptions {
        style: Some(CliStyles::Custom),
        command: fake(),
        args: args(extra),
        prompt: "stdin".into(),
        answer: "stdout".into(),
        env: BTreeMap::from([("TN_FAKE_CLI".into(), mode.into())]),
        transport: CliTransports::Cli,
        ..Default::default()
    }
}

async fn run_cli(o: CliOptions, tk: &Toolkit) -> Result<toolnexus::client::RunResult, String> {
    let c = create_cli_client(o).unwrap();
    let r = c
        .run("What is my badge number?", tk)
        .await
        .map_err(|e| e.to_string());
    c.close().await;
    r
}

fn temp_rec() -> PathBuf {
    std::env::temp_dir().join(format!("tn-cli-rec-{}", uuid_like()))
}

fn uuid_like() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    format!(
        "{}-{}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    )
}

// ---------------------------------------------------------------------------
// Shared data: styles and preamble are byte-identical to conformance/.
// ---------------------------------------------------------------------------

#[test]
fn styles_match_conformance() {
    let dir = PathBuf::from("../conformance/cli-styles");
    let mut n = 0;
    for e in std::fs::read_dir(&dir).unwrap() {
        let e = e.unwrap();
        let name = e.file_name().to_string_lossy().into_owned();
        if e.file_type().unwrap().is_dir() || name == "README.md" {
            continue;
        }
        let want = std::fs::read_to_string(e.path()).unwrap();
        let got = cli_style_files()
            .into_iter()
            .find(|(n, _)| *n == name)
            .map(|(_, s)| s);
        assert_eq!(
            got,
            Some(want.as_str()),
            "rust/src/cli_styles/{name} differs from conformance/cli-styles (copy it)"
        );
        n += 1;
    }
    assert_eq!(n, 8, "expected 7 styles + preamble");
    for s in CliStyles::ALL {
        if s == CliStyles::Custom {
            assert!(cli_style_def(s).is_none());
            continue;
        }
        let d = cli_style_def(s).unwrap_or_else(|| panic!("style {s} not embedded"));
        assert_eq!(d.name, s.name());
        assert!(!d.verified.is_empty(), "style {s} has no verified note");
        create_cli_client(CliOptions {
            style: Some(s),
            command: "x".into(),
            transport: CliTransports::Cli,
            ..Default::default()
        })
        .unwrap_or_else(|e| panic!("style {s} invalid: {e}"));
    }
    assert!(cli_style_def(CliStyles::Codex).unwrap().acp.is_none());
    assert_eq!(
        cli_style_def(CliStyles::Devin)
            .unwrap()
            .acp
            .unwrap()
            .project_mcp_file,
        ".mcp.json"
    );
    assert!(
        cli_style_def(CliStyles::Goose)
            .unwrap()
            .verified
            .starts_with("UNVERIFIED")
    );
}

// ---------------------------------------------------------------------------
// The conformance fixture every port replays.
// ---------------------------------------------------------------------------

fn style_by_name(n: &str) -> CliStyles {
    *CliStyles::ALL.iter().find(|s| s.name() == n).unwrap()
}

#[test]
fn conformance_cases() {
    let fx: Value = serde_json::from_str(
        &std::fs::read_to_string("../conformance/cli-client/cases.json").unwrap(),
    )
    .unwrap();
    for c in fx["argv"].as_array().unwrap() {
        let mut o = CliOptions {
            style: Some(style_by_name(c["style"].as_str().unwrap())),
            command: c["command"].as_str().unwrap().into(),
            transport: CliTransports::Cli,
            ..Default::default()
        };
        o.in_process.model = c["model"].as_str().unwrap().into();
        let cl = create_cli_client(o).unwrap();
        let r = cl.render_argv(c["prompt"].as_str().unwrap()).unwrap();
        let argv: Vec<String> = r
            .argv
            .iter()
            .map(|a| {
                if r.out.as_ref().is_some_and(|p| p.to_string_lossy() == *a) {
                    "<out>".into()
                } else if r.file.as_ref().is_some_and(|p| p.to_string_lossy() == *a) {
                    "<file>".into()
                } else {
                    a.clone()
                }
            })
            .collect();
        if let Some(f) = &r.file {
            assert!(!f.exists(), "temp prompt file must be removed");
        }
        assert_eq!(json!(argv), c["argv"], "{c}");
        assert_eq!(json!(r.stdin), c["stdin"], "{c}");
    }
    let norm = |v: &Value| -> Value {
        match v {
            Value::String(s) => serde_json::from_str(s).unwrap_or(v.clone()),
            _ => v.clone(),
        }
    };
    for c in fx["parse"].as_array().unwrap() {
        let r = parse_cli_reply(c["in"].as_str().unwrap());
        if let Some(tc) = c.get("toolCall") {
            assert_eq!(r.tool_calls.len(), 1, "{c}");
            let got = &r.tool_calls[0];
            assert_eq!(got.name, tc["name"].as_str().unwrap());
            assert_eq!(got.id, tc["id"].as_str().unwrap_or(""));
            assert_eq!(norm(&got.arguments), tc["arguments"]);
        } else {
            assert!(r.tool_calls.is_empty(), "{c}");
            assert_eq!(r.content, c["content"].as_str().unwrap(), "{c}");
        }
    }
    for c in fx["constructionErrors"].as_array().unwrap() {
        let op = &c["options"];
        let s = |k: &str| op[k].as_str().unwrap_or("").to_owned();
        let mut o = CliOptions {
            style: op["style"].as_str().map(style_by_name),
            command: s("command"),
            prompt: s("prompt"),
            answer: s("answer"),
            ..Default::default()
        };
        o.in_process.model = s("model");
        if let Some(a) = op["args"].as_array() {
            o.args = a
                .iter()
                .map(|x| serde_json::from_value(x.clone()).unwrap())
                .collect();
        }
        o.transport = match op["transport"].as_str() {
            Some("cli") => CliTransports::Cli,
            Some("acp") => CliTransports::Acp,
            _ => CliTransports::Auto,
        };
        let err = create_cli_client(o).err().expect("must fail");
        assert_eq!(err.to_string(), c["error"].as_str().unwrap());
    }
    assert_eq!(
        render_cli_prompt(&[], &[]),
        fx["renderEmpty"]
            .as_str()
            .unwrap()
            .replacen("<PREAMBLE>", CLI_PREAMBLE, 1)
    );
}

// ---------------------------------------------------------------------------
// One-shot transport.
// ---------------------------------------------------------------------------

#[tokio::test]
async fn one_shot_tool_round_trip() {
    let rec = temp_rec();
    let mut o = fake_cli_opts("tool", &[]);
    o.env
        .insert("TN_FAKE_REC".into(), rec.to_string_lossy().into());
    o.in_process.system_prompt = "You are a badge clerk.".into();
    let tk = badge_toolkit().await;
    let res = run_cli(o, &tk).await.unwrap();
    assert!(res.text.contains("BADGE-77Q"), "{}", res.text);
    assert_eq!(res.tool_calls.len(), 1);
    assert_eq!(res.tool_calls[0].name, "get_badge_number");
    let b = std::fs::read_to_string(&rec).unwrap();
    let _ = std::fs::remove_file(&rec);
    let first = b.split("\n=====\n").next().unwrap();
    let prefix = format!("{CLI_PREAMBLE}\nREQUEST:\n");
    assert!(
        first.starts_with(&prefix),
        "prompt must start with the preamble"
    );
    let req: Value = serde_json::from_str(&first[prefix.len()..]).unwrap();
    assert_eq!(req["messages"][0]["role"], "system");
    assert!(
        req["messages"][0]["content"]
            .as_str()
            .unwrap()
            .contains("badge clerk")
    );
    assert!(!req["tools"].as_array().unwrap().is_empty());
}

#[tokio::test]
async fn prompt_modes_and_out_file() {
    let tk = badge_toolkit().await;
    for (a, p, ans) in [
        (vec!["--p", "{{prompt}}"], "arg", "stdout"),
        (vec!["--f", "{{file}}"], "file", "stdout"),
        (vec!["--o", "{{out}}"], "stdin", "out"),
    ] {
        let mut o = fake_cli_opts("tool", &a);
        o.prompt = p.into();
        o.answer = ans.into();
        let res = run_cli(o, &tk).await;
        assert!(
            res.as_ref().is_ok_and(|r| r.text.contains("BADGE-77Q")),
            "{p}/{ans}: {res:?}"
        );
    }
}

#[tokio::test]
async fn model_group() {
    let tk = empty_toolkit().await;
    let mut o = fake_cli_opts("x", &["--argv", "-q"]);
    o.args.push(CliArg::from(vec!["--model", "{{model}}"]));
    let res = run_cli(o.clone(), &tk).await.unwrap();
    assert!(
        !res.text.contains("--model"),
        "no model ⇒ group dropped: {}",
        res.text
    );
    o.in_process.model = "prov/m-1".into();
    let res = run_cli(o, &tk).await.unwrap();
    assert!(res.text.contains("--model prov/m-1"), "{}", res.text);
}

#[tokio::test]
async fn pinned_errors() {
    let tk = empty_toolkit().await;
    let mut out = fake_cli_opts("nooutfile", &["--x", "{{out}}"]);
    out.answer = "out".into();
    let mut slow = fake_cli_opts("sleep", &[]);
    slow.call_timeout_ms = 300;
    let mut missing = fake_cli_opts("x", &[]);
    missing.command = "definitely-not-a-cli-xyz".into();
    for (o, want) in [
        (fake_cli_opts("exit", &[]), "cli: exit 3: boom: bad login"),
        (fake_cli_opts("empty", &[]), "cli: empty reply"),
        (out, "cli: no output file"),
        (slow, "cli: timed out after 300ms"),
        (missing, "cli: command not found: definitely-not-a-cli-xyz"),
    ] {
        let started = std::time::Instant::now();
        let err = run_cli(o, &tk).await.expect_err("must fail");
        assert!(err.contains(want), "got {err:?}, want {want:?}");
        assert!(started.elapsed().as_secs() < 5, "{want}: too slow");
    }
}

async fn collect(c: &CliClient, tk: &Toolkit) -> (Vec<String>, String) {
    let mut rx = c.stream("What is my badge number?", tk).unwrap();
    let (mut kinds, mut text) = (Vec::new(), String::new());
    while let Some(ev) = rx.recv().await {
        let ev: StreamEvent = ev;
        assert_ne!(ev.kind, "error", "stream must not error: {:?}", ev.error);
        if ev.kind == "text" {
            text.push_str(&ev.delta);
        }
        kinds.push(ev.kind.clone());
    }
    (kinds, text)
}

#[tokio::test]
async fn one_shot_streams_non_incrementally() {
    let tk = badge_toolkit().await;
    let c = create_cli_client(fake_cli_opts("tool", &[])).unwrap();
    let (kinds, text) = collect(&c, &tk).await;
    c.close().await;
    let k: Vec<&str> = kinds
        .iter()
        .map(String::as_str)
        .filter(|k| *k != "usage")
        .collect();
    assert_eq!(k, ["tool_call", "tool_result", "text", "done"], "{kinds:?}");
    assert!(text.contains("BADGE-77Q"), "{text:?}");
}

/// Streaming never errors on a CLI client, for every built-in style and
/// custom, over the one-shot CLI and ACP.
#[tokio::test]
async fn stream_every_style_both_transports() {
    let styles = [
        CliStyles::Claude,
        CliStyles::Codex,
        CliStyles::Copilot,
        CliStyles::Opencode,
        CliStyles::Devin,
        CliStyles::Gemini,
        CliStyles::Goose,
        CliStyles::Custom,
    ];
    let mut transports = vec![CliTransports::Cli];
    if cfg!(feature = "serve") {
        transports.push(CliTransports::Acp);
    }
    for st in styles {
        for &tr in &transports {
            let tk = badge_toolkit().await;
            let mut o = CliOptions {
                style: Some(st),
                command: fake(),
                transport: tr,
                toolkit: Some(tk.clone()),
                env: BTreeMap::from([("TN_FAKE_CLI".into(), "tool".into())]),
                ..Default::default()
            };
            if st == CliStyles::Custom {
                o.args = vec![];
                o.prompt = "stdin".into();
                o.answer = "stdout".into();
            }
            if tr == CliTransports::Acp {
                o.env.insert("TN_FAKE_ACP".into(), "1".into());
                o.env.insert("FAKE_HTTP".into(), "1".into());
                o.bridge_command = vec![fake(), "mcp-bridge".into()];
                o.acp_command = vec![fake(), "acp".into()];
            }
            let c = create_cli_client(o).unwrap();
            let (kinds, text) = collect(&c, &tk).await;
            assert_eq!(c.transport(), tr, "{st:?}/{tr:?}");
            c.close().await;
            assert_eq!(
                kinds.last().map(String::as_str),
                Some("done"),
                "{st:?}/{tr:?}: {kinds:?}"
            );
            assert!(text.contains("BADGE-77Q"), "{st:?}/{tr:?}: {text:?}");
            if tr == CliTransports::Cli {
                assert!(kinds.iter().any(|k| k == "tool_call"), "{st:?}: {kinds:?}");
            }
        }
    }
}

#[test]
fn system_hint_and_served_call_forms() {
    let got = cli_with_system_hint(&[json!({"role": "user", "content": "x"})], "H");
    assert_eq!(got.len(), 2);
    assert_eq!(got[0]["content"], "H");
    let msgs = vec![json!({"role": "system", "content": "S"})];
    let got = cli_with_system_hint(&msgs, "H");
    assert_eq!(got[0]["content"], "S\n\nH");
    assert_eq!(
        msgs[0]["content"], "S",
        "caller's messages must not be mutated"
    );
    let devin = cli_style_def(CliStyles::Devin).unwrap();
    assert!(devin.acp.unwrap().system_hint.contains("\"toolnexus\""));

    let served = ["get_badge_number", "load_skill"];
    for (title, want) in [
        ("get_badge_number", true),
        ("mcp__toolnexus__get_badge_number", true),
        ("toolnexus/get_badge_number", true),
        ("get_badge_number (toolnexus MCP Server)", true),
        ("Calling get_badge_number from toolnexus", true),
        ("load_skill(name: secret-recipe)", true),
        ("skill", false),
        ("Bash", false),
        ("", false),
        ("mcp__other__get_badge_number", false),
        ("run get_badge_number and then rm -rf", false),
        ("Calling get_badge_number from toolnexus2", false),
        ("get_badge_number (evil MCP Server)", false),
        ("mcp__toolnexus__get_badge_number_and_more", false),
    ] {
        assert_eq!(cli_is_served_call(title, served), want, "{title:?}");
    }
}

#[test]
fn load_cli_style_from_json() {
    let d = load_cli_style(r#"{"name":"mine","args":["-q",["--model","{{model}}"]],"prompt":"stdin","answer":"stdout"}"#).unwrap();
    assert_eq!(d.name, "mine");
    assert_eq!(d.args[1], CliArg::from(vec!["--model", "{{model}}"]));
    assert!(load_cli_style("/definitely/not/here.json").is_err());
}

// ---------------------------------------------------------------------------
// ACP transport.
// ---------------------------------------------------------------------------

#[cfg(feature = "serve")]
mod acp {
    use super::*;

    async fn fake_acp_opts(env: &[(&str, &str)]) -> (CliOptions, PathBuf) {
        let rec = temp_rec();
        let mut e = BTreeMap::from([
            ("TN_FAKE_ACP".to_owned(), "1".to_owned()),
            ("TN_FAKE_REC".to_owned(), rec.to_string_lossy().into_owned()),
        ]);
        for (k, v) in env {
            e.insert((*k).into(), (*v).into());
        }
        let d = CliStyleDef {
            name: "fake".into(),
            prompt: "stdin".into(),
            answer: "stdout".into(),
            acp: Some(CliAcpLaunch {
                args: vec!["acp".into()],
                ..Default::default()
            }),
            ..Default::default()
        };
        (
            CliOptions {
                style_def: Some(d),
                command: fake(),
                env: e,
                toolkit: Some(badge_toolkit().await),
                bridge_command: vec![fake(), "mcp-bridge".into()],
                ..Default::default()
            },
            rec,
        )
    }

    async fn assert_fake_run(c: &CliClient, text: &str, entry: &str) {
        assert_eq!(c.transport(), CliTransports::Acp, "{}", c.fallback_reason());
        for want in [
            "perm ours=yes own=no fake=no again=yes",
            &format!("entry={entry}"),
            "BADGE-77Q",
            "Hello",
        ] {
            assert!(text.contains(want), "missing {want:?} in {text:?}");
        }
        assert!(
            text.contains(r#""name":"load_skill""#) && !text.contains(r#""name":"skill""#),
            "skill must be served as load_skill, never skill"
        );
        let info = c.acp_session().await.unwrap();
        assert_eq!(info.served_calls.len(), 2, "{info:?}");
    }

    fn read(rec: &PathBuf) -> String {
        std::fs::read_to_string(rec).unwrap_or_default()
    }

    #[tokio::test]
    async fn http_entry_with_bearer_token() {
        let (o, rec) = fake_acp_opts(&[("FAKE_HTTP", "1")]).await;
        let tk = empty_toolkit().await;
        let c = create_cli_client(o).unwrap();
        let res = c.run("badge?", &tk).await.unwrap();
        assert_fake_run(&c, &res.text, "http").await;
        assert!(res.text.contains("noauth=401"), "{}", res.text);
        assert!(
            read(&rec).contains(r#""headers":[{"name":"Authorization","value":"Bearer "#),
            "session/new must carry the bearer header"
        );
        let info = c.acp_session().await.unwrap();
        assert!(!format!("{:?}", info.mcp_servers_sent).contains("Bearer "));
        let s = read(&rec);
        assert!(s.contains(r#""terminal":false"#));
        c.close().await;
    }

    #[tokio::test]
    async fn stdio_bridge_when_agent_lacks_http() {
        let (o, rec) = fake_acp_opts(&[("FAKE_HTTP", "0")]).await;
        let tk = empty_toolkit().await;
        let c = create_cli_client(o).unwrap();
        let res = c.run("badge?", &tk).await.unwrap();
        assert_fake_run(&c, &res.text, "<nil>").await;
        let s = read(&rec);
        assert!(
            s.contains(r#""env":[{"name":"TOOLNEXUS_MCP_TOKEN""#)
                && !s.contains(r#""type":"stdio""#),
            "stdio entry shape: {s}"
        );
        c.close().await;
    }

    #[tokio::test]
    async fn project_mcp_file() {
        let (mut o, _) = fake_acp_opts(&[("FAKE_HTTP", "0"), ("FAKE_PROJECT", "1")]).await;
        o.style_def
            .as_mut()
            .unwrap()
            .acp
            .as_mut()
            .unwrap()
            .project_mcp_file = ".mcp.json".into();
        let tk = empty_toolkit().await;
        let c = create_cli_client(o).unwrap();
        let res = c.run("badge?", &tk).await.unwrap();
        assert_fake_run(&c, &res.text, "http").await;
        let info = c.acp_session().await.unwrap();
        assert!(info.mcp_servers_sent.is_empty());
        let dir = info.session_cwd.clone().expect("temp cwd");
        let f = dir.join(".mcp.json");
        assert!(f.exists());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(&f).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        c.close().await;
        assert!(!dir.exists(), "temp session dir must be removed on close");
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        let r = reqwest::Client::new()
            .post(&info.url)
            .body("{}")
            .send()
            .await;
        assert!(r.is_err(), "served endpoint must stop with the session");
    }

    #[tokio::test]
    async fn model_pass_through() {
        let (mut o, rec) = fake_acp_opts(&[("FAKE_HTTP", "1"), ("FAKE_NO_CONFIG", "1")]).await;
        o.in_process.model = "anthropic/claude-x".into();
        o.transport = CliTransports::Acp; // the fake style's one-shot args take no model
        let tk = empty_toolkit().await;
        let c = create_cli_client(o).unwrap();
        c.run("badge?", &tk).await.unwrap();
        let s = read(&rec);
        assert!(
            s.contains(r#"session/set_config_option {"configId":"model","sessionId":"s1","value":"anthropic/claude-x"}"#)
                && s.contains(r#"session/set_model {"modelId":"anthropic/claude-x","sessionId":"s1"}"#),
            "model must go to set_config_option then set_model, unchanged: {s}"
        );
        c.close().await;
    }

    #[tokio::test]
    async fn falls_back_to_one_shot() {
        let (o, _) = fake_acp_opts(&[("FAKE_FAIL_INIT", "1"), ("TN_FAKE_CLI", "tool")]).await;
        let tk = o.toolkit.clone().unwrap();
        let c = create_cli_client(o).unwrap();
        assert_eq!(c.transport(), CliTransports::Auto);
        let res = c.run("badge?", &tk).await.unwrap();
        assert_eq!(c.transport(), CliTransports::Cli);
        assert!(!c.fallback_reason().is_empty());
        assert!(res.text.contains("BADGE-77Q"), "{}", res.text);
        c.close().await;

        // Forced ACP never falls back.
        let (mut o2, _) = fake_acp_opts(&[("FAKE_FAIL_INIT", "1")]).await;
        o2.transport = CliTransports::Acp;
        let c2 = create_cli_client(o2).unwrap();
        let err = c2
            .run("badge?", &tk)
            .await
            .expect_err("forced ACP must fail");
        assert!(err.to_string().contains("cli: acp: "), "{err}");
        c2.close().await;
    }

    #[tokio::test]
    async fn system_hint_from_style() {
        let (mut o, rec) = fake_acp_opts(&[("FAKE_HTTP", "1")]).await;
        o.style_def
            .as_mut()
            .unwrap()
            .acp
            .as_mut()
            .unwrap()
            .system_hint = "HINT-USE-TOOLNEXUS".into();
        o.in_process.system_prompt = "You are terse.".into();
        let tk = empty_toolkit().await;
        let c = create_cli_client(o).unwrap();
        c.run("badge?", &tk).await.unwrap();
        assert!(
            read(&rec).contains(r"You are terse.\n\nHINT-USE-TOOLNEXUS"),
            "hint must be appended to the system prompt"
        );
        c.close().await;
    }

    #[tokio::test]
    async fn bridge_refuses_without_token() {
        let (o, _) = fake_acp_opts(&[("FAKE_HTTP", "1")]).await;
        let tk = empty_toolkit().await;
        let c = create_cli_client(o).unwrap();
        c.run("badge?", &tk).await.unwrap();
        let url = c.acp_session().await.unwrap().url;
        let input = tokio::io::BufReader::new(
            &b"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n"[..],
        );
        let err = run_mcp_bridge(&url, "", input, Vec::<u8>::new())
            .await
            .err()
            .unwrap();
        assert!(err.contains("unauthorized"), "{err}");
        let r = reqwest::Client::new().get(&url).send().await.unwrap();
        assert_eq!(r.status().as_u16(), 401);
        c.close().await;
    }

    #[tokio::test]
    async fn hooks_run_at_the_server_boundary() {
        use std::sync::{Arc, Mutex};
        use toolnexus::client::{BeforeToolEvent, ToolOverride};
        let (mut o, _) = fake_acp_opts(&[("FAKE_HTTP", "1")]).await;
        let seen = Arc::new(Mutex::new(Vec::<String>::new()));
        let s2 = seen.clone();
        o.in_process.hooks.before_tool = Some(Arc::new(move |ev: BeforeToolEvent| {
            s2.lock().unwrap().push(ev.name.clone());
            Box::pin(async move {
                if ev.name == "get_badge_number" {
                    return Ok(Some(ToolOverride {
                        result: Some(toolnexus::ToolResult::ok("HOOKED-1")),
                        ..Default::default()
                    }));
                }
                Ok(None)
            })
        }));
        let tk = empty_toolkit().await;
        let c = create_cli_client(o).unwrap();
        let res = c.run("badge?", &tk).await.unwrap();
        assert!(
            res.text.contains("HOOKED-1") && !res.text.contains("BADGE-77Q"),
            "{}",
            res.text
        );
        // the hook sees the toolkit's own name (skill), not the served alias
        assert_eq!(*seen.lock().unwrap(), vec!["get_badge_number", "skill"]);
        assert_eq!(
            c.acp_session().await.unwrap().served_calls,
            vec!["load_skill"]
        );
        c.close().await;
    }

    #[tokio::test]
    async fn non_post_with_token_is_405() {
        let (mut o, _) = fake_acp_opts(&[("FAKE_HTTP", "0"), ("FAKE_PROJECT", "1")]).await;
        o.style_def
            .as_mut()
            .unwrap()
            .acp
            .as_mut()
            .unwrap()
            .project_mcp_file = ".mcp.json".into();
        let tk = empty_toolkit().await;
        let c = create_cli_client(o).unwrap();
        c.run("badge?", &tk).await.unwrap();
        let info = c.acp_session().await.unwrap();
        let pf: Value = serde_json::from_str(
            &std::fs::read_to_string(info.session_cwd.unwrap().join(".mcp.json")).unwrap(),
        )
        .unwrap();
        let auth = pf["mcpServers"]["toolnexus"]["headers"]["Authorization"]
            .as_str()
            .unwrap()
            .to_owned();
        assert_eq!(auth.len(), "Bearer ".len() + 64, "32-byte hex token");
        let r = reqwest::Client::new()
            .get(&info.url)
            .header("Authorization", auth)
            .send()
            .await
            .unwrap();
        assert_eq!(r.status().as_u16(), 405);
        c.close().await;
    }
}
