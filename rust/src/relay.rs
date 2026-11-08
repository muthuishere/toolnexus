//! Relay (declaration-only) tools and the durable resume path — SPEC §10,
//! ADR-0010. Port of `golang/relay.go`.
//!
//! A relay tool carries a schema but no host-side behaviour: the model emits a
//! call, the call is surfaced to the host as a §10 suspension of kind
//! `"tool_call"`, the HOST executes it, and the host's output is fed back to the
//! model as that call's tool result. Relay is a *use* of the suspension
//! primitive, not a second mechanism.

use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

use serde_json::{Map, Value, json};

use crate::tool::{Answer, Request, Tool, ToolResult, ToolSource, pending};

/// The reserved `Request.kind` for a relayed tool call. Pinned across ports.
pub const RELAY_KIND: &str = "tool_call";
/// `Request.data` key holding the turn's relayed calls.
const RELAY_CALLS_KEY: &str = "calls";
/// `Answer.data` key holding the host's tool output.
pub const RELAY_OUTPUT_KEY: &str = "output";
/// `Answer.data` key marking the host's tool as failed.
pub const RELAY_IS_ERROR_KEY: &str = "isError";
/// `Answer.data` key holding per-call results (multi-call resume).
const RELAY_RESULTS_KEY: &str = "results";

/// Internal sentinel prompt meaning "continue from the transcript; do NOT
/// append a user turn". Only the answer-carrying resume entry points use it.
pub(crate) const RESUME_NO_PROMPT: &str = "\u{0}toolnexus:resume\u{0}";

/// One relayed call as it appears in `Request.data["calls"]`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RelayCall {
    /// The provider's tool-call id — the correlation key for the tool result.
    pub id: String,
    pub name: String,
    /// The parsed arguments object.
    pub input: Map<String, Value>,
    /// The raw arguments JSON string.
    pub arguments: String,
}

/// One executed result the host hands back on resume.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RelayResult {
    pub id: String,
    pub output: String,
    pub is_error: bool,
}

/// A declaration-only tool: never executed here. When the model calls it the
/// run suspends with a `tool_call` Request carrying the call; the host answers
/// with `Answer.data["output"]` (and `["isError"]` when its tool failed).
/// `schema: None` ⇒ an empty object schema.
pub fn relay_tool(
    name: impl Into<String>,
    description: impl Into<String>,
    schema: Option<Value>,
) -> Tool {
    let name: String = name.into();
    let n = name.clone();
    Tool {
        output_schema: None,
        annotations: None,
        // The host runs a relay call, and every relay call of a turn surfaces in
        // one Request (§10), so relay calls are never serialised here.
        concurrency_safe: Some(Arc::new(|_| true)),
        name,
        description: description.into(),
        input_schema: schema.unwrap_or_else(|| json!({"type": "object", "properties": {}})),
        source: ToolSource::Relay,
        hints: None,
        execute: Arc::new(move |args, ctx| {
            let n = n.clone();
            Box::pin(async move {
                if let Some(a) = &ctx.answer {
                    return relay_result_from_answer(&n, a);
                }
                let args = if args.is_object() { args } else { json!({}) };
                let raw = serde_json::to_string(&args).unwrap_or_else(|_| "{}".into());
                let mut data = Map::new();
                data.insert(
                    RELAY_CALLS_KEY.into(),
                    json!([{"id": "", "name": n, "input": args, "arguments": raw}]),
                );
                pending(Request {
                    kind: RELAY_KIND.into(),
                    prompt: format!("relay tool call {n} to the caller for execution"),
                    data: Some(data),
                    ..Default::default()
                })
            })
        }),
    }
}

fn type_name(v: &Value) -> &'static str {
    match v {
        Value::Null => "null",
        Value::Bool(_) => "bool",
        Value::Number(_) => "number",
        Value::String(_) => "string",
        Value::Array(_) => "array",
        Value::Object(_) => "object",
    }
}

