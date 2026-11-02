//! `bash` (job-group kill) and the opt-in background shells
//! (`bash_output`, `kill_shell`). Ports of `golang/builtin.go` bashTool and
//! `golang/builtin_extras.go`.

use std::collections::HashMap;
use std::process::Stdio;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime};

use serde_json::{Map, Value, json};
use tokio::io::AsyncReadExt;
use tokio::process::{Child, Command};
use tokio::sync::watch;

use super::{Args, BuiltinEnv, arg_bool, arg_num, arg_str, b_err, b_ok, builtin, meta};
use crate::tool::Tool;

type Buf = Arc<Mutex<Vec<u8>>>;

fn buf_string(b: &Buf) -> String {
    String::from_utf8_lossy(&b.lock().unwrap_or_else(|e| e.into_inner())).into_owned()
}

/// Start `argv... command` in its own process group (POSIX), stdout+stderr
/// captured into one buffer. Returns the child, its pid, the buffer and the
/// reader handles.
fn start_job(
    argv: &[String],
    command: &str,
    workdir: &str,
) -> std::io::Result<(Child, u32, Buf, Vec<tokio::task::JoinHandle<()>>)> {
    let mut cmd = Command::new(&argv[0]);
    cmd.args(&argv[1..])
        .arg(command)
        .current_dir(workdir)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(false);
    #[cfg(unix)]
    cmd.process_group(0);
    let mut child = cmd.spawn()?;
    let pid = child.id().unwrap_or(0);
    let buf: Buf = Arc::new(Mutex::new(Vec::new()));
    let mut readers = Vec::new();
    if let Some(mut out) = child.stdout.take() {
        let b = buf.clone();
        readers.push(tokio::spawn(async move {
            let mut chunk = [0u8; 8192];
            while let Ok(n) = out.read(&mut chunk).await {
                if n == 0 {
                    break;
                }
                b.lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .extend_from_slice(&chunk[..n]);
            }
        }));
    }
    if let Some(mut err) = child.stderr.take() {
        let b = buf.clone();
        readers.push(tokio::spawn(async move {
            let mut chunk = [0u8; 8192];
            while let Ok(n) = err.read(&mut chunk).await {
                if n == 0 {
                    break;
                }
                b.lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .extend_from_slice(&chunk[..n]);
            }
        }));
    }
    Ok((child, pid, buf, readers))
}

/// Signal the whole job: TERM when `graceful`, else KILL. `true` = delivered.
#[cfg(unix)]
fn signal_job(pid: u32, graceful: bool) -> bool {
    if pid == 0 {
        return false;
    }
    let sig = if graceful {
        libc::SIGTERM
    } else {
        libc::SIGKILL
    };
    // SAFETY: plain syscall on a process-group id we created.
    unsafe { libc::kill(-(pid as i32), sig) == 0 }
}

