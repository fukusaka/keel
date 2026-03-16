# keel — KMP Native ネットワーク I/O エンジン

[![CI](https://github.com/keel-kt/keel/actions/workflows/ci.yml/badge.svg)](https://github.com/keel-kt/keel/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![KMP](https://img.shields.io/badge/Kotlin%20Multiplatform-✓-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/docs/multiplatform.html)
[![kotlinx.io](https://img.shields.io/badge/kotlinx.io-0.9.0-orange)](https://github.com/Kotlin/kotlinx-io)
[![Platforms](https://img.shields.io/badge/Platforms-Linux%20%7C%20macOS%20%7C%20JVM%20%7C%20JS-informational)](#ターゲット)
[![Status](https://img.shields.io/badge/Status-Pre--release-yellow)](#ロードマップ)

Linux の epoll、macOS の kqueue、JVM の Netty — プラットフォームごとに異なるネイティブ非同期 I/O を、keel が単一の Kotlin Multiplatform インターフェースに統一します。
KMP 上でネットワークプロトコルを実装するための適切な基盤を提供します。

- **Native ファースト**: epoll（Linux）・kqueue（macOS）を Kotlin Native から直接駆動
- **非同期イベントループ**: 全ターゲットでスレッドをブロックしない I/O
- **プラットフォーム最適**: Native=epoll/kqueue · JVM=Netty · Node.js=net モジュール
- **コーデック層**: kotlinx.io プリミティブのみで構成した HTTP/1.1・WebSocket

```
  ┌──────┬──────┬────────────┬──────┬───────────────┐
  │epoll │kqueue│  io_uring  │ NIO  │ NWConnection  │
  │Linux │macOS │ Linux 5.1+ │ JVM  │   Apple       │
  └──┬───┴──┬───┴─────┬──────┴──┬───┴───────┬───────┘
     └──────┴─────────┴─────────┴───────────┘
                           │
               ┌───────────┴───────────┐
               │          keel         │
               │   非同期 I/O エンジン  │
               └───────────┬───────────┘
                           │
  ┌────────────────────────┴─────────────────────────┐
  │      アプリケーション / Ktor / gRPC KMP           │
  └──────────────────────────────────────────────────┘
```

---

## ドキュメント

- [Web サイト](https://keel-kt.github.io/keel/) — アーキテクチャガイド・How-to・コーデックドキュメント
- [API リファレンス](https://keel-kt.github.io/keel/api/) — Dokka 生成 KDoc
- [English README](README.md)

---

## モジュール構成

```
keel/
├── core/                  # IoEngine / NativeBuf（expect/actual）
├── engine-epoll/          # linuxX64, linuxArm64
├── engine-kqueue/         # macosArm64, macosX64
├── engine-nio/            # JVM（java.nio.Selector）
├── engine-netty/          # JVM（Netty 4.x 委譲）
├── engine-nodejs/         # JS nodejs()
├── engine-nwconnection/   # macosArm64, macosX64（Network.framework）
├── codec-http/            # HTTP/1.1 パーサー／ライター（RFC 7230/7231）
└── codec-websocket/       # WebSocket フレーミング（RFC 6455）
```

---

## ターゲット

| ターゲット | エンジン | 状態 | 備考 |
|---|---|---|---|
| `linuxX64`, `linuxArm64` | epoll | ✅ | |
| `macosArm64` | kqueue / NWConnection | ✅ | |
| `macosX64` | kqueue / NWConnection | ✅ | Kotlin 2.3 で deprecated（Tier 3） |
| `jvm` | NIO / Netty | ✅ | |
| `js (nodejs())` | Node.js net | ✅ | |
| `iosArm64`, `iosSimulatorArm64` | NWConnection | 🔲 予定 | クライアント限定 |
| `mingwX64` | IOCP | 🔲 保留 | |
| `androidNativeArm64`, `androidNativeX64` | epoll | 🔲 保留 | |
| `tvosArm64`, `watchosArm64` | — | ❌ 対象外 | サンドボックス制約により対応困難 |
| `wasmJs`, `wasmWasi` | — | ❌ 対象外 | syscall 直接アクセス不可 |

---

## ロードマップ

| 状態 | 内容 |
|---|---|
| ✅ 完了 | プロジェクト骨格・CI |
| ✅ 完了 | kqueue エンジン（macOS） |
| ✅ 完了 | epoll エンジン（Linux） |
| ✅ 完了 | NIO / Netty / Node.js / NWConnection エンジン |
| ✅ 完了 | HTTP/1.1 コーデック・WebSocket コーデック |
| 🔄 進行中 | OSS 公開前整備（LICENSE / README / KDoc / Dokka / Docusaurus） |
| 🔲 予定 | IoEngine 再設計 / BufferAllocator / Ktor アダプタ |
| 🔲 予定 | TLS（Mbed TLS）/ io_uring |
| 🔲 予定 | UDP / MQTT / HTTP2 / gRPC |

---

## インストール

> **注意:** keel はまだ Maven Central に公開されていません。0.1.0 リリースまでは、ソースからビルドしてローカル Maven リポジトリに公開して使用してください。

```bash
git clone https://github.com/keel-kt/keel.git
cd keel
./gradlew publishToMavenLocal
```

プロジェクトへの依存関係の追加:

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
}

dependencies {
    implementation("io.github.keel:core:0.1.0-SNAPSHOT")
    implementation("io.github.keel:codec-http:0.1.0-SNAPSHOT")   // 任意
    implementation("io.github.keel:codec-websocket:0.1.0-SNAPSHOT") // 任意
}
```

Maven Central への公開は 0.1.0 リリース時を予定しています。

---

## ビルド

### 前提

- Java 21+
- Gradle 9.4+（wrapper 同梱）
- macOS ビルド：M1/M2 Mac 推奨

### テスト実行

```bash
# JVM テスト
./gradlew :engine-nio:jvmTest :engine-netty:jvmTest
./gradlew :codec-http:jvmTest :codec-websocket:jvmTest

# macOS Native テスト
./gradlew :engine-kqueue:macosArm64Test
./gradlew :codec-http:macosArm64Test :codec-websocket:macosArm64Test

# Linux テスト（Docker）
docker run --rm --platform linux/amd64 \
  -v $(pwd):/work -w /work gradle:8-jdk21 \
  ./gradlew :engine-epoll:linuxX64Test

# API ドキュメント生成（Dokka）
./gradlew dokkaGeneratePublicationHtml
```

---

## ライセンス

Apache License 2.0 — Copyright 2026 The keel-kt Authors
詳細は [LICENSE](LICENSE) を参照。
