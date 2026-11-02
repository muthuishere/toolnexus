//! Judge adapters (SPEC §8B "Simple judgments — ask / gate") against the
//! shared examples/judge/adapters fixtures. Mirrors golang/judge_test.go.

mod common;

use std::sync::{Arc, Mutex};

use common::judge::*;
use common::*;
use serde_json::{Map, Value, json};
use toolnexus::classifier::*;
use toolnexus::judge::*;

fn fixture_question(m: &Value) -> JudgeQuestion {
    let (name, instr) = (
        m["name"].as_str().unwrap(),
        m["instructions"].as_str().unwrap(),
    );
    match m["kind"].as_str().unwrap() {
        "noul" => noul(name, instr),
        "choice" => choice(
            name,
            instr,
            m["options"]
                .as_object()
                .unwrap()
                .iter()
                .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_owned())),
        ),
        "score" => score(
            name,
            instr,
            m["levels"]
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap().to_owned()),
        ),
        k => panic!("unknown kind {k}"),
    }
}

fn wire_map(qs: &Questions) -> Value {
    Value::Object(
        qs.iter()
            .map(|(k, q)| (k.clone(), question_wire(q)))
            .collect(),
    )
}

#[test]
fn state_cases() {
    let fx = load("adapters/state-cases.json");
    let cases = fx["cases"].as_array().unwrap();
    assert!(!cases.is_empty());
    for c in cases {
        let name = c["name"].as_str().unwrap();
        let qs: Vec<JudgeQuestion> = c["questions"]
            .as_array()
            .unwrap()
            .iter()
            .map(fixture_question)
            .collect();
        let got = questions(&qs);
        if let Some(want) = c.get("wantError").and_then(Value::as_str) {
            let e = got.unwrap_err().0;
            assert!(e.contains(want), "{name}: {e}");
            continue;
        }
        let got = got.unwrap();
        let mut st = c.get("state").cloned().unwrap_or(Value::Null);
        if let Some(cx) = c.get("context") {
            st = Value::Object(message_state(
                cx["context"].as_str().unwrap(),
                cx["message"].as_str().unwrap(),
                cx.get("extra")
                    .and_then(Value::as_object)
                    .cloned()
                    .unwrap_or_default(),
            ));
        }
        if let Some(rs) = c.get("roleState") {
            st = Value::Object(state(rs["role"].as_str().unwrap(), &rs["data"]));
        }
        assert!(json_eq(&st, &c["wantState"]), "{name}: state {st}");
        assert!(
            json_eq(&wire_map(&got), &c["wantQuestions"]),
            "{name}: questions"
        );
    }
}

#[test]
fn state_role() {
    let s = state(
        "You are Donkey Kong, you want to win.",
        json!({"message_received": "hi"}),
    );
    assert_eq!(
        Value::Object(s),
        json!({"role": "You are Donkey Kong, you want to win.", "message_received": "hi"})
    );
    assert_eq!(
        Value::Object(state("r", "plain text")),
        json!({"role": "r", "data": "plain text"})
    );
    #[derive(serde::Serialize)]
    struct Bug {
        title: String,
    }
    assert_eq!(
        Value::Object(state("r", Bug { title: "x".into() })),
        json!({"role": "r", "title": "x"})
    );
}

fn rules_of(v: &Value) -> Vec<Rule> {
    serde_json::from_value(v.clone()).unwrap()
}

