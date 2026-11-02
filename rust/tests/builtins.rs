//! Builtin tool tests — mirrors golang/builtin_test.go, builtin_hostboundary_test.go
//! and host_builtin_extras_test.go. Hermetic: temp dirs + a local TCP server.

use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use toolnexus::builtins::{BackgroundShells, WebSearchResult, create_builtin_tools};
use toolnexus::{
    Answer, BuiltinsConfig, Tool, ToolContext, ToolResult, ToolSource, ToolkitOptions,
    create_toolkit, define_tool, pending_of, select_builtins,
};

const BUILTIN_NAMES: [&str; 10] = [
    "bash",
    "read",
    "write",
    "edit",
    "grep",
    "glob",
    "webfetch",
    "question",
    "apply_patch",
    "todowrite",
];

fn named(cfg: &BuiltinsConfig, name: &str) -> Tool {
    create_builtin_tools(cfg)
        .into_iter()
        .find(|t| t.name == name)
        .unwrap_or_else(|| panic!("builtin {name} not found"))
}

fn tool(name: &str) -> Tool {
    named(&BuiltinsConfig::default(), name)
}

async fn run(t: &Tool, args: Value) -> ToolResult {
    t.call(args, ToolContext::default()).await
}

fn meta<'a>(r: &'a ToolResult, k: &str) -> &'a Value {
    r.metadata
        .as_ref()
        .and_then(|m| m.get(k))
        .unwrap_or(&Value::Null)
}

fn tmp() -> tempdir::Dir {
    tempdir::Dir::new()
}

mod tempdir {
    use std::path::{Path, PathBuf};
    use std::sync::atomic::{AtomicU64, Ordering};
    static N: AtomicU64 = AtomicU64::new(0);
    pub struct Dir(PathBuf);
    impl Dir {
        pub fn new() -> Self {
            let p = std::env::temp_dir().join(format!(
                "tn-builtins-{}-{}",
                std::process::id(),
                N.fetch_add(1, Ordering::SeqCst)
            ));
            let _ = std::fs::remove_dir_all(&p);
            std::fs::create_dir_all(&p).unwrap();
            Dir(std::fs::canonicalize(&p).unwrap())
        }
        pub fn path(&self) -> &Path {
            &self.0
        }
        pub fn s(&self) -> String {
            self.0.to_string_lossy().into_owned()
        }
        pub fn join(&self, p: &str) -> String {
            self.0.join(p).to_string_lossy().into_owned()
        }
    }
    impl Drop for Dir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
}

// ---------------------------------------------------------------------------
// toggles + toolkit assembly (builtin_test.go)
// ---------------------------------------------------------------------------

#[test]
fn builtins_enabled_precedence() {
    let cases = [
        (json!(null), true),
        (json!(true), true),
        (json!(false), false),
        (json!({}), true),
        (json!({"enabled": false}), false),
        (json!({"disabled": true}), false),
        (json!({"enabled": true, "disabled": true}), false),
    ];
    for (v, want) in cases {
        assert_eq!(BuiltinsConfig::from_value(&v).is_enabled(), want, "{v}");
    }
}

#[tokio::test]
async fn toolkit_default_builtins_and_schema() {
    let tk = create_toolkit(ToolkitOptions::default()).await.unwrap();
    for n in BUILTIN_NAMES {
        let t = tk.get(n).unwrap_or_else(|| panic!("{n} missing"));
        assert_eq!(t.source, ToolSource::Builtin);
        assert!(t.hints.is_some(), "{n} has hints");
    }
    let names: Vec<String> = tk
        .to_openai()
        .iter()
        .map(|f| f["function"]["name"].as_str().unwrap().to_owned())
        .collect();
    for n in BUILTIN_NAMES {
        assert!(names.iter().any(|x| x == n), "{n} in openai schema");
    }
}

#[tokio::test]
async fn toolkit_builtins_toggle_off() {
    for off in [
        BuiltinsConfig::off(),
        BuiltinsConfig {
            disabled: Some(true),
            ..Default::default()
        },
        BuiltinsConfig {
            enabled: Some(false),
            ..Default::default()
        },
    ] {
        let extra = define_tool("myextra", "", None, |_, _| async {
            Ok::<_, String>("x".into())
        });
        let tk = create_toolkit(ToolkitOptions {
            skills_dir: vec!["../examples/skills".into()],
            extra_tools: vec![extra],
            builtins: Some(off),
            ..Default::default()
        })
        .await
        .unwrap();
        for n in BUILTIN_NAMES {
            assert!(tk.get(n).is_none(), "{n} should be absent");
        }
        assert!(tk.get("skill").is_some());
        assert!(tk.get("myextra").is_some());
    }
}

