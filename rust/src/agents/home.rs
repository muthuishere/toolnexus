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
//! 4. The memory hierarchy (all opt-in; absent ⇒ byte-identical): a MEMORY.md
//!    index cap, a notes tier (`note:<slug>`, `read`, `list`) and the
//!    ground-truth tier, [`crate::transcript_search_tool`]. Dreaming ships as
//!    one preset, [`dreamer`], plus the `from_dir` `dream` option.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{Value, json};
use tokio::sync::Notify;
use tokio_util::sync::CancellationToken;

use super::agent::{Agent, Spec, new};
use super::runtime::{Handle, InboxItem, Options, Runtime, State};
use crate::store::ConversationStore;
use crate::tool::{Tool, ToolResult, ToolSource};
use crate::transcript_search::{TranscriptSearchOptions, transcript_search_tool};

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

/// The preset index cap (§7E "Memory hierarchy"): 200 lines / 25600 bytes.
pub const MEMORY_INDEX_CAP_PRESET: (usize, usize) = (200, 25600);

/// The `MEMORY.md` index cap: whole lines, bounded by line count and UTF-8
/// bytes. A `None` field takes the preset's value; `Some(0)` is an error.
/// `MemoryIndexCap::default()` is the preset.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct MemoryIndexCap {
    pub max_lines: Option<usize>,
    pub max_bytes: Option<usize>,
}

/// [`compose_soul_with`] options. The default is byte-identical to
/// [`compose_soul`].
#[derive(Clone, Copy, Debug, Default)]
pub struct ComposeSoulOptions {
    /// Cap the injected `MEMORY.md` (after the 2 MB read cap, before the
    /// section trim). `None` ⇒ off.
    pub memory_index_cap: Option<MemoryIndexCap>,
    /// Notes tier on — selects the notice that points at `note:<slug>`.
    pub notes: bool,
}

fn resolve_index_cap(c: Option<MemoryIndexCap>) -> Result<Option<(usize, usize)>, String> {
    let Some(c) = c else { return Ok(None) };
    let lines = c.max_lines.unwrap_or(MEMORY_INDEX_CAP_PRESET.0);
    let bytes = c.max_bytes.unwrap_or(MEMORY_INDEX_CAP_PRESET.1);
    if lines < 1 || bytes < 1 {
        return Err("memoryIndexCap: maxLines and maxBytes must be integers >= 1".into());
    }
    Ok(Some((lines, bytes)))
}

/// The exact notice appended to a capped `MEMORY.md`.
pub fn memory_index_notice(max_lines: usize, max_bytes: usize, notes: bool) -> String {
    let tail = if notes {
        "; move detail into notes (memory target \"note:<slug>\")."
    } else {
        " (keep entries short)."
    };
    format!(
        "\n\n[MEMORY.md index truncated at {max_lines} lines / {max_bytes} bytes. Keep MEMORY.md a short index{tail}]"
    )
}

/// Apply the index cap to `MEMORY.md`'s (already 2 MB-capped) content, exactly
/// as §7E pins it: within both bounds ⇒ unchanged; else the first `max_lines`
/// lines, trailing whole lines dropped until the `"\n"`-joined bytes fit (a
/// lone over-long line is cut at a code-point boundary), plus the notice.
pub fn apply_index_cap(content: &str, max_lines: usize, max_bytes: usize, notes: bool) -> String {
    let mut lines: Vec<&str> = content.split('\n').collect();
    if content.ends_with('\n') {
        lines.pop();
    }
    if lines.len() <= max_lines && lines.join("\n").len() <= max_bytes {
        return content.to_string();
    }
    lines.truncate(max_lines);
    while lines.len() > 1 && lines.join("\n").len() > max_bytes {
        lines.pop();
    }
    let mut text = lines.join("\n");
    if text.len() > max_bytes {
        let mut end = max_bytes;
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        text.truncate(end);
    }
    text + &memory_index_notice(max_lines, max_bytes, notes)
}

