---
sidebar_position: 3
---

# IoBuf と BufferAllocator

## 要点

- `IoBuf` は keel のバイトバッファ型。読み書きは `readByte()` / `writeByte()` による。
- 使用終了時は `release()` を呼び出す。GC に依存しない明示的な解放が前提である。
- 所有権モデルは **2 層** で異なる:
  - **Pipeline 内 (handler 間)**: ownership transfer — `ctx.propagateRead(msg)` で渡したら触らない。
  - **Transport / Channel 境界**: retain-on-input — `channel.write(buf)` は transport が retain し `readerIndex` を消費する。caller も `release()` を呼ぶ責任がある。

`IoBuf` は Netty の `ByteBuf` と同系統の設計で、Pipeline 層は Netty と同じ transfer convention だが、`channel.write` レベルでは Netty より明示的な release 管理を要求する。NIO の `ByteBuffer` に参照カウントと明示的な `release()` を加えたものと見なせる。

## IoBuf の概要

バイト列を表現する参照カウント付きバッファである。platform ごとに backing storage は異なるが、API は共通である。

| Target | Backing storage |
|---|---|
| JVM | `ByteBuffer.allocateDirect`（off-heap） |
| Native | `nativeHeap.allocArray<ByteVar>`（native memory） |
| JS | `Int8Array`（V8 heap） |

### 参照カウントが必要な理由

JVM および Native では backing storage が heap 外に確保される。GC は heap 内オブジェクトのみを回収対象とするため、off-heap memory は明示的に解放しない限り保持され続ける。leak を避けるため `release()` による明示解放を要する。

JS の `Int8Array` は GC 管理下にあるが、platform 間の API 一貫性を保つために同じ呼び出し規約を適用している。JS 環境での `release()` は実質的な no-op である。

### スレッドセーフティ契約

`IoBuf` の `refCount` は **非 atomic な bare `Int`**（`@Volatile` すら付与しない）として保持されている。`AtomicInteger` や `AtomicIntegerFieldUpdater` を使う Netty `ByteBuf` とは対照的である。

これは oversight ではなく明示的な設計判断で、以下の契約に基づく:

- **buffer の `refCount` 操作は、その時点で buffer を所有している 1 スレッドからのみ行われる**

所有権の単一性は次節の「所有権モデル」で定義する transfer semantics により保証される。所有権移譲が thread 境界を跨ぐ場合、移譲機構自体（EventLoop の `dispatch`、Netty `EventLoop.execute`、NWConnection の `dispatch_queue_async` 等）が **happens-before 関係を提供する** ため、受け手 thread は直前の `refCount` / index 値を正しく観測できる。`@Volatile` や atomic CAS は不要になる。

この契約を支える keel 側の仕組み:

- 全 engine で `ioDispatcher` を worker EventLoop に統一している。transport 層の操作は必ず EL 上で実行される。
- push 型 engine（NWConnection / Netty）も `NwConnectionQueueDispatcher` / `NettyEventLoopDispatcher` で EL thread alignment される。`SuspendBridgeHandler` 経由の callback → coroutine resume も同 EL 上で実行される。
- `Channel.write(buf)` は内部で write queue に積むが、dequeue + flush + release は同一 EL 上で行われる。

### 契約違反が発生する典型ケース

以下のコードは契約を破る:

```kotlin
override fun channelRead(ctx: HandlerContext, msg: Any) {
    val buf = msg as IoBuf
    coroutineScope.launch(Dispatchers.Default) {
        // Default thread pool で実行される — EL thread ではない
        processAsync(buf)     // refCount は非 atomic、race になりうる
        buf.release()          // release も別 thread から
    }
}
```

- `withContext(Dispatchers.IO)` で buffer を別 dispatcher に渡して touch する
- coroutine の suspend 点を跨いで buffer を保持し、resume 先 dispatcher を明示変更する
- user code が明示的に別 thread へ buffer を受け渡す（例: `ArrayDeque` に積んで別 executor が pull）

これらのパターンでは `refCount` の更新が失われる、あるいは別 thread から観測できない可能性がある。症状は Native の segfault、JVM の silent leak / double-free、JS の silent corruption として顕在化する。

### Netty が atomic を採用している理由との対比

Netty `ByteBuf` は cross-thread 共有を安全に許容するために atomic CAS を選択している。keel は hot-path cost を優先して非 atomic を選択した。

| | keel `IoBuf` | Netty `ByteBuf` |
|---|---|---|
| `refCount` | 非 atomic `Int` | `AtomicIntegerFieldUpdater`（CAS） |
| cross-thread 共有 | 契約違反（EL alignment 前提） | 許容（atomic がガード） |
| hot-path cost | increment / decrement + 境界 check のみ | CAS loop（x86: `LOCK` prefix、ARM: LL/SC） |
| 実測コスト | ほぼ 0 | per-op で数 ns × hit 回数 |
| user 誤用時 | undefined behavior | safe（leak せず壊れもしない） |

両者とも defensible な設計で、適用コンテキストの違い（Netty は汎用 Java networking、keel は KMP の薄い I/O layer）に起因する。keel の選択が成立する前提は以下の 3 点である:

