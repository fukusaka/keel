---
sidebar_position: 1
---

# アーキテクチャ概要

## レイヤー構造

```
Your App / Ktor DSL
        ↑
Codec   (keel-codec-http, keel-codec-websocket)   ← 任意
TLS     (keel-tls-jsse · keel-tls-openssl · …)    ← 任意
        ↑
  StreamEngine  ← keel の統一インターフェース
        ↑
epoll │ io_uring │ kqueue │ NWConnection │ NIO │ Netty │ Node
```

`StreamEngine` は keel がプラットフォーム I/O の上に置く唯一の抽象です。その上にある TLS・コーデック・Ktor 統合はすべて任意であり、エンジン非依存です — クラスパスにどのエンジンがあっても同一コードで動作します。

`StreamEngine` は `IoEngine` のサブインターフェースです。`IoEngine` はエンジン設定（`IoEngineConfig`）とライフサイクル（`close()`）を持つルートインターフェースです。この階層設計により、将来 UDP 向けの兄弟インターフェース `DatagramEngine` を追加できます。既存の TCP API に影響なく、同じ `IoEngine` 基盤を共有します。

```
IoEngine  (config + close)
├── StreamEngine    ← TCP / バイトストリーム（現在）
└── DatagramEngine  ← UDP               （予定）
```

### keel vs Netty vs Ktor

| ライブラリ | 役割 |
|---|---|
| **keel** | トランスポート I/O エンジン — epoll / kqueue / io_uring / NWConnection / NIO / Netty / Node.js を単一の KMP インターフェースで統一 |
| **Netty** | JVM 専用 I/O フレームワーク — `keel-engine-netty` が JVM 上でこれに委譲。Native では keel が完全に置き換える |
| **Ktor** | Web フレームワーク — ルーティング・プラグイン・シリアライゼーション。`keel-ktor-engine` が keel を Ktor の I/O バックエンドとして接続。Ktor 標準の CIO エンジンは Native ターゲットで TLS 非対応 — keel がこのギャップを埋めます |

## 2 つの I/O モード

アプリケーションは `StreamEngine` を 2 通りの方法で利用できます:

**Coroutine モード** (`engine.bind()` + `server.accept()`)  
`engine.bind()` は呼び出し元スレッドで同期実行し、`Server` を返します。`server.accept()` は `suspend fun` で、EventLoop が新しい接続を検出するまで suspend し、コルーチンを再開します。接続後は `val buf = channel.read()` がデータ到着まで suspend し、スレッドをブロックせずに逐次的に書けます。Ktor および kotlinx coroutines と自然に統合できます。トレードオフ: `read()` の再開ごとにコルーチンのコンテキストスイッチが発生します。

**Pipeline モード** (`engine.bindPipeline()`)  
エンジンが EventLoop スレッド上でハンドラを直接呼び出します — suspend なし、コンテキストスイッチなし。ソケットが読み取り可能になると、EventLoop がデータを `IoBuf` に読み込み、`Pipeline` のインバウンドイベントを発火します: データは TLS → デコーダ → ユーザーハンドラ → エンコーダと EventLoop スレッドを離れることなく同期的に流れます。トレードオフ: ハンドラが EventLoop スレッドをブロックしてはなりません。

どちらのモードも全 7 エンジンで利用できます。Pipeline モードは Coroutine モードの約 1.5 倍のスループットを発揮します（コルーチンのコンテキストスイッチコストの分）。

詳しくは [Coroutine モード](./coroutine.md) と [Pipeline モード](./pipeline.md) を参照してください。

## スレッドモデル

**EventLoop** は I/O イベントを監視してコルーチンやハンドラにディスパッチするシングルスレッドのループです。epoll と kqueue はファイルディスクリプタが読み書き可能になったときに通知し（`epoll_wait` / `kevent`）、NIO は `Selector.select()` を使用します。io_uring は完了通知モデルで、I/O 操作をカーネルに投入し、カーネルが非同期に実行した結果を完了キュー（CQE）で返します。いずれも EventLoop スレッドがイベント監視とタスクキューを単独で所有します。Channel は生涯同じ EventLoop に束縛されるため、その Channel のすべてのイベントと handler は同じスレッドで実行されます — Channel レベルの操作にロックは不要です。

keel は 1 EventLoop につき 1 スレッドで動作します。スレッド数は `IoEngineConfig(threads = N)` で設定します — `0`（デフォルト）は CPU コア数と同数です。

**Coroutine モード**: I/O の準備が整ったとき（epoll、kqueue、NIO）または完了したとき（io_uring）、EventLoop が中断中のコルーチンを再開します。コルーチンがどこで実行されるかはターゲットによって異なります:

