//! Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as [`Tool`](crate::Tool)
//! is the contract for an ACTION. A System One model takes a state plus
//! pre-declared, typed questions and returns calibrated answers with no free
//! text. It has no messages, no tool calling and no streaming, so it never
//! enters the client loop.
//!
//! A classifier INTERPRETS; it never AUTHORISES. Schema validity is not
//! correctness: a decision can be confidently wrong. Numeric limits,
//! permission checks and allowlists stay in code. Nothing here is a security
//! control. Ported from `golang/classifier.go`.

use std::collections::{BTreeMap, BTreeSet};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use crate::client::{
    BodyTransformFn, BoxFuture, Client, ErrorInfo, ErrorTier, MetricEvent, OnErrorFn, OnMetricFn,
    redact_provider_body, retry_after_seconds,
};

mod backends;
pub use backends::*;

// ---------------------------------------------------------------- constants

/// The System One endpoint base.
pub const DEFAULT_CLASSIFIER_BASE_URL: &str = "https://api.typesafe.ai/v1";
/// The floating alias. Pin it once thresholds are tuned.
pub const DEFAULT_CLASSIFIER_MODEL: &str = "jev-latest";
/// The NAME of the env var holding the credential.
pub const DEFAULT_CLASSIFIER_API_KEY_ENV: &str = "TYPESAFE_API_KEY";
/// Bounds one request in ms (a classifier has no loop to bound).
pub const DEFAULT_CLASSIFIER_TIMEOUT_MS: u64 = 10_000;
/// The OpenRouter pairing. The model id differs from TypeSafe's own spelling.
pub const OPENROUTER_CLASSIFIER_BASE_URL: &str = "https://openrouter.ai/api/v1";
pub const OPENROUTER_CLASSIFIER_MODEL: &str = "typesafe/jev-1.14";
pub const OPENROUTER_CLASSIFIER_API_KEY_ENV: &str = "OPENROUTER_API_KEY";
/// Client-side cap on a choice's named options.
pub const MAX_CHOICE_OPTIONS: usize = 255;
/// Bounds of a score rubric.
pub const MIN_SCORE_LEVELS: usize = 2;
pub const MAX_SCORE_LEVELS: usize = 10;
/// ABSOLUTE tolerance on max|p - 1/n|, compared INCLUSIVELY.
pub const NEAR_UNIFORM_TOLERANCE: f64 = 0.05;
/// MetricEvent `event` values this module emits into the §8 `on_metric` sink.
pub const METRIC_CLASSIFIER_EVALUATE: &str = "classifier.evaluate";
pub const METRIC_CLASSIFIER_WARNING: &str = "classifier.warning";
/// How many states `evaluate_batch` has in flight at once.
pub const DEFAULT_CLASSIFIER_BATCH_CONCURRENCY: usize = 16;

/// A classifier failure. The text is the shared cross-port message. When the
/// failure is a backend-table gap, [`ClassifierError::unsupported`] returns the
/// typed [`ClassifierUnsupported`].
#[derive(Debug, Clone, PartialEq, thiserror::Error)]
#[error("{0}")]
pub struct ClassifierError(pub String, Option<ClassifierUnsupported>);

impl ClassifierError {
    /// A plain failure with this message.
    pub fn new(s: impl Into<String>) -> Self {
        ClassifierError(s.into(), None)
    }

    /// The typed error when this failure is [`ClassifierUnsupported`].
    pub fn unsupported(&self) -> Option<&ClassifierUnsupported> {
        self.1.as_ref()
    }
}

impl From<ClassifierUnsupported> for ClassifierError {
    fn from(u: ClassifierUnsupported) -> Self {
        ClassifierError(u.to_string(), Some(u))
    }
}

fn err(s: impl Into<String>) -> ClassifierError {
    ClassifierError::new(s)
}

/// Selects the backend (§8B Backends).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum ClassifierStyle {
    /// POST the canonical body to `{base_url}/systemone`.
    #[default]
    SystemOne,
    /// One structured-output call on a §8 [`Client`]; reports `calibrated: false`.
    ///
    /// Deprecated: System One is the single product wire (ADR 0038 D3). `Llm`
    /// keeps working byte-identically as an alias for at least one minor
    /// release; there is no runtime warning.
    #[deprecated(note = "System One is the single product wire (ADR 0038 D3); kept as an alias")]
    Llm,
    /// The host's own `evaluate`.
    Custom,
    /// A recorded corpus (`decisions`) — what CI runs: no network, no credential.
    Static,
}

#[allow(deprecated)]
impl ClassifierStyle {
    /// Parse the wire spelling (`systemone` | `llm` | `custom` | `static`).
    pub fn parse(s: &str) -> Result<Self, ClassifierError> {
        match s {
            "" | "systemone" => Ok(Self::SystemOne),
            "llm" => Ok(Self::Llm),
            "custom" => Ok(Self::Custom),
            "static" => Ok(Self::Static),
            _ => Err(err(format!("classifier: unknown style {s:?}"))),
        }
    }
}

/// Whose endpoint answers: sets base URL + model + key env AS A UNIT. The
/// table is [`CLASSIFIER_BACKENDS`]; `Custom` is a self-hosted System One
/// endpoint and requires `base_url` + `supports`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum ClassifierBackend {
    #[default]
    TypeSafe,
    OpenRouter,
    Cloudflare,
    Vercel,
    Custom,
}

