# Module engine-io-uring

Linux io_uring-based IoEngine implementation with multishot accept/recv and provided buffer ring.

Provides both **Pipeline mode** (zero-suspend, zero-allocation multishot recv) and
**Channel mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Architecture

```
IoUringEngine
├── bossLoop (IoUringEventLoop) ── accept via IORING_OP_ACCEPT
│     └── multishot accept → clientFd → assign to worker
└── workerGroup (IoUringEventLoopGroup, N workers)
      ├── worker[0]: IoUringPipelinedChannel A, D, ...
      │     └── ProvidedBufferRing (shared per worker)
      ├── worker[1]: IoUringPipelinedChannel B, E, ...
      └── worker[N]: ...
```

Each worker runs its own io_uring ring on a dedicated pthread. Channels are
assigned in round-robin order. The EventLoop also serves as a `CoroutineDispatcher`.

**Multishot accept** (Linux 5.19+): a single SQE produces multiple CQEs — one
per accepted connection — eliminating per-accept SQE resubmission overhead.

**SO_REUSEPORT** (Pipeline mode): each worker owns a private server socket.
The kernel distributes connections by 4-tuple hash — no boss EventLoop bottleneck.

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): multishot recv with provided buffer ring.
Data is delivered directly to the pipeline via pre-allocated `RingBufferIoBuf`
wrappers — zero allocation on the hot path.

```
io_uring CQE (multishot recv)
  → RingBufferIoBuf.reset() → pipeline.notifyRead(buf)
    → TlsHandler → Decoder → Router → Encoder → IoTransport
```

**Channel mode** (`bind`/`connect`): a `SuspendBridgeHandler` is lazily installed.
Multishot recv pushes data into the bridge queue; app calls suspend `read()`.

```
io_uring CQE (multishot recv)
  → RingBufferIoBuf → pipeline.notifyRead(buf)
    → SuspendBridgeHandler.onRead → queue
                                      ↓
App:                      suspend channel.read(buf) ← dequeue
```

## I/O Mode Selection

Write/flush uses adaptive mode selection per connection via `IoModeSelector`:

| Mode | Strategy | Best for |
|------|----------|----------|
| `FALLBACK_CQE` | Direct `send()`, EAGAIN → SEND SQE | Low-latency (default) |
| `CQE` | Always via SEND SQE/CQE | High-throughput, backpressure |
| `SEND_ZC` | `IORING_OP_SEND_ZC` (zero-copy, two CQEs) | Large payloads |

`ConnectionStats` tracks per-connection EAGAIN rate (EMA) for adaptive switching.

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

# Package io.github.fukusaka.keel.engine.iouring

Linux io_uring-based IoEngine with multi-threaded EventLoop, unified Pipeline + Channel
mode via `IoUringPipelinedChannel`, multishot recv with provided buffer ring, and
adaptive write mode selection (direct send / CQE / SEND_ZC).
