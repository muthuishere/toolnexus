//! CLI client styles (SPEC §8C, ADR 0037) — port of `golang/cli.go`: an agent
//! CLI the user already has installed (and pays for) becomes the model behind
//! an ordinary in-process [`Client`], selected by a closed enum, never a string.
//!
//! Two transports, one constructor:
//!
//! - ACP (primary, [`crate::cli_acp`], `serve` feature): when the style declares
//!   an ACP launch and the binary speaks it, the toolkit is served as a local
//!   MCP server and handed to the agent in `session/new` `mcpServers`. The agent
//!   calls the tools natively.
//! - one-shot CLI (fallback, this module): the CLI runs once per model turn with
//!   the conversation rendered under a fixed preamble; its reply is parsed as an
//!   OpenAI-shaped envelope and tool calls go back to the toolnexus loop.
//!
//! A style is data (`cli_styles/*.json`, byte-identical to
//! `conformance/cli-styles/`) and holds no binary: `command` is always the caller's.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

use process_wrap::tokio::CommandWrap;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio_util::sync::CancellationToken;

use crate::acp::UpdateHandler;
use crate::client::{
    Client, EmitFn, GenerateStreamFn, InProcessOptions, InProcessRequest, InProcessResponse,
    InProcessToolCall, create_in_process_client,
};
use crate::toolkit::Toolkit;

/// The fixed preamble of every one-shot prompt (byte-identical to
/// `conformance/cli-styles/preamble.txt`).
pub const CLI_PREAMBLE: &str = include_str!("cli_styles/preamble.txt");

/// One CLI invocation / one ACP prompt is bounded by this many ms by default.
pub const CLI_DEFAULT_CALL_TIMEOUT_MS: u64 = 300_000;

/// The name the toolkit's `skill` tool is served under to an ACP agent
/// (copilot and devin ship their own `skill`).
pub const CLI_SERVED_SKILL_TOOL_NAME: &str = "load_skill";

/// Carries the bearer token to a stdio MCP bridge.
pub const CLI_MCP_TOKEN_ENV: &str = "TOOLNEXUS_MCP_TOKEN";

const STYLE_FILES: [(&str, &str); 7] = [
    ("claude", include_str!("cli_styles/claude.json")),
    ("codex", include_str!("cli_styles/codex.json")),
    ("copilot", include_str!("cli_styles/copilot.json")),
    ("opencode", include_str!("cli_styles/opencode.json")),
    ("devin", include_str!("cli_styles/devin.json")),
    ("gemini", include_str!("cli_styles/gemini.json")),
    ("goose", include_str!("cli_styles/goose.json")),
];

/// The embedded style files, `(file name, bytes)` — for drift tests.
pub fn cli_style_files() -> Vec<(String, &'static str)> {
    let mut v: Vec<(String, &'static str)> = STYLE_FILES
        .iter()
        .map(|(n, s)| (format!("{n}.json"), *s))
        .collect();
    v.push(("preamble.txt".into(), CLI_PREAMBLE));
    v
}

/// The closed set of CLI families a CLI client can drive. Each is a shape
/// (argv, prompt mode, answer source, ACP launch), never a binary.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum CliStyles {
    Claude,
    Codex,
    Copilot,
    Opencode,
    Devin,
    Gemini,
    Goose,
    /// Described by [`CliOptions::args`] / `prompt` / `answer`.
    Custom,
}

impl CliStyles {
    /// Every style, in declaration order.
    pub const ALL: [CliStyles; 8] = [
        Self::Claude,
        Self::Codex,
        Self::Copilot,
        Self::Opencode,
        Self::Devin,
        Self::Gemini,
        Self::Goose,
        Self::Custom,
    ];

    /// The style's shared file name. Never accepted as input.
    pub fn name(self) -> &'static str {
        match self {
            Self::Claude => "claude",
            Self::Codex => "codex",
            Self::Copilot => "copilot",
            Self::Opencode => "opencode",
            Self::Devin => "devin",
            Self::Gemini => "gemini",
            Self::Goose => "goose",
            Self::Custom => "custom",
        }
    }
}

impl std::fmt::Display for CliStyles {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.name())
    }
}

/// How a CLI client reaches the agent.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CliTransports {
    /// ACP when the style has an ACP launch and the binary answers
    /// `initialize`, else the one-shot CLI.
    #[default]
    Auto,
    Acp,
    Cli,
}

impl CliTransports {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Acp => "acp",
            Self::Cli => "cli",
        }
    }
}

