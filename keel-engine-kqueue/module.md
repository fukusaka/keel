# Module keel-engine-kqueue

macOS kqueue-based IoEngine implementation with multi-threaded EventLoop.

Provides both **Pipeline mode** (zero-suspend, callback-driven HTTP server) and
**Channel mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Architecture

```
KqueueEngine
├── bossLoop (KqueueEventLoop) ── accept readiness on server fd
│     └── kevent(EVFILT_READ) → accept() → assign to worker
└── workerGroup (KqueueEventLoopGroup, N workers)
      ├── worker[0]: KqueuePipelinedChannel A, D, ...
      ├── worker[1]: KqueuePipelinedChannel B, E, ...
      └── worker[N]: ...
```

Each worker runs its own kqueue fd on a dedicated pthread. Channels are assigned
in round-robin order. The EventLoop also serves as a `CoroutineDispatcher`,
so I/O + request processing runs on a single thread per channel — no cross-thread
dispatch overhead.

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): engine drives read via EVFILT_READ callbacks.
Data flows through the handler chain synchronously on the EventLoop thread.
Used for high-performance HTTP servers.

```
kevent(EVFILT_READ) → read(fd, buf)
  → pipeline.notifyRead(buf)
    → TlsHandler → Decoder → Router → Encoder → IoTransport.write()
```

**Channel mode** (`bind`/`connect`): app drives I/O via suspend `read()`/`write()`/`flush()`.
A `SuspendBridgeHandler` is lazily installed to bridge Pipeline callbacks to suspend.
Used for interactive protocols (SMTP, Redis), proxies, and Ktor integration.

```
kevent(EVFILT_READ) → read(fd, buf)
  → pipeline.notifyRead(buf)
    → SuspendBridgeHandler.onRead() → queue
                                        ↓
App:                        suspend channel.read(buf) ← dequeue
```

## Zero-Copy I/O

Read and write pass `IoBuf.unsafePointer` directly to POSIX `read()`/`write()`:

```
Read:  POSIX read(fd, unsafePointer + writerIndex, writableBytes)
Write: POSIX write(fd, unsafePointer + readerIndex, readableBytes)
Gather: POSIX writev(fd, iovec[]) for multiple pending buffers
```

No intermediate `ByteArray` copy. The `IoBuf` backing memory is accessed directly.

## Key Classes

| Class | Role |
|-------|------|
| `KqueueEngine` | `IoEngine` implementation. Creates boss + worker EventLoops |
| `KqueuePipelinedChannel` | Unified channel supporting Pipeline + Channel modes |
| `KqueuePipelinedServerChannel` | Pipeline-mode server (callback-driven accept) |
| `KqueueServerChannel` | Channel-mode server (suspend-based accept) |
| `KqueueIoTransport` | `IoTransport` for pipeline write/flush with EVFILT_WRITE |
| `KqueueEventLoop` | Single-threaded event loop + `CoroutineDispatcher` |
| `KqueueEventLoopGroup` | Round-robin distribution of channels across EventLoops |
| `SocketUtils` | POSIX socket helpers (bind, connect, address conversion) |

# Package io.github.fukusaka.keel.engine.kqueue

macOS kqueue-based IoEngine with multi-threaded EventLoop, unified Pipeline + Channel
mode via `KqueuePipelinedChannel`, and zero-copy I/O via `IoBuf.unsafePointer`.
