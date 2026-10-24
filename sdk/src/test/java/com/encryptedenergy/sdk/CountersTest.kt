package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class CountersTest {

    @Test
    fun snapshotReflectsIncrements() {
        val c = Counters()
        c.incHeard(); c.incHeard(); c.incForwarded(); c.incDroppedNoFix()
        val s = c.snapshotAndReset()
        assertEquals(2, s.heard)
        assertEquals(1, s.forwarded)
        assertEquals(1, s.droppedNoFix)
        assertEquals(0, s.scanErrors)
    }

    @Test
    fun snapshotResets() {
        val c = Counters()
        c.incHeard()
        c.snapshotAndReset()
        val s = c.snapshotAndReset()
        assertEquals(0, s.heard)
    }

    @Test
    fun restoreAddsOnTopOfNewCounts() {
        // Worker semantics (§1b): counts arriving while the failed POST was
        // in flight must survive alongside the restored snapshot.
        val c = Counters()
        c.incHeard(); c.incHeard()
        val failed = c.snapshotAndReset()
        c.incHeard()                       // heard while POST was failing
        c.restore(failed)
        val s = c.snapshotAndReset()
        assertEquals(3, s.heard)
    }
}
