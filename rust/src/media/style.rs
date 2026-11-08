//! Style clients (§7G.7): the JSON style engine of ADR 0036, now one kind of
//! [`MediaClient`]. A2E is a style client over the embedded preset.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use super::http::{self, Body, Gather, Raw, SendError};
use super::{
    GatherMode, MEDIA_VERBS, MediaArg, MediaArgs, MediaClient, MediaClientConfig, MediaCtx,
    MediaError, MediaFuture, MediaResult, canonical_args, positive, report,
};

// ---------------------------------------------------------------------------
// style (data)
// ---------------------------------------------------------------------------

/// A provider style (§7G.7).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct MediaStyle {
    /// The client name in messages; `None` ⇒ `"style"`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
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
    /// verb ∈ the seven of §7G.1.
    pub verbs: BTreeMap<String, MediaVerb>,
    /// What [`load_media_style_with`] attached: the preset (if any), the
    /// client config, and the client built from them. Not part of the JSON.
    #[doc(hidden)]
    #[serde(skip)]
    pub loaded: LoadedStyle,
}

/// The non-JSON half of a loaded [`MediaStyle`] (preset + config + client).
#[doc(hidden)]
#[derive(Clone, Default)]
pub struct LoadedStyle {
    preset: Option<&'static str>,
    cfg: MediaClientConfig,
    client: OnceLock<Arc<dyn MediaClient>>,
}

impl std::fmt::Debug for LoadedStyle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LoadedStyle")
            .field("preset", &self.preset)
            .finish_non_exhaustive()
    }
}

impl PartialEq for LoadedStyle {
    fn eq(&self, other: &Self) -> bool {
        self.preset == other.preset
    }
}

/// The built-in CLI presets (string value, client label).
const PRESETS: [(&str, &str); 6] = [
    (MediaStyle::XAI_GROK_CLI, "grok"),
    (MediaStyle::OPENAI_CODEX_CLI, "codex"),
    (MediaStyle::GOOGLE_AGY_CLI, "agy"),
    (MediaStyle::XAI_GROK_CLI_MOCK, "grok"),
    (MediaStyle::OPENAI_CODEX_CLI_MOCK, "codex"),
    (MediaStyle::GOOGLE_AGY_CLI_MOCK, "agy"),
];

impl MediaStyle {
    /// Preset: the `grok` CLI on the user's subscription (image, video).
    pub const XAI_GROK_CLI: &'static str = "xai-grok-cli";
    /// Preset: the `codex` CLI on the user's subscription (image).
    pub const OPENAI_CODEX_CLI: &'static str = "openai-codex-cli";
    /// Preset: Google's Antigravity CLI `agy` (image).
    pub const GOOGLE_AGY_CLI: &'static str = "google-agy-cli";
    /// Mock twin of [`MediaStyle::XAI_GROK_CLI`]: same client, never starts a process.
    pub const XAI_GROK_CLI_MOCK: &'static str = "xai-grok-cli-mock";
    /// Mock twin of [`MediaStyle::OPENAI_CODEX_CLI`].
    pub const OPENAI_CODEX_CLI_MOCK: &'static str = "openai-codex-cli-mock";
    /// Mock twin of [`MediaStyle::GOOGLE_AGY_CLI`].
    pub const GOOGLE_AGY_CLI_MOCK: &'static str = "google-agy-cli-mock";

