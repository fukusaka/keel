# Module keel-engine-netty

JVM Netty 4.x-based `StreamEngine` implementation.

Provides both **Pipeline mode** (zero-suspend, callback-driven I/O) and
**Coroutine mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): Netty delivers data via `channelRead` callbacks.
Handlers process data synchronously on the Netty EventLoop thread — zero suspend overhead.
Used for high-performance HTTP servers.

**Coroutine mode** (`bind`/`connect`): app drives I/O via suspend
`read()`/`write()`/`flush()`. A `SuspendBridgeHandler` bridges pipeline
callbacks to suspend. Used for interactive protocols (SMTP, Redis) and Ktor.

**Pipeline direction**: inbound (read) flows HEAD → TAIL; outbound (write/flush)
flows TAIL → HEAD. `HeadHandler` connects the pipeline to `NettyIoTransport`.

## Architecture

```
NettyEngine (owns boss + worker EventLoopGroups)
├── bossGroup (1 thread) ── accept on server channel
└── workerGroup (N threads)
      └── accepts I/O callbacks for each connection
```

The Netty transport is selectable via the `NettyTransport` sealed interface
(`Auto` / `Epoll` / `KQueue` / `IoUring` / `Nio`); each variant builds the
matching `EventLoopGroup` through `newEventLoopGroup(threads)`. The default
`Auto` prefers the platform's native transport (Linux → epoll, macOS/BSD →
kqueue) and falls back to NIO.

`IoEngineConfig.threads` maps directly to the Netty worker group size.
Passing `0` (default) lets Netty choose automatically (`cpu × 2`).

All `bind`/`connect` operations use `suspendCancellableCoroutine` + `ChannelFuture`
listeners — no thread blocking occurs.

`autoRead` starts disabled on every accepted/connected channel. It is enabled
when `ensureBridge()` (Coroutine mode) or `armRead()` (Pipeline mode) is called.

## Read Path

Netty delivers data asynchronously via `channelRead(ByteBuf)` before the user
provides a buffer. When the inbound `ByteBuf` is backed by a single NIO buffer
(`nioBufferCount() == 1`), the transport wraps it **zero-copy** via
`NettyByteBufIoBuf.borrowInbound` — a pooled, engine-direct `IoBuf` view over
the same off-heap memory. Composite buffers (`nioBufferCount() > 1`) fall back
to an allocate-and-copy path (`ByteBuf.getBytes`).

**Pipeline**: `armRead()` enables `autoRead = true` immediately after pipeline setup.

```
Netty channelRead(ByteBuf) → IoBuf wrap (borrowInbound; copy fallback for composites)
  → pipeline.notifyRead(buf)
    → handler chain (Decoder → Router → ...)
```

**Coroutine**: `autoRead = true` is enabled lazily when `ensureBridge()` is called.

```
Netty channelRead(ByteBuf) → IoBuf wrap (borrowInbound; copy fallback for composites)
  → pipeline.notifyRead(buf)
    → SuspendBridgeHandler.onRead() → queue
                                        ↓
App:                    suspend channel.read(buf) ← dequeue
```

## Write/Flush Path

Both modes share the same outbound path. The pipeline terminates at `HeadHandler`,
which delegates to `NettyIoTransport`.

**Pipeline**: a handler calls `ctx.propagateWrite/Flush` to push data toward HEAD.

```
handler (e.g. Encoder)
  → ctx.propagateWrite(buf) → HeadHandler.onWrite → NettyIoTransport.write(buf)
  → ctx.propagateFlush()   → HeadHandler.onFlush → NettyIoTransport.flush()
```

**Coroutine**: the app enters the pipeline at TAIL.

```
App: channel.write(buf)        → pipeline.requestWrite(buf) → ... → NettyIoTransport.write(buf)
App: channel.requestFlush()    → pipeline.requestFlush()    → ... → NettyIoTransport.flush()
```

Flush strategy: all pending writes are batched via `nettyChannel.write(ByteBuf)`, then
`writeAndFlush` is issued on the last buffer. The `ChannelFuture` completion listener
releases buffers and signals `onFlushComplete`. `flush()` always returns `false` (async).

## TLS Integration

`NettySslInstaller` installs Netty's `SslHandler` in the Netty pipeline (before
the keel bridge handler). Decryption happens at the Netty transport level —
the keel pipeline receives plaintext. No keel `TlsHandler` is needed:

```
Netty pipeline:  SslHandler → keel bridge handler
keel pipeline:   HEAD → ... → TAIL   (no TlsHandler)
```

Supported certificate sources: `TlsCertificateSource.Pem`, `TlsCertificateSource.Der`,
`TlsCertificateSource.KeyStoreFile`. `SystemKeychain` is not supported.

## Key Classes

| Class | Role |
|-------|------|
| `NettyEngine` | `StreamEngine` implementation. Creates boss + worker EventLoopGroups |
| `NettyTransport` | Sealed transport selector (`Auto` / `Epoll` / `KQueue` / `IoUring` / `Nio`) — builds the matching `EventLoopGroup` |
| `NettyByteBufAllocator` | `BufferAllocator` adapter over a Netty `ByteBufAllocator`; usable as `IoEngineConfig.allocator` on JVM engines |
| `NettyPipelinedChannel` | Unified channel: Pipeline + Coroutine modes. Bridges `channelRead` to keel pipeline |
| `NettyIoTransport` | `IoTransport` for buffered async writes via `writeAndFlush`. Always async |
| `NettySslInstaller` | `TlsServerInstaller` (in `:keel-server`) that installs Netty `SslHandler` at the transport level |
| `NettyStreamServer` | Coroutine-mode server: accepts connections into a suspend queue |

# Package io.github.fukusaka.keel.engine.netty

JVM Netty 4.x-based `StreamEngine` with boss/worker `NioEventLoopGroup`, unified Pipeline + Coroutine
mode via `NettyPipelinedChannel`, and transport-level TLS via `NettySslInstaller`.