impl std::fmt::Display for CliTransports {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

/// One argv element of a style: a string, or an optional group kept only when
/// a model is set.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum CliArg {
    Arg(String),
    Group(Vec<String>),
}

impl From<&str> for CliArg {
    fn from(s: &str) -> Self {
        Self::Arg(s.into())
    }
}

impl From<String> for CliArg {
    fn from(s: String) -> Self {
        Self::Arg(s)
    }
}

impl<S: Into<String>> From<Vec<S>> for CliArg {
    fn from(v: Vec<S>) -> Self {
        Self::Group(v.into_iter().map(Into::into).collect())
    }
}

/// A style's ACP launch.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct CliAcpLaunch {
    /// Appended to `command` to start it as an ACP agent.
    #[serde(default)]
    pub args: Vec<String>,
    /// For agents that ignore `session/new` `mcpServers`: the project MCP file
    /// written into a fresh temp session cwd.
    #[serde(
        rename = "projectMcpFile",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub project_mcp_file: String,
    /// Appended to the first system message of every ACP turn.
    #[serde(
        rename = "systemHint",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub system_hint: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub verified: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub notes: String,
}

/// One CLI's invocation recipe — data shared by every port.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct CliStyleDef {
    pub name: String,
    #[serde(default)]
    pub args: Vec<CliArg>,
    /// `stdin` | `arg` | `file`.
    #[serde(default)]
    pub prompt: String,
    /// `stdout` | `out`.
    #[serde(default)]
    pub answer: String,
    #[serde(rename = "timeoutMs", default, skip_serializing_if = "is_zero")]
    pub timeout_ms: u64,
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub env: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub verified: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub notes: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub acp: Option<CliAcpLaunch>,
}

fn is_zero(v: &u64) -> bool {
    *v == 0
}

/// The built-in definition of a style (`None` for [`CliStyles::Custom`]).
pub fn cli_style_def(style: CliStyles) -> Option<CliStyleDef> {
    let (_, text) = STYLE_FILES.iter().find(|(n, _)| *n == style.name())?;
    serde_json::from_str(text).ok()
}

/// A CLI client failure. The message is pinned across ports.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{0}")]
pub struct CliError(pub String);

impl CliError {
    fn new(s: impl Into<String>) -> Self {
        Self(s.into())
    }
}

/// Read a user style from a file path, or from JSON text starting with `{`.
/// Pass it as [`CliOptions::style_def`].
pub fn load_cli_style(path_or_json: &str) -> Result<CliStyleDef, CliError> {
    let text = if path_or_json.trim_start().starts_with('{') {
        path_or_json.to_owned()
    } else {
        std::fs::read_to_string(path_or_json).map_err(|e| CliError(format!("cli style: {e}")))?
    };
    serde_json::from_str(&text).map_err(|e| CliError(format!("cli style: {e}")))
}

/// [`create_cli_client`] options. `command` is REQUIRED: the library never
/// picks or defaults the binary. `in_process.model` is passed to the CLI
/// unchanged (empty ⇒ the CLI's own default model); `in_process.generate*` are
/// ignored.
#[derive(Clone, Default)]
pub struct CliOptions {
    /// Every in-process client option (system prompt, hooks, wait_for, …).
    pub in_process: InProcessOptions,
    /// A built-in recipe. Required unless `style_def` is set.
    pub style: Option<CliStyles>,
    /// A user style ([`load_cli_style`]); wins over `style`.
    pub style_def: Option<CliStyleDef>,
    /// The CLI binary: a path or a name on PATH. Required.
    pub command: String,
    /// argv / prompt mode / answer source for [`CliStyles::Custom`].
    pub args: Vec<CliArg>,
    pub prompt: String,
    pub answer: String,
    /// Added to the child's environment; values expand `${VAR}` from this
    /// process's environment at call time and are never logged.
    pub env: BTreeMap<String, String>,
    /// Bounds one CLI invocation or one ACP prompt; 0 ⇒ the style's, else 300000.
    pub call_timeout_ms: u64,
    /// The child's working directory (default: this process's).
    pub cwd: Option<PathBuf>,
    pub transport: CliTransports,
    /// Served to an ACP agent as a local MCP server.
    pub toolkit: Option<Toolkit>,
    /// The full argv that starts the agent in ACP mode (e.g. an adapter);
    /// replaces `command` + the style's `acp.args` for the ACP launch only.
    pub acp_command: Vec<String>,
    /// argv of a stdio⇄HTTP MCP bridge for an agent that accepts neither http
    /// MCP servers nor a project MCP file; the server URL is appended and the
    /// token goes in env `TOOLNEXUS_MCP_TOKEN`. Default: `toolnexus mcp-bridge`
    /// when `toolnexus` is on PATH.
    pub bridge_command: Vec<String>,
    /// Observes every raw ACP `session/update` params (must not block).
    pub on_acp_update: Option<UpdateHandler>,
}

