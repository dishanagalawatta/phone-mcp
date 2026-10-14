package com.phonemcp.server.presentation.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Guard rail for destructive or privacy-sensitive one-tap actions (Secure
 * screen flips, token rotation, kill-switch changes): the action fires only
 * on explicit Confirm, never on a mis-tap. Copy is JVM-pinned in
 * ConfirmTextTest so safety wording cannot silently regress.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm_dialog_ok"),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("confirm_dialog_cancel"),
            ) {
                Text("Cancel")
            }
        },
        modifier = modifier.testTag("confirm_dialog"),
    )
}

/** (title, message, confirmLabel) for Secure-screen flips. */
fun secureConfirmText(enable: Boolean): Triple<String, String, String> =
    if (enable) {
        Triple(
            "Hide screen in mirrors?",
            "Mirrors, recents, and screenshots will show black instead of the dashboard.",
            "Hide screen",
        )
    } else {
        Triple(
            "Show screen in mirrors?",
            "Anyone viewing a mirror or recording can read the pairing token and QR. " +
                "Re-enable Secure screen afterwards.",
            "Show anyway",
        )
    }
