//! Agent home — personas (SPEC §7E) over the shipped §7D runtime, with NO
//! runtime changes. Port of `golang/agents/home.go`.
//!
//! 1. [`from_dir`] — the directory IS the agent: the ordered bootstrap files
//!    compose the soul as `## <file>` sections (2 MB cap each), once, at
//!    session start (a frozen snapshot).
//! 2. [`memory_tool`] — one add|replace|remove tool over MEMORY.md / USER.md.
//!    Writes go to DISK and load at the START of the next session; the live
//!    prompt never changes. A replace/remove of an absent substring is a loud
//!    isError.
//! 3. [`start_agent`] — a heartbeat: each interval posts a tick to the agent's
//!    own inbox (ticks coalesce) and, when idle, wakes it; a `HEARTBEAT_OK`
//!    reply stays silent. All timing goes through the runtime Clock.

use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{Value, json};
use tokio::sync::Notify;
use tokio_util::sync::CancellationToken;

use super::agent::{Agent, Spec, new};
use super::runtime::{Handle, InboxItem, Options, Runtime, State};
use crate::tool::{Tool, ToolResult, ToolSource};

/// The canonical discovery order (identity first, memory last).
pub const BOOTSTRAP_ORDER: [&str; 7] = [
    "AGENTS.md",
    "SOUL.md",
    "IDENTITY.md",
    "USER.md",
    "TOOLS.md",
    "HEARTBEAT.md",
    "MEMORY.md",
];

const MAX_BOOTSTRAP_BYTES: usize = 2 * 1024 * 1024;

/// The reply a persona gives when a beat needs no action (silent).
pub const HEARTBEAT_OK: &str = "HEARTBEAT_OK";

const TRUNCATION_NOTICE: &str = "\n[truncated: exceeds 2 MB bootstrap cap]";

fn read_capped(p: &Path) -> Option<String> {
    let b = std::fs::read(p).ok()?;
    if b.len() > MAX_BOOTSTRAP_BYTES {
        let mut s = String::from_utf8_lossy(&b[..MAX_BOOTSTRAP_BYTES]).into_owned();
        s.push_str(TRUNCATION_NOTICE);
        return Some(s);
    }
    Some(String::from_utf8_lossy(&b).into_owned())
}

/// Compose the bootstrap files under `dir` into one soul (the frozen
/// snapshot); returns the soul and the files found, in order.
pub fn compose_soul(dir: impl AsRef<Path>) -> (String, Vec<String>) {
    let dir = dir.as_ref();
    let mut sections = Vec::new();
    let mut found = Vec::new();
    for f in BOOTSTRAP_ORDER {
        if let Some(body) = read_capped(&dir.join(f)) {
            sections.push(format!("## {f}\n\n{}", body.trim()));
            found.push(f.to_string());
        }
    }
    (sections.join("\n\n"), found)
}

/// The file-backed `memory` tool (§7E): add / replace / remove over
/// MEMORY.md (`target: self`, default) or USER.md (`target: user`).
pub fn memory_tool(dir: impl AsRef<Path>) -> Tool {
    let dir = dir.as_ref().to_path_buf();
    Tool {
        name: "memory".into(),
        description: "Persist durable memory. action=add appends an entry; replace swaps an existing substring; remove deletes one. target=self (MEMORY.md, default) or user (USER.md). Writes persist to disk and load at the START of your next session — they do NOT change your current context.".into(),
        input_schema: json!({
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["add", "replace", "remove"]},
                "target": {"type": "string", "enum": ["self", "user"]},
                "text": {"type": "string", "description": "For add: the entry. For replace/remove: the existing text."},
                "with": {"type": "string", "description": "For replace: the replacement text."},
            },
            "required": ["action", "text"],
        }),
        source: ToolSource::Custom,
        hints: None,
        execute: Arc::new(move |args, _ctx| {
            let dir = dir.clone();
            Box::pin(async move {
                let s = |k: &str| args.get(k).and_then(Value::as_str).unwrap_or("").to_string();
                let file = if s("target") == "user" {
                    dir.join("USER.md")
                } else {
                    dir.join("MEMORY.md")
                };
                let (action, text) = (s("action"), s("text"));
                let cur = std::fs::read_to_string(&file).unwrap_or_default();
                let ws: &[char] = &[' ', '\t', '\r', '\n'];
                let next = match action.as_str() {
                    "add" => format!("{}\n- {text}\n", cur.trim_end_matches(ws))
                        .trim_start_matches(ws)
                        .to_string(),
                    "replace" | "remove" => {
                        if !cur.contains(&text) {
                            return ToolResult::error(format!("not found: {text}"));
                        }
                        let with = if action == "replace" { s("with") } else { String::new() };
                        cur.replacen(&text, &with, 1)
                    }
                    other => return ToolResult::error(format!("unknown action: {other}")),
                };
                if let Some(parent) = file.parent()
                    && let Err(e) = std::fs::create_dir_all(parent)
                {
                    return ToolResult::error(e.to_string());
                }
                if let Err(e) = std::fs::write(&file, next) {
                    return ToolResult::error(e.to_string());
                }
                let base = file
                    .file_name()
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_default();
                ToolResult::ok(format!("ok ({action} → {base}); loads next session"))
            })
        }),
    }
}

