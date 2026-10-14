package com.phonemcp.server.data

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.phonemcp.server.domain.MediaStore
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.objectSchema
import com.phonemcp.server.mcp.prop
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.sqrt

/** Mic clips via MediaRecorder → MediaStore. Needs RECORD_AUDIO + microphone FGS (held by the service). */
@Singleton
class AudioProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStore: MediaStore,
) : ToolProvider {
    override val id = "audio"
    override fun listTools() = listOf(
        ToolDefinition(
            "audio_record",
            "Record a mic clip (AAC in .m4a), returns a /media URL. Args: durationMs (default 5000, clamped 1000..30000).",
            objectSchema(
                buildJsonObject {
                    put("durationMs", prop("integer", "Recording length in ms"))
                },
            ),
        ),
        ToolDefinition(
            "audio_stream",
            "Live mic stream as base64 PCM batches over SSE. Requires Accept: text/event-stream. " +
                "Args: durationMs total cap (default 10000, clamped 2000..60000), chunkMs audio per event " +
                "(default 1000, clamped 250..5000). One stream at a time; client disconnect cancels.",
            objectSchema(
                buildJsonObject {
                    put("durationMs", prop("integer", "Total stream cap in ms"))
                    put("chunkMs", prop("integer", "Audio milliseconds per SSE event"))
                },
            ),
        ),
    )

    override suspend fun call(name: String, args: JsonObject): ToolCallResult {
        require(name == "audio_record") {
            if (name == "audio_stream") {
                "audio_stream requires SSE (POST /mcp with Accept: text/event-stream)"
            } else {
                "unknown tool $name"
            }
        }
        val durationMs = parseArgs(args)
        val tmp = File.createTempFile("mic-", ".m4a", context.cacheDir)
        var staged = false
        // MediaRecorder(context) exists only on API 31+; guard preserves minSdk 29.
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(tmp.absolutePath)
                prepare()
                start()
            }
            delay(durationMs)
            try {
                recorder.stop()
            } catch (e: RuntimeException) {
                return ToolCallResult.error("Mic record failed: ${e.message}")
            }
            val ref = mediaStore.stage(tmp, "m4a", "audio/mp4")
            staged = true
            val size = mediaStore.get(ref.id)?.length() ?: -1
            return ToolCallResult.text(
                buildJsonObject {
                    put("mediaUrl", ref.urlPath)
                    put("mimeType", ref.mimeType)
                    put("durationMs", durationMs)
                    put("sizeBytes", size)
                }.toString(),
            )
        } catch (e: SecurityException) {
            return ToolCallResult.error("Mic permission missing")
        } catch (e: IOException) {
            return ToolCallResult.error("Mic record failed: ${e.message}")
        } catch (e: RuntimeException) {
            return ToolCallResult.error("Mic record failed: ${e.message}")
        } finally {
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            if (!staged) tmp.delete()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** Pure arg parsing; JVM-test safe. */
        fun parseArgs(args: JsonObject): Long =
            ArgParser.long(args, "durationMs", 5000L, 1000L..30_000L)

        data class StreamRequest(val durationMs: Long, val chunkMs: Long)

        /** Pure arg parsing; JVM-test safe. */
        fun parseStreamArgs(args: JsonObject): StreamRequest {
            val durationMs = ArgParser.long(args, "durationMs", 10_000L, 2000L..60_000L)
            val chunkMs = ArgParser.long(args, "chunkMs", 1000L, 250L..5000L)
            return StreamRequest(durationMs, chunkMs)
        }

        /** Normalized RMS level 0..1 over the first [count] samples. Pure; JVM-test safe. */
        fun rms(samples: ShortArray, count: Int): Double {
            if (count <= 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) {
                val v = samples[i] / 32768.0
                sum += v * v
            }
            return sqrt(sum / count).coerceIn(0.0, 1.0)
        }
    }

    override fun supportsStreaming(name: String): Boolean = name == "audio_stream"

    override fun streamNotificationMethod(name: String): String = "notifications/audio"

    override fun openStream(name: String, args: JsonObject): Flow<JsonObject> = flow {
        require(name == "audio_stream") { "unknown stream $name" }
        val request = parseStreamArgs(args) // throws IllegalArgumentException on bad args
        val samplesPerChunk = (SAMPLE_RATE * request.chunkMs / 1000).toInt()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "Mic unavailable on this device" }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, samplesPerChunk * 2),
            )
        } catch (e: SecurityException) {
            throw SecurityException("Mic permission missing")
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Mic unavailable: ${e.message}")
        }
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Mic failed to start")
            }
            val slice = ShortArray((SAMPLE_RATE / 10).coerceAtLeast(160))
            val chunk = ShortArray(samplesPerChunk)
            var filled = 0
            var seq = 0
            val deadline = SystemClock.elapsedRealtime() + request.durationMs
            while (SystemClock.elapsedRealtime() < deadline) {
                val read = recorder.read(slice, 0, slice.size)
                if (read < 0) throw IllegalStateException("Mic read error: $read")
                if (read == 0) continue
                var copied = 0
                while (copied < read) {
                    val space = samplesPerChunk - filled
                    val take = minOf(space, read - copied)
                    slice.copyInto(chunk, filled, copied, copied + take)
                    filled += take
                    copied += take
                    if (filled == samplesPerChunk) {
                        emit(chunkPayload(seq++, chunk, samplesPerChunk, request.chunkMs))
                        filled = 0
                        if (SystemClock.elapsedRealtime() >= deadline) break
                    }
                }
            }
        } catch (e: SecurityException) {
            throw SecurityException("Mic permission missing")
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
        }
    }

    private fun chunkPayload(seq: Int, chunk: ShortArray, count: Int, chunkMs: Long): JsonObject {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = chunk[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return buildJsonObject {
            put("seq", seq)
            put("sampleRateHz", SAMPLE_RATE)
            put("channels", 1)
            put("encoding", "pcm_s16le")
            put("chunkMs", chunkMs)
            put("rms", rms(chunk, count))
            put("dataBase64", Base64.getEncoder().encodeToString(bytes))
        }
    }
}
