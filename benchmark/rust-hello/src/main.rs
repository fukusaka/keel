// Minimal Rust Axum HTTP server for benchmarking.
// Endpoints: GET /hello (13 bytes), GET /large (100KB)
//
// CLI: --port=8080 --profile=default|tuned|keel-equiv-0.1
//      --show-config --connection-close=true
//      --tcp-nodelay=true --reuse-address=true --backlog=1024
//      --send-buffer=N --receive-buffer=N --threads=N
//      --tokio-blocking-threads=N

use axum::{middleware, routing::get, Router};
use std::sync::LazyLock;
use std::net::SocketAddr;

mod config;
use config::Config;

static LARGE_PAYLOAD: LazyLock<String> = LazyLock::new(|| "x".repeat(102_400));

async fn hello() -> &'static str {
    "Hello, World!"
}

async fn large() -> &'static str {
    &LARGE_PAYLOAD
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

    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(threads)
        .enable_all()
        .build()
        .expect("failed to build tokio runtime");

    rt.block_on(async move {
        let app = Router::new()
            .route("/hello", get(hello))
            .route("/large", get(large));

        let app = if config.connection_close {
            app.layer(middleware::from_fn(connection_close_middleware))
        } else {
            app
        };

        let addr = SocketAddr::from(([0, 0, 0, 0], config.port));
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
    });
}
