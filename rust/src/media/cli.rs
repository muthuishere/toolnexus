//! CLI media clients (§7G.10): run a local subscription CLI once, headless,
//! read its JSON-lines stdout, collect files. Killed (process group on Unix)
//! on cancellation or deadline.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use serde_json::Value;
use tokio::io::{AsyncBufReadExt, BufReader};

use super::http::{self, Gather, Raw};
use super::{
    GatherMode, MediaArg, MediaArgs, MediaClient, MediaClientConfig, MediaCtx, MediaError,
    MediaResult, MediaUsage, canonical_args, positive, report, verb_kind,
};

/// What one stdout line contributed (§7G.10 step 2).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct CliEvent {
    /// Sets the job id (last wins).
    pub session: Option<String>,
    /// Appended to the run's text.
    pub text: Option<String>,
    /// Candidate file paths.
    pub paths: Vec<String>,
    /// Replaces the run's last error.
    pub error: Option<String>,
    /// Reported as progress.
    pub status: Option<String>,
    /// Provider-reported usage (D21; merged field by field, last wins, raw per key).
    pub usage: Option<MediaUsage>,
}

/// How one CLI is driven: argv, env, line parser, file folder, and which
/// failures mean `unavailable`.
pub trait CliProfile: Send + Sync {
    /// The client name (`grok`, `codex`, …).
    fn name(&self) -> &str;
    /// The executable when `command` is not configured.
    fn default_command(&self) -> &str;
    /// The verbs this CLI serves (prompt-only, §7G.10).
    fn verbs(&self) -> &[&'static str];
    /// The prompt template for `verb` (`{prompt}` = the folded prompt);
    /// `None` ⇒ the folded prompt as is. Also the mock's reply text.
    fn template(&self, _verb: &str) -> Option<&str> {
        None
    }
    /// The client argv after `command` for one run; `prompt` is the
    /// templated prompt (see [`CliProfile::template`]).
    fn argv(&self, verb: &str, prompt: &str, cwd: &Path, model: Option<&str>) -> Vec<String>;
    /// Fixed env added to the process env.
    fn env(&self) -> Vec<(String, String)> {
        Vec::new()
    }
    /// Parse one JSON-object line (`raw` is the line as read).
    fn parse(&self, raw: &str, obj: &Value) -> CliEvent;
    /// A folder whose entries are candidate files once a session is known.
    fn folder(&self, _session: &str) -> Option<PathBuf> {
        None
    }
    /// The `unavailable` message for a run's last error, when it is one.
    fn unavailable(&self, verb: &str, error: &str) -> Option<String>;
}

/// The CLI base (`CliMediaClient`). Accepts `prompt` only.
#[derive(Clone)]
pub struct CliMediaClient {
    profile: Arc<dyn CliProfile>,
    cfg: MediaClientConfig,
    /// Mock preset: never spawn; return a fixed file.
    mock: bool,
}

impl std::fmt::Debug for CliMediaClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CliMediaClient")
            .field("name", &self.profile.name())
            .finish_non_exhaustive()
    }
}

static CWD_SEQ: AtomicU64 = AtomicU64::new(0);

/// Kills the run's process group when dropped before the process exits.
struct Reaper {
    pid: Option<u32>,
}

impl Reaper {
    fn kill(&mut self) {
        if let Some(_pid) = self.pid.take() {
            #[cfg(unix)]
            // SAFETY: plain syscalls on a pid we spawned as a group leader.
            unsafe {
                libc::killpg(_pid as libc::pid_t, libc::SIGKILL);
                libc::kill(_pid as libc::pid_t, libc::SIGKILL);
            }
        }
    }
}

impl Drop for Reaper {
    fn drop(&mut self) {
        self.kill();
    }
}

fn cap(t: &str, n: usize) -> String {
    http::cap(t, n)
}

