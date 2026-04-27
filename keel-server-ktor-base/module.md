# Module keel-server-ktor-base

Codec-agnostic skeleton for keel's Ktor server adapters.

Provides the engine-neutral plumbing — Ktor `BaseApplicationEngine` integration, accept loop with
backoff, two-phase graceful shutdown, TLS connector configuration — and delegates per-connection
HTTP handling to a [KtorConnectionHandler] supplied by a sibling codec module:

- `:keel-server-ktor` injects `KeelCodecConnectionHandler`, which uses keel's
  `addHttp1ServerCodec()` from `:keel-codec-http` (Pattern B).
- `:keel-server-ktor-cio` (future) injects `KtorCioConnectionHandler`, which uses
  `ktor-http-cio`'s `parseRequest` (Pattern C).

Both factories produce instances of the same `KeelApplicationEngine` class — only the
connection handler differs. This keeps the Ktor lifecycle wiring single-source while letting
each codec own its own request/response building.

## Key Types

| Type | Role |
|------|------|
| `KeelApplicationEngine` | `BaseApplicationEngine` impl. Bind / accept loop / shutdown / TLS connectors |
| `KeelApplicationEngine.Configuration` | Engine settings: `engine`, `keepAlive`, `acceptBackoff`, `applicationDispatcher`, `sslConnector()` |
| `KtorConnectionHandler` | `fun interface` — codec-specific per-connection handler |
| `KeelConnectionPoint` | keel `SocketAddress` → Ktor `RequestConnectionPoint` adapter |
| `KtorLoggerAdapter` (`KtorLoggerFactory`) | Bridges Ktor's `Logger` to keel's `LoggerFactory` |

# Package io.github.fukusaka.keel.server.ktor

Codec-agnostic primitives shared by keel's Ktor adapters.
