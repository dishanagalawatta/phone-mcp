package com.phonemcp.server

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.phonemcp.server.data.AudioProvider
import com.phonemcp.server.data.BleGatt
import com.phonemcp.server.data.CameraProvider
import com.phonemcp.server.data.FileMediaStore
import com.phonemcp.server.data.LocationProvider
import com.phonemcp.server.data.NfcReader
import com.phonemcp.server.data.OutputProvider
import com.phonemcp.server.data.RadioProvider
import com.phonemcp.server.data.SensorsProvider
import com.phonemcp.server.data.SystemProvider
import com.phonemcp.server.di.NfcEntryPoint
import com.phonemcp.server.domain.Gatekeeper
import dagger.hilt.android.EntryPointAccessors
import com.phonemcp.server.domain.MediaStore
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.McpRouter
import com.phonemcp.server.service.McpServerService
import com.phonemcp.server.service.TokenStore
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises every provider against real hardware. The app stays foregrounded for the whole
 * class (clipboard reads and TTS need it). Run: gradle :app:connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class ProvidersDeviceTest {

    companion object {
        // Lab BLE peripheral for live GATT proof. Example placeholder only —
        // override locally via `BLE_PEER` env / Gradle property, never commit a real MAC.
        private const val TEST_BLE_PEER = "11:22:33:44:55:66"
        private lateinit var scenario: ActivityScenario<MainActivity>
        private val context: Context
            get() = ApplicationProvider.getApplicationContext()

        private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
            for ((key, value) in pairs) {
                when (value) {
                    is String -> put(key, value)
                    is Number -> put(key, value.toDouble())
                    is Boolean -> put(key, value)
                }
            }
        }

        @BeforeClass
        @JvmStatic
        fun openApp() {
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }

        @AfterClass
        @JvmStatic
        fun closeApp() {
            scenario.close()
        }

        private fun textOf(result: com.phonemcp.server.mcp.ToolCallResult): String =
            result.content[0]["text"]!!.jsonPrimitive.contentOrNull.orEmpty()
    }

    @get:Rule
    val perms: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val media: MediaStore by lazy { FileMediaStore(context) }

    @Test
    fun sensorsReadReturnsValues() = runBlocking {
        val result = SensorsProvider(context).call("sensors_read", args("timeoutMs" to 3000))
        assertEquals(false, result.isError)
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(textOf(result)).jsonObject
        val readings = payload["readings"]!!.jsonArray
        assertTrue("expected IMU readings, got ${readings.size}", readings.size >= 1)
        val accel = readings.first { it.jsonObject["sensor"]!!.jsonPrimitive.content == "accelerometer" }
        val values = accel.jsonObject["values"]!!.jsonArray
        assertEquals(3, values.size)
        val magnitude = kotlin.math.sqrt(
            values.map { it.jsonPrimitive.content.toDouble() }
                .fold(0.0) { acc, v -> acc + v * v },
        )
        // At rest the accelerometer must read ~1 g in some direction (m/s^2).
        assertTrue("accel magnitude $magnitude far from 1 g", magnitude in 8.0..12.0)
    }

    @Test
    fun killSwitchGatesThenMicRecords() = runBlocking {
        val gatekeeper = Gatekeeper()
        val router = McpRouter(
            setOf<ToolProvider>(AudioProvider(context, media), SystemProvider(context, com.phonemcp.server.service.TlsStore(context))),
            gatekeeper,
        )
        val blocked = router.callTool("audio_record", args("durationMs" to 1000))
        assertEquals(true, blocked.isError)
        gatekeeper.setEnabled(Gatekeeper.TOOLS_MIC, true)
        val ok = router.callTool("audio_record", args("durationMs" to 1000))
        assertEquals(false, ok.isError)
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(textOf(ok)).jsonObject
        val id = payload["mediaUrl"]!!.jsonPrimitive.content.substringAfterLast("/")
        val file = media.get(id)!!
        assertTrue("clip too small: ${file.length()}", file.length() > 1024)
        val magic = file.inputStream().use { stream ->
            ByteArray(12).also { stream.read(it) }
        }
        assertEquals("ftyp", String(magic, 4, 4, Charsets.US_ASCII))
    }

    @Test
    fun cameraCaptureProducesJpeg() = runBlocking {
        val camera = CameraProvider(context, media)
        val result = camera.call("camera_capture", args("lens" to "back"))
        assertEquals(false, result.isError)
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(textOf(result)).jsonObject
        val id = payload["mediaUrl"]!!.jsonPrimitive.content.substringAfterLast("/")
        val file = media.get(id)!!
        assertTrue("photo too small: ${file.length()}", file.length() > 10_000)
        val header = file.inputStream().use { stream -> ByteArray(2).also { stream.read(it) } }
        assertEquals(0xFF.toByte(), header[0])
        assertEquals(0xD8.toByte(), header[1])
    }

    @Test
    fun cameraVideoProducesMp4() = runBlocking {
        val camera = CameraProvider(context, media)
        val result = camera.call(
            "camera_video",
            args("lens" to "back", "durationMs" to 2000, "withAudio" to false),
        )
        assertEquals(false, result.isError)
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(textOf(result)).jsonObject
        val id = payload["mediaUrl"]!!.jsonPrimitive.content.substringAfterLast("/")
        val file = media.get(id)!!
        assertTrue("video too small: ${file.length()}", file.length() > 10_000)
        val magic = file.inputStream().use { stream -> ByteArray(12).also { stream.read(it) } }
        assertEquals("ftyp", String(magic, 4, 4, Charsets.US_ASCII))
    }

    @Test
    fun outputsBehave() = runBlocking {
        val output = OutputProvider(context)
        assertEquals(false, output.call("vibrate", args("durationMs" to 100)).isError)
        // Torch may briefly report CAMERA_IN_USE right after a CameraX session releases the HAL.
        var torch: com.phonemcp.server.mcp.ToolCallResult? = null
        repeat(8) {
            torch = output.call("torch", args("on" to true))
            if (!(torch!!.isError && textOf(torch!!).contains("CAMERA_IN_USE"))) return@repeat
            Thread.sleep(250)
        }
        assertEquals("torch on", textOf(torch!!))
        assertEquals(false, output.call("torch", args("on" to false)).isError)
        assertEquals(false, output.call("clipboard_set", args("text" to "mcp-probe-123")).isError)
        // Foreground read round-trips; a backgrounded test process gets the specified OS denial.
        val clipText = textOf(output.call("clipboard_get", buildJsonObject { }))
        assertTrue(clipText == "mcp-probe-123" || clipText.contains("foreground"))
        val said = output.call("speaker_say", args("text" to "probe"))
        assertTrue("TTS content empty", said.content.isNotEmpty())
    }

    @Test
    fun bleConnectListsServices() = runBlocking {
        // Live peer seen in scans (set TEST_BLE_PEER / BLE_PEER to your lab peripheral).
        val gatt = BleGatt(context)
        val address = TEST_BLE_PEER
        try {
            val services = gatt.connect(address)
            assertTrue("no services from $address", services.isNotEmpty())
        } finally {
            gatt.disconnect(address)
        }
    }

    @Test
    fun radioAndSystemReport() = runBlocking {        val radio = RadioProvider(context, NfcReader(context), BleGatt(context))
        val battery = kotlinx.serialization.json.Json.parseToJsonElement(
            textOf(radio.call("battery_read", buildJsonObject { })),
        ).jsonObject
        val percent = battery["percent"]!!.jsonPrimitive.content.toInt()
        assertTrue("battery $percent out of range", percent in 0..100)
        val nfc = textOf(radio.call("nfc_status", buildJsonObject { }))
        assertTrue("expected present flag in $nfc", nfc.contains("present"))
        val scanResult = radio.call("ble_scan", args("durationMs" to 3000))
        if (scanResult.isError) {
            // Bluetooth radio off on the device: the explicit error is the correct behavior.
            assertTrue(textOf(scanResult).contains("Bluetooth"))
        } else {
            val scan = kotlinx.serialization.json.Json.parseToJsonElement(textOf(scanResult)).jsonObject
            assertTrue(scan.containsKey("devices") && scan.containsKey("count"))
        }
        val info = kotlinx.serialization.json.Json.parseToJsonElement(
            textOf(SystemProvider(context, com.phonemcp.server.service.TlsStore(context)).call("device_info", buildJsonObject { })),
        ).jsonObject
        assertEquals("2025-06-18", info["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(info["model"]!!.jsonPrimitive.contentOrNull?.isNotEmpty() == true)
    }

    @Test
    fun sensorsStreamCarriesLocationFix() = runBlocking {
        // Needs a real fix; this device has repeatedly returned last-known fixes.
        val seen = mutableListOf<JsonObject>()
        SensorsProvider(context).openStream(
            "sensors_stream",
            buildJsonObject {
                put("intervalMs", JsonPrimitive(500))
                put("durationMs", JsonPrimitive(15_000))
                put("includeLocation", JsonPrimitive(true))
            },
        ).collect { seen.add(it) }
        assertTrue("no batches collected", seen.isNotEmpty())
        val withFix = seen.filter { it.containsKey("location") }
        assertTrue("no batch carried a location fix", withFix.isNotEmpty())
        val fix = withFix.first()["location"]!!.jsonObject
        val lat = fix["lat"]!!.jsonPrimitive.content.toDouble()
        val lon = fix["lon"]!!.jsonPrimitive.content.toDouble()
        assertTrue("lat $lat out of range", lat in -90.0..90.0)
        assertTrue("lon $lon out of range", lon in -180.0..180.0)
    }

    @Test
    fun nfcTapEndToEnd() {
        // Human-in-the-loop: tap any NFC tag/card to the phone within the window.
        // Owns a FRESH scenario (shared @BeforeClass scenarios can be torn down by
        // overlapping runs on a busy device) and uses the Hilt singleton reader
        // (same instance MainActivity arms).
        val fresh = ActivityScenario.launch(MainActivity::class.java)
        try {
            val reader =
                EntryPointAccessors.fromApplication(context, NfcEntryPoint::class.java).reader()
            android.util.Log.i("PhoneMcp", "taptest: enabling reader on scenario activity")
            fresh.onActivity { activity ->
                reader.enable(activity)
            }
            android.util.Log.i("PhoneMcp", "taptest: awaiting tag")
            val tag = kotlinx.coroutines.runBlocking { reader.awaitTag(45_000) }
            assertTrue(
                "no tap seen in 45 s (tap a card/tag to the phone and re-run)",
                tag != null,
            )
            assertTrue(tag!!.idHex.isNotEmpty())
            assertTrue(tag.techs.isNotEmpty())
        } finally {
            try {
                fresh.close()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun locationReturnsFixOrExplicitError() = runBlocking {        val result = LocationProvider(context).call("location_read", args("timeoutMs" to 8000))
        val text = textOf(result)
        if (!result.isError) {
            val fix = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            val lat = fix["latitude"]!!.jsonPrimitive.content.toDouble()
            val lon = fix["longitude"]!!.jsonPrimitive.content.toDouble()
            assertTrue("lat $lat out of range", lat in -90.0..90.0)
            assertTrue("lon $lon out of range", lon in -180.0..180.0)
        } else {
            assertTrue("unexpected error: $text", text.contains("No location fix"))
        }
    }

    @Test
    fun tlsServesAndTokenGates() {
        val appContext = context.applicationContext
        appContext.startForegroundService(
            Intent(appContext, McpServerService::class.java).setAction(McpServerService.ACTION_START),
        )
        val token = TokenStore(appContext).ensure()
        // Wait for the listener (up to 30 s).
        var health = -1
        repeat(30) {
            try {
                health = https("GET", "/healthz", null).first
            } catch (_: Exception) {
            }
            if (health == 200) return@repeat
            Thread.sleep(1000)
        }
        assertEquals(200, health)
        // Cert pin matches device_info.
        val presented = peerFingerprint()
        val info = runBlocking {
            SystemProvider(appContext, com.phonemcp.server.service.TlsStore(appContext))
                .call("device_info", buildJsonObject { })
        }
        val reported = kotlinx.serialization.json.Json.parseToJsonElement(textOf(info))
            .jsonObject["tlsSha256"]!!.jsonPrimitive.content
        assertEquals(reported.lowercase(), presented)
        // Bad token rejected, good token accepted.
        assertEquals(401, https("POST", "/mcp", "Bearer wrong").first)
        val (code, body) = https(
            "POST",
            "/mcp",
            "Bearer $token",
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
        )
        assertEquals(200, code)
        assertTrue(body.contains("2025-06-18"))
    }

    // ---- permissive-TLS helpers (test-only: TOFU pin checked separately above) ----

    private fun sslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            },
        )
        return SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }.socketFactory
    }

    private fun https(method: String, path: String, auth: String?, body: String? = null): Pair<Int, String> {
        val conn = URL("https://127.0.0.1:8080$path").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = sslSocketFactory()
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        if (auth != null) conn.setRequestProperty("Authorization", auth)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val text = try {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        } finally {
            conn.disconnect()
        }
        return code to text
    }

    private fun peerFingerprint(): String {
        val conn = URL("https://127.0.0.1:8080/healthz").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = sslSocketFactory()
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 5000
        try {
            conn.connect()
            val cert = conn.serverCertificates[0]
            return MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                .joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }
}
