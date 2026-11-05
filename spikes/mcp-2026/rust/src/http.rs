//! A tiny HTTP layer: a hyper 1 server with plain request/response structs (used by the MCP
//! server, the dev AS, the loopback callback listener and the examples), plus the shared
//! reqwest client that trusts the in-process dev TLS certificate.

use std::net::SocketAddr;
use std::sync::{Arc, OnceLock, RwLock};
use std::time::Duration;

use bytes::Bytes;
use futures_util::future::BoxFuture;
use http_body_util::{BodyExt, Full, Limited};
use hyper::service::service_fn;
use hyper_util::rt::TokioIo;
use tokio::net::TcpListener;
use tokio::task::JoinHandle;

/// Bounds every request made through the default client (a hung server cannot hang the caller).
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);
/// Request bodies larger than this get 413.
pub const MAX_BODY_BYTES: usize = 4 << 20;

/// An incoming HTTP request, fully read.
#[derive(Debug, Clone, Default)]
pub struct Request {
    pub method: String,
    pub path: String,
    pub query: String,
    pub headers: Vec<(String, String)>,
    pub body: Bytes,
    pub host: String,
    pub tls: bool,
    /// The body exceeded MAX_BODY_BYTES or could not be read.
    pub body_error: bool,
}

impl Request {
    /// Header value (case-insensitive name), "" if absent.
    pub fn header(&self, name: &str) -> String {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.clone())
            .unwrap_or_default()
    }
    pub fn query_param(&self, name: &str) -> Option<String> {
        url::form_urlencoded::parse(self.query.as_bytes())
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.into_owned())
    }
    pub fn has_query(&self, name: &str) -> bool {
        self.query_param(name).is_some()
    }
    /// path + "?" + query.
    pub fn uri(&self) -> String {
        if self.query.is_empty() {
            self.path.clone()
        } else {
            format!("{}?{}", self.path, self.query)
        }
    }
    /// scheme://host
    pub fn origin(&self) -> String {
        format!(
            "{}://{}",
            if self.tls { "https" } else { "http" },
            self.host
        )
    }
}

/// An HTTP response to write.
#[derive(Debug, Clone, Default)]
pub struct Response {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
    /// Close the connection without answering (test hook: a lost response).
    pub abort: bool,
}

impl Response {
    pub fn new(status: u16, content_type: &str, body: impl Into<Vec<u8>>) -> Self {
        Response {
            status,
            headers: vec![("Content-Type".into(), content_type.into())],
            body: body.into(),
            abort: false,
        }
    }
    pub fn json(status: u16, v: &serde_json::Value) -> Self {
        Self::new(
            status,
            "application/json",
            serde_json::to_vec(v).unwrap_or_default(),
        )
    }
    pub fn text(status: u16, s: &str) -> Self {
        Self::new(status, "text/plain; charset=utf-8", s.as_bytes().to_vec())
    }
    pub fn redirect(location: &str) -> Self {
        Response {
            status: 302,
            headers: vec![("Location".into(), location.into())],
            ..Default::default()
        }
    }
    pub fn status(status: u16) -> Self {
        Response {
            status,
            ..Default::default()
        }
    }
    pub fn abort() -> Self {
        Response {
            abort: true,
            ..Default::default()
        }
    }
    pub fn with_header(mut self, k: &str, v: &str) -> Self {
        self.headers.push((k.into(), v.into()));
        self
    }
    pub fn header(&self, name: &str) -> String {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.clone())
            .unwrap_or_default()
    }
}

pub type Handler = Arc<dyn Fn(Request) -> BoxFuture<'static, Response> + Send + Sync>;

/// Wraps an async closure as a [`Handler`].
pub fn handler<F, Fut>(f: F) -> Handler
where
    F: Fn(Request) -> Fut + Send + Sync + 'static,
    Fut: std::future::Future<Output = Response> + Send + 'static,
{
    Arc::new(move |r| Box::pin(f(r)))
}

/// Anything that can serve HTTP: a Handler, a Server, a DevAuthServer.
pub trait IntoHandler {
    fn into_handler(self) -> Handler;
}
impl IntoHandler for Handler {
    fn into_handler(self) -> Handler {
        self
    }
}

/// A running HTTP server; aborted on `close` or drop.
pub struct HttpServer {
    pub addr: SocketAddr,
    /// http(s)://127.0.0.1:<port>
    pub url: String,
    task: JoinHandle<()>,
}

impl HttpServer {
    /// Serves on 127.0.0.1:<random> (plain HTTP).
    pub async fn start(h: impl IntoHandler) -> HttpServer {
        Self::bind("127.0.0.1:0", h)
            .await
            .expect("bind 127.0.0.1:0")
    }
    /// Serves on 127.0.0.1:<random> over TLS with the process-wide dev certificate (trusted by
    /// [`default_client`]). DEV/TEST ONLY.
    pub async fn start_tls(h: impl IntoHandler) -> HttpServer {
        let ln = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        Self::serve_on(ln, h.into_handler(), Some(dev_tls().acceptor.clone()))
    }
    /// Serves on addr (plain HTTP).
    pub async fn bind(addr: &str, h: impl IntoHandler) -> std::io::Result<HttpServer> {
        let ln = TcpListener::bind(addr).await?;
        Ok(Self::serve_on(ln, h.into_handler(), None))
    }
    /// Serves on an already-bound listener.
    pub fn serve_on(
        ln: TcpListener,
        h: Handler,
        tls: Option<tokio_rustls::TlsAcceptor>,
    ) -> HttpServer {
        let addr = ln.local_addr().expect("local addr");
        let url = format!(
            "{}://{}",
            if tls.is_some() { "https" } else { "http" },
            addr
        );
        let task = tokio::spawn(async move {
            loop {
                let Ok((stream, _)) = ln.accept().await else {
                    continue;
                };
                let h = h.clone();
                let tls = tls.clone();
                tokio::spawn(async move {
                    match tls {
                        Some(acc) => {
                            if let Ok(s) = acc.accept(stream).await {
                                serve_conn(TokioIo::new(s), h, true).await
                            }
                        }
                        None => serve_conn(TokioIo::new(stream), h, false).await,
                    }
                });
            }
        });
        HttpServer { addr, url, task }
    }
    pub fn close(&self) {
        self.task.abort();
    }
    /// Runs until the process ends.
    pub async fn wait(self) {
        let _ = (&mut { self }.task).await;
    }
}

