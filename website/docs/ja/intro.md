---
sidebar_position: 1
---

# はじめに

keel は Kotlin Multiplatform ネットワーク I/O エンジンライブラリです。Linux の epoll、macOS の kqueue、最新 Linux カーネルの io_uring といったプラットフォームネイティブな I/O システムコールを、JVM・Native・JS ターゲット間で共通の `StreamEngine` インターフェースの裏に統一します。

## keel とは何か・何でないか

| 質問 | 答え |
|---|---|
| keel とは何か？ | Linux（epoll、io_uring）、macOS（kqueue、NWConnection）、JVM（NIO、Netty）、JS（Node.js）にまたがるソケット I/O のための統一 `StreamEngine` インターフェースを提供する KMP ライブラリ。 |
| keel は Kotlin/Native で動作するか？ | Yes。keel は Kotlin/Native を最初のターゲットとして設計されており、epoll・kqueue・io_uring・NWConnection はすべて JVM 依存なしのネイティブエンジンです。 |
| keel は Ktor のバックエンドとして使えるか？ | Yes。`keel-ktor-engine` が keel を Ktor のサーバーエンジンとして接続します。Ktor でルートを書き、keel がバイトを運びます。 |
| keel は Web フレームワーク？ | No。keel はトランスポート層 — ソケット上のバイト転送を担います。ルーティング・リクエスト解析・HTTP セマンティクスには [Ktor](https://ktor.io) を上位に載せます。 |
| keel は Netty の代替？ | No。JVM では `keel-engine-netty` が I/O バックエンドとして Netty を使用します。Kotlin/Native では Netty 自体が動作しないため、keel が OS システムコールを直接呼び出します。keel と Netty は異なる抽象レイヤーに位置します。 |

## アーキテクチャ

keel の中心的な価値は **7 つのエンジンを単一の `StreamEngine` インターフェースで統一すること** です。コーデックと TLS はインターフェースの上に位置する任意層として、全エンジン共通で利用できます:

```
  Your App / Ktor
        │
  ┌─────┴─────────────────────────────────────────────┐
  │  Codec (HTTP, WebSocket)                          │  任意
  │  TLS (JSSE · OpenSSL · Mbed TLS · AWS-LC · ...)  │  任意
  ├───────────────────────────────────────────────────┤
  │               StreamEngine                        │  ← keel の統一インターフェース
  ├──────┬──────┬───────┬────────┬───────┬──────┬─────┤
  │ NIO  │Netty │ epoll │io_uring│kqueue │  NW  │Node │
  │    JVM     │      Linux      │    macOS     │  JS │
  └──────┴──────┴───────┴────────┴───────┴──────┴─────┘
```

## クイックスタート

keel はまだ Maven Central に公開されていません。まずソースからビルドしてローカル Maven に公開します:

```bash
git clone https://github.com/fukusaka/keel.git
cd keel && ./gradlew publishToMavenLocal
```

### Coroutine モード（Ktor 使用）

```kotlin
// build.gradle.kts
repositories { mavenLocal() }

dependencies {
    implementation("io.github.fukusaka.keel:keel-ktor-engine:0.3.0")
    implementation("io.ktor:ktor-server-core:3.4.1")
}
```

```kotlin
import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Keel, port = 8080) {
        routing {
            get("/hello") {
                call.respondText("Hello from keel!")
            }
        }
    }.start(wait = true)
}
```

どのエンジンが動くかは、コンパイル時にクラスパスにある `keel-engine-*` 依存関係によって決まります。`keel-ktor-engine` は各ターゲットの依存関係セットにあるエンジンモジュールを使用します。各ターゲットの設定方法については[エンジン選択ガイド](./architecture/engine-guide.md)を参照してください。

### Pipeline モード（Ktor なし）

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.fukusaka.keel:keel-engine-epoll:0.3.0")  // または keel-engine-kqueue、keel-engine-nio 等
    implementation("io.github.fukusaka.keel:keel-codec-http:0.3.0")
}
```

```kotlin
class HelloHandler : TypedChannelInboundHandler<HttpRequest>() {
    override fun onMessage(ctx: ChannelHandlerContext, msg: HttpRequest) {
        ctx.write(HttpResponse(HttpStatus.OK, body = "Hello!".encodeToByteArray()))
        ctx.flush()
    }
}

