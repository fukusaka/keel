---
sidebar_position: 1
---

# Getting Started

keel is a Kotlin Multiplatform Native network I/O engine library.

On JVM it delegates to Netty or NIO. On Native targets (Linux, macOS) it drives
epoll / kqueue / io_uring directly. On JS it uses Node.js net/tls.

```
Application / Ktor DSL
        ↑
   keel  (fast, controllable I/O + TLS + codec)
        ↑
epoll / kqueue / io_uring / NIO / Netty / NWConnection / Node.js
```

## Modules

| Module | Description |
|---|---|
| `keel-core` | StreamEngine / Channel / Server / BindConfig / Logger |
| `keel-io` | IoBuf / SuspendSource / SuspendSink / BufferAllocator |
| `keel-native-posix` | Shared POSIX socket utils for Native engines |
| `keel-engine-epoll` | Linux (epoll) |
| `keel-engine-kqueue` | macOS (kqueue) |
| `keel-engine-io-uring` | Linux (io_uring, Linux 5.1+) |
| `keel-engine-nio` | JVM (java.nio.Selector) |
| `keel-engine-netty` | JVM (Netty 4.2 delegation) |
| `keel-engine-nodejs` | JS (Node.js net/tls) |
| `keel-engine-nwconnection` | Apple (Network.framework) |
| `keel-tls` | TlsConfig / TlsInstaller / PemDerConverter |
| `keel-tls-jsse` | JVM TLS (JSSE / JDK SSLContext) |
| `keel-tls-openssl` | Native TLS (OpenSSL cinterop) |
| `keel-tls-mbedtls` | Native TLS (Mbed TLS cinterop) |
| `keel-tls-awslc` | Native TLS (AWS-LC cinterop) |
| `keel-tls-nodejs` | JS TLS (Node.js tls module) |
| `keel-codec-http` | HTTP/1.1 parser / writer |
| `keel-codec-websocket` | WebSocket framing |
| `keel-ktor-engine` | Ktor server engine adapter |

## Requirements

- Kotlin 2.3.20+
- JVM 21+ (for JVM targets)
- Linux 4.5+ (for epoll), 5.1+ (for io_uring)
- macOS 10.14+ (for kqueue / NWConnection)

## License

Apache 2.0 — Copyright 2026 fukusaka
