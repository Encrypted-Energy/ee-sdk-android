package com.encryptedenergy.sdk

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Scan power policy (0009, roadmap 3b) — decides whether the BLE scanner
 * runs full rate (SCAN_MODE_LOW_LATENCY) or reduced (SCAN_MODE_BALANCED).
 * Pure JVM so the whole policy unit-tests without a device.
 *
 * The triad, same spec as iOS 1.3:
 *  - charging            -> FULL     (windowsill-as-fixed-gateway: a 5-15 s
 *                                     transit can't be re-asked, and
 *                                     plugged-in listening is free)
 *  - on battery, moving  -> FULL     (unrepeatable ground)
 *  - on battery, stationary 5+ min -> REDUCED (repeat local devices re-mint
 *                                     daily; hearing them an hour later
 *                                     costs nothing)
 *
 * HARD FLOOR: location cadence never adapts — the 25 s packet gate and
 * Hubble's ±30 s location-vs-detection filter need fresh fixes whenever
 * forwarding. Only the scanner's radio duty cycle changes, which is also why
 * there is no valve like iOS 0005's: reduced SCANNING cannot starve the fix
 * stream, so the failure mode the valve guards against does not exist here.
 *
 * Motion is inferred from the location stream the pipeline already runs (no
 * play-services, no extra sensor): displacement from an anchor fix, with the
 * threshold widened by both fixes' accuracy radii so network-provider tower
 * jumps (±1500 m while parked on a desk) never read as movement. The anchor
 * only moves when movement is detected, so slow walking accumulates
 * displacement against a fixed anchor and still trips the threshold.
 *
 * Hysteresis: a 5 min dwell before reducing (red lights and short stops
 * never trigger); restoration is instant on movement or plug-in. Worst-case
 * flip rate is once per dwell — far under the framework's 5-scan-starts-per-
 * 30 s throttle.
 *
 * @Synchronized throughout: fixes and battery broadcasts arrive on the main
 * looper, but the heartbeat executor reads [mode] for scan_mode.
 */
internal class ScanPolicy {

    enum class Mode { FULL, REDUCED }

    private var charging = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorAccuracyM = 0.0
    private var hasAnchor = false
    private var lastMotionMillis = 0L

    /** Arm the dwell: full rate for the first 5 min after every (re)start. */
    @Synchronized
    fun reset(nowMillis: Long) {
        hasAnchor = false
        lastMotionMillis = nowMillis
    }

    /** Returns true when the mode may have changed (caller retunes). */
    @Synchronized
    fun onCharging(chargingNow: Boolean): Boolean {
        if (charging == chargingNow) return false
        charging = chargingNow
        return true
    }

    /** Feed every accepted PLATFORM fix. Fixed overrides never come here. */
    @Synchronized
    fun onFix(fix: LocationFix) {
        val accuracy = fix.accuracyM ?: DEFAULT_ACCURACY_M
        if (!hasAnchor) {
            // The first fix is a position, not movement — a phone on a desk
            // must not re-arm the dwell on every warm start.
            anchor(fix, accuracy)
            return
        }
        val threshold = MOVE_FLOOR_M + anchorAccuracyM + accuracy
        if (distanceMeters(anchorLat, anchorLon, fix.latitude, fix.longitude) > threshold) {
            lastMotionMillis = maxOf(lastMotionMillis, fix.capturedAtMillis)
            anchor(fix, accuracy)
        }
    }

    @Synchronized
    fun mode(nowMillis: Long): Mode = when {
        charging -> Mode.FULL
        nowMillis - lastMotionMillis < DWELL_MILLIS -> Mode.FULL
        else -> Mode.REDUCED
    }

    /** Diagnostics label for why the mode is what it is. */
    @Synchronized
    fun reason(nowMillis: Long): String = when {
        charging -> "charging"
        nowMillis - lastMotionMillis < DWELL_MILLIS -> "moving"
        else -> "stationary on battery"
    }

    private fun anchor(fix: LocationFix, accuracy: Double) {
        anchorLat = fix.latitude
        anchorLon = fix.longitude
        anchorAccuracyM = accuracy
        hasAnchor = true
    }

    companion object {
        /** Stationary this long on battery before reducing — iOS 1.3 parity. */
        const val DWELL_MILLIS = 5 * 60_000L

        /** Minimum displacement that can ever count as movement. */
        const val MOVE_FLOOR_M = 50.0

        /** Accuracy assumed for fixes that carry no estimate. */
        const val DEFAULT_ACCURACY_M = 50.0

        private const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Equirectangular approximation — under 0.1% error at movement
         * scale (< 10 km), which the accuracy-widened threshold absorbs.
         */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
            return EARTH_RADIUS_M * sqrt(dLat * dLat + dLon * dLon)
        }
    }
}
