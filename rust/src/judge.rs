//! Simple judgments (SPEC §8B "Simple judgments — ask / gate"): builders,
//! [`ask`], [`gate`], [`Policy`] and [`Tape`] over any [`Classifier`]. The wire
//! is unchanged — the builders produce exactly the `evaluate` inputs a host
//! would write by hand. Ported from `golang/judge.go`.

pub mod batteries;

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

use crate::classifier::{
    ChoiceQuestion, Classifier, ClassifierError, ClassifierOptions, ClassifierStyle, Decision,
    DecisionAnswer, EvalContext, NoulCriteria, NoulQuestion, Question, Questions, RecordedDecision,
    ScoreQuestion, create_classifier,
};
use crate::tool::Request;

/// One named question. Build with [`noul`] / [`choice`] / [`score`] and pass an
/// ORDERED list; a repeated name is an error.
#[derive(Debug, Clone, PartialEq)]
pub struct JudgeQuestion {
    pub name: String,
    pub question: Question,
}

/// "How likely is this true?"
pub fn noul(name: &str, instructions: &str) -> JudgeQuestion {
    JudgeQuestion {
        name: name.into(),
        question: NoulQuestion {
            instructions: instructions.into(),
            criteria: None,
        }
        .into(),
    }
}

/// [`noul`] with criteria labelling the true and false cases.
pub fn noul_with(
    name: &str,
    instructions: &str,
    when_true: &str,
    when_false: &str,
) -> JudgeQuestion {
    JudgeQuestion {
        name: name.into(),
        question: NoulQuestion {
            instructions: instructions.into(),
            criteria: Some(NoulCriteria {
                when_true: when_true.into(),
                when_false: when_false.into(),
            }),
        }
        .into(),
    }
}

/// Pick one option id; `options` maps id -> what picking it MEANS.
pub fn choice<K: Into<String>, V: Into<String>>(
    name: &str,
    instructions: &str,
    options: impl IntoIterator<Item = (K, V)>,
) -> JudgeQuestion {
    JudgeQuestion {
        name: name.into(),
        question: ChoiceQuestion {
            instructions: instructions.into(),
            criteria: options
                .into_iter()
                .map(|(k, v)| (k.into(), v.into()))
                .collect(),
        }
        .into(),
    }
}

/// Rate on ordered levels, lowest first.
pub fn score<S: Into<String>>(
    name: &str,
    instructions: &str,
    levels: impl IntoIterator<Item = S>,
) -> JudgeQuestion {
    JudgeQuestion {
        name: name.into(),
        question: ScoreQuestion {
            instructions: instructions.into(),
            criteria: levels.into_iter().map(Into::into).collect(),
        }
        .into(),
    }
}

/// The ordered list as the §8B map; a repeated name is an error naming it.
pub fn questions(qs: &[JudgeQuestion]) -> Result<Questions, ClassifierError> {
    let mut m = Questions::new();
    for q in qs {
        if m.contains_key(&q.name) {
            return Err(ClassifierError(format!(
                "duplicate question name {:?}",
                q.name
            )));
        }
        m.insert(q.name.clone(), q.question.clone());
    }
    Ok(m)
}

/// The public question -> §8B wire conversion.
pub fn question_wire(q: &Question) -> Value {
    q.wire()
}

/// `role` next to `data`'s fields at the top level; a non-object `data` goes
/// under `"data"`. The role is never copied into instructions (ADR 0035 D7).
pub fn state(role: &str, data: impl Serialize) -> Map<String, Value> {
    let mut s = match serde_json::to_value(data) {
        Ok(Value::Object(m)) => m,
        Ok(Value::Null) | Err(_) => Map::new(),
        Ok(v) => {
            let mut m = Map::new();
            m.insert("data".into(), v);
            m
        }
    };
    s.insert("role".into(), json!(role));
    s
}

/// State sugar: `{"context": ctx, "message": msg}` plus `extra`.
pub fn message_state(ctx: &str, message: &str, extra: Map<String, Value>) -> Map<String, Value> {
    let mut s = Map::new();
    s.insert("context".into(), json!(ctx));
    s.insert("message".into(), json!(message));
    s.extend(extra);
    s
}

// ---------------------------------------------------------------- bands

/// Confidence cut-points: no / uncertain / yes. EXCLUSIVE on the confident
/// side — exactly `low` or exactly `high` is uncertain.
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
pub struct Bands {
    pub low: f64,
    pub high: f64,
}

