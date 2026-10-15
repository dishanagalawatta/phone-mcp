package com.phonemcp.server.data

import android.content.Context
import com.phonemcp.server.domain.LogBuffer
import com.phonemcp.server.mcp.ExternalCapabilityRegistry
import com.phonemcp.server.mcp.exportSnapshots
import com.phonemcp.server.mcp.importSnapshots
import com.phonemcp.server.mcp.snapshotsFromJsonString
import com.phonemcp.server.mcp.toJsonString
import com.phonemcp.server.service.CryptoPrefs
import com.phonemcp.server.service.SecretsLock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Restart persistence for third-party capability records (registry +
 * approval + signer pins), backed by EncryptedSharedPreferences like the
 * bearer token. Restored records get a fresh heartbeat so providers have
 * the normal TTL window to re-announce; approval survives reboot.
 * Failures are swallowed (best-effort) — never crash app start for this.
 */
@Singleton
class ExternalCapabilityStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ExternalCapabilityRegistry,
    private val log: LogBuffer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Restores persisted records once (called from Application.onCreate). */
    suspend fun restore() {
        try {
            val raw = withContext(Dispatchers.IO) {
                SecretsLock.withLock(context.filesDir) { prefs().getString(KEY, "") ?: "" }
            }
            val snapshots = snapshotsFromJsonString(raw)
            if (snapshots.isEmpty()) return
            val accepted = withContext(Dispatchers.Default) {
                registry.importSnapshots(snapshots)
            }
            log.append("capabilities restored $accepted/${snapshots.size}")
        } catch (e: Exception) {
            log.append("capabilities restore failed (${e.javaClass.simpleName})")
        }
    }

    /** Persists on every registry list change for the life of the process. */
    fun watch() {
        scope.launch {
            try {
                registry.version.drop(1).collect { save() }
            } catch (_: Exception) {
                // Scope cancelled at process death; nothing to persist after that.
            }
        }
    }

    private suspend fun save() {
        try {
            val raw = withContext(Dispatchers.Default) {
                registry.exportSnapshots().toJsonString()
            }
            withContext(Dispatchers.IO) {
                SecretsLock.withLock(context.filesDir) {
                    prefs().edit().putString(KEY, raw).apply()
                }
            }
        } catch (_: Exception) {
            // Best-effort: a failed write just means an older snapshot survives.
        }
    }

    private fun prefs() = CryptoPrefs.auth(PREFS, context)

    companion object {
        const val PREFS = "mcp_capabilities"
        const val KEY = "snapshots_v1"
    }
}
