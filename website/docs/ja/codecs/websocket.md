---
sidebar_position: 2
---

# WebSocket コーデック

`keel-codec-websocket` モジュールは RFC 6455 準拠の WebSocket フレーミングコーデックを提供します。依存関係は `kotlinx.io` のみです。SHA-1 は RFC 3174 に従い純粋な Kotlin で実装されているため、外部の暗号ライブラリは不要です。

すべての API（`parseFrame`、`writeFrame`）は同期的であり、`kotlinx.io.Source` / `Sink` を直接操作します。サスペンドバリアントはありません。

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

`parseFrame(source: Source)` を使って 1 フレームずつ読み取ります:

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

## 主要な型

| 型 | 備考 |
|---|---|
| `WsFrame` | `fin`、`rsv1`〜`rsv3`、`opcode`、`maskKey?`、`payload`。ファクトリ: `text()`、`binary()`、`ping()`、`pong()`、`close()`、`continuation()` |
| `WsOpcode` | 列挙型: `CONTINUATION`、`TEXT`、`BINARY`、`CLOSE`、`PING`、`PONG`。`isControl` / `isData` プロパティ |
| `WsCloseCode` | ステータスコード値（1000〜4999）。定数: `NORMAL_CLOSURE`、`GOING_AWAY`、`PROTOCOL_ERROR` 等。`isPrivateUse`（4000〜4999）、`isReserved`（1005、1006、1015） |

## エラー処理

| 例外 | スローされる場面 |
|---|---|
| `IllegalArgumentException` | `parseFrame` での未知のオペコード、RSV ビット非ゼロ、コントロールフレームのフラグメント化（`fin = false`）またはペイロード > 125 バイト、`WsCloseCode` が 1000〜4999 の範囲外 |

コントロールフレームの制約は `parseFrame` と `WsFrame` コンストラクタの両方で検証されます。そのため、無効なフレームを直接構築した場合もスローされます。

## RFC 準拠

- **RSV ビット**: RSV1〜3 は拡張がネゴシエーションされていない限りゼロでなければならず、非ゼロの場合は `IllegalArgumentException` をスローします
- **コントロールフレーム**: フラグメント化不可（`fin = true`）かつペイロードは 125 バイト以下 — RFC 6455 §5.5
- **マスキング**: クライアント→サーバーのフレームはマスク必須、サーバー→クライアントはマスク禁止 — RFC 6455 §5.3
- **クローズコード**（RFC 6455 §7.4.1）: 有効範囲は 1000〜4999。コード 1005、1006、1015（`isReserved`）はワイヤー上の Close フレームに設定してはなりません — これらは API 用途のみに定義されています

## ターゲット

`jvm` / `js (nodejs())` / `linuxX64` / `linuxArm64` / `macosArm64` / `macosX64`