/// Compose the bootstrap files under `dir` into one soul (the frozen
/// snapshot); returns the soul and the files found, in order.
pub fn compose_soul(dir: impl AsRef<Path>) -> (String, Vec<String>) {
    compose_soul_with(dir, ComposeSoulOptions::default()).unwrap_or_default()
}

/// [`compose_soul`] with the memory-hierarchy options (§7E). Errors only on an
/// invalid `memory_index_cap`.
pub fn compose_soul_with(
    dir: impl AsRef<Path>,
    opts: ComposeSoulOptions,
) -> Result<(String, Vec<String>), String> {
    let dir = dir.as_ref();
    let cap = resolve_index_cap(opts.memory_index_cap)?;
    let mut sections = Vec::new();
    let mut found = Vec::new();
    for f in BOOTSTRAP_ORDER {
        if let Some(mut body) = read_capped(&dir.join(f)) {
            if let (Some((lines, bytes)), "MEMORY.md") = (cap, f) {
                body = apply_index_cap(&body, lines, bytes, opts.notes);
            }
            sections.push(format!("## {f}\n\n{}", body.trim()));
            found.push(f.to_string());
        }
    }
    Ok((sections.join("\n\n"), found))
}

const MEMORY_DESCRIPTION: &str = "Persist durable memory. action=add appends an entry; replace swaps an existing substring (with); remove deletes one. target=self (MEMORY.md, default) or user (USER.md). Writes persist to disk and load at the START of your NEXT session — they do NOT change your current context.";

/// The notes-on `memory` description (§7E).
pub const MEMORY_NOTES_DESCRIPTION: &str = "Persist durable memory. MEMORY.md is a short index loaded at the start of every session; put detail in notes (target note:<slug>, stored in memory/<slug>.md, never loaded automatically) and point to them from the index. Record what will change a future decision. action=add appends an entry; replace swaps the first occurrence of an existing substring (with); remove deletes it; read returns a file; list lists the notes. target=self (MEMORY.md, default), user (USER.md) or note:<slug> (slug: lowercase letters, digits, '.', '_', '-'). Writes persist to disk and load at the START of your NEXT session — they do NOT change your current context.";

/// A valid note slug: `^[a-z0-9][a-z0-9._-]{0,63}$` and no `..`.
fn valid_slug(s: &str) -> bool {
    let b = s.as_bytes();
    !b.is_empty()
        && b.len() <= 64
        && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit())
        && b.iter().all(|c| {
            c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'.' | b'_' | b'-')
        })
        && !s.contains("..")
}

/// [`memory_tool_with`] options.
#[derive(Clone, Copy, Debug, Default)]
pub struct MemoryToolOptions {
    /// The notes tier: `note:<slug>` targets plus `read`/`list`. Off ⇒
    /// byte-identical to [`memory_tool`].
    pub notes: bool,
}

