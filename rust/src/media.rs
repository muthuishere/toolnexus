//! Media generation (SPEC §7G, ADRs 0036 + 0037; `source: "media"`): one
//! interface, two faces. Typed verbs a host calls ([`Media::image`],
//! [`Media::video`], …) and tools an LLM calls ([`Media::tools`]).
//!
//! Providers are **media clients** — anything implementing [`MediaClient`]:
//!
//! - built-in API clients: [`A2EMediaClient`], [`ElevenLabsMediaClient`],
//!   [`OpenAIMediaClient`], [`OpenRouterMediaClient`], [`FalMediaClient`];
//! - CLI clients over a local subscription CLI: [`GrokMediaClient`],
//!   [`CodexMediaClient`] (both built on [`CliMediaClient`]);
//! - style clients over a JSON style ([`load_media_style`] + [`style_media_client`]);
//! - your own type implementing [`MediaClient`].
//!
//! [`create_media`] routes each verb to the first client that supports it and
//! falls through to the next one **only** when a client is
//! [`MediaError::Unavailable`] (no key, no credits, quota, CLI missing, …).
//!
//! Cancellation: every verb takes a [`CancellationToken`] (`*_with`, or
//! [`MediaCtx::cancel`]); the tool face uses `ToolContext.cancel`. A CLI run is
//! killed (its whole process group on Unix) when cancelled or past its deadline.
//! Conformance: `conformance/media/`.

use std::collections::BTreeMap;
use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::{Arc, OnceLock};
use std::time::Instant;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use tokio_util::sync::CancellationToken;

use crate::tool::{ContentPart, Tool, ToolResult, ToolSource};

/// Implements the seven verb methods of [`MediaClient`] by calling
/// `self.run(verb, args, ctx)`, each stopped early by `ctx.cancel`.
macro_rules! verbs_via_run {
    () => {
        fn image<'a>(
            &'a self,
            args: $crate::media::MediaArgs,
            ctx: &'a $crate::media::MediaCtx,
        ) -> $crate::media::MediaFuture<'a> {
            Box::pin($crate::media::cancellable(
                ctx,
                self.run("image", args, ctx),
            ))
        }
        fn video<'a>(
            &'a self,
            args: $crate::media::MediaArgs,
            ctx: &'a $crate::media::MediaCtx,
        ) -> $crate::media::MediaFuture<'a> {
            Box::pin($crate::media::cancellable(
                ctx,
                self.run("video", args, ctx),
            ))
        }
        fn speech<'a>(
            &'a self,
            args: $crate::media::MediaArgs,
            ctx: &'a $crate::media::MediaCtx,
        ) -> $crate::media::MediaFuture<'a> {
            Box::pin($crate::media::cancellable(
                ctx,
                self.run("speech", args, ctx),
            ))
        }
        fn music<'a>(
            &'a self,
            args: $crate::media::MediaArgs,
            ctx: &'a $crate::media::MediaCtx,
        ) -> $crate::media::MediaFuture<'a> {
            Box::pin($crate::media::cancellable(
                ctx,
                self.run("music", args, ctx),
            ))
        }
        fn transcribe<'a>(
            &'a self,
            args: $crate::media::MediaArgs,
            ctx: &'a $crate::media::MediaCtx,
        ) -> $crate::media::MediaFuture<'a> {
            Box::pin($crate::media::cancellable(
                ctx,
                self.run("transcribe", args, ctx),
            ))
        }
        fn clone_voice<'a>(
            &'a self,
            args: $crate::media::MediaArgs,
            ctx: &'a $crate::media::MediaCtx,
        ) -> $crate::media::MediaFuture<'a> {
            Box::pin($crate::media::cancellable(
                ctx,
                self.run("cloneVoice", args, ctx),
            ))
        }
        fn talk<'a>(
            &'a self,
            args: $crate::media::MediaArgs,
            ctx: &'a $crate::media::MediaCtx,
        ) -> $crate::media::MediaFuture<'a> {
            Box::pin($crate::media::cancellable(ctx, self.run("talk", args, ctx)))
        }
    };
}

mod api;
mod cli;
mod http;
mod style;

