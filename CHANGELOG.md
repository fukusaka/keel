# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- core: add `onUserEvent`/`propagateUserEvent`/`notifyUserEvent` to Pipeline framework for user-defined inbound events (e.g., TLS handshake completion)
- tls: add `:tls` module with `TlsCodec`/`TlsCodecFactory` buffer-to-buffer protection API (RFC 8446/9001 terminology: `protect`/`unprotect`)
- tls: add `TlsHandler` ChannelDuplexHandler for Pipeline integration with zero-copy recv fast path
- tls: add `TlsHandshakeComplete` user event and `TlsErrorCategory` structured error classification
- tls-mbedtls: add `MbedTlsCodec` TlsCodec implementation with pointer-based BIO adapter (Mbed TLS 4.x, PSA Crypto)
- tls-jsse: add `JsseTlsCodec` and `JsseTlsCodecFactory` — JSSE SSLEngine-backed TlsCodec for JVM
- tls-openssl: add `OpenSslCodec` and `OpenSslCodecFactory` — OpenSSL 3.x pointer-based BIO TlsCodec for Native
- tls-awslc: add `AwsLcCodec` and `AwsLcCodecFactory` — AWS-LC (BoringSSL fork) pointer-based BIO TlsCodec for Native
- core: add `PipelinedServer` interface and `IoEngine.bindPipeline` (non-suspend, default throw for unsupported engines)
- build: add `detekt-formatting` (ktlint wrapper) for automated Kotlin coding conventions enforcement
- ci: add OpenSSL (`libssl-dev`) and AWS-LC install to CI and Dokka workflows for tls-openssl/tls-awslc/tls-jsse builds
- ktor-engine: add HTTPS support via connector-based `sslConnector` DSL with `TlsHandler` pipeline injection
- benchmark: add `--tls=jsse|openssl|awslc|mbedtls` CLI flag and `BENCH_SCHEME`/`BENCH_TLS` env vars for HTTPS benchmarking across all engines (keel, ktor-netty, netty-raw, spring, vertx, rust, go, swift)

### Removed

- tls-mbedtls: remove `TestEngine` workaround and `findFreePort` — use `IoEngine.bindPipeline` + `PipelinedServer.localAddress` directly

### Fixed

- tls: loop `TlsHandler.flushHandshakeResponse` to handle handshake flights exceeding single output buffer (e.g., long certificate chains)
- tls-mbedtls: add `-ltfpsacrypto` linker option for Mbed TLS 4.x PSA Crypto library separation
- tls-mbedtls: add `--allow-shlib-undefined` for Linux to resolve lld indirect glibc reference errors
- core: enforce EventLoop thread for `PipelinedChannel` Channel mode (`read`/`write`/`flush`) via `withContext(coroutineDispatcher)` — fixes JMM visibility bug causing random test hang on 2-core CI

### Changed

- core: rename `ServerChannel` to `Server` — a server is not a channel (`ServerChannel` typealias kept for backward compatibility)
- all engines: rename `*ServerChannel` to `*Server` (e.g., `KqueueServerChannel` → `KqueueServer`)
- io-core: rename `PushSuspendSource` to `OwnedSuspendSource`, `PushToSuspendSourceAdapter` to `OwnedToSuspendSourceAdapter`
- core: remove `PushChannel` and `PushServerChannel` — Pipeline-incompatible design replaced by `Channel.asBufferedSuspendSource()` + `SuspendBridgeHandler.readOwned()`
- core: add `Channel.asBufferedSuspendSource()` with zero-copy push-mode override in `PipelinedChannel`
- engine-nio: make `bindPipeline` non-suspend via `registerChannelBlocking` (Pipeline zero-coroutine principle)
- engine-nwconnection: make `bindPipeline` non-suspend via `dispatch_semaphore_wait`
- engine-nio: unify `NioChannel` into `NioPipelinedChannel` — single type supports both Pipeline (push) and Channel (suspend) modes via `SuspendBridgeHandler`
- engine-epoll: unify `EpollChannel` into `EpollPipelinedChannel` — same Channel/Pipeline unification pattern
- engine-io-uring: unify `IoUringChannel` into `IoUringPipelinedChannel` — same Channel/Pipeline unification pattern
- engine-kqueue, engine-epoll, engine-nio: Channel mode `write()`/`flush()` now use `pipeline.requestWrite/Flush` directly instead of `ensureBridge()`, preventing read-path side effects on outbound operations
- core: add `requestFlush()` (fire-and-forget) and `awaitFlushComplete()` (completion wait) to Channel interface; `flush()` is now `requestFlush() + awaitFlushComplete()` by default
- engine-io-uring: integrate `IoModeSelector` into fire-and-forget `flush()` — CQE (with writev gather write), FALLBACK_CQE, and SEND_ZC modes all supported; remove `flushSuspend()` and suspend flush strategies

