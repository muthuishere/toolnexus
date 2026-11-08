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
#[allow(deprecated)] // style Llm is deprecated but still supported
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
    assert!(e.contains("typesafe/jev-1.14"), "{e}");
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
#[allow(deprecated)] // style Llm is deprecated but still supported
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

// ------------------------------------------------ backends, images, cache, V1
// SPEC §8B "Backends, images, cache and validation" against the shared
// fixtures in examples/judge/backends/ (every file is exercised). Mirrors
// golang/classifier_backends_test.go. Hermetic: a loopback fake endpoint.

use toolnexus::ContentPart;
use toolnexus::judge::{
    BAND_UNCERTAIN, Bands, DEFAULT_BANDS, DEFAULT_THRESHOLDS, Thresholds, questions,
};

fn backend_fixture(name: &str) -> Value {
    load(&format!("backends/{name}"))
}

/// `{"$image":{mimeType,data}}` / `{"$text":s}` markers -> §1B content parts.
fn fixture_state(v: &Value) -> Value {
    match v {
        Value::Object(m) => {
            if let Some(im) = m.get("$image") {
                return serde_json::to_value(ContentPart {
                    kind: "image".into(),
                    mime_type: im["mimeType"].as_str().unwrap().into(),
                    data: im["data"].as_str().unwrap().into(),
                    ..Default::default()
                })
                .unwrap();
            }
            if let Some(t) = m.get("$text") {
                return serde_json::to_value(ContentPart::text(t.as_str().unwrap())).unwrap();
            }
            Value::Object(
                m.iter()
                    .map(|(k, e)| (k.clone(), fixture_state(e)))
                    .collect(),
            )
        }
        Value::Array(a) => Value::Array(a.iter().map(fixture_state).collect()),
        _ => v.clone(),
    }
}

const NOUL_REPLY: &str = r#"{"model":"jev-1.14.0","answers":{"q":{"type":"noul","noul":0.91}},"usage":{"input_tokens":3,"output_tokens":1}}"#;

/// A loopback System One endpoint recording every request.
async fn fake(
    reply: impl Fn(usize, &Req) -> Resp + Send + Sync + 'static,
) -> (Server, Arc<Mutex<Vec<Req>>>) {
    let seen: Arc<Mutex<Vec<Req>>> = Arc::default();
    let s2 = seen.clone();
    let reply = Arc::new(reply);
    let srv = serve(move |r: Req| {
        let n = {
            let mut g = s2.lock().unwrap();
            g.push(r.clone());
            g.len()
        };
        let out = reply(n, &r);
        async move { out }
    })
    .await;
    (srv, seen)
}

fn ok(body: &str) -> Resp {
    Resp::status(200, body)
}

#[tokio::test]
async fn backend_presets_fixture() {
    let fx = backend_fixture("presets.json");
    let rows = fx["backends"].as_object().unwrap();
    assert_eq!(CLASSIFIER_BACKENDS.len(), rows.len());
    for (name, want) in rows {
        let b = ClassifierBackend::parse(name).unwrap();
        assert_eq!(b.as_str(), name);
        let p = b.preset();
        assert_eq!(p.backend, b);
        assert_eq!(p.base_url, want["baseUrl"].as_str().unwrap_or(""), "{name}");
        assert_eq!(p.model, want["model"].as_str().unwrap(), "{name}");
        assert_eq!(p.api_key_env, want["apiKeyEnv"].as_str().unwrap(), "{name}");
        assert_eq!(p.wire.as_str(), want["wire"].as_str().unwrap(), "{name}");
        assert_eq!(p.images_header, want["imagesHeader"].as_bool().unwrap());
        match want["supportsImages"].as_object() {
            Some(m) => {
                for (model, img) in m {
                    assert_eq!(
                        (p.supports.unwrap())(model).images,
                        img.as_bool().unwrap(),
                        "{name} {model}"
                    );
                }
            }
            None => assert!(p.supports.is_none(), "{name}"),
        }
    }
    assert_eq!(
        ClassifierBackend::parse("nope").unwrap_err().0,
        fx["unknownBackendError"].as_str().unwrap()
    );
    let e = create_classifier(ClassifierOptions {
        backend: ClassifierBackend::Custom,
        base_url: "http://x".into(),
        ..Default::default()
    })
    .unwrap_err();
    assert_eq!(e.0, fx["customError"].as_str().unwrap());
    create_classifier(ClassifierOptions {
        backend: ClassifierBackend::Custom,
        base_url: "http://x".into(),
        supports: Some(ClassifierSupports::default()),
        ..Default::default()
    })
    .unwrap();
    // The custom requirement is a wire rule: a non-wire style never checks it.
    create_classifier(ClassifierOptions {
        backend: ClassifierBackend::Custom,
        style: ClassifierStyle::Static,
        ..Default::default()
    })
    .unwrap();
}

