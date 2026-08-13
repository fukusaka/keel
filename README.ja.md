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
├── keel-native-readiness/       # epoll + kqueue が共有する readiness ループ実装
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

| プレフィックス | カテゴリ | 説明 |
|--------|----------|-------------|
| `native:server-http-*` | **keel server（Native）** | スタンドアロン `keel-server-http`（`keelHttpServer { }` DSL）、ネイティブバイナリ |
| `native:ktor-keel-*` | **keel Ktor（Native）** | Ktor + keel I/O エンジン、ネイティブバイナリ |
| `jvm:ktor-keel-*` | **keel（JVM）** | Ktor + keel I/O エンジン、JVM 上で実行 |
| `jvm:server-http-*` / `js:server-http-*` | **keel server（JVM / Node.js）** | スタンドアロン `keel-server-http`、JVM / Node.js 上で実行 |
| `native:ktor-cio` | Ktor CIO（Native） | Ktor 標準 CIO エンジン、ネイティブバイナリ |
| `jvm:ktor-cio` | Ktor CIO（JVM） | Ktor 標準 CIO エンジン、JVM 上で実行 |
| `jvm:ktor-netty` | Ktor + Netty | Ktor の Netty エンジンアダプタ |
| `jvm:spring` | Spring WebFlux | Spring Boot + Reactor Netty |
| `jvm:vertx` | Vert.x | Eclipse Vert.x Web |
| `jvm:netty-raw` | Netty（raw） | フレームワークなしの素の Netty |
| `rust/go/zig/swift` | Native ベースライン | 各言語の最小 HTTP サーバー |

### Linux x86_64

AMD Ryzen 9 9950X3D（16 コア / 32 スレッド）、192 GB RAM、Ubuntu 24.04、Java 21（Azul Zulu）

| Server | Req/sec | p50 | p99 |
|---|---:|---:|---:|
| zig-bench | 1,276K | 41us | 82us |
| rust-bench | 1,223K | 41us | 116us |
| **jvm:server-http-nio** | **921K** | **55us** | **152us** |
| **native:server-http-io-uring** | **861K** | **56us** | **268us** |
| **native:server-http-epoll** | **854K** | **58us** | **260us** |
| **jvm:ktor-keel-nio** | **827K** | **60us** | **230us** |
| jvm:netty-raw | 827K | 61us | 192us |
| jvm:ktor-netty | 816K | 90us | 758us |
| jvm:spring | 796K | 63us | 241us |
| **jvm:ktor-keel-netty** | **787K** | **64us** | **384us** |
| go-bench | 538K | 103us | 1.02ms |
| **native:ktor-keel-epoll** | **483K** | **113us** | **2.68ms** |
| jvm:vertx | 339K | 289us | 311us |
| **js:server-http-nodejs** | **184K** | **529us** | **0.86ms** |
| jvm:ktor-cio | 133K | 545us | 4.40ms |
| native:ktor-cio | 9K | 10.47ms | 20.18ms |

### macOS Apple Silicon

Apple M1 Max（10 コア: 8P + 2E）、64 GB RAM、macOS 15.4、Java 21（Temurin）

| Server | Req/sec | p50 | p99 |
|---|---:|---:|---:|
| jvm:spring | 159K | 414us | 3.96ms |
| **native:server-http-kqueue** | **159K** | **593us** | **761us** |
| **jvm:server-http-nio** | **157K** | **605us** | **723us** |
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
| **js:server-http-nodejs** | **87K** | **1.13ms** | **2.27ms** |
| jvm:ktor-cio | 58K | 1.28ms | 28.72ms |
| **native:server-http-nwconnection** | **54K** | **1.82ms** | **2.13ms** |
| native:ktor-cio | 3K | 10.13ms | 410.35ms |

### HTTPS（`keel-server-http`、`/hello`）

Native エンジンは keel の `TlsCodec` pipeline で駆動し、各列は個別にリンクした TLS backend
（1 バイナリ 1 backend、`-Ptls-backend=<name>` で選択）。JVM エンジンは JSSE を使用。

| Server | OpenSSL | AWS-LC | Mbed TLS |
|---|---:|---:|---:|
| **native:server-http-epoll** (Linux) | **578K** | **685K** | **666K** |
| **native:server-http-io-uring** (Linux) | **597K** | **681K** | **661K** |
| **native:server-http-kqueue** (macOS) | **154K** | **155K** | **153K** |

| Server | JSSE |
|---|---:|
| **jvm:server-http-nio** (Linux) | **729K** |
| **jvm:server-http-nio** (macOS) | **151K** |

### `/large` レスポンス（100 KB）

`keel-server-http`、wrk 4t/100c/10s、3 回計測中央値:

| Server | macOS M1 | Linux Ryzen 9 |
|---|---:|---:|
| **native:server-http-io-uring** | — | **381K** |
| **jvm:server-http-nio** | **62K** | **368K** |
| **native:server-http-epoll** | — | **311K** |
| **jvm:server-http-netty** | **61K** | **287K** |
| **native:server-http-kqueue** | **63K** | — |
| **native:server-http-nwconnection** | **28K** | — |
| **js:server-http-nodejs** | **7K** | **10K** |

Ktor Coroutine モード（`keel-server-ktor`）、Linux Ryzen 9:

| Server | `/large` Req/sec | Notes |
|---|---:|---|
| **jvm:ktor-keel-nio** | **292K** | |
| **jvm:ktor-keel-netty** | **239K** | |
| jvm:netty-raw (reference) | 273K | raw Netty, no framework |

### 備考

- keel の全エンジンは完全非同期 I/O + HTTP/1.1 keep-alive で動作。
- **server-http 行**は、アプリケーションが実際に使う構成そのまま（`keelHttpServer { }` DSL、ゼロコルーチン push pipeline 上）のスタンドアロン `keel-server-http` の計測 — **jvm:server-http-nio**（921K）は Linux の最速ネイティブ基準の約 72-75% に到達し、DSL 層のコストは手組み pipeline 比で 1% 未満。
- **Ktor Coroutine モード**（suspend ベース）はコルーチンのオーバーヘッドが乗る — **jvm:ktor-keel-nio**（827K）は Linux でスタンドアロンサーバーの 11% 以内。
- HTTPS では **Linux の最速 native TLS backend は AWS-LC**（OpenSSL 比 ~15% 上）。JVM/JSSE 経路（**729K**）は Linux で全 native backend を上回る。
- Ktor 経由の `/large`（100KB）では **jvm:ktor-keel-nio** が **292K req/s** — adapter の aggregated body zero-copy 化により raw Netty（273K）を上回る。
- macOS はループバック律速で密集（149-159K）: **server-http-kqueue**・**server-http-nio**・**ktor-keel-nio** はいずれも最速サーバーの 2% 以内。

---

## ライセンス

Apache License 2.0 — Copyright 2026 fukusaka
詳細は [LICENSE](LICENSE) を参照。