### Fixed

- engine-kqueue: add `check(!closed)` guard to Channel mode `read()`/`write()`/`flush()` to prevent infinite suspend on closed channel
- engine-nio, engine-netty: add 10-second test timeout to all JVM tests to prevent CI hang
- engine-epoll: fix `EpollEventLoop` fd registration to support concurrent READ + WRITE interests via `EPOLL_CTL_MOD` fallback
- engine-io-uring: fix `IoUringIoTransport.flush()` data loss when EAGAIN occurs with multiple pending writes

### Documentation

- Dokka: document all visibility levels (public, internal, protected, private) for complete API reference
- Dokka: add source links to GitHub for each declaration
- Dokka: add `module.md` for all 13 modules with module and package descriptions
- Dokka: shorten navigation package names by removing `io.github.fukusaka.keel.` prefix via custom JavaScript

### Added

- `engine-kqueue`: add `KqueueEngine.bindPipeline()` for callback-driven pipeline server without coroutine overhead
- `engine-kqueue`: add `KqueueIoTransport`, `KqueuePipelinedChannel`, `KqueuePipelinedServerChannel` for zero-suspend pipeline I/O
- `engine-kqueue`: add `registerCallback()` / `unregisterCallback()` to `KqueueEventLoop` for one-shot fd readiness callbacks
- `benchmark`: add `pipeline-http-kqueue` engine using `HttpRequestDecoder` + `RoutingHandler` + `HttpResponseEncoder` on `KqueueEngine.bindPipeline()`
- `engine-epoll`: add `EpollEngine.bindPipeline()` for callback-driven pipeline server on Linux
- `engine-epoll`: add `EpollIoTransport`, `EpollPipelinedChannel`, `EpollPipelinedServerChannel` for zero-suspend pipeline I/O
- `engine-epoll`: add `registerCallback()` / `unregisterCallback()` to `EpollEventLoop`
- `benchmark`: add `pipeline-http-epoll` engine for Linux pipeline throughput comparison
- `engine-nio`: add `NioEngine.bindPipeline()` for callback-driven pipeline server on JVM
- `engine-nio`: add `NioIoTransport`, `NioPipelinedChannel`, `NioPipelinedServerChannel` for non-suspend pipeline I/O
- `engine-nio`: add `setInterestCallback()` to `NioEventLoop` for one-shot readiness callbacks via SelectionKey
- `benchmark`: add `pipeline-http-nio` engine for JVM pipeline throughput comparison
- `engine-nwconnection`: add `NwEngine.bindPipeline()` for pipeline server via NWConnection dispatch queues
- `engine-nwconnection`: add `NwIoTransport`, `NwPipelinedChannel` for async pipeline I/O
- `benchmark`: add `pipeline-http-nwconnection` engine for macOS NWConnection pipeline comparison

### Fixed

- `engine-epoll`: fix registration race window in `EpollEventLoop.register()` — store map entry before `epoll_ctl(ADD)`

- `engine-kqueue`: fix registration race window in `KqueueEventLoop.register()` / `registerCallback()` — store map entry before `kevent(EV_ADD)` to prevent event loss

### Performance

- `codec-http`: cache `path` and `queryString` in `HttpRequestHead` and `HttpRequest` to eliminate per-access String allocations on the hot path
- `codec-http`: replace `List<Pair>` flatEntries with parallel arrays in `HttpHeaders` to eliminate Pair object allocations per cache rebuild
- `codec-http`: add pre-lowered header name constants and `getByLowercaseKey()` to avoid `String.lowercase()` allocation in typed property access