1. EL alignment が engine 側で保証される
2. user handler で blocking I/O を行う場合は `withContext(Dispatchers.IO)` で明示的に退避し、**buffer を持ち越さない**（block 前に release または transfer する）
3. cross-thread な buffer 共有が必要な use case が現時点で存在しない（Ktor / pipeline / codec が全て EL 上で完結する）

この前提が崩れる use case が現れた場合（例: user が多数の buffer を集約する並列処理を書きたい等）、`IoBuf` に atomic variant を追加する、あるいは Netty `ByteBuf` を直接扱う `keel-engine-netty` 経路に切り替える、といった選択肢がある。

### バッファの 3 領域構造

```
+-------------------+------------------+------------------+
| discardable bytes | readable bytes   | writable bytes   |
+-------------------+------------------+------------------+
|                   |                  |                  |
0      <=      readerIndex   <=   writerIndex    <=    capacity
```

- `readerIndex` は読み取り位置。`readByte()` で進む。
- `writerIndex` は書き込み位置。`writeByte()` で進む。
- `compact()` は discardable region を破棄し、writable 領域を回収する。

`readableBytes = writerIndex - readerIndex`、`writableBytes = capacity - writerIndex`。Netty `ByteBuf` の index モデルと同等である。

## 所有権モデル

参照カウント起因のバグはほぼすべてこのモデルの誤解に由来する。

### 2 層の所有権モデル

keel の buffer 所有権は **層によって 2 つのモデル**を使い分ける。層を混同すると leak または double-release になるので、まずここを押さえる。

| 層 | モデル | 該当 API |
|---|---|---|
| **Pipeline 内 (handler 間)** | ownership transfer (渡したら触らない) | `onRead` / `onWrite` / `ctx.propagateRead` / `ctx.propagateWrite` / `transport.onRead` callback |
| **Transport / Channel 境界** | retain-on-input (caller も transport も ref を持つ) | `Channel.write(buf)` / `IoTransport.write(buf)` / `SuspendSink.write(buf)` |
| **読み取り API** | 非移譲 (caller が buffer を渡して fill してもらう) | `Channel.read(buf)` / `IoTransport.read(buf)` |

#### 層 1: Pipeline 内の ownership transfer

pipeline 内で buffer を渡すと所有権が移譲される。渡した側は以降触れない。最終 handler が release する（または次の handler に `propagateRead` で渡し続ける）。

```kotlin
class HttpDecoder : InboundHandler<IoBuf> {
    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        try {
            // msg は handler が所有。処理して下流に slice を渡す
            val slice = ctx.allocator.slice(msg, msg.readerIndex, bytesToEmit)
            ctx.propagateRead(HttpBody(slice))   // slice を下流に移譲
        } finally {
            msg.release()     // handler が元 msg を release (所有権を引き受けた責任)
        }
    }
}
```

Netty `ByteBuf` の pipeline convention と同じ mental model。

#### 層 2: Transport / Channel 境界の retain-on-input

user code が `channel.write(buf)` を呼ぶと、**transport は内部で retain** し、**caller の `readerIndex` を消費**する。caller は自身の参照を release する責任を持つ。

```kotlin
val buf = allocator.allocate(128)                // refCount = 1
buf.writeAscii("hello", 0, 5)                   // writerIndex = 5
channel.write(buf)                                // transport が retain (refCount = 2)
                                                  // buf.readerIndex が 5 に進む (consumed)
channel.flush()
buf.release()                                     // caller が自分の参照を release (refCount = 1)
// transport が flush 完了後に retained ref を release (refCount = 0、memory free)
```

**caller が `release()` を呼ばないとメモリリーク** — transport の retain だけでは refCount が 1 に残り、memory は解放されない。

なぜこの設計か: 同じ buffer に header + body を連続書き込みする codec 使用例で自然。Netty の `channel.writeAndFlush(buf.retain())` のような ceremony を省ける。トレードオフは release 忘れの leak リスクと、pipeline 層との mental model 非対称性。

#### 層 3: 読み取り API (非移譲)

`channel.read(buf)` / `transport.read(buf)` は caller が allocate した buffer を渡し、engine が中身を fill する。所有権は caller が継続保持、caller が release する。

```kotlin
val buf = allocator.allocate(8192)
val n = channel.read(buf)       // n bytes が buf に入る、所有権は caller のまま
processData(buf)
buf.release()
```

### API の所有権分類

**A. Pipeline 層: ownership transfer**（渡したら触らない）

| API | 移譲先の release タイミング |
|---|---|
| `transport.onRead(buf)` callback | pipeline HEAD → handler chain の最終 handler |
| `onRead(ctx, msg)` / `onReadTyped(ctx, msg)` | handler 自身（`try/finally` で release するのが慣用） |
| `ctx.propagateRead(msg)` / `ctx.propagateWrite(msg)` | 下流 / 上流 handler |
| `ctx.propagateUserEvent(evt)` | 下流 handler（`evt` が `IoBuf` の場合） |

**B. Transport / Channel 境界: retain-on-input**（caller も release する）

| API | transport 側 | caller 側 |
|---|---|---|
| `Channel.write(buf)` | 内部で `buf.retain()`、flush 完了後に release | `readerIndex` が消費される。**自身の参照を `release()` する責任あり** |
| `IoTransport.write(buf)` | 同上 | 同上 |
| `SuspendSink.write(buf: IoBuf)` | 同上 (実装による) | 同上 |