/// 0.30 / 0.70.
pub const DEFAULT_BANDS: Bands = Bands {
    low: 0.30,
    high: 0.70,
};

pub const BAND_YES: &str = "yes";
pub const BAND_NO: &str = "no";
pub const BAND_UNCERTAIN: &str = "uncertain";

impl Bands {
    /// Noul: value against the cuts. Choice: yes only if confidence > high and
    /// not near-uniform. Score: yes only if confidence > high.
    pub fn band(&self, a: &DecisionAnswer) -> &'static str {
        match a {
            DecisionAnswer::Noul(x) => {
                if x.noul < self.low {
                    return BAND_NO;
                }
                if x.noul > self.high {
                    return BAND_YES;
                }
            }
            DecisionAnswer::Choice(x) => {
                if !x.near_uniform && x.confidence > self.high {
                    return BAND_YES;
                }
            }
            DecisionAnswer::Score(x) => {
                if x.confidence > self.high {
                    return BAND_YES;
                }
            }
        }
        BAND_UNCERTAIN
    }
}

/// `None` or the zero value ⇒ [`DEFAULT_BANDS`].
pub(crate) fn pick_bands(b: Option<Bands>) -> Bands {
    match b {
        Some(b) if b != Bands::default() => b,
        _ => DEFAULT_BANDS,
    }
}

/// A classifier answer plus its band (`sure` is band == yes).
#[derive(Debug, Clone, PartialEq)]
pub struct JudgeAnswer {
    pub answer: DecisionAnswer,
    pub band: String,
    pub sure: bool,
}

impl JudgeAnswer {
    /// The one number: noul probability, score value, choice confidence.
    pub fn value(&self) -> f64 {
        match &self.answer {
            DecisionAnswer::Noul(x) => x.noul,
            DecisionAnswer::Score(x) => x.score,
            DecisionAnswer::Choice(x) => x.confidence,
        }
    }

    /// The picked option id (`""` for noul/score).
    pub fn choice(&self) -> &str {
        match &self.answer {
            DecisionAnswer::Choice(x) => &x.choice,
            _ => "",
        }
    }
}

/// Serialises FLAT: the wire fields plus `type`, then `band` (noul) or `sure`.
impl Serialize for JudgeAnswer {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        let mut m = self.answer.wire();
        if matches!(self.answer, DecisionAnswer::Noul(_)) {
            m.insert("band".into(), json!(self.band));
        } else {
            m.insert("sure".into(), json!(self.sure));
        }
        m.serialize(s)
    }
}

/// Answers by question name. A question the model did not answer is absent.
pub type Answers = BTreeMap<String, JudgeAnswer>;

pub(crate) fn band_all(d: &Decision, b: Bands) -> Answers {
    d.answers
        .iter()
        .map(|(k, a)| {
            let band = b.band(a);
            (
                k.clone(),
                JudgeAnswer {
                    answer: a.clone(),
                    band: band.into(),
                    sure: band == BAND_YES,
                },
            )
        })
        .collect()
}

/// Evaluate once and band every answer (`None` ⇒ 0.30 / 0.70).
pub async fn ask(
    c: &Classifier,
    state: impl Serialize,
    qs: &[JudgeQuestion],
    bands: Option<Bands>,
) -> Result<Answers, ClassifierError> {
    ask_ctx(EvalContext::default(), c, state, qs, bands).await
}

/// [`ask`] with an [`EvalContext`] (cancellation, tape call name).
pub async fn ask_ctx(
    ctx: EvalContext,
    c: &Classifier,
    state: impl Serialize,
    qs: &[JudgeQuestion],
    bands: Option<Bands>,
) -> Result<Answers, ClassifierError> {
    let m = questions(qs)?;
    let st = serde_json::to_value(state).map_err(|e| ClassifierError(e.to_string()))?;
    let d = c.evaluate_ctx(ctx, st, &m).await?;
    Ok(band_all(&d, pick_bands(bands)))
}

// ---------------------------------------------------------------- gate

/// One gate. Exactly one of `below` / `at_least` / `is` applies.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Rule {
    pub question: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub below: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub at_least: Option<f64>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub is: String,
    #[serde(default)]
    pub action: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub target: String,
}

/// An action fired, or escalated with a §10 `input` Request, or neither
/// (`action` "" — fall through).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Outcome {
    pub action: String,
    pub target: String,
    pub rule: Option<Rule>,
    pub answers: Answers,
    pub escalated: bool,
    pub request: Option<Request>,
}

