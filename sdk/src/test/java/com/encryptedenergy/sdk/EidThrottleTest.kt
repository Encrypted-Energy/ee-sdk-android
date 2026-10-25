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
}
