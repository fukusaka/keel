package io.github.fukusaka.keel.ktor

import io.ktor.http.*
import io.github.fukusaka.keel.codec.http.HttpHeaders as KeelHttpHeaders

/**
 * Adapts keel [KeelHttpHeaders] to Ktor [Headers].
 */
internal class KeelHeaders(
    private val keelHeaders: KeelHttpHeaders,
) : Headers {

    override val caseInsensitiveName: Boolean get() = true

    override fun getAll(name: String): List<String>? {
        val values = keelHeaders.getAll(name)
        return values.ifEmpty { null }
    }

    override fun names(): Set<String> = keelHeaders.names()

    override fun entries(): Set<Map.Entry<String, List<String>>> {
        val map = linkedMapOf<String, MutableList<String>>()
        keelHeaders.forEach { name, value ->
            map.getOrPut(name) { mutableListOf() }.add(value)
        }
        return map.entries
    }

    override fun isEmpty(): Boolean = keelHeaders.isEmpty

    override fun get(name: String): String? = keelHeaders[name]

    override fun contains(name: String): Boolean = name in keelHeaders

    override fun contains(name: String, value: String): Boolean =
        keelHeaders.getAll(name).any { it.equals(value, ignoreCase = true) }

    override fun forEach(body: (String, List<String>) -> Unit) {
        val map = linkedMapOf<String, MutableList<String>>()
        keelHeaders.forEach { name, value ->
            map.getOrPut(name) { mutableListOf() }.add(value)
        }
        map.forEach { (name, values) -> body(name, values) }
    }
}