impl ClassifierBackend {
    /// The wire spelling.
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::TypeSafe => "typesafe",
            Self::OpenRouter => "openrouter",
            Self::Cloudflare => "cloudflare",
            Self::Vercel => "vercel",
            Self::Custom => "custom",
        }
    }

    /// Parse the wire spelling (`""` ⇒ `typesafe`); an unknown name lists all five.
    pub fn parse(s: &str) -> Result<Self, ClassifierError> {
        match s {
            "" | "typesafe" => Ok(Self::TypeSafe),
            "openrouter" => Ok(Self::OpenRouter),
            "cloudflare" => Ok(Self::Cloudflare),
            "vercel" => Ok(Self::Vercel),
            "custom" => Ok(Self::Custom),
            _ => Err(err(format!(
                "classifier: unknown backend {s:?} (want \"typesafe\", \"openrouter\", \"cloudflare\", \"vercel\" or \"custom\")"
            ))),
        }
    }

    /// This backend's row of [`CLASSIFIER_BACKENDS`].
    pub fn preset(&self) -> &'static ClassifierBackendPreset {
        match self {
            Self::TypeSafe => &CLASSIFIER_BACKENDS[0],
            Self::OpenRouter => &CLASSIFIER_BACKENDS[1],
            Self::Cloudflare => &CLASSIFIER_BACKENDS[2],
            Self::Vercel => &CLASSIFIER_BACKENDS[3],
            Self::Custom => &CLASSIFIER_BACKENDS[4],
        }
    }
}

// ---------------------------------------------------------------- questions

/// Labels the true and false cases of a noul question. `None` criteria is
/// ABSENT on the wire; `Some(default)` is `{"false":"","true":""}`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct NoulCriteria {
    pub when_true: String,
    pub when_false: String,
}

/// The probability that a statement holds (0..1).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct NoulQuestion {
    pub instructions: String,
    pub criteria: Option<NoulCriteria>,
}

/// One option from a named set (1..255). `criteria[id]` must say what picking
/// it would MEAN — the encoding obligation is the caller's (ADR 0021).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ChoiceQuestion {
    pub instructions: String,
    pub criteria: BTreeMap<String, String>,
}

/// A rating against an ORDERED rubric of 2..10 levels (never sorted).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ScoreQuestion {
    pub instructions: String,
    pub criteria: Vec<String>,
}

/// One pre-declared question. `Raw` is the OPEN end of the union (§8B): a
/// System One type the library does not model yet, sent as written.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Question {
    Noul(NoulQuestion),
    Choice(ChoiceQuestion),
    Score(ScoreQuestion),
    Raw(RawQuestion),
}

impl From<NoulQuestion> for Question {
    fn from(q: NoulQuestion) -> Self {
        Question::Noul(q)
    }
}
impl From<ChoiceQuestion> for Question {
    fn from(q: ChoiceQuestion) -> Self {
        Question::Choice(q)
    }
}
impl From<ScoreQuestion> for Question {
    fn from(q: ScoreQuestion) -> Self {
        Question::Score(q)
    }
}
impl From<RawQuestion> for Question {
    fn from(q: RawQuestion) -> Self {
        Question::Raw(q)
    }
}

/// Questions keyed by the CALLER's keys (never transmitted as content).
pub type Questions = BTreeMap<String, Question>;

impl Question {
    /// The §8B wire form of this question.
    pub fn wire(&self) -> Value {
        match self {
            Question::Noul(q) => {
                let mut m = Map::new();
                m.insert("type".into(), json!("noul"));
                m.insert("instructions".into(), json!(q.instructions));
                if let Some(c) = &q.criteria {
                    m.insert(
                        "criteria".into(),
                        json!({"true": c.when_true, "false": c.when_false}),
                    );
                }
                Value::Object(m)
            }
            Question::Choice(q) => {
                json!({"type": "choice", "instructions": q.instructions, "criteria": q.criteria})
            }
            Question::Score(q) => {
                json!({"type": "score", "instructions": q.instructions, "criteria": q.criteria})
            }
            Question::Raw(q) => q.wire(),
        }
    }

    /// The wire `type` of this question.
    pub fn question_type(&self) -> &str {
        match self {
            Question::Noul(_) => "noul",
            Question::Choice(_) => "choice",
            Question::Score(_) => "score",
            Question::Raw(q) => &q.kind,
        }
    }

    /// Parse a §8B wire question (`{type, instructions, criteria?}`).
    pub fn from_wire(v: &Value) -> Result<Question, ClassifierError> {
        let instructions = v
            .get("instructions")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        let crit = v.get("criteria");
        match v.get("type").and_then(Value::as_str).unwrap_or("") {
            "noul" => {
                let criteria = match crit {
                    Some(Value::Object(c)) => Some(NoulCriteria {
                        when_true: c.get("true").and_then(Value::as_str).unwrap_or("").into(),
                        when_false: c.get("false").and_then(Value::as_str).unwrap_or("").into(),
                    }),
                    _ => None,
                };
                Ok(NoulQuestion {
                    instructions,
                    criteria,
                }
                .into())
            }
            "choice" => {
                let criteria = crit
                    .and_then(Value::as_object)
                    .map(|m| {
                        m.iter()
                            .map(|(k, v)| (k.clone(), v.as_str().unwrap_or("").to_owned()))
                            .collect()
                    })
                    .unwrap_or_default();
                Ok(ChoiceQuestion {
                    instructions,
                    criteria,
                }
                .into())
            }
            "score" => {
                let criteria = crit
                    .and_then(Value::as_array)
                    .map(|a| {
                        a.iter()
                            .map(|v| v.as_str().unwrap_or("").to_owned())
                            .collect()
                    })
                    .unwrap_or_default();
                Ok(ScoreQuestion {
                    instructions,
                    criteria,
                }
                .into())
            }
            "" => Err(err("classifier: unknown question type \"\"")),
            t => Ok(RawQuestion {
                kind: t.into(),
                instructions,
                criteria: crit.cloned(),
            }
            .into()),
        }
    }

