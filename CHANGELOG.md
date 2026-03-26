# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- `logging`: add logging module with `Logger`, `LoggerFactory`, `LogLevel`, `NoopLoggerFactory`, and `PrintLogger`
- `core`: add `loggerFactory` property to `IoEngineConfig` (defaults to `NoopLoggerFactory`)
- `ktor-engine`: add `KtorLoggerAdapter` to bridge Ktor Logger to keel `LoggerFactory`
- `io-core`: add `KeelEofException` as domain-specific base exception for unexpected EOF
- `codec-http`: add `HttpParseException` and `HttpEofException` for layered error handling
- `codec-http`: add status code range validation in `parseStatusLine` before `HttpStatus` construction

### Changed

- `io-core`: `BufferedSuspendSource.readByte()`/`readByteArray()` now throw `KeelEofException` instead of `IllegalStateException`
- `codec-http`: `HttpParser` throws `HttpEofException`/`HttpParseException` instead of `IllegalArgumentException`
- `codec-http`: `HttpVersion.of()` throws `HttpParseException` instead of `IllegalArgumentException`
- `ktor-engine`: catch specific `HttpEofException`/`HttpParseException` instead of generic `Exception`
- `ktor-engine`: respond with HTTP 400 Bad Request on malformed requests before closing connection

### Added

- `core`: tests for `SocketAddress`, `IoEngineConfig`, and `SuspendChannelSource`/`SuspendChannelSink` bridge
- Add detekt 1.23.8 static analysis with KMP-tuned configuration for all production modules
- `io-core`: add `TrackingAllocator` for allocate/release leak detection in tests
- CI: add detekt step (runs before compilation)
- `engine-kqueue`: TrackingAllocator leak detection tests (echo, large payload, connect)
- `codec-websocket`: replace Japanese comment with English in SHA-1 implementation
- `ktor-engine`: error handling tests (malformed request, client disconnect, empty request)
- `engine-kqueue`, `engine-epoll`: replace pthread_mutex with lock-free MPSC queue for coroutine dispatch
- `codec-http`: reduce alloc in suspend writeResponseHead (index-based headers, split string templates, remove trimEnd)
- `codec-websocket`: consolidate mask key byte extraction in WsFrameWriter
- `ktor-engine`: add accept error backoff strategy (Fixed / Exponential) via `Configuration.acceptBackoff`
- `ktor-engine`: concurrent keep-alive connection test (5 connections × 3 requests)
- `detekt-rules`: custom detekt rules for resource leak detection (NativeBufLeak, ArenaLeak, StableRefLeak)
- `engine-epoll`: TrackingAllocator leak detection tests (echo, large payload, connect)
- `engine-nio`: TrackingAllocator leak detection tests (echo, large payload, connect)
- `engine-kqueue`, `engine-epoll`: GC heap growth verification tests (100 echo cycles, 512KB tolerance)
- `engine-netty`: TrackingAllocator leak detection tests + re-enable detekt in lint-only mode
- `engine-nwconnection`: TrackingAllocator leak detection tests + GC heap growth test (50 echo cycles)
- `engine-nodejs`: asSuspendSource/asSuspendSink tests

### Fixed

- `engine-nio`: protect `processSelectedKeys` with try-catch so one channel's error does not stop other channels
- `engine-nodejs`: replace byte-by-byte read loop with bulk `writeBytes()` copy

- `io-core`: Native `NativeBuf.writeBytes()` with zero-length input no longer throws `ArrayIndexOutOfBoundsException` from `usePinned`
- `engine-kqueue`: log fatal `kevent()` error to stderr before EventLoop exit (previously silent)
- `engine-epoll`: log fatal `epoll_wait()` error to stderr before EventLoop exit (previously silent)

### Changed

- `engine-kqueue`: cache wakeup byte arrays to avoid per-dispatch allocation

## [0.2.0] - 2026-03-25

### Added

- `ktor-engine`: add keep-alive integration tests using raw sockets (multiple requests on same connection, `Connection: close`, `keepAlive=false` config)

### Fixed

- `io-core`: `NativeBuf.clear()` on JVM now resets DirectByteBuffer position/limit — fixes `IndexOutOfBoundsException` on keep-alive connections with large payloads
- `engine-nio`: `flush()` handles partial write with OP_WRITE suspension — fixes data loss on large payloads (100KB: 10 req/s → 25K req/s)
- `io-core`: `NativeBuf.writeBytes()` bulk copy (memcpy/ByteBuffer.put) replaces per-byte loop in `BufferedSuspendSink` — /large +22% (kqueue), +263% (epoll)
- `engine-nwconnection`: fix StableRef use-after-dispose crash when cancelling suspended I/O coroutines (read, write, connect, bind) — `CallbackContext` atomic flag ensures StableRef is always disposed by the C callback, not by `invokeOnCancellation`
- All engines: cancel pending `accept()` coroutine with `CancellationException` on `ServerChannel.close()` — previously the continuation was abandoned (Netty already handled this correctly)