#[tokio::test]
async fn images_fixture() {
    let fx = backend_fixture("images.json");
    let qs = questions_of(&fx["questions"]);
    unsafe { std::env::set_var("TN_RS_IMG_ACCOUNT", "acct123") };
    for c in fx["cases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let backend = ClassifierBackend::parse(c["backend"].as_str().unwrap()).unwrap();
        let cf = backend == ClassifierBackend::Cloudflare;
        let (srv, seen) = fake(move |_, _| {
            if cf {
                ok(&format!(r#"{{"success":true,"result":{NOUL_REPLY}}}"#))
            } else {
                ok(NOUL_REPLY)
            }
        })
        .await;
        let ex = &c["expect"];
        // Point the preset's host at the fake, keeping its path and template.
        let base = if cf {
            if let Some(u) = ex["url"].as_str() {
                assert_eq!(u, backend.preset().base_url, "{name}");
            }
            format!("{}/accounts/${{TN_RS_IMG_ACCOUNT}}/ai/run", srv.url)
        } else {
            format!("{}/v1", srv.url)
        };
        let cl = create_classifier(ClassifierOptions {
            backend,
            base_url: base,
            model: c["model"].as_str().unwrap_or("").into(),
            supports: c
                .get("supports")
                .map(|s| serde_json::from_value(s.clone()).unwrap()),
            retries: -1,
            ..Default::default()
        })
        .unwrap();
        let res = cl.evaluate(fixture_state(&c["state"]), &qs).await;
        if ex["unsupported"].as_bool() == Some(true) {
            let e = res.unwrap_err();
            let u = e.unsupported().unwrap_or_else(|| panic!("{name}: {e}"));
            assert_eq!(u.feature, "images");
            assert_eq!(e.0, ex["error"].as_str().unwrap(), "{name}");
            assert_eq!(u.to_string(), e.0);
        } else if let Some(p) = ex["errorPrefix"].as_str() {
            let e = res.unwrap_err();
            assert!(e.0.starts_with(p), "{name}: {e}");
            assert!(e.unsupported().is_none(), "{name}");
        } else {
            res.unwrap_or_else(|e| panic!("{name}: {e}"));
            let g = seen.lock().unwrap();
            assert_eq!(g[0].raw, ex["body"].as_str().unwrap(), "{name}");
            assert_eq!(
                g[0].headers
                    .get("typesafe-danger-zone-images")
                    .map(String::as_str)
                    == Some("true"),
                ex["imagesHeader"].as_bool().unwrap(),
                "{name}"
            );
            if cf {
                assert_eq!(g[0].path, "/accounts/acct123/ai/run", "{name}");
            } else {
                assert_eq!(g[0].path, "/v1/systemone", "{name}");
            }
            continue;
        }
        assert!(
            seen.lock().unwrap().is_empty(),
            "{name}: a request was sent"
        );
    }
}

#[tokio::test]
async fn host_header_wins_over_images_header() {
    let (srv, seen) = fake(|_, _| ok(NOUL_REPLY)).await;
    let cl = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        headers: [(
            "typesafe-danger-zone-images".to_string(),
            "false".to_string(),
        )]
        .into(),
        ..Default::default()
    })
    .unwrap();
    let fx = backend_fixture("images.json");
    let state = fixture_state(&fx["cases"][1]["state"]);
    cl.evaluate(state, &questions_of(&fx["questions"]))
        .await
        .unwrap();
    let g = seen.lock().unwrap();
    assert_eq!(g[0].headers["typesafe-danger-zone-images"], "false");
}

