package com.localmind.chat.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Produces the passphrase that encrypts the local SQLite file.
 *
 * The passphrase is random per install. It is wrapped with an AES key that lives in
 * the Android Keystore, so the wrapped blob in SharedPreferences is useless on its own
 * and the raw key never exists outside hardware-backed storage. Nothing here touches
 * the network, and the passphrase is never uploaded — which also means that if the user
 * wipes the app, the local history is unrecoverable by design.
 */
object DatabaseKeys {

    private const val PREFS = "localmind_secure"
    private const val PREF_WRAPPED = "db_passphrase"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "localmind_db_wrapping_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val PASSPHRASE_BYTES = 32

    fun passphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(PREF_WRAPPED, null)?.let { stored ->
            return unwrap(Base64.decode(stored, Base64.NO_WRAP))
        }

        val fresh = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(PREF_WRAPPED, Base64.encodeToString(wrap(fresh), Base64.NO_WRAP))
            .apply()
        return fresh
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not requiring user authentication: the database has to be
                // readable by background sync. Add setUserAuthenticationRequired(true) if
                // you want the history locked behind biometrics.
                .build()
        )
        return generator.generateKey()
    }

    /** Output layout: [iv (12 bytes)][ciphertext + GCM tag]. */
    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val encrypted = cipher.doFinal(plain)
        return cipher.iv + encrypted
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }
}