### Fixed

- `codec-http`: reject HTTP/1.1 requests without mandatory Host header per RFC 7230 §5.4
- `codec-http`: reject requests with both Content-Length and Transfer-Encoding in pipeline decoder to prevent HTTP Request Smuggling (RFC 7230 §3.3.3)
- `codec-http`: replace `!!` non-null assertions with `checkNotNull()` in `HttpRequestDecoder.emitHead()`

### Changed

- `codec-http`: reimplement `HttpHeaders` with `LinkedHashMap`-based storage for O(1) lookup with lowercase normalization and original case preservation
- `codec-http`: convert `HttpHeaders` convenience accessors from methods to properties (`contentLength`, `contentType`, `isChunked`, `connection`)
- `codec-http`: add `HttpHeaders.build {}` DSL, `HttpHeaders.of()` factory, `names()`, and `isEmpty` property
- `codec-http`: convert `HttpRequest` to data class with `path`/`queryString` lazy properties, `isKeepAlive` property, and `get()`/`post()` factory methods
- `codec-http`: convert `HttpResponse` to data class with `ok()`/`notFound()`/`of()` factory methods that auto-set Content-Type/Content-Length headers
- `codec-http`: add `path`/`queryString` properties and convert `isKeepAlive()` to property on `HttpRequestHead`

### Added

- `codec-http`: add `HttpRequestDecoder` pipeline handler that decodes inbound `IoBuf` chunks into `HttpRequestHead` messages with state machine parsing, partial-read support across `IoBuf` boundaries, HTTP pipelining, Content-Length body skipping, and max-line-size guard
- `codec-http`: add `HttpResponseEncoder` pipeline handler that encodes `HttpResponse` into a single pre-sized `IoBuf` with direct `writeAscii`/`writeByte` calls — no intermediate String/ByteArray allocation on the hot path
- `codec-http`: add `RoutingHandler` pipeline handler that routes `HttpRequestHead` messages by path to registered handler functions, returning 404 for unmatched paths
- `benchmark`: add `pipeline-http-io-uring` engine using `HttpRequestDecoder` + `RoutingHandler` + `HttpResponseEncoder` on `IoUringEngine.bindPipeline()`

