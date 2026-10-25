package com.encryptedenergy.sdk

import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The crypto path under a plain JVM AES key. The Keystore handle is
 * android-bound ([TokenStore], verified in John's Studio build + on-device);
 * the seal/open rules themselves are provider-independent and pinned here.
 */
class TokenCryptoTest {

    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripsTheToken() {
        val k = key()
        val sealed = TokenCrypto.seal("ee_live_abc123", k)
        assertEquals("ee_live_abc123", TokenCrypto.open(sealed, k))
    }

    @Test
    fun freshIvEverySeal() {
        val k = key()
        val a = TokenCrypto.seal("ee_live_abc123", k)
        val b = TokenCrypto.seal("ee_live_abc123", k)
        assertNotEquals("IV reuse under GCM is catastrophic", a.ivB64, b.ivB64)
        assertNotEquals(a.ciphertextB64, b.ciphertextB64)
    }

    @Test
    fun wrongKeyReadsAsSignedOut() {
        val sealed = TokenCrypto.seal("ee_live_abc123", key())
        assertNull(TokenCrypto.open(sealed, key()))
    }

    @Test
    fun tamperedCiphertextReadsAsSignedOut() {
        val k = key()
        val sealed = TokenCrypto.seal("ee_live_abc123", k)
        val bytes = java.util.Base64.getDecoder().decode(sealed.ciphertextB64)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = sealed.copy(ciphertextB64 = java.util.Base64.getEncoder().encodeToString(bytes))
        assertNull(TokenCrypto.open(tampered, k))
    }

    @Test
    fun corruptPrefsReadAsSignedOut() {
        // Not even Base64 — a half-written prefs file must not throw.
        assertNull(TokenCrypto.open(TokenCrypto.Sealed("%%%", "%%%"), key()))
    }
}
