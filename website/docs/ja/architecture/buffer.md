---
sidebar_position: 3
---

# IoBuf と BufferAllocator

## 要点

`IoBuf` は keel のバイトバッファ型。使用パターンの 95% は次の 2 つのルールで尽きる:

1. **write は所有権を移譲する。** `channel.write(buf)` / `sink.write` / `ctx.propagateWrite` の後、buf は呼び出し側から消える — 触らない、`release()` しない、index も見ない。engine が flush 完了後に release する。
2. **read は所有権を保持する。** 呼び出し側が空の buf を allocate し、`channel.read(buf)` で engine に fill してもらう。その後 read して使い終わったら `release()` する。

以上。`retain()` / fan-out / slice / pool 挙動など細部は、この 2 ルールから意図的にはみ出すときだけ意識すれば良い。

```kotlin
// write
val buf = allocator.allocate(128)
buf.writeAscii("hello", 0, 5)
channel.write(buf)    // ここで所有権移譲。以降 buf に触らない
channel.flush()

// read
val buf = allocator.allocate(8192)
val n = channel.read(buf)
processData(buf)
buf.release()         // caller 所有、使い終わりで release
```

**Netty 経験者向け**: モデルは Netty の `ByteBuf` + `ctx.writeAndFlush(buf)` と同じ — write で transfer、keep したければ `retain()` を先に呼ぶ。差分は cosmetic な点だけ (固定 capacity、KMP target ごとの platform-native backing)。

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

`IoBuf` のスレッドセーフティ保証は API カテゴリで分かれる — Netty `ByteBuf` と同じ分け方である:

- **ライフサイクル（`retain()` / `release()` / `close()`）は thread-safe。** 参照カウントは atomic で、更新プロトコルは「現在値を check してから bump する」CAS loop である: 並行更新に負けた retain / release は新しい値で retry し、release 済み buffer を観測した呼び出しはカウントを乱さずに `IllegalStateException` を throw する。参照を保持している thread であれば、どの thread からでも調整なしに retain / release を呼べる。
- **content アクセス（read\*/write\*、`readerIndex` / `writerIndex`、`clear()`）は thread-safe ではない。** ある瞬間に buffer の content にアクセスできるのは最大 1 thread。content アクセスを別 thread に引き渡すには happens-before edge（EventLoop の `dispatch`、Netty `EventLoop.execute`、NWConnection の `dispatch_queue_async`、channel send 等）が必要で、引き渡し後は受け手 thread が唯一の content アクセス主体になる。

実際には keel は content アクセスもライフサイクルも channel を所有する EventLoop 上に揃えている:

- 全 engine で `ioDispatcher` を worker EventLoop に統一している。transport 層の操作は必ず EL 上で実行される。
- push 型 engine（NWConnection / Netty）も `NwConnectionQueueDispatcher` / `NettyEventLoopDispatcher` で EL thread alignment される。`SuspendBridgeHandler` 経由の callback → coroutine resume も同 EL 上で実行される。
- `Channel.write(buf)` は内部で write queue に積むが、dequeue + flush + release は同一 EL 上で行われる。

atomic なライフサイクルが効くのは、残る off-EL パターンである: 非 EventLoop coroutine から channel を消費する consumer や、別 thread で release する fan-out も、retain / release 自体は正しく動く — 避けるべきは buffer の *content* に別のアクセス主体と並行して触ることだけである。

### 契約違反が発生する典型ケース

ライフサイクル保証は content には及ばない。以下のコードは依然として契約違反である:

```kotlin
override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
    val buf = msg as IoBuf
    coroutineScope.launch(Dispatchers.Default) {
        // 下流 handler がまだ buffer に触っているかもしれない間に Default pool で実行:
        processAsync(buf)     // 並行 content アクセス — data race
        buf.release()          // release 自体は thread-safe だが race は直らない
    }
}
```

- EventLoop（または下流 handler）がまだアクセスしている buffer を `withContext(Dispatchers.IO)` 側から read / write する
- happens-before edge なしに plain field / collection 経由で buffer を別 thread に渡す — 受け手は stale な content や書きかけの index を観測しうる
- 2 thread が同じ buffer に「協調して」書き込む

症状は一般的な data race のそれである: 化けた / 古い byte 列、そして Native では並行 free の後に content アクセスが走った場合の segfault。一方で refcount の誤用は今は loud に失敗する: double-release や release 後の retain はカウントを壊さず `IllegalStateException` を throw する。

### Netty の atomic 方式との対比

かつての keel は refcount を非 atomic な plain `Int` で保持し、「thread を跨ぐ所有権移譲は必ず happens-before edge に乗る」ことに賭けていた。この賭けは GCD backed の NWConnection engine で外れた: GCD は 1 接続の callback を直列化するが OS worker pthread 間を移動させるため、worker を跨いだ refcount race が HTTPS 負荷時の間欠的な `Buffer already released` クラッシュとして顕在化した。以降 refcount は atomic である — Netty が `AtomicIntegerFieldUpdater` で到達したのと同じ結論である。fetch-and-add のコストが発生するのはライフサイクル遷移時のみで、byte 単位・read 単位では発生しない。

