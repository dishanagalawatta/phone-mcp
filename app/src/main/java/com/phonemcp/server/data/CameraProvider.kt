package com.phonemcp.server.data

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.phonemcp.server.domain.MediaStore
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.objectSchema
import com.phonemcp.server.mcp.prop
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import android.os.Handler
import android.os.Looper

/** CameraX still (+ video stub) → MediaStore. Needs CAMERA (+RECORD_AUDIO for video) and camera FGS. */
@Singleton
class CameraProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStore: MediaStore,
) : ToolProvider {
    override val id = "camera"
    override fun listTools() = listOf(
        ToolDefinition(
            "camera_capture",
            "Capture a still photo, returns a /media URL. Args: lens back|front (default back).",
            objectSchema(buildJsonObject { put("lens", prop("string", "back or front")) }),
        ),
        ToolDefinition(
            "camera_video",
            "Record a short video clip (H.264 .mp4), returns a /media URL. " +
                "Args: lens back|front (default back), durationMs (default 5000, clamped 2000..30000), " +
                "withAudio (default true, needs mic permission).",
            objectSchema(
                buildJsonObject {
                    put("lens", prop("string", "back or front"))
                    put("durationMs", prop("integer", "Recording length in ms"))
                    put("withAudio", prop("boolean", "Include microphone audio"))
                },
            ),
        ),
    )

    override suspend fun call(name: String, args: JsonObject): ToolCallResult {
        when (name) {
            "camera_capture" -> return capture(parseLens(args)) // throws IllegalArgumentException on bad lens
            "camera_video" -> return recordVideo(parseVideoArgs(args))
        }
        throw IllegalArgumentException("unknown tool $name")
    }

    private suspend fun capture(lens: String): ToolCallResult {
        val selector = if (lens == "front") {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        return try {
            // Bind/unbind must happen on the main thread; staging stays on the caller's thread.
            val tmp = withContext(Dispatchers.Main) { captureStill(selector) }
            val ref = mediaStore.stage(tmp, "jpg", "image/jpeg")
            val size = mediaStore.get(ref.id)?.length() ?: -1
            ToolCallResult.text(
                buildJsonObject {
                    put("mediaUrl", ref.urlPath)
                    put("mimeType", ref.mimeType)
                    put("lens", lens)
                    put("sizeBytes", size)
                }.toString(),
            )
        } catch (e: SecurityException) {
            ToolCallResult.error("Camera permission missing")
        } catch (e: Exception) {
            ToolCallResult.error("Camera capture failed: ${e.message}")
        }
    }

    private suspend fun recordVideo(request: VideoRequest): ToolCallResult {
        val selector = if (request.lens == "front") {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        return try {
            val tmp = withContext(Dispatchers.Main) { recordClip(selector, request) }
            val ref = mediaStore.stage(tmp, "mp4", "video/mp4")
            val size = mediaStore.get(ref.id)?.length() ?: -1
            ToolCallResult.text(
                buildJsonObject {
                    put("mediaUrl", ref.urlPath)
                    put("mimeType", ref.mimeType)
                    put("lens", request.lens)
                    put("durationMs", request.durationMs)
                    put("sizeBytes", size)
                }.toString(),
            )
        } catch (e: SecurityException) {
            ToolCallResult.error(e.message ?: "Permission missing")
        } catch (e: Exception) {
            ToolCallResult.error("Video record failed: ${e.message}")
        }
    }

    private suspend fun recordClip(selector: CameraSelector, request: VideoRequest): File {
        val provider = withTimeoutOrNull(INIT_TIMEOUT_MS) { awaitCameraProvider() }
            ?: throw TimeoutException("Camera init timed out")
        val owner = HeadlessOwner()
        owner.resume()
        try {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            val useCase = VideoCapture.withOutput(recorder)
            provider.bindToLifecycle(owner, selector, useCase)
            val file = File.createTempFile("vid-", ".mp4", context.cacheDir)
            val output = FileOutputOptions.Builder(file).build()
            val mainHandler = Handler(Looper.getMainLooper())
            val stopToken = Any()
            var recording: Recording? = null
            fun safeStop() {
                try {
                    recording?.stop()
                } catch (_: Exception) {
                }
            }
            try {
                withTimeoutOrNull(request.durationMs + FINALIZE_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        val pending = useCase.output.prepareRecording(context, output)
                        val prepared = if (request.withAudio) {
                            try {
                                pending.withAudioEnabled()
                            } catch (e: SecurityException) {
                                cont.resumeWithException(
                                    SecurityException("Mic permission missing for video audio (or set withAudio false)"),
                                )
                                return@suspendCancellableCoroutine
                            }
                        } else {
                            pending
                        }
                        recording = prepared.start(ContextCompat.getMainExecutor(context)) { event ->
                            if (event is VideoRecordEvent.Finalize) {
                                if (event.hasError()) {
                                    cont.resumeWithException(RuntimeException("Video error ${event.error}"))
                                } else {
                                    cont.resume(Unit) { }
                                }
                            }
                        }
                        cont.invokeOnCancellation {
                            mainHandler.removeCallbacksAndMessages(stopToken)
                            safeStop()
                        }
                        mainHandler.postDelayed({ safeStop() }, stopToken, request.durationMs)
                    }
                } ?: throw TimeoutException("Video finalize timed out")
                return file
            } finally {
                mainHandler.removeCallbacksAndMessages(stopToken)
                safeStop()
            }
        } finally {
            provider.unbindAll()
            owner.destroy()
        }
    }
    private suspend fun captureStill(selector: CameraSelector): File {
        val provider = withTimeoutOrNull(INIT_TIMEOUT_MS) { awaitCameraProvider() }
            ?: throw TimeoutException("Camera init timed out")
        val owner = HeadlessOwner()
        owner.resume()
        try {
            val useCase = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.bindToLifecycle(owner, selector, useCase)
            val tmp = File.createTempFile("cam-", ".jpg", context.cacheDir)
            val output = ImageCapture.OutputFileOptions.Builder(tmp).build()
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    useCase.takePicture(
                        output,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                cont.resume(Unit) { }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                cont.resumeWithException(exception)
                            }
                        },
                    )
                }
            } ?: throw TimeoutException("Capture timed out")
            return tmp
        } finally {
            provider.unbindAll()
            owner.destroy()
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }

    /** Minimal LifecycleOwner so CameraX can bind without an Activity. */
    private class HeadlessOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }

        override val lifecycle: Lifecycle get() = registry
    }

    companion object {
        const val INIT_TIMEOUT_MS = 15_000L
        const val CAPTURE_TIMEOUT_MS = 30_000L
        const val FINALIZE_TIMEOUT_MS = 15_000L

        /** Pure arg parsing; throws IllegalArgumentException on bad lens. JVM-test safe. */
        fun parseLens(args: JsonObject): String {
            val lens = (args["lens"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() ?: "back"
            require(lens == "back" || lens == "front") { "Invalid lens: $lens (use back|front)" }
            return lens
        }

        data class VideoRequest(val lens: String, val durationMs: Long, val withAudio: Boolean)

        /** Pure arg parsing; throws IllegalArgumentException on bad lens. JVM-test safe. */
        fun parseVideoArgs(args: JsonObject): VideoRequest {
            val durationMs = ArgParser.long(args, "durationMs", 5000L, 2000L..30_000L)
            val withAudio = ArgParser.boolean(args, "withAudio", true)
            return VideoRequest(parseLens(args), durationMs, withAudio)
        }
    }
}
