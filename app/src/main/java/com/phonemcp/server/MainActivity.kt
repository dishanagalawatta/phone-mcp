package com.phonemcp.server

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phonemcp.server.data.NfcReader
import com.phonemcp.server.presentation.DashboardScreen
import com.phonemcp.server.presentation.DashboardViewModel
import com.phonemcp.server.presentation.theme.PhoneMcpTheme
import com.phonemcp.server.service.McpServerService
import com.phonemcp.server.service.NetInspectVpnService
import com.phonemcp.server.service.McpServerTile
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    @Inject
    lateinit var nfc: NfcReader

    private var nfcJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refresh()
        }

    private val vpnConsentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, NetInspectVpnService::class.java).setAction(NetInspectVpnService.ACTION_START),
                )
            } else {
                android.widget.Toast.makeText(this, "VPN consent denied — capture stays off", android.widget.Toast.LENGTH_LONG).show()
            }
            viewModel.refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Secure-screen default: set once; afterwards the dashboard toggle owns the
        // flag reactively (secure default protects token/QR in mirrors/recents).
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refresh() }
        }
        setContent {
            val state by viewModel.state.collectAsState()
            androidx.compose.runtime.LaunchedEffect(state.secureScreen) {
                if (state.secureScreen) {
                    window.setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    )
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            PhoneMcpTheme(mode = state.themeMode) {
                DashboardScreen(
                    state = state,
                    onStart = { startService(McpServerService.ACTION_START) },
                    onStop = { startService(McpServerService.ACTION_STOP) },
                    onAddTile = { requestTileAdd() },
                    onAddWidget = { requestWidgetPin() },
                    onGrantPermissions = {
                        val missing = viewModel.missingPermissions()
                        if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                    },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onMicToggle = viewModel::setMicEnabled,
                    onCameraToggle = viewModel::setCameraEnabled,
                    onLocationToggle = viewModel::setLocationEnabled,
                    onBleWriteToggle = viewModel::setBleWriteEnabled,
                    onUiToggle = viewModel::setUiEnabled,
                    onNetToggle = viewModel::setNetEnabled,
                    onExternalToggle = viewModel::setExternalEnabled,
                    onUnregisterExternal = viewModel::unregisterExternal,
                    onRescanExternal = viewModel::rescanExternal,
                    onThemeChange = viewModel::setThemeMode,
                    onSecureChange = viewModel::setSecureScreen,
                    onStartVpn = { requestVpnStart() },
                    onStopVpn = {
                        startService(Intent(this, NetInspectVpnService::class.java).setAction(NetInspectVpnService.ACTION_STOP))
                        viewModel.refresh()
                    },
                    onCopy = viewModel::copyToClipboard,
                    onRotateToken = viewModel::rotateToken,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Demand-driven NFC reader mode: armed exactly while an nfc_read is parked.
        nfcJob = lifecycleScope.launch {
            nfc.hasWaiters.collect { waiting ->
                if (waiting) nfc.enable(this@MainActivity) else nfc.disable(this@MainActivity)
            }
        }
    }

    override fun onPause() {
        nfcJob?.cancel()
        nfcJob = null
        nfc.disable(this)
        super.onPause()
    }

    private fun startService(action: String) {
        val intent = Intent(this, McpServerService::class.java).setAction(action)
        try {
            if (action == McpServerService.ACTION_STOP) {
                // STOP carries no foreground obligation: plain start avoids the
                // startForegroundService contract (DidNotStartInTime crash when the
                // process was dead and STOP never promotes to foreground).
                startService(intent)
            } else {
                ContextCompat.startForegroundService(this, intent)
            }
        } catch (e: Exception) {
            // Background-start denials (API 31+/34+): surface, never crash the dashboard.
            android.widget.Toast.makeText(
                this,
                "Server start denied in background — open the app and retry (${e.javaClass.simpleName})",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** VPN needs explicit user consent via the system sheet; never start headless. */
    private fun requestVpnStart() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnConsentLauncher.launch(consent)
        } else {
            ContextCompat.startForegroundService(
                this,
                Intent(this, NetInspectVpnService::class.java).setAction(NetInspectVpnService.ACTION_START),
            )
            viewModel.refresh()
        }
    }

    /** API 33+ can pin the tile directly; older releases need the manual QS edit panel. */
    private fun requestTileAdd() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            android.widget.Toast.makeText(
                this,
                "Pull down Quick Settings, tap edit, and add Phone MCP",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val manager = getSystemService(android.app.StatusBarManager::class.java) ?: return
        manager.requestAddTileService(
            android.content.ComponentName(this, McpServerTile::class.java),
            getString(R.string.tile_label),
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher_foreground),
            mainExecutor,
        ) { _ -> }
    }

    /** Pinned widget request (API 26+); the system asks the user to confirm placement. */
    private fun requestWidgetPin() {
        val manager =
            getSystemService(android.appwidget.AppWidgetManager::class.java) ?: return
        val provider = android.content.ComponentName(
            this,
            com.phonemcp.server.presentation.PhoneMcpWidget::class.java,
        )
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(provider, null, null)
        } else {
            android.widget.Toast.makeText(
                this,
                "Long-press the home screen → Widgets → Phone MCP",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