| | keel `IoBuf` | Netty `ByteBuf` |
|---|---|---|
| `refCount` | atomic（`AtomicInt`、CAS loop） | `AtomicIntegerFieldUpdater`（CAS） |
| cross-thread な retain / release | 参照保持者ならどの thread からでも可 | 可 |
| cross-thread な content アクセス | 契約違反（happens-before の引き渡しが必要） | 同じ契約 |
| double-release / release 後の retain | `IllegalStateException` を throw | `IllegalReferenceCountException` を throw |

残る差分は cosmetic である: 固定 capacity（動的 resize なし）と KMP target ごとの platform-native backing。

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
- `clear()` は両 index を 0 に reset し、buffer 全体を再び writable にする。`compact()` は存在しない — `IoBuf` は設計上 fixed-capacity であり、byte を詰め直す代わりに、消費済み buffer は pool へ release して新しい buffer を allocate する。

`readableBytes = writerIndex - readerIndex`、`writableBytes = capacity - writerIndex`。Netty `ByteBuf` の index モデルと同等である。

## 所有権モデル

モデルは **write は ownership transfer / read は非移譲** — 1 つのルールとその逆向き操作だけ。参照カウント起因のバグはほぼすべてこの区別を取りこぼすことに由来する。

### 中心ルール

> content の入った `IoBuf` を受け取って送出する API は、**参照を引き受ける**。呼び出し側は以降 buffer に触らない。

具体的に該当する API:

| 分類 | API |
|---|---|
| Transport 層の write | `Channel.write(buf)` / `IoTransport.write(buf)` / `SuspendSink.write(buf)` |
| Pipeline 層の write | `ctx.propagateWrite(msg)` |
| Pipeline 層の inbound 伝播 | `ctx.propagateRead(msg)` / `Pipeline.notifyRead(msg)` |
| user-event 伝播 | `ctx.propagateUserEvent(evt)` (`evt` が `IoBuf` を含む場合) |

「以降触らない」とは: `readByte` / `writeByte` 不可、`release()` 不可、`readerIndex` / `writerIndex` の inspect も不可。engine もしくは次 handler が使い終わった後に release する。

### Write

```kotlin
val buf = allocator.allocate(128)
buf.writeAscii("hello", 0, 5)
channel.write(buf)    // transfer 済み。buf は caller 視点で消えた
channel.flush()
// ここで buf.release() を呼ばない — transport が release する
```

write 後に `buf.release()` を書いてしまうと、次に transport が release しようとした時点で double-release エラー (`IllegalStateException: Buffer already released`) になる。

### Read は inverse

Read は transfer せず、**write の逆向き操作**: caller が空 buffer を allocate して engine に貸す。所有権は caller から離れない。

```kotlin
val buf = allocator.allocate(8192)
val n = channel.read(buf)   // engine が buf を埋める、caller が所有したまま
processData(buf)
buf.release()               // 使い終わったら caller が release
```

これは「3 番目の所有権モデル」というより、単に **`write` の反転**。caller が byte の受け手、engine が送り手になるため、所有権フローが逆向きに見えるだけ。

### engine 渡しの read (advanced)

NWConnection / Netty / Node.js のような push-model engine は、受信データを自分 (engine) の buffer に持っている。そのため caller 側で別 buffer を allocate して貸すのは無駄になる。こうした経路のために `OwnedSuspendSource.readOwned(): IoBuf?` が用意されている:

```kotlin
val source: OwnedSuspendSource = ...     // engine 提供（下記参照）
val buf = source.readOwned() ?: return   // null は EOF
// engine が出来合いの buf を渡した、以降 caller が所有
processData(buf)
buf.release()                              // 使い終わったら release
```

考え方は同じ: **最終的に buffer を手にした主体が release する**。`channel.read(buf)` との違いは **buffer の出所だけ**:

- `channel.read(buf)` — caller が allocate して engine に貸す → 使い終わったら release
- `readOwned()` — engine が allocate して caller に返す → 使い終わったら release

`readOwned` は「buffer を return する関数」と思えば良い (受け取る → 使う → release)。実際、下の API 分類では `allocator.allocate(...)` と並んで「新しい参照を返す API」側に分類される。

`OwnedSuspendSource` は engine integration 用 interface である — engine は pipeline bridge（`SuspendBridgeHandler` がこれを実装する）経由で露出し、push-model channel では `channel.asBufferedSuspendSource()` が内部でこれを消費する。Ktor / codec 層からは直接見えない。初見 keel 開発者は通常 `Channel.read(buf)` しか扱わないので、zero-copy push-mode read が必要になるまでこの節はスキップして構わない。

### API 所有権サマリ

**Transfer (caller は参照を手放す)**

