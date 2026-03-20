// Benchmark configuration with CLI parsing, profiles, and show-config.
// Mirrors the JVM BenchmarkConfig for consistent cross-language comparison.

use socket2::{Domain, Protocol, Socket, Type};
use std::{env, process};

pub struct Config {
    pub port: u16,
    pub profile: String,
    pub show_config: bool,
    pub connection_close: bool,
    pub socket: SocketConfig,
    pub tokio_blocking_threads: Option<usize>,
}

pub struct SocketConfig {
    pub tcp_nodelay: Option<bool>,
    pub reuse_address: Option<bool>,
    pub backlog: Option<u32>,
    pub send_buffer: Option<u32>,
    pub receive_buffer: Option<u32>,
    pub threads: Option<usize>,
}

struct OsDefaults {
    tcp_nodelay: bool,
    reuse_address: bool,
    send_buffer: usize,
    receive_buffer: usize,
}

impl OsDefaults {
    fn detect() -> Self {
        let sock =
            Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP)).expect("temp socket");
        let defaults = OsDefaults {
            tcp_nodelay: sock.nodelay().unwrap_or(false),
            reuse_address: sock.reuse_address().unwrap_or(false),
            send_buffer: sock.send_buffer_size().unwrap_or(0),
            receive_buffer: sock.recv_buffer_size().unwrap_or(0),
        };
        drop(sock);
        defaults
    }
}

impl Config {
    pub fn parse() -> Self {
        let mut config = Config {
            port: 8080,
            profile: "default".to_string(),
            show_config: false,
            connection_close: false,
            socket: SocketConfig {
                tcp_nodelay: None,
                reuse_address: None,
                backlog: None,
                send_buffer: None,
                receive_buffer: None,
                threads: None,
            },
            tokio_blocking_threads: None,
        };

        for arg in env::args().skip(1) {
            if arg == "--show-config" {
                config.show_config = true;
                continue;
            }
            if let Some((key, value)) = arg.strip_prefix("--").and_then(|s| s.split_once('=')) {
                match key {
                    "port" => config.port = value.parse().expect("invalid port"),
                    "profile" => config.profile = value.to_string(),
                    "connection-close" => {
                        config.connection_close = value.parse().expect("invalid bool")
                    }
                    "tcp-nodelay" => {
                        config.socket.tcp_nodelay = Some(value.parse().expect("invalid bool"))
                    }
                    "reuse-address" => {
                        config.socket.reuse_address = Some(value.parse().expect("invalid bool"))
                    }
                    "backlog" => {
                        config.socket.backlog = Some(value.parse().expect("invalid int"))
                    }
                    "send-buffer" => {
                        config.socket.send_buffer = Some(value.parse().expect("invalid int"))
                    }
                    "receive-buffer" => {
                        config.socket.receive_buffer = Some(value.parse().expect("invalid int"))
                    }
                    "threads" => {
                        config.socket.threads = Some(value.parse().expect("invalid int"))
                    }
                    "tokio-blocking-threads" => {
                        config.tokio_blocking_threads = Some(value.parse().expect("invalid int"))
                    }
                    _ => {} // silently ignore unknown args
                }
            }
        }

        config.apply_profile();
        config
    }

    fn apply_profile(&mut self) {
        match self.profile.as_str() {
            "default" => {}
            "tuned" => {
                let cpu = cpu_cores();
                let s = &mut self.socket;
                s.tcp_nodelay = s.tcp_nodelay.or(Some(true));
                s.reuse_address = s.reuse_address.or(Some(true));
                s.backlog = s.backlog.or(Some(1024));
                s.threads = s.threads.or(Some(cpu));
            }
            p if p.starts_with("keel-equiv") => {
                self.connection_close = true;
            }
            _ => {
                eprintln!("Unknown profile: {}", self.profile);
                eprintln!("Available: default, tuned, keel-equiv-<version>");
                process::exit(1);
            }
        }
    }

    /// Create a pre-configured TCP socket with all resolved socket options.
    pub fn create_socket(&self) -> std::io::Result<Socket> {
        let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
        if let Some(v) = self.socket.tcp_nodelay {
            socket.set_nodelay(v)?;
        }
        if let Some(v) = self.socket.reuse_address {
            socket.set_reuse_address(v)?;
        }
        if let Some(v) = self.socket.send_buffer {
            socket.set_send_buffer_size(v as usize)?;
        }
        if let Some(v) = self.socket.receive_buffer {
            socket.set_recv_buffer_size(v as usize)?;
        }
        Ok(socket)
    }

    pub fn display(&self) -> String {
        let os = OsDefaults::detect();
        let cpu = cpu_cores();
        let s = &self.socket;

        let mut out = String::new();
        let fmt = |label: &str, value: &str| -> String {
            format!("  {:<22} {}\n", label, value)
        };

        out.push_str("=== Benchmark Configuration ===\n");
        out.push_str(&fmt("server:", "rust-hello"));
        out.push_str(&fmt("port:", &self.port.to_string()));
        out.push_str(&fmt("profile:", &self.profile));
        out.push_str(&fmt("cpu-cores:", &cpu.to_string()));
        out.push('\n');

        out.push_str("--- Connection ---\n");
        out.push_str(&fmt("connection-close:", &self.connection_close.to_string()));
        out.push('\n');

        out.push_str("--- Socket Options ---\n");
        out.push_str(&fmt(
            "tcp-nodelay:",
            &opt_or_default(s.tcp_nodelay, &format!("{} (default by OS)", os.tcp_nodelay)),
        ));
        out.push_str(&fmt(
            "reuse-address:",
            &opt_or_default(
                s.reuse_address,
                &format!("{} (default by OS)", os.reuse_address),
            ),
        ));
        out.push_str(&fmt(
            "backlog:",
            &opt_or_default_int(s.backlog, "128 (default by OS)"),
        ));
        out.push_str(&fmt(
            "send-buffer:",
            &opt_or_default(
                s.send_buffer,
                &format!("{} bytes (default by OS)", os.send_buffer),
            ),
        ));
        out.push_str(&fmt(
            "receive-buffer:",
            &opt_or_default(
                s.receive_buffer,
                &format!("{} bytes (default by OS)", os.receive_buffer),
            ),
        ));
        let threads_display = match s.threads {
            Some(t) if t == cpu => format!("{} (tuned: cpu-cores)", t),
            Some(t) => t.to_string(),
            None => format!("{} (default by Tokio, cpu-cores)", cpu),
        };
        out.push_str(&fmt("threads:", &threads_display));
        out.push('\n');

        out.push_str("--- Engine-Specific (rust-hello) ---\n");
        let default_blocking = 512; // tokio default max_blocking_threads
        out.push_str(&fmt(
            "tokio-blocking-threads:",
            &opt_or_default(
                self.tokio_blocking_threads,
                &format!("{} (default by Tokio)", default_blocking),
            ),
        ));

        out
    }
}

fn cpu_cores() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1)
}

fn opt_or_default<T: std::fmt::Display>(opt: Option<T>, default: &str) -> String {
    match opt {
        Some(v) => v.to_string(),
        None => default.to_string(),
    }
}

fn opt_or_default_int(opt: Option<u32>, default: &str) -> String {
    match opt {
        Some(v) => v.to_string(),
        None => default.to_string(),
    }
}
