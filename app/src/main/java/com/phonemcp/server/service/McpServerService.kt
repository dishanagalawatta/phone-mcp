package com.phonemcp.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.Manifest
import com.phonemcp.server.BuildConfig
import com.phonemcp.server.domain.LogBuffer
import com.phonemcp.server.domain.MediaStore
import com.phonemcp.server.presentation.PhoneMcpWidget
import com.phonemcp.server.mcp.JsonRpcCodes
import com.phonemcp.server.mcp.McpProtocol
import com.phonemcp.server.mcp.McpRouter
import com.phonemcp.server.mcp.jsonRpcError
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.FileInputStream
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Foreground service hosting the Ktor Netty MCP endpoint (Netty: CIO has no server-SSL support).
 * Binds LAN-only; bearer-auth on /mcp, /media, /info (/healthz open) with Origin validation
 * + kill-switches (see ADR-0006/0009/0010).
 */
@AndroidEntryPoint
class McpServerService : Service() {

    @Inject lateinit var router: McpRouter

    @Inject lateinit var mediaStore: MediaStore

    @Inject lateinit var tokens: TokenStore

    @Inject lateinit var tls: TlsStore

    @Inject lateinit var log: LogBuffer

    @Inject lateinit var config: ServerConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One-shot scope for graceful engine shutdown; never cancelled with [scope]. */
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: EmbeddedServer<*, *>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // NOTE: running is set only in startServer()/onDestroy()/STOP — never here.
        // onCreate also runs for STOP-tap creations on a dead process; claiming
        // Running there produces a stuck UI (enabled Start that goes nowhere).
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            logEvent("server stop requested")
            // Clear promptly (onDestroy repeats it): the dashboard must not offer
            // a dead Start while the graceful shutdown drains.
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        // Headless restart (system killed process, intent == null) must not resume
        // camera/mic/location capture without a foreground user action (API 34 while-in-use).
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val notification = buildNotification()
        // API 34+ throws SecurityException if we claim a while-in-use type (camera,
        // microphone, location) without its runtime permission yet. Fresh installs
        // have granted nothing, so claim dataSync always and add each type only when
        // its permission is already granted. Types can be added later via a second
        // startForeground() once the user grants them — never crash the process.
        var types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (hasRuntime(Manifest.permission.RECORD_AUDIO)) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (hasRuntime(Manifest.permission.CAMERA)) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (hasRuntime(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasRuntime(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        // connectedDevice bits are unknown to API 29 runtimes; add them only where defined.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            hasRuntime(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        try {
            startForeground(NOTIFICATION_ID, notification, types)
        } catch (e: SecurityException) {
            logEvent("startForeground refused (${e.message}); grant runtime perms then retry")
            stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        sendBroadcast(PhoneMcpWidget.refreshIntent(this))
        // Never auto-resume after a kill: the user restarts from dashboard/tile (foreground).
        return START_NOT_STICKY
    }

    private fun startServer() {
        if (server != null) return
        // TLS identity first: fail closed (no cleartext fallback) when it cannot be built.
        val identity = try {
            tls.loadOrCreate()
        } catch (e: Exception) {
            logEvent("TLS init failed (${e.javaClass.simpleName}: ${e.message}); refusing to serve cleartext")
            stopSelf()
            return
        }
        logEvent("server starting on https://${config.host}:${config.port}")
        logEvent("TLS fingerprint SHA-256: ${Pairing.groupedFingerprint(identity.fingerprint)}")
        server = embeddedServer(
            Netty,
            configure = {
                sslConnector(
                    identity.keyStore,
                    TlsStore.KEY_ALIAS,
                    { identity.password },
                    { identity.password },
                ) {
                    host = config.host
                    port = config.port
                }
            },
        ) {
            install(ContentNegotiation) { json() }
            routing {
                post("/mcp") { serveMcp(call) }
                get("/mcp") {
                    // Auth first: /mcp is a protected endpoint even for wrong-method probes.
                    if (!checkAuth(call, "GET /mcp")) return@get
                    call.response.header("Allow", "POST")
                    respondJson(
                        call,
                        HttpStatusCode.MethodNotAllowed,
                        jsonRpcError(null, JsonRpcCodes.INVALID_REQUEST, "Use POST /mcp"),
                    )
                }
                get("/media/{id}") { serveMedia(call) }
                get("/mcp/events") { serveEvents(call) }
                get("/healthz") {
                    call.respondText("{\"ok\":true}", ContentType.Application.Json)
                }
                get("/info") { serveInfo(call) }
            }
        }
        // Truthful run flag: set only once the engine object exists (not in onCreate,
        // which also runs for STOP-tap creations on a dead process). Bind failures
        // below route through stopSelf()/onDestroy() which clears it again.
        running = true
        scope.launch {
            try {
                server?.start(wait = false)
            } catch (e: Throwable) {
                // Engine bind/start failures (incl. linkage Errors on shrunk builds) must fail
                // the service, never the process.
                logEvent("server start failed (${e.javaClass.simpleName}: ${e.message})")
                stopSelf()
            }
        }
    }

    private suspend fun serveMcp(call: ApplicationCall) {
        try {
            if (!McpHttp.isOriginAllowed(call.request.header("Origin"), call.request.host())) {
                logEvent("POST /mcp → 403 origin blocked")
                return respondJson(
                    call,
                    HttpStatusCode.Forbidden,
                    jsonRpcError(null, JsonRpcCodes.INVALID_REQUEST, "Origin not allowed"),
                )
            }
            if (!checkAuth(call, "POST /mcp")) return
            // Declared length is a fast-path only: missing/chunked length still bounded
            // by the receive cap below (Ktor limits + 1 MiB policy). Never trust it alone.
            val declaredLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (McpHttp.isBodyTooLarge(declaredLength)) {
                logEvent("POST /mcp → 413 body too large")
                return respondJson(
                    call,
                    HttpStatusCode.PayloadTooLarge,
                    jsonRpcError(null, JsonRpcCodes.INVALID_REQUEST, "Body too large"),
                )
            }
            val body = try {
                // Bound parse RAM: reject oversized JSON before full materialization where possible.
                // Ktor's receive has no byte cap here, so McpHttp.MAX_MCP_BODY_BYTES is enforced
                // on the wire via Content-Length fast-path + downstream arg clamps (clipboard 10k).
                call.receive<JsonElement>()
            } catch (e: Exception) {
                logEvent("POST /mcp → 400 parse error")
                return respondJson(
                    call,
                    HttpStatusCode.BadRequest,
                    jsonRpcError(null, JsonRpcCodes.PARSE_ERROR, "Parse error"),
                )
            }
            if (body is JsonArray) {
                logEvent("POST /mcp → 400 batch rejected")
                return respondJson(
                    call,
                    HttpStatusCode.BadRequest,
                    jsonRpcError(null, JsonRpcCodes.INVALID_REQUEST, "Batches not supported"),
                )
            }
            if (body !is JsonObject) {
                logEvent("POST /mcp → 400 parse error")
                return respondJson(
                    call,
                    HttpStatusCode.BadRequest,
                    jsonRpcError(null, JsonRpcCodes.PARSE_ERROR, "Parse error"),
                )
            }
            val method = (body["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (method != "initialize" && !method.startsWith("notifications/")) {
                if (!McpHttp.isVersionHeaderValid(call.request.header("MCP-Protocol-Version"))) {
                    logEvent("POST /mcp $method → 400 bad version")
                    return respondJson(
                        call,
                        HttpStatusCode.BadRequest,
                        jsonRpcError(
                            body["id"],
                            JsonRpcCodes.INVALID_REQUEST,
                            "Missing or unsupported MCP-Protocol-Version",
                        ),
                    )
                }
            }
            if (method == "tools/call" && router.isStreamable(streamToolName(body))) {
                val streamName = streamToolName(body)
                if (!call.request.header("Accept").orEmpty().contains("text/event-stream")) {
                    logEvent("POST /mcp tools/call $streamName → 400 needs SSE accept")
                    return respondJson(
                        call,
                        HttpStatusCode.BadRequest,
                        jsonRpcError(
                            body["id"],
                            JsonRpcCodes.INVALID_PARAMS,
                            "$streamName requires Accept: text/event-stream",
                        ),
                    )
                }
                return serveStream(call, body)
            }
            val response = try {
                router.handle(body)
            } catch (e: Exception) {
                logEvent("POST /mcp $method → 500 router crash")
                return respondJson(
                    call,
                    HttpStatusCode.InternalServerError,
                    jsonRpcError(body["id"], JsonRpcCodes.INTERNAL_ERROR, "Internal error"),
                )
            }
            if (response == null) {
                logEvent("POST /mcp $method → 202 notification")
                call.respond(HttpStatusCode.Accepted)
                return
            }
            logEvent("POST /mcp ${describe(body)} → 200")
            respondJson(call, HttpStatusCode.OK, response)
        } catch (e: Exception) {
            try {
                respondJson(
                    call,
                    HttpStatusCode.InternalServerError,
                    jsonRpcError(null, JsonRpcCodes.INTERNAL_ERROR, "Internal error"),
                )
            } catch (_: Exception) {
                // Response already committed; nothing left to do.
            }
        }
    }

    private suspend fun serveMedia(call: ApplicationCall) {
        if (!checkAuth(call, "GET /media")) return
        val id = call.parameters["id"].orEmpty()
        if (!MEDIA_ID.matches(id)) {
            logEvent("GET /media/… → 404 bad id")
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return
        }
        val file = withContext(Dispatchers.IO) { mediaStore.get(id)?.takeIf { it.isFile } }
        if (file == null) {
            logEvent("GET /media/$id → 404")
            call.respondText("Not found", status = HttpStatusCode.NotFound)
            return
        }
        val contentType = try {
            ContentType.parse(McpHttp.mimeTypeFor(file.name))
        } catch (_: Exception) {
            ContentType.Application.OctetStream
        }
        val length = withContext(Dispatchers.IO) { file.length() }
        val range = McpHttp.parseRange(call.request.header("Range"))
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (range != null) {
            val start = range.start
            val end = (range.endInclusive ?: (length - 1)).coerceAtMost(length - 1)
            if (start >= length || end < start) {
                call.response.header(HttpHeaders.ContentRange, "bytes */$length")
                logEvent("GET /media/$id → 416")
                call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                return
            }
            val count = end - start + 1
            call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$length")
            logEvent("GET /media/$id → 206 range $start-$end")
            call.respondOutputStream(contentType, HttpStatusCode.PartialContent) {
                FileInputStream(file).use { input ->
                    var skipped = 0L
                    while (skipped < start) {
                        val step = input.skip(start - skipped)
                        if (step <= 0) break
                        skipped += step
                    }
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = count
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            return
        }
        call.respondOutputStream(contentType, HttpStatusCode.OK) {
            logEvent("GET /media/$id → 200 ${file.length()}B")
            file.inputStream().use { it.copyTo(this) }
        }
    }

    private suspend fun serveInfo(call: ApplicationCall) {
        if (!checkAuth(call, "GET /info")) return
        val info = router.serverInfo()
        respondJson(
            call,
            HttpStatusCode.OK,
            buildJsonObject {
                put("name", info["name"] ?: JsonPrimitive(McpProtocol.SERVER_NAME))
                put("version", info["version"] ?: JsonPrimitive(McpProtocol.SERVER_VERSION))
                put("protocolVersion", info["protocolVersion"] ?: JsonPrimitive(McpProtocol.LATEST_VERSION))
                put("tools", router.listTools().size)
                put("externalTools", info["externalTools"] ?: JsonPrimitive(0))
                put("externalCapabilities", info["externalCapabilities"] ?: JsonPrimitive(0))
                put("capabilitiesVersion", info["capabilitiesVersion"] ?: JsonPrimitive(0L))
            },
        )
    }

    private suspend fun respondJson(call: ApplicationCall, status: HttpStatusCode, body: JsonObject) {
        call.respondText(Json.encodeToString(JsonObject.serializer(), body), ContentType.Application.Json, status)
    }

    /**
     * Dashboard ring + logcat mirror. Logcat is debug-gated, or enabled per-tag in any build
     * via `adb shell setprop log.tag.PhoneMcp INFO`. Never carries tokens or argument values.
     */
    private fun logEvent(event: String) {
        log.append(event)
        if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.INFO)) Log.i(TAG, event)
    }

    /**
     * Bearer gate for every data endpoint (/healthz open).
     * Read-only on the hot path: uses current() (no auto-create, no file-lock churn).
     * Responds 401 + JSON-RPC -32001 on failure. The token value is never logged.
     */
    private suspend fun checkAuth(call: ApplicationCall, label: String): Boolean {
        val presented = AuthToken.extract(call.request.header(HttpHeaders.Authorization))
        // Hot-path must not create secrets: missing store => unauthorized (UI calls ensure()).
        val expected = try {
            tokens.current()
        } catch (_: Exception) {
            null
        }
        val ok = presented != null && expected != null &&
            java.security.MessageDigest.isEqual(
                presented.toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8),
            )
        if (!ok) {
            logEvent("$label → 401")
            respondJson(
                call,
                HttpStatusCode.Unauthorized,
                jsonRpcError(null, JsonRpcCodes.UNAUTHORIZED, "Missing or invalid bearer token"),
            )
        }
        return ok
    }

    /** One-line log summary without argument values (arg values stay out of the log). */
    private fun describe(body: JsonObject): String {
        val method = (body["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (method != "tools/call") return method
        return "$method ${streamToolName(body)}"
    }

    private fun streamToolName(body: JsonObject): String {
        val params = body["params"] as? JsonObject ?: return ""
        return (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    }

    /**
     * Serves tools/call sensors_stream as Content-Type: text/event-stream:
     * notification envelopes per batch, final result envelope last, then close.
     * Client disconnect cancels the router collection (busy flag cleared in its finally).
     */
    private suspend fun serveStream(call: ApplicationCall, body: JsonObject) {
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        val streamName = streamToolName(body)
        logEvent("POST /mcp tools/call $streamName → 200 sse")
        try {
            call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                val final = router.handleStream(body) { notification ->
                    write("data: ")
                    write(Json.encodeToString(JsonObject.serializer(), notification))
                    write("\n\n")
                    flush()
                }
                write("data: ")
                write(Json.encodeToString(JsonObject.serializer(), final))
                write("\n\n")
                flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Client went away mid-stream (or writer failed); not an app error.
            logEvent("POST /mcp ${streamToolName(body)} closed")
        }
    }

    /**
     * Serves `GET /mcp/events` as `Content-Type: text/event-stream`: clients
     * hold this open to receive `notifications/tools/list_changed` whenever
     * the external capability registry changes (MCP 2025-06-18 §list-changed).
     * Bearer-gated like every data endpoint; `:ping` comments every 25 s keep
     * intermediaries from idling the connection out. Client disconnect ends
     * the stream (no server-side state per subscriber).
     */
    private suspend fun serveEvents(call: ApplicationCall) {
        if (!checkAuth(call, "GET /mcp/events")) return
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        logEvent("GET /mcp/events → 200 sse")
        try {
            call.respondTextWriter(ContentType.Text.EventStream, HttpStatusCode.OK) {
                val writeMutex = Mutex()
                suspend fun send(frame: String) = writeMutex.withLock {
                    write(frame)
                    flush()
                }
                send(": connected\n\n")
                val beats = CoroutineScope(currentCoroutineContext()).launch {
                    while (true) {
                        delay(EVENT_HEARTBEAT_MS)
                        try {
                            send(": ping\n\n")
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
                try {
                    router.externalChanges().collect { notification ->
                        send(
                            "data: " + Json.encodeToString(JsonObject.serializer(), notification) + "\n\n",
                        )
                    }
                } finally {
                    beats.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Client went away (or writer failed); not an app error.
            logEvent("GET /mcp/events closed")
        }
    }

    override fun onDestroy() {
        logEvent("server stopped")
        // Snapshot + clear synchronously so a rapid re-Start never observes a
        // half-stopped engine (stale Running flag / startServer early-return).
        // The blocking graceful stop runs off the main thread (no ANR window).
        val stopping = server
        server = null
        running = false
        sendBroadcast(PhoneMcpWidget.refreshIntent(this))
        shutdownScope.launch {
            try {
                stopping?.stop(500, 1500)
            } catch (_: Exception) {
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phone MCP", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun hasRuntime(permission: String): Boolean =
        try {
            androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone MCP running")
            .setContentText("LAN Streamable HTTPS on port ${config.port} — bearer token required")
            .setSmallIcon(com.phonemcp.server.R.drawable.ic_notification)
            .build()

    companion object {
        const val CHANNEL_ID = "phone_mcp"
        const val NOTIFICATION_ID = 41
        const val ACTION_START = "com.phonemcp.server.START"
        const val ACTION_STOP = "com.phonemcp.server.STOP"

        /** SSE comment cadence on GET /mcp/events (idle-connection keepalive). */
        const val EVENT_HEARTBEAT_MS = 25_000L
        private const val TAG = "PhoneMcp"
        private val MEDIA_ID = Regex("[A-Za-z0-9-]+")

        /** Same-process run flag, read by the dashboard. */
        @Volatile
        var running = false
            private set
    }
}
