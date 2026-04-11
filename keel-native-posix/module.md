# Module keel-native-posix

Shared POSIX socket utilities for Native I/O engines (epoll, kqueue, io_uring).

Targets: **linuxX64**, **linuxArm64**, **macosArm64**, **macosX64**

## Role

`keel-native-posix` provides the `PosixSocketUtils` singleton used by all Native engine modules.
Engine modules (`keel-engine-epoll`, `keel-engine-kqueue`, `keel-engine-io-uring`) depend on this
module to avoid duplicating socket lifecycle code.

## C Interop

Two cinterop definitions wrap POSIX functions that Kotlin/Native cannot bind directly:

| Definition | Headers | Purpose |
|-----------|---------|---------|
| `posix_socket` | `sys/socket.h`, `netinet/in.h`, `arpa/inet.h`, `sys/uio.h` | `keel_inet_pton`, `keel_inet_ntop`, `keel_init_sockaddr_in`, `keel_ntohs` — address conversion helpers |
| `posix_inet` | `sys/eventfd.h` (Linux only) | `eventfd(2)` for epoll wakeup |

`inet_pton` / `inet_ntop` are wrapped (`keel_inet_pton` / `keel_inet_ntop`) because
direct Kotlin/Native binding is unreliable across cross-compilation targets.
`htons` is a C macro and requires `keel_ntohs`.

## PosixSocketUtils

`PosixSocketUtils` creates and configures POSIX TCP sockets for engine use:

| Function | Description |
|----------|-------------|
| `createServerSocket(host, port, backlog)` | `socket` → `SO_REUSEADDR` → non-blocking → `bind` → `listen` |
| `createReusePortServerSocket(host, port, backlog)` | Same as above + `SO_REUSEPORT` for io_uring Pipeline mode (kernel distributes connections by 4-tuple hash) |
| `createUnconnectedSocket()` | Creates a non-blocking TCP socket; caller drives `connect()` |
| `connectNonBlocking(fd, host, port)` | Initiates non-blocking `connect()`. Returns `EINPROGRESS` for non-loopback |
| `getSocketError(fd)` | Reads `SO_ERROR` via `getsockopt` after WRITE readiness (connect completion check) |
| `getLocalAddress(fd)` | `getsockname` → `SocketAddress` |
| `getRemoteAddress(fd)` | `getpeername` → `SocketAddress` |
| `setNonBlocking(fd)` | Sets `O_NONBLOCK` via `fcntl(F_GETFL)` + `fcntl(F_SETFL)` |
| `toSocketAddress(addr)` | Converts C `sockaddr_in` to keel `SocketAddress` |

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
