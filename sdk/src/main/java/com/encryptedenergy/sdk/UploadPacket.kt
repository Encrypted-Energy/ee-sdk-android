package com.encryptedenergy.sdk

/**
 * One BLE packet stamped with a fresh GPS fix, ready for upload (plan §1a).
 * [eidHex] is non-null by construction: BleScanner drops eid-less frames
 * before onPacket, and ee-web 422s any batch containing one.
 */
internal data class UploadPacket(
    val eidHex: String,
    val payloadB64: String,
    val rssi: Int,
    /** When the packet was heard (unix seconds). */
    val timestampSeconds: Long,
    val latitude: Double,
    val longitude: Double,
    /** When the stamped fix was captured (unix seconds) -> `position_at`. */
    val positionAtSeconds: Long,
    val accuracyM: Double?,
)