- **Native エンジン（epoll、kqueue、io_uring）** — コルーチンは EventLoop スレッド上で直接実行されます。EventLoop がコルーチンの実行スレッドを兼ねており、スレッド間の受け渡しは発生しません。
- **NIO（JVM）** — EventLoop スレッドが I/O 通知を処理し、コルーチンの再開を `Dispatchers.Default` にディスパッチします。JVM では I/O 監視とコルーチン実行を分離することで、ワークスティーリングのスレッドプールが EventLoop とは独立してコルーチンを全コアに分散できます。

**Pipeline モード**: EventLoop がハンドラチェーンを自スレッド上で同期的に呼び出します。コルーチンもスレッド間ディスパッチもなく、すべてのハンドラコードは EventLoop スレッド上で実行されます。

上記の挙動は keel 独自のエンジン実装（epoll、kqueue、io_uring、NIO）に適用されます。3 つのエンジンは独自のスレッドモデルを持ち、`IoEngineConfig(threads)` を無視します:

- **`keel-engine-netty`** — Netty の `NioEventLoopGroup` / `EpollEventLoopGroup` に委譲します。Netty は `threads = 0` を受け取り独自のデフォルト（CPU コア数 × 2）に解決します。`bind()` を含むすべての I/O 操作が Netty の EventLoop キューに投入されます（keel の Native エンジンは `bind()` を呼び出し元スレッドで直接実行します）。
- **`keel-engine-nwconnection`** — 接続ごとに GCD `dispatch_queue` を使用します。スレッド管理は OS に委譲されます。
- **`keel-engine-nodejs`** — Node.js / V8 ランタイムの EventLoop に委譲します。

## KMP ターゲット

| プラットフォーム | ターゲット | KMP Tier | エンジン | keel 状態 |
|---|---|---|---|---|
| JVM | JVM | Stable | NIO / Netty | ✅ |
| macOS | macosArm64 / macosX64 | Native Tier 1 | kqueue / NWConnection | ✅ |
| iOS | iosArm64 / iosSimulatorArm64 | Native Tier 1 | NWConnection | 🔲 予定 |
| JS（Node.js） | nodejs() | Stable | Node.js net/tls | ✅ |
| Linux | linuxX64 / linuxArm64 | Native Tier 2 | epoll / io_uring | ✅ |
| watchOS / tvOS | watchosArm64 / tvosArm64（など） | Native Tier 2 | — | 対象外 |
| Windows | mingwX64 | Native Tier 3 | — | 後回し |
| Android Native | androidNativeArm64 / Arm32 / X64 / X86 | Native Tier 3 | — | 対象外 |
| JS（Browser） | browser() | Stable | — | 対象外 |
| Wasm/JS | wasmJs | Beta | — | 対象外 |
| Wasm/WASI | wasmWasi | Beta | — | ブロッカー待ち |

**Android**: Android アプリは JVM 上で動作します。`jvm()` ターゲットに `keel-engine-nio` または `keel-engine-netty` を組み合わせて使用してください。`androidNativeArm64` などは Android SDK とは無関係なベアメタルの Native ターゲットであり、keel はこれらを対象としていません。

**iOS**: macOS と同じ NWConnection エンジンを共有しており、実装上は準備が整っています。App Sandbox の制限によりサーバーソケットが使用できないためクライアント用途に限られます。TLS は Network.framework に固定され、`keel-tls-*` モジュールは使用できません。

**Windows**: WSAPoll / IOCP 向けの別エンジンが必要であり、後のフェーズに先送りされています。

**JS（Browser）/ Wasm/JS**: ブラウザ Sandbox では TCP ソケットが使用できません。

**Wasm/WASI**: TCP には `wasi-sockets`（WASI 0.2）が必要です。KT-64568（WASI 0.2 移行）と KT-64569（Component Model）が解決し `kotlinx-coroutines` の wasmWasi サポートが安定すれば、ターゲット追加が現実的になります。

## TLS 戦略

keel の TLS には 2 つの統合モードがあります:

**接続ごとの TLS**（kqueue、epoll、io_uring、NIO、Netty）— `TlsHandler` を `Pipeline` にインストールし、選択した TLS バックエンドで各 `IoBuf` を暗号化・復号します。`keel-engine-netty` では `keel-tls-jsse` を使う方法のほか、`NettySslInstaller`（Netty 内蔵の `SslHandler`）を使う方法もあり、後者は `keel-tls-*` モジュール不要です。

**リスナーレベルの TLS**（NWConnection、Node.js）— OS またはランタイムがデータを keel に渡す前に TLS を処理します。`keel-tls-*` モジュールは不要です。

