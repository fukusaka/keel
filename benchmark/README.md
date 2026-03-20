# keel benchmark

HTTP throughput benchmark comparing keel against mainstream server engines across JVM, Rust, Go, Swift, and Zig.

## Servers

### JVM (via Gradle)

| Engine | Framework | I/O Model | Threads (default) |
|---|---|---|---|
| `keel-nio` | keel + NioEngine | Sync | 64 (Dispatchers.IO) |
| `keel-netty` | keel + NettyEngine | Sync | 64 (Dispatchers.IO) |
| `ktor-cio` | Ktor CIO | Async (coroutines) | coroutine-based |
| `ktor-netty` | Ktor Netty | Async (EventLoop) | cpu/2+1 (by Netty) |
| `netty-raw` | Netty ServerBootstrap | Async (EventLoop) | cpu*2 (by Netty) |
| `spring` | Spring WebFlux | Async (Reactor Netty) | cpu (by Reactor) |
| `vertx` | Vert.x | Async (EventLoop) | cpu (by Vert.x) |

### Native (standalone binaries)

| Server | Language | Framework | I/O Model |
|---|---|---|---|
| `rust-hello` | Rust | Axum 0.8 + tokio | Async (work-stealing) |
| `go-hello` | Go | Gin | Goroutines |
| `swift-hello` | Swift | Hummingbird 2 + SwiftNIO | Async (EventLoop) |
| `zig-hello` | Zig | std.http.Server | Thread-per-connection |

## Quick Start

```bash
# JVM engine
./gradlew -Pbenchmark :benchmark:run --args="--engine=keel-nio --port=8080"
wrk -t4 -c100 -d10s --latency http://127.0.0.1:8080/hello

# Native server (Rust example)
cd benchmark/rust-hello && cargo build --release
./target/release/rust-hello --port=8080
wrk -t4 -c100 -d10s --latency http://127.0.0.1:8080/hello
```

## Profiles

All servers (JVM and Native) share the same 3 profiles:

| Profile | Description |
|---|---|
| `default` | Each engine's out-of-box settings |
| `tuned` | Maximum performance (auto-tuned for CPU cores) |
| `keel-equiv-0.1` | All engines match keel 0.1.x (keep-alive off) |

```bash
# JVM — default profile
./gradlew -Pbenchmark :benchmark:run --args="--engine=ktor-netty --port=8080"
wrk -t4 -c100 -d10s --latency http://127.0.0.1:8080/hello

# JVM — tuned profile
./gradlew -Pbenchmark :benchmark:run --args="--engine=ktor-netty --port=8080 --profile=tuned"
wrk -t4 -c100 -d10s --latency http://127.0.0.1:8080/hello

# JVM — keel-equiv (keep-alive off, fair comparison with keel sync)
./gradlew -Pbenchmark :benchmark:run --args="--engine=ktor-netty --port=8080 --profile=keel-equiv-0.1"
wrk -t4 -c100 -d10s --latency http://127.0.0.1:8080/hello

# Native — tuned profile
cd benchmark/rust-hello && cargo build --release
./target/release/rust-hello --port=8080 --profile=tuned
wrk -t4 -c100 -d10s --latency http://127.0.0.1:8080/hello

# Show resolved config without starting server
./gradlew -Pbenchmark :benchmark:run --args="--engine=ktor-netty --profile=tuned --show-config"
./target/release/rust-hello --profile=tuned --show-config
```

## Endpoints

| Path | Response | Purpose |
|---|---|---|
| `/hello` | `Hello, World!` (13 bytes) | Minimal overhead |
| `/large` | 100KB text | Buffer write efficiency |

## CLI Reference

All servers accept the same `--key=value` CLI format.

### Common

| Argument | Description |
|---|---|
| `--port=N` | Listen port (default: `8080`) |
| `--profile=NAME` | Profile preset (default: `default`) |
| `--show-config` | Display resolved config and exit |
| `--connection-close=BOOL` | Force keep-alive off (Connection: close header) |

### Socket Options

| Argument | Description | JVM | rust | go | swift | zig |
|---|---|---|---|---|---|---|
| `--tcp-nodelay=BOOL` | TCP_NODELAY | o | o | o | \* | o |
| `--reuse-address=BOOL` | SO_REUSEADDR | o | o | o | o | o |
| `--backlog=N` | SO_BACKLOG | o | o | \* | o | o |
| `--send-buffer=N` | SO_SNDBUF (bytes) | o | o | o | \* | o |
| `--receive-buffer=N` | SO_RCVBUF (bytes) | o | o | o | \* | o |
| `--threads=N` | Worker thread count | o | o | o | o | \* |

o = applied. \* = accepted and displayed in show-config but not applied by framework (swift: tcp-nodelay/send-buffer/receive-buffer managed by SwiftNIO; go: backlog uses SOMAXCONN; zig: thread-per-connection model, no thread pool).

### Engine-Specific (JVM)

