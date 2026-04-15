# keel benchmark

HTTP throughput benchmark comparing keel against mainstream server engines across JVM, Kotlin/Native, Rust, Go, Swift, and Zig.

## Servers

### JVM (via classpath)

| Engine | Framework | Mode | I/O Model |
|---|---|---|---|
| `ktor-keel-nio` | keel + NioEngine | Ktor | Async (EventLoop + Dispatchers.Default) |
| `pipeline-http-nio` | keel + NioEngine | Pipeline | Async (EventLoop) |
| `ktor-keel-netty` | keel + NettyEngine | Ktor | Async (Netty EventLoop) |
| `pipeline-http-netty` | keel + NettyEngine | Pipeline | Async (Netty EventLoop) |
| `ktor-cio` | Ktor CIO | Ktor | Async (coroutines) |
| `ktor-netty` | Ktor Netty | Ktor | Async (EventLoop) |
| `netty-raw` | Netty ServerBootstrap | Raw | Async (EventLoop) |
| `spring` | Spring WebFlux | — | Async (Reactor Netty) |
| `vertx` | Vert.x | — | Async (EventLoop) |

### Kotlin/Native (standalone binary)

| Engine | Platform | Mode | I/O Model |
|---|---|---|---|
| `ktor-keel-kqueue` | macOS (kqueue) | Ktor | Async (EventLoop dispatch) |
| `pipeline-http-kqueue` | macOS (kqueue) | Pipeline | Async (EventLoop) |
| `ktor-keel-epoll` | Linux (epoll) | Ktor | Async (EventLoop dispatch) |
| `pipeline-http-epoll` | Linux (epoll) | Pipeline | Async (EventLoop) |
| `ktor-keel-io-uring` | Linux (io_uring) | Ktor | Async (io_uring SQ/CQ) |
| `pipeline-http-io-uring` | Linux (io_uring) | Pipeline | Async (io_uring SQ/CQ) |
| `ktor-keel-nwconnection` | macOS (NWConnection) | Ktor | Async (dispatch queue) |
| `pipeline-http-nwconnection` | macOS (NWConnection) | Pipeline | Async (dispatch queue) |
| `raw-io-uring` | Linux (io_uring) | Raw | Pre-encoded bytes (no codec) |
| `ktor-cio` | macOS / Linux | Ktor | Async (coroutines) |

### JS / Node.js

| Engine | Framework | Mode | I/O Model |
|---|---|---|---|
| `pipeline-http-nodejs` | keel + NodeEngine | Pipeline | Async (Node.js event loop) |

### Phase 2 Native (standalone binaries)

| Server | Language | Framework | I/O Model |
|---|---|---|---|
| `rust-hello` | Rust | Axum 0.8 + tokio | Async (work-stealing) |
| `go-hello` | Go | Gin | Goroutines |
| `swift-hello` | Swift | Hummingbird 2 + SwiftNIO | Async (EventLoop) |
| `zig-hello` | Zig | std.http.Server | Thread-per-connection |

## Quick Start

```bash
# Build (Native + JVM + JS)
./gradlew -Pbenchmark :benchmark:linkReleaseExecutableMacosArm64 :benchmark:writeClasspath :benchmark:compileProductionExecutableKotlinJs

# Single engine
./benchmark/bench-one.sh ktor-keel-kqueue \
  ./benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe \
  --engine=ktor-keel-kqueue --port=18090

# Single engine (Node.js)
./benchmark/bench-one.sh pipeline-http-nodejs \
  node benchmark/build/compileSync/js/main/productionExecutable/kotlin/keel-benchmark.js \
  --engine=pipeline-http-nodejs --port=18090

# All engines (default /hello)
./benchmark/bench-all.sh

# All engines (/large, 3 runs median, shuffled)
BENCH_RUNS=3 BENCH_SHUFFLE=true BENCH_ENDPOINT=/large ./benchmark/bench-all.sh
```

## TLS / HTTPS Support

### HTTP / HTTPS support matrix

#### keel engines

