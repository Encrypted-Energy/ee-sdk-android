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
    fun throwingOutcomeCallbackIsSwallowedAfterQueueWorkCommits() {
        // C6: an uncaught throw would cancel scheduleWithFixedDelay forever.
        val queue = loaded(2)
        val counters = Counters()
        val loop = UploadLoop({ EEClient.Result.Ok(200) }, queue, counters) {
            throw IllegalStateException("host callback bug")
        }
        loop.tick()   // must not propagate
        assertEquals(0, queue.size())
        assertEquals(2, counters.snapshotAndReset().forwarded)
    }

    @Test
    fun bountySurfacesInOutcome() {
        var outcome: BatchSender.Outcome? = null
        UploadLoop(
            { EEClient.Result.Ok(200, bountyEarned = 2) }, loaded(2), Counters()
        ) { outcome = it }.tick()
        assertEquals(2, outcome!!.bountyEarned)
    }

    // --- 429 cooldown (0.2.0 item vi) ---

    @Test
    fun rateLimitArmsCooldownAndSkipsTicksInsideIt() {
        var calls = 0
        val queue = loaded(2)
        val loop = UploadLoop(
            { calls++; EEClient.Result.Transient("HTTP 429") }, queue, Counters()
        )
        loop.tick(0)
        assertEquals(1, calls)
        // Inside the 60 s cooldown: no wire call, queue untouched.
        loop.tick(UploadLoop.COOLDOWN_BASE_MILLIS - 1)
        assertEquals(1, calls)
        assertEquals(2, queue.size())
        // Cooldown expired: the loop tries again.
        loop.tick(UploadLoop.COOLDOWN_BASE_MILLIS)
        assertEquals(2, calls)
    }

    @Test
    fun repeatedRateLimitDoublesUpToTheCap() {
        var calls = 0
        val loop = UploadLoop(
            { calls++; EEClient.Result.Transient("HTTP 429") }, loaded(1), Counters()
        )
        var now = 0L
        loop.tick(now)                       // arms 60 s
        now += UploadLoop.COOLDOWN_BASE_MILLIS
        loop.tick(now)                       // 429 again: arms 120 s
        assertEquals(2, calls)
        loop.tick(now + 2 * UploadLoop.COOLDOWN_BASE_MILLIS - 1)
        assertEquals(2, calls)               // still cooling at +119 s
        now += 2 * UploadLoop.COOLDOWN_BASE_MILLIS
        loop.tick(now)
        assertEquals(3, calls)
        // Many more bursts: the step must stop at the cap.
        repeat(10) {
            now += UploadLoop.COOLDOWN_CAP_MILLIS
            loop.tick(now)
        }
        val before = calls
        loop.tick(now + UploadLoop.COOLDOWN_CAP_MILLIS - 1)
        assertEquals(before, calls)          // capped, not beyond
        loop.tick(now + UploadLoop.COOLDOWN_CAP_MILLIS)
        assertEquals(before + 1, calls)
    }

    @Test
    fun acceptedBatchResetsTheLadder() {
        var rateLimit = true
        var calls = 0
        val queue = loaded(3)
        val loop = UploadLoop(
            {
                calls++
                if (rateLimit) EEClient.Result.Transient("HTTP 429")
                else EEClient.Result.Ok(200)
            },
            queue, Counters()
        )
        loop.tick(0)                                      // arms 60 s
        rateLimit = false
        loop.tick(UploadLoop.COOLDOWN_BASE_MILLIS)        // accepted: drains + resets
        // Ladder disarmed: a fresh 429 starts back at base, and the very
        // next tick (plain 10 s cadence) is not blocked by any old state.
        rateLimit = true
        queue.add(packet(9))
        loop.tick(UploadLoop.COOLDOWN_BASE_MILLIS + 1)    // fresh 429 at base
        assertEquals(3, calls)
        loop.tick(2 * UploadLoop.COOLDOWN_BASE_MILLIS)    // still cooling (fresh 60 s)
        assertEquals(3, calls)
        loop.tick(2 * UploadLoop.COOLDOWN_BASE_MILLIS + 2)
        assertEquals(4, calls)
    }

    @Test
    fun otherTransientsKeepThePlainCadence() {
        var calls = 0
        val loop = UploadLoop(
            { calls++; EEClient.Result.Transient("HTTP 502") }, loaded(1), Counters()
        )
        loop.tick(0)
        loop.tick(1)   // next tick immediately: no cooldown for non-429
        assertEquals(2, calls)
    }
}
