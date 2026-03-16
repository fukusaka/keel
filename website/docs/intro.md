---
sidebar_position: 1
---

# Getting Started

keel is a Kotlin Multiplatform Native network I/O engine library.

On JVM it delegates to Netty. On Native targets (Linux, macOS) it drives
epoll / kqueue directly. The goal is to serve as a Ktor Native engine.

```
Application / Ktor DSL / gRPC KMP
        ↑
   keel  (fast, controllable I/O)
        ↑
epoll / kqueue / io_uring / NIO / NWConnection
```

## Modules

| Module | Description |
|---|---|
| `:core` | `IoEngine` / `NativeBuf` expect declarations |
| `:engine-epoll` | Linux (epoll) |
| `:engine-kqueue` | macOS (kqueue) |
| `:engine-nio` | JVM (java.nio.Selector) |
| `:engine-netty` | JVM (Netty delegation) |
| `:engine-nodejs` | JS (Node.js net) |
| `:engine-nwconnection` | Apple (Network.framework) |
| `:codec-http` | HTTP/1.1 parser / writer |
| `:codec-websocket` | WebSocket framing |

## Requirements

- Kotlin 2.1+
- JVM 11+ (for JVM targets)
- Linux 4.5+ (for epoll)
- macOS 10.14+ (for kqueue)

## License

Apache 2.0 — Copyright 2026 The keel-kt Authors
