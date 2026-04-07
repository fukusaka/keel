# Module keel-engine-io-uring

Linux io_uring-based IoEngine implementation with multishot accept/recv and provided buffer ring.

Provides both **Pipeline mode** (zero-suspend, zero-allocation multishot recv) and
**Channel mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): engine drives I/O via callbacks. Handlers
process data synchronously on the EventLoop thread — zero suspend overhead.
Used for high-performance HTTP servers.

**Channel mode** (`bind`/`connect`): app drives I/O via suspend
`read()`/`write()`/`flush()`. A `SuspendBridgeHandler` bridges pipeline
callbacks to suspend. Used for interactive protocols (SMTP, Redis) and Ktor.

**Pipeline direction**: inbound (read) flows HEAD → TAIL; outbound (write/flush)
flows TAIL → HEAD. `HeadHandler` connects the pipeline to `IoUringIoTransport`.

## Architecture

Each worker runs its own io_uring ring on a dedicated pthread and serves as
a `CoroutineDispatcher`. The EventLoop drives I/O via `io_uring_submit_and_wait`
in a single loop: drain tasks → submit SQEs + wait for CQEs → process completions.

The two modes use different accept models:

**Channel mode** (`bind`): a boss EventLoop accepts via `IORING_OP_ACCEPT`
(multishot on Linux 5.19+) and distributes connections to workers in round-robin.

```
IoUringEngine (Channel mode)
├── bossLoop (IoUringEventLoop)
│     └── IORING_OP_ACCEPT (multishot) → clientFd → assign to worker
└── workerGroup (N workers, round-robin)
      ├── worker[0]: IoUringPipelinedChannel A, D, ...
      │     └── ProvidedBufferRing (shared per worker)
      ├── worker[1]: IoUringPipelinedChannel B, E, ...
      └── worker[N]: ...
```

**Pipeline mode** (`bindPipeline`): each worker owns a private server socket
with `SO_REUSEPORT`. The kernel distributes connections by 4-tuple hash —
no boss EventLoop bottleneck.

```
IoUringEngine (Pipeline mode)
└── workerGroup (N workers, SO_REUSEPORT)
      ├── worker[0]: serverFd[0] + ProvidedBufferRing
      │     └── IORING_OP_ACCEPT (multishot) → IoUringPipelinedChannel ...
      ├── worker[1]: serverFd[1] + ProvidedBufferRing
      └── worker[N]: ...
```

**Client connect** (`connect`): uses `IORING_OP_CONNECT` to establish a TCP
connection asynchronously via io_uring (no POSIX `connect()` + EPOLLOUT).

## Read Path

Both modes use **multishot recv** with a **provided buffer ring**: a single
`IORING_OP_RECV` SQE with `IOSQE_BUFFER_SELECT` produces one CQE per incoming
data segment. The kernel selects a buffer from the `ProvidedBufferRing` for each
CQE — no per-read SQE resubmission or buffer allocation.

`RingBufferIoBuf` wrappers are pre-allocated (one per buffer slot) and reused
via `reset()` on each CQE. When the handler releases the buffer, it is returned
to the kernel ring for reuse.

The `armRecv()` CQE callback calls `pipeline.notifyRead(buf)` to enter the
pipeline at HEAD.

**Pipeline**: `armRecv()` is called immediately after pipeline initialization.

```
EventLoop CQE callback (armRecv)
  → RingBufferIoBuf.reset()
    → pipeline.notifyRead(buf)
      → handler chain (Decoder → Router → ...)
```

**Channel**: `armRecv()` is called lazily on the first `channel.read()` via
`ensureBridge()`. Data is copied from `RingBufferIoBuf` to the caller's `IoBuf`
via `IoBuf.copyTo`.

```
EventLoop CQE callback (armRecv)
  → RingBufferIoBuf
    → pipeline.notifyRead(buf)
      → SuspendBridgeHandler.onRead → queue
                                        ↓
App:                  suspend channel.read(buf) ← IoBuf.copyTo
```

**ENOBUFS handling**: when all provided buffers are consumed, the kernel
terminates the multishot recv with `-ENOBUFS`. The channel re-arms immediately;
TCP flow control prevents data loss.

## Write/Flush Path

Both modes share the same outbound path. The pipeline terminates at
`HeadHandler`, which delegates to `IoUringIoTransport` for the actual I/O.