#[cfg_attr(not(feature = "serve"), allow(dead_code))]
pub(crate) struct CliShared {
    pub(crate) style: CliStyleDef,
    pub(crate) opts: CliOptions,
    elems: Vec<Elem>,
    has_cli: bool,
    pub(crate) env: BTreeMap<String, String>,
    pub(crate) timeout_ms: u64,
    pub(crate) acp_argv: Vec<String>,
    chosen: Mutex<CliTransports>,
    note: Mutex<String>,
    closed: std::sync::atomic::AtomicBool,
    #[cfg(feature = "serve")]
    pub(crate) acp: tokio::sync::Mutex<Option<Arc<crate::cli_acp::CliAcpSession>>>,
}

/// One rendered invocation ([`CliClient::render_argv`]).
#[doc(hidden)]
#[derive(Debug, Clone)]
pub struct CliArgv {
    pub argv: Vec<String>,
    pub out: Option<PathBuf>,
    pub file: Option<PathBuf>,
    /// The prompt goes on stdin.
    pub stdin: bool,
}

/// An ordinary [`Client`] (via `Deref`) whose model is an agent CLI, plus
/// [`close`](Self::close), [`transport`](Self::transport) and
/// [`fallback_reason`](Self::fallback_reason).
#[derive(Clone)]
pub struct CliClient {
    client: Client,
    pub(crate) shared: Arc<CliShared>,
}

impl std::ops::Deref for CliClient {
    type Target = Client;
    fn deref(&self) -> &Client {
        &self.client
    }
}

impl CliClient {
    /// The underlying client.
    pub fn client(&self) -> &Client {
        &self.client
    }

    /// The resolved style definition.
    pub fn style_def(&self) -> &CliStyleDef {
        &self.shared.style
    }

    /// The argv one invocation would run for `prompt`, with the `{{out}}` and
    /// `{{file}}` temp paths it used (already removed again). For conformance
    /// tests and diagnostics.
    #[doc(hidden)]
    pub fn render_argv(&self, prompt: &str) -> Result<CliArgv, CliError> {
        let r = render_argv(&self.shared, prompt)?;
        Ok(CliArgv {
            argv: r.argv.clone(),
            out: r.out.clone(),
            file: r.file.clone(),
            stdin: self.shared.style.prompt == "stdin",
        })
    }

    /// The transport in use: `Auto` until the first model call has decided.
    pub fn transport(&self) -> CliTransports {
        *self.shared.chosen.lock().unwrap()
    }

    /// Why `Auto` fell back from ACP to the one-shot CLI (empty when it did not).
    pub fn fallback_reason(&self) -> String {
        self.shared.note.lock().unwrap().clone()
    }

    /// End the ACP agent's process group, stop the served toolkit and remove
    /// any temp session dir. Idempotent; a no-op for the one-shot CLI.
    pub async fn close(&self) {
        self.shared
            .closed
            .store(true, std::sync::atomic::Ordering::SeqCst);
        #[cfg(feature = "serve")]
        {
            let s = self.shared.acp.lock().await.take();
            if let Some(s) = s {
                s.close().await;
            }
        }
    }
}

impl CliOptions {
    fn resolve_style(&self) -> Result<CliStyleDef, CliError> {
        if self.command.is_empty() {
            return Err(CliError::new(
                "cli: Command is required (path or name of the CLI binary)",
            ));
        }
        if let Some(d) = &self.style_def {
            return Ok(d.clone());
        }
        match self.style {
            Some(CliStyles::Custom) => Ok(CliStyleDef {
                name: "custom".into(),
                args: self.args.clone(),
                prompt: self.prompt.clone(),
                answer: self.answer.clone(),
                ..Default::default()
            }),
            Some(s) => cli_style_def(s).ok_or_else(|| CliError::new("cli: style not embedded")),
            None => Err(CliError::new(
                "cli: Style is required (CliStyles.Claude, CliStyles.Codex, ...)",
            )),
        }
    }
}

