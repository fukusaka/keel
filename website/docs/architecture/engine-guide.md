---
sidebar_position: 2
---

# Engine Selection Guide

keel provides multiple engine implementations. The engine is selected by
the Gradle dependency you include — no runtime switching is needed.

## Engines

### epoll (`:engine-epoll`)

- **Targets**: `linuxX64`, `linuxArm64`
- **Mechanism**: `epoll_create1` / `epoll_ctl` / `epoll_wait`, level-triggered
- **Use when**: building a Linux server binary

### kqueue (`:engine-kqueue`)

- **Targets**: `macosArm64`, `macosX64`
- **Mechanism**: `kqueue` / `kevent`, `EV_SET` wrapper
- **Use when**: building a macOS server binary or developing on M1/M2 Mac

### NIO (`:engine-nio`)

- **Targets**: `jvm`
- **Mechanism**: `java.nio.Selector` + `ServerSocketChannel`
- **Use when**: JVM deployment without Netty dependency

### Netty (`:engine-netty`)

- **Targets**: `jvm`
- **Mechanism**: Netty 4.x `ServerBootstrap` + `NioEventLoopGroup`
- **Use when**: integrating with an existing Netty ecosystem or needing
  Netty's battle-tested reliability

### Node.js net (`:engine-nodejs`)

- **Targets**: `js` (IR, `nodejs()`)
- **Mechanism**: Node.js `net` module via Kotlin/JS external declarations
- **Use when**: targeting Node.js runtime

### NWConnection (`:engine-nwconnection`)

- **Targets**: `macosArm64`, `macosX64`
- **Mechanism**: `Network.framework` `NWListener` + `NWConnection`
- **Use when**: macOS App Store distribution or requiring system-managed TLS

## Planned

- **io_uring** (`:engine-io-uring`) — Phase 6, Linux 5.1+, zero-copy send
