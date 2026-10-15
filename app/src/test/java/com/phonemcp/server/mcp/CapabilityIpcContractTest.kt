package com.phonemcp.server.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityIpcContractTest {

    @Test
    fun namespacedNameRoundTrips() {
        val full = CapabilityIpcContract.namespacedName("notes", "search")
        assertEquals("ext_notes__search", full)
        assertEquals("notes" to "search", CapabilityIpcContract.parseNamespaced(full))
    }

    @Test
    fun parseRejectsMalformedNames() {
        assertNull(CapabilityIpcContract.parseNamespaced("vibrate"))
        assertNull(CapabilityIpcContract.parseNamespaced("ext_"))
        assertNull(CapabilityIpcContract.parseNamespaced("ext_notes_search"))
        assertNull(CapabilityIpcContract.parseNamespaced("ext_Notes__search"))
        assertNull(CapabilityIpcContract.parseNamespaced("ext_notes__Search"))
        assertNull(CapabilityIpcContract.parseNamespaced("ext_notes__9bad"))
        assertNull(CapabilityIpcContract.parseNamespaced("ext_thiscapabilityidistoolong__tool"))
    }

    @Test
    fun validateAcceptsWellFormedDescriptor() {
        assertTrue(CapabilityIpcContract.validateDescriptor(descriptor("notes", "search")).isEmpty())
    }

    @Test
    fun validateRejectsBadIdentity() {
        val badId = buildJsonObject {
            put("capabilityId", "Has-Dashes!")
            put("displayName", "x")
            put("version", "1")
            put("tools", toolsArray("a"))
        }
        assertTrue(CapabilityIpcContract.validateDescriptor(badId).any { it.contains("capabilityId") })
    }

    @Test
    fun validateRejectsEmptyAndDuplicateTools() {
        val empty = buildJsonObject {
            put("capabilityId", "notes")
            put("displayName", "x")
            put("version", "1")
            put("tools", JsonArray(emptyList()))
        }
        assertTrue(CapabilityIpcContract.validateDescriptor(empty).any { it.contains("tools") })

        val dup = descriptor("notes", "a", "a")
        assertTrue(CapabilityIpcContract.validateDescriptor(dup).any { it.contains("duplicates") })
    }

    @Test
    fun validateRejectsNonObjectSchema() {
        val bad = buildJsonObject {
            put("capabilityId", "notes")
            put("displayName", "x")
            put("version", "1")
            put(
                "tools",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("name", "search")
                            put("description", "d")
                            put("inputSchema", buildJsonObject { put("type", "string") })
                        },
                    ),
                ),
            )
        }
        assertTrue(CapabilityIpcContract.validateDescriptor(bad).any { it.contains("inputSchema") })
    }

    companion object {
        fun toolsArray(vararg names: String): JsonArray =
            JsonArray(
                names.map { name ->
                    buildJsonObject {
                        put("name", name)
                        put("description", "Does $name")
                        put("inputSchema", buildJsonObject { put("type", "object") })
                    }
                },
            )

        fun descriptor(id: String, vararg tools: String) = buildJsonObject {
            put("capabilityId", id)
            put("displayName", "Test $id")
            put("version", "1")
            put("tools", toolsArray(*tools))
        }
    }
}
