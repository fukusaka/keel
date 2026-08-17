package io.github.fukusaka.keel.native.posix

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The published region limit is what every gather caller batches against, so
 * its own shape is worth pinning: a batch loop computing `minOf(owed, IOV_MAX)`
 * against a non-positive constant offers zero regions, and a zero-region write
 * is reported as a definitive failure — which drops the whole queue. The
 * constant is the only thing standing between a batching caller and that.
 *
 * The exact value is the platform's and not asserted — POSIX requires at
 * least 16 (`_XOPEN_IOV_MAX`), and both hosts this project builds on report
 * 1024.
 */
class IovMaxTest {

    @Test
    fun `the published region limit is a usable batch size`() {
        assertTrue(IOV_MAX >= 16, "a gather that cannot offer 16 regions is not usable, got: $IOV_MAX")
    }
}
