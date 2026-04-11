# TLS

keel は 4 つのバックエンドと 2 つの統合モードによるプラガブルな TLS サポートを提供します。

## バックエンド

| モジュール | プラットフォーム | ライブラリ | 備考 |
|--------|----------|---------|-------|
| `keel-tls-jsse` | JVM | JDK SSLContext（JSSE） | 常時インクルード。追加のビルドフラグ不要 |
| `keel-tls-openssl` | Native | OpenSSL（cinterop） | `-Ptls` ビルドフラグが必要 |
| `keel-tls-mbedtls` | Native | Mbed TLS（cinterop） | `-Ptls` ビルドフラグが必要 |
| `keel-tls-awslc` | Native | AWS-LC（cinterop） | `-Ptls` ビルドフラグが必要 |

## 2 つの TLS 統合モード

keel は TLS を keel が処理するか、基盤エンジンが処理するかによって 2 つの統合方法をサポートします:

| モード | 対応エンジン | 仕組み |
|---|---|---|
| **接続ごとの TLS** | kqueue、epoll、io_uring、NIO、Netty | `TlsHandler` が `ChannelPipeline` の先頭にインストールされます。keel が選択した TLS バックエンドを使って各バッファを暗号化／復号します。 |
| **リスナーレベル TLS** | NWConnection、Node.js | TLS は OS またはランタイムレベルでネゴシエーションされます。接続はパイプラインに届く時点ですでに復号済みです。`keel-tls-*` モジュールは不要です。 |

NWConnection と Node.js 以外の全エンジンでは**接続ごとの TLS** を使用します。NWConnection（macOS App Store）または Node.js でランタイムが TLS セッションを管理する場合は**リスナーレベル TLS** を使用します。

## TlsConfig

`TlsConfig` は接続の TLS 設定を保持します。1 つのインスタンスを複数の接続で再利用できます:

```kotlin
data class TlsConfig(
    val certificates: TlsCertificateSource? = null,  // サーバー証明書と秘密鍵
    val trustAnchors: TlsTrustSource? = null,         // null → SystemDefault（OS/JDK トラストストア）
    val verifyMode: TlsVerifyMode = TlsVerifyMode.PEER,
    val alpnProtocols: List<String>? = null,          // 例: ["h2", "http/1.1"]。null で ALPN 無効
    val serverName: String? = null,                   // SNI ホスト名（クライアントモード）。null で SNI 無効
)
```

## TlsCertificateSource

全バックエンドは `TlsCertificateSource` シール型インターフェースを通じて PEM または DER 形式の証明書を受け取ります:

```kotlin
sealed interface TlsCertificateSource {
    class Pem(val certificatePem: String, val privateKeyPem: String)
    class Der(val certificate: ByteArray, val privateKey: ByteArray)
    class KeyStoreFile(val path: String, val password: String, val type: String = "PKCS12")  // JVM のみ
    class SystemKeychain(val identityLabel: String)             // NWConnection のみ
}
```

`asPem()` / `asDer()` 拡張関数で `Pem` と `Der` の間の相互変換が行えます（PEM は Base64 エンコードされた DER）。`KeyStoreFile` と `SystemKeychain` は鍵の取り出しができないため、両関数とも例外を投げます。

## TlsTrustSource

`TlsTrustSource` はピアの証明書チェーンを検証する際に信頼する CA 証明書を制御します。`TlsConfig.trustAnchors` に設定してデフォルトを上書きします:

```kotlin
sealed interface TlsTrustSource {
    data object SystemDefault : TlsTrustSource    // OS/JDK トラストストア — trustAnchors が null の場合のデフォルト
    class Pem(val caPem: String) : TlsTrustSource // カスタム CA 証明書（PEM 形式）
    data object InsecureTrustAll : TlsTrustSource  // 検証を無効化 — テスト専用
}
```

`trustAnchors` を省略（`null`）すると `SystemDefault` が使用されます。内部 PKI を使う場合は `TlsTrustSource.Pem` に CA の PEM を渡します。`InsecureTrustAll` は `curl -k` 相当であり、本番環境では使用しないでください。

## TlsVerifyMode

`TlsVerifyMode` はピア証明書の検証方法を制御します。`TlsConfig.verifyMode` で設定してデフォルトを変更します:

```kotlin
enum class TlsVerifyMode {
    NONE,      // 検証なし — テスト用の自己署名証明書向け
    PEER,      // 提示された場合に検証するが必須としない（デフォルト）
    REQUIRED,  // 必須かつ検証 — 相互 TLS（mTLS）に使用
}
```

