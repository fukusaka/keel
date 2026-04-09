---
sidebar_position: 1
---

# Architecture Overview

## Layer Structure

```
Application / Ktor DSL
        ↑
   keel-ktor-engine (Ktor adapter)
        ↑
Codec layer  (keel-codec-http, keel-codec-websocket)
        ↑
Pipeline layer  (ChannelPipeline — push-mode handler chain)
        ↑
TLS layer  (keel-tls-jsse, keel-tls-openssl, keel-tls-mbedtls, keel-tls-awslc)
        ↑
Engine layer  (keel-engine-epoll, keel-engine-kqueue, keel-engine-io-uring, ...)
        ↑
keel-core  (StreamEngine / IoBuf / BindConfig / BufferAllocator)
```

keel focuses on **how to connect**, complementing Ktor's **what to build**.

## Two I/O Modes

keel provides two I/O modes:

- **Channel mode**: suspend-based `read()` / `write()` per connection. Used via `engine.bind()` + `server.accept()`. Suitable for Ktor integration.
- **Pipeline mode**: push-based `ChannelPipeline` with handler chains. Used via `engine.bindPipeline()`. Zero coroutine overhead on the I/O hot path for maximum throughput.

See [Pipeline Mode](./pipeline.md) for details.

## Design Principles

- **Engine-independent codecs** — codec modules depend only on `kotlinx.io`;
  they work on any source/sink regardless of the underlying engine.
- **ChannelPipeline** — Netty-inspired handler chain for Pipeline mode. Each handler
  processes inbound (read) or outbound (write) data and passes results to the next handler.
- **Native memory control** — `IoBuf` uses `nativeHeap` on Native targets
  and `ByteBuffer.allocateDirect` on JVM for zero-copy I/O.
- **Pluggable allocator** — `BufferAllocator` allows per-engine
  memory strategies: `SlabAllocator` (Native), `PooledDirectAllocator` (JVM NIO),
  `HeapAllocator` (testing).

## KMP Targets

| Target | Engine | Status |
|---|---|---|
| linuxX64 / linuxArm64 | epoll | ✅ |
| linuxX64 / linuxArm64 | io_uring (Linux 5.1+) | ✅ |
| macosArm64 / macosX64 | kqueue | ✅ |
| macosArm64 / macosX64 | NWConnection | ✅ |
| JVM | NIO / Netty | ✅ |
| JS nodejs() | Node.js net/tls | ✅ |
| iosArm64 / iosSimulatorArm64 | NWConnection | 🔲 Planned |

## TLS Strategy

| Platform | Backend | Module |
|---|---|---|
| JVM | JSSE (JDK SSLContext) | `keel-tls-jsse` |
| Native (Linux/macOS) | OpenSSL | `keel-tls-openssl` |
| Native (Linux/macOS) | Mbed TLS | `keel-tls-mbedtls` |
| Native (Linux/macOS) | AWS-LC | `keel-tls-awslc` |
| macOS (NWConnection) | Network.framework (listener-level) | `keel-engine-nwconnection` |
| JS (Node.js) | Node.js tls (listener-level) | `keel-engine-nodejs` |

See [TLS](./tls.md) for details.
