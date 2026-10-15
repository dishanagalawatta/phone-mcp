package com.phonemcp.server.mcp

import com.phonemcp.server.domain.ExternalToolInvoker
import com.phonemcp.server.mcp.CapabilityIpcContractTest.Companion.descriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalCapabilityRegistryTest {

    private class FakeInvoker : ExternalToolInvoker {
        var lastTool: String? = null
        var lastPackage: String? = null
        override suspend fun invoke(
            capability: ExternalCapability,
            tool: String,
            args: JsonObject,
        ): ToolCallResult {
            lastTool = tool
            lastPackage = capability.packageName
            return ToolCallResult.text("ok:$tool")
        }
    }

    private var now = 1_000_000L
    private val invoker = FakeInvoker()
    private fun registry(): ExternalCapabilityRegistry =
        ExternalCapabilityRegistry(invoker).also { it.clockMs = { now } }

    @Test
    fun registerAcceptsValidDescriptor() {
        val registry = registry()
        val outcome = registry.register("com.example.notes", descriptor("notes", "search", "add"))
        assertTrue(outcome is ExternalCapabilityRegistry.RegisterOutcome.Ok)
        val names = (outcome as ExternalCapabilityRegistry.RegisterOutcome.Ok).toolNames
        assertEquals(listOf("ext_notes__search", "ext_notes__add"), names)
        assertEquals(1L, registry.version.value)
    }

    @Test
    fun registerRejectsInvalidDescriptor() {
        val registry = registry()
        val bad = buildJsonObject {
            put("capabilityId", "Bad Id!")
            put("displayName", "x")
            put("version", "1")
            put("tools", CapabilityIpcContractTest.toolsArray("a"))
        }
        val outcome = registry.register("com.example.notes", bad)
        assertTrue(outcome is ExternalCapabilityRegistry.RegisterOutcome.Rejected)
        assertTrue(registry.listTools().isEmpty())
    }

    @Test
    fun registerEnforcesOwnership() {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        val outcome = registry.register("com.example.b", descriptor("notes", "search"))
        assertTrue(outcome is ExternalCapabilityRegistry.RegisterOutcome.Rejected)
        assertTrue(
            (outcome as ExternalCapabilityRegistry.RegisterOutcome.Rejected)
                .errors.any { it.contains("com.example.a") },
        )
    }

    @Test
    fun samePackageReplaceUpdatesVersion() {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        val outcome = registry.register("com.example.a", descriptor("notes", "search", "add"))
        assertTrue(outcome is ExternalCapabilityRegistry.RegisterOutcome.Ok)
        assertEquals(2, registry.listTools().size)
        assertEquals(2L, registry.version.value)
    }

    @Test
    fun listToolsAnnotatesOrigin() {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        val tools = registry.listTools()
        assertEquals(1, tools.size)
        assertEquals("ext_notes__search", tools[0].name)
        assertTrue(tools[0].description.contains("Test notes"))
    }

    @Test
    fun unregisterNeedsOwnerOrForce() {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        assertFalse(registry.unregister("notes", requesterPackage = "com.example.b"))
        assertTrue(registry.listTools().isNotEmpty())
        assertTrue(registry.unregister("notes", requesterPackage = "com.example.a"))
        assertTrue(registry.listTools().isEmpty())
    }

    @Test
    fun dashboardForceUnregisterRemovesAnyOwner() {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        assertTrue(registry.unregister("notes", forceLocal = true))
    }

    @Test
    fun heartbeatRefreshesLiveness() {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        now += ExternalCapabilityRegistry.HEARTBEAT_TIMEOUT_MS - 1_000L
        assertTrue(registry.heartbeat("notes", requesterPackage = "com.example.a"))
        now += 2_000L
        // Heartbeat moved the window: still alive.
        assertEquals(1, registry.listCapabilities().size)
        now += ExternalCapabilityRegistry.HEARTBEAT_TIMEOUT_MS + 1L
        assertEquals(0, registry.listCapabilities().size)
    }

    @Test
    fun expiryEvictsStaleRecords() {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        now += ExternalCapabilityRegistry.HEARTBEAT_TIMEOUT_MS + 1L
        val removed = registry.pruneExpired()
        assertEquals(listOf("notes"), removed)
        assertTrue(registry.listTools().isEmpty())
    }

    @Test
    fun callRoutesToInvoker() = runBlocking {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        registry.setApproved("notes", true, forceLocal = true)
        val result = registry.callTool("ext_notes__search", buildJsonObject { })
        assertFalse(result.isError)
        assertEquals("ok:search", result.content[0]["text"]!!.jsonPrimitive.content)
        assertEquals("search", invoker.lastTool)
        assertEquals("com.example.a", invoker.lastPackage)
    }

    @Test
    fun callUnknownReturnsInvalidParams() = runBlocking {
        val registry = registry()
        val result = registry.callTool("ext_ghost__ping", buildJsonObject { })
        assertTrue(result.isError)
        assertEquals(JsonRpcCodes.INVALID_PARAMS, result.rpcCode)
    }

    @Test
    fun toolLimitRejected() {
        val registry = registry()
        val many = Array(CapabilityIpcContract.MAX_TOOLS_PER_CAPABILITY + 1) { "t$it" }
        val outcome = registry.register("com.example.a", descriptor("notes", *many))
        assertTrue(outcome is ExternalCapabilityRegistry.RegisterOutcome.Rejected)
    }

    @Test
    fun capabilityLimitRejected() {
        val registry = registry()
        repeat(CapabilityIpcContract.MAX_CAPABILITIES) { index ->
            val id = "c$index"
            val outcome = registry.register("com.example.a", descriptor(id, "ping"))
            assertTrue(outcome is ExternalCapabilityRegistry.RegisterOutcome.Ok)
        }
        val outcome = registry.register("com.example.a", descriptor("overflow", "ping"))
        assertTrue(outcome is ExternalCapabilityRegistry.RegisterOutcome.Rejected)
    }

    @Test
    fun serverInfoExtraReportsCounts() {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search", "add"))
        val extra = registry.serverInfoExtra()
        assertEquals(2, extra["externalTools"]!!.jsonPrimitive.int)
        assertEquals(1, extra["externalCapabilities"]!!.jsonPrimitive.int)
    }

    @Test
    fun approvalDefaultsDenyAndGatesCall() = runBlocking {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        val denied = registry.callTool("ext_notes__search", buildJsonObject { })
        assertTrue(denied.isError)
        assertTrue(denied.content[0]["text"]!!.jsonPrimitive.content.contains("not approved"))
        assertTrue(registry.setApproved("notes", true, forceLocal = true))
        assertFalse(registry.callTool("ext_notes__search", buildJsonObject { }).isError)
        assertTrue(registry.setApproved("notes", false, forceLocal = true))
        assertTrue(registry.callTool("ext_notes__search", buildJsonObject { }).isError)
    }

    @Test
    fun setApprovedRejectsOtherOwner() = runBlocking {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        assertFalse(registry.setApproved("notes", true, requesterPackage = "com.example.b"))
        assertTrue(registry.callTool("ext_notes__search", buildJsonObject { }).isError)
    }

    @Test
    fun approvalSurvivesSameOwnerReregister() = runBlocking {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        registry.setApproved("notes", true, forceLocal = true)
        registry.register("com.example.a", descriptor("notes", "search", "add"))
        assertFalse(registry.callTool("ext_notes__add", buildJsonObject { }).isError)
    }

    @Test
    fun signerChangeRejected() {
        val registry = registry()
        val first = registry.register("com.example.a", descriptor("notes", "search"), signerSha256 = "aa")
        assertTrue(first is ExternalCapabilityRegistry.RegisterOutcome.Ok)
        val hijack = registry.register("com.example.a", descriptor("notes", "search"), signerSha256 = "bb")
        assertTrue(hijack is ExternalCapabilityRegistry.RegisterOutcome.Rejected)
        assertTrue(
            (hijack as ExternalCapabilityRegistry.RegisterOutcome.Rejected)
                .errors.any { it.contains("signer changed") },
        )
    }

    @Test
    fun emptySignerAdoptsFirstPin() {
        val registry = registry()
        registry.register("com.example.a", descriptor("notes", "search"))
        val outcome = registry.register("com.example.a", descriptor("notes", "search"), signerSha256 = "aa")
        assertTrue(outcome is ExternalCapabilityRegistry.RegisterOutcome.Ok)
        assertEquals("aa", registry.listCapabilities().first().signerSha256)
    }
}
