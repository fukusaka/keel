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

The Ktor adapter (`:keel-ktor-engine`) and the upcoming HTTP/1.1 native server
(`:keel-server-http`) both consume these primitives so neither side has to own them.

`TlsConnectorConfig` and `TlsInstaller` currently live in `:keel-tls` and continue to be
imported from there. A follow-up will move them into this module so server-binding TLS
types live next to the other server primitives, leaving `:keel-tls` strictly about TLS
protocol primitives.

# Package io.github.fukusaka.keel.server

`ServerConnector` — bind endpoint descriptor with optional TLS.

`AcceptBackoff` — accept-loop backoff strategy.

`acceptLoopWithBackoff` — `StreamServer` extension implementing the accept loop.

`gracefulShutdown` — server graceful shutdown helper.
