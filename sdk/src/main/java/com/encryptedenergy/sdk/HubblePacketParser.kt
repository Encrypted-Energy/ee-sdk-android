package com.encryptedenergy.sdk

/**
 * A parsed Hubble FCA6 advertisement frame.
 *
 * [eidHex] is the server-side identity key: lowercase hex, NO leading-zero
 * padding, matching Python `format(eid, 'x')` on the Pi worker. A frame with
 * `eidHex == null` must never be enqueued for upload: ee-web rejects the
 * entire batch with 422 if any packet lacks an eid (plan §1a).
 */
internal class ParsedPacket(
    val version: Int,
    val eidHex: String?,
    /** The bytes that get base64-encoded into `payload_b64`. */
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ParsedPacket &&
            version == other.version &&
            eidHex == other.eidHex &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        31 * (31 * version + (eidHex?.hashCode() ?: 0)) + payload.contentHashCode()
}

/**
 * Byte-exact port of pyhubblenetwork/ble.py packet parsing (plan §2), matching
 * the iOS HubblePacketParser fixture-for-fixture.
 * Version is the top 6 bits of the first byte: `raw[0] >> 2`.
 */
internal object HubblePacketParser {

    fun parse(raw: ByteArray): ParsedPacket? {
        if (raw.isEmpty()) return null
        val version = (raw[0].toInt() and 0xFF) shr 2

        return when (version) {
            0 -> {
                // AES-CTR: hdr(1) + seq(1) + EID(4, big-endian) + auth_tag(4) + ciphertext.
                // Payload is the ENTIRE raw frame.
                var eidHex: String? = null
                if (raw.size >= 6) {
                    var eid = 0L
                    for (i in 2 until 6) eid = (eid shl 8) or (raw[i].toLong() and 0xFF)
                    eidHex = eid.toString(16)
                }
                ParsedPacket(0, eidHex, raw)
            }

            1 -> {
                // Unencrypted: 40-bit big-endian header, no EID. Payload is raw[5...].
                // No EID means this frame is dropped before the queue (§1a).
                if (raw.size < 5) ParsedPacket(1, null, raw)
                else ParsedPacket(1, null, raw.copyOfRange(5, raw.size))
            }

            2 -> {
                // AES-EAX: ver(1) + salt(2) + EID(8, little-endian) + ciphertext(0-9) + tag(4).
                if (raw.size < 15) ParsedPacket(2, null, raw)
                else {
                    var eid = 0L
                    for (i in 0 until 8) eid = eid or ((raw[3 + i].toLong() and 0xFF) shl (8 * i))
                    // EID is an unsigned 64-bit value; a high bit set would make a
                    // signed toString(16) print a minus sign. Format unsigned to
                    // match Python format(eid, 'x').
                    val payload = raw.copyOfRange(11, raw.size - 4)
                    ParsedPacket(2, java.lang.Long.toUnsignedString(eid, 16), payload)
                }
            }

            else -> {
                // Unknown version: no EID -> dropped before the queue (§1a).
                ParsedPacket(version, null, raw)
            }
        }
    }
}
