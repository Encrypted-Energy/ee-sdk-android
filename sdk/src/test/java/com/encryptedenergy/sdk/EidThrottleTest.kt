package com.encryptedenergy.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EidThrottleTest {

    @Test
    fun forwardsFirstSighting() {
        val t = EidThrottle(3_000)
        assertTrue(t.shouldForward("98b628e", 1_000))
    }

    @Test
    fun suppressesWithinWindow() {
        val t = EidThrottle(3_000)
        assertTrue(t.shouldForward("98b628e", 1_000))
        assertFalse(t.shouldForward("98b628e", 2_000))
        assertFalse(t.shouldForward("98b628e", 3_999))
    }

    @Test
    fun forwardsAgainAfterWindow() {
        val t = EidThrottle(3_000)
        assertTrue(t.shouldForward("98b628e", 1_000))
        assertTrue(t.shouldForward("98b628e", 4_000))
    }

    @Test
    fun tracksEidsIndependently() {
        val t = EidThrottle(3_000)
        assertTrue(t.shouldForward("98b628e", 1_000))
        assertTrue(t.shouldForward("efcacfae", 1_001))
        assertFalse(t.shouldForward("98b628e", 1_002))
    }

    @Test
    fun suppressionDoesNotExtendTheWindow() {
        // A sighting rejected mid-window must not reset the clock: the EID
        // becomes forwardable windowMillis after the last FORWARD, not the
        // last sighting.
        val t = EidThrottle(3_000)
        assertTrue(t.shouldForward("98b628e", 1_000))
        assertFalse(t.shouldForward("98b628e", 3_500))
        assertTrue(t.shouldForward("98b628e", 4_100))
    }

    @Test
    fun boundedAtMaxEntriesEvictingLeastRecentlyForwarded() {
        // 0.2.0 item iv: a rotating-identifier flood must not grow the map
        // without limit. Fill past the cap, then confirm the OLDEST eid was
        // evicted (forwardable again inside its window) while a fresh one
        // is still throttled.
        val window = 1_000_000L
        val t = EidThrottle(window)
        (0..EidThrottle.MAX_ENTRIES).forEach { i ->
            assertTrue(t.shouldForward("eid$i", 1_000L + i))
        }
        // eid0 fell off the LRU: inside the window but forwardable again.
        assertTrue(t.shouldForward("eid0", 10_000L))
        // The newest entry is still tracked and throttled.
        assertFalse(t.shouldForward("eid${EidThrottle.MAX_ENTRIES}", 10_001L))
    }

    @Test
    fun throttledLookupRefreshesLruRecency() {
        // Access order, not insert order: an EID that keeps being SEEN (even
        // if suppressed) is hot and must not be the eviction victim.
        val t = EidThrottle(1_000_000L)
        assertTrue(t.shouldForward("hot", 1_000))
        (1 until EidThrottle.MAX_ENTRIES).forEach { i ->
            assertTrue(t.shouldForward("eid$i", 2_000L + i))
        }
        // Touch "hot" (suppressed, but refreshes recency), then overflow by
        // one: the victim must be eid1, not hot.
        assertFalse(t.shouldForward("hot", 500_000))
        assertTrue(t.shouldForward("overflow", 600_000))
        assertFalse(t.shouldForward("hot", 600_001))
        assertTrue(t.shouldForward("eid1", 600_002))
    }
}
