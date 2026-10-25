package com.encryptedenergy.sdk

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM seal/open for the token at rest. Split out of [TokenStore] so the
 * crypto path unit-tests on a plain JVM (any AES [SecretKey] works); only the
 * Keystore key handle and SharedPreferences are android-bound.
 *
 * java.util.Base64 (not android.util) keeps this file in the harness's
 * compile set — available since API 26, our minSdk floor.
 */
internal object TokenCrypto {

    const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** What lands in prefs: iv + ciphertext, Base64, nothing else. */
    data class Sealed(val ivB64: String, val ciphertextB64: String)

    /** Encrypt [plaintext]; the cipher picks a fresh random IV every call. */
    fun seal(plaintext: String, key: SecretKey): Sealed {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Sealed(b64(cipher.iv), b64(ct))
    }

    /**
     * Decrypt, or null on ANY failure — a rotated/invalidated Keystore key, a
     * tampered ciphertext, or corrupt prefs all read as "no token stored"
     * (the app's signed-out semantics, carried over).
     */
    fun open(sealed: Sealed, key: SecretKey): String? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(TAG_BITS, unb64(sealed.ivB64)),
        )
        String(cipher.doFinal(unb64(sealed.ciphertextB64)), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