    fn validate(&self, key: &str) -> Result<(), ClassifierError> {
        match self {
            Question::Noul(_) => Ok(()),
            Question::Choice(q) => {
                let n = q.criteria.len();
                if !(1..=MAX_CHOICE_OPTIONS).contains(&n) {
                    return Err(err(format!(
                        "classifier: question {key:?}: a choice needs 1..{MAX_CHOICE_OPTIONS} options, got {n}"
                    )));
                }
                Ok(())
            }
            Question::Score(q) => {
                let n = q.criteria.len();
                if !(MIN_SCORE_LEVELS..=MAX_SCORE_LEVELS).contains(&n) {
                    return Err(err(format!(
                        "classifier: question {key:?}: a score needs {MIN_SCORE_LEVELS}..{MAX_SCORE_LEVELS} ordered levels, got {n}"
                    )));
                }
                Ok(())
            }
            Question::Raw(q) => q.validate(key),
        }
    }
}

/// Build a choice from any (name, description) pairs — tools, skills, agents.
pub fn choice_over<K: Into<String>, V: Into<String>>(
    instructions: &str,
    items: impl IntoIterator<Item = (K, V)>,
) -> ChoiceQuestion {
    ChoiceQuestion {
        instructions: instructions.into(),
        criteria: items
            .into_iter()
            .map(|(k, v)| (k.into(), v.into()))
            .collect(),
    }
}

// ---------------------------------------------------------------- the wire

fn wire_questions(questions: &Questions) -> Value {
    Value::Object(
        questions
            .iter()
            .map(|(k, q)| (k.clone(), q.wire()))
            .collect(),
    )
}

/// The bytes the byte-identity claim covers: `model` + `questions`, keys
/// sorted recursively, arrays never reordered, compact, `<>&'"` and non-ASCII
/// raw. `state` is deliberately NOT here (numbers do not canonicalise).
pub fn canonical_request(model: &str, questions: &Questions) -> String {
    canonical_json(&json!({"model": model, "questions": wire_questions(questions)}))
}

/// Canonical JSON in Go `encoding/json` form (SetEscapeHTML(false)): keys
/// sorted bytewise, compact, Go string escaping and Go float formatting.
pub fn canonical_json(v: &Value) -> String {
    let mut out = String::new();
    write_canonical(v, &mut out);
    out
}

fn write_canonical(v: &Value, out: &mut String) {
    match v {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Number(n) => out.push_str(&go_number(n)),
        Value::String(s) => write_go_string(s, out),
        Value::Array(a) => {
            out.push('[');
            for (i, e) in a.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write_canonical(e, out);
            }
            out.push(']');
        }
        Value::Object(m) => {
            let keys: BTreeSet<&String> = m.keys().collect();
            out.push('{');
            for (i, k) in keys.into_iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write_go_string(k, out);
                out.push(':');
                write_canonical(&m[k], out);
            }
            out.push('}');
        }
    }
}

fn write_go_string(s: &str, out: &mut String) {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{2028}' => out.push_str("\\u2028"),
            '\u{2029}' => out.push_str("\\u2029"),
            c if (c as u32) < 0x20 => {
                let b = c as u32 as usize;
                out.push_str("\\u00");
                out.push(HEX[b >> 4] as char);
                out.push(HEX[b & 0xf] as char);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Go `encoding/json` float64 formatting (every JSON number is a float64 in Go).
fn go_number(n: &serde_json::Number) -> String {
    let Some(f) = n.as_f64() else {
        return n.to_string();
    };
    if !f.is_finite() {
        return n.to_string();
    }
    let abs = f.abs();
    if abs != 0.0 && !(1e-6..1e21).contains(&abs) {
        let s = format!("{f:e}");
        // Rust: 1e21 / 1.5e-7 ; Go: 1e+21 / 1.5e-07 cleaned to 1.5e-7.
        return match s.split_once('e') {
            Some((m, e)) if !e.starts_with('-') => format!("{m}e+{e}"),
            _ => s,
        };
    }
    format!("{f}")
}

// ---------------------------------------------------------------- answers

/// The probability that a statement holds. No confidence: the number is the answer.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct NoulAnswer {
    #[serde(default)]
    pub noul: f64,
}

/// One option from the offered set, with a probability for every offered option.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ChoiceAnswer {
    #[serde(default)]
    pub choice: String,
    #[serde(default)]
    pub probabilities: BTreeMap<String, f64>,
    #[serde(default)]
    pub confidence: f64,
    /// DERIVED from `probabilities` on decode, never read from the wire.
    /// ADVISORY, not a correctness signal.
    #[serde(skip)]
    pub near_uniform: bool,
}

/// A rating against the ordered rubric; may fall between levels.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ScoreAnswer {
    #[serde(default)]
    pub score: f64,
    #[serde(default)]
    pub legend: BTreeMap<String, String>,
    #[serde(default)]
    pub probabilities: BTreeMap<String, f64>,
    #[serde(default)]
    pub confidence: f64,
}

impl ScoreAnswer {
    /// The legend in level order (numeric: "2" < "10").
    pub fn levels(&self) -> Vec<String> {
        let mut keys: Vec<&String> = self.legend.keys().collect();
        keys.sort_by(|a, b| a.len().cmp(&b.len()).then_with(|| a.cmp(b)));
        keys.into_iter().map(|k| self.legend[k].clone()).collect()
    }
}

