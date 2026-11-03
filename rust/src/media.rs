//! Media generation (SPEC §7G, ADR 0036; `source: "media"`): one engine, two
//! faces. Typed verbs a host calls ([`Media::image`], [`Media::video`], …) and
//! tools an LLM calls ([`Media::tools`]). Providers are **styles** — JSON data,
//! never code. Conformance: `conformance/media/`.
//!
//! Cancellation: drop the future, or pass a [`CancellationToken`] to
//! [`Media::generate_with`] (the tool face uses `ToolContext.cancel`).

use std::collections::BTreeMap;
use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use crate::mcp::expand_env_headers;
use crate::tool::{ContentPart, Tool, ToolResult, ToolSource};

// ---------------------------------------------------------------------------
// style (data)
// ---------------------------------------------------------------------------

/// A provider style (§7G). Loaded from JSON; the library names no vendor.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MediaStyle {
    #[serde(rename = "baseUrl")]
    pub base_url: String,
    /// Values may contain `${VAR}` (expanded at call time, unset ⇒ ""; never logged).
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub headers: BTreeMap<String, String>,
    #[serde(
        rename = "errorEnvelope",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub error_envelope: Option<ErrorEnvelope>,
    /// verb ∈ image | video | speech | transcribe | cloneVoice | talking.
    pub verbs: BTreeMap<String, MediaVerb>,
}

/// HTTP-200 error signalling: `codePath` not in `okValues` ⇒ error.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ErrorEnvelope {
    #[serde(rename = "codePath")]
    pub code_path: String,
    #[serde(rename = "okValues", default)]
    pub ok_values: Vec<Value>,
    #[serde(
        rename = "messagePath",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub message_path: String,
}

/// One verb of a style.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MediaVerb {
    /// `sync` | `async`.
    pub mode: String,
    /// `image` | `video` | `audio` | `text`.
    pub kind: String,
    pub start: MediaStart,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub defaults: Option<Value>,
    /// normalised arg → dotPath in the request body.
    #[serde(default)]
    pub args: BTreeMap<String, String>,
    #[serde(rename = "idPath", default, skip_serializing_if = "String::is_empty")]
    pub id_path: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub poll: Option<MediaPoll>,
    #[serde(rename = "resultPath")]
    pub result_path: String,
}

/// The start request.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MediaStart {
    /// Empty ⇒ `POST`.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub method: String,
    pub path: String,
}

/// Async polling.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MediaPoll {
    /// Empty ⇒ `GET`.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub method: String,
    /// Contains `{id}`.
    pub path: String,
    #[serde(rename = "statusPath")]
    pub status_path: String,
    #[serde(default)]
    pub done: Vec<String>,
    #[serde(default)]
    pub failed: Vec<String>,
    #[serde(
        rename = "errorPath",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub error_path: String,
    /// `None` ⇒ 5000.
    #[serde(
        rename = "intervalMs",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub interval_ms: Option<u64>,
    /// `None` ⇒ 600000.
    #[serde(rename = "timeoutMs", default, skip_serializing_if = "Option::is_none")]
    pub timeout_ms: Option<u64>,
}

/// Read one style file (`loadMediaStyle`).
pub fn load_media_style(path: impl AsRef<Path>) -> Result<MediaStyle, MediaError> {
    let p = path.as_ref();
    let text = std::fs::read_to_string(p)
        .map_err(|e| MediaError::Message(format!("media: cannot read {}: {e}", p.display())))?;
    serde_json::from_str(&text)
        .map_err(|e| MediaError::Message(format!("media: bad style {}: {e}", p.display())))
}

// ---------------------------------------------------------------------------
// canonical verbs (conformance/media/verbs.json, embedded verbatim)
// ---------------------------------------------------------------------------

struct ArgSpec {
    name: &'static str,
    ty: &'static str,
    items: Option<&'static str>,
    description: &'static str,
    required: bool,
}

