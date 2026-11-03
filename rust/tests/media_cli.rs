//! SPEC §7G.10 conformance: replays `../conformance/media/cli.json` (Grok and
//! Codex CLI clients) over the portable fake CLI
//! `conformance/media/fake-cli/fake-cli.sh`, spawned with `/bin/sh`.
#![cfg(unix)]

mod common;
mod media_support;

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use media_support::*;
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;
use toolnexus::{
    CliMediaClient, CodexMediaClient, GrokMediaClient, MediaClientConfig, MediaError, ToolContext,
    create_media,
};

fn build(kind: &str, cfg: MediaClientConfig) -> CliMediaClient {
    match kind {
        "grok" => GrokMediaClient::new(cfg),
        "codex" => CodexMediaClient::new(cfg),
        other => panic!("unknown client {other}"),
    }
}

struct Run {
    out: PathBuf,
    dir: PathBuf,
    case: Value,
    cfg: MediaClientConfig,
    prog: Progress,
}

/// One harness setup (fake-cli/README.md steps 1–3).
fn setup(case: &Value) -> Run {
    let tmp = temp_dir("cli");
    let fixture = tmp.join("fixture.jsonl");
    let lines: Vec<&str> = case["fixture"]
        .as_array()
        .unwrap()
        .iter()
        .map(|l| l.as_str().unwrap())
        .collect();
    std::fs::write(&fixture, lines.join("\n")).unwrap();
    let out = tmp.join("out");
    std::fs::create_dir_all(&out).unwrap();
    let dir = tmp.join("dir");
    let out_s = out.to_string_lossy().into_owned();
    let dir_s = dir.to_string_lossy().into_owned();
    let c = subst(
        case,
        &[("{out}", out_s.as_str()), ("{dir}", dir_s.as_str())],
    );
    set_env(&c, &[]);
    let (mut cfg, prog) = config(c.get("config"));
    cfg.command = match c.get("command").and_then(Value::as_array) {
        Some(cmd) => cmd
            .iter()
            .map(|x| x.as_str().unwrap().to_string())
            .collect(),
        None => vec![
            "/bin/sh".into(),
            conformance("fake-cli/fake-cli.sh")
                .canonicalize()
                .unwrap()
                .to_string_lossy()
                .into_owned(),
            fixture.to_string_lossy().into_owned(),
            out_s,
        ],
    };
    Run {
        out,
        dir,
        case: c,
        cfg,
        prog,
    }
}

fn check_argv(name: &str, run: &Run) {
    let c = &run.case;
    if c.get("expectNoSpawn").and_then(Value::as_bool) == Some(true) {
        assert!(!run.out.join("argv.txt").exists(), "{name}: spawned");
    }
    if let Some(want) = c.get("expectArgv").and_then(Value::as_array) {
        let argv = std::fs::read_to_string(run.out.join("argv.txt")).unwrap();
        let got: Vec<&str> = argv.lines().collect();
        let cwd = std::fs::read_to_string(run.out.join("cwd.txt")).unwrap();
        let cwd = cwd.trim_end_matches('\n');
        assert_eq!(got.len(), want.len(), "{name}: argv {got:?}");
        for (g, w) in got.iter().zip(want) {
            let w = w.as_str().unwrap();
            if w == "{cwd}" {
                assert_eq!(*g, cwd, "{name}: --cwd (D17: byte for byte)");
            } else {
                assert_eq!(*g, w, "{name}: argv");
            }
        }
    }
    if let Some(env) = c.get("expectEnv").and_then(Value::as_object)
        && let Some(want) = env.get("GROK_CONFIG")
    {
        let got = std::fs::read_to_string(run.out.join("env-GROK_CONFIG.txt")).unwrap();
        assert_eq!(got, want.as_str().unwrap(), "{name}: GROK_CONFIG");
    }
}

#[tokio::test]
async fn conformance_cli() {
    let f = load("cli.json");
    let cases = f["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 24, "cli.json case count");
    let mut tool_faces = 0;
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let kind = case["client"].as_str().unwrap();
        let verb = case["verb"].as_str().unwrap();

        // function face
        let run = setup(case);
        let media = create_media([build(kind, run.cfg.clone())]).unwrap();
        let args = run.case["args"].as_object().unwrap().clone();
        let t0 = Instant::now();
        let got = generate_case(&media, &run.case, verb, args).await;
        let elapsed = t0.elapsed();
        if let Some(max) = case.get("maxElapsedMs").and_then(Value::as_u64) {
            assert!(
                elapsed < Duration::from_millis(max),
                "{name}: took {elapsed:?}"
            );
        }
        check_outcome(name, &run.case, &got, kind, verb, &run.dir);
        check_argv(name, &run);
        check_progress(name, &run.case, &run.prog);
        for p in run
            .case
            .get("expectExists")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            assert!(
                Path::new(p.as_str().unwrap()).exists(),
                "{name}: {p} missing"
            );
        }
        for p in run
            .case
            .get("expectMissing")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            assert!(!Path::new(p.as_str().unwrap()).exists(), "{name}: {p} kept");
        }
        clear_env(&run.case);

        // tool face on a fresh <out>
        if let Some(want) = case.get("tool") {
            tool_faces += 1;
            let run = setup(case);
            let out_s = run.out.to_string_lossy().into_owned();
            let want = subst(want, &[("{out}", out_s.as_str())]);
            let media = create_media([build(kind, run.cfg.clone())]).unwrap();
            let tname = tool_name(&want, verb);
            let tool = media
                .tools()
                .into_iter()
                .find(|t| t.name == tname)
                .unwrap_or_else(|| panic!("{name}: no tool {tname}"));
            let r = tool
                .call(run.case["args"].clone(), ToolContext::default())
                .await;
            check_tool(name, &want, &r, &run.dir);
            check_argv(name, &run);
            clear_env(&run.case);
        }
    }
    assert_eq!(tool_faces, 19, "cli.json tool-face count");
}

#[tokio::test]
async fn cancel_kills_the_cli() {
    let case = json!({"fixture": ["{\"type\":\"text\",\"data\":\"thinking\"}", "#hang"],
                      "config": {"gather": {"dir": "{dir}"}}});
    let run = setup(&case);
    let media = create_media([GrokMediaClient::new(run.cfg.clone())]).unwrap();
    let tok = CancellationToken::new();
    let t2 = tok.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(300)).await;
        t2.cancel();
    });
    let mut args = Map::new();
    args.insert("prompt".into(), json!("x"));
    let t0 = Instant::now();
    let e = media
        .generate_with("image", args, None, &tok)
        .await
        .unwrap_err();
    assert_eq!(e, MediaError::Cancelled);
    assert!(t0.elapsed() < Duration::from_secs(5));
}
