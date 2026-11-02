//! Classifier conformance (SPEC §8B) against the SHARED fixtures in
//! examples/judge/. Mirrors golang/classifier_test.go and
//! classifier_batch_test.go. Hermetic: static/custom backends and a loopback
//! fake for the systemone wire.

mod common;

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use common::judge::*;
use common::*;
use serde_json::{Value, json};
use toolnexus::classifier::*;
use toolnexus::client::{ClientOptions, ErrorTier, MetricEvent, create_client};

fn noul_q() -> Questions {
    let mut q = Questions::new();
    q.insert(
        "q".into(),
        NoulQuestion {
            instructions: "?".into(),
            criteria: None,
        }
        .into(),
    );
    q
}

/// A `static` classifier answering this fixture's own request.
fn static_from(f: &Value, mut opts: ClassifierOptions) -> (Classifier, Questions) {
    let qs = questions_of(&f["request"]["questions"]);
    opts.style = ClassifierStyle::Static;
    opts.model = f["request"]["model"].as_str().unwrap().into();
    opts.decisions = vec![RecordedDecision {
        state: f["request"]["state"].clone(),
        questions: qs.clone(),
        response: serde_json::to_vec(&f["response"]).unwrap(),
    }];
    (create_classifier(opts).unwrap(), qs)
}

fn custom(f: impl Fn(Value) -> Result<Decision, String> + Send + Sync + 'static) -> Classifier {
    let f = Arc::new(f);
    create_classifier(ClassifierOptions {
        style: ClassifierStyle::Custom,
        evaluate: Some(Arc::new(move |_ctx, state, _qs| {
            let f = f.clone();
            Box::pin(async move { f(state) })
        })),
        ..Default::default()
    })
    .unwrap()
}

// ---------------------------------------------------------------- the bytes

#[test]
fn canonical_bytes_match_every_fixture() {
    for name in [
        "base",
        "hardened",
        "numbers",
        "wide",
        "degenerate",
        "near-uniform",
    ] {
        let f = load(&format!("{name}.json"));
        let got = canonical_request(
            f["request"]["model"].as_str().unwrap(),
            &questions_of(&f["request"]["questions"]),
        );
        assert_eq!(got, f["canonical"].as_str().unwrap(), "{name}: bytes");
        assert_eq!(
            got.len() as u64,
            f["canonicalBytes"].as_u64().unwrap(),
            "{name}: len"
        );
        assert_eq!(
            sha256_hex(got.as_bytes()),
            f["canonicalSha256"].as_str().unwrap(),
            "{name}: sha256"
        );
    }
}

#[test]
fn decisions_fixture_bytes_share_one_hash() {
    let f = load("decisions.json");
    let entries = f["entries"].as_array().unwrap();
    assert_eq!(entries.len(), 3);
    for (i, e) in entries.iter().enumerate() {
        let got = canonical_request(
            e["request"]["model"].as_str().unwrap(),
            &questions_of(&e["request"]["questions"]),
        );
        assert_eq!(got, e["canonical"].as_str().unwrap(), "entry {i}");
        assert_eq!(
            sha256_hex(got.as_bytes()),
            e["canonicalSha256"].as_str().unwrap()
        );
    }
}

