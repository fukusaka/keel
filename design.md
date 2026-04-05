# keel — KMP 非同期 I/O ライブラリ 設計まとめ

> **keel**（キール）: 船の竜骨。水面下に潜み、船全体を支える基盤。
> 本ドキュメントは、Kotlin Multiplatform (KMP) で Netty 相当の低レベル非同期 I/O ライブラリ
> `keel` を自前実装する際の設計・実装計画を議論した内容のまとめです。

---

## 1. プロジェクト定義

### ライブラリ名

| 項目 | 内容 |
|---|---|
| 名称 | **keel** |
| 読み | キール |
| 由来 | 船の竜骨（keel）。水面下に潜んで船全体を支える基盤のメタファー |
| Maven Group ID | `io.github.fukusaka.keel` |
| GitHub | `fukusaka/keel`（private） |

### Gradle 座標イメージ

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.fukusaka.keel:core:1.0")
    implementation("io.github.fukusaka.keel:engine-epoll:1.0")   // I/O エンジン（選択）
    implementation("io.github.fukusaka.keel:tls-mbedtls:1.0")    // TLS エンジン（選択）
    implementation("io.github.fukusaka.keel:ktor-engine:1.0")    // Ktor 統合（任意）
}
```

### 目標

1. **自前実装**（libuv は使わない）
2. **JVM は Netty 委譲エンジンも 1 エンジンとして含む**
3. **Ktor への Native エンジンも提供する**
4. **TLS エンジンも差し替え可能にする**（OpenSSL に依存しない）
5. **将来的に gRPC 完全 KMP 対応を見据える**

### keel の位置づけ

```
アプリケーション / Ktor DSL / gRPC KMP
        ↑
   keel（I/O の「速さ・制御」を提供）
        ↑
IoEngine  (epoll / kqueue / io_uring / NIO / NWConnection / Node.js)
TlsEngine (Mbed TLS / OS ネイティブ / Rustls / JSSE)
```

Ktor の「何を作るか」フレームワークに対し、keel は「どう繋ぐか」のエンジン。
**競合ではなく補完関係**であり、最終的には Ktor の Native エンジンとして採用されることが最大の社会的還元。

---

## 2. アーキテクチャ概要

### レイヤー構成

| レイヤー | 内容 | モジュール |
|---|---|---|
| 応用層 | Ktor DSL（HTTP クライアント機能は Ktor に委譲）、gRPC KMP、keel ネイティブ API（IoT 向け軽量 HTTP サーバー・非 HTTP クライアント）| `ktor-engine`、`grpc-core`、`server`、`client` |
| コーデック層（TCP）| HTTP/1.1、HTTP/2、WebSocket、MQTT、Redis、AMQP、gRPC など | `codec-http`、`codec-websocket`、`codec-mqtt`、`codec-redis`、`codec-http2`、`codec-amqp`、`codec-grpc` |
| コーデック層（UDP）| CoAP、DNS など | `codec-coap`、`codec-dns` |
| コア層 | IoEngine / TlsEngine / NativeBuf / BufferAllocator / EventLoop | `core` |
| I/O エンジン層（TCP）| TCP バックエンド（OS 差異を吸収）| `engine-epoll`、`engine-kqueue`、`engine-nio`、`engine-netty`、`engine-nodejs`、`engine-nwconnection`、`engine-io-uring` |
| I/O エンジン層（UDP）| UDP バックエンド | `engine-epoll-udp`、`engine-kqueue-udp`、`engine-nio-udp` |
| TLS エンジン層 | TLS 実装（ライセンス・要件で差し替え可能）| `tls-*` |
| OS 層 | epoll / io_uring / kqueue / NIO / IOCP | — |

> **Note**: ChannelPipeline は不採用。`kotlinx.io` の `RawSource` / `RawSink` と関数合成で代替する（§4.4 参照）。

### I/O エンジン一覧

| モジュール | ターゲット | I/O バックエンド | 状態 |
|---|---|---|---|
| `engine-epoll` | linuxX64、linuxArm64 | epoll | ✅ 完了（PR #7） |
| `engine-io-uring` | linuxX64、linuxArm64 | io_uring | ✅ 完了（PR #121〜#131） |
| `engine-epoll-udp` | linuxX64、linuxArm64 | UDP / epoll | 🔲 Phase 7 |
| `engine-kqueue` | macosX64、macosArm64 | kqueue | ✅ 完了（PR #6） |
| `engine-kqueue-udp` | macosX64、macosArm64 | UDP / kqueue | 🔲 Phase 7 |
| `engine-nio` | JVM | java.nio.Selector | ✅ 完了（PR #8） |
| `engine-nio-udp` | JVM | UDP / DatagramChannel | 🔲 Phase 7 |
| `engine-netty` | JVM | Netty 委譲 | ✅ 完了（PR #9） |
| `engine-nodejs` | JS (nodejs()) | Node.js `net` モジュール | ✅ 完了（PR #10） |
| `engine-nwconnection` | macosArm64、macosX64 | NWConnection | ✅ 完了（PR #11） |

### TLS エンジン一覧

| モジュール | ターゲット | TLS 実装 | ライセンス |
|---|---|---|---|
| `tls-mbedtls` | linuxX64/Arm64、macosX64/Arm64 | Mbed TLS (cinterop) | Apache 2.0 ◎ |
| `tls-system` | macosX64/Arm64（SecureTransport）、iOS（必須）、mingwX64（SChannel） | OS 標準 TLS | OS 依存（制約なし） |
| `tls-rustls` | linuxX64/Arm64 | Rustls FFI | Apache 2.0 / MIT |
| `tls-jsse` | JVM | JSSE（Conscrypt 差し替え対応） | JDK 標準 |
| `tls-nodejs` | JS nodejs() | node:tls モジュール | Node.js 標準 |

---

## 3. ターゲット対応方針

### KMP ターゲット分類と採用判断

Kotlin/Native の Tier 分類（Tier 1/2/3）は **Kotlin/Native 専用の概念**。
JVM・JS・WasmJS は独立した KMP 安定性分類（Stable / Beta）を持つ。

| ターゲット | KMP 分類 | 採用 | 理由 |
|---|---|---|---|
| `macosArm64` | Native **Tier 1** | ◎ | kqueue 直接利用可 |
| `iosArm64` / `iosSimulatorArm64` | Native **Tier 1** | △ | クライアント限定（App Sandbox 制限）、TLS は Network.framework 必須 |
| `linuxX64` | Native **Tier 2** | ◎ | epoll / io_uring、設計の軸 |
| `linuxArm64` | Native **Tier 2** | ○ | linuxX64 と共通実装 |
| `macosX64` | Native **Tier 3** | ○ | macosArm64 と共通実装 |
| `mingwX64` | Native **Tier 3** | 後回し | WSAPoll / IOCP の別エンジンが必要 |
| JVM（サーバー） | KMP **Stable** | ◎ | NIO + Netty 委譲 |
| JS + `nodejs()` | KMP **Stable** | ○ | Node.js `net` モジュールで TCP 可能 |
| JS + `browser()` | KMP **Stable** | **対象外** | ブラウザ Sandbox で TCP 不可 |
| WasmJS | KMP **Beta** | 対象外 | 同上 |

> **注意**: BSD 系（FreeBSD、NetBSD、OpenBSD）は KMP のサポートターゲットに存在しない。
> コード資産は macOS（kqueue）と共有できるが、KMP ターゲットとしては成立しない。

---

## 4. 主要設計決定

### 4.1 IoEngine（Phase 5 で再設計済み）

`expect class IoEngine`（poll/close スタブ）を `interface IoEngine` に置き換えた。

#### 設計判断

| 項目 | 決定 | 根拠 |
|---|---|---|
| IoEngine | `interface`（`expect class` から変更） | JVM に NIO/Netty が共存、テスト用 mock 容易 |
| Channel | 独自 API（`read`/`write`/`flush` on NativeBuf）+ `asSource()`/`asSink()` 変換 | エンジン層ゼロコピー + コーデック層 kotlinx-io 両立 |
| I/O モデル | pull model（`suspend fun`） | coroutine ベース、Netty アダプタ層で push→pull 変換 |
| write/flush | 分離 | writev/gather-write 最適化、io_uring SQE バッチング対応 |
| Half-close | `shutdownOutput()` のみ | `shutdownInput()` は不要（EOF で検知）。Netty/Go でもテスト内のみ |
| EventLoop | エンジン内部に閉じる（`:core` に公開しない） | Netty/Node.js/NWConnection が独自 EL を持つ |
| ServerChannel | `suspend fun accept(): Channel` のみ | `Flow<Channel>` は拡張関数で提供可能 |
| ライフサイクル | 最小（`isOpen`/`isActive`/`awaitClosed()`） | YAGNI、ミドルウェア設計時に拡張 |

#### インターフェース定義

```kotlin
interface IoEngine : AutoCloseable {
    suspend fun bind(host: String, port: Int): ServerChannel
    suspend fun connect(host: String, port: Int): Channel
    override fun close()
}

interface Channel : AutoCloseable {
    val allocator: BufferAllocator
    val remoteAddress: SocketAddress?
    val localAddress: SocketAddress?
    val isOpen: Boolean
    val isActive: Boolean
    suspend fun awaitClosed()
    suspend fun read(buf: NativeBuf): Int    // -1 = EOF
    suspend fun write(buf: NativeBuf): Int   // バッファに積む
    suspend fun flush()                      // writev 実行
    fun shutdownOutput()
    fun asSource(): RawSource                // コーデック層ブリッジ
    fun asSink(): RawSink                    // コーデック層ブリッジ
    override fun close()
}

interface ServerChannel : AutoCloseable {
    val localAddress: SocketAddress
    val isActive: Boolean
    suspend fun accept(): Channel
    override fun close()
}

```

#### IoEngineConfig

```kotlin
data class IoEngineConfig(
    val allocator: BufferAllocator = HeapAllocator,
    val threads: Int = 1,
)
```

- `threads`: EventLoop スレッド数。epoll/kqueue/NIO で使用。デフォルト 1（single-thread）
- Netty の `NioEventLoopGroup` は Netty エンジン固有のコンストラクタで受ける（共通 Config に入れない）
- `CoroutineDispatcher` の注入は Ktor アダプタ層の責務
- 設定項目が増えたら DSL ビルダーに移行する（非破壊的に可能）

#### IoEngineFactory

Ktor 統合（`embeddedServer(Keel)`）で必要になるまで追加しない。
アプリ直接利用では `expect fun platformEngine()` またはコンストラクタ直接呼び出しで十分。

#### bind/listen の一体化

`IoEngine.bind()` は POSIX の `bind()` + `listen()` を1メソッドにまとめている。
Go/tokio/Swift NIO/Ktor/Netty 全てが高レベル API では一体化しており、分離する実用上の理由はない。
`backlog` は将来 `bind(host, port, backlog)` のデフォルト引数で非破壊追加する。

#### Config の適用範囲

| 設定 | 適用範囲 | 配置先 |
|---|---|---|
| `allocator`, `threads` | エンジン全体 | `IoEngineConfig` |
| `backlog` | ServerChannel ごと | `bind()` パラメータ（将来追加） |
| `readTimeout`, `tcpNoDelay`, `soRcvBuf` 等 | Channel ごと | `Channel` プロパティ（将来追加） |

#### Phase (a) 実装パターン（KqueueEngine / NWConnection で確立）

Phase (a) は同期 I/O。suspend は API 互換のみで、内部はブロッキング。
Phase (b) で非同期 EventLoop に移行する。

**write/flush の PendingWrite パターン**:
```
write(buf)  --> buf.retain(), record offset/length in PendingWrite list
write(buf2) --> buf2.retain(), append to PendingWrite list
flush()     --> for each PendingWrite: send(ptr + offset, length), buf.release()
```
readerIndex を write() 時に即座に進めることで、呼び出し側がバッファを再利用可能。
Phase (b) で writev/gather-write に置き換え可能（API 変更なし）。

**エンジン別の I/O ブリッジ方式**:

| エンジン | read ブロッキング | write ブロッキング | 備考 |
|---|---|---|---|
| kqueue | EAGAIN → kevent wait → retry | POSIX write | non-blocking socket + kevent 待機 |
| epoll | EAGAIN → epoll_wait → retry | POSIX write | kqueue と同構造 |
| NIO | `SocketChannel.read(ByteBuffer)` | `SocketChannel.write(ByteBuffer)` | blocking mode |
| Netty | Netty Channel のラッパー | Netty Channel のラッパー | push→pull 変換 |
| NWConnection | `keel_nw_read` (C) + dispatch semaphore | `keel_nw_write` (C) + dispatch semaphore | bool 回避で C ラッパー必須 |
| Node.js | Promise ベース | Promise ベース | JS 制約、NativeBuf JS actual 未実装 |

**NWConnection の C ラッパー設計**:
`nw_connection_receive_completion_t` の `bool` パラメータが Kotlin/Native ObjC ブロック変換に非対応。
read/write/shutdown/start を個別の C 関数に実装し、async callback で coroutine 連携:
- `keel_nw_read_async`: `nw_connection_receive` → `dispatch_data_apply` + memcpy → callback
- `keel_nw_write_async`: `dispatch_data_create` → `nw_connection_send` → callback
- `keel_nw_shutdown_output`: `nw_connection_send(NULL, FINAL_MESSAGE, is_complete=true)`
- `keel_nw_start_conn_async`: state handler + start → callback on ready/failed

**StableRef cancel-safety（CallbackContext）**:
NWConnection の dispatch callback は必ず発火する（`nw_connection_cancel()` でも cancelled 状態遷移で発火）。
`invokeOnCancellation` で StableRef を dispose すると、callback が disposed ポインタを参照して crash する。
解決策: `CallbackContext<T>` ラッパーで atomic フラグを管理。
- `invokeOnCancellation` はフラグ設定のみ（StableRef は dispose しない）
- C callback が `tryResume()` で CAS チェック後に resume（cancelled なら skip）
- StableRef は常に C callback 側で dispose（所有権ルール）

#### 残課題

- `SocketAddress` は現在 TCP（host + port）のみ。Unix Domain Socket 対応は将来フェーズで再設計
- `:core` への JS ターゲット追加（`NativeBuf` の JS actual 実装）は別タスク

### 4.2 TlsEngine インターフェース（TLS 差し替えの軸）

IoEngine と完全対称な設計。OpenSSL に依存しない。

```kotlin
// keel-core / commonMain
interface TlsEngine {
    suspend fun clientHandshake(
        transport: NativeBuf,
        host: String,
        config: TlsConfig
    ): TlsSession