/// `from_dir` tuning.
#[derive(Clone, Default)]
pub struct FromDirOptions {
    /// Default `"persona agent from <dir>"`.
    pub does: String,
    /// Default the directory's base name.
    pub name: String,
    /// Default `"inherit"`.
    pub model: String,
    /// Extra tools beyond the memory builtin.
    pub tools: Vec<Tool>,
    /// `None` ⇒ the memory tool is on; `Some(false)` omits it.
    pub memory: Option<bool>,
}

/// A persona Agent from a directory: the bootstrap files compose the soul and
/// the memory tool is wired over the same directory (unless disabled).
pub fn from_dir(dir: impl AsRef<Path>, opts: FromDirOptions) -> Agent {
    let dir = dir.as_ref();
    let (soul, _) = compose_soul(dir);
    let name = if opts.name.is_empty() {
        dir.file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    } else {
        opts.name.clone()
    };
    let does = if opts.does.is_empty() {
        format!("persona agent from {}", dir.display())
    } else {
        opts.does.clone()
    };
    let model = if opts.model.is_empty() {
        "inherit".to_string()
    } else {
        opts.model.clone()
    };
    let mut tools = opts.tools.clone();
    if opts.memory.unwrap_or(true) {
        tools.push(memory_tool(dir));
    }
    new(
        name,
        Spec {
            does,
            soul,
            model,
            tools,
            ..Default::default()
        },
    )
}

/// Receives each substantive beat's text.
pub type OnBeatFn = Arc<dyn Fn(&str) + Send + Sync>;

/// `start_agent` options: the runtime wiring plus the heartbeat.
#[derive(Clone, Default)]
pub struct StartOptions {
    pub options: Options,
    /// The heartbeat interval in milliseconds.
    pub every_ms: u64,
    /// Every SUBSTANTIVE beat (`HEARTBEAT_OK` replies are silent).
    pub on_beat: Option<OnBeatFn>,
}

/// A running persona heartbeat. `stop` tears it down.
pub struct StartedAgent {
    rt: Runtime,
    handle: Handle,
    stop: CancellationToken,
    tasks: Mutex<Vec<tokio::task::JoinHandle<()>>>,
    beats: Arc<Mutex<Vec<String>>>,
    beat: Arc<Notify>,
}

impl StartedAgent {
    /// The substantive beats so far.
    pub fn beats(&self) -> Vec<String> {
        self.beats.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }
    /// The persona's handle, for host-delivered events (`post` / `wake`).
    pub fn handle(&self) -> Handle {
        self.handle.clone()
    }
    pub fn runtime(&self) -> Runtime {
        self.rt.clone()
    }
    /// Resolves after the next beat cycle completes (a test sync point).
    pub async fn next_beat(&self) {
        self.beat.notified().await;
    }
    /// End the heartbeat and close the runtime (stop-all at the root).
    pub async fn stop(&self) {
        self.stop.cancel();
        let tasks: Vec<_> =
            std::mem::take(&mut *self.tasks.lock().unwrap_or_else(|e| e.into_inner()));
        for t in tasks {
            let _ = t.await;
        }
        self.rt.close(&self.rt.root(), None).await;
    }
}

/// Give a persona its own clock: each `every_ms` it posts a tick to the
/// agent's own inbox and, when idle, wakes it with the heartbeat prompt. All
/// timing goes through the runtime's Clock.
pub fn start_agent(a: &Agent, opts: StartOptions) -> Result<StartedAgent, String> {
    let mut ro = opts.options.clone();
    ro.registry = a.registry();
    let rt = Runtime::new(ro);
    let h = rt.spawn(&rt.root(), &a.name, None)?;
    let stop = CancellationToken::new();
    let beats = Arc::new(Mutex::new(Vec::new()));
    let beat = Arc::new(Notify::new());
    let every = Duration::from_millis(opts.every_ms);
    let prompt = format!(
        "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply {HEARTBEAT_OK}."
    );
    let task = {
        let (rt, h, stop, beats, beat) = (
            rt.clone(),
            h.clone(),
            stop.clone(),
            beats.clone(),
            beat.clone(),
        );
        let on_beat = opts.on_beat.clone();
        tokio::spawn(async move {
            let clock = rt.clock();
            loop {
                tokio::select! {
                    _ = stop.cancelled() => return,
                    _ = clock.after(every) => {}
                }
                rt.post(&h, InboxItem::new("clock", "timer", "tick"));
                if rt.state_of(&h) == State::Idle && rt.wake(&h, &prompt).ok {
                    // The beat's turn is awaited off the clock loop, so ticks
                    // keep landing (and coalescing) while it runs.
                    let (rt, h, beats, beat, on_beat) = (
                        rt.clone(),
                        h.clone(),
                        beats.clone(),
                        beat.clone(),
                        on_beat.clone(),
                    );
                    tokio::spawn(async move {
                        let r = rt.wait(&h, None).await;
                        if !r.is_error && !r.text.contains(HEARTBEAT_OK) {
                            beats
                                .lock()
                                .unwrap_or_else(|e| e.into_inner())
                                .push(r.text.clone());
                            if let Some(f) = &on_beat {
                                f(&r.text);
                            }
                        }
                        beat.notify_one();
                    });
                } else {
                    beat.notify_one();
                }
            }
        })
    };
    Ok(StartedAgent {
        rt,
        handle: h,
        stop,
        tasks: Mutex::new(vec![task]),
        beats,
        beat,
    })
}
