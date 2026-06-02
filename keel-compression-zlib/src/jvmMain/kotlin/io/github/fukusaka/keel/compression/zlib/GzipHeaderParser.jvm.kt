package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.DecompressionException

/**
 * Chunk-aware RFC 1952 gzip header parser.
 *
 * Consumes gzip header bytes byte-by-byte across an arbitrary number
 * of input chunks (the `JvmZlibDecoderSession.update` is invoked per
 * `IoBuf` arrival, so a 14-byte gzip header can split across N
 * chunks). When the header completes, returns the unconsumed tail of
 * the current input chunk so the inflater can process the deflate
 * payload from the same call.
 *
 * Handles every standard FLG bit per RFC 1952 §2.3:
 *
 * ```
 *   FTEXT     bit 0  → no extra payload
 *   FHCRC     bit 1  → 2-byte CRC16 of header preceding it
 *   FEXTRA    bit 2  → 2-byte XLEN + XLEN bytes of extra data
 *   FNAME     bit 3  → NUL-terminated filename
 *   FCOMMENT  bit 4  → NUL-terminated comment
 *   bits 5-7         → reserved (parser rejects)
 * ```
 *
 * The parser does not validate FHCRC checksum bytes against the
 * header content (it only consumes / discards them); RFC 1952 leaves
 * FHCRC verification optional and most decoders skip it.
 *
 * Implementation pattern follows OkHttp's `GzipSource` and Netty's
 * `JdkZlibDecoder.gzipHeader()` — a small state enum + per-byte loop.
 */
internal class GzipHeaderParser {

    private enum class State {
        ID1,
        ID2,
        CM,
        FLG,
        MTIME,
        XFL,
        OS,
        XLEN_LO,
        XLEN_HI,
        EXTRA_BYTES,
        FNAME_BYTES,
        FCOMMENT_BYTES,
        FHCRC_LO,
        FHCRC_HI,
        DONE,
    }

    private var state: State = State.ID1
    private var flg: Int = 0
    private var mtimeLeft: Int = 4
    private var xlenLow: Int = 0
    private var extraLeft: Int = 0

    /** True once the entire header has been consumed and the next byte belongs to the deflate payload. */
    val done: Boolean get() = state == State.DONE

    /**
     * Feed one chunk of input bytes. Returns:
     *
     *  - `null` — header still incomplete; parser consumed all of [bytes]
     *  - non-null `ByteArray` — header completed mid-chunk; returned
     *    array is the unconsumed tail (which the caller routes into the
     *    inflater). May be empty (header consumed exactly to chunk end).
     *
     * @throws DecompressionException on invalid magic, unsupported CM,
     *   or reserved FLG bits.
     */
    fun consume(bytes: ByteArray): ByteArray? = consume(bytes, bytes.size)

    /**
     * Length-aware variant: only the first [length] bytes of [bytes] are
     * consumed. Lets the caller hand in a reusable scratch array that may be
     * larger than the actual input — eliminates the `ByteArray(n)` per-update
     * allocation on the gzip-header parse path. Returned tail (when non-null)
     * is sized to the unconsumed portion, not to the full backing array.
     */
    fun consume(bytes: ByteArray, length: Int): ByteArray? {
        require(length in 0..bytes.size) { "length=$length out of bounds for bytes.size=${bytes.size}" }
        var i = 0
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            i++
            advance(b)
            if (state == State.DONE) {
                return if (i < length) bytes.copyOfRange(i, length) else EMPTY_BYTES
            }
        }
        return null
    }

    /** Per-byte state advance; split out from [consume] to keep its cyclomatic complexity tractable. */
    private fun advance(b: Int) {
        when (state) {
            State.ID1, State.ID2, State.CM -> advanceFixedMagic(b)
            State.FLG -> advanceFlg(b)
            State.MTIME -> advanceMtime()
            State.XFL -> { state = State.OS }
            State.OS -> { state = nextStateAfterFixedHeader() }
            State.XLEN_LO, State.XLEN_HI, State.EXTRA_BYTES -> advanceFextra(b)
            State.FNAME_BYTES -> if (b == 0) state = stateAfterFname()
            State.FCOMMENT_BYTES -> if (b == 0) state = stateAfterFcomment()
            State.FHCRC_LO -> { state = State.FHCRC_HI }
            State.FHCRC_HI -> { state = State.DONE }
            State.DONE -> Unit
        }
    }

    private fun advanceFixedMagic(b: Int) {
        val (next, badness) = when (state) {
            State.ID1 -> if (b == 0x1F) State.ID2 to null else null to "invalid gzip ID1: 0x${b.toString(16)}"
            State.ID2 -> if (b == 0x8B) State.CM to null else null to "invalid gzip ID2: 0x${b.toString(16)}"
            State.CM -> if (b == 0x08) State.FLG to null else null to "unsupported gzip CM: $b"
            else -> error("unreachable: $state")
        }
        if (badness != null) throw DecompressionException(badness)
        state = next!!
    }

    private fun advanceFlg(b: Int) {
        if (b and 0xE0 != 0) {
            throw DecompressionException("reserved gzip FLG bits set: $b")
        }
        flg = b
        state = State.MTIME
        mtimeLeft = 4
    }

    private fun advanceMtime() {
        mtimeLeft--
        if (mtimeLeft == 0) state = State.XFL
    }

    private fun advanceFextra(b: Int) {
        when (state) {
            State.XLEN_LO -> {
                xlenLow = b
                state = State.XLEN_HI
            }
            State.XLEN_HI -> {
                extraLeft = (b shl 8) or xlenLow
                state = if (extraLeft > 0) State.EXTRA_BYTES else stateAfterExtra()
            }
            State.EXTRA_BYTES -> {
                extraLeft--
                if (extraLeft == 0) state = stateAfterExtra()
            }
            else -> error("unreachable: $state")
        }
    }

    private fun nextStateAfterFixedHeader(): State = when {
        flg and FEXTRA != 0 -> State.XLEN_LO
        flg and FNAME != 0 -> State.FNAME_BYTES
        flg and FCOMMENT != 0 -> State.FCOMMENT_BYTES
        flg and FHCRC != 0 -> State.FHCRC_LO
        else -> State.DONE
    }

    private fun stateAfterExtra(): State = when {
        flg and FNAME != 0 -> State.FNAME_BYTES
        flg and FCOMMENT != 0 -> State.FCOMMENT_BYTES
        flg and FHCRC != 0 -> State.FHCRC_LO
        else -> State.DONE
    }

    private fun stateAfterFname(): State = when {
        flg and FCOMMENT != 0 -> State.FCOMMENT_BYTES
        flg and FHCRC != 0 -> State.FHCRC_LO
        else -> State.DONE
    }

    private fun stateAfterFcomment(): State = when {
        flg and FHCRC != 0 -> State.FHCRC_LO
        else -> State.DONE
    }

    private companion object {
        const val FHCRC = 0x02
        const val FEXTRA = 0x04
        const val FNAME = 0x08
        const val FCOMMENT = 0x10

        val EMPTY_BYTES: ByteArray = ByteArray(0)
    }
}
