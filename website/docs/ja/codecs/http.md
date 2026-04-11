---
sidebar_position: 1
---

# HTTP/1.1 コーデック

`keel-codec-http` モジュールは RFC 7230/7231 準拠の HTTP/1.1 パーサーとライターを提供します。`keel-io`、`keel-core`、`kotlinx.io` に依存し、サポートされる全ターゲットで動作します。

## Pipeline モード

Pipeline モードのサーバーでは、`HttpRequestDecoder` と `HttpResponseEncoder` をチャネルパイプラインに追加します。**アウトバウンドメッセージは tail から head に向かって流れる**ため、encoder を decoder および handler より先に追加する必要があります:

```kotlin
engine.bindPipeline("0.0.0.0", 8080) { channel ->
    channel.pipeline.addLast("encoder", HttpResponseEncoder())
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    channel.pipeline.addLast("handler", MyHandler())
}
```

結果のパイプライン順序は以下のとおりです:

```
HEAD ↔ encoder ↔ decoder ↔ handler ↔ TAIL

インバウンド:  HEAD → (encoder スキップ) → decoder → handler
アウトバウンド: handler → (decoder スキップ) → encoder → HEAD
```

`HttpRequestDecoder` は受信した `IoBuf` バイト列を `HttpRequestHead` メッセージにデコードします。`HttpResponseEncoder` は送信する `HttpResponse` を `IoBuf` にシリアライズします。ハンドラは次のように実装します:

```kotlin
class MyHandler : TypedChannelInboundHandler<HttpRequestHead>(HttpRequestHead::class) {
    override fun onReadTyped(ctx: ChannelHandlerContext, msg: HttpRequestHead) {
        ctx.propagateWriteAndFlush(HttpResponse.ok("Hello!"))
    }
}
```

`HttpRequestDecoder` が発出するのは `HttpRequestHead`（リクエストライン + ヘッダーのみ）です。ボディはバッファリングせず、同一バッファ内のパイプライン済みリクエストをすぐにデコードできるよう読み飛ばします。

## Coroutine モード

コルーチンベースのサーバー（Pipeline 以外）では、`Channel` から `BufferedSuspendSource` と `BufferedSuspendSink` を取得し、サスペンドオーバーロードを使用します:

```kotlin
import io.github.fukusaka.keel.codec.http.*
import io.github.fukusaka.keel.io.BufferedSuspendSink

val source = channel.asBufferedSuspendSource()
val sink = BufferedSuspendSink(
    channel.asSuspendSink(), channel.allocator, channel.supportsDeferredFlush
)
try {
    // サスペンドバリアント — runBlocking 不要
    val head: HttpRequestHead = parseRequestHead(source)

    // Content-Length が存在する場合はボディを読み取る
    val body: ByteArray? = head.headers.contentLength?.let { len ->
        source.readByteArray(len.toInt())
    }

    // レスポンスを構築して書き込む
    val responseHeaders = HttpHeaders.build {
        add(HttpHeaderName.CONTENT_TYPE, "text/plain; charset=utf-8")
        add(HttpHeaderName.CONTENT_LENGTH, "5")
    }
    writeResponseHead(HttpStatus.OK, HttpVersion.HTTP_1_1, responseHeaders, sink)
    sink.write("hello".encodeToByteArray())
    sink.flush()
} finally {
    source.close()
    sink.close()
}
```

`parseRequestHead(BufferedSuspendSource)` と `parseResponseHead(BufferedSuspendSource)` はサスペンドオーバーロードです — ボディは**消費されません**。ヘッドのパース後、`source.readByteArray(length)` でボディバイトを手動で読み取ります。

`writeResponseHead(status, version, headers, BufferedSuspendSink)` はステータスラインとヘッダーを書き込みます。`sink.write(bytes)` でボディを書き込んだ後、`sink.flush()` を呼び出してバッファされたデータをネットワークに送信します。

## パース

