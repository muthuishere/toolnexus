//! Media generation (SPEC §7G) — the shared E1 example: typed verbs and the
//! tool face over named media clients.
//!
//!     cargo run --example media
//!     ELEVENLABS_API_KEY=... MEDIA_VOICE=<voice> cargo run --example media
//!
//! Clients, in order, each added only when its CLI is on PATH or its key is
//! set: Grok (`grok`), Codex (`codex`), ElevenLabs (`ELEVENLABS_API_KEY`), then
//! the paid fallbacks OpenAI (`OPENAI_API_KEY`), fal (`FAL_API_KEY`) and A2E
//! (`A2E_API_KEY`), which run at their smallest / lowest-quality image
//! defaults. Steps: (1) image, (2) ElevenLabs speech (voice `MEDIA_VOICE`,
//! else the default), (3) the tool face `generate_image`, (4) Grok video only
//! with `MEDIA_VIDEO=1`, (5) paid video and talk only with `MEDIA_PAID=1`.
//! Exits non-zero if any attempted step errors; exits 0 with a hint when no
//! client is available. Keys are read at call time and never printed.

use std::sync::{Arc, Mutex};

use serde_json::{Value, json};
use toolnexus::media::call_media_verb;
use toolnexus::{
    A2EMediaClient, CodexMediaClient, ElevenLabsMediaClient, FalMediaClient, GrokMediaClient,
    ImageInput, IntoMediaClient, MediaArg, MediaArgs, MediaClient, MediaClientConfig, MediaCtx,
    MediaFuture, MediaResult, OpenAIMediaClient, SpeechInput, TalkInput, ToolContext, VideoInput,
    create_media,
};

const PROMPT: &str = "a red apple on a white table";

fn has_env(k: &str) -> bool {
    std::env::var(k).is_ok_and(|v| !v.is_empty())
}

fn on_path(cmd: &str) -> bool {
    std::env::var_os("PATH")
        .is_some_and(|p| std::env::split_paths(&p).any(|d| d.join(cmd).is_file()))
}

/// Wraps a client and records its name when it serves a call, so the typed
/// verbs can say which client answered.
struct Tracked {
    inner: Arc<dyn MediaClient>,
    served: Arc<Mutex<String>>,
}

impl Tracked {
    fn run<'a>(
        &'a self,
        verb: &'static str,
        args: MediaArgs,
        ctx: &'a MediaCtx,
    ) -> MediaFuture<'a> {
        Box::pin(async move {
            let r = call_media_verb(self.inner.as_ref(), verb, args, ctx).await;
            if r.is_ok() {
                *self.served.lock().unwrap() = self.inner.name().to_string();
            }
            r
        })
    }
}

impl MediaClient for Tracked {
    fn name(&self) -> &str {
        self.inner.name()
    }
    fn supports(&self, verb: &str) -> bool {
        self.inner.supports(verb)
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        self.inner.args(verb)
    }
    fn image<'a>(&'a self, a: MediaArgs, c: &'a MediaCtx) -> MediaFuture<'a> {
        self.run("image", a, c)
    }
    fn video<'a>(&'a self, a: MediaArgs, c: &'a MediaCtx) -> MediaFuture<'a> {
        self.run("video", a, c)
    }
    fn speech<'a>(&'a self, a: MediaArgs, c: &'a MediaCtx) -> MediaFuture<'a> {
        self.run("speech", a, c)
    }
    fn talk<'a>(&'a self, a: MediaArgs, c: &'a MediaCtx) -> MediaFuture<'a> {
        self.run("talk", a, c)
    }
}

fn print_result(client: &str, verb: &str, r: &MediaResult) {
    for f in &r.files {
        let loc = if !f.path.is_empty() { &f.path } else { &f.url };
        let size = if !f.path.is_empty() {
            std::fs::metadata(&f.path)
                .map(|m| m.len().to_string())
                .unwrap_or("?".into())
        } else if !f.data.is_empty() {
            (f.data.len() / 4 * 3).to_string()
        } else {
            "?".into()
        };
        let loc = if loc.is_empty() { "(inline)" } else { loc };
        println!("{client} {verb} {loc} {} {size} bytes", f.mime_type);
    }
    if let Some(u) = &r.usage {
        println!("  usage: {}", serde_json::to_string(u).unwrap_or_default());
    }
}

