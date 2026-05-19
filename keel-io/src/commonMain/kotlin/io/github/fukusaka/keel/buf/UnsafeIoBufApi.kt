package io.github.fukusaka.keel.buf

/**
 * Marks the raw-memory escape-hatch surface of [IoBuf] — `unsafePointer`,
 * `unsafeBuffer`, `unsafeNioByteBuffer`. These expose platform-native
 * memory directly (a C pointer / a direct `ByteBuffer`) with no bounds
 * checking or lifetime safety; they exist for keel's own engine and codec
 * hot paths that read into / write out of the kernel.
 *
 * Application code (e.g. a route handler holding an `IoBuf` from
 * `HttpCall.receiveChunk()`) must not need these — the ordinary
 * bounds-checked `IoBuf` accessors suffice. Opting in is a deliberate,
 * auditable "I am engine-internal code" declaration.
 */
@RequiresOptIn(
    message = "This is a raw, unchecked memory escape hatch for keel engine/codec internals. " +
        "Opt in only from trusted engine code.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
public annotation class UnsafeIoBufApi