### Changed

- `engine-kqueue`: make `KqueueEventLoop` a `CoroutineDispatcher` — I/O coroutines execute on the EventLoop thread, eliminating cross-thread dispatch overhead
- `engine-kqueue`: `KqueueChannel.coroutineDispatcher` now returns the EventLoop dispatcher instead of `Dispatchers.Default`
- `engine-epoll`: make `EpollEventLoop` a `CoroutineDispatcher` — I/O coroutines execute on the EventLoop thread, eliminating cross-thread dispatch overhead
- `engine-epoll`: `EpollChannel.coroutineDispatcher` now returns the EventLoop dispatcher instead of `Dispatchers.Default`
- `ktor-engine`: dispatch I/O on `channel.coroutineDispatcher` (EventLoop) and offload Ktor pipeline to `Dispatchers.Default`, eliminating cross-thread dispatch for read/parse
- `engine-kqueue`: handle EAGAIN and short write in `flush()` — suspend on EVFILT_WRITE and retry, preventing data loss under send buffer saturation
- `engine-epoll`: handle EAGAIN and short write in `flush()` — suspend on EPOLLOUT and retry, preventing data loss under send buffer saturation
- `engine-kqueue`: boss/worker EventLoop separation with `KqueueEventLoopGroup` — `IoEngineConfig.threads` controls worker count, round-robin channel assignment
- `engine-epoll`: boss/worker EventLoop separation with `EpollEventLoopGroup` — same pattern as kqueue and NIO
- `engine-kqueue`: non-blocking `connect()` with EINPROGRESS + EVFILT_WRITE suspend, replacing blocking connect
- `engine-epoll`: non-blocking `connect()` with EINPROGRESS + EPOLLOUT suspend, replacing blocking connect
- `engine-nio`: non-blocking `connect()` with OP_CONNECT suspend, replacing blocking connect
- `core`: `IoEngineConfig.threads` default changed from 1 to 0 (auto) — each engine resolves to `availableProcessors()` at construction
- Extract `io-core` module from `core` — NativeBuf, SuspendSource/Sink, BufferedSuspendSource/Sink, BufferAllocator moved to `io.github.fukusaka.keel.io` package. codec-http now depends on `io-core` only (engine-independent)
- `engine-nio`: cache SelectionKey and use `interestOps()` toggle instead of per-read `channel.register()` + `key.cancel()` — eliminates JNI re-registration overhead (2.4K → 121K req/s on macOS)
- Rename GitHub organization from `keel-kt` to `fukusaka` — the dedicated org was premature at this stage
- Update copyright holder from `The keel-kt Authors` to `fukusaka`
- `engine-netty`: replace blocking `LinkedBlockingQueue` I/O with `suspendCancellableCoroutine` + Netty listener callbacks (Phase 5b async)
- `engine-netty`: enable `autoRead=false` for pull-model semantics and TCP backpressure
- `engine-kqueue`: replace blocking kevent wait with async EventLoop + `suspendCancellableCoroutine` (Phase 5b async)
- `engine-kqueue`: add `KqueueEventLoop` with pipe wakeup and pthread-based event loop thread
- `engine-epoll`: replace blocking epoll_wait with async EventLoop + `suspendCancellableCoroutine` (Phase 5b async)
- `engine-epoll`: add `EpollEventLoop` with eventfd wakeup and pthread-based event loop thread
- `engine-nwconnection`: replace blocking `dispatch_semaphore_wait` with async C wrappers + `suspendCancellableCoroutine` (Phase 5b async)
- `engine-nwconnection`: replace `keel_nw_read`/`keel_nw_write`/`keel_nw_start_conn` with callback-based async versions
- `ktor-engine`: add HTTP/1.1 keep-alive support with configurable `keepAlive` setting (default: true)
- `codec-http`: add `isKeepAlive()` to `HttpRequestHead` for HTTP/1.1 Connection header semantics
- `core`: add `SuspendSource`/`SuspendSink` interfaces (NativeBuf-based, kotlinx-io independent)
- `core`: add `BufferedSuspendSource`/`BufferedSuspendSink` for zero-copy readLine/readByte/writeString
- `core`: add `NativeBuf.compact()` and `NativeBuf.clear()` for buffer reuse
- `core`: add `Channel.asSuspendSource()`/`Channel.asSuspendSink()` with default implementations
- `core`: deprecate `Channel.asSource()`/`Channel.asSink()` in favor of suspend variants
- `codec-http`: add suspend overloads for `parseRequestHead`/`writeResponseHead` using `BufferedSuspendSource`/`BufferedSuspendSink`
- `codec-http`: add `HttpHeaders.entries()` for suspend-compatible iteration
- `ktor-engine`: switch from `asSource()`/`asSink()` to `asSuspendSource()`/`asSuspendSink()` — eliminates `runBlocking` from I/O path
- `engine-nio`: replace blocking SocketChannel with non-blocking mode + Selector EventLoop (Phase 5b async)
- `engine-nio`: add `NioEventLoop` with Selector.wakeup and dedicated thread
- `engine-nio`: add `NioEventLoopGroup` for boss/worker model with round-robin channel assignment
- `engine-nio`: remove `ChannelSource`/`ChannelSink` — first engine fully migrated to `SuspendSource`/`SuspendSink`
- `core`: add `Channel.coroutineDispatcher` for engine-specific EventLoop dispatcher (default: `Dispatchers.Default`)
- `core`: add `kotlinx-coroutines-core` as `api` dependency in commonMain
- All engines: delete `ChannelSource`/`ChannelSink` and remove `asSource()`/`asSink()` from `Channel` interface
- `core`: remove kotlinx-io dependency — kotlinx-io is now confined to codec layer only (design.md §4.8)

