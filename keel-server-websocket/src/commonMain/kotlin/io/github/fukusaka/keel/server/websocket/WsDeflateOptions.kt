package io.github.fukusaka.keel.server.websocket

/**
 * Server-side configuration for the WebSocket `permessage-deflate`
 * extension (RFC 7692).
 *
 * `permessage-deflate` compresses each application message independently
 * with DEFLATE. These knobs control how keel's server side behaves once
 * the extension is negotiated at the handshake.
 *
 * @property contextTakeover whether the DEFLATE sliding-window context
 *   is preserved across messages. RFC 7692 §7.1.1 defines
 *   `server_no_context_takeover` / `client_no_context_takeover`. keel's
 *   default is `false` (no context takeover): each message resets the
 *   compression window, which bounds per-connection memory to a single
 *   window regardless of how many connections are open. With `true` the
 *   window carries over for a better ratio at the cost of one retained
 *   window per connection.
 * @property threshold messages whose payload is smaller than this many
 *   bytes are sent uncompressed (RSV1=0). DEFLATE adds a few bytes of
 *   framing overhead, so compressing a tiny message can make it larger;
 *   below the threshold the cost is not worth it.
 * @property level DEFLATE compression level passed to the backend.
 *   `-1` means the backend default (`Z_DEFAULT_COMPRESSION` for zlib);
 *   `0` = no compression, `1` = fastest, `9` = best ratio.
 */
public class WsDeflateOptions(
    public val contextTakeover: Boolean = false,
    public val threshold: Int = DEFAULT_THRESHOLD,
    public val level: Int = DEFAULT_LEVEL,
) {
    init {
        require(threshold >= 0) { "threshold must be non-negative, got $threshold" }
        require(level in MIN_LEVEL..MAX_LEVEL) {
            "level must be in $MIN_LEVEL..$MAX_LEVEL, got $level"
        }
    }

    public companion object {
        /** Default uncompressed-message threshold (1 KiB). */
        public const val DEFAULT_THRESHOLD: Int = 1024

        /** Backend-default compression level sentinel (`Z_DEFAULT_COMPRESSION`). */
        public const val DEFAULT_LEVEL: Int = -1

        /** Lowest accepted [level] value (`-1` = backend default). */
        private const val MIN_LEVEL: Int = -1

        /** Highest accepted [level] value (`9` = best ratio). */
        private const val MAX_LEVEL: Int = 9

        /** Default configuration: no context takeover, 1 KiB threshold, backend-default level. */
        public val Default: WsDeflateOptions = WsDeflateOptions()
    }
}
