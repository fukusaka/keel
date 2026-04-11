# Module benchmark

HTTP/1.1 throughput benchmark server for comparing keel engines against Netty, Ktor, Spring, and Vert.x.

Not a production library module — this is a standalone runnable program.

## Purpose

The benchmark module provides a single HTTP server binary that can be switched between engines
at startup via `--engine=<name>`. This enables apples-to-apples comparisons under identical
OS/JVM/hardware conditions.

Two endpoints:
- **`/hello`** — `Hello, World!` (11 bytes). Measures raw request/response throughput.
- **`/large`** — 100 KB response. Measures large-payload write throughput.

## Usage

```
# macOS: keel-kqueue (default)
./benchmark --engine=keel-kqueue --port=8080 --profile=tuned

# Linux: keel-epoll with HTTPS (AWS-LC)
./benchmark --engine=keel-epoll --tls=awslc --tls-installer=keel

# Show resolved config
./benchmark --engine=ktor-netty --profile=tuned --show-config
```

## Engine Registry

Engines are registered per-platform via `engineRegistry()`:

| Platform | Available engines |
|----------|-------------------|
| JVM | `keel-nio`, `keel-netty`, `ktor-netty`, `ktor-cio`, `netty-raw`, `spring`, `vertx` |
| macOS Native | `keel-kqueue`, `keel-nwconnection`, `ktor-cio` |
| Linux Native | `keel-epoll`, `keel-io-uring`, `ktor-cio` |
| JS (Node.js) | `keel-nodejs` |

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
}
```

Platform-specific engine files register implementations in `engineRegistry()`.
Signal handlers (`SIGTERM`/`SIGINT`) call `_exit(0)` for immediate port release.

## Key Types

| Type | Role |
|------|------|
| `EngineBenchmark` | Per-engine interface: `start`, `tunedSocket`, `tunedConfig`, `mergeConfig` |
| `BenchmarkConfig` | Full server config: engine, port, profile, TLS, socket options, engine-specific config |
| `SocketConfig` | Common socket options: `tcpNoDelay`, `reuseAddress`, `backlog`, `sendBuffer`, `receiveBuffer`, `threads` |
| `EngineConfig` | Sealed per-engine settings: `None`, `KtorNetty`, `Cio`, `Vertx`, `Spring`, `NettyRaw` |

# Package io.github.fukusaka.keel.benchmark

Benchmark server: `EngineBenchmark`, `BenchmarkConfig`, `SocketConfig`, `EngineConfig`,
and platform-specific engine registries.
