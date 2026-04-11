# Module benchmark

HTTP/1.1 throughput benchmark suite comparing keel engines against Netty, Ktor, Spring, Vert.x, and Phase 2 Native servers (Rust, Go, Swift, Zig).

Not a production library module — this is a standalone benchmark program.

## Purpose

The benchmark module produces platform-specific HTTP server binaries (Kotlin/Native `.kexe`,
JVM classpath runner, Node.js script). Each binary accepts `--engine=<name>` at startup,
enabling apples-to-apples comparisons under identical OS/JVM/hardware conditions.

Two endpoints:
- **`/hello`** — `Hello, World!` (13 bytes). Measures raw request/response throughput.
- **`/large`** — the ASCII character `x` repeated 102,400 times (≈100 KiB). Measures large-payload write throughput.

## Build

### Kotlin (JVM + Native + JS)

```bash
# macOS (JVM + Native + JS)
./gradlew -Pbenchmark :benchmark:linkReleaseExecutableMacosArm64 :benchmark:writeClasspath :benchmark:compileProductionExecutableKotlinJs

# Linux (JVM + Native + JS)
./gradlew -Pbenchmark :benchmark:linkReleaseExecutableLinuxX64 :benchmark:writeClasspath :benchmark:compileProductionExecutableKotlinJs

# With TLS support (default: OpenSSL backend for Native)
./gradlew -Pbenchmark -Ptls :benchmark:linkReleaseExecutableMacosArm64 :benchmark:writeClasspath
```

Output artifacts:
- **Native binary**: `benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe` (or `linuxX64`)
- **JVM classpath file**: `benchmark/build/benchmark-classpath.txt` (used with `@` classpath expansion)
- **JS script**: `benchmark/build/compileSync/js/main/productionExecutable/kotlin/keel-benchmark.js`

### Phase 2 Native (Rust, Go, Swift, Zig)

| Server | Build command | Binary path |
|--------|---------------|-------------|
| `rust-hello` | `cd benchmark/rust-hello && cargo build --release` | `target/release/rust-hello` |
| `go-hello` | `cd benchmark/go-hello && go build -o go-hello` | `go-hello` |
| `swift-hello` | `cd benchmark/swift-hello && swift build -c release` | `.build/release/swift-hello` |
| `zig-hello` | `cd benchmark/zig-hello && zig build -Doptimize=ReleaseFast` | `zig-out/bin/zig-hello` |

`swift-hello` is macOS only (requires SwiftNIO + Network.framework).

## Usage

```bash
# Native binary: macOS ktor-keel-kqueue (default)
./benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe \
  --engine=ktor-keel-kqueue --port=8080 --profile=tuned

# Native binary: macOS pipeline mode (no Ktor overhead)
./benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe \
  --engine=pipeline-http-kqueue

# JVM: ktor-keel-nio
java -cp @benchmark/build/benchmark-classpath.txt \
  io.github.fukusaka.keel.benchmark.JvmMainKt --engine=ktor-keel-nio --port=8080

# JS / Node.js
node benchmark/build/compileSync/js/main/productionExecutable/kotlin/keel-benchmark.js \
  --engine=pipeline-http-nodejs --port=8080

# Linux: ktor-keel-epoll with HTTPS (AWS-LC)
./benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe \
  --engine=ktor-keel-epoll --tls=awslc

# Show resolved config and exit
java -cp @benchmark/build/benchmark-classpath.txt \
  io.github.fukusaka.keel.benchmark.JvmMainKt --engine=ktor-netty --profile=tuned --show-config
```

## Benchmark Scripts

| Script | Purpose |
|--------|---------|
| `bench-one.sh` | Single engine: `bench-one.sh <name> <command> [args...]` |
| `bench-keel.sh` | keel engines + `ktor-cio` only |
| `bench-all.sh` | All engines (Phase 2 Native + Kotlin/Native + JVM + JS) |
| `bench-pull.sh` | Pull results from remote host (`luna.local`) |
| `bench-snapshot.sh` | Snapshot raw results with summary |

Key environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `BENCH_ENDPOINT` | `/hello` | Endpoint to benchmark |
| `BENCH_RUNS` | `1` | Runs per engine; median reported |
| `BENCH_SHUFFLE` | `false` | Randomize engine execution order |
| `BENCH_SCHEME` | `http` | `http` or `https` |
| `BENCH_PORT` | `18090` | Starting port (incremented per engine) |
| `BENCH_COOLDOWN` | `2` | Seconds between engines for OS resource recovery |
| `BENCH_WRK_THREADS` | `4` | wrk thread count |
| `BENCH_WRK_CONNS` | `100` | wrk connections |
| `BENCH_WRK_DURATION` | `10s` | wrk duration |

```bash
# Quick single-engine regression check
./benchmark/bench-one.sh ktor-keel-kqueue \
  ./benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe \
  --engine=ktor-keel-kqueue --port=18090

# Full matrix (3 runs, shuffled)
BENCH_RUNS=3 BENCH_SHUFFLE=true ./benchmark/bench-all.sh
BENCH_RUNS=3 BENCH_SHUFFLE=true BENCH_ENDPOINT=/large ./benchmark/bench-all.sh
```

## Engine Registry

Engines are registered per-platform via `engineRegistry()`. The default engine for each platform:

| Platform | Default engine |
|----------|----------------|
| JVM | `ktor-keel-nio` |
| macOS Native | `ktor-keel-kqueue` |
| Linux Native | `ktor-keel-epoll` |
| JS (Node.js) | `pipeline-http-nodejs` |

All registered engines by platform:

