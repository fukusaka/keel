# Module keel-server

Server-side primitives shared between keel's engine adapters and HTTP-family server modules.

Exposes:

- `ServerConnector` — `(host, port, tls?)` descriptor for a single listen endpoint.
- `AcceptBackoff` — sealed strategy (`Fixed` / `Exponential`) controlling how the accept
  loop pauses on persistent failure (e.g. EMFILE).
- `acceptLoopWithBackoff` — extension on `StreamServer` that drives `accept()` in a loop
  with [AcceptBackoff] applied to errors. The caller passes a per-accept callback that is
  responsible for launching the per-connection handler on the appropriate scope/dispatcher
  (typically the engine scope and the channel's `ioDispatcher`).
- `gracefulShutdown` — two-phase shutdown helper. Signals stop, waits for the accept
  coordinator and engine-scope handlers to drain within a grace period, then forces
  cancellation if the deadline is exceeded, and always closes the engine in `finally`.
- `TlsServerInstaller` — `fun interface` that installs server-side TLS on a
  `PipelinedChannel`. Set on `TlsServerConfig.installer`; `null` activates engine-native
  listener-level TLS (NWConnection / Node.js).
- `TlsServerConfig` — `BindConfig` subclass carrying `TlsConfig` plus an optional
  `TlsServerInstaller`. Pass directly to `engine.bindPipeline(...)` / `engine.bind(...)`
  for HTTPS connectors.
- `TlsCodecServerInstaller(factory)` — adapter that turns a `TlsCodecFactory` (from
  `:keel-tls`) into a `TlsServerInstaller`. Default choice for keel's `TlsHandler`-based
  TLS; engine-specific installers (e.g. a Netty `SslHandler` adapter) replace it for
  transport-level TLS.

The Ktor adapter (`:keel-ktor-engine`) and the upcoming HTTP/1.1 native server
(`:keel-server-http`) both consume these primitives so neither side has to own them.

# Package io.github.fukusaka.keel.server

`ServerConnector` — bind endpoint descriptor with optional TLS.

`AcceptBackoff` — accept-loop backoff strategy.

`acceptLoopWithBackoff` — `StreamServer` extension implementing the accept loop.

`gracefulShutdown` — server graceful shutdown helper.

`TlsServerInstaller` — pipeline-level TLS installer interface.

`TlsServerConfig` — `BindConfig` subclass for HTTPS listeners.

`TlsCodecServerInstaller` — adapter from `TlsCodecFactory` to `TlsServerInstaller`.
