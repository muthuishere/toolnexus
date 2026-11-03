//! Shared harness for the media conformance replays (SPEC §7G.11): a scripted
//! fake HTTP server, placeholder substitution, config parsing and the
//! result / request / tool assertions of `conformance/media/*.json`.
#![allow(dead_code)]

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use serde_json::{Map, Value};
use toolnexus::{
    GatherMode, MediaClientConfig, MediaError, MediaGather, MediaLoop, MediaResult, ToolResult,
};

use crate::common::{Req, Resp, serve};

pub fn load(name: &str) -> Value {
    serde_json::from_str(&std::fs::read_to_string(conformance(name)).unwrap()).unwrap()
}

pub fn conformance(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../conformance/media")
        .join(name)
}

pub type Log = Arc<Mutex<Vec<Req>>>;

/// A fresh server answering `script` in order (the last entry repeats);
/// `{server}` substituted in bodies and header values.
pub async fn script_server(script: Vec<Value>) -> (String, Log) {
    let log: Log = Arc::default();
    let l2 = log.clone();
    let base: Arc<OnceLock<String>> = Arc::default();
    let b2 = base.clone();
    let srv = serve(move |r: Req| {
        let log = l2.clone();
        let script = script.clone();
        let base = b2.clone();
        async move {
            let n = {
                let mut g = log.lock().unwrap();
                g.push(r);
                g.len() - 1
            };
            let url = base.get().cloned().unwrap_or_default();
            let e = subst(&script[n.min(script.len() - 1)], &[("{server}", &url)]);
            let status = e["status"].as_u64().unwrap() as u16;
            let mut resp = if let Some(raw) = e.get("rawBody").and_then(Value::as_str) {
                Resp::status(status, raw)
            } else if let Some(b) = e.get("bodyBase64").and_then(Value::as_str) {
                let bytes = STANDARD.decode(b).unwrap();
                Resp::status(status, &String::from_utf8_lossy(&bytes))
            } else {
                let mut r = Resp::json(e["body"].clone());
                r.status = status;
                r
            };
            if let Some(h) = e.get("headers").and_then(Value::as_object) {
                for (k, v) in h {
                    // a scripted Content-Type replaces the default one
                    resp.headers.retain(|(hk, _)| !hk.eq_ignore_ascii_case(k));
                    resp = resp.header(k, v.as_str().unwrap());
                }
            }
            resp
        }
    })
    .await;
    base.set(srv.url.clone()).unwrap();
    (srv.url, log)
}

/// Replace every placeholder in every JSON string.
pub fn subst(v: &Value, pairs: &[(&str, &str)]) -> Value {
    match v {
        Value::String(s) => {
            let mut s = s.clone();
            for (k, r) in pairs {
                s = s.replace(k, r);
            }
            Value::String(s)
        }
        Value::Array(a) => Value::Array(a.iter().map(|x| subst(x, pairs)).collect()),
        Value::Object(m) => Value::Object(
            m.iter()
                .map(|(k, x)| (k.clone(), subst(x, pairs)))
                .collect(),
        ),
        other => other.clone(),
    }
}

