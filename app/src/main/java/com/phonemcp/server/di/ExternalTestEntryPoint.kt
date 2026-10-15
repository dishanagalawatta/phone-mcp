package com.phonemcp.server.di

import com.phonemcp.server.data.ExternalCapabilityDiscovery
import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.mcp.ExternalCapabilityRegistry
import com.phonemcp.server.mcp.McpRouter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Exposes the dynamic-capability stack to on-device E2E tests driving the
 * full IPC loop (rescan → approve → proxied tools/call) from the test
 * process. Follows the NfcEntryPoint/GatekeeperEntryPoint precedent.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExternalTestEntryPoint {
    fun gatekeeper(): Gatekeeper
    fun registry(): ExternalCapabilityRegistry
    fun discovery(): ExternalCapabilityDiscovery
    fun router(): McpRouter
}