pub use api::{
    A2EMediaClient, ElevenLabsMediaClient, FalMediaClient, OpenAIMediaClient, OpenRouterMediaClient,
};
pub use cli::{
    CliEvent, CliMediaClient, CliProfile, CodexMediaClient, GrokMediaClient, fold_media_prompt,
};
pub use style::{
    ErrorEnvelope, LoadedStyle, MEDIA_MOCK_AVAILABLE_ENV, MediaAvailability, MediaPoll, MediaStart,
    MediaStyle, MediaVerb, StyleMediaClient, load_media_style, load_media_style_with,
    style_media_client,
};

// ---------------------------------------------------------------------------
// canonical verbs (conformance/media/verbs.json, embedded verbatim)
// ---------------------------------------------------------------------------

const VERBS_JSON: &str = include_str!("media/verbs.json");
const A2E_JSON: &str = include_str!("media/a2e.json");

/// The embedded `verbs.json` (byte copy of `conformance/media/verbs.json`).
#[doc(hidden)]
pub fn verbs_json() -> &'static str {
    VERBS_JSON
}

/// The embedded a2e preset (byte copy of `examples/media-styles/a2e.json`).
#[doc(hidden)]
pub fn a2e_style_json() -> &'static str {
    A2E_JSON
}

/// The seven verbs, in `verbs.json` order.
pub const MEDIA_VERBS: [&str; 7] = [
    "image",
    "video",
    "speech",
    "music",
    "transcribe",
    "cloneVoice",
    "talk",
];

#[derive(Deserialize)]
pub(crate) struct VerbSpec {
    pub(crate) verb: String,
    pub(crate) tool: String,
    pub(crate) description: String,
    pub(crate) args: Vec<ArgSpec>,
}

#[derive(Deserialize)]
pub(crate) struct ArgSpec {
    pub(crate) name: String,
    #[serde(rename = "type")]
    pub(crate) ty: String,
    #[serde(default)]
    pub(crate) items: Option<String>,
    pub(crate) description: String,
    #[serde(default)]
    pub(crate) required: bool,
}

#[derive(Deserialize)]
struct VerbsFile {
    verbs: Vec<VerbSpec>,
}

pub(crate) fn verb_specs() -> &'static [VerbSpec] {
    static V: OnceLock<Vec<VerbSpec>> = OnceLock::new();
    V.get_or_init(|| {
        serde_json::from_str::<VerbsFile>(VERBS_JSON)
            .expect("embedded verbs.json")
            .verbs
    })
}

pub(crate) fn verb_spec(verb: &str) -> Option<&'static VerbSpec> {
    verb_specs().iter().find(|v| v.verb == verb)
}

/// Every canonical arg of `verb`, with its canonical `required`.
pub(crate) fn canonical_args(verb: &str) -> Vec<MediaArg> {
    verb_spec(verb)
        .map(|s| {
            s.args
                .iter()
                .map(|a| MediaArg::new(&a.name, a.required))
                .collect()
        })
        .unwrap_or_default()
}

/// The result kind of a media verb: `image` | `video` | `audio` | `text`.
pub(crate) fn verb_kind(verb: &str) -> &'static str {
    match verb {
        "image" => "image",
        "video" | "talk" => "video",
        "speech" | "music" => "audio",
        _ => "text",
    }
}

// ---------------------------------------------------------------------------
// results, progress, context, errors
// ---------------------------------------------------------------------------

/// Verb arguments (canonical names, §7G.1).
pub type MediaArgs = Map<String, Value>;

/// One produced file: `type` image | audio | file (video). Which of
/// `url` / `path` / `data` are set is decided by gather (§7G.5).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MediaFile {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(rename = "mimeType")]
    pub mime_type: String,
    /// An `http(s)://` URL or a `file://` URL.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub url: String,
    /// An absolute local path.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub path: String,
    /// Standard padded base64 of the bytes.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub data: String,
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
    /// What the provider reported (§7G.12 D21); never estimated.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub usage: Option<MediaUsage>,
    /// Who served the call (§7G.10b): the preset id, an HTTP style's name
    /// (else `style`), or the client's name. Set by the router.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub style: Option<String>,
    /// The configured model for the verb of a loaded style; never guessed.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
}

