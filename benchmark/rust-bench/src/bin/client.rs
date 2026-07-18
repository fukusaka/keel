// Rust reqwest client benchmark — a native reference for the keel HTTP client
// bench. reqwest (hyper-based) reuses keep-alive connections and speaks HTTP/2.
// Accepts the same CLI flags as the JVM harness and prints the same result line
// (name=rust-reqwest); the shared driving logic lives in clientbench.

#[path = "../clientbench.rs"]
mod clientbench;

use clientbench::{log_start, parse, report, run_phase, HttpGet};
use std::future::Future;
use std::time::Duration;

const NAME: &str = "rust-reqwest";

#[derive(Clone)]
struct Reqwest(reqwest::Client);

impl HttpGet for Reqwest {
    fn get(&self, url: String) -> impl Future<Output = bool> + Send {
        let client = self.0.clone();
        async move {
            match client.get(&url).send().await {
                Ok(resp) => resp.bytes().await.is_ok(),
                Err(_) => false,
            }
        }
    }
}

fn main() {
    let cfg = parse();
    let rt = tokio::runtime::Runtime::new().unwrap();
    rt.block_on(async {
        let client = Reqwest(
            reqwest::Client::builder()
                .pool_max_idle_per_host(cfg.connections)
                .pool_idle_timeout(Duration::from_secs(30))
                .build()
                .expect("reqwest client"),
        );
        log_start(NAME, &cfg);
        if cfg.warmup > 0 {
            run_phase(client.clone(), &cfg, cfg.warmup).await;
        }
        let p = run_phase(client, &cfg, cfg.duration).await;
        report(NAME, &cfg, &p);
    });
}
