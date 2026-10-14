package com.phonemcp.server.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileMediaStoreTest {

    @get:Rule
    val folders = TemporaryFolder()

    private fun store(): FileMediaStore {
        val context = mockk<Context> {
            every { cacheDir } returns folders.newFolder("cache")
        }
        return FileMediaStore(context)
    }

    @Test
    fun putRoundTrips() {
        val store = store()
        val ref = store.put("hello".toByteArray(), "txt", "text/plain")
        assertEquals("/media/${ref.id}", ref.urlPath)
        assertEquals("hello", store.get(ref.id)!!.readText())
    }

    @Test
    fun stageMovesFile() {
        val store = store()
        val source = File(folders.root, "rec.m4a").apply { writeBytes(ByteArray(16)) }
        val ref = store.stage(source, "m4a", "audio/mp4")
        assertTrue(!source.exists())
        assertEquals(16, store.get(ref.id)!!.length().toInt())
    }

    @Test
    fun expiredEntriesEvicted() {
        val store = store()
        val ref = store.put("gone".toByteArray(), "txt", "text/plain")
        store.evictExpired(ttlMillis = -1)
        assertNull(store.get(ref.id))
    }

    @Test
    fun sizeEvictionKeepsNewestWithinCap() {
        val store = store()
        val old = store.put(ByteArray(60) { 1 }, "bin", "application/octet-stream")
        Thread.sleep(20)
        val mid = store.put(ByteArray(60) { 2 }, "bin", "application/octet-stream")
        Thread.sleep(20)
        val fresh = store.put(ByteArray(60) { 3 }, "bin", "application/octet-stream")
        // 180 bytes live, cap 120: oldest must go, the two newest stay.
        store.evictExpired(ttlMillis = Long.MAX_VALUE, maxBytes = 120)
        assertNull(store.get(old.id))
        assertTrue(store.get(mid.id)!!.exists())
        assertTrue(store.get(fresh.id)!!.exists())
    }
}
