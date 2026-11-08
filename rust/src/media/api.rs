//! Built-in API media clients (§7G.9): code on one shared API base — the
//! §7G.8 HTTP rules, gather, and the `unavailable` mapping.

use std::time::{Duration, Instant};

use serde_json::{Map, Value, json};

use super::http::{self, Body, Gather, Multipart, Raw, SendError};
use super::style::{StyleMediaClient, get_path, job_id_of, render};
use super::{
    GatherMode, MediaArg, MediaArgs, MediaClient, MediaClientConfig, MediaCtx, MediaError,
    MediaFile, MediaResult, MediaStyle, MediaUsage, positive, report, verb_kind,
};

// ---------------------------------------------------------------------------
// the API base
// ---------------------------------------------------------------------------

struct ApiBase {
    name: &'static str,
    env: &'static str,
    default_url: &'static str,
    /// `Authorization`-style header: (name, value prefix).
    auth: (&'static str, &'static str),
    default_interval: u64,
    primary: &'static str,
    cfg: MediaClientConfig,
    http: reqwest::Client,
}

/// One verb call's job state: the deadline, the job id once known.
struct Job {
    start: Instant,
    deadline: Instant,
    timeout_ms: u64,
    id: Option<String>,
}

impl Job {
    fn timeout_msg(&self, verb: &str) -> String {
        match &self.id {
            Some(id) => format!("job {id} timed out after {}ms", self.timeout_ms),
            None => format!("{verb}: timed out after {}ms", self.timeout_ms),
        }
    }
    fn timed_out(&self, verb: &str) -> MediaError {
        MediaError::timeout(self.timeout_msg(verb), self.id.clone())
    }
}

impl ApiBase {
    fn new(
        name: &'static str,
        env: &'static str,
        default_url: &'static str,
        auth: (&'static str, &'static str),
        default_interval: u64,
        primary: &'static str,
        cfg: MediaClientConfig,
    ) -> Self {
        ApiBase {
            name,
            env,
            default_url,
            auth,
            default_interval,
            primary,
            http: cfg.http_client.clone().unwrap_or_else(http::default_http),
            cfg,
        }
    }

    /// The key, read at call time; none ⇒ `unavailable`, no request.
    fn key(&self) -> Result<String, MediaError> {
        self.cfg
            .key
            .clone()
            .filter(|k| !k.is_empty())
            .or_else(|| std::env::var(self.env).ok().filter(|k| !k.is_empty()))
            .ok_or_else(|| {
                MediaError::unavailable(format!("{}: no API key (set {})", self.name, self.env))
            })
    }

    fn base(&self) -> String {
        self.cfg
            .url
            .as_deref()
            .unwrap_or(self.default_url)
            .trim_end_matches('/')
            .to_string()
    }

    /// `models[verb]`, else `model` for the primary verb, else `default`.
    fn model(&self, verb: &str, default: &str) -> String {
        if let Some(m) = self.cfg.models.get(verb) {
            return m.clone();
        }
        if verb == self.primary
            && let Some(m) = &self.cfg.model
        {
            return m.clone();
        }
        default.to_string()
    }

