# keel — KMP Native ネットワーク I/O エンジン

[![CI](https://github.com/keel-kt/keel/actions/workflows/ci.yml/badge.svg)](https://github.com/keel-kt/keel/actions/workflows/ci.yml)
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
> **初期の実験的リリース（0.1.0）** — API は不安定であり、予告なく変更される可能性があります。エラーハンドリングは最小限です。本番環境での使用は推奨しません。

---

## ドキュメント

- [Web サイト](https://keel-kt.github.io/keel/) — アーキテクチャガイド・How-to・コーデックドキュメント
- [API リファレンス](https://keel-kt.github.io/keel/api/) — Dokka 生成 KDoc
- [English README](README.md)

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

### 0.1.0（現在 — 実験的）

- 6 種の I/O エンジン: epoll, kqueue, NIO, Netty, NWConnection, Node.js
- suspend API（IoEngine / Channel / ServerChannel / NativeBuf）
- HTTP/1.1・WebSocket コーデック（純粋 kotlinx.io）
- Ktor サーバーエンジンアダプタ

### Next

- 非同期イベントループ + コルーチン統合（ノンブロッキング I/O）
- HTTP keep-alive
- 例外処理改善・安定性の向上
- io_uring エンジン（Linux 5.1+）
- Ktor クライアントエンジンアダプタ

### Future

- TLS
- keel ネイティブクライアント（非 HTTP プロトコル向け）
- iOS / Android ターゲット
- UDP トランスポート
- HTTP/2, gRPC, MQTT
- HTTP/3 (QUIC)

---

## インストール

> **注意:** keel はまだ Maven Central に公開されていません。0.1.0 リリースまでは、ソースからビルドしてローカル Maven リポジトリに公開して使用してください。

```bash
git clone https://github.com/keel-kt/keel.git
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
    implementation("io.github.keel:ktor-engine:0.1.0-SNAPSHOT")
    implementation("io.ktor:ktor-server-core:3.4.1")

    // 低レベル I/O（Ktor なし）
    implementation("io.github.keel:core:0.1.0-SNAPSHOT")

    // コーデック（任意）
    implementation("io.github.keel:codec-http:0.1.0-SNAPSHOT")
    implementation("io.github.keel:codec-websocket:0.1.0-SNAPSHOT")
}
```

Maven Central への公開は 0.1.0 リリース時を予定しています。

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
| zig-hello | 1,295K | 40us | 92us |
| rust-hello | 1,280K | 39us | 123us |
| jvm:netty-raw | 882K | 58us | 173us |
| jvm:ktor-netty | 838K | 82us | 1.80ms |
| jvm:spring | 814K | 62us | 269us |
| go-hello | 562K | 97us | 0.91ms |
| jvm:vertx | 359K | 277us | 312us |
| **native:ktor-keel-epoll** | **158K** | **384us** | **2.24ms** |
| jvm:ktor-cio | 142K | 608us | 4.63ms |
| jvm:ktor-keel-nio | 138K | 114us | 220ms |
| jvm:ktor-keel-netty | 40K | 1.17ms | 25.57ms |
| native:ktor-cio | 8.3K | 11.47ms | 21.72ms |

### macOS Apple Silicon

Apple M1 Max（10 コア: 8P + 2E）、64 GB RAM、macOS 15.4、Java 21（Temurin）

| Server | Req/sec | p50 | p99 |
|---|---|---|---|
| rust-hello | 159K | 574us | 0.95ms |
| jvm:spring | 151K | 566us | 7.75ms |
| go-hello | 147K | 502us | 1.95ms |
| jvm:netty-raw | 141K | 678us | 0.92ms |
| zig-hello | 140K | 680us | 0.91ms |
| jvm:ktor-netty | 133K | 498us | 7.42ms |
| jvm:vertx | 113K | 0.88ms | 1.67ms |
| swift-hello | 103K | 619us | 21.83ms |
| jvm:ktor-cio | 54K | 1.34ms | 17.98ms |
| jvm:ktor-keel-nio | 20K | 1.94ms | 68.44ms |
| **native:ktor-keel-kqueue** | **11K** | **2.05ms** | **50.09ms** |
| native:ktor-cio | 6.2K | 11.01ms | 246ms |
| native:ktor-keel-nwconnection | 0.4 | - | - |
| jvm:ktor-keel-netty | 0.1 | - | - |

### 備考

- keel エンジンは現在 **Connection: close**（keep-alive なし）で動作しており、リクエストごとに TCP ハンドシェイクが発生します。非同期イベントループ + keep-alive で大幅に改善予定。
- Linux で **native:ktor-keel-epoll**（158K）は **native:ktor-cio**（8.3K）の 18 倍高速。
- Linux 32 コアでは **jvm:ktor-keel-nio**（138K）が Connection: close にもかかわらず **jvm:ktor-cio**（142K）と同等の性能。

---

## ライセンス

Apache License 2.0 — Copyright 2026 The keel-kt Authors
詳細は [LICENSE](LICENSE) を参照。