/// Ask once and apply rules first-match. A rule whose answer is missing or
/// uncertain ESCALATES (`needs_input`) instead of deciding.
pub async fn gate(
    c: &Classifier,
    state: impl Serialize,
    qs: &[JudgeQuestion],
    rules: &[Rule],
    bands: Option<Bands>,
) -> Result<Outcome, ClassifierError> {
    let a = ask(c, state, qs, bands).await?;
    Ok(apply_rules(a, rules))
}

/// The pure half of [`gate`].
pub fn apply_rules(a: Answers, rules: &[Rule]) -> Outcome {
    apply(a, rules, false)
}

fn raw_answers(a: &Answers) -> Value {
    Value::Object(
        a.iter()
            .map(|(k, v)| (k.clone(), Value::Object(v.answer.wire())))
            .collect(),
    )
}

fn apply(a: Answers, rules: &[Rule], skip_uncertain: bool) -> Outcome {
    for (i, r) in rules.iter().enumerate() {
        if skip_uncertain
            && let Some(x) = a.get(&r.question)
            && x.band == BAND_UNCERTAIN
        {
            continue;
        }
        let (fired, reason) = check_rule(&a, r);
        if !reason.is_empty() {
            let mut data = Map::new();
            data.insert("question".into(), json!(r.question));
            data.insert("reason".into(), json!(reason));
            data.insert("answers".into(), raw_answers(&a));
            data.insert(
                "rule".into(),
                serde_json::to_value(r).unwrap_or(Value::Null),
            );
            let request = Request {
                id: format!("gate:{i}:{}", r.question),
                kind: "input".into(),
                prompt: format!(
                    "Classifier is unsure about {:?} ({reason}). Decide rule {i} ({}).",
                    r.question, r.action
                ),
                data: Some(data),
                ..Default::default()
            };
            return Outcome {
                action: "needs_input".into(),
                rule: Some(r.clone()),
                answers: a,
                escalated: true,
                request: Some(request),
                ..Default::default()
            };
        }
        if fired {
            return Outcome {
                action: r.action.clone(),
                target: r.target.clone(),
                rule: Some(r.clone()),
                answers: a,
                ..Default::default()
            };
        }
    }
    Outcome {
        answers: a,
        ..Default::default()
    }
}

/// (fired, escalate reason); a non-empty reason means ask a human.
fn check_rule(answers: &Answers, r: &Rule) -> (bool, String) {
    let Some(a) = answers.get(&r.question) else {
        return (false, format!("missing answer \"{}\"", r.question));
    };
    if a.band == BAND_UNCERTAIN {
        return (false, format!("uncertain answer \"{}\"", r.question));
    }
    match &a.answer {
        DecisionAnswer::Noul(_) | DecisionAnswer::Score(_) => {
            let v = a.value();
            if let Some(b) = r.below {
                return (v < b, String::new());
            }
            if let Some(at) = r.at_least {
                return (v >= at, String::new());
            }
        }
        DecisionAnswer::Choice(_) => {
            if !r.is.is_empty() {
                return (a.choice() == r.is, String::new());
            }
        }
    }
    (
        false,
        format!("rule does not fit {} answer", a.answer.answer_type()),
    )
}

/// [`gate`] with a DECLARED fall-through: `default` names the action when no
/// rule fires; `""` escalates with reason "no rule fired". With
/// `skip_uncertain`, an uncertain answer skips its rule instead of escalating.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Policy {
    pub rules: Vec<Rule>,
    pub default: String,
    /// `None` ⇒ [`DEFAULT_BANDS`].
    pub bands: Option<Bands>,
    pub skip_uncertain: bool,
}

impl Policy {
    /// Apply the rules, then the declared default.
    pub fn decide(&self, a: Answers) -> Outcome {
        let mut o = apply(a, &self.rules, self.skip_uncertain);
        if !o.action.is_empty() || o.escalated {
            return o;
        }
        if !self.default.is_empty() {
            o.action = self.default.clone();
            return o;
        }
        let mut data = Map::new();
        data.insert("question".into(), json!(""));
        data.insert("reason".into(), json!("no rule fired"));
        data.insert("answers".into(), raw_answers(&o.answers));
        o.escalated = true;
        o.action = "needs_input".into();
        o.request = Some(Request {
            id: "gate:default".into(),
            kind: "input".into(),
            prompt: "No rule fired: the answers are confident but in between. Decide.".into(),
            data: Some(data),
            ..Default::default()
        });
        o
    }

