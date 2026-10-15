package com.phonemcp.server.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransportTest {
    @Test
    fun portOf_readsEndpointPort() {
        assertEquals(8080, UsbTransport.portOf("https://192.168.1.7:8080/mcp"))
        assertEquals(9090, UsbTransport.portOf("https://192.168.1.7:9090/mcp"))
    }

    @Test
    fun portOf_defaultsOnMissingOrGarbage() {
        assertEquals(8080, UsbTransport.portOf(null))
        assertEquals(8080, UsbTransport.portOf(""))
        assertEquals(8080, UsbTransport.portOf("not-a-url"))
        assertEquals(8080, UsbTransport.portOf("https://phone.local/mcp"))
    }

    @Test
    fun loopbackUrl_rewritesHostKeepsPort() {
        assertEquals(
            "https://127.0.0.1:8080/mcp",
            UsbTransport.loopbackUrl("https://192.168.1.7:8080/mcp"),
        )
        assertEquals(
            "https://127.0.0.1:9090/mcp",
            UsbTransport.loopbackUrl("https://192.168.1.7:9090/mcp"),
        )
    }

    @Test
    fun forwardCommand_isForwardNeverReverse() {
        val cmd = UsbTransport.forwardCommand("https://192.168.1.7:8080/mcp")
        assertEquals("adb forward tcp:8080 tcp:8080", cmd)
        assertTrue(!cmd.contains("reverse"))
    }
}
