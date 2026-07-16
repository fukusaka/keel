# Module benchmark

HTTP/1.1 throughput benchmark suite comparing keel engines against Netty, Ktor, Spring, Vert.x, and external reference servers (Rust, Go, Swift, Zig).

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

### External Reference Servers (Rust, Go, Swift, Zig)

| Server | Build command | Binary path |
|--------|---------------|-------------|
| `rust-bench` | `cd benchmark/rust-bench && cargo build --release` | `target/release/rust-bench` |
| `go-bench` | `cd benchmark/go-bench && go build -o go-bench` | `go-bench` |
| `swift-bench` | `cd benchmark/swift-bench && swift build -c release` | `.build/release/swift-bench` |
| `zig-bench` | `cd benchmark/zig-bench && zig build -Doptimize=ReleaseFast` | `zig-out/bin/zig-bench` |

`swift-bench` is macOS only (requires SwiftNIO + Network.framework).

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
| `bench-one.sh` | Single engine on loopback (wrk): `bench-one.sh <name> <command> [args...]` |
| `bench-keel.sh` | keel engines + `ktor-cio` only |
| `bench-all.sh` | All engines (external servers + Kotlin/Native + JVM + JS) |
| `bench-stream-one.sh` | Single engine, streaming scenarios (upload / SSE / WebSocket) driven by k6 instead of wrk |
| `bench-stream-all.sh` | All servers across all streaming scenarios, using `bench-stream-one.sh` per engine |
| `bench-https-matrix.sh` | HTTPS full matrix: runs `bench-keel.sh` once per TLS backend, rebuilding the binary in between |
| `bench-keepalive-compare.sh` | A/B compare of HTTP keep-alive vs `Connection: close` for one engine + scenario |
| `bench-remote.sh` | Single engine with server / wrk on different ssh hosts (real NIC) |
| `bench-remote-keel.sh` | Real-network batch run of every keel engine via `bench-remote.sh` |
| `bench-remote-slow.sh` | Slow-path scenarios (netem delay + small send buffers) to force partial-write handling on a real network |
| `bench-remote-ws.sh` | WebSocket permessage-deflate real-network throughput benchmark |
| `bench-pull.sh` | Pull results from a remote host over `rsync`/`ssh` |
| `bench-snapshot.sh` | Snapshot raw results with summary |
| `bench-preflight.sh` | Sourced helper: pre-flight binary validation so `bench-all.sh` / `bench-stream-all.sh` refuse silent partial sweeps |
| `bench-temp.sh` | Sourced helper: no-sudo CPU temperature capture (opt-in via `BENCH_TEMP_CAPTURE=1`) |
| `bench-jvm-cp.sh` | Resolves the JVM classpath file's placeholders against the running host and sanity-checks each entry |

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
| JVM | `ktor-cio-keel-nio` | keel NIO + Ktor with ktor-http-cio parser (`KeelCio`) |
| JVM | `pipeline-http-nio` | keel NIO Pipeline mode (no Ktor) |
| JVM | `server-http-nio` | keel NIO + `keelHttpServer { }` (`:keel-server-http`) |
| JVM | `ktor-keel-netty` | keel Netty + Ktor |
| JVM | `ktor-keel-netty-io-uring` | keel Netty (io_uring transport) + Ktor |
| JVM | `ktor-cio-keel-netty` | keel Netty + Ktor with ktor-http-cio parser (`KeelCio`) |
| JVM | `pipeline-http-netty` | keel Netty Pipeline mode (no Ktor) |
| JVM | `pipeline-http-netty-io-uring` | keel Netty (io_uring transport) Pipeline mode |
| JVM | `server-http-netty` | keel Netty + `keelHttpServer { }` (`:keel-server-http`) |
| JVM | `ktor-cio` | Ktor CIO |
| JVM | `ktor-netty` | Ktor Netty |
| JVM | `netty-raw` | Raw Netty (no Ktor) |
| JVM | `spring` | Spring Boot |
| JVM | `vertx` | Vert.x |
| macOS | `ktor-keel-kqueue` | keel kqueue + Ktor |
| macOS | `ktor-cio-keel-kqueue` | keel kqueue + Ktor with ktor-http-cio parser (`KeelCio`) |
| macOS | `pipeline-http-kqueue` | keel kqueue Pipeline mode (no Ktor) |
| macOS | `server-http-kqueue` | keel kqueue + `keelHttpServer { }` (`:keel-server-http`) |
| macOS | `ktor-keel-nwconnection` | keel NWConnection + Ktor |
| macOS | `ktor-cio-keel-nwconnection` | keel NWConnection + Ktor with ktor-http-cio parser (`KeelCio`) |
| macOS | `pipeline-http-nwconnection` | keel NWConnection Pipeline mode |
| macOS | `server-http-nwconnection` | keel NWConnection + `keelHttpServer { }` (`:keel-server-http`) |
| macOS | `ktor-cio` | Ktor CIO |
| Linux | `ktor-keel-epoll` | keel epoll + Ktor |
| Linux | `ktor-cio-keel-epoll` | keel epoll + Ktor with ktor-http-cio parser (`KeelCio`) |
| Linux | `pipeline-http-epoll` | keel epoll Pipeline mode (no Ktor) |
| Linux | `server-http-epoll` | keel epoll + `keelHttpServer { }` (`:keel-server-http`) |
| Linux | `ktor-keel-io-uring` | keel io_uring + Ktor |
| Linux | `ktor-cio-keel-io-uring` | keel io_uring + Ktor with ktor-http-cio parser (`KeelCio`) |
| Linux | `pipeline-http-io-uring` | keel io_uring Pipeline mode |
| Linux | `server-http-io-uring` | keel io_uring + `keelHttpServer { }` (`:keel-server-http`) |
| Linux | `raw-io-uring` | io_uring raw benchmark (no HTTP codec) |
| Linux | `ktor-cio` | Ktor CIO |
| JS | `pipeline-http-nodejs` | keel Node.js Pipeline mode |
| JS | `server-http-nodejs` | keel Node.js + `keelHttpServer { }` (`:keel-server-http`) |

`ktor-keel-*` engines run a full Ktor application pipeline on top of keel's `StreamEngine`.
`ktor-cio-keel-*` engines run the same Ktor application but parse HTTP with ktor-http-cio
(the `:keel-server-ktor-cio` adapter) instead of keel's own codec.
`pipeline-http-*` engines use keel's `bindPipeline` directly (`HttpRequestDecoder` + `RoutingHandler` + `HttpResponseEncoder`) without Ktor — zero-suspend, maximum throughput.
`server-http-*` engines run the productized `keelHttpServer { }` DSL stack from `:keel-server-http`.

## External Reference Servers

Non-Kotlin standalone servers included in `bench-all.sh` for cross-language comparisons:

| Server | Language | Framework | I/O Model | Platforms |
|--------|----------|-----------|-----------|-----------|
| `rust-bench` | Rust | Axum 0.8 + Tokio | Async (work-stealing) | macOS, Linux |
| `go-bench` | Go | Gin | Goroutines | macOS, Linux |
| `swift-bench` | Swift | Hummingbird 2 + SwiftNIO | Async (EventLoop) | macOS only |
| `zig-bench` | Zig | std.http.Server | Thread-per-connection | macOS, Linux |

Each accepts the same `--key=value` CLI format for `--port`, `--threads`, `--tcp-nodelay`, etc.
Source lives under `benchmark/rust-bench/`, `benchmark/go-bench/`, `benchmark/swift-bench/`, `benchmark/zig-bench/`.

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

External reference servers (Rust, Go, Swift, Zig) are separate programs and do not implement
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
