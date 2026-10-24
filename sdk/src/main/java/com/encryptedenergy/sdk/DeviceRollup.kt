package com.encryptedenergy.sdk

/**
 * 24 h devices-heard rollup for the heartbeat's devices_seen block (plan
 * §1b), matching the iOS wire shape. Pure JVM. ee-web keeps at most 50
 * devices and only the keys eid/packets/last_rssi/last_seen_at, so we cap
 * and shape identically client-side.
 */
internal class DeviceRollup(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {

    private class Entry(
        var packets: Int,
        var lastRssi: Int,
        var lastSeenAtMillis: Long,
    )

    /** UI-facing view of one device (plan §5 S3). */
    data class Seen(
        val eidHex: String,
        val packets: Int,
        val lastRssi: Int,
        val lastSeenAtMillis: Long,
    )

    private val devices = HashMap<String, Entry>()

    @Synchronized
    fun record(eidHex: String, rssi: Int, atMillis: Long) {
        val entry = devices[eidHex]
        if (entry == null) {
            devices[eidHex] = Entry(packets = 1, lastRssi = rssi, lastSeenAtMillis = atMillis)
        } else {
            entry.packets++
            entry.lastRssi = rssi
            if (atMillis > entry.lastSeenAtMillis) entry.lastSeenAtMillis = atMillis
        }
    }

    @Synchronized
    fun deviceCount(nowMillis: Long): Int {
        prune(nowMillis)
        return devices.size
    }

    /** Pruned, newest first, capped like the wire block — feeds the Devices tab. */
    @Synchronized
    fun snapshot(nowMillis: Long): List<Seen> {
        prune(nowMillis)
        return devices.entries
            .sortedByDescending { it.value.lastSeenAtMillis }
            .take(MAX_DEVICES)
            .map { (eid, e) -> Seen(eid, e.packets, e.lastRssi, e.lastSeenAtMillis) }
    }

    /** Total packets across the 24 h window — the Dashboard "Heard 24h" tile. */
    @Synchronized
    fun packets24h(nowMillis: Long): Int {
        prune(nowMillis)
        return devices.values.sumOf { it.packets }
    }

    /** {"as_of": iso, "window_hours": 24, "devices": [...]} — iOS parity. */
    @Synchronized
    fun toJson(nowMillis: Long): Json.Raw {
        prune(nowMillis)
        val top = devices.entries
            .sortedByDescending { it.value.lastSeenAtMillis }
            .take(MAX_DEVICES)
            .map { (eid, e) ->
                Json.Raw(
                    Json.obj(
                        listOf(
                            "eid" to eid,
                            "last_rssi" to e.lastRssi,
                            "last_seen_at" to HeartbeatPayload.isoUtc(e.lastSeenAtMillis),
                            "packets" to e.packets,
                        )
                    )
                )
            }
        return Json.Raw(
            Json.obj(
                listOf(
                    "as_of" to HeartbeatPayload.isoUtc(nowMillis),
                    "window_hours" to (windowMillis / MILLIS_PER_HOUR).toInt(),
                    "devices" to Json.arr(top),
                )
            )
        )
    }

    private fun prune(nowMillis: Long) {
        devices.values.removeIf { nowMillis - it.lastSeenAtMillis > windowMillis }
    }

    companion object {
        private const val MILLIS_PER_HOUR = 3_600_000L
        const val DEFAULT_WINDOW_MILLIS = 24 * MILLIS_PER_HOUR
        /** ee-web DEVICES_SEEN_MAX — extra entries are discarded upstream. */
        const val MAX_DEVICES = 50
    }
}