/// Per resolved absolute path: one lock, so read-modify-write never
/// interleaves within this process (§7E write safety).
fn path_lock(p: &Path) -> Arc<Mutex<()>> {
    static LOCKS: std::sync::OnceLock<Mutex<HashMap<PathBuf, Arc<Mutex<()>>>>> =
        std::sync::OnceLock::new();
    // The key is the resolved REAL path (§7E): symlinks resolved; a file that
    // does not exist yet resolves its nearest existing ancestor and appends
    // the rest; nothing resolvable falls back to the absolute path.
    let abs = std::path::absolute(p).unwrap_or_else(|_| p.to_path_buf());
    let key = real_path_key(&abs);
    LOCKS
        .get_or_init(Mutex::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .entry(key)
        .or_default()
        .clone()
}

/// One read-modify-write over the FULL file (never the capped view), under the
/// per-path lock. `Err` is the tool error's output.
fn rmw(file: &Path, action: &str, text: &str, with: &str) -> Result<(), String> {
    let lock = path_lock(file);
    let _held = lock.lock().unwrap_or_else(|e| e.into_inner());
    let cur = std::fs::read_to_string(file).unwrap_or_default();
    let ws: &[char] = &[' ', '\t', '\r', '\n'];
    let next = match action {
        "add" => format!("{}\n- {text}\n", cur.trim_end_matches(ws))
            .trim_start_matches(ws)
            .to_string(),
        "replace" | "remove" => {
            if !cur.contains(text) {
                return Err(format!("not found: {text}"));
            }
            cur.replacen(text, with, 1) // first occurrence only (§7E)
        }
        other => return Err(format!("unknown action: {other}")),
    };
    if let Some(parent) = file.parent()
        && let Err(e) = std::fs::create_dir_all(parent)
    {
        return Err(e.to_string());
    }
    write_atomic(file, next.as_bytes()).map_err(|e| e.to_string())
}

fn memory_schema(notes: bool) -> Value {
    let text = json!({"type": "string", "description": "For add: the entry. For replace/remove: the existing text."});
    let with = json!({"type": "string", "description": "For replace: the replacement text."});
    if notes {
        json!({
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["add", "replace", "remove", "read", "list"]},
                "target": {"type": "string", "description": "self=MEMORY.md (default), user=USER.md, note:<slug>=memory/<slug>.md"},
                "text": text,
                "with": with,
            },
            "required": ["action"],
        })
    } else {
        json!({
            "type": "object",
            "properties": {
                "action": {"type": "string", "enum": ["add", "replace", "remove"]},
                "target": {"type": "string", "enum": ["self", "user"], "description": "self=MEMORY.md (default), user=USER.md"},
                "text": text,
                "with": with,
            },
            "required": ["action", "text"],
        })
    }
}

/// The file-backed `memory` tool (§7E): add / replace / remove over
/// MEMORY.md (`target: self`, default) or USER.md (`target: user`).
/// replace/remove act on the FIRST occurrence; writes are atomic and
/// serialized per file.
pub fn memory_tool(dir: impl AsRef<Path>) -> Tool {
    memory_tool_with(dir, MemoryToolOptions::default())
}

/// [`memory_tool`] with options. Notes on adds `target: note:<slug>`
/// (`<dir>/memory/<slug>.md`, never injected into the soul) and the `read` /
/// `list` actions (§7E "Memory hierarchy").
pub fn memory_tool_with(dir: impl AsRef<Path>, opts: MemoryToolOptions) -> Tool {
    let dir = dir.as_ref().to_path_buf();
    let notes = opts.notes;
    Tool {
        output_schema: None,
        annotations: None,
        concurrency_safe: None,
        name: "memory".into(),
        description: if notes {
            MEMORY_NOTES_DESCRIPTION
        } else {
            MEMORY_DESCRIPTION
        }
        .into(),
        input_schema: memory_schema(notes),
        source: ToolSource::Custom,
        hints: None,
        execute: Arc::new(move |args, _ctx| {
            let dir = dir.clone();
            Box::pin(async move {
                if notes {
                    notes_execute(&dir, &args)
                } else {
                    plain_execute(&dir, &args)
                }
            })
        }),
    }
}

fn plain_execute(dir: &Path, args: &Value) -> ToolResult {
    let s = |k: &str| {
        args.get(k)
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string()
    };
    let file = if s("target") == "user" {
        dir.join("USER.md")
    } else {
        dir.join("MEMORY.md")
    };
    let (action, text) = (s("action"), s("text"));
    let with = if action == "replace" {
        s("with")
    } else {
        String::new()
    };
    if let Err(e) = rmw(&file, &action, &text, &with) {
        return ToolResult::error(e);
    }
    let base = file
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    ToolResult::ok(format!("ok ({action} → {base}); loads next session"))
}

