# Module keel-server

Server-side primitives shared between keel's engine adapters and HTTP-family server modules.

Currently exposes only `ServerConnector` — a `(host, port, tls?)` descriptor for a single
listen endpoint. The Ktor adapter (`:keel-ktor-engine`) and the upcoming HTTP/1.1 native
server (`:keel-server-http`) both consume this type so neither side has to own it.

`TlsConnectorConfig` and `TlsInstaller` currently live in `:keel-tls` and continue to be
imported from there. A follow-up will revisit whether those server-binding-flavoured types
belong in this module instead, once a second consumer (`:keel-server-http`) makes the
trade-off concrete.

# Package io.github.fukusaka.keel.server

`ServerConnector` — bind endpoint descriptor with optional TLS.
