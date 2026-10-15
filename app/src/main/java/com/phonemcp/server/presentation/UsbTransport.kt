package com.phonemcp.server.presentation

/**
 * USB-cable transport derived values (display-only, no I/O).
 *
 * Canonical path is host-side `adb forward tcp:PORT tcp:PORT` plus the
 * loopback URL `https://127.0.0.1:PORT/mcp` (ADR-0036). `adb reverse` is
 * never correct here: the phone already binds :PORT, so a reverse listener
 * collides with Ktor (`cannot bind listener: Address already in use`).
 * Same bearer token + SHA-256 pin as LAN (pin check skips the hostname).
 *
 * Pure Kotlin (no `android.*`); JVM-testable.
 */
object UsbTransport {
    const val DEFAULT_PORT = 8080
    const val LOOPBACK_HOST = "127.0.0.1"

    /** Port carried by an `https://host:port/mcp` endpoint URL, else [DEFAULT_PORT]. */
    fun portOf(endpointUrl: String?): Int {
        val raw = endpointUrl.orEmpty()
        val host = raw.substringAfter("://", "")
        val authority = host.substringBefore('/', host)
        val portPart = authority.substringAfterLast(':', "")
        // IPv6 literals (`[::1]:8080`) keep the port after the last colon.
        return portPart.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
    }

    /** Loopback twin of an endpoint URL: `https://127.0.0.1:PORT/mcp`. */
    fun loopbackUrl(endpointUrl: String?): String =
        "https://$LOOPBACK_HOST:${portOf(endpointUrl)}/mcp"

    /** Host-side forward command for the endpoint's port (never `reverse`). */
    fun forwardCommand(endpointUrl: String?): String {
        val port = portOf(endpointUrl)
        return "adb forward tcp:$port tcp:$port"
    }
}