- `core`: add `ChannelPipeline` framework for zero-suspend protocol processing with `notify*`/`propagate*`/`on*` naming convention, construction-time type chain validation, and `TypedChannelInboundHandler` with reified factory
- `core`: make `IoTransport` public for cross-module implementation by engine modules
- `engine-io-uring`: extract `IoUringIoTransport` from `IoUringChannel` for shared write/flush between Channel (suspend) and pipeline HeadHandler (fire-and-forget)
- `engine-io-uring`: add `IoUringPipelinedChannel` and `IoUringPipelinedServerChannel` for zero-suspend pipeline processing with multishot recv + SO_REUSEPORT
- `engine-io-uring`: add `IoUringEngine.bindPipeline()` for callback-driven server without coroutine overhead
- `engine-io-uring`: add `SocketUtils.createReusePortServerSocket()` for multi-thread accept distribution
- `benchmark`: rewrite `raw-io-uring` benchmark using pipeline API (`bindPipeline` + `typedHandler<IoBuf>`) — no `@InternalKeelApi` access
- `engine-io-uring`: add `IoUringCapabilities` for runtime kernel feature detection (opcode probe + kernel version); user-overridable via `IoUringEngine(capabilities = ...)`
- `engine-io-uring`: apply `IORING_SETUP_SINGLE_ISSUER` + `IORING_SETUP_COOP_TASKRUN` ring init flags with automatic fallback on older kernels
- `engine-io-uring`: guard all features (multishot accept/recv, provided buffer ring, SEND_ZC) with capabilities; graceful fallback instead of crash on unsupported kernels
- `engine-io-uring`: add `IoMode.SEND_ZC` for zero-copy send via `IORING_OP_SEND_ZC` (Linux 6.0+); kernel sends directly from user-space buffer without socket buffer copy; two-CQE model handled transparently by EventLoop
- `engine-io-uring`: add hybrid I/O mode (`IoMode.CQE` / `IoMode.FALLBACK_CQE`) with `IoModeSelector` for runtime write strategy selection; default `eagainThreshold(0.1)` starts with direct `send()` syscall and auto-switches to CQE on high EAGAIN rate
- `io-core`: add push-mode to `BufferedSuspendSource` for zero-copy reading from `PushSuspendSource`; engine-owned `IoBuf` chain consumed directly without copy
- `io-core`: add `BufSlice` chain support (`next` field) for zero-copy representation of lines spanning buffer boundaries
- `core`: add `PushChannel` and `PushServerChannel` interfaces for push-model engines; separate from `Channel`/`ServerChannel` (pull-model) with type-level read-model distinction
- `engine-io-uring`: implement `PushChannel` on `IoUringChannel` and `PushServerChannel` on `IoUringServerChannel` via `asPushSuspendSource()`
- `engine-io-uring`: add Linux io_uring-based `IoEngine` implementation (`IoUringEngine`) with zero-copy read/write via `IORING_OP_RECV`/`IORING_OP_SEND`, gather write (`IORING_OP_WRITEV`), and eventfd-based wakeup mechanism
- `engine-io-uring`: cancel in-flight SQEs via `IORING_OP_ASYNC_CANCEL` when the waiting coroutine is cancelled; fast path (EventLoop thread) captures user_data directly, slow path (cross-thread) uses `AtomicLong` to bridge the submission/cancellation race
- `engine-io-uring`: add cinterop bindings for provided buffer ring (`io_uring_setup_buf_ring`, `io_uring_buf_ring_add`, etc.) and multi-shot recv helpers (`keel_cqe_get_buf_id`, `keel_cqe_has_more`, `keel_sqe_set_buffer_select`)
- `engine-io-uring`: add `ProvidedBufferRing` for kernel-managed buffer selection with pre-allocated contiguous buffer pool (64 × 8KB default)
- `engine-io-uring`: add multishot callback support to `IoUringEventLoop` slot pool for one-SQE-to-many-CQE operations (`submitMultishot`/`cancelMultishot`)
- `engine-io-uring`: implement multishot accept (`IORING_ACCEPT_MULTISHOT`, Linux 5.19+) in `IoUringServerChannel`, eliminating per-accept SQE resubmission overhead
- `io-core`: add `NativeBuf.wrapExternal()` factory for wrapping externally-owned memory (Native: `CPointer<ByteVar>`, JVM: `ByteBuffer`, JS: `Int8Array`) with custom deallocator for buffer recycling
- `io-core`: add `NativeBuf.copyTo()` for platform-optimized bulk buffer-to-buffer copy (memcpy on Native, ByteBuffer.put on JVM, Int8Array.set on JS)
- `io-core`: add `PushSuspendSource` interface for push-model engines that deliver data in engine-owned buffers, and `PushToSuspendSourceAdapter` for backward-compatible integration with `BufferedSuspendSource`
- `engine-io-uring`: add `IoUringPushSource` implementing `PushSuspendSource` with multishot recv (`IORING_RECV_MULTISHOT`) and provided buffer ring (`IOSQE_BUFFER_SELECT`) for SQE-resubmission-free data delivery
- `engine-io-uring`: add `RingBufferNativeBuf` as engine-specific `NativeBuf` implementation for provided buffer ring slots, eliminating `HeapNativeBuf.wrapExternal`/`resetForReuse` dependency
- `engine-io-uring`: add `keel_prep_recv_multishot` cinterop wrapper combining `io_uring_prep_recv_multishot` with `IOSQE_BUFFER_SELECT`
- `engine-io-uring`: add per-worker `ProvidedBufferRing` in `IoUringEventLoopGroup` for multishot recv buffer selection
- `engine-io-uring`: override `IoUringChannel.asSuspendSource()` to use `IoUringPushSource` + `PushToSuspendSourceAdapter` for multishot recv path
- `engine-io-uring`: add tests for `asSuspendSink` via `BufferedSuspendSink`, round-robin worker EventLoop assignment, and `connect` with invalid host address

### Fixed

