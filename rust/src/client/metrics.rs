//! Observability (SPEC §8): metric events and a Prometheus text registry whose
//! output is byte-identical to the other ports.

use std::collections::BTreeMap;
use std::sync::Mutex;

/// One observability event (`llm` | `tool` | `run`, plus the classifier's
/// `classifier.evaluate` / `classifier.warning`, which the registry ignores).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct MetricEvent {
    pub event: String,
    pub model: String,
    pub status: String,
    pub ms: i64,
    pub prompt_tokens: i64,
    pub completion_tokens: i64,
    pub tool: String,
    pub source: String,
    pub is_error: bool,
    pub pending: bool,
    pub turns: i64,
    pub tool_calls: i64,
    pub total_tokens: i64,
    pub error: String,
    /// The question key a `classifier.warning` names (SPEC §8B).
    pub question: String,
    /// The `classifier.warning` text (never a failure: `error` stays empty).
    pub warning: String,
    /// The number of questions a `classifier.evaluate` asked (L1).
    pub questions: i64,
    /// sha256 hex of the canonical request (model + questions) of a
    /// `classifier.evaluate` — the fixtures' `canonicalSha256` (L1).
    pub questions_hash: String,
    /// A `classifier.evaluate` answered from the classifier cache, with no
    /// request sent.
    pub cached: bool,
}

const BUCKETS: [f64; 10] = [0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0];
const BUCKET_LABELS: [&str; 10] = [
    "0.05", "0.1", "0.25", "0.5", "1", "2.5", "5", "10", "30", "60",
];

#[derive(Default)]
struct Hist {
    counts: [i64; 10],
    sum: f64,
    count: i64,
}

#[derive(Default)]
struct Inner {
    llm_requests: BTreeMap<String, i64>,
    llm_tokens: BTreeMap<String, i64>,
    llm_duration: BTreeMap<String, Hist>,
    tool_calls: BTreeMap<String, i64>,
    tool_duration: BTreeMap<String, Hist>,
    run_errors: BTreeMap<String, i64>,
}

#[derive(Default)]
pub(crate) struct Registry {
    inner: Mutex<Inner>,
}

fn escape(v: &str) -> String {
    v.replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
}

fn labels(pairs: &[(&str, &str)]) -> String {
    pairs
        .iter()
        .map(|(k, v)| format!("{k}=\"{}\"", escape(v)))
        .collect::<Vec<_>>()
        .join(",")
}

fn observe(m: &mut BTreeMap<String, Hist>, key: String, seconds: f64) {
    let h = m.entry(key).or_default();
    h.sum += seconds;
    h.count += 1;
    for (i, b) in BUCKETS.iter().enumerate() {
        if seconds <= *b {
            h.counts[i] += 1;
        }
    }
}

/// JS `String(number)` / Go `FormatFloat(f,'g',-1,64)` for the ranges a sum takes.
fn js_number(f: f64) -> String {
    if f == f.trunc() && f.abs() < 1e21 {
        return format!("{}", f as i64);
    }
    format!("{f}")
}

fn counter(out: &mut Vec<String>, name: &str, help: &str, m: &BTreeMap<String, i64>) {
    out.push(format!("# HELP {name} {help}"));
    out.push(format!("# TYPE {name} counter"));
    for (k, v) in m {
        if k.is_empty() {
            out.push(format!("{name} {v}"));
        } else {
            out.push(format!("{name}{{{k}}} {v}"));
        }
    }
}

fn with_le(base: &str, le: &str) -> String {
    if base.is_empty() {
        format!("le=\"{le}\"")
    } else {
        format!("{base},le=\"{le}\"")
    }
}

fn histogram(out: &mut Vec<String>, name: &str, help: &str, m: &BTreeMap<String, Hist>) {
    out.push(format!("# HELP {name} {help}"));
    out.push(format!("# TYPE {name} histogram"));
    for (k, h) in m {
        for (i, l) in BUCKET_LABELS.iter().enumerate() {
            out.push(format!(
                "{name}_bucket{{{}}} {}",
                with_le(k, l),
                h.counts[i]
            ));
        }
        out.push(format!(
            "{name}_bucket{{{}}} {}",
            with_le(k, "+Inf"),
            h.count
        ));
        if k.is_empty() {
            out.push(format!("{name}_sum {}", js_number(h.sum)));
            out.push(format!("{name}_count {}", h.count));
        } else {
            out.push(format!("{name}_sum{{{k}}} {}", js_number(h.sum)));
            out.push(format!("{name}_count{{{k}}} {}", h.count));
        }
    }
}

impl Registry {
    pub(crate) fn record(&self, ev: &MetricEvent) {
        let mut r = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        match ev.event.as_str() {
            "llm" => {
                *r.llm_requests
                    .entry(labels(&[("model", &ev.model), ("status", &ev.status)]))
                    .or_default() += 1;
                if ev.status == "ok" {
                    *r.llm_tokens
                        .entry(labels(&[("type", "prompt")]))
                        .or_default() += ev.prompt_tokens;
                    *r.llm_tokens
                        .entry(labels(&[("type", "completion")]))
                        .or_default() += ev.completion_tokens;
                }
                observe(
                    &mut r.llm_duration,
                    labels(&[("model", &ev.model)]),
                    ev.ms as f64 / 1000.0,
                );
            }
            "tool" => {
                let key = labels(&[
                    ("tool", &ev.tool),
                    ("source", &ev.source),
                    ("is_error", if ev.is_error { "true" } else { "false" }),
                    ("pending", if ev.pending { "true" } else { "false" }),
                ]);
                *r.tool_calls.entry(key).or_default() += 1;
                observe(
                    &mut r.tool_duration,
                    labels(&[("tool", &ev.tool)]),
                    ev.ms as f64 / 1000.0,
                );
            }
            "run" if !ev.error.is_empty() => {
                *r.run_errors
                    .entry(labels(&[("model", &ev.model)]))
                    .or_default() += 1;
            }
            _ => {}
        }
    }

    pub(crate) fn render(&self) -> String {
        let r = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let mut out = Vec::new();
        counter(
            &mut out,
            "toolnexus_llm_requests_total",
            "Total LLM requests.",
            &r.llm_requests,
        );
        counter(
            &mut out,
            "toolnexus_llm_tokens_total",
            "Total tokens, by type.",
            &r.llm_tokens,
        );
        histogram(
            &mut out,
            "toolnexus_llm_request_duration_seconds",
            "LLM request duration in seconds.",
            &r.llm_duration,
        );
        counter(
            &mut out,
            "toolnexus_tool_calls_total",
            "Total tool calls.",
            &r.tool_calls,
        );
        histogram(
            &mut out,
            "toolnexus_tool_duration_seconds",
            "Tool execution duration in seconds.",
            &r.tool_duration,
        );
        counter(
            &mut out,
            "toolnexus_run_errors_total",
            "Total run errors.",
            &r.run_errors,
        );
        out.join("\n") + "\n"
    }
}
