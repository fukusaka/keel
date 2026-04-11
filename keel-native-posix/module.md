# Module keel-native-posix

Shared POSIX socket utilities for Native I/O engines (epoll, kqueue, io_uring).

Targets: **linuxX64**, **linuxArm64**, **macosArm64**, **macosX64**

## Role

`keel-native-posix` provides the `PosixSocketUtils` singleton used by all Native engine modules.
Engine modules (`keel-engine-epoll`, `keel-engine-kqueue`, `keel-engine-io-uring`) depend on this
module to avoid duplicating socket lifecycle code.

## C Interop

Two cinterop definitions expose POSIX functions that Kotlin/Native cannot bind directly:

| Definition | Headers | Provides |
|-----------|---------|---------|
| `posix_socket` | `sys/socket.h`, `netinet/in.h`, `arpa/inet.h`, `sys/uio.h`, `string.h` | `keel_inet_pton`, `keel_inet_ntop`, `keel_init_sockaddr_in`, `keel_htons`, `keel_ntohs`, `keel_htonl`, `keel_loopback_addr`, `keel_writev` |
| `posix_inet` (Linux only) | `sys/eventfd.h` | `keel_eventfd_create`, `keel_eventfd_write`, `keel_eventfd_read` — used by `EpollEventLoop` and `IoUringEventLoop` |

**Why wrappers are needed:**

- `inet_pton` / `inet_ntop`: cinterop binding is unreliable on some Linux cross-compilation configurations. Wrapped as `keel_inet_pton` / `keel_inet_ntop`.
- `htons` / `ntohs` / `htonl`: C macros — cinterop cannot bind macros. Wrapped as `keel_htons` / `keel_ntohs` / `keel_htonl`.
- `INADDR_LOOPBACK`: C macro — exposed as `keel_loopback_addr()`.
- `sin_family` type: differs between Linux (`UShort`) and macOS (`UByte`), causing commonization errors. `keel_init_sockaddr_in` sets all `sockaddr_in` fields from C, avoiding the type divergence.
- `writev`: provided as `keel_writev(fd, bases[], lens[], count)` — builds `iovec[]` internally for gather-write in a single syscall. Used by epoll and kqueue `IoTransport` for multiple pending buffers.

## PosixSocketUtils

`PosixSocketUtils` creates and configures POSIX TCP sockets for engine use:

| Function | Description |
|----------|-------------|
| `createServerSocket(host, port, backlog)` | `socket` → `SO_REUSEADDR` → non-blocking → `bind` → `listen` |
| `createReusePortServerSocket(host, port, backlog)` | Same as above + `SO_REUSEPORT`. Used by io_uring Pipeline mode — the kernel distributes connections across worker sockets by 4-tuple hash |
| `createUnconnectedSocket()` | Creates a non-blocking TCP socket; caller drives `connect()` |
| `connectNonBlocking(fd, host, port)` | Initiates non-blocking `connect()`. Returns 0 on immediate success (e.g. loopback) or -1 with `errno` set to `EINPROGRESS` for non-blocking sockets |
| `getSocketError(fd)` | Reads `SO_ERROR` via `getsockopt` after EventLoop reports WRITE readiness (non-blocking connect completion check) |
| `getLocalAddress(fd)` | `getsockname` → `SocketAddress` |
| `getRemoteAddress(fd)` | `getpeername` → `SocketAddress` |
| `setNonBlocking(fd)` | Sets `O_NONBLOCK` via `fcntl(F_GETFL)` + `fcntl(F_SETFL)` |
| `toSocketAddress(addr)` | Converts C `sockaddr_in` to keel `SocketAddress` via `keel_ntohs` + `keel_inet_ntop` |

All functions are IPv4 only (`AF_INET`). IPv6 support is deferred.

**`IntArray.usePinned` workaround**: `IntVar.value` and `socklen_tVar.value` assignment
fails on some Kotlin/Native versions. `getsockopt` and `setsockopt` calls use
`intArrayOf(...).usePinned { pinned -> ... }` to obtain a stable pointer.

## Key Types

| Type | Role |
|------|------|
| `PosixSocketUtils` | Singleton. POSIX TCP socket lifecycle: create, bind, connect, query |

# Package io.github.fukusaka.keel.native.posix

Shared POSIX socket utilities for Native engines: `PosixSocketUtils`.