| API | 誰が release するか |
|---|---|
| `Channel.write(buf)` / `IoTransport.write(buf)` / `SuspendSink.write(buf)` | transport、flush 完了後 |
| `ctx.propagateWrite(msg)` / `ctx.propagateRead(msg)` | 下流 / 上流 handler (最終 consumer) |
| `Pipeline.notifyRead(msg)` → pipeline HEAD | pipeline chain の最終 handler |
| `onRead(ctx, msg)` / `onReadTyped(ctx, msg)` | handler 自身 (慣用的に `try/finally`) |

**非移譲 (caller が参照を保持)**

| API | caller 責任 |
|---|---|
| `Channel.read(buf)` | buf を allocate した → 使い終わったら release |
| `buf.readByte()` / `writeByte()` / `getByte(i)` / `readByteArray(...)` / `writeByteArray(...)` | buf を所有、index のみ進む |
| `buf.copyTo(dst, length)` | source も dst も caller 所有 |
| `buf.clear()` | 所有権不変 |

**新しい参照を返す API (caller が所有権を取得)**

| API | 初期 refCount | 誰が release するか |
|---|---|---|
| `allocator.allocate(size)` | 1 | 最終 consumer |
| `allocator.wrapBytes(bytes, offset, length)` | 1 (JS は `null`) | 最終 consumer (入力 `ByteArray` は caller 所有のまま) |
| `allocator.slice(src, offset, length)` | 1 (`src` と独立) | slice 所有者 (`src` は allocator 内部で track) |
| `buf.retain()` | 既存 +1、同 instance | 追加参照を作った主体 |
| `OwnedSuspendSource.readOwned()` | 1 (EOF は `null`) | caller (engine が return 経由で transfer、「engine 渡しの read」節参照) |

### transport 層で index は advance されない

`channel.write(buf)` の後、`buf.readerIndex` / `buf.writerIndex` は **変化しない** — engine は pending-writes queue に snapshot として捕らえ、live buffer を mutate しない。Netty の `ChannelOutboundBuffer` の semantic と一致。

caller は transfer したので buffer を見ないはずだが、もし `retain()` で保持した主体が index を確認すると、**write 時点の状態のまま**の buffer が見える — 直感に反しない答えが返る。

### `retain()` を使うタイミング

`retain()` は transfer ルールから意図的にはみ出すときだけ意識する。現実的なケースは 3 つ:

**(1) Fan-out: 同じ buf を複数 sink に送る**

```kotlin
channel1.write(buf.retain())   // channel1 用に +1
channel2.write(buf.retain())   // channel2 用に +1
channel3.write(buf)             // 最後に元参照
```

N 個の sink に送るなら `N - 1` 個に `retain()` を呼ぶ。Netty 使いには馴染みのパターン。

**(2) 後で使うため保持しておく**

```kotlin
class DelayedEcho : TypedInboundHandler<IoBuf>(IoBuf::class) {
    private var cached: IoBuf? = null

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        cached = msg.retain()       // handler 用に +1
        ctx.propagateRead(msg)       // 元参照を下流に移譲
        // autoRelease (デフォルト true) は同一オブジェクトを propagate した
        // 場合 release を skip する。handler 自身の参照は上の retain() が
        // 生かし続ける。
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        cached?.release()            // 上の retain と対の release
        cached = null
    }
}
```

**(3) write 前に suspension を跨いで保持**

```kotlin
suspend fun relay(src: Channel, dst: Channel) {
    val buf = allocator.allocate(8192)
    try {
        val n = src.read(buf)                // 非移譲
        if (n > 0) dst.write(buf.retain())  // 1 つ transfer、自分は keep
        // buf はここでもまだ使える
    } finally {
        buf.release()                         // caller 自身の ref
    }
}
```

もし `dst.write` 行で `retain()` しなければ、dst.write から返った時点で buf は消えており、`finally` の `release()` は double-release になる。「境界を跨いで keep する」たびに `retain()` を 1 つ添える。

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

## 典型的な不具合パターン

| 不具合 | 症状 | 原因 |
|---|---|---|
| `channel.write(buf)` 後に余分な `release()` | 次の transport 操作時に `IllegalStateException: Buffer already released` | transport が flush 後に release 済。caller 側で `release()` すると double-release |
| propagate しない handler で `release()` 漏れ | memory leak (`TrackingAllocator` で検出可能) | handler が msg を受け取って `propagateRead(msg)` も `msg.release()` も呼ばなかった。どちらか一方は必須 |
| use-after-write | Native で segfault、JVM で invalid data、JS で silent corruption | `channel.write(buf)` 後に buffer を触った (byte 読み、`readerIndex` 確認等) |
| `retain()` なしの fan-out | 1 回目は成功、2 回目は `readableBytes == 0` か throw | 1 回目の `channel.write` で所有権が移った。2 回目は解放済み buf を受け取る。最後以外は `buf.retain()` |
| handler が retain なしで msg を field 保存 | 後続 event で use-after-release | 下流で release された msg を handler が stale ref として持っている。保存前に `retain()` |

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