/// Build a client whose model is an installed agent CLI. Everything that can be
/// validated is validated here; the transport is decided on the first call.
pub fn create_cli_client(o: CliOptions) -> Result<CliClient, CliError> {
    let d = o.resolve_style()?;
    let mut acp_argv = Vec::new();
    if !o.acp_command.is_empty() {
        acp_argv = o.acp_command.clone();
    } else if let Some(a) = &d.acp {
        acp_argv.push(o.command.clone());
        acp_argv.extend(a.args.iter().cloned());
    }
    if o.transport == CliTransports::Acp && acp_argv.is_empty() {
        return Err(CliError(format!(
            "cli: style {:?} has no ACP launch; set ACPCommand or use CliTransports.CLI",
            d.name
        )));
    }
    if !cfg!(feature = "serve") {
        if o.transport == CliTransports::Acp {
            return Err(CliError::new(
                "cli: the ACP transport needs the `serve` feature of the toolnexus crate",
            ));
        }
        acp_argv.clear();
    }
    let model = o.in_process.model.clone();
    let (mut elems, mut has_cli) = (Vec::new(), false);
    if o.transport != CliTransports::Acp {
        let acp_only = d.args.is_empty()
            && d.prompt.is_empty()
            && d.answer.is_empty()
            && !acp_argv.is_empty()
            && o.transport == CliTransports::Auto;
        if !acp_only {
            elems = normalize_args(&d.args);
            validate_style(&d, &elems, &model)?;
            has_cli = true;
        }
    }
    let mut env = d.env.clone();
    env.extend(o.env.clone());
    let mut timeout_ms = o.call_timeout_ms;
    if timeout_ms == 0 {
        timeout_ms = d.timeout_ms;
    }
    if timeout_ms == 0 {
        timeout_ms = CLI_DEFAULT_CALL_TIMEOUT_MS;
    }
    let chosen = if o.transport == CliTransports::Cli || acp_argv.is_empty() {
        CliTransports::Cli
    } else if o.transport == CliTransports::Acp {
        CliTransports::Acp
    } else {
        CliTransports::Auto
    };
    let mut ip = o.in_process.clone();
    if ip.model.is_empty() {
        ip.model = d.name.clone();
    }
    let shared = Arc::new(CliShared {
        style: d,
        opts: o,
        elems,
        has_cli,
        env,
        timeout_ms,
        acp_argv,
        chosen: Mutex::new(chosen),
        note: Mutex::new(String::new()),
        closed: std::sync::atomic::AtomicBool::new(false),
        #[cfg(feature = "serve")]
        acp: tokio::sync::Mutex::new(None),
    });
    let s2 = shared.clone();
    let gs: GenerateStreamFn = Arc::new(move |cancel, req, emit| {
        let s = s2.clone();
        Box::pin(async move { generate(s, cancel, req, emit).await })
    });
    ip.generate = None;
    ip.generate_stream = Some(gs);
    Ok(CliClient {
        client: create_in_process_client(ip),
        shared,
    })
}

async fn generate(
    s: Arc<CliShared>,
    cancel: CancellationToken,
    req: InProcessRequest,
    emit: EmitFn,
) -> Result<InProcessResponse, String> {
    if s.closed.load(std::sync::atomic::Ordering::SeqCst) {
        return Err("cli: client is closed".into());
    }
    #[cfg(feature = "serve")]
    if let Some(acp) = session(&s).await? {
        return acp.generate(cancel, req, emit).await;
    }
    let _ = &emit;
    if req.body.get("stream").and_then(Value::as_bool) == Some(true) {
        return Err(
            "cli: streaming is not supported by the one-shot CLI transport; use Run".into(),
        );
    }
    let prompt = render_cli_prompt(&req.messages, &req.tools);
    let text = invoke(&s, &cancel, &prompt).await.map_err(|e| e.0)?;
    Ok(parse_cli_reply(&text))
}

/// The live ACP session, opened on first use; `None` ⇒ the one-shot transport.
#[cfg(feature = "serve")]
async fn session(s: &Arc<CliShared>) -> Result<Option<Arc<crate::cli_acp::CliAcpSession>>, String> {
    let mut slot = s.acp.lock().await;
    if *s.chosen.lock().unwrap() == CliTransports::Cli {
        return Ok(None);
    }
    if let Some(a) = slot.as_ref() {
        return Ok(Some(a.clone()));
    }
    match crate::cli_acp::CliAcpSession::open(s).await {
        Ok(a) => {
            let a = Arc::new(a);
            *slot = Some(a.clone());
            *s.chosen.lock().unwrap() = CliTransports::Acp;
            Ok(Some(a))
        }
        Err((launch, msg)) => {
            let mut chosen = s.chosen.lock().unwrap();
            if *chosen == CliTransports::Auto && launch && s.has_cli {
                *chosen = CliTransports::Cli;
                *s.note.lock().unwrap() = msg;
                return Ok(None);
            }
            Err(msg)
        }
    }
}