fn text_paths(text: &str) -> Vec<String> {
    static RE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    let re = RE.get_or_init(|| {
        regex::Regex::new(r#"/[^\s"'()\[\]]+\.(?i:png|jpe?g|webp|gif|mp4|mov|webm|mp3|wav)"#)
            .expect("path regex")
    });
    re.find_iter(text).map(|m| m.as_str().to_string()).collect()
}

impl CliMediaClient {
    /// A CLI client driven by `profile`.
    pub fn new(profile: impl CliProfile + 'static, cfg: MediaClientConfig) -> Self {
        CliMediaClient {
            profile: Arc::new(profile),
            cfg,
            mock: false,
        }
    }

    fn gatherer<'a>(
        &'a self,
        verb: &'a str,
        http_client: &'a reqwest::Client,
        deadline: Instant,
        timeout_ms: u64,
        session: Option<String>,
    ) -> Gather<'a> {
        let name = self.profile.name();
        Gather {
            client: name,
            verb,
            kind: verb_kind(verb),
            mode: self.cfg.gather.mode.unwrap_or(GatherMode::Download),
            dir: self.cfg.gather.dir.clone(),
            name: self.cfg.gather.name.clone(),
            max: self.cfg.gather.max_bytes.unwrap_or(http::DEFAULT_MAX_BYTES),
            keep: self.cfg.gather.keep.unwrap_or(true),
            http: http_client,
            deadline,
            timeout_msg: format!("{name}: timed out after {timeout_ms}ms"),
            job_id: session,
        }
    }

    /// The mock run (§7G CLI mock presets): no process. Writes
    /// `<gather.dir>/<client>-mock-<verb>.<png|mp4>` holding
    /// `toolnexus mock <verb>` and gathers it like a CLI file.
    async fn mock_run(
        &self,
        verb: &str,
        prompt: String,
        deadline: Instant,
        timeout_ms: u64,
    ) -> Result<MediaResult, MediaError> {
        let name = self.profile.name();
        let ext = if verb == "video" { "mp4" } else { "png" };
        let dir = self
            .cfg
            .gather
            .dir
            .clone()
            .unwrap_or_else(|| std::env::temp_dir().join("toolnexus-media"));
        let dir = std::path::absolute(&dir).unwrap_or(dir);
        let io = |e: std::io::Error| MediaError::failed(format!("{name}: {e}"));
        std::fs::create_dir_all(&dir).map_err(io)?;
        let path = dir.join(format!("{name}-mock-{verb}.{ext}"));
        std::fs::write(&path, format!("toolnexus mock {verb}")).map_err(io)?;
        let http_client = http::default_http();
        let g = self.gatherer(
            verb,
            &http_client,
            deadline,
            timeout_ms,
            Some("mock".into()),
        );
        let f = g.gather(Raw::Local(path)).await?;
        Ok(MediaResult {
            files: vec![f],
            text: Some(prompt),
            job_id: Some("mock".into()),
            status: "completed".into(),
            usage: None,
            style: None,
            model: None,
        })
    }

    async fn run(
        &self,
        verb: &str,
        args: MediaArgs,
        ctx: &MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        let p = &self.profile;
        let name = p.name().to_string();
        if !p.verbs().contains(&verb) {
            return Err(MediaError::failed(format!(
                "{name}: {verb} is not supported"
            )));
        }
        let folded = fold_media_prompt(verb, &args);
        let prompt = match p.template(verb) {
            Some(t) => t.replace("{prompt}", &folded),
            None => folded,
        };
        let prompt = prompt.as_str();
        let timeout_ms = positive(self.cfg.r#loop.timeout_ms).unwrap_or(600_000);
        let start = Instant::now();
        let mut deadline = start + Duration::from_millis(timeout_ms);
        if let Some(d) = ctx.deadline {
            deadline = deadline.min(d);
        }
        if self.mock {
            return self
                .mock_run(verb, prompt.to_string(), deadline, timeout_ms)
                .await;
        }
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        // canonical, so the CLI's `pwd -P` and our `--cwd` agree (macOS /var → /private/var)
        let tmp =
            std::fs::canonicalize(std::env::temp_dir()).unwrap_or_else(|_| std::env::temp_dir());
        let cwd = tmp.join(format!(
            "toolnexus-cli-{}-{nanos}-{}",
            std::process::id(),
            CWD_SEQ.fetch_add(1, Ordering::SeqCst)
        ));
        std::fs::create_dir_all(&cwd)
            .map_err(|e| MediaError::failed(format!("{name}: cannot make a work dir: {e}")))?;
        let r = self
            .run_in(verb, prompt, ctx, &cwd, start, deadline, timeout_ms)
            .await;
        let _ = std::fs::remove_dir_all(&cwd);
        r
    }

    #[allow(clippy::too_many_arguments)]
    async fn run_in(
        &self,
        verb: &str,
        prompt: &str,
        ctx: &MediaCtx,
        cwd: &Path,
        start: Instant,
        deadline: Instant,
        timeout_ms: u64,
    ) -> Result<MediaResult, MediaError> {
        let p = &self.profile;
        let name = p.name().to_string();
        let command: Vec<String> = if self.cfg.command.is_empty() {
            vec![p.default_command().to_string()]
        } else {
            self.cfg.command.clone()
        };
        let model = self
            .cfg
            .models
            .get(verb)
            .or(self.cfg.model.as_ref())
            .map(String::as_str);
        let mut cmd = tokio::process::Command::new(&command[0]);
        cmd.args(&command[1..])
            .args(p.argv(verb, prompt, cwd, model))
            .current_dir(cwd)
            .envs(p.env())
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        #[cfg(unix)]
        cmd.process_group(0);
        let mut child = cmd.spawn().map_err(|_| {
            MediaError::unavailable(format!("{name}: cannot start \"{}\"", command[0]))
        })?;
        let mut reaper = Reaper { pid: child.id() };
        let stdout = child.stdout.take().expect("piped stdout");
        let mut lines = BufReader::new(stdout).lines();

        let mut session: Option<String> = None;
        let mut text = String::new();
        let mut paths: Vec<String> = Vec::new();
        let mut usage = MediaUsage::default();
        let mut error: Option<String> = None;
        let timed_out = |session: &Option<String>| {
            MediaError::timeout(
                format!("{name}: timed out after {timeout_ms}ms"),
                session.clone(),
            )
        };
        loop {
            let line = tokio::select! {
                biased;
                _ = ctx.cancel.cancelled() => {
                    reaper.kill();
                    return Err(MediaError::Cancelled);
                }
                _ = tokio::time::sleep_until(deadline.into()) => {
                    reaper.kill();
                    return Err(timed_out(&session));
                }
                l = lines.next_line() => l,
            };
            let Ok(Some(line)) = line else { break };
            let Ok(obj @ Value::Object(_)) = serde_json::from_str::<Value>(&line) else {
                continue;
            };
            let ev = p.parse(&line, &obj);
            if let Some(s) = ev.session {
                session = Some(s);
            }
            if let Some(t) = ev.text {
                text.push_str(&t);
            }
            paths.extend(ev.paths);
            if let Some(e) = ev.error {
                error = Some(e);
            }
            if let Some(u) = ev.usage {
                usage.input_tokens = u.input_tokens.or(usage.input_tokens);
                usage.output_tokens = u.output_tokens.or(usage.output_tokens);
                usage.cost_usd = u.cost_usd.or(usage.cost_usd);
                if let Some(r) = u.raw {
                    usage.raw.get_or_insert_with(serde_json::Map::new).extend(r);
                }
            }
            if let Some(st) = ev.status {
                report(ctx, &self.cfg, verb, &name, session.as_deref(), &st, start);
            }
        }
        let status = tokio::select! {
            biased;
            _ = ctx.cancel.cancelled() => {
                reaper.kill();
                return Err(MediaError::Cancelled);
            }
            _ = tokio::time::sleep_until(deadline.into()) => {
                reaper.kill();
                return Err(timed_out(&session));
            }
            s = child.wait() => s,
        };
        reaper.pid = None;
        let code = status.ok().and_then(|s| s.code()).unwrap_or(-1);

        // candidates: parsed paths, then paths in the text, then the folder
        let mut cands: Vec<String> = paths;
        cands.extend(text_paths(&text));
        if let Some(dir) = session.as_deref().and_then(|s| p.folder(s)) {
            if let Ok(rd) = std::fs::read_dir(&dir) {
                let mut names: Vec<PathBuf> = rd.filter_map(|e| e.ok().map(|e| e.path())).collect();
                names.sort();
                cands.extend(names.into_iter().map(|p| p.to_string_lossy().into_owned()));
            }
        }
        let mut seen: Vec<String> = Vec::new();
        for c in cands {
            if !seen.contains(&c) && std::fs::metadata(&c).is_ok_and(|m| m.is_file()) {
                seen.push(c);
            }
        }
        let want = match verb_kind(verb) {
            "video" => "video/",
            "audio" => "audio/",
            _ => "image/",
        };
        let kept: Vec<String> = seen
            .into_iter()
            .filter(|c| http::mime_from_ext(c).is_some_and(|m| m.starts_with(want)))
            .collect();
        if kept.is_empty() {
            if let Some(e) = error {
                if let Some(m) = p.unavailable(verb, &e) {
                    return Err(MediaError::unavailable(m));
                }
                return Err(MediaError::Failed {
                    message: format!("{name}: no {verb}: {}", cap(&e, 400)),
                    job_id: session,
                });
            }
            let mut m = format!("{name}: no {verb} file in the run (exit code {code})");
            if !text.is_empty() {
                m.push_str(&format!(": {}", cap(&text, 300)));
            }
            return Err(MediaError::Failed {
                message: m,
                job_id: session,
            });
        }
        let http_client = http::default_http();
        let g = self.gatherer(verb, &http_client, deadline, timeout_ms, session.clone());
        let mut files = Vec::with_capacity(kept.len());
        for k in kept {
            files.push(g.gather(Raw::Local(PathBuf::from(k))).await?);
        }
        Ok(MediaResult {
            files,
            text: (!text.is_empty()).then_some(text),
            job_id: session,
            status: "completed".into(),
            usage: usage.nonempty(),
            style: None,
            model: None,
        })
    }
}

