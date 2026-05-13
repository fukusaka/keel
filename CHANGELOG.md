# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- `server-ktor-base`: `KeelCompression` (Native plugin from PR #495) gains ktor-server-compression `mode = Mode.All` parity — `KeelCompressionConfig` now exposes `Mode` enum (`CompressResponse` / `DecompressRequest` / `All`, default `All`) and four inbound limit fields (`decompressionLimit` 1 MiB, `ratioLimit` 100, `ratioBurst` 3, `unknownEncodingPolicy` 415). `onCallReceive` decode block decodes `Content-Encoding`-tagged request bodies with shared dual-gate zip-bomb defence. Opt-in `installKeelCompressionStatusMappers()` for ktor `StatusPages`. Default `Mode.All` auto-enables inbound side on the four Native `KeelCio*` engines via the existing PR #495 `installKeelCioCompression` call — no benchmark side change. 19/19 engine variants green on `compression-upload` bench (#499)

- `benchmark`: extend `keelNativeCompressionCustomizer` and `installPipelineHttpHandlers` to install `HttpRequestDecompressionHandler` alongside the existing `CompressionHandler`. Closes the inbound compression gap on 11 engines: 4 Native ktor-keel-* + 4 Native pipeline-http-* + 2 JVM pipeline-http-* + 1 JS pipeline-http-*. The `compression-upload` k6 scenario flips to 0% / 3-41 K req/s. KeelCio* (4 Native) and JVM ktor-keel-* (2 engines) remain a follow-up (#498)
- `codec-http`: `HttpRequestDecompressionHandler` — inbound `DuplexHandler` decoding `Content-Encoding` request bodies through the keel-compression streaming SPI. Apache `mod_deflate` pattern dual-gate zip-bomb defence (1 MiB absolute cap + 100:1 ratio + burst 3 tolerance), `UnknownEncodingPolicy` enum (default 415 Unsupported Media Type). Companion: `RequestDecompressionLimitException` / `UnsupportedContentEncodingException` (#497)
- `benchmark`: new `compression-upload` k6 scenario + `bench-stream-one.sh` wiring — POSTs a pre-built gzip-compressed body (133 bytes → 100 KiB decoded) to `/upload-stream` with `Content-Encoding: gzip` to verify the inbound decompression path. Visible gap until `HttpRequestDecompressionHandler` lands; `BENCH_COMPRESSION_UPLOAD_STRICT=false` allows uncompressed-throughput comparison meanwhile (#496)
- `server-ktor-base`: `KeelCompressionPlugin` (Native-only `RouteScopedPlugin` mirroring `ktor-server-compression` API) backed by `keel-compression-zlib`. Closes the Native compression gap where ktor's stock `GZipEncoder` / `DeflateEncoder` are identity no-op stubs; JVM keeps `install(Compression)` unchanged (#495)
- `benchmark`: install `KeelCompression` on the four Native `KeelCio*` engines, closing the response compression gap for ktor-http-cio's raw byte-channel parser. `BENCH_COMPRESSION_STRICT=true` now passes on all eight Native engines (#495)
- `server-ktor`: `KeelApplicationEngine.Configuration.pipelineCustomizer` hook — opt-in `(PipelinedChannel) -> Unit` callback invoked per accepted connection between the HTTP/1.1 codec stack install and the suspend-bridge handler. Used by Native `ktor-keel-*` engines to inject keel-codec-http's `CompressionHandler` (alternative to JVM's `Application.install(Compression)` plugin which is not available on Native) (#494)
- `benchmark`: Native `ktor-keel-{kqueue,nwconnection,epoll,io-uring}` wire `CompressionHandler` (gzip + deflate) at the engine pipeline level via `pipelineCustomizer` when `--compression=true`. `BENCH_COMPRESSION_STRICT=true` now passes on all four Native ktor-keel engines (req/s: kqueue 9,418, nwconnection 8,883, epoll 15,720, io-uring 15,829, all 0 failures). `KeelCio*` Native variants remain a follow-up (ktor-http-cio raw byte-channel parser bypasses keel's pipeline messages) (#494)
- `compression`: new `keel-compression` SPI module (`Encoder` / `Decoder` / `CompressionCodec` / `EncoderOptions` / `DecoderOptions` / `CompressionRegistry`) for protocol-agnostic byte-stream compression — reusable across HTTP server / HTTP client / WebSocket `permessage-deflate` / gRPC per-message; HPACK / QPACK are out of scope (#492)
- `compression-zlib`: gzip + deflate + raw-deflate backend (`GzipCodec` / `DeflateCodec`) for JVM (`java.util.zip.Deflater`), Native (`libz` cinterop), and JS (Node `zlib`). Supports `WrapFormat` Gzip/Zlib/Raw, `FlushMode` Sync/Full/Block/NoFlush, `EncoderSession.reset()`, dictionary, and `DecoderOptions.maxOutputSize` / `maxRatio` zip-bomb defence (#492)
- `codec-http`: `CompressionHandler` (`DuplexHandler`) — server-outbound response compression with `Accept-Encoding` negotiation (RFC 9110 §12.5.3), `Content-Encoding` / `Content-Length` / `Vary` header rewrite, both streaming (`HttpResponseHead` + `HttpBody` + `HttpBodyEnd`) and aggregated (`HttpResponse`) message shapes. `CompressionCondition` skip rules for pre-compressed mime types and small bodies (#492)
- `benchmark`: pipeline-http engines wire `keel-compression-zlib` (gzip + deflate) when `--compression=true`, closing the K16 gate for Native pipeline-http engines. k6 `compression.js` with `BENCH_COMPRESSION_STRICT=true` passes on `pipeline-http-{kqueue,nwconnection,nio,netty}` on macOS; epoll / io_uring follow under the same wiring once verified on Linux (#492)

### Changed

- `benchmark`: migrate `ws-echo.js` / `ws-large.js` / `ws-slow-consumer.js` from legacy `k6/ws` to stable `k6/websockets`. The legacy module's `socket.sendBinary` blocked the JS event loop and ignored SIGTERM during partial-write to a closed socket (5+ min hang against zig-bench's MessageOversize 1003-close pattern); `k6/websockets`' async `ws.send` clears the hang (verified: zig-bench 4 s clean exit vs 5+ min legacy hang). Bench-result column: `ws_msg_rtt_ms` is now the sole RTT source (`k6/websockets` does not auto-populate the legacy `ws_ping` Trend); ms granularity is unchanged. `bench-stream-one.sh` parser updated accordingly (#504)
- `benchmark`: `bench-stream-one.sh` auto-enables `BENCH_COMPRESSION_ENABLE=true` for the `compression-upload` scenario so the server starts with `--compression=true` and installs the inbound decode path. Without the default, the server runs decompression-disabled, route handlers see raw gzip bytes, and the strict check fails uniformly across all engines — masking real engine behaviour as a blanket "everything FAIL". Override with `BENCH_COMPRESSION_ENABLE=false` for non-strict throughput comparisons against engines that do not decompress yet (#500)
- **BREAKING** (`compression`): `EncoderSession.update` / `DecoderSession.update` / `finish` rewritten from aggregated single-output (`update(input): IoBuf`) to caller-provided streaming output (`update(input, output): CodecStatus`) — Netty `MessageToByteEncoder` / OkHttp `GzipSource` pattern. Eliminates aggregated-output anti-pattern: high-ratio decompression (e.g., 133 byte gzip → 100 KB plain) now emits N bounded chunks instead of one giant buffer, zip-bomb defence fires per-chunk before allocation, and bounded memory peak / pipeline backpressure are transparent. `CompressionHandler` updated to per-channel scratch + while-loop drain pattern. Backends (zlib JVM / Native libz / Node sync API) all updated. `CompressionRegistry` / `CompressionCodec` / `EncoderOptions` / `DecoderOptions` / `Encoder` / `Decoder` factory interfaces unchanged (#493)
- **BREAKING** (`engine-nio`, `engine-netty` NIO fallback, `engine-nwconnection`): `IoEngineConfig.idleReadPolicy` default flipped from `IdleReadPolicy.PRESERVE_BACKPRESSURE` to `IdleReadPolicy.DETECT_PEER_CLOSE` so peer FIN is surfaced through `onReadClosed` on every engine without explicit configuration — bringing these three engines in line with the pull-model engines (kqueue / epoll / netty native / nodejs) where peer-close detection is structurally free. The data-loss caveat that motivated the previous conservative default was closed by the pre-attach event journal (#473) + per-handler lifecycle replay (#474). Workloads that require kernel-level TCP back-pressure on the idle read window (bulk transfer, large upload streaming with no application-level flow control) opt in to `IdleReadPolicy.PRESERVE_BACKPRESSURE` explicitly via `IoEngineConfig`. Users who relied on the old default to keep bytes from arriving at the engine while `readEnabled = false` can restore the previous behaviour with `EngineX(IoEngineConfig(idleReadPolicy = IdleReadPolicy.PRESERVE_BACKPRESSURE))` (#475)

### Fixed

- `engine-kqueue`, `engine-epoll`, `native-posix`: set `FD_CLOEXEC` (or the platform's atomic equivalent: `EPOLL_CLOEXEC` / `EFD_CLOEXEC`) on every internal fd keel opens — kqueue fd, epoll fd, wakeup pipe / eventfd, listening / accepted / client socket fds, both TCP and Unix domain. Defensive hardening so keel's internal fds cannot leak into any subprocess the host application later `fork+exec`s. Mirror-image protection for K52, where keel was the inheritance recipient — this PR closes the inverse direction so a hostile or careless caller cannot inadvertently inherit keel internals into their own fork chain. Apple's published kqueue guidance and POSIX daemon-hardening idiom both recommend this. macOS uses post-fcntl (no `kqueue1` / `pipe2` / `accept4` available); Linux uses atomic flag variants where present (#TODO)
- `benchmark`: `bench-stream-one.sh` closes inherited high fds (3..255) before `exec`ing the backgrounded server so process-substitution FIFOs that bash 5.3+ keeps alive for the duration of the surrounding compound command (`for ... done` / `while ... done` / `{ ... }`) do not leak into the daemon. Without this, `bench-stream-all.sh ws-large` deterministically hung the Native `ktor-cio-keel-kqueue` / `ktor-keel-kqueue` engines at `client SIGKILL @ 90 s` while the same engine via `bench-stream-one.sh` directly (no compound wrapper) passed — symptom traced via strip-down bisect to the inherited FIFO fd correlating with the kqueue server's READ filter failing to fire on the 50-VU 1 MB workload. Adds the standard daemon-hardening idiom around `setsid` (#510)
- `engine-kqueue`: funnel all `kevent` `EV_ADD` submissions through the owning `KqueueEventLoop` thread (`if (inEventLoop()) submit else dispatch(Runnable)` idiom shared with libuv / Netty / Vert.x). Closes a cross-thread submission race where a user-thread `EV_ADD` reordered against the EL-thread `EV_DELETE` for a stale event left a registered listener with no armed filter, silently dropping subsequent events and hanging `ws-large` 50-VU clients against `pipeline-http-kqueue` / `ktor-keel-kqueue` / `ktor-cio-keel-kqueue` (#509)
- `benchmark`: `bench-stream-one.sh` escalates the `k6 run` / `wsbench` timeout to SIGKILL after `BENCH_K6_KILL_AFTER` (default 30s) grace following the initial SIGTERM. Without this, GNU `timeout` only sends SIGTERM and waits forever for a non-responsive child — observed when k6/websockets parks a VU goroutine inside a Go `net.Conn.Write` syscall that ignores scheduler cancel. The follow-up makes the wall-clock protection from PR #505 actually release the bench chain in the SIGTERM-non-responsive case (#506)
- `benchmark`: `bench-stream-one.sh` now wraps each `k6 run` / `wsbench` invocation in `timeout ${BENCH_K6_TIMEOUT:-90s}` so a stuck client (e.g. a k6/websockets VU deadlocked on `ws.send` partial-write to a wedged peer) can no longer wedge the whole `bench-stream-all.sh` chain indefinitely. Previously, the bench infrastructure had no wall-clock protection: a single hung VU could block the entire scenario sweep, requiring manual `pkill -9` to recover. Timeouts now surface as `TIMEOUT Ns` in the parsed row + `[TIMEOUT exit=124 after Ns]` marker in the raw output file, and the chain proceeds to the next engine. Requires GNU `timeout` (coreutils) on PATH; falls back to no-protection with a stderr warning when unavailable (#505)
- `benchmark`: `bench-stream-one.sh` probes the `wsbench` Go binary with `--help` before invoking it and auto-rebuilds when the binary is the wrong platform (e.g. a Mach-O binary rsync'd onto a Linux bench host). Previously the `Exec format error` was captured as the bench result file, leaving silent empty cells in the `ws-fragment` table. Opt out with `BENCH_WSBENCH_AUTOBUILD=false` (#504)
- `server-ktor-cio`: serialise `request.release()` (header recycle) through `HeaderParseMutex` on Native targets, in addition to the existing `parseRequest` (header borrow) coverage. ktor-io's `DefaultPool.posix.kt` runs the subclass `clearInstance` hook inside `synchronized(lock)`, and ktor-http-cio's `HeadersDataPool` exploits that anti-pattern by calling another pool's `recycle()` from inside its own `clearInstance`. Without recycle-side serialisation, worker A's `request.release()` ran concurrently with worker B's `parseRequest`, the cascading nested-lock wait on Kotlin/Native's `SynchronizedObject` (escalating to `pthread_mutex` under contention) collapsed parser throughput for ≈ 30 s in 4/10 deployment-style restart cycles (multi-worker fresh server taking 50 keep-alive connections at once). With both ends serialised, 0/10 collapse iterations, ≈ 25 K RPS stable (vs ≈ 27 K RPS pre-fix when not collapsed) on the `compression-upload` k6 scenario. Production impact: previously affected fresh Native instances during deployments / pod restarts / scale-out events; the fix closes the borrow ↔ recycle race entirely (#502)
- `codec-http`: `CompressionHandler.rewriteHeaders` now adds `Transfer-Encoding: chunked` when stripping the original `Content-Length` and the post-compression size is unknown (streaming response path). Previously the encoder threw "HttpResponseHead must declare either Content-Length or Transfer-Encoding: chunked" for compressed streaming responses, surfaced when wiring `CompressionHandler` into `ktor-keel-*` Native engines (`pipeline-http-*` engines were unaffected because they always emit aggregated `HttpResponse` with a known compressed size) (#494)
- `engine-io-uring`: detect peer-FIN even when `PipelinedChannel.readEnabled = false`; mirrors the kqueue / epoll / nio / netty / nwconnection / nodejs fix from #467-#475 for the io-uring engine. `IoUringIoTransport` arms a single-shot `IORING_OP_POLL_ADD` (mask `POLLRDHUP | POLLHUP | POLLERR`, no `POLLIN`) at the `onChannelAttached` hook, surfaces peer FIN through `onReadClosed` regardless of `readEnabled` state, and cancels the SQE in teardown so the kernel-side `struct file` reference is released before `close(fd)` and FIN is emitted promptly to the peer. Genuine TCP back-pressure (kernel `rcvbuf` retains bytes when reads are disabled) is preserved — POLL_ADD is event-only, never consumes bytes from the receive buffer (#477)
- `core`: defer the `DETECT_PEER_CLOSE` arm until after `AbstractPipelinedChannel.init` has wired up `transport.onRead` — pre-arming in the engine's `init { }` block races with the channel-construction sequence and can leak the first inbound buffer through a still-null `onRead`. Reproduced empirically on `engine-nio` once the default flipped to `DETECT_PEER_CLOSE` (`KeelCioEngineTest.chunked streaming all frames arrive` failed with a 5 s read-timeout because the 57-byte HTTP request was lost). Introduces an `IoTransport.onChannelAttached()` hook (default no-op) that engines override; `engine-nio` / `engine-netty` / `engine-nwconnection` move their `DETECT_PEER_CLOSE` arm out of `init { }` and into the hook (#475)
- `engine-netty`: detect peer-FIN even when `PipelinedChannel.readEnabled = false` on Linux / macOS by selecting Netty's native `EpollEventLoopGroup` / `KQueueEventLoopGroup` automatically (covers both TCP and UDS). The native transports issue `epoll_ctl(EPOLLIN | EPOLLRDHUP)` / `kevent(EVFILT_READ)` directly through JNI, bypassing Java NIO `Selector`'s API constraint that prevents NIO transport from observing peer FIN while `setAutoRead(false)`. Genuine TCP back-pressure (kernel `rcvbuf` retains bytes when reads are disabled) is preserved on the supported platforms. UDS uses the same transport selection — keel's `UnixSocketAddress` is translated to Netty's `DomainSocketAddress` for native transports, `UnixDomainSocketAddress` for NIO, via a `NettyTransport.newUdsAddress(path)` factory. Windows etc. keep the NIO fallback with the documented gap (#470)
- `engine-nodejs`: detect peer-FIN even when `PipelinedChannel.readEnabled = false`; mirrors the kqueue / epoll fix from #467 / #468 for the Node.js engine. `'end'` and `'error'` listeners are registered at transport construction so peer FIN / RST is surfaced through `onReadClosed` regardless of `readEnabled` state. The `'data'` listener is attached / detached with `readEnabled` so kernel `rcvbuf` retains bytes for genuine TCP back-pressure when reads are disabled (#469)
- `engine-epoll`: detect peer-FIN even when `PipelinedChannel.readEnabled = false`; mirrors the kqueue fix from #467 for the epoll engine. `EpollIoTransport` arms `EPOLLIN` (with `EPOLLRDHUP`) at construction, surfaces peer FIN through `onReadClosed` regardless of `readEnabled` state. The `keel-core` deferred-close + `notifyInactive` replay introduced in #467 absorbs the peer-FIN-before-bridge-install race (#468)
- `core`: defer the auto-close triggered by engine-driven peer-FIN until `SuspendBridgeHandler` is installed; `DefaultPipeline` replays `onInactive` to handlers added after `notifyInactive` so a peer-FIN observed before the user calls `read(buf)` is surfaced as `-1` instead of leaving the suspend reader hung (#467)
- `engine-kqueue`: detect peer-FIN even when `PipelinedChannel.readEnabled = false`; write-only push clients no longer linger in CLOSE-WAIT until the next write attempt or `SO_KEEPALIVE` timer. `FdReadyListener` gains an `onPeerClosed(interest)` default-no-op method and transports surface `EV_EOF` via `onReadClosed` regardless of `readEnabled` state. First engine of a roll-out — epoll / io-uring / nio / netty / nwconnection follow in separate PRs (#467)
- `engine-nio`: WARN + clear interest when a `SelectionKey` ready op fires without a registered callback; mirrors the K22 / K33 guards in `EpollEventLoop` / `KqueueEventLoop` (#465)
- `server-ktor-base`, `server-ktor-cio`, `core`: bound `pendingWrites` against slow readers — apply K37-style high-water gate to `AbstractPipelinedWriteChannel.flush` and the cio outbound pump; `@Volatile` on `AbstractIoTransport.writable` for the new off-EL read (#464)
- `server-ktor-base`: fix Netty SSE / chunked streaming delivering a 0-byte body; `terminate()` now enqueues `writeTerminator` via `CoroutineDispatcher.dispatch` instead of `withContext` to preserve emit-task FIFO ordering in the EventLoop task queue (#461)
- `server-ktor-cio`: close connection when the chunked streaming write channel is cancelled; `cancel()` without rethrowing skips the `0\r\n\r\n` trailer, desynchronising the client's HTTP parser if the keep-alive loop advances to the next request (#460)
- `server-ktor`: close connection when the streaming write channel is cancelled; Ktor's exception path in `respondWriteChannelContent` calls `cancel(cause)` which skips `HttpBodyEnd`, leaving the encoder in `CHUNKED` mode and causing a keep-alive guard violation on the next request (#459)
- `server-ktor`: fix SSE / chunked streaming response truncation under keep-alive; the connection handler now awaits `HttpBodyEnd` terminator write-and-flush before reading the next request head, eliminating an encoder `streamingMode` guard violation that closed the connection and left the previous response body incomplete (~5 % check failure rate at 50 VUs) (#458)
- `engine-netty`: `awaitPendingFlush` now suspends on Netty's `ChannelFuture` from the most recent `writeAndFlush`, confirming bytes have reached the OS TCP send buffer before `terminate()` completes (#458)
- `server-ktor`: fix infinite hang on large WebSocket frames (e.g. 1 MB echo); the outbound upgrade pump now yields the EventLoop thread when pending bytes exceed the high-water mark, allowing write-readiness events to be processed before the next chunk is forwarded (#457)
- `server-ktor-cio`: restore `ktor-cio-keel-{epoll,io-uring}` SSE throughput from ~154 / ~28 RPS to ~1,266 RPS; the previous chunked streaming path serialised each frame through a separate EventLoop wake-up cycle (#456)
- `ktor-engine`: `HeaderParseMutex` on Linux now serialises `parseRequest` with a process-wide `Mutex` (same as Apple); the previous no-op allowed all `availableProcessors()` worker threads to call ktor-http-cio concurrently, triggering `HeadersDataPool` non-reentrant lock contention and collapsing `ktor-cio-keel-epoll` / `ktor-cio-keel-io-uring` throughput to 0 RPS (#453)
- `engine-*`: `awaitPendingFlush` no longer deadlocks when `onWritable` drains the send queue before the continuation is stored; the isEmpty check and continuation store are now atomic on the EventLoop thread (#452)
- `benchmark`: fix ws-fragment result aggregation in `bench-stream-one.sh`; `grep` now matches on verbatim `NAME` instead of sanitised `SAFE_NAME`, which caused all ws-fragment rows to be empty in the stream results file (#451)
- `engine-kqueue`: remove stale `EVFILT_WRITE` kqueue filter after a pipeline WRITE callback completes without re-registering; without this, the persistent `EV_ADD` filter fired on every `kevent()` call while the fd was writable, busy-looping the EventLoop thread and causing `ktor-cio-keel-kqueue` to stop serving after warmup (#449)
- `engine-epoll`, `engine-kqueue`: suppress SIGPIPE per-connection instead of process-wide `signal(SIG_IGN)`; writes to peer-closed sockets return `WriteResult.Failed(EPIPE)` without terminating the process or disrupting application SIGPIPE handling (#448)
- `pipeline`: writes arriving after `close()` are now discarded instead of enqueued; `PipelinedChannel.requestFlush()` is now a no-op when the channel is already closed (#448)
- `engine-epoll`: log WARN and remove the interest from epoll when `dispatchReady` fires for a fd+interest that has neither a callback nor a suspend waiter; without this, a stale interest left in `fdEvents` caused a level-triggered busy loop with no log output until the fd was closed (#447)
- `engine-epoll`: remove stale `EPOLLOUT` from the epoll filter after a pipeline WRITE callback completes without re-registering; without this, level-triggered epoll busy-looped on every `epoll_wait` for all connections that had ever stalled on `EAGAIN`, saturating the EventLoop thread and causing `ktor-cio-keel-epoll` to stop serving after warmup (#447)
- `engine-epoll`: map `EPOLLHUP` and `EPOLLERR` to the READ-ready branch in the event loop; the kernel delivers these flags regardless of the interest mask, so on peer FIN / RST a socket could receive `EPOLLHUP` without `EPOLLIN`, leaving connections in CLOSE-WAIT (#447)

### Changed

- `io`: add `CharSequence.toDecLongOrNull` / `toHexLongOrNull` extensions for zero-alloc number parsing on non-`String` `CharSequence`; `codec-http` and `server-ktor-cio` migrate from stdlib equivalents (#463)
- `keel-server-ktor`: `KeelByteWriteChannel.drainAndDispatch` now submits the per-frame `emitBody` to the engine `EventLoop` with a fire-and-forget `CoroutineDispatcher.dispatch` instead of `withContext(ioDispatcher)`. `emitBody` only enqueues `pipeline.requestWrite + requestFlush` (themselves async — bytes are not on the wire when they return), so the caller never had to wait for them; dropping the suspend round-trip removes the per-frame continuation rebuild + scheduler resume hop that previously dominated the CPU profile on SSE / chunked streaming. Pipeline handler exceptions thrown inside the dispatched `Runnable` are caught and surfaced via `closeCause`; the next `flush` / `flushAndClose` rethrows, mirroring the previous synchronous propagation but delaying the user-visible error by one operation. Bench A/B (k6 sse 50 VU / 15s, JVM): `ktor-keel-nio` 1,231 → 3,336 RPS macOS (+171 %), 2,254 → 6,294 RPS luna (+179 %); `/hello` 4t/100c/10s unchanged within run-to-run variance. Ktor's `IOBridge` wrapping is preserved, so user lambdas that perform blocking I/O still run on `Dispatchers.IO` (#446)
- `keel-engine-nio`: `NioEventLoop.setInterestCallback` now skips `selector.wakeup()` when called from the EventLoop thread itself (same `inEventLoop()` guard as `dispatch`); removes a per-`armRead` / `registerWriteCallback` pipe-write syscall on the SSE / WebSocket per-frame hot path. Bench A/B on macOS M1 (`/sse` 50 VU / 15s): `ktor-keel-nio` 1,231 → 1,437 RPS (+16.7 %), `ktor-cio-keel-nio` 8,837 → 10,430 RPS (+18.0 %); `/hello` 4t/100c/10s unchanged within run-to-run variance (#445)
- **BREAKING** (`core`): `SocketOptions.DEFAULT` now enables `TCP_NODELAY` (`tcpNoDelay = true`), matching the default of Netty, OkHttp, Go `net/http`, and SwiftNIO; code that relied on Nagle being enabled must explicitly pass `SocketOptions(tcpNoDelay = false)` (#431)
- `keel-server-ktor-cio`: ktor-cio inbound now flows through a direct `KtorCioInboundBridge` `InboundHandler` (`SuspendMessageBridge` shape from `:keel-codec-http` / `:keel-server-ktor`, specialised for `IoBuf`) instead of the previous `BufferedSuspendSource(SuspendBridgeHandler)` chain, shortening connection-close propagation from 4 cross-context hops to 2 (#419)
- `keel-server-ktor-cio`: serialise `parseRequest` on Kotlin/Native to dodge the ktor-http-cio `HeadersDataPool` non-reentrant `SynchronizedObject` lock contention (`HeaderParseMutex`, no-op on JVM); turns 30 % accept-burst failure rate into 0 % and lifts steady-state `/hello` throughput from ≈ 14 k to ≈ 43 k RPS on macOS M1 (#419)
- `ktor-engine`: request body is now streamed via `HttpBody` / `HttpBodyEnd` pipeline messages into a per-request `ByteChannel` pump, eliminating the full-body `ByteArray` peak allocation for large uploads; `ByteChannel`'s internal buffer (~1 MB) is the new working-memory ceiling (#414)

### Added

- `core`: per-handler lifecycle replay on `DefaultPipeline` — generalises the existing `inactiveObserved` replay (PR #467) to cover `onActive` and `onWritabilityChanged`. New private fields `activeFired` (head propagated `onActive`), `inactiveFired` (renamed from `inactiveHeadFired` for symmetry), and `writabilityCurrent: Boolean?` (latest value seen by head, latest-only) feed `callHandlerAdded`'s per-handler replay so a handler added after the chain has already received a lifecycle event observes the current state. Replay precedence is "inactive wins" (terminal — replaying `onActive` on a closed channel would confuse cleanup logic) followed by `onActive` + (optional) `onWritabilityChanged`. Enables protocol-upgrade codecs, conditional codec injection, and runtime-attached metrics handlers to join a running channel without missing the lifecycle hook (#474)
- `core`: pre-attach event journal on `DefaultPipeline` — inbound events (`notifyActive`, `notifyRead`, `notifyReadComplete`, `notifyError`, `notifyUserEvent`, `notifyWritabilityChanged`, `notifyInactive`) that arrive before the pipeline acquires its first user `InboundHandler` are now buffered and replayed onto the assembled chain via dispatcher-tick drain on the first user inbound `addX`. The dispatcher-tick deferral lets a synchronous codec-stack setup (`addLast(decoder)` / `addLast(aggregator)` / `addLast(handler)` back-to-back) finish before drain replays through the now-fully-built chain — the keel equivalent of Netty's `ChannelInitializer` model. Per-event replay strategy: queue (reads / errors / user events with caps 64 / 8 / 16, oldest released on overflow with `WARN`), latest-only (writability), or flag (active / readComplete / inactive). With this in place, `IdleReadPolicy.DETECT_PEER_CLOSE` on engine-nio / engine-netty NIO fallback / engine-nwconnection no longer drops bytes at the transport level when `readEnabled = false`; the engines always deliver through `transport.onRead` and the pipeline-level journal absorbs early data — closes the data-loss caveat documented on `IdleReadPolicy` in PR #472 (#473)
- `core`, `engine-nio`, `engine-netty`, `engine-nwconnection`: `IdleReadPolicy` (`DETECT_PEER_CLOSE` / `PRESERVE_BACKPRESSURE`) on `IoEngineConfig` lets users pick the trade-off between peer-close detection and TCP back-pressure for the read-side idle window (`PipelinedChannel.readEnabled = false`) on engines that face a structural API constraint (Java NIO `Selector` exposes only `POLLIN`; Apple's NWConnection has no event-readiness API distinct from `nw_connection_receive`). Default is `PRESERVE_BACKPRESSURE` so the existing behaviour of these three engines is unchanged; opt in to `DETECT_PEER_CLOSE` for write-only push clients that need early peer-FIN. Pull-model engines (kqueue / epoll / io_uring / netty native / nodejs) achieve both simultaneously and silently ignore the setting; Netty resolves the policy to `PRESERVE_BACKPRESSURE` automatically when the active `NettyTransport` is native. Replaces the documentation-only entry from PR #471 with the actual fix (#472)
- `benchmark`: add `bench-stream-all.sh` to run all non-compression streaming scenarios across available engines and write a per-host summary table (#462)
- `keel-server-ktor`, `keel-server-ktor-cio`: Ktor's `webSocket { }` DSL is now functional on both adapters; `respondUpgrade` is implemented for both (#436)
- `keel-server-ktor`: `KeelApplicationEngine.Configuration.socketOptions` forwards `SocketOptions` to every accepted connection; defaults to `SocketOptions.DEFAULT` (`TCP_NODELAY` enabled); pass `SocketOptions(tcpNoDelay = false)` to re-enable Nagle for bulk streaming workloads (#431)
- `benchmark`: `ktor-cio-keel-nwconnection` (macOS) and `ktor-cio-keel-netty` (JVM) complete the Pattern C engine set; all keel push-model engines (`kqueue`, `epoll`, `io-uring`, `nwconnection`, `netty`, `nio`) now have both `ktor-keel-*` and `ktor-cio-keel-*` bench entries (#450)
- `benchmark`: `ktor-cio-keel-{nio,kqueue,epoll,io-uring}` engines wire the new `:keel-server-ktor-cio` adapter into the bench harness for 3-way comparison (`pipeline-http-*` / `ktor-keel-*` / `ktor-cio-keel-*`). Native targets (kqueue / epoll) currently report intermittent 0 RPS — JVM is stable; investigation deferred (#417)
- `keel-server-ktor-cio`: connection handler implementation — `KtorCioConnectionHandler` bridges keel transport to `ktor-http-cio` via two byte-channel pumps (inbound from `asBufferedSuspendSource`, outbound to `pipeline.requestWrite`). Ships `KeelCioApplicationCall` / `Request` / `Response` triple writing HTTP/1.1 wire-format directly to a `ByteWriteChannel` (Content-Length-delimited or chunked-encoded). 6 JVM integration tests (#416)
- `keel-server-ktor-cio`: new module — Ktor adapter using `ktor-http-cio`'s parser instead of `:keel-codec-http`, ships the `KeelCio` `ApplicationEngineFactory`. Per-connection handler is a scaffolding stub; the implementation lands in follow-up PRs (#415)
- `benchmark`: `/ws-echo` WebSocket echo endpoint on `pipeline-http-*` engines (kqueue / epoll / io_uring / NIO / Netty). Performs the RFC 6455 handshake inside the EventLoop pipeline without coroutines: swaps the HTTP codec for `WsFrameDecoder` + `WsFrameEncoder` after sending `101 Switching Protocols`, then echoes each frame back (mask stripped, PING→PONG, CLOSE→CLOSE) (#413)
- `keel-server-ktor`: keel-native WebSocket DSL — `Application.keelWebSocket(path) { ... }` registers a path-keyed handler that receives a `WsSession` (`incoming: ReceiveChannel<WsFrame>` + `suspend fun send(frame)` + `close(code, reason)`). Bypasses Ktor's `webSocket` plugin so the WebSocket frame codec stays in keel's pipeline (`addWsServerCodec` from `:keel-codec-websocket`) end-to-end — no decode → encode round-trip. Detection runs inside `KeelCodecConnectionHandler` ahead of the Ktor pipeline; only requests with `Upgrade: websocket` AND a registered path are diverted. `send` automatically strips the mask key (RFC 6455 §5.3 forbids server frames from being masked); PING is auto-replied with PONG; PONG is dropped; CLOSE is captured and echoed back after the user handler returns so any in-flight `send` completes first (#412)
- `keel-codec-websocket`: pipeline handler integration — `WsFrameDecoder` (incremental `IoBuf` → `WsFrame` parsing with `maxFramePayloadSize` cap and `requireClientMasking` validation), `WsFrameEncoder` (`WsFrame` → wire `IoBuf` written directly into an exact-sized `IoBuf` with no intermediate `kotlinx.io.Buffer`; masked frames XOR-apply the mask during the payload write), and the `addWsServerCodec` installer extension on `PipelinedChannel`. Mirrors `:keel-codec-http`'s `addHttp1ServerCodec` shape so HTTP and WS codecs install the same way. 21 new pipeline-level unit tests cover happy paths, chunk-spanning frames, masking validation, oversized-frame rejection, and round-trip via the encoder (#410)
- `benchmark`: opt-in JFR + GC log capture for JVM bench runs via `BENCH_JFR=true` and `BENCH_GC_LOG=true` env knobs on `bench-stream-one.sh`. Detects `java`-as-arg-0 and prepends `-XX:StartFlightRecording=settings=…,filename=…,dumponexit=true` and `-Xlog:gc*:file=…:tags,uptime,time,level`; artefacts land alongside the existing raw bench output (#408)
- `benchmark`: HTTP response compression bench — `compression.js` k6 scenario sends `Accept-Encoding` to `/large`, opt-in via `BENCH_COMPRESSION_ENABLE=true`. Server side wired through Ktor adapters (gzip + deflate, JVM only via expect/actual; Native gets a no-op), Vertx, Netty raw, and Spring. The default mode measures every engine (compression-on vs compression-missing on a single leaderboard); `BENCH_COMPRESSION_STRICT=true` flips the Content-Encoding assertion to gating for verification runs (#407)
- `keel-codec-http`: `PipelinedChannel.addHttp1ServerCodec(aggregateBody: Boolean = true)` extension that installs the standard HTTP/1.1 server-side codec stack on a pipeline (`encoder` + `decoder` + optional `aggregator`). Lets `:keel-ktor-engine` and the upcoming `:keel-server-http` share one install path instead of repeating the four `pipeline.addLast(...)` calls (#391)

### Documentation

- `engine-nwconnection`: document the contract gap that peer-FIN is not surfaced through `onReadClosed` while `PipelinedChannel.readEnabled = false`; NWConnection has no event-readiness API analogous to kqueue's `EV_EOF` so peer FIN is observable only via an active `nw_connection_receive` completion. Always-arming receive at construction (the kqueue / epoll / netty-native fix shape) would (1) drain framework-level TCP back-pressure and (2) silently lose bytes that arrive before `PipelinedChannel.ensureBridge` installs `SuspendBridgeHandler`. Same shape as the engine-nio / engine-netty NIO fallback gap; the planned `NioReadMode` follow-up will cover engine-nio / engine-netty NIO fallback / engine-nwconnection uniformly with a per-engine config to opt into peer-FIN detection at the cost of those trade-offs (#471)

### Removed

- **BREAKING** (`keel-ktor-engine`): the platform-default `StreamEngine` factory (`DefaultEngine` `expect`/`actual` returning `NioEngine` / `KqueueEngine` / `EpollEngine`) is removed; `KeelApplicationEngine.Configuration.engine` must be set explicitly (e.g. `engine = NioEngine()`). Avoids pulling every keel engine module into the adapter just to pick one at runtime; consumers depend only on the engine they actually use. `start()` throws `IllegalStateException` if `engine` is left unset (#391)

### Added

- `benchmark`: cross-language reference servers (`rust-bench` axum, `go-bench` gin + `gorilla/websocket`, `swift-bench` Hummingbird + `HummingbirdWebSocket`, `zig-bench` Zig 0.15+ std.http with built-in `respondWebSocket`) gain `/upload-stream` + `/sse-stream` + `/ws-echo` so the streaming + WebSocket bench tables include every non-keel reference column (#395, renamed in #398)
- `benchmark`: streaming HTTP endpoints `POST /upload-stream` (drain request body, reply with byte count) and `GET /sse-stream?count=N&size=M` (chunked SSE frames) on every engine — Ktor route block, raw keel pipeline, `NettyRawEngine`, `SpringEngine`, `VertxEngine` — for benchmarking request-body / response-body streaming paths independently of `/hello` + `/large` (#394)
- `benchmark`: WebSocket echo endpoint `GET /ws-echo` on every engine. The `:keel-server-ktor` adapter (`ktor-keel-*`) rejects the upgrade until `respondUpgrade` support lands; non-keel engines (`ktor-cio` / `ktor-netty` / `netty-raw` / `spring` / `vertx`) work today (#394)
- `benchmark`: k6 scenarios `benchmark/k6/{upload,sse,ws-echo}.js` + `bench-stream-one.sh` helper (mirrors `bench-one.sh`'s `<name>|<rps>|<p50>|<p99>` row format but invokes k6 instead of wrk). WebSocket RTT uses the built-in `ws_ping` Trend (Go-side ns precision) populated via interleaved `socket.ping()` (#394)
- `benchmark`: `bench-stream-one.sh` `BENCH_K6_SUCCESS_THRESHOLD` env (default 95) flags corrupt benchmark runs as `engine||checks=NN.NN%|-` instead of reporting phantom RPS when responses fail body-size checks. Raw k6 output now persists under `benchmark/results/{host}/` mirroring the wrk convention (#394)
- `keel-server-ktor-base`: new module — codec-agnostic skeleton for keel's Ktor adapters. Owns `KeelApplicationEngine` (Ktor `BaseApplicationEngine` impl), `Configuration` (engine / keepAlive / acceptBackoff / applicationDispatcher / sslConnector), `KtorConnectionHandler` (per-connection handler interface), `KeelConnectionPoint`, and `KtorLoggerAdapter` / `KtorLoggerFactory`. Sibling codec modules inject the connection handler at factory time so the engine class is shared across codec variants (#393)

### Fixed

- `keel-engine-nio`: `NioEventLoop.setInterestCallback` now stores per-op callbacks (read / write / accept / connect) instead of a single `key.attach(...)`, so a SelectionKey armed for `OP_READ` and `OP_WRITE` simultaneously no longer drops the earlier callback. The lost callback caused `NioIoTransport.onReadable` to miss the peer FIN that follows a WebSocket close handshake on macOS, deadlocking `ktor-keel-nio` / `ktor-cio-keel-nio` upgrade sessions (K23) (#444)
- `benchmark`: per-frame SSE flush in `netty-raw` / `zig-bench` / `rust-bench`, and raw-byte body in `spring` (replaces the `Flux<String>` SSE adapter that mangled the wire format and broke k6's body-size check); the SSE rows for these reference servers are now comparable to the keel engines and k6 returns to 100 % checks (#442)
- `keel-server-ktor`: `KeelApplicationResponse.responseChannel()` now propagates `ByteWriteChannel.flush()` to the engine pipeline 1:1 (per-frame `requestWrite + requestFlush`) instead of coalescing 1-15 frames per drain through the previous `ByteChannel` + 8 KB `readAvailable` bridge; SSE / chunked streaming on `ktor-keel-*` engines now match the per-frame send semantics that `respondBytesWriter { writeFully + flush }` callers (and `pipeline-http-*` after #440) already provide (#441)
- `keel-server-ktor-cio`: `KtorCioConnectionHandler` now awaits transport flush completion before closing the connection, preventing chunked-stream terminator loss under high concurrency on epoll / io-uring engines (#439)
- `benchmark`: `wsbench` WebSocket fragment client now sets `TCP_NODELAY` on its outgoing connections; `bench-stream-one.sh`'s `bindConfigFor`/`childSocketOptions` now defaults `tcpNoDelay` to `true` (matching `SocketOptions.DEFAULT`) so POSIX engines (epoll/io-uring) no longer stall ~40 ms per echo round-trip due to Nagle+delayed-ACK interaction (#438)
- `keel-core`: `TypedInboundHandler` auto-release now correctly releases the original input when a transforming handler propagates a different output object (e.g. `WsFrameDecoder` propagating `WsFrame` after consuming `IoBuf`); the previous identity-blind check leaked the source Netty `ByteBuf` indefinitely, causing OOM under sustained WebSocket load (#437)
- `benchmark`: `pipeline-http-*` SSE responses (`/sse-stream`) no longer send a spurious second response on `HttpBodyEnd`, fixing HTTP/1.1 keep-alive reuse after every SSE stream (#435)
- `native-posix`: `PosixNativeSocketOps` now checks the return values of `setsockopt(2)` (logged as warnings), `fcntl(F_GETFL/F_SETFL)` in `setNonBlocking`, `getsockopt(SO_ERROR)` in `getSocketError`, and `getsockname`/`getpeername` in `getLocalAddress`/`getRemoteAddress`; previously these could fail silently, leaving the fd in blocking mode or returning garbage addresses (#433)
- `keel-server-ktor-cio`: `HeaderParseMutex` is now a no-op on Linux (epoll / io_uring), where the single-threaded EventLoop makes concurrent `HeadersDataPool` access impossible; previously the process-wide mutex serialised all 50 VU connections at the header-parse step, collapsing multipart throughput from ~52 k req/s (JVM) to 42 req/s on Native Linux (#432)
- `benchmark`: `bench-{one,keel,all}.sh` now poll via `lsof` / `fuser` after killing each server to confirm the port is released before starting the next engine, eliminating the READY timeout seen on macOS when server-side TIME_WAIT sockets blocked a new `bind()` in multi-engine serial runs (#430)
- `keel-io`, `keel-codec-http`, `keel-core`: introduce `Releasable` interface (`keel-io`); `IoBuf` extends it and `HttpBody` implements it, replacing the interim `AutoCloseable`; `ReferenceCountUtil.safeRelease` now uses a single `is Releasable` check — previously the pipeline's error-cleanup path leaked the owned `IoBuf` because `HttpBody` (a wrapper) was not recognised as releasable (#429)
- `engine-nwconnection`, `server-ktor`: `ktor-keel-nwconnection` close-per-request and streaming responses no longer drop data; `NwIoTransport.awaitPendingFlush()` now suspends until `nw_connection_send` callback fires, and `respondFromBytes` / `respondNoContent` / `responseChannel` all await flush completion before returning so `close()` cannot cancel in-flight sends (#427)
- `engine-netty`: `ktor-keel-netty` upload endpoints no longer return 0 bytes for all request body sizes; an inbound buffer copy had a miscounted offset that caused an exception collapsing the connection before the body was consumed (#426)
- `keel-server-ktor-base`: `call.receiveMultipart()` on Native keel engines (kqueue / epoll / io_uring / NWConnection) no longer returns HTTP 415; a `CIOMultipartDataBase`-backed `ApplicationReceivePipeline` interceptor fills the gap left by Ktor's JVM-only `defaultPlatformTransformations` (#425)
- `tls`, `engine-netty`: HTTPS on `ktor-keel-netty` (JSSE) no longer fails immediately; the TLS handshake now completes where it previously crashed due to a buffer-type mismatch between the JSSE codec and the Netty buffer allocator (#424)
- `keel-codec-http`: `HttpResponseEncoder` now suppresses the response body for HEAD requests (RFC 9110 §9.3.2); status line and headers (including `Content-Length` / `Transfer-Encoding`) are forwarded unchanged (#423)
- `keel-codec-http`: `HttpResponseEncoder` chunked streaming no longer crashes (`ArrayIndexOutOfBoundsException`) once a response exceeds ~50 chunks; the 256-byte per-encoder scratch was written without a pre-emit bounds check, so long SSE / chunked streams overran it and broke HTTP/1.1 keep-alive on the connection (#422)
- `keel-codec-http`: `HttpResponseEncoder` accepts RFC-9112-bodyless responses (1xx / 204 / 304) without requiring `Content-Length` or `Transfer-Encoding: chunked`. A new internal `BODYLESS` streaming state emits the head and treats a single terminating `HttpBodyEnd` as a no-op; a non-empty `HttpBody` after a bodyless head is rejected as a contract violation. Unblocks 101 Switching Protocols handshakes and other no-body responses going through the streaming encoder (#411)
- `benchmark`: `KeelKqueueEngine` / `KeelNioEngine` benchmark wrappers now set `this.engine = …` (`KqueueEngine()` / `NioEngine()`) — without it `--engine=ktor-keel-kqueue` and `--engine=ktor-keel-nio` crashed the benchmark binary with `IllegalStateException: KeelApplicationEngine.Configuration.engine must be set explicitly` since the explicit-engine contract landed (#394)
- `benchmark`: `bench-{one,keel,all}.sh` wrk percentile parser now anchors on `^\s+50%\s` / `^\s+99%\s` instead of substring `grep "50%"` / `grep "99%"` so the Thread Stats `Req/Sec ... 50.99%` `+/- Stdev` band can no longer be misread as a percentile row (was putting `14.11k` into P50 / P99 for high-variance runs) (#394)
- `benchmark`: `bench-{one,keel,all,stream-one}.sh` READY check validates HTTP status (`curl -w '%{http_code}'` matched against `2xx` / `3xx`) instead of just TCP connect — half-broken engines that reply 5xx no longer pass readiness and collect garbage benchmarks (#394)
- `benchmark`: `bench-{one,stream-one}.sh` teardown sends SIGTERM to the entire process group via `kill -TERM -- "-$PID"` (when `setsid` is available) instead of just `$PID`, so JVM helper threads / native subprocess forks don't survive teardown and hold the bench port across runs (#394)

### Changed

- **BREAKING** (`benchmark`): cross-language reference servers renamed from `rust-hello` / `go-hello` / `swift-hello` / `zig-hello` to `rust-bench` / `go-bench` / `swift-bench` / `zig-bench`. Affects directory paths under `benchmark/`, produced binary names, package / module identifiers in the respective build configs, and bench identifier strings reported in summary tables. The `-hello` suffix dated from when the reference servers only served `/hello`; PR #395 added `/upload-stream` + `/sse-stream` + `/ws-echo` and the name no longer matched. Consumers that script against the old paths or grep summary tables for the old identifiers need to update (#398)
- **BREAKING** (`keel-server-ktor`): refactored to depend on `:keel-server-ktor-base` for the engine skeleton; now provides only `Keel` (factory) and `KeelCodecConnectionHandler` (the keel-codec-specific per-connection handler) plus the keel-codec-specific `KeelApplicationCall` / `KeelApplicationRequest` / `KeelApplicationResponse` / `KeelHeaders`. `KeelApplicationEngine` constructor gains a required `connectionHandler: KtorConnectionHandler` parameter (consumers using only the public `embeddedServer(Keel)` API are unaffected; only direct constructor callers need to pass an explicit handler). `KeelConnectionPoint`, `KtorLoggerAdapter`, and `KtorLoggerFactory` are now `public` because they cross the module boundary (#393)
- **BREAKING** (`keel-ktor-engine` → `keel-server-ktor`): module renamed from `:keel-ktor-engine` to `:keel-server-ktor` and the package from `io.github.fukusaka.keel.ktor` to `io.github.fukusaka.keel.server.ktor`. Aligns the naming with the `:keel-server` family (`:keel-server` primitives + `:keel-server-ktor` Ktor adapter + future `:keel-server-http` native HTTP server). Class names (`Keel`, `KeelApplicationEngine`, `KeelApplicationCall`, `KeelApplicationRequest`, `KeelApplicationResponse`, `KeelConnectionPoint`, `KeelHeaders`, `KtorLoggerAdapter`) are unchanged; consumers update the Gradle coordinate (`implementation(project(":keel-server-ktor"))`) and the import path. README + website intro / architecture docs + sample + benchmark all updated to match (#392)
- **BREAKING** (`keel-ktor-engine`): drops the per-platform engine module dependencies (`:keel-engine-nio` from `jvmMain`, `:keel-engine-kqueue` from `macosMain`, `:keel-engine-epoll` from `linuxMain`) along with the `DefaultEngine` files. The KMP target set is unchanged (jvm + linux/macos native), but the platform-specific source sets are gone — the adapter is now engine-neutral and applications wire the engine via the configuration block (#391)
- `sample`: the bundled hello-world server now sets `engine = NioEngine()` and uses the `embeddedServer(Keel, configure = { ... })` form to follow the new explicit-engine contract. `:sample` adds direct `:keel-core` + `:keel-engine-nio` dependencies. Application code that previously copied the sample template needs the same migration (#391)

### Added

- `keel-server`: `TlsServerInstaller` (`fun interface` for installing server-side TLS on a `PipelinedChannel`), `TlsServerConfig` (`BindConfig` subclass carrying `TlsConfig` + optional installer for HTTPS listeners), and `TlsCodecServerInstaller` (adapter that wraps a `TlsCodecFactory` for keel's `TlsHandler`-based TLS). Relocated from `:keel-tls` so server-side install plumbing lives next to the other server primitives, leaving `:keel-tls` strictly about TLS protocol primitives (#390)
- `keel-server`: `AcceptBackoff` (sealed: `Fixed` / `Exponential`), `StreamServer.acceptLoopWithBackoff` extension, and `gracefulShutdown` two-phase helper relocated from `:keel-ktor-engine`. The accept-loop helper takes an `onAccept: (Channel) -> Unit` callback so the caller decides per-connection scope/dispatcher; the shutdown helper completes a stop-signal job, drains the accept coordinator and engine-scope handlers within a grace period, then forces cancel and always closes the engine in `finally`. Both let `:keel-ktor-engine` and the future `:keel-server-http` share the same accept/shutdown semantics (#389)
- `keel-server`: new module — server-side primitives shared between engine adapters and HTTP-family server modules. Currently exposes `ServerConnector` (`(host, port, tls?)` descriptor for a single listen endpoint), relocated from `:keel-ktor-engine`. Lets the upcoming `:keel-server-http` (HTTP/1.1 native server) and `:keel-ktor-engine` share the same type without either side owning it. KMP target set: jvm / js / linuxX64 / linuxArm64 / macosArm64 / macosX64 (#388)

### Changed

- **BREAKING** (`keel-tls` / `keel-server` / `keel-ktor-engine` / `keel-engine-netty` / `keel-engine-nodejs` / `keel-engine-nwconnection`): `io.github.fukusaka.keel.tls.TlsConnectorConfig` and `io.github.fukusaka.keel.tls.TlsInstaller` are removed and replaced by `io.github.fukusaka.keel.server.TlsServerConfig` (renamed) and `io.github.fukusaka.keel.server.TlsServerInstaller` (renamed) in `:keel-server`. `TlsServerConfig.tls` replaces `TlsConnectorConfig.config`. `TlsCodecFactory` no longer implements `TlsInstaller`; install keel `TlsHandler` via `io.github.fukusaka.keel.server.TlsCodecServerInstaller(factory)`. `NettySslInstaller` now implements `TlsServerInstaller`. Consumers update imports + adopt the rename + wrap factory in `TlsCodecServerInstaller(factory)` for the default `sslConnector(...)` path. No runtime behaviour change; the move keeps `:keel-tls` strictly about TLS protocol primitives and co-locates server-binding plumbing in `:keel-server` (#390)
- **BREAKING** (`keel-ktor-engine`): `KeelApplicationEngine.AcceptBackoff` (sealed nested class) is removed; consumers configure `acceptBackoff` with `io.github.fukusaka.keel.server.AcceptBackoff` (`Fixed` / `Exponential`) from `:keel-server` instead. Behaviour and defaults are unchanged (#389)
- **BREAKING** (`keel-ktor-engine`): `io.github.fukusaka.keel.ktor.ServerConnector` moves to `io.github.fukusaka.keel.server.ServerConnector` in the new `:keel-server` module. Ktor adapter consumers update the import; the data class shape is unchanged. `:keel-ktor-engine` gains a `:keel-server` dependency (#388)
- `engine-epoll` / `engine-kqueue`: rebuild the partial-`writev` remainder in place by popping fully-written entries off the head of `pendingWrites` and mutating the partially-written entry at index 0, replacing the per-partial `mutableListOf<PendingWrite>()` + iterator + `addAll` rebuild path. Eliminates the per-partial allocation and reduces `PendingWrite` allocations to one (only the split entry — trailing untouched entries stay as-is). Throughput tied at peak on a real-network slow-path bench (`pipeline-http-epoll` `/large` with `tc qdisc netem 50ms 10ms` + `SO_SNDBUF=4096`, partial-write firing visible at 23-25%); merge basis is structural alloc reduction, not perf delta (#387)
- `core` / `engine-epoll` / `engine-kqueue`: `AbstractIoTransport.pendingWrites` switches from `mutableListOf<PendingWrite>` to `ArrayDeque<PendingWrite>`, and the partial-write WouldBlock retry path uses `addFirst(remainder)` instead of `add(0, remainder)` — `O(n) shift` → `O(1) head decrement` on the queue's head-touch operation. No API break (`pendingWrites` is `protected`); the remaining call sites behave identically on both types. Throughput tied at peak on the same real-network slow-path bench; merge basis is the algorithmically-correct data structure for the queue's head + tail mutation pattern, not perf delta (#386)
- **BREAKING** (`engine-epoll` / `engine-kqueue` / `engine-nio` internal API): `{Epoll,Kqueue,Nio}EventLoop` gain a `val allocator: BufferAllocator = DefaultAllocator` constructor parameter co-locating the per-loop allocator on the loop itself. `{Epoll,Kqueue,Nio}EventLoopGroup.next()` / `at(idx)` now return just the loop instead of `Pair<EventLoop, BufferAllocator>`; callers read the allocator off the loop via `workerLoop.allocator`. Removes the per-accept `Pair` allocation and replaces a parallel `allocators: Array<BufferAllocator>` in each Group with a property on the loop. Test seams now use named `syscallOps = fake` for POSIX engines because the parameter order shifted (#364)
- **BREAKING** (`engine-epoll` / `engine-kqueue` internal API): `EpollEventLoop.registerCallback` / `KqueueEventLoop.registerCallback` change the third parameter from `callback: () -> Unit` to `listener: FdReadyListener` (a new `fun interface` nested in each EventLoop). Receiver classes (`{Epoll,Kqueue}IoTransport`, `{Epoll,Kqueue}PipelinedStreamServer`) now `implement FdReadyListener` and pass `this` to `registerCallback`, eliminating the per-call lambda allocation that the inline `{ onReadable() }` form created on every read re-arm. Engine throughput tied at peak saturation (luna `pipeline-http-epoll` `/hello` 16t/500c: 1.94M → 1.94M req/s); the alloc reduction is structural — fast-path lambda alloc cost is dwarfed by per-request syscall + parse + write at engine bench scale (#363)
- `engine-io-uring`: `RegisteredBufferTable.ptrToIndex` switches from `HashMap<Long, Int>` to `LongObjectMap<Int>`. Page-aligned pooled-buffer pointers were the case the Fibonacci top-bit hash was designed for; the lookup-only sub-bench measures 1.42× / 1.71× speedup vs `HashMap` on macOS arm64 / linuxX64 at 64 entries. No user-visible behaviour change (#362)
- **BREAKING** (`native-posix` / `engine-kqueue` / `engine-epoll` internal API): `NativeSocket.writev(fd, regions: List<NativeRegion>)` is replaced by `NativeSocket.writev(fd, ptrs: LongArray, lens: IntArray, count: Int)` so gather writes can be fed from caller-owned primitive arrays. The POSIX `IoTransport` implementations now keep a per-transport `LongArray`/`IntArray` pair (initial capacity 8, 1.5x growth) and rebuild it in place from `pendingWrites` before each `writev` call, eliminating the former `.map { NativeRegion(...) }` allocation that cost ~13x vs. the primitive path on a luna micro-benchmark (#358). The `NativeRegion` data class is removed along with its sole consumer. No user-visible behaviour change; `pipeline-http-epoll` / `pipeline-http-kqueue` `/hello` throughput is unchanged (#359)

### Added

- `engine-epoll` / `engine-kqueue`: seam-test coverage for the main-loop `waitEvents` error branch (EINTR / EAGAIN retry + fatal errno exit). Three test cases per engine drive `loop()` directly on the test thread via the `internal` accessor introduced in PR #356 / PR #357, closing the explicit gap those PRs noted as out of scope (#365)
- `io` / `engine-kqueue` / `engine-epoll`: add `LongObjectMap<V : Any>` — open-addressing primitive Long-keyed map (Fibonacci hash with top-bit extraction + backshift delete) and adopt it for the `KqueueEventLoop` / `EpollEventLoop` fd registration tables, replacing `MutableMap<Long, …>` and removing per-event `Long` boxing. Read-dominant micro-bench (9 get + 1 (rm+put), 64 entries) measures 1.95× / 2.87× speedup vs `HashMap<Long, V>` on macOS arm64 / linuxX64 loopback (#361)
- `benchmark`: `--bench=longmap-variants` Kotlin/Native micro-bench that exhaustively measures Long-keyed hash map design choices (encoding × hash function × delete strategy × workload × key shape) for engine registration tables and pointer-keyed lookups (#361)
- `benchmark`: Kotlin/Native `--bench=collection-alloc` micro-benchmark that measures ns/op for the collection patterns used by the EventLoop / IoTransport hot paths (ArrayList vs ArrayDeque prepend, `.map{}` vs primitive parallel-array, `HashMap<Long,V>` vs open-addressing LongObjectMap, indexed vs iterator for-loop). Supports both macosArm64 and linuxX64; invoked via the standard benchmark binary. Intended as a local development aid for validating hot-path refactors (#358)
- `engine-epoll`: internal `EpollSyscallOps` seam + `PosixEpollSyscallOps` production impl, the Linux counterpart of the `KqueueSyscallOps` seam (#356). Routes `epoll_create1` / `eventfd` / `epoll_ctl` (ADD + MOD) / `epoll_wait` / eventfd wakeup read/write through a semantic interface. `EpollEventLoop` gains a constructor parameter defaulting to the production impl; the seam enables unit tests for init-time failure cleanup, `addOrModifyEpoll` EEXIST fallback, and wakeup eventfd error branches — all of which were previously only reachable via a real Linux kernel failure. `eventfd_write` `EAGAIN` is now swallowed as benign (matching kqueue wakeup semantics). `loop()` relaxed from `private` to `internal` to allow direct test-thread driving in a future main-loop seam test (#357)
- `engine-kqueue`: internal `KqueueSyscallOps` seam + `PosixKqueueSyscallOps` production impl, routing all `kqueue(2)` family calls (`kqueue` / `pipe` / `kevent` submit+wait / wakeup `read`/`write`) through a semantic interface. `KqueueEventLoop` gains a constructor parameter defaulting to the production impl; the seam enables unit tests for init-time failure cleanup, `register` / `registerCallback` `kevent(EV_ADD)` failure recovery, and wakeup `write` error branches — all of which were previously only reachable via a real BSD kernel failure. `loop()` relaxed from `private` to `internal` to allow direct test-thread driving in a future main-loop seam test (#356)

### Fixed

- `engine-nio`: concurrent `accept()` callers on `NioStreamServer` now queue in `pendingAcceptConts: ArrayDeque<...>` and the SelectionKey holds a single shared `resumeAllRunnable` that resumes every queued waiter on `OP_ACCEPT` fire. Previously the design lost continuations on every level — a single `pendingAcceptCont` slot AND a per-waiter Runnable bound via `key.attach` (silent continuation leak fix) (#372)
- `engine-nwconnection`: concurrent `accept()` callers on `NwStreamServer` now queue in `pendingAcceptConts: ArrayDeque<...>` instead of overwriting a single-slot `pendingAcceptCont` (silent continuation leak fix); `onNewConnection` from the listener's dispatch queue pops FIFO, `close()` resumes every queued waiter with `CancellationException` (#371)
- `engine-nodejs`: concurrent `accept()` callers on `NodeStreamServer` now queue in `pendingAcceptConts: ArrayDeque<...>` instead of overwriting a single-slot `pendingAcceptCont` (silent continuation leak fix); `onConnection` from Node.js `net.Server` pops FIFO, `close()` resumes every queued waiter with `CancellationException` (#370)
- `engine-netty`: concurrent `accept()` callers on `NettyStreamServer` now queue in `pendingAcceptConts: ArrayDeque<...>` instead of overwriting a single-slot `pendingAcceptCont` (silent continuation leak fix); `onNewChannel` from Netty's boss EventLoop pops FIFO, `close()` resumes every queued waiter with `CancellationException` (#369)
- `engine-io-uring`: concurrent `accept()` callers on the multishot path now queue in `pendingAcceptConts: ArrayDeque<...>` instead of overwriting a single-slot `pendingAcceptCont` (silent continuation leak fix); CQE delivery pops FIFO, `close()` resumes every queued waiter with `CancellationException` (#368)
- **BREAKING** (`engine-kqueue` / `engine-epoll` internal API): concurrent `accept()` callers on a shared `serverFd` now form a FIFO chain in `register()` instead of overwriting each other in the registrations map (silent continuation leak fix); `unregister(fd, interest)` is replaced by `unregister(reg: Registration)` and `cancelAll(fd, interest, cause)` is added (#367)
- `engine-kqueue` / `engine-epoll`: check return values on every `kevent` / `epoll_ctl` / `pthread_create` / `pipe` / `kqueue` / `eventfd` call (was swallowed in ~6 places across both engines). `register` / `registerCallback` now resume the caller with an exception instead of hanging forever on `kevent(EV_ADD)` failure; init-time failures clean up partially-allocated fds before throwing. Also eliminates the per-iteration `mutableListOf<Runnable>()` allocation in both EventLoops' `drainTasks()` hot path by reusing a field-level scratch buffer (#355)

### Documentation

- `core` / `io` / `website`: rewrite buffer ownership docs to the unified transfer model — single rule ("writes transfer, reads don't") + 3 `retain()` scenarios. Updates `buffer.md` (EN + JA), `IoBuf` / `Channel` / `IoTransport` / `SuspendSink` KDocs, and `keel-io` / `keel-core` module.md (#350)
- `core` / `io` / `website`: document `Channel.write(buf)` / `IoTransport.write(buf)` as retain-on-input (caller must still `release()`), not transfer; aligns `buffer.md` (EN + JA), KDocs, and `module.md` (#349)
- `website` (architecture): rewrite `buffer.md` (EN + JA) for first-time readers — ownership rules, thread-safety contract, per-platform implementation details, 6-way buffer API comparison, 5 factual-error fixes ([#348])

### Removed

- **BREAKING** (`native-posix-testing` test consumers): `@InternalTestApi` opt-in annotation removed. It was a guard against production callers reaching into test scaffolding when those lived inside `keel-native-posix`'s production source set; since PR #346 moved them into the test-only `keel-native-posix-testing` module, external consumers cannot reach them and the opt-in is redundant. Test consumers remove their `@OptIn(InternalTestApi::class)` annotations + the corresponding import. No runtime behaviour change ([#347])

### Changed

- **BREAKING** (`core` / `engine-*` / `ktor-engine`): rename `Server` → `StreamServer` and move `PipelinedServer` → `io.github.fukusaka.keel.pipeline.PipelinedStreamServer`; drop the `ServerChannel` deprecated alias kept since PR #197. Engine implementations follow: `{Kqueue,Epoll,IoUring,Nio,Netty,Node,Nw}Server` → `{…}StreamServer`, and `{Kqueue,Epoll,IoUring,Nio}PipelinedServerChannel` → `{…}PipelinedStreamServer` (Channel suffix dropped — servers are not Channels). Makes the transport family explicit and leaves room for a future `DatagramEngine` sibling (#354)
- `core`: reorganize pipeline internals — move `HeadHandler` / `TailHandler` / `DefaultPipeline` / `ReferenceCountUtil` into `io.github.fukusaka.keel.pipeline.internal` subpackage (with `DefaultPipeline` tightened from accidental-public to `internal`), nest `PropagateTrackingContext` into `TypedInboundHandler.kt` and `SuspendChannelSink` / `SuspendChannelSource` into `Channel.kt` as file-private helpers. Package structure now makes the public / internal boundary explicit (#353)
- `engine-netty`: route inbound + outbound buffers through Netty's own pooled `ByteBufAllocator`. `channelRead` wraps the incoming `ByteBuf` via `DirectIoBuf.wrapExternal` + `NettyByteBufOwner` (composite buffers fall back to copy); allocator returns `NettyByteBufIoBuf` so `flush()` hands the underlying `ByteBuf` to `writeAndFlush` via `retainedSlice`, eliminating `Unpooled.wrappedBuffer(duplicate())` per pending write. On a 32-core Linux loopback, `pipeline-http-netty` reaches 2.48M req/s at 4t/200c/16wt (matches `netty-raw`; +103% vs. `ktor-netty`), p99 latency 240 ms → 165 µs; GC pause drops ~180× (4.9 s → 27 ms per 30 s window) (#352)
- `io`: add `IoBufMemoryOwner` as a `val` on `IoBuf` — a pluggable release-strategy interface (`HeapOwner` / `PoolOwner` / `SliceOwner` / `ExternalWrapOwner` and engine-specific variants such as `RingSlotOwner`) invoked at refcount zero. Unifies the per-buffer release dispatch across the backing taxonomy and unblocks io_uring Fixed Buffers + Netty `ByteBuf` 2-stage allocators (#351)
- **BREAKING** (`core` / `io`): `Channel.write(buf)` / `IoTransport.write(buf)` / `SuspendSink.write(buf)` now use ownership-transfer semantics (match Netty `ByteBuf`). Callers must not call `IoBuf.release()` after write — transport takes the ref and releases after flush. Use `buf.retain()` before write to keep an alive ref. `readerIndex` advance at write time also dropped; snapshot captured in `PendingWrite` (match Netty `ChannelOutboundBuffer`) (#350)
- **BREAKING** (`native-posix` test consumers): `FakeNativeSocket` / `FakeNativeSocketOps` / `PosixRawClient` / `@InternalTestApi` extracted from `keel-native-posix` (production artifact) into a new `keel-native-posix-testing` module. Engine test modules must switch `implementation(project(":keel-native-posix"))` in test source sets to `implementation(project(":keel-native-posix-testing"))`. Import paths (`io.github.fukusaka.keel.native.posix.*`) are unchanged. Production artifact no longer carries test scaffolding; the 2 test-only C helpers (`keel_connect_inet_loopback` / `keel_set_rcvtimeo`) moved with `PosixRawClient` to a separate `posix_testing` cinterop def. Not published to Maven, not included in Dokka ([#346])

### Added

- `engine-epoll` / `engine-kqueue`: seam-level accept-path tests via `FakeNativeSocket.enqueueAccept` — covers `EpollServer.accept` / `KqueueServer.accept` Failed (ECONNABORTED / EMFILE) + Accepted (full `setNonBlocking` + `getRemoteAddress` + `getLocalAddress` + `childSocketOptions` chain) branches and `EpollPipelinedServerChannel.onAcceptable` / `KqueuePipelinedServerChannel.onAcceptable` Failed + WouldBlock re-arm branches. `onAcceptable` relaxed from `private` to `internal` so tests can drive the edge-triggered accept loop directly (6 cases per engine, 12 total) ([#342])
- `engine-nodejs`: `ConnectConfig.socketOptions` + `BindConfig.childSocketOptions` support — `tcpNoDelay` / `keepAlive` applied via Node.js `net.Socket.setNoDelay` / `setKeepAlive`. `receiveBufferSize` / `sendBufferSize` silently ignored (Node.js `net.Socket` exposes no buffer-size API). Completes the typed Socket Options API across all 6 engines (with platform-coverage note for NWConnection + Node.js) ([#341])
- `engine-nwconnection`: `ConnectConfig.socketOptions` + `BindConfig.childSocketOptions` support — `tcpNoDelay` / `keepAlive` applied via NW framework's TCP configure block (`nw_tcp_options_set_no_delay` / `_set_enable_keepalive`). New C wrappers `keel_nw_create_tcp_params_with_options` / `keel_nw_create_tls_tcp_params_with_options` / `keel_nw_create_tcp_params_unix_listener_with_options`. `receiveBufferSize` / `sendBufferSize` are silently ignored (NW framework has no buffer-size API) — platform-coverage note added to `SocketOptions` KDoc ([#340])
- `engine-nio` / `engine-netty`: `ConnectConfig.socketOptions` and `BindConfig.childSocketOptions` support for JVM engines. NIO applies via `SocketChannel.setOption(StandardSocketOptions.*)` before `connect(2)` (client) and after `accept(2)` (server); Netty uses `Bootstrap.option` / `ServerBootstrap.childOption` with `ChannelOption.*`. Completes the JVM side of the user-facing Socket Options API introduced for Native engines in #336 ([#339])
- `engine-epoll` / `engine-kqueue`: seam-level `bind()` happy-path tests via a real `socket(AF_INET, SOCK_STREAM, 0)` fd as sentinel — `bindListener` / `bindUnixListener` is scripted to return a real fd so `epoll_ctl(ADD)` / `kevent(EV_ADD)` succeeds, letting the engine read the scripted local address and construct `EpollServer` / `KqueueServer`. Closes the final `bind` gap in `*EngineLifecycleSeamTest`; full accept flow remains integration-only ([#338])
- `engine-epoll` / `engine-kqueue`: `EpollSuspendRegister` / `KqueueSuspendRegister` narrow seam (1 method `awaitWriteReady(fd, logger)`) over the "suspend until fd write-ready" pattern used by `connect()`'s `ConnectResult.InProgress` branch. `EpollEventLoop` / `KqueueEventLoop` implement directly; engine constructors accept a nullable override for tests. Unlocks seam-level tests for `InProgress → SO_ERROR != 0` (throws) and `InProgress → SO_ERROR 0` (happy path) — previously only integration-testable. Each engine gains 3 cases (TCP-error / TCP-success / UDS-error) ([#337])
- `core`: `SocketOptions` (typed properties: `tcpNoDelay` / `keepAlive` / `receiveBufferSize` / `sendBufferSize`) + `SocketOption` sealed type + `ConnectConfig(socketOptions)` + `BindConfig.childSocketOptions` — user-facing socket option configuration. `null` values leave kernel defaults; engines apply non-null properties via `setsockopt(2)` ([#336])
- `native-posix`: `NativeSocketOps.setSocketOption(fd, option)` primitive (11th method) + `applySocketOptions(fd, options)` free extension. Production `PosixNativeSocketOps` maps [`SocketOption` variants to `(level, optname, optval)` for `setsockopt(2)`]; `FakeNativeSocketOps` tracks applied options in `appliedOptions: List<Pair<Int, SocketOption>>` ([#336])
- `engine-epoll` / `engine-kqueue` / `engine-io-uring`: `connect(address, config: ConnectConfig)` override applies `socketOptions` to the client fd before `connect(2)`; `Server.accept` / `PipelinedServerChannel.onAcceptable` apply `BindConfig.childSocketOptions` to every accepted fd after `setNonBlocking`. `StreamEngine.connect(address, config)` default implementation throws `UnsupportedOperationException` for engines that haven't yet adopted ([#336])
- `engine-epoll` / `engine-kqueue`: seam-level engine lifecycle tests (`*EngineLifecycleSeamTest`) through `FakeNativeSocketOps` — 7 cases per engine covering `connect()` TCP+UDS `Connected` / `Failed(errno)` branches and `bind()` `bindListener` / `bindUnixListener` throw paths. First use of the `NativeSocketOps` cold-path seam at engine level ([#335])
- `native-posix`: `FakeNativeSocketOps.enqueue*Throws(vararg exceptions: Throwable)` helpers — scripts exceptions thrown from `bindListener` / `bindListener(reusePort)` / `bindUnixListener` / `openClientSocket` / `openUnixClientSocket`, emulating production failures like socket EMFILE / bind EADDRINUSE / bind EACCES. Interleaves FIFO with fd responses from the matching `enqueue*` method ([#335])
- `native-posix`: `NativeSocketOps` cold-path seam — 10-method minimal interface (`bindListener` + reusePort flag, `openClientSocket`, `connectNonBlocking`, `getSocketError`, `getLocalAddress`, `getRemoteAddress`, `setNonBlocking` + UDS variants) with `PosixNativeSocketOps` as the production singleton impl (renamed from `PosixSocketUtils`) + `FakeNativeSocketOps` for unit tests. Naming follows observable state transitions (`bindListener` = bind + listen returning listener fd, `openClientSocket` = socket + setNonBlocking returning unconnected fd). Parallels the `NativeSocket` hot-path seam but kept separate to keep the hot path 8-method narrow ([#334])
- `engine-*` (Native): inject `nativeSocketOps: NativeSocketOps = PosixNativeSocketOps` via engine constructor and thread through `*Server` / `*PipelinedServerChannel`. Unblocks seam tests for `connect()` / `accept` chain / bind-failure branches that were outside the `NativeSocket` seam. Also fixes latent bugs from PR #332 where `KqueueServer` / `IoUringServer` constructors were omitting the injected `nativeSocket` on the `bindInet` path, falling back to the singleton default ([#334])
- `engine-epoll` / `engine-kqueue` / `engine-io-uring`: seam-level errno-branch unit tests through `FakeNativeSocket` — `*TransportSeamTest` covers `shutdownOutput` + `flush` / `flushSingle` / `flushGather` (epoll/kqueue) and `shutdownOutput` + `flushDirectSendSingle` (io_uring); `*OnReadableSeamTest` (epoll/kqueue) exhausts `ReadResult` variants via a pipe-driven real EventLoop as direct regression coverage for the PR #321 `EINTR → onReadClosed` misclassification. 39 cases total; integration tests retain cross-fd coverage ([#333])
- `native-posix`: `FakeNativeSocket` — scripted in-memory `NativeSocket` impl with per-fd FIFO response queues for `read` / `write` / `writev` / `send` / `accept` / `connect` / `shutdown` / `close`, `default*` fallbacks, per-syscall call counters, ordered `closedFds` tracking, and `assertNoDoubleClose` / `assertAllConsumed` helpers. Lets unit tests drive engine code through specific errno branches without a real kernel ([#330])
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

- `engine-epoll` / `engine-kqueue` / `engine-io-uring`: expose `nativeSocket: NativeSocket = PosixNativeSocket` engine-constructor parameter and thread it through `*Server` / `*PipelinedServerChannel` / `*IoTransport`; engines now dispatch every POSIX syscall through the injected instance instead of the `PosixNativeSocket` singleton. Production behaviour unchanged (default resolves to the singleton); tests can inject `FakeNativeSocket` to drive errno branches without a real kernel ([#332])
- **BREAKING** (`native-posix`): `PosixSocketUtils.createServerSocket` / `createReusePortServerSocket` / `createUnixServerSocket` take a trailing `logger: Logger` parameter and route error-cleanup `close(fd)` through `closeFdSafely`. Closes the last production silent `close(fd)` inside `keel-native-posix` itself ([#331])
- `ci`: drop `pull_request` auto-trigger on the `io_uring stress` workflow — NativeSocket refactor (#323 → #328) fixed the flake root cause structurally, so per-PR 20-iteration stress runs are no longer needed. `workflow_dispatch` remains for on-demand diagnostics ([#329])
- `engine-epoll` / `engine-kqueue` / `engine-io-uring`: route every remaining production `close(fd)` (IoTransport teardown, Server / PipelinedServer close, EventLoop teardown, Engine connect cleanup / cancellation) through `closeFdSafely`. Previously silent drops on `close(2)` failure now surface as warn-level logs with fd + `<role>` context ([#328])
- `native-posix`: `closeFdSafely(fd, logger, context)` now routes through `PosixNativeSocket.close` (sealed `CloseResult`), so every production `close(fd)` call shares the `NativeSocket` seam and test fakes can track fd lifecycle uniformly ([#327])
- `native-posix` / `engine-epoll` / `engine-kqueue`: inline `PosixWrite.writeSingle` / `writeGather` back-compat helpers into `EpollIoTransport` / `KqueueIoTransport` flush paths and delete `PosixWrite.kt`. `WriteResult` sealed class moves to `NativeSocket.kt` alongside the other result types ([#326])
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
- `engine-netty`: replace blocking `LinkedBlockingQueue` I/O with `suspendCancellableCoroutine` + Netty listener callbacks
- `engine-netty`: enable `autoRead=false` for pull-model semantics and TCP backpressure
- `engine-kqueue`: replace blocking kevent wait with async EventLoop + `suspendCancellableCoroutine`
- `engine-kqueue`: add `KqueueEventLoop` with pipe wakeup and pthread-based event loop thread
- `engine-epoll`: replace blocking epoll_wait with async EventLoop + `suspendCancellableCoroutine`
- `engine-epoll`: add `EpollEventLoop` with eventfd wakeup and pthread-based event loop thread
- `engine-nwconnection`: replace blocking `dispatch_semaphore_wait` with async C wrappers + `suspendCancellableCoroutine`
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
- `engine-nio`: replace blocking SocketChannel with non-blocking mode + Selector EventLoop
- `engine-nio`: add `NioEventLoop` with Selector.wakeup and dedicated thread
- `engine-nio`: add `NioEventLoopGroup` for boss/worker model with round-robin channel assignment
- `engine-nio`: remove `ChannelSource`/`ChannelSink` — first engine fully migrated to `SuspendSource`/`SuspendSink`
- `core`: add `Channel.coroutineDispatcher` for engine-specific EventLoop dispatcher (default: `Dispatchers.Default`)
- `core`: add `kotlinx-coroutines-core` as `api` dependency in commonMain
- All engines: delete `ChannelSource`/`ChannelSink` and remove `asSource()`/`asSink()` from `Channel` interface
- `core`: remove kotlinx-io dependency — kotlinx-io is now confined to the codec layer

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
- `benchmark`: cross-language reference servers (Rust Axum, Go Gin, Swift Hummingbird, Zig std.http) with CLI config, profiles, and `--show-config`
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
[#326]: https://github.com/fukusaka/keel/pull/326
[#327]: https://github.com/fukusaka/keel/pull/327
[#328]: https://github.com/fukusaka/keel/pull/328
[#329]: https://github.com/fukusaka/keel/pull/329
[#330]: https://github.com/fukusaka/keel/pull/330
[#331]: https://github.com/fukusaka/keel/pull/331
[#332]: https://github.com/fukusaka/keel/pull/332
[#333]: https://github.com/fukusaka/keel/pull/333
[#334]: https://github.com/fukusaka/keel/pull/334
[#335]: https://github.com/fukusaka/keel/pull/335
[#336]: https://github.com/fukusaka/keel/pull/336
[#337]: https://github.com/fukusaka/keel/pull/337
[#338]: https://github.com/fukusaka/keel/pull/338
[#339]: https://github.com/fukusaka/keel/pull/339
[#340]: https://github.com/fukusaka/keel/pull/340
[#341]: https://github.com/fukusaka/keel/pull/341
[#342]: https://github.com/fukusaka/keel/pull/342
[#346]: https://github.com/fukusaka/keel/pull/346
[#347]: https://github.com/fukusaka/keel/pull/347
[#348]: https://github.com/fukusaka/keel/pull/348
