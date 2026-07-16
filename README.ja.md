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

> **計測時期**: 以下の表は v0.4.0 以前のビルドで 2026 年 4 月に最終更新したもの。
> v0.4.0 での full 再計測は後続の patch リリースで予定。

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
| rust-bench | 1,319K | 39us | 110us |
| zig-bench | 1,133K | 42us | 108us |
| jvm:netty-raw | 877K | 59us | 166us |
| **native:pipeline-http-epoll** | **870K** | **58us** | **174us** |
| **native:pipeline-http-io-uring** | **860K** | **59us** | **170us** |
| jvm:ktor-netty | 845K | 85us | 0.97ms |
| jvm:spring | 821K | 61us | 216us |
| **jvm:pipeline-http-nio** | **715K** | **73us** | **1.23ms** |
| **jvm:ktor-keel-netty** | **677K** | **99us** | **2.88ms** |
| **native:ktor-keel-epoll** | **589K** | **99us** | **1.93ms** |
| **jvm:ktor-keel-nio** | **540K** | **106us** | **2.24ms** |
| go-bench | 536K | 102us | 1.02ms |
| swift-bench | 527K | 146us | 473us |
| jvm:vertx | 354K | 275us | 301us |
| **native:pipeline-http-nodejs** | **151K** | **560us** | **1.67ms** |
| jvm:ktor-cio | 146K | 572us | 4.40ms |
| native:ktor-cio | 9K | 10.43ms | 19.94ms |

### macOS Apple Silicon

Apple M1 Max（10 コア: 8P + 2E）、64 GB RAM、macOS 15.4、Java 21（Temurin）

| Server | Req/sec | p50 | p99 |
|---|---:|---:|---:|
| rust-bench | 161K | 583us | 0.88ms |
| **native:pipeline-http-kqueue** | **154K** | **380us** | **4.38ms** |
| jvm:spring | 150K | 598us | 1.91ms |
| **jvm:pipeline-http-nio** | **146K** | **410us** | **11.80ms** |
| go-bench | 141K | 521us | 2.14ms |
| jvm:netty-raw | 139K | 684us | 0.91ms |
| zig-bench | 136K | 690us | 0.93ms |
| jvm:ktor-netty | 132K | 499us | 6.18ms |
| **jvm:ktor-keel-nio** | **128K** | **410us** | **11.80ms** |
| jvm:vertx | 112K | 0.86ms | 1.75ms |
| **native:ktor-keel-kqueue** | **108K** | **588us** | **9.47ms** |
| swift-bench | 98K | 651us | 23.56ms |
| **jvm:ktor-keel-netty** | **94K** | **487us** | **41.21ms** |
| **native:pipeline-http-nodejs** | **71K** | **1.43ms** | **2.32ms** |
| jvm:ktor-cio | 64K | 1.02ms | 18.20ms |
| **native:pipeline-http-nwconnection** | **47K** | **1.87ms** | **13.82ms** |
| native:ktor-cio | 7K | 10.40ms | 130.66ms |

### HTTPS（Pipeline API、`/hello`）

| Server | TLS バックエンド | Req/sec | p50 |
|---|---|---:|---:|
| **native:pipeline-http-io-uring** (Linux) | OpenSSL | **508K** | **94us** |
| **native:pipeline-http-epoll** (Linux) | OpenSSL | **490K** | **99us** |
| **native:pipeline-http-kqueue** (macOS) | OpenSSL | **133K** | **458us** |
| **jvm:pipeline-http-netty** (macOS) | JSSE/SslHandler | **130K** | **481us** |

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

| Server | `/large` Req/sec | 備考 |
|---|---:|---|
| **jvm:ktor-keel-nio** | **228K** | |
| **jvm:ktor-keel-netty** | **207K** | ゼロコピー direct-write パス（PR #246） |
| jvm:netty-raw（参考） | 287K | フレームワークなし Netty |

### 備考

- keel エンジンは完全非同期 I/O + HTTP/1.1 keep-alive で動作。
- **Pipeline モード**（ゼロコルーチン push I/O）が最速 — **pipeline-http-epoll**（870K）は Linux で Rust の 66% に到達。
- **Ktor Coroutine モード**（suspend ベース）はコルーチンオーバーヘッドあり — **ktor-keel-epoll**（589K）でも **ktor-cio** の 65 倍高速。
- `/large`（100KB）の Ktor Coroutine モードでは **jvm:ktor-keel-nio** が **228K req/s** — raw Netty の 80% を達成。
- **jvm:ktor-keel-nio**（macOS 128K、Linux 540K）は `/hello` でも **jvm:ktor-netty** に近い性能を達成。

---

## ライセンス

Apache License 2.0 — Copyright 2026 fukusaka
詳細は [LICENSE](LICENSE) を参照。