| プラットフォーム | バックエンド | モジュール |
|---|---|---|
| JVM (NIO) | JSSE（JDK SSLContext） | `keel-tls-jsse` |
| JVM (Netty) ¹ | Netty SslHandler | `keel-engine-netty` 内蔵 |
| JVM (Netty) ¹ | JSSE（JDK SSLContext） | `keel-tls-jsse` |
| Native（Linux/macOS） | OpenSSL | `keel-tls-openssl` |
| Native（Linux/macOS） | Mbed TLS | `keel-tls-mbedtls` |
| Native（Linux/macOS） | AWS-LC | `keel-tls-awslc` |
| macOS / iOS（NWConnection） | Network.framework（リスナーレベル） | `keel-engine-nwconnection` |
| JS（Node.js） | Node.js tls（リスナーレベル） | `keel-engine-nodejs` |

¹ `keel-engine-netty` は両方の TLS オプションに対応 — デプロイごとにどちらか一方を選択します。

詳しくは [TLS](./tls.md) を参照してください。

## 設計原則

**スレッド固定による Channel のロックフリー設計**  
各 Channel は生涯同じ EventLoop に束縛されます。その Channel に関する I/O イベント・handler 呼び出し・状態変更はすべて同じスレッドで実行されます。Channel レベルの状態にロックを置かないのは意図的な設計であり、スレッド固定の保証によって不要になっています。keel の Channel や Pipeline の実装でロックがないように見えるコードは、偶然ではなく設計によって正しいのです。I/O ホットパスにロックを置くと接続数の増加とともにレイテンシと競合が増大します — スレッド固定はその両方を根本から排除します。

**エンジン非依存コーデック**  
コーデックモジュールはいずれの `keel-engine-*` モジュールにも依存しません。`keel-codec-websocket` は `kotlinx.io` のみに依存します。`keel-codec-http` はさらに `keel-io`（`SuspendSource` / `SuspendSink` のため）に依存します。どちらもエンジンを知りません。コーデックがエンジン内部に依存していたとすれば、7 つのエンジンそれぞれに固有のコーデック実装が必要になり、ユニットテストにも動作中のエンジンが必要になります。代わりに単一の実装が全エンジンで動作し、テストはエンジンなしのインメモリ `Buffer` に対して実行できます。

この境界は意図的な設計です: コーデックはリクエスト粒度で動作するため、kotlinx-io の GC 管理 `Buffer` で十分です。エンジンはパケット粒度（recv システムコールごと）で動作するため、`IoBuf` + `BufferAllocator` でアロケーションを完全制御し、I/O ホットパスから GC を排除します。

**ゼロコピー I/O のためのプラットフォームネイティブメモリ `IoBuf`**  
`IoBuf` は各ターゲットでプラットフォームネイティブなメモリを使用します: Native では `nativeHeap`、JVM では `ByteBuffer.allocateDirect`、JS では `Int8Array`（V8 管理）。エンジン実装は `IoBuf.unsafePointer`（Native）または `IoBuf.unsafeBuffer`（JVM）を OS の read/write システムコールに直接渡します。I/O ホットパスで OS バッファとアプリケーションヒープ間のコピーが発生しません。ネイティブメモリを使わない場合、JVM は GC 管理ヒープのオブジェクトが再配置される可能性があるため OS に直接アドレスを渡せず、システムコールのたびに一時的なネイティブバッファへのコピーが暗黙的に発生します。

**プラガブル設計**  
デプロイ環境によって最適な実装は異なります — セキュリティ要件の厳しい環境では特定の TLS ライブラリが必要になり、デバッグ時にはリークを検出しつつ本番では不要なオーバーヘッドを除きたく、組み込み用途ではアロケータを最小化したい。keel の主要コンポーネントをすべて差し替え可能にしているのは、こうした関心事をアプリケーションコードから切り離し、同じビジネスロジックをあらゆる環境で変更なしにデプロイするためです。

- **I/O エンジン** — `keel-engine-*` Gradle 依存関係によるコンパイル時選択
- **TLS バックエンド** — `keel-tls-*` 依存関係の選択（Netty・NWConnection・Node.js はエンジン内蔵 TLS を使用）
- **バッファアロケータ** — `BufferAllocator` をエンジン構築時に `IoEngineConfig` 経由で注入。本番では `SlabAllocator`（Native）と `PooledDirectAllocator`（JVM）を使用し、バッファのライフサイクル問題のデバッグ時は `TrackingAllocator` や `LeakDetectingAllocator` に差し替えられます
- **ロガー** — `LoggerFactory` を `IoEngineConfig.loggerFactory` 経由で注入。デフォルトは no-op。開発用には `PrintLogger`、`keel-ktor-engine` 使用時は `KtorLoggerAdapter` で Ktor の Logger にブリッジできます
