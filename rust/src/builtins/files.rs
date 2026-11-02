//! read / write / edit / grep / glob / apply_patch (port of `golang/builtin.go`).

use std::path::Path;
use std::sync::Arc;

use base64::Engine as _;
use regex::Regex;
use serde_json::json;

use super::diff::{FileChange, add_diff_meta, read_if_exists};
use super::{
    Args, BuiltinEnv, abs, arg_bool, arg_num, arg_str, b_err, b_ok, builtin, dir_arg, go_io_err,
    meta, rel_path, to_slash,
};
use crate::tool::{ContentPart, Tool, ToolResult};

// ---------------------------------------------------------------------------
// media table (§4A / §1B)
// ---------------------------------------------------------------------------

/// `(mimeType, partType)` for a recognised media extension.
pub(crate) fn lookup_media(path: &str) -> Option<(&'static str, &'static str)> {
    let ext = Path::new(path)
        .extension()
        .map(|e| e.to_string_lossy().to_lowercase())?;
    Some(match ext.as_str() {
        "png" => ("image/png", "image"),
        "jpg" | "jpeg" => ("image/jpeg", "image"),
        "gif" => ("image/gif", "image"),
        "webp" => ("image/webp", "image"),
        "pdf" => ("application/pdf", "file"),
        "mp3" => ("audio/mpeg", "audio"),
        "wav" => ("audio/wav", "audio"),
        _ => return None,
    })
}

// ---------------------------------------------------------------------------
// glob + walk
// ---------------------------------------------------------------------------

fn glob_to_regex(glob: &str) -> Regex {
    const ESC: &str = "\\^$.|+()[]{}";
    let b = glob.as_bytes();
    let mut out = String::from("^");
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        if c == b'*' {
            if i + 1 < b.len() && b[i + 1] == b'*' {
                out.push_str(".*");
                i += 1;
                if i + 1 < b.len() && b[i + 1] == b'/' {
                    i += 1;
                }
            } else {
                out.push_str("[^/]*");
            }
            i += 1;
        } else if c == b'?' {
            out.push_str("[^/]");
            i += 1;
        } else if ESC.as_bytes().contains(&c) {
            out.push('\\');
            out.push(c as char);
            i += 1;
        } else {
            // copy one whole UTF-8 char
            let ch = glob[i..].chars().next().unwrap_or('\u{FFFD}');
            out.push(ch);
            i += ch.len_utf8().max(1);
        }
    }
    out.push('$');
    Regex::new(&out).unwrap_or_else(|_| Regex::new("^$").expect("static regex"))
}

fn match_glob(rel: &str, re: &Regex, glob: &str) -> bool {
    if !glob.contains('/') {
        let base = Path::new(rel)
            .file_name()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_default();
        return re.is_match(&base);
    }
    re.is_match(&to_slash(rel))
}

/// Every regular file under `root` (skipping node_modules/.git below the root),
/// in walk order — callers sort (the capped-listing rule).
fn walk_files(root: &str) -> Vec<String> {
    let mut out = Vec::new();
    let rootp = Path::new(root);
    match std::fs::metadata(rootp) {
        Ok(m) if m.is_dir() => walk_dir(rootp, &mut out),
        Ok(m) if m.is_file() => out.push(root.to_owned()),
        _ => {}
    }
    out
}

fn walk_dir(dir: &Path, out: &mut Vec<String>) {
    let Ok(rd) = std::fs::read_dir(dir) else {
        return;
    };
    let mut entries: Vec<_> = rd.filter_map(Result::ok).collect();
    entries.sort_by_key(|e| e.file_name());
    for e in entries {
        let Ok(ft) = e.file_type() else { continue };
        let p = e.path();
        if ft.is_dir() {
            let name = e.file_name();
            if name == "node_modules" || name == ".git" {
                continue;
            }
            walk_dir(&p, out);
        } else if ft.is_file() {
            out.push(p.to_string_lossy().into_owned());
        }
    }
}

fn rel_to(root: &str, file: &str) -> String {
    rel_path(&abs(root), &abs(file)).unwrap_or_else(|| file.to_owned())
}

fn limit_arg(args: &Args) -> usize {
    match arg_num(args, "limit") {
        Some(l) => l.max(0.0) as usize,
        None => 100,
    }
}

// ---------------------------------------------------------------------------
// read
// ---------------------------------------------------------------------------

