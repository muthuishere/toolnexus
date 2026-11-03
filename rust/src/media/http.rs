//! The shared HTTP layer of every API media client (§7G.8): redirects followed
//! by hand with one rule set, headers only on the client's origin, every hop
//! bounded by the job deadline. Plus gather (§7G.5), input references (§7G.1)
//! and the small text helpers.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;

use super::{GatherMode, MediaError, MediaFile};

pub(crate) const DEFAULT_MAX_BYTES: u64 = 104_857_600;
const MAX_REDIRECTS: usize = 10;

pub(crate) fn b64_encode(b: &[u8]) -> String {
    STANDARD.encode(b)
}

pub(crate) fn b64_decode(s: &str) -> Option<Vec<u8>> {
    STANDARD.decode(s.trim()).ok()
}

/// The default client: never follows redirects on its own (we do, §7G.8).
pub(crate) fn default_http() -> reqwest::Client {
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .unwrap_or_default()
}

/// The first `n` code points of `t`, `…` appended when cut.
pub(crate) fn cap(t: &str, n: usize) -> String {
    match t.char_indices().nth(n) {
        Some((i, _)) => format!("{}…", &t[..i]),
        None => t.to_string(),
    }
}

/// RFC 3986 unreserved kept; every other UTF-8 byte as `%XX`.
pub(crate) fn pct_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// The extension table of §7G.5 / §7G.7.
pub(crate) fn mime_from_ext(path: &str) -> Option<&'static str> {
    let last = path.rsplit('/').next().unwrap_or("");
    let ext = last.rsplit_once('.')?.1.to_ascii_lowercase();
    Some(match ext.as_str() {
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
        _ => return None,
    })
}

/// The default `User-Agent` of every media HTTP request (§7G.12 D23).
pub(crate) fn user_agent() -> String {
    format!("toolnexus/{} (rust)", env!("CARGO_PKG_VERSION"))
}

/// D22: the URL path's own extension (last segment, `.` + 1-5 ASCII
/// letters/digits, lowercased).
fn url_ext(url: &str) -> Option<String> {
    let last = url_path(url).rsplit('/').next().unwrap_or("");
    let (_, ext) = last.rsplit_once('.')?;
    if (1..=5).contains(&ext.len()) && ext.bytes().all(|b| b.is_ascii_alphanumeric()) {
        Some(format!(".{}", ext.to_ascii_lowercase()))
    } else {
        None
    }
}

fn ext_for_mime(mime: &str) -> &'static str {
    match mime {
        "image/png" => ".png",
        "image/jpeg" => ".jpg",
        "image/webp" => ".webp",
        "image/gif" => ".gif",
        "video/mp4" => ".mp4",
        "video/webm" => ".webm",
        "video/quicktime" => ".mov",
        "audio/mpeg" => ".mp3",
        "audio/wav" | "audio/x-wav" | "audio/wave" => ".wav",
        "audio/ogg" => ".ogg",
        "audio/mp4" => ".m4a",
        "audio/flac" => ".flac",
        "audio/aac" => ".aac",
        "text/plain" => ".txt",
        _ => ".bin",
    }
}

/// The path of a URL (query and fragment stripped); a plain path as is.
pub(crate) fn url_path(url: &str) -> &str {
    let no_frag = url.split('#').next().unwrap_or("");
    let no_q = no_frag.split('?').next().unwrap_or("");
    match no_q.find("://") {
        Some(i) => {
            let rest = &no_q[i + 3..];
            rest.find('/').map(|j| &rest[j..]).unwrap_or("")
        }
        None => no_q,
    }
}

/// mimeType for a URL: the extension of its path, else the kind default.
pub(crate) fn mime_for(url: &str, kind: &str) -> String {
    mime_from_ext(url_path(url))
        .unwrap_or(match kind {
            "video" => "video/mp4",
            "audio" => "audio/mpeg",
            _ => "image/png",
        })
        .to_string()
}

/// `MediaFile.type` for a result kind: image ⇒ image, audio ⇒ audio, video ⇒ file.
pub(crate) fn part_type(kind: &str) -> &'static str {
    match kind {
        "image" => "image",
        "audio" => "audio",
        _ => "file",
    }
}

