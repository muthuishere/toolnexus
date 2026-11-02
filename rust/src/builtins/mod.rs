//! Built-in tools (SPEC §4A, `source: "builtin"`), ported from `golang/builtin*.go`.
//!
//! The ten default tools — `bash, read, write, edit, grep, glob, webfetch,
//! question, apply_patch, todowrite` — plus the opt-in extras (background
//! shells, `websearch`, `notebook_edit`). Names, schemas, descriptions, output
//! strings and metadata keys match the Go reference.

mod bash;
mod diff;
mod extras;
mod files;
mod net;

use std::collections::BTreeMap;
use std::future::Future;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use futures_util::FutureExt;
use serde_json::{Map, Value};

use crate::tool::{Tool, ToolContext, ToolHints, ToolResult, ToolSource};

pub use bash::{BackgroundShellInfo, BackgroundShells};
pub use extras::{WebSearchFn, WebSearchFuture, WebSearchResult};

/// POSIX TERM→KILL grace window (§4A), ms.
pub const DEFAULT_KILL_GRACE_MS: u64 = 2000;

/// The `builtins` option (§4 assembly + §4A host boundary).
#[derive(Clone, Default)]
pub struct BuiltinsConfig {
    pub enabled: Option<bool>,
    pub disabled: Option<bool>,
    /// Per-tool name→bool map on the all-on baseline.
    pub tools: BTreeMap<String, bool>,
    /// argv prefix for `bash`; empty ⇒ detected at construction.
    pub shell: Vec<String>,
    /// What a relative path means; empty ⇒ process cwd.
    pub base_dir: String,
    pub confine_to_base_dir: bool,
    /// POSIX TERM→KILL grace; 0 ⇒ 2000.
    pub kill_grace_ms: u64,
    /// Opt-in background shells: adds `run_in_background` to `bash` plus the
    /// `bash_output` / `kill_shell` tools. The host owns it and calls
    /// [`BackgroundShells::close_all`] on shutdown.
    pub background: Option<Arc<BackgroundShells>>,
    /// Opt-in `websearch` over a host-supplied provider (the library ships none).
    pub web_search: Option<WebSearchFn>,
    /// Opt-in `notebook_edit` for `.ipynb` cells.
    pub notebook_edit: bool,
}

impl std::fmt::Debug for BuiltinsConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BuiltinsConfig")
            .field("enabled", &self.enabled)
            .field("disabled", &self.disabled)
            .field("tools", &self.tools)
            .field("shell", &self.shell)
            .field("base_dir", &self.base_dir)
            .field("confine_to_base_dir", &self.confine_to_base_dir)
            .field("kill_grace_ms", &self.kill_grace_ms)
            .field("background", &self.background.is_some())
            .field("web_search", &self.web_search.is_some())
            .field("notebook_edit", &self.notebook_edit)
            .finish()
    }
}

impl BuiltinsConfig {
    /// `builtins: false`.
    pub fn off() -> Self {
        Self {
            enabled: Some(false),
            ..Default::default()
        }
    }
    /// Parse the JSON spelling (`false` | `{enabled,disabled,tools,shell,baseDir,...}`).
    pub fn from_value(v: &Value) -> Self {
        match v {
            Value::Bool(b) => Self {
                enabled: Some(*b),
                ..Default::default()
            },
            Value::Object(m) => {
                let mut c = Self {
                    enabled: m.get("enabled").and_then(Value::as_bool),
                    disabled: m.get("disabled").and_then(Value::as_bool),
                    ..Default::default()
                };
                if let Some(Value::Object(t)) = m.get("tools") {
                    for (k, v) in t {
                        if let Some(b) = v.as_bool() {
                            c.tools.insert(k.clone(), b);
                        }
                    }
                }
                if let Some(Value::Array(a)) = m.get("shell") {
                    c.shell = a
                        .iter()
                        .filter_map(|x| x.as_str().map(String::from))
                        .collect();
                }
                if let Some(s) = m.get("baseDir").and_then(Value::as_str) {
                    c.base_dir = s.into();
                }
                c.confine_to_base_dir = m
                    .get("confineToBaseDir")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);
                c.notebook_edit = m
                    .get("notebookEdit")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);
                c.kill_grace_ms = m
                    .get("killGraceMs")
                    .and_then(Value::as_f64)
                    .map(|g| g.max(0.0) as u64)
                    .unwrap_or(0);
                c
            }
            _ => Self::default(),
        }
    }
    /// Whole-source toggle, MCP precedence: `disabled:true` wins, else `enabled:false`.
    pub fn is_enabled(&self) -> bool {
        !(self.disabled == Some(true) || self.enabled == Some(false))
    }
}