    /// `available()` (§7G.10b): whether the style can run, in well under a
    /// second, generating nothing and reading no secret. CLI preset: the
    /// command is found and the CLI's own login file exists; HTTP style: the
    /// config key is set or every `${VAR}` in its headers is.
    pub fn available(&self) -> MediaAvailability {
        let avail = |installed, logged_in, reason: String| MediaAvailability {
            installed,
            logged_in,
            reason,
        };
        let Some(preset) = self.loaded.preset else {
            let id = self.name.clone().unwrap_or_else(|| "style".into());
            if self
                .loaded
                .cfg
                .key
                .as_deref()
                .is_some_and(|k| !k.is_empty())
            {
                return avail(true, true, format!("{id}: key from config"));
            }
            for v in style_env_vars(self) {
                if std::env::var(&v).map(|x| x.is_empty()).unwrap_or(true) {
                    return avail(true, false, format!("{id}: {v} is not set"));
                }
            }
            return avail(true, true, format!("{id}: env present"));
        };
        let label = PRESETS
            .iter()
            .find(|(k, _)| *k == preset)
            .map(|(_, l)| *l)
            .unwrap_or("style");
        if preset.ends_with("-mock") {
            if std::env::var(MEDIA_MOCK_AVAILABLE_ENV).as_deref() == Ok("0") {
                return avail(
                    false,
                    false,
                    format!("{label}: mock set unavailable by {MEDIA_MOCK_AVAILABLE_ENV}=0"),
                );
            }
            return avail(true, true, format!("{label}: mock"));
        }
        let bin = self
            .loaded
            .cfg
            .command
            .first()
            .cloned()
            .unwrap_or_else(|| label.to_string());
        if !look_path(&bin) {
            return avail(false, false, format!("{label}: {bin:?} not found on PATH"));
        }
        let auth = cli_auth_file(label);
        let auth_s = auth.to_string_lossy();
        if std::fs::metadata(&auth).is_ok_and(|m| !m.is_dir()) {
            avail(
                true,
                true,
                format!("{label}: installed, logged in ({auth_s} present)"),
            )
        } else {
            avail(
                true,
                false,
                format!("{label}: installed, not logged in ({auth_s} missing)"),
            )
        }
    }

    /// The built-in preset this style was loaded from, if any.
    pub fn preset(&self) -> Option<&'static str> {
        self.loaded.preset
    }

    /// The client that serves this style (built once).
    fn client(&self) -> &Arc<dyn MediaClient> {
        self.loaded.client.get_or_init(|| {
            let cfg = self.loaded.cfg.clone();
            if let Some(p) = self.loaded.preset {
                return Arc::new(super::cli::preset_client(p, cfg));
            }
            match StyleMediaClient::new(self.clone_data(), cfg) {
                Ok(c) => Arc::new(c),
                Err(e) => Arc::new(BrokenStyle {
                    name: self.name.clone().unwrap_or_else(|| "style".into()),
                    _err: e.to_string(),
                }),
            }
        })
    }

    fn clone_data(&self) -> MediaStyle {
        MediaStyle {
            name: self.name.clone(),
            base_url: self.base_url.clone(),
            headers: self.headers.clone(),
            error_envelope: self.error_envelope.clone(),
            verbs: self.verbs.clone(),
            loaded: LoadedStyle::default(),
        }
    }
}

/// The answer of [`MediaStyle::available`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaAvailability {
    pub installed: bool,
    #[serde(rename = "loggedIn")]
    pub logged_in: bool,
    pub reason: String,
}

/// Set to `0`, every mock preset reports unavailable.
pub const MEDIA_MOCK_AVAILABLE_ENV: &str = "TOOLNEXUS_MEDIA_MOCK_AVAILABLE";

fn home_dir() -> PathBuf {
    PathBuf::from(std::env::var("HOME").unwrap_or_default())
}

/// The file each CLI writes when logged in (existence only, never read).
fn cli_auth_file(label: &str) -> PathBuf {
    match label {
        "grok" => home_dir().join(".grok").join("auth.json"),
        "codex" => match std::env::var("CODEX_HOME") {
            Ok(h) if !h.is_empty() => PathBuf::from(h).join("auth.json"),
            _ => home_dir().join(".codex").join("auth.json"),
        },
        _ => home_dir()
            .join(".gemini")
            .join("antigravity-cli")
            .join("antigravity-oauth-token"),
    }
}

fn is_exec(p: &Path) -> bool {
    let Ok(m) = std::fs::metadata(p) else {
        return false;
    };
    if m.is_dir() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        m.permissions().mode() & 0o111 != 0
    }
    #[cfg(not(unix))]
    {
        true
    }
}

/// `exec.LookPath`: a name with a separator is checked as a file, else PATH.
fn look_path(bin: &str) -> bool {
    if bin.contains('/') || bin.contains(std::path::MAIN_SEPARATOR) {
        return is_exec(Path::new(bin));
    }
    let Some(path) = std::env::var_os("PATH") else {
        return false;
    };
    std::env::split_paths(&path).any(|d| is_exec(&d.join(bin)))
}

