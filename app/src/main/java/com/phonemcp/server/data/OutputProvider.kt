package com.phonemcp.server.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.emptySchema
import com.phonemcp.server.mcp.objectSchema
import com.phonemcp.server.mcp.prop
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Outputs: vibrate (VIBRATE normal), torch (CAMERA), speaker/TTS, clipboard. */
@Singleton
class OutputProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {
    override val id = "output"
    override fun listTools() = listOf(
        ToolDefinition(
            "vibrate",
            "One-shot vibration. Args: durationMs (default 200, clamped 1..5000).",
            objectSchema(buildJsonObject { put("durationMs", prop("integer", "Vibration length in ms")) }),
        ),
        ToolDefinition(
            "torch",
            "Set camera flash on/off. Args: on (required boolean).",
            objectSchema(buildJsonObject { put("on", prop("boolean", "true = flash on")) }),
        ),
        ToolDefinition(
            "speaker_say",
            "Speak text via on-device TTS. Args: text (required, max 1000 chars), rate/pitch (default 1.0, clamped 0.5..2.0). " +
                "Returns spokeChars plus the active output route and music volume — check `output` when sound " +
                "seems to come from the wrong place (e.g. remote_submix means a screen-mirror like scrcpy is capturing device audio).",
            objectSchema(
                buildJsonObject {
                    put("text", prop("string", "Text to speak"))
                    put("rate", prop("number", "Speech rate multiplier"))
                    put("pitch", prop("number", "Pitch multiplier"))
                },
            ),
        ),
        ToolDefinition("clipboard_get", "Read clipboard text (foreground only on API 29+)", emptySchema()),
        ToolDefinition(
            "clipboard_set",
            "Write clipboard text (works headless). Args: text (required).",
            objectSchema(buildJsonObject { put("text", prop("string", "Text to copy")) }),
        ),
    )