#[tokio::test]
async fn cloudflare_replies_fixture() {
    let fx = backend_fixture("cloudflare-replies.json");
    let qs = questions_of(&fx["questions"]);
    for c in fx["cases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let reply = c["reply"].to_string();
        let (srv, _) = fake(move |_, _| ok(&reply)).await;
        let cl = create_classifier(ClassifierOptions {
            backend: ClassifierBackend::Cloudflare,
            base_url: srv.url.clone(),
            ..Default::default()
        })
        .unwrap();
        let res = cl.evaluate(json!("x"), &qs).await;
        if let Some(e) = c["expect"]["error"].as_str() {
            assert_eq!(res.unwrap_err().0, e, "{name}");
            continue;
        }
        let d = res.unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(d.model, c["expect"]["model"].as_str().unwrap(), "{name}");
        assert_eq!(
            d.noul("q").unwrap().noul,
            c["expect"]["noul"].as_f64().unwrap()
        );
    }
}

/// SPEC §8B Decisions wire (#157): request bytes, reply normalisation,
/// preflight errors and key routing for the `openai` backend.
#[tokio::test]
async fn openai_decisions_fixture() {
    let fx = backend_fixture("openai-decisions.json");
    let qs = questions_of(&fx["questions"]);
    let ok_reply = fx["replyCases"][0]["reply"].to_string();

    for c in fx["requestCases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let r = ok_reply.clone();
        let (srv, seen) = fake(move |_, _| ok(&r)).await;
        let cl = create_classifier(ClassifierOptions {
            backend: ClassifierBackend::OpenAi,
            base_url: format!("{}/v1", srv.url),
            model: c["model"].as_str().unwrap_or("").into(),
            retries: -1,
            ..Default::default()
        })
        .unwrap();
        cl.evaluate(fixture_state(&c["state"]), &qs)
            .await
            .unwrap_or_else(|e| panic!("{name}: {e}"));
        let g = seen.lock().unwrap();
        assert_eq!(g[0].raw, c["expect"]["body"].as_str().unwrap(), "{name}");
        assert_eq!(g[0].path, "/v1/decisions", "{name}");
        assert!(
            !g[0].headers.contains_key("typesafe-danger-zone-images"),
            "{name}: danger-zone header sent"
        );
    }

    for c in fx["replyCases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let ex = &c["expect"];
        if let Some(n) = ex["normalized"].as_str() {
            let got = decisions_reply(c["reply"].to_string().as_bytes(), &qs).unwrap();
            assert_eq!(got, n, "{name}");
        }
        let reply = c["reply"].to_string();
        let (srv, _) = fake(move |_, _| ok(&reply)).await;
        let cl = create_classifier(ClassifierOptions {
            backend: ClassifierBackend::OpenAi,
            base_url: srv.url.clone(),
            retries: -1,
            ..Default::default()
        })
        .unwrap();
        let res = cl.evaluate(json!("x"), &qs).await;
        if let Some(e) = ex["error"].as_str() {
            assert_eq!(res.unwrap_err().0, e, "{name}");
            continue;
        }
        let d = res.unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(d.model, ex["model"].as_str().unwrap(), "{name}");
        assert_eq!(d.calibrated, ex["calibrated"].as_bool().unwrap(), "{name}");
        for (k, v) in ex["noul"].as_object().into_iter().flatten() {
            assert_eq!(d.noul(k).unwrap().noul, v.as_f64().unwrap(), "{name} {k}");
        }
        for (k, v) in ex["choice"].as_object().into_iter().flatten() {
            assert_eq!(
                d.choice(k).unwrap().choice,
                v.as_str().unwrap(),
                "{name} {k}"
            );
        }
        for (k, v) in ex["score"].as_object().into_iter().flatten() {
            assert_eq!(d.score(k).unwrap().score, v.as_f64().unwrap(), "{name} {k}");
        }
    }

    for c in fx["errorCases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let r = ok_reply.clone();
        let (srv, seen) = fake(move |_, _| ok(&r)).await;
        let cl = create_classifier(ClassifierOptions {
            backend: ClassifierBackend::OpenAi,
            base_url: srv.url.clone(),
            retries: -1,
            ..Default::default()
        })
        .unwrap();
        let e = cl
            .evaluate(json!("x"), &questions_of(&c["questions"]))
            .await
            .unwrap_err();
        assert_eq!(e.0, c["expect"]["error"].as_str().unwrap(), "{name}");
        assert_eq!(
            e.unsupported().is_some(),
            c["expect"]["unsupported"].as_bool().unwrap_or(false),
            "{name}"
        );
        assert!(
            seen.lock().unwrap().is_empty(),
            "{name}: a request was sent"
        );
    }

    for c in fx["captureCases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let r = ok_reply.clone();
        let (srv, seen) = fake(move |_, _| ok(&r)).await;
        let env: Vec<(String, String)> = c["env"]
            .as_object()
            .map(|m| {
                m.iter()
                    .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_owned()))
                    .collect()
            })
            .unwrap_or_default();
        let guard = ENV_LOCK.lock().await;
        let prior: Vec<(String, Option<String>)> = env
            .iter()
            .map(|(k, _)| (k.clone(), std::env::var(k).ok()))
            .collect();
        for (k, v) in &env {
            // SAFETY: serialised by ENV_LOCK.
            unsafe { std::env::set_var(k, v) };
        }
        let cl = create_classifier(ClassifierOptions {
            backend: ClassifierBackend::OpenAi,
            base_url: format!("{}/v1", srv.url),
            api_key_env: c["apiKeyEnv"].as_str().unwrap_or("").into(),
            retries: -1,
            ..Default::default()
        })
        .unwrap();
        let res = cl.evaluate(json!("x"), &qs).await;
        for (k, v) in prior {
            // SAFETY: as above.
            unsafe {
                match v {
                    Some(v) => std::env::set_var(&k, v),
                    None => std::env::remove_var(&k),
                }
            }
        }
        drop(guard);
        res.unwrap_or_else(|e| panic!("{name}: {e}"));
        let g = seen.lock().unwrap();
        assert_eq!(
            g[0].headers.get("authorization").map(String::as_str),
            c["expectAuthorization"].as_str(),
            "{name}"
        );
    }
}

