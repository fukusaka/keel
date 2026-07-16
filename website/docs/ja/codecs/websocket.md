---
sidebar_position: 2
---

# WebSocket コーデック

`keel-codec-websocket` モジュールは RFC 6455 準拠の WebSocket フレーミングコーデックを提供します。`keel-io` と `keel-core` に依存し（コーデックの I/O 境界は `IoBuf` とパイプラインハンドラ抽象）、`kotlinx.io` はフレームのパースと蓄積の内部でのみ使用します。SHA-1 は RFC 3174 に従い純粋な Kotlin で実装されているため、外部の暗号ライブラリは不要です。

本モジュールは 2 つの利用レイヤーを提供します:

1. **同期フレーム関数** —
   `parseFrame(source: Source, allowRsv1: Boolean = false): WsFrame` と
   `writeFrame(frame: WsFrame, sink: Sink)`。kotlinx-io の `Source` / `Sink` 上で動作し、テストや既にバッファ済みバイトを持つ呼び出し側向けです。
2. **パイプラインハンドラ** — `WsFrameDecoder` / `WsFrameEncoder`。トランスポートとは `IoBuf` を、アプリケーションとは `WsFrame` を交換します。`PipelinedChannel.addWsServerCodec()` でペアでインストールします。

## ハンドシェイク

WebSocket フレーミングに切り替える前に、HTTP アップグレードハンドシェイクを行います。`HttpHeaderName` は `keel-codec-http` モジュールの型です:

```kotlin
// サーバーサイド: クライアントキーの検証と Sec-WebSocket-Accept の計算
val clientKey = request.headers[HttpHeaderName.SEC_WEBSOCKET_KEY] ?: error("missing key")
if (!validateClientKey(clientKey)) error("invalid Sec-WebSocket-Key")
val acceptKey = computeAcceptKey(clientKey)
```

`validateClientKey` はキーが Base64 エンコードされた 16 バイトの nonce であることを確認します（RFC 6455 §4.2.1）。`computeAcceptKey` はキーと固定 GUID を連結し、Base64 エンコードされた SHA-1 ダイジェストを返します（RFC 6455 §4.2.2）。

## パース

`parseFrame(source: Source, allowRsv1: Boolean = false)` を使って 1 フレームずつ読み取ります（`allowRsv1 = true` はハンドシェイクで `permessage-deflate` をネゴシエーションした場合にのみ渡します）:

```kotlin
import io.github.fukusaka.keel.codec.websocket.*

val frame: WsFrame = parseFrame(source)
when (frame.opcode) {
    WsOpcode.TEXT         -> println(frame.payload.decodeToString())
    WsOpcode.BINARY       -> process(frame.payload)
    WsOpcode.PING         -> writeFrame(WsFrame.pong(frame.payload), sink)
    WsOpcode.CLOSE        -> { /* クローズ処理 */ }
    WsOpcode.CONTINUATION -> { /* フラグメントされたメッセージの再結合 */ }
    else                  -> { }
}
```

マスクされたペイロードは自動的にアンマスクされます — 受信フレームがマスクされていたかどうかに関係なく、`frame.payload` は常に生の（アンマスク済み）バイト列を返します。

## 書き込み

`writeFrame(frame: WsFrame, sink: Sink)` を使ってフレームを送信します:

```kotlin
// テキストフレーム — サーバー→クライアント、マスク不要
writeFrame(WsFrame.text("hello"), sink)

// テキストフレーム — クライアント→サーバー、マスク必須（RFC 6455 §5.3）
writeFrame(WsFrame.text("hello", maskKey = 0x37FA213D), sink)

// ステータスコード付きクローズフレーム
writeFrame(WsFrame.close(WsCloseCode.NORMAL_CLOSURE), sink)

// ステータスコードと理由テキスト付きクローズフレーム
writeFrame(WsFrame.close(WsCloseCode.GOING_AWAY, "server shutting down"), sink)

// ステータスコードなしクローズフレーム（空ペイロード — RFC 6455 §5.5.1）
writeFrame(WsFrame.close(), sink)
```

ファクトリメソッドと `maskKey` サポートの一覧:

| ファクトリ | `maskKey` パラメータ | 備考 |
|---|---|---|
| `WsFrame.text(text, maskKey, fin)` | あり | フラグメントメッセージには `fin = false` |
| `WsFrame.binary(data, maskKey, fin)` | あり | フラグメントメッセージには `fin = false` |
| `WsFrame.continuation(data, maskKey, fin)` | あり | 中間フラグメント |
| `WsFrame.ping(data)` | なし | 常にアンマスク。マスクする場合はコンストラクタを使用 |
| `WsFrame.pong(data)` | なし | 常にアンマスク。マスクする場合はコンストラクタを使用 |
| `WsFrame.close(code, reason)` | なし | コントロールフレーム、常にアンマスク |
| `WsFrame.close()` | なし | ステータスコードなし、常にアンマスク |

マスクされた ping/pong（クライアント→サーバー）には `WsFrame` コンストラクタを直接使用します:

```kotlin
WsFrame(fin = true, opcode = WsOpcode.PING, maskKey = 0x37FA213D, payload = data)
```

## Pipeline モード

Pipeline モードのサーバーでは、HTTP/1.1 ハンドシェイクが接続を WS フレーミングに引き渡した後（通常は HTTP コーデックスタックをパイプラインから取り外した後）、`addWsServerCodec` でハンドラペアをインストールします:

