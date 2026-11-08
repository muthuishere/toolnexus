//! Shared helpers for the MCP handler tests: a real local hyper server.
#![allow(dead_code)]

use hyper::body::Incoming;
use tokio::net::TcpListener;
use toolnexus::mcp_handler::{McpAuthMetadata, McpHandler};
use toolnexus::{BuiltinsConfig, Toolkit, ToolkitOptions, create_toolkit};

pub async fn tk() -> Toolkit {
    create_toolkit(ToolkitOptions {
        builtins: Some(BuiltinsConfig::off()),
        ..Default::default()
    })
    .await
    .unwrap()
}

/// Serve `h` on an ephemeral port; returns its URL (`http://127.0.0.1:N/mcp`).
pub async fn serve(h: McpHandler) -> String {
    let ln = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = ln.local_addr().unwrap();
    tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = ln.accept().await else {
                return;
            };
            let h = h.clone();
            tokio::spawn(async move {
                let svc = hyper::service::service_fn(move |req: hyper::Request<Incoming>| {
                    let h = h.clone();
                    async move { Ok::<_, std::convert::Infallible>(h.handle(req).await) }
                });
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), svc)
                    .await;
            });
        }
    });
    format!("http://{addr}/mcp")
}

/// Serve an RFC 9728 metadata handler on an ephemeral port; returns its origin.
pub async fn serve_metadata(m: McpAuthMetadata) -> String {
    let ln = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = ln.local_addr().unwrap();
    tokio::spawn(async move {
        loop {
            let Ok((stream, _)) = ln.accept().await else {
                return;
            };
            let m = m.clone();
            tokio::spawn(async move {
                let _ = hyper::server::conn::http1::Builder::new()
                    .serve_connection(hyper_util::rt::TokioIo::new(stream), m)
                    .await;
            });
        }
    });
    format!("http://{addr}")
}