/// Every builtin enabled by `cfg`, in §4A order. Errors when `bash` is enabled
/// and no shell resolves.
pub fn select_builtins(cfg: &BuiltinsConfig) -> Result<Vec<Tool>, String> {
    if !cfg.is_enabled() {
        return Ok(Vec::new());
    }
    let env = Arc::new(BuiltinEnv::new(cfg));
    let out: Vec<Tool> = create_with_env(env.clone())
        .into_iter()
        .filter(|t| cfg.tools.get(&t.name).copied().unwrap_or(true))
        .collect();
    if out.iter().any(|t| t.name == "bash") {
        env.shell_argv()?;
    }
    Ok(out)
}

/// Every builtin (default ten + whichever opt-ins `cfg` turns on), ignoring the
/// toggles. Mirrors Go `CreateBuiltinToolsWith`.
pub fn create_builtin_tools(cfg: &BuiltinsConfig) -> Vec<Tool> {
    create_with_env(Arc::new(BuiltinEnv::new(cfg)))
}

/// The argv `bash` would run with under `cfg` (host-set, or detected).
pub fn builtin_shell(cfg: &BuiltinsConfig) -> Result<Vec<String>, String> {
    BuiltinEnv::new(cfg).shell_argv()
}

fn create_with_env(env: Arc<BuiltinEnv>) -> Vec<Tool> {
    let mut tools = vec![
        bash::bash_tool(env.clone()),
        files::read_tool(env.clone()),
        files::write_tool(env.clone()),
        files::edit_tool(env.clone()),
        files::grep_tool(env.clone()),
        files::glob_tool(env.clone()),
        net::webfetch_tool(),
        extras::question_tool(),
        files::apply_patch_tool(env.clone()),
        extras::todowrite_tool(),
    ];
    if env.bg.is_some() {
        tools.push(bash::bash_output_tool(env.clone()));
        tools.push(bash::kill_shell_tool(env.clone()));
    }
    if let Some(ws) = &env.web_search {
        tools.push(extras::websearch_tool(ws.clone()));
    }
    if env.notebook {
        tools.push(extras::notebook_edit_tool(env.clone()));
    }
    for t in &mut tools {
        t.hints = Some(builtin_hints(&t.name));
    }
    tools
}

fn builtin_hints(name: &str) -> ToolHints {
    let (read_only, destructive, open_world) = match name {
        "bash" => (false, true, true),
        "read" | "grep" | "glob" | "question" | "todowrite" | "bash_output" => (true, false, false),
        "write" | "edit" | "apply_patch" | "kill_shell" | "notebook_edit" => (false, true, false),
        "webfetch" | "websearch" => (true, false, true),
        _ => (false, false, false),
    };
    ToolHints {
        read_only,
        destructive,
        open_world,
    }
}

// ---------------------------------------------------------------------------
// host boundary env
// ---------------------------------------------------------------------------

pub(crate) struct BuiltinEnv {
    shell: Vec<String>,
    shell_err: Option<String>,
    base_dir: String,
    confine: bool,
    kill_grace_ms: u64,
    bg: Option<Arc<BackgroundShells>>,
    web_search: Option<WebSearchFn>,
    notebook: bool,
}

