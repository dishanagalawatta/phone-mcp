package com.phonemcp.server.di

import com.phonemcp.server.domain.Gatekeeper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Exposes the kill-switch singleton to components that cannot use constructor
 * injection (e.g. on-device E2E tests driving the service from the test process).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GatekeeperEntryPoint {
    fun gatekeeper(): Gatekeeper
}