/// Provider-reported usage of one media call (§7G.12 D21). A field the
/// provider omits stays `None`; Grok's `total_cost_usd_ticks` stays unconverted in `raw`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MediaUsage {
    #[serde(
        rename = "inputTokens",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub input_tokens: Option<i64>,
    #[serde(
        rename = "outputTokens",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub output_tokens: Option<i64>,
    #[serde(rename = "costUsd", default, skip_serializing_if = "Option::is_none")]
    pub cost_usd: Option<f64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub raw: Option<Map<String, Value>>,
}

impl MediaUsage {
    /// `Some` only when at least one field is set.
    pub(crate) fn nonempty(self) -> Option<Self> {
        (self != MediaUsage::default()).then_some(self)
    }
}

/// One progress observation (a poll status, a CLI event).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaProgress {
    pub verb: String,
    pub client: String,
    pub job_id: Option<String>,
    pub status: String,
    pub elapsed_ms: u64,
}

/// Progress callback.
pub type OnProgressFn = Arc<dyn Fn(&MediaProgress) + Send + Sync>;

/// Per-call context (§7G.2 `ctx`).
#[derive(Clone, Default)]
pub struct MediaCtx {
    /// Native cancellation.
    pub cancel: CancellationToken,
    /// Absolute deadline; the job deadline is the earlier of it and `loop.timeoutMs`.
    pub deadline: Option<Instant>,
    /// Called for every progress status; when `None`, the client's `loop.on_progress` is.
    pub progress: Option<OnProgressFn>,
    /// Top-level keys overwrite the JSON body last (function face only).
    pub provider_options: Option<MediaArgs>,
}

/// A media failure (§7G.2). `Display` is the exact §7G message.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum MediaError {
    #[error("{message}")]
    Failed {
        message: String,
        job_id: Option<String>,
    },
    #[error("{message}")]
    Timeout {
        message: String,
        job_id: Option<String>,
    },
    /// The call was valid but this client cannot serve it now (no key, no
    /// credits, quota, CLI missing, ZDR, …). The only error `createMedia`
    /// falls through on.
    #[error("{message}")]
    Unavailable { message: String },
    /// Stopped by the cancellation token.
    #[error("media: cancelled")]
    Cancelled,
}

impl MediaError {
    /// `failed` | `timeout` | `cancelled` | `unavailable`.
    pub fn status(&self) -> &'static str {
        match self {
            MediaError::Failed { .. } => "failed",
            MediaError::Timeout { .. } => "timeout",
            MediaError::Unavailable { .. } => "unavailable",
            MediaError::Cancelled => "cancelled",
        }
    }
    pub fn is_unavailable(&self) -> bool {
        matches!(self, MediaError::Unavailable { .. })
    }
    pub fn job_id(&self) -> Option<&str> {
        match self {
            MediaError::Failed { job_id, .. } | MediaError::Timeout { job_id, .. } => {
                job_id.as_deref()
            }
            _ => None,
        }
    }
    /// A `failed` error.
    pub fn failed(message: impl Into<String>) -> Self {
        MediaError::Failed {
            message: message.into(),
            job_id: None,
        }
    }
    /// An `unavailable` error.
    pub fn unavailable(message: impl Into<String>) -> Self {
        MediaError::Unavailable {
            message: message.into(),
        }
    }
    pub(crate) fn timeout(message: impl Into<String>, job_id: Option<String>) -> Self {
        MediaError::Timeout {
            message: message.into(),
            job_id,
        }
    }
    pub(crate) fn with_job(self, id: &Option<String>) -> Self {
        match self {
            MediaError::Failed { message, job_id } => MediaError::Failed {
                message,
                job_id: job_id.or_else(|| id.clone()),
            },
            other => other,
        }
    }
}

// ---------------------------------------------------------------------------
// shared client config (§7G.4)
// ---------------------------------------------------------------------------

/// `gather.mode` (§7G.5).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GatherMode {
    /// Keep provider URLs as given (bytes / base64 / data URLs are still saved).
    Url,
    /// Save every file under `gather.dir`.
    Download,
    /// Return the bytes as base64 `data`.
    Inline,
}

