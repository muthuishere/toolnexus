//! Classifier backends, images, cache and validation (SPEC.md §8B "Backends,
//! images, cache and validation", OpenSpec change `add-classifier-backends`,
//! ADR 0038). Every default here is a PROPOSED default, owner to confirm
//! (`openspec/changes/add-classifier-backends/design.md` PD-1..PD-15). Ported
//! from `golang/classifier_backends.go`.

use std::collections::{BTreeSet, HashMap};
use std::sync::{LazyLock, Mutex};
use std::time::{Duration, Instant};

use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use super::{
    Classifier, ClassifierBackend, ClassifierError, DEFAULT_CLASSIFIER_API_KEY_ENV,
    DEFAULT_CLASSIFIER_BASE_URL, DEFAULT_CLASSIFIER_BATCH_CONCURRENCY, DEFAULT_CLASSIFIER_MODEL,
    Decision, DecisionAnswer, EvalContext, OPENROUTER_CLASSIFIER_API_KEY_ENV,
    OPENROUTER_CLASSIFIER_BASE_URL, OPENROUTER_CLASSIFIER_MODEL, Questions, canonical_json, err,
    wire_questions,
};
use crate::judge::JudgeQuestion;

// ---------------------------------------------------------------- backends

/// TypeSafe's required opt-in for image ("danger zone") input.
pub const IMAGES_HEADER: &str = "TypeSafe-Danger-Zone-Images";
/// Image limits (docs.typesafe.ai/images). Outside them the API rejects and
/// never resizes, so they are checked client-side before any request.
pub const CLASSIFIER_IMAGE_MIN_SIDE: u32 = 280;
pub const CLASSIFIER_IMAGE_MAX_SIDE: u32 = 1568;
pub const CLASSIFIER_IMAGE_MAX_BYTES: usize = 16 << 20;
pub const CLASSIFIER_REQUEST_IMAGE_BYTES: usize = 32 << 20;

/// The request shape a backend row uses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClassifierWire {
    /// `POST {base_url}/systemone` with `{model, questions, state, assets?}`.
    SystemOne,
    /// `POST {base_url}` with `{model, input:{state, questions, images?}}`,
    /// reply `{success, result}`.
    Cloudflare,
    /// `POST {base_url}/decisions` (OpenAI Decisions API): `{model, input,
    /// questions:[…]}`, answers by position (issue #157).
    Decisions,
}

impl ClassifierWire {
    /// The wire spelling (`systemone` | `cloudflare` | `decisions`).
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::SystemOne => "systemone",
            Self::Cloudflare => "cloudflare",
            Self::Decisions => "decisions",
        }
    }
}

/// What a backend+model can take. DATA: a fail-fast for what the table says,
/// never a claim about the vendor. Override per classifier with
/// `ClassifierOptions::supports`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClassifierSupports {
    #[serde(default)]
    pub images: bool,
}

/// One row of the backend table.
#[derive(Debug, Clone, Copy)]
pub struct ClassifierBackendPreset {
    pub backend: ClassifierBackend,
    /// `""` on `Custom`, which requires an explicit base URL.
    pub base_url: &'static str,
    pub model: &'static str,
    pub api_key_env: &'static str,
    pub wire: ClassifierWire,
    /// Send [`IMAGES_HEADER`]`: true` when a request carries images.
    pub images_header: bool,
    /// The default support for a model on this backend. `None` on `Custom`,
    /// which requires `ClassifierOptions::supports`.
    pub supports: Option<fn(&str) -> ClassifierSupports>,
}

fn images_yes(_: &str) -> ClassifierSupports {
    ClassifierSupports { images: true }
}
fn images_no(_: &str) -> ClassifierSupports {
    ClassifierSupports { images: false }
}
fn images_if_clef(model: &str) -> ClassifierSupports {
    ClassifierSupports {
        images: model.contains("clef"),
    }
}

