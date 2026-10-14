package com.phonemcp.server.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetPolicyArgsTest {

    @Test
    fun policyDefaults() {
        val policy = NetInspectProvider.parsePolicy(buildJsonObject { })
        assertTrue(policy.defaultAllow)
        assertTrue(policy.blockedHosts.isEmpty())
        assertTrue(policy.blockedPorts.isEmpty())
    }

    @Test
    fun policyParsesLists() {
        val policy = NetInspectProvider.parsePolicy(
            buildJsonObject {
                put("defaultAllow", JsonPrimitive(false))
                put("blockedHosts", JsonArray(listOf(JsonPrimitive("evil.com"))))
                put("blockedIpRanges", JsonArray(listOf(JsonPrimitive("10.0.0.0/8"))))
                put("blockedPorts", JsonArray(listOf(JsonPrimitive(25))))
                put("blockedPackages", JsonArray(listOf(JsonPrimitive("com.bad"))))
                put("allowedHosts", JsonArray(listOf(JsonPrimitive("good.com"))))
            },
        )
        assertFalse(policy.defaultAllow)
        assertEquals(listOf("evil.com"), policy.blockedHosts)
        assertEquals(listOf(25), policy.blockedPorts)
        assertEquals(listOf("com.bad"), policy.blockedPackages)
    }

    @Test
    fun policyRejectsBadPort() {
        try {
            NetInspectProvider.parsePolicy(
                buildJsonObject {
                    put("blockedPorts", JsonArray(listOf(JsonPrimitive(99999))))
                },
            )
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun streamArgsClamp() {
        val d = NetInspectProvider.parseStreamArgs(buildJsonObject { })
        assertEquals(1000L, d.intervalMs)
        assertEquals(10_000L, d.durationMs)
        val clamped = NetInspectProvider.parseStreamArgs(
            buildJsonObject {
                put("intervalMs", JsonPrimitive(1))
                put("durationMs", JsonPrimitive(999_999))
            },
        )
        assertEquals(500L, clamped.intervalMs)
        assertEquals(60_000L, clamped.durationMs)
    }

    @Test
    fun policyJsonRoundTrip() {
        val policy = NetInspectProvider.parsePolicy(buildJsonObject { })
        val json = NetInspectProvider.policyJson(policy)
        assertEquals(true, json["defaultAllow"].toString().toBoolean())
    }
}
