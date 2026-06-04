package io.github.fukusaka.keel.compression

/**
 * Registry of [CompressionCodec] / [Encoder] / [Decoder] instances
 * keyed by coding token, with a configurable [priority][registerEncoder]
 * used as a tie-breaker by negotiators.
 *
 * Each coding-name slot holds at most one instance — re-registering an
 * existing name overwrites. Tokens are matched case-insensitively (ASCII).
 *
 * Transport-agnostic by design: this is the compression *algorithm* layer
 * (codec instances + their priority). HTTP `Accept-Encoding` negotiation
 * (RFC 9110 §12.5.3) lives in `keel-codec-http`, which enumerates the
 * registered encoders via [registeredEncoders]; the registry itself knows
 * nothing about HTTP headers.
 *
 * **Thread-safety: register before use, then read-only.** The backing
 * maps are plain (unsynchronized) [LinkedHashMap]s, built on the setup
 * thread and read concurrently by EventLoop threads at runtime. That is
 * safe **only** while registration completes before the registry is
 * handed to the pipeline — a lazy `registerEncoder` from a per-request
 * handler would race the concurrent lookups and corrupt the map. To make
 * that misuse fail fast instead of silently corrupting, the registry can
 * be **sealed** ([seal]): once sealed, any `register*` throws
 * [IllegalStateException] while lookups stay open. The `keel-server-http`
 * compression DSL seals the registry when it finalizes the pipeline
 * config, so a register() after server setup fails loudly; registries
 * built directly (e.g. in tests) are unsealed until [seal] is called.
 */
public class CompressionRegistry {

    private val byName: MutableMap<String, RegisteredEncoder> = LinkedHashMap()
    private val decoderByName: MutableMap<String, Decoder> = LinkedHashMap()

    // Once true, register* throws. Flipped by seal() at the setup→runtime
    // boundary; lookups never touch it (no hot-path write).
    private var sealed: Boolean = false

    /**
     * Mark the registry read-only at the setup→runtime boundary. After
     * this, any `register*` throws [IllegalStateException]; lookups stay
     * open. Idempotent. Called by the consuming pipeline (e.g. the
     * `keel-server-http` compression DSL) once registration is complete,
     * so a late registration races nothing — it fails fast instead.
     */
    public fun seal() {
        sealed = true
    }

    private fun checkMutable() {
        check(!sealed) {
            "CompressionRegistry is sealed: register all codecs at setup, before the registry is handed " +
                "to a pipeline (a late register() would race concurrent lookups on the unsynchronized map)"
        }
    }

    /** Register an [Encoder] (server-side). Higher [priority] wins ties. */
    public fun registerEncoder(encoder: Encoder, priority: Int = 0) {
        checkMutable()
        byName[encoder.name.lowercase()] = RegisteredEncoder(encoder, priority)
    }

    /** Register a [Decoder] (client-side). */
    public fun registerDecoder(decoder: Decoder) {
        checkMutable()
        decoderByName[decoder.name.lowercase()] = decoder
    }

    /** Register both halves of a [CompressionCodec]. Convenience. */
    public fun register(codec: CompressionCodec, priority: Int = 0) {
        registerEncoder(codec.encoder, priority)
        registerDecoder(codec.decoder)
    }

    /** Look up an encoder by `Content-Encoding` token (case-insensitive). */
    public fun findEncoder(name: String): Encoder? = byName[name.lowercase()]?.encoder

    /** Look up a decoder by `Content-Encoding` token (case-insensitive). */
    public fun findDecoder(name: String): Decoder? = decoderByName[name.lowercase()]

    /**
     * The registered encoders with their priorities, for a negotiator to
     * enumerate (e.g. `keel-codec-http`'s `Accept-Encoding` selection).
     *
     * A read-only view over the registry's current contents (not a copy);
     * registration is expected to be complete before the consuming
     * pipeline starts (see the class thread-safety note), so the view is
     * stable in practice. Each encoder's coding token is [Encoder.name].
     */
    public fun registeredEncoders(): Collection<RegisteredEncoder> = byName.values

    /** A registered [encoder] with the [priority] that breaks negotiation ties (higher wins). */
    public class RegisteredEncoder internal constructor(public val encoder: Encoder, public val priority: Int)
}