pub(crate) fn read_tool(env: Arc<BuiltinEnv>) -> Tool {
    builtin(
        "read",
        "Read a file. A recognised media file (png/jpg/jpeg/gif/webp/pdf/mp3/wav) comes back as a content part; anything else is read as UTF-8 text, and with offset/limit only that line window.",
        json!({
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to the file to read"},
                "offset": {"type": "number", "description": "1-based line to start from"},
                "limit": {"type": "number", "description": "Maximum number of lines to read"}
            },
            "required": ["path"],
            "additionalProperties": false
        }),
        move |args, _ctx| {
            let env = env.clone();
            async move { read_impl(&env, &args) }
        },
    )
}

fn read_impl(env: &BuiltinEnv, args: &Args) -> ToolResult {
    let p = arg_str(args, "path").unwrap_or("");
    if p.is_empty() {
        return b_err("read: path is required", None);
    }
    let full = match env.resolve_path(p) {
        Ok(f) => f,
        Err(e) => return b_err(format!("read: {e}"), None),
    };
    let raw = match std::fs::read(&full) {
        Ok(r) => r,
        Err(e) => return b_err(format!("read: {}", go_io_err("open", &full, &e)), None),
    };
    if let Some((mime, kind)) = lookup_media(&full) {
        let mut part = ContentPart {
            kind: kind.into(),
            mime_type: mime.into(),
            data: base64::engine::general_purpose::STANDARD.encode(&raw),
            ..Default::default()
        };
        if kind == "file" {
            part.name = Path::new(p)
                .file_name()
                .map(|s| s.to_string_lossy().into_owned())
                .unwrap_or_default();
        }
        let mut res = b_ok(format!("{p} ({mime}, {} bytes)", raw.len()), None);
        res.parts = vec![part];
        return res;
    }
    let Ok(content) = String::from_utf8(raw) else {
        return b_err(
            format!(
                "read: {p} is not valid UTF-8 text and its extension is not a recognised media type"
            ),
            None,
        );
    };
    if !args.contains_key("offset") && !args.contains_key("limit") {
        return b_ok(content, None);
    }
    let lines: Vec<&str> = content.split('\n').collect();
    let mut offset: i64 = 1;
    if let Some(o) = arg_num(args, "offset") {
        offset = (o as i64).max(1);
    }
    let mut start = offset - 1;
    let mut limit = lines.len() as i64 - start;
    if let Some(l) = arg_num(args, "limit") {
        limit = (l as i64).max(0);
    }
    start = start.clamp(0, lines.len() as i64);
    let end = (start + limit).max(start).min(lines.len() as i64);
    let (start, end) = (start as usize, end as usize);
    let out = lines[start..end].join("\n");
    let mut total = lines.len();
    if total > 0 && lines[total - 1].is_empty() {
        total -= 1;
    }
    if end < total {
        return b_ok(
            out,
            meta(vec![
                ("truncated", json!(true)),
                ("totalLines", json!(total)),
                ("nextOffset", json!(end + 1)),
            ]),
        );
    }
    b_ok(out, None)
}

// ---------------------------------------------------------------------------
// write / edit
// ---------------------------------------------------------------------------

fn mkdir_parent(full: &str) {
    if let Some(parent) = Path::new(&abs(full)).parent() {
        let _ = std::fs::create_dir_all(parent);
    }
}

pub(crate) fn write_tool(env: Arc<BuiltinEnv>) -> Tool {
    builtin(
        "write",
        "Write content to a file (create/overwrite), creating parent directories.",
        json!({
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to write to"},
                "content": {"type": "string", "description": "Content to write"}
            },
            "required": ["path", "content"],
            "additionalProperties": false
        }),
        move |args, _ctx| {
            let env = env.clone();
            async move {
                let p = arg_str(&args, "path").unwrap_or("");
                if p.is_empty() {
                    return b_err("write: path is required", None);
                }
                let content = arg_str(&args, "content").unwrap_or("");
                let full = match env.resolve_path(p) {
                    Ok(f) => f,
                    Err(e) => return b_err(format!("write: {e}"), None),
                };
                let before = read_if_exists(&full);
                mkdir_parent(&full);
                if let Err(e) = std::fs::write(&full, content) {
                    return b_err(format!("write: {}", go_io_err("open", &full, &e)), None);
                }
                let bytes = content.len();
                let status = if before.is_some() {
                    "modified"
                } else {
                    "added"
                };
                let mut m = meta(vec![("bytes", json!(bytes))]).unwrap_or_default();
                add_diff_meta(
                    &mut m,
                    &[FileChange {
                        path: env.diff_path(p, &full),
                        status,
                        before: before.unwrap_or_default(),
                        after: content.to_owned(),
                    }],
                );
                b_ok(format!("Wrote {bytes} bytes to {p}"), Some(m))
            }
        },
    )
}