**C. 非移譲 API**（caller が所有権を継続保持）

| API | caller の責任 |
|---|---|
| `Channel.read(buf)` / `IoTransport.read(buf)` | buffer を渡して fill 後、使用が終わったら release |
| `buf.readByte()` / `writeByte()` / `getByte(i)` / `readByteArray(...)` / `writeByteArray(...)` | 所有権不変（index のみ進む） |
| `buf.copyTo(dest, length)` | source / dest どちらも caller 所有のまま |
| `buf.compact()` / `clear()` | 所有権不変 |

**D. 新しい buffer を返す API**（caller が所有権を取得）

| API | 返り値の初期 refCount | release 責任 |
|---|---|---|
| `allocator.allocate(size)` | 1 | 最終 consumer |
| `allocator.wrapBytes(bytes, offset, length)` | 1（wrap 可能な allocator のみ、不可の場合 `null`） | 最終 consumer（引数の `bytes` は caller 所有のまま） |
| `allocator.slice(src, offset, length)` | 1（slice 独自のカウント） | slice 所有者（`src` は allocator 内部で管理） |
| `buf.retain()` | 既存 refCount +1、同 instance を返す | 追加参照を作成した主体 |

`BufferedSuspendSink.write(bytes: ByteArray, offset, length)` は `ByteArray` 引数を取るため本分類の対象外である。sink は内部で `wrapBytes` または scratch copy を経由して `IoBuf` を生成し、その `IoBuf` の release も sink 自身が担う。

### retain() を呼ぶ条件

呼び出し側が buffer の参照を追加で保持する必要がある場合にのみ `retain()` を呼ぶ。該当する場面は以下の 3 種である。Pipeline 層の ownership transfer 下での話で、Transport/Channel 境界の retain-on-input では caller は暗黙的に参照を保持するので不要。

**(1) Pipeline handler が msg を field に保存して後続 event で再利用する場合**

```kotlin
class DelayedEcho : InboundHandler<IoBuf> {
    private var cached: IoBuf? = null

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        cached = msg.retain()        // handler が保持する参照 (+1)
        ctx.propagateRead(msg)        // 元の参照を下流に移譲 (transfer)
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        cached?.release()              // retain と対の release
        cached = null
    }
}
```

**(2) Pipeline 内で同じ msg を複数の downstream に fan-out する場合**

```kotlin
override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
    ctx.propagateRead(msg.retain())   // 追加参照を primary 下流に
    ctx.propagateRead(msg)             // 元の参照を secondary 下流に
    // 各 downstream が 1 回ずつ release する
}
```

`retain()` は ownership transfer の**前**に呼ぶ。最後の移譲先には元の参照を渡すため `retain()` 不要。N 回 propagate するなら `retain()` は `N - 1` 回。

**(3) 非同期処理のスコープを跨ぐ場合**

coroutine の suspend を跨いで msg を保持する必要がある場合、元の owner が release する前に retain して生存期間を確保する。

**Transport/Channel 境界での「fan-out write」は retain だけでは成立しない**。`channel.write(buf)` は `readerIndex` を消費するため、単純に複数 channel に渡しても 2 回目以降は `readableBytes = 0` となる。対処法:

```kotlin
// 方法 1: readerIndex を save / restore
val saved = buf.readerIndex
channel1.write(buf)       // retain + readerIndex 消費
buf.readerIndex = saved    // reset
channel2.write(buf)       // retain + 同じ範囲を再 read
buf.release()              // caller の参照を release

// 方法 2: slice で独立 view を作る (allocator.slice が src を内部 retain)
val view1 = allocator.slice(buf, buf.readerIndex, bytes)
val view2 = allocator.slice(buf, buf.readerIndex, bytes)
channel1.write(view1); channel2.write(view2)
view1.release(); view2.release()   // slice 所有者が release
buf.release()                       // 元 buffer の caller 所有分
```

### release() の責任

参照を最後に保持する主体が `release()` を呼ぶ。具体的には:

- `allocator.allocate()` で取得した buffer は最終 consumer が release する。
- `buf.retain()` で増やした参照は、retain を呼んだ主体が release する。
- `allocator.slice(src, offset, length)` で作成した slice は slice の owner が release する（`src` の参照は allocator が内部で管理する）。

参照カウントは `allocate()` 時に 1、`retain()` で +1、`release()` で -1。0 到達時点でメモリ解放（または pool 返却）が行われる。

### close() と release() の違い

- `release()` — 参照カウントを 1 減らす。0 到達時のみ実解放。通常はこれを用いる。
- `close()` — 参照カウントを無視して即時解放。限られた場面でのみ使用する。

`close()` の挙動は platform 依存である:

| Platform | `close()` の動作 |
|---|---|
| Native | `nativeHeap.free` により即時解放。参照カウント無視 |
| JVM | no-op（GC 管理） |
| JS | no-op（GC 管理） |

`close()` は engine shutdown 時に pipeline に残った buffer を強制回収する等の teardown 用途である。通常のライフサイクル管理では `release()` のみで完結する。

## 典型的な使用パターン

### パターン 1: Transport/Channel に書き込み (retain-on-input)

