# keel benchmark

HTTP throughput benchmark comparing keel against mainstream server engines across JVM, Kotlin/Native, Rust, Go, Swift, and Zig.

## Servers

### JVM (via classpath)

| Engine | Framework | I/O Model |
|---|---|---|
| `ktor-keel-nio` | keel + NioEngine | Async (EventLoop + Dispatchers.Default) |
| `ktor-keel-netty` | keel + NettyEngine | Async (Netty EventLoop) |
| `ktor-cio` | Ktor CIO | Async (coroutines) |
| `ktor-netty` | Ktor Netty | Async (EventLoop) |
| `netty-raw` | Netty ServerBootstrap | Async (EventLoop) |
| `spring` | Spring WebFlux | Async (Reactor Netty) |
| `vertx` | Vert.x | Async (EventLoop) |

### Kotlin/Native (standalone binary)

| Engine | Platform | I/O Model |
|---|---|---|
| `ktor-keel-kqueue` | macOS (kqueue) | Async (EventLoop dispatch) |
| `ktor-keel-epoll` | Linux (epoll) | Async (EventLoop dispatch) |
| `ktor-keel-nwconnection` | macOS (NWConnection) | Async (dispatch queue) |
| `ktor-cio` | macOS / Linux | Async (coroutines) |

### Phase 2 Native (standalone binaries)

| Server | Language | Framework | I/O Model |
|---|---|---|---|
| `rust-hello` | Rust | Axum 0.8 + tokio | Async (work-stealing) |
| `go-hello` | Go | Gin | Goroutines |
| `swift-hello` | Swift | Hummingbird 2 + SwiftNIO | Async (EventLoop) |
| `zig-hello` | Zig | std.http.Server | Thread-per-connection |

## Quick Start

```bash
# Build
./gradlew -Pbenchmark :benchmark:linkReleaseExecutableMacosArm64 :benchmark:writeClasspath

# Single engine
./benchmark/bench-one.sh ktor-keel-kqueue \
  ./benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe \
  --engine=ktor-keel-kqueue --port=18090

# All engines (default /hello)
./benchmark/bench-all.sh

# All engines (/large, 3 runs median, shuffled)
BENCH_RUNS=3 BENCH_SHUFFLE=true BENCH_ENDPOINT=/large ./benchmark/bench-all.sh
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
| `bench-pull.sh` | Pull results from remote host (luna.local) |
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

### Recommended Usage

```bash
# Quick: single engine regression check
./benchmark/bench-one.sh <name> <command> [args...]

# Keel comparison: keel engines vs ktor-cio
./benchmark/bench-keel.sh

# Full matrix (release / milestone):
BENCH_RUNS=3 BENCH_SHUFFLE=true ./benchmark/bench-all.sh
BENCH_RUNS=3 BENCH_SHUFFLE=true BENCH_ENDPOINT=/large ./benchmark/bench-all.sh

# Remote (luna.local):
ssh luna.local "cd /home/fukusaka/prj/keel-work/keel && BENCH_RUNS=3 BENCH_SHUFFLE=true ./benchmark/bench-all.sh"
./benchmark/bench-pull.sh
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

### Kotlin (JVM + Native)

```bash
./gradlew -Pbenchmark :benchmark:linkReleaseExecutableMacosArm64 :benchmark:writeClasspath
# or for Linux:
./gradlew -Pbenchmark :benchmark:linkReleaseExecutableLinuxX64 :benchmark:writeClasspath
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
│   ├── BenchmarkModule.kt           # Shared Ktor routing (/hello, /large)
│   ├── EngineBenchmark.kt           # Engine abstraction
│   ├── EngineConfig.kt              # Engine configuration
│   ├── SocketConfig.kt              # Socket options
│   └── Platform.kt                  # Platform expect declarations
├── src/jvmMain/kotlin/.../
│   ├── JvmMain.kt                   # JVM entry point
│   ├── EngineRegistry.jvm.kt        # JVM engine registry
│   ├── KeelNioEngine.kt             # keel NIO adapter
│   ├── KeelNettyEngine.kt           # keel Netty adapter
│   ├── KtorNettyEngine.kt           # Ktor Netty adapter
│   ├── NettyRawEngine.kt            # Raw Netty ServerBootstrap
│   ├── SpringEngine.kt              # Spring WebFlux adapter
│   └── VertxEngine.kt               # Vert.x adapter
├── src/nativeMain/kotlin/.../
│   ├── NativeMain.kt                # Native entry point
│   └── Platform.native.kt           # Native platform actual
├── src/macosMain/kotlin/.../
│   ├── EngineRegistry.macos.kt      # macOS engine registry
│   ├── KeelKqueueEngine.kt          # keel kqueue adapter
│   └── KeelNwConnectionEngine.kt    # keel NWConnection adapter
├── src/linuxMain/kotlin/.../
│   ├── EngineRegistry.linux.kt      # Linux engine registry
│   └── KeelEpollEngine.kt           # keel epoll adapter
├── rust-hello/                      # Rust Axum (Phase 2)
├── go-hello/                        # Go Gin (Phase 2)
├── swift-hello/                     # Swift Hummingbird (Phase 2, macOS only)
└── zig-hello/                       # Zig std.http.Server (Phase 2)
```
