package com.phonemcp.server.service

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bearer-token lifecycle backed by EncryptedSharedPreferences.
 * Created lazily on first pairing view / first authenticated check; rotation kills the old token immediately.
 * The token value is NEVER written to LogBuffer (see McpServerService.describe).
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // In-memory copy: EncryptedSharedPreferences.apply() persists asynchronously, so a
    // fresh read can briefly see stale disk state. Companion-wide so every instance in this
    // process (service, UI, tests) observes the same value; single writer (rotate) keeps it exact.
    companion object {
        const val PREFS = "mcp_auth"
        const val KEY = "bearer_token"

        @Volatile
        private var cached: String? = null
    }

    private fun prefs(): SharedPreferences =
        CryptoPrefs.auth(PREFS, context)

    fun current(): String? = cached ?: SecretsLock.withLock(context.filesDir) { currentLocked() }

    /** Returns the existing token, generating and persisting one on first call. */
    fun ensure(): String = SecretsLock.withLock(context.filesDir) {
        cached ?: (currentLocked() ?: AuthToken.generate().also {
            prefs().edit().putString(KEY, it).apply()
            cached = it
        })
    }

    /** Generates a replacement token; the previous one stops working immediately. */
    fun rotate(): String = SecretsLock.withLock(context.filesDir) {
        AuthToken.generate().also {
            prefs().edit().putString(KEY, it).apply()
            cached = it
        }
    }

    private fun currentLocked(): String? = cached ?: prefs().getString(KEY, null)?.also { cached = it }
}
