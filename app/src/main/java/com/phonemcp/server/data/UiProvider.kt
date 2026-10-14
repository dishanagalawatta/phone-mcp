package com.phonemcp.server.data

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.phonemcp.server.domain.MediaStore
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.emptySchema
import com.phonemcp.server.mcp.objectSchema
import com.phonemcp.server.mcp.prop
import com.phonemcp.server.service.PhoneMcpAccessibilityService
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Operate-the-phone tools over the accessibility service. Every tool requires the service
 * bound (user enabled it in Settings) and the `ui_` kill-switch on. Text the agent types
 * and reads is never written to the log (only tool names are logged).
 */
@Singleton
class UiProvider @Inject constructor(
    private val mediaStore: MediaStore,
) : ToolProvider {
    override val id = "ui"

    sealed interface ClickTarget {
        data class Point(val x: Int, val y: Int) : ClickTarget
        data class Text(val text: String) : ClickTarget
        data class Id(val id: String) : ClickTarget
    }

    data class SwipeRequest(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Long)
    data class TypeRequest(val text: String, val selectorText: String?, val resourceId: String?)
    enum class UiKey { BACK, HOME, RECENTS }

    override fun listTools() = listOf(
        ToolDefinition(
            "ui_screenshot",
            "Capture the current screen as PNG, returns a /media URL. Needs API 30+.",
            emptySchema(),
        ),
        ToolDefinition(
            "ui_dump",
            "Flat list of on-screen elements (index, text, description, id, bounds, flags). " +
                "Password fields are redacted unless includeSensitive is true. " +
                "Args: includeSensitive (default false).",
            objectSchema(
                buildJsonObject {
                    put("includeSensitive", prop("boolean", "Include password field contents"))
                },
            ),
        ),
        ToolDefinition(
            "ui_click",
            "Tap an element by exactly one of: x/y, visible text (contains), or resource-id. " +
                "Args: x, y (integers) | text (string) | resourceId (string).",
            objectSchema(
                buildJsonObject {
                    put("x", prop("integer", "Screen X"))
                    put("y", prop("integer", "Screen Y"))
                    put("text", prop("string", "Visible-text substring"))
                    put("resourceId", prop("string", "View resource-id"))
                },
            ),
        ),
        ToolDefinition(
            "ui_swipe",
            "Swipe gesture. Args: x1, y1, x2, y2 (required integers), durationMs (default 300, clamped 50..2000).",
            objectSchema(
                buildJsonObject {
                    put("x1", prop("integer", "Start X"))
                    put("y1", prop("integer", "Start Y"))
                    put("x2", prop("integer", "End X"))
                    put("y2", prop("integer", "End Y"))
                    put("durationMs", prop("integer", "Gesture duration"))
                },
            ),
        ),
        ToolDefinition(
            "ui_type",
            "Type text into an editable field (by text/resourceId selector, or the focused field). " +
                "Args: text (required, max 1000 chars), selectorText?, resourceId?.",
            objectSchema(
                buildJsonObject {
                    put("text", prop("string", "Text to type"))
                    put("selectorText", prop("string", "Field hint/label substring"))
                    put("resourceId", prop("string", "Field resource-id"))
                },
            ),
        ),
        ToolDefinition(
            "ui_key",
            "System key. Args: key one of back|home|recents.",
            objectSchema(
                buildJsonObject {
                    put("key", prop("string", "back, home, or recents"))
                },
            ),
        ),
    )

    override suspend fun call(name: String, args: JsonObject): ToolCallResult {
        val service = PhoneMcpAccessibilityService.instance
            ?: return ToolCallResult.error(
                "Accessibility service not enabled (Settings → Accessibility → Phone MCP)",
            )
        return try {
            when (name) {
                "ui_screenshot" -> screenshot(service)
                "ui_dump" -> UiTree.dump(service, parseDumpArgs(args))
                "ui_click" -> click(service, parseClickArgs(args))
                "ui_swipe" -> swipe(service, parseSwipeArgs(args))
                "ui_type" -> type(service, parseTypeArgs(args))
                "ui_key" -> key(service, parseKeyArg(args))
                else -> throw IllegalArgumentException("unknown tool $name")
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            ToolCallResult.error("UI action failed: ${e.message}")
        }
    }

    private suspend fun screenshot(service: PhoneMcpAccessibilityService): ToolCallResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ToolCallResult.error("Screen capture needs Android 11+ (API 30)")
        }
        return screenshotApi30(service)
    }

    // Split out so API-29 runtimes never verify the API-30 callback class:
    // the guarded call above is the only entry point (NewApi-clean).
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun screenshotApi30(service: PhoneMcpAccessibilityService): ToolCallResult {
        val result = try {
            withTimeout(SCREENSHOT_TIMEOUT_MS) {
                suspendCancellableCoroutine<AccessibilityService.ScreenshotResult> { cont ->
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        service.mainExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                                cont.resume(screenshot) { }
                            }

                            override fun onFailure(errorCode: Int) {
                                cont.resumeWithException(IllegalStateException("Screenshot failed (code $errorCode)"))
                            }
                        },
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            return ToolCallResult.error("Screenshot timed out")
        }
        val bitmap = try {
            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                ?: return ToolCallResult.error("Screenshot decode failed")
        } finally {
            try {
                result.hardwareBuffer.close()
            } catch (_: Exception) {
            }
        }
        try {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)) {
                return ToolCallResult.error("PNG encode failed")
            }
            val bytes = out.toByteArray()
            val ref = mediaStore.put(bytes, "png", "image/png")
            return ToolCallResult.text(
                buildJsonObject {
                    put("mediaUrl", ref.urlPath)
                    put("mimeType", ref.mimeType)
                    put("width", bitmap.width)
                    put("height", bitmap.height)
                    put("sizeBytes", bytes.size)
                }.toString(),
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun click(service: PhoneMcpAccessibilityService, target: ClickTarget): ToolCallResult {
        val node = when (target) {
            is ClickTarget.Point -> UiTree.findAt(service, target.x, target.y)
            is ClickTarget.Text -> UiTree.findByText(service, target.text)
            is ClickTarget.Id -> UiTree.findById(service, target.id)
        } ?: return ToolCallResult.error("No element found for $target")
        return try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                ToolCallResult.text("clicked ${UiTree.describeNode(node)}")
            } else {
                ToolCallResult.error("Element refused the click action")
            }
        } finally {
            try {
                node.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun swipe(service: PhoneMcpAccessibilityService, request: SwipeRequest): ToolCallResult {
        val path = android.graphics.Path().apply {
            moveTo(request.x1.toFloat(), request.y1.toFloat())
            lineTo(request.x2.toFloat(), request.y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, request.durationMs)
        val done = CompletableDeferred<Boolean>()
        val dispatched = service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.complete(false)
                }
            },
            null,
        )
        if (!dispatched) return ToolCallResult.error("Gesture rejected (another gesture in flight?)")
        val ok = try {
            withTimeout(GESTURE_TIMEOUT_MS) { done.await() }
        } catch (e: TimeoutCancellationException) {
            return ToolCallResult.error("Swipe timed out")
        }
        return if (ok) ToolCallResult.text("swiped") else ToolCallResult.error("Swipe cancelled")
    }

    private fun type(service: PhoneMcpAccessibilityService, request: TypeRequest): ToolCallResult {
        val root = service.rootInActiveWindow
            ?: return ToolCallResult.error("No active window (wake the device and retry)")
        val node = when {
            request.selectorText != null -> UiTree.findByText(service, request.selectorText)
            request.resourceId != null -> UiTree.findById(service, request.resourceId)
            else -> try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (_: Exception) {
                null
            }
        } ?: return ToolCallResult.error("No editable field found")
        return try {
            if (!node.isEditable) return ToolCallResult.error("Target field is not editable")
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    request.text,
                )
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                ToolCallResult.text("typed ${request.text.length} chars")
            } else {
                ToolCallResult.error("Field refused the text")
            }
        } finally {
            try {
                node.recycle()
            } catch (_: Exception) {
            }
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun key(service: PhoneMcpAccessibilityService, key: UiKey): ToolCallResult {
        val action = when (key) {
            UiKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            UiKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            UiKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        }
        return if (service.performGlobalAction(action)) {
            ToolCallResult.text("pressed ${key.name.lowercase()}")
        } else {
            ToolCallResult.error("System refused the key press")
        }
    }

    companion object {
        const val MAX_NODES = 500
        const val MAX_DEPTH = 12
        const val GESTURE_TIMEOUT_MS = 10_000L
        const val SCREENSHOT_TIMEOUT_MS = 10_000L

        /** Exactly one of x/y, text, resourceId. Pure; JVM-test safe. */
        fun parseClickArgs(args: JsonObject): ClickTarget {
            val x = (args["x"] as? JsonPrimitive)?.content?.toLongOrNull()
            val y = (args["y"] as? JsonPrimitive)?.longOrNull
            val hasPoint = x != null || y != null || args.containsKey("x") || args.containsKey("y")
            val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            val id = (args["resourceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            val count = (if (hasPoint) 1 else 0) + (if (text != null) 1 else 0) + (if (id != null) 1 else 0)
            require(count == 1) { "Provide exactly one of x/y, text, resourceId" }
            if (text != null) return ClickTarget.Text(text)
            if (id != null) return ClickTarget.Id(id)
            val xi = (args["x"] as? JsonPrimitive)?.content?.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid x")
            val yi = (args["y"] as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("Invalid y")
            require(xi in 0..10000 && yi in 0..10000) { "Coordinates out of range" }
            return ClickTarget.Point(xi.toInt(), yi.toInt())
        }

        /** Pure arg parsing; throws IllegalArgumentException. JVM-test safe. */
        fun parseSwipeArgs(args: JsonObject): SwipeRequest {
            fun coord(key: String): Int {
                val v = (args[key] as? JsonPrimitive)?.content?.toLongOrNull()
                    ?: throw IllegalArgumentException("Missing or invalid $key")
                require(v in 0..10000) { "$key out of range" }
                return v.toInt()
            }
            val durationMs = ArgParser.long(args, "durationMs", 300L, 50L..2000L)
            return SwipeRequest(coord("x1"), coord("y1"), coord("x2"), coord("y2"), durationMs)
        }

        /** Pure arg parsing; throws IllegalArgumentException. JVM-test safe. */
        fun parseTypeArgs(args: JsonObject): TypeRequest {
            val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            require(text.isNotEmpty()) { "Missing 'text'" }
            val selector = (args["selectorText"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            val id = (args["resourceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            return TypeRequest(text.take(1000), selector, id)
        }

        /** Pure arg parsing; throws IllegalArgumentException. JVM-test safe. */
        fun parseKeyArg(args: JsonObject): UiKey {
            val key = (args["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.uppercase()
            return try {
                UiKey.valueOf(key ?: "")
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid key (back|home|recents)")
            }
        }

        fun parseDumpArgs(args: JsonObject): Boolean =
            ArgParser.boolean(args, "includeSensitive", false)

        /**
         * Password-field detection from node flags. Pure; JVM-test safe
         * (EditorInfo constants are compile-time inlined).
         */
        fun isSensitiveInput(inputType: Int, isPasswordFlag: Boolean): Boolean {
            if (isPasswordFlag) return true
            val variation = inputType and android.view.inputmethod.EditorInfo.TYPE_MASK_VARIATION
            return variation == android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == android.view.inputmethod.EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
        }

        /** Node-level redaction check pairing [isSensitiveInput] with the framework flag. */
        fun isSensitive(node: AccessibilityNodeInfo): Boolean {
            val inputType = try {
                node.inputType
            } catch (_: Exception) {
                android.view.inputmethod.EditorInfo.TYPE_NULL
            }
            val passwordFlag = try {
                node.isPassword
            } catch (_: Exception) {
                false
            }
            return isSensitiveInput(inputType, passwordFlag)
        }
    }
}
