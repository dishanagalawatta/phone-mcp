package com.phonemcp.server.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Shared JSON-arg coercion helpers. Pure-Kotlin, JVM-testable.
 * Eliminates the `((args[x] as? JsonPrimitive)?.longOrNull ?: d).coerceIn(...)`
 * copy-paste across every provider.
 */
object ArgParser {
    fun long(args: JsonObject, key: String, default: Long, range: LongRange): Long =
        ((args[key] as? JsonPrimitive)?.longOrNull ?: default).coerceIn(range.first, range.last)

    fun int(args: JsonObject, key: String, default: Int, range: IntRange): Int =
        ((args[key] as? JsonPrimitive)?.longOrNull?.toInt() ?: default).coerceIn(range.first, range.last)

    fun double(args: JsonObject, key: String, default: Double, range: ClosedRange<Double>): Double =
        ((args[key] as? JsonPrimitive)?.doubleOrNull ?: default).coerceIn(range.start, range.endInclusive)

    fun boolean(args: JsonObject, key: String, default: Boolean): Boolean =
        (args[key] as? JsonPrimitive)?.booleanOrNull ?: default

    fun string(args: JsonObject, key: String, default: String = ""): String =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: default

    fun requiredString(args: JsonObject, key: String): String {
        val v = string(args, key)
        require(v.isNotEmpty()) { "Missing '$key'" }
        return v
    }
}