impl MediaClient for CliMediaClient {
    fn name(&self) -> &str {
        self.profile.name()
    }
    fn supports(&self, verb: &str) -> bool {
        self.profile.verbs().contains(&verb)
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        Some(if !self.supports(verb) {
            Vec::new()
        } else if verb == "video" {
            vec![
                MediaArg::new("prompt", true),
                MediaArg::new("seconds", false),
                MediaArg::new("aspectRatio", false),
            ]
        } else {
            vec![
                MediaArg::new("prompt", true),
                MediaArg::new("width", false),
                MediaArg::new("height", false),
                MediaArg::new("aspectRatio", false),
            ]
        })
    }
    verbs_via_run!();
}

/// How an argument value renders in a prompt: a string as is, an integral
/// number as integer text.
fn arg_text(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        Value::Number(n) => match (n.as_i64(), n.as_f64()) {
            (Some(i), _) => i.to_string(),
            (None, Some(f)) if f.fract() == 0.0 && f.abs() < 1e15 => (f as i64).to_string(),
            _ => n.to_string(),
        },
        Value::Null => String::new(),
        other => other.to_string(),
    }
}

/// `FoldMediaPrompt`: the prompt a CLI style receives — `prompt`, then the
/// present size hints in canonical verb order as ` (h1, h2)` (§7G CLI hint
/// folding). Nothing is appended when no hint is present.
pub fn fold_media_prompt(verb: &str, args: &MediaArgs) -> String {
    let prompt = args.get("prompt").map(arg_text).unwrap_or_default();
    let mut hs: Vec<String> = Vec::new();
    for a in canonical_args(verb) {
        let Some(v) = args.get(&a.name).filter(|v| !v.is_null()) else {
            continue;
        };
        let t = arg_text(v);
        match a.name.as_str() {
            "width" => hs.push(format!("width {t} px")),
            "height" => hs.push(format!("height {t} px")),
            "seconds" => hs.push(format!("about {t} seconds long")),
            "aspectRatio" => hs.push(format!("aspect ratio {t}")),
            _ => {}
        }
    }
    if hs.is_empty() {
        prompt
    } else {
        format!("{prompt} ({})", hs.join(", "))
    }
}