#[test]
fn absent_criteria_is_not_empty_criteria() {
    let mut a = Questions::new();
    a.insert(
        "q".into(),
        NoulQuestion {
            instructions: "i".into(),
            criteria: None,
        }
        .into(),
    );
    let mut e = Questions::new();
    e.insert(
        "q".into(),
        NoulQuestion {
            instructions: "i".into(),
            criteria: Some(NoulCriteria::default()),
        }
        .into(),
    );
    let absent = canonical_request("m", &a);
    let empty = canonical_request("m", &e);
    assert!(!absent.contains("criteria"), "{absent}");
    assert!(
        empty.contains(r#""criteria":{"false":"","true":""}"#),
        "{empty}"
    );
}

#[test]
fn canonical_json_uses_go_escaping_and_numbers() {
    let v = json!({"b": "<a&b>\u{2028}\u{8}", "a": [1e21, 1.6716e-5, 0, -0.0, 1.5]});
    assert_eq!(
        canonical_json(&v),
        "{\"a\":[1e+21,0.000016716,0,-0,1.5],\"b\":\"<a&b>\\u2028\\u0008\"}"
    );
    assert_eq!(canonical_json(&json!(1.5e-7)), "1.5e-7");
}

// ---------------------------------------------------------------- the parse

#[tokio::test]
async fn base_parse() {
    let f = load("base.json");
    let (c, qs) = static_from(&f, Default::default());
    let d = c
        .evaluate(f["request"]["state"].clone(), &qs)
        .await
        .unwrap();
    assert!(d.calibrated);
    assert_eq!(d.model, "typesafe/jev-1.13-20260917");
    let ch = d.choice("department").unwrap();
    assert_eq!(ch.choice, "shipping");
    assert_eq!(ch.confidence, 0.41);
    assert!(!ch.near_uniform);
    assert_eq!(
        ch.probabilities.get("technical"),
        Some(&0.0),
        "a zero probability must stay an entry"
    );
    assert_eq!(d.noul("is_refund_request").unwrap().noul, 0.98);
    let s = d.score("urgency").unwrap();
    assert_eq!((s.score, s.confidence), (1.21, 0.57));
    let lv = s.levels();
    assert_eq!(lv.len(), 3);
    assert_eq!((lv[0].as_str(), lv[2].as_str()), ("routine", "urgent"));
    assert!(d.noul("department").is_err());
    assert!(d.choice("nope").is_err());
}

#[tokio::test]
async fn numbers_parse_numerically() {
    let f = load("numbers.json");
    let (c, qs) = static_from(&f, Default::default());
    let d = c
        .evaluate(f["request"]["state"].clone(), &qs)
        .await
        .unwrap();
    let s = d.score("urgency").unwrap();
    assert_eq!(s.score, 1.21);
    assert_eq!(s.probabilities["0"], 0.04);
    assert_eq!(d.noul("is_expensive").unwrap().noul, 0.0);
    assert_eq!(d.usage.cost, Some(1.6716e-05));
}

#[tokio::test]
async fn wide_forty_keys() {
    let f = load("wide.json");
    let (c, qs) = static_from(&f, Default::default());
    let d = c
        .evaluate(f["request"]["state"].clone(), &qs)
        .await
        .unwrap();
    let ch = d.choice("skill").unwrap();
    assert_eq!(ch.choice, "skill_07");
    assert_eq!(ch.probabilities.len(), 40);
    assert!(!ch.near_uniform);
}

// ---------------------------------------------------------------- nearUniform

#[tokio::test]
async fn near_uniform_fixture() {
    let f = load("near-uniform.json");
    assert_eq!(
        f["expect"]["tolerance"].as_f64().unwrap(),
        NEAR_UNIFORM_TOLERANCE
    );
    let (c, qs) = static_from(&f, Default::default());
    let d = c
        .evaluate(f["request"]["state"].clone(), &qs)
        .await
        .unwrap();
    for (key, want) in f["expect"]["answers"].as_object().unwrap() {
        let ch = d.choice(key).unwrap();
        assert_eq!(
            ch.near_uniform,
            want["nearUniform"].as_bool().unwrap(),
            "{key}"
        );
        let target = 1.0 / ch.probabilities.len() as f64;
        let max = ch
            .probabilities
            .values()
            .map(|p| (p - target).abs())
            .fold(0.0, f64::max);
        assert!(
            (max - want["maxDeviation"].as_f64().unwrap()).abs() <= 1e-9,
            "{key}: {max}"
        );
    }
}

#[test]
fn near_uniform_boundary() {
    let m = |p: &[(&str, f64)]| -> BTreeMap<String, f64> {
        p.iter().map(|(k, v)| (k.to_string(), *v)).collect()
    };
    assert!(near_uniform(&m(&[("a", 0.5499), ("b", 0.4501)])));
    assert!(!near_uniform(&m(&[("a", 0.5501), ("b", 0.4499)])));
    assert!(near_uniform(&m(&[("only", 1.0)])));
    assert!(!near_uniform(&m(&[])));
}

// ---------------------------------------------------------------- degenerate

#[tokio::test]
async fn degenerate_criteria_warn_once_per_key_and_change_nothing() {
    let f = load("degenerate.json");
    let qs = questions_of(&f["request"]["questions"]);
    let warned = Arc::new(Mutex::new(Vec::<MetricEvent>::new()));
    let w2 = warned.clone();
    let model = f["request"]["model"].as_str().unwrap().to_owned();
    let m2 = model.clone();
    let c = create_classifier(ClassifierOptions {
        style: ClassifierStyle::Custom,
        model: model.clone(),
        evaluate: Some(Arc::new(move |_, _, _| {
            let m = m2.clone();
            Box::pin(async move {
                Ok(Decision {
                    model: m,
                    ..Default::default()
                })
            })
        })),
        on_metric: Some(Arc::new(move |ev: &MetricEvent| {
            if ev.event == METRIC_CLASSIFIER_WARNING {
                w2.lock().unwrap().push(ev.clone());
            }
        })),
        ..Default::default()
    })
    .unwrap();
    for _ in 0..2 {
        c.evaluate(f["request"]["state"].clone(), &qs)
            .await
            .unwrap();
    }
    let evs = warned.lock().unwrap().clone();
    for ev in &evs {
        assert!(ev.error.is_empty(), "a warning is not a failure");
        assert!(ev.warning.contains(&ev.question), "{}", ev.warning);
    }
    let mut keys: Vec<String> = evs.iter().map(|e| e.question.clone()).collect();
    keys.sort();
    let want: Vec<String> = f["expect"]["warnings"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_str().unwrap().into())
        .collect();
    assert_eq!(keys, want);
    for k in f["expect"]["noWarning"].as_array().unwrap() {
        assert!(!keys.contains(&k.as_str().unwrap().to_string()));
    }
    assert_eq!(
        sha256_hex(canonical_request(&model, &qs).as_bytes()),
        f["canonicalSha256"].as_str().unwrap()
    );
}

#[test]
fn degenerate_predicate() {
    let m = |p: &[(&str, &str)]| -> BTreeMap<String, String> {
        p.iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    };
    assert!(degenerate_criteria(&m(&[("a", ""), ("b", "")])).is_some());
    assert!(degenerate_criteria(&m(&[("a", "a"), ("b", "b")])).is_some());
    assert!(degenerate_criteria(&m(&[("a", "same"), ("b", "same")])).is_some());
    assert!(degenerate_criteria(&m(&[("a", "one thing"), ("b", "another")])).is_none());
    assert!(degenerate_criteria(&m(&[("a", ""), ("b", "described")])).is_none());
    assert!(degenerate_criteria(&m(&[("only", "only")])).is_none());
}

// ---------------------------------------------------------------- static backend

#[tokio::test]
async fn static_bands_key_on_state() {
    let f = load("decisions.json");
    let entries = f["entries"].as_array().unwrap();
    let recorded: Vec<RecordedDecision> = entries
        .iter()
        .map(|e| RecordedDecision {
            state: e["request"]["state"].clone(),
            questions: questions_of(&e["request"]["questions"]),
            response: serde_json::to_vec(&e["response"]).unwrap(),
        })
        .collect();
    let c = create_classifier(ClassifierOptions {
        style: ClassifierStyle::Static,
        model: entries[0]["request"]["model"].as_str().unwrap().into(),
        decisions: recorded,
        ..Default::default()
    })
    .unwrap();
    for (i, want) in [0.02, 2.25, 2.97].iter().enumerate() {
        let e = &entries[i];
        let d = c
            .evaluate(
                e["request"]["state"].clone(),
                &questions_of(&e["request"]["questions"]),
            )
            .await
            .unwrap();
        assert_eq!(d.score("risk").unwrap().score, *want, "band {i}");
    }
    assert!(
        c.evaluate(
            json!({"command": "unseen"}),
            &questions_of(&entries[0]["request"]["questions"])
        )
        .await
        .is_err()
    );
}

// ---------------------------------------------------------------- limits

#[tokio::test]
async fn limits_are_rejected_preflight() {
    let calls = Arc::new(AtomicUsize::new(0));
    let c2 = calls.clone();
    let srv = serve(move |_| {
        c2.fetch_add(1, Ordering::SeqCst);
        async { Resp::status(500, "no request should have been sent") }
    })
    .await;
    let too_many: BTreeMap<String, String> = (0..=MAX_CHOICE_OPTIONS)
        .map(|i| (format!("opt_{i:03}"), format!("description {i}")))
        .collect();
    let cases: Vec<(Question, &str)> = vec![
        (
            ChoiceQuestion {
                instructions: "?".into(),
                criteria: too_many,
            }
            .into(),
            "1..255 options",
        ),
        (
            ChoiceQuestion {
                instructions: "?".into(),
                criteria: BTreeMap::new(),
            }
            .into(),
            "1..255 options",
        ),
        (
            ScoreQuestion {
                instructions: "?".into(),
                criteria: vec!["only".into()],
            }
            .into(),
            "2..10 ordered levels",
        ),
        (
            ScoreQuestion {
                instructions: "?".into(),
                criteria: vec![String::new(); 11],
            }
            .into(),
            "2..10 ordered levels",
        ),
    ];
    for (q, want) in cases {
        let c = create_classifier(ClassifierOptions {
            base_url: srv.url.clone(),
            api_key_env: "TEST_JUDGE_UNSET".into(),
            ..Default::default()
        })
        .unwrap();
        let mut qs = Questions::new();
        qs.insert("the_key".into(), q);
        let e = c.evaluate(json!("s"), &qs).await.unwrap_err().0;
        assert!(e.contains("the_key") && e.contains(want), "{e}");
    }
    assert_eq!(calls.load(Ordering::SeqCst), 0);
}

// ---------------------------------------------------------------- secrets

#[tokio::test]
async fn never_leaks_credentials() {
    const KEY: &str = "sk-live-NEVER-IN-AN-ERROR";
    const HDR: &str = "tenant-NEVER-IN-AN-ERROR";
    // SAFETY: test-local env var names, set before any request reads them.
    unsafe {
        std::env::set_var("TEST_RS_JUDGE_KEY", KEY);
        std::env::set_var("TEST_RS_JUDGE_TENANT", HDR);
    }
    let seen = Arc::new(Mutex::new((String::new(), String::new())));
    let s2 = seen.clone();
    let srv = serve(move |r: Req| {
        let auth = r.headers.get("authorization").cloned().unwrap_or_default();
        let ten = r.headers.get("x-tenant").cloned().unwrap_or_default();
        *s2.lock().unwrap() = (auth.clone(), ten.clone());
        async move {
            Resp::status(
                401,
                &format!(r#"{{"error":"bad credential {auth} for {ten}"}}"#),
            )
        }
    })
    .await;
    let events = Arc::new(Mutex::new(Vec::<MetricEvent>::new()));
    let e2 = events.clone();
    let mut headers = BTreeMap::new();
    headers.insert("X-Tenant".into(), "${TEST_RS_JUDGE_TENANT}".into());
    let c = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        api_key_env: "TEST_RS_JUDGE_KEY".into(),
        headers,
        retries: 1,
        on_metric: Some(Arc::new(move |ev: &MetricEvent| {
            e2.lock().unwrap().push(ev.clone())
        })),
        ..Default::default()
    })
    .unwrap();
    let e = c.evaluate(json!("s"), &noul_q()).await.unwrap_err().0;
    let (auth, ten) = seen.lock().unwrap().clone();
    assert_eq!(auth, format!("Bearer {KEY}"));
    assert_eq!(ten, HDR);
    let mut hay = vec![e.clone()];
    for ev in events.lock().unwrap().iter() {
        hay.push(format!(
            "{}{}{}{}",
            ev.error, ev.model, ev.question, ev.status
        ));
    }
    for s in &hay {
        assert!(!s.contains("NEVER-IN-AN-ERROR"), "leaked: {s}");
    }
    assert!(e.contains("401") && e.contains(&srv.url), "{e}");
}