struct VerbSpec {
    verb: &'static str,
    tool: &'static str,
    description: &'static str,
    args: &'static [ArgSpec],
}

const fn a(
    name: &'static str,
    ty: &'static str,
    description: &'static str,
    required: bool,
) -> ArgSpec {
    ArgSpec {
        name,
        ty,
        items: None,
        description,
        required,
    }
}

const VERBS: &[VerbSpec] = &[
    VerbSpec {
        verb: "image",
        tool: "generate_image",
        description: "Generate an image from a text prompt.",
        args: &[
            a("prompt", "string", "What to draw.", true),
            a("width", "integer", "Width in pixels.", false),
            a("height", "integer", "Height in pixels.", false),
            a("aspectRatio", "string", "Aspect ratio, e.g. 16:9.", false),
            a("seed", "integer", "Seed for reproducible output.", false),
        ],
    },
    VerbSpec {
        verb: "video",
        tool: "generate_video",
        description: "Generate a short video from a prompt, optionally starting from an image URL. Can take minutes.",
        args: &[
            a("prompt", "string", "What should happen in the video.", true),
            a("image", "string", "URL of the starting image.", false),
            a("seconds", "integer", "Video length in seconds.", false),
            a("aspectRatio", "string", "Aspect ratio, e.g. 9:16.", false),
        ],
    },
    VerbSpec {
        verb: "speech",
        tool: "generate_speech",
        description: "Speak text aloud and return the audio.",
        args: &[
            a("text", "string", "The text to speak.", true),
            a("voice", "string", "Voice or speaker id.", false),
        ],
    },
    VerbSpec {
        verb: "transcribe",
        tool: "transcribe_audio",
        description: "Transcribe speech in an audio URL to text.",
        args: &[a("audio", "string", "URL of the audio.", true)],
    },
    VerbSpec {
        verb: "cloneVoice",
        tool: "clone_voice",
        description: "Create a cloned voice from audio sample URLs and return its voice id.",
        args: &[
            a("name", "string", "Name for the new voice.", true),
            ArgSpec {
                name: "samples",
                ty: "array",
                items: Some("string"),
                description: "URLs of audio samples of the voice.",
                required: true,
            },
        ],
    },
    VerbSpec {
        verb: "talking",
        tool: "talking_video",
        description: "Make the person in an image speak the given audio (lip-synced video).",
        args: &[
            a("image", "string", "URL of the face image.", true),
            a("audio", "string", "URL of the speech audio.", true),
        ],
    },
];

/// The embedded canonical verb table in `verbs.json` shape (`{"verbs": [...]}`).
#[doc(hidden)]
pub fn canonical_verbs() -> Value {
    let verbs: Vec<Value> = VERBS
        .iter()
        .map(|v| {
            let args: Vec<Value> = v
                .args
                .iter()
                .map(|a| {
                    let mut m = Map::new();
                    m.insert("name".into(), a.name.into());
                    m.insert("type".into(), a.ty.into());
                    if let Some(i) = a.items {
                        m.insert("items".into(), i.into());
                    }
                    m.insert("description".into(), a.description.into());
                    if a.required {
                        m.insert("required".into(), true.into());
                    }
                    Value::Object(m)
                })
                .collect();
            json!({"verb": v.verb, "tool": v.tool, "description": v.description, "args": args})
        })
        .collect();
    json!({ "verbs": verbs })
}

// ---------------------------------------------------------------------------
// results, options, errors
// ---------------------------------------------------------------------------

/// One produced file: `type` image | audio | file.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MediaFile {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(rename = "mimeType")]
    pub mime_type: String,
    pub url: String,
}

/// What every verb returns.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MediaResult {
    pub files: Vec<MediaFile>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(rename = "jobId", default, skip_serializing_if = "Option::is_none")]
    pub job_id: Option<String>,
    pub status: String,
}

/// One poll observation passed to `on_progress`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaProgress {
    pub verb: String,
    pub job_id: String,
    pub status: String,
}

/// Progress callback.
pub type OnProgressFn = Arc<dyn Fn(&MediaProgress) + Send + Sync>;

