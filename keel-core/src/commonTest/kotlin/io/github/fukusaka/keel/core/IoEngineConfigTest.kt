package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `default read buffer size matches transport default`() {
        assertEquals(IoTransport.DEFAULT_READ_BUFFER_SIZE, IoEngineConfig().readBufferSize)
    }

    @Test
    fun `custom power-of-two read buffer size is accepted`() {
        assertEquals(16384, IoEngineConfig(readBufferSize = 16384).readBufferSize)
        assertEquals(
            IoEngineConfig.MIN_READ_BUFFER_SIZE,
            IoEngineConfig(readBufferSize = IoEngineConfig.MIN_READ_BUFFER_SIZE).readBufferSize,
        )
        assertEquals(
            IoEngineConfig.MAX_READ_BUFFER_SIZE,
            IoEngineConfig(readBufferSize = IoEngineConfig.MAX_READ_BUFFER_SIZE).readBufferSize,
        )
    }

    @Test
    fun `non power-of-two read buffer size is rejected`() {
        assertFailsWith<IllegalArgumentException> { IoEngineConfig(readBufferSize = 8000) }
        assertFailsWith<IllegalArgumentException> { IoEngineConfig(readBufferSize = 8193) }
    }

    @Test
    fun `read buffer size below minimum is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            IoEngineConfig(readBufferSize = IoEngineConfig.MIN_READ_BUFFER_SIZE / 2)
        }
    }

    @Test
    fun `read buffer size above maximum is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            IoEngineConfig(readBufferSize = IoEngineConfig.MAX_READ_BUFFER_SIZE * 2)
        }
    }
}