    suspend fun serverHandshake(
        transport: NativeBuf,
        config: TlsConfig
    ): TlsSession
}

expect object TlsEngineConfig {
    var engine: TlsEngine
}
```

ターゲット別のデフォルト：

```kotlin
// linuxX64Main
actual object TlsEngineConfig {
    actual var engine: TlsEngine = MbedTlsEngine()   // Apache 2.0、静的リンク可
}

// iosArm64Main — Network.framework TLS で固定（変更不可）
actual object TlsEngineConfig {
    actual var engine: TlsEngine
        get() = SystemTlsEngine()
        set(_) = error("iOS では TLS エンジンの差し替えは不可")
}

// jvmMain
actual object TlsEngineConfig {
    actual var engine: TlsEngine = JsseTlsEngine()   // Conscrypt に差し替え可
}
```

### 4.3 2 軸独立差し替えの組み合わせ例

```kotlin
// keel の使用例
IoEngineConfig.engine  = EpollEngine()
TlsEngineConfig.engine = MbedTlsEngine()
```

| I/O エンジン | TLS エンジン | 想定ユースケース |
|---|---|---|
| epoll | Mbed TLS | Linux 汎用サーバー（デフォルト） |
| io_uring | Mbed TLS | 高スループット Linux サーバー |
| epoll | Rustls | セキュリティ監査が厳しい環境 |
| kqueue | SystemTLS | macOS サーバー |
| NIO | JSSE | JVM 汎用 |
| Netty | JSSE | JVM 既存 Netty 資産活用 |
| NWConnection | SystemTLS | iOS クライアント（固定） |
| Node.js net | node:tls | Node.js サーバー |

### 4.4 コーデック層の統合方針とミドルウェア設計

#### Phase 4 現在の方針（関数合成）

Phase 4 では ChannelPipeline を実装しない。`kotlinx.io` の `RawSource` / `RawSink` と Kotlin の関数合成で代替する。

```kotlin
// Netty 的な発想
pipeline.addLast(HttpDecoder()).addLast(BusinessLogicHandler())

// keel Phase 4 の発想（kotlinx.io + 関数合成）
val handler: (RawSource) -> Unit = ::parseRequest andThen ::handleRequest
```

コーデック層は IoEngine に非依存の純粋関数として `commonMain` に実装する。

```kotlin
// commonMain（エンジン非依存）
fun parseRequest(source: RawSource): HttpRequest
fun writeResponse(response: HttpResponse, sink: RawSink)
```

これにより全 KMP ターゲット（JVM / linuxX64 / macosArm64 / JS nodejs）で同一のコーデックを再利用できる。

#### 将来フェーズ：ミドルウェア機構の再設計（Phase 5 以降）

Phase 5 の IoEngine 再設計タイミングで、Netty の ChannelPipeline に相当する**ミドルウェア差し込み機構**を設計する予定。
ただし Netty の `ChannelHandler` / `ChannelHandlerContext` をそのまま踏襲するのではなく、
KMP・coroutine・`kotlinx.io` に適合した形に再設計する。

設計上の要件：

- 各フェーズで Middleware（ロギング、TLS、圧縮、認証など）を差し込める
- `RawSource` / `RawSink` を ラップする形で実装し、コーデック層を無変更のまま再利用できる
- Netty 的なボイラープレートを排除し、Kotlin らしい DSL or 関数合成で表現する

```kotlin
// 将来イメージ（未確定）
val pipeline = pipeline {
    use(LoggingMiddleware())
    use(TlsMiddleware(tlsConfig))
    use(HttpCodecMiddleware())
    handle(::myApplicationHandler)
}
```

### 4.5 TLS 実装の選定根拠

| 実装 | 採用判断 | 理由 |
|---|---|---|
| **Mbed TLS** | ◎ デフォルト | Apache 2.0、外部依存ゼロ、ノンブロッキング統合が素直、60KB〜 |
| **OS ネイティブ** | ◎ macOS/iOS | 依存ゼロ、証明書ストア OS 管理、iOS は必須 |
| **Rustls** | ○ セキュリティ重視 | メモリ安全、Apache 2.0/MIT だが Rust ツールチェイン必要 |
| OpenSSL | ✗ 採用しない | ライセンス複雑、BIO ブリッジ実装が複雑、重い |
| wolfSSL | △ 避ける | GPLv2 / 商用ライセンス、OSSライブラリ公開時に利用者へ波及 |
| 純 Kotlin | ✗ 非現実的 | 暗号実装の工数・セキュリティリスクが過大 |

> **Mbed TLS の優位点**: `mbedtls_ssl_set_bio()` コールバックモデルは
> OpenSSL の `SSL_ERROR_WANT_READ/WRITE` BIO ステートマシンより EventLoop 統合が素直で実装工数が少ない。

### 4.6 TLS ライブラリ比較表

| 比較軸 | Mbed TLS | OS ネイティブ | Rustls | OpenSSL |
|---|---|---|---|---|
| ライセンス | Apache 2.0 ◎ | OS 依存 ◎ | Apache 2.0/MIT ◎ | OpenSSL+SSLeay △ |
| cinterop 難易度 | 低 ◎ | 中 ○ | 中（FFI 要） ○ | 低（BIO 複雑） △ |
| フットプリント | 小（60KB〜） ◎ | ゼロ ◎ | 中 ○ | 大 △ |
| メモリ安全性 | C（手動） | OS 依存 | Rust ◎ | C（手動） |
| ノンブロッキング統合 | 素直 ◎ | OS 依存 ○ | 素直 ◎ | 複雑（BIO） △ |
| 外部ツールチェイン | 不要 ◎ | 不要 ◎ | Rust 要 △ | 不要 ◎ |

### 4.7 IoBuf とプラガブルアロケータ

> **命名変更**: NativeBuf → IoBuf（PR #143）。パッケージ `.keel.io` → `.keel.buf`。

#### IoBuf interface 設計（PR #141〜#143）

`IoBuf` は commonMain の interface。エンジンモジュールが独自実装を作成可能。

```
commonMain:
  interface IoBuf              ← 公開 API（read/write/retain/release 等）
  internal interface PoolableIoBuf : IoBuf  ← deallocator/nextLink/resetForReuse

nativeMain:
  class NativeIoBuf : IoBuf, PoolableIoBuf, NativePointerAccess
  (nativeHeap.allocArray<ByteVar>)

jvmMain:
  class DirectIoBuf : IoBuf, PoolableIoBuf
  (ByteBuffer.allocateDirect)

jsMain:
  class TypedArrayIoBuf : IoBuf, PoolableIoBuf
  (Int8Array)

engine-io-uring:
  class RingBufferIoBuf : IoBuf, NativePointerAccess
  (ProvidedBufferRing スロット、メモリ非所有)
```

- dual-pointer（readerIndex/writerIndex）、compact() でフラグメンテーション解消
- `PoolableIoBuf` は io-core internal — deallocator/nextLink/resetForReuse をカプセル化
- `NativePointerAccess` は nativeMain の interface — unsafePointer を提供

#### 参照カウントと deallocator

`IoBuf.release()` が refCount=0 になったとき、deallocator callback を呼ぶ。
deallocator が null の場合は `close()` にフォールバック（直接 free）。

```kotlin
// PoolableIoBuf (commonMain, internal)
internal interface PoolableIoBuf : IoBuf {
    var deallocator: ((IoBuf) -> Unit)?
    var nextLink: IoBuf?
    fun resetForReuse()
}
```

- DefaultAllocator: deallocator = null（fallback で close()）
- SlabAllocator: deallocator = ::returnToPool
- TrackingAllocator: delegate の deallocator をラップしてカウント

#### MemoryOwner 抽象の不採用

`MemoryOwner<IoBuf>` ラッパーを検討したが、以下の理由で不採用:
- IoBuf が既に `retain()`/`release()`/`deallocator` で所有権管理を完結
- `owner.value.readByte()` のような間接アクセスがホットパスで不利
- `MemoryOwner<ByteBuffer>` 等の汎用性は keel では不要（IoBuf がプラットフォーム差を吸収済み）
- YAGNI: IoBuf 以外のメモリ所有権管理が必要になった時点で再検討

#### PushChannel（push-model read）

`Channel` が pull-model（`read(buf)`）に対し、`PushChannel` は push-model（`asPushSuspendSource()`）。
エンジン所有バッファを `IoBuf` として直接配送し、`BufferedSuspendSource` push-mode でゼロコピー。

```
Pull model (Channel):     App provides IoBuf → Channel.read(buf) → kernel writes into buf
Push model (PushChannel):  Kernel writes into engine buffer → readOwned() → App receives IoBuf
```

- write パスは変更なし（既にゼロコピー: writev/wrappedBuffer）
- PushChannel は Channel とは別 interface（`read(buf)` は pull 前提のため型レベルで分離）
- write 側 + lifecycle は Channel と重複定義（ChannelBase 抽出は後続 PR で検討）

#### BufferAllocator（プラガブルアロケータ）

`:io-core` に `BufferAllocator` インターフェースを定義。エンジン構築時に `IoEngineConfig` 経由で注入。

```kotlin
interface BufferAllocator {
    fun allocate(capacity: Int): NativeBuf
    fun createForEventLoop(): BufferAllocator = this
}
```

- `createForEventLoop()`: per-EventLoop インスタンスを返す。ステートレス allocator は `this`、プール型は新しいインスタンス（ロック不要）
- Engine が各 EventLoop に対して 1 回呼ぶ: `Array(threads) { allocator.createForEventLoop() }`

#### デフォルト allocator

`IoEngineConfig.allocator` のデフォルトは `defaultAllocator()` (expect/actual):

| プラットフォーム | allocator | 根拠 |
|---|---|---|
| Native (Linux/macOS) | `SlabAllocator()` | per-EventLoop nativeHeap プール |
| JVM | `PooledDirectAllocator()` | per-EventLoop DirectByteBuffer プール |
| JS | `HeapAllocator` | V8 GC 管理、プール不要 |

#### SlabAllocator（Native）

per-EventLoop プール方式。固定サイズ（8KB）のみ。

```kotlin
class SlabAllocator(
    private val bufferSize: Int = 8192,
    private val maxPoolSize: Int = 256,
) : BufferAllocator {
    private val pool = ArrayDeque<NativeBuf>()

    override fun createForEventLoop(): BufferAllocator =
        SlabAllocator(bufferSize, maxPoolSize)

    override fun allocate(capacity: Int): NativeBuf { /* pool hit or new */ }
    private fun returnToPool(buf: NativeBuf) { /* pool or close */ }
}
```

- per-EventLoop プール（ロック完全不要）
- maxPoolSize で上限制御（デフォルト 256）
- プール満杯時は close() で実際に free
- 複数サイズ対応は Phase 7 AdaptiveAllocator で

#### PooledDirectAllocator（JVM）

SlabAllocator と同じパターン。`ByteBuffer.allocateDirect()` は高コスト（JNI + OS mmap）なので再利用効果が大きい。

| 実装 | ターゲット | 特性 |
|---|---|---|
| `SlabAllocator` | Native | per-EventLoop nativeHeap プール |
| `PooledDirectAllocator` | JVM | per-EventLoop DirectByteBuffer プール |
| `HeapAllocator` | 全ターゲット | プールなし、テスト用 |

#### 破棄した設計

- **`BufferAllocator.release(buf)`**: 初期設計では allocator にバッファ解放の責任を持たせていたが、deallocator callback に変更。`buf.release()` に統一することで、どの allocator で生成されたバッファでも統一的に解放できる
- **`expect object PlatformAllocator`**: 初期設計では expect object でプラットフォーム別 allocator を提供する予定だったが、`createForEventLoop()` パターンと `defaultAllocator()` expect fun で代替。object はシングルトンで per-EventLoop 分離ができないため不採用
- **NativeBuf コンストラクタ public**: 初期は public だったが、allocator 経由の生成を強制するため `internal` に変更。deallocator 未設定のバッファが leak するリスクを排除

### 4.8 I/O バッファ最適化方針

#### kotlinx-io の使用範囲を明確化

kotlinx-io はコーデック層専用とし、エンジン層では使用しない：

```
┌─────────────────────────────────────────────────┐
│  コーデック層 (:codec-http, :codec-websocket ...) │
│  → kotlinx-io Buffer / Source / Sink 使用         │
│  → アロケーション頻度: 低（リクエスト単位）       │
├─────────────────────────────────────────────────┤
│  エンジン層 (:engine-*)                           │
│  → keel NativeBuf + BufferAllocator              │
│  → kotlinx-io は使わない                         │
│  → アロケーション頻度: 極高（パケット単位）       │
└─────────────────────────────────────────────────┘
```

コーデック層は「1リクエスト = 1回のパース」粒度なので kotlinx-io の GC 依存でも許容範囲。
問題はエンジン層の受信バッファであり、`NativeBuf + BufferAllocator` で完全制御する。

#### NativeBuf のバルク操作

`writeBytes(src: ByteArray, offset: Int, length: Int)` を提供する（PR #79）。
プラットフォーム最適化されたバルクコピー（Native: `memcpy`、JVM: `ByteBuffer.put`）を使用し、
per-byte `writeByte` ループを置換する。

| 層 | データパス | コピー回数 |
|---|---|---|
| エンジン層 | NativeBuf のポインタ / ByteBuffer を直接 syscall に渡す | 0（ゼロコピー） |
| コーデック層 | kotlinx-io の Source / Sink 経由 | GC 管理（許容） |
| ByteArray → NativeBuf | `writeBytes` による memcpy/arraycopy | 1（Ktor respondFromBytes 等） |

`readBytes(): ByteArray` は追加しない（NativeBuf → ByteArray コピーが必要なユースケースがない）。

**JS 固有の注意**: JS actual は per-byte ループで実装（PR #86）。`Int8Array.set(array, offset)` で
bulk copy が可能だが、Kotlin/JS の `ByteArray` → `Int8Array` 変換にコピーが発生するため
per-byte ループと大差ない。Phase 6 で `ByteArray` を経由しない `Int8Array` 直接操作を検討。

**JVM 固有の注意**: `NativeBuf.clear()` は `readerIndex`/`writerIndex` のリセットに加え、
DirectByteBuffer の `position(0)` / `limit(capacity)` もリセットする。
NioChannel の `flushSingle` が `bb.limit(offset + length)` を設定するため、
`clear()` で limit をリセットしないと次の `put(index, value)` で
`IndexOutOfBoundsException` が発生する（PR #79 で修正）。

#### NWConnection エンジンの dispatch_data_t コピー制限

NWConnection の `nw_connection_receive` は受信データを `dispatch_data_t` として返す。
NativeBuf への書き込み先を指定する receive API が存在しないため、
`dispatch_data_apply` + `memcpy` による NativeBuf へのコピーが不可避である。

| エンジン | read パス | コピー回数 |
|---|---|---|
| kqueue / epoll | POSIX `read(fd)` → NativeBuf.unsafePointer に直接書き込み | 0 |
| NIO | `SocketChannel.read(ByteBuffer)` → NativeBuf.unsafeBuffer に直接書き込み | 0 |
| **NWConnection** | `nw_connection_receive` → `dispatch_data_t` → `memcpy` → NativeBuf | **1** |

実測コスト: 小パケット（1–4 KB）で ~0.1–0.4 us、64 KB で ~1–2 us。
実ワークロードでは無視できるレベルであり、Phase 5 では意図的にこのコピーを許容する。

ゼロコピー化には NativeBuf に MemoryOwner 抽象（`nativeHeap.free` 以外の解放戦略）を
追加する設計変更が必要。io_uring の Fixed Buffers（Phase 6）も同様の抽象を必要とするため、
Phase 6 着手時に統合的に再検討する。

#### バッファサイズ適応計画

Phase 5b では BufferedSuspendSink の BUFFER_SIZE を 8KB 固定で維持する。
Phase 6 で SlabAllocator + codec ゼロコピーと合わせて適応バッファを導入する。

##### TCP バッファサイズの分析

**OS レベルの TCP バッファ設定**:

| パラメータ | macOS | Linux (luna.local) | 意味 |
|-----------|-------|-------------------|------|
| send buffer 初期値 | `net.inet.tcp.sendspace` = 128KB | `tcp_wmem[1]` = 16KB | ソケット作成時の send buffer サイズ |
| send buffer 最大値 | `net.inet.tcp.sendspace` = 128KB | `tcp_wmem[2]` = 4MB | autotuning で到達可能な上限 |
| recv buffer 初期値 | `net.inet.tcp.recvspace` = 131KB | `tcp_rmem[1]` = 128KB | ソケット作成時の recv buffer サイズ |

Linux の `tcp_wmem` は `min init max` の 3 値。初期値 16KB は TCP autotuning の開始値で、
通信が進むと自動的に拡大する。ただし write 直後の初回 flush では 16KB しか使えない。

**send buffer サイズと /large 性能の関係**:

100KB レスポンスを write する際、send buffer に入りきらない分は OP_WRITE suspend が必要。
suspend/resume のたびに EventLoop のフルサイクル（taskQueue → select → processKeys）が走る。

| 環境 | send buffer | BufferedSuspendSink | flush 回数 | OP_WRITE suspend | /large 結果 |
|------|-----------|-------------------|-----------|-----------------|------------|
| macOS | 128KB | 8KB | 13 | 0-1 回 | 25K req/s |
| luna.local | 16KB | 8KB | 13 | 6-7 回 | 8.6K req/s |

**setsockopt による SO_SNDBUF 設定の検討**:

`setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &size)` で send buffer を明示的に設定可能。
ただし以下の理由で Phase 5b では採用しない:

1. **TCP autotuning を無効化する**: Linux では `SO_SNDBUF` を明示設定すると autotuning が
   そのソケットで無効になる。固定値がワークロードに合わない場合、逆に性能低下する
2. **プラットフォーム差異**: macOS は autotuning が弱いため影響が少ないが、Linux では重要
3. **適切な値の決定が困難**: ワークロード（/hello 13B vs /large 100KB）で最適値が異なる
4. **Netty のアプローチ**: Netty は SO_SNDBUF を設定せず、アプリレベルバッファ
   （ChannelOutboundBuffer）で OS の制約を吸収する

**Phase 6 での対応方針**: setsockopt ではなく、Netty と同様にアプリレベルバッファで吸収する。

##### 各レイヤーのバッファサイズ比較

```
                keel (Phase 5b)              Netty
                ──────────────               ─────