**Pipeline**: a handler calls `ctx.propagateWrite/Flush` to push data toward HEAD.

```
handler (e.g. Encoder)
  → ctx.propagateWrite(buf)
    → HeadHandler.onWrite → IoTransport.write(buf)
  → ctx.propagateFlush()
    → HeadHandler.onFlush → IoTransport.flush()
```

**Channel**: the app enters the pipeline at TAIL via `pipeline.requestWrite/requestFlush`.

```
App: channel.write(buf)
  → pipeline.requestWrite(buf)
    → ... → HeadHandler.onWrite → IoTransport.write(buf)

App: channel.requestFlush()
  → pipeline.requestFlush()
    → ... → HeadHandler.onFlush → IoTransport.flush()

App: channel.awaitFlushComplete()
  → transport.awaitPendingFlush()
```

`IoTransport.flush()` is fire-and-forget. `IoModeSelector` selects the
io_uring operation per connection based on `ConnectionStats`:

| Mode | io_uring operation | Strategy |
|------|-------------------|----------|
| `FALLBACK_CQE` | POSIX `send()` → `IORING_OP_SEND` on EAGAIN | Direct syscall first; SQE fallback. Low-latency default |
| `CQE` | `IORING_OP_SEND` (single) / `IORING_OP_WRITEV` (gather) | All I/O via SQE/CQE. Gather write batches multiple buffers in one SQE |
| `SEND_ZC` | `IORING_OP_SEND_ZC` | Zero-copy: kernel sends from user-space memory. Two CQEs per send (result + buffer release) |

**Partial send handling**: when a send completes partially (`res < len`), the
remainder is retried via sequential callback chain — each SQE is submitted after
the previous CQE completes. Multiple pending buffers on EAGAIN (FALLBACK_CQE)
are also chained sequentially to preserve TCP byte-stream order.

`ConnectionStats` tracks per-connection EAGAIN rate (EMA) for adaptive switching
via `IoModeSelectors.eagainThreshold()`.

## Key Classes

| Class | Role |
|-------|------|
| `IoUringEngine` | `IoEngine` implementation. Creates boss + worker EventLoops |
| `IoUringPipelinedChannel` | Unified channel: Pipeline + Channel + PushChannel |
| `IoUringPipelinedServerChannel` | Pipeline-mode server (SO_REUSEPORT, multishot accept) |
| `IoUringServerChannel` | Channel-mode server (suspend-based accept) |
| `IoUringIoTransport` | `IoTransport` for write/flush with adaptive mode selection |
| `IoUringEventLoop` | Single-threaded io_uring loop + `CoroutineDispatcher` |
| `IoUringEventLoopGroup` | Round-robin + per-worker `ProvidedBufferRing` |
| `ProvidedBufferRing` | Kernel-managed buffer pool for multishot recv |
| `RingBufferIoBuf` | Zero-allocation `IoBuf` wrapper over provided buffers |
| `IoUringCapabilities` | Runtime kernel feature detection (multishot, sendZc, etc.) |
| `KernelVersion` | Parses `/proc/version` for feature gating |
| `IoMode` | Flush strategy enum: CQE, FALLBACK_CQE, SEND_ZC |
| `IoModeSelector` | Per-connection strategy selection based on `ConnectionStats` |
| `ConnectionStats` | Per-connection EAGAIN rate (EMA) for adaptive mode switching |
| `SocketUtils` | POSIX socket helpers (bind, connect, address conversion) |

## Kernel Version Requirements

| Feature | Kernel | Used by |
|---------|--------|---------|
| io_uring basic | 5.1+ | All operations |
| Multishot accept | 5.19+ | `IoUringServerChannel`, `IoUringPipelinedServerChannel` |
| Provided buffer ring | 5.19+ | `ProvidedBufferRing` for multishot recv |
| Multishot recv | 6.0+ | `armRecv()` in `IoUringPipelinedChannel` |
| SEND_ZC | 6.0+ | `IoMode.SEND_ZC` (optional, auto-detected) |

`IoUringCapabilities` probes the running kernel and enables features gracefully.

# Package io.github.fukusaka.keel.engine.iouring

Linux io_uring-based IoEngine with multi-threaded EventLoop, unified Pipeline + Channel
mode via `IoUringPipelinedChannel`, multishot recv with provided buffer ring, and
adaptive write mode selection (direct send / CQE / SEND_ZC).
