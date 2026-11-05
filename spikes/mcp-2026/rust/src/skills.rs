//! Skill URI safety (python-sdk #3685): a skill:// URI is percent-decoded ONCE, then rejected if
//! it still contains '%' (double encoding), a backslash, NUL, an absolute/empty path, or a
//! "."/".." segment. Both the server (before lookup) and the client (before writing) use it.

use std::path::{Path, PathBuf};

use serde_json::{Value, json};

use crate::{Client, Error, sha256_hex};

/// Splits `skill://<name>/<path>` into a validated name and slash-separated relative path.
pub fn parse_skill_uri(uri: &str) -> Result<(String, String), String> {
    let rest = uri.strip_prefix("skill://").ok_or("not a skill:// URI")?;
    let dec = path_unescape(rest).ok_or("bad percent-encoding")?;
    if dec.contains(['%', '\\', '\0']) {
        // '%' left after one decode = double encoding
        return Err("skill URI has encoded, backslash or NUL characters".into());
    }
    let segs: Vec<&str> = dec.split('/').collect();
    if segs.len() < 2 {
        return Err("skill URI needs skill://<name>/<path>".into());
    }
    if segs
        .iter()
        .any(|s| s.is_empty() || *s == "." || *s == ".." || s.contains(':'))
    {
        return Err("skill URI path escapes the skill (empty, '.', '..' or ':' segment)".into());
    }
    Ok((segs[0].to_string(), segs[1..].join("/")))
}

/// Strict single percent-decode (like Go's url.PathUnescape): a bad `%xx` is an error.
fn path_unescape(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            let h = std::str::from_utf8(b.get(i + 1..i + 3)?).ok()?;
            out.push(u8::from_str_radix(h, 16).ok()?);
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8(out).ok()
}

fn is_local(rel: &str) -> bool {
    let p = Path::new(rel);
    !rel.is_empty()
        && p.is_relative()
        && p.components()
            .all(|c| matches!(c, std::path::Component::Normal(_)))
}

impl Client {
    /// Fetches a skill (skills/get + resources/read for every file) into `dir/<name>/`, verifying
    /// each file's sha256 digest. A file whose URI is not inside the skill is refused, and nothing
    /// is written unless every file validated.
    pub async fn download_skill(&self, uri: &str, dir: impl AsRef<Path>) -> Result<(), Error> {
        let (name, _) = parse_skill_uri(uri).map_err(Error::msg)?;
        let r = self.call("skills/get", json!({"uri": uri})).await?;
        let res = r
            .get("skill")
            .and_then(|s| s.get("resources"))
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        let mut files: Vec<(PathBuf, Vec<u8>)> = vec![];
        for m in res {
            let fu = m.get("uri").and_then(Value::as_str).unwrap_or_default();
            let ok = matches!(parse_skill_uri(fu), Ok((n, rel)) if n == name && is_local(&rel));
            if !ok {
                return Err(Error::msg(format!(
                    "refusing skill resource {fu:?}: outside skill {name}"
                )));
            }
            let (_, rel) = parse_skill_uri(fu).unwrap();
            let rr = self.call("resources/read", json!({"uri": fu})).await?;
            let Some(first) = rr
                .get("contents")
                .and_then(Value::as_array)
                .and_then(|c| c.first())
            else {
                return Err(Error::msg(format!("{fu}: empty resources/read")));
            };
            let t = first
                .get("text")
                .and_then(Value::as_str)
                .unwrap_or_default();
            if let Some(d) = m.get("digest").and_then(Value::as_str)
                && !d.is_empty()
                && d != format!("sha256:{}", sha256_hex(t.as_bytes()))
            {
                return Err(Error::msg(format!("{fu}: digest mismatch")));
            }
            let mut p = dir.as_ref().join(&name);
            for s in rel.split('/') {
                p.push(s);
            }
            files.push((p, t.as_bytes().to_vec()));
        }
        for (p, data) in files {
            std::fs::create_dir_all(p.parent().unwrap()).map_err(|e| Error::msg(e.to_string()))?;
            std::fs::write(&p, data).map_err(|e| Error::msg(e.to_string()))?;
        }
        Ok(())
    }
}