/// One typed answer, discriminated by the wire's `type`. An answer of a type
/// the library does not model decodes to `Raw` instead of failing the decision.
#[derive(Debug, Clone, PartialEq)]
pub enum DecisionAnswer {
    Noul(NoulAnswer),
    Choice(ChoiceAnswer),
    Score(ScoreAnswer),
    Raw(RawAnswer),
}

impl DecisionAnswer {
    /// The wire discriminator: `noul` | `choice` | `score` | a raw answer's type.
    pub fn answer_type(&self) -> &str {
        match self {
            DecisionAnswer::Noul(_) => "noul",
            DecisionAnswer::Choice(_) => "choice",
            DecisionAnswer::Score(_) => "score",
            DecisionAnswer::Raw(a) => &a.kind,
        }
    }

    /// The answer in wire shape (its fields plus `type`).
    pub fn wire(&self) -> Map<String, Value> {
        let v = match self {
            DecisionAnswer::Noul(a) => serde_json::to_value(a),
            DecisionAnswer::Choice(a) => serde_json::to_value(a),
            DecisionAnswer::Score(a) => serde_json::to_value(a),
            DecisionAnswer::Raw(a) => return a.value.clone(),
        };
        let mut m = match v {
            Ok(Value::Object(m)) => m,
            _ => Map::new(),
        };
        m.insert("type".into(), json!(self.answer_type()));
        m
    }

    fn decode(key: &str, v: &Value) -> Result<DecisionAnswer, ClassifierError> {
        let wrap = |e: serde_json::Error| err(format!("classifier: answer {key:?}: {e}"));
        let t = match v.get("type") {
            None | Some(Value::Null) => "",
            Some(Value::String(s)) => s.as_str(),
            Some(_) => {
                return Err(err(format!(
                    "classifier: answer {key:?}: type is not a string"
                )));
            }
        };
        Ok(match t {
            "noul" => DecisionAnswer::Noul(NoulAnswer::deserialize(v).map_err(wrap)?),
            "choice" => {
                let mut a = ChoiceAnswer::deserialize(v).map_err(wrap)?;
                a.near_uniform = near_uniform(&a.probabilities);
                DecisionAnswer::Choice(a)
            }
            "score" => DecisionAnswer::Score(ScoreAnswer::deserialize(v).map_err(wrap)?),
            // The union is OPEN (§8B): a type the library does not model (e.g.
            // Jev 1.14 "box") decodes to a raw answer instead of failing.
            other => match v {
                Value::Object(m) => DecisionAnswer::Raw(RawAnswer {
                    kind: other.into(),
                    value: m.clone(),
                }),
                _ => {
                    return Err(err(format!(
                        "classifier: answer {key:?}: not a JSON object"
                    )));
                }
            },
        })
    }
}

/// `max_i |p_i - 1/n| <= 0.05` over the map AS RETURNED; n == 1 is uniform,
/// an empty map is not a distribution (false).
pub fn near_uniform(probabilities: &BTreeMap<String, f64>) -> bool {
    let n = probabilities.len();
    match n {
        0 => false,
        1 => true,
        _ => {
            let target = 1.0 / n as f64;
            probabilities
                .values()
                .all(|p| (p - target).abs() <= NEAR_UNIFORM_TOLERANCE)
        }
    }
}

/// The wire's usage block. `cost: None` means the backend did not say — NOT zero.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ClassifierUsage {
    #[serde(default)]
    pub input_tokens: i64,
    #[serde(default)]
    pub output_tokens: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cost: Option<f64>,
}

/// One answer per question, keyed by the caller's keys.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Decision {
    /// What actually answered (may be more specific than what was asked for).
    pub model: String,
    pub answers: BTreeMap<String, DecisionAnswer>,
    pub usage: ClassifierUsage,
    /// Calibrated probabilities? A threshold tuned on one backend does not transfer.
    pub calibrated: bool,
    /// True when the decision came from the classifier cache and no request was
    /// sent. Never on the wire.
    pub cached: bool,
}

impl Decision {
    /// Decode a backend response (absent/null `calibrated` ⇒ true).
    pub fn from_slice(b: &[u8]) -> Result<Decision, ClassifierError> {
        let v: Value = serde_json::from_slice(b).map_err(|e| err(e.to_string()))?;
        Decision::from_value(&v)
    }

    pub fn from_value(v: &Value) -> Result<Decision, ClassifierError> {
        let Some(m) = v.as_object() else {
            return Err(err("classifier: response is not a JSON object"));
        };
        let model = match m.get("model") {
            None | Some(Value::Null) => String::new(),
            Some(Value::String(s)) => s.clone(),
            Some(_) => return Err(err("classifier: model is not a string")),
        };
        let usage = match m.get("usage") {
            None | Some(Value::Null) => ClassifierUsage::default(),
            Some(u) => ClassifierUsage::deserialize(u).map_err(|e| err(e.to_string()))?,
        };
        let calibrated = match m.get("calibrated") {
            None | Some(Value::Null) => true,
            Some(Value::Bool(b)) => *b,
            Some(_) => return Err(err("classifier: calibrated is not a bool")),
        };
        let mut answers = BTreeMap::new();
        match m.get("answers") {
            None | Some(Value::Null) => {}
            Some(Value::Object(a)) => {
                for (k, r) in a {
                    answers.insert(k.clone(), DecisionAnswer::decode(k, r)?);
                }
            }
            Some(_) => return Err(err("classifier: answers is not an object")),
        }
        Ok(Decision {
            model,
            answers,
            usage,
            calibrated,
            cached: false,
        })
    }