async fn attempts_for(status: u16, extra: Vec<u16>) -> (usize, bool) {
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let srv = serve(move |_| {
        let first = n2.fetch_add(1, Ordering::SeqCst) == 0;
        async move {
            if first {
                Resp::status(status, "nope")
            } else {
                Resp::json(
                    json!({"model":"m","answers":{"q":{"type":"noul","noul":0.5}},"usage":{"input_tokens":1,"output_tokens":1}}),
                )
            }
        }
    })
    .await;
    let c = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        api_key_env: "TEST_JUDGE_UNSET".into(),
        retries: 2,
        retry_base_ms: 1,
        retryable_statuses: extra,
        ..Default::default()
    })
    .unwrap();
    let ok = c.evaluate(json!("s"), &noul_q()).await.is_ok();
    (n.load(Ordering::SeqCst), ok)
}

#[tokio::test]
async fn retryable_statuses_are_an_additive_enumeration() {
    let cf: Vec<u16> = (520..=527).collect();
    for (extra, status, want) in [
        (vec![], 529, 2),
        (vec![], 520, 1),
        (vec![], 501, 1),
        (cf.clone(), 520, 2),
        (cf.clone(), 429, 2),
        (cf.clone(), 501, 1),
        (vec![], 422, 1),
        (vec![], 408, 2),
    ] {
        let (attempts, ok) = attempts_for(status, extra).await;
        assert_eq!(attempts, want, "status {status}");
        assert_eq!(ok, want > 1, "status {status}");
    }
}

