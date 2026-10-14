package com.phonemcp.server

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.phonemcp.server.di.GatekeeperEntryPoint
import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.service.McpServerService
import com.phonemcp.server.service.TokenStore
import dagger.hilt.android.EntryPointAccessors
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full MCP round-trip over loopback TLS: starts the real foreground service, flips the real
 * kill-switches, and exercises every tool through HTTP exactly as a remote agent would —
 * including SSE streaming, media fetch + ranges, auth gates, and rotation.
 */
@RunWith(AndroidJUnit4::class)
class McpE2EDeviceTest {

    companion object {
        // Lab BLE peripheral for live GATT proof. Example placeholder only —
        // override locally, never commit a real MAC.
        private const val TEST_BLE_PEER = "11:22:33:44:55:66"
        private lateinit var scenario: ActivityScenario<MainActivity>

        @Volatile
        var lastStreamError: String = ""
        private val context: Context
            get() = ApplicationProvider.getApplicationContext()

        private fun gatekeeper(): Gatekeeper =
            EntryPointAccessors.fromApplication(context, GatekeeperEntryPoint::class.java).gatekeeper()

        private fun sslFactory(): javax.net.ssl.SSLSocketFactory {
            val trustAll = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                },
            )
            return SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }.socketFactory
        }

        private fun call(method: String, id: Int, params: String, token: String?): Pair<Int, String> {
            val conn = URL("https://127.0.0.1:8080/mcp").openConnection() as HttpsURLConnection
            conn.sslSocketFactory = sslFactory()
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            val body = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
            conn.outputStream.use { it.write(body.toByteArray()) }
            return readResponse(conn)
        }

        private fun readResponse(conn: HttpsURLConnection): Pair<Int, String> {
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

        private fun get(path: String, token: String?, range: String? = null): Triple<Int, ByteArray, String?> {
            val conn = URL("https://127.0.0.1:8080$path").openConnection() as HttpsURLConnection
            conn.sslSocketFactory = sslFactory()
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (range != null) conn.setRequestProperty("Range", range)
            val code = conn.responseCode
            val contentRange = conn.getHeaderField("Content-Range")
            val bytes = try {
                conn.inputStream.use { it.readBytes() }
            } catch (_: Exception) {
                ByteArray(0)
            } finally {
                conn.disconnect()
            }
            return Triple(code, bytes, contentRange)
        }

        private fun tool(token: String, name: String, args: String): Pair<Int, String> =
            call("tools/call", 100, """{"name":"$name","arguments":$args}""", token)

        private fun toolText(json: String): String =
            Json.parseToJsonElement(json).jsonObject["result"]!!
                .jsonObject["content"]!!.jsonArray[0]
                .jsonObject["text"]!!.jsonPrimitive.content

        @BeforeClass
        @JvmStatic
        fun openAppAndServe() {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val app = context.applicationContext
            app.startForegroundService(
                Intent(app, McpServerService::class.java).setAction(McpServerService.ACTION_START),
            )
            val token = TokenStore(app).ensure()
            var ok = false
            repeat(30) {
                try {
                    if (get("/healthz", token).first == 200) ok = true
                } catch (_: Exception) {
                }
                if (!ok) Thread.sleep(1000)
            }
            assertTrue("server never became healthy", ok)
        }

        @AfterClass
        @JvmStatic
        fun closeApp() {
            scenario.close()
        }
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

    @Test
    fun fullAgentJourney() {
        val token = TokenStore(context.applicationContext).ensure()

        // --- protocol basics + gates ---
        assertEquals(200, get("/healthz", null).first)
        // /mcp is auth-first (ADR-0021): wrong-method probes without a token get 401, not 405.
        assertEquals(401, get("/mcp", null).first)
        assertEquals(401, call("tools/list", 1, "{}", null).first)
        val (listCode, listBody) = call("tools/list", 2, "{}", token)
        assertEquals(200, listCode)
        val tools = Json.parseToJsonElement(listBody).jsonObject["result"]!!
            .jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("expected >= 28 tools, got ${tools.size}", tools.size >= 28)
        assertTrue(tools.containsAll(listOf("sensors_read", "sensors_stream", "audio_stream", "camera_video", "torch", "battery_read", "ble_connect", "ble_read", "ble_write", "ble_subscribe", "ble_disconnect", "nfc_read", "nfc_status", "ui_screenshot", "ui_dump", "ui_click", "ui_swipe", "ui_type", "ui_key")))

        // --- kill-switches deny when off, allow when on (set explicitly: order-independent) ---
        gatekeeper().setEnabled(Gatekeeper.TOOLS_MIC, false)
        gatekeeper().setEnabled(Gatekeeper.TOOLS_CAMERA, false)
        gatekeeper().setEnabled(Gatekeeper.TOOLS_LOCATION, false)
        val denied = tool(token, "audio_record", """{"durationMs":1000}""")
        assertEquals(200, denied.first)
        assertTrue(Json.parseToJsonElement(denied.second).jsonObject["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
        // BLE write stays gated for the whole run (it actuates real hardware): denial only.
        val writeDenied = tool(token, "ble_write", """{"address":"11:22:33:44:55:66","serviceUuid":"0000180f-0000-1000-8000-00805f9b34fb","charUuid":"00002a19-0000-1000-8000-00805f9b34fb","hex":"00"}""")
        assertEquals(200, writeDenied.first)
        assertTrue(Json.parseToJsonElement(writeDenied.second).jsonObject["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
        gatekeeper().setEnabled(Gatekeeper.TOOLS_MIC, true)
        gatekeeper().setEnabled(Gatekeeper.TOOLS_CAMERA, true)
        gatekeeper().setEnabled(Gatekeeper.TOOLS_LOCATION, true)

        // --- outputs ---
        assertEquals(200, tool(token, "vibrate", "{}").first)
        assertEquals("torch on", toolText(tool(token, "torch", """{"on":true}""").second))
        assertEquals(200, tool(token, "torch", """{"on":false}""").first)
        assertEquals(200, tool(token, "speaker_say", """{"text":"probe"}""").first)
        assertEquals(200, tool(token, "clipboard_set", """{"text":"e2e-456"}""").first)
        val got = tool(token, "clipboard_get", "{}")
        assertEquals(200, got.first)
        val gotText = toolText(got.second)
        // Foreground read round-trips; backgrounded reads are denied by the OS — both are specified.
        assertTrue(gotText == "e2e-456" || gotText.contains("foreground"))

        // --- sensors + system ---
        val sensors = toolText(tool(token, "sensors_read", """{"timeoutMs":3000}""").second)
        assertTrue(Json.parseToJsonElement(sensors).jsonObject["readings"]!!.jsonArray.size >= 1)
        val battery = toolText(tool(token, "battery_read", "{}").second)
        assertTrue(Json.parseToJsonElement(battery).jsonObject["percent"]!!.jsonPrimitive.content.toInt() in 0..100)
        val info = toolText(tool(token, "device_info", "{}").second)
        val infoObj = Json.parseToJsonElement(info).jsonObject
        assertEquals("2025-06-18", infoObj["protocolVersion"]!!.jsonPrimitive.content)
        val nfc = toolText(tool(token, "nfc_status", "{}").second)
        assertTrue(nfc.contains("present"))
        val loc = tool(token, "location_read", """{"timeoutMs":8000}""")
        assertEquals(200, loc.first)

        // --- media round-trips (audio + photo + video) ---
        val audioUrl = mediaUrl(tool(token, "audio_record", """{"durationMs":2000}""").second)
        val (fullCode, fullBytes, _) = get(audioUrl, token)
        assertEquals(200, fullCode)
        assertTrue(fullBytes.size > 1024)
        assertEquals("ftyp", String(fullBytes, 4, 4, Charsets.US_ASCII))
        val (rangeCode, rangeBytes, contentRange) = get(audioUrl, token, "bytes=0-99")
        assertEquals(206, rangeCode)
        assertEquals(100, rangeBytes.size)
        assertTrue(contentRange.orEmpty().startsWith("bytes 0-99/"))
        val photoUrl = mediaUrl(tool(token, "camera_capture", """{"lens":"back"}""").second)
        val (_, photoBytes, _) = get(photoUrl, token)
        assertTrue(photoBytes.size > 10_000)
        assertEquals(0xFF.toByte(), photoBytes[0])
        assertEquals(0xD8.toByte(), photoBytes[1])
        val videoUrl = mediaUrl(tool(token, "camera_video", """{"lens":"back","durationMs":2000,"withAudio":false}""").second)
        val (_, videoBytes, _) = get(videoUrl, token)
        assertTrue(videoBytes.size > 10_000)
        assertEquals("ftyp", String(videoBytes, 4, 4, Charsets.US_ASCII))

        // --- BLE GATT over MCP (live peer; read-only, no writes) ---
        val connectBody = toolText(tool(token, "ble_connect", """{"address":"$TEST_BLE_PEER"}""").second)
        val services = Json.parseToJsonElement(connectBody).jsonObject["services"]!!.jsonArray
        assertTrue("no GATT services listed", services.size > 0)
        val nameChar = services.mapNotNull { service ->
            val chars = service.jsonObject["characteristics"]!!.jsonArray
                .map { it.jsonPrimitive.content.lowercase() }
            if (chars.contains("00002a00-0000-1000-8000-00805f9b34fb")) {
                service.jsonObject["uuid"]!!.jsonPrimitive.content to "00002a00-0000-1000-8000-00805f9b34fb"
            } else {
                null
            }
        }.firstOrNull()
        if (nameChar != null) {
            val (serviceUuid, charUuid) = nameChar
            val readBody = toolText(
                tool(token, "ble_read", """{"address":"$TEST_BLE_PEER","serviceUuid":"$serviceUuid","charUuid":"$charUuid"}""").second,
            )
            val readObj = Json.parseToJsonElement(readBody).jsonObject
            assertTrue(readObj["hex"]!!.jsonPrimitive.content.isNotEmpty())
        }
        // Subscribe sweep: first notifiable characteristic must stream or explicitly refuse.
        val candidates = services.flatMap { service ->
            val uuid = service.jsonObject["uuid"]!!.jsonPrimitive.content
            service.jsonObject["characteristics"]!!.jsonArray.map { uuid to it.jsonPrimitive.content }
        }.take(10)
        var sawStream = false
        var sawUnsupported = false
        for ((serviceUuid, charUuid) in candidates) {
            val (code, body) = tool(
                token,
                "ble_subscribe",
                """{"address":"$TEST_BLE_PEER","serviceUuid":"$serviceUuid","charUuid":"$charUuid","durationMs":2000}""",
            )
            assertEquals(200, code)
            val root = Json.parseToJsonElement(body).jsonObject
            // Refusals arrive two ways: domain errors as result/isError, bad targets
            // (unknown char, no notify property) as JSON-RPC -32602 with no "result".
            // Both are explicit — only a silent/malformed reply fails the sweep.
            val rpcError = (root["error"] as? JsonObject)?.get("message")
                ?.jsonPrimitive?.content
            if (rpcError != null) {
                if (rpcError.contains("notify", ignoreCase = true)) sawUnsupported = true
                else throw AssertionError("unexpected subscribe failure: $body")
                continue
            }
            val result = root["result"]!!.jsonObject
            if (!result["isError"]!!.jsonPrimitive.content.toBoolean()) {
                val sub = Json.parseToJsonElement(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject
                assertTrue(sub.containsKey("count") && sub.containsKey("events"))
                sawStream = true
                break
            } else if (result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                    .contains(Regex("notify|cccd|descriptor", RegexOption.IGNORE_CASE))
            ) {
                sawUnsupported = true
            } else {
                throw AssertionError("unexpected subscribe failure: $body")
            }
        }
        assertTrue("neither streamed nor explicitly unsupported", sawStream || sawUnsupported)
        assertEquals(200, tool(token, "ble_disconnect", """{"address":"$TEST_BLE_PEER"}""").first)

        // --- NFC status over MCP (tap itself needs a human + foreground app; see device-matrix) ---
        val nfcStatus = toolText(tool(token, "nfc_status", "{}").second)
        assertTrue(nfcStatus.contains("present"))

        // --- SSE stream ---
        val events = streamBatches("sensors_stream", """{"durationMs":3000,"intervalMs":500,"includeLocation":false}""", token)
        assertTrue("expected ~12 batches, got ${events.first}", events.first >= 6)
        assertTrue(events.second)

        // --- SSE audio stream: decode one chunk, verify PCM shape ---
        val audio = streamBatches("audio_stream", """{"durationMs":3000,"chunkMs":1000}""", token)
        assertTrue("expected audio batches, got ${audio.first} (server error: $lastStreamError)", audio.first >= 2)
        assertTrue("audio final not ok (server error: $lastStreamError)", audio.second)
        val chunk = audio.third!!
        assertEquals(16000, chunk["sampleRateHz"]!!.jsonPrimitive.content.toInt())
        assertEquals("pcm_s16le", chunk["encoding"]!!.jsonPrimitive.content)
        val pcm = java.util.Base64.getDecoder().decode(chunk["dataBase64"]!!.jsonPrimitive.content)
        assertEquals(16000 * 2, pcm.size)
        val rms = chunk["rms"]!!.jsonPrimitive.content.toDouble()
        assertTrue("rms $rms out of range", rms in 0.0..1.0)



        if (com.phonemcp.server.service.PhoneMcpAccessibilityService.instance != null) {
            assertUiOverMcp(token)
        }

        // --- rotation kills the old token ---
        val rotated = TokenStore(context.applicationContext).rotate()
        assertTrue(rotated != token)
        assertEquals(401, call("tools/list", 3, "{}", token).first)
        assertEquals(200, call("tools/list", 4, "{}", rotated).first)
    }


    @Test
    fun uiOperateOverMcp() {
        // Effect-free UI coverage over loopback HTTPS (dump, screenshot, miss, gate).
        // Skipped when the accessibility service isn't bound: instrumentation restarts
        // kill the app process, which the system records as an unclean death and then
        // refuses to rebind for a while (see docs/testing.md).
        org.junit.Assume.assumeTrue(
            "accessibility service not bound on this run",
            com.phonemcp.server.service.PhoneMcpAccessibilityService.instance != null,
        )
        val token = TokenStore(context.applicationContext).ensure()
        assertUiOverMcp(token)
    }

    private fun assertUiOverMcp(token: String) {
        gatekeeper().setEnabled(Gatekeeper.TOOLS_UI, true)
        val dump = toolText(tool(token, "ui_dump", "{}").second)
        val nodes = Json.parseToJsonElement(dump).jsonObject["nodes"]!!.jsonArray
        assertTrue("empty UI dump", nodes.size > 0)
        val shotUrl = Json.parseToJsonElement(
            toolText(tool(token, "ui_screenshot", "{}").second),
        ).jsonObject["mediaUrl"]!!.jsonPrimitive.content
        val (_, shotBytes, _) = get(shotUrl, token)
        assertTrue(shotBytes.size > 10_000)
        assertEquals(0x89.toByte(), shotBytes[0])
        assertEquals('P'.code.toByte(), shotBytes[1])
        val miss = tool(token, "ui_click", """{"text":"zzz-no-such-element-zzz"}""")
        assertEquals(200, miss.first)
        assertTrue(Json.parseToJsonElement(miss.second).jsonObject["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
        gatekeeper().setEnabled(Gatekeeper.TOOLS_UI, false)
        val gated = tool(token, "ui_dump", "{}")
        assertTrue(Json.parseToJsonElement(gated.second).jsonObject["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
        gatekeeper().setEnabled(Gatekeeper.TOOLS_UI, true)
    }

    private fun mediaUrl(callJson: String): String =
        Json.parseToJsonElement(toolText(callJson)).jsonObject["mediaUrl"]!!.jsonPrimitive.content


    private fun streamBatches(tool: String, argsJson: String, token: String): Triple<Int, Boolean, JsonObject?> {
        val conn = URL("https://127.0.0.1:8080/mcp").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = sslFactory()
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 25000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
        conn.setRequestProperty("Authorization", "Bearer $token")
        val body = """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"$tool","arguments":$argsJson}}"""
        conn.outputStream.use { it.write(body.toByteArray()) }
        assertEquals(200, conn.responseCode)
        assertTrue(conn.contentType.orEmpty().contains("text/event-stream"))
        var data = 0
        var finalOk = false
        var firstBatch: JsonObject? = null
        var errorText = ""
        conn.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data: ")) continue
                data++
                val payload = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                if (payload.containsKey("result")) {
                    finalOk = payload["result"]!!.jsonObject["batches"]!!.jsonPrimitive.content.toInt() > 0
                    break
                }
                if (payload.containsKey("error")) {
                    errorText = payload["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                    break
                }
                if (firstBatch == null && payload.containsKey("params")) {
                    firstBatch = payload["params"]!!.jsonObject
                }
            }
        }
        conn.disconnect()
        lastStreamError = errorText
        return Triple(data, finalOk, firstBatch)
    }
}