/// A JSON value as JS `String(v)` would render it for the cases that matter.
fn js_string(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

fn notes_execute(dir: &Path, args: &Value) -> ToolResult {
    let notes_dir = dir.join("memory");
    let action = args
        .get("action")
        .map(js_string)
        .unwrap_or("undefined".into());
    if action == "list" {
        return ToolResult::ok(list_notes(&notes_dir));
    }
    let target = match args.get("target") {
        None | Some(Value::Null) => "self".to_string(),
        Some(t) => js_string(t),
    };
    let (file, label) = match target.as_str() {
        "self" => (dir.join("MEMORY.md"), "MEMORY.md".to_string()),
        "user" => (dir.join("USER.md"), "USER.md".to_string()),
        t if t.starts_with("note:") => {
            let slug = &t[5..];
            if !valid_slug(slug) {
                return ToolResult::error(format!("invalid note slug: {slug}"));
            }
            (
                notes_dir.join(format!("{slug}.md")),
                format!("memory/{slug}.md"),
            )
        }
        t => return ToolResult::error(format!("unknown target: {t}")),
    };
    if action == "read" {
        return match read_capped(&file) {
            Some(body) => ToolResult::ok(body),
            None => ToolResult::error(format!("not found: {target}")),
        };
    }
    if !matches!(action.as_str(), "add" | "replace" | "remove") {
        return ToolResult::error(format!("unknown action: {action}"));
    }
    let Some(text) = args.get("text").and_then(Value::as_str) else {
        return ToolResult::error("text is required");
    };
    let with = match (action.as_str(), args.get("with")) {
        ("replace", Some(w)) if !w.is_null() => js_string(w),
        _ => String::new(),
    };
    if let Err(e) = rmw(&file, &action, text, &with) {
        return ToolResult::error(e);
    }
    if label.starts_with("memory/") {
        ToolResult::ok(format!("ok ({action} → {label})"))
    } else {
        ToolResult::ok(format!("ok ({action} → {label}); loads next session"))
    }
}

/// The real path of `abs`, resolving the nearest existing ancestor when
/// `abs` (or its parents) do not exist yet.
fn real_path_key(abs: &Path) -> PathBuf {
    let mut rest: Vec<std::ffi::OsString> = Vec::new();
    let mut cur = abs.to_path_buf();
    loop {
        if let Ok(real) = std::fs::canonicalize(&cur) {
            return rest.iter().rev().fold(real, |acc, n| acc.join(n));
        }
        match (cur.file_name().map(|n| n.to_os_string()), cur.parent()) {
            (Some(n), Some(parent)) => {
                rest.push(n);
                cur = parent.to_path_buf();
            }
            _ => return abs.to_path_buf(),
        }
    }
}

/// `list`: `<slug>\t<bytes>` per note, slug-ascending by explicit sort (never
/// directory order); `(no notes)` when none.
fn list_notes(notes_dir: &Path) -> String {
    let mut rows: Vec<(String, u64)> = Vec::new();
    if let Ok(entries) = std::fs::read_dir(notes_dir) {
        for e in entries.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            let Some(slug) = name.strip_suffix(".md") else {
                continue;
            };
            if !valid_slug(slug) {
                continue;
            }
            // lstat: a symlink (dangling or not) or a directory is skipped (§7E).
            if let Ok(m) = std::fs::symlink_metadata(e.path())
                && m.is_file()
            {
                rows.push((slug.to_string(), m.len()));
            }
        }
    }
    if rows.is_empty() {
        return "(no notes)".into();
    }
    rows.sort_by(|a, b| a.0.cmp(&b.0));
    rows.iter()
        .map(|(s, n)| format!("{s}\t{n}"))
        .collect::<Vec<_>>()
        .join("\n")
}

/// Write `data` to a temp file in the same directory, flush it (fsync), then
/// rename it over `p`, so a crash mid-write never leaves a partial memory file.
fn write_atomic(p: &Path, data: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let dir = p.parent().unwrap_or_else(|| Path::new("."));
    let base = p
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    static SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let seq = SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let tmp = dir.join(format!(".{base}.tmp-{}-{nanos}-{seq}", std::process::id()));
    // File::create opens with 0o666 & !umask, the platform default for a new file.
    let res = std::fs::File::create(&tmp)
        .and_then(|mut f| {
            f.write_all(data)?;
            f.sync_all()
        })
        .and_then(|()| keep_mode(p, &tmp))
        .and_then(|()| std::fs::rename(&tmp, p));
    if res.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    res
}

/// Copy an existing target's permission bits onto the temp file, so the rename
/// does not replace them with the temp's. No-op off unix or for a new file.
#[cfg(unix)]
fn keep_mode(target: &Path, tmp: &Path) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    match std::fs::metadata(target) {
        Ok(m) => std::fs::set_permissions(
            tmp,
            std::fs::Permissions::from_mode(m.permissions().mode() & 0o777),
        ),
        Err(_) => Ok(()),
    }
}