/// `gather` config.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct MediaGather {
    /// `None` ⇒ the client's default (`Download`; style clients `Url`).
    pub mode: Option<GatherMode>,
    /// `None` ⇒ `<os temp dir>/toolnexus-media`, created when missing.
    pub dir: Option<PathBuf>,
    /// `gather.name`: saved files are named `<slug><ext>`, then
    /// `<slug>-2<ext>`, … (first free name). `slug` keeps only
    /// `[A-Za-z0-9._-]` (others ⇒ `-`), leading `.`/`-` dropped; empty ⇒ default naming.
    pub name: Option<String>,
    /// `None` ⇒ 104857600 (100 MiB).
    pub max_bytes: Option<u64>,
    /// CLI clients: `Some(false)` deletes the CLI's original after a copy.
    pub keep: Option<bool>,
}

/// `loop` config. Values absent or `<= 0` ⇒ the default.
#[derive(Clone, Default)]
pub struct MediaLoop {
    pub interval_ms: Option<i64>,
    pub timeout_ms: Option<i64>,
    /// Progress callback when `ctx.progress` is absent.
    pub on_progress: Option<OnProgressFn>,
}

/// One config shape for every built-in client (§7G.4). Fields a client does
/// not use are ignored.
#[derive(Clone, Default)]
pub struct MediaClientConfig {
    /// The model of the client's primary verb (CLI: `-m <model>`).
    pub model: Option<String>,
    /// Per-verb model override; wins over `model` and the default.
    pub models: BTreeMap<String, String>,
    /// API key; `None` ⇒ the client's env var, read at call time. Never logged.
    pub key: Option<String>,
    /// Base URL override (trailing `/` trimmed).
    pub url: Option<String>,
    /// CLI argv prefix; empty ⇒ the CLI's name.
    pub command: Vec<String>,
    pub r#loop: MediaLoop,
    pub gather: MediaGather,
    /// The same HTTP-client injection seam as the LLM client.
    pub http_client: Option<reqwest::Client>,
}

pub(crate) fn positive(v: Option<i64>) -> Option<u64> {
    v.filter(|n| *n > 0).map(|n| n as u64)
}

pub(crate) fn report(
    ctx: &MediaCtx,
    cfg: &MediaClientConfig,
    verb: &str,
    client: &str,
    job_id: Option<&str>,
    status: &str,
    start: Instant,
) {
    let cb = ctx.progress.as_ref().or(cfg.r#loop.on_progress.as_ref());
    if let Some(cb) = cb {
        cb(&MediaProgress {
            verb: verb.to_string(),
            client: client.to_string(),
            job_id: job_id.map(str::to_string),
            status: status.to_string(),
            elapsed_ms: start.elapsed().as_millis() as u64,
        });
    }
}

// ---------------------------------------------------------------------------
// MediaClient
// ---------------------------------------------------------------------------

/// The boxed future every verb returns.
pub type MediaFuture<'a> =
    Pin<Box<dyn Future<Output = Result<MediaResult, MediaError>> + Send + 'a>>;

/// One accepted canonical argument of a verb.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaArg {
    pub name: String,
    pub required: bool,
}

impl MediaArg {
    pub fn new(name: impl Into<String>, required: bool) -> Self {
        MediaArg {
            name: name.into(),
            required,
        }
    }
}

fn unsupported<'a>(name: &str, verb: &str) -> MediaFuture<'a> {
    let m = format!("{name}: {verb} is not supported");
    Box::pin(async move { Err(MediaError::failed(m)) })
}

