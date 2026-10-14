package com.phonemcp.server.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline IP classifiers + policy matchers. Pure Kotlin, no android.* imports.
 *
 * Geo stance: no bundled MaxMind DB (license weight + APK size). [GeoLookup]
 * classifies private/link-local ranges locally and marks public IPs as
 * `unknown (offline DB not bundled)` with the ASN left unresolved, so agents
 * get honest telemetry without an external round-trip. A future `.mmdb`
 * asset can implement [GeoResolver] and be injected without changing tools.
 */
object GeoLookup {
    fun isPrivateIpv4(ip: String): Boolean {
        val b = NetParsers.ipv4ToBytes(ip) ?: return false
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        return when {
            a == 10 -> true
            a == 172 && c in 16..31 -> true
            a == 192 && c == 168 -> true
            a == 127 -> true
            a == 169 && c == 254 -> true
            a == 0 -> true
            a >= 224 -> true // multicast/reserved
            else -> false
        }
    }

    fun isPrivateIpv6(ip: String): Boolean {
        val b = NetParsers.ipv6ToBytes(ip) ?: return false
        val b0 = b[0].toInt() and 0xFF
        val b1 = b[1].toInt() and 0xFF
        return when {
            b0 == 0xFC || b0 == 0xFD -> true // fc00::/7 unique-local
            b0 == 0xFE && (b1 and 0xC0) == 0x80 -> true // fe80::/10 link-local
            b0 == 0xFF -> true // ff00::/8 multicast
            else -> b.sliceArray(0 until 15).all { it == 0.toByte() } && b[15] == 1.toByte() // ::1
        }
    }

    fun lookup(ip: String): Map<String, String> {
        val private = when {
            ip.contains(":") -> isPrivateIpv6(ip)
            else -> isPrivateIpv4(ip)
        }
        return if (private) {
            mapOf("ip" to ip, "scope" to "private", "country" to "ZZ", "asn" to "private")
        } else {
            mapOf(
                "ip" to ip,
                "scope" to "public",
                // Honest stub: no bundled DB, no network fetch (LAN-only product).
                "country" to "unknown (offline DB not bundled)",
                "asn" to "unknown (offline DB not bundled)",
            )
        }
    }
}

/** Suffix-aware blocklist matcher (`example.com` also matches `a.example.com`). */
object Blocklist {
    /** Built-in sample of well-known malicious-test domains (RFC 2606 + Malware-traffic-analysis samples). */
    val DEFAULT_ENTRIES: Set<String> = setOf(
        "malware.test",
        "phishing.test",
        "evil.example.com",
        "badsite.invalid",
        "command-and-control.test",
    )

    fun matches(host: String, entries: Set<String> = DEFAULT_ENTRIES): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        for (entry in entries) {
            val e = entry.trim().lowercase().trimEnd('.')
            if (e.isEmpty()) continue
            if (h == e || h.endsWith(".$e")) return true
        }
        return false
    }

    fun check(host: String, custom: Set<String> = emptySet()): Map<String, Any> {
        val inDefault = matches(host, DEFAULT_ENTRIES)
        val inCustom = matches(host, custom)
        return mapOf("host" to host, "listed" to (inDefault || inCustom), "defaultList" to inDefault, "customList" to inCustom)
    }
}

object FirewallEngine {
    fun decide(
        policy: FirewallPolicy,
        host: String?,
        ip: String,
        port: Int,
        packageName: String?,
    ): Pair<Verdict, String?> {
        if (!host.isNullOrEmpty() && policy.allowedHosts.any { hostMatches(host, it) }) {
            return Verdict.ALLOW to null
        }
        if (!packageName.isNullOrEmpty() && policy.blockedPackages.contains(packageName)) {
            return Verdict.BLOCK_POLICY to "package $packageName blocked"
        }
        if (policy.blockedPorts.contains(port)) {
            return Verdict.BLOCK_POLICY to "port $port blocked"
        }
        if (!host.isNullOrEmpty() && policy.blockedHosts.any { hostMatches(host, it) }) {
            return Verdict.BLOCK_POLICY to "host $host blocked"
        }
        if (policy.blockedIpRanges.any { cidrMatches(ip, it) }) {
            return Verdict.BLOCK_POLICY to "ip $ip blocked"
        }
        if (!host.isNullOrEmpty() && Blocklist.matches(host)) {
            return Verdict.BLOCK_BLOCKLIST to "host $host on threat list"
        }
        return if (policy.defaultAllow) Verdict.ALLOW to null else Verdict.BLOCK_POLICY to "default deny"
    }

    fun hostMatches(host: String, pattern: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        var p = pattern.lowercase().trimEnd('.')
        if (p.startsWith("*.")) p = p.removePrefix("*.")
        return h == p || h.endsWith(".$p")
    }

    /** IPv4/IPv6 CIDR/exact matcher. Returns false on parse failure (never blocks). */
    fun cidrMatches(ip: String, range: String): Boolean {
        try {
            val slash = range.indexOf('/')
            if (slash < 0) {
                // Exact match, family-agnostic (normalize v6 text before comparing).
                val want = range.trim()
                if (ip.contains(':') || want.contains(':')) {
                    val a = NetParsers.ipv6ToBytes(ip) ?: return false
                    val b = NetParsers.ipv6ToBytes(want) ?: return false
                    return a.contentEquals(b)
                }
                return want == ip
            }
            val base = range.substring(0, slash).trim()
            val bits = range.substring(slash + 1).trim().toIntOrNull() ?: return false
            if (ip.contains(':') || base.contains(':')) {
                if (bits !in 0..128) return false
                val addr = NetParsers.ipv6ToBytes(ip) ?: return false
                val net = NetParsers.ipv6ToBytes(base) ?: return false
                var remaining = bits
                for (i in 0 until 16) {
                    val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
                    if (((addr[i].toInt() and 0xFF) and mask) != ((net[i].toInt() and 0xFF) and mask)) return false
                    remaining -= 8
                    if (remaining <= 0) break
                }
                return true
            }
            val addr = NetParsers.ipv4ToBytes(ip) ?: return false
            val net = NetParsers.ipv4ToBytes(base) ?: return false
            if (bits !in 0..32) return false
            var remaining = bits
            for (i in 0..3) {
                val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
                if (((addr[i].toInt() and 0xFF) and mask) != ((net[i].toInt() and 0xFF) and mask)) return false
                remaining -= 8
                if (remaining <= 0) break
            }
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /** Pure /proc/net/{tcp,udp} line parser: maps local port -> uid. JVM-testable. */
    fun parseProcNetLines(lines: List<String>): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 8) continue
            if (parts[0] == "sl") continue
            val local = parts[1]
            val uid = parts[7].toIntOrNull() ?: continue
            val portHex = local.substringAfter(':', missingDelimiterValue = "")
            val port = portHex.toIntOrNull(16) ?: continue
            if (port in 1..65535) out.putIfAbsent(port, uid)
        }
        return out
    }
}