#[tokio::test]
async fn toolkit_tool_map_disables_named_and_source_off_wins() {
    let mut cfg = BuiltinsConfig::default();
    for n in ["bash", "write", "nope"] {
        cfg.tools.insert(n.into(), false);
    }
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(cfg),
        ..Default::default()
    })
    .await
    .unwrap();
    for n in BUILTIN_NAMES {
        assert_eq!(tk.get(n).is_some(), n != "bash" && n != "write", "{n}");
    }

    let mut off = BuiltinsConfig {
        disabled: Some(true),
        ..Default::default()
    };
    off.tools.insert("read".into(), true);
    let tk = create_toolkit(ToolkitOptions {
        builtins: Some(off),
        ..Default::default()
    })
    .await
    .unwrap();
    for n in BUILTIN_NAMES {
        assert!(tk.get(n).is_none());
    }
}

#[tokio::test]
async fn toolkit_reads_builtins_key_from_parsed_config() {
    let tk = create_toolkit(ToolkitOptions {
        mcp_config: Some(toolnexus::McpConfigInput::Json(
            json!({"mcpServers": {}, "builtins": {"tools": {"bash": false}}}),
        )),
        ..Default::default()
    })
    .await
    .unwrap();
    assert!(tk.get("bash").is_none());
    assert!(tk.get("read").is_some());
}

#[tokio::test]
async fn toolkit_extra_shadows_builtin() {
    let mine = define_tool("read", "host read", None, |_, _| async {
        Ok::<_, String>("HOST_READ".into())
    });
    let tk = create_toolkit(ToolkitOptions {
        extra_tools: vec![mine],
        ..Default::default()
    })
    .await
    .unwrap();
    assert_eq!(tk.get("read").unwrap().source, ToolSource::Native);
    let r = tk.execute("read", json!({}), ToolContext::default()).await;
    assert_eq!(r.output, "HOST_READ");
}

// ---------------------------------------------------------------------------
// file tools
// ---------------------------------------------------------------------------

#[tokio::test]
async fn read_write_edit() {
    let d = tmp();
    let file = d.join("note.txt");
    let w = run(
        &tool("write"),
        json!({"path": file, "content": "alpha\nbeta\nalpha\n"}),
    )
    .await;
    assert!(!w.is_error, "{}", w.output);
    assert_eq!(w.output, format!("Wrote 17 bytes to {file}"));
    assert_eq!(meta(&w, "bytes"), &json!(17));
    assert_eq!(meta(&w, "files")[0]["status"], "added");

    let r = run(&tool("read"), json!({"path": file})).await;
    assert_eq!(r.output, "alpha\nbeta\nalpha\n");
    let win = run(
        &tool("read"),
        json!({"path": file, "offset": 2, "limit": 1}),
    )
    .await;
    assert_eq!(win.output, "beta");

    let dup = run(
        &tool("edit"),
        json!({"path": file, "oldString": "alpha", "newString": "X"}),
    )
    .await;
    assert!(dup.is_error && dup.output.contains("not unique"));
    assert_eq!(
        dup.output,
        format!("edit: oldString is not unique in {file} (2 occurrences); use replaceAll")
    );
    assert_eq!(
        std::fs::read_to_string(&file).unwrap(),
        "alpha\nbeta\nalpha\n"
    );

    let nf = run(
        &tool("edit"),
        json!({"path": file, "oldString": "zzz", "newString": "X"}),
    )
    .await;
    assert!(nf.is_error && nf.output.contains("not found"));

    let ra = run(
        &tool("edit"),
        json!({"path": file, "oldString": "alpha", "newString": "OMEGA", "replaceAll": true}),
    )
    .await;
    assert_eq!(ra.output, format!("Edited {file} (2 replacements)"));
    assert_eq!(
        std::fs::read_to_string(&file).unwrap(),
        "OMEGA\nbeta\nOMEGA\n"
    );

    let one = run(
        &tool("edit"),
        json!({"path": file, "oldString": "beta", "newString": "gamma"}),
    )
    .await;
    assert_eq!(one.output, format!("Edited {file} (1 replacement)"));
    assert_eq!(
        meta(&one, "files"),
        &json!([{"path": file, "status": "modified", "additions": 1, "deletions": 1}])
    );
    assert_eq!(
        meta(&one, "diff").as_str().unwrap(),
        format!("--- a/{file}\n+++ b/{file}\n@@ -1,3 +1,3 @@\n OMEGA\n-beta\n+gamma\n OMEGA\n")
    );

    let miss = run(&tool("read"), json!({"path": d.join("nope.txt")})).await;
    assert!(miss.is_error);
    assert!(miss.output.starts_with("read: open "), "{}", miss.output);
}

