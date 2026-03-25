package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.io.HeapAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class IoEngineConfigTest {

    @Test
    fun `default allocator is HeapAllocator`() {
        val config = IoEngineConfig()
        assertSame(HeapAllocator, config.allocator)
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
        assertSame(HeapAllocator, copied.allocator)
        assertEquals(8, copied.threads)
    }
}