- `engine-io-uring`: fix stale KDoc referencing `IORING_OP_READ`/`IORING_OP_WRITE` in `IoUringChannel`; actual opcodes are `IORING_OP_RECV`/`IORING_OP_SEND`
- `engine-io-uring`: fix stale `[buildSockAddr]` KDoc link in `SocketUtils`; replaced with `[IoUringEngine.connect]`
- `engine-io-uring`, `engine-epoll`, `engine-kqueue`: fix fd leak in `createServerSocket()` when `bind()`, `listen()`, or address parsing fails after `socket()` succeeds; fd is now closed via try-catch before rethrowing
- `io-core`: fix potential double-release in `BufferedSuspendSink` deferFlush path (allocate before release)
- `io-core`: replace unchecked cast with safe cast (`as?`) in `TrackingAllocator`
- `benchmark`: add graceful shutdown via SIGTERM/SIGINT signal handling (Native) and JVM shutdown hook; fixes `Address already in use` on consecutive benchmark runs
- `engine-io-uring`: fix fd leak in `IoUringEngine.connect()` and `IoUringServerChannel.accept()` when an exception (e.g., `CancellationException`) occurs after fd creation but before it is wrapped in a Channel
- `engine-io-uring`: fix NativeBuf leak in `flushSingle`/`flushGather` when `submitAndAwait` throws (e.g., `CancellationException`); buffers are now released via try-finally
- `engine-io-uring`: fix potential deadlock in `IoUringEventLoop` when the wakeup SQE submission was silently dropped due to a full SQ ring; the submission is now deferred via `wakeupSqePending` and retried at the top of the next loop iteration

### Changed

- build: conditionally apply Dokka plugin based on host OS; skip platform-specific cinterop modules (engine-io-uring on macOS, engine-kqueue/engine-nwconnection on Linux) to prevent build failures
- build: add missing modules (logging, io-core, ktor-engine) to root Dokka aggregation
- build: add `scripts/merge-dokka.py` for cross-platform Dokka HTML merging with Docusaurus-aligned theme
- ci: generate complete Dokka API docs via parallel macOS + Linux jobs on release and deploy-docs label
- website: add API navbar link; update footer with dual-license notice (Code: Apache 2.0, Docs: CC BY 4.0)
- `ktor-engine`: use `BufferedSuspendSource` push-mode for `PushChannel` engines (io_uring); eliminates `IoBuf.copyTo()` per read in the HTTP codec path
- `io-core`: rename `IoBuf.writeBytes` to `writeByteArray` and `writeAsciiString` to `writeAscii` for naming clarity
- `io-core`: add `IoBuf.readByteArray(dest, offset, length)` for platform-optimized bulk read; symmetric with `writeByteArray`
- `io-core`: replace per-byte loops in `BufferedSuspendSource.readByteArray`/`readAtMostTo` with `IoBuf.readByteArray` bulk operations
- `io-core`: eliminate all `!!` assertions in `BufSlice` via `checkNotNull` and parallel segment traversal
- `io-core`: eliminate all `!!` assertions in `BufferedSuspendSource` via sealed class `Mode` and `fillAndGet()` pattern
- `io-core`: rename `NativeBuf` to `IoBuf`; platform implementations: `NativeIoBuf` (Native), `DirectIoBuf` (JVM), `TypedArrayIoBuf` (JS); buffer classes moved from `.keel.io` to `.keel.buf` package
- `io-core`: rename `HeapAllocator` to `DefaultAllocator`
- `engine-io-uring`: rename `RingBufferNativeBuf` to `RingBufferIoBuf`
- `io-core`: add `NativePointerAccess` interface for cross-type unsafe pointer access on Native; `NativeIoBuf.copyTo()` uses `NativePointerAccess` for polymorphic destination support
- `io-core`: extract `NativeBuf` from `expect class` to `interface`; implementation classes renamed to `HeapNativeBuf` per platform; `PoolableNativeBuf` internal interface encapsulates `deallocator`/`nextLink`/`resetForReuse`
- `io-core`: widen visibility of `NativeBuf.wrapExternal()`, `NativeBuf.resetForReuse()`, and `PushToSuspendSourceAdapter` from `internal` to `public` for use by engine modules; add `deallocator` parameter to `wrapExternal()` for safe callback registration
- `io-core`: replace byte-by-byte copy in `PushToSuspendSourceAdapter` with `NativeBuf.copyTo()` bulk copy
- `engine-io-uring`: replace `HeapNativeBuf` wrappers with `RingBufferNativeBuf` in `IoUringPushSource`, removing external dependency on `wrapExternal`/`resetForReuse`
- `io-core`: make `HeapNativeBuf.wrapExternal()` internal (no longer needed by engine modules)
- `engine-epoll`, `engine-kqueue`, `engine-io-uring`: mark all C wrapper functions in cinterop `.def` files as `static` to prevent linker symbol collisions when multiple engine modules are linked into the same binary (including `keel_alloc_iovec` / `keel_free_iovec` added in a follow-up)
- `engine-io-uring`: add typed `submitRecv`/`submitSend`/`submitWritev` methods eliminating `prepare` lambda allocation on the hot path; `invokeOnCancellation` lambda removed from typed API (cancellation handled by fd close)
- `engine-io-uring`: extract magic numbers to named constants (`LISTEN_BACKLOG`, `INET_ADDRSTRLEN`) in `SocketUtils`
- `engine-io-uring`: use index-based loop in `drainTasks` to avoid `Iterator` allocation on the hot path
- `engine-io-uring`: replace `Pair` return in `IoUringEventLoopGroup.next()` with index-based accessors (`nextIndex`/`loopAt`/`allocatorAt`)
- `engine-io-uring`: remove redundant `@Suppress("UNCHECKED_CAST")` and unnecessary cast from `contSlots` initializer
- `engine-io-uring`: replace `StableRef`-per-operation with a slot-indexed continuation pool (`IntArray` stack + Kotlin array) eliminating per-I/O GC allocation on the hot path
- `engine-io-uring`: replace separate `io_uring_submit` + `io_uring_wait_cqe` with `io_uring_submit_and_wait(1)` reducing per-iteration kernel entries by 50%
- `engine-io-uring`: use `IORING_OP_RECV`/`IORING_OP_SEND` instead of `IORING_OP_READ`/`IORING_OP_WRITE` for socket I/O (socket-optimised opcodes)

