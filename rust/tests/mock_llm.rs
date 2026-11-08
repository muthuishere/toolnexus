//! Integration suite against the shared mock model server (tools/mock-llm).
//! Starts `python3 tools/mock-llm/mock_llm.py --port 0 --quiet` per test, or
//! uses `TN_MOCK_LLM_URL` when set. Skips (passes with a message) when python3
//! is not available. No network beyond loopback, no key.

use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::OnceLock;

use serde_json::{Value, json};
use toolnexus::classifier::{
    ChoiceQuestion, ClassifierBackend, ClassifierSupports, NoulQuestion, ScoreQuestion,
};
use toolnexus::client::{ClientOptions, Style, create_client};
use toolnexus::{
    BuiltinsConfig, ClassifierOptions, CloneVoiceInput, ContentPart, ImageInput, MediaOptions,
    Questions, SpeechInput, TalkingInput, Tool, Toolkit, ToolkitOptions, TranscribeInput,
    VideoInput, create_classifier, create_media, create_toolkit, define_tool, load_media_style,
};

fn repo() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..")
}

/// The mock's state (faults, counts) is global, so tests run one at a time.
fn lock() -> &'static tokio::sync::Mutex<()> {
    static L: OnceLock<tokio::sync::Mutex<()>> = OnceLock::new();
    L.get_or_init(|| tokio::sync::Mutex::new(()))
}

struct Mock {
    base: String,
    child: Option<Child>,
}

impl Drop for Mock {
    fn drop(&mut self) {
        if let Some(c) = self.child.as_mut() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
}

impl Mock {
    fn origin(&self) -> &str {
        self.base.trim_end_matches("/v1")
    }
}

fn start_mock() -> Option<Mock> {
    if let Ok(u) = std::env::var("TN_MOCK_LLM_URL")
        && !u.is_empty()
    {
        return Some(Mock {
            base: u.trim_end_matches('/').to_string(),
            child: None,
        });
    }
    let script = repo().join("tools/mock-llm/mock_llm.py");
    let mut child = match Command::new("python3")
        .arg(&script)
        .args(["--port", "0", "--quiet"])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
    {
        Ok(c) => c,
        Err(e) => {
            eprintln!("SKIP mock_llm suite: python3 not available ({e})");
            return None;
        }
    };
    let mut line = String::new();
    BufReader::new(child.stdout.take().unwrap())
        .read_line(&mut line)
        .unwrap();
    let base = line
        .trim()
        .rsplit(' ')
        .next()
        .filter(|u| u.starts_with("http"))
        .unwrap_or_else(|| panic!("unexpected mock banner: {line:?}"))
        .to_string();
    Some(Mock {
        base,
        child: Some(child),
    })
}

macro_rules! mock_or_skip {
    () => {{
        match start_mock() {
            Some(m) => m,
            None => return,
        }
    }};
}

fn add_tool() -> Tool {
    define_tool(
        "add",
        "Add two integers.",
        Some(json!({"type": "object",
            "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
            "required": ["a", "b"]})),
        |a: Value, _| async move {
            let s = a["a"].as_i64().unwrap_or(0) + a["b"].as_i64().unwrap_or(0);
            Ok::<_, String>(s.to_string())
        },
    )
}

async fn toolkit(tools: Vec<Tool>) -> Toolkit {
    create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        extra_tools: tools,
        ..Default::default()
    })
    .await
    .unwrap()
}

fn opts(m: &Mock, style: Style) -> ClientOptions {
    ClientOptions {
        base_url: m.base.clone(),
        model: "mock-model".into(),
        api_key: "mock-key".into(),
        style,
        ..Default::default()
    }
}

const PLAIN: &str = "This is a reply from the toolnexus mock model.";

async fn tool_loop(style: Style) {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let tk = toolkit(vec![add_tool()]).await;
    let r = create_client(opts(&m, style))
        .run("add", &tk)
        .await
        .unwrap();
    assert_eq!(r.tool_calls.len(), 1, "{style:?}");
    assert_eq!(r.tool_calls[0].name, "add");
    assert_eq!(r.tool_calls[0].args, json!({"a": 1, "b": 1}));
    assert_eq!(r.tool_calls[0].output, "2");
    assert_eq!(r.text, "Tool result: 2", "{style:?}");
}