`channel.write(buf)` は **transport が内部で retain** する。caller は自分の参照を明示的に release する必要がある (layer 2 モデル)。

```kotlin
val buf = allocator.allocate(128)
buf.writeAscii("hello", 0, 5)
channel.write(buf)      // transport が retain (refCount 2)、caller の readerIndex 消費
channel.flush()
buf.release()            // caller の参照を release (transport が flush 後に自身の retain を release)
```

`buf.release()` を忘れると memory leak。`channel.write` に渡した後でも caller は同じ buffer に続けて書き込みを行い、再度 `channel.write` で送ることもできる（writable region が残っていれば）。

### パターン 2: Transport/Channel から読み取り (非移譲)

`channel.read(buf)` は所有権を移譲しない。caller が allocate した buffer を渡して engine に fill してもらい、caller が release する。

```kotlin
val buf = allocator.allocate(8192)
val n = channel.read(buf)
processData(buf)
buf.release()
```

### パターン 3: Pipeline handler で msg を field に保存 (retain 必要)

Pipeline 層 (ownership transfer) で handler が msg を field に保存する場合、保持前に `retain()` を呼び teardown 時に release する。

```kotlin
class DelayedEcho : InboundHandler<IoBuf> {
    private var cached: IoBuf? = null

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        cached = msg.retain()        // handler 保持分 (+1)
        ctx.propagateRead(msg)        // 元参照は下流に移譲
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        cached?.release()
        cached = null
    }
}
```

### パターン 4: Pipeline 内で下流複数 handler に fan-out (retain 必要)

`ctx.propagateRead(msg)` は pipeline 内で ownership transfer なので、複数下流に配るには `retain()` を**移譲前**に呼ぶ。最後の移譲先には元参照を渡すため `retain()` 不要。N 回 propagate なら `retain()` は `N - 1` 回。

```kotlin
override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
    ctx.propagateRead(msg.retain())   // +1 を primary 下流へ
    ctx.propagateRead(msg)             // 元参照を secondary 下流へ
    // 各下流 handler が 1 回ずつ release する
}
```

Transport/Channel 境界の `channel.write` は retain-on-input なので `buf.retain()` を足しても index consumption は解消されない。2 つ以上の channel に同じ内容を書く場合は「retain() を呼ぶ条件」節の方法 1 (readerIndex save/restore) または方法 2 (slice) を参照。

## 典型的な不具合パターン

| 不具合 | 症状 | 原因 |
|---|---|---|
| `channel.write` 後の `release()` 忘れ | memory leak（leak detection で検出可能） | retain-on-input モデルを transfer と誤解。caller の参照 release が必要 |
| Pipeline 層での release 忘れ | memory leak | handler が `msg.release()` を呼ばず `propagateRead` もしない（どちらか一方は必須） |
| 二重 `release()` | `IllegalStateException: Buffer already released` | Pipeline handler で `propagateRead(msg)` した後に自分で `msg.release()` した |
| use-after-release | Native で segfault、JVM で invalid data、JS で silent corruption | release 後の buffer に対して read / write を行った |
| Pipeline handler 内で retain without release | memory leak | handler で field に保持 (`retain()`) したが teardown (`onInactive`) 時に release していない |
| retain-on-input 経路で ownership transfer 前提のコード | memory leak (caller 側) or double-release | `channel.write(buf)` の後で触らず release しない Netty 式の書き方。keel では `buf.release()` が必須 |
| `channel.write` 連鎖で fan-out を期待 | 2 回目以降の write が 0 bytes | `readerIndex` が 1 回目で消費される。readerIndex reset または slice が必要 |

テスト実行時に疑わしい場合、allocator を `TrackingAllocator` で wrap し、終了時に `assertNoLeaks()` を呼ぶことで検出できる（次節参照）。

## 不具合検出ツール

### 2 種類の検出器

| ツール | 目的 | 対応 |
|---|---|---|
| `TrackingAllocator` | allocation と release の不一致を計数で検出 | 全 platform |
| `LeakDetectingAllocator` | release 漏れ buffer の allocation call site をスタックトレースで報告 | Native、JVM |

### 使用例

```kotlin
val tracker = DefaultAllocator
    .withLeakDetection { msg -> fail(msg) }
    .withTracking()

// テスト実行...

tracker.assertNoLeaks()  // release 漏れがあれば throw
```

`withTracking()` を最外に配置するのは、`assertNoLeaks()` がその層に存在するためである。

### GC の明示的トリガー

`LeakDetectingAllocator` は GC 経由で leak を検出する。テストでは明示的に GC を起動する必要がある:

- **Native**: `kotlin.native.runtime.GC.collect()`
- **JVM**: `System.gc()` の後、`tracker.allocate(1).release()` で `PhantomReference` queue を drain する
- **JS**: 不要（V8 GC 管理下のため leak detection 自体が適用外）

## BufferAllocator

`IoBuf` 生成の pluggable interface である。各 engine は platform に応じた既定値を持ち、必要に応じて `IoEngineConfig` で上書き可能である。

```kotlin
val engine = KqueueEngine(
    config = IoEngineConfig(
        allocator = SlabAllocator(bufferSize = 8192, maxPoolSize = 256),
    ),
)
```

### 3 実装の比較