/// The backend table (researched and live-probed 2026-11-04, ADR 0038).
/// Vercel and Cloudflare-Jev images stay false until a live probe proves them:
/// an unproven image path fails loudly rather than send bytes a gateway may
/// silently drop.
pub const CLASSIFIER_BACKENDS: [ClassifierBackendPreset; 6] = [
    ClassifierBackendPreset {
        backend: ClassifierBackend::TypeSafe,
        base_url: DEFAULT_CLASSIFIER_BASE_URL,
        model: DEFAULT_CLASSIFIER_MODEL,
        api_key_env: DEFAULT_CLASSIFIER_API_KEY_ENV,
        wire: ClassifierWire::SystemOne,
        images_header: true,
        supports: Some(images_yes),
    },
    ClassifierBackendPreset {
        backend: ClassifierBackend::OpenRouter,
        base_url: OPENROUTER_CLASSIFIER_BASE_URL,
        model: OPENROUTER_CLASSIFIER_MODEL,
        api_key_env: OPENROUTER_CLASSIFIER_API_KEY_ENV,
        wire: ClassifierWire::SystemOne,
        images_header: true,
        supports: Some(images_yes),
    },
    ClassifierBackendPreset {
        backend: ClassifierBackend::Cloudflare,
        base_url: "https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run",
        model: "typesafe/jev",
        api_key_env: "CLOUDFLARE_JEV_API_KEY",
        wire: ClassifierWire::Cloudflare,
        images_header: false,
        supports: Some(images_if_clef),
    },
    ClassifierBackendPreset {
        backend: ClassifierBackend::Vercel,
        base_url: "https://ai-gateway.vercel.sh/typesafe/v1",
        model: "typesafe-ai/jev",
        api_key_env: "AI_GATEWAY_API_KEY",
        wire: ClassifierWire::SystemOne,
        images_header: true,
        supports: Some(images_no),
    },
    ClassifierBackendPreset {
        backend: ClassifierBackend::OpenAi,
        base_url: "https://api.openai.com/v1",
        model: "gpt-6-luna",
        api_key_env: "OPENAI_API_KEY",
        wire: ClassifierWire::Decisions,
        images_header: false,
        supports: Some(images_yes),
    },
    ClassifierBackendPreset {
        backend: ClassifierBackend::Custom,
        base_url: "",
        model: DEFAULT_CLASSIFIER_MODEL,
        api_key_env: "",
        wire: ClassifierWire::SystemOne,
        images_header: true,
        supports: None,
    },
];

/// The env var whose key may be sent to `base_url` (already `${NAME}`-expanded):
/// the backend's own variable, and only when `base_url` is that backend's own
/// host over https (OpenRouter also its subdomains). `""` when none may; custom
/// has no preset key. A key is never sent to a host the caller did not name
/// (#155, SPEC §8B).
#[doc(hidden)]
pub fn classifier_key_env(backend: ClassifierBackend, base_url: &str) -> &'static str {
    let p = backend.preset();
    if p.api_key_env.is_empty() || p.base_url.is_empty() {
        return "";
    }
    let host_of = |u: &str| -> Option<String> {
        let u = url::Url::parse(u).ok()?;
        if u.scheme() != "https" {
            return None;
        }
        u.host_str().map(str::to_ascii_lowercase)
    };
    let (Some(h), Some(own)) = (host_of(base_url), host_of(p.base_url)) else {
        return "";
    };
    if h == own || (backend == ClassifierBackend::OpenRouter && h.ends_with(".openrouter.ai")) {
        p.api_key_env
    } else {
        ""
    }
}

/// Raised BEFORE any network call when the backend's support table says it
/// cannot take what the request carries. Reach it from a [`ClassifierError`]
/// with [`ClassifierError::unsupported`].
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("classifier: {backend} ({model}) does not support {feature}")]
pub struct ClassifierUnsupported {
    pub backend: String,
    pub model: String,
    pub feature: String,
}

static ENV_REF: LazyLock<regex::Regex> =
    LazyLock::new(|| regex::Regex::new(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}").expect("static regex"));

/// Expands `${NAME}` only (not `$NAME`) from the environment, unset ⇒ empty.
pub(super) fn expand_env_refs(s: &str) -> String {
    if !s.contains("${") {
        return s.to_owned();
    }
    ENV_REF
        .replace_all(s, |c: &regex::Captures| {
            std::env::var(&c[1]).unwrap_or_default()
        })
        .into_owned()
}

// ---------------------------------------------------------------- open union

/// The open end of the question union: a System One type the library does
/// not model (e.g. Jev 1.14 `box`), sent as written with no client-side limits.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RawQuestion {
    /// The wire `type`; must be non-empty.
    pub kind: String,
    pub instructions: String,
    /// Sent verbatim when `Some`, omitted when `None`.
    pub criteria: Option<Value>,
}

