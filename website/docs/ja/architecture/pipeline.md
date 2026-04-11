# Pipeline モード

Pipeline モードは keel の push ベース I/O API です。データが届くとエンジンが EventLoop スレッド上でハンドラを直接呼び出します — コルーチンなし、コンテキストスイッチなし、suspend なし。I/O ホットパスからコルーチンオーバーヘッドを排除します。

## Pipeline モードを使う場面

| 状況 | 推奨 |
|---|---|
| 最大スループットのためにカスタムプロトコルを実装する | `engine.bindPipeline()` を使用 — ホットパスにコルーチンオーバーヘッドなし |
| suspend コードよりコールバック／イベント駆動モデルを好む | Pipeline モードが自然に合致する |
| push コールバックと suspend 読み取りを同じ接続で使う | `PipelinedChannel` は `Channel` を継承しており、両モードが使用可能 |

## Pipeline モードの仕組み

```
受信方向:
  エンジンが IoBuf に読み込む → pipeline.notifyRead(buf)
      HEAD → [TLS] → デコーダ → ハンドラ → TAIL

送信方向:
  ハンドラが ctx.propagateWriteAndFlush(response) を呼ぶ
      ハンドラ → エンコーダ → [TLS] → HEAD
                                       → IoTransport.write() / flush() → ソケット
```

Accept した各接続は `ChannelPipeline` — ハンドラの順序付きチェーン — を持ちます。受信イベントは HEAD から TAIL へ、送信操作は呼び出し元ハンドラから HEAD 方向へ流れます。HEAD が `IoTransport` を通じて実際のソケット I/O を担います。

