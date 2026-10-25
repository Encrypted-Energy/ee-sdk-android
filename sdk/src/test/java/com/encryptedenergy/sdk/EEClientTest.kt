package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure status+body mappers behind mint/ingest/heartbeat. The HTTP itself
 * needs a device; these pin the branch logic that decides the outcome — the
 * "missing key is rejection", "bounty_earned drives the earn signal", and
 * "a malformed disable can never pause a fleet" rules.
 */
class EEClientTest {

    // --- bounty_earned (ingest 2xx body) ---

    @Test fun bountyEarnedParsesTheField() =
        assertEquals(3, EEClient.parseBountyEarned("""{"bounty_earned":3,"accepted":12}"""))

    @Test fun bountyEarnedAbsentIsZero() =
        assertEquals(0, EEClient.parseBountyEarned("""{"accepted":12}"""))

    @Test fun bountyEarnedNullBodyIsZero() =
        assertEquals(0, EEClient.parseBountyEarned(null))

    @Test fun bountyEarnedMalformedIsZero() =
        assertEquals(0, EEClient.parseBountyEarned("""{not json"""))

    // --- disable (heartbeat 2xx body, the C9 fleet kill switch) ---

    @Test fun disableParsesTrue() =
        assertTrue(EEClient.parseDisable("""{"ok":true,"disable":true}"""))

    @Test fun disableAbsentIsFalse() =
        assertFalse(EEClient.parseDisable("""{"ok":true}"""))

    @Test fun disableNullBodyIsFalse() =
        assertFalse(EEClient.parseDisable(null))

    @Test fun disableMalformedIsFalse() {
        // A parse failure must never pause a fleet.
        assertFalse(EEClient.parseDisable("""{broken"""))
    }

    @Test fun disableNonBooleanIsFalse() =
        assertFalse(EEClient.parseDisable("""{"disable":"yes"}"""))

    // --- gateway.public_id (heartbeat/verify 2xx body, C6) ---

    @Test fun publicIdParsesFromGatewayBlock() =
        assertEquals(
            "gw_a1b2c3",
            EEClient.parsePublicId(
                """{"ok":true,"gateway":{"name":"Fleet 7","public_id":"gw_a1b2c3"}}"""
            ),
        )

    @Test fun publicIdAbsentIsNull() {
        assertEquals(null, EEClient.parsePublicId("""{"ok":true}"""))
        assertEquals(null, EEClient.parsePublicId("""{"ok":true,"gateway":{"name":"x"}}"""))
    }

    @Test fun publicIdNullOrMalformedBodyIsNull() {
        // Identity is a nicety, never a failure.
        assertEquals(null, EEClient.parsePublicId(null))
        assertEquals(null, EEClient.parsePublicId("""{broken"""))
        assertEquals(null, EEClient.parsePublicId("""{"gateway":"not an object"}"""))
    }

    // --- invalid_reasons (ingest 422 body) ---

    @Test fun invalidReasonsParsesStrings() =
        assertEquals(
            listOf("packet[0]: missing eid", "packet[2]: latitude/longitude out of range"),
            EEClient.parseInvalidReasons(
                """{"ok":false,"error":"invalid packets","invalid_reasons":""" +
                    """["packet[0]: missing eid","packet[2]: latitude/longitude out of range"]}"""
            ),
        )

    @Test fun invalidReasonsAbsentIsEmpty() =
        assertEquals(emptyList<String>(), EEClient.parseInvalidReasons("""{"ok":false}"""))

    @Test fun invalidReasonsNullBodyIsEmpty() =
        assertEquals(emptyList<String>(), EEClient.parseInvalidReasons(null))

    @Test fun invalidReasonsMalformedIsEmpty() =
        assertEquals(emptyList<String>(), EEClient.parseInvalidReasons("""not json"""))

    // --- mint (POST /api/v1/devices) ---

    @Test fun mintMintedPullsKeyAndGatewayName() {
        val r = EEClient.mapMint(201, """{"api_key":"ee_live_xyz","gateway":{"name":"Pixel 7"}}""", "fallback")
        assertEquals(EEClient.MintResult.Minted("ee_live_xyz", "Pixel 7"), r)
    }

    @Test fun mintFallsBackToDeviceNameWhenGatewayNameAbsent() {
        val r = EEClient.mapMint(201, """{"api_key":"ee_live_xyz"}""", "Moto G")
        assertEquals(EEClient.MintResult.Minted("ee_live_xyz", "Moto G"), r)
    }

    @Test fun mintRejectsMissingApiKey() {
        val r = EEClient.mapMint(201, """{"gateway":{"name":"x"}}""", "fb")
        assertEquals(EEClient.MintResult.Rejected("mint response missing api_key"), r)
    }

    @Test fun mintRejectsWrongKeyPrefix() {
        val r = EEClient.mapMint(201, """{"api_key":"nope_123"}""", "fb")
        assertEquals(EEClient.MintResult.Rejected("mint response missing api_key"), r)
    }

    @Test fun mintSurfacesServerErrorMessage() {
        val r = EEClient.mapMint(403, """{"error":"attestation failed"}""", "fb")
        assertEquals(EEClient.MintResult.Rejected("attestation failed"), r)
    }

    @Test fun mintTreats5xxAndThrottleAsTransient() {
        assertEquals(EEClient.MintResult.Transient, EEClient.mapMint(503, null, "fb"))
        assertEquals(EEClient.MintResult.Transient, EEClient.mapMint(500, null, "fb"))
        assertEquals(EEClient.MintResult.Transient, EEClient.mapMint(429, null, "fb"))
        assertEquals(EEClient.MintResult.Transient, EEClient.mapMint(408, null, "fb"))
    }
}
