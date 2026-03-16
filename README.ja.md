# keel

[![CI](https://github.com/keel-kt/keel/actions/workflows/ci.yml/badge.svg)](https://github.com/keel-kt/keel/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/Kotlin%20Multiplatform-✓-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![kotlinx.io](https://img.shields.io/badge/kotlinx.io-0.6.0-orange)](https://github.com/Kotlin/kotlinx-io)
[![Platforms](https://img.shields.io/badge/Platforms-Linux%20%7C%20macOS%20%7C%20JVM%20%7C%20JS-informational)](#ターゲット)
[![Status](https://img.shields.io/badge/Status-Pre--release-yellow)](#ロードマップ)

> [English README](README.md)

**KMP Native ネットワーク I/O エンジンライブラリ。**
epoll / kqueue を Kotlin Native から直接駆動し、JVM では Netty に委譲する。
最終目標は Ktor の Native エンジンとして採用されること。

---

## 概要

```
アプリケーション / Ktor DSL / gRPC KMP
        ↑
   keel（I/O の「速さ・制御」を提供）
        ↑
epoll / kqueue / io_uring / NIO / NWConnection
```

Ktor の「何を作るか」フレームワークに対し、keel は「どう繋ぐか」のエンジン。
競合ではなく補完関係。

---

## モジュール構成

```
keel/
├── core/                  # IoEngine / NativeBuf（expect/actual）
├── engine-epoll/          # linuxX64, linuxArm64
├── engine-kqueue/         # macosArm64, macosX64
├── engine-nio/            # JVM（java.nio.Selector）
├── engine-netty/          # JVM（Netty 4.x 委譲）
├── engine-nodejs/         # JS nodejs()
├── engine-nwconnection/   # macosArm64, macosX64（Network.framework）
├── codec-http/            # HTTP/1.1 パーサー／ライター（RFC 7230/7231）
└── codec-websocket/       # WebSocket フレーミング（RFC 6455）
```

---

## ターゲット

| ターゲット | エンジン |
|---|---|
| `linuxX64`, `linuxArm64` | epoll |
| `macosArm64`, `macosX64` | kqueue / NWConnection |
| `jvm` | NIO / Netty |
| `js (nodejs())` | Node.js net |

---

## ロードマップ

| フェーズ | 内容 | 状態 |
|---|---|---|
| Phase 0 | プロジェクト骨格 | ✅ |
| Phase 1 | kqueue エンジン | ✅ |
| Phase 2 | epoll エンジン | ✅ |
| Phase 3 | NIO / Netty / Node.js / NWConnection エンジン | ✅ |
| Phase 4 | HTTP/1.1 コーデック・WebSocket コーデック | ✅ |
| Phase 4.5 | OSS 公開前整備（LICENSE / README / KDoc / Dokka / Docusaurus）| 🔄 |
| Phase 5 | IoEngine 再設計 / BufferAllocator / Ktor アダプタ | 🔲 |
| Phase 6 | TLS（Mbed TLS）/ io_uring | 🔲 |
| Phase 7 | UDP / MQTT / HTTP2 / gRPC など | 🔲 |

---

## ビルド

### 前提

- Java 21+（Temurin 推奨）
- Gradle 9.4+（wrapper 同梱）
- macOS ビルド：M1/M2 Mac 推奨

### ローカルビルド・テスト

```bash
# JVM テスト
./gradlew :engine-nio:jvmTest :engine-netty:jvmTest
./gradlew :codec-http:jvmTest :codec-websocket:jvmTest

# macOS Native テスト
./gradlew :engine-kqueue:macosArm64Test
./gradlew :codec-http:macosArm64Test :codec-websocket:macosArm64Test

# Linux テスト（Docker）
docker run --rm --platform linux/amd64 \
  -v $(pwd):/work -w /work gradle:8-jdk21 \
  ./gradlew :engine-epoll:linuxX64Test

# API ドキュメント生成（Dokka）
./gradlew dokkaHtmlMultiModule
```

---

## ライセンス

Apache License 2.0 — Copyright 2026 The keel-kt Authors
詳細は [LICENSE](LICENSE) を参照。