/// The tool_result text for a call resolved by a declined Answer — one spelling
/// for the inline and the durable path (§10, issue #125).
fn relay_declined_output(name: &str, reason: &str) -> String {
    let reason = if reason.is_empty() {
        "declined"
    } else {
        reason
    };
    format!("relay declined for {name}: {reason}")
}

fn relay_result_from_answer(name: &str, a: &Answer) -> ToolResult {
    if !a.ok {
        return ToolResult::error(relay_declined_output(name, &a.reason));
    }
    let data = a.data.clone().unwrap_or_default();
    let Some(raw) = data.get(RELAY_OUTPUT_KEY) else {
        return ToolResult::error(format!(
            "relay answer for {name} carries no \"output\" — build it with AnswerOutput(id, output)"
        ));
    };
    let Some(out) = raw.as_str() else {
        return ToolResult::error(format!(
            "relay answer for {name} has a non-string {RELAY_OUTPUT_KEY:?} ({}) — it must be a string",
            type_name(raw)
        ));
    };
    let is_err = data
        .get(RELAY_IS_ERROR_KEY)
        .and_then(Value::as_bool)
        .unwrap_or(false);
    ToolResult {
        output: out.into(),
        is_error: is_err,
        ..Default::default()
    }
}

/// The Answer for a SINGLE outstanding call: the output under the one key the
/// engine reads.
pub fn answer_output(id: impl Into<String>, output: impl Into<String>) -> Answer {
    let mut data = Map::new();
    data.insert(RELAY_OUTPUT_KEY.into(), Value::String(output.into()));
    Answer {
        id: id.into(),
        ok: true,
        data: Some(data),
        reason: String::new(),
    }
}

/// The Answer for a refused request (empty reason ⇒ `"declined"`).
pub fn answer_declined(id: impl Into<String>, reason: &str) -> Answer {
    Answer {
        id: id.into(),
        ok: false,
        data: None,
        reason: if reason.is_empty() {
            "declined".into()
        } else {
            reason.into()
        },
    }
}

/// The Answer a durable host returns on resume: one result per relayed call.
pub fn relay_answer(request_id: impl Into<String>, results: &[RelayResult]) -> Answer {
    let arr: Vec<Value> = results
        .iter()
        .map(|r| json!({"id": r.id, "output": r.output, "isError": r.is_error}))
        .collect();
    let mut data = Map::new();
    data.insert(RELAY_RESULTS_KEY.into(), Value::Array(arr));
    Answer {
        id: request_id.into(),
        ok: true,
        data: Some(data),
        reason: String::new(),
    }
}

/// Whether a Request is a relayed tool call.
pub fn is_relay_request(req: &Request) -> bool {
    req.kind == RELAY_KIND
}

/// The relayed calls on a Request, in tool-call order (empty when not relay).
pub fn relay_calls_of(req: &Request) -> Vec<RelayCall> {
    if !is_relay_request(req) {
        return Vec::new();
    }
    let Some(arr) = req
        .data
        .as_ref()
        .and_then(|d| d.get(RELAY_CALLS_KEY))
        .and_then(Value::as_array)
    else {
        return Vec::new();
    };
    arr.iter()
        .filter_map(Value::as_object)
        .map(|m| RelayCall {
            id: m.get("id").and_then(Value::as_str).unwrap_or("").into(),
            name: m.get("name").and_then(Value::as_str).unwrap_or("").into(),
            input: m
                .get("input")
                .and_then(Value::as_object)
                .cloned()
                .unwrap_or_default(),
            arguments: m
                .get("arguments")
                .and_then(Value::as_str)
                .unwrap_or("")
                .into(),
        })
        .collect()
}

/// Stamp the provider call id on a single-call relay suspension.
pub(crate) fn stamp_relay_call_id(req: &mut Request, id: &str) {
    if !is_relay_request(req) {
        return;
    }
    let Some(calls) = req
        .data
        .as_mut()
        .and_then(|d| d.get_mut(RELAY_CALLS_KEY))
        .and_then(Value::as_array_mut)
    else {
        return;
    };
    for c in calls.iter_mut().filter_map(Value::as_object_mut) {
        if c.get("id").and_then(Value::as_str).unwrap_or("").is_empty() {
            c.insert("id".into(), Value::String(id.into()));
        }
    }
}

