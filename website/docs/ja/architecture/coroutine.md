# Coroutine モード

Coroutine モードは keel の suspend ベース I/O API です。Accept した接続は `Channel` オブジェクトとして扱われ、`suspend fun read()`・`write()`・`flush()` を持ちます。このモデルは Kotlin コルーチンと自然に統合でき、`keel-server-ktor` を通じた Ktor 連携の基盤となっています。

## Coroutine モードを使う場面

| 状況 | 推奨 |
|---|---|
| Ktor アプリケーションを構築する | `keel-server-ktor` を使用 — Coroutine モードは自動的に配線される |
| コルーチンベースのカスタムサーバーを書く | `engine.bind()` + `server.accept()` を直接使用する |
| カスタムプロトコルで最大スループットが必要 | [Pipeline モード](./pipeline.md)を検討する |

## Ktor 統合

`keel-server-ktor` は Coroutine モードのライフサイクル全体を内部で管理します。`engine.bind()` や `server.accept()` を自分で呼び出す必要はありません — Ktor の `embeddedServer` 呼び出しと Ktor エンジンが処理します:

```kotlin
embeddedServer(Keel, port = 8080) {
    routing {
        get("/hello") {
            call.respondText("Hello from keel!")
        }
    }
}.start(wait = true)
```

内部的には、`keel-server-ktor` が `engine.bind()` を呼び出し、`server.accept()` をループし、`channel.asBufferedSuspendSource()` と `channel.asSuspendSink()` を使って Accept した各 `Channel` を Ktor の `ApplicationCall` パイプラインに橋渡しします。

## Coroutine モードの仕組み

```
engine.bind()                    → Server（リッスンソケット）
    └── server.accept()          → Channel（Accept した接続ごとに 1 つ）
            ├── channel.read()   → データが届くまで suspend し、IoBuf に読み込む
            ├── channel.write()  → データをバッファリングする
            ├── channel.flush()  → バッファされたデータが送信されるまで suspend する
            └── channel.close()  → 接続を閉じる
```

`server.accept()` は新しい接続が来るまで suspend します。`channel.read()` は相手からデータが届くまで suspend します。エンジンはバックグラウンドの EventLoop で I/O イベントを処理し、I/O が進行可能になるとコルーチンを自動的に再開します。

## 直接使用する場合

```kotlin
import io.github.fukusaka.keel.core.*

val engine: StreamEngine = EpollEngine()  // または KqueueEngine、NioEngine 等

val server = engine.bind("0.0.0.0", 8080)
println("Listening on ${server.localAddress}")

while (true) {
    val channel = server.accept()         // 接続が来るまで suspend する
    launch {                              // 各接続を独自のコルーチンで処理する
        val buf = channel.allocator.allocate(4096)  // 接続ごとに 1 つ確保し、read ごとに使い回す
        try {
            while (true) {
                buf.clear()               // readerIndex と writerIndex を 0 にリセット
                val n = channel.read(buf) // データが届くまで suspend する。EOF は -1
                if (n == -1) break
                channel.write(buf)        // echo: 受信データをそのまま返す（write() が内部で retain）
                channel.flush()           // データが送信されるまで suspend する
            }
        } finally {
            buf.release()
            channel.close()
        }
    }
}
```

## Channel インターフェース

```kotlin
interface Channel : AutoCloseable {
    val allocator: BufferAllocator         // このチャネルのエンジン用アロケータ
    val remoteAddress: SocketAddress?
    val localAddress: SocketAddress?
    val isOpen: Boolean                    // トランスポートが開いている間は true
    val isActive: Boolean                  // 接続済みで I/O 可能な間は true

    suspend fun read(buf: IoBuf): Int      // buf に読み込む。バイト数を返す。EOF は -1
    suspend fun write(buf: IoBuf): Int     // 送信データをバッファリングする。バイト数を返す
    suspend fun flush()                    // バッファされたデータを送信し、完了まで suspend する
    fun shutdownOutput()                   // TCP FIN — 送信終了を通知。受信側は継続
    override fun close()                   // 両方向を閉じ、リソースを解放する

    val ioDispatcher: CoroutineDispatcher  // このチャネルの I/O に最適なディスパッチャ
}
```

`read()` は `buf.writerIndex` から書き込みを開始し、読み込んだバイト数だけ `writerIndex` を進めます。`buf` の所有権と解放責任は呼び出し元にあります — 読み込み前にアロケートし、処理後に `release()` してください。

`write()` は `buf` を内部で retain し、呼び出し時点の byte range をスナップショットとして記録します。`buf.readerIndex` は即座に進められます。retain された参照は `flush()` の完了時に解放されます。同じバッファを再利用する場合は、`flush()` が完了してからバッファの内容を上書きしてください。

## コーデックブリッジ

Coroutine モードは `asSuspendSource()` と `asSuspendSink()` でコーデック層と統合します。`BufferedSuspendSource` と `BufferedSuspendSink` はこれらをラップし、行指向・バイト指向のコーデックアクセスを提供します。`keel-codec-http` と `keel-codec-websocket` はこの仕組みでデータを読み書きしています:

```kotlin
// コーデック層の読み込み:
val source: BufferedSuspendSource = channel.asBufferedSuspendSource()

// コーデック層の書き込み:
val sink = BufferedSuspendSink(channel.asSuspendSink(), channel.allocator)

// チャネルから HTTP リクエストヘッドをパースする:
val requestHead = parseRequestHead(source)
```

## バックプレッシャー

`write()` は複数回呼び出してもデータをローカルにバッファリングするだけで、即時送信はしません。`flush()` はバッファされたデータをすべて OS に一括投入し（エンジンが対応していれば gather-write `writev` を使用）、OS が受理するまで suspend します。ピアの受信バッファが満杯になると TCP フロー制御が自然に伝播し、`flush()` はスペースが空くまで suspend します。

```kotlin
// 複数の write、1 回の flush — gather-write の最適化を活かす:
channel.write(headersBuf)
channel.write(bodyBuf)
channel.flush()  // 可能な場合はヘッダとボディを 1 回の OS 呼び出しで送信
```

## EventLoop との連携

`server.accept()` や `channel.read()` が suspend すると、呼び出し元コルーチンはスレッドを手放し、EventLoop は他の接続の I/O を処理できます。I/O が進行可能になると、EventLoop は `channel.ioDispatcher` でコルーチンを再開します。

`keel-server-ktor` は接続ハンドラを `ioDispatcher` で起動し、I/O 読み取り・リクエストパース・Ktor パイプライン・レスポンス符号化をすべて同一スレッド上で実行します。Ktor パイプライン自体は `KeelApplicationEngine.Configuration.applicationDispatcher` で実行され、null（デフォルト）の場合は `ioDispatcher` に collapse するため、パイプラインを囲む内部 `withContext(applicationDispatcher)` は no-op（同一ディスパッチャなのでコルーチンランタイムが省略）となり、リクエストごとのスレッド切り替えコストはゼロです。ブロッキング処理を多用するハンドラを抱えるデプロイでは `applicationDispatcher = Dispatchers.Default` を明示設定して work-stealing pool に offload することも可能です（リクエストごとに 1 回の hop コスト）。

カスタムサーバーコードで同じチャネルの I/O を行う追加コルーチンを起動する場合は `ioDispatcher` を使ってください:

```kotlin
launch(channel.ioDispatcher) {
    // このチャネルの I/O はエンジンに最適なスレッドで実行される
}
```

`ioDispatcher` が指すスレッドはエンジンによって異なります:

| エンジン | `ioDispatcher`（EventLoop） |
|---|---|
| epoll / kqueue / io_uring | チャネルごとの EventLoop スレッド（単一 pthread） |
| NIO（JVM） | `NioEventLoop` の Selector スレッド |
| Netty（JVM） | `io.netty.channel.EventLoop`（`NettyEventLoopDispatcher` でラップ） |
| NWConnection | 接続ごとの GCD serial dispatch queue（`NwConnectionQueueDispatcher` でラップ） |
| Node.js | JS イベントループ（`Dispatchers.Unconfined`） |

どのエンジンも native I/O プリミティブ（epoll `epoll_wait`、kqueue `kevent`、io_uring CQE、Java NIO `Selector.select`、Netty `EventLoop.run`、GCD `dispatch_async`、Node.js microtask queue）を駆動するのと同じスレッドでコルーチンを再開します。リクエストの全処理（I/O 読み取り → HTTP パース → Ktor ハンドラ → レスポンス符号化）がそのスレッド上で実行され、スレッド間の受け渡しは発生しません。

トレードオフとして、ユーザーハンドラで EventLoop を長時間ブロックしてはなりません。ブロッキング I/O — JDBC、`Thread.sleep`、非同期 API 未対応のファイルシステム読み書き — は同一 EventLoop に多重化された他の全接続を当該呼び出しの完了までストールさせます。ブロッキング処理は `withContext(Dispatchers.IO)` でキャッシュスレッドプールに逃がしてください:

```kotlin
get("/user/{id}") {
    val user = withContext(Dispatchers.IO) { jdbcTemplate.queryForObject(...) }
    call.respond(user)
}
```

CPU 負荷の高い処理も同様に `withContext(Dispatchers.Default)` でオフロードします。

## パフォーマンス

Coroutine モードは接続ごとに 1 コルーチンと、`read()` の再開ごとにコンテキストスイッチが発生します。Native エンジン（epoll、kqueue、io_uring）では再開したコルーチンが EventLoop スレッド上で直接実行されるため、このオーバーヘッドは小さく抑えられます。ベンチマーク数値はエンジン別に[エンジン選択ガイド](./engine-guide.md#エンジン別パフォーマンス)を参照してください。

Ktor を使わずに最大スループットを追求する場合は[Pipeline モード](./pipeline.md)を参照してください。
