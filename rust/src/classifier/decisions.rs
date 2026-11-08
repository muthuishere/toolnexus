//! The Decisions API wire (SPEC.md §8B "Decisions wire", OpenSpec change
//! `add-classifier-openai-decisions`, issue #157). OpenAI's `POST /v1/decisions`
//! takes the same three question kinds as System One under other names
//! (predicate / choice / score) as an ORDERED LIST, and answers in that order.
//! This maps a §8B question set onto that list and reads the answers back into
//! the one [`Decision`](super::Decision) type. Ported from
//! `golang/classifier_decisions.go`.

use serde::Deserialize;
use serde_json::{Map, Value, json};

use super::backends::Prepared;
use super::{
    ClassifierBackend, ClassifierError, ClassifierUnsupported, MAX_CHOICE_OPTIONS, Question,
    Questions, canonical_json, err,
};

/// The Decisions API's per-request question cap.
pub const MAX_DECISIONS_QUESTIONS: usize = 400;

/// The question set as the Decisions API list, in ASCII key order. Caller
/// keys are NOT sent (no `name`): answers come back by position. Every check
/// fires before any request.
pub(super) fn decisions_questions(
    backend: ClassifierBackend,
    model: &str,
    questions: &Questions,
) -> Result<Vec<Value>, ClassifierError> {
    if questions.len() > MAX_DECISIONS_QUESTIONS {
        return Err(err(format!(
            "classifier: the decisions wire takes at most {MAX_DECISIONS_QUESTIONS} questions, got {}",
            questions.len()
        )));
    }
    let mut out = Vec::with_capacity(questions.len());
    for (k, q) in questions {
        out.push(match q {
            Question::Noul(q) => {
                let mut instr = q.instructions.clone();
                if let Some(c) = &q.criteria {
                    instr.push_str(&format!(
                        "\n\nTrue means: {}\nFalse means: {}",
                        c.when_true, c.when_false
                    ));
                }
                json!({"type": "predicate", "instructions": instr})
            }
            Question::Choice(q) => {
                if q.criteria.len() < 2 {
                    return Err(err(format!(
                        "classifier: question {k:?}: the decisions wire needs 2..{MAX_CHOICE_OPTIONS} choice options, got {}",
                        q.criteria.len()
                    )));
                }
                // BTreeMap iterates in byte (ASCII) order.
                let choices: Vec<Value> = q
                    .criteria
                    .iter()
                    .map(|(id, d)| {
                        let mut c = Map::new();
                        c.insert("value".into(), Value::String(id.clone()));
                        if !d.is_empty() {
                            c.insert("description".into(), Value::String(d.clone()));
                        }
                        Value::Object(c)
                    })
                    .collect();
                json!({"type": "choice", "instructions": q.instructions, "choices": choices})
            }
            Question::Score(q) => {
                let levels: Vec<Value> = q.criteria.iter().map(|l| json!({"label": l})).collect();
                json!({"type": "score", "instructions": q.instructions, "levels": levels})
            }
            Question::Raw(q) => {
                return Err(ClassifierUnsupported {
                    backend: backend.as_str().into(),
                    model: model.into(),
                    feature: format!("raw question type {:?}", q.kind),
                }
                .into());
            }
        });
    }
    Ok(out)
}

/// The state as sent: a string verbatim, anything else as canonical JSON text;
/// with images, one user message holding that text then the images as data
/// URLs, in collection order.
pub(super) fn decisions_input(p: &Prepared) -> Value {
    let text = match &p.state {
        Value::String(s) => s.clone(),
        v => canonical_json(v),
    };
    if p.imgs.is_empty() {
        return Value::String(text);
    }
    let mut content = vec![json!({"type": "input_text", "text": text})];
    for im in &p.imgs {
        content.push(json!({
            "type": "input_image",
            "image_url": format!("data:image/{};base64,{}", im.format, im.data),
        }));
    }
    json!([{"role": "user", "content": content}])
}

#[derive(Deserialize)]
struct Reply {
    model: Option<String>,
    answers: Option<Vec<Value>>,
    #[serde(default)]
    usage: Option<Usage>,
}

