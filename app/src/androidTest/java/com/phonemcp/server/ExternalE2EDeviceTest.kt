package com.phonemcp.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonemcp.server.di.ExternalTestEntryPoint
import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.mcp.JsonRpcCodes
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full third-party capability loop on-device: PackageManager discovery →
 * bound-service descriptor fetch → approval → proxied `tools/call` against
 * the real sample provider app (`com.phonemcp.sampleprovider`, must be
 * installed; see `sample-provider/`). No dashboard taps needed — consent is
 * driven through the same registry paths the dashboard uses.
 */
@RunWith(AndroidJUnit4::class)
class ExternalE2EDeviceTest {

    companion object {
        private const val PROVIDER_PKG = "com.phonemcp.sampleprovider"

        private fun entryPoint(): ExternalTestEntryPoint =
            EntryPointAccessors.fromApplication(
                ApplicationProvider.getApplicationContext<Context>(),
                ExternalTestEntryPoint::class.java,
            )
    }

    @Test
    fun fullIpcLoopRescanApproveInvoke() = runBlocking {
        val entry = entryPoint()
        val gatekeeper = entry.gatekeeper()
        val registry = entry.registry()
        val discovery = entry.discovery()
        val router = entry.router()

        gatekeeper.setEnabled(Gatekeeper.TOOLS_EXT, true)
        try {
            val found = discovery.rescan(PROVIDER_PKG)
            assertTrue("sample provider discovered via rescan (is it installed?)", found >= 1)
            assertTrue(registry.setApproved("demo", true, forceLocal = true))

            val names = router.listTools().map { it.name }
            assertTrue(names.contains("ext_demo__echo"))
            assertTrue(names.contains("ext_demo__add"))
            assertTrue(names.contains("ext_demo__time"))

            val echo = router.callTool(
                "ext_demo__echo",
                buildJsonObject { put("text", "hello e2e") },
            )
            assertFalse("echo proxied: ${echo.content.firstOrNull()}", echo.isError)
            assertTrue(echo.content[0]["text"]!!.jsonPrimitive.content.contains("hello e2e"))

            val sum = router.callTool(
                "ext_demo__add",
                buildJsonObject {
                    put("a", 2)
                    put("b", 3)
                },
            )
            assertFalse("add proxied: ${sum.content.firstOrNull()}", sum.isError)
            assertTrue(sum.content[0]["text"]!!.jsonPrimitive.content.contains("5"))

            val unknown = registry.callTool("ext_demo__nope", buildJsonObject { })
            assertTrue(unknown.isError)
            assertEquals(JsonRpcCodes.INVALID_PARAMS, unknown.rpcCode)
        } finally {
            registry.setApproved("demo", false, forceLocal = true)
            registry.unregister("demo", forceLocal = true)
            gatekeeper.setEnabled(Gatekeeper.TOOLS_EXT, false)
        }
    }
}