// ---------------------------------------------------------------------------
// Argv templating and validation.
// ---------------------------------------------------------------------------

static PLACEHOLDER: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\{\{([^{}]*)\}\}").unwrap());
static ENV_REF: LazyLock<Regex> =
    LazyLock::new(|| Regex::new(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}").unwrap());

#[derive(Debug, Clone)]
struct Elem {
    group: bool,
    parts: Vec<String>,
}

fn normalize_args(args: &[CliArg]) -> Vec<Elem> {
    args.iter()
        .map(|a| match a {
            CliArg::Arg(s) => Elem {
                group: false,
                parts: vec![s.clone()],
            },
            CliArg::Group(g) => Elem {
                group: true,
                parts: g.clone(),
            },
        })
        .collect()
}

fn validate_style(d: &CliStyleDef, elems: &[Elem], model: &str) -> Result<(), CliError> {
    if !matches!(d.prompt.as_str(), "stdin" | "arg" | "file") {
        return Err(CliError(format!(
            "cli: prompt must be stdin, arg or file, not {:?}",
            d.prompt
        )));
    }
    if !matches!(d.answer.as_str(), "stdout" | "out") {
        return Err(CliError(format!(
            "cli: answer must be stdout or out, not {:?}",
            d.answer
        )));
    }
    let mut seen = std::collections::HashSet::new();
    for e in elems {
        for p in &e.parts {
            for m in PLACEHOLDER.captures_iter(p) {
                let name = m[1].to_owned();
                match name.as_str() {
                    "prompt" | "file" | "out" => {}
                    "model" => {
                        if !e.group && model.is_empty() {
                            return Err(CliError::new(
                                "cli: the style needs a model: {{model}} is outside an optional group",
                            ));
                        }
                    }
                    _ => return Err(CliError(format!("cli: unknown placeholder {{{{{name}}}}}"))),
                }
                seen.insert(name);
            }
        }
    }
    if !model.is_empty() && !seen.contains("model") {
        return Err(CliError(format!("cli: style {:?} takes no model", d.name)));
    }
    if d.prompt == "arg" && !seen.contains("prompt") {
        return Err(CliError::new(
            r#"cli: prompt "arg" needs {{prompt}} in args"#,
        ));
    }
    if d.prompt == "file" && !seen.contains("file") {
        return Err(CliError::new(
            r#"cli: prompt "file" needs {{file}} in args"#,
        ));
    }
    if d.answer == "out" && !seen.contains("out") {
        return Err(CliError::new(r#"cli: answer "out" needs {{out}} in args"#));
    }
    Ok(())
}

/// The env entries with `${VAR}` expanded from this process's environment.
pub(crate) fn expand_cli_env(env: &BTreeMap<String, String>) -> Vec<(String, String)> {
    env.iter()
        .map(|(k, v)| {
            let x = ENV_REF.replace_all(v, |c: &regex::Captures| {
                std::env::var(&c[1]).unwrap_or_default()
            });
            (k.clone(), x.into_owned())
        })
        .collect()
}

/// Temp files of one invocation, removed when dropped (every path).
#[derive(Default)]
pub(crate) struct TempFiles(pub(crate) Vec<PathBuf>);

impl Drop for TempFiles {
    fn drop(&mut self) {
        for f in &self.0 {
            let _ = std::fs::remove_file(f);
        }
    }
}

/// One rendered invocation.
pub(crate) struct Rendered {
    pub(crate) argv: Vec<String>,
    pub(crate) out: Option<PathBuf>,
    pub(crate) file: Option<PathBuf>,
    pub(crate) tmp: TempFiles,
}

fn temp_path() -> PathBuf {
    std::env::temp_dir().join(format!("toolnexus-cli-{}", uuid::Uuid::new_v4().simple()))
}

pub(crate) fn render_argv(s: &CliShared, prompt: &str) -> Result<Rendered, CliError> {
    let model = s.opts.in_process.model.as_str();
    let mut r = Rendered {
        argv: vec![s.opts.command.clone()],
        out: None,
        file: None,
        tmp: TempFiles::default(),
    };
    let mut err: Option<std::io::Error> = None;
    for e in &s.elems {
        if e.group && model.is_empty() {
            continue;
        }
        for p in &e.parts {
            let mut out = String::new();
            let mut last = 0;
            for m in PLACEHOLDER.captures_iter(p) {
                let whole = m.get(0).unwrap();
                out.push_str(&p[last..whole.start()]);
                last = whole.end();
                match &m[1] {
                    "prompt" => out.push_str(prompt),
                    "model" => out.push_str(model),
                    "file" => {
                        if r.file.is_none() {
                            let f = temp_path();
                            r.tmp.0.push(f.clone());
                            if let Err(e) = write_private(&f, prompt.as_bytes()) {
                                err.get_or_insert(e);
                            }
                            r.file = Some(f);
                        }
                        out.push_str(&r.file.as_ref().unwrap().to_string_lossy());
                    }
                    "out" => {
                        if r.out.is_none() {
                            // the CLI creates it; absence ⇒ "no output file"
                            let f = temp_path();
                            r.tmp.0.push(f.clone());
                            r.out = Some(f);
                        }
                        out.push_str(&r.out.as_ref().unwrap().to_string_lossy());
                    }
                    _ => out.push_str(whole.as_str()),
                }
            }
            out.push_str(&p[last..]);
            r.argv.push(out);
        }
    }
    match err {
        Some(e) => Err(CliError(format!("cli: {e}"))),
        None => Ok(r),
    }
}

pub(crate) fn write_private(path: &Path, data: &[u8]) -> std::io::Result<()> {
    let mut o = std::fs::OpenOptions::new();
    o.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        o.mode(0o600);
    }
    use std::io::Write;
    o.open(path)?.write_all(data)
}

/// `exec.LookPath`: a name with a path separator is checked as is; a bare name
/// is searched on PATH.
pub(crate) fn look_path(name: &str) -> Option<PathBuf> {
    fn runnable(p: &Path) -> bool {
        let Ok(m) = std::fs::metadata(p) else {
            return false;
        };
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            m.is_file() && m.permissions().mode() & 0o111 != 0
        }
        #[cfg(not(unix))]
        {
            m.is_file()
        }
    }
    if name.is_empty() {
        return None;
    }
    let p = Path::new(name);
    if p.components().count() > 1 || p.is_absolute() {
        return runnable(p).then(|| p.to_path_buf());
    }
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        let c = dir.join(name);
        if runnable(&c) {
            return Some(c);
        }
        #[cfg(windows)]
        for ext in ["exe", "cmd", "bat"] {
            let c = dir.join(format!("{name}.{ext}"));
            if runnable(&c) {
                return Some(c);
            }
        }
    }
    None
}

