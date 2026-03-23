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
| `engine-io-uring` | linuxX64、linuxArm64 | io_uring | 🔲 Phase 6 |
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

### 4.7 NativeBuf とプラガブルアロケータ

#### NativeBuf の実装方針

- **Native**: `nativeHeap.allocArray<Byte>()` + 参照カウント方式
- **JVM**: `ByteBuffer.allocateDirect()` を actual で実装（カーネルコピー削減）
- スライス（同一メモリを refCount 共有）、`writev` / `readv` 向け `iovec` 直接埋め込み
- mmap スライスによるファイル送信ゼロコピー

参照カウントの実装イメージ：

```kotlin
class NativeBuf(val capacity: Int, private val allocator: BufferAllocator) {
    private var refCount = 1
    fun retain() { refCount++ }
    fun release() { if (--refCount == 0) allocator.release(this) }
}
```

#### BufferAllocator（プラガブルアロケータ）

`:core` に `BufferAllocator` インターフェースを定義し、エンジン構築時に注入する設計とする。
これにより本番／テスト／プラットフォームごとにアロケータを差し替えられる。

```kotlin
// :core/commonMain
interface BufferAllocator {
    fun allocate(capacity: Int): NativeBuf
    fun release(buf: NativeBuf)
}

expect object PlatformAllocator : BufferAllocator
// jvmMain actual  → DirectByteBuffer プール（jemalloc 方式）
// nativeMain actual → スラブアロケータ / mmap
```

エンジン構築時の使用例：

```kotlin
val engine = EpollEngine(allocator = SlabAllocator(slabSize = 4096, maxSlabs = 1024))
val engine = NioEngine(allocator = PooledDirectAllocator(maxPoolSize = 256))
val engine = EpollEngine(allocator = HeapAllocator())   // テスト用
```

| 実装 | ターゲット | 特性 |
|---|---|---|
| `SlabAllocator` | Native | スラブ方式、per-CPU ロックフリー フリーリスト |
| `PooledDirectAllocator` | JVM | `ByteBuffer.allocateDirect()` プール |
| `HeapAllocator` | テスト全ターゲット | プールなし、内容アサートが容易 |

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

#### NativeBuf のバルク操作方針

NativeBuf に ByteArray 経由のバルク操作（`writeBytes(ByteArray)` / `readBytes(): ByteArray`）は追加しない。
ゼロコピーにならないデータコピーを API として提供する場合、設計上の明確な理由が必要である。

| 層 | データパス | コピー回数 |
|---|---|---|
| エンジン層 | NativeBuf のポインタ / ByteBuffer を直接 syscall に渡す | 0（ゼロコピー） |
| コーデック層 | kotlinx-io の Source / Sink 経由 | GC 管理（許容） |
| ByteArray 経由 | NativeBuf → ByteArray → 宛先 | 2（不要なコピー） |

バルク操作が必要になった場合は、IoEngine 再設計時に実際の使用パターンに基づいて
NativeBuf 間コピーやポインタ直接アクセス API として追加する。

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

### Phase 6〜8（拡張）

| Phase | 内容 | 成果物 | 状態 |
|---|---|---|---|
| 6 | io_uring エンジン + TLS（Mbed TLS） | io_uring 動作 + HTTPS 対応 | 🔲 |
| 7 | UDP エンジン + マルチプロトコル拡張 | MQTT / CoAP / Redis / HTTP/2 / gRPC / keel ネイティブ API | 🔲 |
| 8 | gRPC コード生成 + QUIC（HTTP/3）+ エコシステム統合 | .proto から KMP コード生成・全ターゲット動作 | 🔲 |

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
├── io                        # commonMain — NativeBuf / BufferAllocator / SuspendSource・Sink
├── core                      # commonMain — IoEngine / Channel / ServerChannel（:io に依存）
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