Application     respondFromBytes(100KB)      respondFromBytes(100KB)
                      │                            │
BufferedSink    8KB 固定バッファ              ChannelOutboundBuffer
                13回の flushBuffer()          (リンクリスト、上限なし)
                      │                            │
Channel.write   PendingWrite → flush         ctx.write → outbound buffer
                      │                            │
Channel.flush   OS write() 直接              EventLoop 内 tight loop
                send buffer 超過→suspend     send buffer 超過→OP_WRITE 内部待ち
                      │                            │
OS TCP          send buffer (16-128KB)       send buffer (16-128KB)
```

keel は BufferedSuspendSink → Channel.flush → OS write を直列に呼ぶため、
OS send buffer の制約がコルーチン suspend として直接伝播する。
Netty は ChannelOutboundBuffer が OS との間にバッファ層を置くため、
アプリは OS の send buffer サイズに依存しない。

**現状の制約（Phase 5b）**:

| 環境 | TCP send buffer | keel-nio /large | suspend 回数 (推定) |
|------|----------------|----------------|-------------------|
| macOS | 128KB | 25K req/s | 0-1 回 |
| luna.local | 16KB | 8.6K req/s | 6-7 回 |

**Phase 6 で導入する仕組み**:

1. **Read 側: AdaptiveAllocator**
   - Netty の `AdaptiveRecvByteBufAllocator` と同等
   - 前回の read サイズに基づいてバッファサイズを動的調整（1KB-64KB）
   - SlabAllocator のプール管理で拡大・縮小のコストを吸収

2. **Write 側: Write 水位線 + アプリレベルバッファ**
   - Netty の `ChannelOutboundBuffer` と同等の仕組み
   - `write()` でアプリレベルバッファに溜め、`flush()` で OS に書けるだけ書く
   - High/Low water mark で backpressure 制御
   - OS の send buffer 制約（luna.local 16KB）をアプリ層で吸収

3. **BufferedSuspendSink のバッファサイズ**
   - 固定 8KB から設定可能に変更（`IoEngineConfig` に追加）
   - デフォルト値は SlabAllocator のスラブサイズと揃える

適応バッファは SlabAllocator と密接に関連する（バッファ拡大・縮小にプールからの
allocate/release が頻発するため、HeapAllocator では適応のコスト自体がボトルネック）。
Phase 6 の SlabAllocator 完成後に着手する。

##### GC noop ベンチマーク（GC コストの分離測定）

`kotlin.native.binary.gc=noop` でビルドし、GC を完全に無効化してベンチマーク。
GC オーバーヘッドが性能の何 % を占めるかを分離測定する。

macOS M1 — `wrk -t4 -c100 -d10s /hello`

| エンジン | 通常 GC | GC noop | 差分 | 分析 |
|---------|--------|---------|------|------|
| keel-kqueue | 93K | 87K | -6% | GC はボトルネックではない。noop でヒープ膨張→alloc 遅延 |
| keel-nwconnection | 47K | 56K | +19% | GC が ~16% の性能を消費。push→pull ブリッジの短命オブジェクトが原因 |
| ktor-cio | 7K | 8K | +14% | 参考値 |

**結論**:
- keel-kqueue のボトルネックは GC ではない。93K → Rust 162K の差は Ktor pipeline +
  coroutine suspend/resume + codec-http の String 生成が主因
- keel-nwconnection は GC コストが 16%。SlabAllocator + alloc 削減で改善余地あり
- GC noop で kqueue が低下する現象は、GC がない → メモリが解放されない →
  nativeHeap の断片化 → alloc 自体が遅くなる、が原因

#### リソースリーク検出ツール体系（Phase 6a 確立）

3 層の自動検出 + 2 種の手動プロファイリングで構成。

**自動検出（CI/テスト）:**

| 層 | ツール | 検出対象 | 導入 PR |
|---|--------|---------|--------|
| 1 | detekt カスタムルール | 静的パターン（NativeBuf/Arena/StableRef の解放漏れ） | #93 |
| 2 | TrackingAllocator | NativeBuf allocate/release 対称性（ランタイム） | #91, #92, #94, #95, #97, #98 |
| 3 | GC.lastGCInfo | ヒープ全体の成長（coroutine/cinterop リーク） | #96 |

**手動プロファイリング（deep-review 時）:**

| ツール | プラットフォーム | 検出対象 |
|--------|--------------|---------|
| JFR | JVM | alloc サイト + スタックトレース（全 Kotlin/Java オブジェクト） |
| `-Xruntime-logs=gc=info` | Native | GC 頻度、pause、swept/kept 傾向、stable references |

**Kotlin/Native の制約:**
Kotlin/Native GC は独自ヒープ管理（malloc 非経由）のため、外部ツール
（macOS heap, Instruments, Linux perf）から Kotlin オブジェクトの alloc サイトは不可視。
JVM の JFR に相当する Kotlin/Native 用プロファイリング API は存在しない（2026-03 時点）。
alloc サイト分析は TrackingAllocator（NativeBuf のみ）+ GC ログ（全体傾向）で代替。

**GC ログ分析の判定基準:**

| 指標 | 正常 | リーク兆候 |
|------|------|----------|
| Heap after GC | 安定（±10%） | 単調増加 |
| Kept objects | 安定 | 単調増加 |
| Stable references | 一定（±5） | 増加（StableRef dispose 漏れ） |
| GC epoch 時間 | 安定 | 増加（マーク対象増） |
| Mutators pause | < 1ms | > 10ms（GC 圧力大） |

**GC ログ比較実績（kqueue vs nwconnection）:**

| 項目 | kqueue | nwconnection |
|------|--------|-------------|
| GC epoch 時間 | ~2.4ms | ~5-24ms |
| Kept objects | ~12K | ~118K（10x） |
| Heap after GC | 128MB 安定 | 155-216MB 成長 |
| Stable references | 69 | 103 |

nwconnection のヒープ不安定は dispatch callback + CallbackContext + StableRef の
中間状態が GC 時点で多数生存するため。構造的制約であり、リークではない。
Phase 9 の PushChannel でブリッジ排除により解消予定。

**プラットフォーム別 alloc サイト分析ツール:**

| プラットフォーム | ツール | Kotlin オブジェクト | C/OS オブジェクト |
|--------------|--------|-----------------|----------------|
| JVM | JFR (`-XX:StartFlightRecording`) | 全クラス + スタックトレース | — |
| Native macOS | `heap` + MallocStackLogging | 不可視（GC 管理） | Network framework 等 |
| Native macOS | Instruments (Allocations) | 不可視（SIP 制約 + GC 管理） | Network framework 等 |
| Native macOS | `-Xruntime-logs=gc=info` | 全体 swept/kept/heap のみ | — |
| JS/Node.js | `--inspect` + Chrome DevTools | V8 Heap snapshot | — |
| JS/Node.js | `--heap-prof` | V8 ヒーププロファイル | — |
| JS/Node.js | `--prof` | V8 CPU プロファイル | — |

Kotlin/Native の alloc サイト分析は JVM の JFR に相当する機能がなく、
TrackingAllocator（NativeBuf のみ）+ GC ログ（全体傾向）で代替する。
Node.js は V8 の DevTools/heap-prof で分析可能だが、Kotlin/JS の場合
`./gradlew jsNodeRun` 経由のため Node.js 起動引数の制御に Gradle 設定が必要。
ktor-engine JS 対応が未実装のため、現時点では実用性は低い。

#### NativeBuf コンストラクタの可視性

現状 `NativeBuf(capacity)` は public だが、これは Phase 5 Step 1 の段階で HeapAllocator しか存在しないため。
SlabAllocator / PooledDirectAllocator が導入される段階でコンストラクタを `internal` にし、
生成を `BufferAllocator.allocate()` 経由に限定する。

理由:
- プール型アロケータでは `release()` 時に free ではなくプールに返却する必要があり、バッファが返却先を知る必要がある
- Slab からの切り出しでは `NativeBuf(capacity)` の意味自体が変わる（新規 alloc ではなくスラブ内オフセット）
- アロケータを経由しない生成はリーク検知・メトリクス収集の抜け穴になる

変更タイミング: IoEngine 再設計（Phase 5 Step 2）

#### `:core` モジュール分割: `:io-core` 切り出し（PR #77 で実施済み）

`:core` が持つ 4 つの責務（バッファ/メモリ、suspend I/O ストリーム、エンジンインターフェース、
設定/アドレス）を単一責務の原則に基づき分割した。

```
分割後:
  codec-http ──→ io-core ←── core ←── engine-*    codec-http は I/O プリミティブのみに依存