| 実装 | Target | pool 構造 | thread safety |
|---|---|---|---|
| `DefaultAllocator` | 全 target | pool なし | stateless |
| `PooledDirectAllocator` | JVM | size 毎の Treiber stack (lock-free) | `AtomicReference` CAS |
| `SlabAllocator` | Native | size 毎の `ArrayDeque` (LIFO) | spin-lock (`AtomicReference<Boolean>`) |

### createForEventLoop の役割

engine は各 EventLoop 起動時に `allocator.createForEventLoop()` を呼び、per-thread の allocator instance を得る。stateless allocator は `this` を返すが、pooled 系は別 instance を返すことで pool を single thread に閉じ込め、hot path での atomic 操作を不要にする。parent allocator (`IoEngineConfig` に渡した instance) は起動時の size 登録専用で、実際の割り当ては child が担う。

### `DefaultAllocator`

毎回新規割り当てする stateless 実装。`createDefaultIoBuf(capacity)` を直接呼ぶだけで pool を持たない。`createForEventLoop()` は `this` を返す。test / fallback / JS の既定値として使用する。

- **`wrapBytes`**: `null` を返す（zero-copy wrap 非対応）
- **`slice`**: copy base。`allocate(length)` で新規 buffer を確保し `copyTo` で内容を複製するため、slice は source から独立する（source を retain しない）

### `PooledDirectAllocator` (JVM)

size class 毎に Treiber stack を持ち、lock-free で pool を操作する。stack head は `AtomicReference<DirectIoBuf?>`、pool 内の連結は `IoBuf.nextLink` 経由の intrusive リンクで行う。

- **allocate**: pool から CAS で pop、miss 時のみ `ByteBuffer.allocateDirect(capacity)` を新規確保。取得した buffer の deallocator に `returnToPool` を設定する
- **release (refCount 0 到達時)**: deallocator が CAS で stack に push。pool が満杯 (`maxSlots` 超) なら push を諦めて `buf.close()` で解放
- **`registerPoolSize(size, maxSlots)`**: lazy registration。総メモリ予算 (`maxTotalBytes` デフォルト 251 KiB) を超える場合は `maxSlots` を自動削減。重複登録は no-op
- **`createForEventLoop()`**: 親の size class を引き継いだ新 instance を返し、per-pool 上限は `LOCAL_POOL_SLOTS = 8`（親のデフォルト 16 から縮小）
- **`wrapBytes`**: `ByteBuffer.wrap(bytes, offset, length)` で zero-copy wrap、`DirectIoBuf.wrapExternal` として返す。backing は caller の heap array であり release まで mutate 禁止
- **`slice`**: `ByteBuffer.duplicate().slice()` で zero-copy、source を retain し、slice 解放時に source を release する deallocator を仕込む

### `SlabAllocator` (Native)

size class 毎に `ArrayDeque<NativeIoBuf>` を持つ LIFO pool。pool 全体の HashMap を spin-lock (`AtomicReference<Boolean>` による CAS) で保護する。

- **allocate**: spin-lock 下で `removeLast()`、miss 時のみ `NativeIoBuf(capacity)` を `nativeHeap` から新規確保。deallocator に `returnToPool` を設定する
- **release (refCount 0 到達時)**: deallocator が spin-lock 下で `addLast()`。pool が満杯なら `buf.close()`（`nativeHeap.free`）で解放
- **`registerPoolSize(size, maxSlots)`**: lazy registration。予算 (`maxTotalBytes` デフォルト 256 KiB) を超える場合は `maxSlots` を自動削減。spin-lock 下で重複チェックと挿入を atomic に実施
- **`createForEventLoop()`**: 親の size class を引き継ぎ per-pool `LOCAL_POOL_SLOTS = 8` を適用した新 instance を返す
- **`wrapBytes`**: `ByteArray` を pin して `CPointer` を取り、`NativeIoBuf.wrapExternal` として返す。deallocator が release 時に unpin する
- **`slice`**: pointer 加算による zero-copy。source を retain し、slice 解放時に source を release する

### 共通の設計方針

- **pool hit path の cost を最小化**: PooledDirect は CAS のみ、Slab は spin-lock のみ。いずれも heap allocation を発生させない
- **budget 超過時の graceful degradation**: `maxTotalBytes` を守るため、`registerPoolSize` は自動的に `maxSlots` を縮小する
- **size class 未登録時は fallback**: 登録されていない size を要求すると pool が存在せず、常に fresh allocation になる（機能上は動作、性能は劣化）

### io_uring engine の特殊性

io_uring engine の inbound read path のみ `BufferAllocator` を経由せず、kernel 管理の `ProvidedBufferRing` スロットを `RingBufferIoBuf` として露出する。他の read path および全 write path は通常の allocator を通る。

## platform 別実装

`IoBuf` interface の具象実装は platform ごとに異なる。いずれも参照カウントは **非 atomic な `Int`**（EventLoop 1 本に閉じる前提）、`writeByte` / `readByte` は単要素のため bounds check を省略し hot path を薄くする、という方針は共通である。bulk 操作にのみ bounds check を行う。

### 4 実装の比較

