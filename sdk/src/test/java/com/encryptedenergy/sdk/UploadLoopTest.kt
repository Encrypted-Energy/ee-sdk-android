package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The loop's own jobs around BatchSender (which has its own contract tests):
 * pacing (one server batch per tick), requeue of the undecided remainder in
 * original order, forwarded-counter bumps, and the outcome callback.
 * Everything runs through [UploadLoop.tick] — no executor, no clock.
 */
class UploadLoopTest {

    private fun packet(i: Int) = UploadPacket(
        eidHex = "eid$i",
        payloadB64 = "AQID",
        rssi = -50,
        timestampSeconds = 1_785_545_352L + i,
        latitude = 40.5,
        longitude = -74.25,
        positionAtSeconds = 1_785_545_340L + i,
        accuracyM = 14.0,
    )

    private fun loaded(n: Int): PacketQueue =
        PacketQueue().also { q -> (1..n).forEach { q.add(packet(it)) } }

    @Test
    fun emptyQueueMakesNoWireCallAndNoCallback() {
        val loop = UploadLoop(
            ingest = { fail("wire call on empty queue"); EEClient.Result.Ok(200) },
            queue = PacketQueue(),
            counters = Counters(),
            onOutcome = { fail("callback on empty queue") },
        )
        loop.tick()
    }

    @Test
    fun okDrainsQueueAndCountsForwarded() {
        val queue = loaded(3)
        val counters = Counters()
        var outcome: BatchSender.Outcome? = null
        UploadLoop({ EEClient.Result.Ok(200) }, queue, counters) { outcome = it }.tick()
        assertEquals(0, queue.size())
        assertEquals(3, counters.snapshotAndReset().forwarded)
        assertEquals(3, outcome!!.forwarded)
    }

    @Test
    fun transientRequeuesInOriginalOrderAndCountsNothing() {
        val queue = loaded(3)
        val counters = Counters()
        UploadLoop({ EEClient.Result.Transient("HTTP 502") }, queue, counters).tick()
        assertEquals(listOf("eid1", "eid2", "eid3"), queue.snapshot().map { it.eidHex })
        assertEquals(0, counters.snapshotAndReset().forwarded)
    }

    @Test
    fun unauthorizedRequeuesAndFlagsOutcome() {
        val queue = loaded(2)
        var outcome: BatchSender.Outcome? = null
        UploadLoop({ EEClient.Result.Unauthorized }, queue, Counters()) { outcome = it }.tick()
        assertEquals(2, queue.size())
        assertTrue(outcome!!.unauthorized)
    }

    @Test
    fun terminalDropsBatch() {
        val queue = loaded(3)
        val counters = Counters()
        var outcome: BatchSender.Outcome? = null
        UploadLoop({ EEClient.Result.Terminal(400) }, queue, counters) { outcome = it }.tick()
        assertEquals(0, queue.size())
        assertEquals(0, counters.snapshotAndReset().forwarded)
        assertEquals(3, outcome!!.dropped)
    }

    @Test
    fun outcomeCarries422Reasons() {
        val queue = loaded(1)
        var outcome: BatchSender.Outcome? = null
        UploadLoop(
            {
                EEClient.Result.Terminal(
                    422, """{"ok":false,"invalid_reasons":["packet[0]: missing eid"]}"""
                )
            },
            queue, Counters(),
        ) { outcome = it }.tick()
        assertEquals(listOf("packet[0]: missing eid"), outcome!!.droppedReasons)
    }

    @Test
    fun drainsAtMostOneServerBatchPerTick() {
        // Pacing: a tick takes MAX_BATCH off the queue; the rest wait for the
        // next tick rather than hammering the server in one pass.
        val queue = loaded(PacketQueue.MAX_BATCH + 1)
        var calls = 0
        UploadLoop({ calls++; EEClient.Result.Ok(200) }, queue, Counters()).tick()
        assertEquals(1, calls)
        assertEquals(1, queue.size())
    }

    @Test
    fun bountySurfacesInOutcome() {
        var outcome: BatchSender.Outcome? = null
        UploadLoop(
            { EEClient.Result.Ok(200, bountyEarned = 2) }, loaded(2), Counters()
        ) { outcome = it }.tick()
        assertEquals(2, outcome!!.bountyEarned)
    }
}
