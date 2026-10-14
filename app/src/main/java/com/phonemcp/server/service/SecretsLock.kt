package com.phonemcp.server.service

import java.io.File
import java.io.RandomAccessFile

/**
 * Serializes secret generation (token, TLS identity) across threads and processes.
 * Without this, two concurrent first-launch initializers (e.g. service start racing
 * dashboard refresh) each mint a DIFFERENT secret: the service serves one while the
 * file — and everyone reading it — holds the other. The synchronized block covers
 * same-process threads ( OverlappingFileLockException on shared JVM locks makes the
 * file lock alone insufficient); the file lock covers multi-process edge cases.
 */
object SecretsLock {
    fun <T> withLock(filesDir: File, block: () -> T): T {
        synchronized(SecretsLock::class.java) {
            val lockFile = File(filesDir, "secrets.lock").apply { parentFile?.mkdirs() }
            RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.lock().use {
                    return block()
                }
            }
        }
    }
}