/// `createMedia` options.
#[derive(Clone, Default)]
pub struct MediaOptions {
    pub on_progress: Option<OnProgressFn>,
    /// The same injection seam as `ClientOptions.http_client`; `None` ⇒ a default client.
    pub http_client: Option<reqwest::Client>,
}

/// An engine failure. `Message` displays the exact §7G error string.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum MediaError {
    #[error("{0}")]
    Message(String),
    /// Stopped by the cancellation token.
    #[error("media: cancelled")]
    Cancelled,
}

fn err<T>(m: impl Into<String>) -> Result<T, MediaError> {
    Err(MediaError::Message(m.into()))
}

// ---------------------------------------------------------------------------
// typed inputs
// ---------------------------------------------------------------------------

macro_rules! input {
    ($(#[$m:meta])* $name:ident { $($(#[$fm:meta])* $f:ident : $t:ty),* $(,)? }) => {
        $(#[$m])*
        #[derive(Debug, Clone, Default, PartialEq, Serialize)]
        pub struct $name {
            $($(#[$fm])* pub $f: $t,)*
            /// Top-level keys overwrite the request body last (function face only).
            #[serde(skip)]
            pub provider_options: Option<Map<String, Value>>,
        }
    };
}

input!(
    /// `image` arguments.
    ImageInput {
        prompt: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        width: Option<i64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        height: Option<i64>,
        #[serde(rename = "aspectRatio", skip_serializing_if = "Option::is_none")]
        aspect_ratio: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        seed: Option<i64>,
    }
);
input!(
    /// `video` arguments.
    VideoInput {
        prompt: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        image: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        seconds: Option<i64>,
        #[serde(rename = "aspectRatio", skip_serializing_if = "Option::is_none")]
        aspect_ratio: Option<String>,
    }
);
input!(
    /// `speech` arguments.
    SpeechInput {
        text: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        voice: Option<String>,
    }
);
input!(
    /// `transcribe` arguments.
    TranscribeInput { audio: String }
);
input!(
    /// `cloneVoice` arguments.
    CloneVoiceInput { name: String, samples: Vec<String> }
);
input!(
    /// `talking` arguments.
    TalkingInput { image: String, audio: String }
);

fn to_args<T: Serialize>(t: &T) -> Map<String, Value> {
    match serde_json::to_value(t) {
        Ok(Value::Object(m)) => m,
        _ => Map::new(),
    }
}

// ---------------------------------------------------------------------------
// dotPath
// ---------------------------------------------------------------------------

fn get_path<'a>(v: &'a Value, path: &str) -> Option<&'a Value> {
    let mut cur = v;
    for seg in path.split('.') {
        cur = if is_index(seg) {
            cur.as_array()?.get(seg.parse::<usize>().ok()?)?
        } else {
            cur.as_object()?.get(seg)?
        };
    }
    Some(cur)
}

fn is_index(seg: &str) -> bool {
    !seg.is_empty() && seg.bytes().all(|b| b.is_ascii_digit())
}

fn set_path(root: &mut Value, path: &str, val: Value) {
    let segs: Vec<&str> = path.split('.').collect();
    let mut cur = root;
    for (i, seg) in segs.iter().enumerate() {
        let last = i + 1 == segs.len();
        let fresh = || {
            if !last && is_index(segs[i + 1]) {
                Value::Array(Vec::new())
            } else {
                Value::Object(Map::new())
            }
        };
        if is_index(seg) {
            let idx: usize = seg.parse().unwrap_or(0);
            if !cur.is_array() {
                *cur = Value::Array(Vec::new());
            }
            let arr = cur.as_array_mut().expect("array");
            while arr.len() <= idx {
                arr.push(Value::Null);
            }
            if last {
                arr[idx] = val;
                return;
            }
            if !(arr[idx].is_object() || arr[idx].is_array()) {
                arr[idx] = fresh();
            }
            cur = &mut arr[idx];
        } else {
            if !cur.is_object() {
                *cur = Value::Object(Map::new());
            }
            let obj = cur.as_object_mut().expect("object");
            if last {
                obj.insert((*seg).to_string(), val);
                return;
            }
            let slot = obj.entry((*seg).to_string()).or_insert_with(fresh);
            if !(slot.is_object() || slot.is_array()) {
                *slot = fresh();
            }
            cur = slot;
        }
    }
}

fn is_integral(n: &serde_json::Number) -> bool {
    n.is_i64()
        || n.is_u64()
        || n.as_f64()
            .is_some_and(|f| f.fract() == 0.0 && f.abs() < 1e18)
}

/// A number as integer text when integral, else as JSON renders it.
fn num_text(n: &serde_json::Number) -> String {
    if let Some(i) = n.as_i64() {
        return i.to_string();
    }
    if let Some(u) = n.as_u64() {
        return u.to_string();
    }
    match n.as_f64() {
        Some(f) if f.fract() == 0.0 && f.abs() < 1e18 => (f as i64).to_string(),
        _ => n.to_string(),
    }
}

// ---------------------------------------------------------------------------
// file mapping
// ---------------------------------------------------------------------------

fn mime_for(url: &str, kind: &str) -> String {
    let no_frag = url.split('#').next().unwrap_or("");
    let no_q = no_frag.split('?').next().unwrap_or("");
    let path = match no_q.find("://") {
        Some(i) => {
            let rest = &no_q[i + 3..];
            rest.find('/').map(|j| &rest[j..]).unwrap_or("")
        }
        None => no_q,
    };
    let last = path.rsplit('/').next().unwrap_or("");
    let ext = last
        .rsplit_once('.')
        .map(|(_, e)| e.to_ascii_lowercase())
        .unwrap_or_default();
    let m = match ext.as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "gif" => "image/gif",
        "mp4" => "video/mp4",
        "webm" => "video/webm",
        "mov" => "video/quicktime",
        "mp3" => "audio/mpeg",
        "wav" => "audio/wav",
        "ogg" => "audio/ogg",
        "m4a" => "audio/mp4",
        "flac" => "audio/flac",
        _ => match kind {
            "video" => "video/mp4",
            "audio" => "audio/mpeg",
            _ => "image/png",
        },
    };
    m.to_string()
}

fn part_type(kind: &str) -> &'static str {
    match kind {
        "image" => "image",
        "audio" => "audio",
        _ => "file",
    }
}