    /// Ask once and decide.
    pub async fn gate(
        &self,
        c: &Classifier,
        state: impl Serialize,
        qs: &[JudgeQuestion],
    ) -> Result<Outcome, ClassifierError> {
        let a = ask(c, state, qs, self.bands).await?;
        Ok(self.decide(a))
    }
}

// ---------------------------------------------------------------- static / tape

/// One static-corpus entry; `response` is the backend body as JSON.
pub fn recorded(state: impl Serialize, questions: Questions, response: &Value) -> RecordedDecision {
    RecordedDecision {
        state: serde_json::to_value(state).unwrap_or(Value::Null),
        questions,
        response: serde_json::to_vec(response).unwrap_or_default(),
    }
}

/// The one-line static classifier over recorded decisions.
pub fn static_classifier(decisions: Vec<RecordedDecision>) -> Result<Classifier, ClassifierError> {
    create_classifier(ClassifierOptions {
        style: ClassifierStyle::Static,
        decisions,
        ..Default::default()
    })
}

/// Records live decisions BY CALL NAME ([`EvalContext::call_name`]) and replays
/// them offline. A replay miss names the key.
#[derive(Debug, Default)]
pub struct Tape {
    decisions: Mutex<BTreeMap<String, Value>>,
}

impl Tape {
    pub fn new() -> Arc<Tape> {
        Arc::new(Tape::default())
    }

    /// Read a tape file.
    pub fn load(path: impl AsRef<std::path::Path>) -> Result<Arc<Tape>, ClassifierError> {
        let b = std::fs::read(path).map_err(|e| ClassifierError(e.to_string()))?;
        #[derive(Deserialize)]
        struct File {
            #[serde(default)]
            decisions: Option<BTreeMap<String, Value>>,
        }
        let f: File = serde_json::from_slice(&b).map_err(|e| ClassifierError(e.to_string()))?;
        Ok(Arc::new(Tape {
            decisions: Mutex::new(f.decisions.unwrap_or_default()),
        }))
    }

    /// Write the tape (sorted keys, 2-space indent, trailing newline).
    pub fn save(&self, path: impl AsRef<std::path::Path>) -> Result<(), ClassifierError> {
        let d = self.decisions.lock().unwrap_or_else(|e| e.into_inner());
        let v = if d.is_empty() {
            json!({"decisions": null})
        } else {
            json!({"decisions": *d})
        };
        drop(d);
        let mut s = serde_json::to_string_pretty(&v).map_err(|e| ClassifierError(e.to_string()))?;
        s.push('\n');
        std::fs::write(path, s).map_err(|e| ClassifierError(e.to_string()))
    }

    /// The recorded decisions by call name (wire shape).
    pub fn decisions(&self) -> BTreeMap<String, Value> {
        self.decisions
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    /// Wrap a live classifier: every call is forwarded and taped.
    pub fn recording(self: &Arc<Self>, live: Classifier) -> Result<Classifier, ClassifierError> {
        let tape = self.clone();
        create_classifier(ClassifierOptions {
            style: ClassifierStyle::Custom,
            evaluate: Some(Arc::new(move |ctx: EvalContext, state, qs| {
                let (tape, live) = (tape.clone(), live.clone());
                Box::pin(async move {
                    let name = ctx.call_name.clone();
                    let d = live.evaluate_ctx(ctx, state, &qs).await.map_err(|e| e.0)?;
                    tape.decisions
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .insert(name, d.to_value());
                    Ok(d)
                })
            })),
            ..Default::default()
        })
    }

    /// Answer from the tape by call name, with no network.
    pub fn replayer(self: &Arc<Self>) -> Result<Classifier, ClassifierError> {
        let tape = self.clone();
        create_classifier(ClassifierOptions {
            style: ClassifierStyle::Custom,
            evaluate: Some(Arc::new(move |ctx: EvalContext, _state, _qs| {
                let tape = tape.clone();
                Box::pin(async move {
                    let raw = tape
                        .decisions
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .get(&ctx.call_name)
                        .cloned();
                    match raw {
                        None => Err(format!(
                            "tape: no recorded decision for call \"{}\"",
                            ctx.call_name
                        )),
                        Some(v) => Decision::from_value(&v).map_err(|e| e.0),
                    }
                })
            })),
            ..Default::default()
        })
    }
}
