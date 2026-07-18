// Rust hyper client benchmark — the low-level native ceiling below reqwest.
// Uses hyper-util's pooling client directly (the layer reqwest is built on),
// without reqwest's conveniences (redirects, decompression). Accepts the same
// CLI flags as the JVM harness and prints the same result line (name=rust-hyper);
// the shared driving logic lives in clientbench.

#[path = "../clientbench.rs"]
mod clientbench;

use clientbench::{log_start, parse, report, run_phase, HttpGet};
use http_body_util::{BodyExt, Empty};
use hyper::body::Bytes;
use hyper_util::client::legacy::connect::HttpConnector;
use hyper_util::client::legacy::Client;
use hyper_util::rt::TokioExecutor;
use std::future::Future;
use std::time::Duration;

const NAME: &str = "rust-hyper";

#[derive(Clone)]
struct Hyper(Client<HttpConnector, Empty<Bytes>>);

impl HttpGet for Hyper {
    fn get(&self, url: String) -> impl Future<Output = bool> + Send {
        let client = self.0.clone();
        async move {
            let uri: hyper::Uri = match url.parse() {
                Ok(u) => u,
                Err(_) => return false,
            };
            let req = match hyper::Request::builder().uri(uri).body(Empty::<Bytes>::new()) {
                Ok(r) => r,
                Err(_) => return false,
            };
            match client.request(req).await {
                Ok(resp) => resp.into_body().collect().await.is_ok(),
                Err(_) => false,
            }
        }
    }
}

fn main() {
    let cfg = parse();
    let rt = tokio::runtime::Runtime::new().unwrap();
    rt.block_on(async {
        let client = Hyper(
            Client::builder(TokioExecutor::new())
                .pool_max_idle_per_host(cfg.connections)
                .pool_idle_timeout(Duration::from_secs(30))
                .build_http::<Empty<Bytes>>(),
        );
        log_start(NAME, &cfg);
        if cfg.warmup > 0 {
            run_phase(client.clone(), &cfg, cfg.warmup).await;
        }
        let p = run_phase(client, &cfg, cfg.duration).await;
        report(NAME, &cfg, &p);
    });
}