impl RawQuestion {
    pub(super) fn wire(&self) -> Value {
        let mut m = Map::new();
        m.insert("type".into(), Value::String(self.kind.clone()));
        m.insert(
            "instructions".into(),
            Value::String(self.instructions.clone()),
        );
        if let Some(c) = &self.criteria {
            m.insert("criteria".into(), c.clone());
        }
        Value::Object(m)
    }

    pub(super) fn validate(&self, key: &str) -> Result<(), ClassifierError> {
        if self.kind.is_empty() {
            return Err(err(format!(
                "classifier: question {key:?}: a raw question needs a type"
            )));
        }
        Ok(())
    }
}

/// An answer of a type the library does not model. `value` is the whole
/// answer object as decoded (including `type`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RawAnswer {
    pub kind: String,
    pub value: Map<String, Value>,
}

// ---------------------------------------------------------------- images

/// One image lifted out of a state, in collection order.
#[derive(Debug, Clone)]
pub(super) struct LiftedImage {
    pub reference: String,
    /// The MIME type until [`check_images`] normalises it to `png` | `jpeg`.
    pub format: String,
    pub data: String,
}

/// A §1B content part inside a JSON state: `{type:"text",text}` or
/// `{type:"image"|"file"|"audio",mimeType}` (the shape `ContentPart` serialises to).
fn part_type(v: &Value) -> Option<&str> {
    let m = v.as_object()?;
    let t = m.get("type")?.as_str()?;
    match t {
        "text" if m.get("text").is_some_and(Value::is_string) => Some(t),
        "image" | "file" | "audio" if m.get("mimeType").is_some_and(Value::is_string) => Some(t),
        _ => None,
    }
}

fn contains_part(v: &Value) -> bool {
    if part_type(v).is_some() {
        return true;
    }
    match v {
        Value::Object(m) => m.values().any(contains_part),
        Value::Array(a) => a.iter().any(contains_part),
        _ => false,
    }
}

/// The §8B lifting walk. A state with no content part is returned VERBATIM, so
/// a text-only state is byte-identical to before. Otherwise depth-first,
/// object keys in ASCII order, arrays in order.
pub(super) fn lift_images(state: &Value) -> Result<(Value, Vec<LiftedImage>), ClassifierError> {
    if !contains_part(state) {
        return Ok((state.clone(), Vec::new()));
    }
    let mut used = BTreeSet::new();
    let mut imgs = Vec::new();
    let out = walk(state, "", &mut used, &mut imgs)?;
    Ok((out, imgs))
}

fn walk(
    v: &Value,
    key: &str,
    used: &mut BTreeSet<String>,
    imgs: &mut Vec<LiftedImage>,
) -> Result<Value, ClassifierError> {
    let str_of = |name: &str| v.get(name).and_then(Value::as_str).unwrap_or("").to_owned();
    match part_type(v) {
        Some("text") => return Ok(Value::String(str_of("text"))),
        Some("image") => {
            let data = str_of("data");
            if data.is_empty() {
                return Err(err(
                    "classifier: state: an image part needs inline data (a URL image cannot be sent)",
                ));
            }
            let reference = if key.is_empty() || used.contains(key) {
                (1..)
                    .map(|n| format!("image{n}"))
                    .find(|r| !used.contains(r))
                    .unwrap_or_default()
            } else {
                key.to_owned()
            };
            used.insert(reference.clone());
            imgs.push(LiftedImage {
                reference: reference.clone(),
                format: str_of("mimeType"),
                data,
            });
            return Ok(Value::String(reference));
        }
        Some(t) => {
            return Err(err(format!(
                "classifier: state: {t} parts are not supported"
            )));
        }
        None => {}
    }
    match v {
        Value::Object(m) => {
            let mut keys: Vec<&String> = m.keys().collect();
            keys.sort();
            let mut out = Map::new();
            for k in keys {
                out.insert(k.clone(), walk(&m[k], k, used, imgs)?);
            }
            Ok(Value::Object(out))
        }
        Value::Array(a) => a
            .iter()
            .map(|e| walk(e, "", used, imgs))
            .collect::<Result<Vec<_>, _>>()
            .map(Value::Array),
        _ => Ok(v.clone()),
    }
}