fn is_http(u: &str) -> bool {
    u.starts_with("http://") || u.starts_with("https://")
}

// ---------------------------------------------------------------------------
// send
// ---------------------------------------------------------------------------

/// A request body.
#[derive(Clone)]
pub(crate) enum Body {
    None,
    /// JSON (`Content-Type: application/json`).
    Json(Vec<u8>),
    /// Any other body with its content type (multipart).
    Raw(Vec<u8>, String),
}

pub(crate) struct Sent {
    pub status: u16,
    pub headers: reqwest::header::HeaderMap,
    pub body: Vec<u8>,
}

impl Sent {
    pub fn header(&self, name: &str) -> Option<String> {
        self.headers
            .get(name)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string)
    }
}

pub(crate) enum SendError {
    /// The deadline passed.
    TimedOut,
    /// A formatted, final error message.
    Msg(String),
}

/// One request with §7G.8's redirect rules. `headers` go only to `origin`
/// (the client's base origin); `cap` stops reading a body at `cap + 1` bytes.
#[allow(clippy::too_many_arguments)]
pub(crate) async fn send(
    http: &reqwest::Client,
    verb: &str,
    method: &str,
    url: &str,
    body: Body,
    headers: &[(String, String)],
    origin: Option<&reqwest::Url>,
    deadline: Instant,
    cap: Option<u64>,
) -> Result<Sent, SendError> {
    let remaining = deadline.saturating_duration_since(Instant::now());
    let fut = send_hops(http, verb, method, url, body, headers, origin, cap);
    match tokio::time::timeout(remaining, fut).await {
        Ok(r) => r,
        Err(_) => Err(SendError::TimedOut),
    }
}

#[allow(clippy::too_many_arguments)]
async fn send_hops(
    http: &reqwest::Client,
    verb: &str,
    method: &str,
    url: &str,
    mut body: Body,
    headers: &[(String, String)],
    origin: Option<&reqwest::Url>,
    cap: Option<u64>,
) -> Result<Sent, SendError> {
    let fail = |e: &dyn std::fmt::Display| SendError::Msg(format!("{verb}: {e}"));
    let mut url = reqwest::Url::parse(url).map_err(|e| fail(&e))?;
    let mut m = reqwest::Method::from_bytes(method.to_ascii_uppercase().as_bytes())
        .map_err(|e| fail(&e))?;
    let origin = origin.map(reqwest::Url::origin);
    for _ in 0..=MAX_REDIRECTS {
        let mut req = http.request(m.clone(), url.clone());
        match &body {
            Body::None => {}
            Body::Json(b) => {
                req = req
                    .header("Content-Type", "application/json")
                    .body(b.clone());
            }
            Body::Raw(b, ct) => {
                req = req.header("Content-Type", ct.as_str()).body(b.clone());
            }
        }
        let same_origin = origin.as_ref() == Some(&url.origin());
        if same_origin {
            for (k, v) in headers {
                req = req.header(k.as_str(), v.as_str());
            }
        }
        // D23: a default User-Agent unless the client/style headers set one
        if !(same_origin
            && headers
                .iter()
                .any(|(k, _)| k.eq_ignore_ascii_case("user-agent")))
        {
            req = req.header("User-Agent", user_agent());
        }
        let mut resp = req.send().await.map_err(|e| fail(&e))?;
        let code = resp.status().as_u16();
        let location = resp
            .headers()
            .get(reqwest::header::LOCATION)
            .and_then(|l| l.to_str().ok())
            .and_then(|l| url.join(l).ok());
        if let Some(next) = location.filter(|_| matches!(code, 301 | 302 | 303 | 307 | 308)) {
            if matches!(code, 301..=303) && m != reqwest::Method::GET && m != reqwest::Method::HEAD
            {
                m = reqwest::Method::GET;
                body = Body::None;
            }
            url = next;
            continue;
        }
        let headers = resp.headers().clone();
        let mut buf = Vec::new();
        // D14: a declared oversize body is never read; callers only see `cap + 1` bytes
        if let (Some(c), Some(n)) = (cap, resp.content_length())
            && n > c
            && (200..300).contains(&code)
        {
            buf.resize(c as usize + 1, 0);
            return Ok(Sent {
                status: code,
                headers,
                body: buf,
            });
        }
        while let Some(chunk) = resp.chunk().await.map_err(|e| fail(&e))? {
            buf.extend_from_slice(&chunk);
            if let Some(c) = cap
                && buf.len() as u64 > c
            {
                buf.truncate(c as usize + 1);
                break;
            }
        }
        return Ok(Sent {
            status: code,
            headers,
            body: buf,
        });
    }
    Err(SendError::Msg(format!("{verb}: too many redirects")))
}

