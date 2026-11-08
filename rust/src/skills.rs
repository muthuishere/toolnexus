//! Agent skills (SPEC §0 items 5–6, §3): discover `**/SKILL.md`, parse YAML
//! frontmatter, and expose ONE `skill` tool that loads a skill's instructions
//! plus a sample of its files on demand. Output is byte-identical to the other
//! ports; port of `golang/skill.go`, including its ordering rules.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde_json::{Map, Value, json};
use yaml_rust2::{Yaml, YamlLoader};

use crate::tool::{Tool, ToolResult, ToolSource};

/// The `skill` tool's description, verbatim from opencode.
pub const SKILL_TOOL_DESCRIPTION: &str = "Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.\n\nUse this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.\n\nThe skill name must match one of the skills listed in your system prompt.";

/// Prepended to [`SkillSource::prompt`] when at least one skill has a description.
pub const SKILLS_PROMPT_PREAMBLE: &str = "Skills provide specialized instructions and workflows for specific tasks.\nUse the skill tool to load a skill when a task matches its description.";

/// One discovered skill.
#[derive(Debug, Clone, PartialEq)]
pub struct SkillInfo {
    pub name: String,
    pub description: String,
    /// Absolute path to SKILL.md (filesystem) or the logical base (data).
    pub location: String,
    /// The body after the frontmatter.
    pub content: String,
    /// `"fs"` or `"logical"`.
    pub origin: String,
    pub resources: Vec<String>,
    pub base: String,
}

/// A skill supplied as data (S1); never touches disk.
#[derive(Debug, Clone, Default)]
pub struct SkillDef {
    pub name: String,
    pub description: String,
    pub content: String,
    pub resources: Vec<String>,
    /// Logical base; default `skill://<name>/`.
    pub base: String,
}

/// Why a candidate did not become a skill (S3). Wire values match every port.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkillSkipReason {
    MissingName,
    MalformedFrontmatter,
    DuplicateName,
    Unreadable,
}

impl SkillSkipReason {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::MissingName => "missing-name",
            Self::MalformedFrontmatter => "malformed-frontmatter",
            Self::DuplicateName => "duplicate-name",
            Self::Unreadable => "unreadable",
        }
    }
}

/// A skipped candidate. `detail` is the native parser message (not compared across ports).
#[derive(Debug, Clone, PartialEq)]
pub struct SkillSkip {
    pub location: String,
    pub reason: SkillSkipReason,
    pub detail: String,
}

/// The published Agent Skills description limit, in Unicode code points.
/// Exactly this many does not warn (SPEC.md §3, issue #84).
pub const MAX_SKILL_DESCRIPTION_CHARS: usize = 1024;

/// Why a KEPT skill was flagged (SPEC.md §3, issue #84).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkillWarningReason {
    /// Description longer than [`MAX_SKILL_DESCRIPTION_CHARS`] code points; never modified.
    OversizedDescription,
}

impl SkillWarningReason {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::OversizedDescription => "oversized-description",
        }
    }
}

/// A skill that loaded but breaks a published limit. A warning, not a skip:
/// the skill stays, byte-identical.
#[derive(Debug, Clone, PartialEq)]
pub struct SkillWarning {
    pub location: String,
    pub reason: SkillWarningReason,
    /// Description length in Unicode code points (not bytes).
    pub chars: usize,
}

/// The result of a list-only pass (S3).
#[derive(Debug, Clone)]
pub struct SkillInventory {
    pub skills: Vec<SkillInfo>,
    pub skipped: Vec<SkillSkip>,
    /// Kept skills over a published limit (unfiltered), in discovery order.
    pub warnings: Vec<SkillWarning>,
}

/// Options for [`load_skills_with`] / [`list_skills`].
#[derive(Debug, Clone, Default)]
pub struct LoadSkillsOptions {
    pub dirs: Vec<PathBuf>,
    pub skills: Vec<SkillDef>,
    /// Per-agent allowlist (S2): empty ⇒ all; any `true` ⇒ allowlist; only `false` ⇒ drop-list.
    pub filter: HashMap<String, bool>,
    /// 0 ⇒ default 10, n>0 ⇒ cap, -1 ⇒ omit `<skill_files>` (S5).
    pub sample_limit: i64,
}

