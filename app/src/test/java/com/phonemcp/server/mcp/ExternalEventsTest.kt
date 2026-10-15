package com.phonemcp.server.mcp

import com.phonemcp.server.domain.ExternalToolInvoker
import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.mcp.CapabilityIpcContractTest.Companion.descriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server-initiated `notifications/tools/list_changed` shape and delivery
 * (surfaced over `GET /mcp/events` SSE by the service layer).
 */
class ExternalEventsTest {

    @Test
    fun notificationMatchesSpecShape() {
        val router = McpRouter(emptySet(), Gatekeeper())
        val notification = router.externalChangeNotification()
        assertEquals("2.0", notification["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(
            "notifications/tools/list_changed",
            notification["method"]!!.jsonPrimitive.content,
        )
        // Spec shape is method-only: no params to keep strict clients happy.
        assertEquals(2, notification.size)
    }

    @Test
    fun changesEmitOnRegisterAndUnregister() = runBlocking {
        val registry = ExternalCapabilityRegistry(ExternalToolInvoker.UNREACHABLE)
        val router = McpRouter(emptySet(), Gatekeeper(), registry)
        val collected = async { router.externalChanges().take(2).toList() }
        // Wait for the collector to attach (StateFlow replays + drop(1) would
        // otherwise eat the first bump), and separate mutations so conflation
        // cannot coalesce them.
        withTimeoutOrNull(5_000) { registry.versionSubscribers.first { it > 0 } }
        registry.register("com.example.a", descriptor("notes", "ping"))
        delay(50)
        registry.unregister("notes", forceLocal = true)
        val events = withTimeoutOrNull(5_000) { collected.await() } ?: emptyList()
        collected.cancel()
        assertEquals(2, events.size)
        assertTrue(
            events.all {
                it["method"]!!.jsonPrimitive.content == "notifications/tools/list_changed"
            },
        )
    }

    @Test
    fun heartbeatOnlyMutationsDoNotEmit() = runBlocking {
        val registry = ExternalCapabilityRegistry(ExternalToolInvoker.UNREACHABLE)
        val router = McpRouter(emptySet(), Gatekeeper(), registry)
        registry.register("com.example.a", descriptor("notes", "ping"))
        val collected = async { router.externalChanges().take(1).toList() }
        withTimeoutOrNull(5_000) { registry.versionSubscribers.first { it > 0 } }
        assertTrue(registry.heartbeat("notes", requesterPackage = "com.example.a"))
        val events = withTimeoutOrNull(500) { collected.await() } ?: emptyList()
        // The collector itself never completes (infinite version flow):
        // cancel it explicitly or runBlocking would wait forever.
        collected.cancel()
        assertTrue(events.isEmpty())
    }
}
