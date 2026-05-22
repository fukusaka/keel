package io.github.fukusaka.keel.server.ktor

import io.ktor.http.Headers
import io.github.fukusaka.keel.codec.http.HttpHeaders as KeelHttpHeaders

/**
 * Adapts keel [KeelHttpHeaders] to Ktor [Headers].
 */
internal class KeelHeaders(
    private val keelHeaders: KeelHttpHeaders,
) : Headers {

    override val caseInsensitiveName: Boolean get() = true

    /**
     * Name → values grouped map, built **once per request** on the first
     * enumeration ([names] / [entries] / [forEach]) and reused thereafter,
     * matching Ktor's own `CIOHeaders` (lazy `names` + lazy `entries`).
     * The single-header [get] / [contains] / [getAll] paths stay fully
     * lazy and never trigger this build — so a request that only reads a
     * few headers by name pays no full materialisation, and one that
     * enumerates repeatedly pays the grouping only once.
     */
    private val grouped: Map<String, List<String>> by lazy(LazyThreadSafetyMode.NONE) {
        val map = linkedMapOf<String, MutableList<String>>()
        keelHeaders.forEach { name, value ->
            map.getOrPut(name) { mutableListOf() }.add(value)
        }
        map
    }

    override fun getAll(name: String): List<String>? {
        val values = keelHeaders.getAll(name)
        return values.ifEmpty { null }
    }

    override fun names(): Set<String> = grouped.keys

    override fun entries(): Set<Map.Entry<String, List<String>>> = grouped.entries

    override fun isEmpty(): Boolean = keelHeaders.isEmpty

    override fun get(name: String): String? = keelHeaders.getString(name)

    override fun contains(name: String): Boolean = name in keelHeaders

    override fun contains(name: String, value: String): Boolean =
        keelHeaders.getAll(name).any { it.equals(value, ignoreCase = true) }

    override fun forEach(body: (String, List<String>) -> Unit) {
        grouped.forEach { (name, values) -> body(name, values) }
    }
}
