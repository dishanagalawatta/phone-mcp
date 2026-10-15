package com.phonemcp.server.presentation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonemcp.server.BuildConfig
import com.phonemcp.server.data.ExternalCapabilityDiscovery
import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.domain.LogBuffer
import com.phonemcp.server.mcp.ExternalCapabilityRegistry
import com.phonemcp.server.presentation.components.ExternalCapabilityEntry
import com.phonemcp.server.presentation.theme.ThemeMode
import com.phonemcp.server.service.McpServerService
import com.phonemcp.server.service.NetInspectVpnService
import com.phonemcp.server.service.NetInfo
import com.phonemcp.server.service.ServerConfig
import com.phonemcp.server.service.TlsStore
import com.phonemcp.server.service.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardUiState(
    val running: Boolean = false,
    val urls: List<String> = emptyList(),
    val log: List<String> = emptyList(),
    val micEnabled: Boolean = false,
    val cameraEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val permissions: Map<String, Boolean> = emptyMap(),
    val token: String? = null,
    val fingerprint: String = "",
    val bleWriteEnabled: Boolean = false,
    val uiEnabled: Boolean = false,
    val netEnabled: Boolean = false,
    val vpnRunning: Boolean = false,
    /** Third-party proxied tools (`ext_*`) master switch. Default OFF (untrusted). */
    val externalEnabled: Boolean = false,
    /** Registered third-party capabilities (id + invocation consent). */
    val externalCapabilities: List<ExternalCapabilityEntry> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accessibilityOn: Boolean = false,
    /** When true the window is hidden from mirrors/recents (scrcpy shows black). Default ON. */
    val secureScreen: Boolean = true,
    /**
     * True once the user granted the QS-tile add prompt (or it was already
     * added). Headless `adb … click-tile` start/stop silently no-ops until
     * this is true — the hero card says so instead of failing quietly.
     * Best-effort (grant-time signal; later manual tile removal is not
     * re-detected), never a secret.
     */
    val tileAdded: Boolean = false,
) {
    /** True when all 7 hardware and capability kill-switches are active. */
    val allGatesEnabled: Boolean
        get() = micEnabled && cameraEnabled && locationEnabled && bleWriteEnabled && uiEnabled && netEnabled && externalEnabled

    /** True when all required Android runtime permissions are granted. */
    val allPermissionsGranted: Boolean
        get() = permissions.isNotEmpty() && permissions.values.all { it }

    /** True when gates, permissions, and accessibility service are fully configured for agent autonomy. */
    val hasFullAgentAccess: Boolean
        get() = allGatesEnabled && allPermissionsGranted && accessibilityOn

    /** Number of active hardware/capability gates out of 7. */
    val activeGateCount: Int
        get() = listOf(micEnabled, cameraEnabled, locationEnabled, bleWriteEnabled, uiEnabled, netEnabled, externalEnabled).count { it }

    /** Number of granted runtime permissions. */
    val grantedPermissionCount: Int
        get() = permissions.values.count { it }
}