#[cfg(not(unix))]
fn keep_mode(_target: &Path, _tmp: &Path) -> std::io::Result<()> {
    Ok(())
}

/// Dream on a schedule next to a `from_dir` persona (§7E "Dreaming").
#[derive(Clone)]
pub struct DreamOptions {
    /// The dream interval in milliseconds (must be > 0).
    pub every_ms: u64,
    /// The transcripts to verify memory against; must support `list`.
    pub store: Arc<dyn ConversationStore>,
    /// Host prose appended after [`DREAM_PROMPT`] (and `DREAM.md`).
    pub instructions: String,
}

/// The dreamer `from_dir(…, dream)` attaches, read by [`start_agent`].
pub(crate) struct DreamConfig {
    dreamer: Agent,
    every_ms: u64,
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
    /// Cap the injected `MEMORY.md` (`MemoryIndexCap::default()` ⇒ 200 lines /
    /// 25600 bytes). `None` ⇒ off.
    pub memory_index_cap: Option<MemoryIndexCap>,
    /// The notes tier of the memory tool (`note:<slug>`, `read`, `list`).
    pub notes: bool,
    /// Run a [`dreamer`] over the same dir on the same runtime every
    /// `every_ms` once started with [`start_agent`]. `None` ⇒ off.
    pub dream: Option<DreamOptions>,
}

fn base_name(dir: &Path) -> String {
    dir.file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default()
}

/// A persona Agent from a directory: the bootstrap files compose the soul and
/// the memory tool is wired over the same directory (unless disabled).
///
/// # Panics
///
/// On an invalid configuration — a `memory_index_cap` field of `Some(0)`, or a
/// `dream` with `every_ms == 0` or a store without `list` (a programmer error).
pub fn from_dir(dir: impl AsRef<Path>, opts: FromDirOptions) -> Agent {
    let dir = dir.as_ref();
    let (soul, _) = compose_soul_with(
        dir,
        ComposeSoulOptions {
            memory_index_cap: opts.memory_index_cap,
            notes: opts.notes,
        },
    )
    .unwrap_or_else(|e| panic!("{e}"));
    let name = if opts.name.is_empty() {
        base_name(dir)
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
        tools.push(memory_tool_with(
            dir,
            MemoryToolOptions { notes: opts.notes },
        ));
    }
    let mut persona = new(
        name.clone(),
        Spec {
            does,
            soul,
            model,
            tools,
            ..Default::default()
        },
    );
    if let Some(d) = opts.dream {
        assert!(d.every_ms > 0, "fromDir: dream.everyMs must be > 0");
        let dreamer = dreamer(
            dir,
            DreamerOptions {
                store: Some(d.store),
                instructions: d.instructions,
                name: format!("{name}-dreamer"),
                model: String::new(),
            },
        )
        .unwrap_or_else(|e| panic!("{e}"));
        persona.dream = Some(Arc::new(DreamConfig {
            dreamer,
            every_ms: d.every_ms,
        }));
    }
    persona
}

/// What a dream pass is FOR and the order it works in (§7E "Dreaming"),
/// byte-identical in every port. It becomes the dreamer's `## HEARTBEAT.md`
/// section, so the ordinary [`HEARTBEAT_PROMPT`] wake drives a pass and
/// `HEARTBEAT_OK` keeps an idle pass silent.
pub const DREAM_PROMPT: &str = "You are dreaming: an offline pass over your own memory. Memory exists so that a future session decides better. MEMORY.md is a short index loaded at the start of every session; notes (memory target \"note:<slug>\") hold the detail; the transcripts (transcript_search) are the ground truth of what actually happened.
Work in this order:
1. Verify: read MEMORY.md and the notes it points to (memory read, memory list) and check each claim against the transcripts. Fix a claim the transcripts contradict; remove one they no longer support.
2. Demote: move detail out of MEMORY.md into a note and leave a one-line [[slug]] pointer, so the index stays short.
3. Merge: fold duplicate or conflicting notes into one.
4. Repair links: point every [[slug]] at an existing note or create it; give every note an index line.
5. Log: if you changed anything, append exactly one entry with memory add, target \"note:log\", text starting \"dream: \" and naming every change and why.
If nothing needs changing, write nothing and reply HEARTBEAT_OK.";

