# keel — KMP Native ネットワーク I/O エンジン

[![CI](https://github.com/fukusaka/keel/actions/workflows/ci.yml/badge.svg)](https://github.com/fukusaka/keel/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/Kotlin%20Multiplatform-✓-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![kotlinx.io](https://img.shields.io/badge/kotlinx.io-0.9.0-orange)](https://github.com/Kotlin/kotlinx-io)
[![Platforms](https://img.shields.io/badge/Platforms-Linux%20%7C%20macOS%20%7C%20JVM%20%7C%20JS-informational)](#ターゲット)
[![Status](https://img.shields.io/badge/Status-Pre--release-yellow)](#ロードマップ)

Linux の epoll、macOS の kqueue、JVM の Netty — プラットフォームごとに異なる I/O プリミティブを、keel が単一の Kotlin Multiplatform インターフェースに統一します。

- **Native ファースト**: epoll・kqueue・io_uring を Kotlin/Native から直接駆動
- **7 種の I/O エンジン**: epoll・kqueue・io_uring・NIO・Netty・NWConnection・Node.js
- **TLS**: 4 バックエンド (OpenSSL, Mbed TLS, AWS-LC, JSSE) + NWConnection/Node.js リスナーレベル TLS
- **Pipeline モード**: ゼロコルーチンの ChannelPipeline による push-mode I/O で最大スループット
- **コーデック層**: kotlinx.io プリミティブのみで構成した HTTP/1.1・WebSocket
- **suspend API**: Channel モードの全 I/O 操作が `suspend fun`

```
  ┌────────────────────────────────────────────┐
  │      アプリケーション / Ktor / :server      │
  └──────────────────┬─────────────────────────┘
                     │
       ┌─────────────┴─────────────┐
       │   コーデック (HTTP, WS)    │
       ├───────────────────────────┤
       │  ChannelPipeline (push)   │
       ├───────────────────────────┤
       │  TLS (OpenSSL│JSSE│...)   │
       └─────────────┬─────────────┘
                     │
  ┌──────┬──────┬──────┬──────┬───────┬──────┬───────┐
  │epoll │kqueue│uring │ NIO  │Netty  │  NW  │Node.js│
  │Linux │macOS │Linux │ JVM  │ JVM   │Apple │  JS   │
  └──────┴──────┴──────┴──────┴───────┴──────┴───────┘
```

> [!WARNING]
> **初期の実験的リリース** — API は不安定であり、予告なく変更される可能性があります。本番環境での使用は推奨しません。

---

## ドキュメント

- [Web サイト](https://keel-kt.pages.dev/) — アーキテクチャガイド・エンジン選択・コーデックドキュメント
- API リファレンス — Dokka 生成 KDoc（準備中）
- [English README](README.md)

---

## モジュール構成

```
keel/
├── keel-core/                 # StreamEngine / Channel / Server / BindConfig / Logger
├── keel-io/                   # IoBuf / SuspendSource / SuspendSink / BufferAllocator
├── keel-native-posix/         # Native エンジン共有 POSIX ソケットユーティリティ
├── keel-engine-epoll/         # linuxX64, linuxArm64 (epoll)
├── keel-engine-kqueue/        # macosArm64, macosX64 (kqueue)
├── keel-engine-io-uring/      # linuxX64, linuxArm64 (io_uring, Linux 5.1+)
├── keel-engine-nio/           # JVM (java.nio.Selector)
├── keel-engine-netty/         # JVM (Netty 4.2 委譲)
├── keel-engine-nodejs/        # JS (Node.js net/tls)
├── keel-engine-nwconnection/  # macosArm64, macosX64 (Network.framework)
├── keel-tls/                  # TlsConfig / TlsInstaller / PemDerConverter / Pkcs8KeyUnwrapper
├── keel-tls-jsse/             # JVM (JSSE / JDK SSLContext)
├── keel-tls-openssl/          # Native (OpenSSL cinterop, -Ptls ビルド)
├── keel-tls-mbedtls/          # Native (Mbed TLS cinterop, -Ptls ビルド)
├── keel-tls-awslc/            # Native (AWS-LC cinterop, -Ptls ビルド)
├── keel-tls-nodejs/           # JS (Node.js tls, -Ptls ビルド)
├── keel-codec-http/           # HTTP/1.1 パーサー／ライター (RFC 7230/7231)
├── keel-codec-websocket/      # WebSocket フレーミング (RFC 6455)
└── keel-ktor-engine/          # Ktor サーバーエンジンアダプタ
```

---

## ターゲット

| ターゲット | エンジン | 状態 | 備考 |
|---|---|---|---|
| `linuxX64`, `linuxArm64` | epoll, io_uring | ✅ | |
| `macosArm64` | kqueue / NWConnection | ✅ | |
| `macosX64` | kqueue / NWConnection | ✅ | Kotlin 2.3 で deprecated (Tier 3) |
| `jvm` | NIO / Netty | ✅ | Android 含む |
| `js (nodejs())` | Node.js net/tls | ✅ | |
| `iosArm64`, `iosSimulatorArm64` | NWConnection | 🔲 予定 | クライアント限定 |
| `mingwX64` | IOCP | 🔲 保留 | |
| `wasmJs`, `wasmWasi` | — | 🔲 保留 | |

---

## ロードマップ

### 現在

- 7 種の I/O エンジン: epoll, kqueue, io_uring, NIO, Netty, NWConnection, Node.js
- Pipeline モード: 全 7 エンジンで ChannelPipeline + push-mode I/O
- TLS: 4 バックエンド (OpenSSL, Mbed TLS, AWS-LC, JSSE) + リスナーレベル TLS
- per-server 設定 (backlog, TLS)
- Write バックプレッシャー (high/low 水位線)
- SlabAllocator (Native) + PooledDirectAllocator (JVM) + リーク検出
- EventLoop dispatch、deferred flush、writev バッチング

### 次期

- `:server` モジュール (push ベース、Ktor 非依存ネイティブサーバー)
- `:client` モジュール (コネクションプーリング付き HTTP クライアント)
- iOS ターゲット

### 将来

- UDP トランスポート
- HTTP/2, gRPC
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
| `native:pipeline-http-*` | **keel Pipeline（Native）** | Pipeline モード HTTP、ネイティブバイナリ |
| `native:ktor-keel-*` | **keel Ktor（Native）** | Ktor + keel I/O エンジン、ネイティブバイナリ |
| `jvm:ktor-keel-*` | **keel（JVM）** | Ktor + keel I/O エンジン、JVM 上で実行 |
| `jvm:pipeline-http-*` | **keel Pipeline（JVM）** | Pipeline モード HTTP、JVM 上で実行 |
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
|---|---:|---:|---:|
| rust-hello | 1,319K | 39us | 110us |
| zig-hello | 1,133K | 42us | 108us |
| jvm:netty-raw | 877K | 59us | 166us |
| **native:pipeline-http-epoll** | **864K** | **58us** | **174us** |
| **native:pipeline-http-io-uring** | **855K** | **59us** | **170us** |
| jvm:ktor-netty | 845K | 85us | 0.97ms |
| jvm:spring | 821K | 61us | 216us |
| **jvm:pipeline-http-nio** | **719K** | **73us** | **1.23ms** |
| **jvm:ktor-keel-netty** | **677K** | **99us** | **2.88ms** |
| **native:ktor-keel-epoll** | **589K** | **99us** | **1.93ms** |
| **jvm:ktor-keel-nio** | **540K** | **106us** | **2.24ms** |
| go-hello | 536K | 102us | 1.02ms |
| swift-hello | 527K | 146us | 473us |
| jvm:vertx | 354K | 275us | 301us |
| **native:pipeline-http-nodejs** | **153K** | **560us** | **1.67ms** |
| jvm:ktor-cio | 146K | 572us | 4.40ms |
| native:ktor-cio | 9K | 10.43ms | 19.94ms |

### macOS Apple Silicon

Apple M1 Max（10 コア: 8P + 2E）、64 GB RAM、macOS 15.4、Java 21（Temurin）

| Server | Req/sec | p50 | p99 |
|---|---:|---:|---:|
| rust-hello | 161K | 583us | 0.88ms |
| **native:pipeline-http-kqueue** | **151K** | **380us** | **4.38ms** |
| jvm:spring | 150K | 598us | 1.91ms |
| **jvm:pipeline-http-nio** | **142K** | **410us** | **11.80ms** |
| go-hello | 141K | 521us | 2.14ms |
| jvm:netty-raw | 139K | 684us | 0.91ms |
| zig-hello | 136K | 690us | 0.93ms |
| jvm:ktor-netty | 132K | 499us | 6.18ms |
| **jvm:ktor-keel-nio** | **128K** | **410us** | **11.80ms** |
| jvm:vertx | 112K | 0.86ms | 1.75ms |
| **native:ktor-keel-kqueue** | **108K** | **588us** | **9.47ms** |
| swift-hello | 98K | 651us | 23.56ms |
| **jvm:ktor-keel-netty** | **94K** | **487us** | **41.21ms** |
| **native:pipeline-http-nodejs** | **66K** | **1.43ms** | **2.32ms** |
| jvm:ktor-cio | 64K | 1.02ms | 18.20ms |
| **native:pipeline-http-nwconnection** | **47K** | **1.87ms** | **13.82ms** |
| native:ktor-cio | 7K | 10.40ms | 130.66ms |

### HTTPS (Pipeline API)

| Server | TLS バックエンド | Req/sec | p50 |
|---|---|---:|---:|
| **native:pipeline-http-io-uring** (Linux) | OpenSSL | **543K** | **94us** |
| **native:pipeline-http-epoll** (Linux) | OpenSSL | **529K** | **99us** |
| **native:pipeline-http-kqueue** (macOS) | OpenSSL | **139K** | **458us** |
| **jvm:pipeline-http-nio** (macOS) | JSSE | **133K** | **481us** |

### 備考

- keel エンジンは完全非同期 I/O + HTTP/1.1 keep-alive で動作。
- **Pipeline モード**（ゼロコルーチン push I/O）が最速 — **pipeline-http-epoll**（864K）は Linux で Rust の 66% に到達。
- **Ktor Channel モード**（suspend ベース）はコルーチンオーバーヘッドあり — **ktor-keel-epoll**（589K）でも **ktor-cio** の 65 倍高速。
- /large（100KB）では kqueue が Rust の 95% に到達（57K vs 60K）。
- **jvm:ktor-keel-nio**（macOS 128K、Linux 540K）は **jvm:ktor-netty** に近い性能を達成。

---

## ライセンス

Apache License 2.0 — Copyright 2026 fukusaka
詳細は [LICENSE](LICENSE) を参照。
