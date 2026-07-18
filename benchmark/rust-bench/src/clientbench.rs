// Shared driving logic for the Rust client benchmarks (reqwest, hyper): CLI
// parsing, the concurrent load loop, and the harness result line. Each client
// implements [HttpGet] (one GET that consumes its body); everything else is
// shared. Included into each bin via `#[path = "../clientbench.rs"] mod ...`.

use hdrhistogram::Histogram;
use std::future::Future;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub struct Config {
    pub targets: Vec<String>, // full URLs (base + endpoint)
    pub endpoint: String,
    pub connections: usize,
    pub warmup: u64,
    pub duration: u64,
    pub pinned: bool,
}

pub fn parse() -> Config {
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

/// A client that performs one GET and fully consumes the body, returning
/// whether it succeeded. Cloneable so every worker task shares the pool.
pub trait HttpGet: Clone + Send + Sync + 'static {
    fn get(&self, url: String) -> impl Future<Output = bool> + Send;
}

pub struct Phase {
    pub rps: f64,
    pub hist: Histogram<u64>,
    pub errors: u64,
}

pub async fn run_phase<C: HttpGet>(client: C, cfg: &Config, secs: u64) -> Phase {
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
                if client.get(url).await {
                    let _ = hist.record((t0.elapsed().as_nanos() as u64).max(1));
                    completed.fetch_add(1, Ordering::Relaxed);
                } else {
                    errors.fetch_add(1, Ordering::Relaxed);
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

pub fn report(name: &str, cfg: &Config, p: &Phase) {
    let ms = |q: f64| p.hist.value_at_quantile(q) as f64 / 1e6;
    println!(
        "{}{}|{:.0}|{:.3}|{:.3}|{:.3}|{:.3}|n/a|{}",
        name,
        cfg.endpoint,
        p.rps,
        ms(0.50),
        ms(0.99),
        ms(0.999),
        p.hist.max() as f64 / 1e6,
        p.errors
    );
}

pub fn log_start(name: &str, cfg: &Config) {
    eprintln!(
        "client bench: type={} targets={} conns={} warmup={}s duration={}s",
        name,
        cfg.targets.len(),
        cfg.connections,
        cfg.warmup,
        cfg.duration
    );
}
