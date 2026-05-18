---
sidebar_position: 1
---

# HTTP サーバ DSL

`keel-server-http` は、1 つの Kotlin DSL ブロックで設定するネイティブ
HTTP/1.1 サーバです。keel の全エンジンターゲット — Linux（epoll,
io_uring）、macOS（kqueue）、JVM（NIO, Netty）、JS（Node.js）— で同一
ソースから動作します。

このページは `keelHttpServer { }` DSL のガイドツアーです。各セクションで
機能を 1 つずつ追加し、最後までに全ビルダーメソッドを網羅します。

## Hello, world

```kotlin
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.keelHttpServer

fun main() {
    val engine = NioEngine()
    val server = keelHttpServer(engine) {
        connector { port = 8080 }
        get("/hello") { call -> call.respondText("Hello, world!") }
    }
    server.start()
}
```

`keelHttpServer(engine) { ... }` がサーバを構築し、`start()` がソケットを
bind して接続受付を開始します。`NioEngine` の代わりに任意の keel エンジン
— `EpollEngine`、`KqueueEngine`、`IoUringEngine`、`NettyEngine`、
`NodeEngine` — が使えます。

## connector — サーバが listen する場所

`connector { }` は listen するエンドポイントを設定します。省略すると、
全インターフェース上の OS 割り当てポートに bind します。

```kotlin
connector {
    host = "0.0.0.0"   // bind アドレス（IP リテラル）
    port = 8080
    backlog = 128      // accept キューの深さ
}
```

HTTPS を提供するには `tls { }` ブロックを追加します:

```kotlin
connector {
    port = 8443
    tls {
        config = myTlsConfig                       // 証明書 + 鍵
        strategy = ServerTlsStrategy.EngineNative   // 必須 — デフォルトなし
    }
}
```

`strategy` は TLS ハンドシェイクを誰が行うか（エンジンのネイティブ TLS か、
keel TLS コーデックか）を選びます。デフォルトはなく、明示的に選択します。

## ルーティング

HTTP メソッドとパスにハンドラを登録します:

```kotlin
get("/users") { call -> call.respondText("all users") }
post("/users") { call -> /* ユーザ作成 */ }
```

`get` / `post` / `put` / `delete` / `patch` / `head` / `options` は
ショートハンドで、`route(method, path, handler)` はメソッドを引数に取ります。

### パスパターン

パスはセグメント単位でマッチします:

| パターン | マッチ対象 | 例 |
|---|---|---|
| `users` | そのセグメント完全一致 | `/users` |
| `:id` | 任意の 1 セグメント、`id` として捕捉 | `/users/42` → `id = "42"` |
| `:id(int)` | 整数の 1 セグメント | `/items/42` ✓ `/items/abc` ✗ |
| `:id(uuid)` | UUID の 1 セグメント | `/items/550e8400-...` |
| `:id(^[a-f]+$)` | regex に一致する 1 セグメント | カスタムパターン |
| `:id?` | 末尾セグメント（省略可） | `/users` **と** `/users/42` の両方 |
| `*` | パスの残り全部（末尾セグメントのみ） | `/static/css/site.css` → `"*" = "css/site.css"` |

```kotlin
get("/users/:id(int)") { call ->
    val id = call.pathParameters["id"]   // 数値であることが保証される
    call.respondText("user $id")
}
```

制約付きパラメータと無制約のものは共存できます — `/items/:id(int)` と
`/items/:id(uuid)` は `/items/42` と `/items/<uuid>` を別のハンドラに
ルーティングします。登録済みパスへ**未登録のメソッド**でリクエストすると
`Allow` ヘッダ付きの `405 Method Not Allowed` が返ります。

### 述語ルーティング（predicate routing）

2 つのハンドラが同一のメソッドとパスを共有し、リクエストの属性で選択
できます — content negotiation に便利です:

```kotlin
get("/report", accept("application/json")) { call -> /* JSON */ }
get("/report", accept("text/html"))        { call -> /* HTML */ }
```

組み込み述語: `header(name, value)`、`query(name, value)`、
`accept(contentType)`、`host(name)`。登録順で最初に述語がリクエストを
受理したハンドラが勝ちます。述語なしのハンドラは catch-all です。

## リクエストの読み取り、レスポンスの書き込み

ハンドラは `HttpCall` を受け取ります:

```kotlin
post("/echo") { call ->
    call.method            // POST
    call.path              // "/echo"
    call.queryString       // "?a=1" → "a=1"、なければ null
    call.headers["X-Foo"]  // リクエストヘッダ
    call.pathParameters["id"]

    val body: ByteArray = call.receiveBytes()   // body 全体を読む
    call.respondText(body.decodeToString())
}
```

レスポンス:

```kotlin
call.respondText("hi")                                 // 200 text/plain
call.respond(HttpResponse.of(HttpStatus.CREATED, "")) // 任意のステータス + body
call.respondStream(head) { sink -> sink.write(buf) }   // chunked ストリーミング
```

大きい body やストリーム body には、`receiveChunk()` が集約せずバッファを
1 つずつ返し、`respondStream` がレスポンスを逐次書き込みます。

## ミドルウェア

`install` は**すべての**リクエストを包むステージを追加します。ミドルウェアは
ハンドラの前後で走り、短絡（short-circuit）もできます:

```kotlin
install { call, next ->
    val start = TimeSource.Monotonic.markNow()
    next()                                  // チェーンの残りを実行
    println("${call.method} ${call.path} — ${start.elapsedNow()}")
}
```