val engine = EpollEngine()  // または KqueueEngine、NioEngine 等
engine.bindPipeline("0.0.0.0", 8080) { channel ->
    channel.pipeline
        .addLast("decoder", HttpRequestDecoder())
        .addLast("handler", HelloHandler())
        .addLast("encoder", HttpResponseEncoder())
}
```

## 2 つの I/O モード

イベント駆動型 I/O — Native プラットフォームの epoll・kqueue・io_uring、JVM の NIO Selector — は、I/O が進行可能になるとアプリケーションに通知します: データの到着、送信バッファの空き、または新しい接続の待機です。この通知をアプリケーションコードに伝える方法には、根本的に異なる 2 つのアプローチがあります:

**ノンブロッキング逐次モデル** — I/O が進行可能になると、I/O スレッドが中断中のハンドラを再開します。コードは同期処理のように逐次的に書けますが、スレッドをブロックしません。Kotlin では `suspend fun` として現れ、Python・C#・JavaScript では `async`/`await` として同じパターンが使われます。従来のブロッキング逐次モデル（接続ごとに 1 OS スレッドを `read()` でブロック）とは異なり、1 スレッドで数千の並行接続を扱えます。

**Push モデル** — I/O が進行可能になると、エンジンが I/O スレッド上でハンドラを直接呼び出します。suspend もコンテキストスイッチもなく、関数呼び出しのチェーンだけです。Netty の `ChannelPipeline` が採用しているモデルであり、イベント駆動型 I/O と自然に合致します。

keel は両方を提供します:

| | Coroutine モード | Pipeline モード |
|---|---|---|
| モデル | ノンブロッキング逐次 | Push / イベント駆動 |
| API | `suspend fun read() / write()` | `ChannelPipeline` ハンドラチェーン |
| 並行処理単位 | 接続ごとに 1 コルーチン | EventLoop スレッドのコールバック |
| 使用方法 | `keel-ktor-engine` または `engine.bind()` | `engine.bindPipeline(...)` |
| 最適な用途 | Ktor を使ったアプリケーションサーバー | 高スループットのカスタムプロトコルサーバー |

**Coroutine モード**は `keel-ktor-engine` を使用した場合に得られるモードです。全 Ktor プラグインと統合できるため、ほとんどのアプリケーションに適しています。

**Pipeline モード**は Push モデルに従います。keel-core が `ChannelPipeline` — Netty にインスパイアされたハンドラチェーン — を提供し、全エンジンがこれを実装しています。`engine.bindPipeline()` でデコーダ・ルータ・エンコーダをハンドラとして設定します。I/O コールバックはコルーチンのコンテキストスイッチなしにエンジンの EventLoop スレッド上で動作します。

詳しくは[Coroutine モード](./architecture/coroutine.md)と[Pipeline モード](./architecture/pipeline.md)を参照してください。

## モジュール

### コア

| モジュール | 提供するもの |
|---|---|
| `keel-core` | `StreamEngine`、`Channel`、`Server`、`BindConfig`、`Logger` |
| `keel-io` | `IoBuf`、`SuspendSource`、`SuspendSink`、`BufferAllocator` |
| `keel-tls` | `TlsConfig`、`TlsInstaller`、証明書ユーティリティ（TLS インターフェース定義。推移的依存として自動で取り込まれる） |
| `keel-native-posix` | POSIX ソケットユーティリティ（Native エンジンの内部利用） |

### エンジン（ターゲットプラットフォームごとに 1 つ選ぶ）

| モジュール | プラットフォーム | 仕組み |
|---|---|---|
| `keel-engine-nio` | JVM | java.nio.Selector |
| `keel-engine-netty` | JVM | Netty 4.2 |
| `keel-engine-epoll` | Linux | epoll |
| `keel-engine-io-uring` | Linux（カーネル 5.1+） | io_uring |
| `keel-engine-kqueue` | macOS | kqueue |
| `keel-engine-nwconnection` | macOS / iOS（Apple） | Network.framework |
| `keel-engine-nodejs` | JS（Node.js） | Node.js net/tls |

### TLS バックエンド（任意）

| モジュール | プラットフォーム | ライブラリ |
|---|---|---|
| `keel-tls-jsse` | JVM | JSSE（JDK 標準） |
| `keel-tls-mbedtls` | Native | Mbed TLS 4.x ¹ |
| `keel-tls-openssl` | Native | OpenSSL 3.x ¹ |
| `keel-tls-awslc` | Native | AWS-LC（BoringSSL fork）¹ |

¹ `-Ptls` ビルドフラグが必要。

`keel-engine-netty`・`keel-engine-nwconnection`・`keel-engine-nodejs` は `keel-tls-*` モジュール不要です。

### Ktor アダプタとコーデック

| モジュール | 提供するもの |
|---|---|
| `keel-ktor-engine` | Ktor サーバーエンジンアダプタ |
| `keel-codec-http` | HTTP/1.1 パーサー / ライター（RFC 7230/7231） |
| `keel-codec-websocket` | WebSocket フレーミング（RFC 6455） |

## 動作要件

- Kotlin 2.3.20+
- JVM 21+（JVM ターゲット）
- Linux 4.5+（epoll）、5.1+（io_uring）
- macOS 10.14+（kqueue / NWConnection）
- TLS（`-Ptls` ビルド）: OpenSSL 3.x · Mbed TLS 4.x · AWS-LC

## ライセンス

Apache 2.0 — Copyright 2026 fukusaka