```

**`:io-core` モジュール** (`io.github.fukusaka.keel.io`): NativeBuf, BufferAllocator,
HeapAllocator, SuspendSource/Sink, BufferedSuspendSource/Sink。
依存は `kotlinx-coroutines-core` のみ。

**`:core` モジュール（縮小）** (`io.github.fukusaka.keel.core`): IoEngine, Channel,
ServerChannel, SocketAddress, IoEngineConfig, SuspendChannelSource/Sink。
`:io-core` に `api()` で依存。

**バッファ/メモリと suspend I/O ストリームは分割しない**（`:buf` + `:io-core` にしない）:
SuspendSource/Sink のシグネチャが `suspend fun read(buf: NativeBuf): Int` であり
NativeBuf に直接依存する。「NativeBuf ベースのゼロコピー I/O」という単一の設計意図で
結ばれており、分割しても NativeBuf のみに依存するモジュールが現時点で存在しない（YAGNI）。
kotlinx-io も `Buffer` + `Source` + `Sink` を単一モジュール（`kotlinx-io-core`）に配置している。

**codec-http の suspend / non-suspend 2 系統は維持する**:
- non-suspend 版（kotlinx-io `Source`/`Sink`）: テスト・スタンドアロン利用向け
- suspend 版（`BufferedSuspendSource`/`BufferedSuspendSink`）: 実運用パス（ゼロコピー）
- suspend 版が codec-http の `:io-core` 依存の唯一の理由
- keel の suspend I/O 設計（bulk suspend + 同期バッファ読み取り）は kotlinx-io の
  Async API 提案（[#163](https://github.com/Kotlin/kotlinx-io/issues/163)）と方向性が一致

#### エンジン別の主要最適化施策

| 施策 | 対象エンジン | フェーズ | 効果 |
|---|---|---|---|
| Gather Write（`writev`） | epoll / kqueue / NIO | Phase 5 | syscall 削減 |
| Scatter Read（`readv`） | epoll / kqueue | Phase 5 | memcpy 削減 |
| per-CPU NativeBuf プール | epoll / kqueue | Phase 5 | GC 圧力ゼロ |
| sendfile / `F_NOCACHE` | epoll / kqueue | Phase 5 | ファイル送信ゼロコピー |
| DirectByteBuffer プール | NIO | Phase 5 | カーネルコピー削減 |
| Hot-path alloc 削減 | 全エンジン | Phase 6b | writeAscii, StringBuilder reuse 等で per-request alloc 削減。epoll +7.7% |
| io_uring `IORING_OP_SEND_ZC` | io_uring | Phase 6 | 送信バッファコピーゼロ |
| io_uring Fixed Buffers | io_uring | Phase 6 | バッファ登録コスト削減 |
| io_uring Multi-shot receive | io_uring | Phase 6 | 受信 SQE 再発行コスト削減 |

> kotlinx-io を全面置き換えするコストに見合う場面は限定的。
> io_uring なしでも `writev` + DirectByteBuffer プールで Netty の NIO モードに匹敵するスループットは出せる。

### 4.9 EventLoop（スレッド固定 I/O ループ）

Netty の最重要設計原則「1 スレッド = 1 EventLoop = ロック不要」を踏襲。

```
linuxX64Main: epoll + eventfd で wakeup
macosX64Main: kqueue + pipe で wakeup
jvmMain:      java.nio.Selector + wakeup()
jsMain:       Node.js EventLoop に委譲（run() 不要、コールバック登録モデル）
```

### 4.10 epoll vs io_uring の使い分け

| ワークロード | 推奨エンジン | 理由 |
|---|---|---|
| ping-pong（小パケット多数） | io_uring | 完了型で syscall を削減 |
| ストリーミング（大容量転送） | epoll | 準備通知型の方が効率的な場合あり |
| 汎用 | epoll（デフォルト） | 安定性・実績 |

> **aio（linux AIO）は採用しない**。O_DIRECT ファイル専用でネットワーク I/O を扱えないため。

### 4.11 io_uring ハイブリッド I/O モード

io_uring の SQE/CQE 間接化は小レスポンス（`/hello` 13B）で epoll に対して -3.8% のオーバーヘッドを生む。
操作ごとに direct syscall と CQE を使い分ける方式 D を採用:

```
enum IoMode { CQE, FALLBACK_CQE }
fun interface IoModeSelector { fun select(stats: ConnectionStats): IoMode }
```

| 操作 | モード | デフォルト | 理由 |
|---|---|---|---|
| write (flush) | ランタイム選択 | `eagainThreshold(0.1)` | direct +4.5% 実証。EAGAIN 頻発で CQE 自動切替 |
| read (pull) | ランタイム選択 | `CQE` | push-mode multishot recv が primary |
| accept | 常に multishot CQE | — | 常に有利 |
| connect | 常に CQE | — | direct は完了待ち不可 |
| shutdown/close | 常に direct | — | 同期操作 |

**FALLBACK_CQE**: direct syscall を最初に試行。EAGAIN 発生時のみ io_uring SQE にフォールバック。
**eagainThreshold**: EAGAIN 率が閾値を超えたら CQE モードに動的切替。接続ごとの `ConnectionStats` で統計管理。

---

## 5. Ktor / gRPC との関係

### Ktor との関係

```kotlin
// fukusakaor-engine の使用イメージ
embeddedServer(KeelEngine, port = 8080) {
    routing {
        get("/") { call.respondText("Hello from keel!") }
    }
}.start(wait = true)
```

- Ktor の `ApplicationEngine` インターフェースを実装するアダプタ
- keel の HTTP/1.1 コーデックを Ktor の `ApplicationCall` に変換
- Ktor CIO エンジンの Native TLS は現時点で未実装（`TLS sessions are not supported on Native platform`）
- keel がこのギャップを埋める

### gRPC KMP との関係

既存の状況：

| ライブラリ | 状況 |
|---|---|
| `grpc-kotlin`（公式） | JVM のみ、Native 対応なし |
| `GRPC-Kotlin-Multiplatform` | KMP 対応だが iOS に Rust が必要 |
| `kotlinx.rpc` | gRPC は実験的、独自プロトコル（kRPC）が主体 |

keel の gRPC は「**標準 gRPC との完全互換**」を目標として差別化。
Protobuf エンコード/デコードは pbandk または Wire に委譲（自前実装は不要）。

---

## 6. 実装フェーズ計画

### フェーズ依存関係

```
Phase 0 → Phase 1 → Phase 2 ──────────→ Phase 4 → Phase 5 → Phase 6+
                         └──→ Phase 3 ─┘
                              (並行可)
```

### Phase 0〜5（コア実装）

| Phase | 内容 | 成果物 | 状態 |
|---|---|---|---|
| 0 | プロジェクト骨格（Gradle マルチプロジェクト、KMP 設定、expect/actual スタブ） | `./gradlew :core:compileKotlinLinuxX64` が通る | ✅ PR #1 |
| 1 | kqueue エンジン実装 | macosArm64/macosX64 で TCP echo | ✅ PR #6 |
| 2 | epoll エンジン実装 | linuxX64/Arm64 で TCP echo | ✅ PR #7 |
| 3 | JVM（NIO / Netty）/ Node.js / NWConnection エンジン実装 | 全エンジンで TCP echo | ✅ PR #8〜#11 |
| 4A | `:codec-http`（HTTP/1.1 パーサー / ライター） | RFC 7230/7231 準拠、99テスト PASS | ✅ PR #13 |
| 4B | `:codec-http2`（HTTP/2 + HPACK） | HTTP/2 フレームの解析・生成 | ⏸ Phase 7 頭 |
| 4C | `:codec-websocket`（WebSocket フレーミング） | RFC 6455 準拠 | ✅ PR #14 |
| 4.5 | OSS 公開前整備（LICENSE / README / KDoc / Dokka / Docusaurus） | — | ✅ PR #15/#16 |
| 5 | IoEngine 再設計 + BufferAllocator + エンジン最適化 + ミドルウェア + Ktor アダプタ | `embeddedServer(KeelEngine)` が動く | 🔨 進行中 |

### Phase 6〜10（拡張）

| Phase | 内容 | 成果物 | 状態 |
|---|---|---|---|
| 6a | 品質改善（コードレビュー + テスト + KDoc） | 本番品質の基盤 | 🔲 |
| 6b | SlabAllocator + codec-http ゼロコピー | 性能基盤 → v0.3.0 | 🔲 |
| 7 | TLS (Mbed TLS) + 適応バッファ | HTTPS 対応 | 🔲 |
| 8 | io_uring エンジン | Linux 高性能エンジン | 🔲 |
| 9 | Push Channel + :server + マルチプロトコル | Ktor 非依存 API + MQTT 等 | 🔲 |
| 10 | gRPC コード生成 + QUIC（HTTP/3） | .proto → KMP コード生成 | 🔲 |

工数見積もりは plan.md に記載。

---

## 7. 主要な難所と対策

| 難所 | 内容 | 対策 |
|---|---|---|
| EAGAIN ハンドリング | ノンブロッキングソケットの再試行ループで EventLoop が詰まる | EAGAIN を受けたら「次の epoll_wait まで待つ」に切り替える |
| 参照カウントの安全性 | 複数スレッドが同じ NativeBuf にアクセスするとレースが起きる | `AtomicInt.addAndGet(-1)` が 0 になった瞬間だけ free |
| Kotlin/Native スレッドモデル | 旧来の freeze 制約、新メモリモデルとの整合 | `Worker` と `newSingleThreadContext` の使い分け |
| wakeup 機構 | `epoll_wait` ブロック中に外部タスクを投入する仕組み | Linux は `eventfd`、macOS/BSD は `pipe` で実装 |
| TLS + ノンブロッキング統合 | OpenSSL は BIO ステートマシンが複雑 | Mbed TLS の `mbedtls_ssl_set_bio()` コールバックで解決 |
| iOS の TLS 制約 | App Sandbox で raw TLS 不可 | Network.framework TLS（SystemTlsEngine）で固定 |
| Ktor アダプタの仕様追従 | Ktor のバージョンで `ApplicationEngine` API が変わる | Phase 5 開始前に最新仕様を必ず確認 |
| Node.js の EventLoop 主権 | `epoll_wait` ではなくコールバック登録モデルに変わる | `Selector.select()` をブロッキングではなくイベント登録として実装 |

---

## 8. 現実的な価値評価

### 意味があるシナリオ

| シナリオ | 評価 |
|---|---|
| プロダクション投入（現時点） | 厳しい。需要・エコシステム両面で未成熟 |
| KMP Native サーバー需要の顕在化 | これが来れば明確に意義を持つ |
| Ktor の Native エンジンとして採用 | 最も社会的還元が大きい |
| Ktor Native TLS の空白を埋める | 現時点で誰も解決していない実用的なギャップ |
| 技術的挑戦・Zenn 記事ネタ | Kotlin/Native の限界を測る意義深い試み |

### 競合との棲み分け

```
REST API / マイクロサービス    → Ktor 一択
ゲームサーバー / 独自プロトコル → keel が適合
エッジ / FaaS                 → keel（起動時間・メモリ優位）
KMP クライアント              → Ktor Client が楽
IoT / 組み込み Linux          → keel の最強ユースケース
```

---

## 9. Gradle モジュール構成（最終形）

```
keel/
├── logging                   # commonMain — Logger / LoggerFactory / LogLevel（外部依存ゼロ）
├── io                        # commonMain — NativeBuf / BufferAllocator / SuspendSource・Sink
├── core                      # commonMain — IoEngine / Channel / ServerChannel（:io, :logging に依存）
│
├── # I/O エンジン群
├── engine-epoll              # linuxX64 / linuxArm64（✅ 完了）
├── engine-io-uring           # linuxX64 / linuxArm64（Linux 5.1+、Phase 6）
├── engine-epoll-udp          # linuxX64 / linuxArm64（UDP、Phase 7）
├── engine-kqueue             # macosX64 / macosArm64（✅ 完了）
├── engine-kqueue-udp         # macosX64 / macosArm64（UDP、Phase 7）
├── engine-nio                # JVM 自前実装（✅ 完了）
├── engine-nio-udp            # JVM（DatagramChannel、Phase 7）
├── engine-netty              # JVM Netty 委譲（✅ 完了）
├── engine-nodejs             # JS nodejs() ターゲット（✅ 完了）
├── engine-nwconnection       # macosArm64 / macosX64（✅ 完了）
│
├── # TLS エンジン群（未着手）
├── tls-mbedtls               # linuxX64/Arm64、macosX64/Arm64（Apache 2.0、Phase 6）
├── tls-system                # macosX64/Arm64（SecureTransport）、iOS（必須）、mingwX64（SChannel）
├── tls-rustls                # linuxX64/Arm64（Rustls FFI、Rust ツールチェイン要）
├── tls-jsse                  # JVM（Conscrypt 差し替え対応）
├── tls-nodejs                # JS nodejs()（node:tls）
│
├── # コーデック群 — TCP（全ターゲット commonMain）
├── codec-http                # HTTP/1.1（✅ 完了 — RFC 7230/7231/5789/6265）
├── codec-websocket           # WebSocket（RFC 6455、✅ 完了）
├── codec-http2               # HTTP/2 + HPACK（Phase 7 頭、gRPC 前）
├── codec-mqtt                # MQTT 3.1.1 / 5.0（Phase 7）
├── codec-redis               # Redis RESP3（Phase 7）
├── codec-amqp                # AMQP 0-9-1（Phase 7）
├── codec-grpc                # gRPC フレーミング（Phase 7）
├── codec-protobuf            # pbandk / Wire ラップ（Phase 7）
│
├── # コーデック群 — UDP（UDP エンジン追加後）
├── codec-coap                # CoAP（RFC 7252、IoT、Phase 7）
├── codec-dns                 # DNS（RFC 1035、Phase 7）
│
├── # 応用層（未着手）
├── server                    # KeelHttpServer / KeelWsServer（Ktor なし軽量版、IoT 向け、Phase 7）
├── client                    # KeelMqttClient / KeelCoapClient / KeelRedisClient（非 HTTP のみ、Phase 7）
│                             # ※ HTTP クライアントは Ktor HttpClient + :ktor-engine に委譲
├── ktor-engine               # Ktor ApplicationEngine アダプタ（Phase 5）
├── grpc-core                 # commonMain — Unary / Streaming RPC（Phase 7）
├── grpc-stub-generator       # Gradle Plugin — .proto → KMP コード生成（Phase 8）
│
└── benchmark                 # linuxX64 — wrk / h2load 比較（Phase 6+）
```

---

## 10. 参考情報

### 関連ライブラリ

| ライブラリ | 役割 | URL |
|---|---|---|
| Netty | JVM の参考実装 | https://netty.io |
| Ktor | KMP フレームワーク（統合先） | https://ktor.io |
| Mbed TLS | TLS 実装（推奨、Apache 2.0） | https://github.com/Mbed-TLS/mbedtls |
| Rustls | TLS 実装（Rust、セキュリティ重視） | https://github.com/rustls/rustls |
| rsocket-kotlin | KMP RSocket 実装（設計参考） | https://github.com/rsocket/rsocket-kotlin |
| kotlinx-io | KMP 基本 I/O プリミティブ | https://github.com/Kotlin/kotlinx-io |
| kotlinx-nodejs | Node.js API external 宣言 | https://github.com/Kotlin/kotlinx-nodejs |
| pbandk | KMP Protobuf 実装 | https://github.com/streem/pbandk |
| liburing | io_uring ユーザー空間ライブラリ | https://github.com/axboe/liburing |
| kotlinx.rpc | JetBrains 製 KMP RPC | https://github.com/Kotlin/kotlinx-rpc |

---

## 11. HTTP クライアントパイプライン層の検討（2026-03-18）

### 背景

KMP で OkHttp の Interceptor チェーンに相当する機能を提供するパイプライン層を、keel の上位レイヤーとして検討した。
OkHttp は JVM 専用、Ktor プラグインは Inbound/Outbound の型分離がない、という課題意識が出発点。

### 提案された設計

```
[パイプライン層] — OutboundHandler / InboundHandler / DuplexHandler
       ↓ engine adapter