/// Process env is global and tests run in parallel: serialise the cases that set it.
static ENV_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// #155: a preset's env key is never sent to a host that is not its own; an
/// explicit api_key_env / header still goes where the caller says.
#[tokio::test]
async fn key_routing_fixture() {
    let fx = backend_fixture("key-routing.json");
    let qs = questions_of(&fx["questions"]);
    for c in fx["captureCases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let backend = c["backend"].as_str().unwrap();
        let reply = if backend == "cloudflare" {
            format!(r#"{{"success":true,"result":{NOUL_REPLY}}}"#)
        } else {
            NOUL_REPLY.to_owned()
        };
        let (srv, seen) = fake(move |_, _| ok(&reply)).await;
        let env: Vec<(String, String)> = c["env"]
            .as_object()
            .map(|m| {
                m.iter()
                    .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_owned()))
                    .collect()
            })
            .unwrap_or_default();
        let headers: BTreeMap<String, String> = c["headers"]
            .as_object()
            .map(|m| {
                m.iter()
                    .map(|(k, v)| (k.clone(), v.as_str().unwrap().to_owned()))
                    .collect()
            })
            .unwrap_or_default();
        let guard = ENV_LOCK.lock().await;
        let prior: Vec<(String, Option<String>)> = env
            .iter()
            .map(|(k, _)| (k.clone(), std::env::var(k).ok()))
            .collect();
        for (k, v) in &env {
            // SAFETY: serialised by ENV_LOCK; no other test reads these names.
            unsafe { std::env::set_var(k, v) };
        }
        let cl = create_classifier(ClassifierOptions {
            backend: ClassifierBackend::parse(backend).unwrap(),
            base_url: format!("{}/v1", srv.url),
            api_key_env: c["apiKeyEnv"].as_str().unwrap_or("").into(),
            headers,
            supports: (backend == "custom").then_some(ClassifierSupports { images: false }),
            retries: -1,
            ..Default::default()
        })
        .unwrap();
        let res = cl.evaluate(json!("x"), &qs).await;
        for (k, v) in prior {
            // SAFETY: as above.
            unsafe {
                match v {
                    Some(v) => std::env::set_var(&k, v),
                    None => std::env::remove_var(&k),
                }
            }
        }
        drop(guard);
        res.unwrap_or_else(|e| panic!("{name}: {e}"));
        let g = seen.lock().unwrap();
        assert!(
            !g.is_empty(),
            "{name}: no request reached the capture server"
        );
        for r in g.iter() {
            let got = r.headers.get("authorization").map(String::as_str);
            assert_eq!(got, c["expectAuthorization"].as_str(), "{name}");
        }
    }
    for c in fx["hostCases"].as_array().unwrap() {
        let b = ClassifierBackend::parse(c["backend"].as_str().unwrap()).unwrap();
        let url = c["baseUrl"].as_str().unwrap();
        assert_eq!(
            classifier_key_env(b, url),
            c["expectKeyEnv"].as_str().unwrap(),
            "{url}"
        );
    }
}