/// §7G.2 / §7G.7 step 4: 402, or 429 naming quota/credit/billing/balance ⇒
/// `unavailable` (`<client>: HTTP <status>: <body>`); else `HTTP <status>: <body>`.
pub(crate) fn http_error(client: &str, status: u16, body: &[u8]) -> MediaError {
    let text = String::from_utf8_lossy(body);
    let capped = cap(&text, 1000);
    let lower = text.to_ascii_lowercase();
    let quota = ["quota", "credit", "billing", "balance"]
        .iter()
        .any(|w| lower.contains(w));
    if status == 402 || (status == 429 && quota) {
        MediaError::unavailable(format!("{client}: HTTP {status}: {capped}"))
    } else {
        MediaError::failed(format!("HTTP {status}: {capped}"))
    }
}

// ---------------------------------------------------------------------------
// gather (§7G.5)
// ---------------------------------------------------------------------------

/// A provider result before gather.
pub(crate) enum Raw {
    Bytes(Vec<u8>, String),
    Base64(String, String),
    DataUrl(String),
    Url(String),
    Local(PathBuf),
}

/// Everything gather needs for one verb call.
pub(crate) struct Gather<'a> {
    pub client: &'a str,
    pub verb: &'a str,
    /// image | video | audio.
    pub kind: &'a str,
    pub mode: GatherMode,
    pub dir: Option<PathBuf>,
    pub max: u64,
    pub keep: bool,
    pub http: &'a reqwest::Client,
    pub deadline: Instant,
    /// Formats a deadline expiry (`job <id> timed out …` / `<verb>: timed out …`).
    pub timeout_msg: String,
    pub job_id: Option<String>,
}

static SEQ: AtomicU64 = AtomicU64::new(0);

