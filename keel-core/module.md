# Module keel-core

Core I/O engine interfaces, channel pipeline framework, and logging facade.
Engine modules implement these interfaces; codec and application code depend on them.

## I/O Engine Interfaces

`IoEngine` is the root interface for all keel I/O engines (lifecycle: `close()`).
It is also a `CoroutineScope` whose context carries a `SupervisorJob` but — by
documented invariant — **no default dispatcher**: callers launching on the engine
must pass an explicit dispatcher (typically `channel.ioDispatcher`), otherwise the
coroutine silently falls back to `Dispatchers.Default`.
`StreamEngine : IoEngine` extends it with TCP byte-stream operations.
Engine modules implement `StreamEngine` for each platform:

```
Application
      |
  StreamEngine : IoEngine  (bind / connect)
      |
  +---+---+---+---+---+---+---+
  |   |   |   |   |   |   |   |
 kq  ep  nio net  nw  js  uring
```

- `bind(host, port)` — server socket + listen; returns `StreamServer` (Coroutine mode)
- `bindPipeline(host, port, init)` — server socket + listen; drives I/O via callbacks (Pipeline mode)
- `connect(host, port)` — outbound TCP connection; returns `Channel`

## Channel

`Channel` represents a single bidirectional TCP connection. Key design points:

- **Write/flush separation**: `write()` buffers; `flush()` sends. Enables writev/gather-write batching.
- **Zero-copy I/O**: `read(IoBuf)` / `write(IoBuf)` pass `unsafePointer` (Native) or `unsafeBuffer` (JVM) directly to OS syscalls.
- **Buffer ownership (transfer for writes, non-transfer for reads)**: `write(buf)` takes over the caller's reference and releases it after flush completes — the caller must not touch `buf` after the call. `read(buf)` is the inverse: the caller allocates, the engine fills, the caller releases. To keep a reference alive across a write (e.g., fan-out), call `IoBuf.retain()` before passing the buffer in. See `website/docs/architecture/buffer.md`.
- **Half-close**: `shutdownOutput()` sends TCP FIN; input remains open.
- **Stream bridge**: `asSuspendSource()` / `asSuspendSink()` expose the channel as keel's own suspending `SuspendSource` / `SuspendSink` streams (IoBuf-based, kotlinx-io independent).
- **`ioDispatcher`**: returns the engine's EventLoop dispatcher. I/O + processing run on the same thread — no cross-thread dispatch overhead.

## Pipeline Framework

`Pipeline` is an ordered handler chain for protocol processing.

**Inbound events** (data received, connection lifecycle) flow HEAD → TAIL.
**Outbound operations** (write, flush) flow TAIL → HEAD.

```
HEAD ↔ [Encoder] ↔ [Decoder] ↔ [UserHandler] ↔ TAIL

Inbound:  HEAD → (encoder skipped) → Decoder → UserHandler
Outbound: UserHandler → (decoder skipped) → Encoder → HEAD
```

**`addLast` order**: outbound handlers must be added before inbound handlers
so that writes from `UserHandler` reach encoders on the way to HEAD.

**Type chain validation**: handlers declare `acceptedType` and `producedType`;
the pipeline validates adjacent handlers have compatible types at `addLast` time.

**`SuspendBridgeHandler`**: bridges pipeline callbacks to suspend `read()`/`write()`.
Installed automatically on the first `channel.read()` call (Coroutine mode).

## IoEngineConfig

Configuration shared by all engines:

| Property | Default | Description |
|----------|---------|-------------|
| `allocator` | `defaultAllocator()` | Root buffer allocator (platform pooled allocator — Native: `SlabAllocator`, JVM: `PooledDirectAllocator`, JS: `DefaultAllocator`); engines derive per-EventLoop child allocators from it. The epoll and kqueue engines also ask it once while being built (one allocation and one release; `requireNativePointerAccess` records what that costs) whether the buffers they would read into carry a native pointer, and refuse to start when they do not |
| `threads` | 0 (auto) | Worker EventLoop thread count; 0 resolves per engine (typically `availableProcessors()`) |
| `loggerFactory` | `NoopLoggerFactory` | Logging factory |
| `resolver` | `DnsResolver.SYSTEM` | DNS resolver used when `bind` / `connect` receives an unresolved hostname; swap in `CachingDnsResolver` or a custom implementation |
| `idleReadPolicy` | `IdleReadPolicy.DETECT_PEER_CLOSE` | Peer-close detection vs TCP back-pressure trade-off for the idle-read window (consulted only by engines with the structural constraint) |
| `readBufferSize` | 8 KiB | Engine-wide default per-read buffer size for pull-model engines; must be a power of two; overridable per server (`BindConfig`) / per client (`ConnectConfig`) |
| `idleTimeoutMillis` | 0 (disabled) | Per-connection no-progress timeout (slowloris / stalled-peer defence); overridable per server / per client |
| `flushCoalescing` | `true` | Coalesce same-tick flushes into a single gathered send (writev / batched send) |

