package com.phonemcp.server.presentation.components

import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmTextTest {

    @Test
    fun secureOffWarnsAboutTokenExposure() {
        val (title, message, confirm) = secureConfirmText(false)
        assertTrue(title.isNotBlank() && message.contains("token") && confirm.isNotBlank())
    }

    @Test
    fun secureOnNamesMirrorHiding() {
        val (title, message, _) = secureConfirmText(true)
        assertTrue(title.isNotBlank() && message.contains("black"))
    }
}