/// Windows has no graceful stop for a console process: stop the tree outright.
#[cfg(not(unix))]
fn signal_job(pid: u32, _graceful: bool) -> bool {
    if pid == 0 {
        return false;
    }
    std::process::Command::new("taskkill")
        .args(["/T", "/F", "/PID", &pid.to_string()])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn exit_code(status: std::process::ExitStatus) -> i64 {
    status.code().map(i64::from).unwrap_or(-1)
}

async fn join_readers(readers: Vec<tokio::task::JoinHandle<()>>) {
    for r in readers {
        let _ = r.await;
    }
}

pub(crate) fn bash_tool(env: Arc<BuiltinEnv>) -> Tool {
    let mut props = json!({
        "command": {"type": "string", "description": "The shell command to run"},
        "workdir": {"type": "string", "description": "Working directory (default: process cwd)"},
        "timeout": {"type": "number", "description": "Timeout in milliseconds (default 60000)"},
        "description": {"type": "string", "description": "Human-readable description of the command"}
    });
    if env.bg.is_some() {
        props["run_in_background"] = json!({"type": "boolean", "description": "Start the command and return at once with a background shell id; read it with bash_output, stop it with kill_shell. No timeout applies."});
    }
    builtin(
        "bash",
        "Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
        json!({
            "type": "object",
            "properties": props,
            "required": ["command"],
            "additionalProperties": false
        }),
        move |args, ctx| {
            let env = env.clone();
            async move { bash_impl(&env, &args, ctx).await }
        },
    )
}

async fn bash_impl(
    env: &BuiltinEnv,
    args: &Args,
    ctx: crate::tool::ToolContext,
) -> crate::tool::ToolResult {
    let command = arg_str(args, "command").unwrap_or("");
    if command.is_empty() {
        return b_err("bash: command is required", None);
    }
    let argv = match env.shell_argv() {
        Ok(a) => a,
        Err(e) => return b_err(format!("bash: {e}"), None),
    };
    let workdir = match arg_str(args, "workdir") {
        Some(w) if !w.is_empty() => match env.resolve_path(w) {
            Ok(r) => r,
            Err(e) => return b_err(format!("bash: {e}"), None),
        },
        _ => env.dir(),
    };
    if let Some(bg) = &env.bg
        && arg_bool(args, "run_in_background")
    {
        return match bg.start(&argv, command, &workdir, env.kill_grace()) {
            Ok(id) => b_ok(
                format!(
                    "Started background shell {id}. Read its output with bash_output, stop it with kill_shell."
                ),
                meta(vec![
                    ("shell", json!(env.shell_label())),
                    ("backgroundId", json!(id)),
                ]),
            ),
            Err(e) => b_err(
                format!("bash: {e}"),
                meta(vec![("shell", json!(env.shell_label()))]),
            ),
        };
    }
    let timeout_ms = arg_num(args, "timeout").unwrap_or(60000.0);
    let mut m: Map<String, Value> = Map::new();
    m.insert("shell".into(), json!(env.shell_label()));
    let (mut child, pid, buf, readers) = match start_job(&argv, command, &workdir) {
        Ok(j) => j,
        Err(e) => return b_err(format!("bash: {e}"), Some(m)),
    };
    let sleep = tokio::time::sleep(Duration::from_millis(timeout_ms.max(0.0) as u64));
    let stopped: Option<&str>;
    let mut status = None;
    tokio::select! {
        s = child.wait() => { status = s.ok(); stopped = None; }
        _ = sleep => { stopped = Some("timeout"); }
        _ = ctx.cancel.cancelled() => { stopped = Some("cancelled"); }
    }
    if let Some(why) = stopped {
        let mut killed_tree = signal_job(pid, true);
        match tokio::time::timeout(env.kill_grace(), child.wait()).await {
            Ok(_) => {}
            Err(_) => {
                if signal_job(pid, false) {
                    killed_tree = true;
                }
                let _ = child.wait().await;
            }
        }
        // The group is gone; the readers see EOF promptly.
        let _ = tokio::time::timeout(Duration::from_millis(500), join_readers(readers)).await;
        m.insert("timedOut".into(), json!(why == "timeout"));
        m.insert("killedTree".into(), json!(killed_tree));
        let output = buf_string(&buf);
        if why == "cancelled" {
            return b_err(format!("bash: command cancelled\n{output}"), Some(m));
        }
        return b_err(
            format!(
                "bash: command timed out after {}ms\n{output}",
                timeout_ms as i64
            ),
            Some(m),
        );
    }
    join_readers(readers).await;
    let output = buf_string(&buf);
    match status {
        Some(s) if s.success() => {
            m.insert("exitCode".into(), json!(0));
            b_ok(output, Some(m))
        }
        Some(s) => {
            let code = exit_code(s);
            m.insert("exitCode".into(), json!(code));
            b_err(
                format!("{output}\nbash: command exited with code {code}"),
                Some(m),
            )
        }
        None => b_err("bash: wait failed", Some(m)),
    }
}

// ---------------------------------------------------------------------------
// background shells (opt-in)
// ---------------------------------------------------------------------------

struct JobState {
    exit_code: i64,
    killed: bool,
    read: usize,
}

struct BgJob {
    id: String,
    command: String,
    started: SystemTime,
    pid: u32,
    buf: Buf,
    grace: Duration,
    state: Mutex<JobState>,
    done: watch::Receiver<bool>,
}

impl BgJob {
    fn running(&self) -> bool {
        !*self.done.borrow()
    }
    fn info(&self) -> BackgroundShellInfo {
        let st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        BackgroundShellInfo {
            id: self.id.clone(),
            command: self.command.clone(),
            started: self.started,
            running: self.running(),
            exit_code: st.exit_code,
            killed: st.killed,
        }
    }
    async fn wait_done(&self) {
        let mut rx = self.done.clone();
        let _ = rx.wait_for(|d| *d).await;
    }
    async fn kill(&self) -> bool {
        if !self.running() {
            return false;
        }
        self.state.lock().unwrap_or_else(|e| e.into_inner()).killed = true;
        signal_job(self.pid, true);
        if tokio::time::timeout(self.grace, self.wait_done())
            .await
            .is_err()
        {
            signal_job(self.pid, false);
            self.wait_done().await;
        }
        true
    }
}

/// A snapshot of one background shell.
#[derive(Debug, Clone, PartialEq)]
pub struct BackgroundShellInfo {
    pub id: String,
    pub command: String,
    pub started: SystemTime,
    pub running: bool,
    /// Valid when `!running`; -1 if killed by a signal.
    pub exit_code: i64,
    pub killed: bool,
}

/// Host-owned registry of background shells (opt-in, 0.22.0). Call
/// [`BackgroundShells::close_all`] on shutdown.
#[derive(Default)]
pub struct BackgroundShells {
    inner: Mutex<(usize, HashMap<String, Arc<BgJob>>)>,
}

impl std::fmt::Debug for BackgroundShells {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BackgroundShells").finish_non_exhaustive()
    }
}