/// The client behind a built-in CLI preset (`xai-grok-cli`, …, `*-mock`).
pub(crate) fn preset_client(preset: &str, cfg: MediaClientConfig) -> CliMediaClient {
    let mut c = match preset.trim_end_matches("-mock") {
        "xai-grok-cli" => GrokMediaClient::new(cfg),
        "openai-codex-cli" => CodexMediaClient::new(cfg),
        _ => CliMediaClient::new(AgyProfile, cfg),
    };
    c.mock = preset.ends_with("-mock");
    c
}

fn str_at<'a>(v: &'a Value, k: &str) -> Option<&'a str> {
    v.get(k).and_then(Value::as_str)
}

// ---------------------------------------------------------------------------
// Grok
// ---------------------------------------------------------------------------

/// `GrokMediaClient` (`grok`): image, video through `grok -p …
/// --output-format streaming-json --yolo`. Never edits the user's Grok settings.
pub struct GrokMediaClient;

impl GrokMediaClient {
    #[allow(clippy::new_ret_no_self)]
    pub fn new(cfg: MediaClientConfig) -> CliMediaClient {
        CliMediaClient::new(GrokProfile, cfg)
    }
}

struct GrokProfile;

impl CliProfile for GrokProfile {
    fn name(&self) -> &str {
        "grok"
    }
    fn default_command(&self) -> &str {
        "grok"
    }
    fn verbs(&self) -> &[&'static str] {
        &["image", "video"]
    }
    fn template(&self, verb: &str) -> Option<&str> {
        Some(if verb == "video" {
            "Generate exactly one short video with your video generation tool, shortest duration available: {prompt}. Do not write code. Reply only with the file path."
        } else {
            "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the file path."
        })
    }
    fn argv(&self, _verb: &str, prompt: &str, cwd: &Path, model: Option<&str>) -> Vec<String> {
        let mut a: Vec<String> = vec![
            "-p".into(),
            prompt.into(),
            "--cwd".into(),
            cwd.to_string_lossy().into_owned(),
            "--output-format".into(),
            "streaming-json".into(),
            "--yolo".into(),
            "--no-auto-update".into(),
        ];
        if let Some(m) = model {
            a.push("-m".into());
            a.push(m.into());
        }
        a
    }
    fn env(&self) -> Vec<(String, String)> {
        vec![(
            "GROK_CONFIG".into(),
            r#"{"features":{"image_gen":true,"video_gen":true}}"#.into(),
        )]
    }
    fn parse(&self, _raw: &str, obj: &Value) -> CliEvent {
        let mut ev = CliEvent::default();
        match str_at(obj, "type") {
            Some("text") => ev.text = str_at(obj, "data").map(str::to_string),
            Some("tool_call") => {
                ev.status = Some(format!("tool {}", str_at(obj, "toolName").unwrap_or("")));
            }
            Some("tool_call_update") => {
                if let Some(st) = str_at(obj, "status").filter(|s| !s.is_empty()) {
                    ev.status = Some(format!("tool {st}"));
                    let raw = obj.get("rawOutput");
                    if st == "completed" {
                        if let Some(p) = raw
                            .and_then(|r| str_at(r, "path"))
                            .filter(|p| !p.is_empty())
                        {
                            ev.paths.push(p.to_string());
                        }
                    } else if st == "failed" {
                        ev.error = Some(match raw.and_then(|r| str_at(r, "message")) {
                            Some(m) => m.to_string(),
                            None => raw.map(Value::to_string).unwrap_or_default(),
                        });
                    }
                }
            }
            Some("end") => {
                ev.session = str_at(obj, "sessionId").map(str::to_string);
                ev.status = Some(format!("end {}", str_at(obj, "stopReason").unwrap_or("")));
                // D21: usage from the end event; ticks kept raw, never converted
                let u = obj.get("usage");
                let mut usage = MediaUsage {
                    input_tokens: u
                        .and_then(|u| u.get("input_tokens"))
                        .and_then(Value::as_i64),
                    output_tokens: u
                        .and_then(|u| u.get("output_tokens"))
                        .and_then(Value::as_i64),
                    cost_usd: obj.get("total_cost_usd").and_then(Value::as_f64),
                    raw: None,
                };
                if let Some(t) = obj.get("total_cost_usd_ticks").filter(|t| !t.is_null()) {
                    let mut m = serde_json::Map::new();
                    m.insert("total_cost_usd_ticks".into(), t.clone());
                    usage.raw = Some(m);
                }
                ev.usage = usage.nonempty();
            }
            Some("error") => ev.error = str_at(obj, "message").map(str::to_string),
            _ => {}
        }
        ev
    }
    fn unavailable(&self, verb: &str, error: &str) -> Option<String> {
        let l = error.to_ascii_lowercase();
        if l.contains("zero data retention") {
            Some(format!(
                "grok: {verb} blocked by Zero Data Retention — run /privacy and opt in, or configure ZDR video storage (docs/media#grok-video-zdr)"
            ))
        } else if l.contains("usage limit") {
            Some(format!("grok: {}", cap(error, 400)))
        } else {
            None
        }
    }
}

