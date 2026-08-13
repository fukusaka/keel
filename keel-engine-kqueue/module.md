# Module keel-engine-kqueue

macOS kqueue-based IoEngine implementation with multi-threaded EventLoop.

Provides both **Pipeline mode** (zero-suspend, callback-driven HTTP server) and
**Coroutine mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): engine drives read via EVFILT_READ callbacks.
Handlers process data synchronously on the EventLoop thread — zero suspend overhead.
Used for high-performance HTTP servers.

**Coroutine mode** (`bind`/`connect`): app drives I/O via suspend
`read()`/`write()`/`flush()`. A `SuspendBridgeHandler` bridges pipeline
callbacks to suspend. Used for interactive protocols (SMTP, Redis) and Ktor.

**Pipeline direction**: inbound (read) flows HEAD → TAIL; outbound (write/flush)
flows TAIL → HEAD. `HeadHandler` connects the pipeline to `ReadinessIoTransport`.

## Architecture

```
KqueueEngine
├── bossLoop (KqueueEventLoop) ── accept readiness on server fd
│     └── kevent(EVFILT_READ) → accept() → assign to worker
└── workerGroup (KqueueEventLoopGroup, N workers)
      ├── worker[0]: PipelinedChannel A, D, ...
      ├── worker[1]: PipelinedChannel B, E, ...
      └── worker[N]: ...
```

Each worker runs its own kqueue fd on a dedicated pthread. Channels are assigned
in round-robin order. The EventLoop also serves as a `CoroutineDispatcher`,
so I/O + request processing runs on a single thread per channel — no cross-thread
dispatch overhead.

**Wakeup mechanism**: a `pipe(2)` fd pair is registered with kqueue.
External threads write 1 byte to the pipe, causing `kevent()` to return
immediately so the EventLoop can process newly registered fds or queued tasks.

## Read Path

Both modes issue POSIX `read()` with `IoBuf.unsafePointer` — zero-copy, no intermediate
`ByteArray`. The read result enters the pipeline via `notifyRead`.

**Pipeline**: `armRead` is called immediately after pipeline setup. On each EVFILT_READ event:

```
kevent(EVFILT_READ) → read(fd, unsafePointer + writerIndex, writableBytes)
  → pipeline.notifyRead(buf)
    → handler chain (Decoder → Router → ...)
```

**Coroutine**: `armRead` is called lazily on the first `channel.read()` via `ensureBridge()`.

```
kevent(EVFILT_READ) → read(fd, unsafePointer + writerIndex, writableBytes)
  → pipeline.notifyRead(buf)
    → SuspendBridgeHandler.onRead() → queue
                                        ↓
App:                        suspend channel.read(buf) ← dequeue
```

## Write/Flush Path

Both modes share the same outbound path. The pipeline terminates at `HeadHandler`,
which delegates to `ReadinessIoTransport`.

**Pipeline**: a handler calls `ctx.propagateWrite/Flush` to push data toward HEAD.

```
handler (e.g. Encoder)
  → ctx.propagateWrite(buf) → HeadHandler.onWrite → ReadinessIoTransport.write(buf)
  → ctx.propagateFlush()   → HeadHandler.onFlush → ReadinessIoTransport.flush()
```

**Coroutine**: the app enters the pipeline at TAIL.

```
App: channel.write(buf)           → pipeline.requestWrite(buf) → ... → ReadinessIoTransport.write(buf)
App: channel.requestFlush()       → pipeline.requestFlush()    → ... → ReadinessIoTransport.flush()
App: channel.awaitFlushComplete() → transport.awaitPendingFlush()
```

Flush strategy: direct POSIX `write()` / `writev()` (gather write for multiple pending buffers).
On EAGAIN: EVFILT_WRITE is registered; when the fd becomes writable the remainder is retried
and EVFILT_WRITE is deleted.

## Key Classes

| Class | Role |
|-------|------|
| `KqueueEngine` | `StreamEngine` implementation. Creates boss + worker EventLoops |
| `PipelinedChannel` | Unified channel: Pipeline + Coroutine modes. The implementation is shared with the other readiness engine and internal to `keel-native-readiness`, so it has no page of its own |
| `ReadinessPipelinedStreamServer` | Pipeline-mode server (callback-driven accept) — shared, in `keel-native-readiness` |
| `ReadinessStreamServer` | Coroutine-mode server (suspend-based accept) — shared, in `keel-native-readiness` |
| `ReadinessIoTransport` | `IoTransport` for write/flush with EVFILT_WRITE backpressure — shared with the epoll engine, in `keel-native-readiness` |
| `KqueueEventLoop` | Single-threaded kqueue loop + `CoroutineDispatcher` |
| `KqueueEventLoopGroup` | Round-robin distribution of channels across EventLoops |
| `KqueueSyscallOps` / `PosixKqueueSyscallOps` | Seam over the `kqueue(2)`-family syscalls, making error branches testable without a real kernel |

Socket lifecycle helpers (bind/listen, non-blocking connect, address queries) come
from the shared `keel-native-posix` module (`NativeSocketOps` / `NativeSocket`).

# Package io.github.fukusaka.keel.engine.kqueue

macOS kqueue-based IoEngine with multi-threaded EventLoop, unified Pipeline + Coroutine
mode via a shared `PipelinedChannel`, and zero-copy I/O via `IoBuf.unsafePointer`.