/** IO-bound snapshot (LAN IPs, perms, theme, a11y) computed off the main thread. */
data class DashboardNetSnapshot(
    val urls: List<String> = emptyList(),
    val permissions: Map<String, Boolean> = emptyMap(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accessibilityOn: Boolean = false,
    val secureScreen: Boolean = true,
    val tileAdded: Boolean = false,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val gatekeeper: Gatekeeper,
    private val logBuffer: LogBuffer,
    private val config: ServerConfig,
    private val tokens: TokenStore,
    private val tls: TlsStore,
    private val externalRegistry: ExternalCapabilityRegistry,
    private val externalDiscovery: ExternalCapabilityDiscovery,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val tick = MutableStateFlow(0)
    private val tokenState = MutableStateFlow<String?>(null)
    private val fingerprintState = MutableStateFlow("")
    private val netState = MutableStateFlow(DashboardNetSnapshot())

    val state = combine(
        combine(tick, gatekeeper.disabled, logBuffer.lines, tokenState, fingerprintState) {
            _, disabled, lines, token, fingerprint ->
            val net = netState.value
            DashboardUiState(
                running = McpServerService.running,
                urls = net.urls,
                log = lines,
                micEnabled = !disabled.contains(Gatekeeper.TOOLS_MIC),
                cameraEnabled = !disabled.contains(Gatekeeper.TOOLS_CAMERA),
                locationEnabled = !disabled.contains(Gatekeeper.TOOLS_LOCATION),
                permissions = net.permissions,
                token = token,
                fingerprint = fingerprint,
                bleWriteEnabled = !disabled.contains(Gatekeeper.TOOLS_BLEWRITE),
                uiEnabled = !disabled.contains(Gatekeeper.TOOLS_UI),
                netEnabled = !disabled.contains(Gatekeeper.TOOLS_NET),
                vpnRunning = NetInspectVpnService.running,
                externalEnabled = !disabled.contains(Gatekeeper.TOOLS_EXT),
                themeMode = net.themeMode,
                accessibilityOn = net.accessibilityOn,
                secureScreen = net.secureScreen,
                tileAdded = net.tileAdded,
            )
        },
        externalRegistry.snapshot,
    ) { ui, external ->
        ui.copy(
            externalCapabilities = external
                .map { ExternalCapabilityEntry(it.id, it.approved) }
                .sortedBy { it.id },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardUiState())

    /** Re-reads running flag, addresses, permission states, and ensures pairing material exists. */
    fun refresh() {
        if (tokenState.value == null) tokenState.value = tokens.ensure()
        if (fingerprintState.value.isEmpty()) fingerprintState.value = tls.fingerprint()
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = withContext(Dispatchers.IO) {
                DashboardNetSnapshot(
                    urls = try {
                        NetInfo.lanIpv4().map { "https://$it:${config.port}/mcp" }
                    } catch (_: Exception) {
                        emptyList()
                    },
                    permissions = REQUIRED_PERMISSIONS.associateWith { isGranted(it) },
                    themeMode = readThemeMode(),
                    accessibilityOn = isAccessibilityEnabled(),
                    secureScreen = readSecureScreen(),
                    tileAdded = readTileAdded(),
                )
            }
            netState.value = snapshot
            tick.value += 1
        }
    }

    /** Rotates the bearer token; paired agents must re-pair immediately. */
    fun rotateToken() {
        tokenState.value = tokens.rotate()
        tick.value += 1
        // Value never logged: rotation event only.
        if (BuildConfig.DEBUG) Log.i("PhoneMcp", "bearer token rotated")
    }

    fun copyToClipboard(label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        // API 33+: mark auth material sensitive so the system can redact/auto-clear it.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            } catch (_: Exception) {
            }
        }
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(clip)
    }

    fun setMicEnabled(enabled: Boolean) = gatekeeper.setEnabled(Gatekeeper.TOOLS_MIC, enabled)

    fun setCameraEnabled(enabled: Boolean) = gatekeeper.setEnabled(Gatekeeper.TOOLS_CAMERA, enabled)

    fun setLocationEnabled(enabled: Boolean) = gatekeeper.setEnabled(Gatekeeper.TOOLS_LOCATION, enabled)

    fun setBleWriteEnabled(enabled: Boolean) = gatekeeper.setEnabled(Gatekeeper.TOOLS_BLEWRITE, enabled)

    fun setUiEnabled(enabled: Boolean) = gatekeeper.setEnabled(Gatekeeper.TOOLS_UI, enabled)

    fun setNetEnabled(enabled: Boolean) = gatekeeper.setEnabled(Gatekeeper.TOOLS_NET, enabled)

    /** Master switch for third-party proxied tools (`ext_*`). Default OFF. */
    fun setExternalEnabled(enabled: Boolean) = gatekeeper.setEnabled(Gatekeeper.TOOLS_EXT, enabled)

    /** Removes a third-party capability (dashboard owner action, bearer-gated by the app itself). */
    fun unregisterExternal(capabilityId: String) {
        externalRegistry.unregister(capabilityId, forceLocal = true)
        tick.value += 1
    }

    /** Sets per-capability invocation consent from the dashboard card. */
    fun setExternalApproval(capabilityId: String, approved: Boolean) {
        externalRegistry.setApproved(capabilityId, approved, forceLocal = true)
        tick.value += 1
    }

    /** Re-queries installed provider apps; refreshes the capability list on completion. */
    fun rescanExternal() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                externalDiscovery.rescan()
            } catch (_: Exception) {
            }
            tick.value += 1
        }
    }

    /** Persists the appearance choice; a tick re-read picks it up immediately. */
    fun setThemeMode(mode: ThemeMode) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_THEME, mode.stored).apply()
        } catch (_: Exception) {
        }
        tick.value += 1
    }

    /** Persists the secure-screen choice (mirror/recents blanking); re-read on next tick. */
    fun setSecureScreen(enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SECURE, enabled).apply()
        } catch (_: Exception) {
        }
        // Re-snapshot (not just tick): state.secureScreen is served from netState.
        refresh()
    }

    /**
     * Records a granted QS-tile add prompt (or already-added). Called from
     * the add-tile result callback — never from the tile itself.
     */
    fun markTileAdded() {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TILE_ADDED, true).apply()
        } catch (_: Exception) {
        }
        refresh()
    }

    private fun readSecureScreen(): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SECURE, true)
    } catch (_: Exception) {
        true
    }

    private fun readTileAdded(): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TILE_ADDED, false)
    } catch (_: Exception) {
        false
    }

    private fun readThemeMode(): ThemeMode = try {
        ThemeMode.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null))
    } catch (_: Exception) {
        ThemeMode.SYSTEM
    }

    /** Subset of [REQUIRED_PERMISSIONS] that is currently denied. */
    fun missingPermissions(): Array<String> =
        REQUIRED_PERMISSIONS.filter { !isGranted(it) }.toTypedArray()

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Whether our accessibility service is currently enabled (read-only check, no permission needed). */
    private fun isAccessibilityEnabled(): Boolean {
        val masterOn = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
        if (!masterOn) return false
        val enabled = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Exception) {
            null
        }
        return isServiceListed(enabled, context.packageName)
    }

    companion object {
        const val PREFS = "phone_mcp_ui"
        const val KEY_THEME = "theme_mode"
        const val KEY_SECURE = "secure_screen"
        const val KEY_TILE_ADDED = "qs_tile_added"

        /**
         * Matches our service in the colon-separated `ENABLED_ACCESSIBILITY_SERVICES`
         * list. The system stores the flattened component in whatever form the
         * resolving package used (full `com.phonemcp.server.service.…` or short
         * `.service.…`), so an exact-string compare against one form always misses
         * the other — compare package + class short name instead. Pure; JVM-testable.
         */
        fun isServiceListed(enabledServices: String?, packageName: String): Boolean {
            if (enabledServices.isNullOrBlank()) return false
            return enabledServices.split(':').any { part ->
                val cmp = part.trim()
                if (cmp.isEmpty()) return@any false
                val slash = cmp.indexOf('/')
                if (slash < 0) return@any false
                val pkg = cmp.substring(0, slash)
                val cls = cmp.substring(slash + 1)
                pkg == packageName && (cls == CLASS_NAME || cls.endsWith(".$CLASS_SHORT"))
            }
        }

        private const val CLASS_NAME = "com.phonemcp.server.service.PhoneMcpAccessibilityService"
        private const val CLASS_SHORT = "PhoneMcpAccessibilityService"
        /** Runtime permissions needed across the tool matrix (API-guarded where the perm is new). */
        val REQUIRED_PERMISSIONS: List<String> = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
