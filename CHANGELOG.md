# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
