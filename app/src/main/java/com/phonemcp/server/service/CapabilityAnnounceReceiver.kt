package com.phonemcp.server.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phonemcp.server.data.ExternalCapabilityDiscovery
import com.phonemcp.server.domain.LogBuffer
import com.phonemcp.server.mcp.CapabilityIpcContract
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles provider rescan hints ([CapabilityIpcContract.ACTION_ANNOUNCE]).
 *
 * The broadcast extras are UNTRUSTED: only [CapabilityIpcContract.EXTRA_PACKAGE]
 * is read, and the authoritative descriptor is re-fetched from that package's
 * own bound service — so a malicious app can at most trigger a rescan that
 * re-reads the victim's genuine descriptor. No manifest permission is required
 * for this reason; the receiver never mutates the registry directly.
 */
@AndroidEntryPoint
class CapabilityAnnounceReceiver : BroadcastReceiver() {

    @Inject
    lateinit var discovery: ExternalCapabilityDiscovery

    @Inject
    lateinit var log: LogBuffer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != CapabilityIpcContract.ACTION_ANNOUNCE) return
        val packageName = intent.getStringExtra(CapabilityIpcContract.EXTRA_PACKAGE).orEmpty()
        if (packageName.isEmpty()) return
        val pending = goAsync()
        scope.launch {
            try {
                val registered = discovery.rescan(packageName)
                log.append("capability announce $packageName → $registered registered")
            } catch (e: Exception) {
                // Rescan hints are best-effort; the failure mode is a stale
                // listing, recoverable via capabilities_rescan.
                log.append("capability announce $packageName failed (${e.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }
}