#[test]
fn cache_keys_fixture() {
    let fx = backend_fixture("cache-keys.json");
    for c in fx["cases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let ep = c["endpoint"].as_str().unwrap();
        assert_eq!(
            classifier_model_cache_key(ep, c["model"].as_str().unwrap()),
            c["modelKey"].as_str().unwrap(),
            "{name}"
        );
        let dk = classifier_decision_cache_key(
            ep,
            c["served"].as_str().unwrap(),
            &c["state"],
            c.get("assets").and_then(Value::as_object),
            &questions_of(&c["questions"]),
        );
        assert_eq!(dk, c["decisionKey"].as_str().unwrap(), "{name}");
        assert_eq!(
            sha256_hex(c["decisionKeyBytes"].as_str().unwrap().as_bytes()),
            dk,
            "{name}"
        );
    }
}

#[test]
fn validate_cases_fixture() {
    let fx = backend_fixture("validate-cases.json");
    for c in fx["cases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let d = Decision::from_value(&c["response"]).unwrap_or_else(|e| panic!("{name}: {e}"));
        let res = validate_decision(&questions_of(&c["questions"]), &d);
        assert_eq!(
            res.is_ok(),
            c["valid"].as_bool().unwrap(),
            "{name}: {res:?}"
        );
        if let Err(e) = res {
            let key = c["key"].as_str().unwrap();
            assert!(
                e.0.starts_with("classifier: invalid decision: "),
                "{name}: {e}"
            );
            assert!(
                e.0.contains(&format!("\"{key}\"")),
                "{name}: {e} does not name {key}"
            );
        }
    }
}

#[test]
fn thresholds_fixture() {
    let fx = backend_fixture("thresholds-cases.json");
    let def: Thresholds = serde_json::from_value(fx["default"].clone()).unwrap();
    assert_eq!(def, DEFAULT_THRESHOLDS);
    assert_eq!(Thresholds::default(), DEFAULT_THRESHOLDS);
    for c in fx["cases"].as_array().unwrap() {
        let name = c["name"].as_str().unwrap();
        let d = Decision::from_value(&json!({"answers": {"a": c["answer"]}})).unwrap();
        let a = &d.answers["a"];
        let th: Thresholds = c
            .get("thresholds")
            .map(|t| serde_json::from_value(t.clone()).unwrap())
            .unwrap_or(DEFAULT_THRESHOLDS);
        assert_eq!(th.band(a), c["band"].as_str().unwrap(), "{name}");
        assert_eq!(
            DEFAULT_BANDS.band(a),
            c["bandsBand"].as_str().unwrap(),
            "{name}"
        );
    }
    let _: Bands = DEFAULT_BANDS;
    assert_eq!(BAND_UNCERTAIN, "uncertain");
}

