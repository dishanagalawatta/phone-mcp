package com.phonemcp.server.service

import android.content.Context
import android.content.SharedPreferences
import com.phonemcp.server.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TLS identity for the embedded server: a self-signed cert generated once via Ktor's
 * certificate builder, persisted as app-private BKS. Stable across restarts so TOFU pins hold.
 * BKS (not PKCS#12): Android's PKCS12 KeyStore cannot reliably round-trip Ktor-written
 * stores, while BKS is Android's native format (BC provider is built in).
 * The keystore password is random per install, kept in EncryptedSharedPreferences.
 */
@Singleton
class TlsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Identity(val keyStore: KeyStore, val password: CharArray, val fingerprint: String)

    fun loadOrCreate(): Identity = SecretsLock.withLock(context.filesDir) {
        // Companion cache means this logs at most details, never secrets.
        val password = password()
        if (BuildConfig.DEBUG) {
            val f = keystoreFile()
            android.util.Log.d("PhoneMcp", "tls fromCache fileExists=${f.isFile}")
        }
        // Legacy PKCS#12 files from earlier builds could never round-trip on Android; drop them.
        File(context.filesDir, "tls/keystore.p12").delete()
        File(context.filesDir, "tls/keystore.p12.tmp").delete()
        val file = keystoreFile().apply { parentFile?.mkdirs() }
        load(file, password)?.let { identity ->
            val publicKey = identity.keyStore.getCertificate(KEY_ALIAS)?.publicKey
            if (publicKey != null && !McpHttp.isTlsKeyTooWeak(publicKey)) return@withLock identity
            // Pre-2048 (or unparsable) identity: modern TLS stacks abort the handshake
            // against it (fuzz finding, ADR-0017). Rotate; the dashboard shows the new pin.
            if (BuildConfig.DEBUG) {
                android.util.Log.w("PhoneMcp", "tls rotating weak identity (see ADR-0017)")
            }
            file.delete()
        }
        // Missing or unreadable (e.g. a truncated write): regenerate atomically.
        file.delete()
        val created = buildKeyStore {
            certificate(KEY_ALIAS) {
                // Qualified: a local `password` val shadows the builder member without `this.`.
                this.password = password.concatToString()
                // Ktor defaults to 1024-bit RSA (rejected by modern TLS stacks) — pin 2048.
                keySizeInBits = 2048
                // Defaults are SHA-1 (rejected by modern TLS stacks) — pin SHA-256 explicitly.
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.RSA
                domains = listOf("localhost", "phone-mcp.local")
                ipAddresses = NetInfo.lanIpv4().mapNotNull { ip ->
                    runCatching { InetAddress.getByName(ip) }.getOrNull()
                }
                daysValid = VALIDITY_DAYS
            }
        }
        // Write temp + validate + atomic rename so a failed write can never brick the store.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { created.store(it, password) }
        load(tmp, password) ?: throw IllegalStateException("freshly written keystore failed validation")
        if (!tmp.renameTo(file)) throw IllegalStateException("keystore rename failed")
        return@withLock load(file, password) ?: throw IllegalStateException("keystore missing after rename")
    }

    private fun load(file: File, password: CharArray): Identity? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply {
                FileInputStream(file).use { load(it, password) }
            }
            val cert = keyStore.getCertificate(KEY_ALIAS) ?: return null
            Identity(keyStore, password, Pairing.sha256Hex(cert.encoded))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w("PhoneMcp", "keystore load failed: ${e.javaClass.simpleName} len=${file.length()}")
            }
            null
        }
    }

    fun fingerprint(): String = try {
        loadOrCreate().fingerprint
    } catch (_: Exception) {
        ""
    }

    private fun keystoreFile(): File = File(context.filesDir, "tls/keystore.bks")

    // ALWAYS returns a fresh copy: JCA KeyStore ops have been observed mutating the
    // caller's password array on this device, which silently corrupts any shared instance.
    // The companion cache holds the pristine copy; every consumer gets its own.
    private fun password(): CharArray {
        cachedPassword?.let { return it.copyOf() }
        val stored = prefs().getString(PASSWORD_KEY, null)
        if (stored != null) {
            cachedPassword = stored.toCharArray()
            return stored.toCharArray()
        }
        val fresh = buildString {
            val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
            val random = SecureRandom()
            repeat(32) { append(alphabet[random.nextInt(alphabet.size)]) }
        }
        prefs().edit().putString(PASSWORD_KEY, fresh).apply()
        cachedPassword = fresh.toCharArray()
        return fresh.toCharArray()
    }

    private fun prefs(): SharedPreferences =
        // Shares TokenStore's file/alias: one encrypted prefs file for all auth secrets.
        CryptoPrefs.auth(TokenStore.PREFS, context)

    companion object {
        const val KEY_ALIAS = "phone-mcp"
        const val KEYSTORE_TYPE = "BKS"

        /**
         * 825 days: maximum trusted lifetime (CA/Browser Forum). Self-signed TOFU
         * does not need 10-year pins; shorter rotation bounds key compromise.
         */
        const val VALIDITY_DAYS = 825L
        const val PASSWORD_KEY = "tls_keystore_password"

        // Same async-apply staleness rationale as TokenStore: companion-wide cache.
        @Volatile
        private var cachedPassword: CharArray? = null
    }
}