    fn job(&self, ctx: &MediaCtx) -> Job {
        let timeout_ms = positive(self.cfg.r#loop.timeout_ms).unwrap_or(600_000);
        let start = Instant::now();
        let mut deadline = start + Duration::from_millis(timeout_ms);
        if let Some(d) = ctx.deadline {
            deadline = deadline.min(d);
        }
        Job {
            start,
            deadline,
            timeout_ms,
            id: None,
        }
    }

    fn interval(&self) -> Duration {
        Duration::from_millis(
            positive(self.cfg.r#loop.interval_ms).unwrap_or(self.default_interval),
        )
    }

    fn headers(&self, key: &str) -> Vec<(String, String)> {
        vec![(self.auth.0.to_string(), format!("{}{key}", self.auth.1))]
    }

    /// One request to the provider; non-2xx mapped per §7G.2/§7G.7.
    async fn call(
        &self,
        job: &Job,
        verb: &str,
        method: &str,
        url: &str,
        body: Body,
        key: &str,
    ) -> Result<http::Sent, MediaError> {
        let origin = reqwest::Url::parse(&self.base()).ok();
        let sent = http::send(
            &self.http,
            verb,
            method,
            url,
            body,
            &self.headers(key),
            origin.as_ref(),
            job.deadline,
            None,
        )
        .await
        .map_err(|e| match e {
            SendError::TimedOut => job.timed_out(verb),
            SendError::Msg(m) => MediaError::failed(m).with_job(&job.id),
        })?;
        if !(200..300).contains(&sent.status) {
            return Err(http::http_error(self.name, sent.status, &sent.body).with_job(&job.id));
        }
        Ok(sent)
    }

    /// [`ApiBase::call`] whose response must be JSON.
    async fn json(
        &self,
        job: &Job,
        verb: &str,
        method: &str,
        url: &str,
        body: Body,
        key: &str,
    ) -> Result<Value, MediaError> {
        let sent = self.call(job, verb, method, url, body, key).await?;
        serde_json::from_slice(&sent.body).map_err(|_| {
            MediaError::failed(format!("{verb}: response is not JSON")).with_job(&job.id)
        })
    }

    /// Poll `url` until `classify` says done (`Some(Ok)`) or failed (`Some(Err(msg))`).
    #[allow(clippy::too_many_arguments)]
    async fn poll(
        &self,
        job: &Job,
        ctx: &MediaCtx,
        verb: &str,
        url: &str,
        key: &str,
        classify: impl Fn(&Value, &str) -> Option<Result<(), String>>,
    ) -> Result<Value, MediaError> {
        let interval = self.interval();
        let id = job.id.clone().unwrap_or_default();
        loop {
            tokio::time::sleep(
                interval.min(job.deadline.saturating_duration_since(Instant::now())),
            )
            .await;
            if Instant::now() >= job.deadline {
                return Err(job.timed_out(verb));
            }
            let r = self.json(job, verb, "GET", url, Body::None, key).await?;
            let st = render(r.get("status"));
            report(ctx, &self.cfg, verb, self.name, Some(&id), &st, job.start);
            match classify(&r, &st) {
                Some(Ok(())) => return Ok(r),
                Some(Err(m)) => return Err(MediaError::failed(m).with_job(&job.id)),
                None => {}
            }
            if Instant::now() >= job.deadline {
                return Err(job.timed_out(verb));
            }
        }
    }

    fn gather<'a>(&'a self, verb: &'a str, job: &Job) -> Gather<'a> {
        Gather {
            client: self.name,
            verb,
            kind: verb_kind(verb),
            mode: self.cfg.gather.mode.unwrap_or(GatherMode::Download),
            dir: self.cfg.gather.dir.clone(),
            name: self.cfg.gather.name.clone(),
            max: self.max(),
            keep: true,
            http: &self.http,
            deadline: job.deadline,
            timeout_msg: job.timeout_msg(verb),
            job_id: job.id.clone(),
        }
    }

    fn max(&self) -> u64 {
        self.cfg.gather.max_bytes.unwrap_or(http::DEFAULT_MAX_BYTES)
    }

    async fn input(&self, job: &Job, verb: &str, value: &str) -> Result<http::Input, MediaError> {
        http::read_input(&self.http, verb, value, self.max(), job.deadline).await
    }
}

fn s<'a>(args: &'a MediaArgs, k: &str) -> Option<&'a str> {
    args.get(k).and_then(Value::as_str)
}

fn int(args: &MediaArgs, k: &str) -> Option<i64> {
    args.get(k)
        .and_then(|v| v.as_i64().or_else(|| v.as_f64().map(|f| f as i64)))
}

/// A JSON body with `providerOptions` written over its top-level keys.
fn json_body(mut v: Value, ctx: &MediaCtx) -> Body {
    if let (Some(po), Some(obj)) = (&ctx.provider_options, v.as_object_mut()) {
        for (k, val) in po {
            obj.insert(k.clone(), val.clone());
        }
    }
    Body::Json(v.to_string().into_bytes())
}