#[tokio::main]
async fn main() {
    let cfg = MediaClientConfig::default(); // gather: download to <os temp>/toolnexus-media
    let mut clients: Vec<Arc<dyn MediaClient>> = Vec::new();
    if on_path("grok") {
        clients.push(GrokMediaClient::new(cfg.clone()).into_media_client());
    }
    if on_path("codex") {
        clients.push(CodexMediaClient::new(cfg.clone()).into_media_client());
    }
    let eleven = has_env("ELEVENLABS_API_KEY");
    if eleven {
        clients.push(ElevenLabsMediaClient::new(cfg.clone()).into_media_client());
    }
    // Paid fallbacks: their built-in image defaults are already the cheapest
    // (OpenAI gpt-image-1-mini 1024x1024 quality low, fal flux/schnell square 4 steps).
    let mut paid: Vec<Arc<dyn MediaClient>> = Vec::new();
    if has_env("OPENAI_API_KEY") {
        paid.push(OpenAIMediaClient::new(cfg.clone()).into_media_client());
    }
    if has_env("FAL_API_KEY") {
        paid.push(FalMediaClient::new(cfg.clone()).into_media_client());
    }
    if has_env("A2E_API_KEY") {
        paid.push(A2EMediaClient::new(cfg.clone()).into_media_client());
    }
    clients.extend(paid.iter().cloned());
    if clients.is_empty() {
        println!(
            "no media client available: install/log in the grok or codex CLI, or set \
             ELEVENLABS_API_KEY, OPENAI_API_KEY, FAL_API_KEY or A2E_API_KEY \
             (optional: MEDIA_VOICE, MEDIA_VIDEO=1, MEDIA_PAID=1)"
        );
        return;
    }

    let served = Arc::new(Mutex::new(String::new()));
    let tracked = |cs: &[Arc<dyn MediaClient>]| {
        create_media(
            cs.iter()
                .map(|c| {
                    Arc::new(Tracked {
                        inner: c.clone(),
                        served: served.clone(),
                    }) as Arc<dyn MediaClient>
                })
                .collect::<Vec<_>>(),
        )
    };
    let media = tracked(&clients).expect("clients");
    let who = || served.lock().unwrap().clone();
    let mut errors = 0;
    let mut fail = |step: &str, e: &dyn std::fmt::Display| {
        errors += 1;
        println!("{step} FAILED: {e}");
    };

    // 1. image
    let mut image_path = None;
    if clients.iter().any(|c| c.supports("image")) {
        match media
            .image(ImageInput {
                prompt: PROMPT.into(),
                ..Default::default()
            })
            .await
        {
            Ok(r) => {
                print_result(&who(), "image", &r);
                image_path = r.files.first().map(|f| {
                    if f.path.is_empty() {
                        f.url.clone()
                    } else {
                        f.path.clone()
                    }
                });
            }
            Err(e) => fail("image", &e),
        }
    }

    // 2. speech through ElevenLabs
    let mut audio_path = None;
    if eleven {
        let voice = std::env::var("MEDIA_VOICE").ok().filter(|v| !v.is_empty());
        match media
            .speech(SpeechInput {
                text: "Hello from toolnexus.".into(),
                voice,
                ..Default::default()
            })
            .await
        {
            Ok(r) => {
                print_result(&who(), "speech", &r);
                audio_path = r.files.first().map(|f| {
                    if f.path.is_empty() {
                        f.url.clone()
                    } else {
                        f.path.clone()
                    }
                });
            }
            Err(e) => fail("speech", &e),
        }
    }

    // 3. tool face
    let tools = media.tools();
    println!(
        "tools: {}",
        tools
            .iter()
            .map(|t| t.name.as_str())
            .collect::<Vec<_>>()
            .join(", ")
    );
    if let Some(t) = tools.iter().find(|t| t.name == "generate_image") {
        let res = t
            .call(json!({"prompt": PROMPT}), ToolContext::default())
            .await;
        let meta = res.metadata.clone().unwrap_or_default();
        let client = meta.get("client").and_then(Value::as_str).unwrap_or("?");
        if res.is_error {
            fail("generate_image", &res.output);
        } else {
            println!("{client} generate_image (tool) {}", res.output);
            for p in &res.parts {
                println!(
                    "  part: {} {} {} bytes",
                    p.kind,
                    p.mime_type,
                    p.data.len() / 4 * 3
                );
            }
            if let Some(u) = meta.get("usage") {
                println!("  usage: {u}");
            }
        }
    }

    // 4. Grok video (opt-in)
    if std::env::var("MEDIA_VIDEO").as_deref() == Ok("1") {
        if let Some(grok) = clients.iter().find(|c| c.name() == "grok") {
            let m = tracked(std::slice::from_ref(grok)).expect("grok");
            match m
                .video(VideoInput {
                    prompt: "the apple slowly rotates".into(),
                    ..Default::default()
                })
                .await
            {
                Ok(r) => print_result(&who(), "video", &r),
                Err(e) => fail("video (grok)", &e),
            }
        } else {
            println!("MEDIA_VIDEO=1 but the grok CLI is not on PATH; skipping video");
        }
    }

    // 5. paid video + talk (opt-in)
    if std::env::var("MEDIA_PAID").as_deref() == Ok("1") && !paid.is_empty() {
        let m = tracked(&paid).expect("paid");
        if paid.iter().any(|c| c.supports("video")) {
            match m
                .video(VideoInput {
                    prompt: "the apple slowly rotates".into(),
                    ..Default::default()
                })
                .await
            {
                Ok(r) => print_result(&who(), "video", &r),
                Err(e) => fail("video (paid)", &e),
            }
        }
        if paid.iter().any(|c| c.supports("talk")) {
            if let (Some(img), Some(aud)) = (&image_path, &audio_path) {
                match m
                    .talk(TalkInput {
                        image: img.clone(),
                        audio: Some(aud.clone()),
                        ..Default::default()
                    })
                    .await
                {
                    Ok(r) => print_result(&who(), "talk", &r),
                    Err(e) => fail("talk", &e),
                }
            } else {
                println!("talk needs the image and speech results; skipping");
            }
        }
    }

    if errors > 0 {
        std::process::exit(1);
    }
}
