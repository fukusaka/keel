---
sidebar_position: 2
---

# Engine Selection Guide

keel provides multiple engine implementations. The engine is selected by
the Gradle dependency you include — no runtime switching is needed.

## Engines

### epoll (`keel-engine-epoll`)

- **Targets**: `linuxX64`, `linuxArm64`
- **Mechanism**: `epoll_create1` / `epoll_ctl` / `epoll_wait`, level-triggered
- **Use when**: building a Linux server binary

### kqueue (`keel-engine-kqueue`)

- **Targets**: `macosArm64`, `macosX64`
- **Mechanism**: `kqueue` / `kevent`, `EV_SET` wrapper
- **Use when**: building a macOS server binary or developing on M1/M2 Mac

### io_uring (`keel-engine-io-uring`)

- **Targets**: `linuxX64`, `linuxArm64`
- **Mechanism**: `io_uring` SQE/CQE ring buffers (Linux 5.1+)
- **Use when**: targeting modern Linux kernels for maximum throughput. Supports multishot accept, fixed buffers, and `SEND_ZC` zero-copy send
- **Note**: Requires Linux 5.1+ (basic), 5.19+ (multishot accept), 6.0+ (send zero-copy)

### NIO (`keel-engine-nio`)

- **Targets**: `jvm`
- **Mechanism**: `java.nio.Selector` + `ServerSocketChannel`
- **Use when**: JVM deployment without Netty dependency

### Netty (`keel-engine-netty`)

- **Targets**: `jvm`
- **Mechanism**: Netty 4.2 `ServerBootstrap` + `NioEventLoopGroup`
- **Use when**: integrating with an existing Netty ecosystem or needing
  Netty's battle-tested reliability

### Node.js net (`keel-engine-nodejs`)

- **Targets**: `js` (IR, `nodejs()`)
- **Mechanism**: Node.js `net` / `tls` module via Kotlin/JS external declarations
- **Use when**: targeting Node.js runtime

### NWConnection (`keel-engine-nwconnection`)

- **Targets**: `macosArm64`, `macosX64`
- **Mechanism**: `Network.framework` `NWListener` + `NWConnection`
- **Use when**: macOS App Store distribution or requiring system-managed TLS

## Choosing an Engine

| Platform | Recommended | Alternative |
|----------|-------------|-------------|
| Linux server | epoll or io_uring | — |
| macOS server | kqueue | NWConnection |
| JVM | NIO | Netty (ecosystem integration) |
| Node.js | nodejs | — |
| macOS + native TLS | NWConnection | kqueue + OpenSSL |