/// `providerOptions` add/replace multipart text fields.
fn multipart_body(mut m: Multipart, ctx: &MediaCtx) -> Body {
    if let Some(po) = &ctx.provider_options {
        for (k, v) in po {
            let text = match v {
                Value::String(s) => s.clone(),
                other => other.to_string(),
            };
            m.set_field(k, &text);
        }
    }
    m.body()
}

fn args_of(table: &[(&str, &[(&str, bool)])], verb: &str) -> Option<Vec<MediaArg>> {
    table
        .iter()
        .find(|(v, _)| *v == verb)
        .map(|(_, a)| a.iter().map(|(n, r)| MediaArg::new(*n, *r)).collect())
}

fn completed(files: Vec<MediaFile>, text: Option<String>, job_id: Option<String>) -> MediaResult {
    MediaResult {
        files,
        text,
        job_id,
        status: "completed".into(),
        usage: None,
        style: None,
        model: None,
    }
}

fn unknown(name: &str, verb: &str) -> MediaError {
    MediaError::failed(format!("{name}: {verb} is not supported"))
}

// ---------------------------------------------------------------------------
// A2E (a style client over the embedded preset)
// ---------------------------------------------------------------------------

/// `A2EMediaClient` (`a2e`): image, video, speech, talk over the embedded
/// `examples/media-styles/a2e.json`. Key: `key` or `A2E_API_KEY` at call time.
#[derive(Debug, Clone)]
pub struct A2EMediaClient(StyleMediaClient);

impl A2EMediaClient {
    pub fn new(cfg: MediaClientConfig) -> Self {
        let style: MediaStyle =
            serde_json::from_str(super::A2E_JSON).expect("embedded a2e.json is a valid style");
        A2EMediaClient(
            StyleMediaClient::builtin(style, cfg, "A2E_API_KEY", ("image", "model_type"))
                .expect("embedded a2e.json passes the style checks"),
        )
    }

    async fn run(
        &self,
        verb: &str,
        args: MediaArgs,
        ctx: &MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        self.0.run(verb, args, ctx).await
    }
}

impl MediaClient for A2EMediaClient {
    fn name(&self) -> &str {
        self.0.name()
    }
    fn supports(&self, verb: &str) -> bool {
        self.0.supports(verb)
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        self.0.args(verb)
    }
    verbs_via_run!();
}

// ---------------------------------------------------------------------------
// ElevenLabs
// ---------------------------------------------------------------------------

const ELEVEN_ARGS: &[(&str, &[(&str, bool)])] = &[
    ("speech", &[("text", true), ("voice", false)]),
    (
        "music",
        &[
            ("prompt", true),
            ("seconds", false),
            ("instrumental", false),
        ],
    ),
    ("transcribe", &[("audio", true), ("language", false)]),
    ("cloneVoice", &[("name", true), ("samples", true)]),
];

/// `ElevenLabsMediaClient` (`elevenlabs`): speech (live-verified), music,
/// transcribe, cloneVoice. Key `ELEVENLABS_API_KEY`, header `xi-api-key`.
pub struct ElevenLabsMediaClient {
    base: ApiBase,
}

impl ElevenLabsMediaClient {
    pub fn new(cfg: MediaClientConfig) -> Self {
        ElevenLabsMediaClient {
            base: ApiBase::new(
                "elevenlabs",
                "ELEVENLABS_API_KEY",
                "https://api.elevenlabs.io",
                ("xi-api-key", ""),
                5000,
                "speech",
                cfg,
            ),
        }
    }

