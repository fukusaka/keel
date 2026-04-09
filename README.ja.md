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
- SlabAllocator (Native) + PooledDirectAllocator (JVM)
- EventLoop dispatch、deferred flush、writev バッチング

### 次期

- `:server` モジュール (push ベース、Ktor 非依存ネイティブサーバー)
- 適応バッファ + バックプレッシャー
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

`GET /hello` (13 bytes) — [wrk](https://github.com/wg/wrk) 4 スレッド、100 接続、10 秒、ループバック。

| ホスト | CPU | RAM | OS |
|--------|-----|-----|----|
| macOS | Apple M1 Max (10 cores) | 64 GB | macOS 15.4, Java 21 Temurin |
| Linux | AMD Ryzen 9 9950X3D (16 cores) | 192 GB | Ubuntu 24.04, Java 21 Azul Zulu |

### ハイライト

- **864K req/s** — pipeline-http-epoll (Linux Ryzen 9)、ktor-cio の 6 倍
- **543K req/s** — pipeline-http-io-uring HTTPS (Linux, OpenSSL)
- **151K req/s** — pipeline-http-kqueue (macOS M1)、Rust actix-web の 94%
- **138K req/s** — pipeline-http-kqueue HTTPS (macOS, OpenSSL、オーバーヘッドわずか 9%)

### Pipeline API (req/s)

| エンジン | macOS | Linux |
|---|---:|---:|
| epoll | — | 864K |
| io_uring | — | 855K |
| kqueue | 151K | — |
| NIO (JVM) | 142K | 719K |
| Netty (JVM) | 132K | — |
| Node.js (JS) | 66K | 153K |
| NWConnection | 47K | — |

---

## ライセンス

Apache License 2.0 — Copyright 2026 fukusaka
詳細は [LICENSE](LICENSE) を参照。
