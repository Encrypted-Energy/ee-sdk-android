package com.encryptedenergy.sdk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Heartbeat body builder (plan §1b). Pure JVM so it unit-tests without a
 * device. All fields are optional server-side.
 *
 * `worker_version` identifies this as the SDK, not the consumer app: fleet
 * gateways report `ee-sdk-android/<version>` so the ops side can tell SDK
 * traffic from the first-party app at a glance (and the C9 kill switch can be
 * reasoned about per worker string).
 */
internal object HeartbeatPayload {

    const val WORKER_VERSION = "ee-sdk-android/" + SdkVersion.STRING

    data class Runtime(
        val osVersion: String,
        val uptimeSeconds: Long,
        val lastPacketAtMillis: Long?,
        /** "fix" | "no_fix" | "dongle_missing" (permission denied) — §1b mapping. */
        val gpsStatus: String,
        /** When the newest fix was captured, any age (0004+). */
        val lastKnownPositionAtMillis: Long? = null,
        /** "active" | "backgrounded" — server client_state (0005+). */
        val clientState: String? = null,
        /** DeviceRollup.toJson output — devices_seen block (0005+). */
        val devicesSeen: Json.Raw? = null,
        /**
         * "full" | "reduced" — ScanPolicy state (0009+). ee-web ignores
         * unpermitted params today; a later gateways column makes the
         * adaptive policy observable fleet-wide with no app release.
         */
        val scanMode: String? = null,
    )

    fun build(runtime: Runtime, deltas: Counters.Snapshot): String {
        val fields = mutableListOf<Pair<String, Any>>(
            "worker_version" to WORKER_VERSION,
            "platform" to "android",
            "os_version" to runtime.osVersion,
            "uptime_seconds" to runtime.uptimeSeconds,
            "gps_status" to runtime.gpsStatus,
            "packets_forwarded_delta" to deltas.forwarded,
            "packets_dropped_no_fix_delta" to deltas.droppedNoFix,
            "packets_heard_delta" to deltas.heard,
            "ble_scan_errors_delta" to deltas.scanErrors,
        )
        runtime.lastPacketAtMillis?.let { fields.add("last_packet_at" to isoUtc(it)) }
        runtime.lastKnownPositionAtMillis?.let {
            fields.add("last_known_position_at" to isoUtc(it))
        }
        runtime.clientState?.let { fields.add("status" to it) }
        runtime.devicesSeen?.let { fields.add("devices_seen" to it) }
        runtime.scanMode?.let { fields.add("scan_mode" to it) }
        return Json.obj(fields)
    }

    /** YYYY-MM-DDTHH:MM:SSZ UTC (§1b). */
    fun isoUtc(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