/// Format, per-image and per-request limits, in collection order; normalises
/// the format. Errors name the reference.
pub(super) fn check_images(imgs: &mut [LiftedImage]) -> Result<(), ClassifierError> {
    let mut total = 0usize;
    for im in imgs.iter_mut() {
        let r = &im.reference;
        let format = match im.format.as_str() {
            "image/png" => "png",
            "image/jpeg" | "image/jpg" => "jpeg",
            other => {
                return Err(err(format!(
                    "classifier: image {r:?}: type {other:?} is not supported (PNG or JPEG only)"
                )));
            }
        };
        let raw = base64::engine::general_purpose::STANDARD
            .decode(&im.data)
            .map_err(|_| err(format!("classifier: image {r:?}: data is not base64")))?;
        if raw.len() > CLASSIFIER_IMAGE_MAX_BYTES {
            return Err(err(format!(
                "classifier: image {r:?}: {} bytes, over 16 MiB",
                raw.len()
            )));
        }
        let Some((w, h)) = image_size(format, &raw) else {
            return Err(err(format!(
                "classifier: image {r:?}: not a readable {}",
                format.to_ascii_uppercase()
            )));
        };
        let side = CLASSIFIER_IMAGE_MIN_SIDE..=CLASSIFIER_IMAGE_MAX_SIDE;
        if !side.contains(&w) || !side.contains(&h) {
            return Err(err(format!(
                "classifier: image {r:?}: {w}x{h}; each side must be {CLASSIFIER_IMAGE_MIN_SIDE}..{CLASSIFIER_IMAGE_MAX_SIDE} px"
            )));
        }
        im.format = format.into();
        total += raw.len();
    }
    if total > CLASSIFIER_REQUEST_IMAGE_BYTES
        && let Some(last) = imgs.last()
    {
        return Err(err(format!(
            "classifier: image {:?}: request images total {total} bytes, over 32 MiB",
            last.reference
        )));
    }
    Ok(())
}

/// Width/height from a PNG IHDR or the first JPEG SOF marker.
fn image_size(format: &str, b: &[u8]) -> Option<(u32, u32)> {
    let be16 = |i: usize| (u32::from(b[i]) << 8) | u32::from(b[i + 1]);
    if format == "png" {
        if b.len() < 24 || &b[..8] != b"\x89PNG\r\n\x1a\n" || &b[12..16] != b"IHDR" {
            return None;
        }
        let w = u32::from_be_bytes([b[16], b[17], b[18], b[19]]);
        let h = u32::from_be_bytes([b[20], b[21], b[22], b[23]]);
        return Some((w, h));
    }
    if b.len() < 4 || b[0] != 0xFF || b[1] != 0xD8 {
        return None;
    }
    let mut i = 2usize;
    while i + 3 < b.len() {
        if b[i] != 0xFF {
            return None;
        }
        let m = b[i + 1];
        if m == 0xFF {
            // fill byte
            i += 1;
            continue;
        }
        if (0xC0..=0xCF).contains(&m) && m != 0xC4 && m != 0xC8 && m != 0xCC {
            if i + 8 >= b.len() {
                return None;
            }
            return Some((be16(i + 7), be16(i + 5)));
        }
        i += 2 + be16(i + 2) as usize;
    }
    None
}

// ---------------------------------------------------------------- cache

/// An injected answer cache. Values are opaque strings: the served model under
/// a model key, the System One reply body under a decision key.
pub trait ClassifierCache: Send + Sync {
    fn get(&self, key: &str) -> Option<String>;
    fn set(&self, key: &str, value: &str);
}

/// An in-process TTL cache with a size cap (oldest-inserted out first).
/// `ttl` zero ⇒ no expiry; `max` 0 ⇒ unbounded. Safe for concurrent use.
#[derive(Debug, Default)]
pub struct MemoryCache {
    ttl: Duration,
    max: usize,
    inner: Mutex<MemInner>,
}

