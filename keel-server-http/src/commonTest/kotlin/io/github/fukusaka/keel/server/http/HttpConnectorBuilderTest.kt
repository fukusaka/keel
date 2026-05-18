package io.github.fukusaka.keel.server.http

import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit tests for [HttpConnectorBuilder] and its `queryParameters { }` block. */
class HttpConnectorBuilderTest {

    @Test
    fun `connector fields are forwarded to the built ServerConnector`() {
        val connector = HttpConnectorBuilder().apply {
            host = "127.0.0.1"
            port = 8080
            backlog = 64
        }.buildConnector()

        assertEquals("127.0.0.1", connector.host)
        assertEquals(8080, connector.port)
        assertEquals(64, connector.backlog)
    }

    @Test
    fun `the default query config is used when no queryParameters block is given`() {
        val config = HttpConnectorBuilder().buildQueryConfig()
        assertEquals(QueryParameterConfig.DEFAULT.maxParameterCount, config.maxParameterCount)
    }

    @Test
    fun `the queryParameters block configures the query config`() {
        val config = HttpConnectorBuilder().apply {
            queryParameters {
                maxParameterCount = 5
                rejectControlCharacters = true
                rejectMalformedEncoding = true
            }
        }.buildQueryConfig()

        assertEquals(5, config.maxParameterCount)
        assertEquals(true, config.rejectControlCharacters)
        assertEquals(true, config.rejectMalformedEncoding)
    }
}
