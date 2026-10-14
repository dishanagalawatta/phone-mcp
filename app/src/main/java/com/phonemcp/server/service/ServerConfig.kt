package com.phonemcp.server.service

/** Static server defaults. Dashboard may override port at runtime (v1: fixed). */
data class ServerConfig(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val protocolVersion: String = "2025-06-18",
)
