# Module keel-engine-io-uring

Linux io_uring-based IoEngine implementation with multishot accept/recv and provided buffer ring.

Provides both **Pipeline mode** (zero-suspend, zero-allocation multishot recv) and
**Coroutine mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): engine drives I/O via callbacks. Handlers
process data synchronously on the EventLoop thread — zero suspend overhead.
Used for high-performance HTTP servers.

**Coroutine mode** (`bind`/`connect`): app drives I/O via suspend
`read()`/`write()`/`flush()`. A `SuspendBridgeHandler` bridges pipeline
callbacks to suspend. Used for interactive protocols (SMTP, Redis) and Ktor.

**Pipeline direction**: inbound (read) flows HEAD → TAIL; outbound (write/flush)
flows TAIL → HEAD. `HeadHandler` connects the pipeline to `IoUringIoTransport`.

## Architecture

Each worker runs its own io_uring ring on a dedicated pthread and serves as
a `CoroutineDispatcher`. The EventLoop drives I/O via `io_uring_submit_and_wait`
in a single loop: drain tasks → submit SQEs + wait for CQEs → process completions.

The two modes use different accept models:

**Coroutine mode** (`bind`): a boss EventLoop accepts via `IORING_OP_ACCEPT`
(multishot on Linux 5.19+) and distributes connections to workers in round-robin.

```
IoUringEngine (Coroutine mode)
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
to the kernel ring for reuse. Under ring pressure the consumer may instead
receive an allocator-owned copy so the slot can return to the ring immediately
(copy-on-pressure).

The `armRecv()` CQE callback calls `pipeline.notifyRead(buf)` to enter the
pipeline at HEAD.

**Pipeline**: `armRecv()` is called immediately after pipeline initialization.

```
EventLoop CQE callback (armRecv)
  → RingBufferIoBuf.reset()
    → pipeline.notifyRead(buf)
      → handler chain (Decoder → Router → ...)
```

**Coroutine**: `armRecv()` is called lazily on the first `channel.read()` via
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
terminates the multishot recv with `-ENOBUFS`. The channel re-arms immediately
if the ring has buffers available; if the ring is genuinely empty, the re-arm
is deferred until a buffer is returned (avoiding an ENOBUFS busy-loop). TCP
flow control prevents data loss either way.

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

**Coroutine**: the app enters the pipeline at TAIL via `pipeline.requestWrite/requestFlush`.

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

`IoTransport.flush()` is fire-and-forget. The adaptive `IoModeSelector` toggles
between `FALLBACK_CQE` and `CQE` per connection based on `ConnectionStats`; the
zero-copy modes are manual opt-ins, not part of the adaptive strategies:

| Mode | io_uring operation | Strategy |
|------|-------------------|----------|
| `FALLBACK_CQE` | POSIX `send()` → `IORING_OP_SEND` on EAGAIN | Direct syscall first; SQE fallback. Low-latency default |
| `CQE` | `IORING_OP_SEND` (single) / `IORING_OP_WRITEV` (gather) | All I/O via SQE/CQE. Gather write batches multiple buffers in one SQE |
| `SEND_ZC` | `IORING_OP_SEND_ZC` | Zero-copy: kernel sends from user-space memory. Two CQEs per send (result + buffer release). Manual selection only |
| `SENDMSG_ZC` | `IORING_OP_SENDMSG_ZC` | Zero-copy gather send (Linux 6.1+). Manual selection only |

**Partial send handling**: when a send completes partially (`res < len`), the
remainder is retried via sequential callback chain — each SQE is submitted after
the previous CQE completes. Multiple pending buffers on EAGAIN (FALLBACK_CQE)
are also chained sequentially to preserve TCP byte-stream order.

`ConnectionStats` tracks per-connection EAGAIN rate (EMA) for adaptive switching
via `IoModeSelectors.eagainThreshold()`.

## Key Classes

| Class | Role |
|-------|------|
| `IoUringEngine` | `StreamEngine` implementation. Creates boss + worker EventLoops |
| `IoUringPipelinedChannel` | Unified channel: Pipeline + Coroutine modes |
| `IoUringPipelinedStreamServer` | Pipeline-mode server (SO_REUSEPORT, multishot accept) |
| `IoUringStreamServer` | Coroutine-mode server (suspend-based accept) |
| `IoUringIoTransport` | `IoTransport` for write/flush with adaptive mode selection |
| `IoUringEventLoop` | Single-threaded io_uring loop + `CoroutineDispatcher` |
| `IoUringEventLoopGroup` | Round-robin + per-worker `ProvidedBufferRing` |
| `ProvidedBufferRing` | Kernel-managed buffer pool for multishot recv |
| `RingBufferIoBuf` | Zero-allocation `IoBuf` wrapper over provided buffers |
| `IoUringCapabilities` | Runtime kernel feature detection (multishot, sendZc, etc.) |
| `KernelVersion` | Kernel version via `uname(2)` for feature gating |
| `IoMode` | Flush strategy enum: CQE, FALLBACK_CQE, SEND_ZC, SENDMSG_ZC |
| `IoModeSelector` | Per-connection strategy selection based on `ConnectionStats` |
| `ConnectionStats` | Per-connection EAGAIN rate (EMA) for adaptive mode switching |
| `RegisteredBufferStrategy` | Registered ("fixed") buffer strategy enum, configured via the `IoUringEngine` constructor |
| `IoUringSyscallOps` / `IoUringRing` / `IoUringProbe` | Seams over the io_uring syscalls, ring lifecycle, and opcode probing — error branches testable without a real kernel |
| `FixedFileRegistry` | Per-EventLoop registered-fd (fixed file) table with deferred on-loop kernel registration |

Socket lifecycle helpers (bind/listen, non-blocking connect, address queries) come
from the shared `keel-native-posix` module (`NativeSocketOps` / `NativeSocket`).

## Kernel Version Requirements

**Hard floor: Linux 5.6** (`IORING_OP_SEND` / `IORING_OP_RECV`) — enforced at
engine construction; older kernels should use the epoll engine. Every feature
above the floor degrades behind its own capability gate:

| Feature | Kernel | Fallback below it |
|---------|--------|-------------------|
| Single-shot accept / recv / send | 5.6+ (floor) | — (construction fails fast below 5.6) |
| Multishot accept | 5.19+ | single-shot accept re-armed per connection (both server modes) |
| Provided buffer ring | 5.19+ | single-shot recv into an allocator-owned buffer, re-armed per CQE |
| Multishot recv | 6.0+ | single-shot buffer-select recv re-armed per CQE (ring still used) |
| SEND_ZC | 6.0+ | regular send (opcode-probed; `IoMode.SEND_ZC` is optional) |
| SENDMSG_ZC | 6.1+ | regular gather send (opcode-probed; `IoMode.SENDMSG_ZC` is optional) |

`IoUringCapabilities` probes the running kernel and selects the appropriate tier.

# Package io.github.fukusaka.keel.engine.iouring

Linux io_uring-based IoEngine with multi-threaded EventLoop, unified Pipeline + Coroutine
mode via `IoUringPipelinedChannel`, multishot recv with provided buffer ring, and
adaptive write mode selection (direct send / CQE, with manual zero-copy send modes).
