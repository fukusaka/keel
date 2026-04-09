# Pipeline Mode

keel offers two I/O modes: **Channel mode** (suspend-based) and **Pipeline mode** (push-based). Pipeline mode eliminates coroutine overhead on the I/O hot path for maximum throughput.

## Channel vs Pipeline

| | Channel Mode | Pipeline Mode |
|---|---|---|
| API | `suspend fun read() / write()` | `ChannelPipeline` handlers |
| Concurrency | Coroutine per connection | Push callbacks on EventLoop |
| Bind | `engine.bind(host, port)` | `engine.bindPipeline(host, port, config) { ... }` |
| Use case | Simple protocol handling | High-throughput servers |
| Ktor | Via `keel-ktor-engine` | Direct (no Ktor) |

## ChannelPipeline

Pipeline mode processes data through a chain of handlers:

```
Network ↔ HEAD ↔ [TLS] ↔ Decoder ↔ Router ↔ Encoder ↔ TAIL ↔ Transport
              inbound →                              ← outbound
```

Each handler processes inbound data (reads) or outbound data (writes) and passes results to the next handler. The pipeline is configured per-connection via `bindPipeline`:

```kotlin
engine.bindPipeline("0.0.0.0", 8080) { channel ->
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    channel.pipeline.addLast("router", RoutingHandler(routes))
    channel.pipeline.addLast("encoder", HttpResponseEncoder())
}
```

## BindConfig

`BindConfig` provides per-server configuration for `bind()` and `bindPipeline()`:

```kotlin
// HTTP with custom backlog
engine.bindPipeline(host, port, BindConfig(backlog = 512)) { ... }

// HTTPS with per-connection TLS (keel TlsHandler)
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig, factory)) { ... }

// HTTPS with engine-native TLS (NWConnection / Node.js)
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig)) { ... }

// HTTPS with Netty SslHandler + custom backlog
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig, NettySslInstaller(), backlog = 256)) { ... }
```

`TlsConnectorConfig` extends `BindConfig`, inheriting the `backlog` parameter.

## PipelinedChannel

`PipelinedChannel` is the per-connection handle in Pipeline mode. It extends `Channel` (Channel mode interface) so Pipeline connections can also be used with suspend APIs via `ensureBridge()`.

```kotlin
interface PipelinedChannel : Channel {
    val pipeline: ChannelPipeline
    val allocator: BufferAllocator
    fun armRead()    // Start receiving data
    fun close()
}
```

All 7 engines implement `PipelinedChannel`:

| Engine | Implementation |
|--------|---------------|
| kqueue | `KqueuePipelinedChannel` |
| epoll | `EpollPipelinedChannel` |
| io_uring | `IoUringPipelinedChannel` |
| NIO | `NioPipelinedChannel` |
| Netty | `NettyPipelinedChannel` |
| NWConnection | `NwPipelinedChannel` |
| Node.js | `NodePipelinedChannel` |

## Performance

Pipeline mode avoids coroutine context switches on the I/O path. On Linux (Ryzen 9), pipeline-http-epoll reaches **864K req/s** compared to ktor-keel-epoll at 589K (47% improvement). With HTTPS (OpenSSL), pipeline-http-epoll reaches **529K req/s**.