fn shell_candidates() -> Vec<Vec<String>> {
    #[cfg(unix)]
    {
        vec![
            vec!["/bin/sh".into(), "-c".into()],
            vec!["sh".into(), "-c".into()],
        ]
    }
    #[cfg(windows)]
    {
        let mut c = Vec::new();
        if let Ok(spec) = std::env::var("COMSPEC")
            && !spec.is_empty()
        {
            c.push(vec![spec, "/d".into(), "/s".into(), "/c".into()]);
        }
        c.push(vec![
            "cmd.exe".into(),
            "/d".into(),
            "/s".into(),
            "/c".into(),
        ]);
        c.push(vec!["pwsh".into(), "-NoProfile".into(), "-Command".into()]);
        c.push(vec![
            "powershell".into(),
            "-NoProfile".into(),
            "-Command".into(),
        ]);
        c.push(vec!["bash".into(), "-lc".into()]);
        c
    }
}

fn look_path(cmd: &str) -> bool {
    let p = Path::new(cmd);
    if p.is_absolute() || cmd.contains('/') || cmd.contains('\\') {
        return is_executable(p);
    }
    let Some(paths) = std::env::var_os("PATH") else {
        return false;
    };
    std::env::split_paths(&paths).any(|dir| {
        let c = dir.join(cmd);
        if is_executable(&c) {
            return true;
        }
        #[cfg(windows)]
        {
            for ext in ["exe", "cmd", "bat", "com"] {
                if is_executable(&c.with_extension(ext)) {
                    return true;
                }
            }
        }
        false
    })
}

fn is_executable(p: &Path) -> bool {
    match std::fs::metadata(p) {
        Ok(m) if m.is_file() => {
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                m.permissions().mode() & 0o111 != 0
            }
            #[cfg(not(unix))]
            {
                true
            }
        }
        _ => false,
    }
}

fn detect_shell() -> Result<Vec<String>, String> {
    let cands = shell_candidates();
    let mut tried = Vec::new();
    for argv in cands {
        tried.push(argv[0].clone());
        if look_path(&argv[0]) {
            return Ok(argv);
        }
    }
    Err(format!(
        "no shell interpreter found (tried: {}); set builtins.shell, or disable the bash builtin with builtins.tools {{\"bash\": false}}",
        tried.join(", ")
    ))
}

impl BuiltinEnv {
    fn new(cfg: &BuiltinsConfig) -> Self {
        let mut env = BuiltinEnv {
            shell: cfg.shell.clone(),
            shell_err: None,
            base_dir: cfg.base_dir.clone(),
            confine: cfg.confine_to_base_dir,
            kill_grace_ms: cfg.kill_grace_ms,
            bg: cfg.background.clone(),
            web_search: cfg.web_search.clone(),
            notebook: cfg.notebook_edit,
        };
        if env.shell.is_empty() {
            match detect_shell() {
                Ok(s) => env.shell = s,
                Err(e) => env.shell_err = Some(e),
            }
        }
        env
    }

    pub(crate) fn kill_grace(&self) -> std::time::Duration {
        let ms = if self.kill_grace_ms == 0 {
            DEFAULT_KILL_GRACE_MS
        } else {
            self.kill_grace_ms
        };
        std::time::Duration::from_millis(ms)
    }

    pub(crate) fn shell_argv(&self) -> Result<Vec<String>, String> {
        if self.shell.is_empty() {
            if let Some(e) = &self.shell_err {
                return Err(e.clone());
            }
            return Ok(vec!["sh".into(), "-c".into()]);
        }
        Ok(self.shell.clone())
    }

    pub(crate) fn shell_label(&self) -> String {
        self.shell_argv().map(|a| a.join(" ")).unwrap_or_default()
    }

    pub(crate) fn dir(&self) -> String {
        if !self.base_dir.is_empty() {
            return self.base_dir.clone();
        }
        std::env::current_dir()
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_else(|_| ".".into())
    }

    /// Resolve `p` against baseDir, enforcing confinement when set.
    pub(crate) fn resolve_path(&self, p: &str) -> Result<String, String> {
        if self.confine && self.base_dir.is_empty() {
            return Err("confineToBaseDir is set but baseDir is empty".into());
        }
        let full = if !self.base_dir.is_empty() && !Path::new(p).is_absolute() {
            clean(&Path::new(&self.base_dir).join(p))
        } else {
            p.to_owned()
        };
        if !self.confine {
            return Ok(full);
        }
        if reserved_device_name(&full) {
            return Err(format!(
                "{p} names a reserved device, which is not a file inside {}",
                self.base_dir
            ));
        }
        let base = canonical_path(&self.base_dir);
        let target = canonical_path(&full);
        match rel_path(&base, &target) {
            Some(rel) if rel != ".." && !rel.starts_with("../") => Ok(full),
            _ => Err(format!("{p} resolves outside baseDir {}", self.base_dir)),
        }
    }

