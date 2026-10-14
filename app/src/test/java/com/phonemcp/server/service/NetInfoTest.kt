package com.phonemcp.server.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetInfoTest {

    @Test
    fun onlyIpv4NonLoopback() {
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        for (ip in NetInfo.lanIpv4()) {
            assertTrue(ipv4.matches(ip))
            assertFalse(ip == "127.0.0.1")
        }
    }
}
