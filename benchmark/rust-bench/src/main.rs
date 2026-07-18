// Minimal Rust Axum HTTP server for benchmarking.
// Endpoints:
//   GET  /hello         (13 bytes)
//   GET  /large         (100KB)
//   POST /upload-stream (drains request body, replies with byte count)
//   GET  /sse-stream?count=N&size=M (chunked SSE frames)
//   WS   /ws-echo       (echo every frame back)
//
// CLI: --port=8080 --profile=default|tuned|keel-equiv-0.1
//      --show-config --connection-close=true
//      --tcp-nodelay=true --reuse-address=true --backlog=1024
//      --send-buffer=N --receive-buffer=N --threads=N
//      --tokio-blocking-threads=N
//      --tls --tls-cert=PATH --tls-key=PATH

use axum::{
    body::Body,
    extract::{ws::{Message, WebSocket, WebSocketUpgrade}, Query},
    http::{HeaderMap, HeaderValue},
    middleware,
    response::{IntoResponse, Response, Sse, sse::Event},
    routing::{get, post},
    Router,
};
use axum_server::tls_rustls::RustlsConfig;
use futures_util::{SinkExt, StreamExt};
use std::collections::HashMap;
use std::convert::Infallible;
use std::sync::LazyLock;
use std::net::SocketAddr;

mod config;
use config::Config;

static LARGE_PAYLOAD: LazyLock<String> = LazyLock::new(|| "x".repeat(102_400));
// Pre-allocated 1 MiB buffer sliced per request (zero per-request server alloc)
// for the client payload-size matrix via GET /bytes?n=N.
static BYTES_PAYLOAD: LazyLock<Vec<u8>> = LazyLock::new(|| vec![b'x'; 1_048_576]);

const SSE_DEFAULT_COUNT: usize = 100;
const SSE_DEFAULT_SIZE: usize = 1024;

async fn hello() -> &'static str {
    "Hello, World!"
}

async fn large() -> &'static str {
    &LARGE_PAYLOAD
}

// GET /bytes?n=N — N bytes (default 1024, capped at 1 MiB) from the shared
// buffer, for the client payload-size matrix (128 B .. 1 MiB).
async fn bytes(Query(params): Query<HashMap<String, String>>) -> impl IntoResponse {
    let n = params
        .get("n")
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(1024)
        .min(BYTES_PAYLOAD.len());
    &BYTES_PAYLOAD[..n]
}

// GET /close — small body with `Connection: close` so the server closes the
// connection after each response, forcing every client to open a fresh
// connection per request (the keep-alive-vs-fresh axis; no client change).
async fn close_conn() -> impl IntoResponse {
    ([("connection", "close")], "Hello, World!")
}

// POST /upload-stream — drain the request body chunk-by-chunk via the
// Body data stream (no aggregation), reply with the byte count in the
// X-Bytes-Received header. Mirrors the keel / Spring / Vertx route.
async fn upload_stream(body: Body) -> impl IntoResponse {
    let mut received: u64 = 0u64;
    let mut stream = body.into_data_stream();
    while let Some(chunk) = stream.next().await {
        match chunk {
            Ok(bytes) => received += bytes.len() as u64,
            Err(_) => break,
        }
    }
    let mut headers = HeaderMap::new();
    headers.insert(
        "X-Bytes-Received",
        HeaderValue::from_str(&received.to_string()).unwrap(),
    );
    (headers, "ok")
}

// GET /sse-stream?count=N&size=M — emit N SSE frames of M bytes each via
// chunked Transfer-Encoding. count / size default to 100 / 1024.
async fn sse_stream(Query(params): Query<HashMap<String, String>>) -> impl IntoResponse {
    let count = params
        .get("count")
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(SSE_DEFAULT_COUNT);
    let size = params
        .get("size")
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(SSE_DEFAULT_SIZE);
    let payload = "x".repeat(size);
    // Per-frame yield: `stream::iter` produces every item synchronously
    // in one tokio poll, so hyper's HTTP/1 body writer coalesces them
    // into a single chunked-transfer batch (~3-4× the per-frame baseline
    // that `pipeline-http-*` / `ktor-keel-*` / `netty-raw` / `zig-bench`
    // enforce). `unfold` + `tokio::task::yield_now` reschedules the body
    // task between events so hyper can flush each encoded frame.
    let stream = futures_util::stream::unfold(0usize, move |i| {
        let payload = payload.clone();
        async move {
            if i >= count {
                None
            } else {
                if i > 0 {
                    tokio::task::yield_now().await;
                }
                Some((Ok::<_, Infallible>(Event::default().data(payload)), i + 1))
            }
        }
    });
    Sse::new(stream)
}

// WebSocket /ws-echo — echo every message back. Matches the k6 ws-echo.js
// scenario and the corresponding Spring / Vertx / Netty handlers.
async fn ws_echo(ws: WebSocketUpgrade) -> Response {
    ws.on_upgrade(handle_ws_echo)
}

async fn handle_ws_echo(socket: WebSocket) {
    let (mut sender, mut receiver) = socket.split();
    while let Some(Ok(msg)) = receiver.next().await {
        let echo = match msg {
            Message::Text(t) => Message::Text(t),
            Message::Binary(b) => Message::Binary(b),
            // Pong is implicit in axum's ping handling; close ends the loop.
            Message::Close(_) => break,
            other => other,
        };
        if sender.send(echo).await.is_err() {
            break;
        }
    }
}

async fn connection_close_middleware(
    req: axum::extract::Request,
    next: middleware::Next,
) -> axum::response::Response {
    let mut res = next.run(req).await;
    res.headers_mut()
        .insert("connection", "close".parse().unwrap());
    res
}

fn main() {
    let config = Config::parse();

    if config.show_config {
        print!("{}", config.display());
        return;
    }

    let threads = config.socket.threads.unwrap_or_else(|| {
        std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(1)
    });

    let mut builder = tokio::runtime::Builder::new_multi_thread();
    builder.worker_threads(threads).enable_all();
    if let Some(bt) = config.tokio_blocking_threads {
        builder.max_blocking_threads(bt);
    }
    let rt = builder.build().expect("failed to build tokio runtime");

    rt.block_on(async move {
        let app = Router::new()
            .route("/hello", get(hello))
            .route("/large", get(large))
            .route("/bytes", get(bytes))
            .route("/close", get(close_conn))
            .route("/upload-stream", post(upload_stream))
            .route("/sse-stream", get(sse_stream))
            .route("/ws-echo", get(ws_echo));

        let app = if config.connection_close {
            app.layer(middleware::from_fn(connection_close_middleware))
        } else {
            app
        };

        let addr = SocketAddr::from(([0, 0, 0, 0], config.port));

        if config.tls.enabled {
            let tls_config = RustlsConfig::from_pem_file(&config.tls.cert, &config.tls.key)
                .await
                .expect("failed to load TLS cert/key");
            println!(
                "Rust Axum server started on port {} (TLS)",
                config.port,
            );
            axum_server::bind_rustls(addr, tls_config)
                .serve(app.into_make_service())
                .await
                .unwrap();
        } else {
            let socket = config.create_socket().expect("failed to create socket");
            socket.bind(&addr.into()).expect("failed to bind");
            socket
                .listen(config.socket.backlog.unwrap_or(128) as i32)
                .expect("failed to listen");
            socket.set_nonblocking(true).unwrap();
            let std_listener: std::net::TcpListener = socket.into();
            let listener = tokio::net::TcpListener::from_std(std_listener).unwrap();

            println!("Rust Axum server started on port {}", config.port);
            axum::serve(listener, app).await.unwrap();
        }
    });
}