    /// Back to wire shape (what a tape stores).
    pub fn to_value(&self) -> Value {
        let answers: Map<String, Value> = self
            .answers
            .iter()
            .map(|(k, a)| (k.clone(), Value::Object(a.wire())))
            .collect();
        json!({"model": self.model, "answers": answers, "usage": self.usage, "calibrated": self.calibrated})
    }

    fn type_err(&self, key: &str, want: &str) -> ClassifierError {
        match self.answers.get(key) {
            Some(a) => err(format!(
                "classifier: answer {key:?} is a {} answer, not {want}",
                a.answer_type()
            )),
            None => err(format!("classifier: no answer {key:?} in this decision")),
        }
    }

    /// Typed accessors: a wrong-type read is an error, never a panic.
    pub fn noul(&self, key: &str) -> Result<&NoulAnswer, ClassifierError> {
        match self.answers.get(key) {
            Some(DecisionAnswer::Noul(a)) => Ok(a),
            _ => Err(self.type_err(key, "noul")),
        }
    }
    pub fn choice(&self, key: &str) -> Result<&ChoiceAnswer, ClassifierError> {
        match self.answers.get(key) {
            Some(DecisionAnswer::Choice(a)) => Ok(a),
            _ => Err(self.type_err(key, "choice")),
        }
    }
    pub fn score(&self, key: &str) -> Result<&ScoreAnswer, ClassifierError> {
        match self.answers.get(key) {
            Some(DecisionAnswer::Score(a)) => Ok(a),
            _ => Err(self.type_err(key, "score")),
        }
    }
}

// ---------------------------------------------------------------- options

/// One entry of the `static` corpus, keyed on the canonical request AND the state.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RecordedDecision {
    pub state: Value,
    pub questions: Questions,
    /// The recorded backend response body, verbatim.
    pub response: Vec<u8>,
}

/// What a call carries besides state and questions: cancellation, and the
/// call name a [`Tape`](crate::judge::Tape) records under.
#[derive(Debug, Clone, Default)]
pub struct EvalContext {
    pub cancel: CancellationToken,
    pub call_name: String,
}

/// Name the next evaluation for a tape (`"triage"`, `"plan"`).
pub fn with_call_name(name: &str) -> EvalContext {
    EvalContext {
        call_name: name.into(),
        ..Default::default()
    }
}

/// The host's own evaluation (`style: Custom`).
pub type EvaluateFn =
    Arc<dyn Fn(EvalContext, Value, Questions) -> BoxFuture<Result<Decision, String>> + Send + Sync>;

/// Classifier options (§8B); mirrors §8 `ClientOptions` wherever it makes sense.
#[derive(Clone, Default)]
pub struct ClassifierOptions {
    pub style: ClassifierStyle,
    /// Sets base URL + model + key env as a unit; explicit fields still win.
    pub backend: ClassifierBackend,
    /// "" ⇒ the backend's base URL.
    pub base_url: String,
    /// "" ⇒ the backend's model.
    pub model: String,
    /// NAME of the env var holding the key (read at call time, never logged).
    pub api_key_env: String,
    /// Extra headers; values expand `${ENV_VAR}` at call time, never logged.
    pub headers: BTreeMap<String, String>,
    /// Bounds ONE request. 0 ⇒ 10 000 ms.
    pub timeout_ms: u64,
    pub http_client: Option<reqwest::Client>,
    /// 0 ⇒ 2; -1 ⇒ none.
    pub retries: i32,
    /// Backoff base (base * 2^attempt, no jitter). 0 ⇒ 500.
    pub retry_base_ms: u64,
    /// ADDED to 408/429/500/502/503/504/529.
    pub retryable_statuses: Vec<u16>,
    pub on_error: Option<OnErrorFn>,
    /// Shallow-merged into the body; a key here WINS.
    pub request_params: Map<String, Value>,
    pub body_transform: Option<BodyTransformFn>,
    pub on_metric: Option<OnMetricFn>,
    /// `style: Llm` only.
    pub client: Option<Client>,
    /// `style: Custom` only.
    pub evaluate: Option<EvaluateFn>,
    /// `style: Static` only.
    pub decisions: Vec<RecordedDecision>,
    /// Overrides the backend table's support row for this classifier.
    /// Required with [`ClassifierBackend::Custom`].
    pub supports: Option<ClassifierSupports>,
    /// Opt-in answer cache (§8B). `None` = no caching. Consulted by
    /// `style: SystemOne` only; stores only replies that pass
    /// [`validate_decision`].
    pub cache: Option<Arc<dyn ClassifierCache>>,
}

impl std::fmt::Debug for ClassifierOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ClassifierOptions")
            .field("style", &self.style)
            .field("base_url", &self.base_url)
            .field("backend", &self.backend)
            .field("model", &self.model)
            .field("api_key_env", &self.api_key_env)
            .field("timeout_ms", &self.timeout_ms)
            .finish_non_exhaustive()
    }
}

// ---------------------------------------------------------------- classifier

struct Inner {
    opts: ClassifierOptions,
    preset: &'static ClassifierBackendPreset,
    supports: ClassifierSupports,
    statics: BTreeMap<String, Vec<u8>>,
    warned: Mutex<BTreeSet<String>>,
}

/// The whole seam: one verb. Cheap to clone.
#[derive(Clone)]
pub struct Classifier {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for Classifier {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.inner.opts.fmt(f)
    }
}