    async fn run(
        &self,
        verb: &str,
        args: MediaArgs,
        ctx: &MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        let b = &self.base;
        if args_of(ELEVEN_ARGS, verb).is_none() {
            return Err(unknown(b.name, verb));
        }
        let key = b.key()?;
        let job = b.job(ctx);
        let url = b.base();
        match verb {
            "speech" => {
                let text = s(&args, "text").unwrap_or_default();
                let id = match s(&args, "voice") {
                    None => "JBFqnCBsd6RMkjVDRZzb".to_string(),
                    Some(voice) => self.voice_id(&job, &key, voice).await?,
                };
                let body =
                    json!({"text": text, "model_id": b.model("speech", "eleven_flash_v2_5")});
                let sent = b
                    .call(
                        &job,
                        verb,
                        "POST",
                        &format!(
                            "{url}/v1/text-to-speech/{}?output_format=mp3_22050_32",
                            http::pct_encode(&id)
                        ),
                        json_body(body, ctx),
                        &key,
                    )
                    .await?;
                let job_id = sent.header("request-id").filter(|s| !s.is_empty());
                let f = b
                    .gather(verb, &job)
                    .gather(Raw::Bytes(sent.body, "audio/mpeg".into()))
                    .await?;
                Ok(completed(vec![f], None, job_id))
            }
            "music" => {
                let mut body = json!({
                    "prompt": s(&args, "prompt").unwrap_or_default(),
                    "music_length_ms": int(&args, "seconds").unwrap_or(10) * 1000,
                });
                if args.get("instrumental").and_then(Value::as_bool) == Some(true) {
                    body["force_instrumental"] = json!(true);
                }
                let sent = b
                    .call(
                        &job,
                        verb,
                        "POST",
                        &format!("{url}/v1/music?output_format=mp3_44100_128"),
                        json_body(body, ctx),
                        &key,
                    )
                    .await?;
                let f = b
                    .gather(verb, &job)
                    .gather(Raw::Bytes(sent.body, "audio/mpeg".into()))
                    .await?;
                Ok(completed(vec![f], None, None))
            }
            "transcribe" => {
                let input = b
                    .input(&job, verb, s(&args, "audio").unwrap_or_default())
                    .await?;
                let mut m = Multipart::default();
                m.field("model_id", &b.model("transcribe", "scribe_v1"));
                if let Some(l) = s(&args, "language") {
                    m.field("language_code", l);
                }
                m.file("file", input);
                let v = b
                    .json(
                        &job,
                        verb,
                        "POST",
                        &format!("{url}/v1/speech-to-text"),
                        multipart_body(m, ctx),
                        &key,
                    )
                    .await?;
                text_result(verb, &v, "text")
            }
            _ => {
                // cloneVoice
                let mut m = Multipart::default();
                m.field("name", s(&args, "name").unwrap_or_default());
                let samples: Vec<String> = args
                    .get("samples")
                    .and_then(Value::as_array)
                    .map(|a| {
                        a.iter()
                            .filter_map(|x| x.as_str().map(str::to_string))
                            .collect()
                    })
                    .unwrap_or_default();
                for smp in &samples {
                    let input = b.input(&job, verb, smp).await?;
                    m.file("files", input);
                }
                let v = b
                    .json(
                        &job,
                        verb,
                        "POST",
                        &format!("{url}/v1/voices/add"),
                        multipart_body(m, ctx),
                        &key,
                    )
                    .await?;
                text_result(verb, &v, "voice_id")
            }
        }
    }

    /// Voice name or id ⇒ id (§7G.9 ElevenLabs speech).
    async fn voice_id(&self, job: &Job, key: &str, voice: &str) -> Result<String, MediaError> {
        let b = &self.base;
        let v = b
            .json(
                job,
                "speech",
                "GET",
                &format!(
                    "{}/v2/voices?page_size=100&search={}",
                    b.base(),
                    http::pct_encode(voice)
                ),
                Body::None,
                key,
            )
            .await?;
        let voices = v
            .get("voices")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        for e in &voices {
            let name = e.get("name").and_then(Value::as_str).unwrap_or("");
            let id = e.get("voice_id").and_then(Value::as_str).unwrap_or("");
            if !id.is_empty() && (name.eq_ignore_ascii_case(voice) || id == voice) {
                return Ok(id.to_string());
            }
        }
        if voice.chars().count() == 20 && !voice.contains(' ') {
            return Ok(voice.to_string());
        }
        Err(MediaError::failed(format!(
            "speech: voice \"{voice}\" not found"
        )))
    }
}