`IoBuf` 生成の pluggable interface である。各 engine は platform に応じた既定値（`defaultAllocator()` 経由）を持ち、必要に応じて `IoEngineConfig` で上書き可能である — 例えば stats counter や lifecycle listener を組み込む場合:

```kotlin
val engine = KqueueEngine(
    config = IoEngineConfig(
        allocator = SlabAllocator(),   // Native の pooled allocator、既定の size-class ladder
    ),
)
```

### 3 実装の比較

| 実装 | Target | pooling | freelist の並行性 |
|---|---|---|---|
| `DefaultAllocator` | 全 target | なし（毎回 fresh allocation） | stateless |
| `SlabAllocator` | Native | chunk ベースの `PooledAllocator` | size class 毎の spin-lock + cross-thread 用 MPSC return queue |
| `PooledDirectAllocator` | JVM | chunk ベースの `PooledAllocator` | size class 毎の mutex（`ReentrantLock`） |

### createChild() の役割

engine は `allocator.createChild()` を呼び、自身がライフサイクルを管理する child allocator を得る — thread 固定型 engine（epoll / kqueue / NIO / io_uring）では EventLoop thread ごとに 1 つ、per-thread 分割のない engine（NWConnection、Node.js）では engine ごとに 1 つ。pool 系の parent は child ごとに専用の size-class freelist cache を持たせつつ、全 child で parent の chunk arena を共有する。parent は child を追跡し、`close()` で cascade-close する。`IoEngineConfig` に渡した parent instance 自身は、これらの engine の hot path では割り当てを行わない。ただし parent 経由で割り当てる engine が禁じられているわけではない — テストで使う in-memory engine は flush ごとに parent を経由してコピーする。兄弟メソッドの `createUntrackedChild()` は caller 自身が close する child を返す — accepted connection ごとに 1 allocator のような、無制限に増減する population 向けである。返るものが新しいとは限らない: 既定のチェーンは `createChild()` の `this` で終わり、wrapper は delegate の答えを外へ転送するので、他人の allocator を close してはならない caller には、見分けさせるのではなく実際に child を作る allocator を渡すべきである。

### engine が allocator に要求すること

engine は read buffer のメモリを無検査 cast で kernel へ直接渡す — Native target では `NativePointerAccess`、JVM では `NioByteBufferBacking`、JS では `TypedArrayIoBuf`。engine と組み合わせるカスタム allocator は、その backing を持つ buffer を配らなければならず、子も同様である（engine が読むのは子だからである）。epoll と kqueue の engine は構築中に 1 度だけ問い、満たさなければ allocator を名指して起動を拒否する。他の engine では同じ誤りが後になって現れるが、その形は互いに比較できない — 実測では、NIO は connection を落とさず client が timeout するまで hang させ、NWConnection は Kotlin の frame が無い dispatch queue から送出するため accept の時点で process を abort させる。Netty engine は対象外である: buffer は各 channel 自身の `ByteBufAllocator` から確保し、設定された allocator は lifecycle listener の運搬にだけ使う。

### `DefaultAllocator`

毎回新規割り当てする stateless 実装で pool を持たない。`createChild()` は `this` を返す。test / fallback / JS の既定値として使用する。

- **`wrapBytes`**: `null` を返す（zero-copy wrap 非対応）
- **`slice`**: zero-copy。source を retain し、slice 自身の release 時に source を release する `SliceOwner` 付きの platform-native view を返す

### chunk ベース pooling（`SlabAllocator` / `PooledDirectAllocator`）

2 つの platform pool は、共通の chunk ベース pool 骨格 `PooledAllocator` の薄い facade である。設計は jemalloc / Netty の定石に従う:

- **size-class ladder**: `allocate` 要求は、それを収容できる最小の size class に **切り上げ**られ（16 byte quantum、倍化ごとに 4 class、内部フラグメンテーションは最悪 ~20–25 %）、その class の freelist から供給される — 事前登録したサイズだけでなく、あらゆる要求サイズが poolable になる。返却 buffer の capacity は class サイズであり、`allocate` の「少なくとも `capacity` byte」という契約を満たす
- **chunk arena**: pool miss は buffer ごとに platform heap を叩かない。buffer は sharded arena（EventLoop あたりおよそ 1 shard、各 shard は専用 lock で保護）に保持された大きな chunk の部分領域として carve される。arena は 1 つの root allocator の全 child で共有され、carve された buffer の解放はその run を chunk に返す
- **総 byte 予算**: `maxTotalBytes`（デフォルト 2 MiB）が freelist の保持しうる worst-case byte 数に上限を課す。slot 数は install 時に clamp され、freelist は lazy に埋まるため、実際の常駐量は上限を大きく下回る
- **大きな allocation は pool を bypass**: 最大 cached class（32 KiB）を超える要求は正確なサイズで確保され、release で解放される。pool には入らない（256 KiB は別の定数で、arena が pooled buffer を carve する chunk のサイズ）
- **`hintSizeClass(byteSize, maxCount)`** は best-effort な warm-cache hint であり契約ではない: 同一サイズへの重複 hint は no-op で、`maxCount` は予算内に収めるため下方 clamp されうる

