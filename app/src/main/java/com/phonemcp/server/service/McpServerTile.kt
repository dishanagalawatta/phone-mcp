package com.phonemcp.server.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import com.phonemcp.server.BuildConfig

/**
 * Quick Settings toggle for the MCP server. Runs in the app process, so the run flag
 * is read directly — no IPC. Long-press opens the dashboard (tile preferences).
 */
class McpServerTile : TileService() {

    override fun onStartListening() {
        qsTile?.let {
            it.state = tileState(McpServerService.running)
            it.updateTile()
        }
    }

    override fun onClick() {
        if (BuildConfig.DEBUG) Log.d(TAG, "tile clicked, running=${McpServerService.running}")
        unlockAndRun {
            try {
                if (McpServerService.running) {
                    stopService(Intent(this, McpServerService::class.java).setAction(McpServerService.ACTION_STOP))
                } else {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, McpServerService::class.java).setAction(McpServerService.ACTION_START),
                    )
                }
            } catch (e: Exception) {
                // Background-start denials (API 34+) land here: fall back to the dashboard.
                Log.w(TAG, "tile start failed (${e.javaClass.simpleName}), opening dashboard instead")
                openDashboard()
            }
            qsTile?.let {
                it.state = tileState(McpServerService.running)
                it.updateTile()
            }
        }
    }

    companion object {
        private const val TAG = "PhoneMcpTile"

        /** Dashboard fallback: PendingIntent collapse on 34+, legacy overload below. */
        // Legacy overload is intentional for API 29–33 (the only collapse path there).
        @SuppressLint("StartActivityAndCollapseDeprecated")
        private fun McpServerTile.openDashboard() {
            val intent = Intent(this, com.phonemcp.server.MainActivity::class.java)
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }

        /** Pure mapping; JVM-test safe. */
        fun tileState(running: Boolean): Int =
            if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    }
}
