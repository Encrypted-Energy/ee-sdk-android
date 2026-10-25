package com.encryptedenergy.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * ee_live_ token at rest, encrypted with an Android Keystore AES-GCM key —
 * ported from gateway-android 0.6.0 with the claim-state block removed (no
 * end user in a fleet SDK) and every name SDK-namespaced: its own prefs file
 * and its own Keystore alias, so the SDK can never collide with the host
 * app's prefs or keys — including a host that embeds generic names like
 * "ee_gateway", and including the first-party EE app itself if both ever
 * share a process.
 *
 * The key never leaves secure hardware; prefs hold only iv + ciphertext
 * (see [TokenCrypto], where the crypto path is JVM-tested).
 *
 * The gateway name rides in the same file as plain text — it's a display
 * label, not a secret, and keeping it beside the token means both move (or
 * reset) together.
 */
internal class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun load(): String? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val ct = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return TokenCrypto.open(TokenCrypto.Sealed(iv, ct), key())
    }

    fun save(token: String) {
        val sealed = TokenCrypto.seal(token, key())
        prefs.edit()
            .putString(KEY_IV, sealed.ivB64)
            .putString(KEY_CIPHERTEXT, sealed.ciphertextB64)
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_IV)
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_GATEWAY_NAME)
            .apply()
    }

    /** Self-mint keeps the server-assigned name for [EEStatus] surfacing. */
    fun loadGatewayName(): String? = prefs.getString(KEY_GATEWAY_NAME, null)

    fun saveGatewayName(name: String) {
        prefs.edit().putString(KEY_GATEWAY_NAME, name).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        /** SDK-owned alias — the Keystore namespace is app-wide; don't squat generic names. */
        const val ALIAS = "com.encryptedenergy.sdk.token_key"
        /** SDK-owned prefs file — never the host app's. */
        const val PREFS_FILE = "com.encryptedenergy.sdk.store"
        const val KEY_IV = "token_iv"
        const val KEY_CIPHERTEXT = "token_ct"
        const val KEY_GATEWAY_NAME = "gateway_name"
    }
}