fn text_result(verb: &str, v: &Value, key: &str) -> Result<MediaResult, MediaError> {
    match v.get(key) {
        Some(Value::String(t)) => Ok(completed(Vec::new(), Some(t.clone()), None)),
        _ => Err(MediaError::failed(format!("{verb}: no result at {key}"))),
    }
}

impl MediaClient for ElevenLabsMediaClient {
    fn name(&self) -> &str {
        self.base.name
    }
    fn supports(&self, verb: &str) -> bool {
        args_of(ELEVEN_ARGS, verb).is_some()
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        Some(args_of(ELEVEN_ARGS, verb).unwrap_or_default())
    }
    verbs_via_run!();
}

// ---------------------------------------------------------------------------
// OpenAI
// ---------------------------------------------------------------------------

const OPENAI_ARGS: &[(&str, &[(&str, bool)])] = &[
    (
        "image",
        &[("prompt", true), ("width", false), ("height", false)],
    ),
    ("video", &[("prompt", true), ("seconds", false)]),
    ("speech", &[("text", true), ("voice", false)]),
    ("transcribe", &[("audio", true), ("language", false)]),
];

/// `OpenAIMediaClient` (`openai`): image (live-verified), video, speech,
/// transcribe. `url` points it at any OpenAI-compatible server. Key `OPENAI_API_KEY`.
pub struct OpenAIMediaClient {
    base: ApiBase,
}

impl OpenAIMediaClient {
    pub fn new(cfg: MediaClientConfig) -> Self {
        OpenAIMediaClient {
            base: ApiBase::new(
                "openai",
                "OPENAI_API_KEY",
                "https://api.openai.com/v1",
                ("Authorization", "Bearer "),
                5000,
                "image",
                cfg,
            ),
        }
    }

