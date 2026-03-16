# keel

[![CI](https://github.com/keel-kt/keel/actions/workflows/ci.yml/badge.svg)](https://github.com/keel-kt/keel/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/Kotlin%20Multiplatform-✓-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![kotlinx.io](https://img.shields.io/badge/kotlinx.io-0.6.0-orange)](https://github.com/Kotlin/kotlinx-io)
[![Platforms](https://img.shields.io/badge/Platforms-Linux%20%7C%20macOS%20%7C%20JVM%20%7C%20JS-informational)](#targets)
[![Status](https://img.shields.io/badge/Status-Pre--release-yellow)](#roadmap)

> [日本語 README](README.ja.md)

**KMP Native Network I/O Engine.**
Drives epoll (Linux) and kqueue (macOS) directly from Kotlin Native.
Delegates to Netty on JVM. Designed as a Ktor Native engine.

---

## Overview

```
Application / Ktor DSL / gRPC KMP
        ↑
   keel  (fast, controllable I/O)
        ↑
epoll / kqueue / io_uring / NIO / NWConnection
```

keel focuses on *how to connect*, complementing Ktor's *what to build*.
They are complementary, not competing.

---

## Modules

```
keel/
├── core/                  # IoEngine / NativeBuf (expect/actual)
├── engine-epoll/          # linuxX64, linuxArm64
├── engine-kqueue/         # macosArm64, macosX64
├── engine-nio/            # JVM (java.nio.Selector)
├── engine-netty/          # JVM (Netty 4.x delegation)
├── engine-nodejs/         # JS nodejs()
├── engine-nwconnection/   # macosArm64, macosX64 (Network.framework)
├── codec-http/            # HTTP/1.1 parser / writer (RFC 7230/7231)
└── codec-websocket/       # WebSocket framing (RFC 6455)
```

---

## Targets

| Target | Engine |
|---|---|
| `linuxX64`, `linuxArm64` | epoll |
| `macosArm64`, `macosX64` | kqueue / NWConnection |
| `jvm` | NIO / Netty |
| `js (nodejs())` | Node.js net |

---

## Roadmap

| Phase | Description | Status |
|---|---|---|
| Phase 0 | Project scaffold | ✅ |
| Phase 1 | kqueue engine | ✅ |
| Phase 2 | epoll engine | ✅ |
| Phase 3 | NIO / Netty / Node.js / NWConnection engines | ✅ |
| Phase 4 | HTTP/1.1 codec, WebSocket codec | ✅ |
| Phase 4.5 | OSS prep (LICENSE / README / KDoc / Dokka / Docusaurus) | 🔄 |
| Phase 5 | IoEngine redesign / BufferAllocator / Ktor adapter | 🔲 |
| Phase 6 | TLS (Mbed TLS) / io_uring | 🔲 |
| Phase 7 | UDP / MQTT / HTTP2 / gRPC and more | 🔲 |

---

## Building

### Prerequisites

- Java 21+ (Temurin recommended)
- Gradle 9.4+ (wrapper included)
- macOS builds: M1/M2 Mac recommended

### Run tests

```bash
# JVM tests
./gradlew :engine-nio:jvmTest :engine-netty:jvmTest
./gradlew :codec-http:jvmTest :codec-websocket:jvmTest

# macOS Native tests
./gradlew :engine-kqueue:macosArm64Test
./gradlew :codec-http:macosArm64Test :codec-websocket:macosArm64Test

# Linux tests (Docker)
docker run --rm --platform linux/amd64 \
  -v $(pwd):/work -w /work gradle:8-jdk21 \
  ./gradlew :engine-epoll:linuxX64Test

# Generate API docs (Dokka)
./gradlew dokkaHtmlMultiModule
```

---

## License

Apache License 2.0 — Copyright 2026 The keel-kt Authors.
See [LICENSE](LICENSE) for details.