/// The `${VAR}` names the style's headers use, sorted, unique.
fn style_env_vars(s: &MediaStyle) -> Vec<String> {
    static RE: OnceLock<regex::Regex> = OnceLock::new();
    let re = RE.get_or_init(|| regex::Regex::new(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}").expect("re"));
    let mut out: Vec<String> = s
        .headers
        .values()
        .flat_map(|v| re.captures_iter(v).map(|c| c[1].to_string()))
        .collect();
    out.sort();
    out.dedup();
    out
}

/// A style whose client could not be built: supports nothing.
struct BrokenStyle {
    name: String,
    _err: String,
}

impl MediaClient for BrokenStyle {
    fn name(&self) -> &str {
        &self.name
    }
    fn supports(&self, _verb: &str) -> bool {
        false
    }
}

/// A loaded style is itself a [`MediaClient`], so `create_media([style])` works.
impl MediaClient for MediaStyle {
    fn name(&self) -> &str {
        self.client().name()
    }
    fn supports(&self, verb: &str) -> bool {
        self.client().supports(verb)
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        self.client().args(verb)
    }
    fn is_media_style(&self) -> bool {
        true
    }
    fn source_id(&self) -> String {
        match self.loaded.preset {
            Some(p) => p.to_string(),
            None => self.name.clone().unwrap_or_else(|| "style".into()),
        }
    }
    fn source_model(&self, verb: &str) -> Option<String> {
        let c = &self.loaded.cfg;
        c.models
            .get(verb)
            .filter(|m| !m.is_empty())
            .or(c.model.as_ref().filter(|m| !m.is_empty()))
            .cloned()
    }
    fn image<'a>(&'a self, args: MediaArgs, ctx: &'a MediaCtx) -> MediaFuture<'a> {
        self.client().image(args, ctx)
    }
    fn video<'a>(&'a self, args: MediaArgs, ctx: &'a MediaCtx) -> MediaFuture<'a> {
        self.client().video(args, ctx)
    }
    fn speech<'a>(&'a self, args: MediaArgs, ctx: &'a MediaCtx) -> MediaFuture<'a> {
        self.client().speech(args, ctx)
    }
    fn music<'a>(&'a self, args: MediaArgs, ctx: &'a MediaCtx) -> MediaFuture<'a> {
        self.client().music(args, ctx)
    }
    fn transcribe<'a>(&'a self, args: MediaArgs, ctx: &'a MediaCtx) -> MediaFuture<'a> {
        self.client().transcribe(args, ctx)
    }
    fn clone_voice<'a>(&'a self, args: MediaArgs, ctx: &'a MediaCtx) -> MediaFuture<'a> {
        self.client().clone_voice(args, ctx)
    }
    fn talk<'a>(&'a self, args: MediaArgs, ctx: &'a MediaCtx) -> MediaFuture<'a> {
        self.client().talk(args, ctx)
    }
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
    /// Extra required args, beyond canonical ∩ mapped.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub required: Vec<String>,
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
        deserialize_with = "lenient_ms",
        skip_serializing_if = "Option::is_none"
    )]
    pub interval_ms: Option<u64>,
    /// `None` ⇒ 600000.
    #[serde(
        rename = "timeoutMs",
        default,
        deserialize_with = "lenient_ms",
        skip_serializing_if = "Option::is_none"
    )]
    pub timeout_ms: Option<u64>,
}

/// A positive whole number of ms; anything else (absent, `<= 0`, not a number) ⇒ `None` (default).
fn lenient_ms<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Option<u64>, D::Error> {
    let v = Value::deserialize(d)?;
    Ok(v.as_f64().filter(|f| *f > 0.0).map(|f| f.ceil() as u64))
}

/// `loadMediaStyle(pathOrPreset)`: a built-in preset name
/// ([`MediaStyle::XAI_GROK_CLI`], …) or a style JSON file; load-time style errors
/// included. The result is a [`MediaClient`] for [`super::create_media`].
pub fn load_media_style(path: impl AsRef<Path>) -> Result<MediaStyle, MediaError> {
    load_media_style_with(path, MediaClientConfig::default())
}

