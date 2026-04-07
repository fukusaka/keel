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
> **初期の実験的リリース（0.3.0）** — API は不安定であり、予告なく変更される可能性があります。本番環境での使用は推奨しません。

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
├── keel-io/               # NativeBuf / SuspendSource / SuspendSink / BufferAllocator
├── logging/               # Logger / LoggerFactory（外部依存なし）
├── keel-engine-epoll/          # linuxX64, linuxArm64
├── keel-engine-kqueue/         # macosArm64, macosX64
├── keel-engine-nio/            # JVM（java.nio.Selector）
├── keel-engine-netty/          # JVM（Netty 4.x 委譲）
├── keel-engine-nodejs/         # JS nodejs()
├── keel-engine-nwconnection/   # macosArm64, macosX64（Network.framework）
├── keel-codec-http/            # HTTP/1.1 パーサー／ライター（RFC 7230/7231）
├── keel-codec-websocket/       # WebSocket フレーミング（RFC 6455）
└── keel-ktor-engine/           # Ktor サーバーエンジンアダプタ
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

### 0.3.0（現在 — 実験的）

- 6 種の I/O エンジン: epoll, kqueue, NIO, Netty, NWConnection, Node.js
- SlabAllocator (Native) + PooledDirectAllocator (JVM) による EventLoop 単位のバッファプーリング
- Native エンジン向け EventLoop dispatch
- Deferred flush + writev バッチング
- ログモジュール（外部依存なしの Logger インターフェース）
- 例外階層（KeelEofException, HttpParseException）
- detekt 静的解析 + TrackingAllocator リーク検出

### Next（Phase 7）

- TLS（Mbed TLS）
- 適応バッファ + Write 水位線
- iOS ターゲット

### Future（Phase 8+）

- io_uring エンジン（Linux 5.1+）
- Push Channel + keel ネイティブサーバー（:server、Ktor 非依存）
- UDP トランスポート
- HTTP/2, gRPC, MQTT
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
    implementation("io.github.fukusaka.keel:keel-ktor-engine:0.3.0")
    implementation("io.ktor:ktor-server-core:3.4.1")

    // 低レベル I/O（Ktor なし）
    implementation("io.github.fukusaka.keel:keel-core:0.3.0")

    // コーデック（任意）
    implementation("io.github.fukusaka.keel:keel-codec-http:0.3.0")
    implementation("io.github.fukusaka.keel:keel-codec-websocket:0.3.0")
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
./gradlew :keel-engine-nio:jvmTest :keel-engine-netty:jvmTest
./gradlew :keel-codec-http:jvmTest :keel-codec-websocket:jvmTest

# macOS Native テスト
./gradlew :keel-engine-kqueue:macosArm64Test
./gradlew :keel-codec-http:macosArm64Test :keel-codec-websocket:macosArm64Test

# Linux テスト（Docker）
docker run --rm --platform linux/amd64 \
  -v $(pwd):/work -w /work gradle:8-jdk21 \
  ./gradlew :keel-engine-epoll:linuxX64Test

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
| rust-hello | 1,298K | 39us | 110us |
| zig-hello | 1,133K | 42us | 108us |
| jvm:netty-raw | 877K | 59us | 166us |
| jvm:ktor-netty | 845K | 85us | 0.97ms |
| jvm:spring | 821K | 61us | 216us |
| **jvm:ktor-keel-netty** | **677K** | **99us** | **2.88ms** |
| **native:ktor-keel-epoll** | **589K** | **99us** | **1.93ms** |
| **jvm:ktor-keel-nio** | **540K** | **106us** | **2.24ms** |
| go-hello | 536K | 102us | 1.02ms |
| swift-hello | 527K | 146us | 473us |
| jvm:vertx | 354K | 275us | 301us |
| jvm:ktor-cio | 146K | 572us | 4.40ms |
| native:ktor-cio | 9K | 10.43ms | 19.94ms |

### macOS Apple Silicon

Apple M1 Max（10 コア: 8P + 2E）、64 GB RAM、macOS 15.4、Java 21（Temurin）

| Server | Req/sec | p50 | p99 |
|---|---|---|---|
| rust-hello | 154K | 583us | 0.88ms |
| jvm:spring | 150K | 598us | 1.91ms |
| go-hello | 141K | 521us | 2.14ms |
| jvm:netty-raw | 139K | 684us | 0.91ms |
| zig-hello | 136K | 690us | 0.93ms |
| jvm:ktor-netty | 132K | 499us | 6.18ms |
| **jvm:ktor-keel-nio** | **128K** | **410us** | **11.80ms** |
| jvm:vertx | 112K | 0.86ms | 1.75ms |
| **native:ktor-keel-kqueue** | **108K** | **588us** | **9.47ms** |
| swift-hello | 98K | 651us | 23.56ms |
| **jvm:ktor-keel-netty** | **94K** | **487us** | **41.21ms** |
| jvm:ktor-cio | 64K | 1.02ms | 18.20ms |
| **native:ktor-keel-nwconnection** | **45K** | **1.87ms** | **13.82ms** |
| native:ktor-cio | 7K | 10.40ms | 130.66ms |

### 備考

- keel エンジンは完全非同期 I/O + HTTP/1.1 keep-alive で動作。
- **native:ktor-keel-epoll**（589K）は **native:ktor-cio** の 65 倍高速、Rust の 45% に到達。/large（100KB）では kqueue が Rust の 95% に到達（57K vs 60K）。
- **jvm:ktor-keel-nio**（macOS 128K、Linux 540K）は **jvm:ktor-netty** に近い性能を達成。
- Phase 6b の改善: EventLoop dispatch（/hello +27-39%）、deferred flush + writev バッチング（/large +210-578%）、SlabAllocator/PooledDirectAllocator による EventLoop 単位のバッファプーリング。

---

## ライセンス

Apache License 2.0 — Copyright 2026 fukusaka
詳細は [LICENSE](LICENSE) を参照。