impl BackgroundShells {
    pub fn new() -> Self {
        Self::default()
    }

    fn start(
        &self,
        argv: &[String],
        command: &str,
        workdir: &str,
        grace: Duration,
    ) -> std::io::Result<String> {
        let (mut child, pid, buf, readers) = start_job(argv, command, workdir)?;
        let (tx, rx) = watch::channel(false);
        let id = {
            let mut g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            g.0 += 1;
            format!("bg{}", g.0)
        };
        let job = Arc::new(BgJob {
            id: id.clone(),
            command: command.to_owned(),
            started: SystemTime::now(),
            pid,
            buf,
            grace,
            state: Mutex::new(JobState {
                exit_code: 0,
                killed: false,
                read: 0,
            }),
            done: rx,
        });
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .1
            .insert(id.clone(), job.clone());
        tokio::spawn(async move {
            let status = child.wait().await;
            join_readers(readers).await;
            let code = match status {
                Ok(s) => exit_code(s),
                Err(_) => -1,
            };
            job.state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .exit_code = code;
            let _ = tx.send(true);
        });
        Ok(id)
    }

    fn get(&self, id: &str) -> Option<Arc<BgJob>> {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .1
            .get(id)
            .cloned()
    }

    fn jobs(&self) -> Vec<Arc<BgJob>> {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .1
            .values()
            .cloned()
            .collect()
    }

    /// Every shell, oldest first.
    pub fn list(&self) -> Vec<BackgroundShellInfo> {
        let mut jobs = self.jobs();
        jobs.sort_by_key(|j| j.started);
        jobs.iter().map(|j| j.info()).collect()
    }

    /// Stop one shell and everything it started.
    pub async fn kill(&self, id: &str) -> Result<(), String> {
        match self.get(id) {
            Some(j) => {
                j.kill().await;
                Ok(())
            }
            None => Err(format!("no background shell {id:?}")),
        }
    }

    /// Stop every running shell (concurrently). Call on shutdown.
    pub async fn close_all(&self) {
        let jobs = self.jobs();
        futures_util::future::join_all(jobs.iter().map(|j| j.kill())).await;
    }
}

fn bg_status(i: &BackgroundShellInfo) -> String {
    if i.running {
        "running".into()
    } else if i.killed {
        "killed".into()
    } else {
        format!("exited with code {}", i.exit_code)
    }
}

fn id_schema() -> Value {
    json!({
        "type": "object",
        "properties": {
            "id": {"type": "string", "description": "The background shell id bash returned"}
        },
        "required": ["id"],
        "additionalProperties": false
    })
}

pub(crate) fn bash_output_tool(env: Arc<BuiltinEnv>) -> Tool {
    builtin(
        "bash_output",
        "Read the output a background bash command (started with run_in_background) produced since the last read, and whether it is still running.",
        id_schema(),
        move |args, _ctx| {
            let env = env.clone();
            async move {
                let id = arg_str(&args, "id").unwrap_or("").to_owned();
                let Some(j) = env.bg.as_ref().and_then(|b| b.get(&id)) else {
                    return b_err(format!("bash_output: no background shell {id:?}"), None);
                };
                let all = j.buf.lock().unwrap_or_else(|e| e.into_inner()).clone();
                let fresh = {
                    let mut st = j.state.lock().unwrap_or_else(|e| e.into_inner());
                    let from = st.read.min(all.len());
                    st.read = all.len();
                    String::from_utf8_lossy(&all[from..]).into_owned()
                };
                let info = j.info();
                let status = bg_status(&info);
                let first = status.split(' ').next().unwrap_or("").to_owned();
                let mut m =
                    meta(vec![("id", json!(id)), ("status", json!(first))]).unwrap_or_default();
                if !info.running && !info.killed {
                    m.insert("exitCode".into(), json!(info.exit_code));
                }
                b_ok(format!("{fresh}\n[status: {status}]"), Some(m))
            }
        },
    )
}

pub(crate) fn kill_shell_tool(env: Arc<BuiltinEnv>) -> Tool {
    builtin(
        "kill_shell",
        "Stop a background bash command and everything it started.",
        id_schema(),
        move |args, _ctx| {
            let env = env.clone();
            async move {
                let id = arg_str(&args, "id").unwrap_or("").to_owned();
                let Some(j) = env.bg.as_ref().and_then(|b| b.get(&id)) else {
                    return b_err(format!("kill_shell: no background shell {id:?}"), None);
                };
                if !j.kill().await {
                    return b_ok(
                        format!("Background shell {id} had already {}", bg_status(&j.info())),
                        meta(vec![("id", json!(id)), ("killed", json!(false))]),
                    );
                }
                b_ok(
                    format!("Killed background shell {id}"),
                    meta(vec![("id", json!(id)), ("killed", json!(true))]),
                )
            }
        },
    )
}