    override suspend fun call(name: String, args: JsonObject): ToolCallResult {
        when (name) {
            "vibrate" -> {
                val durationMs = parseVibrateArgs(args)
                // VibratorManager exists only on API 31+; guard preserves minSdk 29.
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    null
                } ?: context.getSystemService(Vibrator::class.java)
                    ?: return ToolCallResult.error("Vibrator unavailable")
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                return ToolCallResult.text("vibrated ${durationMs}ms")
            }
            "speaker_say" -> {
                // parseSpeakArgs throws IllegalArgumentException on missing text → router maps to -32602.
                return speakOnce(parseSpeakArgs(args))
            }
            "clipboard_set" -> {
                val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                require(text.isNotEmpty()) { "Missing 'text'" }
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                    ?: return ToolCallResult.error("Clipboard unavailable")
                clipboard.setPrimaryClip(ClipData.newPlainText("mcp", text.take(CLIP_MAX)))
                return ToolCallResult.text("clipboard set (${minOf(text.length, CLIP_MAX)} chars)")
            }
            "clipboard_get" -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                    ?: return ToolCallResult.error("Clipboard unavailable")
                val clip = try {
                    clipboard.primaryClip
                } catch (e: SecurityException) {
                    return ToolCallResult.error("Clipboard read blocked (needs foreground on API 29+)")
                }
                val text = (0 until (clip?.itemCount ?: 0))
                    .asSequence()
                    .mapNotNull { clip?.getItemAt(it)?.coerceToText(context)?.toString() }
                    .firstOrNull()
                    ?: return ToolCallResult.error("Clipboard empty or unavailable (needs foreground on API 29+)")
                return ToolCallResult.text(text.take(CLIP_MAX))
            }
            "torch" -> {
                // parseTorchArgs throws IllegalArgumentException on missing 'on' → router maps to -32602.
                val on = parseTorchArgs(args)
                return setTorch(on)
            }
        }
        throw IllegalArgumentException("unknown tool $name")
    }

    private suspend fun speakOnce(request: SpeakRequest): ToolCallResult {        val ready = CompletableDeferred<Boolean>()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        val initialized = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ready.await() } ?: false
        if (!initialized) {
            engine?.shutdown()
            return ToolCallResult.error("TTS init failed or timed out")
        }
        val tts = engine ?: return ToolCallResult.error("TTS init failed")
        try {
            val language = tts.setLanguage(Locale.getDefault())
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                return ToolCallResult.error("TTS language not supported")
            }
            tts.setSpeechRate(request.rate)
            tts.setPitch(request.pitch)
            val utteranceId = UUID.randomUUID().toString()
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        if (id == utteranceId) done.complete(true)
                    }

                    override fun onError(id: String?) {
                        if (id == utteranceId) done.complete(false)
                    }
                },
            )
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            if (tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId) != TextToSpeech.SUCCESS) {
                return ToolCallResult.error("TTS speak rejected")
            }
            val finished = withTimeoutOrNull(TTS_SPEAK_TIMEOUT_MS) { done.await() } ?: false
            if (!finished) {
                return ToolCallResult.error("TTS speak timed out")
            }
            val audio = context.getSystemService(AudioManager::class.java)
            val outputTypes = try {
                audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type }?.toSet().orEmpty()
            } catch (_: Exception) {
                emptySet()
            }
            val route = describeOutput(
                outputTypes,
                btA2dpOn = try {
                    audio?.isBluetoothA2dpOn == true
                } catch (_: Exception) {
                    false
                },
                wiredOn = try {
                    audio?.isWiredHeadsetOn == true
                } catch (_: Exception) {
                    false
                },
            )
            return ToolCallResult.text(
                buildJsonObject {
                    put("spokeChars", request.text.length)
                    put("output", route)
                    put("musicVolume", audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1)
                    put("musicMax", audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1)
                }.toString(),
            )
        } finally {
            tts.shutdown()
        }
    }

    private fun setTorch(on: Boolean): ToolCallResult {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return ToolCallResult.error("Camera service unavailable")
        val id = try {
            manager.cameraIdList.firstOrNull { cameraId ->
                manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolCallResult.error("No flash unit on this device")
        } catch (e: CameraAccessException) {
            return ToolCallResult.error("Torch unavailable: ${e.message}")
        }
        return try {
            manager.setTorchMode(id, on)
            ToolCallResult.text("torch ${if (on) "on" else "off"}")
        } catch (e: SecurityException) {
            ToolCallResult.error("Camera permission missing for torch")
        } catch (e: IllegalArgumentException) {
            ToolCallResult.error("Torch unavailable: ${e.message}")
        } catch (e: CameraAccessException) {
            ToolCallResult.error("Torch unavailable: ${e.message}")
        }
    }

    companion object {
        const val CLIP_MAX = 10_000
        const val TTS_INIT_TIMEOUT_MS = 10_000L
        const val TTS_SPEAK_TIMEOUT_MS = 30_000L

        /** Pure arg parsing; JVM-test safe. */
        fun parseVibrateArgs(args: JsonObject): Long =
            ArgParser.long(args, "durationMs", 200L, 1L..5000L)

        data class SpeakRequest(val text: String, val rate: Float, val pitch: Float)

        /** Pure arg parsing; throws IllegalArgumentException on missing text. JVM-test safe. */
        fun parseSpeakArgs(args: JsonObject): SpeakRequest {
            val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            require(text.isNotEmpty()) { "Missing 'text'" }
            val rate = ((args["rate"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).coerceIn(0.5, 2.0).toFloat()
            val pitch = ((args["pitch"] as? JsonPrimitive)?.doubleOrNull ?: 1.0).coerceIn(0.5, 2.0).toFloat()
            return SpeakRequest(text.take(1000), rate, pitch)
        }
        /** Pure arg parsing; throws IllegalArgumentException on missing 'on'. JVM-test safe. */
        fun parseTorchArgs(args: JsonObject): Boolean {
            val on = (args["on"] as? JsonPrimitive)?.booleanOrNull
            require(on != null) { "Missing 'on' (boolean)" }
            return on
        }

        /**
         * Names the active music-stream route from output device types. Pure; JVM-test safe
         * (AudioDeviceInfo.TYPE_* are compile-time constants).
         */
        fun describeOutput(deviceTypes: Set<Int>, btA2dpOn: Boolean, wiredOn: Boolean): String {
            if (deviceTypes.contains(AudioDeviceInfo.TYPE_REMOTE_SUBMIX)) return "remote_submix(mirrored)"
            if (btA2dpOn || deviceTypes.contains(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) return "bt_a2dp"
            if (
                wiredOn || deviceTypes.contains(AudioDeviceInfo.TYPE_WIRED_HEADSET) ||
                deviceTypes.contains(AudioDeviceInfo.TYPE_WIRED_HEADPHONES) ||
                deviceTypes.contains(AudioDeviceInfo.TYPE_USB_HEADSET)
            ) return "wired"
            return "speaker"
        }
    }
}