fn collect_urls(v: &Value, out: &mut Vec<String>) {
    match v {
        Value::String(s) if !s.is_empty() => out.push(s.clone()),
        Value::Array(a) => a.iter().for_each(|x| collect_urls(x, out)),
        _ => {}
    }
}

// ---------------------------------------------------------------------------
// engine
// ---------------------------------------------------------------------------

struct Inner {
    styles: Vec<MediaStyle>,
    on_progress: Option<OnProgressFn>,
    http: reqwest::Client,
}

/// The media engine (`createMedia`). Cheap to clone.
#[derive(Clone)]
pub struct Media {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for Media {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Media").finish_non_exhaustive()
    }
}

/// `createMedia(styles, opts)`: a verb resolves to the first style that declares it.
pub fn create_media(styles: impl Into<Vec<MediaStyle>>, opts: MediaOptions) -> Media {
    Media::new(styles, opts)
}

impl From<MediaStyle> for Vec<MediaStyle> {
    fn from(s: MediaStyle) -> Self {
        vec![s]
    }
}

impl Media {
    /// One style or a list of styles.
    pub fn new(styles: impl Into<Vec<MediaStyle>>, opts: MediaOptions) -> Self {
        Media {
            inner: Arc::new(Inner {
                styles: styles.into(),
                on_progress: opts.on_progress,
                http: opts.http_client.unwrap_or_default(),
            }),
        }
    }

    fn resolve(&self, verb: &str) -> Option<(&MediaStyle, &MediaVerb)> {
        self.inner
            .styles
            .iter()
            .find_map(|s| s.verbs.get(verb).map(|v| (s, v)))
    }