/// Fold every relay suspension of one turn into the FIRST one (ADR-0010 F2-a).
pub(crate) fn merge_relay_calls(halted: &mut [Option<Request>]) {
    let mut first: Option<usize> = None;
    for i in 0..halted.len() {
        let is_relay = halted[i].as_ref().is_some_and(is_relay_request);
        if !is_relay {
            continue;
        }
        let Some(f) = first else {
            first = Some(i);
            continue;
        };
        let more: Vec<Value> = halted[i]
            .as_ref()
            .and_then(|r| r.data.as_ref())
            .and_then(|d| d.get(RELAY_CALLS_KEY))
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        if let Some(target) = halted[f].as_mut() {
            let data = target.data.get_or_insert_with(Map::new);
            let entry = data
                .entry(RELAY_CALLS_KEY.to_string())
                .or_insert_with(|| Value::Array(Vec::new()));
            if let Some(arr) = entry.as_array_mut() {
                arr.extend(more);
            }
        }
    }
}

/// Fails when a relay tool's name collides with a builtin's (ADR-0010 D5),
/// unconditionally — even with builtins off.
pub(crate) fn check_relay_collisions(tools: &[Tool]) -> Result<(), String> {
    let builtins: std::collections::HashSet<String> =
        crate::builtins::create_builtin_tools(&crate::BuiltinsConfig::default())
            .into_iter()
            .map(|t| t.name)
            .collect();
    let mut bad: Vec<String> = tools
        .iter()
        .filter(|t| t.source == ToolSource::Relay && builtins.contains(&t.name))
        .map(|t| t.name.clone())
        .collect();
    if bad.is_empty() {
        return Ok(());
    }
    bad.sort();
    Err(format!(
        "toolnexus: relay tool name collides with a built-in tool: [{}] — rename the relay tool (the guard applies even with builtins disabled, so a future builtin cannot capture a declaration-only tool)",
        bad.join(" ")
    ))
}

type ResultsOf = (HashMap<String, RelayResult>, Option<RelayResult>);

fn relay_results_of(a: &Answer) -> Result<ResultsOf, String> {
    let mut by_id = HashMap::new();
    let Some(data) = &a.data else {
        return Ok((by_id, None));
    };
    if let Some(arr) = data.get(RELAY_RESULTS_KEY).and_then(Value::as_array) {
        for (i, e) in arr.iter().enumerate() {
            let Some(m) = e.as_object() else {
                return Err(format!(
                    "toolnexus: cannot resume — answer data[{RELAY_RESULTS_KEY:?}][{i}] is {}, want an object {{id, output, isError}}",
                    type_name(e)
                ));
            };
            let id = m
                .get("id")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string();
            let out = m.get("output").unwrap_or(&Value::Null);
            let Some(out) = out.as_str() else {
                return Err(format!(
                    "toolnexus: cannot resume — answer data[{RELAY_RESULTS_KEY:?}][{i}].output is {}, want a string",
                    type_name(out)
                ));
            };
            let is_error = m.get("isError").and_then(Value::as_bool).unwrap_or(false);
            by_id.insert(
                id.clone(),
                RelayResult {
                    id,
                    output: out.into(),
                    is_error,
                },
            );
        }
        return Ok((by_id, None));
    }
    if let Some(raw) = data.get(RELAY_OUTPUT_KEY) {
        let Some(out) = raw.as_str() else {
            return Err(format!(
                "toolnexus: cannot resume — answer data[{RELAY_OUTPUT_KEY:?}] is {}, want a string (build it with AnswerOutput(id, output))",
                type_name(raw)
            ));
        };
        let is_error = data
            .get(RELAY_IS_ERROR_KEY)
            .and_then(Value::as_bool)
            .unwrap_or(false);
        return Ok((
            by_id,
            Some(RelayResult {
                id: String::new(),
                output: out.into(),
                is_error,
            }),
        ));
    }
    Ok((by_id, None))
}

