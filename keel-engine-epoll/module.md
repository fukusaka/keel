# Module keel-engine-epoll

Linux epoll-based IoEngine implementation with multi-threaded EventLoop.

Provides both **Pipeline mode** (zero-suspend, callback-driven HTTP server) and
**Coroutine mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): engine drives read via EPOLLIN callbacks.
Handlers process data synchronously on the EventLoop thread — zero suspend overhead.
Used for high-performance HTTP servers.

**Coroutine mode** (`bind`/`connect`): app drives I/O via suspend
`read()`/`write()`/`flush()`. A `SuspendBridgeHandler` bridges pipeline
callbacks to suspend. Used for interactive protocols (SMTP, Redis) and Ktor.

**Pipeline direction**: inbound (read) flows HEAD → TAIL; outbound (write/flush)
flows TAIL → HEAD. `HeadHandler` connects the pipeline to `EpollIoTransport`.

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

## Read Path

Both modes issue POSIX `read()` with `IoBuf.unsafePointer` — zero-copy, no intermediate
`ByteArray`. The read result enters the pipeline via `notifyRead`.

**Pipeline**: `armRead` is called immediately after pipeline setup. On each EPOLLIN event:

```
epoll_wait(EPOLLIN) → read(fd, unsafePointer + writerIndex, writableBytes)
  → pipeline.notifyRead(buf)
    → handler chain (Decoder → Router → ...)
```

**Coroutine**: `armRead` is called lazily on the first `channel.read()` via `ensureBridge()`.

```
epoll_wait(EPOLLIN) → read(fd, unsafePointer + writerIndex, writableBytes)
  → pipeline.notifyRead(buf)
    → SuspendBridgeHandler.onRead() → queue
                                        ↓
App:                        suspend channel.read(buf) ← dequeue
```

## Write/Flush Path

Both modes share the same outbound path. The pipeline terminates at `HeadHandler`,
which delegates to `EpollIoTransport`.

**Pipeline**: a handler calls `ctx.propagateWrite/Flush` to push data toward HEAD.

```
handler (e.g. Encoder)
  → ctx.propagateWrite(buf) → HeadHandler.onWrite → EpollIoTransport.write(buf)
  → ctx.propagateFlush()   → HeadHandler.onFlush → EpollIoTransport.flush()
```

**Coroutine**: the app enters the pipeline at TAIL.

```
App: channel.write(buf)           → pipeline.requestWrite(buf) → ... → EpollIoTransport.write(buf)
App: channel.requestFlush()       → pipeline.requestFlush()    → ... → EpollIoTransport.flush()
App: channel.awaitFlushComplete() → transport.awaitPendingFlush()
```

Flush strategy: direct POSIX `write()` / `writev()` (gather write for multiple pending buffers).
On EAGAIN: EPOLLOUT is registered; when the fd becomes writable the remainder is retried
and EPOLLOUT is cleared.

## epoll fd Registration

The EventLoop tracks per-fd interest bits (`EPOLLIN`, `EPOLLOUT`) in `fdEvents`.
When a new interest is added, `EPOLL_CTL_ADD` is attempted first; on `EEXIST`,
it falls back to `EPOLL_CTL_MOD` with the combined events. This supports
concurrent READ + WRITE interests on the same fd (needed for Coroutine mode
where `armRead` and flush EAGAIN overlap).

Pipeline mode's `armRead` → `onReadable` → `armRead` cycle skips `epoll_ctl`
entirely when the interest bits haven't changed — zero syscall overhead on
the hot path after the initial registration.

## Key Classes

| Class | Role |
|-------|------|
| `EpollEngine` | `StreamEngine` implementation. Creates boss + worker EventLoops |
| `EpollPipelinedChannel` | Unified channel: Pipeline + Coroutine modes |
| `EpollPipelinedServerChannel` | Pipeline-mode server (callback-driven accept) |
| `EpollServer` | Coroutine-mode server (suspend-based accept) |
| `EpollIoTransport` | `IoTransport` for write/flush with EPOLLOUT backpressure |
| `EpollEventLoop` | Single-threaded epoll loop + `CoroutineDispatcher` |
| `EpollEventLoopGroup` | Round-robin distribution of channels across EventLoops |
| `SocketUtils` | POSIX socket helpers (bind, connect, address conversion) |

# Package io.github.fukusaka.keel.engine.epoll

Linux epoll-based IoEngine with multi-threaded EventLoop, unified Pipeline + Coroutine
mode via `EpollPipelinedChannel`, and zero-copy I/O via `IoBuf.unsafePointer`.