    pub async fn image(&self, input: ImageInput) -> Result<MediaResult, MediaError> {
        self.typed("image", to_args(&input), input.provider_options)
            .await
    }
    pub async fn video(&self, input: VideoInput) -> Result<MediaResult, MediaError> {
        self.typed("video", to_args(&input), input.provider_options)
            .await
    }
    pub async fn speech(&self, input: SpeechInput) -> Result<MediaResult, MediaError> {
        self.typed("speech", to_args(&input), input.provider_options)
            .await
    }
    pub async fn transcribe(&self, input: TranscribeInput) -> Result<MediaResult, MediaError> {
        self.typed("transcribe", to_args(&input), input.provider_options)
            .await
    }
    pub async fn clone_voice(&self, input: CloneVoiceInput) -> Result<MediaResult, MediaError> {
        self.typed("cloneVoice", to_args(&input), input.provider_options)
            .await
    }
    pub async fn talking(&self, input: TalkingInput) -> Result<MediaResult, MediaError> {
        self.typed("talking", to_args(&input), input.provider_options)
            .await
    }

    async fn typed(
        &self,
        verb: &str,
        args: Map<String, Value>,
        po: Option<Map<String, Value>>,
    ) -> Result<MediaResult, MediaError> {
        self.generate_with(verb, args, po, &CancellationToken::new())
            .await
    }

    /// The generic verb call: `generate(verb, args)`.
    pub async fn generate(
        &self,
        verb: &str,
        args: Map<String, Value>,
    ) -> Result<MediaResult, MediaError> {
        self.generate_with(verb, args, None, &CancellationToken::new())
            .await
    }

    /// [`Media::generate`] with `providerOptions` and a cancellation token.
    pub async fn generate_with(
        &self,
        verb: &str,
        args: Map<String, Value>,
        provider_options: Option<Map<String, Value>>,
        cancel: &CancellationToken,
    ) -> Result<MediaResult, MediaError> {
        tokio::select! {
            biased;
            _ = cancel.cancelled() => Err(MediaError::Cancelled),
            r = self.run(verb, args, provider_options) => r,
        }
    }

