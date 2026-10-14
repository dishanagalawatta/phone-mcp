package com.phonemcp.server.data

import android.content.Context
import com.phonemcp.server.domain.MediaRef
import com.phonemcp.server.domain.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileMediaStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaStore {
    private fun dir(): File = File(context.cacheDir, "mcp").apply { mkdirs() }

    companion object {
        private val KNOWN_SUFFIXES = arrayOf("png", "jpg", "mp4", "m4a", "wav", "json", "bin")
    }

    override fun put(bytes: ByteArray, suffix: String, mimeType: String): MediaRef {
        val id = UUID.randomUUID().toString()
        File(dir(), "$id.$suffix").writeBytes(bytes)
        evictExpired()
        return MediaRef(id, mimeType, "/media/$id")
    }

    override fun stage(source: File, suffix: String, mimeType: String): MediaRef {
        val id = UUID.randomUUID().toString()
        val dest = File(dir(), "$id.$suffix")
        if (!source.renameTo(dest)) {
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
        evictExpired()
        return MediaRef(id, mimeType, "/media/$id")
    }

    override fun get(id: String): File? {
        // Fast path: probe known suffixes before falling back to a directory scan.
        // Cache stays small (<100 files), but per-request scans still waste I/O.
        for (suffix in KNOWN_SUFFIXES) {
            val direct = File(dir(), "$id.$suffix")
            if (direct.isFile) return direct
        }
        return dir().listFiles { _, name -> name.startsWith("$id.") }
            ?.firstOrNull()
    }

    override fun evictExpired(ttlMillis: Long, maxBytes: Long) {
        val now = System.currentTimeMillis()
        val files = dir().listFiles()?.sortedBy { it.lastModified() } ?: return
        files.filter { now - it.lastModified() > ttlMillis }.forEach { it.delete() }
        // Re-list: the pre-delete total over-counts and would over-evict by size.
        var total = dir().listFiles()?.sumOf { it.length() } ?: 0L
        for (file in dir().listFiles()?.sortedBy { it.lastModified() } ?: return) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }
}
