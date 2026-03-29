package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.buf.defaultAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IoEngineConfigTest {

    @Test
    fun `default allocator matches platform default type`() {
        val config = IoEngineConfig()
        assertIs<Any>(config.allocator) // non-null
        assertEquals(defaultAllocator()::class, config.allocator::class)
    }

    @Test
    fun `default threads is zero`() {
        val config = IoEngineConfig()
        assertEquals(0, config.threads)
    }

    @Test
    fun `custom threads value`() {
        val config = IoEngineConfig(threads = 4)
        assertEquals(4, config.threads)
    }

    @Test
    fun `copy preserves allocator`() {
        val config = IoEngineConfig(threads = 2)
        val copied = config.copy(threads = 8)
        assertEquals(config.allocator::class, copied.allocator::class)
        assertEquals(8, copied.threads)
    }
}