fn collect_metrics() -> (Arc<Mutex<Vec<MetricEvent>>>, toolnexus::client::OnMetricFn) {
    let ev: Arc<Mutex<Vec<MetricEvent>>> = Arc::default();
    let e2 = ev.clone();
    (
        ev,
        Arc::new(move |e: &MetricEvent| e2.lock().unwrap().push(e.clone())),
    )
}

#[tokio::test]
async fn state_builder_and_cache() {
    use toolnexus::judge::noul;
    let (srv, seen) = fake(|_, _| ok(NOUL_REPLY)).await;
    let (events, sink) = collect_metrics();
    let cl = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        cache: Some(Arc::new(MemoryCache::new(Duration::from_secs(3600), 100))),
        on_metric: Some(sink),
        ..Default::default()
    })
    .unwrap();
    let q = noul("q", "Is the object red?");
    let d1 = cl
        .state(json!("a red mug"))
        .evaluate(std::slice::from_ref(&q))
        .await
        .unwrap();
    assert!(!d1.cached);
    let d2 = cl
        .state(json!("a red mug"))
        .evaluate(std::slice::from_ref(&q))
        .await
        .unwrap();
    assert!(d2.cached);
    assert_eq!(d2.model, "jev-1.14.0");
    assert_eq!(seen.lock().unwrap().len(), 1);
    {
        let ev = events.lock().unwrap();
        assert!(!ev[0].cached);
        assert!(ev[1].cached);
        assert_eq!(ev[1].questions, 1);
        assert_eq!(ev[1].questions_hash.len(), 64);
    }
    // Different state -> miss.
    cl.state(json!("a blue mug"))
        .evaluate(std::slice::from_ref(&q))
        .await
        .unwrap();
    assert_eq!(seen.lock().unwrap().len(), 2);
    // Duplicate name fails before any request.
    assert!(
        cl.state(json!("x"))
            .evaluate(&[q.clone(), q])
            .await
            .is_err()
    );
    assert_eq!(seen.lock().unwrap().len(), 2);
}

#[tokio::test]
async fn invalid_replies_are_not_cached() {
    use toolnexus::judge::noul;
    let bad = r#"{"model":"m","answers":{"q":{"type":"noul","noul":1.5}}}"#;
    let (srv, seen) = fake(move |_, _| ok(bad)).await;
    let (events, sink) = collect_metrics();
    let cl = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        cache: Some(Arc::new(MemoryCache::new(Duration::ZERO, 0))),
        on_metric: Some(sink),
        ..Default::default()
    })
    .unwrap();
    let qs = questions(&[noul("q", "x")]).unwrap();
    for _ in 0..2 {
        // Plain evaluate: returned as before, never cached.
        cl.evaluate(json!("s"), &qs).await.unwrap();
    }
    assert_eq!(seen.lock().unwrap().len(), 2, "invalid reply was cached");
    let e = cl
        .state(json!("s"))
        .evaluate(&[noul("q", "x")])
        .await
        .unwrap_err();
    assert!(
        e.0.starts_with("classifier: invalid decision: answer \"q\""),
        "{e}"
    );
    let ev = events.lock().unwrap();
    assert_eq!(ev.last().unwrap().status, "error");
    assert_eq!(ev.last().unwrap().error, e.0);
}

#[tokio::test]
async fn validation_applies_to_non_wire_styles_on_builder() {
    use toolnexus::judge::noul;
    let c = custom(|_| Ok(Decision::default()));
    let e = c
        .state(json!("s"))
        .evaluate(&[noul("q", "x")])
        .await
        .unwrap_err();
    assert_eq!(e.0, "classifier: invalid decision: no answer \"q\"");
    // Plain evaluate never fails on V1.
    c.evaluate(json!("s"), &noul_q()).await.unwrap();
}