| Platform | Engine name | Backend |
|----------|-------------|---------|
| JVM | `ktor-keel-nio` | keel NIO + Ktor |
| JVM | `pipeline-http-nio` | keel NIO Pipeline mode (no Ktor) |
| JVM | `ktor-keel-netty` | keel Netty + Ktor |
| JVM | `pipeline-http-netty` | keel Netty Pipeline mode (no Ktor) |
| JVM | `ktor-cio` | Ktor CIO |
| JVM | `ktor-netty` | Ktor Netty |
| JVM | `netty-raw` | Raw Netty (no Ktor) |
| JVM | `spring` | Spring Boot |
| JVM | `vertx` | Vert.x |
| macOS | `ktor-keel-kqueue` | keel kqueue + Ktor |
| macOS | `pipeline-http-kqueue` | keel kqueue Pipeline mode (no Ktor) |
| macOS | `ktor-keel-nwconnection` | keel NWConnection + Ktor |
| macOS | `pipeline-http-nwconnection` | keel NWConnection Pipeline mode |
| macOS | `ktor-cio` | Ktor CIO |
| Linux | `ktor-keel-epoll` | keel epoll + Ktor |
| Linux | `pipeline-http-epoll` | keel epoll Pipeline mode (no Ktor) |
| Linux | `ktor-keel-io-uring` | keel io_uring + Ktor |
| Linux | `pipeline-http-io-uring` | keel io_uring Pipeline mode |
| Linux | `raw-io-uring` | io_uring raw benchmark (no HTTP codec) |
| Linux | `ktor-cio` | Ktor CIO |
| JS | `pipeline-http-nodejs` | keel Node.js Pipeline mode |

`ktor-keel-*` engines run a full Ktor application pipeline on top of keel's `StreamEngine`.
`pipeline-http-*` engines use keel's `bindPipeline` directly (`HttpRequestDecoder` + `RoutingHandler` + `HttpResponseEncoder`) without Ktor — zero-suspend, maximum throughput.

## Phase 2 Native Servers

Non-Kotlin standalone servers included in `bench-all.sh` for cross-language comparisons:

| Server | Language | Framework | I/O Model | Platforms |
|--------|----------|-----------|-----------|-----------|
| `rust-hello` | Rust | Axum 0.8 + Tokio | Async (work-stealing) | macOS, Linux |
| `go-hello` | Go | Gin | Goroutines | macOS, Linux |
| `swift-hello` | Swift | Hummingbird 2 + SwiftNIO | Async (EventLoop) | macOS only |
| `zig-hello` | Zig | std.http.Server | Thread-per-connection | macOS, Linux |

Each accepts the same `--key=value` CLI format for `--port`, `--threads`, `--tcp-nodelay`, etc.
Source lives under `benchmark/rust-hello/`, `benchmark/go-hello/`, `benchmark/swift-hello/`, `benchmark/zig-hello/`.

## Configuration

`BenchmarkConfig` is parsed from CLI arguments. Resolution order: **CLI args > profile presets > engine defaults**.

| Argument | Description |
|----------|-------------|
| `--engine=<name>` | Engine identifier (default: platform default) |
| `--port=<int>` | Listen port (default: 8080) |
| `--profile=<name>` | `default` / `tuned` / `keel-equiv-<version>` |
| `--tls=<backend>` | TLS backend: `jsse`, `openssl`, `awslc`, `mbedtls` |
| `--tls-installer=<name>` | `keel` (TlsHandler), `netty` (NettySslInstaller), `node` |
| `--threads=<int>` | Worker thread count |
| `--backlog=<int>` | SO_BACKLOG |
| `--tcp-nodelay=<bool>` | TCP_NODELAY |
| `--connection-close=<bool>` | Force `Connection: close` (disables HTTP keep-alive) |
| `--show-config` | Print resolved config and exit |

### Profiles

| Profile | Behavior |
|---------|----------|
| `default` | Each engine's out-of-box settings |
| `tuned` | Maximum throughput: auto-calculates threads, backlog, socket options from CPU core count |
| `keel-equiv-0.1` | Constrains all engines to match keel v0.1 behavior (`Connection: close`) |

## Architecture

Each engine implements `EngineBenchmark`:

```kotlin
interface EngineBenchmark {
    fun start(config: BenchmarkConfig): () -> Unit  // returns stop callback
    fun tunedSocket(s: SocketConfig, cpuCores: Int): SocketConfig
    fun tunedConfig(config: BenchmarkConfig, cpuCores: Int): BenchmarkConfig
    fun mergeConfig(base: EngineConfig, args: Map<String, String>): EngineConfig
}
```

Platform-specific engine files register implementations in `engineRegistry()`.

**Kotlin/Native only**: `SIGTERM`/`SIGINT` handlers call `_exit(0)` for immediate port release.
`_exit` bypasses `atexit` handlers that could deadlock on shutdown. Handlers are installed
*after* server start because Ktor overrides them during engine initialization.

Phase 2 Native servers (Rust, Go, Swift, Zig) are separate programs and do not implement
`EngineBenchmark`. They are invoked directly by `bench-all.sh` via their own binaries.

## Key Types

| Type | Role |
|------|------|
| `EngineBenchmark` | Per-engine interface: `start`, `tunedSocket`, `tunedConfig`, `mergeConfig` |
| `BenchmarkConfig` | Full server config: engine, port, profile, TLS, socket options, engine-specific config |
| `SocketConfig` | Common socket options: `tcpNoDelay`, `reuseAddress`, `backlog`, `sendBuffer`, `receiveBuffer`, `threads` |
| `EngineConfig` | Per-engine settings: `None`, `KtorNetty`, `Cio`, `Vertx`, `Spring`, `NettyRaw` |

# Package io.github.fukusaka.keel.benchmark

Benchmark server: `EngineBenchmark`, `BenchmarkConfig`, `SocketConfig`, `EngineConfig`,
and platform-specific engine registries.
