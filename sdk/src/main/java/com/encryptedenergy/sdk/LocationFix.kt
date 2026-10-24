package com.encryptedenergy.sdk

import kotlin.math.abs

/**
 * A GPS fix eligible for stamping onto packets (plan §1a). Pure JVM so the
 * staleness gate and ingest sanity rules unit-test without a device.
 *
 * Construct via [of], never directly from platform values: ee-web validates
 * per-packet fields and rejects the ENTIRE batch over one bad value, so a
 * single junk fix must be filtered here, not discovered server-side.
 *
 * [accuracyM] is null when the platform fix carried no usable accuracy
 * estimate; PacketJson omits accuracy_m in that case (the field is optional
 * server-side).
 */
internal data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Double?,
    /** When the fix was captured (epoch millis) — becomes `position_at`. */
    val capturedAtMillis: Long,
) {

    /**
     * The 25 s staleness gate, same constant as iOS 1.2 fixForPacket and the
     * Pi worker's GPS TTL. A stale fix must never be stamped on a packet —
     * that was the whole stale-location incident (7/31). Future-dated fixes
     * (clock skew) count as fresh; the server clamps.
     */
    fun isFresh(nowMillis: Long): Boolean =
        nowMillis - capturedAtMillis <= MAX_AGE_MILLIS

    companion object {
        const val MAX_AGE_SECONDS = 25L
        const val MAX_AGE_MILLIS = MAX_AGE_SECONDS * 1000

        /** ee-web rejects accuracy_m > 10000; a >10 km fix is also useless. */
        const val MAX_ACCURACY_M = 10_000.0

        /**
         * Applies ee-web's ingest sanity rules client-side:
         * - non-finite lat/lon -> null (0006): NaN slips through the range
         *   check (abs(NaN) > 90 is false) and would only fail later inside
         *   JSON serialization; reachable now that Settings accepts typed
         *   coordinates.
         * - (0, 0) -> null: the null-island tell, batch-rejected upstream.
         * - lat/lon out of range -> null: batch-rejected upstream.
         * - capture time <= 0 -> null: can't pass an honest staleness gate,
         *   and position_at <= 0 is batch-rejected upstream.
         * - accuracy > 10 km -> null: coverage-useless, and batch-rejected
         *   upstream.
         * - accuracy <= 0 or absent -> fix kept, accuracy omitted (optional
         *   field; <= 0 is batch-rejected upstream).
         */
        fun of(
            latitude: Double,
            longitude: Double,
            accuracyM: Double?,
            capturedAtMillis: Long,
        ): LocationFix? {
            if (!latitude.isFinite() || !longitude.isFinite()) return null
            if (latitude == 0.0 && longitude == 0.0) return null
            if (abs(latitude) > 90.0 || abs(longitude) > 180.0) return null
            if (capturedAtMillis <= 0) return null
            if (accuracyM != null && (!accuracyM.isFinite() || accuracyM > MAX_ACCURACY_M)) return null
            return LocationFix(
                latitude = latitude,
                longitude = longitude,
                accuracyM = accuracyM?.takeIf { it > 0.0 },
                capturedAtMillis = capturedAtMillis,
            )
        }
    }
}