    /// The path a diff names: relative to baseDir when set, else as given.
    pub(crate) fn diff_path(&self, given: &str, full: &str) -> String {
        if !self.base_dir.is_empty()
            && let Some(rel) = rel_path(&abs(&self.base_dir), &abs(full))
        {
            return rel;
        }
        to_slash(given)
    }
}

#[cfg(windows)]
fn reserved_device_name(p: &str) -> bool {
    let base = Path::new(p)
        .file_name()
        .map(|s| s.to_string_lossy().to_uppercase())
        .unwrap_or_default();
    let stem = base.split('.').next().unwrap_or("").trim_end().to_string();
    matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ((stem.starts_with("COM") || stem.starts_with("LPT"))
            && stem.len() == 4
            && matches!(stem.as_bytes()[3], b'1'..=b'9'))
}

#[cfg(not(windows))]
fn reserved_device_name(_: &str) -> bool {
    false
}

/// Lexical clean (Go `filepath.Clean`): drop `.`, fold `..`.
pub(crate) fn clean(p: &Path) -> String {
    let mut out: Vec<Component> = Vec::new();
    for c in p.components() {
        match c {
            Component::CurDir => {}
            Component::ParentDir => match out.last() {
                Some(Component::Normal(_)) => {
                    out.pop();
                }
                Some(Component::RootDir) | Some(Component::Prefix(_)) => {}
                _ => out.push(c),
            },
            other => out.push(other),
        }
    }
    let pb: PathBuf = out.iter().collect();
    let s = pb.to_string_lossy().into_owned();
    if s.is_empty() { ".".into() } else { s }
}

/// Go `filepath.Abs`: join onto cwd, then clean.
pub(crate) fn abs(p: &str) -> String {
    let path = Path::new(p);
    if path.is_absolute() {
        return clean(path);
    }
    let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("/"));
    clean(&cwd.join(path))
}

/// Canonicalise through the deepest existing ancestor, re-attaching the tail.
fn canonical_path(p: &str) -> String {
    let a = PathBuf::from(abs(p));
    let mut tail: Vec<std::ffi::OsString> = Vec::new();
    let mut cur = a.clone();
    loop {
        if let Ok(resolved) = std::fs::canonicalize(&cur) {
            let mut r = resolved;
            for t in tail.iter().rev() {
                r.push(t);
            }
            return r.to_string_lossy().into_owned();
        }
        match (cur.parent(), cur.file_name()) {
            (Some(parent), Some(name)) => {
                tail.push(name.to_owned());
                cur = parent.to_path_buf();
            }
            _ => return a.to_string_lossy().into_owned(),
        }
    }
}

/// Lexical relative path (Go `filepath.Rel` on two clean absolute paths), `/`-separated.
pub(crate) fn rel_path(base: &str, target: &str) -> Option<String> {
    let b: Vec<Component> = Path::new(base).components().collect();
    let t: Vec<Component> = Path::new(target).components().collect();
    if b.first() != t.first() {
        return None;
    }
    let common = b.iter().zip(t.iter()).take_while(|(x, y)| x == y).count();
    let mut parts: Vec<String> = Vec::new();
    for _ in common..b.len() {
        parts.push("..".into());
    }
    for c in &t[common..] {
        parts.push(c.as_os_str().to_string_lossy().into_owned());
    }
    if parts.is_empty() {
        Some(".".into())
    } else {
        Some(parts.join("/"))
    }
}

pub(crate) fn to_slash(p: &str) -> String {
    if std::path::MAIN_SEPARATOR == '/' {
        p.to_owned()
    } else {
        p.replace(std::path::MAIN_SEPARATOR, "/")
    }
}