/// [`dreamer`] options.
#[derive(Clone, Default)]
pub struct DreamerOptions {
    /// The transcripts to verify memory against — must support `list`. Required.
    pub store: Option<Arc<dyn ConversationStore>>,
    /// Host prose appended after [`DREAM_PROMPT`] (and `DREAM.md`, if present).
    pub instructions: String,
    /// Default `<basename(dir)>-dreamer`.
    pub name: String,
    /// Default `"inherit"` — the persona's client (use a cheap model there).
    pub model: String,
}

/// The built-in dreamer preset (§7E "Dreaming"): a plain [`Agent`] whose tools
/// are EXACTLY `memory` (notes on, over `dir`) and `transcript_search` (over
/// the store) — never the persona's own tools. Its soul is one
/// `## HEARTBEAT.md` section holding [`DREAM_PROMPT`], then `DREAM.md` from
/// `dir` (trimmed, if non-empty), then `instructions` (trimmed, if non-empty),
/// joined by blank lines. Schedule it with [`start_agent`] or run one pass
/// with `run(opts, HEARTBEAT_PROMPT)`.
pub fn dreamer(dir: impl AsRef<Path>, opts: DreamerOptions) -> Result<Agent, String> {
    let dir = dir.as_ref();
    let store = opts.store.ok_or("dreamer: store is required")?;
    let search = transcript_search_tool(store, TranscriptSearchOptions::default())?;
    let mut parts = vec![DREAM_PROMPT.to_string()];
    if let Some(body) = read_capped(&dir.join("DREAM.md")) {
        let t = body.trim();
        if !t.is_empty() {
            parts.push(t.to_string());
        }
    }
    let extra = opts.instructions.trim();
    if !extra.is_empty() {
        parts.push(extra.to_string());
    }
    let name = if opts.name.is_empty() {
        format!("{}-dreamer", base_name(dir))
    } else {
        opts.name
    };
    let model = if opts.model.is_empty() {
        "inherit".to_string()
    } else {
        opts.model
    };
    Ok(new(
        name,
        Spec {
            does: format!("dreams over the memory in {}", dir.display()),
            soul: format!("## HEARTBEAT.md\n\n{}", parts.join("\n\n")),
            model,
            tools: vec![
                memory_tool_with(dir, MemoryToolOptions { notes: true }),
                search,
            ],
            ..Default::default()
        },
    ))
}

/// The wake prompt of every heartbeat.
pub const HEARTBEAT_PROMPT: &str = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs attention, reply HEARTBEAT_OK.";

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
    /// The clock loop and every in-flight beat turn; `stop` awaits them all.
    tasks: Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>>,
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
    /// End the heartbeat and close the runtime (stop-all at the root). Like
    /// Go's `Stop`, it first waits for a beat turn that is still in flight.
    pub async fn stop(&self) {
        self.stop.cancel();
        // Drain until empty: the clock loop may register one last beat
        // before it observes the cancel.
        loop {
            let tasks: Vec<_> =
                std::mem::take(&mut *self.tasks.lock().unwrap_or_else(|e| e.into_inner()));
            if tasks.is_empty() {
                break;
            }
            for t in tasks {
                let _ = t.await;
            }
        }
        self.rt.close(&self.rt.root(), None).await;
    }
}

