# keel — KMP Native ネットワーク I/O エンジン

[![CI](https://github.com/fukusaka/keel/actions/workflows/ci.yml/badge.svg)](https://github.com/fukusaka/keel/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/Kotlin%20Multiplatform-✓-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![kotlinx.io](https://img.shields.io/badge/kotlinx.io-0.9.0-orange)](https://github.com/Kotlin/kotlinx-io)
[![Platforms](https://img.shields.io/badge/Platforms-Linux%20%7C%20macOS%20%7C%20JVM%20%7C%20JS-informational)](#ターゲット)
[![Status](https://img.shields.io/badge/Status-Pre--release-yellow)](#ロードマップ)

Linux の epoll、macOS の kqueue、JVM の Netty — プラットフォームごとに異なる I/O プリミティブを、keel が単一の Kotlin Multiplatform インターフェースに統一します。
KMP 上でネットワークプロトコルを実装するための適切な基盤を提供します。

- **Native ファースト**: epoll（Linux）・kqueue（macOS）を Kotlin Native から直接駆動
- **suspend API**: 全 I/O 操作が `suspend fun`、非同期イベントループ対応準備済み
- **プラットフォーム最適**: Native=epoll/kqueue · JVM=NIO/Netty · Node.js=net モジュール
- **コーデック層**: kotlinx.io プリミティブのみで構成した HTTP/1.1・WebSocket

```
  ┌────────────────────────────────────────┐
  │      アプリケーション / Ktor            │
  └───────────────┬────────────────────────┘
                  │
      ┌───────────┴───────────┐
      │          keel         │
      │   I/O エンジン層       │
      └───────────┬───────────┘
                  │
  ┌──────┬──────┬──────┬───────┬───────────────┐
  │epoll │kqueue│ NIO  │Netty  │ NWConnection  │
  │Linux │macOS │ JVM  │ JVM   │   Apple       │
  └──────┴──────┴──────┴───────┴───────────────┘
```

> [!WARNING]
> **初期の実験的リリース（0.2.0）** — API は不安定であり、予告なく変更される可能性があります。本番環境での使用は推奨しません。

---

## ドキュメント

- Web サイト — アーキテクチャガイド・How-to・コーデックドキュメント（準備中）
- API リファレンス — Dokka 生成 KDoc（準備中）
- [English README](README.md)

---

## モジュール構成

```
keel/
├── core/                  # IoEngine / Channel / ServerChannel（expect/actual）
├── io-core/               # NativeBuf / SuspendSource / SuspendSink / BufferAllocator
├── engine-epoll/          # linuxX64, linuxArm64
├── engine-kqueue/         # macosArm64, macosX64
├── engine-nio/            # JVM（java.nio.Selector）
├── engine-netty/          # JVM（Netty 4.x 委譲）
├── engine-nodejs/         # JS nodejs()
├── engine-nwconnection/   # macosArm64, macosX64（Network.framework）
├── codec-http/            # HTTP/1.1 パーサー／ライター（RFC 7230/7231）
├── codec-websocket/       # WebSocket フレーミング（RFC 6455）
└── ktor-engine/           # Ktor サーバーエンジンアダプタ
```

---

## ターゲット

| ターゲット | エンジン | 状態 | 備考 |
|---|---|---|---|
| `linuxX64`, `linuxArm64` | epoll | ✅ | |
| `macosArm64` | kqueue / NWConnection | ✅ | |
| `macosX64` | kqueue / NWConnection | ✅ | Kotlin 2.3 で deprecated（Tier 3） |
| `jvm` | NIO / Netty | ✅ | |
| `js (nodejs())` | Node.js net | ✅ | |
| `iosArm64`, `iosSimulatorArm64` | NWConnection | 🔲 予定 | クライアント限定 |
| `mingwX64` | IOCP | 🔲 保留 | |
| `androidNativeArm64`, `androidNativeX64` | epoll | 🔲 保留 | |
| `tvosArm64`, `watchosArm64` | — | ❌ 対象外 | サンドボックス制約により対応困難 |
| `wasmJs`, `wasmWasi` | — | ❌ 対象外 | syscall 直接アクセス不可 |

---

## ロードマップ

### 0.2.0（現在 — 実験的）

- 6 種の I/O エンジン: epoll, kqueue, NIO, Netty, NWConnection, Node.js
- 完全非同期 EventLoop + コルーチン統合（ノンブロッキング I/O）
- HTTP/1.1 keep-alive
- Boss/Worker EventLoop 分離 + スレッド数設定可能
- Ktor サーバーエンジンアダプタ + ディスパッチャ分離（I/O は EventLoop、pipeline は Default）

### Next（Phase 6）

- SlabAllocator（メモリプール）
- codec-http ゼロコピー（BufSlice on NativeBuf）
- TLS（Mbed TLS）
- io_uring エンジン（Linux 5.1+）

### Future（Phase 7+）

- Push Channel + keel ネイティブサーバー（:server、Ktor 非依存）
- UDP トランスポート
- HTTP/2, gRPC, MQTT
- iOS / Android ターゲット
- HTTP/3 (QUIC)

---

## インストール

> **注意:** keel はまだ Maven Central に公開されていません。ソースからビルドしてローカル Maven リポジトリに公開して使用してください。

```bash
git clone https://github.com/fukusaka/keel.git
cd keel
./gradlew publishToMavenLocal
```

プロジェクトへの依存関係の追加:

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
}

dependencies {
    // Ktor + keel サーバーエンジン
    implementation("io.github.fukusaka.keel:ktor-engine:0.2.0")
    implementation("io.ktor:ktor-server-core:3.4.1")

    // 低レベル I/O（Ktor なし）
    implementation("io.github.fukusaka.keel:core:0.2.0")

    // コーデック（任意）
    implementation("io.github.fukusaka.keel:codec-http:0.2.0")
    implementation("io.github.fukusaka.keel:codec-websocket:0.2.0")
}
```

Maven Central への公開は将来のリリースで予定しています。

---

## クイックスタート

```kotlin
fun main() {
    embeddedServer(Keel, port = 8080) {
        routing {
            get("/") {
                call.respondText("Hello from keel!")
            }
        }
    }.start(wait = true)
}
```

```bash
./gradlew :sample:run
# → http://localhost:8080/
```

---

## ビルド

### 前提

- Java 21+
- Gradle 9.4+（wrapper 同梱）
- macOS ビルド：M1/M2 Mac 推奨

### テスト実行

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
./gradlew dokkaGeneratePublicationHtml
```

---

## ベンチマーク

### 計測方法

```
  ┌─────────────┐         loopback          ┌─────────────┐
  │  wrk client  │ ──── 127.0.0.1:18090 ──── │  HTTP server │
  │  4 threads   │    100 connections         │  GET /hello  │
  │  10s run     │                            │  → 13 bytes  │
  └─────────────┘                             └─────────────┘
```

- **エンドポイント**: `GET /hello` → `"Hello, World!"`（13 bytes, text/plain）
- **計測ツール**: [wrk](https://github.com/wg/wrk) — 4 スレッド、100 並列接続、10 秒間
- **構成**: クライアントとサーバーが同一ホスト上（ループバック通信）
- **p50 / p99**: 50 パーセンタイル / 99 パーセンタイルのレスポンスレイテンシ

### サーバー一覧

| プレフィックス | カテゴリ | 説明 |
|--------|----------|-------------|
| `native:ktor-keel-*` | **keel（Native）** | Ktor + keel I/O エンジン、ネイティブバイナリ |
| `jvm:ktor-keel-*` | **keel（JVM）** | Ktor + keel I/O エンジン、JVM 上で実行 |
| `native:ktor-cio` | Ktor CIO（Native） | Ktor 標準 CIO エンジン、ネイティブバイナリ |
| `jvm:ktor-cio` | Ktor CIO（JVM） | Ktor 標準 CIO エンジン、JVM 上で実行 |
| `jvm:ktor-netty` | Ktor + Netty | Ktor の Netty エンジンアダプタ |
| `jvm:spring` | Spring WebFlux | Spring Boot + Reactor Netty |
| `jvm:vertx` | Vert.x | Eclipse Vert.x Web |
| `jvm:netty-raw` | Netty（素） | フレームワークなしの Netty |
| `rust/go/zig/swift` | Native ベースライン | 各言語の最小 HTTP サーバー |

### Linux x86_64

AMD Ryzen 9 9950X3D（16 コア / 32 スレッド）、192 GB RAM、Ubuntu 24.04、Java 21（Azul Zulu）

| Server | Req/sec | p50 | p99 |
|---|---|---|---|
| zig-hello | 1,331K | 39us | 79us |
| rust-hello | 1,291K | 39us | 120us |
| jvm:netty-raw | 1,100K | 45us | 115us |
| jvm:ktor-netty | 832K | 82us | 1.88ms |
| jvm:spring | 821K | 61us | 205us |
| **jvm:ktor-keel-netty** | **667K** | **100us** | **2.88ms** |
| go-hello | 538K | 101us | 1.01ms |
| swift-hello | 527K | 145us | 507us |
| **jvm:ktor-keel-nio** | **487K** | **110us** | **3.23ms** |
| **native:ktor-keel-epoll** | **419K** | **162us** | **3.96ms** |
| jvm:vertx | 346K | 287us | 313us |
| jvm:ktor-cio | 147K | 584us | 4.53ms |
| native:ktor-cio | 7.6K | 12.60ms | 23.71ms |

### macOS Apple Silicon

Apple M1 Max（10 コア: 8P + 2E）、64 GB RAM、macOS 15.4、Java 21（Temurin）

| Server | Req/sec | p50 | p99 |
|---|---|---|---|
| rust-hello | 161K | 569us | 1.19ms |
| jvm:spring | 158K | 558us | 1.91ms |
| go-hello | 153K | 495us | 1.97ms |
| jvm:netty-raw | 146K | 661us | 0.86ms |
| zig-hello | 145K | 667us | 0.85ms |
| jvm:ktor-netty | 138K | 487us | 9.55ms |
| **jvm:ktor-keel-nio** | **129K** | **438us** | **14.78ms** |
| jvm:vertx | 114K | 0.88ms | 1.99ms |
| swift-hello | 106K | 581us | 30.97ms |
| **jvm:ktor-keel-netty** | **99K** | **569us** | **51.37ms** |
| **native:ktor-keel-kqueue** | **88K** | **681us** | **12.04ms** |
| jvm:ktor-cio | 63K | 1.10ms | 15.21ms |
| **native:ktor-keel-nwconnection** | **47K** | **1.77ms** | **11.13ms** |
| native:ktor-cio | 4.8K | 11.08ms | 337ms |

### 備考

- keel エンジンは完全非同期 I/O + HTTP/1.1 keep-alive で動作（v0.2.0）。
- **jvm:ktor-keel-nio**（macOS 129K、Linux 487K）は SelectionKey キャッシュ + EventLoop CoroutineDispatcher により **jvm:ktor-netty** に近い性能を達成。
- **native:ktor-keel-epoll**（419K）は **native:ktor-cio** の 55 倍高速。
- **native:ktor-keel-kqueue**（88K）は **swift-hello**（Hummingbird）の 84%。
- JVM keel エンジンは EventLoop ホットパスの JIT 最適化の恩恵を受ける。Native エンジンは同じ非同期アーキテクチャだが AOT コンパイルのため、コルーチンの suspend/resume や EventLoop ディスパッチの 1 回あたりのコストが高い。ゼロコピーコーデックとメモリプーリング（Phase 6）でこの差を縮める予定。

---

## ライセンス

Apache License 2.0 — Copyright 2026 fukusaka
詳細は [LICENSE](LICENSE) を参照。
