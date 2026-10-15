package com.phonemcp.sampleprovider

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One-screen sample: announces this app's capability to Phone MCP.
 * Announcement is a hint — Phone MCP re-fetches the authoritative
 * descriptor from [SampleProviderService] itself.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val title = TextView(this).apply {
            text = "Sample Provider (demo)"
            textSize = 20f
        }
        status = TextView(this).apply {
            text = "Exposes demo: echo, add, time"
        }
        val announce = Button(this).apply {
            text = "Announce to Phone MCP"
            setOnClickListener { sendAnnounce() }
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(announce)
        setContentView(layout)
        // Announce on launch too: covers first install without a tap.
        sendAnnounce()
    }

    private fun sendAnnounce() {
        try {
            sendBroadcast(
                Intent(ACTION_ANNOUNCE)
                    .setPackage(PHONE_MCP)
                    .putExtra(EXTRA_PACKAGE, packageName),
            )
            status.text = "Announced at ${java.util.Date()}"
        } catch (e: Exception) {
            status.text = "Announce failed: ${e.message}"
        }
    }

    companion object {
        // Must match phone-mcp CapabilityIpcContract (copied: no shared dep by design).
        const val PHONE_MCP = "com.phonemcp.server"
        const val ACTION_ANNOUNCE = "com.phonemcp.server.action.CAPABILITY_ANNOUNCED"
        const val EXTRA_PACKAGE = "com.phonemcp.server.extra.PACKAGE"
    }
}
