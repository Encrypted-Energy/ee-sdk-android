package com.encryptedenergy.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * Platform location source (plan §1a). Framework LocationManager only — no
 * play-services dependency, keeping the zero-third-party-deps discipline.
 *
 * Subscribes to GPS + network providers; latest fix wins. Fixes at exactly
 * (0, 0) are discarded: ee-web's ingest validation rejects the entire batch
 * over one null-island packet, so a bogus fix must never reach the queue.
 *
 * Delta from gateway-android: the fixed-location override is NOT ported. It
 * is a first-party operator feature ("this gateway is HERE", S5); a fleet
 * SDK phone is a moving gateway by definition, and a partner-settable static
 * location would be a coverage-spoofing lever we don't want to hand out.
 *
 * Listener callbacks arrive on the main looper. [latest] is @Volatile
 * because the heartbeat and upload executors read it from background
 * threads.
 */
internal class LocationProvider(private val context: Context) {

    @Volatile private var latest: LocationFix? = null
    private var listening = false

    /**
     * Every accepted fix, main looper (0009) — feeds ScanPolicy's motion
     * inference.
     */
    var onFix: ((LocationFix) -> Unit)? = null

    // Explicit object, NOT a SAM lambda: on API 26-28 the framework still
    // calls onStatusChanged, which a lambda-backed listener doesn't implement
    // (AbstractMethodError at runtime).
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            toFix(location)?.let {
                latest = it
                onFix?.invoke(it)
            }
        }

        @Deprecated("unused since API 29; implemented for API 26-28 safety")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** No-op unless location permission is granted (caller drives the flow). */
    @SuppressLint("MissingPermission")
    fun start() {
        if (listening || !ScanPermissions.granted(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            for (provider in PROVIDERS) {
                if (lm.allProviders.contains(provider)) {
                    lm.requestLocationUpdates(
                        provider, UPDATE_INTERVAL_MILLIS, 0f, listener, Looper.getMainLooper()
                    )
                }
            }
            // Seed from the freshest framework-cached fix so a warm start
            // doesn't wait out the first update; the staleness gate still
            // decides whether it's usable.
            var best: Location? = null
            for (provider in lm.allProviders) {
                val cached = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || cached.time > best.time) best = cached
            }
            // The seed also reaches onFix: it anchors motion inference, so
            // the first live fix far from a stale cached position reads as
            // movement (conservative — errs toward full rate).
            best?.let { seed ->
                toFix(seed)?.let {
                    latest = it
                    onFix?.invoke(it)
                }
            }
            listening = true
        } catch (_: SecurityException) {
            // Permission revoked between check and call: stay stopped;
            // status() reports dongle_missing.
        }
    }

    fun stop() {
        if (!listening) return
        (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
            ?.removeUpdates(listener)
        listening = false
    }

    /** Fix usable for a packet heard at [nowMillis], else null -> drop (§1a). */
    fun freshFix(nowMillis: Long): LocationFix? = latest?.takeIf { it.isFresh(nowMillis) }

    /** Most recent fix regardless of age — heartbeat last_known_position_at. */
    fun lastFix(): LocationFix? = latest

    /** Heartbeat gps_status (§1b): permission denied maps to dongle_missing. */
    fun status(nowMillis: Long): String = when {
        !ScanPermissions.granted(context) -> "dongle_missing"
        freshFix(nowMillis) != null -> "fix"
        else -> "no_fix"
    }

    // LocationFix.of applies the ingest sanity rules (null island, range,
    // time, accuracy bounds) so a junk platform fix never reaches the queue.
    private fun toFix(location: Location): LocationFix? = LocationFix.of(
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        capturedAtMillis = location.time,
    )

    companion object {
        private const val UPDATE_INTERVAL_MILLIS = 5_000L
        private val PROVIDERS =
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    }
}