    async fn run(
        &self,
        verb: &str,
        args: MediaArgs,
        ctx: &MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        let b = &self.base;
        if args_of(OPENAI_ARGS, verb).is_none() {
            return Err(unknown(b.name, verb));
        }
        let key = b.key()?;
        let mut job = b.job(ctx);
        let url = b.base();
        match verb {
            "image" => {
                let model = b.model("image", "gpt-image-1-mini");
                let (w, h) = (int(&args, "width"), int(&args, "height"));
                let size = match (w, h) {
                    (None, None) => "1024x1024".to_string(),
                    (Some(w), None) => format!("{w}x{w}"),
                    (None, Some(h)) => format!("{h}x{h}"),
                    (Some(w), Some(h)) => format!("{w}x{h}"),
                };
                let mut body = json!({"model": model, "prompt": s(&args, "prompt").unwrap_or_default(), "n": 1, "size": size});
                if model.starts_with("gpt-image") {
                    body["quality"] = json!("low");
                }
                let v = b
                    .json(
                        &job,
                        verb,
                        "POST",
                        &format!("{url}/images/generations"),
                        json_body(body, ctx),
                        &key,
                    )
                    .await?;
                let mime = match v.get("output_format").and_then(Value::as_str) {
                    Some("jpeg") => "image/jpeg",
                    Some("webp") => "image/webp",
                    _ => "image/png",
                };
                let mut raws = Vec::new();
                for e in v
                    .get("data")
                    .and_then(Value::as_array)
                    .into_iter()
                    .flatten()
                {
                    if let Some(b64) = e.get("b64_json").and_then(Value::as_str) {
                        raws.push(Raw::Base64(b64.to_string(), mime.to_string()));
                    } else if let Some(u) = e.get("url").and_then(Value::as_str) {
                        raws.push(Raw::Url(u.to_string()));
                    }
                }
                if raws.is_empty() {
                    return Err(MediaError::failed("image: no result at data"));
                }
                let g = b.gather(verb, &job);
                let mut files = Vec::new();
                for r in raws {
                    files.push(g.gather(r).await?);
                }
                let mut res = completed(files, None, None);
                res.usage = MediaUsage {
                    input_tokens: get_path(&v, "usage.input_tokens").and_then(Value::as_i64),
                    output_tokens: get_path(&v, "usage.output_tokens").and_then(Value::as_i64),
                    ..Default::default()
                }
                .nonempty();
                Ok(res)
            }
            "speech" => {
                let body = json!({
                    "model": b.model("speech", "gpt-4o-mini-tts"),
                    "input": s(&args, "text").unwrap_or_default(),
                    "voice": s(&args, "voice").unwrap_or("alloy"),
                });
                let sent = b
                    .call(
                        &job,
                        verb,
                        "POST",
                        &format!("{url}/audio/speech"),
                        json_body(body, ctx),
                        &key,
                    )
                    .await?;
                let f = b
                    .gather(verb, &job)
                    .gather(Raw::Bytes(sent.body, "audio/mpeg".into()))
                    .await?;
                Ok(completed(vec![f], None, None))
            }
            "transcribe" => {
                let input = b
                    .input(&job, verb, s(&args, "audio").unwrap_or_default())
                    .await?;
                let mut m = Multipart::default();
                m.field("model", &b.model("transcribe", "gpt-4o-mini-transcribe"));
                if let Some(l) = s(&args, "language") {
                    m.field("language", l);
                }
                m.file("file", input);
                let v = b
                    .json(
                        &job,
                        verb,
                        "POST",
                        &format!("{url}/audio/transcriptions"),
                        multipart_body(m, ctx),
                        &key,
                    )
                    .await?;
                text_result(verb, &v, "text")
            }
            _ => {
                // video
                let body = json!({
                    "model": b.model("video", "sora-2"),
                    "prompt": s(&args, "prompt").unwrap_or_default(),
                    "seconds": int(&args, "seconds").unwrap_or(4).to_string(),
                });
                let v = b
                    .json(
                        &job,
                        verb,
                        "POST",
                        &format!("{url}/videos"),
                        json_body(body, ctx),
                        &key,
                    )
                    .await?;
                let Some(id) = job_id_of(v.get("id")) else {
                    return Err(MediaError::failed("video: no job id at id"));
                };
                job.id = Some(id.clone());
                let pid = http::pct_encode(&id);
                b.poll(
                    &job,
                    ctx,
                    verb,
                    &format!("{url}/videos/{pid}"),
                    &key,
                    |r, st| match st {
                        "completed" => Some(Ok(())),
                        "failed" => Some(Err(
                            match get_path(r, "error.message").and_then(Value::as_str) {
                                Some(m) if !m.is_empty() => format!("job {id} failed: {m}"),
                                _ => format!("job {id} failed"),
                            },
                        )),
                        _ => None,
                    },
                )
                .await?;
                let sent = b
                    .call(
                        &job,
                        verb,
                        "GET",
                        &format!("{url}/videos/{pid}/content"),
                        Body::None,
                        &key,
                    )
                    .await?;
                let f = b
                    .gather(verb, &job)
                    .gather(Raw::Bytes(sent.body, "video/mp4".into()))
                    .await?;
                Ok(completed(vec![f], None, job.id))
            }
        }
    }
}

impl MediaClient for OpenAIMediaClient {
    fn name(&self) -> &str {
        self.base.name
    }
    fn supports(&self, verb: &str) -> bool {
        args_of(OPENAI_ARGS, verb).is_some()
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        Some(args_of(OPENAI_ARGS, verb).unwrap_or_default())
    }
    verbs_via_run!();
}

// ---------------------------------------------------------------------------
// OpenRouter
// ---------------------------------------------------------------------------

const OPENROUTER_ARGS: &[(&str, &[(&str, bool)])] = &[("image", &[("prompt", true)])];

/// `OpenRouterMediaClient` (`openrouter`): image (live-verified) through chat
/// completions with `modalities: ["image","text"]`. Key `OPENROUTER_API_KEY`.
pub struct OpenRouterMediaClient {
    base: ApiBase,
}

impl OpenRouterMediaClient {
    pub fn new(cfg: MediaClientConfig) -> Self {
        OpenRouterMediaClient {
            base: ApiBase::new(
                "openrouter",
                "OPENROUTER_API_KEY",
                "https://openrouter.ai/api/v1",
                ("Authorization", "Bearer "),
                5000,
                "image",
                cfg,
            ),
        }
    }

