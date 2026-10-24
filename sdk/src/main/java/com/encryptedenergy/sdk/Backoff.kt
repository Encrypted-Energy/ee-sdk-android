package com.encryptedenergy.sdk

import java.util.Random

/**
 * Exponential backoff with equal jitter, for retrying transient outcomes
 * (self-mint 503/429 against the 300/hr global cap, dead-network verify).
 * Pure JVM; the caller owns the clock and the sleep — this only computes
 * delays, so the policy unit-tests deterministically with a seeded [random].
 *
 * Delay for attempt n is uniform in [cap/2, cap] where
 * cap = min(base * 2^n, capMillis) — the "equal jitter" scheme: half the
 * ceiling is guaranteed spacing, half is randomized so a fleet of phones that
 * all failed together (one 503 from the mint cap) doesn't retry together.
 *
 * Not thread-safe by design: each retry loop owns one instance.
 */
internal class Backoff(
    private val baseMillis: Long = DEFAULT_BASE_MILLIS,
    private val capMillis: Long = DEFAULT_CAP_MILLIS,
    private val random: Random = Random(),
) {

    private var attempt = 0

    /** Call after a success so the next failure starts the ladder over. */
    fun reset() {
        attempt = 0
    }

    /** The next delay, in millis. Advances the attempt counter. */
    fun nextDelayMillis(): Long {
        // 1L shl 62 overflows nothing we can reach: clamp the exponent well
        // before the shift can wrap.
        val exponent = minOf(attempt, MAX_EXPONENT)
        val ceiling = minOf(baseMillis shl exponent, capMillis)
        attempt++
        val half = ceiling / 2
        // nextDouble() in [0,1): delay in [half, ceiling], never past the cap.
        return half + (random.nextDouble() * half).toLong()
    }

    companion object {
        /** First retry ~2 s — polite to the mint cap, invisible to a human. */
        const val DEFAULT_BASE_MILLIS = 2_000L

        /** Ceiling 10 min: a capped-out mint window clears within the hour. */
        const val DEFAULT_CAP_MILLIS = 10 * 60_000L

        private const val MAX_EXPONENT = 20
    }
}