/// A media provider (§7G.2). Every verb is optional: implement what you
/// support and say so in [`MediaClient::supports`].
pub trait MediaClient: Send + Sync {
    /// The client's name in messages and tool metadata.
    fn name(&self) -> &str;
    /// Whether this client serves `verb`.
    fn supports(&self, verb: &str) -> bool;
    /// The canonical args this client accepts for `verb`; `None` ⇒ every
    /// canonical arg, with the canonical `required` flags.
    fn args(&self, _verb: &str) -> Option<Vec<MediaArg>> {
        None
    }
    fn image<'a>(&'a self, _args: MediaArgs, _ctx: &'a MediaCtx) -> MediaFuture<'a> {
        unsupported(self.name(), "image")
    }
    fn video<'a>(&'a self, _args: MediaArgs, _ctx: &'a MediaCtx) -> MediaFuture<'a> {
        unsupported(self.name(), "video")
    }
    fn speech<'a>(&'a self, _args: MediaArgs, _ctx: &'a MediaCtx) -> MediaFuture<'a> {
        unsupported(self.name(), "speech")
    }
    fn music<'a>(&'a self, _args: MediaArgs, _ctx: &'a MediaCtx) -> MediaFuture<'a> {
        unsupported(self.name(), "music")
    }
    fn transcribe<'a>(&'a self, _args: MediaArgs, _ctx: &'a MediaCtx) -> MediaFuture<'a> {
        unsupported(self.name(), "transcribe")
    }
    fn clone_voice<'a>(&'a self, _args: MediaArgs, _ctx: &'a MediaCtx) -> MediaFuture<'a> {
        unsupported(self.name(), "cloneVoice")
    }
    fn talk<'a>(&'a self, _args: MediaArgs, _ctx: &'a MediaCtx) -> MediaFuture<'a> {
        unsupported(self.name(), "talk")
    }
    /// True only for a loaded [`MediaStyle`] (the unsupported-verb message
    /// says "style" when every source is one).
    #[doc(hidden)]
    fn is_media_style(&self) -> bool {
        false
    }
    /// `MediaResult.style` for a call this source served (default: its name).
    #[doc(hidden)]
    fn source_id(&self) -> String {
        self.name().to_string()
    }
    /// `MediaResult.model` for `verb` (default: none).
    #[doc(hidden)]
    fn source_model(&self, _verb: &str) -> Option<String> {
        None
    }
}

/// Call `verb` on `c` (the generic dispatch).
pub fn call_media_verb<'a>(
    c: &'a dyn MediaClient,
    verb: &str,
    args: MediaArgs,
    ctx: &'a MediaCtx,
) -> MediaFuture<'a> {
    match verb {
        "image" => c.image(args, ctx),
        "video" => c.video(args, ctx),
        "speech" => c.speech(args, ctx),
        "music" => c.music(args, ctx),
        "transcribe" => c.transcribe(args, ctx),
        "cloneVoice" => c.clone_voice(args, ctx),
        "talk" => c.talk(args, ctx),
        other => unsupported(c.name(), other),
    }
}

pub(crate) async fn cancellable<F>(ctx: &MediaCtx, fut: F) -> Result<MediaResult, MediaError>
where
    F: Future<Output = Result<MediaResult, MediaError>>,
{
    tokio::select! {
        biased;
        _ = ctx.cancel.cancelled() => Err(MediaError::Cancelled),
        r = fut => r,
    }
}

/// Anything [`create_media`] accepts as a client: a concrete client or an
/// `Arc<dyn MediaClient>`.
pub trait IntoMediaClient {
    fn into_media_client(self) -> Arc<dyn MediaClient>;
}

impl<C: MediaClient + 'static> IntoMediaClient for C {
    fn into_media_client(self) -> Arc<dyn MediaClient> {
        Arc::new(self)
    }
}

impl IntoMediaClient for Arc<dyn MediaClient> {
    fn into_media_client(self) -> Arc<dyn MediaClient> {
        self
    }
}

fn accepted_args(c: &dyn MediaClient, verb: &str) -> Vec<MediaArg> {
    let canon = canonical_args(verb);
    match c.args(verb) {
        // D7: only canonical names are ever accepted
        Some(a) => a
            .into_iter()
            .filter(|x| canon.iter().any(|k| k.name == x.name))
            .collect(),
        None => canon,
    }
}

/// §7G.3 per-candidate arg check (D19): the skip reason when `c` cannot take
/// `args` — first unsupported key (sorted), then first missing required
/// (canonical order).
fn skip_reason(c: &dyn MediaClient, verb: &str, args: &MediaArgs) -> Option<String> {
    let accepted = accepted_args(c, verb);
    let mut keys: Vec<&String> = args.keys().collect();
    keys.sort();
    if let Some(k) = keys
        .into_iter()
        .find(|k| !accepted.iter().any(|a| &a.name == *k))
    {
        return Some(format!("{}: argument \"{k}\" not supported", c.name()));
    }
    let required = |n: &str| accepted.iter().any(|a| a.name == n && a.required);
    let mut order: Vec<String> = canonical_args(verb).into_iter().map(|a| a.name).collect();
    for a in &accepted {
        if !order.contains(&a.name) {
            order.push(a.name.clone());
        }
    }
    order
        .iter()
        .find(|n| required(n) && !args.contains_key(n.as_str()))
        .map(|k| format!("{}: missing required argument \"{k}\"", c.name()))
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
        /// URL or local path of the starting image.
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
        /// Voice name or id, including a cloned voice.
        #[serde(skip_serializing_if = "Option::is_none")]
        voice: Option<String>,
    }
);
input!(
    /// `music` arguments.
    MusicInput {
        prompt: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        seconds: Option<i64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        instrumental: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        lyrics: Option<String>,
    }
);
input!(
    /// `transcribe` arguments.
    TranscribeInput {
        /// URL or local path of the audio.
        audio: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        language: Option<String>,
    }
);
input!(
    /// `cloneVoice` arguments.
    CloneVoiceInput { name: String, samples: Vec<String> }
);
input!(
    /// `talk` arguments.
    TalkInput {
        /// URL or local path of the face image.
        image: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        audio: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        text: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        voice: Option<String>,
    }
);