## [0.3.0] - 2026-03-28

### Added

- `logging`: add logging module with `Logger`, `LoggerFactory`, `LogLevel`, `NoopLoggerFactory`, and `PrintLogger`
- `core`: add `loggerFactory` property to `IoEngineConfig` (defaults to `NoopLoggerFactory`)
- `core`: add `Channel.appDispatcher` for per-engine pipeline dispatch strategy
- `ktor-engine`: add `KtorLoggerAdapter` to bridge Ktor Logger to keel `LoggerFactory`
- `ktor-engine`: run Ktor pipeline on EventLoop for Native engines (kqueue +26%, epoll +33%)
- `ktor-engine`: add accept error backoff strategy (Fixed / Exponential) via `Configuration.acceptBackoff`
- `io-core`: add `KeelEofException` as domain-specific base exception for unexpected EOF
- `io-core`: add `NativeBuf.deallocator` callback for pool-based buffer reclamation
- `io-core`: add `NativeBuf.nextLink` for intrusive lock-free pool freelists (Treiber stack)
- `io-core`: add `NativeBuf.getByte(index)` for absolute byte access without modifying readerIndex
- `io-core`: add `NativeBuf.writeAsciiString()` for bulk ASCII string-to-buffer writes without ByteArray allocation
- `io-core`: add `NativeBuf.resetForReuse()` for pool-based buffer recycling
- `io-core`: add `BufferedSuspendSink.writeAscii()` for zero-allocation HTTP header writing
- `io-core`: add `BufferedSuspendSource.scanLine()` returning `BufSlice` instead of `String`
- `io-core`: add `BufSlice` for zero-copy read-only views over `NativeBuf` regions
- `io-core`: add `defaultAllocator()` expect/actual returning the platform-recommended pooled allocator
- `io-core`: add `BufferAllocator.createForEventLoop()` for per-EventLoop allocator instances
- `io-core`: add `SlabAllocator` (Native) and `PooledDirectAllocator` (JVM) for per-EventLoop buffer pooling
- `io-core`: add `TrackingAllocator` for allocate/release leak detection in tests
- `codec-http`: add `HttpParseException` and `HttpEofException` for layered error handling
- `codec-http`: add `HttpMethod.of()` factory that returns cached instances for standard methods
- `codec-http`: add status code range validation in `parseStatusLine` before `HttpStatus` construction
- `engine-*`: add DEBUG lifecycle logging (bind, connect, close) to all six engines
- `engine-kqueue`, `engine-epoll`, `engine-nio`: skip wakeup syscall when dispatching from EventLoop thread (inEventLoop optimization)
- `engine-kqueue`, `engine-epoll`, `engine-nio`: wire per-EventLoop allocators via `createForEventLoop()`
- `engine-kqueue`, `engine-epoll`: replace pthread_mutex with lock-free MPSC queue for coroutine dispatch
- `detekt-rules`: custom detekt rules for resource leak detection (NativeBufLeak, ArenaLeak, StableRefLeak)
- Add detekt 1.23.8 static analysis with KMP-tuned configuration for all production modules
- CI: add detekt step (runs before compilation)
- `benchmark`: add `BENCH_RUNS` for multi-run median, `BENCH_SHUFFLE` for randomized engine order, and `BENCH_COOLDOWN` for inter-engine recovery delay