#[tokio::test]
async fn cache_follows_served_model() {
    use toolnexus::judge::noul;
    let served = Arc::new(Mutex::new("jev-1.13.0".to_string()));
    let s2 = served.clone();
    let (srv, seen) = fake(move |_, _| {
        ok(&format!(
            r#"{{"model":"{}","answers":{{"q":{{"type":"noul","noul":0.5}}}}}}"#,
            s2.lock().unwrap()
        ))
    })
    .await;
    let cache = Arc::new(MemoryCache::new(Duration::ZERO, 0));
    let cl = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        cache: Some(cache.clone()),
        ..Default::default()
    })
    .unwrap();
    cl.state(json!("s"))
        .evaluate(&[noul("q", "x")])
        .await
        .unwrap();
    let mk = classifier_model_cache_key(&srv.url, DEFAULT_CLASSIFIER_MODEL);
    assert_eq!(cache.get(&mk).as_deref(), Some("jev-1.13.0"));
    // The alias moves: a live call on another state re-points the model key,
    // and the old state's answer (keyed on 1.13) is no longer found.
    *served.lock().unwrap() = "jev-1.14.0".into();
    cl.state(json!("other"))
        .evaluate(&[noul("q", "x")])
        .await
        .unwrap();
    let d = cl
        .state(json!("s"))
        .evaluate(&[noul("q", "x")])
        .await
        .unwrap();
    assert!(!d.cached, "stale alias answer served");
    assert_eq!(d.model, "jev-1.14.0");
    assert_eq!(seen.lock().unwrap().len(), 3);
}

#[test]
fn memory_cache_ttl_and_max() {
    let c = MemoryCache::new(Duration::ZERO, 2);
    c.set("a", "1");
    c.set("b", "2");
    c.set("c", "3");
    assert_eq!(c.get("a"), None, "oldest-inserted goes first");
    assert_eq!(c.get("b").as_deref(), Some("2"));
    assert_eq!(c.get("c").as_deref(), Some("3"));
    let t = MemoryCache::new(Duration::from_millis(1), 0);
    t.set("k", "v");
    std::thread::sleep(Duration::from_millis(5));
    assert_eq!(t.get("k"), None, "expired");
}

#[tokio::test]
async fn states_batch_per_item() {
    use toolnexus::judge::noul;
    let (srv, seen) = fake(|_, r| {
        if r.raw.contains(r#""state":"bad""#) {
            Resp::status(400, r#"{"error":"nope"}"#)
        } else {
            ok(NOUL_REPLY)
        }
    })
    .await;
    let cl = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        ..Default::default()
    })
    .unwrap();
    let rs = cl
        .states(vec![json!("a"), json!("bad"), json!("c")])
        .evaluate(&[noul("q", "Is the object red?")])
        .await
        .unwrap();
    assert_eq!(rs.len(), 3);
    assert_eq!(rs[0].as_ref().unwrap().model, "jev-1.14.0");
    assert!(rs[1].is_err());
    assert!(rs[2].is_ok());
    let n = seen.lock().unwrap().len();
    let empty = cl.states(vec![]).evaluate(&[noul("q", "x")]).await.unwrap();
    assert!(empty.is_empty());
    let q = noul("q", "x");
    assert!(
        cl.states(vec![json!("a")])
            .evaluate(&[q.clone(), q])
            .await
            .is_err()
    );
    assert_eq!(
        seen.lock().unwrap().len(),
        n,
        "no request for empty / bad set"
    );
}