async fn invoke(
    s: &CliShared,
    cancel: &CancellationToken,
    prompt: &str,
) -> Result<String, CliError> {
    let r = render_argv(s, prompt)?;
    let Some(bin) = look_path(&r.argv[0]) else {
        return Err(CliError(format!("cli: command not found: {}", r.argv[0])));
    };
    let mut cmd = tokio::process::Command::new(bin);
    cmd.args(&r.argv[1..])
        .envs(expand_cli_env(&s.env))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    if let Some(d) = &s.opts.cwd {
        cmd.current_dir(d);
    }
    let mut wrap = CommandWrap::from(cmd);
    // Its own process group, so a timeout reaches whatever the CLI spawned.
    #[cfg(unix)]
    wrap.wrap(process_wrap::tokio::ProcessGroup::leader());
    #[cfg(windows)]
    wrap.wrap(process_wrap::tokio::JobObject);
    wrap.wrap(process_wrap::tokio::KillOnDrop);
    let mut child = wrap
        .spawn()
        .map_err(|_| CliError(format!("cli: command not found: {}", r.argv[0])))?;
    let mut stdin = child.stdin().take();
    let mut stdout = child.stdout().take().expect("piped stdout");
    let mut stderr = child.stderr().take().expect("piped stderr");
    let input = if s.style.prompt == "stdin" {
        prompt.as_bytes().to_vec()
    } else {
        Vec::new()
    };
    let feed = async move {
        if let Some(w) = stdin.as_mut() {
            let _ = w.write_all(&input).await;
            let _ = w.shutdown().await;
        }
        drop(stdin);
    };
    let read_out = async move {
        let mut b = Vec::new();
        let _ = stdout.read_to_end(&mut b).await;
        b
    };
    let read_err = async move {
        let mut b = Vec::new();
        let _ = stderr.read_to_end(&mut b).await;
        b
    };
    let work = async {
        let (_, out, err) = tokio::join!(feed, read_out, read_err);
        let status = child.wait().await;
        (out, err, status)
    };
    let timeout = Duration::from_millis(s.timeout_ms);
    let (out, err, status) = tokio::select! {
        r = tokio::time::timeout(timeout, work) => match r {
            Ok(v) => v,
            Err(_) => {
                kill_group(&mut child).await;
                return Err(CliError(format!("cli: timed out after {}ms", s.timeout_ms)));
            }
        },
        _ = cancel.cancelled() => {
            kill_group(&mut child).await;
            return Err(CliError::new("cli: cancelled"));
        }
    };
    let status = status.map_err(|e| CliError(format!("cli: {e}")))?;
    if !status.success() {
        let code = status.code().unwrap_or(-1);
        let msg = String::from_utf8_lossy(&err).trim().to_owned();
        if msg.is_empty() {
            return Err(CliError(format!("cli: exit {code}")));
        }
        let msg = if msg.chars().count() > 1000 {
            msg.chars().take(1000).collect::<String>() + "…"
        } else {
            msg
        };
        return Err(CliError(format!("cli: exit {code}: {msg}")));
    }
    let mut text = String::from_utf8_lossy(&out).into_owned();
    if s.style.answer == "out" {
        let path = r.out.as_ref().expect("validated: answer out has {{out}}");
        text = std::fs::read_to_string(path).map_err(|_| CliError::new("cli: no output file"))?;
    }
    if text.trim().is_empty() {
        return Err(CliError::new("cli: empty reply"));
    }
    Ok(text)
}

