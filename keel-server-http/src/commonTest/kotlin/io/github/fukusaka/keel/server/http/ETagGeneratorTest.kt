package io.github.fukusaka.keel.server.http

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Unit tests for [ETagGenerator.Default] and [ETagGenerator.None]. */
class ETagGeneratorTest {

    /** An [Asset] test double exposing only the metadata an [ETagGenerator] reads. */
    private class FakeAsset(
        override val size: Long,
        override val lastModified: Instant?,
    ) : Asset {
        override val contentType: String? = null
        override val etag: String? = null
        override fun open(offset: Long, length: Long): RawSource = Buffer()
    }

    @Test
    fun `Default produces a weak validator from mtime and size`() {
        val asset = FakeAsset(size = 0x5fL, lastModified = Instant.fromEpochMilliseconds(0x1a2bL))
        assertEquals("W/\"1a2b-5f\"", ETagGenerator.Default.generate(asset))
    }

    @Test
    fun `Default is weak - it carries the W slash prefix`() {
        val asset = FakeAsset(size = 1L, lastModified = Instant.fromEpochMilliseconds(1000L))
        assertTrue(ETagGenerator.Default.generate(asset)?.startsWith("W/\"") == true)
    }

    @Test
    fun `Default returns null when the asset has no mtime`() {
        assertNull(ETagGenerator.Default.generate(FakeAsset(size = 10L, lastModified = null)))
    }

    @Test
    fun `None always returns null`() {
        assertNull(ETagGenerator.None.generate(FakeAsset(size = 10L, lastModified = Instant.fromEpochMilliseconds(1L))))
    }
}
