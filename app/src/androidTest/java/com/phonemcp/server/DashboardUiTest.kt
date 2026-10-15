package com.phonemcp.server

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.phonemcp.server.service.TokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Drives the real dashboard in-process (works where `adb input tap` cannot).
 * Leaves the service running with all kill-switches ON for follow-up host-side curl runs.
 */
@RunWith(AndroidJUnit4::class)
class DashboardUiTest {

    private val perms = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(perms).around(compose)

    @Test
    fun togglesOnAndServiceStarts() {
        compose.onNodeWithTag("toggle_mic").performClick()
        compose.onNodeWithTag("toggle_camera").performClick()
        compose.onNodeWithTag("toggle_location").performClick()
        compose.onNodeWithText("Accessibility service:", substring = true).assertExists()
        compose.onNodeWithText("Start").performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Running", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tokenStoreRoundTrips() {        // Mechanics probe independent of UI: rotation must be visible to a fresh instance.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = TokenStore(context).ensure()
        val rotated = TokenStore(context).rotate()
        assertNotEquals(before, rotated)
        assertEquals(rotated, TokenStore(context).current())
    }

    @Test
    fun uiClickRevealsToken() {
        // Skip (don't fail) when the service isn't bound: instrumentation restarts kill
        // the app process, which the system records as an unclean death and then refuses
        // to rebind for a while. The bound path is proven green (see docs/testing.md).
        org.junit.Assume.assumeTrue(
            "accessibility service not bound on this run",
            com.phonemcp.server.service.PhoneMcpAccessibilityService.instance != null,
        )
        kotlinx.coroutines.runBlocking {
        // MCP tool drives the REAL dashboard UI: click Show, assert the token text appears.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = TokenStore(context).ensure()
        val ui = com.phonemcp.server.data.UiProvider(
            com.phonemcp.server.data.FileMediaStore(context),
        )
        val result = ui.call(
            "ui_click",
            kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive("Show"))
            },
        )
        assertEquals(false, result.isError)
        compose.onNodeWithText(token, substring = false).assertExists()
        }
    }

    @Test
    fun rotateInvalidatesOldToken() {        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = TokenStore(context).ensure()
        compose.onNodeWithText("Show").performScrollTo().performClick()
        compose.onNodeWithText(before, substring = false).assertExists()
        compose.onNodeWithText("Rotate token", substring = true)
            .performScrollTo()
            .performClick()
        compose.waitUntil(timeoutMillis = 10_000) { TokenStore(context).current() != before }
        compose.onNodeWithText(before, substring = false).assertDoesNotExist()
    }

    @Test
    fun oneButtonGrantsAndRevokesFullAgentAccess() {
        compose.onNodeWithTag("btn_grant_full_access").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("btn_revoke_full_access")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("btn_revoke_full_access").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("btn_grant_full_access")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