// ---------------------------------------------------------------------------
// Codex
// ---------------------------------------------------------------------------

/// `CodexMediaClient` (`codex`): image through `codex exec --json`. The file
/// comes from the reply text and `$CODEX_HOME/generated_images/<thread>`.
pub struct CodexMediaClient;

impl CodexMediaClient {
    #[allow(clippy::new_ret_no_self)]
    pub fn new(cfg: MediaClientConfig) -> CliMediaClient {
        CliMediaClient::new(CodexProfile, cfg)
    }
}

struct CodexProfile;

impl CliProfile for CodexProfile {
    fn name(&self) -> &str {
        "codex"
    }
    fn default_command(&self) -> &str {
        "codex"
    }
    fn verbs(&self) -> &[&'static str] {
        &["image"]
    }
    fn argv(&self, _verb: &str, prompt: &str, _cwd: &Path, model: Option<&str>) -> Vec<String> {
        let mut a: Vec<String> = [
            "exec",
            "--json",
            "--skip-git-repo-check",
            "-s",
            "workspace-write",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        if let Some(m) = model {
            a.push("-m".into());
            a.push(m.into());
        }
        a.push(prompt.into());
        a
    }
    fn template(&self, _verb: &str) -> Option<&str> {
        Some(
            "Generate exactly one image with your built-in image generation tool: {prompt}. Do not write code or use scripts. Reply only with the absolute path where the image was saved.",
        )
    }
    fn parse(&self, raw: &str, obj: &Value) -> CliEvent {
        let mut ev = CliEvent::default();
        let ty = str_at(obj, "type");
        ev.status = ty.map(str::to_string);
        match ty {
            Some("thread.started") => ev.session = str_at(obj, "thread_id").map(str::to_string),
            Some("item.completed") => {
                if let Some(item) = obj.get("item")
                    && str_at(item, "type") == Some("agent_message")
                {
                    ev.text = str_at(item, "text").map(str::to_string);
                }
            }
            Some("error") | Some("turn.failed") => {
                ev.error = Some(
                    str_at(obj, "message")
                        .or_else(|| obj.get("error").and_then(|e| str_at(e, "message")))
                        .map(str::to_string)
                        .unwrap_or_else(|| raw.to_string()),
                );
            }
            _ => {}
        }
        ev
    }
    fn folder(&self, session: &str) -> Option<PathBuf> {
        let home = match std::env::var("CODEX_HOME") {
            Ok(h) if !h.is_empty() => PathBuf::from(h),
            _ => PathBuf::from(std::env::var("HOME").ok()?).join(".codex"),
        };
        Some(home.join("generated_images").join(session))
    }
    fn unavailable(&self, _verb: &str, error: &str) -> Option<String> {
        let l = error.to_ascii_lowercase();
        (l.contains("usage limit") || l.contains("quota"))
            .then(|| format!("codex: {}", cap(error, 400)))
    }
}

// ---------------------------------------------------------------------------
// Google Antigravity (agy)
// ---------------------------------------------------------------------------

/// `agy` (preset `google-agy-cli`): image through `agy -p …
/// --output-format stream-json --dangerously-skip-permissions`. The path is in
/// the final result's response, else the conversation's brain folder.
struct AgyProfile;

fn dot<'a>(v: &'a Value, path: &str) -> Option<&'a Value> {
    path.split('.').try_fold(v, |v, k| v.get(k))
}

