package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.codec.http.HttpHeaders as KeelHttpHeaders
import io.ktor.http.*

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

    override fun names(): Set<String> {
        val result = linkedSetOf<String>()
        keelHeaders.forEach { name, _ -> result.add(name) }
        return result
    }

    override fun entries(): Set<Map.Entry<String, List<String>>> {
        val map = linkedMapOf<String, MutableList<String>>()
        keelHeaders.forEach { name, value ->
            map.getOrPut(name) { mutableListOf() }.add(value)
        }
        return map.entries
    }

    override fun isEmpty(): Boolean {
        var empty = true
        keelHeaders.forEach { _, _ -> empty = false }
        return empty
    }

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