freelist の並行性戦略は platform ごとに異なる。`SlabAllocator`（Native）は size class 毎の spin-lock `ArrayDeque` — EL 固定型 engine は無競合でアクセスするため実質タダ — に加えて、release 時の confinement 判定を行う: 所有 thread（または GCD queue）以外からの release は freelist に触らず、lock-free な MPSC return queue 経由で所有者に返送され、off-EventLoop consumer を安全に保つ。`PooledDirectAllocator`（JVM）は size class 毎に `ReentrantLock` で保護された freelist を使う。かつての lock-free stack 設計は真の並行性（off-EventLoop の `asSource` refill が EventLoop の read path と競合するケース）で ABA-unsafe だったため置き換えられた — 無競合時のコスト差は pool 往復あたり数 ns である。

ここで名前を挙げた pool 内部（chunk arena、shard、freelist 型）は API ではなく実装詳細として扱うこと: リリース間で調整・再構成される。

zero-copy 操作は両 pool が実装する:

- **`wrapBytes`**: Native では pin した `ByteArray` + `CPointer` の view（release 時に unpin）、JVM では `ByteBuffer.wrap` の view。backing array は caller 所有であり、buffer の release まで mutate 禁止
- **`slice`**: 両 platform で zero-copy。source を retain し、slice のカウントが 0 に到達した時点で source を release する `SliceOwner` を仕込む

### io_uring engine の特殊性

io_uring engine の inbound read path のみ `BufferAllocator` を経由せず、kernel 管理の `ProvidedBufferRing` スロットを `RingBufferIoBuf` として露出する。他の read path および全 write path は通常の allocator を通る。send 側では追加で、buffer メモリを kernel に事前登録できる — public な engine オプション `RegisteredBufferStrategy` で制御し（デフォルト `STATIC`: 起動時に EventLoop ごとの slot 一式を登録）、登録済みメモリ上のデータを送る zero-copy send は `SEND_ZC_FIXED` として dispatch され（per-send の page pinning を省略）、それ以外は通常の `SEND_ZC` に自動 fallback する。

## platform 別実装

`IoBuf` interface の具象実装は platform ごとに異なる。keel-io の 3 実装は共通骨格（`AbstractIoBuf`）を共有しており、index ペア・**atomic な refcount**・owner dispatch はそこが担う。`writeByte` / `readByte` は bounds check を省略して hot path を薄く保ち、bulk 操作（array ベースの read/write、`copyTo`）にのみ bounds check を行う。

### 4 実装の比較

| 実装 | Target | backing storage | `close()` 挙動 |
|---|---|---|---|
| `DirectIoBuf` | JVM | `ByteBuffer.allocateDirect` または pooled chunk の carved view | refcount を 0 に強制。direct buffer は JVM GC 任せ |
| `NativeIoBuf` | Native | `nativeHeap.allocArray<ByteVar>` または pooled chunk の carved view | refcount を 0 に強制。所有する heap memory を free（idempotent） |
| `TypedArrayIoBuf` | JS | `Int8Array(capacity)`（V8 heap） | refcount を 0 に強制。array は V8 GC が回収 |
| `RingBufferIoBuf` | io_uring engine | `ProvidedBufferRing` slot（kernel 管理） | slot を放棄（`AutoCloseable` 互換用、通常 path は `release()`） |

### `DirectIoBuf` (JVM)

- **backing storage**: 単体の `ByteBuffer.allocateDirect(capacity)`、`PooledDirectAllocator` が carve した pooled chunk の view、または外部から渡された `ByteBuffer`（wrap path）。capacity は構築時に固定
- **refCount / release path**: `AbstractIoBuf` から継承 — atomic CAS。0 到達時に `owner.release(this)` が backing 戦略（pool 返却、slice parent の release、unpin、GC no-op）を実行する
- **close 挙動**: escape hatch — atomic CAS で refcount を 0 に強制し owner をスキップ。direct buffer は JVM GC 任せ
- **`writeByte` / `readByte`**: `buf.put(writerIndex++, value)` / `buf.get(readerIndex++)`、bounds check なし（hot path 薄化）
- **`writeByteArray` / `readByteArray`**: bounds check あり、`ByteBuffer.put(src, offset, length)` / `get(dst)` 経由で bulk copy
- **`clear`**: 両 index を reset し、backing `ByteBuffer` の position/limit も巻き戻す（直前の `SocketChannel.write` が残した limit のままだと absolute `put()` が壊れるため）
- **`copyTo`**: duplicate view を作り `ByteBuffer.put` 経由で転送
- **engine accessor**: `unsafeBuffer: ByteBuffer`。`@UnsafeIoBufApi` opt-in の背後にあり、NIO syscall による zero-copy I/O に使用
- **`wrapExternal`**: companion factory で pre-allocated `ByteBuffer` を wrap（`writerIndex = bytesWritten` で初期化）。任意の custom `IoBufOwner` を渡せる — 外部リソース wrap の JVM 側 seam