| Argument | Engine | Description |
|---|---|---|
| `--engine=NAME` | (JVM only) | Engine to run (default: `keel-nio`) |
| `--running-limit=N` | ktor-netty | Max concurrent pipeline requests |
| `--share-work-group=BOOL` | ktor-netty | Share connection/worker groups |
| `--connection-idle-timeout=N` | ktor-cio, vertx | Idle timeout (seconds) |
| `--max-chunk-size=N` | vertx, spring | Max HTTP chunk size (bytes) |
| `--max-header-size=N` | vertx | Max total header length |
| `--max-initial-line-length=N` | vertx, spring | Max request line length |
| `--decoder-initial-buffer-size=N` | vertx | HTTP decoder buffer |
| `--compression-supported=BOOL` | vertx | Enable gzip/deflate |
| `--compression-level=N` | vertx | Compression level (1-9) |
| `--max-keep-alive-requests=N` | spring | Max requests per connection |
| `--validate-headers=BOOL` | spring | Header validation |
| `--max-in-memory-size=N` | spring | Max in-memory buffer (bytes) |

### Engine-Specific (Native)

| Argument | Server | Description |
|---|---|---|
| `--tokio-blocking-threads=N` | rust-hello | Tokio max blocking threads |
| `--read-buffer=N` | zig-hello | HTTP read buffer size (default: 8192) |
| `--write-buffer=N` | zig-hello | HTTP write buffer size (default: 8192) |

## Default Values and Tuned Overrides (JVM)

All defaults are read at runtime. Use `--show-config` to see resolved values.

### Default Socket Options per Engine

| Option | keel-nio | keel-netty | ktor-cio | ktor-netty | netty-raw | spring | vertx |
|---|---|---|---|---|---|---|---|
| tcp-nodelay | n/a | n/a | n/a | OS | OS | true\* | true\*\* |
| reuse-address | n/a | n/a | false\*\* | OS | OS | true\* | true\*\* |
| backlog | n/a | n/a | n/a | OS | OS | OS | -1\*\* |
| threads | 64 (IO) | 64 (IO) | 64 (IO) | cpu/2+1\*\* | cpu\*2\*\* | cpu\* | cpu\*\* |

n/a = not configurable. OS = read from `java.net.Socket`. \* = by Reactor Netty. \*\* = by engine class.

### Tuned Socket Overrides per Engine

> **Note**: Tuned values are initial estimates based on documentation and source code
> analysis, not yet validated by systematic benchmarking. Use `--show-config` to inspect
> and override via CLI as needed.

Only values that differ from the engine's built-in default are overridden:

| Option | keel-nio | keel-netty | ktor-cio | ktor-netty | netty-raw | spring | vertx |
|---|---|---|---|---|---|---|---|
| tcp-nodelay | n/a | n/a | — | true | true | — | — |
| reuse-address | n/a | n/a | true | true | true | — | — |
| backlog | n/a | n/a | — | 1024 | 1024 | 1024 | 1024 |
| threads | n/a | n/a | — | cpu | — | cpu | cpu |

n/a = no tunable socket options. — = already optimal or not configurable. netty-raw keeps Netty default (cpu\*2) for EventLoop model.

### Engine-Specific Tuned Values

| Option | Engine | Default | Tuned |
|---|---|---|---|
| running-limit | ktor-netty | 32 | cpu\*16 |
| share-work-group | ktor-netty | false | false |
| connection-idle-timeout | ktor-cio | 45 sec | 10 sec |
| decoder-buf-size | vertx | 128 | 256 |
| validate-headers | spring | true | false |

## Automated Benchmarks

```bash
# JVM engines (sequential, with wrk)
./scripts/bench-run.sh                                      # all JVM engines, default
./scripts/bench-run.sh --engine=ktor-cio --profile=tuned    # specific engine
./scripts/bench-compare.sh                                  # compare results

# All servers (JVM + Native)
./benchmark/bench-all.sh                                    # default profile
./benchmark/bench-all.sh tuned                              # tuned profile
```

## Build (Native)

| Server | Build | Binary |
|---|---|---|
| rust-hello | `cd benchmark/rust-hello && cargo build --release` | `target/release/rust-hello` |
| go-hello | `cd benchmark/go-hello && go build -o go-hello` | `go-hello` |
| swift-hello | `cd benchmark/swift-hello && swift build -c release` | `.build/release/swift-hello` |
| zig-hello | `cd benchmark/zig-hello && zig build -Doptimize=ReleaseFast` | `zig-out/bin/zig-hello` |

swift-hello is macOS only (requires SwiftNIO + Network.framework).

## Module Structure

```
benchmark/
├── build.gradle.kts                 # JVM-only, opt-in via -Pbenchmark
├── bench-all.sh                     # Cross-language benchmark runner
├── src/jvmMain/kotlin/.../
│   ├── BenchmarkConfig.kt           # Configuration + profiles + CLI parsing
│   ├── BenchmarkModule.kt           # Shared Ktor routing (/hello, /large)
│   ├── BenchmarkApp.kt              # CLI entry point + engine registry
│   ├── NettyRawBenchmark.kt         # Raw Netty ServerBootstrap server
│   ├── SpringBenchmark.kt           # Spring Boot WebFlux server
│   └── VertxBenchmark.kt            # Vert.x server
├── rust-hello/                      # Rust Axum (Phase 2)
├── go-hello/                        # Go Gin (Phase 2)
├── swift-hello/                     # Swift Hummingbird (Phase 2, macOS only)
└── zig-hello/                       # Zig std.http.Server (Phase 2)
```
