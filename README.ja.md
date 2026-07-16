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
- **スタンドアロン HTTP サーバー**: `keel-server-http` — routing・middleware・static files・trailers・builder DSL を備えた Ktor 非依存の HTTP/1.1 サーバー
- **WebSocket サーバー**: upgrade ハンドシェイク + permessage-deflate (RFC 7692)、pooled zero-copy frame payload
- **TLS**: 4 バックエンド (OpenSSL, Mbed TLS, AWS-LC, JSSE) + NWConnection/Node.js リスナーレベル TLS
- **圧縮**: gzip/deflate SPI + リクエスト/レスポンス両方向の HTTP content-encoding ネゴシエーション
- **Pipeline モード**: ゼロコルーチンの Pipeline による push-mode I/O で最大スループット
- **コーデック層**: pooled `IoBuf` を受け渡す pipeline handler として構成した HTTP/1.1・WebSocket（zero-copy I/O 境界）
- **suspend API**: Coroutine モードの全 I/O 操作が `suspend fun`

```
  ┌────────────────────────────────────────────┐
  │ アプリケーション / keel-server-http / Ktor │
  └──────────────────┬─────────────────────────┘
                     │
       ┌─────────────┴─────────────┐
       │   コーデック (HTTP, WS)    │
       ├───────────────────────────┤
       │  Pipeline (push)   │
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
├── keel-io/                     # IoBuf / chunk ベース pooled BufferAllocator / kotlinx-io ブリッジ
├── keel-core/                   # IoEngine / Channel / StreamServer / Pipeline / DNS resolver
├── keel-native-posix/           # Native エンジン共有 POSIX ソケット層
├── keel-engine-epoll/           # linuxX64, linuxArm64 (epoll)
├── keel-engine-kqueue/          # macosArm64, macosX64 (kqueue)
├── keel-engine-io-uring/        # linuxX64, linuxArm64 (io_uring, Linux 5.6+)
├── keel-engine-nio/             # JVM (java.nio.Selector)
├── keel-engine-netty/           # JVM (Netty 4.2 委譲)
├── keel-engine-nodejs/          # JS (Node.js net/tls)
├── keel-engine-nwconnection/    # macosArm64, macosX64 (Network.framework)
├── keel-codec-http/             # HTTP/1.1 コーデック: pipeline handler・streaming・圧縮ネゴシエーション
├── keel-codec-websocket/        # WebSocket フレーミング (RFC 6455) + pipeline handler
├── keel-compression/            # 圧縮 SPI (gzip / deflate)
├── keel-compression-zlib/       # 圧縮 SPI の zlib バックエンド
├── keel-tls/                    # TlsCodec protect/unprotect API・TlsConfig・PEM/DER ヘルパー
├── keel-tls-jsse/               # JVM (JSSE / JDK SSLContext)
├── keel-tls-openssl/            # Native (OpenSSL cinterop, -Ptls ビルド)
├── keel-tls-mbedtls/            # Native (Mbed TLS cinterop, -Ptls ビルド)
├── keel-tls-awslc/              # Native (AWS-LC cinterop, -Ptls ビルド)
├── keel-server/                 # サーバー bootstrap・TLS 配線・サーバー DSL 基盤
├── keel-server-http/            # スタンドアロン HTTP/1.1 サーバー: routing・middleware・static files・DSL
├── keel-server-websocket/       # WebSocket サーバー: upgrade・permessage-deflate
├── keel-server-ktor/            # Ktor サーバーエンジンアダプタ
├── keel-server-ktor-base/       # Ktor アダプタ共有内部実装
├── keel-server-ktor-cio/        # keel ソケット上で ktor-http-cio を駆動する Ktor アダプタ
├── keel-observability-opentelemetry/  # allocator stats の OpenTelemetry バインディング (JVM)
└── keel-testing-{internal,engine,server-http}/  # テストフィクスチャ
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

### 現在 (v0.4.0)

- 7 種の I/O エンジン: epoll, kqueue, io_uring, NIO, Netty, NWConnection, Node.js
- スタンドアロン HTTP/1.1 サーバー (`keel-server-http`): routing・middleware・static files・HTTP trailers・pluggable な pipeline 拡張点を備えた builder DSL
- WebSocket サーバー: upgrade ハンドシェイク、permessage-deflate、pooled zero-copy payload
- TLS: 4 バックエンド (OpenSSL, Mbed TLS, AWS-LC, JSSE) + リスナーレベル TLS
- 圧縮: gzip/deflate SPI + zlib バックエンド、HTTP content-encoding ネゴシエーション
- io_uring: multishot accept・provided buffer ring・registered buffers・zero-copy send
- chunk ベース pooled allocator (size class + chunk arena + sharding)、lifecycle listener・リーク検出・OpenTelemetry stats バインディング
- Pipeline モード (push I/O)、Write バックプレッシャー (high/low 水位線)

### 次期

- HTTP クライアントエンジン (Ktor `HttpClientEngine` アダプタ + client codec pair)
- iOS ターゲット
- Happy Eyeballs (RFC 8305) 接続確立

### 将来

- HTTP/2、UDP トランスポート
- HTTP/3 (QUIC)
- gRPC

---

## インストール

> **注意:** keel はまだ Maven リポジトリに公開されていません（公開は v1.0 で予定）。
> それまでは Gradle composite build でソースから利用してください。

```bash
git clone https://github.com/fukusaka/keel.git
cd keel && git switch --detach v0.4.0
```

```kotlin
// settings.gradle.kts（利用側プロジェクト）
includeBuild("path/to/keel")
```

```kotlin
// build.gradle.kts — Gradle が included build のプロジェクトに置換する
dependencies {
    // Ktor + keel サーバーエンジン
    implementation("io.github.fukusaka.keel:keel-server-ktor")
    implementation("io.ktor:ktor-server-core:3.4.1")

    // スタンドアロン HTTP サーバー（Ktor なし）
    implementation("io.github.fukusaka.keel:keel-server-http")

    // 低レベル I/O のみ
    implementation("io.github.fukusaka.keel:keel-core")
}
```

---

## ベンチマーク

> **計測時期**: 以下の表は 2026 年 7 月に v0.4.1 で full 再計測したもの
>（wrk 4t/100c/10s、3-run median、shuffle 有効）。

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

| Server | macOS M1 | Linux Ryzen 9 |
|---|---:|---:|
| **native:pipeline-http-io-uring** | — | **393K** |
| **jvm:pipeline-http-nio** | **62K** | **365K** |
| **native:pipeline-http-epoll** | — | **357K** |
| **jvm:pipeline-http-netty** | **61K** | **289K** |
| **native:pipeline-http-kqueue** | **63K** | — |
| **native:pipeline-http-nwconnection** | **28K** | — |
| **native:pipeline-http-nodejs** | **7K** | **10K** |

### Linux x86_64

AMD Ryzen 9 9950X3D（16 コア / 32 スレッド）、192 GB RAM、Ubuntu 24.04、Java 21（Azul Zulu）

| Server | Req/sec | p50 | p99 |
|---|---:|---:|---:|
| zig-bench | 1,276K | 41us | 82us |
| rust-bench | 1,223K | 41us | 116us |
| **jvm:pipeline-http-nio** | **926K** | **55us** | **152us** |
| **native:pipeline-http-epoll** | **890K** | **57us** | **164us** |
| **native:pipeline-http-io-uring** | **875K** | **56us** | **179us** |
| **jvm:ktor-keel-nio** | **827K** | **60us** | **230us** |
| jvm:netty-raw | 827K | 61us | 192us |
| jvm:ktor-netty | 816K | 90us | 758us |
| jvm:spring | 796K | 63us | 241us |
| **jvm:ktor-keel-netty** | **787K** | **64us** | **384us** |
| go-bench | 538K | 103us | 1.02ms |
| **native:ktor-keel-epoll** | **483K** | **113us** | **2.68ms** |
| jvm:vertx | 339K | 289us | 311us |
| **native:pipeline-http-nodejs** | **201K** | **484us** | **0.88ms** |
| jvm:ktor-cio | 133K | 545us | 4.40ms |
| native:ktor-cio | 9K | 10.47ms | 20.18ms |

### macOS Apple Silicon

Apple M1 Max（10 コア: 8P + 2E）、64 GB RAM、macOS 15.4、Java 21（Temurin）

| Server | Req/sec | p50 | p99 |
|---|---:|---:|---:|
| jvm:spring | 159K | 414us | 3.96ms |
| **native:pipeline-http-kqueue** | **158K** | **594us** | **742us** |
| **jvm:pipeline-http-nio** | **157K** | **602us** | **721us** |
| rust-bench | 156K | 573us | 0.86ms |
| **jvm:ktor-keel-nio** | **156K** | **573us** | **1.31ms** |
| **jvm:ktor-keel-netty** | **151K** | **541us** | **12.65ms** |
| jvm:netty-raw | 148K | 644us | 776us |
| zig-bench | 148K | 651us | 752us |
| go-bench | 146K | 489us | 1.83ms |
| jvm:ktor-netty | 142K | 480us | 3.64ms |
| swift-bench | 129K | 505us | 12.88ms |
| jvm:vertx | 112K | 0.89ms | 1.31ms |
| **native:ktor-keel-kqueue** | **101K** | **569us** | **5.88ms** |
| **native:pipeline-http-nodejs** | **95K** | **1.02ms** | **2.06ms** |
| jvm:ktor-cio | 58K | 1.28ms | 28.72ms |
| **native:pipeline-http-nwconnection** | **55K** | **1.83ms** | **2.04ms** |
| native:ktor-cio | 3K | 10.13ms | 410.35ms |

### HTTPS（Pipeline API、`/hello`）

| Server | TLS Backend | Req/sec | p50 |
|---|---|---:|---:|
| **native:pipeline-http-io-uring** (Linux) | OpenSSL | **600K** | **69us** |
| **native:pipeline-http-epoll** (Linux) | OpenSSL | **589K** | **70us** |
| **native:pipeline-http-kqueue** (macOS) | OpenSSL | **154K** | **576us** |
| **jvm:pipeline-http-netty** (macOS) | JSSE/SslHandler | **144K** | **630us** |

### `/large` レスポンス（100 KB）

Pipeline API、wrk 4t/100c/10s、3 回計測中央値:

| Server | macOS M1 | Linux Ryzen 9 |
|---|---:|---:|
| **native:pipeline-http-epoll** | — | **121K** |
| **native:pipeline-http-io-uring** | — | **115K** |
| **jvm:pipeline-http-nio** | **56K** | **117K** |
| **jvm:pipeline-http-netty** | **54K** | **109K** |
| **native:pipeline-http-kqueue** | **44K** | — |
| **native:pipeline-http-nwconnection** | **25K** | — |
| **native:pipeline-http-nodejs** | **7K** | — |

Ktor Coroutine モード（`keel-server-ktor`）、Linux Ryzen 9:

| Server | `/large` Req/sec | Notes |
|---|---:|---|
| **jvm:ktor-keel-netty** | **239K** | |
| **jvm:ktor-keel-nio** | **113K** | 4 月値（228K）からの regression、調査中 |
| jvm:netty-raw (reference) | 273K | raw Netty, no framework |

### 備考

- keel の全エンジンは完全非同期 I/O + HTTP/1.1 keep-alive で動作。
- **Pipeline モード**（ゼロコルーチン push I/O）が最速 — **jvm:pipeline-http-nio**（926K）と **pipeline-http-epoll**（890K）は Linux の最速ネイティブ基準の約 73-76% に到達。
- **Ktor Coroutine モード**（suspend ベース）はコルーチンのオーバーヘッドが乗る — **jvm:ktor-keel-nio**（827K）は Linux で Pipeline モードの 11% 以内まで接近。
- Ktor 経由の `/large`（100KB）では **jvm:ktor-keel-netty** が **239K req/s** — raw Netty の 13% 以内。
- macOS はループバック律速で密集（155-159K）: **pipeline-http-kqueue**・**pipeline-http-nio**・**ktor-keel-nio** はいずれも最速サーバーの 2% 以内。

---

## ライセンス

Apache License 2.0 — Copyright 2026 fukusaka
詳細は [LICENSE](LICENSE) を参照。
