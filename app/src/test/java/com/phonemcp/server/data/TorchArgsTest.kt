package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class TorchArgsTest {

    @Test
    fun parsesOnOff() {
        assertEquals(true, OutputProvider.parseTorchArgs(buildJsonObject { put("on", JsonPrimitive(true)) }))
        assertEquals(false, OutputProvider.parseTorchArgs(buildJsonObject { put("on", JsonPrimitive(false)) }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingOnThrows() {
        OutputProvider.parseTorchArgs(buildJsonObject { })
    }
}
