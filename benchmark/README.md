# keel benchmark

HTTP throughput benchmark comparing keel against mainstream JVM server engines.

## Engines

| Engine | Framework | I/O Model |
|---|---|---|
| `keel-nio` | keel Ktor adapter + NioEngine | Sync (Phase (a)) |
| `keel-netty` | keel Ktor adapter + NettyEngine | Sync (Phase (a)) |
| `ktor-cio` | Ktor CIO | Async (coroutines) |
| `ktor-netty` | Ktor Netty | Async (Netty EventLoop) |
| `spring` | Spring Boot WebFlux | Async (Reactor Netty) |
| `vertx` | Vert.x | Async (EventLoop) |

## Quick Start

```bash
# Start a benchmark server
./gradlew -Pbenchmark :benchmark:run --args="--engine=keel-nio --port=8080"

# In another terminal
wrk -t4 -c100 -d10s --latency http://127.0.0.1:8080/hello
```

## Profiles

| Profile | Description |
|---|---|
| `default` | Each engine's out-of-box settings |
| `tuned` | Maximum performance (auto-tuned for CPU cores) |
| `keel-equiv-0.1` | All engines match keel 0.1.x (Connection: close) |
| `keel-equiv-0.2` | All engines match keel 0.2.x (keep-alive, future) |

```bash
# Tuned profile
./gradlew -Pbenchmark :benchmark:run --args="--engine=ktor-netty --profile=tuned"

# keel-equivalent (forces Connection: close)
./gradlew -Pbenchmark :benchmark:run --args="--engine=ktor-cio --profile=keel-equiv-0.1"
```

## CLI Arguments

### Common

| Argument | Description | Default |
|---|---|---|
| `--engine=NAME` | Engine to run | `keel-nio` |
| `--port=N` | Listen port | `8080` |
| `--profile=NAME` | Profile preset | `default` |
| `--show-config` | Display resolved config and exit | — |
| `--connection-close=BOOL` | Force Connection: close | `false` |

### Socket Options (all engines)

| Argument | Description |
|---|---|
| `--tcp-nodelay=BOOL` | TCP_NODELAY |
| `--reuse-address=BOOL` | SO_REUSEADDR |
| `--backlog=N` | SO_BACKLOG |
| `--send-buffer=N` | SO_SNDBUF (bytes) |
| `--receive-buffer=N` | SO_RCVBUF (bytes) |
| `--threads=N` | Worker thread count |

### Ktor Netty

| Argument | Description | Default |
|---|---|---|
| `--running-limit=N` | Max concurrent pipeline requests | 32 |
| `--share-work-group=BOOL` | Share connection/worker groups | false |

### Ktor CIO

| Argument | Description | Default |
|---|---|---|
| `--idle-timeout=N` | Connection idle timeout (seconds) | 45 |

### Vert.x

| Argument | Description | Default |
|---|---|---|
| `--max-chunk-size=N` | Max HTTP chunk size (bytes) | 8192 |
| `--max-header-size=N` | Max total header length | 8192 |
| `--max-initial-line-length=N` | Max request line length | 4096 |
| `--decoder-initial-buffer-size=N` | HTTP decoder buffer | 128 |
| `--compression-supported=BOOL` | Enable gzip/deflate | false |
| `--compression-level=N` | Compression level (1-9) | 6 |
| `--idle-timeout=N` | Idle timeout (seconds) | 0 |

### Spring WebFlux

| Argument | Description | Default |
|---|---|---|
| `--max-keep-alive-requests=N` | Max requests per connection | unlimited |
| `--max-chunk-size=N` | Max chunk size (bytes) | 8192 |
| `--max-initial-line-length=N` | Max request line length | 4096 |
| `--validate-headers=BOOL` | Header validation | true |
| `--max-in-memory-size=N` | Max in-memory buffer (bytes) | 262144 |

## Show Config

Display resolved settings without starting the server:

```bash
./gradlew -Pbenchmark :benchmark:run \
  --args="--engine=ktor-netty --profile=tuned --threads=16 --show-config"
```

## Automated Benchmarks

```bash
# Run all engines with default profile
./scripts/bench-run.sh

# Specific engine and profile
./scripts/bench-run.sh --engine=ktor-cio --profile=keel-equiv-0.1

# Compare results
./scripts/bench-compare.sh
```

## Endpoints

| Path | Response | Purpose |
|---|---|---|
| `/hello` | `Hello, World!` (13 bytes) | Minimal overhead measurement |
| `/large` | 100KB text | Buffer write efficiency |

## Module Structure

```
benchmark/
├── build.gradle.kts                 # JVM-only, opt-in via -Pbenchmark
└── src/jvmMain/kotlin/.../
    ├── BenchmarkConfig.kt           # Configuration + profiles + CLI parsing
    ├── BenchmarkModule.kt           # Shared Ktor routing (/hello, /large)
    ├── BenchmarkApp.kt              # CLI entry point + engine launchers
    ├── SpringBenchmark.kt           # Spring Boot WebFlux server
    └── VertxBenchmark.kt            # Vert.x server
```
