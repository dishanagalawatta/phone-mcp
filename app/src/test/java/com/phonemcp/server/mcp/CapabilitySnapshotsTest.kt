package com.phonemcp.server.mcp

import com.phonemcp.server.domain.ExternalToolInvoker
import com.phonemcp.server.mcp.CapabilityIpcContractTest.Companion.descriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitySnapshotsTest {

    private var now = 2_000_000L
    private fun registry(): ExternalCapabilityRegistry =
        ExternalCapabilityRegistry(ExternalToolInvoker.UNREACHABLE).also { it.clockMs = { now } }

    @Test
    fun roundTripPreservesRecord() {
        val source = registry()
        source.register("com.example.a", descriptor("notes", "search"), signerSha256 = "aa")
        source.setApproved("notes", true, forceLocal = true)
        val json = source.exportSnapshots().toJsonString()

        val restored = registry()
        assertEquals(1, restored.importSnapshots(snapshotsFromJsonString(json)))
        val capabilities = restored.listCapabilities()
        assertEquals(1, capabilities.size)
        assertEquals("notes", capabilities[0].id)
        assertEquals("com.example.a", capabilities[0].packageName)
        assertTrue(capabilities[0].approved)
        assertEquals("aa", capabilities[0].signerSha256)
        assertEquals(listOf("ext_notes__search"), restored.listTools().map { it.name })
    }

    @Test
    fun corruptInputYieldsEmpty() {
        assertTrue(snapshotsFromJsonString("").isEmpty())
        assertTrue(snapshotsFromJsonString("not json{{").isEmpty())
        assertTrue(snapshotsFromJsonString("[{\"id\":1}]").isEmpty())
    }

    @Test
    fun importRevalidatesAndSkipsBad() {
        val tools = CapabilityIpcContractTest.toolsArray("search").toString()
        val good = ExternalCapabilitySnapshot("notes", "com.example.a", "N", "1", tools, "", false, 0L)
        val badId = ExternalCapabilitySnapshot("Bad Id!", "com.example.a", "N", "1", tools, "", false, 0L)
        val badTools = ExternalCapabilitySnapshot("notes", "com.example.a", "N", "1", "[]", "", false, 0L)
        val restored = registry()
        assertEquals(1, restored.importSnapshots(listOf(good, badId, badTools)))
    }

    @Test
    fun importGrantsFreshHeartbeat() {
        val source = registry()
        source.register("com.example.a", descriptor("notes", "search"))
        val json = source.exportSnapshots().toJsonString()
        // The original record would now be stale; the import must still be live.
        now += ExternalCapabilityRegistry.HEARTBEAT_TIMEOUT_MS + 1L
        val restored = registry()
        assertEquals(1, restored.importSnapshots(snapshotsFromJsonString(json)))
        assertEquals(1, restored.listCapabilities().size)
    }
}
