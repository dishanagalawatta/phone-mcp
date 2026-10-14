package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakArgsTest {

    @Test
    fun parsesTextWithDefaults() {
        val request = OutputProvider.parseSpeakArgs(buildJsonObject { put("text", JsonPrimitive(" hello ")) })
        assertEquals("hello", request.text)
        assertEquals(1.0f, request.rate)
        assertEquals(1.0f, request.pitch)
    }

    @Test
    fun clampsRateAndPitch() {
        val request = OutputProvider.parseSpeakArgs(
            buildJsonObject {
                put("text", JsonPrimitive("hi"))
                put("rate", JsonPrimitive(9.0))
                put("pitch", JsonPrimitive(0.1))
            },
        )
        assertEquals(2.0f, request.rate)
        assertEquals(0.5f, request.pitch)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankTextThrows() {
        OutputProvider.parseSpeakArgs(buildJsonObject { put("text", JsonPrimitive("  ")) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingTextThrows() {
        OutputProvider.parseSpeakArgs(buildJsonObject { })
    }

    @Test
    fun routeLabels() {
        assertEquals(
            "remote_submix(mirrored)",
            OutputProvider.describeOutput(setOf(android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX), false, false),
        )
        assertEquals("bt_a2dp", OutputProvider.describeOutput(emptySet(), true, false))
        assertEquals(
            "bt_a2dp",
            OutputProvider.describeOutput(setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), false, false),
        )
        assertEquals("wired", OutputProvider.describeOutput(emptySet(), false, true))
        assertEquals("speaker", OutputProvider.describeOutput(emptySet(), false, false))
    }
}