async fn kill_group(child: &mut Box<dyn process_wrap::tokio::ChildWrapper>) {
    let _ = child.start_kill();
    let _ = child.wait().await;
}

// ---------------------------------------------------------------------------
// The envelope: render and tolerant parse.
// ---------------------------------------------------------------------------

/// `PREAMBLE + "\nREQUEST:\n" + {"messages","tools"}`: the OpenAI-shaped
/// request, unmodified, as compact JSON.
pub fn render_cli_prompt(messages: &[Value], tools: &[Value]) -> String {
    let body = json!({"messages": messages, "tools": tools});
    format!(
        "{CLI_PREAMBLE}\nREQUEST:\n{}",
        serde_json::to_string(&body).unwrap_or_default()
    )
}

fn parse_object(s: &str) -> Option<serde_json::Map<String, Value>> {
    // serde_json rejects trailing text: a failure, never ignored.
    match serde_json::from_str::<Value>(s) {
        Ok(Value::Object(m)) => Some(m),
        _ => None,
    }
}

/// The tolerant six-step envelope parser: (1) trim; (2) strip one markdown
/// fence; (3) parse the whole text as one JSON object, else (4) the span from
/// the first `{` to the last `}`; (5) unwrap `choices[0].message` or
/// `message`; (6) `tool_calls` (flat or `{function:{}}`, string or object
/// arguments) win, else `content`. Anything else is the raw text as content.
pub fn parse_cli_reply(text: &str) -> InProcessResponse {
    let mut s = text.trim().to_owned();
    if s.starts_with("```") {
        s = match s.find('\n') {
            Some(i) => s[i + 1..].to_owned(),
            None => String::new(),
        };
        let t = s.trim();
        s = t.strip_suffix("```").unwrap_or(t).trim().to_owned();
    }
    let mut obj = parse_object(&s);
    if obj.is_none() {
        if let (Some(i), Some(j)) = (s.find('{'), s.rfind('}')) {
            if j > i {
                obj = parse_object(&s[i..=j]);
            }
        }
    }
    let raw = || InProcessResponse {
        content: text.to_owned(),
        ..Default::default()
    };
    let Some(mut obj) = obj else {
        return raw();
    };
    if let Some(Value::Array(ch)) = obj.get("choices") {
        if let Some(Value::Object(m)) = ch.first().and_then(|c| c.get("message")) {
            obj = m.clone();
        }
    } else if let Some(Value::Object(m)) = obj.get("message") {
        obj = m.clone();
    }
    if let Some(Value::Array(tcs)) = obj.get("tool_calls") {
        let calls: Vec<InProcessToolCall> = tcs
            .iter()
            .filter_map(|el| {
                let e = el.as_object()?;
                let f = match e.get("function") {
                    Some(Value::Object(f)) => f,
                    _ => e,
                };
                let name = f.get("name").and_then(Value::as_str).unwrap_or("");
                if name.is_empty() {
                    return None;
                }
                let arguments = match f.get("arguments") {
                    None | Some(Value::Null) => json!({}),
                    Some(v) => v.clone(),
                };
                Some(InProcessToolCall {
                    id: e.get("id").and_then(Value::as_str).unwrap_or("").to_owned(),
                    name: name.to_owned(),
                    arguments,
                })
            })
            .collect();
        if !calls.is_empty() {
            return InProcessResponse {
                tool_calls: calls,
                ..Default::default()
            };
        }
    }
    match obj.get("content") {
        Some(Value::String(c)) => InProcessResponse {
            content: c.clone(),
            ..Default::default()
        },
        Some(Value::Null) => InProcessResponse::default(),
        Some(v) => InProcessResponse {
            content: v.to_string(),
            ..Default::default()
        },
        None => raw(),
    }
}