pub fn temp_dir(tag: &str) -> PathBuf {
    static SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let base = std::fs::canonicalize(std::env::temp_dir()).unwrap();
    let d = base.join(format!(
        "tn-media-test-{tag}-{}-{}",
        std::process::id(),
        SEQ.fetch_add(1, std::sync::atomic::Ordering::SeqCst)
    ));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

pub type Progress = Arc<Mutex<Vec<String>>>;

/// A client config from a case's `config` JSON, recording progress statuses.
pub fn config(v: Option<&Value>) -> (MediaClientConfig, Progress) {
    let prog: Progress = Arc::default();
    let p2 = prog.clone();
    let empty = Value::Object(Map::new());
    let v = v.unwrap_or(&empty);
    let s = |k: &str| v.get(k).and_then(Value::as_str).map(str::to_string);
    let lp = v.get("loop").cloned().unwrap_or(Value::Null);
    let g = v.get("gather").cloned().unwrap_or(Value::Null);
    let cfg = MediaClientConfig {
        model: s("model"),
        models: v
            .get("models")
            .and_then(Value::as_object)
            .map(|m| {
                m.iter()
                    .map(|(k, x)| (k.clone(), x.as_str().unwrap().to_string()))
                    .collect()
            })
            .unwrap_or_default(),
        key: s("key"),
        url: s("url"),
        command: match v.get("command") {
            Some(Value::String(c)) => vec![c.clone()],
            Some(Value::Array(a)) => a.iter().map(|x| x.as_str().unwrap().to_string()).collect(),
            _ => Vec::new(),
        },
        r#loop: MediaLoop {
            interval_ms: lp.get("intervalMs").and_then(Value::as_i64),
            timeout_ms: lp.get("timeoutMs").and_then(Value::as_i64),
            on_progress: Some(Arc::new(move |p| p2.lock().unwrap().push(p.status.clone()))),
        },
        gather: MediaGather {
            mode: g.get("mode").and_then(Value::as_str).map(|m| match m {
                "url" => GatherMode::Url,
                "inline" => GatherMode::Inline,
                _ => GatherMode::Download,
            }),
            dir: g.get("dir").and_then(Value::as_str).map(PathBuf::from),
            max_bytes: g.get("maxBytes").and_then(Value::as_u64),
            keep: g.get("keep").and_then(Value::as_bool),
        },
        http_client: None,
    };
    (cfg, prog)
}

/// Assert a function-face outcome (`result` | `error` | `errorPrefix`, `status`).
pub fn check_outcome(
    name: &str,
    case: &Value,
    got: &Result<MediaResult, MediaError>,
    client: &str,
    verb: &str,
    dir: &Path,
) {
    if let Some(want) = case.get("result") {
        let got = got.as_ref().unwrap_or_else(|e| panic!("{name}: {e}"));
        check_result(name, want, got, client, verb, dir);
        if case.get("noUsage").and_then(Value::as_bool) == Some(true) {
            assert!(got.usage.is_none(), "{name}: usage present");
        }
        if let Some(want_path) = case.get("runFilePath").and_then(Value::as_bool) {
            check_run_file_path(name, want_path, got, client, verb);
        }
        return;
    }
    let e = got
        .as_ref()
        .err()
        .unwrap_or_else(|| panic!("{name}: expected an error, got {got:?}"));
    if let Some(w) = case.get("error").and_then(Value::as_str) {
        assert_eq!(e.to_string(), w, "{name}: error");
    }
    if let Some(w) = case.get("errorPrefix").and_then(Value::as_str) {
        assert!(
            e.to_string().starts_with(w),
            "{name}: error {e:?} lacks prefix {w:?}"
        );
    }
    if let Some(st) = case.get("status").and_then(Value::as_str) {
        assert_eq!(e.status(), st, "{name}: status");
        if st == "unavailable" {
            assert!(
                matches!(e, MediaError::Unavailable { .. }),
                "{name}: typed unavailable"
            );
        }
    }
}

/// D20: RunResult.files built by the port's run-files function from a
/// toolCalls record of this result carry each MediaFile's path; parts never do.
fn check_run_file_path(name: &str, want: bool, got: &MediaResult, client: &str, verb: &str) {
    let tr = toolnexus::media::media_tool_result(verb, client, got.clone());
    let tv = serde_json::to_value(&tr).unwrap();
    assert!(
        !tv.to_string().contains("\"path\""),
        "{name}: a part holds a path"
    );
    let call = toolnexus::client::ToolCall {
        name: "generate".into(),
        args: Value::Null,
        output: tr.output.clone(),
        is_error: tr.is_error,
        metadata: tr.metadata.clone(),
        parts: tr.parts.clone(),
    };
    let files = toolnexus::client::run_files_of(&call, "c1");
    assert_eq!(files.len(), got.files.len(), "{name}: run files");
    for (rf, mf) in files.iter().zip(&got.files) {
        if want {
            assert!(
                Path::new(&rf.path).is_absolute(),
                "{name}: run file path {:?}",
                rf.path
            );
            assert_eq!(rf.path, mf.path, "{name}: run file path");
        } else {
            assert!(rf.path.is_empty(), "{name}: run file has a path");
        }
    }
}

