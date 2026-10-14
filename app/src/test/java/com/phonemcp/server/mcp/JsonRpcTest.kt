package com.phonemcp.server.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonRpcTest {

    @Test
    fun errorEnvelopeShape() {
        val id = buildJsonObject { put("id", 7) }["id"]!!
        val error = jsonRpcError(id, JsonRpcCodes.INVALID_PARAMS, "Missing 'name'")
        assertEquals("2.0", error["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(7, error["id"]!!.jsonPrimitive.content.toInt())
        val body = error["error"]!!.jsonObject
        assertEquals(JsonRpcCodes.INVALID_PARAMS, body["code"]!!.jsonPrimitive.content.toInt())
        assertEquals("Missing 'name'", body["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun nullIdFallsBackToJsonNull() {
        val error = jsonRpcError(null, JsonRpcCodes.INTERNAL_ERROR, "boom")
        assertEquals(JsonNull, error["id"])
    }

    @Test
    fun errorCodesAreStandard() {
        assertEquals(-32700, JsonRpcCodes.PARSE_ERROR)
        assertEquals(-32600, JsonRpcCodes.INVALID_REQUEST)
        assertEquals(-32601, JsonRpcCodes.METHOD_NOT_FOUND)
        assertEquals(-32602, JsonRpcCodes.INVALID_PARAMS)
        assertEquals(-32603, JsonRpcCodes.INTERNAL_ERROR)
        assertEquals(-32001, JsonRpcCodes.UNAUTHORIZED)
    }

    @Test
    fun toolErrorMarksIsError() {
        val result = ToolCallResult.error("nope", JsonRpcCodes.METHOD_NOT_FOUND)
        assertEquals(true, result.isError)
        assertEquals(JsonRpcCodes.METHOD_NOT_FOUND, result.rpcCode)
        assertEquals("nope", result.content[0]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun textResultShape() {
        val result = ToolCallResult.text("hi")
        assertEquals(false, result.isError)
        assertEquals("text", result.content[0]["type"]!!.jsonPrimitive.content)
        assertEquals(null, result.rpcCode)
    }
}
