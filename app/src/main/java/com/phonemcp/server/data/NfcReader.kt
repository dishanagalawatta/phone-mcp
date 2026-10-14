package com.phonemcp.server.data

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import com.phonemcp.server.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One decoded tag tap. */
data class TagDump(
    val idHex: String,
    val techs: List<String>,
    val records: List<JsonObject>,
    val atMs: Long,
)

/**
 * NFC tap intake. Reader mode can only run on a resumed Activity, so MainActivity enables it
 * exactly while waits are pending (demand-driven) — no idle hijacking of the user's taps.
 * Missed taps (nobody waiting) still update [lastTag] for a recency window.
 */
@Singleton
class NfcReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val adapter: NfcAdapter? = try {
        @Suppress("DEPRECATION")
        NfcAdapter.getDefaultAdapter(context)
    } catch (_: Exception) {
        null
    }

    private val waiters = ConcurrentLinkedQueue<CompletableDeferred<TagDump?>>()
    private val _waiterCount = MutableStateFlow(0)

    /** True while at least one `nfc_read` is parked waiting for a tap. */
    private val _armed = MutableStateFlow(false)
    val hasWaiters: StateFlow<Boolean> = _armed

    @Volatile
    private var lastTag: TagDump? = null

    fun isPresent(): Boolean = adapter != null

    fun isEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: Exception) {
        false
    }

    fun waiterCount(): Int = _waiterCount.value

    fun lastTag(maxAgeMs: Long): TagDump? {
        val tag = lastTag ?: return null
        return if (System.currentTimeMillis() - tag.atMs <= maxAgeMs) tag else null
    }

    /**
     * Suspends until the next tap or [timeoutMs] elapses. Returns null on timeout
     * (caller reports `waiting_for_tap` so the agent can ask the user to tap and retry).
     */
    suspend fun awaitTag(timeoutMs: Long): TagDump? {
        val waiter = CompletableDeferred<TagDump?>()
        waiters.add(waiter)
        _waiterCount.update { it + 1 }
        _armed.value = true
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            if (waiters.remove(waiter)) {
                _waiterCount.update { (it - 1).coerceAtLeast(0) }
                if (waiters.isEmpty()) _armed.value = false
            }
        }
    }

    fun enable(activity: Activity) {
        val adapter = adapter ?: return
        try {
            adapter.enableReaderMode(
                activity,
                { tag -> onTag(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE,
                null,
            )
            if (BuildConfig.DEBUG) android.util.Log.i("PhoneMcp", "nfc reader armed")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("PhoneMcp", "nfc reader arm failed: ${e.message}")
        }
    }

    fun disable(activity: Activity) {
        try {
            adapter?.disableReaderMode(activity)
        } catch (_: Exception) {
        }
    }

    private fun onTag(tag: Tag) {
        if (BuildConfig.DEBUG) android.util.Log.i("PhoneMcp", "nfc tag seen: ${toHex(tag.id)}")
        val dump = try {
            decodeTag(tag)
        } catch (_: Exception) {
            TagDump(toHex(tag.id), tag.techList.map { it.substringAfterLast('.') }, emptyList(), System.currentTimeMillis())
        }
        lastTag = dump
        // Drain with poll(): ConcurrentLinkedQueue iterators do not support remove().
        // complete() never throws (false when cancelled) — one bad waiter must not
        // abort the rest.
        while (true) {
            val waiter = waiters.poll() ?: break
            _waiterCount.update { (it - 1).coerceAtLeast(0) }
            try {
                waiter.complete(dump)
            } catch (_: Exception) {
            }
        }
        _armed.value = false
    }

    private fun decodeTag(tag: Tag): TagDump {
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val records = mutableListOf<JsonObject>()
        try {
            val ndef = Ndef.get(tag)
            val message = ndef?.cachedNdefMessage
            if (message != null) {
                for (record in message.records) {
                    records.add(decodeRecord(record.tnf, record.type, record.payload))
                }
            }
        } catch (_: Exception) {
        }
        return TagDump(toHex(tag.id), techs, records, System.currentTimeMillis())
    }

    companion object {
        const val MAX_PAYLOAD = 4096

        fun toHex(bytes: ByteArray?): String =
            bytes?.joinToString("") { "%02x".format(it) }.orEmpty()

        /** Decodes one NDEF record into JSON. Pure; JVM-test safe. */
        fun decodeRecord(tnf: Short, type: ByteArray, payload: ByteArray): JsonObject {
            val payloadHex = if (payload.size <= MAX_PAYLOAD) toHex(payload) else toHex(payload.copyOf(MAX_PAYLOAD))
            return buildJsonObject {
                put("tnf", tnf.toInt())
                put("type", toHex(type))
                val text = decodeTextPayload(payload)
                if (text != null) put("text", text.take(1000))
                val uri = decodeUriPayload(type, payload)
                if (uri != null) put("uri", uri.take(1000))
                put("payloadHex", payloadHex)
                if (payload.size > MAX_PAYLOAD) put("truncated", true)
            }
        }

        /**
         * Well-known Text record (RTD_TEXT): status byte -> encoding + language length.
         * Returns null for non-text payloads. Pure; JVM-test safe.
         */
        fun decodeTextPayload(payload: ByteArray): String? {
            if (payload.isEmpty()) return null
            return try {
                val status = payload[0].toInt()
                val utf16 = (status and 0x80) != 0
                val langLen = status and 0x3F
                if (payload.size < 1 + langLen) return null
                val text = payload.copyOfRange(1 + langLen, payload.size)
                if (utf16) String(text, Charsets.UTF_16) else String(text, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Well-known URI record (type 'U'): first byte indexes the prefix table (NFCForum URI RTD).
         * Returns null unless [type] is the URI RTD. Pure; JVM-test safe.
         */
        fun decodeUriPayload(type: ByteArray, payload: ByteArray): String? {
            if (!(type.size == 1 && type[0] == 0x55.toByte())) return null
            if (payload.isEmpty()) return null
            val prefix = URI_PREFIXES[payload[0].toInt() and 0xFF] ?: ""
            return try {
                prefix + String(payload.copyOfRange(1, payload.size), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        private val URI_PREFIXES = arrayOf(
            "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
            "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://",
            "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:",
            "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
            "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:",
            "urn:epc:raw:", "urn:epc:", "urn:nfc:",
        )
    }
}