/// [`load_media_style`] with the config of the client behind the style
/// (model, command, loop, gather, …).
pub fn load_media_style_with(
    path: impl AsRef<Path>,
    cfg: MediaClientConfig,
) -> Result<MediaStyle, MediaError> {
    let p = path.as_ref();
    if let Some((preset, label)) = p
        .to_str()
        .and_then(|n| PRESETS.iter().find(|(k, _)| *k == n))
    {
        return Ok(MediaStyle {
            name: Some((*label).to_string()),
            loaded: LoadedStyle {
                preset: Some(preset),
                cfg,
                client: OnceLock::new(),
            },
            ..Default::default()
        });
    }
    let mut style = read_style_file(p)?;
    style.loaded.cfg = cfg;
    Ok(style)
}

fn read_style_file(p: &Path) -> Result<MediaStyle, MediaError> {
    let text = std::fs::read_to_string(p)
        .map_err(|e| MediaError::failed(format!("media: cannot read {}: {e}", p.display())))?;
    let style: MediaStyle = serde_json::from_str(&text)
        .map_err(|e| MediaError::failed(format!("media: bad style {}: {e}", p.display())))?;
    check_style(&style)?;
    Ok(style)
}

/// Load-time style checks: only the seven verbs; no argument mapped to "".
fn check_style(style: &MediaStyle) -> Result<(), MediaError> {
    for (verb, vs) in &style.verbs {
        if !MEDIA_VERBS.contains(&verb.as_str()) {
            return Err(MediaError::failed(format!(
                "style: unknown verb \"{verb}\""
            )));
        }
        if let Some((k, _)) = vs.args.iter().find(|(_, p)| p.is_empty()) {
            return Err(MediaError::failed(format!(
                "style: empty path for argument \"{k}\""
            )));
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// the client
// ---------------------------------------------------------------------------

/// A [`MediaClient`] over a parsed style (`styleMediaClient`).
#[derive(Clone)]
pub struct StyleMediaClient {
    style: Arc<MediaStyle>,
    cfg: MediaClientConfig,
    http: reqwest::Client,
    name: String,
    /// Built-in style clients (a2e): the key env; no key ⇒ `unavailable`.
    key_env: Option<&'static str>,
    default_mode: GatherMode,
    /// Built-in: `(verb, dotPath)` the config `model` is written to.
    model_path: Option<(&'static str, &'static str)>,
}

impl std::fmt::Debug for StyleMediaClient {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("StyleMediaClient")
            .field("name", &self.name)
            .finish_non_exhaustive()
    }
}

/// `styleMediaClient(style, config)`: a client named `style.name ?? "style"`.
/// Uses `url`, `loop`, `gather` (default mode `url`), `httpClient`; `key`, when
/// set, is what every `${…}` in the style's headers expands to.
pub fn style_media_client(
    style: MediaStyle,
    cfg: MediaClientConfig,
) -> Result<StyleMediaClient, MediaError> {
    StyleMediaClient::new(style, cfg)
}

impl StyleMediaClient {
    pub fn new(style: MediaStyle, cfg: MediaClientConfig) -> Result<Self, MediaError> {
        check_style(&style)?;
        let name = style.name.clone().unwrap_or_else(|| "style".into());
        Ok(StyleMediaClient {
            http: cfg.http_client.clone().unwrap_or_else(http::default_http),
            style: Arc::new(style),
            cfg,
            name,
            key_env: None,
            default_mode: GatherMode::Url,
            model_path: None,
        })
    }

    /// The built-in shape (a2e): key env, download default, model target.
    pub(crate) fn builtin(
        style: MediaStyle,
        cfg: MediaClientConfig,
        key_env: &'static str,
        model_path: (&'static str, &'static str),
    ) -> Result<Self, MediaError> {
        let mut c = Self::new(style, cfg)?;
        c.key_env = Some(key_env);
        c.default_mode = GatherMode::Download;
        c.model_path = Some(model_path);
        Ok(c)
    }

    fn base(&self) -> String {
        let b = self.cfg.url.as_deref().unwrap_or(&self.style.base_url);
        b.trim_end_matches('/').to_string()
    }

    /// The style's headers, `${…}` expanded (to the config key when set).
    fn headers(&self, key: Option<&str>) -> Vec<(String, String)> {
        self.style
            .headers
            .iter()
            .map(|(k, v)| (k.clone(), expand(v, key)))
            .collect()
    }

    pub(crate) async fn run(
        &self,
        verb: &str,
        args: MediaArgs,
        ctx: &MediaCtx,
    ) -> Result<MediaResult, MediaError> {
        let Some(vs) = self.style.verbs.get(verb) else {
            return Err(MediaError::failed(format!(
                "media: no client supports \"{verb}\""
            )));
        };
        let args: MediaArgs = args.into_iter().filter(|(_, v)| !v.is_null()).collect();
        let acc = self.args(verb).unwrap_or_default();
        if let Some(k) = args.keys().find(|k| !acc.iter().any(|a| &a.name == *k)) {
            return Err(MediaError::failed(format!(
                "{verb}: argument \"{k}\" not supported by this style"
            )));
        }
        let key = match self.key_env {
            Some(env) => {
                let k = self
                    .cfg
                    .key
                    .clone()
                    .filter(|k| !k.is_empty())
                    .or_else(|| std::env::var(env).ok().filter(|k| !k.is_empty()));
                match k {
                    Some(k) => Some(k),
                    None => {
                        return Err(MediaError::unavailable(format!(
                            "{}: no API key (set {env})",
                            self.name
                        )));
                    }
                }
            }
            None => self.cfg.key.clone(),
        };
        let headers = self.headers(key.as_deref());

        let mut body = vs
            .defaults
            .clone()
            .unwrap_or_else(|| Value::Object(Map::new()));
        if let (Some((mverb, mpath)), Some(model)) = (self.model_path, self.model_for(verb))
            && mverb == verb
        {
            set_path(&mut body, mpath, Value::String(model));
        }
        for (k, v) in &args {
            set_path(&mut body, &vs.args[k], v.clone());
        }
        if let Some(po) = &ctx.provider_options {
            if !body.is_object() {
                body = Value::Object(Map::new());
            }
            let obj = body.as_object_mut().expect("object");
            for (k, v) in po {
                obj.insert(k.clone(), v.clone());
            }
        }
        let method = if vs.start.method.is_empty() {
            "POST"
        } else {
            &vs.start.method
        };
        let timeout_ms = positive(self.cfg.r#loop.timeout_ms)
            .or(vs.poll.as_ref().and_then(|p| p.timeout_ms))
            .unwrap_or(600_000);
        let start = Instant::now();
        let mut deadline = start + Duration::from_millis(timeout_ms);
        if let Some(d) = ctx.deadline {
            deadline = deadline.min(d);
        }
        let base = self.base();
        let origin = reqwest::Url::parse(&base).ok();
        let req = |method: String, path: String, body: Option<Value>| {
            let headers = headers.clone();
            let origin = origin.clone();
            let base = base.clone();
            async move {
                let body = match body {
                    Some(b) => Body::Json(b.to_string().into_bytes()),
                    None => Body::None,
                };
                let sent = http::send(
                    &self.http,
                    verb,
                    &method,
                    &format!("{base}{path}"),
                    body,
                    &headers,
                    origin.as_ref(),
                    deadline,
                    None,
                )
                .await;
                self.check(verb, sent)
            }
        };
        let start_resp = match req(method.to_string(), vs.start.path.clone(), Some(body)).await {
            Err(Timed) => {
                return Err(MediaError::timeout(
                    format!("{verb}: timed out after {timeout_ms}ms"),
                    None,
                ));
            }
            Ok(Ok(v)) => v,
            Ok(Err(e)) => return Err(e),
        };

        let (final_resp, status, job_id) = if vs.mode == "async" {
            let job_id = match get_path(&start_resp, &vs.id_path) {
                Some(Value::String(s)) if !s.is_empty() => s.clone(),
                Some(Value::Number(n)) if is_integral(n) => num_text(n),
                _ => {
                    return Err(MediaError::failed(format!(
                        "{verb}: no job id at {}",
                        vs.id_path
                    )));
                }
            };
            let jid = Some(job_id.clone());
            let poll = vs.poll.clone().unwrap_or_default();
            let interval = Duration::from_millis(
                positive(self.cfg.r#loop.interval_ms)
                    .or(poll.interval_ms)
                    .unwrap_or(5000),
            );
            let pmethod = if poll.method.is_empty() {
                "GET".to_string()
            } else {
                poll.method.clone()
            };
            let path = poll.path.replace("{id}", &http::pct_encode(&job_id));
            let timed_out = || {
                MediaError::timeout(
                    format!("job {job_id} timed out after {timeout_ms}ms"),
                    jid.clone(),
                )
            };
            loop {
                tokio::time::sleep(
                    interval.min(deadline.saturating_duration_since(Instant::now())),
                )
                .await;
                if Instant::now() >= deadline {
                    return Err(timed_out());
                }
                let r = match req(pmethod.clone(), path.clone(), None).await {
                    Err(Timed) => return Err(timed_out()),
                    Ok(Ok(v)) => v,
                    Ok(Err(e)) => return Err(e.with_job(&jid)),
                };
                let st = render(get_path(&r, &poll.status_path));
                report(ctx, &self.cfg, verb, &self.name, Some(&job_id), &st, start);
                if poll.done.contains(&st) {
                    break (r, st, jid);
                }
                if poll.failed.contains(&st) {
                    let msg = if poll.error_path.is_empty() {
                        String::new()
                    } else {
                        match get_path(&r, &poll.error_path) {
                            Some(Value::String(s)) => s.clone(),
                            Some(Value::Number(n)) => num_text(n),
                            Some(Value::Null) | None => String::new(),
                            Some(other) => other.to_string(),
                        }
                    };
                    let m = if msg.is_empty() {
                        format!("job {job_id} {st}")
                    } else {
                        format!("job {job_id} {st}: {msg}")
                    };
                    return Err(MediaError::failed(m).with_job(&jid));
                }
                if Instant::now() >= deadline {
                    return Err(timed_out());
                }
            }
        } else {
            (start_resp, "completed".to_string(), None)
        };

        let found = get_path(&final_resp, &vs.result_path);
        let no_result = || {
            MediaError::failed(format!("{verb}: no result at {}", vs.result_path)).with_job(&job_id)
        };
        if vs.kind == "text" {
            let text = match found {
                Some(Value::String(s)) => s.clone(),
                Some(Value::Number(n)) => num_text(n),
                _ => return Err(no_result()),
            };
            return Ok(MediaResult {
                files: Vec::new(),
                text: Some(text),
                job_id,
                status,
                usage: None,
                style: None,
                model: None,
            });
        }
        let mut urls = Vec::new();
        if let Some(v) = found {
            collect_urls(v, &mut urls);
        }
        if urls.is_empty() {
            return Err(no_result());
        }
        let g = Gather {
            client: &self.name,
            verb,
            kind: &vs.kind,
            mode: self.cfg.gather.mode.unwrap_or(self.default_mode),
            dir: self.cfg.gather.dir.clone(),
            name: self.cfg.gather.name.clone(),
            max: self.cfg.gather.max_bytes.unwrap_or(http::DEFAULT_MAX_BYTES),
            keep: true,
            http: &self.http,
            deadline,
            timeout_msg: match &job_id {
                Some(id) => format!("job {id} timed out after {timeout_ms}ms"),
                None => format!("{verb}: timed out after {timeout_ms}ms"),
            },
            job_id: job_id.clone(),
        };
        let mut files = Vec::with_capacity(urls.len());
        for u in urls {
            files.push(g.gather(Raw::Url(u)).await?);
        }
        Ok(MediaResult {
            files,
            text: None,
            job_id,
            status,
            usage: None,
            style: None,
            model: None,
        })
    }

    fn model_for(&self, verb: &str) -> Option<String> {
        self.cfg
            .models
            .get(verb)
            .cloned()
            .or_else(|| self.cfg.model.clone())
    }

    /// Status mapping, JSON parse and the error envelope (§7G.7 step 4).
    fn check(
        &self,
        verb: &str,
        sent: Result<http::Sent, SendError>,
    ) -> Result<Result<Value, MediaError>, Timed> {
        let sent = match sent {
            Ok(s) => s,
            Err(SendError::TimedOut) => return Err(Timed),
            Err(SendError::Msg(m)) => return Ok(Err(MediaError::failed(m))),
        };
        if !(200..300).contains(&sent.status) {
            return Ok(Err(http::http_error(&self.name, sent.status, &sent.body)));
        }
        let Ok(v) = serde_json::from_slice::<Value>(&sent.body) else {
            return Ok(Err(MediaError::failed(format!(
                "{verb}: response is not JSON"
            ))));
        };
        if let Some(env) = &self.style.error_envelope
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
            return Ok(Err(MediaError::failed(format!(
                "API error {code_s}: {msg}"
            ))));
        }
        Ok(Ok(v))
    }
}

/// The deadline passed during a request.
struct Timed;

impl MediaClient for StyleMediaClient {
    fn name(&self) -> &str {
        &self.name
    }
    fn supports(&self, verb: &str) -> bool {
        self.style.verbs.contains_key(verb)
    }
    fn args(&self, verb: &str) -> Option<Vec<MediaArg>> {
        let vs = self.style.verbs.get(verb)?;
        let canon = canonical_args(verb);
        let out: Vec<MediaArg> = canon
            .iter()
            .filter(|a| vs.args.contains_key(&a.name))
            .map(|a| MediaArg::new(&a.name, a.required || vs.required.contains(&a.name)))
            .collect();
        // D6: a mapped non-canonical name stays loadable but is not accepted
        Some(out)
    }
    verbs_via_run!();
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

/// Expand every `${VAR}`: to `key` when given, else from the process env (unset ⇒ "").
fn expand(v: &str, key: Option<&str>) -> String {
    let mut out = String::new();
    let mut rest = v;
    while let Some(i) = rest.find("${") {
        out.push_str(&rest[..i]);
        let after = &rest[i + 2..];
        match after.find('}') {
            Some(j) => {
                let name = &after[..j];
                match key {
                    Some(k) => out.push_str(k),
                    None => out.push_str(&std::env::var(name).unwrap_or_default()),
                }
                rest = &after[j + 1..];
            }
            None => {
                out.push_str("${");
                rest = after;
            }
        }
    }
    out.push_str(rest);
    out
}

pub(crate) fn get_path<'a>(v: &'a Value, path: &str) -> Option<&'a Value> {
    if path.is_empty() {
        return Some(v);
    }
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

pub(crate) fn set_path(root: &mut Value, path: &str, val: Value) {
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
        // An existing object keeps a numeric segment as a string key (§7G.8).
        if is_index(seg) && !cur.is_object() {
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

pub(crate) fn is_integral(n: &serde_json::Number) -> bool {
    n.is_i64()
        || n.is_u64()
        || n.as_f64()
            .is_some_and(|f| f.fract() == 0.0 && f.abs() < 1e18)
}

/// A number as integer text when integral, else in shortest round-trip form.
pub(crate) fn num_text(n: &serde_json::Number) -> String {
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

/// Poll status rendering (§7G.8): string as is, number as [`num_text`], else "".
pub(crate) fn render(v: Option<&Value>) -> String {
    match v {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => num_text(n),
        _ => String::new(),
    }
}

/// A job id: a non-empty string or an integral number.
pub(crate) fn job_id_of(v: Option<&Value>) -> Option<String> {
    match v {
        Some(Value::String(s)) if !s.is_empty() => Some(s.clone()),
        Some(Value::Number(n)) if is_integral(n) => Some(num_text(n)),
        _ => None,
    }
}

fn collect_urls(v: &Value, out: &mut Vec<String>) {
    match v {
        Value::String(s) if !s.is_empty() => out.push(s.clone()),
        Value::Array(a) => a.iter().for_each(|x| collect_urls(x, out)),
        _ => {}
    }
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