/// Give a persona its own clock: each `every_ms` it posts a tick to the
/// agent's own inbox and, when idle, wakes it with the heartbeat prompt. All
/// timing goes through the runtime's Clock.
///
/// A persona built by [`from_dir`] with `dream` also gets its dreamer
/// registered on the SAME runtime, spawned under the root and given its own
/// heartbeat; dreamer replies never reach `on_beat` or `beats`. `stop` stops
/// both.
pub fn start_agent(a: &Agent, opts: StartOptions) -> Result<StartedAgent, String> {
    let mut ro = opts.options.clone();
    ro.registry = a.registry();
    if let Some(d) = &a.dream
        && ro.registry.contains_key(&d.dreamer.name)
    {
        return Err(format!(
            "startAgent: dreamer name collision: {} is already registered",
            d.dreamer.name
        ));
    }
    if let Some(d) = &a.dream {
        for (k, v) in d.dreamer.registry() {
            ro.registry.entry(k).or_insert(v);
        }
    }
    let rt = Runtime::new(ro);
    let h = rt.spawn(&rt.root(), &a.name, None)?;
    let stop = CancellationToken::new();
    let beats = Arc::new(Mutex::new(Vec::new()));
    let beat = Arc::new(Notify::new());
    let tasks: Tasks = Arc::default();
    let on_text: OnBeatFn = {
        let (beats, on_beat) = (beats.clone(), opts.on_beat.clone());
        Arc::new(move |t: &str| {
            beats
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(t.to_string());
            if let Some(f) = &on_beat {
                f(t);
            }
        })
    };
    let main = heartbeat(
        &rt,
        &h,
        opts.every_ms,
        &stop,
        &tasks,
        on_text,
        Some(beat.clone()),
    );
    tasks.lock().unwrap_or_else(|e| e.into_inner()).push(main);
    if let Some(d) = &a.dream {
        // The dreamer's replies are housekeeping — what it did is in memory/log.md.
        let dh = match rt.spawn(&rt.root(), &d.dreamer.name, None) {
            Ok(dh) => dh,
            Err(e) => {
                // Never leak the persona's heartbeat when the dreamer cannot start.
                stop.cancel();
                for t in tasks.lock().unwrap_or_else(|e| e.into_inner()).drain(..) {
                    t.abort();
                }
                let (rt2, h2) = (rt.clone(), h.clone());
                tokio::spawn(async move { rt2.close(&h2, None).await });
                return Err(e);
            }
        };
        let dream = heartbeat(&rt, &dh, d.every_ms, &stop, &tasks, Arc::new(|_| {}), None);
        tasks.lock().unwrap_or_else(|e| e.into_inner()).push(dream);
    }
    Ok(StartedAgent {
        rt,
        handle: h,
        stop,
        tasks,
        beats,
        beat,
    })
}

type Tasks = Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>>;

/// One heartbeat loop on `h` until `stop`: tick into the inbox, wake when idle,
/// hand a substantive reply to `on_text`, pulse `beat` after each cycle.
fn heartbeat(
    rt: &Runtime,
    h: &Handle,
    every_ms: u64,
    stop: &CancellationToken,
    tasks: &Tasks,
    on_text: OnBeatFn,
    beat: Option<Arc<Notify>>,
) -> tokio::task::JoinHandle<()> {
    let every = Duration::from_millis(every_ms);
    let (rt, h, stop, tasks) = (rt.clone(), h.clone(), stop.clone(), tasks.clone());
    tokio::spawn(async move {
        let clock = rt.clock();
        loop {
            tokio::select! {
                _ = stop.cancelled() => return,
                _ = clock.after(every) => {}
            }
            rt.post(&h, InboxItem::new("clock", "timer", "tick"));
            if rt.state_of(&h) == State::Idle && rt.wake(&h, HEARTBEAT_PROMPT).ok {
                // The beat's turn is awaited off the clock loop, so ticks
                // keep landing (and coalescing) while it runs.
                let (rt, h, on_text, beat) = (rt.clone(), h.clone(), on_text.clone(), beat.clone());
                let turn = tokio::spawn(async move {
                    let r = rt.wait(&h, None).await;
                    if !r.is_error && !r.text.contains(HEARTBEAT_OK) {
                        on_text(&r.text);
                    }
                    if let Some(b) = &beat {
                        b.notify_one();
                    }
                });
                tasks.lock().unwrap_or_else(|e| e.into_inner()).push(turn);
            } else if let Some(b) = &beat {
                b.notify_one();
            }
        }
    })
}