## [0.1.0] - 2026-03-22

### Added

- `benchmark`: add Kotlin/Native engines to bench-all.sh (keel-kqueue, keel-nwconnection, ktor-cio on macOS; keel-epoll, ktor-cio on Linux)
- `benchmark`: add bench-one.sh for single-server benchmarking
- `benchmark`: add `writeClasspath` Gradle task for running JVM benchmark without Gradle process tree
- `README`: add benchmark results (macOS M1 + Linux 32-core) and update roadmap

### Changed

- `benchmark`: rename engine keys from `keel-*` to `ktor-keel-*` to clarify Ktor + keel combination
- `benchmark`: use classpath file instead of Gradle for JVM servers in bench-all.sh to fix signal handling
- `benchmark`: increment port per server to avoid TIME_WAIT conflicts
- `benchmark`: refactor file organization — split monolithic files into 1-engine-per-file pattern (JvmMain, NativeEngine.macos/linux)
- `benchmark`: move CioEngine from jvmMain/nativeMain to commonMain (ktor-server-cio is a KMP dependency)
- `benchmark`: split expect/actual declarations — `defaultEngine()` to EngineRegistry, `printErr()` to Platform
- `benchmark`: extract magic numbers to named constants (`DEFAULT_PORT`, `LARGE_PAYLOAD_SIZE`, `TUNED_BACKLOG`, `DEFAULT_MAX_CONTENT_LENGTH`)
- `benchmark`: make Netty raw `maxContentLength` configurable via `--max-content-length` CLI argument
- `benchmark`: add fallback/estimated indicators to show-config output for non-runtime-detected values
- `benchmark`: rename files and classes for consistency (`NettyRawBenchmark` → `NettyRawEngine`, `SpringBenchmark` → `SpringEngine`, etc.)

### Fixed

- `engine-netty`: replace `writeAndFlush().sync()` with batch write + `await(timeout)` to prevent Dispatchers.IO thread starvation under high concurrency
- `engine-nwconnection`: include `nw_error_get_error_code`/`nw_error_get_error_domain` in NWListener failure messages
- `ktor-engine`: always send `Connection: close` header in responses (Phase (a) has no keep-alive support; missing header caused HTTP clients to reuse connections and encounter unexpected EOF)

### Changed

- `engine-kqueue`: use `writev()` in flush for gather-write optimization (single syscall for multiple pending buffers via `keel_writev` C wrapper)
- `engine-epoll`: use `writev()` in flush for gather-write optimization (same pattern as kqueue)
- `engine-nio`: use `GatheringByteChannel.write(ByteBuffer[])` in flush for gather-write optimization (single syscall for multiple pending buffers)

