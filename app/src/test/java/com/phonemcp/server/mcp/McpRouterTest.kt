package com.phonemcp.server.mcp

import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.domain.ToolProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRouterTest {

    private fun stubProvider(toolName: String, reply: String): ToolProvider =
        object : ToolProvider {
            override val id = "stub"
            override fun listTools() = listOf(
                ToolDefinition(toolName, "stub $toolName", buildJsonObject { put("type", "object") }),
            )
            override suspend fun call(name: String, args: JsonObject) = ToolCallResult.text(reply)
        }

    private fun routerWith(vararg providers: ToolProvider): McpRouter =
        McpRouter(providers.toSet(), Gatekeeper())

    private fun request(method: String, id: Int = 1, params: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    @Test
    fun initializeNegotiatesRequestedVersion() = runBlocking {
        val router = routerWith()
        val response = router.handle(
            request(
                "initialize",
                params = buildJsonObject { put("protocolVersion", "2025-03-26") },
            ),
        )!!
        assertEquals("2025-03-26", response["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun initializeFallsBackToLatestOnUnknownVersion() = runBlocking {
        val router = routerWith()
        val response = router.handle(
            request(
                "initialize",
                params = buildJsonObject { put("protocolVersion", "1999-01-01") },
            ),
        )!!
        assertEquals(
            McpRouter.LATEST_VERSION,
            response["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun unknownMethodReturnsMethodNotFound() = runBlocking {
        val router = routerWith()
        val response = router.handle(request("resources/list"))!!
        assertEquals(
            JsonRpcCodes.METHOD_NOT_FOUND,
            response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun toolsListExposesProviderTools() = runBlocking {
        val router = routerWith(stubProvider("vibrate", "ok"))
        val response = router.handle(request("tools/list"))!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("vibrate", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsCallUnknownToolReturnsInvalidParams() = runBlocking {
        val router = routerWith()
        val response = router.handle(
            request("tools/call", params = buildJsonObject { put("name", "nope") }),
        )!!
        assertEquals(
            JsonRpcCodes.INVALID_PARAMS,
            response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun toolsCallRoutesToProvider() = runBlocking {
        val router = routerWith(stubProvider("vibrate", "buzz"))
        val response = router.handle(
            request(
                "tools/call",
                params = buildJsonObject {
                    put("name", "vibrate")
                    put("arguments", buildJsonObject { })
                },
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("buzz", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun killSwitchedToolReturnsIsErrorResult() = runBlocking {
        // Gatekeeper disables audio_*/camera_* by default; no provider needed.
        val router = routerWith()
        val response = router.handle(
            request("tools/call", params = buildJsonObject { put("name", "camera_capture") }),
        )!!
        val result = response["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun pingReturnsEmptyResult() = runBlocking {
        val router = routerWith()
        val response = router.handle(request("ping"))!!
        assertTrue(response["result"]!!.jsonObject.isEmpty())
    }

    @Test
    fun notificationReturnsNull() = runBlocking {
        val router = routerWith()
        assertNull(router.handle(request("notifications/initialized")))
    }

    @Test
    fun errorResultMarksIsError() {
        val result = ToolCallResult.error("nope")
        assertTrue(result.isError)
        assertEquals(1, result.content.size)
        assertEquals("nope", result.content[0]["text"]!!.jsonPrimitive.contentOrNull)
    }
}
