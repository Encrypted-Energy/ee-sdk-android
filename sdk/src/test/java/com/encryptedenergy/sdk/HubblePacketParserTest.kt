package com.encryptedenergy.sdk

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for HubblePacketParser (plan §2) — a 1:1 port of the iOS
 * HubblePacketParserTests, including the two REAL FCA6 service-data payloads
 * captured at John's desk in JC on 2026-07-11. These fixtures are the parity
 * oracle across the iOS and Android apps: expected values were computed
 * independently against pyhubblenetwork/ble.py semantics (version =
 * raw[0] >> 2; v0 EID = big-endian UInt32 at raw[2..<6]; EID string =
 * lowercase hex, NO leading-zero padding, matching Python format(eid, 'x')).
 *
 * A frame whose eidHex is null must be dropped before the queue (§1a: ee-web
 * 422s any batch containing a packet without an eid).
 */
class HubblePacketParserTest {

    private fun data(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    // v0 — real field payloads (JC desk tally, 2026-07-11)

    @Test
    fun v0RealPayloadDevice1() {
        // raw = 03aa098b628e9a7eb126dc49f552 (14 bytes)
        val raw = data("A6oJi2KOmn6xJtxJ9VI=")
        val p = HubblePacketParser.parse(raw)!!
        assertEquals(0, p.version)                         // 0x03 >> 2 == 0
        // EID bytes are 09 8b 62 8e -> 0x098b628e -> unpadded hex drops the
        // leading zero. THE zero-padding regression test: "098b628e" is wrong.
        assertEquals("98b628e", p.eidHex)
        assertArrayEquals(raw, p.payload)                  // v0 forwards entire raw
    }

    @Test
    fun v0RealPayloadDevice2() {
        // raw = 03aaefcacfae392cc3cfe12ed923 (14 bytes)
        val raw = data("A6rvys+uOSzDz+Eu2SM=")
        val p = HubblePacketParser.parse(raw)!!
        assertEquals(0, p.version)
        assertEquals("efcacfae", p.eidHex)
        assertArrayEquals(raw, p.payload)
    }

    @Test
    fun v0TooShortForEID() {
        // 5-byte v0 frame: header parses but raw.size < 6 -> no EID -> must
        // be droppable (null eidHex), never forwarded.
        val p = HubblePacketParser.parse(data("AwECAwQ="))!!
        assertEquals(0, p.version)
        assertNull(p.eidHex)
    }

    // v1 — unencrypted, carries no EID -> local drop (§1a)

    @Test
    fun v1ParsesButHasNoEID() {
        // 40-bit BE header: version=1 (hdr40 >> 34), network_id=5,
        // then 3 payload bytes aa bb cc. raw = 0400000005aabbcc.
        val raw = data("BAAAAAWqu8w=")
        val p = HubblePacketParser.parse(raw)!!
        assertEquals(1, p.version)
        assertNull(p.eidHex)                               // -> drop before queue
        assertArrayEquals(data("qrvM"), p.payload)         // raw[5...]
    }

    // v2 — AES-EAX, 8-byte little-endian EID

    @Test
    fun v2LittleEndianEID() {
        // raw = 08 0102 8877665544332211 aabb deadbeef (17 bytes >= 15)
        // ver 0x08 >> 2 == 2; EID = raw[3..<11] as LE UInt64 = 0x1122334455667788
        val raw = data("CAECiHdmVUQzIhGqu96tvu8=")
        val p = HubblePacketParser.parse(raw)!!
        assertEquals(2, p.version)
        assertEquals("1122334455667788", p.eidHex)
        assertArrayEquals(data("qrs="), p.payload)         // raw[11..<(count-4)]
    }

    @Test
    fun v2HighBitEIDFormatsUnsigned() {
        // Kotlin-specific hazard the Swift port never had: EID lands in a
        // signed Long, so a high bit set must still print as unsigned hex.
        // EID bytes LE 88 77 66 55 44 33 22 f1 -> 0xf122334455667788.
        val raw = byteArrayOf(
            0x08, 0x01, 0x02,
            0x88.toByte(), 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0xF1.toByte(),
            0xAA.toByte(), 0xBB.toByte(),
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )
        val p = HubblePacketParser.parse(raw)!!
        assertEquals(2, p.version)
        assertEquals("f122334455667788", p.eidHex)
    }

    // unknown version -> no EID -> local drop (§1a)

    @Test
    fun unknownVersionHasNoEID() {
        // 0xFF >> 2 == 63: unknown. Whole raw is the payload, no EID.
        val raw = byteArrayOf(0xFF.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05)
        val p = HubblePacketParser.parse(raw)!!
        assertNull(p.eidHex)
        assertArrayEquals(raw, p.payload)
    }

    // EID formatting must match Python format(eid, 'x') exactly

    @Test
    fun eidHexNeverZeroPadded() {
        // Server-side dedup key and bounty cap are keyed on the unpadded
        // string; "098b628e" and "98b628e" would split into two devices.
        // v0 frame with EID bytes 00 00 00 2a -> "2a", not "0000002a".
        val raw = byteArrayOf(
            0x03, 0x00, 0x00, 0x00, 0x00, 0x2A,
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), // auth tag filler
        )
        val p = HubblePacketParser.parse(raw)!!
        assertEquals("2a", p.eidHex)
    }
}
