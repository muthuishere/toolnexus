//! `Toolkit::as_mcp_server` — the toolkit served for a local agent to call
//! (SPEC §8C, ADR 0041). The §7C stateless MCP endpoint on
//! `127.0.0.1:<ephemeral>/mcp` behind a fresh per-call bearer token.

use std::convert::Infallible;
use std::sync::Arc;

use http_body_util::{BodyExt, Full};
use hyper::body::{Bytes, Incoming};
use hyper::{Method, Request, Response, StatusCode};
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::mcp_serve::{McpServeConfig, mcp_http_service};
use crate::serve::ServeError;
use crate::toolkit::Toolkit;

/// A running [`Toolkit::as_mcp_server`] endpoint. Dropping it does not stop
/// the listener; call [`close`](Self::close).
pub struct McpServerHandle {
    /// `http://127.0.0.1:<port>/mcp`.
    pub url: String,
    /// 32 random bytes, lowercase hex. Never logged.
    pub token: String,
    stop: CancellationToken,
}

impl std::fmt::Debug for McpServerHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("McpServerHandle")
            .field("url", &self.url)
            .field("token", &"[redacted]")
            .finish()
    }
}

impl McpServerHandle {
    /// Stop the listener and its connections. Idempotent.
    pub fn close(&self) {
        self.stop.cancel();
    }
}

impl Toolkit {
    /// Serve this toolkit on `127.0.0.1:<ephemeral>/mcp` for a local agent:
    /// every request needs `Authorization: Bearer <token>` (else 401 +
    /// `WWW-Authenticate: Bearer`), only `POST /mcp` is served (other methods
    /// 405, other paths 404). Tool names are verbatim; `cfg` is the §7C
    /// name/version/tools filter.
    pub async fn as_mcp_server(
        &self,
        cfg: Option<McpServeConfig>,
    ) -> Result<McpServerHandle, ServeError> {
        let listener = TcpListener::bind(("127.0.0.1", 0))
            .await
            .map_err(|e| ServeError(e.to_string()))?;
        let port = listener
            .local_addr()
            .map_err(|e| ServeError(e.to_string()))?
            .port();
        let token = random_token();
        let stop = CancellationToken::new();
        let cfg = cfg.unwrap_or_default();
        let mcp = Arc::new(mcp_http_service(
            self.tools(),
            &cfg,
            None,
            stop.child_token(),
        ));
        let want: Arc<Vec<u8>> = Arc::new(format!("Bearer {token}").into_bytes());
        let stop_loop = stop.clone();
        tokio::spawn(async move {
            loop {
                let conn = tokio::select! {
                    _ = stop_loop.cancelled() => return,
                    c = listener.accept() => c,
                };
                let Ok((stream, _)) = conn else { continue };
                let (mcp, want, stop_conn) = (mcp.clone(), want.clone(), stop_loop.clone());
                tokio::spawn(async move {
                    let svc = hyper::service::service_fn(move |req: Request<Incoming>| {
                        let (mcp, want) = (mcp.clone(), want.clone());
                        async move {
                            if req.uri().path() != "/mcp" {
                                return Ok::<_, Infallible>(plain(
                                    StatusCode::NOT_FOUND,
                                    "not found",
                                ));
                            }
                            let auth = req
                                .headers()
                                .get("authorization")
                                .map(|v| v.as_bytes())
                                .unwrap_or_default();
                            if !same(auth, &want) {
                                let mut r = plain(StatusCode::UNAUTHORIZED, "unauthorized");
                                r.headers_mut()
                                    .insert("WWW-Authenticate", "Bearer".parse().unwrap());
                                return Ok(r);
                            }
                            // Stateless: no standalone SSE stream to hold open.
                            if req.method() != Method::POST {
                                return Ok(plain(
                                    StatusCode::METHOD_NOT_ALLOWED,
                                    "method not allowed",
                                ));
                            }
                            Ok(mcp.handle(req).await)
                        }
                    });
                    let conn = hyper::server::conn::http1::Builder::new()
                        .serve_connection(hyper_util::rt::TokioIo::new(stream), svc);
                    tokio::select! {
                        _ = stop_conn.cancelled() => {}
                        _ = conn => {}
                    }
                });
            }
        });
        Ok(McpServerHandle {
            url: format!("http://127.0.0.1:{port}/mcp"),
            token,
            stop,
        })
    }
}

type Body = http_body_util::combinators::BoxBody<Bytes, Infallible>;

fn plain(status: StatusCode, text: &'static str) -> Response<Body> {
    let mut r = Response::new(Full::new(Bytes::from_static(text.as_bytes())).boxed());
    *r.status_mut() = status;
    r
}

/// Constant time in the length of the expected value.
fn same(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// 32 random bytes from the OS CSPRNG, lowercase hex.
fn random_token() -> String {
    let mut bytes = [0u8; 32];
    #[cfg(unix)]
    let filled = {
        use std::io::Read;
        std::fs::File::open("/dev/urandom")
            .and_then(|mut f| f.read_exact(&mut bytes))
            .is_ok()
    };
    #[cfg(not(unix))]
    let filled = false;
    if !filled {
        // uuid v4 is drawn from the OS CSPRNG too (getrandom)
        for c in bytes.chunks_mut(16) {
            let u = uuid::Uuid::new_v4();
            c.copy_from_slice(&u.as_bytes()[..c.len()]);
        }
    }
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
