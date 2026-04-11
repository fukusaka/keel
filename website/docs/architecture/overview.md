---
sidebar_position: 1
---

# Architecture Overview

## Layer Structure

```
Your App / Ktor DSL
        ↑
Codec   (keel-codec-http, keel-codec-websocket)   ← optional
TLS     (keel-tls-jsse · keel-tls-openssl · …)    ← optional
        ↑
  StreamEngine  ← keel's unified interface
        ↑
epoll │ io_uring │ kqueue │ NWConnection │ NIO │ Netty │ Node
```

`StreamEngine` is keel's single abstraction over platform I/O. Everything above it — TLS, codecs, Ktor integration — is optional and engine-independent: it works identically regardless of which engine is on the classpath.

`StreamEngine` is a sub-interface of `IoEngine` — the root interface that holds engine configuration (`IoEngineConfig`) and lifecycle (`close()`). This hierarchy leaves room for a future `DatagramEngine` sibling interface for UDP. Both will share the same `IoEngine` foundation without affecting the existing TCP API.

```
IoEngine  (config + close)
├── StreamEngine    ← TCP / byte-stream  (current)
└── DatagramEngine  ← UDP               (planned)
```

### keel vs Netty vs Ktor

| Library | Role |
|---|---|
| **keel** | Transport I/O engine — drives epoll / kqueue / io_uring / NWConnection / NIO / Netty / Node.js from a single KMP interface |
| **Netty** | JVM-only I/O framework — `keel-engine-netty` delegates to it on JVM; on Native, keel replaces it entirely |
| **Ktor** | Web framework — routing, plugins, serialization. `keel-ktor-engine` plugs keel in as Ktor's I/O backend. Ktor's built-in CIO engine lacks TLS support on Native targets — keel fills this gap |

## Two I/O Modes

Applications interact with `StreamEngine` in one of two ways:

**Coroutine mode** (`engine.bind()` + `server.accept()`)  
`engine.bind()` runs synchronously on the caller thread and returns a `Server`. `server.accept()` is a `suspend fun` that resumes when the EventLoop detects a new connection. Once accepted, `val buf = channel.read()` suspends until data arrives — code reads sequentially without blocking a thread. Integrates naturally with Ktor and all kotlinx coroutines primitives. Tradeoff: a coroutine context switch on each `read()` resume.

**Pipeline mode** (`engine.bindPipeline()`)  
The engine calls handlers directly on the EventLoop thread — no suspend, no context switch. When a socket becomes readable, the EventLoop reads data into `IoBuf` and fires the inbound event on `ChannelPipeline`: data flows synchronously through handlers (TLS → decoder → user handler → encoder) without leaving the EventLoop thread. Tradeoff: handlers must not block the EventLoop thread.

Both modes are available on all 7 engines. Pipeline mode achieves roughly 1.5× the throughput of Coroutine mode — a difference that reflects the coroutine context-switch cost.

See [Coroutine Mode](./coroutine.md) and [Pipeline Mode](./pipeline.md) for details.

## Thread Model

An **EventLoop** is a single-threaded loop that monitors I/O events and dispatches them to coroutines or handlers. epoll and kqueue notify when a file descriptor is ready for I/O (`epoll_wait` / `kevent`); NIO uses `Selector.select()`. io_uring uses a completion-based model — I/O operations are submitted to the kernel, which executes them asynchronously and reports results via a completion queue (CQE) rather than readiness notifications. In all cases, each EventLoop thread owns its event monitoring and task queue exclusively. A channel is bound to one EventLoop for its lifetime, so all events and handlers for that channel execute on the same thread — no locking is needed for channel-level operations.

keel runs one EventLoop per thread. The thread count is set via `IoEngineConfig(threads = N)` — `0` (default) means one thread per CPU core.

**Coroutine mode**: the EventLoop resumes the suspended coroutine when I/O is ready (epoll, kqueue, NIO) or complete (io_uring). Where the coroutine then runs depends on the target:

- **Native engines (epoll, kqueue, io_uring)** — the coroutine runs directly on the EventLoop thread. The EventLoop is also the coroutine executor; no cross-thread handoff occurs.
- **NIO (JVM)** — the EventLoop thread handles I/O notification, then dispatches coroutine resumption to `Dispatchers.Default`. On JVM, separating I/O monitoring from coroutine execution lets the work-stealing thread pool schedule coroutines across all available cores independently of the EventLoop.

**Pipeline mode**: the EventLoop invokes handler chains synchronously on its own thread. No coroutine and no cross-thread dispatch — all handler code runs on the EventLoop thread.

The behavior above applies to keel's own engine implementations (epoll, kqueue, io_uring, NIO). Three engines use their own threading model and ignore `IoEngineConfig(threads)`:

- **`keel-engine-netty`** — delegates to Netty's `NioEventLoopGroup` / `EpollEventLoopGroup`. Netty receives `threads = 0` and resolves it to its own default (CPU cores × 2). All I/O operations including `bind()` are submitted to Netty's EventLoop queue, whereas keel's Native engines run `bind()` directly on the caller thread.
- **`keel-engine-nwconnection`** — uses GCD `dispatch_queue` per connection. Thread management is delegated to the OS.
- **`keel-engine-nodejs`** — delegates to the Node.js / V8 runtime EventLoop.

## KMP Targets

| Platform | Target | KMP Tier | Engine | keel Status |
|---|---|---|---|---|
| JVM | JVM | Stable | NIO / Netty | ✅ |
| macOS | macosArm64 / macosX64 | Native Tier 1 | kqueue / NWConnection | ✅ |
| iOS | iosArm64 / iosSimulatorArm64 | Native Tier 1 | NWConnection | 🔲 Planned |
| JS (Node.js) | nodejs() | Stable | Node.js net/tls | ✅ |
| Linux | linuxX64 / linuxArm64 | Native Tier 2 | epoll / io_uring | ✅ |
| watchOS / tvOS | watchosArm64 / tvosArm64 (etc.) | Native Tier 2 | — | Not targeted |
| Windows | mingwX64 | Native Tier 3 | — | Deferred |
| Android Native | androidNativeArm64 / Arm32 / X64 / X86 | Native Tier 3 | — | Not targeted |
| JS (Browser) | browser() | Stable | — | Out of scope |
| Wasm/JS | wasmJs | Beta | — | Out of scope |
| Wasm/WASI | wasmWasi | Beta | — | Blocked |

**Android**: Android apps run on the JVM, so use the JVM target (`jvm()`) with `keel-engine-nio` or `keel-engine-netty`. `androidNativeArm64` and its siblings are bare-metal native targets unrelated to the Android SDK — keel does not target these.

**iOS**: shares the NWConnection engine with macOS, so the implementation itself is ready. App Sandbox prevents server sockets — iOS support is limited to client use. TLS is fixed to Network.framework; no `keel-tls-*` module is available.

**Windows**: requires a separate engine for WSAPoll / IOCP and is deferred to a later phase.

**JS (Browser) / Wasm/JS**: browser sandbox does not allow raw TCP sockets.

**Wasm/WASI**: TCP requires `wasi-sockets` (WASI 0.2). Blocked on KT-64568 (WASI 0.2 migration) and KT-64569 (Component Model). Once resolved and `kotlinx-coroutines` wasmWasi support stabilizes, adding the target becomes feasible.

## TLS Strategy

keel's TLS has two integration modes:

**Per-connection TLS** (kqueue, epoll, io_uring, NIO, Netty) — A `TlsHandler` is installed in the `ChannelPipeline`, encrypting and decrypting each `IoBuf` using the chosen TLS backend. For `keel-engine-netty`, TLS can be configured via `keel-tls-jsse` or via the built-in `NettySslInstaller` (Netty's own `SslHandler`) — no `keel-tls-*` module required in the latter case.

**Listener-level TLS** (NWConnection, Node.js) — TLS is handled by the OS or runtime before data reaches keel. No `keel-tls-*` module is needed.

| Platform | Backend | Module |
|---|---|---|
| JVM (NIO) | JSSE (JDK SSLContext) | `keel-tls-jsse` |
| JVM (Netty) ¹ | Netty SslHandler | built into `keel-engine-netty` |
| JVM (Netty) ¹ | JSSE (JDK SSLContext) | `keel-tls-jsse` |
| Native (Linux/macOS) | OpenSSL | `keel-tls-openssl` |
| Native (Linux/macOS) | Mbed TLS | `keel-tls-mbedtls` |
| Native (Linux/macOS) | AWS-LC | `keel-tls-awslc` |
| macOS / iOS (NWConnection) | Network.framework (listener-level) | `keel-engine-nwconnection` |
| JS (Node.js) | Node.js tls (listener-level) | `keel-engine-nodejs` |

¹ `keel-engine-netty` supports both TLS options — choose one per deployment.

See [TLS](./tls.md) for details.

## Design Principles

**Thread-confined channel execution — lock-free by design**  
Each channel is bound to one EventLoop for its lifetime. All I/O events, handler invocations, and state mutations for that channel execute on the same thread. There is intentionally no locking on channel-level state — the thread confinement guarantee makes it unnecessary. Code that looks lock-free in keel's channel and pipeline implementation is correct by design, not by accident. Lock-based concurrency on the I/O hot path would add latency and create contention as connection count scales; thread confinement eliminates both.

**Engine-independent codecs**  
Codec modules do not depend on any `keel-engine-*` module. `keel-codec-websocket` requires only `kotlinx.io`. `keel-codec-http` additionally requires `keel-io` (for `SuspendSource` / `SuspendSink`). Neither knows which engine is running underneath. If codecs depended on engine internals, each of the 7 engines would need its own codec implementation and unit tests would require a running engine. Instead, a single codec implementation serves all engines and tests run against a plain in-memory `Buffer` with no engine involved.

The layer boundary is intentional: codecs operate at request granularity, so kotlinx-io's GC-managed `Buffer` is acceptable. Engines operate at packet granularity (every recv syscall), so `IoBuf` + `BufferAllocator` gives complete allocation control and keeps GC off the I/O hot path.

**`IoBuf` — platform-native memory for zero-copy I/O**  
`IoBuf` uses platform-native memory on each target: `nativeHeap` on Native, `ByteBuffer.allocateDirect` on JVM, and `Int8Array` (V8-managed) on JS. Engine implementations pass `IoBuf.unsafePointer` (Native) or `IoBuf.unsafeBuffer` (JVM) directly to OS read/write syscalls — eliminating any copy between the OS buffer and the application heap on the I/O hot path. Without native memory, the JVM would silently copy data to a temporary native buffer on every syscall because GC-managed heap objects can be relocated and their addresses cannot be passed to the OS directly.

**Pluggable components**  
keel's major components are all swappable without changing application code. Different deployment contexts require different implementations: a security-constrained environment may mandate a specific TLS library, a debug session needs leak detection that would be unacceptable overhead in production, and an embedded deployment may want a minimal allocator. Separating these concerns from application code means the same business logic deploys across all contexts unchanged.

- **I/O engine** — compile-time selection via `keel-engine-*` Gradle dependency
- **TLS backend** — optional `keel-tls-*` dependency (or engine built-in TLS for Netty, NWConnection, Node.js)
- **Buffer allocator** — `BufferAllocator` injected at engine construction via `IoEngineConfig`. `SlabAllocator` (Native) and `PooledDirectAllocator` (JVM) are used in production; `TrackingAllocator` and `LeakDetectingAllocator` can be swapped in to debug buffer lifecycle issues
- **Logger** — `LoggerFactory` injected via `IoEngineConfig.loggerFactory`. Defaults to no-op; `PrintLogger` is available for development, and `KtorLoggerAdapter` bridges to Ktor's logger when using `keel-ktor-engine`