pub fn check_result(
    name: &str,
    want: &Value,
    got: &MediaResult,
    client: &str,
    verb: &str,
    dir: &Path,
) {
    let gv = serde_json::to_value(got).unwrap();
    for k in ["text", "jobId", "status", "usage"] {
        assert_eq!(gv.get(k), want.get(k), "{name}: result.{k}");
    }
    let wf = want["files"].as_array().unwrap();
    assert_eq!(got.files.len(), wf.len(), "{name}: file count {gv}");
    for (f, w) in got.files.iter().zip(wf) {
        match w.get("saved") {
            Some(saved) => {
                assert_eq!(f.kind, w["type"].as_str().unwrap(), "{name}");
                assert_eq!(f.mime_type, w["mimeType"].as_str().unwrap(), "{name}");
                check_saved(name, &f.path, &f.url, saved, client, verb, dir);
                assert!(f.data.is_empty(), "{name}: saved file has data");
            }
            None => assert_eq!(serde_json::to_value(f).unwrap(), *w, "{name}: file"),
        }
    }
}

fn check_saved(
    name: &str,
    path: &str,
    url: &str,
    saved: &Value,
    client: &str,
    verb: &str,
    dir: &Path,
) {
    let p = Path::new(path);
    assert!(p.is_absolute(), "{name}: path {path} not absolute");
    assert_eq!(p.parent().unwrap(), dir, "{name}: saved outside {{dir}}");
    let ext = saved["ext"].as_str().unwrap();
    let re = regex::Regex::new(&format!(
        "^{}-{}-[0-9]+-[0-9]+{}$",
        regex::escape(client),
        regex::escape(verb),
        regex::escape(ext)
    ))
    .unwrap();
    let fname = p.file_name().unwrap().to_string_lossy();
    assert!(re.is_match(&fname), "{name}: file name {fname}");
    assert_eq!(url, format!("file://{path}"), "{name}: url");
    let want = STANDARD
        .decode(saved["bytesBase64"].as_str().unwrap())
        .unwrap();
    assert_eq!(std::fs::read(p).unwrap(), want, "{name}: saved bytes");
}

/// Assert a tool-face result against a case's `tool` expectation.
pub fn check_tool(name: &str, want: &Value, got: &ToolResult, dir: &Path) {
    let gv = serde_json::to_value(got).unwrap();
    assert_eq!(
        gv["isError"], want["isError"],
        "{name}: tool isError ({gv})"
    );
    if let Some(o) = want.get("output").and_then(Value::as_str) {
        assert_eq!(got.output, o, "{name}: tool output");
    }
    if let Some(pat) = want.get("outputPattern").and_then(Value::as_str) {
        let pat = pat.replace("{dir}", &regex::escape(&dir.to_string_lossy()));
        let re = regex::Regex::new(&pat).unwrap();
        assert!(
            re.is_match(&got.output),
            "{name}: tool output {:?} !~ {pat}",
            got.output
        );
    }
    if let Some(pre) = want.get("outputPrefix").and_then(Value::as_str) {
        assert!(
            got.output.starts_with(pre),
            "{name}: tool output {:?} lacks prefix {pre:?}",
            got.output
        );
    }
    if let Some(p) = want.get("parts") {
        assert_eq!(gv.get("parts").unwrap_or(&Value::Null), p, "{name}: parts");
    }
    if want.get("noParts").and_then(Value::as_bool) == Some(true) {
        assert!(gv.get("parts").is_none(), "{name}: tool has parts");
    }
    if let Some(m) = want.get("metadata") {
        assert_eq!(
            gv.get("metadata").unwrap_or(&Value::Null),
            m,
            "{name}: metadata"
        );
    }
    if let Some(mp) = want.get("modelParts") {
        let parts = serde_json::to_value(toolnexus::media::model_parts(got)).unwrap();
        assert_eq!(&parts, mp, "{name}: modelParts");
    }
}