#[tokio::test]
async fn read_media_and_invalid_utf8() {
    let d = tmp();
    std::fs::write(d.join("a.png"), [0x89, b'P', b'N', b'G']).unwrap();
    std::fs::write(d.join("doc.pdf"), b"%PDF").unwrap();
    std::fs::write(d.join("bin.dat"), [0xff, 0xfe]).unwrap();
    let cfg = BuiltinsConfig {
        base_dir: d.s(),
        ..Default::default()
    };
    let r = run(&named(&cfg, "read"), json!({"path": "a.png"})).await;
    assert_eq!(r.output, "a.png (image/png, 4 bytes)");
    assert_eq!(r.parts[0].kind, "image");
    assert_eq!(r.parts[0].data, "iVBORw==");
    let r = run(&named(&cfg, "read"), json!({"path": "doc.pdf"})).await;
    assert_eq!(r.parts[0].kind, "file");
    assert_eq!(r.parts[0].name, "doc.pdf");
    let r = run(&named(&cfg, "read"), json!({"path": "bin.dat"})).await;
    assert!(r.is_error);
    assert_eq!(
        r.output,
        "read: bin.dat is not valid UTF-8 text and its extension is not a recognised media type"
    );
}

#[tokio::test]
async fn grep_and_glob() {
    let d = tmp();
    std::fs::write(d.join("a.txt"), "needle here\nplain\n").unwrap();
    std::fs::write(d.join("b.txt"), "no match\nneedle again\n").unwrap();
    std::fs::create_dir(d.join("sub")).unwrap();
    std::fs::write(d.join("sub/c.md"), "needle in md\n").unwrap();
    std::fs::create_dir(d.join("node_modules")).unwrap();
    std::fs::write(d.join("node_modules/x.txt"), "needle hidden\n").unwrap();

    let g = run(&tool("grep"), json!({"pattern": "needle", "path": d.s()})).await;
    assert_eq!(meta(&g, "count"), &json!(3));
    assert_eq!(
        g.output,
        "a.txt:1:needle here\nb.txt:2:needle again\nsub/c.md:1:needle in md"
    );
    let gi = run(
        &tool("grep"),
        json!({"pattern": "needle", "path": d.s(), "include": "*.txt"}),
    )
    .await;
    assert_eq!(meta(&gi, "count"), &json!(2));

    let gl = run(&tool("glob"), json!({"pattern": "*.txt", "path": d.s()})).await;
    assert_eq!(meta(&gl, "count"), &json!(2));
    assert_eq!(gl.output, "a.txt\nb.txt");
    let md = run(&tool("glob"), json!({"pattern": "**/*.md", "path": d.s()})).await;
    assert_eq!(md.output, "sub/c.md");
    let capped = run(
        &tool("glob"),
        json!({"pattern": "*", "path": d.s(), "limit": 1}),
    )
    .await;
    assert_eq!(capped.output, "a.txt");
}

#[tokio::test]
async fn grep_context_and_truncation() {
    let d = tmp();
    std::fs::write(d.join("f.txt"), "a\nb\nHIT1\nc\nd\ne\nf\nHIT2\ng\n").unwrap();
    let cfg = BuiltinsConfig {
        base_dir: d.s(),
        ..Default::default()
    };
    let grep = named(&cfg, "grep");
    let r = run(&grep, json!({"pattern": "HIT", "context": 1})).await;
    assert_eq!(
        r.output,
        "f.txt-2-b\nf.txt:3:HIT1\nf.txt-4-c\n--\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g"
    );
    let r = run(&grep, json!({"pattern": "HIT", "before": 0, "after": 4})).await;
    assert_eq!(
        r.output,
        "f.txt:3:HIT1\nf.txt-4-c\nf.txt-5-d\nf.txt-6-e\nf.txt-7-f\nf.txt:8:HIT2\nf.txt-9-g"
    );
    let r = run(&grep, json!({"pattern": "HIT", "limit": 1})).await;
    assert_eq!(r.output, "f.txt:3:HIT1");
    assert_eq!(meta(&r, "truncated"), &json!(true));
    assert_eq!(meta(&r, "total"), &json!(2));
    let r = run(&named(&cfg, "read"), json!({"path": "f.txt", "limit": 2})).await;
    assert_eq!(r.output, "a\nb");
    assert_eq!(meta(&r, "truncated"), &json!(true));
    assert_eq!(meta(&r, "totalLines"), &json!(9));
    assert_eq!(meta(&r, "nextOffset"), &json!(3));
}

