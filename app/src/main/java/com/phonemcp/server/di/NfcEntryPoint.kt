package com.phonemcp.server.di

import com.phonemcp.server.data.NfcReader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Exposes the stateful NFC tap intake to components that cannot use constructor
 * injection (e.g. on-device tests that must share the exact instance the dashboard arms).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NfcEntryPoint {
    fun reader(): NfcReader
}