    async fn run(
        &self,
        verb: &str,
        args: MediaArgs,
        ctx: &MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        let b = &self.base;
        if verb != "image" {
            return Err(unknown(b.name, verb));
        }
        let key = b.key()?;
        let job = b.job(ctx);
        let body = json!({
            "model": b.model("image", "google/gemini-2.5-flash-image"),
            "modalities": ["image", "text"],
            "messages": [{"role": "user", "content": s(&args, "prompt").unwrap_or_default()}],
        });
        let v = b
            .json(
                &job,
                verb,
                "POST",
                &format!("{}/chat/completions", b.base()),
                json_body(body, ctx),
                &key,
            )
            .await?;
        let job_id = v.get("id").and_then(Value::as_str).map(str::to_string);
        let mut text = String::new();
        let mut raws = Vec::new();
        for c in v
            .get("choices")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            if let Some(t) = get_path(c, "message.content").and_then(Value::as_str) {
                text.push_str(t);
            }
            for im in get_path(c, "message.images")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                if let Some(u) = get_path(im, "image_url.url").and_then(Value::as_str) {
                    if u.starts_with("data:") {
                        raws.push(Raw::DataUrl(u.to_string()));
                    } else {
                        raws.push(Raw::Url(u.to_string()));
                    }
                }
            }
        }
        if raws.is_empty() {
            let mut m = "image: no image in response".to_string();
            if !text.is_empty() {
                m.push_str(&format!(": {}", http::cap(&text, 200)));
            }
            return Err(MediaError::Failed { message: m, job_id });
        }
        let g = b.gather(verb, &job);
        let mut files = Vec::new();
        for r in raws {
            files.push(g.gather(r).await?);
        }
        let text = (!text.is_empty()).then_some(text);
        let mut res = completed(files, text, job_id);
        res.usage = MediaUsage {
            input_tokens: get_path(&v, "usage.prompt_tokens").and_then(Value::as_i64),
            output_tokens: get_path(&v, "usage.completion_tokens").and_then(Value::as_i64),
            cost_usd: get_path(&v, "usage.cost").and_then(Value::as_f64),
            ..Default::default()
        }
        .nonempty();
        Ok(res)
    }
}

impl MediaClient for OpenRouterMediaClient {
    fn name(&self) -> &str {
        self.base.name
    }
    fn supports(&self, verb: &str) -> bool {
        verb == "image"
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        Some(args_of(OPENROUTER_ARGS, verb).unwrap_or_default())
    }
    verbs_via_run!();
}

// ---------------------------------------------------------------------------
// fal
// ---------------------------------------------------------------------------

const FAL_ARGS: &[(&str, &[(&str, bool)])] = &[
    (
        "image",
        &[
            ("prompt", true),
            ("width", false),
            ("height", false),
            ("seed", false),
        ],
    ),
    (
        "video",
        &[
            ("prompt", true),
            ("image", false),
            ("seconds", false),
            ("aspectRatio", false),
        ],
    ),
];

/// `FalMediaClient` (`fal`): the fal queue API for any hosted model id —
/// image (live-verified on `fal-ai/flux/schnell`) and video. Key `FAL_API_KEY`,
/// header `Authorization: Key <key>`.
pub struct FalMediaClient {
    base: ApiBase,
}

impl FalMediaClient {
    pub fn new(cfg: MediaClientConfig) -> Self {
        FalMediaClient {
            base: ApiBase::new(
                "fal",
                "FAL_API_KEY",
                "https://queue.fal.run",
                ("Authorization", "Key "),
                500,
                "image",
                cfg,
            ),
        }
    }