fn dot_str<'a>(v: &'a Value, path: &str) -> Option<&'a str> {
    dot(v, path).and_then(Value::as_str)
}

impl CliProfile for AgyProfile {
    fn name(&self) -> &str {
        "agy"
    }
    fn default_command(&self) -> &str {
        "agy"
    }
    fn verbs(&self) -> &[&'static str] {
        &["image"]
    }
    fn template(&self, _verb: &str) -> Option<&str> {
        Some(
            "Generate exactly one image with your image generation tool: {prompt}. Do not write code. Reply only with the absolute path where the image was saved.",
        )
    }
    fn argv(&self, _verb: &str, prompt: &str, _cwd: &Path, model: Option<&str>) -> Vec<String> {
        let mut a: Vec<String> = vec![
            "-p".into(),
            prompt.into(),
            "--output-format".into(),
            "stream-json".into(),
            "--dangerously-skip-permissions".into(),
        ];
        if let Some(m) = model.filter(|m| !m.is_empty()) {
            a.push("--model".into());
            a.push(m.into());
        }
        a
    }
    fn parse(&self, _raw: &str, obj: &Value) -> CliEvent {
        let mut ev = CliEvent::default();
        match str_at(obj, "event") {
            Some("init") => {
                ev.session = str_at(obj, "conversation_id").map(str::to_string);
                ev.status = Some("init".into());
            }
            Some("step_update") => {
                if let (Some(t), Some(st)) = (
                    dot_str(obj, "step_update.tool_name").filter(|t| !t.is_empty()),
                    dot_str(obj, "step_update.state").filter(|s| !s.is_empty()),
                ) {
                    ev.status = Some(format!("tool {t} {st}"));
                }
            }
            Some("result") => {
                ev.session = dot_str(obj, "result.conversation_id").map(str::to_string);
                ev.text = dot_str(obj, "result.response").map(str::to_string);
                let st = dot_str(obj, "result.status").unwrap_or("");
                ev.status = Some(format!("result {st}"));
                let low = ev.text.as_deref().unwrap_or("").to_lowercase();
                if st == "SUCCESS" && (low.contains("quota") || low.contains("resource_exhausted"))
                {
                    // Live: agy reports an exhausted image quota in a SUCCESS result's text.
                    ev.error = ev.text.clone();
                }
                if st != "SUCCESS" {
                    let text = ev.text.clone().filter(|t| !t.is_empty());
                    ev.error = Some(
                        dot_str(obj, "result.error")
                            .filter(|m| !m.is_empty())
                            .or_else(|| {
                                dot_str(obj, "result.error.message").filter(|m| !m.is_empty())
                            })
                            .map(str::to_string)
                            .or(text)
                            .unwrap_or_else(|| {
                                obj.get("result").map(Value::to_string).unwrap_or_default()
                            }),
                    );
                }
                let usage = MediaUsage {
                    input_tokens: dot(obj, "result.usage.input_tokens").and_then(Value::as_i64),
                    output_tokens: dot(obj, "result.usage.output_tokens").and_then(Value::as_i64),
                    cost_usd: None,
                    raw: None,
                };
                ev.usage = usage.nonempty();
            }
            Some("error") => ev.error = str_at(obj, "message").map(str::to_string),
            _ => {}
        }
        ev
    }
    fn folder(&self, session: &str) -> Option<PathBuf> {
        let home = std::env::var("HOME").ok()?;
        Some(
            PathBuf::from(home)
                .join(".gemini")
                .join("antigravity-cli")
                .join("brain")
                .join(session),
        )
    }
    fn unavailable(&self, _verb: &str, error: &str) -> Option<String> {
        let l = error.to_lowercase();
        (l.contains("quota") || l.contains("usage limit"))
            .then(|| format!("agy: {}", cap(error, 400)))
    }
}
