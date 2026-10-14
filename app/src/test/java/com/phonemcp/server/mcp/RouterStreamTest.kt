package com.phonemcp.server.mcp

import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.domain.ToolProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RouterStreamTest {

    private fun streamingProvider(vararg batches: JsonObject): ToolProvider =
        object : ToolProvider {
            override val id = "stub-stream"
            override fun listTools() = listOf(
                ToolDefinition("sensors_stream", "stub", buildJsonObject { put("type", "object") }),
            )
            override suspend fun call(name: String, args: JsonObject) = ToolCallResult.text("stub")
            override fun supportsStreaming(name: String) = name == "sensors_stream"
            override fun streamNotificationMethod(name: String) = "notifications/sensors"
            override fun openStream(name: String, args: JsonObject): Flow<JsonObject> =
                flow { batches.forEach { emit(it) } }
        }

    private fun streamRequest(): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 9)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", "sensors_stream")
                    put("arguments", buildJsonObject { })
                },
            )
        }

    private fun batch(i: Int): JsonObject = buildJsonObject { put("t", i) }

    private fun routerWith(vararg providers: ToolProvider): McpRouter =
        McpRouter(providers.toSet(), Gatekeeper())

    @Test
    fun emitsNotificationsThenFinalResult(): Unit = runBlocking {
        val gatekeeper = Gatekeeper()
        gatekeeper.setEnabled(Gatekeeper.TOOLS_LOCATION, true)
        val routed = McpRouter(setOf(streamingProvider(batch(1), batch(2), batch(3))), gatekeeper)
        val emitted = mutableListOf<JsonObject>()
        val final = routed.handleStream(streamRequest()) { emitted.add(it) }
        assertEquals(3, emitted.size)
        assertTrue(emitted.all { it["method"]!!.jsonPrimitive.content == "notifications/sensors" })
        assertEquals(3, final["result"]!!.jsonObject["batches"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun locationGateBlocksByDefault(): Unit = runBlocking {
        val router = routerWith(streamingProvider(batch(1)))
        var emitted = 0
        val final = router.handleStream(streamRequest()) { emitted++ }
        assertEquals(0, emitted)
        assertEquals(
            JsonRpcCodes.INVALID_PARAMS,
            final["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun includeLocationFalseBypassesGate(): Unit = runBlocking {
        val router = routerWith(streamingProvider(batch(1)))
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 9)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", "sensors_stream")
                    put("arguments", buildJsonObject { put("includeLocation", false) })
                },
            )
        }
        val emitted = mutableListOf<JsonObject>()
        val final = router.handleStream(request) { emitted.add(it) }
        assertEquals(1, emitted.size)
        assertEquals(1, final["result"]!!.jsonObject["batches"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun secondStreamGetsBusyError(): Unit = runBlocking {
        val gatekeeper = Gatekeeper()
        gatekeeper.setEnabled(Gatekeeper.TOOLS_LOCATION, true)
        val slow = object : ToolProvider {
            override val id = "slow"
            override fun listTools() = listOf(
                ToolDefinition("sensors_stream", "stub", buildJsonObject { put("type", "object") }),
            )
            override suspend fun call(name: String, args: JsonObject) = ToolCallResult.text("stub")
            override fun supportsStreaming(name: String) = true
            override fun openStream(name: String, args: JsonObject): Flow<JsonObject> =
                flow {
                    delay(5000)
                    emit(batch(1))
                }
        }
        val router = McpRouter(setOf(slow), gatekeeper)
        val first = async { router.handleStream(streamRequest()) { } }
        delay(100)
        var secondEmitted = 0
        val second = router.handleStream(streamRequest()) { secondEmitted++ }
        assertEquals(0, secondEmitted)
        assertEquals(
            JsonRpcCodes.INTERNAL_ERROR,
            second["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt(),
        )
        first.cancelAndJoin()
    }

    @Test
    fun unstreamableToolRejected(): Unit = runBlocking {
        val router = routerWith(streamingProvider(batch(1)))
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 9)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", "vibrate")
                    put("arguments", buildJsonObject { })
                },
            )
        }
        val final = router.handleStream(request) { fail("must not emit") }
        assertEquals(
            JsonRpcCodes.INVALID_PARAMS,
            final["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt(),
        )
    }
}
