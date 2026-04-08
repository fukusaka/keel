package io.github.fukusaka.keel.tls

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Lossless conversion between PEM and DER certificate/key formats.
 *
 * PEM is a text encoding of DER binary data:
 * ```
 * -----BEGIN <label>-----
 * <Base64-encoded DER>
 * -----END <label>-----
 * ```
 *
 * The conversion is bijective — no information is lost in either direction.
 * The PEM label (e.g., "CERTIFICATE", "PRIVATE KEY") is preserved via
 * explicit parameters rather than ASN.1 inspection.
 */
object PemDerConverter {

    private const val PEM_LINE_LENGTH = 64

    /**
     * Decodes a PEM-encoded string to DER binary.
     *
     * Strips the `-----BEGIN ...-----` / `-----END ...-----` headers
     * and Base64-decodes the body.
     *
     * @param pem PEM-encoded string (any label: CERTIFICATE, PRIVATE KEY, etc.)
     * @return DER-encoded binary data.
     * @throws IllegalArgumentException if the PEM format is invalid.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun pemToDer(pem: String): ByteArray {
        val lines = pem.trim().lines()
        require(lines.size >= 3) { "Invalid PEM: too few lines" }
        require(lines.first().startsWith("-----BEGIN ")) { "Invalid PEM: missing BEGIN header" }
        require(lines.last().startsWith("-----END ")) { "Invalid PEM: missing END footer" }

        val base64Body = lines
            .drop(1)
            .dropLast(1)
            .joinToString("")
            .replace("\\s".toRegex(), "")

        return Base64.decode(base64Body)
    }

    /**
     * Encodes DER binary to a PEM string with the specified label.
     *
     * @param der DER-encoded binary data.
     * @param label PEM label (e.g., "CERTIFICATE", "PRIVATE KEY").
     * @return PEM-encoded string with 64-character line wrapping.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun derToPem(der: ByteArray, label: String): String = buildString {
        appendLine("-----BEGIN $label-----")
        val base64 = Base64.encode(der)
        for (i in base64.indices step PEM_LINE_LENGTH) {
            appendLine(base64.substring(i, minOf(i + PEM_LINE_LENGTH, base64.length)))
        }
        appendLine("-----END $label-----")
    }
}