#[tokio::test]
async fn raw_question_and_answer() {
    use toolnexus::judge::raw;
    let (srv, seen) = fake(|_, _| {
        ok(r#"{"model":"jev-1.14.0","answers":{"spot":{"type":"box","box":[1,2,3,4]}}}"#)
    })
    .await;
    let cl = create_classifier(ClassifierOptions {
        base_url: srv.url.clone(),
        ..Default::default()
    })
    .unwrap();
    let d = cl
        .state(json!("s"))
        .evaluate(&[raw("spot", "box", "Where is the mug?", None)])
        .await
        .unwrap();
    assert_eq!(
        seen.lock().unwrap()[0].raw,
        r#"{"model":"jev-latest","questions":{"spot":{"instructions":"Where is the mug?","type":"box"}},"state":"s"}"#
    );
    match &d.answers["spot"] {
        DecisionAnswer::Raw(a) => {
            assert_eq!(a.kind, "box");
            assert_eq!(a.value["box"], json!([1, 2, 3, 4]));
        }
        other => panic!("{other:?}"),
    }
    assert_eq!(d.answers["spot"].answer_type(), "box");
    // Builders accept an empty type; evaluate rejects it before the wire.
    let qs = questions(&[raw("x", "", "y", None)]).unwrap();
    assert!(cl.evaluate(json!("s"), &qs).await.is_err());
    assert_eq!(seen.lock().unwrap().len(), 1);
}

#[test]
fn chunk_questions_splits_in_key_order() {
    let mut qs = Questions::new();
    for k in ["c", "a", "b"] {
        qs.insert(k.into(), NoulQuestion::default().into());
    }
    let ch = chunk_questions(&qs, 2).unwrap();
    assert_eq!(ch.len(), 2);
    assert_eq!(ch[0].keys().collect::<Vec<_>>(), ["a", "b"]);
    assert_eq!(ch[1].keys().collect::<Vec<_>>(), ["c"]);
    assert!(chunk_questions(&qs, 0).is_err());
}

#[tokio::test]
async fn metric_hash_matches_fixture() {
    let f = load("base.json");
    let (events, sink) = collect_metrics();
    let cl = create_classifier(ClassifierOptions {
        style: ClassifierStyle::Custom,
        model: f["request"]["model"].as_str().unwrap().into(),
        evaluate: Some(Arc::new(|_, _, _| {
            Box::pin(async {
                Ok(Decision {
                    model: "m".into(),
                    ..Default::default()
                })
            })
        })),
        on_metric: Some(sink),
        ..Default::default()
    })
    .unwrap();
    cl.evaluate(
        f["request"]["state"].clone(),
        &questions_of(&f["request"]["questions"]),
    )
    .await
    .unwrap();
    let ev = events.lock().unwrap();
    assert_eq!(ev[0].questions_hash, f["canonicalSha256"].as_str().unwrap());
    assert_eq!(ev[0].questions, 3);
    assert!(!ev[0].cached);
}

#[tokio::test]
async fn cloudflare_error_keeps_account_template() {
    let (srv, _) = fake(|_, _| Resp::status(500, "boom")).await;
    unsafe { std::env::set_var("TN_RS_CF_ACCOUNT", "secretacct") };
    let cl = create_classifier(ClassifierOptions {
        backend: ClassifierBackend::Cloudflare,
        base_url: format!("{}/accounts/${{TN_RS_CF_ACCOUNT}}/ai/run", srv.url),
        retries: -1,
        ..Default::default()
    })
    .unwrap();
    let e = cl.evaluate(json!("x"), &noul_q()).await.unwrap_err();
    assert!(!e.0.contains("secretacct"), "{e}");
    assert!(e.0.contains("${TN_RS_CF_ACCOUNT}"), "{e}");
    // A transport failure names the template too.
    let dead = create_classifier(ClassifierOptions {
        backend: ClassifierBackend::Cloudflare,
        base_url: "http://127.0.0.1:1/accounts/${TN_RS_CF_ACCOUNT}/ai/run".into(),
        retries: -1,
        ..Default::default()
    })
    .unwrap();
    let e = dead.evaluate(json!("x"), &noul_q()).await.unwrap_err();
    assert!(!e.0.contains("secretacct"), "{e}");
}

#[test]
fn llm_style_still_parses() {
    #[allow(deprecated)]
    let llm = ClassifierStyle::Llm;
    assert_eq!(ClassifierStyle::parse("llm").unwrap(), llm);
}