#[tokio::test]
async fn openai_tool_loop() {
    tool_loop(Style::OpenAI).await;
}

#[tokio::test]
async fn anthropic_tool_loop() {
    tool_loop(Style::Anthropic).await;
}

#[tokio::test]
async fn streaming_text_both_styles() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let tk = toolkit(vec![]).await;
    for style in [Style::OpenAI, Style::Anthropic] {
        let mut text = String::new();
        let mut done = None;
        let mut rx = create_client(opts(&m, style)).stream("hi", &tk).unwrap();
        while let Some(ev) = rx.recv().await {
            match ev.kind.as_str() {
                "text" => text.push_str(&ev.delta),
                "done" => done = ev.result,
                "error" => panic!("{style:?}: {:?}", ev.error),
                _ => {}
            }
        }
        assert_eq!(text, PLAIN, "{style:?}");
        assert_eq!(done.unwrap().text, PLAIN, "{style:?}");
    }
}

#[tokio::test]
async fn image_attachment_arrives_intact() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let sha = std::fs::read_to_string(repo().join("examples/media/fixture.png.sha256")).unwrap();
    let want = format!("image/png 82 bytes sha256={}", &sha.trim()[..12]);
    let tk = toolkit(vec![]).await;
    for style in [Style::OpenAI, Style::Anthropic] {
        let parts = vec![
            ContentPart::text("describe"),
            ContentPart::from_path(repo().join("examples/media/fixture.png")).unwrap(),
        ];
        let r = create_client(opts(&m, style))
            .run(parts, &tk)
            .await
            .unwrap();
        assert!(r.text.contains(&want), "{style:?}: {}", r.text);
        assert!(r.text.starts_with("Received 1 image(s)"), "{}", r.text);
    }
}

#[tokio::test]
async fn retries_a_429_with_retry_after_zero() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let http = reqwest::Client::new();
    http.post(format!("{}/_mock/reset", m.base))
        .send()
        .await
        .unwrap();
    http.post(format!("{}/_mock/faults", m.base))
        .json(&json!({"path": "/chat/completions", "status": 429, "times": 1, "retryAfter": "0"}))
        .send()
        .await
        .unwrap()
        .error_for_status()
        .unwrap();
    let tk = toolkit(vec![]).await;
    let r = create_client(opts(&m, Style::OpenAI))
        .run("hi", &tk)
        .await
        .unwrap();
    assert_eq!(r.text, PLAIN);
    let counts: Value = http
        .get(format!("{}/_mock/requests", m.base))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(counts["/chat/completions"], 2, "{counts}");
}

fn questions() -> Questions {
    let mut q = Questions::new();
    q.insert(
        "is_refund".into(),
        NoulQuestion {
            instructions: "Is the customer asking for a refund?".into(),
            criteria: None,
        }
        .into(),
    );
    q.insert(
        "department".into(),
        ChoiceQuestion {
            instructions: "Which team should handle this?".into(),
            criteria: [
                ("billing", "a charge, invoice or refund"),
                ("shipping", "a delivery or parcel"),
                ("tech", "the product is broken"),
            ]
            .into_iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect(),
        }
        .into(),
    );
    q.insert(
        "urgency".into(),
        ScoreQuestion {
            instructions: "How urgent is it?".into(),
            criteria: vec!["low".into(), "medium".into(), "high".into()],
        }
        .into(),
    );
    q
}

