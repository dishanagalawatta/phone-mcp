package com.phonemcp.server.domain

import java.io.File

/** App-private cache sidecar store for photo/audio/video blobs served via /media/{id}. */
interface MediaStore {
    fun put(bytes: ByteArray, suffix: String, mimeType: String): MediaRef

    /** Moves an already-written file (e.g. MediaRecorder/CameraX output) into the store without re-buffering. */
    fun stage(source: File, suffix: String, mimeType: String): MediaRef
    fun get(id: String): File?
    fun evictExpired(ttlMillis: Long = 15 * 60 * 1000L, maxBytes: Long = 100L * 1024 * 1024)
}

data class MediaRef(
    val id: String,
    val mimeType: String,
    val urlPath: String,
)