[Ktor HttpClient or keel IoEngine]
       ↓
[keel トランスポート層] — epoll / kqueue / NIO 等
```

主な機能：
- Netty 的な Inbound/Outbound 方向分離（型レベル）
- `addBefore` / `addAfter` による位置指定
- OkHttp 的な `newBuilder()` パターン（派生クライアント生成）
- `Authenticator` による 401 自動リトライ

### 分析結果

**keel 側で必要な対応（パイプライン有無に関わらず有用）：**

| 機能 | Phase | 理由 |
|---|---|---|
| ストリーミングボディ | 5 | 現状 `:codec-http` の `body: ByteArray?` は大きなレスポンスでメモリ爆発。`RawSource` ベースに変更すべき |
| TLS | 6 | HTTP クライアントには必須。`connect()` → TLS ハンドシェイク → 暗号化ストリームを返せる必要がある |

**パイプライン層を作る場合にのみ必要：**

| 機能 | 理由 |
|---|---|
| コネクションプール | HTTP Keep-Alive / HTTP/2 多重化。Ktor に委譲するなら不要 |
| DNS 解決の制御 | プール管理にはホスト解決の制御が要る |
| HTTP クライアントセマンティクス | リダイレクト・Cookie・Content-Encoding。Ktor に委譲するかの分岐点 |

**パイプライン設計への懸念：**

1. Ktor 3.x の標準プラグイン（`HttpRequestRetry` / `Auth` 等）で Phase 1-2 の大半がカバー済み
2. Inbound/Outbound 分離は HTTP リクエスト/レスポンス変換では過剰な可能性（OkHttp は `Interceptor` 一本で成功）
3. 提案の `body: ByteArray?` はストリーミング非対応。`RawSource` ベースにすべき

### 現時点の方針

- パイプライン層の実装判断は Phase 5 完了後に延期
- keel 側では Phase 5 でストリーミングボディ対応、Phase 6 で TLS を予定通り実装
- HTTP クライアント機能（Cookie / キャッシュ / リダイレクト / 認証）は引き続き Ktor HttpClient + `:ktor-engine` に委譲する方針を維持

### KMP ターゲット公式ドキュメント

- Kotlin/Native ターゲットサポート: https://kotlinlang.org/docs/native-target-support.html
- KMP サポートプラットフォーム: https://kotlinlang.org/docs/multiplatform/supported-platforms.html

---

## 12. Node.js エンジンの asSource/asSink 方針（2026-03-22）

### 問題

kotlinx-io の `RawSource.readAtMostTo()` / `RawSink.write()` は non-suspend 関数。
他エンジンでは内部で `readBlocking()` を呼んでスレッドをブロックして同期的に値を返すが、
JS はシングルスレッドなのでブロックできない。

### 検討した選択肢

| 選択肢 | 実現性 | 判断 |
|---|---|---|
| A. 現状維持（UnsupportedOperationException） | 即座 | **Phase 5b で採用** |
| B. kotlinx-io の suspend 対応を待つ | 不確定 | 依存できない |
| C. 独自 SuspendSource/SuspendSink を `:core` に追加 | 可能（工数大） | Phase 6 以降 |
| D. JS Promise 同期解決 | 不可能 | — |

### Phase 5b の方針: A（現状維持）

- Node.js エンジンは Phase (a) 時点で既に非同期（`suspendCoroutine` ベース）。Phase 5b での変更なし
- `asSource()` / `asSink()` は `UnsupportedOperationException` のまま
- Ktor Server は JS ターゲット非対応のため、Ktor + Node.js の組み合わせは発生しない
- Node.js で keel を使う場合は `Channel.read()` / `Channel.write()` を直接利用する

### Phase 6 以降の計画: SuspendSource / SuspendSink

io_uring の completion-based I/O と合わせて、suspend 版の kotlinx-io ブリッジを `:core` に導入する。

```kotlin
// core/commonMain（Phase 6 以降）
interface SuspendSource {
    suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long
    fun close()
}

interface SuspendSink {
    suspend fun write(source: Buffer, byteCount: Long)
    suspend fun flush()
    fun close()
}
```

導入の動機:
- **Node.js**: `readAtMostTo` が suspend であれば `suspendCoroutine` で自然に実装できる
- **io_uring**: completion-based I/O は read/write 自体が非同期。non-suspend の `RawSource` では
  中間バッファ + ブロッキング待ちが必要になり、io_uring の性能を活かせない
- **全エンジン統一**: pull model の suspend read/write を全エンジンで統一的に提供できる

影響範囲:
- コーデック層（`codec-http`, `codec-websocket`）を `SuspendSource` / `SuspendSink` ベースに書き換え
- 既存の `RawSource` / `RawSink` 版はアダプタ経由で互換維持（`SuspendSource` → `RawSource` は `runBlocking` ラップ）
- Channel インターフェースに `asSuspendSource()` / `asSuspendSink()` を追加

---

## 13. Netty auto-read=false と pull model 統合（2026-03-22）

### 背景

Netty はデフォルト `autoRead = true`。カーネル受信バッファにデータが到着すると、
ユーザーコードの `read()` 呼び出しに関係なく Netty EventLoop が自動的に読み取り、
`channelRead(ByteBuf)` コールバックを発火する（push model）。

Phase (a) では `LinkedBlockingQueue` で push → pull 変換しているが、
バックプレッシャーなし・スレッドブロック・メモリ滞留の問題がある。

### Phase (b) の方針: auto-read=false

```kotlin
channel.config().setAutoRead(false)
```

auto-read を無効にすると、Netty は OP_READ を Selector に登録しない。
keel が `nettyChannel.read()` を明示的に呼ぶまでデータの読み取りは発生しない。

```
keel の read(buf):
  suspendCancellableCoroutine { cont → pendingReadCont = cont }
  nettyChannel.read()  ← Netty に「1回だけ読んでくれ」と要求

Netty EventLoop:
  OP_READ を Selector に登録 → データ到着 → channelRead(ByteBuf) 発火
  → pendingReadCont.resume(byteBuf) ← coroutine 再開
```

### 効果

| 効果 | 説明 |
|---|---|
| pull model 実現 | `read()` を呼んだときだけ読む。Channel 契約に合致 |
| バックプレッシャー | keel が read() を呼ばない限り Netty は読まない。TCP ウィンドウで自然な流量制御 |
| LinkedBlockingQueue 廃止 | `pendingReadCont` 1つで直接 resume |
| スレッドブロック廃止 | `poll(5s)` → `suspendCancellableCoroutine` |
| メモリ効率 | ByteBuf の readQueue 内滞留がなくなる |

### `channel.read()` の 1回読み切りセマンティクス

auto-read=false で `channel.read()` を呼ぶと:

1. OP_READ を Selector に登録
2. 1回の read ループを実行（`RecvByteBufAllocator` が制御）
3. `channelReadComplete` で OP_READ を解除

`channel.read()` 1回 = `channelRead` 1回以上 + `channelReadComplete` 1回。
keel の `read()` 1回に対して Netty の read が 1回実行される 1:1 対応が成立する。

### 設計上の注意点

- **複数 channelRead**: 1回の read ループで `channelRead` が複数回呼ばれる場合がある。
  最初の `channelRead` で continuation を resume し、残りは内部バッファに保持する
- **channelWritabilityChanged**: write 時に Netty のバッファが溢れた場合の flow control。
  `isWritable == false` なら suspend し、`channelWritabilityChanged` で resume する
- **keep-alive との連携**: keep-alive 実装時に、同一 Channel 上で複数リクエストを
  処理する際の read ループ制御を再検討する

---

## 14. Connection: close 時の TIME_WAIT ポート枯渇（2026-03-22）

### 問題

Connection: close で毎リクエスト新規 TCP 接続を作る場合、サーバーが先に FIN を送ると
サーバー側に TIME_WAIT が蓄積する。macOS の MSL=15s（TIME_WAIT=30s）で、
2,000 req/s の場合 60,000 ソケットが TIME_WAIT に入りエフェメラルポートが枯渇する。

Phase (b) Netty 非同期化後のベンチマークで顕在化:
- 4t/100c で 2,153 req/s を記録した後、wrk が `connect refused` になる

### 検討した対策

| 対策 | 効果 | 副作用 | 判断 |
|---|---|---|---|
| Keep-alive | 根本解決。接続を再利用 | なし | **Phase 5b PR F で対応** |
| SO_LINGER(0) | RST で即座にソケット解放 | 送信中バッファ破棄のリスク | ベンチマーク限定なら有効 |
| クライアントに先に閉じさせる | TIME_WAIT をクライアント側に移動 | Connection: close と矛盾 | 不適切 |
| SO_REUSEADDR / SO_REUSEPORT | listen ポート再利用 | エフェメラルポート枯渇には無効 | 既に設定済み |
| OS の tcp.msl 短縮 | TIME_WAIT 時間を短縮 | OS グローバル設定 | ベンチマーク環境のみ |

### 結論

エンジン側で対応可能な唯一の根本解決は **HTTP keep-alive**。
SO_LINGER(0) は本番コードに入れるべきでない。ベンチマーク環境では
`sysctl net.inet.tcp.msl=1000` の一時設定で回避する。

### NettyServerChannel のスレッドセーフティ

Phase (b) で `LinkedBlockingQueue` を `ArrayDeque` + `suspendCancellableCoroutine` に
置換した際、`onNewChannel()`（Netty boss EventLoop スレッド）と `accept()`（coroutine
スレッド）の並行アクセスで NullPointerException が発生した。

coroutine の `Mutex` は `onNewChannel` が非 coroutine コンテキストから呼ばれるため使用不可
（`Mutex.lock()` は suspend fun）。`synchronized` で保護し、`@Volatile` は不要化。
accept は TCP 接続確立ごとの低頻度操作のため、uncontended lock のコスト（~20-50ns）は
ベンチマークに影響しない。


## 15. ログ設計（2026-03-27）

### 方針

rsocket-kotlin 方式: 独自軽量 interface、外部依存ゼロ、デフォルト無効。

```
logging          ← 外部依存なし
io-core          ← kotlinx-coroutines
  ↑
core             ← api(io-core), api(logging)
  ↑
engine-*         ← implementation(core)  ※ logging は推移的に使える
ktor-engine      ← implementation(core), implementation(codec-http)
```

### 不採用の選択肢

| 選択肢 | 不採用理由 |
|--------|-----------|
| Kermit (`kermit-core`) | transitive 依存をユーザーに強制、バージョン追従が必要 |
| Ktor 方式 (`expect/actual` + SLF4J typealias) | JVM に SLF4J 依存必須、フレームワーク向け |
| `kotlinx.io.IOException` ベース | io-core から kotlinx-io 依存を再導入（PR #66 で削除済み） |
| io-core / core に同居 | logging は I/O プリミティブでもエンジン抽象でもない。独立モジュールが適切 |

### API

- `LogLevel` enum: TRACE, DEBUG, INFO, WARN, ERROR
- `Logger` interface + inline 拡張関数（isLoggable ガードでゼロコスト）
- `LoggerFactory` fun interface + `NoopLoggerFactory`（デフォルト）
- `PrintLogger` — println ベース（開発用）
- `IoEngineConfig.loggerFactory` で設定
- ktor-engine: `KtorLoggerAdapter` で Ktor Logger をブリッジ

---

## 16. perf 分析結果と最適化方針（2026-03-27）

### 計測環境

luna.local (Ryzen 9 9950X3D, 32 cores) で keel-nio (JVM) + Ktor を `perf stat` / `perf record` で分析。
wrk 4t/100c/10s `/hello` エンドポイント。

### 主要ボトルネック

```
CPU 使用率内訳（perf record）:
  GC sweep:           7.79%
  GC alloc (TLAB):    6.90%
  GC 合計:           14.7%

スレッド数:           ~70 threads
  Dispatchers.Default: 32 threads (= CPU cores)
  EventLoop:           ~16 threads
  その他:              ~16 threads (Netty worker, GC 等)

perf stat:
  context-switches:    32K/sec
  cpu-migrations:      12K/sec
  insn per cycle:      0.83 (poor pipeline utilization)
```

### 分析

1. **GC が CPU の 14.7% を占有** — sweep (7.79%) + alloc (6.90%)
2. **70 スレッドは過多** — 32 core マシンで 70 スレッドはコンテキストスイッチの原因
3. **context-switches 32K/sec** — スレッド間切り替えコストが大きい
4. **cpu-migrations 12K/sec** — キャッシュ無効化による性能低下
5. **insn per cycle 0.83** — パイプライン効率が悪い（理想は 2.0+）

### codec ゼロコピーの効果測定

| 方式 | GC alloc | スループット | 判定 |
|------|----------|-------------|------|
| 方式1: BufSlice.toAsciiString() 遅延変換 | 6.90% → 6.06% | 変化なし | 効果不十分 |
| 方式2: BufSlice のまま伝搬 | — | BufSlice ライフタイム問題で断念 | — |

方式1 は alloc を 12% 削減したが、GC 全体 (14.7%) の中の一部であり、スループット向上に至らなかった。
方式2 は BufSlice が参照する NativeBuf のライフサイクルと Ktor pipeline の非同期処理が衝突し、
安全に実装できないため断念。

### 最適化の優先順位

```
効果の大きい順:
1. スレッドモデル最適化 — Dispatchers.Default の関与を削減
   → context-switches/cpu-migrations の大幅削減が見込める
   → Ktor pipeline が Dispatchers.Default を使用するのが根本原因
2. Phase 9 :server — Ktor pipeline を排除し、EventLoop 上で直接処理
   → スレッドモデル + ゼロコピーの両方を根本解決
3. codec ゼロコピー — 単体では効果が限定的
   → :server と組み合わせて初めて効果を発揮