#[tokio::test]
async fn on_error_overrides_retryable_statuses() {
    let n = Arc::new(AtomicUsize::new(0));
    let n2 = n.clone();
    let srv = serve(move |_| {
        n2.fetch_add(1, Ordering::SeqCst);
        async { Resp::status(520, "origin error") }
    })
    .await;
    let c = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        api_key_env: "TEST_JUDGE_UNSET".into(),
        retries: 3,
        retryable_statuses: vec![520],
        on_error: Some(Arc::new(|_| ErrorTier::Fail)),
        ..Default::default()
    })
    .unwrap();
    assert!(c.evaluate(json!("s"), &noul_q()).await.is_err());
    assert_eq!(n.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn absent_cost_is_not_zero() {
    let srv = serve(|_| async {
        Resp::json(
            json!({"model":"jev-1.13.0","answers":{"q":{"type":"noul","noul":0.98}},"usage":{"input_tokens":331,"output_tokens":48}}),
        )
    })
    .await;
    let c = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        model: "jev-latest".into(),
        api_key_env: "TEST_JUDGE_UNSET".into(),
        ..Default::default()
    })
    .unwrap();
    let d = c.evaluate(json!("s"), &noul_q()).await.unwrap();
    assert_eq!(d.usage.input_tokens, 331);
    assert_eq!(d.usage.cost, None);
}

