# Module keel-engine-nio

JVM NIO Selector-based IoEngine implementation with multi-threaded EventLoop.

Provides both **Pipeline mode** (zero-suspend, callback-driven HTTP server) and
**Coroutine mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): engine drives read via OP_READ callbacks.
Handlers process data synchronously on the EventLoop thread — zero suspend overhead.
Used for high-performance HTTP servers.

**Coroutine mode** (`bind`/`connect`): app drives I/O via suspend
`read()`/`write()`/`flush()`. A `SuspendBridgeHandler` bridges pipeline
callbacks to suspend. Used for interactive protocols (SMTP, Redis) and Ktor.

**Pipeline direction**: inbound (read) flows HEAD → TAIL; outbound (write/flush)
flows TAIL → HEAD. `HeadHandler` connects the pipeline to `NioIoTransport`.

## Architecture

```
NioEngine
├── bossLoop (NioEventLoop) ── accept readiness on ServerSocketChannel
│     └── Selector: OP_ACCEPT → accept() → assign to worker
└── workerGroup (NioEventLoopGroup, N workers)
      ├── worker[0]: NioPipelinedChannel A, D, ...
      ├── worker[1]: NioPipelinedChannel B, E, ...
      └── worker[N]: ...
```

Each worker runs its own `Selector` on a dedicated JVM thread. Channels are
assigned in round-robin order. The EventLoop also serves as a `CoroutineDispatcher`,
so I/O + request processing runs on a single thread per channel — no cross-thread
dispatch overhead.

**SelectionKey caching**: channels are registered with the Selector once via
`registerChannel()`. Subsequent I/O uses `interestOps` toggling instead of
re-registration — eliminates per-read JNI overhead (same pattern as Netty).

## Read Path

Both modes issue `SocketChannel.read()` with `IoBuf.unsafeBuffer` (DirectByteBuffer) —
zero-copy, no intermediate `ByteArray`. The read result enters the pipeline via `notifyRead`.

**Pipeline**: OP_READ interest is registered immediately after accept. On each readable event:

```
Selector: OP_READ → SocketChannel.read(DirectByteBuffer)  [IoBuf.unsafeBuffer]
  → pipeline.notifyRead(buf)
    → handler chain (Decoder → Router → ...)
```

**Coroutine**: OP_READ interest is registered lazily on the first `channel.read()` via `ensureBridge()`.

```
Selector: OP_READ → SocketChannel.read(DirectByteBuffer)  [IoBuf.unsafeBuffer]
  → pipeline.notifyRead(buf)
    → SuspendBridgeHandler.onRead() → queue
                                        ↓
App:                        suspend channel.read(buf) ← dequeue
```

## Write/Flush Path

Both modes share the same outbound path. The pipeline terminates at `HeadHandler`,
which delegates to `NioIoTransport`.

**Pipeline**: a handler calls `ctx.propagateWrite/Flush` to push data toward HEAD.

```
handler (e.g. Encoder)
  → ctx.propagateWrite(buf) → HeadHandler.onWrite → NioIoTransport.write(buf)
  → ctx.propagateFlush()   → HeadHandler.onFlush → NioIoTransport.flush()
```

**Coroutine**: the app enters the pipeline at TAIL.

```
App: channel.write(buf)           → pipeline.requestWrite(buf) → ... → NioIoTransport.write(buf)
App: channel.requestFlush()       → pipeline.requestFlush()    → ... → NioIoTransport.flush()
App: channel.awaitFlushComplete() → transport.awaitPendingFlush()
```

Flush strategy: `SocketChannel.write(ByteBuffer)` / `SocketChannel.write(ByteBuffer[])` (gather
write for multiple pending buffers). When the send buffer is full (`write()` returns 0):
OP_WRITE is registered; when the channel becomes writable the remainder is retried and
OP_WRITE is deregistered.

## Pipeline Dispatcher

NIO uses `Dispatchers.Default` (ForkJoinPool work-stealing) as the application dispatcher
instead of the EventLoop fixed-thread. This avoids head-of-line blocking when one channel's
handler chain is slow, and better utilizes CPU cores under mixed workloads.

## Key Classes

| Class | Role |
|-------|------|
| `NioEngine` | `StreamEngine` implementation. Creates boss + worker EventLoops |
| `NioPipelinedChannel` | Unified channel: Pipeline + Coroutine modes |
| `NioPipelinedServerChannel` | Pipeline-mode server (callback-driven accept) |
| `NioServer` | Coroutine-mode server (suspend-based accept) |
| `NioIoTransport` | `IoTransport` for write/flush with OP_WRITE backpressure |
| `NioEventLoop` | Single-threaded Selector loop + `CoroutineDispatcher` |
| `NioEventLoopGroup` | Round-robin distribution of channels across EventLoops |

# Package io.github.fukusaka.keel.engine.nio

JVM NIO Selector-based IoEngine with multi-threaded EventLoop, unified Pipeline + Coroutine
mode via `NioPipelinedChannel`, and zero-copy I/O via `IoBuf.unsafeBuffer` (DirectByteBuffer).
