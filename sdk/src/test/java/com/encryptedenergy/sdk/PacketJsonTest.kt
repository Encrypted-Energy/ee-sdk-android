package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketJsonTest {

    // Values chosen so Double.toString is identical on every JVM/ART
    // (short decimals round-trip unambiguously); the wire-parity test below
    // uses the real iOS 1.2 production fixture with contains-checks instead.
    private val clean = UploadPacket(
        eidHex = "5f982837",
        payloadB64 = "AQID",
        rssi = -50,
        timestampSeconds = 1_785_545_352,
        latitude = 40.5,
        longitude = -74.25,
        positionAtSeconds = 1_785_545_340,
        accuracyM = 14.0,
    )

    @Test
    fun packetMatchesWireContractExactly() {
        assertEquals(
            "{\"payload_b64\":\"AQID\",\"rssi\":-50,\"timestamp\":1785545352," +
                "\"latitude\":40.5,\"longitude\":-74.25,\"position_at\":1785545340," +
                "\"eid\":\"5f982837\",\"accuracy_m\":14.0}",
            PacketJson.packet(clean)
        )
    }

    @Test
    fun ios12ProductionFixtureParity() {
        // Observed on the wire from iOS 1.2 in prod (Loki, 2026-08-01):
        // every key present, unix-seconds ints, unpadded lowercase hex eid.
        val p = UploadPacket(
            eidHex = "5f982837",
            payloadB64 = "A1NfmCg3HoGjWrKY3zg=",
            rssi = -50,
            timestampSeconds = 1_785_545_352,
            latitude = 47.587223436883434,
            longitude = -122.07973720240967,
            positionAtSeconds = 1_785_545_340,
            accuracyM = 13.988265225086755,
        )
        val json = PacketJson.packet(p)
        assertTrue(json, json.contains("\"payload_b64\":\"A1NfmCg3HoGjWrKY3zg=\""))
        assertTrue(json, json.contains("\"timestamp\":1785545352"))
        assertTrue(json, json.contains("\"position_at\":1785545340"))
        assertTrue(json, json.contains("\"latitude\":47.58722"))
        assertTrue(json, json.contains("\"longitude\":-122.07973"))
        assertTrue(json, json.contains("\"accuracy_m\":13.98826"))
        assertTrue(json, json.contains("\"eid\":\"5f982837\""))
        assertTrue(json, json.contains("\"rssi\":-50"))
    }

    @Test
    fun omitsAccuracyWhenFixHadNone() {
        val json = PacketJson.packet(clean.copy(accuracyM = null))
        assertFalse(json, json.contains("accuracy_m"))
    }

    @Test
    fun bodyWrapsPacketsArray() {
        assertEquals(
            "{\"packets\":[${PacketJson.packet(clean)},${PacketJson.packet(clean)}]}",
            PacketJson.body(listOf(clean, clean))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteCoordinateFailsLoudly() {
        PacketJson.packet(clean.copy(latitude = Double.NaN))
    }
}
