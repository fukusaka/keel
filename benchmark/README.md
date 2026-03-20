# keel benchmark

HTTP throughput benchmark comparing keel against mainstream JVM server engines.

## Engines

| Engine | Framework | I/O Model | Threads (default) |
|---|---|---|---|
| `keel-nio` | keel + NioEngine | Sync | 64 (Dispatchers.IO) |
| `keel-netty` | keel + NettyEngine | Sync | 64 (Dispatchers.IO) |
| `ktor-cio` | Ktor CIO | Async (coroutines) | coroutine-based |
| `ktor-netty` | Ktor Netty | Async (EventLoop) | cpu/2+1 (by Netty) |
| `netty-raw` | Netty ServerBootstrap | Async (EventLoop) | cpu*2 (by Netty) |
| `spring` | Spring WebFlux | Async (Reactor Netty) | cpu (by Reactor) |
| `vertx` | Vert.x | Async (EventLoop) | cpu (by Vert.x) |

## Quick Start

```bash
./gradlew -Pbenchmark :benchmark:run --args="--engine=keel-nio --port=8080"
wrk -t4 -c100 -d10s --latency http://127.0.0.1:8080/hello
```

## Profiles

| Profile | Description |
|---|---|
| `default` | Each engine's out-of-box settings |
| `tuned` | Maximum performance (auto-tuned for CPU cores) |
| `keel-equiv-0.1` | All engines match keel 0.1.x (Connection: close) |

```bash
./gradlew -Pbenchmark :benchmark:run --args="--engine=ktor-netty --profile=tuned"
./gradlew -Pbenchmark :benchmark:run --args="--engine=ktor-cio --profile=keel-equiv-0.1"
```

## Default Values and Tuned Overrides

All defaults are read at runtime. Use `--show-config` to see resolved values.

### Socket Options per Engine

| Option | keel-nio | keel-netty | ktor-cio | ktor-netty | netty-raw | spring | vertx | tuned |
|---|---|---|---|---|---|---|---|---|
| tcp-nodelay | n/a | n/a | n/a | OS | OS | true* | true** | true |
| reuse-address | n/a | n/a | false** | OS | OS | true* | true** | true |
| backlog | n/a | n/a | n/a | OS | OS | OS | -1** | 1024 |
| threads | 64 (IO) | 64 (IO) | 64 (IO) | cpu/2+1** | cpu*2** | cpu* | cpu** | cpu |

n/a = not configurable by benchmark. OS = read from `java.net.Socket`. * = by Reactor Netty. ** = by engine class.

### Engine-Specific Tuned Values

| Option | Engine | Default (by) | Tuned |
|---|---|---|---|
| running-limit | ktor-netty | 32 (Netty) | cpu*16 |
| share-work-group | ktor-netty | false (Netty) | false |
| connection-idle-timeout | ktor-cio | 45 sec (CIO) | 10 sec |
| decoder-buf-size | vertx | 128 (Vert.x) | 256 |
| validate-headers | spring | true (Netty) | false |

## CLI Reference

### Common

| Argument | Description |
|---|---|
| `--engine=NAME` | Engine to run (default: `keel-nio`) |
| `--port=N` | Listen port (default: `8080`) |
| `--profile=NAME` | Profile preset (default: `default`) |
| `--show-config` | Display resolved config and exit |
| `--connection-close=BOOL` | Force Connection: close |

### Socket Options

| Argument | Description |
|---|---|
| `--tcp-nodelay` | TCP_NODELAY |
| `--reuse-address` | SO_REUSEADDR |
| `--backlog` | SO_BACKLOG |
| `--send-buffer` | SO_SNDBUF (bytes) |
| `--receive-buffer` | SO_RCVBUF (bytes) |
| `--threads` | Worker thread count |

### Engine-Specific

| Argument | Engine | Description |
|---|---|---|
| `--running-limit` | ktor-netty | Max concurrent pipeline requests |
| `--share-work-group` | ktor-netty | Share connection/worker groups |
| `--connection-idle-timeout` | ktor-cio, vertx | Idle timeout (seconds) |
| `--max-chunk-size` | vertx, spring | Max HTTP chunk size (bytes) |
| `--max-header-size` | vertx | Max total header length |
| `--max-initial-line-length` | vertx, spring | Max request line length |
| `--decoder-initial-buffer-size` | vertx | HTTP decoder buffer |
| `--compression-supported` | vertx | Enable gzip/deflate |
| `--compression-level` | vertx | Compression level (1-9) |
| `--max-keep-alive-requests` | spring | Max requests per connection |
| `--validate-headers` | spring | Header validation |
| `--max-in-memory-size` | spring | Max in-memory buffer (bytes) |

## Automated Benchmarks

```bash
./scripts/bench-run.sh                                      # all engines, default
./scripts/bench-run.sh --engine=ktor-cio --profile=tuned    # specific
./scripts/bench-compare.sh                                  # compare results
```

## Endpoints

| Path | Response | Purpose |
|---|---|---|
| `/hello` | `Hello, World!` (13 bytes) | Minimal overhead |
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