    async fn run(
        &self,
        verb: &str,
        args: Map<String, Value>,
        provider_options: Option<Map<String, Value>>,
    ) -> Result<MediaResult, MediaError> {
        let args: Map<String, Value> = args.into_iter().filter(|(_, v)| !v.is_null()).collect();
        let Some((style, vs)) = self.resolve(verb) else {
            return err(format!("media: no style supports {verb:?}"));
        };
        if let Some(k) = args.keys().find(|k| !vs.args.contains_key(*k)) {
            return err(format!(
                "{verb}: argument {k:?} not supported by this style"
            ));
        }
        let mut body = vs
            .defaults
            .clone()
            .unwrap_or_else(|| Value::Object(Map::new()));
        for (k, v) in &args {
            set_path(&mut body, &vs.args[k], v.clone());
        }
        if let Some(po) = provider_options {
            if !body.is_object() {
                body = Value::Object(Map::new());
            }
            let obj = body.as_object_mut().expect("object");
            for (k, v) in po {
                obj.insert(k, v);
            }
        }
        let method = if vs.start.method.is_empty() {
            "POST"
        } else {
            &vs.start.method
        };
        let start = self
            .request(style, verb, method, &vs.start.path, Some(&body))
            .await?;

        let (final_resp, status, job_id) = if vs.mode == "async" {
            let job_id = match get_path(&start, &vs.id_path) {
                Some(Value::String(s)) if !s.is_empty() => s.clone(),
                Some(Value::Number(n)) if is_integral(n) => num_text(n),
                _ => return err(format!("{verb}: no job id at {}", vs.id_path)),
            };
            let poll = vs.poll.clone().unwrap_or_default();
            let interval = Duration::from_millis(poll.interval_ms.unwrap_or(5000));
            let timeout_ms = poll.timeout_ms.unwrap_or(600_000);
            let method = if poll.method.is_empty() {
                "GET"
            } else {
                &poll.method
            };
            let path = poll.path.replace("{id}", &job_id);
            let began = Instant::now();
            loop {
                tokio::time::sleep(interval).await;
                let r = self.request(style, verb, method, &path, None).await?;
                let st = match get_path(&r, &poll.status_path) {
                    Some(Value::String(s)) => s.clone(),
                    _ => String::new(),
                };
                if let Some(cb) = &self.inner.on_progress {
                    cb(&MediaProgress {
                        verb: verb.to_string(),
                        job_id: job_id.clone(),
                        status: st.clone(),
                    });
                }
                if poll.done.contains(&st) {
                    break (r, st, Some(job_id));
                }
                if poll.failed.contains(&st) {
                    let msg = match get_path(&r, &poll.error_path) {
                        _ if poll.error_path.is_empty() => String::new(),
                        Some(Value::String(s)) => s.clone(),
                        Some(Value::Number(n)) => num_text(n),
                        Some(Value::Null) | None => String::new(),
                        Some(other) => other.to_string(),
                    };
                    return if msg.is_empty() {
                        err(format!("job {job_id} {st}"))
                    } else {
                        err(format!("job {job_id} {st}: {msg}"))
                    };
                }
                if began.elapsed() >= Duration::from_millis(timeout_ms) {
                    return err(format!("job {job_id} timed out after {timeout_ms}ms"));
                }
            }
        } else {
            (start, "completed".to_string(), None)
        };

        let found = get_path(&final_resp, &vs.result_path);
        let no_result = || err(format!("{verb}: no result at {}", vs.result_path));
        if vs.kind == "text" {
            let text = match found {
                Some(Value::String(s)) => s.clone(),
                Some(Value::Number(n)) => num_text(n),
                _ => return no_result(),
            };
            return Ok(MediaResult {
                files: Vec::new(),
                text: Some(text),
                job_id,
                status,
            });
        }
        let mut urls = Vec::new();
        if let Some(v) = found {
            collect_urls(v, &mut urls);
        }
        if urls.is_empty() {
            return no_result();
        }
        let files = urls
            .into_iter()
            .map(|u| MediaFile {
                kind: part_type(&vs.kind).into(),
                mime_type: mime_for(&u, &vs.kind),
                url: u,
            })
            .collect();
        Ok(MediaResult {
            files,
            text: None,
            job_id,
            status,
        })
    }

    async fn request(
        &self,
        style: &MediaStyle,
        verb: &str,
        method: &str,
        path: &str,
        body: Option<&Value>,
    ) -> Result<Value, MediaError> {
        let url = format!("{}{}", style.base_url.trim_end_matches('/'), path);
        let m = reqwest::Method::from_bytes(method.to_ascii_uppercase().as_bytes())
            .map_err(|e| MediaError::Message(format!("{verb}: bad method {method:?}: {e}")))?;
        let mut req = self.inner.http.request(m, &url);
        if let Some(b) = body {
            req = req
                .header("Content-Type", "application/json")
                .body(b.to_string());
        }
        for (k, v) in expand_env_headers(&style.headers) {
            req = req.header(k, v);
        }
        let resp = req
            .send()
            .await
            .map_err(|e| MediaError::Message(format!("{verb}: {e}")))?;
        let status = resp.status().as_u16();
        let text = resp
            .text()
            .await
            .map_err(|e| MediaError::Message(format!("{verb}: {e}")))?;
        if !(200..300).contains(&status) {
            return err(format!("HTTP {status}: {text}"));
        }
        let Ok(v) = serde_json::from_str::<Value>(&text) else {
            return err(format!("{verb}: response is not JSON"));
        };
        if let Some(env) = &style.error_envelope
            && let Some(code) = get_path(&v, &env.code_path)
            && !env.ok_values.iter().any(|ok| json_eq(ok, code))
        {
            let code_s = match code {
                Value::Number(n) => num_text(n),
                Value::String(s) => s.clone(),
                other => other.to_string(),
            };
            let msg = if env.message_path.is_empty() {
                String::new()
            } else {
                match get_path(&v, &env.message_path) {
                    Some(Value::String(s)) => s.clone(),
                    _ => String::new(),
                }
            };
            return err(format!("API error {code_s}: {msg}"));
        }
        Ok(v)
    }

