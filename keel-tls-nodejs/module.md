# Module keel-tls-nodejs

Node.js TLS integration tests for `keel-engine-nodejs`.

Targets: **JS** (Node.js)

## Role

This module contains no main Kotlin sources. It provides integration tests that verify
TLS functionality on the Node.js platform.

Node.js TLS is handled at the engine level by `NodeEngine`, not via `TlsCodec`. Two modes:

**Listener-level TLS** (`TlsConnectorConfig` with `installer = null`): `NodeEngine.bindPipeline`
or `NodeEngine.bind` calls `tls.createServer(options)` with the certificate and key from
`TlsConfig`. The `"secureConnection"` event fires after the TLS handshake — the keel pipeline
receives plaintext. No `TlsHandler` is needed.

**Per-connection TLS** (`TlsConnectorConfig` with non-null `installer`): a plain `net.Server`
is created and a keel `TlsHandler` is installed per connection via `BindConfig.initializeConnection`.

The integration tests in this module exercise listener-level TLS directly via the Node.js
`tls` module (using dynamic JS interop) to validate the TLS layer independently of keel's
pipeline machinery.

# Package io.github.fukusaka.keel.tls.nodejs

Node.js TLS integration tests. No production classes — TLS is handled by `NodeEngine`
at the listener level via `tls.createServer`.