#[tokio::test]
async fn surfaces_backend_cause() {
    let srv = serve(|_| async {
        Resp::status(
            400,
            r#"{"error":"Too many choices. Must have at most 255 choices."}"#,
        )
    })
    .await;
    let c = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        api_key_env: "TEST_JUDGE_UNSET".into(),
        ..Default::default()
    })
    .unwrap();
    let e = c.evaluate(json!("s"), &noul_q()).await.unwrap_err().0;
    assert!(e.contains("Too many choices"), "{e}");
}

#[tokio::test]
async fn systemone_posts_the_canonical_body() {
    let body = Arc::new(Mutex::new(String::new()));
    let b2 = body.clone();
    let srv = serve(move |r: Req| {
        *b2.lock().unwrap() = r.raw.clone();
        let path = r.path.clone();
        async move {
            assert_eq!(path, "/systemone");
            Resp::json(json!({"model":"m","answers":{}}))
        }
    })
    .await;
    let mut params = serde_json::Map::new();
    params.insert("model".into(), json!("override"));
    let c = create_classifier(ClassifierOptions {
        base_url: format!("{}/", srv.url),
        api_key_env: "TEST_JUDGE_UNSET".into(),
        request_params: params,
        ..Default::default()
    })
    .unwrap();
    c.evaluate(json!({"z": 1, "a": "<x>"}), &noul_q())
        .await
        .unwrap();
    assert_eq!(
        *body.lock().unwrap(),
        r#"{"model":"override","questions":{"q":{"instructions":"?","type":"noul"}},"state":{"a":"<x>","z":1}}"#
    );
}

// ---------------------------------------------------------------- non-breaking

#[tokio::test]
async fn absence_is_byte_identical() {
    async fn capture() -> String {
        let body = Arc::new(Mutex::new(String::new()));
        let b2 = body.clone();
        let srv = serve(move |r: Req| {
            *b2.lock().unwrap() = r.raw.clone();
            async { Resp::json(json!({"choices":[{"message":{"content":"done"}}],"usage":{}})) }
        })
        .await;
        let tk = empty_toolkit().await;
        create_client(ClientOptions {
            base_url: srv.url.clone(),
            model: "m".into(),
            api_key: "k".into(),
            ..Default::default()
        })
        .run("hello", &tk)
        .await
        .unwrap();
        body.lock().unwrap().clone()
    }
    let without = capture().await;
    let c = custom(|_| {
        Ok(Decision {
            model: "m".into(),
            ..Default::default()
        })
    });
    c.evaluate(json!("s"), &noul_q()).await.unwrap();
    assert_eq!(without, capture().await);
}

