package com.phonemcp.server.mcp

/**
 * Single source of truth for MCP identity and protocol versions.
 * Replaces triple-duplicated literals in router/service/info paths.
 */
object McpProtocol {
    const val SERVER_NAME = "phone-mcp"
    const val SERVER_VERSION = "0.2.0"
    val SUPPORTED_VERSIONS = listOf("2025-03-26", "2025-06-18")
    const val LATEST_VERSION = "2025-06-18"
}
