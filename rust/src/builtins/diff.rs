//! `metadata.files` + `metadata.diff` for edit/write/apply_patch/notebook_edit
//! (port of `golang/builtin_diff.go`).

use std::fmt::Write as _;

use serde_json::{Value, json};

use super::Args;

pub(crate) struct FileChange {
    pub path: String,
    /// added | modified | deleted
    pub status: &'static str,
    pub before: String,
    pub after: String,
}

pub(crate) fn read_if_exists(p: &str) -> Option<String> {
    std::fs::read(p)
        .ok()
        .map(|b| String::from_utf8_lossy(&b).into_owned())
}

pub(crate) fn add_diff_meta(meta: &mut Args, changes: &[FileChange]) {
    let mut files = Vec::new();
    let mut diff = String::new();
    for ch in changes {
        let a = split_lines(&ch.before);
        let b = split_lines(&ch.after);
        let ops = diff_lines(&a, &b);
        let adds = ops.iter().filter(|o| o.0 == '+').count();
        let dels = ops.iter().filter(|o| o.0 == '-').count();
        files.push(
            json!({"path": ch.path, "status": ch.status, "additions": adds, "deletions": dels}),
        );
        write_unified(&mut diff, ch, &ops);
    }
    meta.insert("files".into(), Value::Array(files));
    meta.insert("diff".into(), Value::String(diff));
}

fn split_lines(s: &str) -> Vec<&str> {
    if s.is_empty() {
        return Vec::new();
    }
    s.strip_suffix('\n').unwrap_or(s).split('\n').collect()
}

type Op<'a> = (char, &'a str);

fn diff_lines<'a>(a: &[&'a str], b: &[&'a str]) -> Vec<Op<'a>> {
    let mut pre = 0;
    while pre < a.len() && pre < b.len() && a[pre] == b[pre] {
        pre += 1;
    }
    let mut suf = 0;
    while suf < a.len() - pre && suf < b.len() - pre && a[a.len() - 1 - suf] == b[b.len() - 1 - suf]
    {
        suf += 1;
    }
    let mut ops: Vec<Op> = a[..pre].iter().map(|l| (' ', *l)).collect();
    let ma = &a[pre..a.len() - suf];
    let mb = &b[pre..b.len() - suf];
    let (n, m) = (ma.len(), mb.len());
    if n * m > 4_000_000 {
        ops.extend(ma.iter().map(|l| ('-', *l)));
        ops.extend(mb.iter().map(|l| ('+', *l)));
    } else {
        let mut lcs = vec![vec![0usize; m + 1]; n + 1];
        for i in (0..n).rev() {
            for j in (0..m).rev() {
                lcs[i][j] = if ma[i] == mb[j] {
                    lcs[i + 1][j + 1] + 1
                } else if lcs[i + 1][j] >= lcs[i][j + 1] {
                    lcs[i + 1][j]
                } else {
                    lcs[i][j + 1]
                };
            }
        }
        let (mut i, mut j) = (0, 0);
        while i < n && j < m {
            if ma[i] == mb[j] {
                ops.push((' ', ma[i]));
                i += 1;
                j += 1;
            } else if lcs[i + 1][j] >= lcs[i][j + 1] {
                ops.push(('-', ma[i]));
                i += 1;
            } else {
                ops.push(('+', mb[j]));
                j += 1;
            }
        }
        ops.extend(ma[i..].iter().map(|l| ('-', *l)));
        ops.extend(mb[j..].iter().map(|l| ('+', *l)));
    }
    ops.extend(a[a.len() - suf..].iter().map(|l| (' ', *l)));
    ops
}

fn write_unified(w: &mut String, ch: &FileChange, ops: &[Op]) {
    let from = if ch.status == "added" {
        "/dev/null".to_owned()
    } else {
        format!("a/{}", ch.path)
    };
    let to = if ch.status == "deleted" {
        "/dev/null".to_owned()
    } else {
        format!("b/{}", ch.path)
    };
    let _ = write!(w, "--- {from}\n+++ {to}\n");
    const CTX: usize = 3;
    let changed = |k: usize| ops[k].0 != ' ';
    let mut k = 0;
    while k < ops.len() {
        if !changed(k) {
            k += 1;
            continue;
        }
        let start = k.saturating_sub(CTX);
        let mut end = k;
        while end < ops.len() {
            if changed(end) {
                end += 1;
                continue;
            }
            let mut next = end;
            while next < ops.len() && !changed(next) && next - end < 2 * CTX {
                next += 1;
            }
            if next < ops.len() && changed(next) {
                end = next;
                continue;
            }
            end = (end + CTX).min(ops.len());
            break;
        }
        let (mut a_line, mut b_line) = (1i64, 1i64);
        for o in &ops[..start] {
            if o.0 != '+' {
                a_line += 1;
            }
            if o.0 != '-' {
                b_line += 1;
            }
        }
        let (mut a_count, mut b_count) = (0i64, 0i64);
        for o in &ops[start..end] {
            if o.0 != '+' {
                a_count += 1;
            }
            if o.0 != '-' {
                b_count += 1;
            }
        }
        if a_count == 0 {
            a_line -= 1;
        }
        if b_count == 0 {
            b_line -= 1;
        }
        let _ = writeln!(w, "@@ -{a_line},{a_count} +{b_line},{b_count} @@");
        for o in &ops[start..end] {
            w.push(o.0);
            w.push_str(o.1);
            w.push('\n');
        }
        k = end;
    }
}