impl Drop for HttpServer {
    fn drop(&mut self) {
        self.task.abort();
    }
}

async fn serve_conn<I>(io: I, h: Handler, tls: bool)
where
    I: hyper::rt::Read + hyper::rt::Write + Unpin + Send + 'static,
{
    let svc = service_fn(move |req: hyper::Request<hyper::body::Incoming>| {
        let h = h.clone();
        async move {
            let (parts, body) = req.into_parts();
            let (bytes, body_error) = match Limited::new(body, MAX_BODY_BYTES).collect().await {
                Ok(c) => (c.to_bytes(), false),
                Err(_) => (Bytes::new(), true),
            };
            let headers = parts
                .headers
                .iter()
                .map(|(k, v)| {
                    (
                        k.as_str().to_string(),
                        String::from_utf8_lossy(v.as_bytes()).into_owned(),
                    )
                })
                .collect::<Vec<_>>();
            let host = parts
                .headers
                .get("host")
                .and_then(|v| v.to_str().ok())
                .unwrap_or_default()
                .to_string();
            let r = Request {
                method: parts.method.as_str().to_string(),
                path: parts.uri.path().to_string(),
                query: parts.uri.query().unwrap_or_default().to_string(),
                headers,
                body: bytes,
                host,
                tls,
                body_error,
            };
            let resp = h(r).await;
            if resp.abort {
                return Err("connection aborted by handler");
            }
            let mut b = hyper::Response::builder().status(resp.status);
            for (k, v) in &resp.headers {
                b = b.header(k, v);
            }
            Ok::<_, &'static str>(b.body(Full::new(Bytes::from(resp.body))).unwrap())
        }
    });
    let _ = hyper::server::conn::http1::Builder::new()
        .timer(hyper_util::rt::TokioTimer::new())
        .header_read_timeout(Duration::from_secs(10))
        .serve_connection(io, svc)
        .await;
}

// ---- dev TLS: one self-signed certificate per process, trusted by the default client ----

struct DevTls {
    cert_der: Vec<u8>,
    acceptor: tokio_rustls::TlsAcceptor,
}

fn dev_tls() -> &'static DevTls {
    static TLS: OnceLock<DevTls> = OnceLock::new();
    TLS.get_or_init(|| {
        let ck = rcgen::generate_simple_self_signed(vec!["127.0.0.1".into(), "localhost".into()])
            .expect("dev cert");
        let cert_der = ck.cert.der().to_vec();
        let key = rustls::pki_types::PrivateKeyDer::Pkcs8(ck.signing_key.serialize_der().into());
        let cfg = rustls::ServerConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(vec![cert_der.clone().into()], key)
        .expect("dev tls config");
        let d = DevTls {
            cert_der,
            acceptor: tokio_rustls::TlsAcceptor::from(Arc::new(cfg)),
        };
        *shared().write().unwrap() = build_client(Some(&d.cert_der), true);
        d
    })
}

/// Makes the default client trust the dev TLS certificate (called by DevAuthServer::start).
pub(crate) fn trust_dev_cert() {
    let _ = dev_tls();
}

fn build_client(dev_cert: Option<&[u8]>, follow: bool) -> reqwest::Client {
    let mut b = reqwest::Client::builder().timeout(DEFAULT_TIMEOUT);
    if let Some(der) = dev_cert {
        b = b.add_root_certificate(reqwest::Certificate::from_der(der).expect("dev cert der"));
    }
    if !follow {
        b = b.redirect(reqwest::redirect::Policy::none());
    }
    b.build().expect("reqwest client")
}

fn shared() -> &'static RwLock<reqwest::Client> {
    static C: OnceLock<RwLock<reqwest::Client>> = OnceLock::new();
    C.get_or_init(|| RwLock::new(build_client(None, true)))
}

/// The shared client: 30 s timeout, follows redirects, trusts an in-process dev TLS cert once
/// one exists (so `ServerAuth { issuer: dev.issuer }` and zero-config OAuth need no wiring in dev).
pub fn default_client() -> reqwest::Client {
    shared().read().unwrap().clone()
}

/// Like [`default_client`] (trusting the dev cert) but never follows redirects (to capture a `Location`).
pub fn no_redirect_client() -> reqwest::Client {
    build_client(Some(dev_tls().cert_der.as_slice()), false)
}

pub(crate) fn dev_acceptor() -> tokio_rustls::TlsAcceptor {
    dev_tls().acceptor.clone()
}