/// Build a classifier, applying the §8B defaults and rejecting a style whose
/// required option is missing before any call is made.
pub fn create_classifier(mut opts: ClassifierOptions) -> Result<Classifier, ClassifierError> {
    let preset = opts.backend.preset();
    if opts.backend == ClassifierBackend::Custom
        && opts.style == ClassifierStyle::SystemOne
        && (opts.base_url.is_empty() || opts.supports.is_none())
    {
        return Err(err(
            "classifier: backend \"custom\" requires baseUrl and supports",
        ));
    }
    if opts.base_url.is_empty() {
        opts.base_url = preset.base_url.into();
    }
    if opts.model.is_empty() {
        opts.model = preset.model.into();
    }
    if opts.api_key_env.is_empty() {
        opts.api_key_env = preset.api_key_env.into();
    }
    let supports = match (opts.supports, preset.supports) {
        (Some(s), _) => s,
        (None, Some(f)) => f(&opts.model),
        (None, None) => ClassifierSupports::default(),
    };
    check_pairing(&opts.base_url, &opts.model)?;
    if opts.timeout_ms == 0 {
        opts.timeout_ms = DEFAULT_CLASSIFIER_TIMEOUT_MS;
    }
    let mut statics = BTreeMap::new();
    match opts.style {
        ClassifierStyle::SystemOne => {}
        #[allow(deprecated)]
        ClassifierStyle::Llm => {
            if opts.client.is_none() {
                return Err(err("classifier: style \"llm\" requires Client"));
            }
        }
        ClassifierStyle::Custom => {
            if opts.evaluate.is_none() {
                return Err(err("classifier: style \"custom\" requires Evaluate"));
            }
        }
        ClassifierStyle::Static => {
            for rec in &opts.decisions {
                statics.insert(
                    static_key(&opts.model, &rec.state, &rec.questions),
                    rec.response.clone(),
                );
            }
        }
    }
    Ok(Classifier {
        inner: Arc::new(Inner {
            opts,
            preset,
            supports,
            statics,
            warned: Mutex::new(BTreeSet::new()),
        }),
    })
}

fn check_pairing(base_url: &str, model: &str) -> Result<(), ClassifierError> {
    if base_url.contains("openrouter.ai") && model.starts_with("jev-") {
        return Err(err(format!(
            "model {model:?} is TypeSafe's spelling; on openrouter.ai use {OPENROUTER_CLASSIFIER_MODEL:?}"
        )));
    }
    Ok(())
}

fn static_key(model: &str, state: &Value, questions: &Questions) -> String {
    format!(
        "{}\u{0}{}",
        canonical_request(model, questions),
        canonical_json(state)
    )
}

impl Classifier {
    /// The resolved options (defaults applied).
    pub fn options(&self) -> &ClassifierOptions {
        &self.inner.opts
    }

    /// A state plus typed questions in, a [`Decision`] out. Questions are
    /// INDEPENDENT — one answer is never context for another.
    pub async fn evaluate(
        &self,
        state: Value,
        questions: &Questions,
    ) -> Result<Decision, ClassifierError> {
        self.evaluate_ctx(EvalContext::default(), state, questions)
            .await
    }

    /// [`evaluate`](Self::evaluate) with cancellation and a call name.
    pub async fn evaluate_ctx(
        &self,
        ctx: EvalContext,
        state: Value,
        questions: &Questions,
    ) -> Result<Decision, ClassifierError> {
        self.evaluate_inner(ctx, state, questions, false).await
    }

    /// The one path. `validate` = fail closed on [`validate_decision`] (the
    /// [`state`](Self::state) builders); plain `evaluate` keeps its old behaviour.
    pub(crate) async fn evaluate_inner(
        &self,
        ctx: EvalContext,
        state: Value,
        questions: &Questions,
        validate: bool,
    ) -> Result<Decision, ClassifierError> {
        let start = Instant::now();
        if questions.is_empty() {
            return Err(err("classifier: no questions to evaluate"));
        }
        // L1: count + hash of the canonical request on every evaluate event.
        let o = &self.inner.opts;
        let hash = sha256_hex(canonical_request(&o.model, questions).as_bytes());
        let count = questions.len() as i64;
        for (key, q) in questions {
            if let Err(e) = q.validate(key) {
                self.emit_error(start, &e, count, &hash);
                return Err(e);
            }
        }
        // Detection, never repair: the request goes out byte-unchanged.
        self.report_degenerate(questions);
        #[allow(deprecated)]
        let res = match o.style {
            ClassifierStyle::Custom => match &o.evaluate {
                Some(f) => f(ctx, state, questions.clone()).await.map_err(err),
                None => Err(err("classifier: style \"custom\" requires Evaluate")),
            },
            ClassifierStyle::Static => self.evaluate_static(&state, questions),
            ClassifierStyle::Llm => self.evaluate_llm(&ctx, &state, questions).await,
            ClassifierStyle::SystemOne => {
                self.evaluate_wire(&ctx, &state, questions, validate).await
            }
        };
        let res = match res {
            Ok(d) if validate && o.style != ClassifierStyle::SystemOne => {
                validate_decision(questions, &d).map(|()| d)
            }
            r => r,
        };
        match res {
            Err(e) => {
                self.emit_error(start, &e, count, &hash);
                Err(e)
            }
            Ok(d) => {
                self.emit(MetricEvent {
                    event: METRIC_CLASSIFIER_EVALUATE.into(),
                    model: d.model.clone(),
                    status: "ok".into(),
                    ms: start.elapsed().as_millis() as i64,
                    prompt_tokens: d.usage.input_tokens,
                    completion_tokens: d.usage.output_tokens,
                    questions: count,
                    questions_hash: hash,
                    cached: d.cached,
                    ..Default::default()
                });
                Ok(d)
            }
        }
    }

