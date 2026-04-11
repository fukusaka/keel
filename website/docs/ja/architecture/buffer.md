---
sidebar_position: 3
---

# IoBuf と BufferAllocator

## IoBuf

`IoBuf` は keel のバイトバッファ型です。参照カウントでライフタイムを管理します:

| ターゲット | バッキングストレージ |
|---|---|
| JVM | `ByteBuffer.allocateDirect`（オフヒープ） |
| Native | `nativeHeap.allocArray<ByteVar>`（オフヒープ） |
| JS | `Int8Array`（V8 GC 管理） |

バッファは 3 つの領域に分かれています:

```
+-------------------+------------------+------------------+
| 読み捨て済みバイト  | 読み取り可能バイト  | 書き込み可能バイト  |
+-------------------+------------------+------------------+
|                   |                  |                  |
0      <=      readerIndex   <=   writerIndex    <=    capacity
```

`readableBytes`（`writerIndex - readerIndex`）は読み取り可能なバイト数、`writableBytes`（`capacity - writerIndex`）は残りの書き込み容量です。`compact()` を呼ぶと読み取り済みバイトを破棄して書き込み可能領域を回収できます — 読み取り可能領域をバッファ先頭に移動します。

バッファの使用が終わったら必ず `release()` を呼び出してください — これによりバッファがアロケータプールに返却されるか、ネイティブメモリが解放されます。`close()` は参照カウントを無視してメモリを強制解放します。通常のライフサイクル管理では `release()` を使用してください。

**スレッドセーフティ**: `IoBuf` は EventLoop スレッド内でのシングルスレッド使用を前提に設計されています。複数スレッドからの同時アクセスには外部同期が必要です。

## 参照カウント

あるオーナーが別のオーナーにバッファを渡す必要がある場合（例: パイプラインハンドラに渡す）、引き渡し前に `retain()` を呼び出します。各 `retain()` には対応する `release()` が必要です:

```kotlin
val buf = allocator.allocate(1024)
// ... buf にデータを書き込む ...

buf.retain()          // 別オーナーに引き渡す前に retain（参照カウントが 2 になる）
anotherOwner.use(buf) // anotherOwner が完了後に release() を呼ぶ

buf.release()         // 元オーナーが解放（参照カウントが 0 になり、解放またはプールへ返却）
```

参照カウントがゼロになると、バッファはアロケータに返却（プールあり）またはメモリ解放（プールなし）されます。

## BufferAllocator

`BufferAllocator` は `IoBuf` インスタンスの割り当て方法を制御するプラガブルインターフェースです。各エンジンはプラットフォームに最適なアロケータを使用します:

| アロケータ | ターゲット | 備考 |
|---|---|---|
| `SlabAllocator` | Native | EventLoop ごとの固定サイズスラブ、断片化が少ない |
| `PooledDirectAllocator` | JVM | `ByteBuffer.allocateDirect` プール |
| `DefaultAllocator` | 全ターゲット | シンプルな割り当て、プールなし — テスト・フォールバック用、および JS 本番のデフォルト（V8 GC が `Int8Array` を管理するためプール不要） |

`io_uring` エンジンは受信読み取りにカーネル管理の `ProvidedBufferRing` を使用するため、そのパスでは `BufferAllocator` を使用しません。

`IoEngineConfig` で設定します:

```kotlin
val engine = KqueueEngine(
    config = IoEngineConfig(
        allocator = SlabAllocator(bufferSize = 8192, maxPoolSize = 256),
    ),
)
```

## 大きなペイロードの最適化

`keel-ktor-engine` を通じて Ktor を使用する場合、レスポンスバイトは Ktor のレスポンス API から `BufferedSuspendSink` を経由して keel のトランスポート層に到達します。8 KiB 以上の大きなペイロードの場合、keel は中間のスクラッチバッファを介したコピーを省略し、バイト配列を `IoBuf` ビューとして直接トランスポートに渡します:

```kotlin
// Ktor ルートハンドラ内で:
call.respondBytes(smallPayload)  // < 8 KiB: スクラッチバッファ経由でコピー
call.respondBytes(largePayload)  // ≥ 8 KiB: IoBuf ビューとしてラップ、スクラッチバッファコピーを省略（JVM）
```

この最適化は JVM では `wrapBytesAsIoBuf` を通じて動作し、Kotlin 側のコピーなしで呼び出し元 `ByteArray` をヒープバック `ByteBuffer` ビューとしてラップします。NIO はヒープバッファを syscall ごとに 1 回 direct メモリへコピーしますが、8 KiB のスクラッチバッファで分割コピーするより大幅に高速です。Native と JS ではこの最適化は利用できず、すべての書き込みがチャンクコピーパスを通ります。

## リーク検出

keel はバッファリークを検出するための 2 つの補完的ツールを提供します:

| ツール | 用途 | 対応範囲 |
|---|---|---|
| `TrackingAllocator` | カウントベース: リークが存在するかを確認 | 全プラットフォーム |
| `LeakDetectingAllocator` | GC ベース: リークした各バッファの割り当てスタックトレースを報告 | Native（Cleaner）、JVM（PhantomReference） |

流暢な合成のために `withTracking()` と `withLeakDetection()` 拡張関数を使用します。`assertNoLeaks()` にアクセスできるよう `withTracking()` を最後に呼び出します:

```kotlin
val tracker = DefaultAllocator
    .withLeakDetection { msg -> fail(msg) }
    .withTracking()

// ... テストを実行 ...
tracker.assertNoLeaks()  // バッファが release されていない場合にスロー
```

`LeakDetectingAllocator` は割り当て箇所のスタックトレースをキャプチャします。バッファが release されずにガベージコレクトされると、`onLeak` コールバックがスタックトレースとともに呼び出されます。検出は GC に依存するため、テストでは明示的にトリガーしてください:

- **Native**: `kotlin.native.runtime.GC.collect()`
- **JVM**: `System.gc()`（ベストエフォートのヒント）の後、`tracker.allocate(1).release()` を呼び出して `PhantomReference` キューをドレインする
- **JS**: ノーオペレーション（GC 管理のため手動 release 不要）