// ---------------------------------------------------------------- construction

#[test]
fn create_rejects_incomplete_styles() {
    assert!(
        create_classifier(ClassifierOptions {
            style: ClassifierStyle::Llm,
            ..Default::default()
        })
        .is_err()
    );
    assert!(
        create_classifier(ClassifierOptions {
            style: ClassifierStyle::Custom,
            ..Default::default()
        })
        .is_err()
    );
    assert!(ClassifierStyle::parse("nonsense").is_err());
    let c = create_classifier(ClassifierOptions::default()).unwrap();
    let o = c.options();
    assert_eq!(o.style, ClassifierStyle::SystemOne);
    assert_eq!(o.model, DEFAULT_CLASSIFIER_MODEL);
    assert_eq!(o.base_url, DEFAULT_CLASSIFIER_BASE_URL);
    assert_eq!(o.api_key_env, DEFAULT_CLASSIFIER_API_KEY_ENV);
    assert_eq!(o.timeout_ms, DEFAULT_CLASSIFIER_TIMEOUT_MS);
}

#[test]
fn backend_presets_and_pairing_check() {
    let c = create_classifier(ClassifierOptions {
        backend: ClassifierBackend::OpenRouter,
        ..Default::default()
    })
    .unwrap();
    assert_eq!(c.options().base_url, OPENROUTER_CLASSIFIER_BASE_URL);
    assert_eq!(c.options().model, OPENROUTER_CLASSIFIER_MODEL);
    assert_eq!(c.options().api_key_env, OPENROUTER_CLASSIFIER_API_KEY_ENV);
    let e = create_classifier(ClassifierOptions {
        base_url: OPENROUTER_CLASSIFIER_BASE_URL.into(),
        ..Default::default()
    })
    .unwrap_err()
    .0;
    assert!(e.contains("typesafe/jev-1.13"), "{e}");
}

#[tokio::test]
async fn retry_base_ms_default_and_short() {
    async fn run(base_ms: u64) -> (Duration, usize) {
        let n = Arc::new(AtomicUsize::new(0));
        let n2 = n.clone();
        let srv = serve(move |_| {
            let first = n2.fetch_add(1, Ordering::SeqCst) == 0;
            async move {
                if first {
                    Resp::status(503, "transient")
                } else {
                    Resp::json(
                        json!({"model":"m","answers":{"q":{"type":"noul","noul":0.5}},"usage":{"input_tokens":1,"output_tokens":1}}),
                    )
                }
            }
        })
        .await;
        let c = create_classifier(ClassifierOptions {
            base_url: srv.url.clone(),
            api_key_env: "TEST_JUDGE_UNSET".into(),
            retries: 2,
            retry_base_ms: base_ms,
            ..Default::default()
        })
        .unwrap();
        let t0 = Instant::now();
        c.evaluate(json!("s"), &noul_q()).await.unwrap();
        (t0.elapsed(), n.load(Ordering::SeqCst))
    }
    let (d, a) = run(0).await;
    assert_eq!(a, 2);
    assert!(d >= Duration::from_millis(450), "waited {d:?}");
    let (d, a) = run(1).await;
    assert_eq!(a, 2);
    assert!(d <= Duration::from_millis(200), "waited {d:?}");
}

