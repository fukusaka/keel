package io.github.fukusaka.keel.codec.http

internal actual fun readBypassEnvVar(): Boolean =
    System.getenv("KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS") == "1"
