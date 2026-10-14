package com.phonemcp.server.service

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** LAN address discovery (no Android imports, JVM-testable). */
object NetInfo {
    /** Non-loopback IPv4 addresses, site-local first. Empty when offline. */
    fun lanIpv4(): List<String> {
        val found = mutableListOf<String>()
        try {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces() ?: return emptyList())
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in Collections.list(iface.inetAddresses)) {
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        address.hostAddress?.let { found.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return found.distinct().sortedBy { !isSiteLocal(it) }
    }

    private fun isSiteLocal(ip: String): Boolean =
        ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
}
