package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.compression.CompressionCodec

/** `Sec-WebSocket-Extensions` header name (RFC 6455 §9.1 / RFC 7692 §5). */
internal const val SEC_WEBSOCKET_EXTENSIONS: String = "Sec-WebSocket-Extensions"

/** The single extension token keel supports (RFC 7692 §7). */
private const val PERMESSAGE_DEFLATE: String = "permessage-deflate"

/** RFC 7692 §7.1.1 parameter: server must not use context takeover. */
private const val SERVER_NO_CONTEXT_TAKEOVER: String = "server_no_context_takeover"

/** RFC 7692 §7.1.1 parameter: client must not use context takeover. */
private const val CLIENT_NO_CONTEXT_TAKEOVER: String = "client_no_context_takeover"

/** RFC 7692 §7.1.2 parameter: cap on the server's LZ77 window (8..15). */
private const val SERVER_MAX_WINDOW_BITS: String = "server_max_window_bits"

/** RFC 7692 §7.1.2 parameter: cap on the client's LZ77 window (8..15). */
private const val CLIENT_MAX_WINDOW_BITS: String = "client_max_window_bits"

/** Smallest LZ77 window-bits value RFC 7692 §7.1.2 permits. */
private const val MIN_WINDOW_BITS: Int = 8

/** Largest LZ77 window-bits value RFC 7692 §7.1.2 permits. */
private const val MAX_WINDOW_BITS: Int = 15

/**
 * Outcome of negotiating WebSocket extensions for one handshake.
 *
 * keel negotiates exactly one extension — `permessage-deflate`
 * (RFC 7692). The two variants tell the upgrade flow whether to wire a
 * compression engine and emit a `Sec-WebSocket-Extensions` response.
 */
internal sealed interface WsExtensionResult {

    /**
     * No compression: the client offered no `permessage-deflate`, or the
     * server was not configured with a [CompressionCodec]. The upgrade
     * proceeds with the plain WS codec.
     */
    data object None : WsExtensionResult

    /**
     * `permessage-deflate` is active.
     *
     * @property responseHeaderValue the `Sec-WebSocket-Extensions` value
     *   to put on the `101` response (RFC 7692 §5.1).
     * @property effectiveOptions the options the server side applies —
     *   [WsDeflateOptions.contextTakeover] is forced off when the offer
     *   (or keel's own config) demands it.
     * @property serverMaxWindowBits negotiated server LZ77 window-bits
     *   cap (8..15), or `null` to use the backend default (15).
     * @property clientMaxWindowBits negotiated client LZ77 window-bits
     *   cap (8..15), or `null` for the default (15). The inbound
     *   decompressor uses this value.
     */
    data class Deflate(
        val responseHeaderValue: String,
        val effectiveOptions: WsDeflateOptions,
        val serverMaxWindowBits: Int?,
        val clientMaxWindowBits: Int?,
    ) : WsExtensionResult
}

/**
 * Negotiates the `permessage-deflate` extension (RFC 7692 §7) for one
 * handshake.
 *
 * Parses the request's `Sec-WebSocket-Extensions` header for a
 * `permessage-deflate` offer and, if [codec] is non-null, accepts it.
 * Because keel's default is no context takeover, the response always
 * carries `server_no_context_takeover` + `client_no_context_takeover`
 * when [options].`contextTakeover` is false — that holds the
 * per-connection memory to a single window. Offered `*_max_window_bits`
 * values in the legal range 8..15 are honoured; an offer with no
 * `permessage-deflate` token (or a null [codec]) yields
 * [WsExtensionResult.None].
 *
 * Only the *first* well-formed `permessage-deflate` offer is accepted —
 * RFC 7692 §5.1 lets a client list several with different parameters and
 * the server picks one.
 *
 * @param extensionsHeader the raw `Sec-WebSocket-Extensions` request
 *   header value, or null when absent.
 * @param codec the server-configured compression backend, or null when
 *   the endpoint runs without compression.
 * @param options the server-wide deflate configuration.
 */
internal fun negotiatePermessageDeflate(
    extensionsHeader: String?,
    codec: CompressionCodec?,
    options: WsDeflateOptions,
): WsExtensionResult {
    if (codec == null || extensionsHeader == null) return WsExtensionResult.None

    // A header may list several extension offers separated by commas;
    // each offer is `token; param; param=value` separated by semicolons.
    for (offer in extensionsHeader.split(',')) {
        val tokens = offer.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty() || !tokens[0].equals(PERMESSAGE_DEFLATE, ignoreCase = true)) continue
        val parsed = parseDeflateParams(tokens.drop(1)) ?: continue
        return buildDeflateResult(parsed, options)
    }
    return WsExtensionResult.None
}

