# Module benchmark

HTTP/1.1 throughput benchmark server for comparing keel engines against Netty, Ktor, Spring, and Vert.x.

Not a production library module — this is a standalone runnable program.

## Purpose

The benchmark module provides a single HTTP server binary that can be switched between engines
at startup via `--engine=<name>`. This enables apples-to-apples comparisons under identical
OS/JVM/hardware conditions.

Two endpoints:
- **`/hello`** — `Hello, World!` (13 bytes). Measures raw request/response throughput.
- **`/large`** — 100 KB (`x` × 102400) response. Measures large-payload write throughput.

## Usage

```
# macOS: ktor-keel-kqueue (default)
./benchmark --engine=ktor-keel-kqueue --port=8080 --profile=tuned

# macOS: Pipeline mode (no Ktor overhead)
./benchmark --engine=pipeline-http-kqueue

# Linux: ktor-keel-epoll with HTTPS (AWS-LC)
./benchmark --engine=ktor-keel-epoll --tls=awslc --tls-installer=keel

# Show resolved config
./benchmark --engine=ktor-netty --profile=tuned --show-config
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
| JVM | `ktor-keel-netty` | keel Netty + Ktor |
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
Native signal handlers (`SIGTERM`/`SIGINT`) call `_exit(0)` for immediate port release — avoids
atexit handlers that could deadlock. Handlers are installed after server start because Ktor
overrides them during engine initialization.

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