- CI trigger on PRs changed from every push to label-based: `needs-pr-check` label required to run CI
- Kotlin upgraded from 2.1.10 to 2.3.20 (KGP 2.3.20, Gradle 9 full compatibility)
- kotlinx.io upgraded from 0.6.0 to 0.9.0
- Dokka upgraded to 2.2.0-Beta with V2 plugin mode; multi-module aggregation now uses `dependencies { dokka(project(":xxx")) }` DSL
- `gradle.properties`: added `org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m` for large multiplatform builds

### Added

- `ktor-engine`: add linuxX64 and linuxArm64 targets with EpollEngine as default
- `benchmark`: 3 profiles (default/tuned/keel-equiv-0.1) with per-engine tuning, CLI override for all socket and engine-specific options, and `--show-config` display
- `benchmark`: Phase 2 native servers (Rust Axum, Go Gin, Swift Hummingbird, Zig std.http) with CLI config, profiles, and `--show-config`
- `benchmark`: HTTP throughput benchmark module comparing keel engines against Ktor CIO, Ktor Netty, Spring Boot WebFlux, and Vert.x
- `sample`: Minimal Ktor + keel hello world demo server
- `scripts/bench-run.sh`: Automated wrk benchmark runner for all engines
- `scripts/bench-compare.sh`: Benchmark result comparison table generator
- `ktor-engine`: Ktor server engine adapter (`embeddedServer(Keel, port = 8080) { ... }`) backed by keel I/O engines (JVM: NioEngine, macOS: KqueueEngine)
- `codec-http`: `parseRequestHead`/`parseResponseHead` — parse only the request/response head (request line + headers) without consuming the body, enabling streaming body consumption for server engines
- `codec-http`: `HttpRequestHead`/`HttpResponseHead` data classes for head-only representations
- `codec-http`: `writeResponseHead` — write only status line + headers to a Sink (body written separately)
- `core`: `BufferAllocator` interface and `HeapAllocator` (pluggable buffer allocation)
- `core`: `NativeBuf` reference counting (`retain`/`release`) and dual-pointer index management (`readerIndex`/`writerIndex`)
- `core`: `NativeBuf.unsafePointer` (Native) / `unsafeBuffer` (JVM) for engine-layer zero-copy I/O
- `core`: `IoEngine` redesigned as `interface` with `suspend fun bind/connect`, `Channel`, `ServerChannel`, `SocketAddress`
- `core`: `IoEngineConfig` (allocator, threads)
- `core`: comprehensive KDoc on all public interfaces
- `engine-kqueue`: `KqueueEngine` IoEngine implementation (bind/connect, KqueueChannel with zero-copy read/write, PendingWrite buffering, kqueue read-wait, shutdownOutput, asSource/asSink bridge, 22 tests)
- `engine-nwconnection`: `NwEngine` IoEngine implementation (Apple Network.framework)
  - C wrappers: `keel_nw_read` (dispatch_data_t → NativeBuf copy via dispatch_data_apply), `keel_nw_write`, `keel_nw_shutdown_output`, `keel_nw_start_conn`
  - `NwChannel`: Channel wrapping nw_connection_t with PendingWrite buffering
  - `NwServerChannel`: semaphore-based accept queue wrapping nw_listener_t
  - `ChannelSource`/`ChannelSink`: kotlinx-io RawSource/RawSink bridges
  - 21 tests (lifecycle, read/write, half-close, connect, asSource/asSink, error)
- `engine-nio`: `NioEngine` IoEngine implementation (JVM java.nio)
  - `NioChannel`: zero-copy read/write via `NativeBuf.unsafeBuffer` (DirectByteBuffer) + PendingWrite buffering
  - `NioServerChannel`: blocking `ServerSocketChannel.accept()`
  - `ChannelSource`/`ChannelSink`: kotlinx-io bridges
  - 22 tests (lifecycle, read/write, half-close, connect, asSource/asSink, error)
- `engine-netty`: `NettyEngine` IoEngine implementation (JVM Netty 4.1)
  - `NettyChannel`: push-to-pull bridge via LinkedBlockingQueue (ByteBuf → NativeBuf copy), PendingWrite buffering, zero-copy write via Unpooled.wrappedBuffer
  - `NettyServerChannel`: accept queue via LinkedBlockingQueue
  - `ChannelSource`/`ChannelSink`: kotlinx-io bridges
  - 22 tests (lifecycle, read/write, half-close, connect, asSource/asSink, error)