impl Gather<'_> {
    fn too_big(&self) -> MediaError {
        MediaError::failed(format!(
            "{}: file exceeds gather.maxBytes ({} bytes)",
            self.verb, self.max
        ))
    }

    fn file(&self, mime: &str) -> MediaFile {
        MediaFile {
            kind: part_type(self.kind).into(),
            mime_type: mime.into(),
            ..Default::default()
        }
    }

    fn save(&self, bytes: &[u8], mime: &str, src: Option<&str>) -> Result<MediaFile, MediaError> {
        if bytes.len() as u64 > self.max {
            return Err(self.too_big());
        }
        let dir = match &self.dir {
            Some(d) => d.clone(),
            None => std::env::temp_dir().join("toolnexus-media"),
        };
        let dir = if dir.is_absolute() {
            dir
        } else {
            std::env::current_dir().unwrap_or_default().join(dir)
        };
        let io = |e: std::io::Error| MediaError::failed(format!("{}: cannot save: {e}", self.verb));
        std::fs::create_dir_all(&dir).map_err(io)?;
        let millis = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);
        let seq = SEQ.fetch_add(1, Ordering::SeqCst) + 1;
        let name = format!(
            "{}-{}-{millis}-{seq}{}",
            self.client,
            self.verb,
            src.and_then(url_ext)
                .unwrap_or_else(|| ext_for_mime(mime).to_string())
        );
        let path = dir.join(name);
        std::fs::write(&path, bytes).map_err(io)?;
        let p = path.to_string_lossy().into_owned();
        let mut f = self.file(mime);
        f.url = format!("file://{p}");
        f.path = p;
        Ok(f)
    }

    fn bytes(&self, bytes: Vec<u8>, mime: &str) -> Result<MediaFile, MediaError> {
        self.bytes_from(bytes, mime, None)
    }

    fn bytes_from(
        &self,
        bytes: Vec<u8>,
        mime: &str,
        src: Option<&str>,
    ) -> Result<MediaFile, MediaError> {
        if self.mode == GatherMode::Inline {
            if bytes.len() as u64 > self.max {
                return Err(self.too_big());
            }
            let mut f = self.file(mime);
            f.data = b64_encode(&bytes);
            return Ok(f);
        }
        self.save(&bytes, mime, src)
    }

    pub(crate) async fn gather(&self, raw: Raw) -> Result<MediaFile, MediaError> {
        match raw {
            Raw::Bytes(b, mime) => self.bytes(b, &mime),
            Raw::Base64(s, mime) => {
                let b = b64_decode(&s).ok_or_else(|| {
                    MediaError::failed(format!("{}: result is not valid base64", self.verb))
                })?;
                self.bytes(b, &mime)
            }
            Raw::DataUrl(u) => {
                let (mime, b) = parse_data_url(&u).ok_or_else(|| {
                    MediaError::failed(format!("{}: malformed data URL", self.verb))
                })?;
                self.bytes(b, &mime)
            }
            Raw::Url(u) => {
                if let Some(p) = u.strip_prefix("file://") {
                    return Box::pin(self.gather(Raw::Local(PathBuf::from(p)))).await;
                }
                if self.mode == GatherMode::Url || !is_http(&u) {
                    let mut f = self.file(&mime_for(&u, self.kind));
                    f.url = u;
                    return Ok(f);
                }
                let sent = send(
                    self.http,
                    self.verb,
                    "GET",
                    &u,
                    Body::None,
                    &[],
                    None,
                    self.deadline,
                    Some(self.max),
                )
                .await
                .map_err(|e| match e {
                    SendError::TimedOut => {
                        MediaError::timeout(self.timeout_msg.clone(), self.job_id.clone())
                    }
                    SendError::Msg(m) => MediaError::failed(m),
                })?;
                if !(200..300).contains(&sent.status) {
                    return Err(MediaError::failed(format!(
                        "{}: download failed: HTTP {}",
                        self.verb, sent.status
                    )));
                }
                let ct = sent
                    .header("content-type")
                    .map(|c| {
                        c.split(';')
                            .next()
                            .unwrap_or("")
                            .trim()
                            .to_ascii_lowercase()
                    })
                    .filter(|c| !c.is_empty() && c != "application/octet-stream");
                if sent.body.len() as u64 > self.max {
                    return Err(MediaError::failed(format!(
                        "{}: download failed: file exceeds gather.maxBytes ({} bytes)",
                        self.verb, self.max
                    )));
                }
                let mime = ct.unwrap_or_else(|| mime_for(&u, self.kind));
                self.bytes_from(sent.body, &mime, Some(&u))
            }
            Raw::Local(p) => {
                let ps = p.to_string_lossy().into_owned();
                let mime = mime_from_ext(&ps)
                    .map(str::to_string)
                    .unwrap_or_else(|| mime_for("", self.kind));
                if self.mode == GatherMode::Url {
                    let mut f = self.file(&mime);
                    f.url = format!("file://{ps}");
                    f.path = ps;
                    return Ok(f);
                }
                let size = std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0);
                if size > self.max {
                    return Err(self.too_big());
                }
                let b = std::fs::read(&p).map_err(|e| {
                    MediaError::failed(format!("{}: cannot read {ps}: {e}", self.verb))
                })?;
                let f = self.bytes(b, &mime)?;
                if self.mode == GatherMode::Download && !self.keep {
                    let _ = std::fs::remove_file(&p);
                }
                Ok(f)
            }
        }
    }
}

fn parse_data_url(u: &str) -> Option<(String, Vec<u8>)> {
    let rest = u.strip_prefix("data:")?;
    let (meta, payload) = rest.split_once(',')?;
    let mut segs = meta.split(';');
    let mime = segs.next().unwrap_or("").to_string();
    let b64 = segs.any(|s| s.eq_ignore_ascii_case("base64"));
    let bytes = if b64 {
        b64_decode(payload)?
    } else {
        payload.as_bytes().to_vec()
    };
    let mime = if mime.is_empty() {
        "application/octet-stream".to_string()
    } else {
        mime
    };
    Some((mime, bytes))
}