/// The tool for `verb` (by `want.name`, else the verb's tool name).
pub fn tool_name(want: &Value, verb: &str) -> String {
    if let Some(n) = want.get("name").and_then(Value::as_str) {
        return n.to_string();
    }
    let verbs = load("verbs.json");
    verbs["verbs"]
        .as_array()
        .unwrap()
        .iter()
        .find(|v| v["verb"] == verb)
        .unwrap()["tool"]
        .as_str()
        .unwrap()
        .to_string()
}

/// Parse a recorded multipart body into `[{name, value} | {name, filename, contentType, bytesBase64}]`.
pub fn multipart(r: &Req) -> Vec<Value> {
    let ct = r.headers.get("content-type").cloned().unwrap_or_default();
    let boundary = ct
        .split("boundary=")
        .nth(1)
        .unwrap_or("")
        .trim()
        .to_string();
    assert!(!boundary.is_empty(), "no multipart boundary in {ct:?}");
    let mut out = Vec::new();
    for chunk in r.raw.split(&format!("--{boundary}")) {
        let chunk = chunk.strip_prefix("\r\n").unwrap_or(chunk);
        if chunk.is_empty() || chunk.starts_with("--") {
            continue;
        }
        let (head, body) = chunk.split_once("\r\n\r\n").unwrap();
        let body = body.strip_suffix("\r\n").unwrap_or(body);
        let mut name = String::new();
        let mut filename = None;
        let mut ctype = None;
        for line in head.split("\r\n") {
            let lower = line.to_ascii_lowercase();
            if lower.starts_with("content-disposition:") {
                for seg in line.split(';') {
                    let seg = seg.trim();
                    if let Some(v) = seg.strip_prefix("name=") {
                        name = v.trim_matches('"').to_string();
                    }
                    if let Some(v) = seg.strip_prefix("filename=") {
                        filename = Some(v.trim_matches('"').to_string());
                    }
                }
            } else if lower.starts_with("content-type:") {
                ctype = Some(line[13..].trim().to_string());
            }
        }
        out.push(match filename {
            Some(f) => serde_json::json!({"name": name, "filename": f,
                "contentType": ctype.unwrap_or_default(),
                "bytesBase64": STANDARD.encode(body.as_bytes())}),
            None => serde_json::json!({"name": name, "value": body}),
        });
    }
    out
}

