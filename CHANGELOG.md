# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- **BREAKING**: `IoEngine` interface was promoted from `AutoCloseable` to `kotlinx.coroutines.CoroutineScope`, and `close()` from a non-suspend method to `suspend fun close()`. All seven engine implementations (`NioEngine`, `NettyEngine`, `KqueueEngine`, `EpollEngine`, `IoUringEngine`, `NwEngine`, `NodeEngine`) now carry a `SupervisorJob` in their `coroutineContext`, and `close()` internally does `coroutineContext.job.cancelAndJoin()` before tearing the underlying dispatcher / listener down. Callers that previously wrote `engine.close()` from a non-suspend context must now wrap with `runBlocking { engine.close() }` (JVM/Native) or schedule on a `CoroutineScope`; callers already inside a coroutine can call it directly. Callers wishing to launch I/O work on the engine scope should pass an explicit dispatcher — typically `channel.ioDispatcher` — when invoking `engine.launch(...)`, since the engine's context intentionally carries no default dispatcher. `IoEngine : AutoCloseable` was removed; `engine.use { }` is no longer supported because `suspend fun close()` cannot participate in `AutoCloseable.use`. Rationale: closes the structured-concurrency gap at the engine boundary — children suspended on engine-managed dispatchers are cancelled and drained while the dispatcher is still alive, eliminating the shutdown hang fixed in this release.
- keel-engine-epoll, keel-engine-kqueue: add `assertInEventLoop(operation)` and promote `inEventLoop()` to internal, matching the pattern established in `IoUringEventLoop` (PR #276). Runtime guardrails for `drainTasks` (both engines) and `dispatchReady` (epoll) enforce EL-thread affinity on methods that are strictly EL-only. Cross-thread callable methods (`register`, `registerCallback`, `cleanupFd`, `dispatch`) remain unasserted — they use `withRegLock` or MPSC queues for cross-thread safety. Soft quality improvement: documents invariants and catches future cross-thread misuse as loud `IllegalStateException` instead of silent state corruption.
- benchmark: `bench-pull.sh` no longer hardcodes an internal benchmark host as its default. Callers must pass the host either as a positional argument (`./benchmark/bench-pull.sh <host>`) or via the new `BENCH_REMOTE_HOST` environment variable; the script prints a usage message on stderr when neither is supplied. The remote working-directory fallback is now generic (`~/prj/keel-work/keel`, tilde-relative) instead of an absolute path rooted at a specific user's home.
- scrub internal benchmark-host identifiers from public-facing text: the four `[Unreleased]` `Added` entries that cited an A/B measurement (`MSG_RING`, `DEFER_TASKRUN`, `COOP_TASKRUN`, `HttpResponseEncoder` zero-copy), the `IoUringCapabilities` KDoc for `singleIssuer` and `deferTaskrun`, `benchmark/README.md`, `benchmark/module.md`, `scripts/install-awslc.sh`. A/B numbers and the `Nt/Mc /hello` benchmark shorthand are preserved; only the FQDN is removed.
- keel-engine-io-uring: restructure ring and register-class lifecycle so `io_uring_queue_init` and all `io_uring_register_*` calls run on the owning EventLoop pthread. The three per-EventLoop register classes (`FixedFileRegistry`, `ProvidedBufferRing`, `RegisteredBufferTable`) now follow a 2-phase lifecycle (user-space alloc in constructor, kernel registration in `initOnEventLoop()`). `IoUringEventLoopGroup` orchestrates the per-loop init via dispatch, and `IoUringEventLoop.onExitHook` runs the register-class teardown on the pthread before the ring is destroyed. `IoUringEngine.connect` / `IoUringServer.accept` construct the transport via `withContext(workerLoop)`; `IoUringIoTransport.close()` dispatches its teardown to the EventLoop. No observable API change; prerequisite for `IORING_SETUP_SINGLE_ISSUER`.
- All Native engines (epoll, kqueue, io_uring) and TLS code: switched syscall-error message construction from `strerror` to the new `errnoMessage` helper. No behavioural change in error messages.

### Fixed

- keel-ktor-engine: `KeelApplicationEngine.stop()` now returns within the configured grace period for every shutdown scenario (no clients, idle keep-alive connections, in-flight suspending handlers). Previously `stop()` always waited out the full `timeoutMillis` when any connection handler or keep-alive reader was suspended on an engine dispatcher, because `IoEngine.close()` was non-suspend and tore the dispatcher down while children were still parked on it. Cancel resumes were then dispatched to a dead dispatcher and never fired, `serverJob.join()` never completed, and the grace/timeout clock drained to zero. The fix routes `handleConnection` through `ioEngine.launch(...)` so handlers are children of the engine's `SupervisorJob`, moves `ioEngine.close()` into `stopSuspend`'s `finally`, and adds an explicit `ioEngine.coroutineContext.job.children.join()` during the grace phase. Combined with the `IoEngine` CoroutineScope promotion above, this closes the structured-concurrency invariant at the engine boundary. Regression covered by `EngineStopLifecycleTest` in `keel-ktor-engine` — 20 idle keep-alive connections and an in-flight `delay(60 s)` handler now both complete `stop(500, 1000)` in < 1 s.
- keel-engine-nio: fix `ClassCastException: CompletedContinuation cannot be cast to DispatchedContinuation` that intermittently fired on `Dispatchers.Default` workers when a `NioEngine`-bound server (e.g. `ktor-keel-nio`) was shut down after handling connections. Root cause: `NioEventLoop.processSelectedKeys` dispatched selector attachments via `when (attachment) { is Runnable -> ...; is CancellableContinuation<*> -> ... }`, but `CancellableContinuationImpl` transitively implements `Runnable` (via `DispatchedTask → scheduling.Task`), so the `Runnable` branch always won and the `CancellableContinuation<*>` branch was dead code. The selector thread invoked `DispatchedTask.run()` directly on the continuation — type erasure made this appear to work (the `Active` state object flowed through `state as T` and was silently treated as `Unit` by the user state machine) but the continuation's own state never transitioned to `CompletedContinuation`, leaving it installed as a stale child handler on the parent Job. Parent-Job cancellation then triggered `cont.cancel()` which dispatched a second `resumeWith` to the already-completed user state machine, where `releaseIntercepted` encountered the `CompletedContinuation` sentinel from the first completion and the `as DispatchedContinuation<*>` cast blew up. Fix: restructure `NioServer.accept` and `NioEngine.connect` to attach a plain `Runnable { cont.resume(Unit) }` callback (via `setInterestCallback`) instead of the continuation itself, so selector attachments are always plain `Runnable` and `CancellableContinuation.resume(Unit)` properly CAS-transitions state and detaches parent handles. Removed the now-unused `NioEventLoop.setInterest(CancellableContinuation)` overload and the dead `is CancellableContinuation<*>` branch in `processSelectedKeys`, and documented the "attachments must be plain Runnable" invariant on `setInterestCallback`. Added `NioShutdownRaceTest` regression coverage for both the accept-cancel and connect-cancel paths.
- keel-engine-io-uring: check return values for previously-ignored teardown syscalls. `RegisteredBufferTable.close` (`io_uring_unregister_buffers`), `FixedFileRegistry.unregister`/`close` (`io_uring_register_files_update`/`io_uring_unregister_files`), `ProvidedBufferRing.close` (`io_uring_free_buf_ring`), `IoUringEventLoop.start` (`pthread_create` — now fail-fast), `IoUringEventLoop.close` (`pthread_join`, `close(wakeupFd)`), `IoUringEventLoop.wakeup` (`keel_eventfd_write`, EAGAIN treated as benign), `IoUringIoTransport.shutdownOutput` (`shutdown(fd, SHUT_WR)`), and `close(fd)` calls in `IoUringEngine.connect` / `IoUringServer.accept`/`close` / `IoUringPipelinedServerChannel.close` cleanup paths. Failures emit a warn-level log so silent kernel-side errors are observable without masking the original exception.

### Added

- keel-engine-io-uring: IO_WQ max workers limits (Linux 5.15+, opt-in). Adds `IoUringCapabilities.iowqMaxBoundedWorkers` and `iowqMaxUnboundedWorkers` (both default `0`). When either is > 0, each EventLoop calls `io_uring_register_iowq_max_workers` on its ring after `io_uring_queue_init`, limiting the number of kernel worker threads the ring's IO_WQ pool can spawn. Bounded workers handle operations with bounded execution time (buffered file I/O); unbounded workers handle operations without bounded time (socket I/O, polling). **Measured on luna (32-core) with pipeline-http-io-uring**: keel's hot path (multishot + SEND_ZC) does not spawn IO_WQ workers — `/proc/<pid>/task` stays at 36 threads (32 EL pthreads + GC + Main) under load with or without a cap. This is primarily an **operational control** for high-density deployments where many keel instances per host share the `nr_threads` budget — not a throughput optimisation. On a 64-core host with 8 EventLoops, the default ceiling is ~1024 unbounded worker threads across all rings; explicit limits allow operators to constrain the kernel thread count without affecting the hot path (remote LAN SEND_ZC A/B with bounded=2 unbounded=4 showed -4 % median, within variance).
- keel-engine-io-uring: NAPI busy-poll registration (Linux 6.9+, opt-in). Adds `IoUringCapabilities.napiBusyPoll` (default `false`), `napiBusyPollTimeoutUs` (default 50), and `napiPreferBusyPoll` (default `false`). When enabled, each EventLoop registers a NAPI busy-poll configuration on its ring after `io_uring_queue_init`: the kernel busy-polls the NIC drivers of sockets added to the ring (auto-tracked by NAPI ID) instead of sleeping on IRQ-driven wake-ups, eliminating the packet-to-CQE IRQ latency (~1-3 µs per packet) at the cost of higher CPU utilisation during polling windows. **Measured effect** (pipeline-http-io-uring with SEND_ZC, 4t/100c /hello, remote LAN with real NIC): `napiBusyPoll = true` with 50 µs timeout shows +13.4 % throughput (410 K → 465 K req/s), p50 -4 %, p99 +21 % — latency distribution shifts to lower average but wider tail. `napiPreferBusyPoll = true` produces no throughput benefit (-1 %) and hurts p50 (+13 %); keep the default off. Not auto-enabled in `detect()` even on kernel 6.9+ because the CPU cost regression on unsuitable workloads (loopback, low traffic, shared cores) is significant. liburing 2.5 (Ubuntu 24.04) lacks the `io_uring_register_napi` helper, so keel invokes `io_uring_register` via raw syscall with kernel-ABI-stable opcode numbers (`IORING_REGISTER_NAPI = 27`, `IORING_UNREGISTER_NAPI = 28`). Opt in via `detect(ring).copy(napiBusyPoll = true, napiBusyPollTimeoutUs = 50)` on systems with pinned EL cores, real NIC traffic, and average-latency-sensitive workloads (where p99 tail expansion is acceptable).
- keel-engine-io-uring: direct-allocated multishot accept (Linux 5.19+, opt-in). Adds `IoUringCapabilities.acceptDirectAlloc` (default `false`). When enabled, the pipelined server path uses `IORING_OP_MULTISHOT_ACCEPT_DIRECT` with `IORING_FILE_INDEX_ALLOC`: the kernel picks a free slot in the registered file table and places the accepted fd there, reporting the allocated index in `cqe->res` instead of the raw fd. This saves the `io_uring_register_files_update` syscall per accept (one less SI-locked syscall per connection setup). Direct-allocated slots route `shutdown(SHUT_WR)` through `IORING_OP_SHUTDOWN` + `IOSQE_FIXED_FILE` and rely on slot unregister for close. Requires `fixedFiles = true`. Opt in via `detect(ring).copy(acceptDirectAlloc = true)`. Also replaces `FixedFileRegistry`'s internal free-slot stack with a bitmap so `claim()` for kernel-allocated slots is O(1) instead of O(maxFiles). **Measured effect** (pipeline-http-io-uring, 4t/100c /hello): remote LAN SEND_ZC keep-alive +5.5 % (412 K → 435 K req/s) with noticeably tighter run-to-run variance; remote LAN SEND_ZC Connection: close +1.7 %; loopback keep-alive with default `FALLBACK_CQE` IoMode regresses 3.6 % because direct-allocated slots force write-path coercion to `CQE` (no raw fd for direct `send()` syscall). Recommend enabling only with `SEND_ZC` / `SENDMSG_ZC` IoMode on real NICs.
- benchmark: `benchmark/bench-remote.sh` — single-engine benchmark that runs the server on one ssh host (`BENCH_REMOTE_HOST`) and drives wrk from a separate ssh client host (`BENCH_CLIENT_HOST`) against `BENCH_SERVER_IP` over a real network link, complementing `bench-one.sh`'s loopback measurement. wrk is auto-detected on the client (`native` if installed, otherwise `docker` via `williamyeh/wrk:latest --network=host`); override with `BENCH_WRK_MODE`. Output format matches `bench-one.sh` (`<name>|<rps>|<p50>|<p99>` or `<name>|<median>|<p50>|<p99>|[<all_rps>]` when `BENCH_RUNS>1`). Formalises the ad-hoc remote A/B scaffolding used during PR #280 so io_uring (and future) optimisations that depend on enter-syscall frequency can be measured under real NIC latency rather than sub-microsecond loopback conditions.
- keel-engine-io-uring: `io_uring_register_ring_fd` self-registration (Linux 5.18+). Adds `IoUringCapabilities.registerRingFd` (default `true`, auto-enabled in `detect()` on supported kernels). Each EventLoop self-registers its own ring fd after `initRing` on the EL pthread, so subsequent `io_uring_submit_and_wait` calls take the `IORING_ENTER_REGISTERED_RING` fast path and the kernel skips the per-syscall fd-table lookup on the ring fd. Paired `io_uring_unregister_ring_fd` runs in the EL `loop()` epilogue before `io_uring_queue_exit`. Remote A/B on a LAN pair (4t/100c /hello, pipeline-http-io-uring, wrk on a separate client host) showed +5.3 % throughput with the default `singleIssuer = true` (400,905 → 422,209 req/s) and +9.0 % with `singleIssuer = false` (400,083 → 436,214 req/s). Loopback A/B on the same server showed -2.6 % because per-enter work on loopback is already sub-microsecond and the `register_ring_fd` / `unregister_ring_fd` bookkeeping cost is not amortised; loopback-optimal benchmarks can opt out via `IoUringCapabilities.detect(ring).copy(registerRingFd = false)`.
- keel-engine-io-uring: `IORING_OP_MSG_RING`-based cross-EventLoop wakeup (Linux 5.18+, opt-in). Adds `IoUringCapabilities.msgRingWakeup` (default `false`, including in `detect()`). When enabled and the dispatch caller is running on another keel EventLoop pthread, the source EL submits a MSG_RING SQE on its own ring targeting the peer's ring fd instead of writing to the peer's eventfd. No syscall is issued by the source (the SQE is flushed in its next `io_uring_submit_and_wait`), and the kernel synthesises a CQE on the target ring that returns the peer from its wait. External-thread callers (e.g. `engine.close()` from main thread, `Dispatchers.Default`) continue to use `eventfd_write` unconditionally, because io_uring has no facility to submit SQEs without a caller-owned ring. Loopback `/hello` A/B (4t/100c, pipeline-http-io-uring) showed -1.7 % throughput (898 K → 883 K) within the variance band and no change in p50/p99 — intended workloads are those with frequent cross-EL dispatch (coroutine Channels, codec handoff, RPC fan-out), which loopback does not exercise. Opt in via `IoUringCapabilities.detect(ring).copy(msgRingWakeup = true)`.
- keel-engine-io-uring: `IORING_SETUP_DEFER_TASKRUN` ring setup flag (Linux 6.1+, opt-in). Adds `IoUringCapabilities.deferTaskrun` (default `false`, including in `detect()`). Requires `singleIssuer = true` (enforced by kernel). When enabled, task_work is deferred to `io_uring_enter(GETEVENTS)` calls so deferred completion work only runs inside `IoUringEventLoop.loop`'s `io_uring_submit_and_wait`, smoothing tail latency at the cost of slightly less eager completion delivery. Loopback A/B benchmark (4t/100c /hello) showed throughput -0.9 % (870 K → 862 K req/s, at variance boundary) and p99 latency -3.2 % (373 µs → 361 µs, improvement). Opt in via `IoUringCapabilities.detect(ring).copy(deferTaskrun = true)` for latency-sensitive deployments.
- keel-engine-io-uring: `IORING_SETUP_SINGLE_ISSUER` ring setup flag (Linux 6.0+, enforced 6.2+). Adds `IoUringCapabilities.singleIssuer` (auto-enabled when kernel >= 6.0). The flag asserts a single issuer pthread for all `io_uring_register_*` and `io_uring_enter` calls, allowing the kernel to skip internal submission-side locks. Safe for keel post-PR #276 (ring lifecycle refactor), which moved all kernel calls onto the owning EventLoop pthread. Loopback benchmark showed no measurable effect (<1% within variance), but the kernel-side lock elimination applies under real-NIC multi-core contention.
- keel-native-posix: `errnoMessage(errno: Int): String` helper that wraps a thread-safe `strerror_r(3)` call (`keel_errno_message` C wrapper). Returns `"<message> (errno=N)"` so logs include both symbolic and numeric forms. Replaces direct uses of `platform.posix.strerror`, which is not required to be thread-safe per POSIX and may race on older glibc or non-glibc libc implementations.
- keel-engine-io-uring: `IORING_SETUP_COOP_TASKRUN` ring setup flag (Linux 6.0+). Adds `IoUringCapabilities.coopTaskrun` (auto-enabled when kernel >= 6.0). The flag defers task_work execution to `io_uring_enter` calls, eliminating IPI overhead from task_work processing on every syscall return. Safe for keel's EventLoop model because the loop always blocks in `io_uring_submit_and_wait`. Loopback benchmark showed no measurable effect (<1% within run-to-run variance), but the kernel-side IPI reduction applies when deployed on real NICs with multi-core contention.
- keel-core: `AbstractPipelinedChannel` base class in commonMain. Wires `IoTransport` callbacks (`onRead` → `pipeline.notifyRead`, `onReadClosed` → `pipeline.notifyInactive`, `onWritabilityChanged` → `pipeline.notifyWritabilityChanged`) and provides default implementations for `ensureBridge`, `shutdownOutput`, `close`, `awaitFlushComplete`, `awaitClosed`, and all channel properties by delegating to the transport. Engine subclasses require no additional overrides for the common case.
- All engines: `Server.accept()` now automatically calls `BindConfig.initializeConnection()` on each accepted channel. Passing `TlsConnectorConfig` to `bind()` enables transparent Channel mode TLS — `channel.read()` returns decrypted plaintext and `channel.write()` encrypts transparently, without manual pipeline setup.
- keel-io: `BufferAllocator.registerPoolSize(size, maxSlots)` — dynamic multi-size-class pool registration for `PooledDirectAllocator` (JVM) and `SlabAllocator` (Native). Allows engines and pipeline handlers to register custom buffer size classes (e.g., 16 KiB for TLS plaintext) with per-class slot limits and a global `maxTotalBytes` safety valve. `createForEventLoop()` propagates registered classes to per-EventLoop child allocators.
- keel-io: `BufferAllocator.wrapBytes(ByteArray, Int, Int)` — wraps a `ByteArray` as a zero-copy read-only `IoBuf` view using platform-native backing (pinned pointer on Native, heap ByteBuffer on JVM). Replaces the standalone `tryWrapBytes` extension. Native support enables zero-copy response body emission that was previously JVM-only.
- keel-io: `BufferAllocator.slice(IoBuf, Int, Int)` — creates a read-only `IoBuf` view of an existing buffer's byte range using the same platform-native type (NativeIoBuf on Native, DirectIoBuf on JVM), ensuring transport compatibility. The parent buffer is retained and released via the deallocator when the slice is released.
- keel-core: `SuspendMessageBridge<T>` — generic pipeline handler that bridges typed inbound messages to a suspendable `Channel<T>`, enabling coroutine-based consumers to receive pipeline-decoded messages without writing event-driven handlers. Used by `keel-ktor-engine` to receive `HttpRequest` from `HttpBodyAggregator`.
- keel-io: `EmptyIoBuf` singleton — zero-capacity `IoBuf` where all read/write methods throw and `retain`/`release` are no-ops. Used as the backing buffer for `HttpBodyEnd.EMPTY`.
- keel-codec-http: `HttpMessage` sealed interface as the common supertype for all streaming HTTP pipeline messages (`HttpRequestHead`, `HttpResponseHead`, `HttpBody`, `HttpBodyEnd`).
- keel-codec-http: `HttpBody` and `HttpBodyEnd` streaming body message types for both Content-Length and chunked transfer-encoding bodies. `HttpBodyEnd.EMPTY` is a trailer-less singleton that avoids per-request allocation.
- keel-codec-http: `HttpHeaders.EMPTY` shared immutable-by-convention empty headers singleton.
- keel-codec-http: `HttpBodyAggregator` pipeline handler that buffers streaming `HttpRequestHead` + `HttpBody` + `HttpBodyEnd` into a complete `HttpRequest(body: ByteArray?)` with configurable `maxContentLength` (default 1 MiB).
- keel-io: `BufferAllocator.tryWrapBytes(ByteArray, Int, Int)` extension that returns a zero-copy `IoBuf` view of an existing `ByteArray`, so codec writers in other modules can opt into wrapping instead of copying. JVM returns a `DirectIoBuf` over `ByteBuffer.wrap`; Native and JS return `null` and callers fall back to an `allocate` + copy path.
- keel-io: direct-write path in `BufferedSuspendSink.write(ByteArray, Int, Int)` that forwards payloads at or above 8 KiB as a zero-copy `IoBuf` view of the caller's array via the new `wrapBytesAsIoBuf` expect/actual helper (JVM only; Native and JS fall back to the chunked copy path). Eliminates the 8 KiB scratch chunking that split large responses into many small sink writes, driving JVM engine `/large` throughput from 78 K to 207 K req/s on `ktor-keel-netty` and collapsing the 47 % run-to-run variance to 10 %.
- keel-io: add `LeakDetectingAllocator` with Cleaner-based leak detection (Native: `createCleaner`, JVM: `PhantomReference`)
- keel-io: add `BufferAllocator.withTracking()` and `BufferAllocator.withLeakDetection()` extension functions for fluent allocator composition
- keel-core: write backpressure with high/low water mark on `IoTransport` (`isWritable`, `onWritabilityChanged`)
- keel-core: `ChannelInboundHandler.onWritabilityChanged()` and `ChannelPipeline.notifyWritabilityChanged()` for pipeline propagation
- All 7 engines: `pendingBytes` tracking in IoTransport, `PipelinedChannel.isWritable` connected to transport
- website: add coroutine architecture page to sidebar navigation

### Changed

- keel-core: rename Channel-prefixed pipeline types to avoid confusion with the `Channel` interface. `ChannelPipeline` → `Pipeline`, `ChannelHandler` → `PipelineHandler`, `ChannelHandlerContext` → `PipelineHandlerContext`, `ChannelInboundHandler` → `InboundHandler`, `ChannelOutboundHandler` → `OutboundHandler`, `ChannelDuplexHandler` → `DuplexHandler`, `DefaultChannelPipeline` → `DefaultPipeline`, `TypedChannelInboundHandler` → `TypedInboundHandler`.
- keel-ktor-engine: full pipeline HTTP codec migration. Request parsing uses `HttpRequestDecoder` + `HttpBodyAggregator` + `SuspendMessageBridge<HttpRequest>` (Phase 2α). Response output uses `HttpResponseHead` + `HttpBody` + `HttpBodyEnd` through pipeline `HttpResponseEncoder` (Phase 2β). `BufferedSuspendSink` removed from normal response path; retained only for `respondBadRequest` fallback when the pipeline is in an inconsistent state.
- keel-engine-netty: `NettyEngine` now instantiates one buffer allocator per worker `EventLoop` (lazy via `config.allocator.createForEventLoop()`) instead of sharing the engine-wide `config.allocator` across every accepted channel. Each allocator is only ever touched by its owning event loop thread, removing the CAS hotspot on the shared freelist that previously cancelled out the benefit of pool hits on high-throughput JVM workloads.
- keel-io: `PooledDirectAllocator.createForEventLoop()` now returns an allocator with a smaller local pool (16 slots vs the 256-slot default) so that the total direct memory footprint is bounded by `numEventLoops × 16 × bufferSize`, independent of the number of open connections.
- keel-core: `BindConfig` converted from marker interface to open class with `backlog` parameter (default 128)
- keel-core: `StreamEngine.bind()` and `bindPipeline()` accept `BindConfig` with backlog propagated to all engines
- keel-tls: `TlsConnectorConfig.installer` is now nullable — `null` means engine-native (listener-level) TLS
- keel-tls: `TlsConnectorConfig` accepts `backlog` parameter inherited from `BindConfig`
- keel-codec-http: refactor `HttpRequestDecoder` to a byte-offset parser that scans `IoBuf` bytes directly via `IoBuf.getByte(index)` instead of accumulating them into a `StringBuilder` one character at a time and splitting the resulting `String` with `substring` / `trim`. The state machine (`READ_REQUEST_LINE → READ_HEADERS → SKIP_BODY`), MAX_LINE_SIZE = 8192 enforcement, RFC 7230 compliance (Host header requirement, obs-fold rejection, Content-Length + Transfer-Encoding conflict rejection), CRLF / LF-only line ending support, pipelining behaviour, and every `HttpParseException` message are all preserved unchanged — external behaviour is identical and the full 21-case test suite passes without modification. The refactor uses a fast path when an entire line fits in the current `IoBuf` (zero intermediate allocations beyond the stored `uri`, header name, and header value `String` instances), and falls back to a lazily allocated per-decoder `ByteArray` accumulator when a line spans more than one `IoBuf`. The fallback accumulator starts at 256 B, grows by doubling up to the 8192 B `MAX_LINE_SIZE` cap, and is reused across lines and even across parse errors. Method and version lookups go through two new internal byte-level helpers (`HttpMethod.fromBytesOrNull(IoBuf/ByteArray, Int, Int)` and `HttpVersion.fromBytes(IoBuf/ByteArray, Int, Int)`) that return cached constants for standard tokens without allocating a `String`; the `HttpMethod.of(String)` extension-method path is retained for RFC 7231 custom methods. Three regression tests are added to `HttpRequestDecoderTest` for LF-only cross-buffer splits, the LF-at-IoBuf-boundary edge, and a header line that grows the fallback accumulator to the MAX_LINE_SIZE limit.
- keel-codec-http: `HttpRequestDecoder` now decodes Content-Length and chunked transfer-encoding request bodies into `HttpBody` / `HttpBodyEnd` sequences instead of silently skipping body bytes. Every request terminates with exactly one `HttpBodyEnd`, including no-body requests (`HttpBodyEnd.EMPTY`). Chunked trailers are delivered via `HttpBodyEnd.trailers`. The `SKIP_BODY` state is replaced by `READ_FIXED_BODY`, `READ_CHUNK_SIZE`, `READ_CHUNK_DATA`, `READ_CHUNK_DATA_CRLF`, and `READ_CHUNK_TRAILER`.
- keel-codec-http: `HttpResponseEncoder` accepts streaming `HttpResponseHead` + `HttpBody` + `HttpBodyEnd` sequences in addition to the legacy `HttpResponse` path. FIXED mode (Content-Length) passes body `IoBuf`s through with length accounting; CHUNKED mode wraps each chunk in hex-size framing (`{size}\r\n{data}\r\n`) and writes a `0\r\n{trailers}\r\n` terminator.
- keel-codec-http: `RoutingHandler` rewritten from `TypedChannelInboundHandler<HttpRequestHead>` to direct `ChannelInboundHandler` that silently releases `HttpBody` and `HttpBodyEnd` messages, preventing `TailHandler` warning logs.
- keel-core: `IoTransport` extended with read path (`onRead`, `onReadClosed`, `readEnabled`), lifecycle (`shutdownOutput`, `awaitClosed`), and properties (`allocator`, `isOpen`, `coroutineDispatcher`, `appDispatcher`, `supportsDeferredFlush`). All 7 engine IoTransport implementations now encapsulate the full read/write/shutdown/close lifecycle. Engine-specific PipelinedChannel classes reduced to empty `AbstractPipelinedChannel` subclasses.
- keel-tls: split `TLS_RECORD_BUF_SIZE` (17 KiB) into `TLS_PLAINTEXT_BUF_SIZE` (16 KiB) for `processInbound` and `TLS_CIPHERTEXT_BUF_SIZE` (17 KiB) for `processOutbound` / `flushHandshakeResponse`. `TlsHandler.handlerAdded` registers the 16 KiB plaintext size class via `registerPoolSize`, so inbound plaintext allocations hit the pool on steady-state connections.
- keel-io: `PooledDirectAllocator` (JVM) and `SlabAllocator` (Native) rewritten from single-size-class pool to multi-size-class pool using `ConcurrentHashMap<Int, Pool>` (lock-free Treiber stack per class) and `HashMap<Int, Pool>` (spin-lock protected ArrayDeque per class) respectively. Constructor signature changed from `(bufferSize, maxPoolSize)` to `(maxTotalBytes)` with default 8 KiB class auto-registered.

### Fixed

- keel-tls: harden `TlsHandler.processOutbound` against three latent codec-state issues that would otherwise silently truncate the outbound plaintext stream. (1) If a codec returns `TlsResult.OK` with `bytesConsumed == 0` and `bytesProduced == 0` while `plainBuf` still has readable bytes, the loop used to re-enter with identical state and spin forever; a stall guard now propagates a `TlsException` with `TlsErrorCategory.PROTOCOL_ERROR`. (2) `TlsResult.NEED_WRAP` from `protect` during application-data encoding used to silently break the loop — the caller saw the write as successful even though only a prefix of `plainBuf` had actually been encoded. The handler now propagates a `TlsException(PROTOCOL_ERROR)` because `TlsCodec` does not support interleaving post-handshake messages mid-plaintext. (3) `TlsResult.NEED_MORE_INPUT` is a signal specific to `unprotect` and is meaningless on the `protect` path; the handler now propagates a `TlsException(PROTOCOL_ERROR)` rather than silently breaking. Each branch matches the shape of the existing `BUFFER_OVERFLOW` handling and the `flushHandshakeResponse` stall check, and is covered by new `TlsHandlerTest` cases.
- keel-tls: neutralize the overflow-behaviour section of the `TlsHandler.TLS_RECORD_BUF_SIZE` KDoc added in the previous release. The original wording contained a parenthetical example claiming that "JSSE's SSLEngine emits [a `record_overflow` alert] when close is called after `BUFFER_OVERFLOW`", which is not generally accurate: `BUFFER_OVERFLOW` is JSSE's cooperative "retry with a larger buffer" signal and does not directly correlate with a wire-level alert. The new wording says the codec is responsible for emitting the on-wire `record_overflow` alert via its own handshake / shutdown state machine without naming a specific backend.
- keel-tls: `TlsHandler.processOutbound` now propagates a `TlsException` with `TlsErrorCategory.BUFFER_ERROR` when the codec returns `TlsResult.BUFFER_OVERFLOW`, and propagates inactive on `TlsResult.CLOSED`, instead of silently exiting the encode loop with a plain `if (result.status != TlsResult.OK) break`. Also wraps `codec.protect` in the same `try`/`catch` that `processInbound` already has around `codec.unprotect`, so a thrown `TlsException` during outbound encoding releases the scratch buffer and propagates through the pipeline error channel instead of unwinding the stack. This closes a silent-write-truncation path on the outbound side and brings the handler's overflow response into alignment with RFC 5246 §6.2.3 / RFC 8446 §5.2, which mandate terminating the connection when a TLS record cannot fit within the protocol ceiling. Adds three tests in `TlsHandlerTest` that cover the BUFFER_OVERFLOW response for all three call sites (`processInbound`, `processOutbound`, `flushHandshakeResponse`).
- keel-codec-http: `HttpResponseEncoder` now emits response bodies at or above 8 KiB as a zero-copy `IoBuf` view of the caller's `ByteArray` via `BufferAllocator.tryWrapBytes` (on platforms where wrapping is supported, i.e. JVM) instead of copying the bytes into a single fresh `allocateDirect(headers + body)` buffer per response. The head (status line + headers) is still written into an exact-sized pooled buffer; the body is propagated as a second outbound message so the downstream transport coalesces both into a single `writev` / `writeAndFlush`. On `pipeline-http-netty` `/large` this lifts throughput from 112 K to 261 K req/s (+133 %), moving the engine from 38 % to 90 % of the `netty-raw` reference ceiling.
- keel-engine-netty: `NettyPipelinedChannel.channelRead` now rounds the inbound IoBuf allocation up to 8 KiB (matching `PooledDirectAllocator`'s freelist slot) so that small TCP reads hit the pool instead of allocating a fresh `DirectByteBuffer`, `Cleaner` and `Deallocator` per packet. Combined with the per-event-loop allocator change above, this reduces `ktor-keel-netty` `/large` GC total pause from around 500 ms per 10 s run to around 55 ms, collapses p99 latency from 34.5 ms to 1.06 ms, and tightens the run-to-run range from 8.1 % to 3.4 %.
- keel-engine-netty: fix DirectIoBuf double-release on /large responses (`supportsDeferredFlush = true` for async Netty flush)
- keel-engine-nodejs: replace byte-by-byte copy with `Int8Array.subarray`/`set` in flush and read paths (/large +544%, /hello +10%)
- keel-engine-io-uring: use `writev` gather write in FALLBACK_CQE direct flush for multi-buffer responses (/large +1,253%)

### Removed

- keel-engine-nwconnection: remove `NwTlsInstaller` sentinel (replaced by `installer = null`)
- benchmark: remove `NodeTlsInstaller` and `MacosTlsInstallerInit` sentinels

### Added

- keel-engine-nwconnection: listener-level TLS via Network.framework (`SecIdentityCreate` keychain-free, macOS 10.12+)
- keel-engine-nwconnection: add `NwTlsParams` for creating NWConnection TLS parameters from DER cert/key
- keel-tls: add `PemDerConverter` for lossless PEM↔DER conversion (Base64 encode/decode)
- keel-tls: add `Pkcs8KeyUnwrapper` for extracting inner PKCS#1/SEC1 keys from PKCS#8 envelopes (needed by Apple SecKeyCreateWithData)
- keel-tls: add `asPem()`/`asDer()` extension functions on `TlsCertificateSource` for transparent format conversion
- keel-tls-openssl, keel-tls-awslc, keel-tls-mbedtls: accept `TlsCertificateSource.Der` via `asPem()` conversion
- keel-engine-netty: `NettySslInstaller` accepts `TlsCertificateSource.Der` via `asPem()` conversion
- keel-engine-nodejs: `NodeEngine` listener-level TLS accepts `TlsCertificateSource.Der` via `asPem()` conversion
- benchmark: add JS (Node.js) target with `pipeline-http-nodejs` benchmark using keel NodeEngine pipeline mode
- benchmark: add `pipeline-http-nodejs` to `bench-keel.sh` and `bench-all.sh`
- keel-engine-nodejs: implement `bindPipeline` for pipeline mode — enables push-model I/O without Ktor overhead
- keel-engine-netty: implement `bindPipeline` for pipeline mode — enables push-model I/O without Ktor overhead
- benchmark: add `pipeline-http-netty` benchmark using keel Netty pipeline mode
- benchmark: add TLS (`--tls`) support to all `pipeline-http-*` benchmarks (kqueue, epoll, io_uring, nio, nwconnection, netty)
- benchmark: add `pipeline-http-*` engines to `bench-keel.sh` and `bench-all.sh`
- benchmark: validate `--tls` backend at startup to detect mismatch with compiled backend
- keel-native-posix: add shared POSIX socket utilities module — extracts common SocketUtils (8 functions + cinterop wrappers) from epoll/kqueue/io_uring engines
- keel-core: add `onUserEvent`/`propagateUserEvent`/`notifyUserEvent` to Pipeline framework for user-defined inbound events (e.g., TLS handshake completion)
- keel-tls: add `:tls` module with `TlsCodec`/`TlsCodecFactory` buffer-to-buffer protection API (RFC 8446/9001 terminology: `protect`/`unprotect`)
- keel-tls: add `TlsHandler` ChannelDuplexHandler for Pipeline integration with zero-copy recv fast path
- keel-tls: add `TlsHandshakeComplete` user event and `TlsErrorCategory` structured error classification
- keel-tls-mbedtls: add `MbedTlsCodec` TlsCodec implementation with pointer-based BIO adapter (Mbed TLS 4.x, PSA Crypto)
- keel-tls-jsse: add `JsseTlsCodec` and `JsseTlsCodecFactory` — JSSE SSLEngine-backed TlsCodec for JVM
- keel-tls-openssl: add `OpenSslCodec` and `OpenSslCodecFactory` — OpenSSL 3.x pointer-based BIO TlsCodec for Native
- keel-tls-awslc: add `AwsLcCodec` and `AwsLcCodecFactory` — AWS-LC (BoringSSL fork) pointer-based BIO TlsCodec for Native
- keel-core: add `PipelinedServer` interface and `IoEngine.bindPipeline` (non-suspend, default throw for unsupported engines)
- build: add `detekt-formatting` (ktlint wrapper) for automated Kotlin coding conventions enforcement
- ci: add OpenSSL (`libssl-dev`) and AWS-LC install to CI and Dokka workflows for tls-openssl/tls-awslc/tls-jsse builds
- keel-ktor-engine: add HTTPS support via connector-based `sslConnector` DSL with `TlsHandler` pipeline injection
- keel-tls: add `TlsInstaller` interface for engine-specific TLS implementations
- keel-tls: add `TlsConnectorConfig` per-connector TLS configuration
- keel-engine-netty: add `NettySslInstaller` for Netty-native `SslHandler` TLS
- keel-core: add `BindConfig` interface for per-server bind configuration with `initializeConnection` callback
- keel-core: add `config: BindConfig?` parameter to `StreamEngine.bindPipeline` for declarative TLS setup
- keel-tls: `TlsConnectorConfig` implements `BindConfig` — delegates `initializeConnection` to `TlsInstaller.install`
- keel-engine-nodejs: add `tls.createServer()` support for Node.js listener-level TLS via `BindConfig`
- benchmark: add `--tls-installer=keel|netty|node` CLI option for selecting TLS backend per engine
- benchmark: add `--tls=jsse|openssl|awslc|mbedtls` CLI flag and `BENCH_SCHEME`/`BENCH_TLS` env vars for HTTPS benchmarking across all engines (keel, ktor-netty, netty-raw, spring, vertx, rust, go, swift)

### Removed

- keel-tls-mbedtls: remove `TestEngine` workaround and `findFreePort` — use `IoEngine.bindPipeline` + `PipelinedServer.localAddress` directly

### Fixed

- keel-tls: loop `TlsHandler.flushHandshakeResponse` to handle handshake flights exceeding single output buffer (e.g., long certificate chains)
- keel-tls-mbedtls: add `-ltfpsacrypto` linker option for Mbed TLS 4.x PSA Crypto library separation
- keel-tls-mbedtls: add `--allow-shlib-undefined` for Linux to resolve lld indirect glibc reference errors
- keel-core: enforce EventLoop thread for `PipelinedChannel` Channel mode (`read`/`write`/`flush`) via `withContext(coroutineDispatcher)` — fixes JMM visibility bug causing random test hang on 2-core CI
- keel-tls: remove `msg.release()` from `TlsHandler.onWrite` to fix double-release of outbound plaintext buffer causing `IndexOutOfBoundsException` under high-load HTTPS

### Changed

- keel-tls: `TlsCodecFactory` now implements `TlsInstaller` with default `install()` that creates server codec and adds `TlsHandler` at pipeline HEAD
- keel-tls: simplify `TlsConnectorConfig` from `codecFactory` + `installer?` to single `installer` field
- keel-ktor-engine: simplify `sslConnector()` API from 3 params (`tlsConfig`, `codecFactory`, `installer?`) to 2 params (`tlsConfig`, `installer`)
- keel-core: change `StreamEngine.bindPipeline` initializer from `(ChannelPipeline) -> Unit` to `(PipelinedChannel) -> Unit` — enables engine-specific TLS installers in pipeline mode
- benchmark: migrate all `pipeline-http-*` TLS setup from manual `TlsCodecFactory.install()` in pipelineInitializer to declarative `config = TlsConnectorConfig(...)` parameter
- benchmark: introduce `commonForKtorServerMain` intermediate source set to isolate Ktor Server dependencies from JS compilation
- keel-engine-nodejs: unify `NodeChannel` into `NodePipelinedChannel` — supports both Pipeline mode (push I/O) and Channel mode (SuspendBridgeHandler pull)
- build: promote `keel-tls-jsse` to always-included (JDK standard, no external deps); move `keel-tls-mbedtls` to `-Ptls` opt-in (requires Mbed TLS cinterop)
- keel-ktor-engine: HTTPS test no longer requires `-Ptls` flag (uses always-available `keel-tls-jsse`)
- keel-codec-http: expose `keel-io` and `keel-core` as `api` dependencies — fixes hidden transitive dependency for consumers using `BufferedSuspendSource`/`TypedChannelInboundHandler` in public signatures
- keel-core: introduce `StreamEngine` sub-interface for byte-stream transports (TCP); `IoEngine` becomes root with `val config` + `close()` only — prepares for future `DatagramEngine` (UDP)
- build: rename all public modules with `keel-` prefix (e.g., `:core` → `:keel-core`, `:engine-epoll` → `:keel-engine-epoll`, `:io-core` → `:keel-io`)
- keel-core: merge `:logging` module into `:keel-core` — Logger/LoggerFactory/LogLevel/PrintLogger now ship with core
- keel-engine-nwconnection: unify `NwChannel` and `NwPipelinedChannel` into single dual-mode `NwPipelinedChannel` — enables TLS/HTTPS via pipeline `TlsHandler` injection
- keel-engine-netty: unify `NettyChannel` into `NettyPipelinedChannel` with `NettyIoTransport` — enables TLS/HTTPS via pipeline `TlsHandler` injection (same pattern as NWConnection)
- benchmark: select single Native TLS backend via `-Ptls-backend=openssl|awslc|mbedtls` to avoid OpenSSL/AWS-LC symbol conflicts
- keel-core: rename `ServerChannel` to `Server` — a server is not a channel (`ServerChannel` typealias kept for backward compatibility)
- keel-ktor-engine: remove `engine-netty` and `netty-all` transitive dependency from jvmMain — users needing `NettySslInstaller` add `:engine-netty` explicitly
- keel-tls-nodejs: change dependency from `:core` to `:tls` for consistent module hierarchy
- all engines: rename `*ServerChannel` to `*Server` (e.g., `KqueueServerChannel` → `KqueueServer`)
- keel-io: rename `PushSuspendSource` to `OwnedSuspendSource`, `PushToSuspendSourceAdapter` to `OwnedToSuspendSourceAdapter`
- keel-core: remove `PushChannel` and `PushServerChannel` — Pipeline-incompatible design replaced by `Channel.asBufferedSuspendSource()` + `SuspendBridgeHandler.readOwned()`
- keel-core: add `Channel.asBufferedSuspendSource()` with zero-copy push-mode override in `PipelinedChannel`
- keel-engine-nio: make `bindPipeline` non-suspend via `registerChannelBlocking` (Pipeline zero-coroutine principle)
- keel-engine-nwconnection: make `bindPipeline` non-suspend via `dispatch_semaphore_wait`
- keel-engine-nio: unify `NioChannel` into `NioPipelinedChannel` — single type supports both Pipeline (push) and Channel (suspend) modes via `SuspendBridgeHandler`
- keel-engine-epoll: unify `EpollChannel` into `EpollPipelinedChannel` — same Channel/Pipeline unification pattern
- keel-engine-io-uring: unify `IoUringChannel` into `IoUringPipelinedChannel` — same Channel/Pipeline unification pattern
- engine-kqueue, engine-epoll, engine-nio: Channel mode `write()`/`flush()` now use `pipeline.requestWrite/Flush` directly instead of `ensureBridge()`, preventing read-path side effects on outbound operations
- keel-core: add `requestFlush()` (fire-and-forget) and `awaitFlushComplete()` (completion wait) to Channel interface; `flush()` is now `requestFlush() + awaitFlushComplete()` by default
- keel-engine-io-uring: integrate `IoModeSelector` into fire-and-forget `flush()` — CQE (with writev gather write), FALLBACK_CQE, and SEND_ZC modes all supported; remove `flushSuspend()` and suspend flush strategies

### Fixed

- keel-engine-kqueue: add `check(!closed)` guard to Channel mode `read()`/`write()`/`flush()` to prevent infinite suspend on closed channel
- engine-nio, engine-netty: add 10-second test timeout to all JVM tests to prevent CI hang
- keel-engine-epoll: fix `EpollEventLoop` fd registration to support concurrent READ + WRITE interests via `EPOLL_CTL_MOD` fallback
- keel-engine-io-uring: fix `IoUringIoTransport.flush()` data loss when EAGAIN occurs with multiple pending writes

### Documentation

- website: rewrite intro.md as a Getting Started guide — adds Quick Start (Ktor + keel), Pipeline vs Channel mode comparison table, and "keel vs Netty vs Ktor" positioning
- website: add performance-based engine selection tables and macOS→Linux development workflow to engine-guide.md
- website: document `BufferedSuspendSink` direct-write path and its 100 KB response throughput impact in buffer.md
- website: update pipeline.md performance numbers (870K epoll, 490K HTTPS epoll) and note direct-write Ktor Channel mode improvement
- website: add "keel vs Netty vs Ktor" positioning table to architecture overview
- README, README.ja: update `/hello` Pipeline benchmark values to latest 3-run medians; add `/large` Pipeline and Ktor tables
- README, README.ja: update HTTPS Pipeline table (508K io_uring, 490K epoll, 133K kqueue, 130K Netty JSSE on macOS)
- website: add Coroutine Mode section to http.md with suspend overloads (`parseRequestHead`/`writeResponseHead` via `BufferedSuspendSource`/`BufferedSuspendSink`); fix Pipeline handler addLast order (encoder must precede decoder); fix `println(request.version.text)`; expand Key Types with `HttpRequestHead` computed properties and `HttpRequest` factories
- website: deep-review websocket.md — reorder sections to Handshake→Parsing→Writing; add factory/maskKey table clarifying that `ping()`/`pong()` have no `maskKey` parameter; add `close(code, reason)` example; note sync-only API; expand RFC Compliance with masking direction rule and reserved close code wire constraint
- website: add Japanese translations for all documentation pages (architecture: overview, engine-guide, buffer, pipeline, tls, coroutine; codecs: http, websocket; intro)

- Dokka: document all visibility levels (public, internal, protected, private) for complete API reference
- Dokka: add source links to GitHub for each declaration
- Dokka: add `module.md` for all 13 modules with module and package descriptions
- Dokka: shorten navigation package names by removing `io.github.fukusaka.keel.` prefix via custom JavaScript
- Dokka: add `module.md` for all 6 TLS modules (`keel-tls`, `keel-tls-jsse`, `keel-tls-mbedtls`, `keel-tls-awslc`, `keel-tls-openssl`, `keel-tls-nodejs`) with TLS integration model, `TlsCodec`/`TlsHandler` architecture, backend-specific BIO transport details, and platform target tables
- Dokka: add `module.md` for `keel-native-posix` (POSIX socket utilities, C interop wrappers) and `benchmark` (engine registry, profiles, CLI config)
- Dokka: expand `benchmark/module.md` with standalone binary build commands, external reference servers (Rust/Go/Swift/Zig), benchmark script reference, and missing JVM pipeline engines (`pipeline-http-nio`, `pipeline-http-netty`); fix inaccuracies: multi-binary description, signal handler Native-only scope, `--connection-close` arg, `BENCH_COOLDOWN`/`BENCH_PORT` env vars

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