```

### 結論

codec-http ゼロコピーは単体での性能改善効果が限定的。
スレッドモデル（Dispatchers.Default の過剰な関与）が最大のボトルネックであり、
Phase 9 の `:server` モジュールがスレッドモデルとゼロコピーの両方を根本解決する。
codec ゼロコピーは `:server` 実装時に BufSlice ベースで自然に実現される。

---

## 17. EventLoop dispatch モデル（2026-03-27）

### 概要

Ktor pipeline の実行スレッドを Dispatchers.Default（ForkJoinPool）から EventLoop スレッドに変更する方式を検証。
Netty はデフォルトで全ハンドラを EventLoop 上で実行しており、ktor-netty も同様にパイプラインを EventLoop で処理する。

### 検証結果

#### Native エンジン（kqueue/epoll）— 大幅改善

| エンジン | 変更前 | 変更後 | 改善率 |
|---|---|---|---|
| macOS kqueue | 89K | 112K | +26% |
| luna.local epoll | 400K | 533K | +33% |

perf stat 比較（luna.local epoll, 4t/100c/10s /hello）:

| メトリクス | 変更前 | 変更後 | 改善率 |
|---|---|---|---|
| context-switches | 32K/s | 22K/s | -31% |
| insn/cycle | 0.83 | 1.11 | +34% |
| cache-misses | 58B | 44B | -24% |
| CPUs utilized | 18.7 | 16.0 | -14% |

EventLoop dispatch によりスレッド間切り替えが大幅に減少し、CPU パイプライン効率とキャッシュヒット率が向上。

#### JVM NIO — 性能退行

| エンジン | 変更前 | 変更後 | 改善率 |
|---|---|---|---|
| macOS NIO | 130K | 82K | -37% |
| macOS NIO (inEventLoop wakeup opt) | — | 87K | -33% |

**原因**: ForkJoinPool の work-stealing が NIO EventLoop の固定パーティション task queue より効率的。
EventLoop dispatch では各 EventLoop が自分に割り当てられた接続のみ処理するため、
負荷の偏りを吸収できず CPU 利用率が 21%/thread に留まる。
ForkJoinPool は他スレッドのキューからタスクを steal することでこの偏りを自動的に平準化する。

### プラットフォーム別戦略

| プラットフォーム | dispatch 方式 | 理由 |
|---|---|---|
| Native kqueue/epoll | EventLoop dispatch + inEventLoop skip | +26-33% の改善。スレッド間切り替え削減 + wakeup syscall 排除 |
| JVM NIO | Dispatchers.Default 維持 | ForkJoinPool work-stealing が優位。EventLoop 固定パーティションでは負荷偏りを吸収できない |
| JVM Netty | Netty EventLoop（既存） | Netty がデフォルトで全ハンドラを EventLoop 上で実行 |
| NWConnection | dispatch queue（既存） | Apple の dispatch フレームワークが管理 |
| Node.js | Node.js EventLoop（既存） | シングルスレッドモデル |

### Netty との比較

Netty は「1 スレッド = 1 EventLoop = ロック不要」を設計原則とし、
Channel に紐づく全ハンドラ（デコーダ、エンコーダ、ビジネスロジック）を同一 EventLoop スレッドで実行する。
ktor-netty もこの方式に従い、Ktor pipeline を Netty EventLoop 上で処理している。

keel Native エンジンでの EventLoop dispatch はこの Netty の設計を踏襲するもの。
JVM NIO エンジンでは ForkJoinPool が Netty EventLoop より効率的であるため、独自の dispatch 方式を維持する。

### inEventLoop wakeup 最適化

EventLoop の `dispatch()` で、呼び出し元が既に EventLoop スレッド上にある場合、
wakeup syscall（pipe write / eventfd_write / Selector.wakeup）をスキップする最適化。
EventLoop dispatch と組み合わせることで、ホットパスの不要な syscall を排除する。

Netty の `SingleThreadEventExecutor.execute()` も同様に `inEventLoop` チェックで
不要な `wakeup()` を省略している。

**inEventLoop 追加効果（EventLoop dispatch との累積）:**

| エンジン | EventLoop dispatch のみ | + inEventLoop skip | 追加効果 |
|---|---|---|---|
| macOS kqueue | 109K | 112K | +2.7% |
| luna.local epoll | 491K | 533K | +8.6% |

epoll で効果が大きいのは、32 コア環境でスレッド数が多く wakeup 頻度が高いため。

### perf ツール環境

luna.local で `perf stat` / `perf record` を使用するために以下を設定済み:
- `linux-tools` パッケージインストール
- `perf_event_paranoid=-1`（全イベント許可、`sudo sysctl kernel.perf_event_paranoid=-1`）
- Kotlin/Native プロセスの `perf stat -p $PID` は `/proc/PID/task/` でメインスレッドのみ検出される場合があるため、system-wide (`perf stat -a`) またはコマンドラップ (`perf stat -- ./benchmark.kexe`) を推奨

### BufferedSuspendSink flush 分割問題（2026-03-27）

100KB レスポンス（`/large`）を書き込む際、BufferedSuspendSink の 8KB バッファにより
13 回の flush が発生する。各 flush は `Channel.write()` + `Channel.flush()` を呼び、
EventLoop dispatch 適用後は全て EventLoop スレッド上で直列実行される。

```
HttpWriter.writeBody(100KB)
  → BufferedSuspendSink.write(100KB)
    → 13x flushBuffer(8KB)
      → Channel.write(8KB) + Channel.flush()
        → EventLoop: OS write() × 13 回（直列）
```

EventLoop dispatch 前は Dispatchers.Default 上で flush → Channel.dispatch() → EventLoop が
パイプライン的に処理されていたが、EventLoop dispatch 後は全てが同一スレッドで直列化され、
EventLoop が 13 回の OS write() で blocking される。

epoll /large の結果: 8.6K → 5.6K (-35%)

**対応方針**: write のみ蓄積し、レスポンス完了時に 1 回だけ flush する。
codec-http の HttpWriter が write/flush を分離制御する。

### isDispatchNeeded = false の検証結果（2026-03-27）

`CoroutineDispatcher.isDispatchNeeded` を false にしてコルーチンの dispatch をスキップする
最適化を検証。効果なし。

**理由**: `isDispatchNeeded` が省略するのは taskQueue への CAS + wakeup のコスト。
CAS は ~ns オーダーで、リクエスト処理時間 (~10us) に対して無視できる。
EventLoop dispatch で既にスレッド間切り替えは排除されており、追加の改善余地がない。
Dispatchers.Default (ForkJoinPool) に対しても同様に効果なし。

### Hot-path per-request alloc 削減（2026-03-28, PR #113）

EventLoop dispatch 後の残余ボトルネックとして per-request アロケーションを分析・削減。

#### 実施した最適化

| 施策 | 削減数/req | 内容 |
|---|---|---|
| `NativeBuf.writeAsciiString` + `BufferedSuspendSink.writeAscii` | 10-20 | `encodeToByteArray()` 中間 ByteArray 除去 |
| `BufferedSuspendSource` StringBuilder 再利用 | 5-20 | フィールド化 + `clear()` で接続内再利用 |
| `parseRequestLine` indexOf 化 | 2 | `String.split()` の List + 余分な String 除去 |
| `HttpMethod.of()` ファクトリ | 1 | 標準メソッド（GET/POST 等）は companion 定数を返す |
| `isKeepAlive` `equals(ignoreCase=true)` | 1 | `String.lowercase()` 除去 |
| body bridge ByteArray 再利用 | 1 (8KB) | per-request → per-connection スコープ移動 |

**結果**: luna.local epoll 533K → 574K (**+7.7%**)。context-switches, insn/cycle は変化なし — GC 負荷軽減が主因。

#### 検証の結果見送った施策

| 施策 | 理由 |
|---|---|
| ヘッダパース `trim` inline | 可読性コストが高い（`.trim()` 2 行 → while ループ 6 行）。削減効果は大きいが Phase 9 :server で BufSlice ベースに移行すれば自然に解消 |
| `HttpStatus.codeText` キャッシュ | 微小効果（1 alloc/req の Int.toString()）に data class フィールド追加は不釣り合い |
| `HttpHeaders.clear()` + per-connection reuse | API 複雑化（overload 追加 + caller-must-clear 契約）に対して 2 alloc 削減は ROI が低い。Phase 9 で BufSlice ベースヘッダに移行予定 |
| `Pair<String, String>` per header 除去 | HttpHeaders 内部構造変更が必要。Phase 9 :server で BufSlice ベースヘッダ表現に移行して解決 |
| `KeelHeaders.names()/entries()` collection 除去 | Ktor adapter 固有。Phase 9 で Ktor 除去により消滅 |

### BufferedSuspendSink deferred flush（2026-03-28, PR #115）

#### 問題

`flushBuffer()` が毎回 `sink.write(buf)` + `sink.flush()` を呼び、100KB レスポンスで 13 回の OS write() が直列実行。EventLoop dispatch (PR #112) 後に顕在化（epoll /large: 8.6K → 5.6K, -35%）。

#### 解決策

`Channel.supportsDeferredFlush` プロパティを導入。true の場合、flushBuffer() は sink.write(buf) 後にバッファを release して新バッファをプールから取得。Channel が pending-write キューで保持し、最終 flush() で writev() 一括送信。

| エンジン | supportsDeferredFlush | 理由 |
|---|---|---|
| kqueue / epoll | true | write/flush が同一 EventLoop スレッド |
| NIO | true | write/flush が同一スレッド（Dispatchers.Default 内で直列） |
| Netty / NWConnection / Node.js | false | flush が別スレッドで実行される可能性 |

#### PooledDirectAllocator スレッドセーフ化

NIO の `appDispatcher = Dispatchers.Default` により、`allocate()` と `returnToPool()` が異なる ForkJoinPool ワーカースレッドで実行される。`ArrayDeque` を `ConcurrentLinkedDeque` + `AtomicInteger` に置換。

Native `SlabAllocator` は変更不要: Native エンジンでは `appDispatcher = coroutineDispatcher = EventLoop`（同一スレッド）。

#### ベンチマーク結果

| Engine | Endpoint | Before | After | Change |
|---|---|---|---|---|
| macOS kqueue | /large | 12K | 111K | +825% |
| luna.local epoll | /large | 5.6K | 561K | +9911% |
| macOS NIO | /hello | 85K | 123K | +45% |
| macOS NIO | /large | 26K | 129K | +396% |

NIO の +45% は deferred flush + スレッドセーフプールの複合効果。以前は非スレッドセーフ `ArrayDeque` がクロススレッド access で暗黙的に破壊され、プールミス → 毎回 `ByteBuffer.allocateDirect()` になっていた。

#### スレッドセーフ allocator の性能オーバーヘッド検証

Native (kqueue/epoll) で `SynchronizedSlabAllocator`（spin lock ベース）をベンチマーク。結果: ±2% のノイズ範囲で差なし。uncontended CAS (~5ns) はリクエスト処理時間 (~2-10us) に対して無視可能。

### Allocator 設計の検討記録（2026-03-28）

#### Netty PooledByteBufAllocator との比較

Netty は jemalloc 方式の多階層 allocator（Chunk/Page/SubPage + サイズクラス + アリーナ分割 + ThreadLocal cache）。keel は単一サイズ (8KB) の固定プール。

keel の用途（8KB 固定サイズの高頻度 allocate/release）では Netty 方式の複雑さは YAGNI。Phase 7 の適応バッファで複数サイズ対応が必要になった段階で再検討。

#### SlabAllocator のスレッドセーフ化は不要

Native エンジンでは全ての allocate/release が同一 EventLoop スレッドで実行されるため不要:
- kqueue/epoll: `appDispatcher = coroutineDispatcher = EventLoop`
- NWConnection/Node.js: `deferFlush = false`（同期 flush）

JVM のみスレッドセーフが必要な理由は NIO の `appDispatcher = Dispatchers.Default` により ForkJoinPool ワーカー間でスレッドが切り替わるため。

#### EventLoop dispatch + ThreadLocal cache の損益分析

NIO で EventLoop dispatch（全処理を EventLoop スレッドに固定）+ ThreadLocal cache（lock-free allocator）を組み合わせた場合:

- allocator 改善効果: CAS 26 回 (~700ns) → 配列 26 回 (~40ns) = **~660ns/req (~8%)**
- EventLoop dispatch の負荷分散損失: **-37%**（ForkJoinPool work-stealing の方が CPU を均等に使う）
- **差引: 大幅に悪化。allocator の局所性改善では負荷分散の損失を補えない**

NIO で EventLoop dispatch が有効になるには、EventLoop 側に work-stealing 相当の負荷分散が必要:
- Least-connections accept（接続数が少ない EventLoop に割当）
- EventLoop 間 task steal
- Adaptive dispatch（軽い処理は EventLoop、重い処理は共有 pool）

これらは Phase 9 `:server` でスレッドモデルを再設計する際の課題。

### NWConnection batch writev 検証（2026-03-28）

NWConnection の flush() は per-PendingWrite で `keel_nw_write_async` を個別呼び出し。`dispatch_data_create_concat` で複数バッファを連結し、1 回の `nw_connection_send` で送信するバッチ化を検証。

```c
// keel_nw_writev_async — NWConnection 版 writev
dispatch_data_t combined = dispatch_data_empty;
for (int i = 0; i < count; i++) {
    dispatch_data_t chunk = dispatch_data_create(bufs[i], lens[i], ...);
    combined = dispatch_data_create_concat(combined, chunk);
}
nw_connection_send(conn, combined, ...);  // 1 回の送信
```

**結果** (macOS, 4t/100c/10s, deferFlush=true + SlabAllocator spin lock):

| Endpoint | Before | After | Change |
|---|---|---|---|
| /hello | 47K | 47K | ±0%（バッファに収まるため flush 1 回） |
| /large | 6.6K | 47K | **+612%** |

/large が /hello と同レベルに到達。batch writev は有効。

### 侵入型 Treiber stack 設計（2026-03-28）

#### 概要

プール型 allocator のフリーリストで使用するラッパーノードアロケーションを排除するため、`NativeBuf` に侵入型リンクフィールド `nextLink` を導入する。

```
通常の Treiber stack:
  Node { value: NativeBuf, next: Node? }  ← 毎回 Node を alloc
  head → Node → Node → null

侵入型 Treiber stack:
  head → NativeBuf → NativeBuf → null
         (nextLink)  (nextLink)