| 実装 | Target | backing storage | `close()` 挙動 | 外部 wrap 対応 |
|---|---|---|---|---|
| `DirectIoBuf` | JVM | `ByteBuffer.allocateDirect(capacity)` | no-op（GC 管理） | `wrapExternal(buffer, bytesWritten)` |
| `NativeIoBuf` | Native | `nativeHeap.allocArray<ByteVar>(capacity)` | `nativeHeap.free` 実行、`freed` flag で idempotent | `wrapExternal(ptr, capacity, bytesWritten, deallocator)` |
| `TypedArrayIoBuf` | JS | `Int8Array(capacity)`（V8 heap） | no-op（GC 管理） | `wrapExternal(array, bytesWritten)` |
| `RingBufferIoBuf` | io_uring engine | `ProvidedBufferRing` slot（kernel 管理） | slot を leak（`AutoCloseable` 互換用、通常 path は `release()`） | 構造そのものが wrap 専用 |

### `DirectIoBuf` (JVM)

- **backing storage**: `ByteBuffer.allocateDirect(capacity)` を primary constructor で確保。`capacity` は immutable field
- **refCount**: bare `Int refCount = 1`（非 atomic）
- **release path**: `refCount` を decrement し 0 到達時に deallocator（設定時）または `close()` を呼ぶ。double-release は `refCount > 0` check で防御
- **close 挙動**: `refCount = 0` をセットし、backing `ByteBuffer` は GC 任せ（実解放なし）。external wrap の場合は deallocator callback が cleanup
- **`writeByte` / `readByte`**: `buf.put(writerIndex++, value)` / `buf.get(readerIndex++)`、bounds check なし（hot path 薄化）
- **`writeByteArray` / `readByteArray`**: bounds check あり、`ByteBuffer.put(src, offset, length)` / `get(dst)` 経由で bulk copy
- **`compact` / `clear`**: `ByteBuffer.compact()` を position/limit を正しく設定して呼び出し、`clear()` は index + position/limit を全 reset
- **`copyTo`**: duplicate view を作り `ByteBuffer.put` 経由で転送
- **engine accessor**: `unsafeBuffer: ByteBuffer` （property + extension）、NIO syscall による zero-copy I/O に使用
- **`wrapExternal`**: companion factory で pre-allocated `ByteBuffer` を wrap。`writerIndex = bytesWritten` で初期化

### `NativeIoBuf` (Native: Linux / macOS)

- **backing storage**: primary constructor で `nativeHeap.allocArray<ByteVar>(capacity)` を確保、`ownsMemory = true` を設定
- **refCount**: bare `Int refCount = 1`（非 atomic）+ `freed: Boolean` flag（二重 free 防止）
- **release path**: `refCount` decrement、0 で deallocator または `close()` 呼び出し
- **close 挙動**: `if (!freed)` guard → `freed = true` + `refCount = 0` をセット → `ownsMemory` が true の場合のみ `nativeHeap.free(ptr.rawValue)` 実行。**4 impl 中、実メモリ解放を行うのは本 impl のみ**
- **`writeByte` / `readByte`**: `ptr[writerIndex++] = value` / `ptr[readerIndex++]`、bounds check なし
- **`writeByteArray` / `readByteArray`**: bounds check あり、pin + `memcpy(ptr + index, src, length)` で bulk copy
- **`compact`**: `readerIndex == 0` なら no-op、それ以外は `memmove(ptr, ptr + readerIndex, readable)`
- **`copyTo`**: dest が `NativePointerAccess` (`unsafePointer` を持つ) であれば `memcpy` で直接転送
- **engine accessor**: `unsafePointer: CPointer<ByteVar>` （interface member）、POSIX syscall (`read(2)` / `write(2)` / `writev(2)`) に使用
- **`wrapExternal`**: companion factory で raw pointer + capacity + `bytesWritten` + optional deallocator を受ける。`ownsMemory = false` で初期化、`resetForReuse()` で index と `freed` flag を初期化（ptr / ownsMemory は保持）することで pool 側の再利用を可能にする

### `TypedArrayIoBuf` (JS)

- **backing storage**: `Int8Array(capacity)` を V8 heap に確保、`capacity = array.length`
- **refCount**: bare `Int refCount = 1`（JS は single-threaded 前提）
- **release path**: `refCount` decrement、0 で deallocator または `close()`
- **close 挙動**: `refCount = 0` をセット、backing `Int8Array` は V8 GC 任せ（no-op）
- **`writeByte` / `readByte`**: `buf.asDynamic()[writerIndex++] = value` / `(buf.asDynamic()[readerIndex++] as Int).toByte()`。Kotlin/JS IR mode では typed array の indexing が直接 compile できないため `asDynamic()` で V8 native operation に直結
- **`writeByteArray` / `readByteArray`**: bounds check あり、element-wise loop で `asDynamic()` 経由
- **`compact`**: `Int8Array.copyWithin(0, readerIndex, writerIndex)` で V8 native 実装を利用
- **`copyTo`**: `destBuf.set(buf.subarray(readerIndex, ...), dest.writerIndex)` で V8 typed-array bulk copy
- **engine accessor**: `unsafeArray: Int8Array` （property + extension）、Node.js `net.Socket.write` 等に使用
- **`wrapExternal`**: companion factory で pre-allocated `Int8Array` を wrap、`writerIndex = bytesWritten`。`ownsMemory` field は持たない（常に false 相当）

### `RingBufferIoBuf` (io_uring engine)

