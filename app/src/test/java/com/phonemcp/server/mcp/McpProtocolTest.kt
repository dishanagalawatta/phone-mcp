package com.phonemcp.server.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    @Test
    fun singleSourceOfTruth() {
        assertEquals("phone-mcp", McpProtocol.SERVER_NAME)
        assertEquals("0.2.0", McpProtocol.SERVER_VERSION)
        assertEquals(listOf("2025-03-26", "2025-06-18"), McpProtocol.SUPPORTED_VERSIONS)
        assertEquals("2025-06-18", McpProtocol.LATEST_VERSION)
        // Legacy aliases stay in sync for callers/tests.
        assertEquals(McpProtocol.SUPPORTED_VERSIONS, McpRouter.SUPPORTED_VERSIONS)
        assertEquals(McpProtocol.LATEST_VERSION, McpRouter.LATEST_VERSION)
    }

    @Test
    fun routerServerInfoUsesProtocol() {
        val router = McpRouter(emptySet(), com.phonemcp.server.domain.Gatekeeper())
        val info = router.serverInfo()
        assertTrue(info.toString().contains("phone-mcp"))
    }
}