| Engine | Mode | HTTP | HTTPS | TLS backend |
|---|---|---|---|---|
| `ktor-keel-nio` | Ktor | o | o | `--tls=jsse` |
| `pipeline-http-nio` | Pipeline | o | o | `--tls=jsse` |
| `ktor-keel-netty` | Ktor | o | o | `--tls=jsse` |
| `pipeline-http-netty` | Pipeline | o | o | `--tls=jsse` |
| `ktor-keel-kqueue` | Ktor | o | o | `--tls=openssl\|mbedtls\|awslc` |
| `pipeline-http-kqueue` | Pipeline | o | o | `--tls=openssl\|mbedtls\|awslc` |
| `ktor-keel-epoll` | Ktor | o | o | `--tls=openssl\|mbedtls\|awslc` |
| `pipeline-http-epoll` | Pipeline | o | o | `--tls=openssl\|mbedtls\|awslc` |
| `ktor-keel-io-uring` | Ktor | o | o | `--tls=openssl\|mbedtls\|awslc` |
| `pipeline-http-io-uring` | Pipeline | o | o | `--tls=openssl\|mbedtls\|awslc` |
| `ktor-keel-nwconnection` | Ktor | o | o | `--tls=openssl\|mbedtls\|awslc` |
| `pipeline-http-nwconnection` | Pipeline | o | o | `--tls=openssl\|mbedtls\|awslc` |
| `raw-io-uring` | Raw | o | — \*1 | |
| `ktor-cio` | Ktor | o | — \*2 | |

\*1 Pre-encoded bytes bypass codec layer; TLS requires pipeline integration.
\*2 Ktor CIO engine does not support HTTPS (`ConnectorType.HTTPS` is explicitly rejected).

#### External engines

| Engine | HTTP | HTTPS | TLS implementation |
|---|---|---|---|
| `ktor-netty` | o | o | JDK KeyStore |
| `netty-raw` | o | o | Netty SslContext |
| `spring` | o | o | Spring SSL properties |
| `vertx` | o | o | Vert.x PemKeyCert |
| `rust-hello` | o | o | rustls |
| `go-hello` | o | o | Go crypto/tls |
| `swift-hello` | o | o | SwiftNIO SSL |
| `zig-hello` | o | — \*3 | |

\*3 Zig std.http.Server does not support TLS.

### TLS backends (keel engines)

| Backend | Platform | Library |
|---|---|---|
| `jsse` | JVM | JDK standard JSSE (SSLEngine) |
| `openssl` | Native | OpenSSL 3.x (cinterop) |
| `mbedtls` | Native | Mbed TLS 4.x (cinterop) |
| `awslc` | Native | AWS-LC (cinterop) |

### Build with TLS

```bash
# JVM + Native with TLS (default: OpenSSL backend for Native)
./gradlew -Pbenchmark -Ptls :benchmark:linkReleaseExecutableMacosArm64 :benchmark:writeClasspath

# Native with specific TLS backend
./gradlew -Pbenchmark -Ptls -Ptls-backend=mbedtls :benchmark:linkReleaseExecutableMacosArm64
```

### Run HTTPS benchmarks

```bash
# Single engine
BENCH_SCHEME=https ./benchmark/bench-one.sh ktor-keel-nio-tls \
  java -cp "$(cat benchmark/build/benchmark-classpath.txt)" \
  io.github.fukusaka.keel.benchmark.JvmMainKt --engine=ktor-keel-nio --tls=jsse --port=18090

# Full matrix HTTPS (all keel engines)
BENCH_SCHEME=https ./benchmark/bench-keel.sh

# Full matrix HTTPS (all engines including external)
BENCH_SCHEME=https ./benchmark/bench-all.sh
```

## Endpoints

| Path | Response | Purpose |
|---|---|---|
| `/hello` | `Hello, World!` (13 bytes) | Minimal overhead |
| `/large` | 100KB text | Buffer write efficiency |

## Benchmark Scripts

