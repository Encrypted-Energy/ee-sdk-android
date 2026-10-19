package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EEStateTest {

    @Test
    fun theSixFrozenStatesAreAllPresent() {
        // The state set is part of the frozen contract: a partner's when() over
        // it must stay exhaustive across SDK versions. Lock the exact roster.
        val expected = setOf(
            "UNCONFIGURED",
            "READY",
            "AWAITING_PERMISSIONS",
            "RUNNING",
            "PAUSED_REMOTE",
            "STOPPED",
        )
        assertEquals(expected, EEState.entries.map { it.name }.toSet())
    }

    @Test
    fun orderIsTheDeclaredLifecycleOrder() {
        // Ordinals are part of the surface too (persisted, compared); pin them.
        assertEquals(0, EEState.UNCONFIGURED.ordinal)
        assertEquals(1, EEState.READY.ordinal)
        assertEquals(2, EEState.AWAITING_PERMISSIONS.ordinal)
        assertEquals(3, EEState.RUNNING.ordinal)
        assertEquals(4, EEState.PAUSED_REMOTE.ordinal)
        assertEquals(5, EEState.STOPPED.ordinal)
    }

    @Test
    fun valueOfRoundTripsEveryEntry() {
        assertTrue(EEState.entries.all { EEState.valueOf(it.name) == it })
    }
}
