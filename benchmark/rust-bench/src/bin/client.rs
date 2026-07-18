// Rust reqwest client benchmark — a native reference for the keel HTTP client
// bench. reqwest (hyper-based) reuses keep-alive connections and speaks HTTP/2,
// so it is the native-ceiling counterpart to the JVM reference clients. Accepts
// the same CLI flags as the JVM harness and prints the same result line, so
// bench-client.sh can drive it like any other client type.
//
// Line format: <name><endpoint>|<rps>|<p50ms>|<p99ms>|<p99.9ms>|<maxms>|<b/op>|<errors>
// bytes/op is n/a for native clients (no GC; allocation is not the JVM metric).

use hdrhistogram::Histogram;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

const NAME: &str = "rust-reqwest";

struct Config {
    targets: Vec<String>, // full URLs (base + endpoint) already joined
    endpoint: String,
    connections: usize,
    warmup: u64,
    duration: u64,
    pinned: bool,
}

fn parse() -> Config {
    let mut target = String::new();
    let mut endpoint = "/hello".to_string();
    let mut connections = 50usize;
    let mut warmup = 3u64;
    let mut duration = 10u64;
    let mut pinned = false;
    for arg in std::env::args().skip(1) {
        if let Some((k, v)) = arg.strip_prefix("--").and_then(|s| s.split_once('=')) {
            match k {
                "client-target" => target = v.to_string(),
                "client-endpoint" => endpoint = v.to_string(),
                "client-connections" => connections = v.parse().expect("bad connections"),
                "client-warmup" => warmup = v.parse().expect("bad warmup"),
                "client-duration" => duration = v.parse().expect("bad duration"),
                "client-target-mode" => pinned = v == "pinned",
                _ => {}
            }
        }
    }
    assert!(!target.is_empty(), "missing --client-target");
    let targets = target
        .split(',')
        .map(|t| t.trim())
        .filter(|t| !t.is_empty())
        .map(|t| format!("{}{}", t.trim_end_matches('/'), endpoint))
        .collect::<Vec<_>>();
    assert!(!targets.is_empty(), "no target parsed");
    Config { targets, endpoint, connections, warmup, duration, pinned }
}

struct Phase {
    rps: f64,
    hist: Histogram<u64>,
    errors: u64,
}

async fn run_phase(client: &reqwest::Client, cfg: &Config, secs: u64) -> Phase {
    let deadline = Instant::now() + Duration::from_secs(secs);
    let completed = Arc::new(AtomicU64::new(0));
    let errors = Arc::new(AtomicU64::new(0));
    let pick = Arc::new(AtomicU64::new(0));
    let targets = Arc::new(cfg.targets.clone());

    let start = Instant::now();
    let mut handles = Vec::with_capacity(cfg.connections);
    for worker in 0..cfg.connections {
        let client = client.clone();
        let targets = targets.clone();
        let completed = completed.clone();
        let errors = errors.clone();
        let pick = pick.clone();
        let pinned = if cfg.pinned { Some(targets[worker % targets.len()].clone()) } else { None };
        handles.push(tokio::spawn(async move {
            let mut hist = Histogram::<u64>::new(3).unwrap();
            while Instant::now() < deadline {
                let url = match &pinned {
                    Some(u) => u.clone(),
                    None => {
                        let i = pick.fetch_add(1, Ordering::Relaxed) as usize % targets.len();
                        targets[i].clone()
                    }
                };
                let t0 = Instant::now();
                match client.get(&url).send().await {
                    Ok(resp) => match resp.bytes().await {
                        Ok(_) => {
                            let _ = hist.record((t0.elapsed().as_nanos() as u64).max(1));
                            completed.fetch_add(1, Ordering::Relaxed);
                        }
                        Err(_) => {
                            errors.fetch_add(1, Ordering::Relaxed);
                        }
                    },
                    Err(_) => {
                        errors.fetch_add(1, Ordering::Relaxed);
                    }
                }
            }
            hist
        }));
    }

    let mut total = Histogram::<u64>::new(3).unwrap();
    for h in handles {
        if let Ok(hist) = h.await {
            let _ = total.add(&hist);
        }
    }
    let elapsed = start.elapsed().as_secs_f64().max(1e-9);
    Phase {
        rps: completed.load(Ordering::Relaxed) as f64 / elapsed,
        hist: total,
        errors: errors.load(Ordering::Relaxed),
    }
}

fn main() {
    let cfg = parse();
    let rt = tokio::runtime::Runtime::new().unwrap();
    rt.block_on(async {
        let client = reqwest::Client::builder()
            .pool_max_idle_per_host(cfg.connections)
            .pool_idle_timeout(Duration::from_secs(30))
            .build()
            .expect("reqwest client");
        eprintln!(
            "client bench: type={} targets={} conns={} warmup={}s duration={}s",
            NAME, cfg.targets.len(), cfg.connections, cfg.warmup, cfg.duration
        );
        if cfg.warmup > 0 {
            let _ = run_phase(&client, &cfg, cfg.warmup).await;
        }
        let p = run_phase(&client, &cfg, cfg.duration).await;
        let ms = |q: f64| p.hist.value_at_quantile(q) as f64 / 1e6;
        println!(
            "{}{}|{:.0}|{:.3}|{:.3}|{:.3}|{:.3}|n/a|{}",
            NAME,
            cfg.endpoint,
            p.rps,
            ms(0.50),
            ms(0.99),
            ms(0.999),
            p.hist.max() as f64 / 1e6,
            p.errors
        );
    });
}