| Script | Purpose |
|---|---|
| `bench-one.sh` | Single engine benchmark |
| `bench-keel.sh` | keel engines only (keel-* + ktor-cio) |
| `bench-all.sh` | All engines (Phase 2 Native + Kotlin/Native + JVM) |
| `bench-pull.sh` | Pull results from a remote host over `rsync`/`ssh` |
| `bench-snapshot.sh` | Snapshot raw results with summary |

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `BENCH_ENDPOINT` | `/hello` | Endpoint to benchmark |
| `BENCH_RUNS` | `1` | Runs per engine; median is reported |
| `BENCH_SHUFFLE` | `false` | Randomize engine execution order |
| `BENCH_COOLDOWN` | `2` | Seconds between engines for OS recovery |
| `BENCH_WRK_THREADS` | `4` | wrk threads |
| `BENCH_WRK_CONNS` | `100` | wrk connections |
| `BENCH_WRK_DURATION` | `10s` | wrk duration |
| `BENCH_PORT` | `18090` | Starting port |
| `BENCH_HOST_LABEL` | `$(hostname -s)` | Hostname label for results directory |
| `BENCH_SCHEME` | `http` | `http` or `https` (requires `--tls` on keel engines) |

### Recommended Usage

```bash
# Quick: single engine regression check
./benchmark/bench-one.sh <name> <command> [args...]

# Keel comparison: keel engines vs ktor-cio
./benchmark/bench-keel.sh

# Full matrix (release / milestone):
BENCH_RUNS=3 BENCH_SHUFFLE=true ./benchmark/bench-all.sh
BENCH_RUNS=3 BENCH_SHUFFLE=true BENCH_ENDPOINT=/large ./benchmark/bench-all.sh

# Remote (run on a Linux benchmark host reachable via ssh):
ssh <linux-bench-host> "cd <path-to-keel-checkout> && BENCH_RUNS=3 BENCH_SHUFFLE=true ./benchmark/bench-all.sh"
./benchmark/bench-pull.sh <linux-bench-host>
```

## Profiles

All servers (JVM and Native) share the same 3 profiles:

| Profile | Description |
|---|---|
| `default` | Each engine's out-of-box settings |
| `tuned` | Maximum performance (auto-tuned for CPU cores) |
| `keel-equiv-0.1` | All engines match keel 0.1.x (keep-alive off) |

```bash
# Show resolved config without starting server
java -cp @benchmark/build/benchmark-classpath.txt \
  io.github.fukusaka.keel.benchmark.JvmMainKt --engine=ktor-netty --profile=tuned --show-config
```

## CLI Reference

All servers accept the same `--key=value` CLI format.

### Common

| Argument | Description |
|---|---|
| `--port=N` | Listen port (default: `8080`) |
| `--profile=NAME` | Profile preset (default: `default`) |
| `--show-config` | Display resolved config and exit |
| `--connection-close=BOOL` | Force keep-alive off (Connection: close header) |
| `--tls=BACKEND` | Enable HTTPS with specified TLS backend (`jsse`, `openssl`, `mbedtls`, `awslc`) |

### Socket Options

| Argument | Description | JVM | rust | go | swift | zig |
|---|---|---|---|---|---|---|
| `--tcp-nodelay=BOOL` | TCP_NODELAY | o | o | o | \* | o |
| `--reuse-address=BOOL` | SO_REUSEADDR | o | o | o | o | o |
| `--backlog=N` | SO_BACKLOG | o | o | \* | o | o |
| `--send-buffer=N` | SO_SNDBUF (bytes) | o | o | o | \* | o |
| `--receive-buffer=N` | SO_RCVBUF (bytes) | o | o | o | \* | o |
| `--threads=N` | Worker thread count | o | o | o | o | \* |

o = applied. \* = accepted and displayed in show-config but not applied by framework.

### Engine-Specific (JVM)

| Argument | Engine | Description |
|---|---|---|
| `--engine=NAME` | (JVM only) | Engine to run (default: `ktor-keel-nio`) |
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

## Build

### Kotlin (JVM + Native + JS)