### `NativeIoBuf` (Native: Linux / macOS)

- **backing storage**: 単体の `nativeHeap.allocArray<ByteVar>(capacity)`、pooled chunk の carved view、または external / slice view。内部の `ownsMemory` flag が「buffer が native allocation を所有しているか」を記録する（view と wrap は所有しない）
- **refCount / release path**: `AbstractIoBuf` から継承 — atomic CAS。0 到達時に `owner.release(this)`。所有 heap allocation の場合、`HeapOwner` は buffer 自身の free routine に委譲し、`nativeHeap.free` をちょうど 1 回呼ぶ（`freed` flag が二重 free を防止）。chunk-carved view の場合は代わりに run を chunk に返す。**4 impl 中、実際の native メモリ解放を行うのは本 impl のみ**
- **close 挙動**: escape hatch — atomic CAS で refcount を 0 に強制し、所有 backing を直接 free（custom owner はスキップ）。idempotent
- **`writeByte` / `readByte`**: `ptr[writerIndex++] = value` / `ptr[readerIndex++]`、bounds check なし
- **`writeByteArray` / `readByteArray`**: bounds check あり、pin + `memcpy(ptr + index, src, length)` で bulk copy
- **`copyTo`**: dest が `NativePointerAccess` (`unsafePointer` を持つ) であれば `memcpy` で直接転送
- **engine accessor**: `unsafePointer: CPointer<ByteVar>`。`@UnsafeIoBufApi` opt-in の背後にあり、POSIX syscall (`read(2)` / `write(2)` / `writev(2)`) に使用
- **外部 wrap**: public な seam はトップレベルの `wrapExternalNativePtr(ptr, length, unpin)` で、外部所有の native memory を wrap し refcount 0 到達時に `unpin` を 1 回呼ぶ。allocator 側の経路（pin する `wrapBytes`、slice）は明示 owner 付きの internal companion factory を使う

### `TypedArrayIoBuf` (JS)

- **backing storage**: `Int8Array(capacity)` を V8 heap に確保、`capacity = array.length`
- **refCount / release path**: `AbstractIoBuf` から継承 — 他 platform と同じ atomic 契約（JS は single-threaded なので競合はそもそも起きない）。0 到達時に `owner.release(this)`
- **close 挙動**: escape hatch — refcount を 0 に強制し owner をスキップ。backing `Int8Array` は V8 GC 任せ
- **`writeByte` / `readByte`**: `base.asDynamic()[writerIndex++] = value` / `(base.asDynamic()[readerIndex++] as Int).toByte()`。Kotlin/JS IR mode では typed array の indexing が直接 compile できないため `asDynamic()` で V8 native operation に直結
- **`writeByteArray` / `readByteArray`**: bounds check あり、element-wise loop で `asDynamic()` 経由
- **`copyTo`**: `destBuf.set(buf.subarray(readerIndex, ...), dest.writerIndex)` で V8 typed-array bulk copy
- **engine accessor**: `unsafeArray: Int8Array` （property + extension）、Node.js `net.Socket.write` 等に使用
- **`wrapExternal`**: internal companion factory で pre-allocated `Int8Array` を wrap、`writerIndex = bytesWritten`。JS heap は GC 管理のため default owner は no-op

### `RingBufferIoBuf` (io_uring engine)

他の 3 impl と性格が大きく異なる。**allocator を介さず、kernel 提供の buffer ring slot を直接 `IoBuf` として扱う wrapper 専用** 実装である。engine-direct であり、`AbstractIoBuf` を継承せず `IoBuf` を直接実装して backing を自己管理する。

- **backing storage**: `ProvidedBufferRing` の slot。pointer は `bufferRing.getPointer(bufId)` で算出し、コンストラクタで cache（property 毎のアクセスで再計算しない）
- **refCount**: plain な `Int` — buffer が所有 EventLoop の外に出ないため、ここでは安全
- **lifecycle**: 新規 `allocate` は存在しない。source 起動時に slot 単位で事前生成され、CQE callback で `reset()`（index と `refCount = 1` を初期化、ptr と bufferRing 設定は保持）して再利用。**hot path での object 生成ゼロ**
- **release path**: `refCount` が 0 に到達した時点で、slot は `ProvidedBufferRing.returnBuffer(bufId)` により ring に直接返却される — `IoBufOwner` dispatch は介在しない
- **close 挙動**: escape hatch — slot を返却せず `refCount = 0` をセットする。そのため slot は **意図的に放棄** される（`AutoCloseable` 互換の最後の砦、通常 path は `release()` 経由）
- **`writeByte` / `readByte` / bulk ops**: `NativeIoBuf` と同構造（`ptr[index++]` / `memcpy` / `memmove`）
- **engine accessor**: `unsafePointer: CPointer<ByteVar>`（`NativePointerAccess` 実装）、`io_uring_prep_recv` 等の SQE 提出に使用
- **platform-unique**: provided buffer ring の inbound path は CQE ごとの割り当てが皆無。（send 側の registered「fixed」buffer は既に出荷済みの別機構 — 上の allocator 節の io_uring 注記を参照）