```kotlin
channel.addWsServerCodec(
    maxFramePayloadSize = WsFrameDecoder.DEFAULT_MAX_FRAME_PAYLOAD_SIZE,  // 16 MiB
    requireClientMasking = true,
    allowRsv1 = false,        // permessage-deflate をネゴシエーションした場合のみ true
    poolDataPayloads = false, // コンシューマが pooled payload を扱える場合のみ true
)
```

- `WsFrameDecoder`（インバウンド）は `IoBuf` チャンクを蓄積し、完全な `WsFrame` イベントを発出します。TCP セグメントをまたぐ部分フレームは次のチャンクで再開します。クライアントマスキングを検証し（`requireClientMasking`、RFC 6455 §5.1 に従いデフォルト有効 — コントロールフレームは検査対象外で、マスク必須なのはデータフレームのみ）、フレームあたりのペイロード長を制限し（`maxFramePayloadSize`、デフォルト 16 MiB、ペイロードバイトをバッファする前に拒否）、オプションでデータフレームのペイロードを pooled バッファにデコードします（`poolDataPayloads` → `WsFrame.inboundPayload`）。これにより受信パスはヒープ `ByteArray` への往復を回避できます。
- `WsFrameEncoder`（アウトバウンド）は各 `WsFrame` を新規の正確なサイズの `IoBuf` にシリアライズします。`payloadChunks` を持つフレームは gather 書き込みされます: ヘッダーは小さな `IoBuf` に書き込み、pooled ペイロードチャンクはそのまま伝播され、トランスポートで 1 回の `writev` にまとめられます。

`permessage-deflate` 拡張自体（RFC 7692 — ネゴシエーションと圧縮）は `keel-server-websocket` に実装されています。本コーデックはその基盤となる `allowRsv1` フックと pooled payload キャリアを公開します。

## ペイロードキャリア

`WsFrame` は 3 つの形式のいずれかでペイロードを保持します:

| キャリア | 備考 |
|---|---|
| `payload: ByteArray` | デフォルト — アンマスク済みペイロードバイト |
| `payloadChunks: IoBufChunks?` | 事前構築された pooled チャンク（例: `permessage-deflate` 出力）。encoder が連続コピーなしで gather 書き込み。サーバーアウトバウンド専用で、チャンクの所有権はフレームにあり、必ず 1 回だけ書き込むこと |
| `inboundPayload: IoBuf?` | decoder の `poolDataPayloads` 高速パスが生成する pooled かつアンマスク済みのペイロード。所有権はコンシューマにあり、必ず release すること。`payloadChunks` とは相互排他 |

## 主要な型

| 型 | 備考 |
|---|---|
| `WsFrame` | `fin`、`rsv1`〜`rsv3`、`opcode`、`maskKey?`、`payload` / `payloadChunks` / `inboundPayload`。ファクトリ: `text()`、`binary()`、`ping()`、`pong()`、`close()`、`continuation()` |
| `WsOpcode` | 列挙型: `CONTINUATION`、`TEXT`、`BINARY`、`CLOSE`、`PING`、`PONG`。`isControl` / `isData` プロパティ |
| `WsCloseCode` | ステータスコード値（1000〜4999）。定数: `NORMAL_CLOSURE`、`GOING_AWAY`、`PROTOCOL_ERROR` 等。`isPrivateUse`（4000〜4999）、`isReserved`（1005、1006、1015） |
| `WsFrameDecoder` / `WsFrameEncoder` | パイプラインハンドラ: `IoBuf` ↔ `WsFrame` |
| `WsCodecException` | パイプラインデコードパスでのプロトコル違反 |

## エラー処理

| 例外 | スローされる場面 |
|---|---|
| `WsCodecException` | パイプラインデコードパス限定: `maxFramePayloadSize` を超えるフレーム長（ペイロードバイトをバッファする前に拒否）、`requireClientMasking` 有効時のアンマスクなクライアントデータフレーム |
| `IllegalArgumentException` | 未知のオペコード、無効な RSV ビット、コントロールフレームのフラグメント化（`fin = false`）またはペイロード > 125 バイト、`WsCloseCode` が 1000〜4999 の範囲外 |

コントロールフレームの制約は `parseFrame` と `WsFrame` コンストラクタの両方で検証されます。そのため、無効なフレームを直接構築した場合もスローされます。

## RFC 準拠

- **RSV ビット**: RSV2/RSV3 はゼロでなければなりません。RSV1 は `allowRsv1 = true`（`permessage-deflate` 圧縮メッセージマーカー、RFC 7692 §7.2）でない限り拒否されます
- **コントロールフレーム**: フラグメント化不可（`fin = true`）かつペイロードは 125 バイト以下 — RFC 6455 §5.5
- **マスキング**: クライアント→サーバーのデータフレームはマスク必須（decoder が `requireClientMasking` で強制。コントロールフレームは検査対象外）、サーバー→クライアントはマスク禁止 — RFC 6455 §5.1
- **クローズコード**（RFC 6455 §7.4.1）: 有効範囲は 1000〜4999。コード 1005、1006、1015（`isReserved`）はワイヤー上の Close フレームに設定してはなりません — これらは API 用途のみに定義されています
- **拡張**: 本モジュールは RSV1 / pooled payload のフックを提供します。`permessage-deflate` 拡張自体（ネゴシエーション + 圧縮）は `keel-server-websocket` に実装されています

## ターゲット

`jvm` / `js (nodejs())` / `linuxX64` / `linuxArm64` / `macosArm64` / `macosX64`