```bash
# macOS (JVM + Native + JS)
./gradlew -Pbenchmark :benchmark:linkReleaseExecutableMacosArm64 :benchmark:writeClasspath :benchmark:compileProductionExecutableKotlinJs
# Linux (JVM + Native + JS)
./gradlew -Pbenchmark :benchmark:linkReleaseExecutableLinuxX64 :benchmark:writeClasspath :benchmark:compileProductionExecutableKotlinJs
```

### Phase 2 Native

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
├── build.gradle.kts                 # KMP, opt-in via -Pbenchmark
├── bench-all.sh                     # All engines benchmark runner
├── bench-keel.sh                    # keel engines benchmark runner
├── bench-one.sh                     # Single engine benchmark runner
├── bench-pull.sh                    # Pull results from remote host
├── bench-snapshot.sh                # Snapshot raw results
├── results/                         # Raw wrk output (.gitignore)
│   └── {hostname}/                  # Per-host results
├── results-summary/                 # Summary + snapshots (.gitignore)
├── src/commonMain/kotlin/.../
│   ├── BenchmarkConfig.kt           # Configuration + profiles + CLI parsing
│   ├── BenchmarkConstants.kt        # Shared constants (LARGE_PAYLOAD_SIZE)
│   ├── EngineBenchmark.kt           # Engine abstraction
│   ├── EngineConfig.kt              # Engine configuration
│   ├── SocketConfig.kt              # Socket options
│   └── Platform.kt                  # Platform expect declarations
├── src/commonForKtorServerMain/kotlin/.../
│   ├── BenchmarkModule.kt           # Shared Ktor routing (/hello, /large)
│   └── CioEngine.kt                 # Ktor CIO engine benchmark
├── src/jvmMain/kotlin/.../
│   ├── JvmMain.kt                   # JVM entry point
│   ├── EngineRegistry.jvm.kt        # JVM engine registry
│   ├── KeelNioEngine.kt             # keel NIO adapter (Ktor)
│   ├── PipelineHttpNioBenchmark.kt  # keel NIO pipeline
│   ├── KeelNettyEngine.kt           # keel Netty adapter (Ktor)
│   ├── PipelineHttpNettyBenchmark.kt # keel Netty pipeline
│   ├── KtorNettyEngine.kt           # Ktor Netty adapter
│   ├── NettyRawEngine.kt            # Raw Netty ServerBootstrap
│   ├── SpringEngine.kt              # Spring WebFlux adapter
│   └── VertxEngine.kt               # Vert.x adapter
├── src/nativeMain/kotlin/.../
│   ├── NativeMain.kt                # Native entry point
│   └── Platform.native.kt           # Native platform actual
├── src/macosMain/kotlin/.../
│   ├── EngineRegistry.macos.kt      # macOS engine registry
│   ├── KeelKqueueEngine.kt          # keel kqueue adapter (Ktor)
│   ├── PipelineHttpKqueueBenchmark.kt # keel kqueue pipeline
│   ├── KeelNwConnectionEngine.kt    # keel NWConnection adapter (Ktor)
│   └── PipelineHttpNwBenchmark.kt   # keel NWConnection pipeline
├── src/linuxMain/kotlin/.../
│   ├── EngineRegistry.linux.kt      # Linux engine registry
│   ├── KeelEpollEngine.kt           # keel epoll adapter (Ktor)
│   ├── PipelineHttpEpollBenchmark.kt # keel epoll pipeline
│   ├── KeelIoUringEngine.kt        # keel io_uring adapter (Ktor)
│   └── PipelineHttpIoUringBenchmark.kt # keel io_uring pipeline
├── src/jsMain/kotlin/.../
│   ├── JsMain.kt                    # Node.js entry point
│   ├── Platform.js.kt               # JS platform actual
│   └── PipelineHttpNodejsBenchmark.kt # Node.js pipeline benchmark
├── rust-hello/                      # Rust Axum (Phase 2)
├── go-hello/                        # Go Gin (Phase 2)
├── swift-hello/                     # Swift Hummingbird (Phase 2, macOS only)
└── zig-hello/                       # Zig std.http.Server (Phase 2)
```