#[derive(Debug, Default)]
struct MemInner {
    m: HashMap<String, (String, Instant, u64)>,
    seq: u64,
}

impl MemoryCache {
    pub fn new(ttl: Duration, max: usize) -> Self {
        MemoryCache {
            ttl,
            max,
            inner: Mutex::default(),
        }
    }
}

impl ClassifierCache for MemoryCache {
    fn get(&self, key: &str) -> Option<String> {
        let mut g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let (v, at, _) = g.m.get(key)?;
        if !self.ttl.is_zero() && at.elapsed() > self.ttl {
            g.m.remove(key);
            return None;
        }
        Some(v.clone())
    }

    fn set(&self, key: &str, value: &str) {
        let mut g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        g.seq += 1;
        let seq = g.seq;
        g.m.insert(key.to_owned(), (value.to_owned(), Instant::now(), seq));
        if self.max > 0 && g.m.len() > self.max {
            let mut ks: Vec<(u64, String)> = g.m.iter().map(|(k, e)| (e.2, k.clone())).collect();
            ks.sort();
            let drop = g.m.len() - self.max;
            for (_, k) in ks.into_iter().take(drop) {
                g.m.remove(&k);
            }
        }
    }
}

/// SHA-256 hex (FIPS 180-4), dependency-free.
pub(crate) fn sha256_hex(data: &[u8]) -> String {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];
    let mut msg = data.to_vec();
    let bit_len = (data.len() as u64).wrapping_mul(8);
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());
    for chunk in msg.chunks(64) {
        let mut w = [0u32; 64];
        for (i, word) in w.iter_mut().take(16).enumerate() {
            *word = u32::from_be_bytes([
                chunk[4 * i],
                chunk[4 * i + 1],
                chunk[4 * i + 2],
                chunk[4 * i + 3],
            ]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let mut v = h;
        for i in 0..64 {
            let s1 = v[4].rotate_right(6) ^ v[4].rotate_right(11) ^ v[4].rotate_right(25);
            let ch = (v[4] & v[5]) ^ (!v[4] & v[6]);
            let t1 = v[7]
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = v[0].rotate_right(2) ^ v[0].rotate_right(13) ^ v[0].rotate_right(22);
            let maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
            let t2 = s0.wrapping_add(maj);
            v = [
                t1.wrapping_add(t2),
                v[0],
                v[1],
                v[2],
                v[3].wrapping_add(t1),
                v[4],
                v[5],
                v[6],
            ];
        }
        for (hi, vi) in h.iter_mut().zip(v) {
            *hi = hi.wrapping_add(vi);
        }
    }
    h.iter().map(|x| format!("{x:08x}")).collect()
}

/// §8B's model key: which model an endpoint+alias served last.
pub fn classifier_model_cache_key(endpoint: &str, model: &str) -> String {
    let mut m = Map::new();
    m.insert("endpoint".into(), Value::String(endpoint.into()));
    m.insert("kind".into(), Value::String("model".into()));
    m.insert("model".into(), Value::String(model.into()));
    sha256_hex(canonical_json(&Value::Object(m)).as_bytes())
}

/// §8B's decision key, on the SERVED model. `assets` `None`/empty ⇒ the
/// `assets` member is omitted.
pub fn classifier_decision_cache_key(
    endpoint: &str,
    served_model: &str,
    state: &Value,
    assets: Option<&Map<String, Value>>,
    questions: &Questions,
) -> String {
    let mut m = Map::new();
    m.insert("endpoint".into(), Value::String(endpoint.into()));
    m.insert("kind".into(), Value::String("decision".into()));
    m.insert("model".into(), Value::String(served_model.into()));
    m.insert("questions".into(), wire_questions(questions));
    m.insert("state".into(), state.clone());
    if let Some(a) = assets.filter(|a| !a.is_empty()) {
        m.insert("assets".into(), Value::Object(a.clone()));
    }
    sha256_hex(canonical_json(&Value::Object(m)).as_bytes())
}

// ---------------------------------------------------------------- V1

/// Fails closed on a reply that does not answer exactly the questions asked
/// within their schema (ADR 0038 V1). First failure wins, in the §8B order.
pub fn validate_decision(questions: &Questions, d: &Decision) -> Result<(), ClassifierError> {
    let bad = |s: String| err(format!("classifier: invalid decision: {s}"));
    // Questions is a BTreeMap: iteration is already ASCII key order.
    if let Some(k) = questions.keys().find(|k| !d.answers.contains_key(*k)) {
        return Err(bad(format!("no answer {k:?}")));
    }
    if let Some(k) = d.answers.keys().find(|k| !questions.contains_key(*k)) {
        return Err(bad(format!("unexpected answer {k:?}")));
    }
    let in01 = |p: f64| (0.0..=1.0).contains(&p);
    let dist = |k: &str,
                probs: &std::collections::BTreeMap<String, f64>,
                offered: &dyn Fn(&str) -> bool|
     -> Result<(), ClassifierError> {
        let mut sum = 0.0;
        for (o, p) in probs {
            if !offered(o) {
                return Err(bad(format!(
                    "answer {k:?}: probability for {o:?}, which was not offered"
                )));
            }
            if !in01(*p) {
                return Err(bad(format!("answer {k:?}: probability {p} outside [0,1]")));
            }
            sum += p;
        }
        if !probs.is_empty() && (sum - 1.0).abs() > 0.02 {
            return Err(bad(format!("answer {k:?}: probabilities sum to {sum:.4}")));
        }
        Ok(())
    };
    for (k, q) in questions {
        let a = &d.answers[k];
        let qt = q.question_type();
        if a.answer_type() != qt {
            return Err(bad(format!(
                "answer {k:?} is a {} answer, not {qt}",
                a.answer_type()
            )));
        }
        match (q, a) {
            (_, DecisionAnswer::Noul(x)) => {
                if !in01(x.noul) {
                    return Err(bad(format!("answer {k:?}: noul {} outside [0,1]", x.noul)));
                }
            }
            (super::Question::Choice(cq), DecisionAnswer::Choice(x)) => {
                let offered = |o: &str| cq.criteria.contains_key(o);
                if !offered(&x.choice) {
                    return Err(bad(format!(
                        "answer {k:?}: choice {:?} was not offered",
                        x.choice
                    )));
                }
                dist(k, &x.probabilities, &offered)?;
                if !in01(x.confidence) {
                    return Err(bad(format!(
                        "answer {k:?}: confidence {} outside [0,1]",
                        x.confidence
                    )));
                }
            }
            (super::Question::Score(sq), DecisionAnswer::Score(x)) => {
                let n = sq.criteria.len();
                let top = n as f64 - 1.0;
                if x.score.is_nan() || x.score < 0.0 || x.score > top {
                    return Err(bad(format!(
                        "answer {k:?}: score {} outside [0,{}]",
                        x.score,
                        n as i64 - 1
                    )));
                }
                let offered = |o: &str| (0..n).any(|i| o == i.to_string());
                dist(k, &x.probabilities, &offered)?;
                if !in01(x.confidence) {
                    return Err(bad(format!(
                        "answer {k:?}: confidence {} outside [0,1]",
                        x.confidence
                    )));
                }
            }
            // A raw question matches only on type and gets no further checks.
            _ => {}
        }
    }
    Ok(())
}

// ---------------------------------------------------------------- C1

/// Splits a question set into chunks of `size`, keys in ASCII order. A helper,
/// not automatic: joint calibration across a split is unproven.
pub fn chunk_questions(
    questions: &Questions,
    size: usize,
) -> Result<Vec<Questions>, ClassifierError> {
    if size < 1 {
        return Err(err(format!(
            "classifier: chunk size must be at least 1, got {size}"
        )));
    }
    let entries: Vec<(&String, &super::Question)> = questions.iter().collect();
    Ok(entries
        .chunks(size)
        .map(|c| {
            c.iter()
                .map(|(k, q)| ((*k).clone(), (*q).clone()))
                .collect()
        })
        .collect())
}

// ---------------------------------------------------------------- builders

/// A bound state; nothing is sent until [`evaluate`](Self::evaluate). A
/// value, not a session.
#[derive(Debug, Clone)]
pub struct ClassifierState {
    c: Classifier,
    v: Value,
}

/// One state's outcome in [`ClassifierStates::evaluate`].
pub type BatchResult = Result<Decision, ClassifierError>;

/// A batch of bound states. See [`ClassifierStates::evaluate`].
#[derive(Debug, Clone)]
pub struct ClassifierStates {
    c: Classifier,
    vs: Vec<Value>,
}

impl Classifier {
    /// Binds `v`. See [`ClassifierState`].
    pub fn state(&self, v: Value) -> ClassifierState {
        ClassifierState { c: self.clone(), v }
    }

    /// Binds a batch. See [`ClassifierStates::evaluate`].
    pub fn states(&self, vs: Vec<Value>) -> ClassifierStates {
        ClassifierStates {
            c: self.clone(),
            vs,
        }
    }
}

impl ClassifierState {
    /// Asks `qs` about the state in one request, and validates the reply
    /// fail-closed ([`validate_decision`]).
    pub async fn evaluate(&self, qs: &[JudgeQuestion]) -> Result<Decision, ClassifierError> {
        self.evaluate_ctx(EvalContext::default(), qs).await
    }

    /// [`evaluate`](Self::evaluate) with cancellation and a call name.
    pub async fn evaluate_ctx(
        &self,
        ctx: EvalContext,
        qs: &[JudgeQuestion],
    ) -> Result<Decision, ClassifierError> {
        let questions = crate::judge::questions(qs)?;
        self.c
            .evaluate_inner(ctx, self.v.clone(), &questions, true)
            .await
    }
}

impl ClassifierStates {
    /// Fans out `state(v).evaluate` per state (at most 16 in flight) and
    /// returns one result per state, in order. One state's failure never fails
    /// the batch; the outer error is only for the question set (duplicate name).
    pub async fn evaluate(
        &self,
        qs: &[JudgeQuestion],
    ) -> Result<Vec<BatchResult>, ClassifierError> {
        use futures_util::StreamExt;
        let questions = crate::judge::questions(qs)?;
        let questions = &questions;
        Ok(futures_util::stream::iter(self.vs.iter().cloned().map(|v| {
            let c = self.c.clone();
            async move {
                c.evaluate_inner(EvalContext::default(), v, questions, true)
                    .await
            }
        }))
        .buffered(DEFAULT_CLASSIFIER_BATCH_CONCURRENCY)
        .collect()
        .await)
    }
}

// ---------------------------------------------------------------- wire path

/// One systemone-style request after lifting and checks.
pub(super) struct Prepared {
    pub(super) state: Value,
    pub(super) imgs: Vec<LiftedImage>,
    assets: Option<Map<String, Value>>,
}

/// The System One reply inside a Cloudflare envelope. A failed envelope names
/// `errors[0]` only, through the §8B cause policy (redacted, capped).
fn unwrap_cloudflare(body: &[u8]) -> Result<Vec<u8>, ClassifierError> {
    let env: Value = serde_json::from_slice(body)
        .map_err(|_| err("classifier: cloudflare: envelope not successful"))?;
    if env.get("success") != Some(&Value::Bool(true)) {
        return Err(err(format!(
            "classifier: cloudflare: envelope not successful{}",
            super::cloudflare_cause(env.get("errors"))
        )));
    }
    let mut res = env.get("result").cloned().unwrap_or(Value::Null);
    if let Some(st) = res.get("state").and_then(Value::as_str) {
        if st != "Completed" {
            return Err(err(format!("classifier: cloudflare: run state {st:?}")));
        }
        res = res.get("result").cloned().unwrap_or(Value::Null);
    }
    Ok(canonical_json(&res).into_bytes())
}

impl Classifier {
    fn prepare(&self, state: &Value) -> Result<Prepared, ClassifierError> {
        let (sent, mut imgs) = lift_images(state)?;
        if !imgs.is_empty() && !self.inner.supports.images {
            return Err(ClassifierUnsupported {
                backend: self.inner.opts.backend.as_str().into(),
                model: self.inner.opts.model.clone(),
                feature: "images".into(),
            }
            .into());
        }
        check_images(&mut imgs)?;
        let assets = (!imgs.is_empty()).then(|| {
            imgs.iter()
                .map(|im| {
                    let mut a = Map::new();
                    a.insert("type".into(), Value::String("image".into()));
                    a.insert("format".into(), Value::String(im.format.clone()));
                    a.insert("data_base64".into(), Value::String(im.data.clone()));
                    (im.reference.clone(), Value::Object(a))
                })
                .collect()
        });
        Ok(Prepared {
            state: sent,
            imgs,
            assets,
        })
    }

    /// The body for the backend's wire, then the §8 pipeline (request params
    /// merge → body transform → canonical bytes).
    fn wire_body(&self, p: &Prepared, questions: &Questions) -> Result<String, ClassifierError> {
        let o = &self.inner.opts;
        let mut b = Map::new();
        b.insert("model".into(), Value::String(o.model.clone()));
        if self.inner.preset.wire == ClassifierWire::Decisions {
            let list = super::decisions::decisions_questions(o.backend, &o.model, questions)?;
            b.insert("input".into(), super::decisions::decisions_input(p));
            b.insert("questions".into(), Value::Array(list));
        } else if self.inner.preset.wire == ClassifierWire::Cloudflare {
            let mut input = Map::new();
            input.insert("questions".into(), wire_questions(questions));
            input.insert("state".into(), p.state.clone());
            if !p.imgs.is_empty() {
                let urls = p
                    .imgs
                    .iter()
                    .map(|im| Value::String(format!("data:image/{};base64,{}", im.format, im.data)))
                    .collect();
                input.insert("images".into(), Value::Array(urls));
            }
            b.insert("input".into(), Value::Object(input));
        } else {
            b.insert("questions".into(), wire_questions(questions));
            b.insert("state".into(), p.state.clone());
            if let Some(a) = &p.assets {
                b.insert("assets".into(), Value::Object(a.clone()));
            }
        }
        for (k, v) in &o.request_params {
            b.insert(k.clone(), v.clone());
        }
        let mut body = Value::Object(b);
        if let Some(t) = &o.body_transform
            && let Some(out) = t(body.clone())
        {
            body = out;
        }
        Ok(canonical_json(&body))
    }

    /// The systemone style (every backend): lift, check, cache, post, decode.
    pub(super) async fn evaluate_wire(
        &self,
        ctx: &EvalContext,
        state: &Value,
        questions: &Questions,
        validate: bool,
    ) -> Result<Decision, ClassifierError> {
        let o = &self.inner.opts;
        let p = self.prepare(state)?;
        let model_key = classifier_model_cache_key(&o.base_url, &o.model);
        if let Some(cache) = &o.cache
            && let Some(served) = cache.get(&model_key)
        {
            let dk = classifier_decision_cache_key(
                &o.base_url,
                &served,
                &p.state,
                p.assets.as_ref(),
                questions,
            );
            if let Some(raw) = cache.get(&dk)
                && let Ok(mut d) = Decision::from_slice(raw.as_bytes())
                && validate_decision(questions, &d).is_ok()
            {
                d.cached = true;
                return Ok(d);
            }
        }
        let raw = self.wire_body(&p, questions)?;
        let extra: &[(&str, &str)] = if !p.imgs.is_empty() && self.inner.preset.images_header {
            &[(IMAGES_HEADER, "true")]
        } else {
            &[]
        };
        let mut body = self.post(&ctx.cancel, raw.into_bytes(), extra).await?;
        match self.inner.preset.wire {
            ClassifierWire::Cloudflare => body = unwrap_cloudflare(&body)?,
            ClassifierWire::Decisions => {
                body = super::decisions::decisions_reply(&body, questions)?.into_bytes()
            }
            ClassifierWire::SystemOne => {}
        }
        let d = Decision::from_slice(&body)?;
        let verdict = validate_decision(questions, &d);
        if validate {
            verdict.clone()?;
        }
        if let Some(cache) = &o.cache
            && verdict.is_ok()
        {
            let served = if d.model.is_empty() {
                o.model.clone()
            } else {
                d.model.clone()
            };
            let dk = classifier_decision_cache_key(
                &o.base_url,
                &served,
                &p.state,
                p.assets.as_ref(),
                questions,
            );
            cache.set(&model_key, &served);
            cache.set(&dk, &String::from_utf8_lossy(&body));
        }
        Ok(d)
    }
}