`next()` を 1 回だけ呼べば継続、呼ばなければ短絡します（自分で `401` を
返す認証チェック等）。ミドルウェアは install 順に走り、最初に install した
ものが最も外側です。

## ルートグループ

`route(prefix) { }` は共有パス prefix とグループスコープのミドルウェアで
ルートをまとめます。グループはネストできます:

```kotlin
route("/api/v1") {
    install { call, next -> /* /api/v1 配下すべての認証 */ next() }

    get("/users") { call -> /* GET /api/v1/users */ }

    route("/admin") {
        install { call, next -> /* /api/v1/admin 配下の追加チェック */ next() }
        get("/stats") { call -> /* GET /api/v1/admin/stats */ }
    }
}
```

グループの `install` ミドルウェアはそのグループのルート（とネストした
グループ）にのみ適用されます — 全リクエストを包むサーバ全体の `install` とは
異なります。ネストしたグループは外側グループの prefix とミドルウェアを
継承します。

## 静的ファイル

ディレクトリを配信:

```kotlin
staticFiles("/assets", "./public")   // GET /assets/css/site.css → ./public/css/site.css
```

`staticFiles` は `Content-Type`、条件付き GET（`ETag` / `Last-Modified`
→ `304`）、HTTP `Range` リクエスト（`206 Partial Content`）を処理し、
5 層のパストラバーサル防御を持ちます。`staticFile(urlPath, file)` は単一
ファイルを、`staticAssets(urlPath, source)` はカスタムアセットソースを
配信します。

## WebSocket

`webSockets { }` は WebSocket エンドポイントを登録します。その中の各
`webSocket(path) { }` は開いた `WsSession` に対して実行されます:

```kotlin
webSockets {
    webSocket("/echo") {
        for (message in incoming) {   // incoming: メッセージ全体のチャネル
            send(message)             // echo して返す
        }
    }
    webSocket("/chat/:room") {
        val room = pathParameters["room"]
        // ...
    }
}
```

`incoming` はメッセージ全体（`Text` / `Binary` の `WsMessage`）を配ります
— 分割フレームは再組立済みです。`send` は `WsMessage`、`String`、
`ByteArray` を受け付けます。

`permessage-deflate` 圧縮を有効にするには、コーデックを渡して調整します:

```kotlin
webSockets(DeflateCodec) {
    deflate { contextTakeover = false; threshold = 1024 }
    webSocket("/chat") { for (m in incoming) send(m) }
}
```

`webSockets { }` ブロックは `route(prefix) { }` グループ内にも置けます —
その場合 WebSocket エンドポイントはグループの prefix とミドルウェアを
継承します（ハンドシェイク前に認証が走ります）:

```kotlin
route("/api/v1") {
    install { call, next -> /* 認証 */ next() }
    webSockets {
        webSocket("/chat") { for (m in incoming) send(m) }   // /api/v1/chat
    }
}
```

## エラーハンドリング

組み込みの `404` と `500` を差し替えます:

```kotlin
notFound { call ->
    call.respondText("nothing here", HttpStatus.NOT_FOUND)
}

exception<UserNotFoundException> { call, cause ->
    call.respondText("user ${cause.id} not found", HttpStatus.NOT_FOUND)
}
```

`notFound { }` はどのルートにも一致しなかったときに走ります。
`exception<T> { }` は型 `T` の送出例外 — ハンドラまたはミドルウェア
チェーンから escape したもの — をレスポンスへ変換し、その型に対する
組み込み `500` を置き換えます。例外マッパーは登録順に参照されるので、
より具体的な例外型を先に登録します。どのマッパーにも一致しない例外は
`500` にフォールバックします。

## ライフサイクル

```kotlin
server.start()                                          // bind + accept
server.stop(gracePeriodMillis = 5_000, timeoutMillis = 10_000)
```

`stop` は graceful にシャットダウンします: アイドルな keep-alive 接続は
即座に閉じ、処理中のリクエストは `Connection: close` レスポンスで完了し、
タイムアウトを超えた残りは強制クローズされます。

## DSL リファレンス — どこで何が使えるか

ほとんどのビルダーメソッドはトップレベルと `route(prefix) { }` グループ内の
両方で使えます。一部はトップレベル限定です:

| メソッド | トップレベル | グループ内 | 備考 |
|---|:--:|:--:|---|
| `connector { }` | ✓ | — | listen ソケットはサーバ単位。グループは 1 ソケットの path subtree。 |
| `get` / `post` / … / `route` | ✓ | ✓ | |
| `route(prefix) { }` | ✓ | ✓ | ネスト可。 |
| `install` | ✓ | ✓ | トップレベルは全リクエスト、グループ内はそのグループのルートのみを包む。 |
| `webSockets { }` | ✓ | ✓ | |
| `staticFiles` / `staticFile` / `staticAssets` | ✓ | — | グループ対応は予定。 |
| `notFound { }` | ✓ | — | 「どのルートにも未一致」はサーバ単位の事象。 |
| `exception<T> { }` | ✓ | — | サーバ単位のポリシー。グループ限定なら `try { next() } catch` する `install` ミドルウェアを使う。 |

`connector` だけが**本質的に**サーバ単位です — 他は設計判断でトップレベル
限定であり、グループ相当の手段は利用可能（例外処理は `install`）または
予定済み（`staticFiles`）です。