### Changed

- `io-core`: `BufferedSuspendSink.flushBuffer()` defers `flush()` to the caller; filled buffers are enqueued and sent in a single `writev()` syscall (epoll /large: 9K → 201K)
- `io-core`: `BufferedSuspendSource.fill()` compacts only when writable space falls below 1 KiB threshold, skipping ~87% of unnecessary `compact()` calls
- `io-core`: `PooledDirectAllocator` uses intrusive Treiber stack for lock-free thread-safe pool access
- `io-core`: `SlabAllocator` is now thread-safe via spin lock for NWConnection deferred flush support
- `io-core`: make `NativeBuf` constructor `internal`; create buffers via `BufferAllocator.allocate()`
- `io-core`: remove `BufferAllocator.release(buf)`; use `buf.release()` as the single release path
- `io-core`: reuse `StringBuilder` across `readLine()` calls in `BufferedSuspendSource`
- `core`: `IoEngineConfig.allocator` now defaults to `defaultAllocator()` (Native: `SlabAllocator`, JVM: `PooledDirectAllocator`, JS: `HeapAllocator`)
- `engine-nwconnection`: batch flush via `keel_nw_writev_async`; concatenates pending writes into a single `dispatch_data_t` for one `nw_connection_send` call
- `engine-kqueue`: cache wakeup byte arrays to avoid per-dispatch allocation
- `codec-http`: use `indexOf`-based parsing in `parseRequestLine` instead of `String.split()`
- `codec-http`: use `String.equals(ignoreCase=true)` in `isKeepAlive()` instead of `String.lowercase()`
- `codec-http`: `HttpParser` throws `HttpEofException`/`HttpParseException` instead of `IllegalArgumentException`
- `ktor-engine`: respond with HTTP 400 Bad Request on malformed requests before closing connection
- `ktor-engine`: catch specific `HttpEofException`/`HttpParseException` instead of generic `Exception`
- `ktor-engine`: reuse body bridge `ByteArray` across keep-alive requests on the same connection

### Fixed

- `io-core`: Native `NativeBuf.writeBytes()` with zero-length input no longer throws `ArrayIndexOutOfBoundsException` from `usePinned`
- `engine-nio`: protect `processSelectedKeys` with try-catch so one channel's error does not stop other channels
- `engine-nodejs`: replace byte-by-byte read loop with bulk `writeBytes()` copy
- `benchmark`: `bench-one.sh` now reads `BENCH_ENDPOINT` environment variable instead of hardcoding `/hello`
- `benchmark`: use pre-encoded byte payloads for all servers to eliminate per-request encoding overhead
- `benchmark`: use SIGTERM with graceful fallback instead of SIGKILL in `kill_port()`

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