#[tokio::test]
async fn gate_cases() {
    let fx = load("adapters/gate-cases.json");
    let db: Bands = serde_json::from_value(fx["defaultBands"].clone()).unwrap();
    assert_eq!(db, DEFAULT_BANDS);
    // Sorted by name, as the Go reference builds them.
    let qs: Vec<JudgeQuestion> = fx["questions"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(k, q)| JudgeQuestion {
            name: k.clone(),
            question: Question::from_wire(q).unwrap(),
        })
        .collect();
    let qm = questions(&qs).unwrap();
    let cases = fx["cases"].as_array().unwrap();
    assert!(!cases.is_empty());
    let recs = cases
        .iter()
        .map(|c| {
            recorded(
                json!({"case": c["name"]}),
                qm.clone(),
                &json!({"model": "m", "answers": c["answers"]}),
            )
        })
        .collect();
    let cl = static_classifier(recs).unwrap();
    let default_rules = rules_of(&fx["rules"]);
    for c in cases {
        let name = c["name"].as_str().unwrap();
        let st = json!({"case": name});
        let bands: Option<Bands> = match &c["bands"] {
            Value::Null => None,
            b => Some(serde_json::from_value(b.clone()).unwrap()),
        };
        let rules = match c.get("rules") {
            Some(r) if !r.is_null() => rules_of(r),
            _ => default_rules.clone(),
        };
        let o = if let Some(p) = c.get("policy").filter(|p| !p.is_null()) {
            Policy {
                rules,
                default: p["default"].as_str().unwrap_or("").into(),
                bands,
                skip_uncertain: p["skipUncertain"].as_bool().unwrap_or(false),
            }
            .gate(&cl, &st, &qs)
            .await
            .unwrap()
        } else {
            gate(&cl, &st, &qs, &rules, bands).await.unwrap()
        };
        let w = &c["want"];
        assert_eq!(o.action, w["action"].as_str().unwrap(), "{name}: action");
        assert_eq!(o.target, w["target"].as_str().unwrap(), "{name}: target");
        assert_eq!(o.escalated, w["escalated"].as_bool().unwrap(), "{name}");
        if o.escalated {
            let r = o.request.as_ref().expect("request");
            let d = r.data.as_ref().expect("data");
            assert_eq!(r.kind, "input");
            assert!(d.contains_key("answers") && d.contains_key("reason"));
            assert_eq!(d["question"], w["question"], "{name}: question");
            if let Some(reason) = w.get("reason").and_then(Value::as_str) {
                assert_eq!(d["reason"].as_str().unwrap(), reason, "{name}: reason");
            }
            assert_eq!(r.id, w["requestId"].as_str().unwrap(), "{name}: id");
        }
        let a = ask(&cl, &st, &qs, bands).await.unwrap();
        let wa = c["wantAnswers"].as_object().unwrap();
        assert_eq!(a.len(), wa.len(), "{name}: answers");
        for (k, want) in wa {
            let got = a.get(k).unwrap_or_else(|| panic!("{name}: {k}"));
            assert_eq!(got.value(), want["value"].as_f64().unwrap(), "{name}.{k}");
            if let Some(b) = want.get("band").and_then(Value::as_str) {
                assert_eq!(got.band, b, "{name}.{k} band");
            }
            if let Some(s) = want.get("sure").and_then(Value::as_bool) {
                assert_eq!(got.sure, s, "{name}.{k} sure");
            }
            if let Some(ch) = want.get("choice").and_then(Value::as_str) {
                assert_eq!(got.choice(), ch, "{name}.{k} choice");
            }
        }
    }
}

fn noul_ans(p: f64) -> JudgeAnswer {
    let a = DecisionAnswer::Noul(NoulAnswer { noul: p });
    let b = DEFAULT_BANDS.band(&a);
    JudgeAnswer {
        answer: a,
        band: b.into(),
        sure: b == BAND_YES,
    }
}

#[test]
fn bands_and_value() {
    for p in [0.30, 0.70] {
        assert_eq!(
            DEFAULT_BANDS.band(&DecisionAnswer::Noul(NoulAnswer { noul: p })),
            BAND_UNCERTAIN
        );
    }
    assert_eq!(
        Bands {
            low: 0.2,
            high: 0.5
        }
        .band(&DecisionAnswer::Noul(NoulAnswer { noul: 0.55 })),
        BAND_YES
    );
    let nu = DecisionAnswer::Choice(ChoiceAnswer {
        choice: "a".into(),
        confidence: 0.8,
        near_uniform: true,
        ..Default::default()
    });
    assert_eq!(DEFAULT_BANDS.band(&nu), BAND_UNCERTAIN);
    assert_eq!(noul_ans(0.96).value(), 0.96);
    let ch = JudgeAnswer {
        answer: DecisionAnswer::Choice(ChoiceAnswer {
            choice: "pricing".into(),
            confidence: 0.9,
            ..Default::default()
        }),
        band: String::new(),
        sure: false,
    };
    assert_eq!((ch.choice(), ch.value()), ("pricing", 0.9));
}

