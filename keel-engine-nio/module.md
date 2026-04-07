# Module keel-engine-nio

JVM NIO Selector-based IoEngine implementation with multi-threaded EventLoop.

Provides both **Pipeline mode** (zero-suspend, callback-driven HTTP server) and
**Channel mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

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

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): engine drives read via OP_READ callbacks.
Data flows through the handler chain synchronously on the EventLoop thread.
Used for high-performance HTTP servers.

```
Selector: OP_READ → SocketChannel.read(ByteBuffer)
  → pipeline.notifyRead(buf)
    → TlsHandler → Decoder → Router → Encoder → IoTransport.write()
```

**Channel mode** (`bind`/`connect`): app drives I/O via suspend `read()`/`write()`/`flush()`.
A `SuspendBridgeHandler` is lazily installed to bridge Pipeline callbacks to suspend.
Used for interactive protocols (SMTP, Redis), proxies, and Ktor integration.

```
Selector: OP_READ → SocketChannel.read(ByteBuffer)
  → pipeline.notifyRead(buf)
    → SuspendBridgeHandler.onRead() → queue
                                        ↓
App:                        suspend channel.read(buf) ← dequeue
```

## Zero-Copy I/O

Read and write pass `IoBuf.unsafeBuffer` (`DirectByteBuffer`) directly to
`SocketChannel.read()`/`SocketChannel.write()`:

```
Read:    SocketChannel.read(DirectByteBuffer)  → IoBuf.unsafeBuffer
Write:   SocketChannel.write(DirectByteBuffer) ← IoBuf.unsafeBuffer
Gather:  SocketChannel.write(ByteBuffer[])       for multiple pending buffers
```

No intermediate `ByteArray` copy. The `IoBuf` backing `DirectByteBuffer` is
accessed directly by the JVM NIO layer.

## Pipeline Dispatcher

Pipeline mode uses `Dispatchers.Default` (ForkJoinPool work-stealing) as the
application dispatcher instead of the EventLoop fixed-partition. This avoids
head-of-line blocking when one channel's handler chain is slow, and better
utilizes CPU cores under mixed workloads.

## Key Classes

| Class | Role |
|-------|------|
| `NioEngine` | `IoEngine` implementation. Creates boss + worker EventLoops |
| `NioPipelinedChannel` | Unified channel supporting Pipeline + Channel modes |
| `NioPipelinedServerChannel` | Pipeline-mode server (callback-driven accept) |
| `NioServerChannel` | Channel-mode server (suspend-based accept) |
| `NioIoTransport` | `IoTransport` for pipeline write/flush with OP_WRITE |
| `NioEventLoop` | Single-threaded Selector loop + `CoroutineDispatcher` |
| `NioEventLoopGroup` | Round-robin distribution of channels across EventLoops |

# Package io.github.fukusaka.keel.engine.nio

JVM NIO Selector-based IoEngine with multi-threaded EventLoop, unified Pipeline + Channel
mode via `NioPipelinedChannel`, and zero-copy I/O via `IoBuf.unsafeBuffer` (DirectByteBuffer).