/// Discovered skills plus the `skill` tool.
#[derive(Debug, Clone)]
pub struct SkillSource {
    pub skills: BTreeMap<String, SkillInfo>,
    pub tool: Tool,
    /// Every candidate that did not become a skill (unfiltered).
    pub skipped: Vec<SkillSkip>,
    /// Exposed skills (after the allowlist) over a published limit, in discovery order.
    pub warnings: Vec<SkillWarning>,
}

// ---------------------------------------------------------------------------
// frontmatter
// ---------------------------------------------------------------------------

#[derive(Default)]
pub(crate) struct Frontmatter {
    pub(crate) name: String,
    pub(crate) description: String,
}

/// Split `---`-fenced frontmatter: `^---\r?\n(.*?)\r?\n---\r?\n?(.*)$` (dotall).
fn split_frontmatter(text: &str) -> Option<(&str, &str)> {
    let rest = text
        .strip_prefix("---\n")
        .or_else(|| text.strip_prefix("---\r\n"))?;
    // the first "\n---" (optionally "\r\n---") that ends a line, lazily
    if let Some(at) = rest.find("\n---") {
        let block_end = if at > 0 && rest.as_bytes()[at - 1] == b'\r' {
            at - 1
        } else {
            at
        };
        let after = &rest[at + 4..];
        let body = if let Some(b) = after.strip_prefix("\r\n") {
            b
        } else if let Some(b) = after.strip_prefix('\n') {
            b
        } else {
            after
        };
        return Some((&rest[..block_end], body));
    }
    None
}

fn scalar_string(y: &Yaml) -> Option<String> {
    match y {
        Yaml::String(s) => Some(s.trim().to_owned()),
        Yaml::Integer(i) => Some(i.to_string()),
        Yaml::Real(r) => Some(match r.parse::<f64>() {
            Ok(f) if f.fract() == 0.0 && f.abs() < 1e21 => format!("{}", f as i64),
            Ok(f) => format!("{f}"),
            Err(_) => r.clone(),
        }),
        Yaml::Boolean(b) => Some(b.to_string()),
        _ => None,
    }
}

/// Real YAML first; only when it refuses, a guarded line-wise rescue of
/// `name`/`description` (ADR 0028). Returns (frontmatter, body, malformed, detail).
pub(crate) fn parse_frontmatter(text: &str) -> (Frontmatter, String, bool, String) {
    let Some((block, body)) = split_frontmatter(text) else {
        return (
            Frontmatter::default(),
            text.to_owned(),
            false,
            String::new(),
        );
    };
    let detail = match YamlLoader::load_from_str(block) {
        Ok(docs) => match docs.first() {
            Some(Yaml::Hash(h)) => {
                let mut fm = Frontmatter::default();
                let mut wrong = None;
                for key in ["name", "description"] {
                    let Some(v) = h.get(&Yaml::String(key.into())) else {
                        continue;
                    };
                    match scalar_string(v) {
                        Some(s) if key == "name" => fm.name = s,
                        Some(s) => fm.description = s,
                        None => {
                            wrong = Some(key);
                            break;
                        }
                    }
                }
                match wrong {
                    None => return (fm, body.to_owned(), false, String::new()),
                    Some(k) => format!("frontmatter key {k:?} is not a scalar"),
                }
            }
            _ => "frontmatter is not a YAML mapping".to_owned(),
        },
        Err(e) => e.to_string(),
    };
    let fm = lenient_frontmatter(block);
    if fm.name.is_empty() {
        return (fm, body.to_owned(), true, detail);
    }
    (fm, body.to_owned(), false, String::new())
}

/// A remote (MCP skills extension) skill as data: the same frontmatter parse
/// as a local `SKILL.md`, so the loaded body is identical. `None` when the
/// file has no usable name.
pub(crate) fn skill_def_from_markdown(
    text: &str,
    base: String,
    resources: Vec<String>,
) -> Option<SkillDef> {
    let (fm, content, malformed, _) = parse_frontmatter(text);
    if malformed || fm.name.is_empty() {
        return None;
    }
    Some(SkillDef {
        name: fm.name,
        description: fm.description,
        content,
        resources,
        base,
    })
}