#[test]
fn answer_marshals_flat() {
    assert_eq!(
        serde_json::to_string(&noul_ans(0.96)).unwrap(),
        r#"{"band":"yes","noul":0.96,"type":"noul"}"#
    );
    let mut p = std::collections::BTreeMap::new();
    p.insert("a".to_string(), 0.9);
    let s = serde_json::to_string(&JudgeAnswer {
        answer: DecisionAnswer::Choice(ChoiceAnswer {
            choice: "a".into(),
            confidence: 0.9,
            probabilities: p,
            near_uniform: false,
        }),
        band: BAND_YES.into(),
        sure: true,
    })
    .unwrap();
    assert!(
        s.contains(r#""sure":true"#) && !s.contains("answer\""),
        "{s}"
    );
}

#[test]
fn policy() {
    let rule = |q: &str, act: &str| Rule {
        question: q.into(),
        below: Some(0.3),
        action: act.into(),
        ..Default::default()
    };
    let rules = vec![rule("a", "fail"), rule("b", "stop")];
    let ans = |a: f64, b: f64| -> Answers {
        [
            ("a".to_string(), noul_ans(a)),
            ("b".to_string(), noul_ans(b)),
        ]
        .into_iter()
        .collect()
    };
    let o = Policy {
        rules: rules.clone(),
        ..Default::default()
    }
    .decide(ans(0.9, 0.9));
    let r = o.request.unwrap();
    assert!(o.escalated && r.kind == "input");
    assert_eq!(r.data.unwrap()["reason"], "no rule fired");
    let o = Policy {
        rules: rules.clone(),
        default: "go".into(),
        ..Default::default()
    }
    .decide(ans(0.9, 0.9));
    assert!(o.action == "go" && !o.escalated);
    assert!(
        Policy {
            rules: rules.clone(),
            ..Default::default()
        }
        .decide(ans(0.5, 0.1))
        .escalated
    );
    let o = Policy {
        rules,
        skip_uncertain: true,
        ..Default::default()
    }
    .decide(ans(0.5, 0.1));
    assert!(o.action == "stop" && !o.escalated);
}

#[tokio::test]
async fn tape_records_and_replays_by_call_name() {
    let qs = vec![noul("ok", "Is `message` fine?")];
    let qm = questions(&qs).unwrap();
    let st = json!({"message": "hi"});
    let live = static_classifier(vec![recorded(
        &st,
        qm,
        &json!({"model": "m", "answers": {"ok": {"type": "noul", "noul": 0.9}}}),
    )])
    .unwrap();
    let tape = Tape::new();
    let rec = tape.recording(live).unwrap();
    ask_ctx(with_call_name("triage"), &rec, &st, &qs, None)
        .await
        .unwrap();
    let dir = std::env::temp_dir().join(format!("tn-tape-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("tape.json");
    tape.save(&path).unwrap();
    let rp = Tape::load(&path).unwrap().replayer().unwrap();
    let a = ask_ctx(
        with_call_name("triage"),
        &rp,
        json!({"message": "changed"}),
        &qs,
        None,
    )
    .await
    .unwrap();
    assert_eq!((a["ok"].value(), a["ok"].band.as_str()), (0.9, BAND_YES));
    let e = ask_ctx(with_call_name("plan"), &rp, &st, &qs, None)
        .await
        .unwrap_err()
        .0;
    assert_eq!(e, r#"tape: no recorded decision for call "plan""#);
    let e = ask_ctx(with_call_name(r#"a"b\c"#), &rp, &st, &qs, None)
        .await
        .unwrap_err()
        .0;
    assert_eq!(e, r#"tape: no recorded decision for call "a"b\c""#);
    let _ = std::fs::remove_dir_all(dir);
}

#[tokio::test]
async fn builders_are_byte_identical_to_hand_written() {
    let bodies = Arc::new(Mutex::new(Vec::<String>::new()));
    let b2 = bodies.clone();
    let srv = serve(move |r: Req| {
        b2.lock().unwrap().push(r.raw.clone());
        async {
            Resp::json(json!({"model":"m","answers":{"is_appropriate":{"type":"noul","noul":0.1},"does_this_help":{"type":"noul","noul":0.9}}}))
        }
    })
    .await;
    let c = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        api_key_env: "TEST_JUDGE_UNSET".into(),
        ..Default::default()
    })
    .unwrap();
    let role = "You are Donkey Kong, you want to win.";
    let i1 =
        "Does the message contain inappropriate language or topics that are considered harmful.";
    let i2 = "Does this help donkey kong win?";
    ask(
        &c,
        state(role, json!({"message_received": "jump"})),
        &[noul("is_appropriate", i1), noul("does_this_help", i2)],
        None,
    )
    .await
    .unwrap();
    let mut qs = Questions::new();
    for (k, i) in [("is_appropriate", i1), ("does_this_help", i2)] {
        qs.insert(
            k.into(),
            NoulQuestion {
                instructions: i.into(),
                criteria: None,
            }
            .into(),
        );
    }
    let mut st = Map::new();
    st.insert("role".into(), json!(role));
    st.insert("message_received".into(), json!("jump"));
    c.evaluate(Value::Object(st), &qs).await.unwrap();
    let b = bodies.lock().unwrap();
    assert_eq!(b.len(), 2);
    assert_eq!(b[0], b[1]);
}