他の 3 impl と性格が大きく異なる。**allocator を介さず、kernel 提供の buffer ring slot を直接 `IoBuf` として扱う wrapper 専用** 実装である。

- **backing storage**: `ProvidedBufferRing` の slot。pointer は `bufferRing.getPointer(bufId)` で算出し、コンストラクタで cache（property 毎のアクセスで再計算しない）
- **refCount**: bare `Int refCount = 1`
- **lifecycle**: 新規 `allocate` は存在しない。source 起動時に slot 単位で事前生成され、CQE callback で `reset()`（index と `refCount = 1` を初期化、ptr と bufferRing 設定は保持）して再利用。**hot path での object 生成ゼロ**
- **release path**: `refCount` を 0 に decrement した時点で `onRelease(this)` callback が発火、slot が ring に返却される
- **close 挙動**: `refCount = 0` をセットするのみ。`onRelease` は呼ばないため、slot は **意図的に leak** する（`AutoCloseable` 互換の最後の砦、通常 path は `release()` 経由）
- **`writeByte` / `readByte` / bulk ops**: `NativeIoBuf` と同構造（`ptr[index++]` / `memcpy` / `memmove`）
- **engine accessor**: `unsafePointer: CPointer<ByteVar>`（`NativePointerAccess` 実装）、`io_uring_prep_recv` 等の SQE 提出に使用
- **platform-unique**: hot-path allocation が存在しないため、Fixed Buffers / MemoryOwner 基盤が整備されれば直接 zero-copy read path に接続可能

### 共通の設計方針

- **`refCount` は非 atomic**: 全実装で `@Volatile` すら付けない bare `Int`。EL alignment 前提に基づく意図的選択で、cross-thread 共有は契約違反として扱う。根拠と契約違反の症状は「スレッドセーフティ契約」節を参照
- **単要素 read/write は bounds-check なし**: hot-path throughput 最大化のための意図的 trade-off。境界越えは caller 責任
- **bulk 操作のみ bounds-check**: length 引数を受ける `writeByteArray` / `readByteArray` / `copyTo` は明示検査で early failure させる
- **engine-specific accessor**: 各 impl が platform native memory primitive を `unsafe*` 名で露出。interface には含めない（platform-specific なので common API にできない）

## 大規模 payload の最適化

本節は `keel-ktor-engine` で大規模 response を送信する際に発生する性能問題と、その自動解消機構を説明する。通常の Ktor handler 実装では意識不要だが、挙動を理解しておくと benchmark 結果の解釈や、大規模 file / streaming body の送信設計に有益である。

### 背景: `BufferedSuspendSink` の役割

Ktor application が response を書く経路は以下である。

1. Handler が `call.respondBytes(byteArray)` または `call.respondOutputStream { ... }` を呼ぶ
2. Ktor の write path が `keel-ktor-engine` の transport adapter に到達する
3. adapter は `BufferedSuspendSink` を経由して engine の transport に書き込む

`BufferedSuspendSink` は内部に 8 KiB の scratch buffer を持ち、小さな書き込みを集約してから transport に forward する。これは kotlinx-io の `BufferedSink` と同種の最適化で、多数の小さな `Channel.write` 呼び出しを避けるのが目的である。

### 問題: 大規模 payload での分割オーバーヘッド

ここで問題になるのが、scratch buffer より大きな単一 payload の扱いである。素朴に実装すると、例えば 1 MiB の response は 8 KiB chunk × 128 回の write に分割される。各 chunk 毎に:

- `PendingWrite` 構造体の allocation
- flush listener (callback) の allocation
- Netty 経由の場合、`ByteBuf` の allocation
- scratch buffer から transport 所有 buffer への memcpy

これが 128 回発生する。JVM では per-response の GC 負荷が大きく、full matrix benchmark でも `/large` (100 KiB) が `/hello` (13 byte) より数倍遅くなっていた。

### 解決: `DIRECT_WRITE_THRESHOLD` での scratch bypass

`BufferedSuspendSink` は `DIRECT_WRITE_THRESHOLD` 以上の write を scratch を経由させず、caller の `ByteArray` を直接 `IoBuf` view として wrap して transport に forward する。threshold は scratch buffer size と同じ 8 KiB（コード上は `BUFFER_SIZE = 8192` と一致させている）。

```kotlin
// Ktor route 内
call.respondBytes(small)   // < 8 KiB: scratch 経由でコピー
call.respondBytes(large)   // ≥ 8 KiB: IoBuf view として wrap、scratch bypass
```

経路は `BufferedSuspendSink.write → BufferAllocator.wrapBytes → IoBuf view → transport.write`。

**注意事項**: wrap された `IoBuf` は caller の `ByteArray` を共有するため、次の `flush()` 完了まで元の array を mutate してはならない。通常の `call.respondBytes(...)` では mutate する余地がないので安全。

### platform 別対応

`wrapBytes` の実装有無で挙動が分かれる。

| Platform | zero-copy 最適化 | 実装 |
|---|---|---|
| JVM | 有効 | `PooledDirectAllocator.wrapBytes` が `ByteBuffer.wrap(bytes, offset, length)` で zero-copy |
| Native | 有効 | `SlabAllocator.wrapBytes` が pinned `ByteArray` + `CPointer` で zero-copy |
| JS | 非対応 | `DefaultAllocator.wrapBytes` が `null` を返すため、scratch 経由の chunked copy に fallback |