fn lenient_frontmatter(block: &str) -> Frontmatter {
    let mut fm = Frontmatter::default();
    let mut seen = HashSet::new();
    for raw in block.split('\n') {
        let raw = raw.strip_suffix('\r').unwrap_or(raw);
        let Some(first) = raw.chars().next() else {
            continue;
        };
        if first == ' ' || first == '\t' || first == '#' {
            continue;
        }
        // ^([A-Za-z0-9_][A-Za-z0-9_.-]*):[ \t]*(.*)$
        let Some(colon) = raw.find(':') else { continue };
        let key = &raw[..colon];
        let valid_key = key
            .chars()
            .next()
            .is_some_and(|c| c.is_ascii_alphanumeric() || c == '_')
            && key
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '.' || c == '-');
        if !valid_key {
            continue;
        }
        let value = raw[colon + 1..].trim();
        if (key != "name" && key != "description") || seen.contains(key) {
            continue;
        }
        let Some(v0) = value.chars().next() else {
            continue;
        };
        if "|>&*[{!".contains(v0) {
            continue;
        }
        seen.insert(key.to_owned());
        let value = unquote(value);
        if key == "name" {
            fm.name = value;
        } else {
            fm.description = value;
        }
    }
    fm
}

fn unquote(v: &str) -> String {
    let b = v.as_bytes();
    if b.len() > 1 && b[0] == b[b.len() - 1] && (b[0] == b'"' || b[0] == b'\'') {
        return v[1..v.len() - 1].to_owned();
    }
    v.to_owned()
}

// ---------------------------------------------------------------------------
// walking (deterministic: never filesystem order)
// ---------------------------------------------------------------------------

fn is_dir_following(p: &Path) -> Option<(bool, bool)> {
    let md = std::fs::metadata(p).ok()?; // follows symlinks
    Some((md.is_dir(), md.is_file()))
}

fn rel_slash(root: &Path, p: &Path) -> String {
    p.strip_prefix(root)
        .unwrap_or(p)
        .to_string_lossy()
        .replace('\\', "/")
}

/// Walk `dir`, returning files that `keep(name)` accepts; skips node_modules/.git
/// and directories already seen through another symlink.
fn walk(dir: &Path, keep: &dyn Fn(&str) -> bool) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![dir.to_path_buf()];
    let mut seen = HashSet::new();
    while let Some(cur) = stack.pop() {
        let Ok(rd) = std::fs::read_dir(&cur) else {
            continue;
        };
        for entry in rd.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let full = cur.join(&name);
            let Some((is_dir, is_file)) = is_dir_following(&full) else {
                continue;
            };
            if is_dir {
                if name == "node_modules" || name == ".git" {
                    continue;
                }
                let Ok(real) = std::fs::canonicalize(&full) else {
                    continue;
                };
                if seen.insert(real) {
                    stack.push(full);
                }
            } else if is_file && keep(&name) {
                out.push(full);
            }
        }
    }
    out
}

/// SKILL.md files by depth ascending, then code point over the relative path.
fn walk_skill_files(root: &Path) -> Vec<PathBuf> {
    let mut files = walk(root, &|n| n == "SKILL.md");
    files.sort_by(|a, b| {
        let (ra, rb) = (rel_slash(root, a), rel_slash(root, b));
        let (da, db) = (ra.matches('/').count(), rb.matches('/').count());
        // Rust's str Ord is byte-wise over UTF-8 = Unicode code point order (normative).
        da.cmp(&db).then_with(|| ra.cmp(&rb)).then_with(|| a.cmp(b))
    });
    files
}

/// Sibling resources: ONE global code-point sort over relative paths, then the cap.
fn sample_sibling_files(dir: &Path, limit: i64) -> Vec<PathBuf> {
    let mut all = walk(dir, &|n| n != "SKILL.md");
    all.sort_by(|a, b| {
        rel_slash(dir, a)
            .cmp(&rel_slash(dir, b))
            .then_with(|| a.cmp(b))
    });
    if limit >= 0 && all.len() as i64 > limit {
        all.truncate(limit as usize);
    }
    all
}

