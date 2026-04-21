# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- `core`: `UnixSocketAddress` with `@prefix` / `\u0000`-prefix convention for Linux abstract-namespace UDS; `isAbstract` / `kernelPath` properties; `UnixSocketAddress.filesystem(path)` / `abstract(name)` factories ([#298])
- `core`: `CachingDnsResolver(delegate, ttl = 30.seconds, maxSize = 1024)` — LRU positive cache, single-flight via decoupled `SupervisorJob` scope, `close()` / `invalidate()` for graceful shutdown ([#297])
- `core`: `InetSocketAddress.connectWithFallback(resolver, hints, attempt)` — sequential fallback over resolved candidates, preserves `CancellationException`. Happy Eyeballs deferred to HTTP Dialer layer ([#297])
- `engine-*` (Native): IPv6 end-to-end for `KqueueEngine` / `EpollEngine` / `IoUringEngine`; `PosixSocketUtils` accepts `IpAddress`, `sockaddr_in` / `sockaddr_in6` branching inside C wrappers to sidestep glibc / Darwin union field-name incompatibility ([#301])
- `engine-*` (Native): UDS support for `bind` / `connect` / `bindPipeline` (filesystem + Linux abstract on epoll / io_uring, filesystem only on kqueue) ([#298])
- `engine-nio` / `engine-netty`: UDS support via Java 16+ `UnixDomainSocketAddress`; `NioServerDomainSocketChannel` / `NioDomainSocketChannel` on Netty (filesystem only — JDK limitation) ([#299])
- `engine-nodejs` / `engine-nwconnection`: UDS support; Node uses `net.createServer({ path })`, `NwEngine` uses public `nw_endpoint_create_address(sockaddr_un *)` + `nw_parameters_set_local_endpoint`. Darwin `sun_path[104]` limit validated up-front ([#300])
- `engine-io-uring`: IO_WQ max workers limits (Linux 5.15+, opt-in) via `IoUringCapabilities.iowqMaxWorkers` ([#285])
- `engine-io-uring`: NAPI busy-poll registration (Linux 6.9+, opt-in) via `IoUringCapabilities.napiBusyPoll` ([#284])
- `engine-io-uring`: direct-allocated multishot accept (Linux 5.19+, opt-in) — saves one `register_files_update` syscall per accept. Recommend with SEND_ZC on real NICs ([#283])
- `engine-io-uring`: `io_uring_register_ring_fd` self-registration (Linux 5.18+), promoted to default-on ([#280])
- `engine-io-uring`: `IORING_OP_MSG_RING` cross-EventLoop wakeup (Linux 5.18+, opt-in) ([#279])
- `engine-io-uring`: `IORING_SETUP_DEFER_TASKRUN` ring setup flag (Linux 6.1+, opt-in) for p99 latency ([#278])
- `engine-io-uring`: `IORING_SETUP_SINGLE_ISSUER` ring setup flag (Linux 6.0+, opt-in) ([#277])
- `engine-io-uring`: `IORING_SETUP_COOP_TASKRUN` ring setup flag (Linux 6.0+) ([#274])
- `engine-io-uring`: SENDMSG_ZC mode for gather write + zero-copy ([#271]); fixed file descriptors via `IORING_REGISTER_FILES` ([#272]); registered buffers for `SEND_ZC_FIXED` ([#273])
- `native-posix`: `errnoMessage(errno: Int): String` helper wrapping thread-safe `strerror_r`; `closeFdSafely(fd, logger, context)` for silent-leak-free cleanup paths ([#275])
- `native-posix`: `NativeSocket` interface + `PosixNativeSocket` impl with sealed `ReadResult` / `AcceptResult` / `ConnectResult` / `ShutdownResult` / `CloseResult` (and reused `WriteResult`); EINTR-retrying C wrappers for `read` / `write` / `accept` / `send` / `shutdown` / `writev`; `keel_connect` deliberately does NOT retry (POSIX: interrupted connect continues async → `PosixNativeSocket.connect` maps `EINTR` to `ConnectResult.InProgress`); `close(2)` not retried per POSIX undefined-state rules ([#323])
- `native-posix`: `PosixRawClient` test helper (`rawConnect` / `rawWrite` / `rawRead` / `rawReadBytes` / `rawReadUpTo` / `rawReadOnce`) built on `NativeSocket` — EINTR retry handled by Layer 1, `rawRead` enforces an absolute monotonic deadline so signal storms cannot extend the timeout via kernel `SO_RCVTIMEO` reset; 5 engine tests (`EpollEngineTest` / `KqueueEngineTest` / `IoUringEngineTest` / `IoUringPipelinedServerTest` / `NwEngineTest`) consolidated onto the shared helper ([#324])
- `native-posix`: `PosixSocketUtils.acceptClient(fd)` wraps the `setNonBlocking` + `getRemoteAddress` + `getLocalAddress` sequence; `writeSingle(fd, ptr, length)` / `writeGather(fd, writes)` with sealed `WriteResult` (`Written` / `WouldBlock` / `Failed`) share POSIX `write(2)` / `writev(2)` wrappers across `EpollIoTransport` / `KqueueIoTransport` / `EpollServer` / `KqueueServer` / `IoUringServer` ([#316])
- `core`: `AbstractPipelinedChannel` base class in commonMain — wires `IoTransport` callbacks to the pipeline without per-engine boilerplate ([#266])
- `core`: `BindConfig.initializeConnection(channel)` auto-called by every `Server.accept()` — removes duplicate init calls in engine code ([#265])
- `io`: `BufferAllocator.registerPoolSize(size, maxSlots)` for dynamic multi-class pools; `BufferAllocator.wrapBytes(ByteArray, Int, Int)` / `BufferAllocator.slice(IoBuf, Int, Int)` for zero-copy views; `EmptyIoBuf` singleton ([#263], [#264])
- `core`: `SuspendMessageBridge<T>` — generic pipeline handler that bridges typed messages to suspend consumers ([#260], [#261])
- `codec-http`: `HttpMessage` sealed supertype; `HttpBody` / `HttpBodyEnd` streaming body types; `HttpHeaders.EMPTY`; `HttpBodyAggregator` pipeline handler (streaming → complete `HttpRequest`) ([#258])
- `io`: direct-write path in `BufferedSuspendSink.write(ByteArray, Int, Int)` bypassing the buffer for large writes ([#246])
- `io`: `LeakDetectingAllocator` with Cleaner-based detection (Native) / `PhantomReference` (JVM); `BufferAllocator.withTracking()` / `withLeakDetection()` wrappers ([#245])
- `core`: write backpressure with high/low water marks on `IoTransport` — `isWritable`, `pendingBytes`, `setWritabilityWaterMarks`, `onWritabilityChanged` ([#241])
- `engine-netty`: implement `bindPipeline` for push-mode I/O without Ktor overhead ([#227])
- `engine-nodejs`: implement `bindPipeline` for push-mode I/O without Ktor overhead ([#228])
- `benchmark`: `bench-remote.sh` — single-engine A/B over real-NIC LAN (env-var configured, no hardcoded host) ([#282])
- `benchmark`: pipeline-http-* TLS support (kqueue / epoll / io_uring / nio) + `--tls=jsse|openssl|awslc|mbedtls` CLI flag ([#226])
- `benchmark`: `pipeline-http-netty` / `pipeline-http-nodejs` entries added to `bench-keel.sh` / `bench-all.sh` ([#227], [#229])
- `ktor-engine`: HTTPS via connector-based `sslConnector` DSL with keel `TlsConfig` (works on all KMP targets); `TlsInstaller` interface for engine-specific TLS (`NettySslInstaller` for Netty `SslHandler`) ([#213], [#219], [#220])
- `tls`: PEM/DER converter (`PemDerConverter`, `Pkcs8KeyUnwrapper`); `asPem()` / `asDer()` extensions on `TlsCertificateSource`; `TlsCertificateSource.Der` support on OpenSSL / AWS-LC / MbedTLS / Netty / Node.js ([#233])
- `engine-nwconnection`: listener-level TLS via Network.framework `SecIdentity` + `NwTlsParams` ([#234])
- `engine-nodejs`: `tls.createServer()` listener-level TLS ([#232])
- `tls-jsse` / `tls-openssl` / `tls-awslc` / `tls-mbedtls`: four TLS backends with `TlsCodec` / `TlsCodecFactory` buffer-to-buffer codec; `TlsHandler` pipeline handler; `TlsHandshakeComplete` user event + `TlsErrorCategory` structured errors ([#210], [#211])
- `native-posix`: shared POSIX socket utilities module extracting common code across epoll / kqueue / io_uring ([#223])
- `core`: `Pipeline.onUserEvent` / `propagateUserEvent` / `notifyUserEvent` ([#230])
- `core`: `PipelinedServer` interface and `IoEngine.bindPipeline` — non-suspend pipeline server API ([#230])
- `build`: `detekt-formatting` (ktlint wrapper) for automated Kotlin coding conventions ([#204])
- `ci`: OpenSSL (`libssl-dev`) and AWS-LC install to CI and Dokka workflows ([#212])

### Changed

- `engine-epoll` / `engine-kqueue` / `engine-io-uring`: route read / write / accept / connect / shutdown / send through `PosixNativeSocket`; EINTR retried in Layer 1 so the engines cannot misclassify signal interrupts as EOF. `PosixSocketUtils.connectNonBlocking` / `connectUnixNonBlocking` return sealed `ConnectResult` instead of raw `Int` + ambient errno ([#325])
- **BREAKING** (`core`): remove `Channel.appDispatcher` / `IoTransport.appDispatcher`; move to `KeelApplicationEngine.Configuration.applicationDispatcher: CoroutineDispatcher? = null` (null default = `channel.ioDispatcher`). Custom `IoTransport` implementations drop the override; NIO users relying on Default-pool pipeline set `applicationDispatcher = Dispatchers.Default` ([#312])
- `engine-nio`: drop `appDispatcher = Dispatchers.Default` override; Ktor pipeline runs on the `NioEventLoop` Selector thread (ktor-keel-nio +9.5% on 32-core Ryzen loopback; prior regression no longer reproduces after the `PipelinedChannel` / `HttpWriter` rewrite landed in the same release cycle) ([#311])
- **BREAKING** (`core`): `StreamEngine.bind` / `connect` / `bindPipeline` take `SocketAddress` sealed hierarchy instead of `(host: String, port: Int)`. `(host, port)` preserved as default interface method. Non-suspend `bindPipeline` requires IP literals ([#294])
- **BREAKING** (`core`): `SocketAddress` → sealed hierarchy: `InetSocketAddress(host: Host, port: Int)` and `UnixSocketAddress(path: String)`. `Host` / `IpAddress` also sealed. Pure-Kotlin RFC 5952 parser; canonical V6 compressed form with `%scope` ([#294])
- **BREAKING** (`core`): `IoEngineConfig.resolver: DnsResolver` field (default `DnsResolver.SYSTEM`); `DnsResolver` / `ResolveHints` / `FamilyPreference` / `ResolverResult` public. JVM uses `InetAddress.getAllByName`, JS uses `dns.lookup`, Native uses `getaddrinfo` ([#295], [#296])
- **BREAKING** (`core`): `IoEngine` promoted from `AutoCloseable` to `CoroutineScope`; `close()` now `suspend`. All seven engines carry a `SupervisorJob` and `cancelAndJoin` children before teardown. `engine.use { }` no longer supported; wrap in `runBlocking { engine.close() }` from non-suspend contexts ([#291])
- **BREAKING** (`native-posix`): remove `POSIX_IPV4_RESOLVE_HINTS` / `InetSocketAddress.resolveForPosixSocket`. Callers use `resolveFirst(resolver)` / `connectWithFallback` with `FamilyPreference.Any` default ([#301])
- `engine-*`: route `connectInet` through `InetSocketAddress.connectWithFallback` for resolver-ordered multi-IP fallback (native + JVM engines); NodeEngine / NwEngine defer to OS-driven retry ([#297])
- `engine-io-uring`: restructure ring and register-class lifecycle so `io_uring_queue_init` and all `io_uring_register_*` calls run on the owning EventLoop pthread; 2-phase init (user-space alloc → kernel registration on loop); per-loop teardown hook ([#276])
- All Native engines + TLS: syscall-error reporting switched to the shared `errnoMessage(errno)` helper (thread-safe strerror_r); `close(fd)` cleanup paths use `closeFdSafely(fd, logger, context)` ([#275])
- `engine-epoll` / `engine-kqueue`: add `assertInEventLoop(operation)` guard + promote `inEventLoop()` to internal — matches `IoUringEventLoop` pattern. Runtime assertion on EL-only methods ([#286])
- `benchmark`: `bench-pull.sh` no longer hardcodes an internal host default — requires positional arg or `BENCH_REMOTE_HOST` env var with usage on stderr ([#281])
- `core`: rename Channel-prefixed pipeline types to avoid confusion with the transport `Channel` — `ChannelInboundHandler` → `InboundHandler`, `ChannelPipeline` → `Pipeline`, etc. ([#267])
- `ktor-engine`: full pipeline HTTP codec migration — request parsing via `HttpRequestDecoder` + `HttpBodyAggregator`; response via `HttpResponseEncoder`; pipeline runs on EventLoop push-mode ([#260], [#261])
- `engine-netty`: one buffer allocator per worker `EventLoop` — bounds direct memory footprint to `numEventLoops × localPoolSize × bufferSize`, independent of connection count ([#247])
- `io`: `PooledDirectAllocator.createForEventLoop()` returns an allocator bound to the calling EL thread — per-EL freelist without CAS contention ([#247])
- `core`: `BindConfig` converted from marker interface to open class with `backlog` + `initializeConnection()` defaults; `StreamEngine.bind()` / `bindPipeline()` accept `BindConfig` ([#235], [#236])
- `tls`: `TlsConnectorConfig.installer` is now nullable (null = engine-specific default); accepts `backlog` via `BindConfig` inheritance ([#235])
- `codec-http`: `HttpRequestDecoder` rewritten as byte-offset parser (no intermediate `String` allocation); decodes Content-Length and chunked transfer encoding inline; emits streaming `HttpBody` / `HttpBodyEnd`; `RoutingHandler` rewritten from suspend-bridge to pure pipeline handler ([#252], [#258])
- `core`: `IoTransport` extended with read path (`onRead`, `onReadClosed`, `readEnabled`) + lifecycle (`shutdownOutput`, `awaitClosed`) + properties (`allocator`, `isOpen`, `ioDispatcher`, `supportsDeferredFlush`). All 7 engines encapsulate full lifecycle; engine-specific `PipelinedChannel` classes reduce to empty `AbstractPipelinedChannel` subclasses ([#266])
- `tls`: split `TLS_RECORD_BUF_SIZE` (17 KiB) into `TLS_PLAINTEXT_BUF_SIZE` (16 KiB) + overhead slack, aligning with TLS record size limits ([#264])
- `io`: `PooledDirectAllocator` (JVM) + `SlabAllocator` (Native) rewritten with Treiber-stack intrusive freelists for lock-free per-EL access ([#264])
- `engine-nwconnection`: unify `NwChannel` into `NwPipelinedChannel` (single type for Pipeline + Coroutine modes) ([#217])
- `engine-netty`: unify `NettyChannel` into `NettyPipelinedChannel` with `NettyIoTransport` ([#218])
- `engine-nio`: unify `NioChannel` into `NioPipelinedChannel` ([#184])
- `engine-epoll`: unify `EpollChannel` into `EpollPipelinedChannel` ([#185])
- `engine-io-uring`: unify `IoUringChannel` into `IoUringPipelinedChannel` ([#186])
- `engine-nodejs`: unify `NodeChannel` into `NodePipelinedChannel` ([#228])
- `engine-nio` / `engine-epoll` / `engine-kqueue` / `engine-io-uring`: Channel mode `write()` / `flush()` use `requestFlush()` + `awaitFlushComplete()` — fire-and-forget flush with explicit completion await ([#187], [#188])
- `core`: rename `ServerChannel` → `Server` across all engines (server is not a channel) ([#197], [#198])
- `core`: `StreamEngine` sub-interface for byte-stream transports; `IoEngine` root interface reserves space for future `DatagramEngine` ([#222])
- Rename public modules with `keel-` prefix (e.g. `:core` → `:keel-core`); merge `:logging` into `:keel-core` ([#221])
- `benchmark`: select single Native TLS backend via `-Ptls-backend=openssl|awslc|mbedtls` ([#216])
- `io`: `BufferedSuspendSink.flushBuffer()` defers `flush()` to the caller; filled buffers enqueued and sent in a single `writev()` syscall (epoll `/large`: 9K → 201K req/s) ([#115])
- `io`: `BufferedSuspendSource.fill()` compacts only when writable space falls below 1 KiB threshold (skips ~87% of unnecessary compact calls) ([#118])
- `core`: `IoEngineConfig.allocator` defaults to `defaultAllocator()` (Native: `SlabAllocator`, JVM: `PooledDirectAllocator`, JS: `HeapAllocator`) ([#116])
- `engine-nwconnection`: batch flush via `keel_nw_writev_async` — concatenates pending writes into a single `dispatch_data_t` for one `nw_connection_send` call ([#117])
- Dokka: conditional plugin application by host OS; cross-platform Dokka HTML merging via `scripts/merge-dokka.py`; CI parallel macOS + Linux aggregation ([#163], [#179])
- `io`: rename `NativeBuf` to `IoBuf`; platform implementations `NativeIoBuf` / `JvmIoBuf` / `JsIoBuf`; extract `IoBuf` from `expect class` to `interface` with `NativePointerAccess` for cross-type unsafe pointer access ([#141], [#143])

### Fixed

- `engine-io-uring`: `flushDirectSendSingle` / `submitAsyncSendSequential` now log the errno and trigger teardown on fatal send errors instead of silently releasing the buffer and reporting flush complete ([#321])
- `engine-io-uring`: EventLoop no longer dies on unhandled CQE callback exceptions; log + continue instead ([#318])
- `engine-*`: release server fd / channel / NWListener along `bind()` / `bindPipeline()` error paths across all six engines. Was orphaned on `epoll_ctl` / `kevent` / `start()` / `dispatch_semaphore_wait` failures; `IoUringEngine.bindPipelineInet` tracks `createdCount` so partial `SO_REUSEPORT` fanout only closes acquired fds. New private helpers `closeQuietly` / `cancelListenerQuietly` ([#313])
- `engine-netty`: align `NettyIoTransport.ioDispatcher` with channel `EventLoop` via new `NettyEventLoopDispatcher` — fixes latent `SuspendBridgeHandler` cross-thread race (ktor-keel-netty +17% on loopback) ([#310])
- `engine-nwconnection`: align `NwIoTransport.ioDispatcher` with per-connection `connQueue` via new `NwConnectionQueueDispatcher` — fixes `NwEngineTest.GC heap` cycle-13 stall on CI `macos-latest` (ktor-keel-nwconnection +11%) ([#309])
- `engine-io-uring`: `IoUringPipelinedServerChannel.start()` blocks until every worker has enqueued its multishot accept SQE — fixes spurious `acceptDirectAlloc` test race under CI load ([#307])
- `build`: `./gradlew assemble` succeeds on single-host — host-gate platform-specific engine modules in `settings.gradle.kts` + consumer source-set gating + widen cross-arch cinterop disable filter ([#305])
- `build`: `./gradlew dokkaGeneratePublicationHtml` succeeds on clean `main` — declares `resolve(hostname, hints)` in `SystemDnsResolver` expect body; `keel_fill_sockaddr_un` signature switched to `sockaddr_storage *` for cinterop commonization; `:benchmark` Native targets host-gated ([#304])
- `ci`: expand CI workflow with `assemble` + `jsNodeTest` + `detektJsMain` + macOS runner for Darwin target coverage ([#306])
- `core`: `IpAddress.V4` / `V6` `toString()` returns RFC 5952 canonical form (compressed V6, `%scope` suffix); previously returned `Host.Ip` wrapper `toString()` ([#302])
- All engines: `IoTransport.close()` dispatches teardown onto the owning EventLoop / connQueue — fixes cross-thread `pendingWrites` mutation race ([#293])
- All engines: `Server.close()` thread-safe across all engines via EventLoop dispatch ([#292])
- `ktor-engine`: `KeelApplicationEngine.stop()` returns within the configured grace period — `engine.coroutineContext.job.children` enumeration + cancellation ([#291])
- `engine-nio`: fix `ClassCastException: CompletedContinuation cannot be cast to CancellableContinuation` during server shutdown — unify interest callback protocol ([#288])
- `engine-io-uring`: check return values for previously-ignored teardown syscalls (`io_uring_unregister_buffers`, `pthread_join`, `close(fd)` etc.); warn-level log on failure so silent kernel-side errors are observable ([#275])
- `tls`: `TlsHandler.processOutbound` hardened against three latent codec-state races; `TLS_RECORD_BUF_SIZE` overflow-behaviour KDoc neutralized; `TlsException` with `TlsErrorCategory` on write-path errors ([#249], [#250])
- `codec-http`: `HttpResponseEncoder` emits response bodies at or above 8 KiB — previously buffered indefinitely waiting for a small-size path ([#248])
- `engine-netty`: `NettyPipelinedChannel.channelRead` rounds inbound buffer capacity to `POOL_FRIENDLY_CAPACITY` (8 KiB) so small packets hit the freelist; DirectIoBuf double-release on `/large` responses via `supportsDeferredFlush = false` ([#242], [#247])
- `engine-nodejs`: byte-by-byte read loop replaced with bulk `Int8Array.subarray` / `set` — eliminates per-byte coroutine dispatch on large reads ([#243])
- `engine-io-uring`: `writev` gather write in `FALLBACK_CQE` direct-flush path; fd leak in `connect()` / `Server.accept()` / `PipelinedServerChannel.close()`; `NativeBuf` leak in `flushSingle` / `flushGather` on submit failure; potential `IoUringEventLoop` deadlock when wakeup races submit ([#244])
- `io`: `IoBuf.clear()` on JVM resets DirectByteBuffer position/limit; `NativeBuf.writeBytes()` bulk copy replaces per-byte loop; potential double-release in `BufferedSuspendSink` deferFlush path ([#148], [#151])
- `engine-kqueue`: `check(!closed)` guard on Channel mode `read()` / `write()` / `flush()` — fail-fast on use-after-close ([#183])
- `engine-nio` / `engine-netty`: 10-second test timeout on all JVM tests to prevent hang ([#191])
- `engine-epoll` / `engine-kqueue`: fix fd registration race window in `EventLoop.register()` — concurrent registration from multiple threads now safe via MPSC queue + single-thread consumer ([#174], [#185])
- `engine-io-uring`: `IoUringIoTransport.flush()` data loss when EAGAIN occurs mid-writev — remaining buffers now re-queued instead of dropped ([#186])
- All engines: cancel pending `accept()` coroutine with `CancellationException` on server close ([#69])
- `tls`: loop `TlsHandler.flushHandshakeResponse` to handle handshake flights spanning multiple writes ([#195])
- `tls-mbedtls`: `-ltfpsacrypto` linker option for Mbed TLS 4.x PSA Crypto split; `--allow-shlib-undefined` for Linux lld indirect deps ([#207])
- `tls`: remove `msg.release()` from `TlsHandler.onWrite` to fix double-release under HTTPS load ([#215])
- `engine-nwconnection`: dispatch `NwIoTransport.close()` on connection queue (resolves cross-thread mutation during close) ([#293])
- `benchmark`: `bench-one.sh` reads `BENCH_ENDPOINT` env var; pre-encoded byte payloads for all servers; SIGTERM with graceful fallback instead of SIGKILL in `kill_port` ([#119])

### Removed

- **BREAKING** (`core`): `PushChannel` / `PushServerChannel` — incompatible with unified Pipeline model ([#199])
- **BREAKING** (`tls-mbedtls`): `TestEngine` workaround / `findFreePort` — use `IoEngine.bindPipeline` + `PipelinedServer.localAddress` directly ([#202])
- `engine-nwconnection`: `NwTlsInstaller` sentinel (replaced by `installer == null` convention) ([#220]); `NodeTlsInstaller` / `MacosTlsInstallerInit` sentinels similarly removed

### Documentation

- `engine-io-uring`: route `IoUringPipelinedServerTest` through `PrintLogger(DEBUG)` so CQE diagnostics surface in CI test output ([#319]); `EchoHandler` no longer leaks the inbound buffer reference ([#321]); `rawRead` / `rawWrite` retry on EINTR — Kotlin/Native runtime signals (likely GC safepoints under CPU contention) were interrupting the blocking syscalls on GHA 4-vCPU runners and surfacing as the long-running `read returned -1` flake ([#321])
- `ci(iouring-stress)`: auto-trigger on PRs touching `keel-engine-io-uring` (gated by `needs-pr-check` label) ([#321])
- `ci`: upload JUnit XML + HTML test reports as artifacts; add manually-dispatched `io_uring stress` workflow ([#317])
- `engine-io-uring`: debug trace logs in `FixedFileRegistry` and pipelined server accept CQE ([#317]); armRecv submission + per-recv-CQE traces ([#320])
- Dokka: cover all visibility levels (public, internal, protected, private); GitHub source links per declaration; `module.md` for all 13 + 6 TLS modules; shortened navigation package names ([#253], [#254], [#255])
- website: rewrite `intro.md` as Getting Started guide with Quick Start; add performance-based engine selection tables; macOS → Linux development workflow; keel vs Netty vs Ktor positioning ([#251])
- website: Coroutine / Pipeline / HTTP / WebSocket architecture pages ([#257], [#269]); Japanese translations for all pages ([#251])
- README / README.ja: update `/hello` Pipeline and HTTPS Pipeline tables with 3-run median measurements ([#239])

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

<!-- PR reference definitions for [Unreleased] section -->
[#69]: https://github.com/fukusaka/keel/pull/69
[#115]: https://github.com/fukusaka/keel/pull/115
[#116]: https://github.com/fukusaka/keel/pull/116
[#117]: https://github.com/fukusaka/keel/pull/117
[#118]: https://github.com/fukusaka/keel/pull/118
[#119]: https://github.com/fukusaka/keel/pull/119
[#141]: https://github.com/fukusaka/keel/pull/141
[#143]: https://github.com/fukusaka/keel/pull/143
[#148]: https://github.com/fukusaka/keel/pull/148
[#151]: https://github.com/fukusaka/keel/pull/151
[#163]: https://github.com/fukusaka/keel/pull/163
[#174]: https://github.com/fukusaka/keel/pull/174
[#179]: https://github.com/fukusaka/keel/pull/179
[#183]: https://github.com/fukusaka/keel/pull/183
[#184]: https://github.com/fukusaka/keel/pull/184
[#185]: https://github.com/fukusaka/keel/pull/185
[#186]: https://github.com/fukusaka/keel/pull/186
[#187]: https://github.com/fukusaka/keel/pull/187
[#188]: https://github.com/fukusaka/keel/pull/188
[#191]: https://github.com/fukusaka/keel/pull/191
[#195]: https://github.com/fukusaka/keel/pull/195
[#197]: https://github.com/fukusaka/keel/pull/197
[#198]: https://github.com/fukusaka/keel/pull/198
[#199]: https://github.com/fukusaka/keel/pull/199
[#202]: https://github.com/fukusaka/keel/pull/202
[#204]: https://github.com/fukusaka/keel/pull/204
[#207]: https://github.com/fukusaka/keel/pull/207
[#210]: https://github.com/fukusaka/keel/pull/210
[#211]: https://github.com/fukusaka/keel/pull/211
[#212]: https://github.com/fukusaka/keel/pull/212
[#213]: https://github.com/fukusaka/keel/pull/213
[#215]: https://github.com/fukusaka/keel/pull/215
[#216]: https://github.com/fukusaka/keel/pull/216
[#217]: https://github.com/fukusaka/keel/pull/217
[#218]: https://github.com/fukusaka/keel/pull/218
[#219]: https://github.com/fukusaka/keel/pull/219
[#220]: https://github.com/fukusaka/keel/pull/220
[#221]: https://github.com/fukusaka/keel/pull/221
[#222]: https://github.com/fukusaka/keel/pull/222
[#223]: https://github.com/fukusaka/keel/pull/223
[#226]: https://github.com/fukusaka/keel/pull/226
[#227]: https://github.com/fukusaka/keel/pull/227
[#228]: https://github.com/fukusaka/keel/pull/228
[#229]: https://github.com/fukusaka/keel/pull/229
[#230]: https://github.com/fukusaka/keel/pull/230
[#232]: https://github.com/fukusaka/keel/pull/232
[#233]: https://github.com/fukusaka/keel/pull/233
[#234]: https://github.com/fukusaka/keel/pull/234
[#235]: https://github.com/fukusaka/keel/pull/235
[#236]: https://github.com/fukusaka/keel/pull/236
[#239]: https://github.com/fukusaka/keel/pull/239
[#241]: https://github.com/fukusaka/keel/pull/241
[#242]: https://github.com/fukusaka/keel/pull/242
[#243]: https://github.com/fukusaka/keel/pull/243
[#244]: https://github.com/fukusaka/keel/pull/244
[#245]: https://github.com/fukusaka/keel/pull/245
[#246]: https://github.com/fukusaka/keel/pull/246
[#247]: https://github.com/fukusaka/keel/pull/247
[#248]: https://github.com/fukusaka/keel/pull/248
[#249]: https://github.com/fukusaka/keel/pull/249
[#250]: https://github.com/fukusaka/keel/pull/250
[#251]: https://github.com/fukusaka/keel/pull/251
[#252]: https://github.com/fukusaka/keel/pull/252
[#253]: https://github.com/fukusaka/keel/pull/253
[#254]: https://github.com/fukusaka/keel/pull/254
[#255]: https://github.com/fukusaka/keel/pull/255
[#257]: https://github.com/fukusaka/keel/pull/257
[#258]: https://github.com/fukusaka/keel/pull/258
[#260]: https://github.com/fukusaka/keel/pull/260
[#261]: https://github.com/fukusaka/keel/pull/261
[#263]: https://github.com/fukusaka/keel/pull/263
[#264]: https://github.com/fukusaka/keel/pull/264
[#265]: https://github.com/fukusaka/keel/pull/265
[#266]: https://github.com/fukusaka/keel/pull/266
[#267]: https://github.com/fukusaka/keel/pull/267
[#269]: https://github.com/fukusaka/keel/pull/269
[#271]: https://github.com/fukusaka/keel/pull/271
[#272]: https://github.com/fukusaka/keel/pull/272
[#273]: https://github.com/fukusaka/keel/pull/273
[#274]: https://github.com/fukusaka/keel/pull/274
[#275]: https://github.com/fukusaka/keel/pull/275
[#276]: https://github.com/fukusaka/keel/pull/276
[#277]: https://github.com/fukusaka/keel/pull/277
[#278]: https://github.com/fukusaka/keel/pull/278
[#279]: https://github.com/fukusaka/keel/pull/279
[#280]: https://github.com/fukusaka/keel/pull/280
[#281]: https://github.com/fukusaka/keel/pull/281
[#282]: https://github.com/fukusaka/keel/pull/282
[#283]: https://github.com/fukusaka/keel/pull/283
[#284]: https://github.com/fukusaka/keel/pull/284
[#285]: https://github.com/fukusaka/keel/pull/285
[#286]: https://github.com/fukusaka/keel/pull/286
[#288]: https://github.com/fukusaka/keel/pull/288
[#291]: https://github.com/fukusaka/keel/pull/291
[#292]: https://github.com/fukusaka/keel/pull/292
[#293]: https://github.com/fukusaka/keel/pull/293
[#294]: https://github.com/fukusaka/keel/pull/294
[#295]: https://github.com/fukusaka/keel/pull/295
[#296]: https://github.com/fukusaka/keel/pull/296
[#297]: https://github.com/fukusaka/keel/pull/297
[#298]: https://github.com/fukusaka/keel/pull/298
[#299]: https://github.com/fukusaka/keel/pull/299
[#300]: https://github.com/fukusaka/keel/pull/300
[#301]: https://github.com/fukusaka/keel/pull/301
[#302]: https://github.com/fukusaka/keel/pull/302
[#304]: https://github.com/fukusaka/keel/pull/304
[#305]: https://github.com/fukusaka/keel/pull/305
[#306]: https://github.com/fukusaka/keel/pull/306
[#307]: https://github.com/fukusaka/keel/pull/307
[#309]: https://github.com/fukusaka/keel/pull/309
[#310]: https://github.com/fukusaka/keel/pull/310
[#311]: https://github.com/fukusaka/keel/pull/311
[#312]: https://github.com/fukusaka/keel/pull/312
[#313]: https://github.com/fukusaka/keel/pull/313
[#316]: https://github.com/fukusaka/keel/pull/316
[#317]: https://github.com/fukusaka/keel/pull/317
[#318]: https://github.com/fukusaka/keel/pull/318
[#319]: https://github.com/fukusaka/keel/pull/319
[#320]: https://github.com/fukusaka/keel/pull/320
[#321]: https://github.com/fukusaka/keel/pull/321
[#323]: https://github.com/fukusaka/keel/pull/323
[#324]: https://github.com/fukusaka/keel/pull/324
[#325]: https://github.com/fukusaka/keel/pull/325
