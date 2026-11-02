//! Built-in tools (SPEC §4A, `source: "builtin"`). STUB — being filled in.

use std::collections::BTreeMap;

use serde_json::Value;

use crate::tool::Tool;

/// The `builtins` option (§4 assembly + §4A host boundary).
#[derive(Debug, Clone, Default)]
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
                c.kill_grace_ms = m.get("killGraceMs").and_then(Value::as_u64).unwrap_or(0);
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
    Ok(Vec::new())
}