```

#### NativeBuf への変更

```kotlin
// expect class NativeBuf
internal var nextLink: NativeBuf?
```

- プール内のフリーリストリンクとして使用
- セグメントチェーン（Phase 6b）でもチェーンリンクとして共用可能（排他的所有）
- `resetForReuse()` でクリア

#### 命名の判断

`poolNext` → プール専用の名前だとセグメントチェーン共用時に不適切。
`freeNext` → フリーリスト用語として正確だが汎用性がやや低い。
`nextLink` → 汎用的な侵入型リンク。プールにもセグメントチェーンにも自然。→ **`nextLink` を採用**。

#### PooledDirectAllocator での使用

`ConcurrentLinkedDeque`（内部 Node alloc あり）を侵入型 Treiber stack に置換:

```kotlin
private val head = AtomicReference<NativeBuf?>(null)

private fun pop(): NativeBuf? {
    while (true) {
        val cur = head.get() ?: return null
        if (head.compareAndSet(cur, cur.nextLink)) return cur
    }
}

private fun push(buf: NativeBuf) {
    while (true) {
        buf.nextLink = head.get()
        if (head.compareAndSet(buf.nextLink, buf)) return
    }
}
```

メモリオーダリング: `AtomicReference.compareAndSet` が happens-before を保証。`nextLink` は plain var で十分。

#### SlabAllocator のスレッドセーフ化方式

spin lock（`AtomicReference<Boolean>` CAS）を採用。

| 方式 | 追加 alloc | contended 時 | 評価 |
|---|---|---|---|
| spin lock | **0** | busy-wait | **採用** — クリティカルセクション ~10-20ns、contention 実質ゼロ |
| Treiber stack | Node/回 | CAS リトライ | プールの目的（alloc 削減）に反する |
| 侵入型 Treiber | **0** | CAS リトライ | SlabAllocator は ArrayDeque を spin lock で保護する方が実装シンプル |
| MpscQueue | Node/回 | CAS リトライ | 同上 |

ベンチマーク結果: spin lock ±2%（kqueue uncontended）。

侵入型 Treiber は PooledDirectAllocator (JVM) で使用。SlabAllocator (Native) は spin lock + ArrayDeque。理由: JVM では `ConcurrentLinkedDeque` の内部 Node alloc を排除する効果があるが、Native の ArrayDeque は元々 Node alloc がないため侵入型に変える利点がない。

#### maxPoolSize の厳密制御

- **PooledDirectAllocator**: `poolSize.incrementAndGet()` → `<= maxPoolSize` なら push、超過なら `decrementAndGet()` + close（increment-then-check）
- **SlabAllocator**: spin lock 内で `pool.size < maxPoolSize` を直接参照（lock で直列化されるため別途 AtomicInt 不要）
- **pop() での nextLink クリア**: allocator の責務としてフリーリストリンクを解除。`resetForReuse()` のクリアは defence-in-depth

#### AdaptiveAllocator との関係（Phase 7）

AdaptiveAllocator はサイズクラス別フリーリストを内部に持つ:

```kotlin
class AdaptiveAllocator(sizeClasses: IntArray) : BufferAllocator {
    private val heads = Array(sizeClasses.size) { AtomicReference<NativeBuf?>(null) }

    override fun allocate(capacity: Int): NativeBuf {
        val idx = sizeClasses.indexOfFirst { it >= capacity }
        return pop(idx)?.also { it.resetForReuse() } ?: NativeBuf(sizeClasses[idx])
    }

    private fun returnToPool(buf: NativeBuf) {
        val idx = sizeClasses.indexOfFirst { it == buf.capacity }
        push(idx, buf)  // Treiber push on heads[idx] using buf.nextLink
    }
}
```

`nextLink` は全サイズクラスで共用（バッファは同時に 1 つのフリーリストにのみ属する）。`buf.capacity` でサイズクラスを特定、`buf.deallocator` で allocator インスタンスを特定するため、NativeBuf に `nextLink` 以外の追加フィールドは不要。

---

## 18. セグメントチェーン設計検討（2026-03-28）

### 背景

BufferedSuspendSource は内部に単一 NativeBuf (8KB) を持ち、`fill()` の度に `compact()` を呼んで未読データを先頭にコピーする（Native: `memmove`、JVM: `ByteBuffer.compact()`）。HTTP パースで 1-2 回/req 発生。

### 検討した方式

| 方式 | compact 排除 | 複雑度 | NativeBuf 互換 | 将来拡張 |
|---|---|---|---|---|
| A. セグメントチェーン | 完全 | 高 | あり | readv, :server |
| B. リングバッファ | 完全 | 高 | **なし** | 限定的 |
| C. 適応的 compact | 部分 (~87%) | 極低 | あり | なし |
| D. ダブルバッファ | 完全 | 中 | あり | readv (2 iovec) |
| E. バッファ拡大 | 部分 | 極低 | あり | なし |

#### A. セグメントチェーン

`nextLink` で NativeBuf を連結。消費済みセグメントをプールに返却。compact 不要。

```
head → [seg0: rIdx=50, wIdx=8192] → [seg1: rIdx=0, wIdx=3000] → null
         消費中                        充填中 (= tail)
```

Netty の `CompositeByteBuf` / `ByteToMessageDecoder.COMPOSITE_CUMULATOR` に相当。

#### B. リングバッファ

単一メモリ領域で readerIndex / writerIndex が循環。allocate/release 不要。

```
通常:    [consumed][readable][writable]
折り返し: [writable][  readable       ]  ← readable が 2 領域に分断
```

NativeBuf の全 API が mod 演算必要。read()/scanLine() が wrap 境界を跨ぐ。NativeBuf との互換性がないため新クラスが必要。**不採用**。

#### C. 適応的 compact

`fill()` で `writableBytes < threshold` のときだけ compact。変更 1 行。

```kotlin
if (buf.writableBytes < COMPACT_THRESHOLD) buf.compact()
```

8KB バッファで threshold=1024 の場合、compact 頻度 ~87% 削減。

#### D. ダブルバッファ

セグメントチェーンの `maxSegments = 2` 特殊ケース。読み出し用と充填用の 2 バッファを交代。チェーンより単純だが拡張性が限定的。

### リングバッファ vs ダブルバッファの本質的な違い

| | リングバッファ | ダブルバッファ |
|---|---|---|
| メモリ確保 | 1 回（固定、以降 alloc/release 不要） | fill 毎に allocate（プールから） |
| 領域分断 | 常に発生しうる（wrap 境界） | 遷移時のみ（2 バッファ間） |
| NativeBuf 互換 | なし（新クラス or 全 API に mod 演算） | そのまま使える |
| read() | wrap 境界で 2 領域 → readv 必須 | 各バッファは連続領域 |
| scanLine() | wrap 境界を跨ぐケースがある | 2 バッファ間の跨ぎのみ |

リングバッファは「1 バッファで循環」、ダブルバッファは「2 バッファを交代」。リングバッファの方がメモリ効率は良いが、NativeBuf の dual-pointer 設計と根本的に非互換。

### BufferedSuspendSink vs Netty ChannelOutboundBuffer

#### 書き込みパスの比較

```
keel (deferFlush=true):                    Netty:
BufferedSuspendSink                        Application
  buf に書き込み                              ctx.write(byteBuf)
  満杯 → Channel.write(buf)                     ↓
       → buf.release()                    ChannelOutboundBuffer
       → buf = allocator.allocate()         Entry linked list
  flush() → Channel.flush()                ctx.flush()
           → writev(iovec[])                  → doWrite()
                                              → ch.write(ByteBuffer[])
```

| 観点 | keel | Netty |
|---|---|---|
| キュー構造 | `MutableList<PendingWrite>` | Entry linked list |
| バッファリング層 | BufferedSuspendSink (8KB) | なし（アプリが ByteBuf を直接渡す） |
| gathering write | `keel_writev()` | `ch.write(ByteBuffer[])` |
| partial write | 残余を flushSingle で再送 | Entry 内 progress tracking |
| backpressure | **なし** (Phase 7) | high/low water mark |
| flush 戦略 | deferFlush flag | 常に write/flush 分離 |

**Sink 側の結論**: keel の deferFlush + writev は Netty の write/flush パターンと本質的に同じ動作。追加のチェーン化は不要。

#### 読み込みパスの比較（ByteToMessageDecoder）

Netty の ByteToMessageDecoder には cumulation 戦略が 2 種類:

| 戦略 | Netty | keel 相当 | コピー |
|---|---|---|---|
| MERGE_CUMULATOR（**デフォルト**） | `writeBytes()` で既存バッファに追記 | `compact()` + `read()` | O(n) per fill |
| COMPOSITE_CUMULATOR | `CompositeByteBuf.addComponent()` | セグメントチェーン (方式 A) | O(1) per fill |

**Netty が MERGE をデフォルトにしている理由**:
- ほとんどのケースで小さいデータ（HTTP ヘッダ: 数百バイト）
- コピーコストが CompositeByteBuf の間接参照オーバーヘッドより小さい
- CompositeByteBuf の `getByte(i)` は内部でコンポーネント検索が必要
- 大きいデータの場合のみ COMPOSITE が有利

### Phase 6b vs Phase 9 の判断

Netty の設計判断（小データには MERGE が最適）を踏まえると:

- **Phase 6b**: 方式 C（適応的 compact）で compact 頻度を ~87% 削減。1 行変更、リスクゼロ
- **Phase 9**: :server + codec ゼロコピー + body streaming と合わせてセグメントチェーン (方式 A) を導入。大ペイロードの body 読み込みで CompositeByteBuf 相当の効果を発揮

HTTP ヘッダパース（小データ）では compact で十分。body streaming（大データ）でセグメントチェーンが活きる。

### Phase 9 :server + :client でのセグメントチェーン効果

Phase 6b で方式 C を採用し、セグメントチェーン (方式 A) は Phase 9 に延期する。
Phase 9 で :server と :client の共通基盤としてセグメントチェーンを導入する。

#### Ktor 経由パスの制約

:ktor-engine（サーバー / クライアント両方）は Ktor pipeline が String / ByteArray を前提とし、
`Dispatchers.Default` にディスパッチされるため、セグメントチェーンの恩恵は compact 排除のみ。

BufSlice のライフタイム問題:
- Ktor pipeline が `Dispatchers.Default` に dispatch → EventLoop が同じセグメントを再利用するリスク
- これが Phase 6b で codec ゼロコピー（方式2: BufSlice 伝搬）を断念した根本理由

#### :server でのセグメントチェーン効果

:server は handler が EventLoop 上で実行される。セグメントの全ライフサイクルが同一スレッドで完結。

```
read seg[0] → parse header (BufSlice) → handler → write response → release seg[0]
read seg[1] → parse body chunk → process → write → release seg[1]
...
```

| メリット | 説明 |
|---|---|
| compact 排除 | 消費済みセグメントをプールに返却 |
| ヘッダ BufSlice | String 化不要。EventLoop スコープでライフタイム安全 |
| Body streaming | セグメント単位で read → process → write → release。メモリは 8KB × 2-3 segments |
| readv scatter-read | 空きセグメント複数に 1 syscall で充填 |
| allocator ロック競合ゼロ | EventLoop 完結 |

#### :client でのセグメントチェーン効果

:client も :server と同じ構造（EventLoop 完結 + BufSlice + streaming）が適用可能。

サーバーとクライアントの I/O パターンの違い:

| | サーバー | クライアント |
|---|---|---|
| 接続数 | 多数（数千〜数万） | 少数（数十、コネクションプール） |
| 接続寿命 | 短い（request-response） | 長い（keep-alive / persistent） |
| read の特徴 | リクエスト（小ヘッダ + 可変 body） | レスポンス / メッセージ受信（可変〜大） |
| write の特徴 | レスポンス（ヘッダ + 可変 body） | リクエスト / コマンド（通常小さい） |
| body streaming | upload 受信 | download 受信（**より頻繁**） |

クライアントは大きなデータの受信（read path）が多い:
- Redis `MGET` / bulk response: 数 MB
- AMQP メッセージ consume: 数 KB〜数 MB
- MQTT: 通常 < 1KB（セグメントチェーン効果は限定的）

プロトコル別の効果:

| プロトコル | メッセージサイズ | セグメントチェーン効果 |
|---|---|---|
| Redis (RESP3) | 可変（`MGET` で数 MB） | **大** — bulk response streaming |
| AMQP | 可変（数 KB〜数 MB） | **大** — 大メッセージ consume |
| MQTT | 通常 < 1KB | 小 — 小メッセージでは compact で十分 |
| CoAP | < 1KB（UDP） | なし — UDP は別設計 |

クライアント固有の設計考慮:
- **コネクションプール**: アイドル接続はセグメントを保持しない（release → pool）
- **パイプライニング**（HTTP/1.1, Redis）: レスポンス境界をまたいでスムーズに読める
- **バックプレッシャー**: セグメント数 water mark → PushChannel の `pauseRead()` と統合

#### まとめ

| 層 | compact 排除 | body streaming | BufSlice | 総合効果 |
|---|---|---|---|---|
| :ktor-engine | あり | 不可（Ktor 制約） | 不可 | 小 |
| :server | あり | あり | あり | **大** |
| :client (Redis/AMQP) | あり | あり（大レスポンス） | あり | **大** |
| :client (MQTT) | あり | 不要（小メッセージ） | あり | 小〜中 |

Phase 9 で :server と :client の共通基盤としてセグメントチェーンを設計する。

## 19. ChannelPipeline 設計（2026-04-02）

### 背景

PR #164 の raw-io-uring ベンチマーク (1,578K req/s) で io_uring の理論上限を確認。
Ktor 経由 (589K) との 2.7x gap は coroutine continuation + Ktor pipeline のオーバーヘッド。

当初 ConnectionHandler（raw callback 専用 API）を検討したが、コーデック統合が不可能な設計では
keel の目標（プロトコル基盤 + Pluggable 構造）を満たせない。
Netty/Swift NIO の実績を踏まえ、ChannelPipeline モデルを採用する。

### アーキテクチャ: 2 層構造

```
Protocol layer:  PipelinedChannel + ChannelPipeline
                   [HttpDecoder] → [GrpcCodec] → [UserHandler]

Transport layer: Channel (既存 — 変更なし)
                   epoll / kqueue / io_uring / NIO / Netty / NWConnection / Node.js
