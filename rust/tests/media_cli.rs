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
    GrokMediaClient, MediaClient, MediaClientConfig, MediaError, MediaStyle, ToolContext,
    create_media, generate_media, load_media_style_with,
};

/// The built-in preset a case names.
fn preset_of(case: &Value) -> String {
    case["style"].as_str().unwrap().to_string()
}

fn build(preset: &str, cfg: MediaClientConfig) -> MediaStyle {
    load_media_style_with(preset, cfg).unwrap()
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

const TOOL_FACES: usize = 30;

/// Cases set process env (CODEX_HOME, …): one env-touching test at a time.
static ENV_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

#[tokio::test]
async fn conformance_cli() {
    let _env = ENV_LOCK.lock().await;
    let f = load("cli.json");
    let cases = f["cases"].as_array().unwrap();
    assert_eq!(cases.len(), 35, "cli.json case count");
    let mut tool_faces = 0;
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let preset = preset_of(case);
        let style = build(&preset, MediaClientConfig::default());
        let kind = style.name().to_string();
        let kind = kind.as_str();
        let verb = case["verb"].as_str().unwrap();

        // function face
        let run = setup(case);
        let media = create_media([build(&preset, run.cfg.clone())]).unwrap();
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
        if case.get("oneShot").and_then(Value::as_bool) == Some(true) {
            let args = run.case["args"].as_object().unwrap().clone();
            let got = generate_media(&preset, verb, args, run.cfg.clone()).await;
            check_outcome(name, &run.case, &got, kind, verb, &run.dir);
        }
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
            let dir_s = run.dir.to_string_lossy().into_owned();
            let want = subst(
                want,
                &[("{out}", out_s.as_str()), ("{dir}", dir_s.as_str())],
            );
            let media = create_media([build(&preset, run.cfg.clone())]).unwrap();
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
    assert_eq!(tool_faces, TOOL_FACES, "cli.json tool-face count");
}

#[tokio::test]
async fn conformance_cli_verbs() {
    let f = load("cli.json");
    let cases = f["verbCases"].as_array().unwrap();
    assert_eq!(cases.len(), 8, "cli.json verbCases count");
    let tmp = temp_dir("cli-verbs");
    for vc in cases {
        let name = vc["name"].as_str().unwrap();
        let mut srcs: Vec<MediaStyle> = Vec::new();
        for (i, s) in vc["styles"].as_array().unwrap().iter().enumerate() {
            let cfg = MediaClientConfig {
                command: vec!["/nonexistent/toolnexus-must-not-run".into()],
                ..Default::default()
            };
            let path = match s.as_str() {
                Some(p) => p.to_string(),
                None => {
                    let p = tmp.join(format!("{name}-{i}.json"));
                    std::fs::write(&p, s.to_string()).unwrap();
                    p.to_string_lossy().into_owned()
                }
            };
            srcs.push(load_media_style_with(&path, cfg).unwrap());
        }
        let m = create_media(srcs).unwrap();
        let want = |k: &str| -> Vec<String> {
            vc[k]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap().to_string())
                .collect()
        };
        assert_eq!(m.verbs(), want("expectVerbs"), "{name}: verbs");
        let tools: Vec<String> = m.tools().into_iter().map(|t| t.name).collect();
        assert_eq!(tools, want("expectTools"), "{name}: tools");
        let e = m
            .generate(vc["unsupported"].as_str().unwrap(), Map::new())
            .await
            .unwrap_err();
        assert_eq!(
            e.to_string(),
            vc["error"].as_str().unwrap(),
            "{name}: error"
        );
    }
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

#[tokio::test]
async fn conformance_cli_availability() {
    let _env = ENV_LOCK.lock().await;
    let f = load("cli.json");
    let cases = f["availabilityCases"].as_array().unwrap();
    assert_eq!(cases.len(), 7, "cli.json availabilityCases count");
    for (i, case) in cases.iter().enumerate() {
        let name = case["name"].as_str().unwrap();
        let dir = temp_dir("avail").canonicalize().unwrap();
        let dir_s = dir.to_string_lossy().into_owned();
        let c = subst(case, &[("{dir}", dir_s.as_str())]);
        for p in c
            .get("createFiles")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            std::fs::write(p.as_str().unwrap(), b"").unwrap();
        }
        set_env(&c, &[]);
        let (cfg, _) = config(c.get("config"));
        let path = match c["style"].as_str() {
            Some(p) => p.to_string(),
            None => {
                let p = dir.join(format!("style-{i}.json"));
                std::fs::write(&p, c["style"].to_string()).unwrap();
                p.to_string_lossy().into_owned()
            }
        };
        let style = load_media_style_with(&path, cfg).unwrap();
        let t0 = Instant::now();
        let got = style.available();
        assert!(t0.elapsed() < Duration::from_secs(1), "{name}: slow");
        clear_env(&c);
        assert_eq!(serde_json::to_value(&got).unwrap(), c["expect"], "{name}");
    }
}
