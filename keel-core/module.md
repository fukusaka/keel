# Module keel-core

Core I/O engine interfaces, channel pipeline framework, and logging facade.
Engine modules implement these interfaces; codec and application code depend on them.

## I/O Engine Interfaces

`IoEngine` is the root interface for all keel I/O engines (lifecycle: `close()`).
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

- `bind(host, port)` — server socket + listen; returns `Server` (Coroutine mode)
- `bindPipeline(host, port, init)` — server socket + listen; drives I/O via callbacks (Pipeline mode)
- `connect(host, port)` — outbound TCP connection; returns `Channel`

## Channel

`Channel` represents a single bidirectional TCP connection. Key design points:

- **Write/flush separation**: `write()` buffers; `flush()` sends. Enables writev/gather-write batching.
- **Zero-copy I/O**: `read(IoBuf)` / `write(IoBuf)` pass `unsafePointer` (Native) or `unsafeBuffer` (JVM) directly to OS syscalls.
- **Half-close**: `shutdownOutput()` sends TCP FIN; input remains open.
- **Codec bridge**: `asSuspendSource()` / `asSuspendSink()` expose the channel as kotlinx-io-compatible streams.
- **`coroutineDispatcher`**: returns the engine's EventLoop dispatcher. I/O + processing run on the same thread — no cross-thread dispatch overhead.

## Pipeline Framework

`ChannelPipeline` is an ordered handler chain for protocol processing.

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
| `threads` | 0 (cpu × 2) | Worker thread count |
| `allocator` | `DefaultBufferAllocator` | Buffer allocator |
| `loggerFactory` | `NoopLoggerFactory` | Logging factory |

## Key Classes and Interfaces

| Type | Package | Role |
|------|---------|------|
| `IoEngine` | `core` | Root interface for all keel I/O engines (lifecycle) |
| `StreamEngine` | `core` | TCP byte-stream engine: `bind` / `bindPipeline` / `connect` |
| `Channel` | `core` | Bidirectional TCP channel |
| `Server` | `core` | Coroutine-mode server: suspend-based accept loop |
| `PipelinedServer` | `core` | Pipeline-mode server lifecycle |
| `SocketAddress` | `core` | `(host, port)` tuple |
| `IoEngineConfig` | `core` | Engine-wide configuration |
| `BindConfig` | `core` | Per-server bind configuration (backlog, TLS initializer) |
| `ChannelPipeline` | `pipeline` | Handler chain interface |
| `DefaultChannelPipeline` | `pipeline` | Doubly-linked handler chain implementation |
| `PipelinedChannel` | `pipeline` | Channel with attached `ChannelPipeline` |
| `IoTransport` | `pipeline` | Engine-to-pipeline write/flush bridge |
| `SuspendBridgeHandler` | `pipeline` | Pipeline-to-suspend bridge for raw `IoBuf` (Coroutine mode) |
| `SuspendMessageBridge<T>` | `pipeline` | Generic typed-message bridge: pipeline → suspendable `Channel<T>` |
| `TypedChannelInboundHandler` | `pipeline` | Inbound handler with type-safe message dispatch |
| `Logger` / `LoggerFactory` | `logging` | Logging facade (no dependency on any logging library) |

# Package io.github.fukusaka.keel.core

`IoEngine`, `StreamEngine`, `Channel`, `Server`, `PipelinedServer`,
`IoEngineConfig`, `BindConfig`, `SocketAddress` — the public API for
binding servers and creating connections.

# Package io.github.fukusaka.keel.pipeline

`ChannelPipeline`, `DefaultChannelPipeline`, `PipelinedChannel`, `IoTransport`,
`SuspendBridgeHandler`, and handler interfaces (`ChannelInboundHandler`,
`ChannelOutboundHandler`, `TypedChannelInboundHandler`).

# Package io.github.fukusaka.keel.logging

`Logger`, `LoggerFactory`, `LogLevel`, and default implementations
(`NoopLoggerFactory`, `PrintLogger`).