fn to_args<T: Serialize>(t: &T) -> MediaArgs {
    match serde_json::to_value(t) {
        Ok(Value::Object(m)) => m,
        _ => Map::new(),
    }
}

// ---------------------------------------------------------------------------
// createMedia — routing (§7G.3)
// ---------------------------------------------------------------------------

/// The routed media surface (`createMedia`). Cheap to clone.
#[derive(Clone)]
pub struct Media {
    clients: Arc<Vec<Arc<dyn MediaClient>>>,
}

impl std::fmt::Debug for Media {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let names: Vec<&str> = self.clients.iter().map(|c| c.name()).collect();
        f.debug_struct("Media").field("clients", &names).finish()
    }
}

/// `createMedia(client | [client])`: each verb goes to the first client that
/// supports it; an [`MediaError::Unavailable`] moves on to the next.
/// An empty list fails with `media: no clients`.
pub fn create_media<I>(clients: I) -> Result<Media, MediaError>
where
    I: IntoIterator,
    I::Item: IntoMediaClient,
{
    let clients: Vec<Arc<dyn MediaClient>> = clients
        .into_iter()
        .map(IntoMediaClient::into_media_client)
        .collect();
    if clients.is_empty() {
        return Err(MediaError::failed("media: no clients"));
    }
    Ok(Media {
        clients: Arc::new(clients),
    })
}

/// One-shot `generateMedia(style, verb, args, config)`:
/// `create_media([load_media_style_with(style, config)?])?.generate(verb, args)`.
pub async fn generate_media(
    style: impl AsRef<std::path::Path>,
    verb: &str,
    args: MediaArgs,
    cfg: MediaClientConfig,
) -> Result<MediaResult, MediaError> {
    create_media([load_media_style_with(style, cfg)?])?
        .generate(verb, args)
        .await
}

macro_rules! typed_verb {
    ($verb:literal, $f:ident, $fw:ident, $in:ty) => {
        #[doc = concat!("The `", $verb, "` verb.")]
        pub async fn $f(&self, input: $in) -> Result<MediaResult, MediaError> {
            self.$fw(input, &CancellationToken::new()).await
        }
        #[doc = concat!("The `", $verb, "` verb, stopped early by `cancel`.")]
        pub async fn $fw(
            &self,
            input: $in,
            cancel: &CancellationToken,
        ) -> Result<MediaResult, MediaError> {
            let ctx = MediaCtx {
                cancel: cancel.clone(),
                provider_options: input.provider_options.clone(),
                ..Default::default()
            };
            self.generate_ctx($verb, to_args(&input), ctx).await
        }
    };
}

impl Media {
    /// Whether any client supports `verb`.
    pub fn supports(&self, verb: &str) -> bool {
        self.clients.iter().any(|c| c.supports(verb))
    }

    /// Every verb some source supports, in canonical (`verbs.json`) order.
    pub fn verbs(&self) -> Vec<String> {
        MEDIA_VERBS
            .iter()
            .filter(|v| self.supports(v))
            .map(|v| v.to_string())
            .collect()
    }

