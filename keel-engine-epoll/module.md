# Module keel-engine-epoll

Linux epoll-based IoEngine implementation with multi-threaded EventLoop.

Provides both **Pipeline mode** (zero-suspend, callback-driven HTTP server) and
**Channel mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Architecture

```
EpollEngine
├── bossLoop (EpollEventLoop) ── accept readiness on server fd
│     └── epoll_wait(EPOLLIN) → accept() → assign to worker
└── workerGroup (EpollEventLoopGroup, N workers)
      ├── worker[0]: EpollPipelinedChannel A, D, ...
      ├── worker[1]: EpollPipelinedChannel B, E, ...
      └── worker[N]: ...
```

Each worker runs its own epoll fd on a dedicated pthread. Channels are assigned
in round-robin order. The EventLoop also serves as a `CoroutineDispatcher`,
so I/O + request processing runs on a single thread per channel — no cross-thread
dispatch overhead.

**Wakeup mechanism**: `eventfd(2)` registered with epoll. More efficient than
`pipe(2)` on Linux: single fd, kernel-optimized for signaling.

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): engine drives read via EPOLLIN callbacks.
Data flows through the handler chain synchronously on the EventLoop thread.
Used for high-performance HTTP servers.

```
epoll_wait(EPOLLIN) → read(fd, buf)
  → pipeline.notifyRead(buf)
    → TlsHandler → Decoder → Router → Encoder → IoTransport.write()
```

**Channel mode** (`bind`/`connect`): app drives I/O via suspend `read()`/`write()`/`flush()`.
A `SuspendBridgeHandler` is lazily installed to bridge Pipeline callbacks to suspend.
Used for interactive protocols (SMTP, Redis), proxies, and Ktor integration.

```
epoll_wait(EPOLLIN) → read(fd, buf)
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

## epoll fd Registration

The EventLoop tracks per-fd interest bits (`EPOLLIN`, `EPOLLOUT`) in `fdEvents`.
When a new interest is added, `EPOLL_CTL_ADD` is attempted first; on `EEXIST`,
it falls back to `EPOLL_CTL_MOD` with the combined events. This supports
concurrent READ + WRITE interests on the same fd (needed for Channel mode
where `armRead` and flush EAGAIN overlap).

Pipeline mode's `armRead` → `onReadable` → `armRead` cycle skips `epoll_ctl`
entirely when the interest bits haven't changed — zero syscall overhead on
the hot path after the initial registration.

## Key Classes

| Class | Role |
|-------|------|
| `EpollEngine` | `IoEngine` implementation. Creates boss + worker EventLoops |
| `EpollPipelinedChannel` | Unified channel supporting Pipeline + Channel modes |
| `EpollPipelinedServerChannel` | Pipeline-mode server (callback-driven accept) |
| `EpollServerChannel` | Channel-mode server (suspend-based accept) |
| `EpollIoTransport` | `IoTransport` for pipeline write/flush with EPOLLOUT |
| `EpollEventLoop` | Single-threaded epoll loop + `CoroutineDispatcher` |
| `EpollEventLoopGroup` | Round-robin distribution of channels across EventLoops |
| `SocketUtils` | POSIX socket helpers (bind, connect, address conversion) |

# Package io.github.fukusaka.keel.engine.epoll

Linux epoll-based IoEngine with multi-threaded EventLoop, unified Pipeline + Channel
mode via `EpollPipelinedChannel`, and zero-copy I/O via `IoBuf.unsafePointer`.