/// The last assistant turn that issued tool calls: (index, ids, anthropic?).
fn halted_turn(history: &[Value]) -> Option<(usize, Vec<String>, bool)> {
    for i in (0..history.len()).rev() {
        let m = &history[i];
        if m.get("role").and_then(Value::as_str) != Some("assistant") {
            continue;
        }
        if let Some(tcs) = m.get("tool_calls").and_then(Value::as_array)
            && !tcs.is_empty()
        {
            let ids = tcs
                .iter()
                .map(|t| {
                    t.get("id")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_string()
                })
                .collect();
            return Some((i, ids, false));
        }
        if let Some(blocks) = m.get("content").and_then(Value::as_array) {
            let ids: Vec<String> = blocks
                .iter()
                .filter(|b| b.get("type").and_then(Value::as_str) == Some("tool_use"))
                .map(|b| {
                    b.get("id")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_string()
                })
                .collect();
            if !ids.is_empty() {
                return Some((i, ids, true));
            }
        }
    }
    None
}

/// Rebuild a durably-halted transcript so EVERY tool call of the halted turn
/// has a tool result (ADR-0010 D4): placeholders are REPLACED, missing slots
/// filled with an explicit error result.
pub(crate) fn repair_halted_turn(
    history: &[Value],
    pending_req: &Request,
    answer: &Answer,
) -> Result<Vec<Value>, String> {
    let Some((idx, ids, anthropic)) = halted_turn(history) else {
        return Err("toolnexus: cannot resume — the transcript has no halted assistant turn with tool calls".into());
    };
    let (by_id, single) = relay_results_of(answer)?;
    if answer.ok && by_id.is_empty() && single.is_none() {
        let keys: BTreeMap<&String, ()> = answer
            .data
            .iter()
            .flat_map(|d| d.keys())
            .map(|k| (k, ()))
            .collect();
        let keys: Vec<&str> = keys.keys().map(|k| k.as_str()).collect();
        return Err(format!(
            "toolnexus: cannot resume — answer.Ok is true but its data carries no result the engine can read (expected data[{RELAY_OUTPUT_KEY:?}] as a string, or data[{RELAY_RESULTS_KEY:?}] as an array of {{id, output, isError}}); build it with AnswerOutput(id, output) or RelayAnswer(id, results). Keys seen: [{}]",
            keys.join(" ")
        ));
    }
    let names: HashMap<String, String> = relay_calls_of(pending_req)
        .into_iter()
        .map(|c| (c.id, c.name))
        .collect();
    let result_for = |i: usize, id: &str| -> RelayResult {
        // A declined answer (ok:false) resolves EVERY outstanding call with the
        // host's reason, the same text as the inline relay path (issue #125).
        if !answer.ok {
            let name = names
                .get(id)
                .filter(|n| !n.is_empty())
                .cloned()
                .unwrap_or_else(|| id.to_string());
            return RelayResult {
                id: id.into(),
                output: relay_declined_output(&name, &answer.reason),
                is_error: true,
            };
        }
        if let Some(r) = by_id.get(id) {
            return r.clone();
        }
        if let Some(s) = &single
            && i == 0
        {
            let mut r = s.clone();
            r.id = id.into();
            return r;
        }
        let name = names
            .get(id)
            .filter(|n| !n.is_empty())
            .cloned()
            .unwrap_or_else(|| id.to_string());
        RelayResult {
            id: id.into(),
            output: format!("no result supplied on resume for {name}"),
            is_error: true,
        }
    };
    let mut out: Vec<Value> = history[..=idx].to_vec();
    if anthropic {
        let blocks: Vec<Value> = ids
            .iter()
            .enumerate()
            .map(|(i, id)| {
                let r = result_for(i, id);
                json!({"type": "tool_result", "tool_use_id": id, "content": r.output, "is_error": r.is_error})
            })
            .collect();
        out.push(json!({"role": "user", "content": blocks}));
        return Ok(out);
    }
    for (i, id) in ids.iter().enumerate() {
        let r = result_for(i, id);
        out.push(json!({"role": "tool", "tool_call_id": id, "content": r.output}));
    }
    Ok(out)
}