    /// The SAME questions over MANY states, one decision per state in the SAME
    /// order, at most 16 in flight. Fails closed: the lowest-index error wins
    /// and names its state.
    pub async fn evaluate_batch(
        &self,
        states: Vec<Value>,
        questions: &Questions,
    ) -> Result<Vec<Decision>, ClassifierError> {
        use futures_util::StreamExt;
        if states.is_empty() {
            return Err(err("classifier: no states to evaluate"));
        }
        let results: Vec<Result<Decision, ClassifierError>> =
            futures_util::stream::iter(states.into_iter().enumerate().map(|(i, st)| {
                let c = self.clone();
                async move {
                    c.evaluate(st, questions)
                        .await
                        .map_err(|e| err(format!("state {i}: {e}")))
                }
            }))
            .buffered(DEFAULT_CLASSIFIER_BATCH_CONCURRENCY)
            .collect()
            .await;
        results.into_iter().collect()
    }

    // ---- degenerate ----

    fn report_degenerate(&self, questions: &Questions) {
        for (key, q) in questions {
            let Question::Choice(c) = q else { continue };
            let Some(reason) = degenerate_criteria(&c.criteria) else {
                continue;
            };
            let first = self
                .inner
                .warned
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(key.clone());
            if !first {
                continue;
            }
            self.emit(MetricEvent {
                event: METRIC_CLASSIFIER_WARNING.into(),
                question: key.clone(),
                warning: format!(
                    "classifier: question {key:?} has degenerate criteria ({reason}) — every option reads the same to the model and the answer ranks at chance; describe what picking each option would MEAN (SPEC.md §8B)"
                ),
                ..Default::default()
            });
        }
    }

    // ---- backends ----

    fn evaluate_static(
        &self,
        state: &Value,
        questions: &Questions,
    ) -> Result<Decision, ClassifierError> {
        let k = static_key(&self.inner.opts.model, state, questions);
        let raw = self.inner.statics.get(&k).ok_or_else(|| {
            err("classifier: static: no recorded decision for this request+state")
        })?;
        Decision::from_slice(raw)
    }

    /// One POST with the §8 retry budget. No credential value and no expanded
    /// header value appears on any path out of here.
    ///
    /// `endpoint` is what errors name: the CONFIGURED base, `${ENV}`
    /// unexpanded, so an account id in a templated URL never reaches an error.
    /// `url` is what is dialled, expanded at call time.
    async fn post(
        &self,
        cancel: &CancellationToken,
        raw: Vec<u8>,
        extra: &[(&str, &str)],
    ) -> Result<Vec<u8>, ClassifierError> {
        let o = &self.inner.opts;
        let mut endpoint = o.base_url.trim_end_matches('/').to_owned();
        if self.inner.preset.wire != ClassifierWire::Cloudflare {
            endpoint.push_str("/systemone");
        }
        let url = expand_env_refs(&endpoint);
        let retries: u32 = match o.retries {
            -1 => 0,
            n if n > 0 => n as u32,
            _ => 2,
        };
        let base_ms = if o.retry_base_ms > 0 {
            o.retry_base_ms
        } else {
            500
        };
        let hc = o.http_client.clone().unwrap_or_default();
        let mut attempt: u32 = 0;
        loop {
            let r = tokio::select! {
                _ = cancel.cancelled() => return Err(err("context canceled")),
                r = tokio::time::timeout(Duration::from_millis(o.timeout_ms), self.attempt(&hc, &url, &raw, extra)) => r,
            };
            let (outcome, status, retry_after) = match r {
                Err(_) => (
                    Err(format!("request timeout after {}ms", o.timeout_ms)),
                    0,
                    String::new(),
                ),
                Ok(Err(e)) => (Err(e), 0, String::new()),
                Ok(Ok((status, ra, body))) => (Ok(body), status, ra),
            };
            if let Ok(body) = &outcome
                && status < 300
            {
                return Ok(body.clone());
            }
            if cancel.is_cancelled() {
                return Err(err("context canceled"));
            }
            let retryable = outcome.is_err()
                || status == 408
                || [429, 500, 502, 503, 504, 529].contains(&status)
                || o.retryable_statuses.contains(&status);
            let (last, msg) = match &outcome {
                Err(e) => (err(format!("classifier: POST {endpoint}: {e}")), e.clone()),
                Ok(body) => (
                    err(format!(
                        "classifier: POST {endpoint}: HTTP {status}{}",
                        cause(status, body)
                    )),
                    format!("HTTP {status}"),
                ),
            };
            if attempt >= retries {
                return Err(last);
            }
            let info = ErrorInfo {
                error: msg,
                status,
                attempt,
                retryable,
            };
            let tier = match &o.on_error {
                Some(f) => f(&info),
                None if retryable => ErrorTier::Retry,
                None => ErrorTier::Fail,
            };
            if tier != ErrorTier::Retry {
                return Err(last);
            }
            let delay = retry_after_seconds(&retry_after)
                .unwrap_or_else(|| Duration::from_millis(base_ms * (1u64 << attempt.min(30))));
            tokio::select! {
                _ = cancel.cancelled() => return Err(err("context canceled")),
                _ = tokio::time::sleep(delay) => {}
            }
            attempt += 1;
        }
    }

