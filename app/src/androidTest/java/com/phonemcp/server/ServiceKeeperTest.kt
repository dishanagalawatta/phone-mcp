package com.phonemcp.server

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonemcp.server.di.GatekeeperEntryPoint
import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.service.McpServerService
import dagger.hilt.android.EntryPointAccessors
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Holds the real service up with all kill-switches ON for a fixed window so host-side
 * curl runs have a deterministic target. Not an assertion test: it always passes unless
 * the service fails to bind (health check fails and the curl window is useless).
 */
@RunWith(AndroidJUnit4::class)
class ServiceKeeperTest {

    @Test
    fun holdOpenForHostCurl() {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext
        app.startForegroundService(
            Intent(app, McpServerService::class.java).setAction(McpServerService.ACTION_START),
        )
        val gatekeeper = EntryPointAccessors.fromApplication(app, GatekeeperEntryPoint::class.java).gatekeeper()
        gatekeeper.setEnabled(Gatekeeper.TOOLS_MIC, true)
        gatekeeper.setEnabled(Gatekeeper.TOOLS_CAMERA, true)
        gatekeeper.setEnabled(Gatekeeper.TOOLS_LOCATION, true)
        // Window for host-side ./scripts/verify-mcp.sh + extras (see docs/testing.md).
        Thread.sleep(150_000)
    }
}