    /// "style" when every source is a loaded style, else "client".
    fn noun(&self) -> &'static str {
        if self.clients.iter().all(|c| c.is_media_style()) {
            "style"
        } else {
            "client"
        }
    }

    typed_verb!("image", image, image_with, ImageInput);
    typed_verb!("video", video, video_with, VideoInput);
    typed_verb!("speech", speech, speech_with, SpeechInput);
    typed_verb!("music", music, music_with, MusicInput);
    typed_verb!("transcribe", transcribe, transcribe_with, TranscribeInput);
    typed_verb!("cloneVoice", clone_voice, clone_voice_with, CloneVoiceInput);
    typed_verb!("talk", talk, talk_with, TalkInput);

    /// The generic verb call: `generate(verb, args)`.
    pub async fn generate(&self, verb: &str, args: MediaArgs) -> Result<MediaResult, MediaError> {
        self.generate_ctx(verb, args, MediaCtx::default()).await
    }

    /// [`Media::generate`] with `providerOptions` and a cancellation token.
    pub async fn generate_with(
        &self,
        verb: &str,
        args: MediaArgs,
        provider_options: Option<MediaArgs>,
        cancel: &CancellationToken,
    ) -> Result<MediaResult, MediaError> {
        let ctx = MediaCtx {
            cancel: cancel.clone(),
            provider_options,
            ..Default::default()
        };
        self.generate_ctx(verb, args, ctx).await
    }

    /// [`Media::generate`] with a full [`MediaCtx`].
    pub async fn generate_ctx(
        &self,
        verb: &str,
        args: MediaArgs,
        ctx: MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        self.route(verb, args, &ctx).await.map(|(r, _)| r)
    }

    /// The §7G.3 routing; returns the result and the serving client's name.
    async fn route(
        &self,
        verb: &str,
        args: MediaArgs,
        ctx: &MediaCtx,
    ) -> Result<(MediaResult, String), MediaError> {
        let args: MediaArgs = args.into_iter().filter(|(_, v)| !v.is_null()).collect();
        let cands: Vec<&Arc<dyn MediaClient>> =
            self.clients.iter().filter(|c| c.supports(verb)).collect();
        if cands.is_empty() {
            return Err(MediaError::failed(format!(
                "media: no {} supports \"{verb}\"",
                self.noun()
            )));
        }
        // D7/D19: a non-canonical key can be served by no client
        let canon = canonical_args(verb);
        let mut keys: Vec<&String> = args.keys().collect();
        keys.sort();
        if let Some(k) = keys
            .into_iter()
            .find(|k| !canon.iter().any(|a| &a.name == *k))
        {
            return Err(MediaError::failed(format!(
                "{verb}: argument \"{k}\" not supported"
            )));
        }
        let mut msgs = Vec::new();
        for c in cands {
            if let Some(why) = skip_reason(c.as_ref(), verb, &args) {
                msgs.push(why);
                continue;
            }
            let a = args.clone();
            let r = cancellable(ctx, call_media_verb(c.as_ref(), verb, a, ctx)).await;
            match r {
                Ok(mut res) => {
                    if res.style.as_deref().is_none_or(str::is_empty) {
                        res.style = Some(c.source_id());
                    }
                    if res.model.as_deref().is_none_or(str::is_empty) {
                        res.model = c.source_model(verb);
                    }
                    return Ok((res, c.name().to_string()));
                }
                Err(MediaError::Unavailable { message }) => msgs.push(message),
                Err(e) => return Err(e),
            }
        }
        Err(MediaError::unavailable(format!(
            "media: no client available for \"{verb}\": {}",
            msgs.join("; ")
        )))
    }

    /// One `source: "media"` tool per verb any client supports, in
    /// `verbs.json` order; schema from the first supporting client (§7G.6).
    pub fn tools(&self) -> Vec<Tool> {
        verb_specs()
            .iter()
            .filter_map(|spec| {
                let first = self.clients.iter().find(|c| c.supports(&spec.verb))?;
                Some(self.tool_for(spec, &accepted_args(first.as_ref(), &spec.verb)))
            })
            .collect()
    }

    fn tool_for(&self, spec: &'static VerbSpec, accepted: &[MediaArg]) -> Tool {
        let mut props = Map::new();
        let mut required = Vec::new();
        for arg in spec.args.iter() {
            let Some(acc) = accepted.iter().find(|a| a.name == arg.name) else {
                continue;
            };
            let p = match &arg.items {
                Some(items) => {
                    json!({"type": arg.ty, "items": {"type": items}, "description": arg.description})
                }
                None => json!({"type": arg.ty, "description": arg.description}),
            };
            props.insert(arg.name.clone(), p);
            if acc.required {
                required.push(Value::String(arg.name.clone()));
            }
        }
        let me = self.clone();
        let verb: &'static str = &spec.verb;
        Tool {
            output_schema: None,
            annotations: None,
            name: spec.tool.clone(),
            description: spec.description.clone(),
            input_schema: json!({"type": "object", "properties": props, "required": required}),
            source: ToolSource::Media,
            hints: None,
            // Each call is an independent job: always concurrency-safe (§7G.6).
            concurrency_safe: Some(Arc::new(|_| true)),
            execute: Arc::new(move |args, ctx| {
                let me = me.clone();
                Box::pin(async move {
                    let args = match args {
                        Value::Object(m) => m,
                        _ => Map::new(),
                    };
                    let mctx = MediaCtx {
                        cancel: ctx.cancel.clone(),
                        ..Default::default()
                    };
                    match me.route(verb, args, &mctx).await {
                        Ok((r, client)) => tool_result(verb, &client, r),
                        Err(e) => ToolResult::error(e.to_string()),
                    }
                })
            }),
        }
    }
}