JS で非対応なのは、`Int8Array` ベースの V8 memory model で ByteArray の zero-copy wrap に相当する primitive がないためである。JS 環境では元々 V8 GC 管理下なので、多数の小 allocation でも Native/JVM ほど regression が目立たない。

### 効果

概算で、1 MiB response の送信に対する allocation 数:

| 方式 | `PendingWrite` | flush listener | Netty `ByteBuf` (JVM) | scratch memcpy |
|---|---|---|---|---|
| scratch 経由 (chunked) | 128 | 128 | 128 | 128 |
| zero-copy (wrap) | 1 | 1 | 1 | 0 |

JVM では GC 圧力差が顕著。full matrix benchmark では `/large` path で zero-copy 有効化により 10〜30% の throughput 改善を計測している（詳細は `benchmark/results-summary/`）。

## 他の buffer API との比較

keel を触る開発者の多くは、他の networking ライブラリで既に何らかの buffer API に馴染んでいる。以下は代表的な 6 実装との対応表である。

| 特性 | keel `IoBuf` | Netty `ByteBuf` | SwiftNIO `ByteBuffer` | tokio `bytes::Bytes`/`BytesMut` | NIO `ByteBuffer` | kotlinx.io `Buffer` |
|---|---|---|---|---|---|---|
| 参照カウント | あり（非 atomic） | あり（atomic） | Swift CoW（値型） | あり（`Arc`、atomic） | なし（GC） | なし（GC） |
| 所有権モデル | transfer | transfer | 値型 + CoW | move（Rust 標準） | 該当なし | 該当なし |
| reader / writer index | 分離 | 分離 | 分離 | 単一 cursor + `split_to` | `position` / `limit` 共有 | segmented |
| off-heap memory | あり（JVM/Native） | あり（pooled direct） | N/A（Swift 管理） | N/A（Rust 管理） | option（`allocateDirect`） | なし |
| 対応 platform | KMP 全 target | JVM | Apple platforms (Swift) | Rust 全 target | JVM | KMP |
| zero-copy slice | `allocator.slice(...)` | `slice()` / `retainedSlice()` | `slice()` / `readSlice(n)` | `Bytes::slice` / `split_to(n)` | `slice()` | segment reference |
| compaction | `compact()` | `discardReadBytes()` | `discardReadBytes()` | split で実質代替 | `compact()` | segment rebalance |

**design family の観点で整理すると**:

- **keel `IoBuf` / Netty `ByteBuf` / SwiftNIO `ByteBuffer`**: dual-index モデル（reader/writer 分離）+ 参照カウント（または CoW）の family。Netty の設計を基に、SwiftNIO は Swift の value semantics に合わせて CoW に、keel は KMP の single-thread EventLoop 前提で非 atomic に特化
- **tokio `bytes`**: `Bytes`（immutable、`Arc` による refcount sharing）と `BytesMut`（mutable、exclusive ownership）の 2 型に分離する Rust らしい設計。split 操作が Netty の slice + retain 相当
- **NIO `ByteBuffer`**: 単一 cursor（`position`/`limit`）+ GC 管理。低レベル primitive だが、`flip()` / `clear()` の mental model が独特
- **kotlinx.io `Buffer`**: segment 連結リストで GC 管理。`IoBuf` とは補完関係で、コーデック層の高水準 API として併用される

**要点**:

- **Netty 経験者**: Pipeline 層 (handler 間 ownership transfer) は同一 mental model。ただし `channel.write(buf)` が **retain-on-input** である点が Netty と異なる (Netty は transfer)。keel では caller が `buf.release()` を明示的に呼ぶ必要がある。他に「非 atomic refcount」「固定 capacity」の差がある
- **SwiftNIO 経験者**: API 形状は近い。違いは Swift の値型 + CoW ではなく明示的 `retain` / `release` で、`release()` 忘れが leak になる（SwiftNIO は参照カウントを言語機構に委ねる）
- **tokio `bytes` 経験者**: `IoBuf` は `BytesMut` に近いが、split による分離ではなく `retain()` で refcount を増やすのが主流
- **NIO 経験者**: reader と writer の index が分離しているため `flip()` を要しない。代わりに end-of-life で `release()` が必須
- **kotlinx.io 経験者**: `IoBuf` は低レベル（single buffer、segment list なし）であり、off-heap memory の挙動が予測可能。併用時は codec 層が `kotlinx.io Buffer`、transport 層が `IoBuf`

その他の近接 API として `.NET Memory<byte>` / `IMemoryOwner<byte>`（refcount 不使用、pool based）、Go `bytes.Buffer`（GC 管理、growable）、libuv `uv_buf_t`（C struct: base + len の view のみ）などがある。いずれも keel が直接参照する関係にはないため表からは省いている。

## 関連情報

- `IoBuf` KDoc: [`keel-io/.../buf/IoBuf.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/IoBuf.kt)
- `BufferAllocator` KDoc: [`keel-io/.../buf/BufferAllocator.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/BufferAllocator.kt)
- Netty reference counting: [Reference Counted Objects](https://netty.io/wiki/reference-counted-objects.html)