/// Append a style's ACP system hint to the first system message (a copy; the
/// caller's messages are not mutated), or prepend a system message holding
/// only the hint when there is none.
pub fn cli_with_system_hint(msgs: &[Value], hint: &str) -> Vec<Value> {
    if hint.is_empty() {
        return msgs.to_vec();
    }
    if let Some(first) = msgs.first() {
        if first.get("role").and_then(Value::as_str) == Some("system") {
            if let Some(c) = first.get("content").and_then(Value::as_str) {
                let mut cp = first.clone();
                cp["content"] = json!(if c.is_empty() {
                    hint.to_owned()
                } else {
                    format!("{c}\n\n{hint}")
                });
                let mut out = vec![cp];
                out.extend_from_slice(&msgs[1..]);
                return out;
            }
        }
    }
    let mut out = vec![json!({"role": "system", "content": hint})];
    out.extend_from_slice(msgs);
    out
}

/// Whether a permission title names a tool of OUR server, by the exact
/// spellings agents use: `<tool>`, `mcp__toolnexus__<tool>`,
/// `toolnexus/<tool>`, `<tool> (toolnexus MCP Server)`, `Calling <tool> from
/// toolnexus`, or `<tool>(…)`. No substring matching.
pub fn cli_is_served_call<'a>(title: &str, served: impl IntoIterator<Item = &'a str>) -> bool {
    if title.is_empty() {
        return false;
    }
    served.into_iter().any(|n| {
        title == n
            || title == format!("mcp__toolnexus__{n}")
            || title == format!("toolnexus/{n}")
            || title == format!("{n} (toolnexus MCP Server)")
            || title == format!("Calling {n} from toolnexus")
            || (title.starts_with(&format!("{n}(")) && title.ends_with(')'))
    })
}

/// The stdio side of a stdio `mcpServers` entry: each JSON-RPC line read from
/// `input` is POSTed to the toolkit's streamable-HTTP endpoint (with the bearer
/// token when non-empty), and each reply — plain JSON or SSE `data:` frames —
/// is written to `output` as one line.
pub async fn run_mcp_bridge<R, W>(
    url: &str,
    token: &str,
    input: R,
    mut output: W,
) -> Result<(), String>
where
    R: AsyncBufRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let http = reqwest::Client::new();
    let mut lines = input.lines();
    let mut session = String::new();
    while let Some(line) = lines.next_line().await.map_err(|e| e.to_string())? {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let mut req = http
            .post(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .body(line.to_owned());
        if !token.is_empty() {
            req = req.header("Authorization", format!("Bearer {token}"));
        }
        if !session.is_empty() {
            req = req.header("Mcp-Session-Id", session.clone());
        }
        let resp = req.send().await.map_err(|e| e.to_string())?;
        if let Some(v) = resp
            .headers()
            .get("Mcp-Session-Id")
            .and_then(|v| v.to_str().ok())
        {
            session = v.to_owned();
        }
        if resp.status().as_u16() == 401 {
            return Err(format!(
                "mcp-bridge: unauthorized (missing or wrong {CLI_MCP_TOKEN_ENV})"
            ));
        }
        let sse = resp
            .headers()
            .get("Content-Type")
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.starts_with("text/event-stream"));
        let body = resp.text().await.map_err(|e| e.to_string())?;
        let mut emit = Vec::new();
        if sse {
            for l in body.split('\n') {
                if let Some(d) = l.trim_end_matches('\r').strip_prefix("data:") {
                    emit.push(d.trim().to_owned());
                }
            }
        } else if body.trim().starts_with('{') {
            emit.push(body.trim().to_owned());
        }
        for l in emit {
            output
                .write_all(format!("{l}\n").as_bytes())
                .await
                .map_err(|e| e.to_string())?;
        }
        output.flush().await.map_err(|e| e.to_string())?;
    }
    Ok(())
}