Per-server settings live in `BindConfig` (backlog, `childSocketOptions`, read buffer /
idle-timeout overrides, TLS-capable subclasses); per-client settings in `ConnectConfig`
(`socketOptions` plus the same overrides). `BindSpec` pairs a `SocketAddress` with a
`BindConfig` for multi-address `bindPipeline` calls. `SocketOptions` carries the
per-socket options (TCP_NODELAY, SO_KEEPALIVE, SO_RCVBUF, SO_SNDBUF). `IpAddress`
(sealed `V4` / `V6`) models resolved addresses, including IPv6 scope ids.

## Key Classes and Interfaces

| Type | Package | Role |
|------|---------|------|
| `IoEngine` | `core` | Root interface for all keel I/O engines (lifecycle, `CoroutineScope`) |
| `StreamEngine` | `core` | TCP byte-stream engine: `bind` / `bindPipeline` / `connect` |
| `Channel` | `core` | Bidirectional TCP channel |
| `StreamServer` | `core` | Coroutine-mode server: suspend-based accept loop |
| `PipelinedStreamServer` | `pipeline` | Pipeline-mode server lifecycle |
| `SocketAddress` | `core` | Sealed address type: `InetSocketAddress` / `UnixSocketAddress` |
| `IpAddress` | `core` | Sealed resolved IP address: `V4` / `V6` (with scope id) |
| `DnsResolver` | `core` | Hostname resolution interface (`SystemDnsResolver`, `CachingDnsResolver`) |
| `IoEngineConfig` | `core` | Engine-wide configuration |
| `BindConfig` | `core` | Per-server bind configuration (backlog, child socket options, TLS-capable subclasses) |
| `BindSpec` | `core` | `(address, config)` pair for multi-address `bindPipeline` |
| `ConnectConfig` | `core` | Per-client connect configuration |
| `SocketOptions` | `core` | Per-socket options (TCP_NODELAY, SO_KEEPALIVE, buffer sizes) |
| `DeadlineScheduler` | `pipeline` | EventLoop-local timer for idle/read deadlines (O(1) refresh) |
| `Pipeline` | `pipeline` | Handler chain interface |
| `Pipeline` | `pipeline` | Handler chain contract (the doubly-linked implementation is internal) |
| `PipelinedChannel` | `pipeline` | Channel with attached `Pipeline` |
| `IoTransport` | `pipeline` | Engine-to-pipeline bridge: read callbacks (`onRead`, `onReadClosed`, `readEnabled`), write/flush, lifecycle (`shutdownOutput`, `awaitClosed`), and properties (`allocator`, `isOpen`, `ioDispatcher`) |
| `AbstractIoTransport` | `pipeline` | Base `IoTransport` with write buffering, backpressure, and callback initialization |
| `AbstractPipelinedChannel` | `pipeline` | Base `PipelinedChannel` that wires `IoTransport` callbacks to the pipeline |
| `SuspendBridgeHandler` | `pipeline` | Pipeline-to-suspend bridge for raw `IoBuf` (Coroutine mode) |
| `SuspendMessageBridge<T>` | `pipeline` | Generic typed-message bridge: pipeline → suspendable `Channel<T>` |
| `TypedInboundHandler` | `pipeline` | Inbound handler with type-safe message dispatch |
| `Logger` / `LoggerFactory` | `logging` | Logging facade (no dependency on any logging library) |

# Package io.github.fukusaka.keel.core

`IoEngine`, `StreamEngine`, `Channel`, `StreamServer`, `IoEngineConfig`,
`BindConfig`, `BindSpec`, `ConnectConfig`, `SocketAddress`, `IpAddress`,
`SocketOptions`, `DnsResolver` — the public API for binding servers and
creating connections.

# Package io.github.fukusaka.keel.pipeline

`Pipeline`, `PipelinedChannel`, `IoTransport`,
`AbstractIoTransport`, `AbstractPipelinedChannel`, `SuspendBridgeHandler`,
and handler types (`InboundHandler` / `OutboundHandler` interfaces and the
`TypedInboundHandler` abstract base for type-safe message dispatch).

# Package io.github.fukusaka.keel.logging

`Logger`, `LoggerFactory`, `LogLevel`, and default implementations
(`NoopLoggerFactory`, `PrintLogger`).