    async fn run(
        &self,
        verb: &str,
        args: MediaArgs,
        ctx: &MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        let b = &self.base;
        if args_of(FAL_ARGS, verb).is_none() {
            return Err(unknown(b.name, verb));
        }
        let key = b.key()?;
        let mut job = b.job(ctx);
        let url = b.base();
        let prompt = s(&args, "prompt").unwrap_or_default();
        let (model, body) = if verb == "image" {
            let model = b.model("image", "fal-ai/flux/schnell");
            let mut m = Map::new();
            m.insert("prompt".into(), json!(prompt));
            m.insert("num_images".into(), json!(1));
            if model.contains("schnell") {
                m.insert("image_size".into(), json!("square"));
                m.insert("num_inference_steps".into(), json!(4));
            }
            let (w, h) = (int(&args, "width"), int(&args, "height"));
            if w.is_some() || h.is_some() {
                let w = w.or(h).unwrap_or_default();
                let h = h.or(Some(w)).unwrap_or_default();
                m.insert("image_size".into(), json!({"width": w, "height": h}));
            }
            if let Some(seed) = args.get("seed") {
                m.insert("seed".into(), seed.clone());
            }
            (model, Value::Object(m))
        } else {
            let image = s(&args, "image");
            let model = match self.base.cfg.models.get("video") {
                Some(m) => m.clone(),
                None if image.is_some() => "fal-ai/kling-video/v2.1/standard/image-to-video".into(),
                None => "fal-ai/kling-video/v2.1/standard/text-to-video".into(),
            };
            let mut m = Map::new();
            m.insert("prompt".into(), json!(prompt));
            m.insert(
                "duration".into(),
                json!(int(&args, "seconds").unwrap_or(5).to_string()),
            );
            if let Some(i) = image {
                m.insert("image_url".into(), json!(i));
            }
            if let Some(a) = s(&args, "aspectRatio") {
                m.insert("aspect_ratio".into(), json!(a));
            }
            (model, Value::Object(m))
        };
        let v = b
            .json(
                &job,
                verb,
                "POST",
                &format!("{url}/{model}"),
                json_body(body, ctx),
                &key,
            )
            .await?;
        let Some(id) = job_id_of(v.get("request_id")) else {
            return Err(MediaError::failed(format!(
                "{verb}: no job id at request_id"
            )));
        };
        job.id = Some(id.clone());
        let pid = http::pct_encode(&id);
        let status_url = v
            .get("status_url")
            .and_then(Value::as_str)
            .map(str::to_string)
            .unwrap_or_else(|| format!("{url}/{model}/requests/{pid}/status"));
        let response_url = v
            .get("response_url")
            .and_then(Value::as_str)
            .map(str::to_string)
            .unwrap_or_else(|| format!("{url}/{model}/requests/{pid}"));
        b.poll(&job, ctx, verb, &status_url, &key, |r, st| {
            if st == "COMPLETED" {
                return Some(Ok(()));
            }
            let err = match r.get("error") {
                Some(Value::String(e)) => e.clone(),
                Some(Value::Null) | None => String::new(),
                Some(other) => other.to_string(),
            };
            if err.is_empty() {
                None
            } else {
                Some(Err(format!("job {id} failed: {err}")))
            }
        })
        .await?;
        let out = b
            .json(&job, verb, "GET", &response_url, Body::None, &key)
            .await?;
        let mut urls: Vec<String> = Vec::new();
        for e in out
            .get("images")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            if let Some(u) = e.get("url").and_then(Value::as_str) {
                urls.push(u.to_string());
            }
        }
        for p in ["image.url", "video.url", "audio.url", "audio_file.url"] {
            if let Some(u) = get_path(&out, p).and_then(Value::as_str) {
                urls.push(u.to_string());
            }
        }
        urls.retain(|u| !u.is_empty());
        if urls.is_empty() {
            return Err(
                MediaError::failed(format!("{verb}: no result in response")).with_job(&job.id)
            );
        }
        let g = b.gather(verb, &job);
        let mut files = Vec::new();
        for u in urls {
            files.push(g.gather(Raw::Url(u)).await?);
        }
        Ok(completed(files, None, job.id))
    }
}

impl MediaClient for FalMediaClient {
    fn name(&self) -> &str {
        self.base.name
    }
    fn supports(&self, verb: &str) -> bool {
        args_of(FAL_ARGS, verb).is_some()
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        Some(args_of(FAL_ARGS, verb).unwrap_or_default())
    }
    verbs_via_run!();
}