### 共通の設計方針

- **keel-io 実装の `refCount` は atomic**: `AbstractIoBuf` が `DirectIoBuf` / `NativeIoBuf` / `TypedArrayIoBuf` に「スレッドセーフティ契約」節の thread-safe なライフサイクルを与える。1 つの EventLoop に閉じた engine-direct 実装（`RingBufferIoBuf` 等）は plain なカウントでも良い
- **単要素 read/write は bounds-check なし**: hot-path throughput 最大化のための意図的 trade-off。境界越えは caller 責任
- **bulk 操作のみ bounds-check**: length 引数を受ける `writeByteArray` / `readByteArray` / `copyTo` は明示検査で early failure させる
- **engine-specific accessor**: 各 impl が platform native memory primitive を `unsafe*` 名で露出し、JVM / Native では `@UnsafeIoBufApi` opt-in の背後に置く。interface には含めない（platform-specific なので common API にできない）

## 大規模 payload の最適化

本節は `keel-server-ktor` で大規模 response を送信する際に発生する性能問題と、その自動解消機構を説明する。通常の Ktor handler 実装では意識不要だが、挙動を理解しておくと benchmark 結果の解釈や、大規模 file / streaming body の送信設計に有益である。

### 背景: `BufferedSuspendSink` の役割

Ktor application が response を書く経路は以下である。

1. Handler が `call.respondBytes(byteArray)` または `call.respondOutputStream { ... }` を呼ぶ
2. Ktor の write path が `keel-server-ktor` の transport adapter に到達する
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

## Backing ownership strategies

refcount の流れ (誰が `retain` / `release` を呼ぶか) とは直交する次元として、**refcount=0 到達時に backing memory に何が起こるか** — native allocation を free するか、pool に返すか、wrap された外部 array を unpin するか、kernel 管理 slot を返すか、等 — がある。

keel はこれを [`IoBufOwner`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/IoBufOwner.kt) (plain interface) で表現する。keel-io の buffer には生成時に owner が装着され、`IoBuf.release()` が refcount=0 到達時に `owner.release(this)` を 1 回だけ呼ぶ。（`IoBuf.close()` は owner を bypass する — それこそが escape hatch たる所以である。）

### 戦略一覧

| 戦略 | backing | 追加 state | refcount=0 時の挙動 |
|---|---|---|---|
| `HeapOwner` (singleton) | `nativeHeap` / `ByteBuffer.allocateDirect` / `Int8Array` | なし | buffer 自身の free routine 経由で解放 — Native は `nativeHeap.free`、GC 管理の JVM/JS backing では no-op |
| `PoolOwner` | platform backing、pool が管理 | `returnToPool` lambda | buffer を size-class freelist に返却 |
| `SliceOwner` | 親 `IoBuf` の部分領域 | `parent` ref | `parent.release()` |
| `ExternalWrapOwner` | caller の pinned `ByteArray` 等の外部リソース | `unpin` lambda | pin / 外部 hold を解除 |

この 4 実装は keel-io の `internal` である — keel-io 固有の概念 (pool、slice、外部 wrap) をエンコードするもので、public API には含まれない。`IoBufOwner` interface 自体はあえて **sealed でない plain interface** かつ public: 外部コードが独自戦略を実装し、`IoBufOwner` を受け付ける API に渡せる — 現在は JVM の `DirectIoBuf.wrapExternal`。Native では `wrapExternalNativePtr` が `unpin` lambda を受け取り、内部で `ExternalWrapOwner` に wrap する。

engine-direct な `IoBuf` 実装は owner taxonomy の完全に外側にいる: `RingBufferIoBuf` (engine-io-uring) は kernel 管理 slot を `ProvidedBufferRing` に直接返却し、`NettyByteBufIoBuf` (engine-netty) は wrap した Netty `ByteBuf` 自身の参照カウントに委譲する — どちらも `IoBuf` を直接実装し、`IoBufOwner` なしで backing を自己管理する。

io_uring の registered (fixed) buffer も owner 戦略ではない: engine は public な engine オプション `RegisteredBufferStrategy`（デフォルト `STATIC`）に従って buffer メモリを kernel に事前登録し、write path は buffer の pointer を EventLoop ごとの registry で lookup して `SEND_ZC_FIXED` を選択する — lookup が外れたら通常の zero-copy send に fallback する。この dispatch に owner 型は介在しない。

### 実用上どこで効くか

通常の caller は意識しない。`allocator.allocate(size)` が backing 対応の owner を装着した `IoBuf` を返し、`buf.release()` が自動的に正しい動作をする。engine も common path では owner を query しない。

taxonomy の実用価値:

- **Pool 返却 path**: `PoolOwner` は pool instance を 1 つだけ保持し、同 pool の全 allocate で使い回される — pool hit path は closure 生成なし
- **Slice 安全性**: `SliceOwner` は retain 済みの parent ref のみを持つため、pooled な parent は未解放の slice view がすべて release されてからでないと freelist に戻らない — view が backing より長生きすることはない
- **leak detector**: `TrackingAllocator` / `LeakDetectingAllocator` は keel-io buffer 型の internal seam 経由で owner を in-place に wrap することで、すべての release が counting / stack-recording decorator を通ってから実 owner に届く。この seam を持たない engine-direct buffer は、別チャネルの `BufferAllocatorLifecycleListener` 観測機構がカバーする

## 他の buffer API との比較

keel を触る開発者の多くは、他の networking ライブラリで既に何らかの buffer API に馴染んでいる。以下は代表的な 6 実装との対応表である。

| 特性 | keel `IoBuf` | Netty `ByteBuf` | SwiftNIO `ByteBuffer` | tokio `bytes::Bytes`/`BytesMut` | NIO `ByteBuffer` | kotlinx.io `Buffer` |
|---|---|---|---|---|---|---|
| 参照カウント | あり（atomic） | あり（atomic） | Swift CoW（値型） | あり（`Arc`、atomic） | なし（GC） | なし（GC） |
| 所有権モデル | transfer | transfer | 値型 + CoW | move（Rust 標準） | 該当なし | 該当なし |
| reader / writer index | 分離 | 分離 | 分離 | 単一 cursor + `split_to` | `position` / `limit` 共有 | segmented |
| off-heap memory | あり（JVM/Native） | あり（pooled direct） | N/A（Swift 管理） | N/A（Rust 管理） | option（`allocateDirect`） | なし |
| 対応 platform | KMP 全 target | JVM | Apple platforms (Swift) | Rust 全 target | JVM | KMP |
| zero-copy slice | `allocator.slice(...)` | `slice()` / `retainedSlice()` | `slice()` / `readSlice(n)` | `Bytes::slice` / `split_to(n)` | `slice()` | segment reference |
| compaction | 設計上なし（fixed capacity、release + 再 allocate） | `discardReadBytes()` | `discardReadBytes()` | split で実質代替 | `compact()` | segment rebalance |

**design family の観点で整理すると**:

- **keel `IoBuf` / Netty `ByteBuf` / SwiftNIO `ByteBuffer`**: dual-index モデル（reader/writer 分離）+ 参照カウント（または CoW）の family。Netty の設計を基に、SwiftNIO は Swift の value semantics に合わせて CoW に、keel は KMP に適応 — atomic なライフサイクル、single-thread の content アクセス、固定 capacity
- **tokio `bytes`**: `Bytes`（immutable、`Arc` による refcount sharing）と `BytesMut`（mutable、exclusive ownership）の 2 型に分離する Rust らしい設計。split 操作が Netty の slice + retain 相当
- **NIO `ByteBuffer`**: 単一 cursor（`position`/`limit`）+ GC 管理。低レベル primitive だが、`flip()` / `clear()` の mental model が独特
- **kotlinx.io `Buffer`**: segment 連結リストで GC 管理。`IoBuf` とは補完関係で、コーデック層の高水準 API として併用される

**要点**:

- **Netty 経験者**: mental model は完全一致 — pipeline 層 (handler ↔ handler) も `Channel.write` 境界も同様に ownership transfer。keep したければ `buf.retain()` を write 前に呼ぶ、という流儀も同じ。refcount も Netty 同様 atomic。残る違いは固定 capacity (動的 resize 無し) のみ
- **SwiftNIO 経験者**: API 形状は近い。違いは Swift の値型 + CoW ではなく明示的 `retain` / `release` で、`release()` 忘れが leak になる（SwiftNIO は参照カウントを言語機構に委ねる）
- **tokio `bytes` 経験者**: `IoBuf` は `BytesMut` に近いが、split による分離ではなく `retain()` で refcount を増やすのが主流
- **NIO 経験者**: reader と writer の index が分離しているため `flip()` を要しない。代わりに end-of-life で `release()` が必須
- **kotlinx.io 経験者**: `IoBuf` は低レベル（single buffer、segment list なし）であり、off-heap memory の挙動が予測可能。併用時は codec 層が `kotlinx.io Buffer`、transport 層が `IoBuf`

その他の近接 API として `.NET Memory<byte>` / `IMemoryOwner<byte>`（refcount 不使用、pool based）、Go `bytes.Buffer`（GC 管理、growable）、libuv `uv_buf_t`（C struct: base + len の view のみ）などがある。いずれも keel が直接参照する関係にはないため表からは省いている。

## 関連情報

- `IoBuf` KDoc: [`keel-io/.../buf/IoBuf.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/IoBuf.kt)
- `BufferAllocator` KDoc: [`keel-io/.../buf/BufferAllocator.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/BufferAllocator.kt)
- Netty reference counting: [Reference Counted Objects](https://netty.io/wiki/reference-counted-objects.html)