async fn check_classifier(backend: ClassifierBackend, base_url: String) {
    let c = create_classifier(ClassifierOptions {
        backend,
        base_url,
        api_key_env: "TN_MOCK_LLM_UNSET_KEY".into(),
        supports: (backend == ClassifierBackend::Custom)
            .then_some(ClassifierSupports { images: false }),
        ..Default::default()
    })
    .unwrap();
    let state = json!("I was charged twice for my order, please refund one.");
    let d = c.evaluate(state.clone(), &questions()).await.unwrap();
    assert_eq!(d.noul("is_refund").unwrap().noul, 0.95, "{backend:?}");
    let ch = d.choice("department").unwrap();
    assert!(
        ["billing", "shipping", "tech"].contains(&ch.choice.as_str()),
        "{ch:?}"
    );
    assert_eq!(ch.probabilities.len(), 3);
    let sum: f64 = ch.probabilities.values().sum();
    assert!((sum - 1.0).abs() < 1e-9, "sum {sum}");
    assert_eq!(ch.probabilities[&ch.choice], 0.7);
    assert_eq!(ch.confidence, 0.8);
    let s = d.score("urgency").unwrap();
    assert!((0.0..=2.0).contains(&s.score), "{s:?}");
    assert_eq!(s.confidence, 0.6);
    // deterministic: the same call gives the same answers
    let d2 = c.evaluate(state, &questions()).await.unwrap();
    assert_eq!(d2.choice("department").unwrap().choice, ch.choice);
    assert_eq!(d2.score("urgency").unwrap().score, s.score);
}

#[tokio::test]
async fn classifier_systemone_custom() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    check_classifier(ClassifierBackend::Custom, m.base.clone()).await;
}

#[tokio::test]
async fn classifier_cloudflare() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    check_classifier(
        ClassifierBackend::Cloudflare,
        format!("{}/v1/accounts/acc/ai/run", m.origin()),
    )
    .await;
}

#[tokio::test]
async fn media_every_verb_and_async_failure() {
    let _g = lock().lock().await;
    let m = mock_or_skip!();
    let style: Value = reqwest::get(format!("{}/_mock/media-style.json", m.base))
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let dir = std::env::temp_dir().join(format!("tn-mock-media-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("media-style.json");
    std::fs::write(&path, style.to_string()).unwrap();
    let media = create_media(load_media_style(&path).unwrap(), MediaOptions::default());
    let _ = std::fs::remove_dir_all(&dir);

    let one = |r: &toolnexus::MediaResult, suffix: &str, mime: &str| {
        assert_eq!(r.files.len(), 1, "{r:?}");
        assert!(r.files[0].url.ends_with(suffix), "{r:?}");
        assert_eq!(r.files[0].mime_type, mime, "{r:?}");
    };
    let img = media
        .image(ImageInput {
            prompt: "a red square".into(),
            ..Default::default()
        })
        .await
        .unwrap();
    one(&img, "/files/image.png", "image/png");
    let vid = media
        .video(VideoInput {
            prompt: "a red square spinning".into(),
            ..Default::default()
        })
        .await
        .unwrap();
    one(&vid, "/files/video.mp4", "video/mp4");
    let sp = media
        .speech(SpeechInput {
            text: "hello".into(),
            ..Default::default()
        })
        .await
        .unwrap();
    one(&sp, "/files/speech.wav", "audio/wav");
    let audio = sp.files[0].url.clone();
    let tr = media
        .transcribe(TranscribeInput {
            audio: audio.clone(),
            ..Default::default()
        })
        .await
        .unwrap();
    assert_eq!(
        tr.text.as_deref(),
        Some(format!("Mock transcript of {audio}.").as_str())
    );
    let cv = media
        .clone_voice(CloneVoiceInput {
            name: "muthu".into(),
            samples: vec![audio.clone()],
            ..Default::default()
        })
        .await
        .unwrap();
    use sha2::{Digest, Sha256};
    let h = format!("{:x}", Sha256::digest(b"muthu"));
    assert_eq!(
        cv.text.as_deref(),
        Some(format!("voice-{}", &h[..8]).as_str())
    );
    let tk = media
        .talking(TalkingInput {
            image: img.files[0].url.clone(),
            audio,
            ..Default::default()
        })
        .await
        .unwrap();
    one(&tk, "/files/talking.mp4", "video/mp4");

    let err = media
        .image(ImageInput {
            prompt: "please [fail] now".into(),
            ..Default::default()
        })
        .await
        .unwrap_err();
    assert!(err.to_string().contains("mock: job asked to fail"), "{err}");
}