pub(crate) fn edit_tool(env: Arc<BuiltinEnv>) -> Tool {
    builtin(
        "edit",
        "Exact-string replace in a file. Default replaces a single unique occurrence; replaceAll replaces all.",
        json!({
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to the file to edit"},
                "oldString": {"type": "string", "description": "Exact string to replace"},
                "newString": {"type": "string", "description": "Replacement string"},
                "replaceAll": {"type": "boolean", "description": "Replace all occurrences"}
            },
            "required": ["path", "oldString", "newString"],
            "additionalProperties": false
        }),
        move |args, _ctx| {
            let env = env.clone();
            async move {
                let p = arg_str(&args, "path").unwrap_or("");
                if p.is_empty() {
                    return b_err("edit: path is required", None);
                }
                let old = arg_str(&args, "oldString").unwrap_or("");
                if old.is_empty() {
                    return b_err("edit: oldString is required", None);
                }
                let new = arg_str(&args, "newString").unwrap_or("");
                let full = match env.resolve_path(p) {
                    Ok(f) => f,
                    Err(e) => return b_err(format!("edit: {e}"), None),
                };
                let content = match std::fs::read(&full) {
                    Ok(r) => String::from_utf8_lossy(&r).into_owned(),
                    Err(e) => {
                        return b_err(format!("edit: {}", go_io_err("open", &full, &e)), None);
                    }
                };
                let count = content.matches(old).count();
                if count == 0 {
                    return b_err(format!("edit: oldString not found in {p}"), None);
                }
                let (next, replacements) = if arg_bool(&args, "replaceAll") {
                    (content.replace(old, new), count)
                } else {
                    if count > 1 {
                        return b_err(
                            format!(
                                "edit: oldString is not unique in {p} ({count} occurrences); use replaceAll"
                            ),
                            None,
                        );
                    }
                    (content.replacen(old, new, 1), 1)
                };
                if let Err(e) = std::fs::write(&full, &next) {
                    return b_err(format!("edit: {}", go_io_err("open", &full, &e)), None);
                }
                let plural = if replacements == 1 { "" } else { "s" };
                let mut m = meta(vec![("replacements", json!(replacements))]).unwrap_or_default();
                add_diff_meta(
                    &mut m,
                    &[FileChange {
                        path: env.diff_path(p, &full),
                        status: "modified",
                        before: content,
                        after: next,
                    }],
                );
                b_ok(
                    format!("Edited {p} ({replacements} replacement{plural})"),
                    Some(m),
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------
// grep / glob
// ---------------------------------------------------------------------------

pub(crate) fn grep_tool(env: Arc<BuiltinEnv>) -> Tool {
    builtin(
        "grep",
        "Search file contents by regex under a directory. Output is file:line:text matches.",
        json!({
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Regular expression to search for"},
                "path": {"type": "string", "description": "Directory to search (default: process cwd)"},
                "include": {"type": "string", "description": "Glob filter for file names"},
                "limit": {"type": "number", "description": "Maximum number of matches (default 100)"},
                "context": {"type": "number", "description": "Lines of context before and after each match"},
                "before": {"type": "number", "description": "Lines of context before each match (overrides context)"},
                "after": {"type": "number", "description": "Lines of context after each match (overrides context)"}
            },
            "required": ["pattern"],
            "additionalProperties": false
        }),
        move |args, _ctx| {
            let env = env.clone();
            async move { grep_impl(&env, &args) }
        },
    )
}

struct Hit {
    rel: String,
    line: usize,
    text: String,
}

fn grep_impl(env: &BuiltinEnv, args: &Args) -> ToolResult {
    let pattern = arg_str(args, "pattern").unwrap_or("");
    if pattern.is_empty() {
        return b_err("grep: pattern is required", None);
    }
    let re = match Regex::new(pattern) {
        Ok(r) => r,
        Err(e) => return b_err(format!("grep: invalid regex: {e}"), None),
    };
    let root = match dir_arg(args, env) {
        Ok(r) => r,
        Err(e) => return b_err(format!("grep: {e}"), None),
    };
    let include = arg_str(args, "include").unwrap_or("");
    let include_re = (!include.is_empty()).then(|| glob_to_regex(include));
    let limit = limit_arg(args);
    let (mut before, mut after) = (0usize, 0usize);
    if let Some(c) = arg_num(args, "context")
        && c > 0.0
    {
        before = c as usize;
        after = c as usize;
    }
    if let Some(b) = arg_num(args, "before")
        && b >= 0.0
    {
        before = b as usize;
    }
    if let Some(a) = arg_num(args, "after")
        && a >= 0.0
    {
        after = a as usize;
    }
    let mut file_lines: std::collections::HashMap<String, Vec<String>> = Default::default();
    let mut hits: Vec<Hit> = Vec::new();
    for file in walk_files(&root) {
        let rel = rel_to(&root, &file);
        if let Some(ire) = &include_re
            && !match_glob(&rel, ire, include)
        {
            continue;
        }
        let Ok(raw) = std::fs::read(&file) else {
            continue;
        };
        let text = String::from_utf8_lossy(&raw);
        let lines: Vec<String> = text.split('\n').map(String::from).collect();
        let slash = to_slash(&rel);
        for (i, line) in lines.iter().enumerate() {
            if re.is_match(line) {
                hits.push(Hit {
                    rel: slash.clone(),
                    line: i + 1,
                    text: format!("{slash}:{}:{line}", i + 1),
                });
            }
        }
        if before > 0 || after > 0 {
            file_lines.insert(slash, lines);
        }
    }
    hits.sort_by(|a, b| a.rel.cmp(&b.rel).then(a.line.cmp(&b.line)));
    let total = hits.len();
    hits.truncate(limit);
    let mut out_lines: Vec<String> = Vec::new();
    if before == 0 && after == 0 {
        out_lines.extend(hits.iter().map(|h| h.text.clone()));
    } else {
        let mut match_at: std::collections::HashMap<&str, std::collections::HashSet<usize>> =
            Default::default();
        for h in &hits {
            match_at.entry(h.rel.as_str()).or_default().insert(h.line);
        }
        let (mut last_rel, mut last_line) = (String::new(), 0usize);
        for h in &hits {
            let lines = &file_lines[&h.rel];
            let mut n = lines.len();
            if n > 0 && lines[n - 1].is_empty() {
                n -= 1;
            }
            let mut from = h.line.saturating_sub(before).max(1);
            let to = n.min(h.line + after);
            if h.rel == last_rel && from <= last_line + 1 {
                from = last_line + 1;
            } else if !last_rel.is_empty() {
                out_lines.push("--".into());
            }
            let mut k = from;
            while k <= to {
                let sep = if match_at[h.rel.as_str()].contains(&k) {
                    ":"
                } else {
                    "-"
                };
                out_lines.push(format!("{}{sep}{k}{sep}{}", h.rel, lines[k - 1]));
                k += 1;
            }
            if to > last_line || h.rel != last_rel {
                last_line = to;
            }
            last_rel = h.rel.clone();
        }
    }
    let mut m = meta(vec![("count", json!(hits.len()))]).unwrap_or_default();
    if total > hits.len() {
        m.insert("truncated".into(), json!(true));
        m.insert("total".into(), json!(total));
    }
    b_ok(out_lines.join("\n"), Some(m))
}

pub(crate) fn glob_tool(env: Arc<BuiltinEnv>) -> Tool {
    builtin(
        "glob",
        "List files matching a glob under a directory. Output is newline-joined relative paths.",
        json!({
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Glob pattern to match"},
                "path": {"type": "string", "description": "Directory to search (default: process cwd)"},
                "limit": {"type": "number", "description": "Maximum number of results (default 100)"}
            },
            "required": ["pattern"],
            "additionalProperties": false
        }),
        move |args, _ctx| {
            let env = env.clone();
            async move {
                let pattern = arg_str(&args, "pattern").unwrap_or("");
                if pattern.is_empty() {
                    return b_err("glob: pattern is required", None);
                }
                let root = match dir_arg(&args, &env) {
                    Ok(r) => r,
                    Err(e) => return b_err(format!("glob: {e}"), None),
                };
                let limit = limit_arg(&args);
                let re = glob_to_regex(pattern);
                let mut found: Vec<String> = walk_files(&root)
                    .iter()
                    .map(|f| rel_to(&root, f))
                    .filter(|rel| match_glob(rel, &re, pattern))
                    .map(|rel| to_slash(&rel))
                    .collect();
                found.sort();
                found.truncate(limit);
                let count = found.len();
                b_ok(found.join("\n"), meta(vec![("count", json!(count))]))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// apply_patch
// ---------------------------------------------------------------------------

enum PatchOp {
    Add { path: String, content: String },
    Update { path: String, body: Vec<String> },
    Delete { path: String },
}

impl PatchOp {
    fn path(&self) -> &str {
        match self {
            PatchOp::Add { path, .. } | PatchOp::Update { path, .. } | PatchOp::Delete { path } => {
                path
            }
        }
    }
    fn set_path(&mut self, p: String) {
        match self {
            PatchOp::Add { path, .. } | PatchOp::Update { path, .. } | PatchOp::Delete { path } => {
                *path = p
            }
        }
    }
}

fn file_marker(line: &str) -> Option<(&str, &str)> {
    let rest = line.strip_prefix("*** ")?;
    for kind in ["Add", "Update", "Delete"] {
        if let Some(r) = rest.strip_prefix(kind)
            && let Some(p) = r.strip_prefix(" File: ")
            && !p.is_empty()
        {
            return Some((kind, p));
        }
    }
    None
}

fn parse_patch(text: &str) -> Result<Vec<PatchOp>, String> {
    let lines: Vec<&str> = text.split('\n').collect();
    let mut i = 0;
    while i < lines.len() && lines[i].trim().is_empty() {
        i += 1;
    }
    if i >= lines.len() || lines[i].trim() != "*** Begin Patch" {
        return Err("missing '*** Begin Patch'".into());
    }
    i += 1;
    let mut ops = Vec::new();
    while i < lines.len() {
        let line = lines[i];
        if line.trim() == "*** End Patch" {
            return Ok(ops);
        }
        if line.trim().is_empty() {
            i += 1;
            continue;
        }
        let Some((kind, p)) = file_marker(line) else {
            return Err(format!("unexpected line: {line}"));
        };
        let p = p.trim().to_owned();
        i += 1;
        let mut body = Vec::new();
        while i < lines.len()
            && lines[i].trim() != "*** End Patch"
            && file_marker(lines[i]).is_none()
        {
            body.push(lines[i].to_owned());
            i += 1;
        }
        match kind {
            "Add" => {
                let content = body
                    .iter()
                    .map(|l| l.strip_prefix('+').unwrap_or(l))
                    .collect::<Vec<_>>()
                    .join("\n");
                ops.push(PatchOp::Add { path: p, content });
            }
            "Delete" => ops.push(PatchOp::Delete { path: p }),
            _ => ops.push(PatchOp::Update { path: p, body }),
        }
    }
    Err("missing '*** End Patch'".into())
}

fn apply_update(content: &str, body: &[String]) -> Result<String, String> {
    let mut hunks: Vec<Vec<&str>> = Vec::new();
    let mut cur: Vec<&str> = Vec::new();
    for l in body {
        if l.starts_with("@@") {
            if !cur.is_empty() {
                hunks.push(std::mem::take(&mut cur));
            }
        } else {
            cur.push(l);
        }
    }
    if !cur.is_empty() {
        hunks.push(cur);
    }
    let mut result = content.to_owned();
    for hunk in hunks {
        let (mut old, mut new) = (Vec::new(), Vec::new());
        for l in hunk {
            if let Some(r) = l.strip_prefix('-') {
                old.push(r);
            } else if let Some(r) = l.strip_prefix('+') {
                new.push(r);
            } else if let Some(r) = l.strip_prefix(' ') {
                old.push(r);
                new.push(r);
            } else {
                old.push(l);
                new.push(l);
            }
        }
        let old_block = old.join("\n");
        let new_block = new.join("\n");
        if !old_block.is_empty() {
            if !result.contains(&old_block) {
                return Err("hunk does not match file contents".into());
            }
            result = result.replacen(&old_block, &new_block, 1);
        } else {
            let suffix = if !result.is_empty() && !result.ends_with('\n') {
                "\n"
            } else {
                ""
            };
            result = format!("{result}{suffix}{new_block}");
        }
    }
    Ok(result)
}

pub(crate) fn apply_patch_tool(env: Arc<BuiltinEnv>) -> Tool {
    builtin(
        "apply_patch",
        "Apply a patch (Begin/End Patch grammar: Add/Update/Delete File). Atomic — a non-matching hunk aborts with no writes.",
        json!({
            "type": "object",
            "properties": {
                "patchText": {"type": "string", "description": "The patch text in Begin/End Patch format"}
            },
            "required": ["patchText"],
            "additionalProperties": false
        }),
        move |args, _ctx| {
            let env = env.clone();
            async move { apply_patch_impl(&env, &args) }
        },
    )
}

fn apply_patch_impl(env: &BuiltinEnv, args: &Args) -> ToolResult {
    let text = arg_str(args, "patchText").unwrap_or("");
    if text.is_empty() {
        return b_err("apply_patch: patchText is required", None);
    }
    let mut ops = match parse_patch(text) {
        Ok(o) => o,
        Err(e) => return b_err(format!("apply_patch: {e}"), None),
    };
    let mut shown = Vec::with_capacity(ops.len());
    for op in &mut ops {
        let full = match env.resolve_path(op.path()) {
            Ok(f) => f,
            Err(e) => return b_err(format!("apply_patch: {e}"), None),
        };
        shown.push(env.diff_path(op.path(), &full));
        op.set_path(full);
    }
    let mut changes = Vec::new();
    let mut writes: Vec<(String, String)> = Vec::new();
    let mut deletes: Vec<String> = Vec::new();
    for (oi, op) in ops.iter().enumerate() {
        match op {
            PatchOp::Add { path, content } => {
                if Path::new(path).exists() {
                    return b_err(format!("apply_patch: file already exists: {path}"), None);
                }
                writes.push((path.clone(), content.clone()));
                changes.push(FileChange {
                    path: shown[oi].clone(),
                    status: "added",
                    before: String::new(),
                    after: content.clone(),
                });
            }
            PatchOp::Delete { path } => {
                if !Path::new(path).exists() {
                    return b_err(format!("apply_patch: file not found: {path}"), None);
                }
                deletes.push(path.clone());
                changes.push(FileChange {
                    path: shown[oi].clone(),
                    status: "deleted",
                    before: read_if_exists(path).unwrap_or_default(),
                    after: String::new(),
                });
            }
            PatchOp::Update { path, body } => {
                let raw = match std::fs::read(path) {
                    Ok(r) => String::from_utf8_lossy(&r).into_owned(),
                    Err(e) => {
                        return b_err(
                            format!("apply_patch: {}", go_io_err("open", path, &e)),
                            None,
                        );
                    }
                };
                let next = match apply_update(&raw, body) {
                    Ok(n) => n,
                    Err(e) => return b_err(format!("apply_patch: {e}"), None),
                };
                writes.push((path.clone(), next.clone()));
                changes.push(FileChange {
                    path: shown[oi].clone(),
                    status: "modified",
                    before: raw,
                    after: next,
                });
            }
        }
    }
    for (p, c) in &writes {
        mkdir_parent(p);
        if let Err(e) = std::fs::write(p, c) {
            return b_err(format!("apply_patch: {}", go_io_err("open", p, &e)), None);
        }
    }
    for d in &deletes {
        let _ = std::fs::remove_file(d);
    }
    let added = ops
        .iter()
        .filter(|o| matches!(o, PatchOp::Add { .. }))
        .count();
    let updated = ops
        .iter()
        .filter(|o| matches!(o, PatchOp::Update { .. }))
        .count();
    let deleted = ops
        .iter()
        .filter(|o| matches!(o, PatchOp::Delete { .. }))
        .count();
    let plural = if ops.len() == 1 { "" } else { "s" };
    let mut m = meta(vec![
        ("added", json!(added)),
        ("updated", json!(updated)),
        ("deleted", json!(deleted)),
    ])
    .unwrap_or_default();
    add_diff_meta(&mut m, &changes);
    b_ok(
        format!("Applied patch: {} file operation{plural}", ops.len()),
        Some(m),
    )
}
