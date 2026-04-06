package io.github.fukusaka.keel.benchmark

/**
 * Try to load and register the JVM TLS provider via reflection.
 *
 * When built with `-Ptls`, `JvmTlsInit` (from jvmTls source set) is
 * available and registers the JSSE backend. Without `-Ptls`, the class
 * is absent and `--tls` produces a runtime error from [createTlsCodecFactory].
 */
internal fun initJvmTlsProvider() {
    try {
        val clazz = Class.forName("io.github.fukusaka.keel.benchmark.JvmTlsInit")
        clazz.getMethod("register").invoke(clazz.kotlin.objectInstance)
    } catch (_: ClassNotFoundException) {
        // -Ptls not set — TLS benchmarking unavailable
    }
}
