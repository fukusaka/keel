---
sidebar_position: 1
---

# Architecture Overview

## Layer Structure

```
Application / Ktor DSL / gRPC KMP
        ↑
   keel  (:ktor-engine, :server, :client)
        ↑
Codec layer  (:codec-http, :codec-websocket, :codec-http2, …)
        ↑
Engine layer  (:engine-epoll, :engine-kqueue, :engine-nio, …)
        ↑
:core  (IoEngine / NativeBuf expect/actual)
```

keel focuses on **how to connect**, complementing Ktor's **what to build**.

## Design Principles

- **Engine-independent codecs** — codec modules depend only on `kotlinx.io`;
  they work on any source/sink regardless of the underlying engine.
- **No ChannelPipeline** — Kotlin function composition replaces Netty-style
  handler chains.
- **Native memory control** — `NativeBuf` uses `nativeHeap` on Native targets
  and `ByteBuffer.allocateDirect` on JVM for zero-copy I/O.
- **Pluggable allocator** — `BufferAllocator` (Phase 5) allows per-engine
  memory strategies: `SlabAllocator` (Native), `PooledDirectAllocator` (JVM NIO),
  `HeapAllocator` (testing).

## KMP Targets

| Target | Engine | Priority |
|---|---|---|
| macosArm64 / macosX64 | kqueue | Phase 1 |
| linuxX64 / linuxArm64 | epoll | Phase 2 |
| JVM | NIO / Netty | Phase 3 |
| JS nodejs() | Node.js net | Phase 3 |
| macosArm64 / macosX64 | NWConnection | Phase 3.5 |
| iosArm64 | NWConnection | Phase 6+ |
| linuxX64 / linuxArm64 | io_uring | Phase 6 |

## TLS Strategy

| Target | Library |
|---|---|
| Linux / macOS | Mbed TLS (Apache 2.0) |
| iOS | SecureTransport |
| JVM | JSSE |
