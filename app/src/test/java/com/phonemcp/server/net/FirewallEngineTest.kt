package com.phonemcp.server.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallEngineTest {

    @Test
    fun hostSuffixMatching() {
        assertTrue(FirewallEngine.hostMatches("a.example.com", "example.com"))
        assertTrue(FirewallEngine.hostMatches("example.com", "example.com"))
        assertTrue(FirewallEngine.hostMatches("a.example.com", "*.example.com"))
        assertFalse(FirewallEngine.hostMatches("notexample.com", "example.com"))
        assertFalse(FirewallEngine.hostMatches("example.com.evil.com", "example.com"))
    }

    @Test
    fun cidrMatching() {
        assertTrue(FirewallEngine.cidrMatches("192.168.1.50", "192.168.1.0/24"))
        assertFalse(FirewallEngine.cidrMatches("192.168.2.50", "192.168.1.0/24"))
        assertTrue(FirewallEngine.cidrMatches("10.5.6.7", "10.0.0.0/8"))
        assertTrue(FirewallEngine.cidrMatches("8.8.8.8", "8.8.8.8"))
        assertFalse(FirewallEngine.cidrMatches("8.8.8.8", "9.9.9.9"))
        assertFalse(FirewallEngine.cidrMatches("not-an-ip", "10.0.0.0/8"))
        assertFalse(FirewallEngine.cidrMatches("10.0.0.1", "bogus"))
    }

    @Test
    fun cidrMatchingV6() {
        assertTrue(FirewallEngine.cidrMatches("fd00::1234", "fd00::/8"))
        assertFalse(FirewallEngine.cidrMatches("2001:db8::1", "fd00::/8"))
        assertTrue(FirewallEngine.cidrMatches("::1", "::1/128"))
        assertFalse(FirewallEngine.cidrMatches("::2", "::1/128"))
        assertTrue(FirewallEngine.cidrMatches("2001:db8::1", "2001:db8::/32"))
        assertTrue(FirewallEngine.cidrMatches("2001:0db8:0000:0000:0000:0000:0000:0001", "2001:db8::/32"))
        // Exact-match normalizes compressed text.
        assertTrue(FirewallEngine.cidrMatches("2001:db8::1", "2001:0db8:0:0:0:0:0:1"))
        assertFalse(FirewallEngine.cidrMatches("2001:db8::2", "2001:db8::1"))
        assertFalse(FirewallEngine.cidrMatches("bogus:::", "fd00::/8"))
        assertFalse(FirewallEngine.cidrMatches("fd00::1", "bogus"))
        // Family mismatch never matches.
        assertFalse(FirewallEngine.cidrMatches("10.0.0.1", "fd00::/8"))
        assertFalse(FirewallEngine.cidrMatches("fd00::1", "10.0.0.0/8"))
    }

    @Test
    fun defaultAllowPassesClean() {
        val (verdict, _) = FirewallEngine.decide(FirewallPolicy(), "example.com", "93.184.216.34", 443, "com.app")
        assertEquals(Verdict.ALLOW, verdict)
    }

    @Test
    fun blocksByHostPortPackage() {
        val byHost = FirewallEngine.decide(
            FirewallPolicy(blockedHosts = listOf("evil.com")), "a.evil.com", "1.2.3.4", 443, null,
        )
        assertEquals(Verdict.BLOCK_POLICY, byHost.first)
        val byPort = FirewallEngine.decide(FirewallPolicy(blockedPorts = listOf(25)), null, "1.2.3.4", 25, null)
        assertEquals(Verdict.BLOCK_POLICY, byPort.first)
        val byPkg = FirewallEngine.decide(
            FirewallPolicy(blockedPackages = listOf("com.bad")), null, "1.2.3.4", 443, "com.bad",
        )
        assertEquals(Verdict.BLOCK_POLICY, byPkg.first)
        val byIp = FirewallEngine.decide(
            FirewallPolicy(blockedIpRanges = listOf("10.9.0.0/16")), null, "10.9.1.2", 443, null,
        )
        assertEquals(Verdict.BLOCK_POLICY, byIp.first)
    }

    @Test
    fun allowListBypassesBlock() {
        val policy = FirewallPolicy(
            blockedHosts = listOf("example.com"),
            allowedHosts = listOf("good.example.com"),
        )
        val (v, _) = FirewallEngine.decide(policy, "good.example.com", "1.2.3.4", 443, null)
        assertEquals(Verdict.ALLOW, v)
    }

    @Test
    fun defaultDenyBlocksUnknown() {
        val (v, _) = FirewallEngine.decide(FirewallPolicy(defaultAllow = false), "example.com", "1.2.3.4", 443, null)
        assertEquals(Verdict.BLOCK_POLICY, v)
    }

    @Test
    fun blocklistMatchesSuffix() {
        assertTrue(Blocklist.matches("sub.malware.test"))
        assertTrue(Blocklist.matches("malware.test"))
        assertFalse(Blocklist.matches("example.com"))
        assertFalse(Blocklist.matches(""))
        val check = Blocklist.check("sub.malware.test")
        assertEquals(true, check["listed"])
    }

    @Test
    fun procNetParsing() {
        val lines = listOf(
            "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode",
            "0: 0100000A:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10058 0 1",
            "1: 0100000A:0050 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10058 0 2",
        )
        val map = FirewallEngine.parseProcNetLines(lines)
        assertEquals(10058, map[8080])
        assertEquals(10058, map[80])
        assertFalse(map.containsKey(443))
    }

    @Test
    fun geoClassifiesPrivate() {
        assertEquals("private", GeoLookup.lookup("10.0.0.2")["scope"])
        assertEquals("private", GeoLookup.lookup("192.168.1.1")["scope"])
        assertEquals("private", GeoLookup.lookup("127.0.0.1")["scope"])
        assertEquals("private", GeoLookup.lookup("::1")["scope"])
        assertEquals("private", GeoLookup.lookup("fd00::2")["scope"])
        assertEquals("private", GeoLookup.lookup("fc00::1")["scope"])
        assertEquals("private", GeoLookup.lookup("fe80::1")["scope"])
        assertEquals("private", GeoLookup.lookup("febf::1")["scope"])
        assertEquals("private", GeoLookup.lookup("ff02::1")["scope"])
        assertEquals("public", GeoLookup.lookup("2001:4860:4860::8888")["scope"])
        val pub = GeoLookup.lookup("8.8.8.8")
        assertEquals("public", pub["scope"])
        assertTrue((pub["country"] as String).contains("offline DB"))
    }
}
