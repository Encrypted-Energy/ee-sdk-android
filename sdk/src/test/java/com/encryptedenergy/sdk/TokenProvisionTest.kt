package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one invariant that matters: nothing persists until the server said
 * yes. Every non-Ok branch must leave the store untouched.
 */
class TokenProvisionTest {

    // --- injectToken (verify-before-persist) ---

    @Test
    fun verifiedTokenPersists() {
        var persisted: String? = null
        val out = TokenProvision.injectToken(
            "ee_live_good",
            verify = { EEClient.Result.Ok(200) },
            persist = { persisted = it },
        )
        assertEquals(TokenProvision.InjectOutcome.Verified, out)
        assertEquals("ee_live_good", persisted)
    }

    @Test
    fun verifySeesTheInjectedTokenNotTheStoredOne() {
        var seen: String? = null
        TokenProvision.injectToken(
            "ee_live_new",
            verify = { seen = it; EEClient.Result.Ok(200) },
            persist = {},
        )
        assertEquals("ee_live_new", seen)
    }

    @Test
    fun unauthorizedTokenNeverPersists() {
        var persisted: String? = null
        val out = TokenProvision.injectToken(
            "ee_live_revoked",
            verify = { EEClient.Result.Unauthorized },
            persist = { persisted = it },
        )
        assertEquals(TokenProvision.InjectOutcome.Rejected(401), out)
        assertNull(persisted)
    }

    @Test
    fun terminalRejectionNeverPersists() {
        var persisted: String? = null
        val out = TokenProvision.injectToken(
            "ee_live_odd",
            verify = { EEClient.Result.Terminal(403) },
            persist = { persisted = it },
        )
        assertEquals(TokenProvision.InjectOutcome.Rejected(403), out)
        assertNull(persisted)
    }

    @Test
    fun transientVerifyNeverPersists() {
        var persisted: String? = null
        val out = TokenProvision.injectToken(
            "ee_live_maybe",
            verify = { EEClient.Result.Transient("network") },
            persist = { persisted = it },
        )
        assertEquals(TokenProvision.InjectOutcome.Retry("network"), out)
        assertNull(persisted)
    }

    // --- selfMint ---

    @Test
    fun mintedKeyAndNamePersist() {
        var token: String? = null
        var name: String? = null
        val out = TokenProvision.selfMint(
            "Pixel 7",
            mint = { EEClient.MintResult.Minted("ee_live_minted", "quiet-river-201") },
            persist = { t, n -> token = t; name = n },
        )
        assertEquals(TokenProvision.MintOutcome.Provisioned("quiet-river-201"), out)
        assertEquals("ee_live_minted", token)
        assertEquals("quiet-river-201", name)
    }

    @Test
    fun mintPassesTheDeviceName() {
        var seen: String? = null
        TokenProvision.selfMint(
            "HRY-LX1T",
            mint = { seen = it; EEClient.MintResult.Transient },
            persist = { _, _ -> },
        )
        assertEquals("HRY-LX1T", seen)
    }

    @Test
    fun rejectedMintNeverPersists() {
        var persisted = false
        val out = TokenProvision.selfMint(
            "Pixel 7",
            mint = { EEClient.MintResult.Rejected("attestation failed") },
            persist = { _, _ -> persisted = true },
        )
        assertEquals(TokenProvision.MintOutcome.Rejected("attestation failed"), out)
        assertEquals(false, persisted)
    }

    @Test
    fun transientMintNeverPersists() {
        var persisted = false
        val out = TokenProvision.selfMint(
            "Pixel 7",
            mint = { EEClient.MintResult.Transient },
            persist = { _, _ -> persisted = true },
        )
        assertEquals(TokenProvision.MintOutcome.Retry, out)
        assertEquals(false, persisted)
    }
}