`kotlinx.io.Source` を使って `parseRequest` / `parseResponse` を呼び出します:

```kotlin
import io.github.fukusaka.keel.codec.http.*
import kotlinx.io.Buffer

val buf = Buffer()
buf.writeString("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n")

val request: HttpRequest = parseRequest(buf)
println(request.method)        // GET
println(request.uri)           // /hello
println(request.version.text)  // HTTP/1.1
println(request.path)          // /hello（クエリ文字列を除く）
```

ボディを別途ストリーミングしたい場合は `parseRequestHead` / `parseResponseHead` を使います — ヘッドが返され、ボディバイトはソースに残ります。

## 書き込み

`kotlinx.io.Sink` を使って `writeRequest` / `writeResponse` を呼び出します:

```kotlin
// ファクトリメソッド — Content-Type と Content-Length を自動設定
val response = HttpResponse.ok("hello")

// — または — 手動で構築
val response = HttpResponse(
    status = HttpStatus.OK,
    headers = HttpHeaders.build {
        add(HttpHeaderName.CONTENT_TYPE, "text/plain")
        add(HttpHeaderName.CONTENT_LENGTH, "5")
    },
    body = "hello".encodeToByteArray(),
)

val buf = Buffer()
writeResponse(response, buf)
```

`writeResponse` は `Content-Length` を**自動付与しません**。ヘッダーに明示するか、ファクトリメソッド（`HttpResponse.ok()`、`HttpResponse.of(status)`）を使用してください。

## 主要な型

| 型 | 備考 |
|---|---|
| `HttpRequest` | `method`、`uri`、`version`、`headers`、`body?`。計算プロパティ: `path`、`queryString`、`isKeepAlive`。ファクトリ: `get(uri)`、`post(uri, body)` |
| `HttpRequestHead` | `method`、`uri`、`version`、`headers`。計算プロパティ: `path`、`queryString`、`isKeepAlive`。Pipeline モードの `HttpRequestDecoder` と Coroutine/ブロッキングの `parseRequestHead` が発出する |
| `HttpResponse` | `status`、`version`、`headers`、`body?`。ファクトリ: `ok()`、`notFound()`、`of(status)` |
| `HttpHeaders` | 大文字小文字を区別しないヘッダーストア。`add()` / `set()` / `get()` / `getAll()` / `remove()`。`HttpHeaders.build {}` または `HttpHeaders.of(pairs)` で構築 |
| `HttpMethod` | 大文字小文字を区別するトークン。定数: `GET`、`POST`、`PUT`、`DELETE`、`PATCH` 等。カスタムメソッドも許可 |

## エラー処理

| 例外 | スローされる場面 |
|---|---|
| `HttpParseException` | 不正なリクエストライン、無効なヘッダー、obs-fold、Host ヘッダー欠如、未サポートの HTTP バージョン、TE+CL 競合（Pipeline モード） |
| `HttpEofException` | 完全なメッセージを受信する前に接続が閉じられた |

パイプラインの `onError` ハンドラ、または `try`/`catch` でこれらをキャッチしてください。

## RFC 準拠

- **Host ヘッダー**（RFC 7230 §5.4）: `Host` ヘッダーのない HTTP/1.1 リクエストは拒否されます
- **obs-fold**（ヘッダー行の継続）は拒否されます — RFC 7230 §3.2.6
- **Transfer-Encoding + Content-Length の競合**（RFC 7230 §3.3.3）:
  - `parseRequest` / `readBody`: Transfer-Encoding が Content-Length より優先されます
  - `HttpRequestDecoder`（Pipeline モード）: 両方のヘッダーが存在するリクエストは `HttpParseException` で拒否されます
- **Set-Cookie** ヘッダーはカンマで結合されません — RFC 6265
- チャンク転送エンコーディングはパースと書き込みの両方をサポートします

## ターゲット

`jvm` / `js (nodejs())` / `linuxX64` / `linuxArm64` / `macosArm64` / `macosX64`
