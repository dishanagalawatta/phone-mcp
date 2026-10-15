package com.phonemcp.server.mcp

import com.phonemcp.server.domain.ExternalToolInvoker
import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.mcp.CapabilityIpcContractTest.Companion.descriptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Router integration for dynamic capabilities: merged tools/list,
 * kill-switch gating, proxy routing, and listChanged advertisement.
 */
class ExternalRouterTest {

    private val invoker = object : ExternalToolInvoker {
        override suspend fun invoke(
            capability: ExternalCapability,
            tool: String,
            args: JsonObject,
        ): ToolCallResult = ToolCallResult.text("proxied:$tool")
    }

    private inner class Fixture {
        val registry = ExternalCapabilityRegistry(invoker)
        val gatekeeper = Gatekeeper()
        val router = McpRouter(emptySet(), gatekeeper, registry)

        init {
            registry.register("com.example.notes", descriptor("notes", "search"))
            registry.setApproved("notes", true, forceLocal = true)
        }
    }

    private fun request(method: String, id: Int = 1, params: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    @Test
    fun initializeAdvertisesListChanged() = runBlocking {
        val router = Fixture().router
        val response = router.handle(request("initialize"))!!
        val listChanged = response["result"]!!.jsonObject["capabilities"]!!
            .jsonObject["tools"]!!.jsonObject["listChanged"]!!.jsonPrimitive.content
        assertEquals("true", listChanged)
    }

    @Test
    fun toolsListMergesExternalTools() = runBlocking {
        val router = Fixture().router
        val response = router.handle(request("tools/list"))!!
        val names = response["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.contains("ext_notes__search"))
    }

    @Test
    fun externalCallBlockedByDefaultKillSwitch() = runBlocking {
        val f = Fixture()
        val gatekeeper = f.gatekeeper
        val router = f.router
        // Sanity: ext_ is off out of the box (untrusted third-party code).
        assertEquals(false, gatekeeper.isAllowed("ext_notes__search"))
        val response = router.handle(
            request("tools/call", params = buildJsonObject { put("name", "ext_notes__search") }),
        )!!
        assertEquals(true, response["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun externalCallRoutesWhenEnabled() = runBlocking {
        val f = Fixture()
        val gatekeeper = f.gatekeeper
        val router = f.router
        gatekeeper.setEnabled(Gatekeeper.TOOLS_EXT, true)
        val response = router.handle(
            request(
                "tools/call",
                params = buildJsonObject {
                    put("name", "ext_notes__search")
                    put("arguments", buildJsonObject { })
                },
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "proxied:search",
            result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun malformedExternalNameIsInvalidParams() = runBlocking {
        val f = Fixture()
        val gatekeeper = f.gatekeeper
        val router = f.router
        gatekeeper.setEnabled(Gatekeeper.TOOLS_EXT, true)
        val response = router.handle(
            request("tools/call", params = buildJsonObject { put("name", "ext_bogus") }),
        )!!
        assertEquals(
            JsonRpcCodes.INVALID_PARAMS,
            response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun serverInfoCarriesCapabilityCounts() {
        val router = Fixture().router
        val info = router.serverInfo()
        assertEquals("1", info["externalCapabilities"]!!.jsonPrimitive.content)
        assertEquals("1", info["externalTools"]!!.jsonPrimitive.content)
        // register (1) + approve (2): every list-affecting change bumps.
        assertEquals("2", info["capabilitiesVersion"]!!.jsonPrimitive.content)
    }
}