#[tokio::test]
async fn grep_emits_walk_root_relative_forward_slashed_paths() {
    let d = tmp();
    std::fs::create_dir_all(d.path().join("tree/sub")).unwrap();
    std::fs::write(d.join("tree/sub/a.txt"), "needle\n").unwrap();
    let cfg = BuiltinsConfig {
        base_dir: d.s(),
        ..Default::default()
    };
    let r = run(
        &named(&cfg, "grep"),
        json!({"pattern": "needle", "path": "tree"}),
    )
    .await;
    assert_eq!(r.output, "sub/a.txt:1:needle");
}

#[tokio::test]
async fn apply_patch_atomic() {
    let d = tmp();
    let (del, upd, add) = (d.join("gone.txt"), d.join("keep.txt"), d.join("new.txt"));
    std::fs::write(&del, "bye\n").unwrap();
    std::fs::write(&upd, "line one\nline two\nline three\n").unwrap();
    let patch = [
        "*** Begin Patch".to_owned(),
        format!("*** Add File: {add}"),
        "+created line 1".into(),
        "+created line 2".into(),
        format!("*** Update File: {upd}"),
        "@@".into(),
        " line one".into(),
        "-line two".into(),
        "+line TWO changed".into(),
        " line three".into(),
        format!("*** Delete File: {del}"),
        "*** End Patch".into(),
        "".into(),
    ]
    .join("\n");
    let r = run(&tool("apply_patch"), json!({"patchText": patch})).await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(r.output, "Applied patch: 3 file operations");
    assert_eq!(meta(&r, "added"), &json!(1));
    assert_eq!(
        std::fs::read_to_string(&add).unwrap(),
        "created line 1\ncreated line 2"
    );
    assert_eq!(
        std::fs::read_to_string(&upd).unwrap(),
        "line one\nline TWO changed\nline three\n"
    );
    assert!(!Path::new(&del).exists());
    let statuses: Vec<&str> = meta(&r, "files")
        .as_array()
        .unwrap()
        .iter()
        .map(|f| f["status"].as_str().unwrap())
        .collect();
    assert_eq!(statuses, ["added", "modified", "deleted"]);

    let two = d.join("should-not-exist.txt");
    let bad = [
        "*** Begin Patch".to_owned(),
        format!("*** Add File: {two}"),
        "+content".into(),
        format!("*** Update File: {upd}"),
        "@@".into(),
        "-DOES NOT MATCH".into(),
        "+whatever".into(),
        "*** End Patch".into(),
    ]
    .join("\n");
    let r = run(&tool("apply_patch"), json!({"patchText": bad})).await;
    assert!(r.is_error);
    assert_eq!(r.output, "apply_patch: hunk does not match file contents");
    assert!(!Path::new(&two).exists());
}

