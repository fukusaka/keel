# Pipeline Mode

Pipeline mode is keel's push-based I/O API. When data arrives, the engine calls your handler directly on the EventLoop thread — no coroutine, no context switch, no suspend. This eliminates coroutine overhead on the I/O hot path.

## When to use Pipeline mode

| Situation | Recommendation |
|---|---|
| Implementing a custom protocol for maximum throughput | Use `engine.bindPipeline()` — no coroutine overhead on the hot path |
| Preferring a callback/event-driven model over sequential suspend code | Pipeline mode fits naturally |
| Need both push callbacks and suspend reads on the same connection | `PipelinedChannel` extends `Channel` — both modes are available |

## How Pipeline mode works

```
Inbound:
  engine reads into IoBuf → pipeline.notifyRead(buf)
      HEAD → [TLS] → Decoder → Handler → TAIL

Outbound:
  Handler calls ctx.propagateWriteAndFlush(response)
      Handler → Encoder → [TLS] → HEAD
                                    → IoTransport.write() / flush() → socket
```

Each accepted connection has a `Pipeline` — an ordered chain of handlers. Inbound events flow from HEAD to TAIL; outbound operations flow from the calling handler toward HEAD. HEAD performs the actual socket I/O via `IoTransport`.

`ctx.propagateWrite()` traverses **toward HEAD** from the calling handler's position, triggering each `OutboundHandler` it encounters. Only outbound handlers between the caller and HEAD are invoked — handlers on the TAIL side are not. This determines where encoders must be placed (see [Pipeline](#channelpipeline)).

All handler callbacks run on the EventLoop thread and **must not block or suspend**. CPU-intensive work must be offloaded to another dispatcher.

## Writing handlers

Three interfaces cover the handler roles:

**`InboundHandler`** — receives data and connection lifecycle events flowing HEAD → TAIL:

```kotlin
interface InboundHandler : PipelineHandler {
    fun onActive(ctx: PipelineHandlerContext) { ctx.propagateActive() }
    fun onRead(ctx: PipelineHandlerContext, msg: Any) { ctx.propagateRead(msg) }
    fun onReadComplete(ctx: PipelineHandlerContext) { ctx.propagateReadComplete() }
    fun onInactive(ctx: PipelineHandlerContext) { ctx.propagateInactive() }
    fun onError(ctx: PipelineHandlerContext, cause: Throwable) { ctx.propagateError(cause) }
    fun onUserEvent(ctx: PipelineHandlerContext, event: Any) { ctx.propagateUserEvent(event) }
}
```

Default implementations propagate each event to the next handler. Override only the callbacks you need. Call `ctx.propagateRead(transformed)` to pass a decoded or transformed message to the next inbound handler.

**`OutboundHandler`** — intercepts write, flush, and close operations flowing toward HEAD. An encoder implements this to convert application-level messages into `IoBuf` before the transport writes them:

```kotlin
class MyResponseEncoder : OutboundHandler {
    override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is MyResponse) {
            val buf = ctx.allocator.allocate(/* size */)
            // encode msg into buf
            ctx.propagateWrite(buf)   // release our alloc after propagating
            buf.release()
        } else {
            ctx.propagateWrite(msg)   // pass through unknown types unchanged
        }
    }
}
```

Multiple `propagateWrite` calls accumulate in the transport's outbound buffer. A single `propagateFlush` (or `propagateWriteAndFlush`) flushes them to the OS in one batch, enabling gather-write (`writev`) when the engine supports it.

**`DuplexHandler`** — implements both inbound and outbound. Use this for codecs that transform messages in both directions (e.g., a combined request decoder and response encoder).

### TypedInboundHandler

For inbound handlers that consume a specific decoded message type, `TypedInboundHandler<T>` eliminates the type cast and handles auto-release:

```kotlin
class MyHandler : TypedInboundHandler<HttpRequest>(HttpRequest::class) {
    override fun onReadTyped(ctx: PipelineHandlerContext, msg: HttpRequest) {
        val response = HttpResponse(HttpStatus.OK, body = "Hello!".encodeToByteArray())
        ctx.propagateWriteAndFlush(response)
    }
}
```

When `onReadTyped` returns without forwarding `msg`, the buffer is released automatically. For a lambda-based alternative:

```kotlin
val handler = typedHandler<HttpRequest> { ctx, msg ->
    ctx.propagateWriteAndFlush(HttpResponse(HttpStatus.OK, body = "Hello!".encodeToByteArray()))
}
```

## Pipeline

```
Network ↔ HEAD ↔ [TLS] ↔ Decoder ↔ Encoder ↔ Handler ↔ TAIL
              inbound →                              ← outbound
```

Configure the pipeline per-connection in the `bindPipeline` callback. The engine calls `armRead()` automatically after the initializer returns:

```kotlin
engine.bindPipeline("0.0.0.0", 8080) { channel ->
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    channel.pipeline.addLast("encoder", HttpResponseEncoder())  // must be on the HEAD side of the handler
    channel.pipeline.addLast("handler", MyRoutingHandler())
}
```

**Handler placement matters for outbound.** `ctx.propagateWrite()` travels toward HEAD from the calling handler's position. An encoder placed between the handler and HEAD intercepts outbound writes; an encoder placed on the TAIL side of the handler is never reached.

The pipeline validates that adjacent handlers have compatible message types at construction time (`addLast`, `replace`, etc.), catching mismatches before any data flows. Handlers opt into validation by declaring `acceptedType` and `producedType`; both default to `Any` (no validation).

## BindConfig

`BindConfig` provides per-server configuration. Pass it as the third argument to `bindPipeline`:

```kotlin
// Custom accept backlog
engine.bindPipeline(host, port, BindConfig(backlog = 512)) { ... }

// HTTPS: keel TlsHandler installed per-connection
// factory is a TlsCodecFactory (e.g. OpenSslCodecFactory, JsseTlsCodecFactory)
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig, factory)) { ... }

// HTTPS: engine-native TLS (NWConnection / Node.js only)
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig)) { ... }

// HTTPS: Netty SslHandler with a custom backlog
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig, NettySslInstaller(), backlog = 256)) { ... }
```

`TlsConnectorConfig` extends `BindConfig` and inherits the `backlog` parameter. See [TLS](./tls.md) for how to construct `tlsConfig` and choose a `factory`.

## PipelinedChannel

`PipelinedChannel` is the per-connection object passed to the `bindPipeline` callback:

```kotlin
interface PipelinedChannel : Channel {
    val pipeline: Pipeline
    val isWritable: Boolean  // false when outbound buffer exceeds high-water mark
}
```

`PipelinedChannel` extends `Channel`, so a pipeline connection can also be used with suspend-based reads and writes when needed.

Handlers that need to throttle output should monitor writability changes via `onWritabilityChanged(ctx, isWritable: Boolean)` in `InboundHandler` — pause writes when false, resume when true.

`armRead()` is not part of the interface. Each engine calls it internally after the `bindPipeline` initializer returns, so the initializer does not need to call it.

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

Pipeline mode avoids coroutine context switches on the I/O path: inbound data flows directly from the engine's read callback through the handler chain without suspending. This makes it well-suited for high-throughput custom protocol servers where the overhead of a coroutine per connection is not acceptable. See the [Engine Selection Guide](./engine-guide.md#performance-by-engine) for benchmark numbers.
