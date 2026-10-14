package com.phonemcp.server.presentation

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders a QR bitmap for agent pairing. Content is a JSON envelope
 * `{"url":"http://<lan-ip>:<port>/mcp","token":"<bearer>"}` — any agent can parse it.
 * Called from composition inside remember(); failures return null (QR section hidden).
 */
fun pairingQrBitmap(content: String, size: Int = 512): Bitmap? {
    if (content.isBlank()) return null
    return try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}
