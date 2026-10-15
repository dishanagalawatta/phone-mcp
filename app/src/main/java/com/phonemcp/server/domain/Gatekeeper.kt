package com.phonemcp.server.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Per-category kill-switches. Mic/camera/location/BLE-write/UI/net/external default OFF until explicitly enabled. */
@Singleton
class Gatekeeper @Inject constructor() {
    private val _disabled = MutableStateFlow(setOf(TOOLS_MIC, TOOLS_CAMERA, TOOLS_LOCATION, TOOLS_BLEWRITE, TOOLS_UI, TOOLS_NET, TOOLS_EXT))
    val disabled: StateFlow<Set<String>> = _disabled

    fun isAllowed(toolName: String): Boolean =
        _disabled.value.none { prefix ->
            if (prefix.endsWith("_")) toolName.startsWith(prefix) else toolName == prefix
        }

    fun isEnabled(categoryPrefix: String): Boolean = !_disabled.value.contains(categoryPrefix)

    fun setEnabled(categoryPrefix: String, enabled: Boolean) {
        _disabled.update { if (enabled) it - categoryPrefix else it + categoryPrefix }
    }

    companion object {
        const val TOOLS_MIC = "audio_"
        const val TOOLS_CAMERA = "camera_"
        const val TOOLS_LOCATION = "location_"
        const val TOOLS_BLEWRITE = "ble_write"
        const val TOOLS_UI = "ui_"
        const val TOOLS_NET = "net_"

        /**
         * Third-party proxied tools (`ext_<cap>__<tool>`). Default OFF:
         * external code is untrusted until the user enables it on the
         * dashboard. Management tools (`capabilities_*`) stay ungated.
         */
        const val TOOLS_EXT = "ext_"
    }
}