    /// One `source: "media"` tool per verb the styles support, in `verbs.json` order.
    pub fn tools(&self) -> Vec<Tool> {
        VERBS
            .iter()
            .filter_map(|spec| {
                let (_, vs) = self.resolve(spec.verb)?;
                Some(self.tool_for(spec, vs))
            })
            .collect()
    }

    fn tool_for(&self, spec: &'static VerbSpec, vs: &MediaVerb) -> Tool {
        let mut props = Map::new();
        let mut required = Vec::new();
        for arg in spec.args.iter().filter(|a| vs.args.contains_key(a.name)) {
            let p = match arg.items {
                Some(items) => {
                    json!({"type": arg.ty, "items": {"type": items}, "description": arg.description})
                }
                None => json!({"type": arg.ty, "description": arg.description}),
            };
            props.insert(arg.name.into(), p);
            if arg.required {
                required.push(Value::String(arg.name.into()));
            }
        }
        let me = self.clone();
        let verb = spec.verb;
        Tool {
            name: spec.tool.into(),
            description: spec.description.into(),
            input_schema: json!({"type": "object", "properties": props, "required": required}),
            source: ToolSource::Media,
            hints: None,
            // Each call is an independent remote job: always concurrency-safe
            // (§7G, §8 Tool scheduling).
            concurrency_safe: Some(Arc::new(|_| true)),
            execute: Arc::new(move |args, ctx| {
                let me = me.clone();
                Box::pin(async move {
                    let args = match args {
                        Value::Object(m) => m,
                        _ => Map::new(),
                    };
                    match me.generate_with(verb, args, None, &ctx.cancel).await {
                        Ok(r) => tool_result(verb, r),
                        Err(e) => ToolResult::error(e.to_string()),
                    }
                })
            }),
        }
    }
}

fn tool_result(verb: &str, r: MediaResult) -> ToolResult {
    let mut meta = Map::new();
    meta.insert("source".into(), "media".into());
    meta.insert("verb".into(), verb.into());
    if let Some(id) = &r.job_id {
        meta.insert("jobId".into(), id.clone().into());
    }
    meta.insert("status".into(), r.status.clone().into());
    if let Some(text) = r.text {
        return ToolResult::ok(text).with_metadata(meta);
    }
    let word = match r.files.first().map(|f| f.kind.as_str()) {
        Some("image") => "image",
        Some("audio") => "audio",
        _ => "video",
    };
    let urls: Vec<&str> = r.files.iter().map(|f| f.url.as_str()).collect();
    let mut out = ToolResult::ok(format!("{word} ready: {}", urls.join("\n"))).with_metadata(meta);
    out.parts = r
        .files
        .iter()
        .map(|f| ContentPart {
            kind: f.kind.clone(),
            mime_type: f.mime_type.clone(),
            url: f.url.clone(),
            ..Default::default()
        })
        .collect();
    out
}

/// JSON equality with numbers compared by value (0 == 0.0).
fn json_eq(a: &Value, b: &Value) -> bool {
    match (a, b) {
        (Value::Number(x), Value::Number(y)) => x.as_f64() == y.as_f64(),
        (Value::Array(x), Value::Array(y)) => {
            x.len() == y.len() && x.iter().zip(y).all(|(p, q)| json_eq(p, q))
        }
        (Value::Object(x), Value::Object(y)) => {
            x.len() == y.len()
                && x.iter()
                    .all(|(k, v)| y.get(k).is_some_and(|w| json_eq(v, w)))
        }
        _ => a == b,
    }
}