デフォルトの `PEER` は一般的なケースをカバーします: クライアントモードではサーバー証明書を検証し、サーバーモードではクライアント証明書が提示された場合に検証しますが必須とはしません。クライアント証明書認証（mTLS）を強制するには `REQUIRED` を設定します（クライアントが証明書を提供しない場合はハンドシェイクが失敗します）。`NONE` は検証を完全にスキップするためテスト専用です。

## アーキテクチャ

```
TlsConfig（証明書・トラストストア・ALPN・SNI）
    │
    ├── 接続ごとの TLS（ほとんどのエンジン）
    │   ├── TlsCodecFactory → パイプライン先頭に TlsHandler を追加
    │   │   └── protect() で送信を暗号化 / unprotect() で受信を復号
    │   └── NettySslInstaller → Netty 独自の SslHandler（JVM + Netty のみ）
    │
    └── リスナーレベル TLS（NWConnection / Node.js）
        installer = null → TLS はパイプラインコールバック前に OS/ランタイムが処理
        ├── NWConnection → nw_parameters_create_secure_tcp
        └── Node.js → tls.createServer()
```

### 接続ごとの TLS

`TlsCodecFactory` は接続ごとのコーデックを生成し、バッファ対バッファの操作で暗号化（`protect`）と復号（`unprotect`）を実行します。`TlsConfig` を構築し、ファクトリを生成し、両方を `TlsConnectorConfig` に渡します:

```kotlin
// kqueue、epoll、NIO 等での HTTPS
val tlsConfig = TlsConfig(
    certificates = TlsCertificateSource.Pem(
        certificatePem = File("cert.pem").readText(),
        privateKeyPem  = File("key.pem").readText(),
    ),
)
val factory: TlsCodecFactory = OpenSslCodecFactory()   // または JsseTlsCodecFactory()
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig, factory)) { channel ->
    // channel は平文を受け取る — パイプライン内の TlsHandler が暗号処理を担う
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    // ...
}
```

結果のパイプライン:

```
HEAD ↔ [TlsHandler] ↔ HttpDecoder ↔ Router ↔ TAIL
            │
   protect（暗号化）/ unprotect（復号）
```

ハンドシェイク完了後、`TlsHandler` は `TlsHandshakeComplete` ユーザーイベントをパイプラインに送出します。下流のハンドラで `onUserEvent` を実装して ALPN でネゴシエーションされたプロトコルを確認できます:

```kotlin
override fun onUserEvent(ctx: ChannelHandlerContext, event: Any) {
    if (event is TlsHandshakeComplete) {
        val protocol = event.negotiatedProtocol  // "h2"、"http/1.1"、または null
    }
    ctx.propagateUserEvent(event)
}
```

### リスナーレベル TLS

NWConnection と Node.js はトランスポートレベルで TLS をネゴシエーションします。`installer` 引数なし（デフォルトで `null`）で `TlsConnectorConfig` を渡すことでこのモードが有効になります:

```kotlin
// NWConnection または Node.js: OS/ランタイムが TLS を処理
engine.bindPipeline(host, port, TlsConnectorConfig(tlsConfig)) { channel ->
    // channel は平文を受け取る — パイプラインに TlsHandler なし
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    // ...
}
```

## PEM/DER ユーティリティ

- **`PemDerConverter`**: 双方向の PEM ↔ DER 変換。
- **`Pkcs8KeyUnwrapper`**: PKCS#8 エンベロープから内部の PKCS#1（RSA）または SEC 1（EC）鍵を取り出します。Apple の `SecKeyCreateWithData` が生の鍵バイトを必要とし、PKCS#8 ラッパーを受け付けないため必要です。

## NWConnection TLS

NWConnection は Apple の Security フレームワークを使用して、キーチェーンなしで DER エンコードされた証明書と鍵バイトから TLS アイデンティティを作成します:

```
cert DER → SecCertificateCreateWithData → SecCertificateRef
key  DER → SecKeyCreateWithData         → SecKeyRef
                                          ↓
         SecIdentityCreate(cert, key)   → SecIdentityRef
         sec_identity_create            → sec_identity_t
         nw_parameters_create_secure_tcp → nw_parameters_t（TLS 有効）
```

`SecIdentityCreate` は `Security/SecIdentity.h` で宣言されたパブリック API（macOS 10.12+）です。
