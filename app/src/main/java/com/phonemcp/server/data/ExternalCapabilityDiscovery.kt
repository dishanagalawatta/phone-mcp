package com.phonemcp.server.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.phonemcp.server.mcp.CapabilityIpcContract
import com.phonemcp.server.mcp.ExternalCapabilityRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers provider apps via PackageManager and pulls their authoritative
 * descriptors over the bound-service channel.
 *
 * Discovery is intent-based (no `QUERY_ALL_PACKAGES`): the manifest
 * `<queries>` block advertises [CapabilityIpcContract.ACTION_PROVIDE] so
 * [PackageManager.queryIntentServices] sees provider services on API 30+.
 * Descriptors always come from the claimed package itself, so a malicious
 * app cannot deface another app's record — at worst it triggers a rescan
 * that re-reads the victim's own descriptor.
 */
@Singleton
class ExternalCapabilityDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: ExternalCapabilityRegistry,
    private val invoker: ServiceToolInvoker,
) {
    /** Packages currently exposing the provider service (visible per `<queries>`). */
    fun findProviderPackages(): List<String> {
        val intent = Intent(CapabilityIpcContract.ACTION_PROVIDE)
        return try {
            context.packageManager.queryIntentServices(intent, 0)
                .map { it.serviceInfo.packageName }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Re-fetches descriptors (single package or all visible providers) and
     * upserts them into the registry. Returns the count successfully
     * registered. Failures are skipped, never thrown.
     */
    suspend fun rescan(packageName: String? = null): Int {
        val targets = if (packageName != null) listOf(packageName) else findProviderPackages()
        var registered = 0
        for (target in targets) {
            try {
                val descriptor = invoker.fetchDescriptor(target) ?: continue
                val signer = ServiceToolInvoker.signingSha256(context.packageManager, target).orEmpty()
                if (registry.register(target, descriptor, signer)
                    is ExternalCapabilityRegistry.RegisterOutcome.Ok
                ) {
                    registered++
                }
            } catch (_: Exception) {
                continue
            }
        }
        registry.pruneExpired()
        return registered
    }
}
