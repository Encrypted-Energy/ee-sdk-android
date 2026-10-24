package com.encryptedenergy.sdk

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    /** Equal jitter: every delay lands in [ceiling/2, ceiling]. */
    @Test
    fun delaysStayInsideTheJitterWindow() {
        val b = Backoff(baseMillis = 1_000, capMillis = 60_000, random = Random(42))
        var ceiling = 1_000L
        repeat(10) {
            val d = b.nextDelayMillis()
            assertTrue("delay $d below half of $ceiling", d >= ceiling / 2)
            assertTrue("delay $d above ceiling $ceiling", d <= ceiling)
            ceiling = minOf(ceiling * 2, 60_000)
        }
    }

    @Test
    fun ceilingIsCappedForever() {
        val b = Backoff(baseMillis = 1_000, capMillis = 8_000, random = Random(7))
        // base 1s reaches the 8s cap on the 4th attempt; everything later
        // stays in [4s, 8s] — including attempts far past the exponent clamp.
        repeat(3) { b.nextDelayMillis() }
        repeat(50) {
            val d = b.nextDelayMillis()
            assertTrue("delay $d outside capped window", d in 4_000..8_000)
        }
    }

    @Test
    fun resetStartsTheLadderOver() {
        val b = Backoff(baseMillis = 2_000, capMillis = 600_000, random = Random(1))
        repeat(5) { b.nextDelayMillis() }
        b.reset()
        val d = b.nextDelayMillis()
        assertTrue("post-reset delay $d not back at the base window", d in 1_000..2_000)
    }

    @Test
    fun defaultsMatchTheMintCapStory() {
        assertEquals(2_000L, Backoff.DEFAULT_BASE_MILLIS)
        assertEquals(600_000L, Backoff.DEFAULT_CAP_MILLIS)
    }

    @Test
    fun jitterActuallyVaries() {
        val b = Backoff(baseMillis = 600_000, capMillis = 600_000, random = Random(3))
        val seen = HashSet<Long>()
        repeat(8) { seen.add(b.nextDelayMillis()) }
        assertTrue("jitter produced ${seen.size} distinct delays", seen.size >= 6)
    }
}
