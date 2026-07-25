# Module keel-native-posix

Shared POSIX socket utilities for Native I/O engines (epoll, kqueue, io_uring).

Targets: **linuxX64**, **linuxArm64**, **macosArm64**, **macosX64**

## Role

`keel-native-posix` provides the shared POSIX socket surface used by all Native engine
modules: the `NativeSocket` / `NativeSocketOps` seams with their production
implementations `PosixNativeSocket` / `PosixNativeSocketOps`, plus the helpers
`errnoMessage`, `closeFdSafely`, and `applySocketOptions`, and the `LoopHandoff`
the readiness engines share for handing work to their EventLoop thread.
Engine modules (`keel-engine-epoll`, `keel-engine-kqueue`, `keel-engine-io-uring`) depend on this
module to avoid duplicating socket lifecycle code.

## C Interop

Two cinterop definitions expose POSIX functions that Kotlin/Native cannot bind directly:

| Definition | Headers | Provides |
|-----------|---------|---------|
| `posix_socket` | `sys/socket.h`, `netinet/in.h`, `arpa/inet.h`, `sys/uio.h`, `string.h`, and friends | Byte-order / address helpers (`keel_inet_pton`, `keel_inet_ntop`, `keel_init_sockaddr_in`, `keel_htons`, `keel_ntohs`, `keel_htonl`, `keel_loopback_addr`), gather write (`keel_writev`), EINTR-retry syscall wrappers (`keel_read` / `keel_write` / `keel_accept` / `keel_connect` / `keel_send` / `keel_shutdown`), IPv6 sockaddr and Unix-domain-socket helpers, `keel_errno_message`, `keel_set_nosigpipe` |
| `posix_inet` (Linux only) | `sys/eventfd.h` | `keel_eventfd_create`, `keel_eventfd_write`, `keel_eventfd_read` — used by `EpollEventLoop` and `IoUringEventLoop` |

**Why wrappers are needed:**

- `inet_pton` / `inet_ntop`: cinterop binding is unreliable on some Linux cross-compilation configurations. Wrapped as `keel_inet_pton` / `keel_inet_ntop`.
- `htons` / `ntohs` / `htonl`: C macros — cinterop cannot bind macros. Wrapped as `keel_htons` / `keel_ntohs` / `keel_htonl`.
- `INADDR_LOOPBACK`: C macro — exposed as `keel_loopback_addr()`.
- `sin_family` type: differs between Linux (`UShort`) and macOS (`UByte`), causing commonization errors. `keel_init_sockaddr_in` sets all `sockaddr_in` fields from C, avoiding the type divergence.
- `writev`: provided as `keel_writev(fd, bases[], lens[], count)` — builds `iovec[]` internally for gather-write in a single syscall. Used by epoll and kqueue `IoTransport` for multiple pending buffers.

## NativeSocketOps

`NativeSocketOps` is the socket-lifecycle seam; `PosixNativeSocketOps` is the
production implementation:

| Function | Description |
|----------|-------------|
| `bindListener(address, port, backlog, reusePort)` | `socket` → `SO_REUSEADDR` [→ `SO_REUSEPORT`] → non-blocking → `bind` → `listen`. `reusePort = true` enables kernel-side load balancing across worker sockets |
| `openClientSocket(family)` | Creates a non-blocking TCP socket (unconnected); caller drives `connectNonBlocking` |
| `connectNonBlocking(fd, address, port)` | Initiates non-blocking `connect()`. Returns a `ConnectResult` — `Connected` on immediate success (e.g. loopback), `InProgress` on `EINPROGRESS` / `EINTR`, or `Failed(errno)` otherwise |
| `getSocketError(fd)` | Reads `SO_ERROR` via `getsockopt` after EventLoop reports WRITE readiness (non-blocking connect completion check) |
| `getLocalAddress(fd)` / `getRemoteAddress(fd)` | `getsockname` / `getpeername` → `SocketAddress`, auto-detecting V4 / V6 / UNIX |
| `setNonBlocking(fd)` | Sets `O_NONBLOCK` via `fcntl` |
| `setSocketOption(fd, option)` | Applies a single `SocketOption`; `applySocketOptions(fd, options)` applies a whole `SocketOptions` set |
| `bindUnixListener(address, backlog)` / `openUnixClientSocket()` / `connectUnixNonBlocking(fd, address)` | `AF_UNIX` / `SOCK_STREAM` equivalents for Unix domain sockets |

The socket family follows the `IpAddress` argument: `IpAddress.V4` → `AF_INET`,
`IpAddress.V6` → `AF_INET6` — both IPv4 and IPv6 are supported, plus `AF_UNIX`
for Unix domain sockets.

**`IntArray.usePinned` workaround**: `IntVar.value` and `socklen_tVar.value` assignment
fails on some Kotlin/Native versions. `getsockopt` and `setsockopt` calls use
`intArrayOf(...).usePinned { pinned -> ... }` to obtain a stable pointer.

## Key Types

| Type | Role |
|------|------|
| `NativeSocket` | Interface. Data-path syscalls (8 methods): `read` / `write` / `writev` / `send` / `accept` / `connect` / `shutdown` / `close`. Sealed `Result` types replace raw errno |
| `PosixNativeSocket` | Singleton. Production `NativeSocket` impl backed by EINTR-retrying `keel_*` C wrappers |
| `NativeSocketOps` | Interface. Socket lifecycle: bind / listen, client socket creation, non-blocking connect, address queries, socket options (TCP + Unix domain) |
| `PosixNativeSocketOps` | Production `NativeSocketOps` impl |
| `errnoMessage(errno)` | Thread-safe errno-to-string helper (`strerror_r`-based) |
| `closeFdSafely(fd, logger, context)` | Cleanup-path `close(2)` that logs failures instead of dropping them silently |
| `applySocketOptions(fd, options)` | Applies a `SocketOptions` set through `NativeSocketOps.setSocketOption` |
| `LoopHandoff` | Off-loop to EventLoop hand-off for the readiness engines: runs work on the loop thread, or a fallback on the caller once the loop has stopped. Shared by `keel-engine-epoll` / `keel-engine-kqueue` so the shutdown-race handling has one implementation |

Test doubles for these seams (`FakeNativeSocket`, `FakeNativeSocketOps`, and the
blocking loopback client `PosixRawClient`) live in the `keel-testing-internal`
module, not here.

# Package io.github.fukusaka.keel.native.posix

Shared POSIX socket utilities for Native engines: the `NativeSocket` /
`NativeSocketOps` seams (`PosixNativeSocket` / `PosixNativeSocketOps` in
production) plus the `errnoMessage`, `closeFdSafely`, and
`applySocketOptions` helpers.