/** Parsed `permessage-deflate` offer parameters; null when malformed. */
private class DeflateParams(
    val serverNoContextTakeover: Boolean,
    val clientNoContextTakeover: Boolean,
    val serverMaxWindowBits: Int?,
    val clientMaxWindowBits: Boolean,
    val clientMaxWindowBitsValue: Int?,
)

/**
 * Parses the parameter list of one `permessage-deflate` offer. Returns
 * null when any parameter is unrecognised or out of range — the caller
 * then skips this offer (RFC 7692 §7.1: an offer with an unknown
 * parameter must be declined, not accepted with the parameter ignored).
 */
@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun parseDeflateParams(params: List<String>): DeflateParams? {
    var serverNoCtx = false
    var clientNoCtx = false
    var serverMaxBits: Int? = null
    var clientMaxBitsOffered = false
    var clientMaxBitsValue: Int? = null
    for (param in params) {
        val eq = param.indexOf('=')
        val key = (if (eq >= 0) param.substring(0, eq) else param).trim()
        val value = if (eq >= 0) param.substring(eq + 1).trim().trim('"') else null
        when (key) {
            SERVER_NO_CONTEXT_TAKEOVER -> serverNoCtx = true
            CLIENT_NO_CONTEXT_TAKEOVER -> clientNoCtx = true
            SERVER_MAX_WINDOW_BITS -> {
                serverMaxBits = value?.toIntOrNull() ?: return null
                if (serverMaxBits !in MIN_WINDOW_BITS..MAX_WINDOW_BITS) return null
            }
            CLIENT_MAX_WINDOW_BITS -> {
                clientMaxBitsOffered = true
                if (value != null) {
                    clientMaxBitsValue = value.toIntOrNull() ?: return null
                    if (clientMaxBitsValue !in MIN_WINDOW_BITS..MAX_WINDOW_BITS) return null
                }
            }
            else -> return null
        }
    }
    return DeflateParams(serverNoCtx, clientNoCtx, serverMaxBits, clientMaxBitsOffered, clientMaxBitsValue)
}

/**
 * Builds the [WsExtensionResult.Deflate] response from a parsed offer
 * and the server's [options], assembling the `Sec-WebSocket-Extensions`
 * response header value per RFC 7692 §5.1.
 */
private fun buildDeflateResult(parsed: DeflateParams, options: WsDeflateOptions): WsExtensionResult.Deflate {
    // keel disables context takeover whenever its own config asks for it
    // or the client demands it; the response advertises that decision.
    val noServerCtx = !options.contextTakeover || parsed.serverNoContextTakeover
    val noClientCtx = !options.contextTakeover || parsed.clientNoContextTakeover

    val responseParts = mutableListOf(PERMESSAGE_DEFLATE)
    if (noServerCtx) responseParts.add(SERVER_NO_CONTEXT_TAKEOVER)
    if (noClientCtx) responseParts.add(CLIENT_NO_CONTEXT_TAKEOVER)
    parsed.serverMaxWindowBits?.let { responseParts.add("$SERVER_MAX_WINDOW_BITS=$it") }
    // RFC 7692 §7.1.2.2: the server may pick a client window-bits cap
    // only when the client offered the parameter. If offered valueless,
    // keel echoes the maximum (15) it is willing to accept.
    val clientBits: Int? = when {
        parsed.clientMaxWindowBitsValue != null -> parsed.clientMaxWindowBitsValue
        parsed.clientMaxWindowBits -> MAX_WINDOW_BITS
        else -> null
    }
    clientBits?.let { responseParts.add("$CLIENT_MAX_WINDOW_BITS=$it") }

    return WsExtensionResult.Deflate(
        responseHeaderValue = responseParts.joinToString("; "),
        effectiveOptions = WsDeflateOptions(
            contextTakeover = options.contextTakeover && !noServerCtx,
            threshold = options.threshold,
            level = options.level,
        ),
        serverMaxWindowBits = parsed.serverMaxWindowBits,
        clientMaxWindowBits = clientBits,
    )
}