// ---------------------------------------------------------------------------
// candidates + merge
// ---------------------------------------------------------------------------

enum Candidate {
    Info(SkillInfo),
    Skip(SkillSkip),
}

fn absolute(p: &Path) -> PathBuf {
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir()
            .map(|c| c.join(p))
            .unwrap_or_else(|_| p.to_path_buf())
    }
}

/// Lexically clean a path like Go's filepath.Abs (which calls Clean): drop `.`,
/// resolve `..`, without touching symlinks.
fn clean(p: &Path) -> PathBuf {
    use std::path::Component;
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

fn candidates_from_dir(root: &Path) -> Vec<Candidate> {
    let mut out = Vec::new();
    if !root.is_dir() {
        eprintln!("[toolnexus] skills dir not found: {}", root.display());
        return out;
    }
    for file in walk_skill_files(root) {
        let loc = file.to_string_lossy().into_owned();
        let Ok(raw) = std::fs::read_to_string(&file) else {
            out.push(Candidate::Skip(SkillSkip {
                location: loc,
                reason: SkillSkipReason::Unreadable,
                detail: String::new(),
            }));
            continue;
        };
        let (fm, content, malformed, detail) = parse_frontmatter(&raw);
        if malformed {
            out.push(Candidate::Skip(SkillSkip {
                location: loc,
                reason: SkillSkipReason::MalformedFrontmatter,
                detail,
            }));
            continue;
        }
        if fm.name.is_empty() {
            out.push(Candidate::Skip(SkillSkip {
                location: loc,
                reason: SkillSkipReason::MissingName,
                detail: String::new(),
            }));
            continue;
        }
        let abs = clean(&absolute(&file)).to_string_lossy().into_owned();
        out.push(Candidate::Info(SkillInfo {
            name: fm.name,
            description: fm.description,
            location: abs,
            content,
            origin: "fs".into(),
            resources: Vec::new(),
            base: String::new(),
        }));
    }
    out
}

fn candidates_from_defs(defs: &[SkillDef]) -> Vec<Candidate> {
    defs.iter()
        .map(|d| {
            if d.name.is_empty() {
                let loc = if d.base.is_empty() {
                    "skill://".to_owned()
                } else {
                    d.base.clone()
                };
                return Candidate::Skip(SkillSkip {
                    location: loc,
                    reason: SkillSkipReason::MissingName,
                    detail: String::new(),
                });
            }
            let base = if d.base.is_empty() {
                format!("skill://{}/", d.name)
            } else {
                d.base.clone()
            };
            Candidate::Info(SkillInfo {
                name: d.name.clone(),
                description: d.description.clone(),
                location: base.clone(),
                content: d.content.clone(),
                origin: "logical".into(),
                resources: d.resources.clone(),
                base,
            })
        })
        .collect()
}

fn collect(opts: &LoadSkillsOptions) -> Vec<Candidate> {
    let mut c = Vec::new();
    for root in &opts.dirs {
        c.extend(candidates_from_dir(root));
    }
    c.extend(candidates_from_defs(&opts.skills));
    c
}

type Merged = (BTreeMap<String, SkillInfo>, Vec<SkillSkip>, Vec<String>);

fn merge(cands: Vec<Candidate>) -> Merged {
    let mut skills = BTreeMap::new();
    let mut skipped = Vec::new();
    let mut order = Vec::new();
    for c in cands {
        match c {
            Candidate::Skip(s) => skipped.push(s),
            Candidate::Info(info) => {
                if skills.contains_key(&info.name) {
                    eprintln!(
                        "[toolnexus] duplicate skill name {:?} ({}) — keeping first",
                        info.name, info.location
                    );
                    skipped.push(SkillSkip {
                        location: info.location,
                        reason: SkillSkipReason::DuplicateName,
                        detail: String::new(),
                    });
                } else {
                    order.push(info.name.clone());
                    skills.insert(info.name.clone(), info);
                }
            }
        }
    }
    (skills, skipped, order)
}

/// Flag every skill in `skills`, walked in discovery `order`, whose description
/// exceeds the limit; each is also logged on the duplicate-name channel (stderr).
fn skill_warnings(skills: &BTreeMap<String, SkillInfo>, order: &[String]) -> Vec<SkillWarning> {
    order
        .iter()
        .filter_map(|name| skills.get(name))
        .filter_map(|info| {
            let n = info.description.chars().count();
            if n <= MAX_SKILL_DESCRIPTION_CHARS {
                return None;
            }
            eprintln!(
                "[toolnexus] skill {:?} ({}) description is {n} characters, over the {MAX_SKILL_DESCRIPTION_CHARS} limit — kept unmodified",
                info.name, info.location
            );
            Some(SkillWarning {
                location: info.location.clone(),
                reason: SkillWarningReason::OversizedDescription,
                chars: n,
            })
        })
        .collect()
}

fn apply_filter(
    skills: BTreeMap<String, SkillInfo>,
    filter: &HashMap<String, bool>,
) -> BTreeMap<String, SkillInfo> {
    if filter.is_empty() {
        return skills;
    }
    let has_true = filter.values().any(|v| *v);
    for k in filter.keys() {
        if !skills.contains_key(k) {
            eprintln!("[toolnexus] skill filter name {k:?} matched no skill");
        }
    }
    skills
        .into_iter()
        .filter(|(name, _)| match filter.get(name) {
            Some(v) if has_true => *v,
            None if has_true => false,
            Some(v) => *v,
            None => true,
        })
        .collect()
}

// ---------------------------------------------------------------------------
// public API
// ---------------------------------------------------------------------------

/// Discover skills under one or more roots (no data sources, default sampling).
pub fn load_skills<P: AsRef<Path>>(dirs: &[P]) -> SkillSource {
    load_skills_with(LoadSkillsOptions {
        dirs: dirs.iter().map(|d| d.as_ref().to_path_buf()).collect(),
        ..Default::default()
    })
}

/// List skills plus typed skips, without building a tool (S3). Unfiltered.
pub fn list_skills(opts: &LoadSkillsOptions) -> SkillInventory {
    let (skills, skipped, order) = merge(collect(opts));
    let warnings = skill_warnings(&skills, &order);
    SkillInventory {
        skills: skills.into_values().collect(),
        skipped,
        warnings,
    }
}

/// Discover skills (dirs and/or data) and build the `skill` tool.
pub fn load_skills_with(opts: LoadSkillsOptions) -> SkillSource {
    let (merged, skipped, order) = merge(collect(&opts));
    let skills = apply_filter(merged, &opts.filter);
    let warnings = skill_warnings(&skills, &order);
    let shared = Arc::new(skills.clone());
    let limit = opts.sample_limit;
    let tool = Tool {
        output_schema: None,
        annotations: None,
        concurrency_safe: None,
        name: "skill".into(),
        description: SKILL_TOOL_DESCRIPTION.into(),
        input_schema: json!({
            "type": "object",
            "properties": {"name": {"type": "string", "description": "The name of the skill to load"}},
            "required": ["name"],
            "additionalProperties": false
        }),
        source: ToolSource::Skill,
        hints: None,
        execute: Arc::new(move |args, _ctx| {
            let skills = shared.clone();
            Box::pin(async move { skill_output(&skills, &args, limit) })
        }),
    };
    SkillSource {
        skills,
        tool,
        skipped,
        warnings,
    }
}

fn skill_output(
    skills: &BTreeMap<String, SkillInfo>,
    args: &Value,
    sample_limit: i64,
) -> ToolResult {
    let name = args.get("name").and_then(Value::as_str).unwrap_or("");
    let Some(info) = skills.get(name) else {
        // BTreeMap keys iterate in byte order = code point order.
        let names: Vec<&str> = skills.keys().map(String::as_str).collect();
        let avail = if names.is_empty() {
            "none".to_owned()
        } else {
            names.join(", ")
        };
        return ToolResult::error(format!(
            "Skill {} not found. Available skills: {avail}",
            go_quote(name)
        ));
    };
    let eff = if sample_limit == 0 { 10 } else { sample_limit };
    let mut emit_files = eff != -1;
    let (base, meta_dir, files): (String, String, Vec<String>) = if info.origin == "logical" {
        let base = if info.base.is_empty() {
            format!("skill://{}/", info.name)
        } else {
            info.base.clone()
        };
        let mut res = info.resources.clone();
        if res.is_empty() {
            emit_files = false;
        }
        if eff > 0 && res.len() as i64 > eff {
            res.truncate(eff as usize);
        }
        (base.clone(), base, res)
    } else {
        let dir = Path::new(&info.location)
            .parent()
            .unwrap_or(Path::new("/"))
            .to_path_buf();
        let files = if eff != -1 {
            sample_sibling_files(&dir, eff)
                .into_iter()
                .map(|p| p.to_string_lossy().into_owned())
                .collect()
        } else {
            Vec::new()
        };
        (file_url(&dir), dir.to_string_lossy().into_owned(), files)
    };
    let mut lines = vec![
        format!("<skill_content name={}>", go_quote(&info.name)),
        format!("# Skill: {}", info.name),
        String::new(),
        info.content.trim().to_owned(),
        String::new(),
        format!("Base directory for this skill: {base}"),
        "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.".to_owned(),
    ];
    if emit_files {
        let file_lines: Vec<String> = files.iter().map(|f| format!("<file>{f}</file>")).collect();
        lines.push("Note: file list is sampled.".into());
        lines.push(String::new());
        lines.push("<skill_files>".into());
        lines.push(file_lines.join("\n"));
        lines.push("</skill_files>".into());
    }
    lines.push("</skill_content>".into());
    let mut meta = Map::new();
    meta.insert("name".into(), Value::String(info.name.clone()));
    meta.insert("dir".into(), Value::String(meta_dir));
    ToolResult::ok(lines.join("\n")).with_metadata(meta)
}

impl SkillSource {
    /// The markdown catalog for the system prompt (byte-identical across ports).
    pub fn prompt(&self) -> String {
        // BTreeMap: ordered by name in code point order.
        let described: Vec<&SkillInfo> = self
            .skills
            .values()
            .filter(|s| !s.description.is_empty())
            .collect();
        if described.is_empty() {
            return "No skills are currently available.".into();
        }
        let mut lines = vec![
            SKILLS_PROMPT_PREAMBLE.to_owned(),
            String::new(),
            "## Available Skills".to_owned(),
        ];
        for s in described {
            lines.push(format!("- **{}**: {}", s.name, s.description));
        }
        lines.join("\n")
    }
}

/// Go's `%q` for the strings skill names are made of.
fn go_quote(s: &str) -> String {
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 || c == '\u{7f}' => {
                out.push_str(&format!("\\x{:02x}", c as u32))
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// `file://` URL for an absolute directory, escaped exactly like Go's
/// `url.URL{Scheme: "file", Path: p}.String()`.
fn file_url(dir: &Path) -> String {
    let p = dir.to_string_lossy().replace('\\', "/");
    let mut out = String::from("file://");
    if !p.starts_with('/') {
        out.push('/');
    }
    for b in p.bytes() {
        let keep = b.is_ascii_alphanumeric() || b"-_.~$&+,/:;=@".contains(&b);
        if keep {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

#[cfg(test)]
mod unit {
    use super::*;

    #[test]
    fn frontmatter_split_matches_go_regex() {
        assert_eq!(
            split_frontmatter("---\nname: a\n---\nbody"),
            Some(("name: a", "body"))
        );
        assert_eq!(
            split_frontmatter("---\r\nname: a\r\n---\r\nbody"),
            Some(("name: a", "body"))
        );
        assert_eq!(split_frontmatter("no fences"), None);
        // Go's regex needs a newline before the closing fence
        assert_eq!(split_frontmatter("---\n---\nbody"), None);
        assert_eq!(split_frontmatter("---\n\n---\nbody"), Some(("", "body")));
    }

    #[test]
    fn file_url_escapes_like_go() {
        assert_eq!(file_url(Path::new("/a b/c")), "file:///a%20b/c");
        assert_eq!(file_url(Path::new("/x/é")), "file:///x/%C3%A9");
    }
}
