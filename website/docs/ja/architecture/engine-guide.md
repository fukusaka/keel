---
sidebar_position: 2
---

# エンジン選択ガイド

keel は複数のエンジン実装を提供します。**エンジンは、追加する `keel-engine-*` Gradle 依存関係によってコンパイル時に選択されます** — ランタイムでの切り替えはありません。

KMP プロジェクトでは、通常ターゲットのソースセットごとに異なるエンジン依存関係を追加します:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        linuxX64Main.dependencies {
            implementation("io.github.fukusaka.keel:keel-engine-epoll:0.3.0")
        }
        macosArm64Main.dependencies {
            implementation("io.github.fukusaka.keel:keel-engine-kqueue:0.3.0")
        }
        jvmMain.dependencies {
            implementation("io.github.fukusaka.keel:keel-engine-nio:0.3.0")
        }
    }
}
```

全エンジンは同じ `StreamEngine` インターフェースを実装しているため、アプリケーションコードはターゲット間で同一です。

## エンジン一覧

コンストラクタパラメータや設定オプションの詳細は [API リファレンス](/api/) を参照してください。

### NIO (`keel-engine-nio`)

- **ターゲット**: `jvm`
- **使用場面**: Netty 依存なしの JVM デプロイ。大きなレスポンスでは Netty より若干スループットが高い
- **TLS**: `keel-tls-jsse`（常時インクルード）と組み合わせ

### Netty (`keel-engine-netty`)

- **ターゲット**: `jvm`
- **使用場面**: すでに Netty 依存がある場合、または Netty の `SslHandler` ベース TLS（`NettySslInstaller`）が必要な場合
- **TLS**: `NettySslInstaller`（Netty `SslHandler`）または `keel-tls-jsse` と組み合わせ

### epoll (`keel-engine-epoll`)

- **ターゲット**: `linuxX64`、`linuxArm64`
- **使用場面**: Linux サーバーバイナリのビルド。Linux における最もバランスの良い選択
- **TLS**: `keel-tls-openssl`、`keel-tls-awslc`、または `keel-tls-mbedtls` と組み合わせ

### io_uring (`keel-engine-io-uring`)

- **ターゲット**: `linuxX64`、`linuxArm64`
- **使用場面**: Linux 5.1+ カーネルをターゲットにする場合。`/hello` のスループットは epoll と同等だが、大きなレスポンスペイロードでは io_uring のゼロコピー送信（`SEND_ZC`）が有利
- **要件**: Linux 5.1+（基本）、5.19+（multishot accept）、6.0+（ゼロコピー送信）
- **TLS**: epoll と同じ

### kqueue (`keel-engine-kqueue`)

- **ターゲット**: `macosArm64`、`macosX64`
- **使用場面**: macOS サーバーバイナリのビルド、または M1/M2 Mac 上でのローカル開発
- **TLS**: `keel-tls-openssl` または `keel-tls-mbedtls` と組み合わせ

### NWConnection (`keel-engine-nwconnection`)

- **ターゲット**: `macosArm64`、`macosX64`
- **使用場面**: macOS App Store 配布、またはアプリコード内で証明書を扱わずに OS が TLS を完全管理してほしい場合
- **TLS**: Network.framework 組み込み（リスナーレベル。`keel-tls-*` モジュール不要）

### Node.js (`keel-engine-nodejs`)

- **ターゲット**: `js`（IR、`nodejs()`）
- **使用場面**: Node.js ランタイムをターゲットにする場合
- **TLS**: Node.js 組み込みの `tls` モジュール（リスナーレベル）

## エンジン選択の指針

| プラットフォーム | 推奨 | 代替 | 備考 |
|----------|-------------|-------------|-------|
| JVM | Netty | NIO | Netty 依存を避けたい場合は NIO |
| Linux サーバー | epoll | io_uring | io_uring は Linux 5.1+ 必須。`/large` スループットが重要な場合に選択 |
| macOS サーバー | kqueue | NWConnection | App Store 配布または OS 管理 TLS の場合は NWConnection |
| Node.js | nodejs | — | |

## 開発ワークフロー

一般的なパターン: **macOS（kqueue）で開発**（高速なコンパイル・ローカルテスト）し、**Linux（epoll または io_uring）にデプロイ**。全エンジンが同じインターフェースを実装しているため、環境間でアプリケーションコードの変更は不要です。

```kotlin
// macosArm64Main — kqueue エンジン
fun main() = runBlocking {
    val engine = KqueueEngine()
    val server = engine.bind("0.0.0.0", 8080)
    // ...
}

// linuxX64Main — epoll エンジン、アプリケーションコードは同一
fun main() = runBlocking {
    val engine = EpollEngine()
    val server = engine.bind("0.0.0.0", 8080)
    // ...
}
```

各ソースセットのエンジン型は、そのソースセットのビルド設定における `keel-engine-*` 依存関係によって決まります。