/// Assert `expectRequests` (+ count) and `expectHeaders` against the log.
pub fn check_requests(name: &str, case: &Value, reqs: &[Req]) {
    if let Some(want) = case.get("expectRequests").and_then(Value::as_array) {
        assert_eq!(
            reqs.len(),
            want.len(),
            "{name}: request count {:?}",
            reqs.iter().map(|r| &r.path).collect::<Vec<_>>()
        );
        for (r, w) in reqs.iter().zip(want) {
            assert_eq!(r.method, w["method"].as_str().unwrap(), "{name}: method");
            assert_eq!(r.path, w["path"].as_str().unwrap(), "{name}: path");
            if let Some(b) = w.get("body") {
                assert_eq!(r.body, *b, "{name}: body of {}", r.path);
                assert_eq!(
                    r.headers.get("content-type").map(String::as_str),
                    Some("application/json"),
                    "{name}: content-type"
                );
            }
            if let Some(mp) = w.get("multipart") {
                assert_eq!(Value::Array(multipart(r)), *mp, "{name}: multipart");
            }
            if w.get("body").is_none() && w.get("multipart").is_none() && r.method == "GET" {
                assert!(r.raw.is_empty(), "{name}: GET has a body");
                assert!(
                    !r.headers.contains_key("content-type"),
                    "{name}: GET content-type"
                );
            }
            if let Some(h) = w.get("headers").and_then(Value::as_object) {
                for (k, v) in h {
                    assert_eq!(
                        r.headers.get(&k.to_ascii_lowercase()).map(String::as_str),
                        Some(v.as_str().unwrap()),
                        "{name}: header {k} on {}",
                        r.path
                    );
                }
            }
            if let Some(a) = w.get("absentHeaders").and_then(Value::as_array) {
                for k in a {
                    let k = k.as_str().unwrap().to_ascii_lowercase();
                    assert!(
                        !r.headers.contains_key(&k),
                        "{name}: header {k} sent on {}",
                        r.path
                    );
                }
            }
        }
    }
    if let Some(h) = case.get("expectHeaders").and_then(Value::as_object) {
        assert!(!reqs.is_empty(), "{name}: no requests");
        for r in reqs {
            for (k, v) in h {
                assert_eq!(
                    r.headers.get(&k.to_ascii_lowercase()).map(String::as_str),
                    Some(v.as_str().unwrap()),
                    "{name}: header {k} on {}",
                    r.path
                );
            }
        }
    }
}

/// D23: every request's header matches its pattern.
pub fn check_header_patterns(name: &str, case: &Value, reqs: &[Req]) {
    if let Some(h) = case.get("expectHeaderPatterns").and_then(Value::as_object) {
        assert!(!reqs.is_empty(), "{name}: no requests");
        for r in reqs {
            for (k, v) in h {
                let re = regex::Regex::new(v.as_str().unwrap()).unwrap();
                let got = r
                    .headers
                    .get(&k.to_ascii_lowercase())
                    .cloned()
                    .unwrap_or_default();
                assert!(
                    re.is_match(&got),
                    "{name}: header {k}={got:?} on {}",
                    r.path
                );
            }
        }
    }
}

pub fn check_progress(name: &str, case: &Value, prog: &Progress) {
    if let Some(want) = case.get("expectProgress").and_then(Value::as_array) {
        let want: Vec<String> = want.iter().map(|v| v.as_str().unwrap().into()).collect();
        assert_eq!(*prog.lock().unwrap(), want, "{name}: progress");
    }
}

pub fn set_env(case: &Value, pairs: &[(&str, &str)]) {
    if let Some(env) = case.get("env").and_then(Value::as_object) {
        for (k, v) in env {
            let v = subst(v, pairs);
            // SAFETY: each conformance replay runs alone in its test binary.
            unsafe { std::env::set_var(k, v.as_str().unwrap()) };
        }
    }
    if let Some(un) = case.get("unsetEnv").and_then(Value::as_array) {
        for k in un {
            // SAFETY: as above.
            unsafe { std::env::remove_var(k.as_str().unwrap()) };
        }
    }
}

pub fn clear_env(case: &Value) {
    if let Some(env) = case.get("env").and_then(Value::as_object) {
        for k in env.keys() {
            // SAFETY: as above.
            unsafe { std::env::remove_var(k) };
        }
    }
}

/// Function face honouring `ctxTimeoutMs` (D12: ctx.deadline = now + ctxTimeoutMs).
pub async fn generate_case(
    media: &toolnexus::Media,
    case: &Value,
    verb: &str,
    args: toolnexus::MediaArgs,
) -> Result<MediaResult, MediaError> {
    let mut ctx = toolnexus::MediaCtx::default();
    if let Some(ms) = case.get("ctxTimeoutMs").and_then(Value::as_u64) {
        ctx.deadline = Some(std::time::Instant::now() + std::time::Duration::from_millis(ms));
    }
    media.generate_ctx(verb, args, ctx).await
}