- `engine-epoll`: `EpollEngine` IoEngine implementation (Linux epoll)
  - `EpollChannel`: zero-copy read/write via `NativeBuf.unsafePointer` + epoll_wait EAGAIN handling, PendingWrite buffering
  - `EpollServerChannel`: epoll_wait-based accept with fd filtering
  - `ChannelSource`/`ChannelSink`: kotlinx-io bridges
  - `SocketUtils`: add `createServerSocket(host,port)`, `createClientSocket`, `getLocalAddress`, `getRemoteAddress`; `keel_inet_pton`/`keel_inet_ntop` C wrappers for Linux
  - 22 tests (lifecycle, read/write, half-close, connect, asSource/asSink, error)
- `engine-nodejs`: `NodeEngine` IoEngine implementation (JS Node.js)
  - `NodeChannel`: push-to-pull bridge via ArrayDeque + suspendCoroutine (Node.js Buffer → NativeBuf copy)
  - `NodeServerChannel`: accept queue via ArrayDeque + suspendCoroutine
  - asSource/asSink deferred to Phase (b) — JS single-threaded, RawSource/RawSink require synchronous I/O
  - 17 tests (lifecycle, read/write, half-close, connect, error)

- `LICENSE`: Apache License 2.0 (copyright `The keel-kt Authors`)
- `README.md` (English) and `README.ja.md` (Japanese, primary): badges, module table, KMP target table, roadmap
- `website/`: Docusaurus 3.9.2 site scaffold (intro / architecture / codecs documentation)
- KDoc: `WsOpcode`, `WsCloseCode`, `WsFrame`, `WsFrameParser`, `WsFrameWriter`, `WsHandshake` (`:codec-websocket`)
- KDoc: `IoEngine`, `NativeBuf` (`:core`)

- `:codec-websocket`: WebSocket framing codec (RFC 6455)
  - `WsOpcode`: 4-bit opcode field (CONTINUATION / TEXT / BINARY / CLOSE / PING / PONG); throws on unknown opcode
  - `WsCloseCode`: Close status codes (RFC 6455 §7.4.1), valid range 1000–4999, `isReserved` / `isPrivateUse`
  - `WsFrame`: frame type (FIN / RSV1-3 / opcode / maskKey / payload); control frame constraints enforced in `init`
  - `parseFrame(Source): WsFrame`: 7 / 16 / 64-bit payload length, auto-unmask, throws on non-zero RSV or unknown opcode
  - `writeFrame(WsFrame, Sink)`: masking (XOR), auto-selects extended length field based on payload size
  - `computeAcceptKey(String): String`: RFC 6455 §1.3 handshake key (pure-Kotlin SHA-1 + stdlib Base64)
  - `validateClientKey(String): Boolean`: validates 16-byte Base64 client key
  - Tests: 61 cases (PASS on jvm / macosArm64 / JS nodejs)

- `IoEngine` and `NativeBuf` as `expect class` in `commonMain`
  - JVM actual: `NativeBuf` backed by `ByteBuffer.allocateDirect`
  - Native actual: `NativeBuf` backed by `nativeHeap.allocArray<ByteVar>`
- KMP multi-project scaffold: Gradle 9.4, 5 modules (`core`, `engine-epoll`, `engine-kqueue`, `engine-nio`, `engine-netty`)
- KMP targets: `jvm`, `linuxX64`, `macosArm64` (`applyDefaultHierarchyTemplate` for `nativeMain`)
- GitHub Actions CI workflow (`ubuntu-latest`): `compileKotlinJvm`, `compileKotlinLinuxX64`, `jvmTest`
- `scripts/check-local.sh`: macosArm64 pre-PR validation script (alternative to macOS runner)
- `engine-kqueue`: kqueue cinterop definition (`kqueue.def`)
  - Binds `sys/event.h` with `-D_DARWIN_C_SOURCE`
  - `keel_ev_set()` wrapper for `EV_SET` C macro via cinterop glue code
  - Targets restricted to `macosArm64` / `macosX64`
- `gradle.properties`: `kotlin.mpp.enableCInteropCommonization=true` to expose kqueue types in `macosMain`
- `engine-kqueue`: `KqueueEngine` — standalone TCP echo server using kqueue on macosArm64/macosX64
  - `bind(port)`: TCP server socket (SO_REUSEADDR, O_NONBLOCK) registered with kqueue
  - `runEchoLoop(serverFd, maxEvents)`: accept → EVFILT_READ → read → echo event loop
  - `close()`: releases kqueue fd
- `engine-kqueue`: `KqueueEngineTest` — 4 unit tests including loopback echo test
- `kqueue.def`: `keel_htons`, `keel_ntohs`, `keel_htonl`, `keel_loopback_addr` wrappers (Darwin byte-order macros)
- `core`: added `macosX64` target to unblock cinterop commonization