`ctx.propagateWrite()` は呼び出し元ハンドラの位置から **HEAD 方向に向かって** 伝播し、途中の `ChannelOutboundHandler` を順に呼び出します。TAIL 側にあるハンドラは通過しません。このことがエンコーダの配置場所を決定します（[ChannelPipeline](#channelpipeline) 参照）。

ハンドラの全コールバックは EventLoop スレッド上で動作し、**ブロックも suspend もしてはなりません**。CPU 負荷の高い処理は別のディスパッチャにオフロードしてください。

## ハンドラの実装

ハンドラの役割は 3 つのインターフェースで分担します:

**`ChannelInboundHandler`** — HEAD から TAIL へ流れるデータと接続ライフサイクルイベントを受け取ります:

```kotlin
interface ChannelInboundHandler : ChannelHandler {
    fun onActive(ctx: ChannelHandlerContext) { ctx.propagateActive() }
    fun onRead(ctx: ChannelHandlerContext, msg: Any) { ctx.propagateRead(msg) }
    fun onReadComplete(ctx: ChannelHandlerContext) { ctx.propagateReadComplete() }
    fun onInactive(ctx: ChannelHandlerContext) { ctx.propagateInactive() }
    fun onError(ctx: ChannelHandlerContext, cause: Throwable) { ctx.propagateError(cause) }
    fun onUserEvent(ctx: ChannelHandlerContext, event: Any) { ctx.propagateUserEvent(event) }
}
```

デフォルト実装は各イベントを次のハンドラに伝播します。ハンドラが必要なコールバックのみオーバーライドしてください。デコード済みまたは変換済みメッセージを次の受信ハンドラに渡すには `ctx.propagateRead(transformed)` を呼びます。

**`ChannelOutboundHandler`** — HEAD 方向へ流れる write・flush・close 操作をインターセプトします。エンコーダはこれを実装し、アプリケーションレベルのメッセージをトランスポートが書き込む前に `IoBuf` に変換します:

```kotlin
class MyResponseEncoder : ChannelOutboundHandler {
    override fun onWrite(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is MyResponse) {
            val buf = ctx.allocator.allocate(/* サイズ */)
            // msg を buf にエンコード
            ctx.propagateWrite(buf)   // 伝播後に自分のアロケーションを解放
            buf.release()
        } else {
            ctx.propagateWrite(msg)   // 未知の型はそのまま通過させる
        }
    }
}
```

複数の `propagateWrite` 呼び出しはトランスポートの送信バッファに蓄積されます。1 回の `propagateFlush`（または `propagateWriteAndFlush`）でまとめて OS に送信します。エンジンが対応していれば gather-write（`writev`）による一括送信が行われます。

**`ChannelDuplexHandler`** — 受信と送信の両方を実装します。双方向でメッセージを変換するコーデック（リクエストデコーダとレスポンスエンコーダの組み合わせ等）に使用してください。

### TypedChannelInboundHandler

特定のデコード済みメッセージ型を消費する受信ハンドラには、`TypedChannelInboundHandler<T>` を使うと型キャストを省略しつつ自動解放も受けられます:

```kotlin
class MyHandler : TypedChannelInboundHandler<HttpRequest>(HttpRequest::class) {
    override fun onReadTyped(ctx: ChannelHandlerContext, msg: HttpRequest) {
        val response = HttpResponse(HttpStatus.OK, body = "Hello!".encodeToByteArray())
        ctx.propagateWriteAndFlush(response)
    }
}
```

`onReadTyped` が `msg` を転送せずに返ると、バッファが自動的に解放されます。ラムダ形式の代替手段:

```kotlin
val handler = typedHandler<HttpRequest> { ctx, msg ->
    ctx.propagateWriteAndFlush(HttpResponse(HttpStatus.OK, body = "Hello!".encodeToByteArray()))
}
```

## ChannelPipeline

```
ネットワーク ↔ HEAD ↔ [TLS] ↔ デコーダ ↔ エンコーダ ↔ ハンドラ ↔ TAIL
                  受信 →                                       ← 送信
```

パイプラインは `bindPipeline` コールバック内で接続ごとに設定します。エンジンはイニシャライザが返った後に `armRead()` を自動で呼び出します:

```kotlin
engine.bindPipeline("0.0.0.0", 8080) { channel ->
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    channel.pipeline.addLast("encoder", HttpResponseEncoder())  // ハンドラより HEAD 側に置く
    channel.pipeline.addLast("handler", MyRoutingHandler())
}
```

**ハンドラの配置順序は送信方向に影響します。** `ctx.propagateWrite()` は呼び出し元ハンドラから HEAD 方向へ向かいます。エンコーダをハンドラと HEAD の間に配置することで送信データをインターセプトできます。ハンドラの TAIL 側に置いても送信時には呼ばれません。

パイプラインは `addLast`・`replace` 等の構築時に隣接ハンドラのメッセージ型の互換性を検証し、データが流れる前に型の不整合を検出します。ハンドラは `acceptedType` と `producedType` を宣言することで検証に参加します。両プロパティのデフォルトは `Any`（検証なし）です。

## BindConfig

`BindConfig` はサーバーごとの設定を提供します。`bindPipeline` の第 3 引数として渡します:

```kotlin
// カスタムバックログ付き HTTP
engine.bindPipeline(host, port, BindConfig(backlog = 512)) { ... }

// HTTPS: keel TlsHandler を接続ごとにインストール
// factory は TlsCodecFactory（例: OpenSslCodecFactory, JsseTlsCodecFactory）
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig, factory)) { ... }

// HTTPS: エンジンネイティブ TLS（NWConnection / Node.js のみ）
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig)) { ... }

// HTTPS: Netty SslHandler + カスタムバックログ
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig, NettySslInstaller(), backlog = 256)) { ... }
```

`TlsConnectorConfig` は `BindConfig` を継承し `backlog` パラメータを持ちます。`tlsConfig` の構築方法と `factory` の選択については [TLS](./tls.md) を参照してください。

## PipelinedChannel

`PipelinedChannel` は `bindPipeline` コールバックに渡される接続ごとのオブジェクトです:

```kotlin
interface PipelinedChannel : Channel {
    val pipeline: ChannelPipeline
    val isWritable: Boolean  // 送信バッファが高水位マークを超えると false
}
```

`PipelinedChannel` は `Channel` を継承しているため、必要に応じて suspend ベースの read/write も使用できます。

送信量を絞る必要があるハンドラは、`ChannelInboundHandler` の `onWritabilityChanged(ctx, isWritable: Boolean)` で書き込み可否の変化を監視してください — false になったら送信を停止し、true になったら再開します。

`armRead()` はインターフェースのメンバーではありません。各エンジンが `bindPipeline` のイニシャライザ返却後に内部で呼び出すため、イニシャライザから呼ぶ必要はありません。

全 7 エンジンが `PipelinedChannel` を実装しています:

| エンジン | 実装クラス |
|--------|---------------|
| kqueue | `KqueuePipelinedChannel` |
| epoll | `EpollPipelinedChannel` |
| io_uring | `IoUringPipelinedChannel` |
| NIO | `NioPipelinedChannel` |
| Netty | `NettyPipelinedChannel` |
| NWConnection | `NwPipelinedChannel` |
| Node.js | `NodePipelinedChannel` |

## パフォーマンス

Pipeline モードは I/O パスからコルーチンのコンテキストスイッチを排除します。受信データはエンジンの読み取りコールバックからハンドラチェーンへ suspend なしで直接流れます。接続ごとのコルーチンオーバーヘッドが許容できない高スループットなカスタムプロトコルサーバーに適しています。ベンチマーク数値はエンジン別に[エンジン選択ガイド](./engine-guide.md#エンジン別パフォーマンス)を参照してください。