/// The tool face of a [`MediaResult`] (for the conformance harness).
#[doc(hidden)]
pub fn media_tool_result(verb: &str, client: &str, r: MediaResult) -> ToolResult {
    tool_result(verb, client, r)
}

fn tool_result(verb: &str, client: &str, r: MediaResult) -> ToolResult {
    let mut meta = Map::new();
    meta.insert("source".into(), "media".into());
    meta.insert("verb".into(), verb.into());
    meta.insert("client".into(), client.into());
    if let Some(id) = &r.job_id {
        meta.insert("jobId".into(), id.clone().into());
    }
    meta.insert("status".into(), r.status.clone().into());
    if let Some(u) = &r.usage {
        meta.insert(
            "usage".into(),
            serde_json::to_value(u).unwrap_or(Value::Null),
        );
    }
    if verb_kind(verb) == "text" {
        return ToolResult::ok(r.text.unwrap_or_default()).with_metadata(meta);
    }
    let word = match r.files.first().map(|f| f.kind.as_str()) {
        Some("image") => "image",
        Some("audio") => "audio",
        _ => "video",
    };
    let lines: Vec<String> = r
        .files
        .iter()
        .map(|f| {
            if !f.path.is_empty() {
                f.path.clone()
            } else if !f.url.is_empty() {
                f.url.clone()
            } else {
                let n = http::b64_decode(&f.data).map(|b| b.len()).unwrap_or(0);
                format!("inline {} {n} bytes", f.mime_type)
            }
        })
        .collect();
    let mut out = ToolResult::ok(format!("{word} ready: {}", lines.join("\n"))).with_metadata(meta);
    out.parts = r.files.iter().filter_map(file_part).collect();
    out
}

/// A §1B part for a file: `data` when it has data, `url` for an http(s) URL,
/// else the local file's bytes read now. A part never holds a path.
fn file_part(f: &MediaFile) -> Option<ContentPart> {
    let mut p = ContentPart {
        kind: f.kind.clone(),
        mime_type: f.mime_type.clone(),
        ..Default::default()
    };
    // D20: the local path rides along port-internally (never serialized)
    p.path = f.path.clone();
    if !f.data.is_empty() {
        p.data = f.data.clone();
    } else if f.url.starts_with("http://") || f.url.starts_with("https://") {
        p.url = f.url.clone();
    } else {
        let path = if f.path.is_empty() {
            f.url.strip_prefix("file://")?.to_string()
        } else {
            f.path.clone()
        };
        p.data = http::b64_encode(&std::fs::read(path).ok()?);
    }
    Some(p)
}

/// The parts of a tool result that message assembly (§8A) emits to the model:
/// for a `source: "media"` result only `image` parts (§7G.6 model
/// visibility); every other result keeps all its parts.
#[doc(hidden)]
pub fn model_parts(r: &ToolResult) -> Vec<ContentPart> {
    let media = r
        .metadata
        .as_ref()
        .and_then(|m| m.get("source"))
        .and_then(Value::as_str)
        == Some("media");
    if !media {
        return r.parts.clone();
    }
    r.parts
        .iter()
        .filter(|p| p.kind == "image")
        .cloned()
        .collect()
}
