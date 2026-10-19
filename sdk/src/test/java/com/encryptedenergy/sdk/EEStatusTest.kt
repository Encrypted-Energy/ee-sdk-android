package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EEStatusTest {

    @Test
    fun countersDefaultToZeroAndIdsToNull() {
        // A fresh snapshot carries only a state; everything else is the
        // nothing-has-happened-yet baseline.
        val status = EEStatus(state = EEState.READY)
        assertEquals(EEState.READY, status.state)
        assertEquals(0L, status.pingsHeard)
        assertEquals(0L, status.packetsForwarded)
        assertEquals(0, status.queueDepth)
        assertNull(status.gatewayPublicId)
        assertNull(status.lastHeartbeatAt)
    }

    @Test
    fun allFieldsAreCarriedThrough() {
        val status = EEStatus(
            state = EEState.RUNNING,
            pingsHeard = 42,
            packetsForwarded = 17,
            queueDepth = 3,
            gatewayPublicId = "gw_pub_123",
            lastHeartbeatAt = 1_700_000_000_000,
        )
        assertEquals(EEState.RUNNING, status.state)
        assertEquals(42L, status.pingsHeard)
        assertEquals(17L, status.packetsForwarded)
        assertEquals(3, status.queueDepth)
        assertEquals("gw_pub_123", status.gatewayPublicId)
        assertEquals(1_700_000_000_000L, status.lastHeartbeatAt)
    }

    @Test
    fun copyTweaksOneFieldAndKeepsTheRest() {
        // The facade advances state with copy(state = ...); prove the counters
        // ride along untouched.
        val running = EEStatus(state = EEState.RUNNING, pingsHeard = 9, queueDepth = 2)
        val paused = running.copy(state = EEState.PAUSED_REMOTE)
        assertEquals(EEState.PAUSED_REMOTE, paused.state)
        assertEquals(9L, paused.pingsHeard)
        assertEquals(2, paused.queueDepth)
    }
}