// ---------------------------------------------------------------------------
// input references (§7G.1)
// ---------------------------------------------------------------------------

/// An input file resolved for upload.
pub(crate) struct Input {
    pub bytes: Vec<u8>,
    pub filename: String,
    pub content_type: String,
}

/// Resolve an input reference: http(s) ⇒ GET with no client headers; a
/// `file://` URL or a path ⇒ read. Failure ⇒ `<verb>: cannot read "<value>": …`.
pub(crate) async fn read_input(
    http: &reqwest::Client,
    verb: &str,
    value: &str,
    max: u64,
    deadline: Instant,
) -> Result<Input, MediaError> {
    let fail = |why: String| MediaError::failed(format!("{verb}: cannot read \"{value}\": {why}"));
    let bytes = if is_http(value) {
        let sent = send(
            http,
            verb,
            "GET",
            value,
            Body::None,
            &[],
            None,
            deadline,
            Some(max),
        )
        .await
        .map_err(|e| match e {
            SendError::TimedOut => fail("timed out".into()),
            SendError::Msg(m) => fail(m),
        })?;
        if !(200..300).contains(&sent.status) {
            return Err(fail(format!("HTTP {}", sent.status)));
        }
        sent.body
    } else {
        let p = value.strip_prefix("file://").unwrap_or(value);
        if std::fs::metadata(Path::new(p)).is_ok_and(|m| m.len() > max) {
            return Err(fail(format!("file exceeds gather.maxBytes ({max} bytes)")));
        }
        std::fs::read(Path::new(p)).map_err(|e| fail(e.to_string()))?
    };
    if bytes.len() as u64 > max {
        return Err(fail(format!("file exceeds gather.maxBytes ({max} bytes)")));
    }
    let path = url_path(value);
    let filename = path.rsplit('/').next().unwrap_or("").to_string();
    let filename = if filename.is_empty() {
        "input".to_string()
    } else {
        filename
    };
    let content_type = mime_from_ext(&filename)
        .unwrap_or("application/octet-stream")
        .to_string();
    Ok(Input {
        bytes,
        filename,
        content_type,
    })
}

// ---------------------------------------------------------------------------
// multipart
// ---------------------------------------------------------------------------

/// One multipart part: name, `(filename, content type)` for a file, bytes.
type Part = (String, Option<(String, String)>, Vec<u8>);

/// A `multipart/form-data` body, built by hand (fields and files in order).
#[derive(Default)]
pub(crate) struct Multipart {
    parts: Vec<Part>,
}

impl Multipart {
    pub fn field(&mut self, name: &str, value: &str) {
        self.parts
            .push((name.into(), None, value.as_bytes().to_vec()));
    }
    pub fn file(&mut self, name: &str, input: Input) {
        self.parts.push((
            name.into(),
            Some((input.filename, input.content_type)),
            input.bytes,
        ));
    }
    /// Set (replace) a text field, or add it (`providerOptions`).
    pub fn set_field(&mut self, name: &str, value: &str) {
        if let Some(p) = self.parts.iter_mut().find(|p| p.0 == name && p.1.is_none()) {
            p.2 = value.as_bytes().to_vec();
        } else {
            self.field(name, value);
        }
    }
    pub fn body(&self) -> Body {
        let boundary = format!(
            "toolnexus-{:x}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        );
        let mut out = Vec::new();
        for (name, file, bytes) in &self.parts {
            out.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
            match file {
                Some((fname, ct)) => out.extend_from_slice(
                    format!(
                        "Content-Disposition: form-data; name=\"{name}\"; filename=\"{fname}\"\r\nContent-Type: {ct}\r\n\r\n"
                    )
                    .as_bytes(),
                ),
                None => out.extend_from_slice(
                    format!("Content-Disposition: form-data; name=\"{name}\"\r\n\r\n").as_bytes(),
                ),
            }
            out.extend_from_slice(bytes);
            out.extend_from_slice(b"\r\n");
        }
        out.extend_from_slice(format!("--{boundary}--\r\n").as_bytes());
        Body::Raw(out, format!("multipart/form-data; boundary={boundary}"))
    }
}
