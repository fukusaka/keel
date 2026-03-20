# keel benchmark

HTTP throughput benchmark comparing keel against mainstream JVM server engines.

## Engines

| Engine | Framework | I/O Model |
|---|---|---|
| `keel-nio` | keel Ktor adapter + NioEngine | Sync |
| `keel-netty` | keel Ktor adapter + NettyEngine | Sync |
| `ktor-cio` | Ktor CIO | Async (coroutines) |
| `ktor-netty` | Ktor Netty | Async (Netty EventLoop) |
| `netty-raw` | Netty ServerBootstrap (no framework) | Async (Netty EventLoop) |
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

## Default Settings per Engine

Use `--show-config` to see resolved values. All defaults are read at runtime.

### Socket Options

| Option | keel-nio | keel-netty | ktor-cio | ktor-netty | netty-raw | spring | vertx |
|---|---|---|---|---|---|---|---|
| tcp-nodelay | n/a (OS) | n/a (OS) | n/a (OS) | OS default | OS default | true | true |
| reuse-address | n/a (OS) | n/a (OS) | false | OS default | OS default | true | true |
| backlog | n/a (OS) | n/a (OS) | n/a (OS) | OS default | OS default | OS default | -1 (OS) |
| send-buffer | n/a (OS) | n/a (OS) | n/a (OS) | OS default | OS default | OS default | OS default |
| receive-buffer | n/a (OS) | n/a (OS) | n/a (OS) | OS default | OS default | OS default | OS default |
| threads | 64 (IO) | 64 (IO) | 64 (IO) | cpu/2+1 | cpu*2 | cpu | cpu |

Default source: OS = `java.net.Socket`/`ServerSocket`, IO = `Dispatchers.IO`, cpu = `Runtime.availableProcessors()`

### Engine-Specific

| Option | Engine | Default | Tuned |
|---|---|---|---|
| running-limit | ktor-netty | 32 | cpu*16 |
| share-work-group | ktor-netty | false | false |
| connection-idle-timeout | ktor-cio | 45 sec | 10 sec |
| decoder-buf-size | vertx | 128 | 256 |
| validate-headers | spring | true | false |

## CLI Arguments

### Common

| Argument | Description | Default |
|---|---|---|
| `--engine=NAME` | Engine to run | `keel-nio` |
| `--port=N` | Listen port | `8080` |
| `--profile=NAME` | Profile preset | `default` |
| `--show-config` | Display resolved config and exit | -- |
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
| `--running-limit=N` | Max concurrent pipeline requests | 32 (by Netty) |
| `--share-work-group=BOOL` | Share connection/worker groups | false (by Netty) |

### Ktor CIO

| Argument | Description | Default |
|---|---|---|
| `--connection-idle-timeout=N` | Connection idle timeout (seconds) | 45 (by CIO) |

### Vert.x

| Argument | Description | Default |
|---|---|---|
| `--max-chunk-size=N` | Max HTTP chunk size (bytes) | 8192 (by Vert.x) |
| `--max-header-size=N` | Max total header length | 8192 (by Vert.x) |
| `--max-initial-line-length=N` | Max request line length | 4096 (by Vert.x) |
| `--decoder-initial-buffer-size=N` | HTTP decoder buffer | 128 (by Vert.x) |
| `--compression-supported=BOOL` | Enable gzip/deflate | false (by Vert.x) |
| `--compression-level=N` | Compression level (1-9) | 6 (by Vert.x) |
| `--connection-idle-timeout=N` | Idle timeout (seconds) | 0 (by Vert.x) |

### Spring WebFlux

| Argument | Description | Default |
|---|---|---|
| `--max-keep-alive-requests=N` | Max requests per connection | unlimited (by Spring) |
| `--max-chunk-size=N` | Max chunk size (bytes) | 8192 (by Netty) |
| `--max-initial-line-length=N` | Max request line length | 4096 (by Netty) |
| `--validate-headers=BOOL` | Header validation | true (by Netty) |
| `--max-in-memory-size=N` | Max in-memory buffer (bytes) | 262144 (by Spring) |

### Netty Raw

All tuning via Socket Options. No engine-specific parameters.

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
    ├── BenchmarkApp.kt              # CLI entry point + engine registry
    ├── NettyRawBenchmark.kt         # Raw Netty ServerBootstrap server
    ├── SpringBenchmark.kt           # Spring Boot WebFlux server
    └── VertxBenchmark.kt            # Vert.x server
```