#[tokio::test]
async fn question_suspends_then_returns_answer_json() {
    let questions = json!([
        {"question": "Pick a color?", "header": "Choice", "options": ["red", "green"], "multiple": false},
        {"question": "Confirm?"}
    ]);
    let q = run(&tool("question"), json!({"questions": questions})).await;
    assert!(q.is_error);
    let req = pending_of(&q).expect("pending");
    assert_eq!(req.kind, "question");
    assert_eq!(req.prompt, "Pick a color? (options: red, green)\nConfirm?");
    assert_eq!(req.data.as_ref().unwrap()["questions"], questions);

    let mut data = serde_json::Map::new();
    data.insert("answers".into(), json!(["red"]));
    let ctx = ToolContext {
        answer: Some(Answer {
            id: req.id.clone(),
            ok: true,
            data: Some(data),
            ..Default::default()
        }),
        ..Default::default()
    };
    let a = tool("question")
        .call(json!({"questions": questions}), ctx)
        .await;
    assert!(!a.is_error);
    assert_eq!(a.output, r#"{"answers":["red"]}"#);

    let ctx = ToolContext {
        answer: Some(Answer {
            id: req.id,
            ok: true,
            ..Default::default()
        }),
        ..Default::default()
    };
    let e = tool("question").call(json!({}), ctx).await;
    assert_eq!(e.output, "{}");
}

#[tokio::test]
async fn todowrite_renders() {
    let todos = json!([
        {"id": "1", "text": "write code", "completed": true},
        {"id": "2", "text": "ship it", "completed": false}
    ]);
    let r = run(&tool("todowrite"), json!({"todos": todos})).await;
    assert_eq!(r.output, "[x] write code\n[ ] ship it");
    assert_eq!(meta(&r, "todos"), &todos);
    let r = run(&tool("todowrite"), json!({"todos": []})).await;
    assert_eq!(r.output, "(no todos)");
}

// ---------------------------------------------------------------------------
// webfetch over a local server
// ---------------------------------------------------------------------------

async fn serve_once_per_conn() -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = l.local_addr().unwrap();
    tokio::spawn(async move {
        loop {
            let Ok((mut s, _)) = l.accept().await else {
                return;
            };
            tokio::spawn(async move {
                let mut buf = vec![0u8; 4096];
                let n = s.read(&mut buf).await.unwrap_or(0);
                let req = String::from_utf8_lossy(&buf[..n]);
                let (status, body) = if req.starts_with("GET /ok") {
                    (
                        "200 OK",
                        "<html><script>x</script><p>fetched-body-content</p></html>",
                    )
                } else {
                    ("500 Internal Server Error", "boom")
                };
                let resp = format!(
                    "HTTP/1.1 {status}\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = s.write_all(resp.as_bytes()).await;
            });
        }
    });
    format!("http://{addr}")
}

#[tokio::test]
async fn webfetch_ok_and_error() {
    let base = serve_once_per_conn().await;
    let ok = run(
        &tool("webfetch"),
        json!({"url": format!("{base}/ok"), "format": "text"}),
    )
    .await;
    assert!(!ok.is_error, "{}", ok.output);
    assert_eq!(ok.output, "fetched-body-content");
    assert_eq!(meta(&ok, "status"), &json!(200));
    let html = run(
        &tool("webfetch"),
        json!({"url": format!("{base}/ok"), "format": "html"}),
    )
    .await;
    assert!(html.output.contains("<script>"));
    let bad = run(&tool("webfetch"), json!({"url": format!("{base}/err")})).await;
    assert!(bad.is_error);
    assert_eq!(bad.output, "HTTP 500");
}

// ---------------------------------------------------------------------------
// bash + host boundary (builtin_hostboundary_test.go)
// ---------------------------------------------------------------------------

#[tokio::test]
async fn bash_basic() {
    let ok = run(&tool("bash"), json!({"command": "printf 'hello-bash'"})).await;
    assert!(!ok.is_error);
    assert_eq!(ok.output, "hello-bash");
    assert_eq!(meta(&ok, "exitCode"), &json!(0));
    let bad = run(&tool("bash"), json!({"command": "exit 3"})).await;
    assert!(bad.is_error && bad.output.contains("code 3"));
    assert_eq!(bad.output, "\nbash: command exited with code 3");
    assert_eq!(meta(&bad, "exitCode"), &json!(3));
    let d = tmp();
    let cwd = run(&tool("bash"), json!({"command": "pwd", "workdir": d.s()})).await;
    assert!(
        cwd.output
            .contains(d.path().file_name().unwrap().to_str().unwrap())
    );
}

#[cfg(unix)]
#[tokio::test]
async fn bash_timeout_kills_the_whole_job() {
    let d = tmp();
    let marker = d.join("orphan.marker");
    let command = format!("sleep 0.2; sh -c 'sleep 1; touch {marker}'");
    let start = Instant::now();
    let r = run(&tool("bash"), json!({"command": command, "timeout": 300})).await;
    assert!(
        start.elapsed() < Duration::from_millis(1500),
        "returned as soon as the job was gone"
    );
    assert!(r.is_error && r.output.contains("timed out"), "{}", r.output);
    assert!(
        r.output
            .starts_with("bash: command timed out after 300ms\n")
    );
    assert_eq!(meta(&r, "timedOut"), &json!(true));
    assert_eq!(meta(&r, "killedTree"), &json!(true));
    tokio::time::sleep(Duration::from_secs(2)).await;
    assert!(!Path::new(&marker).exists(), "grandchild outlived the kill");
}

#[cfg(unix)]
#[tokio::test]
async fn bash_timeout_probe_control_can_write_marker() {
    let d = tmp();
    let marker = d.join("control.marker");
    let command = format!("sleep 0.2; sh -c 'sleep 1; touch {marker}'");
    let r = run(&tool("bash"), json!({"command": command, "timeout": 20000})).await;
    assert!(!r.is_error, "{}", r.output);
    assert!(Path::new(&marker).exists());
}

#[cfg(unix)]
#[tokio::test]
async fn bash_cancellation_stops_the_work() {
    let d = tmp();
    let marker = d.join("cancelled.marker");
    let command = format!("sleep 0.2; sh -c 'sleep 1; touch {marker}'");
    let ctx = ToolContext::default();
    let c = ctx.cancel.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_millis(300)).await;
        c.cancel();
    });
    let r = tool("bash")
        .call(json!({"command": command, "timeout": 60000}), ctx)
        .await;
    assert!(r.is_error && r.output.contains("cancelled"), "{}", r.output);
    assert_eq!(meta(&r, "timedOut"), &json!(false));
    tokio::time::sleep(Duration::from_secs(2)).await;
    assert!(!Path::new(&marker).exists());
}

