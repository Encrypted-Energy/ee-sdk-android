package com.encryptedenergy.sdk

/**
 * Ingest body builder for POST /api/v1/gateways/packets (plan §1a). Field
 * names and types mirror the iOS 1.2 wire format byte-for-byte: unix-seconds
 * integers for timestamp/position_at, doubles for coords/accuracy, unpadded
 * lowercase hex eid. accuracy_m is omitted when the fix carried none.
 */
internal object PacketJson {

    fun body(packets: List<UploadPacket>): String =
        Json.obj(listOf("packets" to Json.arr(packets.map { Json.Raw(packet(it)) })))

    fun packet(p: UploadPacket): String {
        val fields = mutableListOf<Pair<String, Any>>(
            "payload_b64" to p.payloadB64,
            "rssi" to p.rssi,
            "timestamp" to p.timestampSeconds,
            "latitude" to p.latitude,
            "longitude" to p.longitude,
            "position_at" to p.positionAtSeconds,
            "eid" to p.eidHex,
        )
        p.accuracyM?.let { fields.add("accuracy_m" to it) }
        return Json.obj(fields)
    }
}
