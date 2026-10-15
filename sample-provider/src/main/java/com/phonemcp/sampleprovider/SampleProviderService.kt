package com.phonemcp.sampleprovider

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.json.JSONObject
import java.time.Instant

/**
 * Sample third-party capability endpoint for Phone MCP.
 *
 * Speaks the bound-service capability protocol with framework APIs only
 * (normative contract: phone-mcp `mcp/CapabilityIpcContract.kt` — the string
 * literals below must match it). Phone MCP binds this service with an
 * explicit package intent, sends MSG_DESCRIBE/MSG_INVOKE, and reads
 * MSG_RESULT replies. Every reply is bounded, synchronous Binder work; a
 * real provider would move heavy tools off the main thread.
 */
class SampleProviderService : Service() {

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            answer(message)
            true
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun answer(message: Message) {
        val replyTo = message.replyTo ?: return
        val reply = Message.obtain(null, MSG_RESULT)
        reply.data = try {
            when (message.what) {
                MSG_DESCRIBE -> Bundle().apply {
                    putBoolean(KEY_OK, true)
                    putString(KEY_DESCRIPTOR_JSON, DESCRIPTOR_JSON)
                }
                MSG_INVOKE -> {
                    val tool = message.data?.getString(KEY_TOOL).orEmpty()
                    val args = message.data?.getString(KEY_ARGS_JSON).orEmpty()
                    val (ok, text) = invoke(tool, args)
                    Bundle().apply {
                        putBoolean(KEY_OK, ok)
                        if (ok) putString(KEY_RESULT_TEXT, text) else putString(KEY_ERROR, text)
                    }
                }
                else -> Bundle().apply {
                    putBoolean(KEY_OK, false)
                    putString(KEY_ERROR, "unknown message ${message.what}")
                }
            }
        } catch (e: Exception) {
            Bundle().apply {
                putBoolean(KEY_OK, false)
                putString(KEY_ERROR, e.message ?: "provider failed")
            }
        }
        try {
            replyTo.send(reply)
        } catch (_: Exception) {
            // Caller went away; nothing to do.
        }
    }

    private fun invoke(tool: String, argsJson: String): Pair<Boolean, String> {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            return false to "args must be a JSON object"
        }
        return when (tool) {
            "echo" -> {
                if (!args.has("text")) return false to "missing 'text'"
                true to args.optString("text").take(MAX_ECHO)
            }
            "add" -> {
                if (!args.has("a") || !args.has("b")) return false to "missing 'a'/'b'"
                try {
                    true to (args.getDouble("a") + args.getDouble("b")).toString()
                } catch (_: Exception) {
                    false to "'a'/'b' must be numbers"
                }
            }
            "time" -> true to Instant.now().toString()
            else -> false to "unknown tool $tool"
        }
    }

    companion object {
        // Must match phone-mcp CapabilityIpcContract (copied: no shared dep by design).
        const val ACTION_PROVIDE = "com.phonemcp.server.action.PROVIDE_CAPABILITY"
        const val MSG_DESCRIBE = 1
        const val MSG_INVOKE = 2
        const val MSG_RESULT = 3
        const val KEY_TOOL = "tool"
        const val KEY_ARGS_JSON = "argsJson"
        const val KEY_DESCRIPTOR_JSON = "descriptorJson"
        const val KEY_OK = "ok"
        const val KEY_RESULT_TEXT = "resultText"
        const val KEY_ERROR = "error"

        const val MAX_ECHO = 1000

        const val DESCRIPTOR_JSON =
            "{\"capabilityId\":\"demo\",\"displayName\":\"Sample Provider\",\"version\":\"1.0\"," +
                "\"tools\":[" +
                "{\"name\":\"echo\",\"description\":\"Echoes text back (up to 1000 chars)\"," +
                "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"description\":\"Text to echo\"}},\"required\":[\"text\"]}}," +
                "{\"name\":\"add\",\"description\":\"Adds two numbers\"," +
                "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\",\"description\":\"First operand\"},\"b\":{\"type\":\"number\",\"description\":\"Second operand\"}},\"required\":[\"a\",\"b\"]}}," +
                "{\"name\":\"time\",\"description\":\"Current device time as ISO-8601\"," +
                "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}"
    }
}