#[cfg(unix)]
#[tokio::test]
async fn bash_term_ignoring_job_is_killed_after_grace() {
    let d = tmp();
    let marker = d.join("stubborn.marker");
    let command = format!("trap '' TERM; sleep 0.2; sh -c 'sleep 5; touch {marker}' & wait");
    let start = Instant::now();
    let r = run(&tool("bash"), json!({"command": command, "timeout": 300})).await;
    let el = start.elapsed();
    assert!(r.is_error);
    assert_eq!(meta(&r, "timedOut"), &json!(true));
    assert!(
        el >= Duration::from_secs(2),
        "used the grace window: {el:?}"
    );
    assert!(el <= Duration::from_secs(4), "overran: {el:?}");
    tokio::time::sleep(Duration::from_secs(2)).await;
    assert!(!Path::new(&marker).exists());
}

#[cfg(unix)]
#[tokio::test]
async fn bash_reports_interpreter_and_host_shell_verbatim() {
    let r = run(&tool("bash"), json!({"command": "echo hi"})).await;
    assert_eq!(meta(&r, "shell"), &json!("/bin/sh -c"));
    let cfg = BuiltinsConfig {
        shell: vec!["sh".into(), "-c".into()],
        ..Default::default()
    };
    let r = run(&named(&cfg, "bash"), json!({"command": "echo verbatim"})).await;
    assert!(r.output.contains("verbatim"));
    assert_eq!(meta(&r, "shell"), &json!("sh -c"));
}

#[test]
fn missing_interpreter_off_when_bash_disabled() {
    let mut off = BuiltinsConfig::default();
    off.tools.insert("bash".into(), false);
    let tools = select_builtins(&off).unwrap();
    assert!(tools.iter().all(|t| t.name != "bash"));
    assert_eq!(tools.len(), 9);
}

#[tokio::test]
async fn base_dir_scopes_relative_paths_patch_and_bash() {
    let d = tmp();
    let cfg = BuiltinsConfig {
        base_dir: d.s(),
        ..Default::default()
    };
    let w = run(
        &named(&cfg, "write"),
        json!({"path": "sub/nested.txt", "content": "landed"}),
    )
    .await;
    assert!(!w.is_error, "{}", w.output);
    assert!(d.path().join("sub/nested.txt").exists());
    assert!(!Path::new("sub/nested.txt").exists());
    assert_eq!(meta(&w, "files")[0]["path"], "sub/nested.txt");
    let r = run(&named(&cfg, "read"), json!({"path": "sub/nested.txt"})).await;
    assert_eq!(r.output, "landed");

    let p = "*** Begin Patch\n*** Add File: pkg/new.txt\n+hello\n*** End Patch";
    let r = run(&named(&cfg, "apply_patch"), json!({"patchText": p})).await;
    assert!(!r.is_error, "{}", r.output);
    assert!(d.path().join("pkg/new.txt").exists());

    std::fs::write(d.join("marker.txt"), "x").unwrap();
    let r = run(&named(&cfg, "bash"), json!({"command": "ls marker.txt"})).await;
    assert!(
        !r.is_error && r.output.contains("marker.txt"),
        "{}",
        r.output
    );
}

#[tokio::test]
async fn confinement_refuses_escapes_symlinks_and_new_paths() {
    let base = tmp();
    let outside = tmp();
    std::fs::write(outside.join("secret.txt"), "secret").unwrap();
    let cfg = BuiltinsConfig {
        base_dir: base.s(),
        confine_to_base_dir: true,
        ..Default::default()
    };
    let read = named(&cfg, "read");
    for p in ["../escape.txt".to_owned(), outside.join("secret.txt")] {
        let r = run(&read, json!({"path": p})).await;
        assert!(
            r.is_error && r.output.contains("outside baseDir"),
            "{p}: {}",
            r.output
        );
    }
    std::fs::write(base.join("ok.txt"), "fine").unwrap();
    assert!(!run(&read, json!({"path": "ok.txt"})).await.is_error);

    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(outside.path(), base.path().join("link")).unwrap();
        let r = run(&read, json!({"path": "link/secret.txt"})).await;
        assert!(r.is_error, "symlink escape refused");
    }

    let write = named(&cfg, "write");
    let r = run(&write, json!({"path": "../escape.txt", "content": "x"})).await;
    assert!(r.is_error);
    let r = run(&write, json!({"path": "deep/new/file.txt", "content": "x"})).await;
    assert!(!r.is_error, "{}", r.output);

    let empty = BuiltinsConfig {
        confine_to_base_dir: true,
        ..Default::default()
    };
    let r = run(&named(&empty, "read"), json!({"path": "x"})).await;
    assert_eq!(
        r.output,
        "read: confineToBaseDir is set but baseDir is empty"
    );
}

// ---------------------------------------------------------------------------
// opt-in extras (host_builtin_extras_test.go)
// ---------------------------------------------------------------------------