    async fn attempt(
        &self,
        hc: &reqwest::Client,
        endpoint: &str,
        raw: &[u8],
        extra: &[(&str, &str)],
    ) -> Result<(u16, String, Vec<u8>), String> {
        let o = &self.inner.opts;
        let mut req = hc.post(endpoint).header("Content-Type", "application/json");
        // Read at call time. Never logged, never returned, never in an error.
        if let Ok(key) = std::env::var(&o.api_key_env)
            && !key.is_empty()
        {
            req = req.header("Authorization", format!("Bearer {key}"));
        }
        // Library headers first; a host header of the same name wins.
        for (k, v) in extra {
            if !o.headers.keys().any(|h| h.eq_ignore_ascii_case(k)) {
                req = req.header(*k, *v);
            }
        }
        for (k, v) in crate::mcp::expand_env_headers(&o.headers) {
            req = req.header(k, v);
        }
        let resp = req
            .body(raw.to_vec())
            .send()
            .await
            .map_err(|e| e.without_url().to_string())?;
        let status = resp.status().as_u16();
        let ra = resp
            .headers()
            .get("retry-after")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_owned();
        let body = resp
            .bytes()
            .await
            .map_err(|e| e.without_url().to_string())?;
        Ok((status, ra, body.to_vec()))
    }

    /// The vendor-neutral fallback: the questions as ONE structured-output call
    /// on any §8 client. `calibrated` is FALSE (self-report, not token probabilities).
    async fn evaluate_llm(
        &self,
        ctx: &EvalContext,
        state: &Value,
        questions: &Questions,
    ) -> Result<Decision, ClassifierError> {
        let o = &self.inner.opts;
        let Some(client) = &o.client else {
            return Err(err("classifier: style \"llm\" requires Client"));
        };
        let prompt = format!(
            "Answer every question about the state below. Questions are INDEPENDENT: one answer is never context for another.\n\nSTATE:\n{}\n\nQUESTIONS:\n{}\n\nReply with JSON only, no prose and no code fence, shaped exactly:\n{}\n{}{}{}{}",
            canonical_json(state),
            canonical_json(&wire_questions(questions)),
            r#"{"answers":{"<key>":{"type":"noul","noul":0.0}}}"#,
            r#"A "noul" answer is {"type":"noul","noul":<0..1>}. A "choice" answer is "#,
            r#"{"type":"choice","choice":"<one offered option id>","probabilities":{"<every offered option id>":<0..1>},"confidence":<0..1>}. "#,
            r#"A "score" answer is {"type":"score","score":<a number within the rubric bounds, fractional allowed>,"#,
            r#""legend":{"0":"<level 0>",…},"probabilities":{"0":<0..1>,…},"confidence":<0..1>}."#,
        );
        let tk = crate::create_toolkit(crate::ToolkitOptions {
            builtins: Some(crate::BuiltinsConfig::off()),
            ..Default::default()
        })
        .await
        .map_err(|e| err(format!("classifier: llm: {e}")))?;
        let run = client
            .run_ctx(ctx.cancel.clone(), prompt, &tk, Vec::new())
            .await
            .map_err(|e| err(e.to_string()))?;
        let payload = first_json_object(&run.text)
            .ok_or_else(|| err("classifier: llm: no JSON object in the reply"))?;
        let mut d = Decision::from_slice(payload.as_bytes())
            .map_err(|e| err(format!("classifier: llm: {e}")))?;
        d.calibrated = false;
        if d.model.is_empty() {
            d.model = o.model.clone();
        }
        d.usage = ClassifierUsage {
            input_tokens: run.usage.prompt_tokens,
            output_tokens: run.usage.completion_tokens,
            cost: None,
        };
        Ok(d)
    }

    // ---- metrics ----

    fn emit(&self, ev: MetricEvent) {
        if let Some(f) = &self.inner.opts.on_metric {
            f(&ev);
        }
    }

    fn emit_error(&self, start: Instant, e: &ClassifierError, questions: i64, hash: &str) {
        self.emit(MetricEvent {
            event: METRIC_CLASSIFIER_EVALUATE.into(),
            model: self.inner.opts.model.clone(),
            status: "error".into(),
            ms: start.elapsed().as_millis() as i64,
            error: e.0.clone(),
            questions,
            questions_hash: hash.into(),
            ..Default::default()
        });
    }
}

/// The §8B degenerate predicate: every value empty, every value its own key,
/// or every value identical (n >= 2). A single-option choice never is.
pub fn degenerate_criteria(criteria: &BTreeMap<String, String>) -> Option<&'static str> {
    if criteria.len() < 2 {
        return None;
    }
    let first = criteria.values().next().map(String::as_str).unwrap_or("");
    if criteria.values().all(String::is_empty) {
        Some("every description is empty")
    } else if criteria.iter().all(|(k, v)| k == v) {
        Some("every description is just its own option id")
    } else if criteria.values().all(|v| v == first) {
        Some("every description is identical")
    } else {
        None
    }
}

/// A backend's cause, intact — except an auth status's body, which is dropped.
fn cause(status: u16, body: &[u8]) -> String {
    let s = redact_provider_body(status, &String::from_utf8_lossy(body));
    if s.is_empty() {
        return String::new();
    }
    if s.len() > 200 {
        let mut n = 200;
        while !s.is_char_boundary(n) {
            n -= 1;
        }
        return format!(": {}…", &s[..n]);
    }
    format!(": {s}")
}

/// The outermost JSON object of a model reply (no repair).
fn first_json_object(s: &str) -> Option<&str> {
    let start = s.find('{')?;
    let end = s.rfind('}')?;
    if end <= start {
        return None;
    }
    Some(&s[start..=end])
}