#[derive(Deserialize, Default)]
struct Usage {
    input_tokens: Option<i64>,
    output_tokens: Option<i64>,
}

#[derive(Deserialize)]
struct Answer {
    #[serde(rename = "type")]
    kind: Option<String>,
    probability: Option<f64>,
    choice: Option<Value>,
    score: Option<f64>,
    confidence: Option<f64>,
    probabilities: Option<Vec<Prob>>,
}

#[derive(Deserialize)]
struct Prob {
    value: Option<Value>,
    probability: Option<f64>,
}

/// Read a Decisions API reply back into a System One-shaped body (canonical
/// JSON), so decode, validation and the cache are the one path. `calibrated`
/// is false: a Decisions model is not a System One model.
#[doc(hidden)]
pub fn decisions_reply(body: &[u8], questions: &Questions) -> Result<String, ClassifierError> {
    let malformed = || err("classifier: decisions: malformed reply");
    let r: Reply = serde_json::from_slice(body).map_err(|_| malformed())?;
    let Some(raw_answers) = r.answers else {
        return Err(malformed());
    };
    if raw_answers.len() != questions.len() {
        return Err(err(format!(
            "classifier: decisions: {} answers for {} questions",
            raw_answers.len(),
            questions.len()
        )));
    }
    let mut answers = Map::new();
    for ((k, q), raw) in questions.iter().zip(raw_answers) {
        let a: Answer = serde_json::from_value(raw).map_err(|_| malformed())?;
        let kind = a.kind.unwrap_or_default();
        if kind == "refusal" {
            return Err(err(format!(
                "classifier: decisions: question {k:?} was refused"
            )));
        }
        let want = match q {
            Question::Noul(_) => "predicate",
            Question::Choice(_) => "choice",
            Question::Score(_) => "score",
            Question::Raw(_) => "",
        };
        if kind != want {
            return Err(err(format!(
                "classifier: decisions: answer {k:?} is a {kind} answer, not {want}"
            )));
        }
        let probs: Map<String, Value> = a
            .probabilities
            .unwrap_or_default()
            .into_iter()
            .map(|p| {
                (
                    decisions_value(p.value.as_ref()),
                    json!(p.probability.unwrap_or(0.0)),
                )
            })
            .collect();
        let confidence = a.confidence.unwrap_or(0.0);
        let v = match q {
            Question::Noul(_) => {
                json!({"type": "noul", "noul": a.probability.unwrap_or(0.0)})
            }
            Question::Choice(_) => json!({
                "type": "choice",
                "choice": decisions_value(a.choice.as_ref()),
                "probabilities": probs,
                "confidence": confidence,
            }),
            Question::Score(s) => {
                let legend: Map<String, Value> = s
                    .criteria
                    .iter()
                    .enumerate()
                    .map(|(j, l)| (j.to_string(), Value::String(l.clone())))
                    .collect();
                json!({
                    "type": "score",
                    "score": a.score.unwrap_or(0.0),
                    "legend": legend,
                    "probabilities": probs,
                    "confidence": confidence,
                })
            }
            Question::Raw(_) => unreachable!("raw questions never reach the decisions wire"),
        };
        answers.insert(k.clone(), v);
    }
    let usage = r.usage.unwrap_or_default();
    Ok(canonical_json(&json!({
        "answers": answers,
        "calibrated": false,
        "model": r.model.unwrap_or_default(),
        "usage": {
            "input_tokens": usage.input_tokens.unwrap_or(0),
            "output_tokens": usage.output_tokens.unwrap_or(0),
        },
    })))
}

/// A choice value or level index as the §8B key: a string as written, a
/// boolean as `true`/`false`, a number in its shortest form.
fn decisions_value(v: Option<&Value>) -> String {
    match v {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Bool(b)) => b.to_string(),
        Some(Value::Number(n)) => n.as_f64().map(|f| f.to_string()).unwrap_or_default(),
        None | Some(Value::Null) => "<nil>".into(),
        Some(other) => other.to_string(),
    }
}