/// An io error in Go's `op path: message` spelling.
pub(crate) fn go_io_err(op: &str, path: &str, e: &std::io::Error) -> String {
    let mut msg = e.to_string();
    if let Some(i) = msg.find(" (os error") {
        msg.truncate(i);
    }
    let mut chars = msg.chars();
    let msg = match chars.next() {
        Some(c) => c.to_lowercase().collect::<String>() + chars.as_str(),
        None => msg,
    };
    format!("{op} {path}: {msg}")
}

/// JSON the way Go's `encoding/json` writes it: sorted keys (serde_json's
/// default map) and HTML-escaped `<`, `>`, `&`, U+2028, U+2029.
pub(crate) fn go_json(v: &Value) -> String {
    go_escape(&serde_json::to_string(v).unwrap_or_default())
}

pub(crate) fn go_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '<' => out.push_str("\\u003c"),
            '>' => out.push_str("\\u003e"),
            '&' => out.push_str("\\u0026"),
            '\u{2028}' => out.push_str("\\u2028"),
            '\u{2029}' => out.push_str("\\u2029"),
            c => out.push(c),
        }
    }
    out
}

// ---------------------------------------------------------------------------
// tool helpers
// ---------------------------------------------------------------------------

pub(crate) type Args = Map<String, Value>;

pub(crate) fn b_err(output: impl Into<String>, meta: Option<Args>) -> ToolResult {
    ToolResult {
        output: output.into(),
        is_error: true,
        parts: Vec::new(),
        metadata: meta,
    }
}

pub(crate) fn b_ok(output: impl Into<String>, meta: Option<Args>) -> ToolResult {
    ToolResult {
        output: output.into(),
        is_error: false,
        parts: Vec::new(),
        metadata: meta,
    }
}

pub(crate) fn arg_str<'a>(args: &'a Args, key: &str) -> Option<&'a str> {
    args.get(key).and_then(Value::as_str)
}

pub(crate) fn arg_num(args: &Args, key: &str) -> Option<f64> {
    args.get(key).and_then(Value::as_f64)
}

pub(crate) fn arg_bool(args: &Args, key: &str) -> bool {
    args.get(key).and_then(Value::as_bool).unwrap_or(false)
}

pub(crate) fn as_array(v: Option<&Value>) -> Vec<Value> {
    match v {
        Some(Value::Array(a)) => a.clone(),
        _ => Vec::new(),
    }
}

pub(crate) fn meta(pairs: Vec<(&str, Value)>) -> Option<Args> {
    let mut m = Map::new();
    for (k, v) in pairs {
        m.insert(k.into(), v);
    }
    Some(m)
}

/// Build a builtin tool: args coerced to an object, a panic becomes an
/// `isError` result (`<name>: <panic>`), never an unwind across the boundary.
pub(crate) fn builtin<F, Fut>(name: &str, description: &str, schema: Value, run: F) -> Tool
where
    F: Fn(Args, ToolContext) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = ToolResult> + Send + 'static,
{
    let run = Arc::new(run);
    let tool_name = name.to_owned();
    Tool {
        name: name.into(),
        description: description.into(),
        input_schema: schema,
        source: ToolSource::Builtin,
        hints: None,
        execute: Arc::new(move |args, ctx| {
            let run = run.clone();
            let name = tool_name.clone();
            Box::pin(async move {
                let args = match args {
                    Value::Object(m) => m,
                    _ => Map::new(),
                };
                match std::panic::AssertUnwindSafe(run(args, ctx))
                    .catch_unwind()
                    .await
                {
                    Ok(r) => r,
                    Err(p) => {
                        let msg = p
                            .downcast_ref::<String>()
                            .cloned()
                            .or_else(|| p.downcast_ref::<&str>().map(|s| s.to_string()))
                            .unwrap_or_else(|| "panic".into());
                        ToolResult::error(format!("{name}: {msg}"))
                    }
                }
            })
        }),
    }
}

/// `path` arg resolved, else the env's directory.
pub(crate) fn dir_arg(args: &Args, env: &BuiltinEnv) -> Result<String, String> {
    match arg_str(args, "path") {
        Some(p) if !p.is_empty() => env.resolve_path(p),
        _ => Ok(env.dir()),
    }
}
