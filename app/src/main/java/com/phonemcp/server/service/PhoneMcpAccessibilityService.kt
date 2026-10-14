package com.phonemcp.server.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Operate-the-phone path for MCP UI tools. The system binds here only after the user
 * enables "Phone MCP" in Settings → Accessibility. Exposes a static instance so
 * [com.phonemcp.server.data.UiProvider] can act; null until bound (tools report this
 * explicitly instead of crashing).
 */
class PhoneMcpAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Poll model: agents pull snapshots/actions on demand; no event subscription in v1.
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PhoneMcpAccessibilityService? = null
            private set
    }
}