```

- Channel は transport layer としてそのまま維持
- PipelinedChannel は Channel をラップ、または push エンジンが直接 `notifyRead` を呼ぶ
- 既存の RawSource/RawSink コーデックはユーティリティとして残す
- HTTP/WebSocket は将来 pipeline handler として再実装

### 命名規約

3 つの呼び出し元を異なるプレフィックスで完全に区別する。

| 呼び出し元 | プレフィックス | 例 | 意味 |
|-----------|-------------|-----|------|
| エンジン → Pipeline | `notify*` / `request*` | `pipeline.notifyRead(buf)` | パイプラインへの入口 |
| ハンドラー → Context | `propagate*` | `ctx.propagateRead(msg)` | 次のハンドラーへ伝播 |
| Pipeline → ハンドラー | `on*` | `handler.onRead(ctx, msg)` | イベント受信 |

Netty (`fire*` / `write` が Context と Pipeline で同名) や Swift NIO (`NIOAny` unwrap) の
混乱を回避する keel 独自の改善。

### 設計判断

| 項目 | 決定 | 根拠 |
|------|------|------|
| メッセージ型 | `Any` + 構築時型検証 | Netty/Swift NIO 踏襲。sealed class は Pluggable 性を阻害 |
| 型検証 | `addLast`/`replace` 時にランタイム検証 | acceptedType/producedType (KClass) で型チェーン検証。Netty/Swift NIO にない keel 独自改善 |
| 型宣言 | 単一 `KClass`（YAGNI） | 将来 `Set<KClass>` に拡張可能 |
| 型安全ヘルパー | `TypedChannelInboundHandler<I>(KClass)` + reified | Netty の TypeParameterMatcher リフレクションハック不要 |
| IoBuf 解放 | consume した handler が release | TailHandler 自動 release（安全網）+ try-catch ラップ（例外時リーク防止）+ propagate 検出（use-after-free 防止） |
| スレッドモデル | EventLoop 限定（Phase 8） | 全ハンドラーが同期実行。SuspendHandler は後続 Phase で追加 |
| Outbound | IoTransport（Channel 内部と共有） | Channel の write/flush 内部を IoTransport に抽出。Pipeline の HeadHandler も同じインスタンスを使用 |
| モジュール配置 | core 内 pipeline パッケージ | IoTransport と HeadHandler が同一モジュール内で internal アクセス必須 |
| Netty エンジン | Phase 8 では対応しない | 二重 pipeline 問題。keel pipeline 成熟後に再評価 |

### IoTransport

Channel の write/flush 内部ロジックを抽出した internal interface。
Pipeline の HeadHandler と Channel が同じインスタンスを共有する。

```kotlin
internal interface IoTransport {
    fun write(buf: IoBuf)
    fun flush(): Boolean       // true=完了, false=非同期 pending
    var onFlushComplete: (() -> Unit)?
    fun close()
}
```

- Channel: `flush()` で suspend 完了待ち（`onFlushComplete` コールバックで resume）
- Pipeline HeadHandler: fire-and-forget（`onFlushComplete` をセットしない）
- 各エンジン固有の最適化（IoMode, writev, SEND_ZC）は IoTransport 内部に閉じ込め

### インターフェース定義

```kotlin
// --- ChannelHandler ---

interface ChannelHandler {
    fun handlerAdded(ctx: ChannelHandlerContext) {}
    fun handlerRemoved(ctx: ChannelHandlerContext) {}
}

interface ChannelInboundHandler : ChannelHandler {
    val acceptedType: KClass<*> get() = Any::class
    val producedType: KClass<*> get() = Any::class
    fun onActive(ctx: ChannelHandlerContext) { ctx.propagateActive() }
    fun onRead(ctx: ChannelHandlerContext, msg: Any) { ctx.propagateRead(msg) }
    fun onReadComplete(ctx: ChannelHandlerContext) { ctx.propagateReadComplete() }
    fun onInactive(ctx: ChannelHandlerContext) { ctx.propagateInactive() }
    fun onError(ctx: ChannelHandlerContext, cause: Throwable) { ctx.propagateError(cause) }
}

interface ChannelOutboundHandler : ChannelHandler {
    fun onWrite(ctx: ChannelHandlerContext, msg: Any) { ctx.propagateWrite(msg) }
    fun onFlush(ctx: ChannelHandlerContext) { ctx.propagateFlush() }
    fun onClose(ctx: ChannelHandlerContext) { ctx.propagateClose() }
}

interface ChannelDuplexHandler : ChannelInboundHandler, ChannelOutboundHandler

// --- ChannelHandlerContext ---

interface ChannelHandlerContext {
    val channel: PipelinedChannel
    val pipeline: ChannelPipeline
    val name: String
    val handler: ChannelHandler
    val allocator: BufferAllocator
    // Inbound: 次の inbound handler へ伝播
    fun propagateActive()
    fun propagateRead(msg: Any)
    fun propagateReadComplete()
    fun propagateInactive()
    fun propagateError(cause: Throwable)
    // Outbound: 次の outbound handler へ伝播
    fun propagateWrite(msg: Any)
    fun propagateFlush()
    fun propagateClose()
    fun propagateWriteAndFlush(msg: Any) { propagateWrite(msg); propagateFlush() }
}

// --- ChannelPipeline ---

interface ChannelPipeline {
    val channel: PipelinedChannel
    fun addFirst(name: String, handler: ChannelHandler): ChannelPipeline
    fun addLast(name: String, handler: ChannelHandler): ChannelPipeline
    fun addBefore(baseName: String, name: String, handler: ChannelHandler): ChannelPipeline
    fun addAfter(baseName: String, name: String, handler: ChannelHandler): ChannelPipeline
    fun remove(name: String): ChannelHandler
    fun replace(oldName: String, newName: String, newHandler: ChannelHandler): ChannelHandler
    fun get(name: String): ChannelHandler?
    fun context(name: String): ChannelHandlerContext?
    // Inbound: エンジンがパイプラインに通知
    fun notifyActive(): ChannelPipeline
    fun notifyRead(msg: Any): ChannelPipeline
    fun notifyReadComplete(): ChannelPipeline
    fun notifyInactive(): ChannelPipeline
    fun notifyError(cause: Throwable): ChannelPipeline
    // Outbound: 外部がパイプラインに要求
    fun requestWrite(msg: Any): ChannelPipeline
    fun requestFlush(): ChannelPipeline
    fun requestClose(): ChannelPipeline
    fun requestWriteAndFlush(msg: Any): ChannelPipeline
}

// --- PipelinedChannel ---

interface PipelinedChannel {
    val pipeline: ChannelPipeline
    val isActive: Boolean
    val isWritable: Boolean
    val allocator: BufferAllocator
}

// --- TypedChannelInboundHandler ---

abstract class TypedChannelInboundHandler<I : Any>(
    private val type: KClass<I>,
    private val autoRelease: Boolean = true,
) : ChannelInboundHandler {
    override val acceptedType: KClass<*> get() = type
    override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        if (type.isInstance(msg)) {
            @Suppress("UNCHECKED_CAST")
            val castedMsg = msg as I
            var propagated = false
            val trackingCtx = PropagateTrackingContext(ctx) { propagated = true }
            try { onReadTyped(trackingCtx, castedMsg) }
            finally { if (autoRelease && !propagated) ReferenceCountUtil.safeRelease(msg) }
        } else { ctx.propagateRead(msg) }
    }
    abstract fun onReadTyped(ctx: ChannelHandlerContext, msg: I)
}
```

### パイプラインの内部構造

```
HEAD ↔ ctx1 ↔ ctx2 ↔ ... ↔ TAIL

Inbound:  HEAD → ctx1(HttpDecoder) → ctx2(UserHandler) → TAIL
Outbound: TAIL → ctx2(UserHandler) → ctx1(HttpEncoder) → HEAD
```

- DefaultChannelPipeline: doubly-linked list。各ノードが ChannelHandlerContext
- HeadHandler: IoTransport 経由の実 I/O（inbound の起点 + outbound の終端）
- TailHandler: 未処理メッセージの自動 release + 警告ログ

### 構築時型検証

```kotlin
// addLast 時に前のハンドラーの producedType と次の acceptedType を検証
pipeline.addLast("http-decoder", HttpDecoder())    // OK: IoBuf → HttpRequest
pipeline.addLast("handler", MyHttpHandler())       // OK: HttpRequest → HttpRequest
pipeline.addLast("mqtt-handler", MqttHandler())    // NG: PipelineTypeException
```

`Any::class`（デフォルト）は検証スキップ。型を宣言したハンドラーのみ検証対象（opt-in）。

### 既存 API との関係

| API | モデル | 用途 | suspend |
|-----|--------|------|:-------:|
| Channel/Server | pull | 一般アプリ、Ktor | Yes |
| **ChannelPipeline** | **push, callback** | **プロトコル基盤** | **No** |
| PipelinedServer | push, callback | Pipeline mode サーバー | No |
| SuspendHandler | push, suspend | アプリロジック | Yes（後続） |

> **Note**: `PushChannel` は §20 で削除。`ServerChannel` は `Server` にリネーム。

### 段階的導入

| Step | 内容 | 破壊度 |
|------|------|:---:|
| 0 | core に pipeline interfaces + DefaultChannelPipeline | なし |
| 1 | io_uring IoTransport 抽出 + IoUringPipelinedChannel | エンジン内部のみ |
| 2 | ベンチマーク移行（@InternalKeelApi 削除） | benchmark のみ |
| 3 | 他エンジン IoTransport 抽出 + PipelinedChannel | 後続 Phase |

## 20. Channel 型階層再設計（2026-04-05）

### 背景

Pipeline 導入（§19）後、以下の対応漏れが判明:

1. `IoEngine` に `bindPipeline` が未定義（各エンジンに個別実装）
2. `bindPipeline` の戻り値が `AutoCloseable`（`localAddress` 取得不可）
3. NIO/NWConnection の `bindPipeline` が不要に `suspend`
4. `PushChannel` が Pipeline を迂回し、Pipeline handlers と合成不可能

### 用語定義

| 用語 | 定義 |
|------|------|
| **Pipeline mode** | エンジンが `pipeline.notifyRead` で I/O を駆動。全ハンドラーが EventLoop スレッドで同期実行。`IoEngine.bindPipeline` で使用 |
| **Channel mode** | アプリが suspend `read()`/`write()` で I/O を駆動。`SuspendBridgeHandler` が Pipeline callbacks を suspend に変換。`IoEngine.bind` で使用 |
| **Owned read** | エンジン（またはハンドラー）が所有する `IoBuf` をコピーなしで返す read モデル。`OwnedSuspendSource.readOwned()` |
| **Pull read** | アプリがバッファを提供し、エンジンが充填する read モデル。`Channel.read(buf)` |

**Pipeline mode と Owned read は異なる概念**: Pipeline mode では全エンジンがエンジン確保バッファを使うが、
`OwnedSuspendSource` は Channel mode での zero-copy 最適化。

### 型階層（変更後）

```
Channel                    — 双方向バイトストリーム（suspend read/write）
├── PipelinedChannel       — Channel + ChannelPipeline（両モード対応）

Server                     — accept ベースサーバー（旧 ServerChannel）
PipelinedServer            — Pipeline mode サーバー（bindPipeline 戻り値）

OwnedSuspendSource         — owned buffer を返す source（旧 PushSuspendSource）

IoEngine
├── bind() → Server
├── bindPipeline() → PipelinedServer  (non-suspend)
└── connect() → Channel
```

### 変更内容

| 変更 | PR | 理由 |
|------|-----|------|
| `ServerChannel` → `Server` | #197, #198 | Server は Channel ではない（read/write なし） |
| `PushChannel` 削除 | #199 | Pipeline を迂回する設計が Pipeline handlers（TLS, HTTP）と合成不可能 |
| `PushServerChannel` 削除 | #199 | 実装 0、未使用 |
| `PushSuspendSource` → `OwnedSuspendSource` | #199 | 「Push」が Pipeline mode と混同。「owned buffer」が正確 |
| `Channel.asBufferedSuspendSource()` 追加 | #199 | `PushChannel` の ktor-engine 分岐を統一 |
| `SuspendBridgeHandler` に `OwnedSuspendSource` 実装 | #199 | Pipeline 経由で zero-copy read（readOwned from queue） |
| NIO/NWConnection `bindPipeline` non-suspend 化 | #200 | Pipeline ゼロコルーチン原則 |
| `PipelinedServer` 追加 | #201 | `bindPipeline` 戻り値型（localAddress, isActive, close） |
| `IoEngine.bindPipeline` 追加 | #201 | 共通インターフェース化（デフォルト throw） |

### PushChannel 削除の経緯

`PushChannel` は Pipeline 導入前（Phase 5b）に設計された。当時のコーデック層は `RawSource`/`RawSink`
ベースの関数合成で、`PushChannel` は「エンジンバッファを `readOwned()` でゼロコピー配送 → コーデック処理」
を想定していた。

Pipeline 導入後、コーデック処理は Pipeline handler（TlsHandler, HttpHandler）で行う設計に移行。
しかし `PushChannel` の `IoUringPushSource` は Pipeline を迂回して独自 recv を arm するため、
Pipeline handlers と共存できない（同一 fd に 2 つの recv が競合）。

**解決**: `SuspendBridgeHandler` に `OwnedSuspendSource`（`readOwned()`）を実装。Pipeline 経由で
handler 処理済みの `IoBuf` をコピーなしで返す。`Channel.asBufferedSuspendSource()` で
`PipelinedChannel` は push-mode `BufferedSuspendSource` を生成し、ktor-engine の `is PushChannel`
分岐を統一。

### IoUringOwnedSource の保留

`IoUringOwnedSource`（旧 `IoUringPushSource`）は Pipeline 迂回パスとして保留。
Pipeline traversal のオーバーヘッドが無視できない raw TCP ワークロードで性能優位がある可能性。
ベンチマーク検証後に廃止判断。

### IoEngine.bindPipeline non-suspend の理由

Pipeline mode は per-request I/O でゼロコルーチンを達成する設計。`bindPipeline` はサーバー起動時
に 1 回呼ばれるだけだが、呼び出し側にコルーチンコンテキストを要求しないことで Pipeline の「ゼロコルーチン」
哲学を一貫させる。

NIO は `registerChannelBlocking`（`runBlocking` 経由）、NWConnection は `dispatch_semaphore_wait`
で内部的に同期化。タイムアウト付き（10 秒）。
