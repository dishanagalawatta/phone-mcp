package com.phonemcp.server.service

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences

/**
 * Single point for EncryptedSharedPreferences creation.
 *
 * security-crypto 1.0.0 `MasterKeys` is deprecated (1.1.0 deprecates even `MasterKey`
 * in favor of direct Android Keystore `KeyGenerator`). This helper isolates the
 * legacy call so migration touches one file. Suppression is intentional and tracked.
 */
object CryptoPrefs {
    @Suppress("DEPRECATION")
    fun auth(sharedPrefsName: String, context: Context): SharedPreferences {
        val alias = androidx.security.crypto.MasterKeys.getOrCreate(
            androidx.security.crypto.MasterKeys.AES256_GCM_SPEC,
        )
        return EncryptedSharedPreferences.create(
            sharedPrefsName,
            alias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