#[test]
fn opt_in_builtins_absent_by_default() {
    for t in create_builtin_tools(&BuiltinsConfig::default()) {
        assert!(
            !["bash_output", "kill_shell", "websearch", "notebook_edit"].contains(&t.name.as_str())
        );
        if t.name == "bash" {
            assert!(
                t.input_schema["properties"]
                    .get("run_in_background")
                    .is_none()
            );
        }
    }
}

#[cfg(unix)]
#[tokio::test]
async fn background_shell_lifecycle() {
    let bg = Arc::new(BackgroundShells::new());
    let cfg = BuiltinsConfig {
        background: Some(bg.clone()),
        kill_grace_ms: 100,
        ..Default::default()
    };
    let (bash, out, kill) = (
        named(&cfg, "bash"),
        named(&cfg, "bash_output"),
        named(&cfg, "kill_shell"),
    );
    assert!(bash.input_schema["properties"]["run_in_background"].is_object());
    let r = run(
        &bash,
        json!({"command": "echo one; sleep 0.2; echo two", "run_in_background": true}),
    )
    .await;
    let id = meta(&r, "backgroundId").as_str().unwrap().to_owned();
    assert!(!r.is_error);
    assert_eq!(
        r.output,
        format!(
            "Started background shell {id}. Read its output with bash_output, stop it with kill_shell."
        )
    );
    let deadline = Instant::now() + Duration::from_secs(5);
    let mut all = String::new();
    loop {
        let o = run(&out, json!({"id": id})).await;
        all.push_str(o.output.split("\n[status:").next().unwrap());
        if meta(&o, "status") == "exited" {
            assert_eq!(meta(&o, "exitCode"), &json!(0));
            assert!(o.output.ends_with("[status: exited with code 0]"));
            break;
        }
        assert!(Instant::now() < deadline, "never exited");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    assert_eq!(all.matches("one").count(), 1, "{all}");
    assert_eq!(all.matches("two").count(), 1, "{all}");

    let r = run(
        &bash,
        json!({"command": "trap '' TERM; sleep 30 & wait", "run_in_background": true}),
    )
    .await;
    let id2 = meta(&r, "backgroundId").as_str().unwrap().to_owned();
    tokio::time::sleep(Duration::from_millis(100)).await;
    let start = Instant::now();
    let k = run(&kill, json!({"id": id2})).await;
    assert!(!k.is_error);
    assert_eq!(meta(&k, "killed"), &json!(true));
    assert!(start.elapsed() < Duration::from_secs(2));
    let o = run(&out, json!({"id": id2})).await;
    assert_eq!(meta(&o, "status"), "killed");
    let k2 = run(&kill, json!({"id": id2})).await;
    assert_eq!(meta(&k2, "killed"), &json!(false));
    assert_eq!(
        k2.output,
        format!("Background shell {id2} had already killed")
    );
    let e = run(&out, json!({"id": "nope"})).await;
    assert!(e.is_error);
    assert_eq!(e.output, "bash_output: no background shell \"nope\"");
    assert_eq!(bg.list().len(), 2);
    bg.close_all().await;
}

#[cfg(unix)]
#[tokio::test]
async fn background_shell_signalled_from_outside_is_minus_one() {
    let bg = Arc::new(BackgroundShells::new());
    let cfg = BuiltinsConfig {
        background: Some(bg.clone()),
        kill_grace_ms: 100,
        ..Default::default()
    };
    let (bash, out) = (named(&cfg, "bash"), named(&cfg, "bash_output"));
    let r = run(
        &bash,
        json!({"command": "kill -TERM $$; sleep 5", "run_in_background": true}),
    )
    .await;
    let id = meta(&r, "backgroundId").as_str().unwrap().to_owned();
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let o = run(&out, json!({"id": id})).await;
        if meta(&o, "status") == "exited" {
            assert_eq!(meta(&o, "exitCode"), &json!(-1));
            assert!(o.output.contains("[status: exited with code -1]"));
            break;
        }
        assert!(Instant::now() < deadline);
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    bg.close_all().await;
}

#[tokio::test]
async fn websearch_provider() {
    let got: Arc<Mutex<(String, usize)>> = Arc::default();
    let g = got.clone();
    let cfg = BuiltinsConfig {
        web_search: Some(Arc::new(move |q, n, _c| {
            *g.lock().unwrap() = (q, n);
            Box::pin(async {
                Ok(vec![
                    WebSearchResult {
                        title: "A".into(),
                        url: "https://a.example".into(),
                        snippet: "sa".into(),
                    },
                    WebSearchResult {
                        title: "B".into(),
                        url: "https://b.example".into(),
                        snippet: String::new(),
                    },
                ])
            })
        })),
        ..Default::default()
    };
    let ws = named(&cfg, "websearch");
    let h = ws.hints.unwrap();
    assert!(h.read_only && h.open_world);
    let r = run(&ws, json!({"query": "go", "limit": 1})).await;
    assert_eq!(*got.lock().unwrap(), ("go".to_owned(), 1));
    assert_eq!(r.output, "1. A\n   https://a.example\n   sa");
    let r = run(&ws, json!({"query": " "})).await;
    assert_eq!(r.output, "websearch: query is required");
}

#[tokio::test]
async fn notebook_edit() {
    let d = tmp();
    let nb = r##"{"cells":[{"cell_type":"code","id":"a","metadata":{},"source":["x = 1\n"],"outputs":[{"output_type":"stream","text":["1"]}],"execution_count":3},{"cell_type":"markdown","id":"b","metadata":{},"source":["# T"]}],"metadata":{},"nbformat":4,"nbformat_minor":5}"##;
    let p = d.join("n.ipynb");
    std::fs::write(&p, nb).unwrap();
    let cfg = BuiltinsConfig {
        notebook_edit: true,
        base_dir: d.s(),
        ..Default::default()
    };
    let ne = named(&cfg, "notebook_edit");
    let load = || -> Vec<Value> {
        let v: Value = serde_json::from_str(&std::fs::read_to_string(&p).unwrap()).unwrap();
        v["cells"].as_array().unwrap().clone()
    };
    let r = run(
        &ne,
        json!({"path": "n.ipynb", "cell_id": "a", "new_source": "x = 2\ny = 3"}),
    )
    .await;
    assert!(!r.is_error, "{}", r.output);
    assert_eq!(r.output, "Replaced cell 0 in n.ipynb");
    let c0 = &load()[0];
    assert_eq!(c0["outputs"], json!([]));
    assert!(c0["execution_count"].is_null());
    assert_eq!(c0["source"], json!(["x = 2\n", "y = 3"]));
    // Go MarshalIndent with a one-space indent + trailing newline
    assert!(
        std::fs::read_to_string(&p)
            .unwrap()
            .starts_with("{\n \"cells\": [\n  {\n")
    );

    let r = run(
        &ne,
        json!({"path": "n.ipynb", "cell_index": 0, "edit_mode": "insert", "cell_type": "markdown", "new_source": "hi"}),
    )
    .await;
    assert_eq!(r.output, "Inserted markdown cell at 1 in n.ipynb");
    let cells = load();
    assert_eq!(cells.len(), 3);
    assert_eq!(cells[1]["cell_type"], "markdown");
    let r = run(
        &ne,
        json!({"path": "n.ipynb", "cell_id": "b", "edit_mode": "delete"}),
    )
    .await;
    assert!(!r.is_error && load().len() == 2 && meta(&r, "files").is_array());
    let r = run(&ne, json!({"path": "n.ipynb", "cell_id": "zz"})).await;
    assert!(r.is_error);
    assert_eq!(r.output, "notebook_edit: no cell with id \"zz\"");
}

#[test]
fn hints_on_every_builtin() {
    let tools = create_builtin_tools(&BuiltinsConfig::default());
    let h = |n: &str| tools.iter().find(|t| t.name == n).unwrap().hints.unwrap();
    assert!(h("bash").destructive && h("bash").open_world && !h("bash").read_only);
    assert!(h("read").read_only && !h("read").destructive);
    assert!(h("write").destructive);
    assert!(h("webfetch").read_only && h("webfetch").open_world);
    assert!(h("question").read_only && h("todowrite").read_only);
}

/// Byte-identical to Go's addDiffMeta for the same before/after (captured from
/// golang/builtin_diff.go): two hunks, a trailing line without a newline.
#[tokio::test]
async fn diff_bytes_match_go() {
    let d = tmp();
    std::fs::write(d.join("p"), "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\nk\n").unwrap();
    let cfg = BuiltinsConfig {
        base_dir: d.s(),
        ..Default::default()
    };
    let r = run(
        &named(&cfg, "write"),
        json!({"path": "p", "content": "a\nB\nc\nd\ne\nf\ng\nh\ni\nJ\nk\nl"}),
    )
    .await;
    assert_eq!(
        meta(&r, "diff").as_str().unwrap(),
        "--- a/p\n+++ b/p\n@@ -1,5 +1,5 @@\n a\n-b\n+B\n c\n d\n e\n@@ -7,5 +7,6 @@\n g\n h\n i\n-j\n+J\n k\n+l\n"
    );
    assert_eq!(
        meta(&r, "files"),
        &json!([{"path": "p", "status": "modified", "additions": 3, "deletions": 2}])
    );
}
