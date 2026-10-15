package com.phonemcp.server.di

import com.phonemcp.server.domain.LogBuffer
import com.phonemcp.server.mcp.ExternalCapabilityRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Exposes the capability registry to [com.phonemcp.server.service.CapabilityDirectoryProvider].
 * A ContentProvider cannot use `@AndroidEntryPoint` (unsupported + it starts
 * before `Application.onCreate`), so the provider resolves this lazily on
 * each call — by then the singleton component exists.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CapabilityDirectoryEntryPoint {
    fun registry(): ExternalCapabilityRegistry
    fun log(): LogBuffer
}