#[tokio::test]
async fn absent_calibrated_decodes_as_true() {
    for (body, want) in [
        (r#"{"model":"m","answers":{}}"#, true),
        (r#"{"model":"m","answers":{},"calibrated":null}"#, true),
        (r#"{"model":"m","answers":{},"calibrated":true}"#, true),
        (r#"{"model":"m","answers":{},"calibrated":false}"#, false),
    ] {
        let srv =
            serve(move |_| async move { Resp::json(serde_json::from_str(body).unwrap()) }).await;
        let c = create_classifier(ClassifierOptions {
            base_url: srv.url.clone(),
            api_key_env: "TEST_JUDGE_UNSET".into(),
            ..Default::default()
        })
        .unwrap();
        assert_eq!(
            c.evaluate(json!("s"), &noul_q()).await.unwrap().calibrated,
            want,
            "{body}"
        );
    }
}

#[tokio::test]
async fn llm_style_reports_uncalibrated() {
    let srv = serve(|_| async {
        Resp::json(oa_text(
            "```json\n{\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.9}}}\n```",
        ))
    })
    .await;
    let client = create_client(ClientOptions {
        base_url: srv.url.clone(),
        model: "m".into(),
        api_key: "k".into(),
        ..Default::default()
    });
    let c = create_classifier(ClassifierOptions {
        style: ClassifierStyle::Llm,
        client: Some(client),
        ..Default::default()
    })
    .unwrap();
    let d = c.evaluate(json!("s"), &noul_q()).await.unwrap();
    assert!(!d.calibrated);
    assert_eq!(d.model, DEFAULT_CLASSIFIER_MODEL);
    assert_eq!(d.noul("q").unwrap().noul, 0.9);
}

// ---------------------------------------------------------------- batch

#[tokio::test]
async fn batch_preserves_order() {
    let c = custom(|st| {
        let mut d = Decision {
            model: "test".into(),
            ..Default::default()
        };
        d.answers.insert(
            "score".into(),
            DecisionAnswer::Noul(NoulAnswer {
                noul: st.as_f64().unwrap(),
            }),
        );
        Ok(d)
    });
    let states: Vec<Value> = (0..50).map(|i| json!(i)).collect();
    let ds = c.evaluate_batch(states, &noul_q()).await.unwrap();
    assert_eq!(ds.len(), 50);
    for (i, d) in ds.iter().enumerate() {
        assert_eq!(d.noul("score").unwrap().noul as usize, i);
    }
}

#[tokio::test]
async fn batch_fails_closed_lowest_index_wins() {
    let c = create_classifier(ClassifierOptions {
        style: ClassifierStyle::Custom,
        evaluate: Some(Arc::new(|_, st, _| {
            Box::pin(async move {
                match st.as_i64() {
                    Some(0) => {
                        tokio::time::sleep(Duration::from_millis(20)).await;
                        Err("boom 0".to_string())
                    }
                    Some(2) | Some(3) => Err("boom".into()),
                    _ => Ok(Decision::default()),
                }
            })
        })),
        ..Default::default()
    })
    .unwrap();
    let e = c
        .evaluate_batch((0..10).map(|i| json!(i)).collect(), &noul_q())
        .await
        .unwrap_err()
        .0;
    assert!(e.starts_with("state 0:"), "{e}");
    let e = c
        .evaluate_batch((1..10).map(|i| json!(i)).collect(), &noul_q())
        .await
        .unwrap_err()
        .0;
    assert!(e.starts_with("state 1: boom"), "{e}");
}

#[tokio::test]
async fn batch_empty_states_and_bounded_concurrency() {
    let calls = Arc::new(AtomicUsize::new(0));
    let in_flight = Arc::new(AtomicUsize::new(0));
    let max = Arc::new(AtomicUsize::new(0));
    let (c2, i2, m2) = (calls.clone(), in_flight.clone(), max.clone());
    let c = create_classifier(ClassifierOptions {
        style: ClassifierStyle::Custom,
        evaluate: Some(Arc::new(move |_, _, _| {
            let (c, i, m) = (c2.clone(), i2.clone(), m2.clone());
            Box::pin(async move {
                c.fetch_add(1, Ordering::SeqCst);
                let n = i.fetch_add(1, Ordering::SeqCst) + 1;
                m.fetch_max(n, Ordering::SeqCst);
                tokio::time::sleep(Duration::from_millis(10)).await;
                i.fetch_sub(1, Ordering::SeqCst);
                Ok(Decision::default())
            })
        })),
        ..Default::default()
    })
    .unwrap();
    assert!(c.evaluate_batch(vec![], &noul_q()).await.is_err());
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    let states = (0..DEFAULT_CLASSIFIER_BATCH_CONCURRENCY * 4)
        .map(|i| json!(i))
        .collect();
    c.evaluate_batch(states, &noul_q()).await.unwrap();
    let m = max.load(Ordering::SeqCst);
    assert!(
        m <= DEFAULT_CLASSIFIER_BATCH_CONCURRENCY && m > 1,
        "max in flight {m}"
    );
}

#[test]
fn json_eq_is_numeric() {
    assert!(json_eq(&json!({"a": [0]}), &json!({"a": [0.0]})));
}
