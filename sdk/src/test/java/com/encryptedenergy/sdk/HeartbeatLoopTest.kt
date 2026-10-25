package com.encryptedenergy.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tick semantics: deltas snapshot-and-reset before the POST, restored on
 * transient failure, dropped on terminal — the Pi worker's rules. Everything
 * runs through [HeartbeatLoop.tick] — no executor, no clock.
 */
class HeartbeatLoopTest {

    private val runtime = HeartbeatPayload.Runtime(
        osVersion = "Android 14 (API 34)",
        uptimeSeconds = 60,
        lastPacketAtMillis = null,
        gpsStatus = "no_fix",
    )

    private fun countersWithHeard(n: Int) = Counters().apply { repeat(n) { incHeard() } }

    @Test
    fun postsDeltasAndResetsOnOk() {
        val counters = countersWithHeard(2)
        var body: String? = null
        HeartbeatLoop({ body = it; EEClient.Result.Ok(200) }, counters, { runtime }).tick()
        assertTrue(body!!.contains("\"packets_heard_delta\":2"))
        assertEquals(0, counters.snapshotAndReset().heard)
    }

    @Test
    fun transientRestoresDeltas() {
        val counters = countersWithHeard(3)
        HeartbeatLoop({ EEClient.Result.Transient("network") }, counters, { runtime }).tick()
        assertEquals(3, counters.snapshotAndReset().heard)
    }

    @Test
    fun restoreAddsOnTopOfCountsSinceSnapshot() {
        val counters = countersWithHeard(3)
        val loop = HeartbeatLoop(
            { counters.incHeard(); EEClient.Result.Transient("network") },
            counters, { runtime },
        )
        loop.tick()
        assertEquals(4, counters.snapshotAndReset().heard)
    }

    @Test
    fun terminalDropsDeltas() {
        val counters = countersWithHeard(2)
        HeartbeatLoop({ EEClient.Result.Terminal(422) }, counters, { runtime }).tick()
        assertEquals(0, counters.snapshotAndReset().heard)
    }

    @Test
    fun unauthorizedDropsDeltas() {
        val counters = countersWithHeard(2)
        HeartbeatLoop({ EEClient.Result.Unauthorized }, counters, { runtime }).tick()
        assertEquals(0, counters.snapshotAndReset().heard)
    }

    @Test
    fun throwingWireCallMapsToTransientAndRestores() {
        // An uncaught exception would cancel scheduleWithFixedDelay forever;
        // the guard turns it into an ordinary transient tick.
        val counters = countersWithHeard(1)
        var result: EEClient.Result? = null
        HeartbeatLoop(
            { throw IllegalStateException("wiring bug") }, counters, { runtime }
        ) { result = it }.tick()
        assertTrue(result is EEClient.Result.Transient)
        assertEquals(1, counters.snapshotAndReset().heard)
    }

    @Test
    fun okResultReachesCallbackWithDisableFlag() {
        var result: EEClient.Result? = null
        HeartbeatLoop(
            { EEClient.Result.Ok(200, disable = true) }, Counters(), { runtime }
        ) { result = it }.tick()
        assertTrue((result as EEClient.Result.Ok).disable)
    }

    @Test
    fun throwingResultCallbackIsSwallowedAfterDeltaBookkeeping() {
        // C6: an uncaught throw would cancel scheduleWithFixedDelay forever.
        val counters = countersWithHeard(2)
        val loop = HeartbeatLoop(
            { EEClient.Result.Transient("network") }, counters, { runtime }
        ) { throw IllegalStateException("engine callback bug") }
        loop.tick()   // must not propagate
        // The transient restore already committed before the callback threw.
        assertEquals(2, counters.snapshotAndReset().heard)
    }

    @Test
    fun sdkCadenceIsFiveMinutes() {
        // Plan §3 diagram + §6 risk 3: conservative fleet cadence, NOT the
        // first-party app's 60 s. A partner's battery budget is a contract.
        assertEquals(300L, HeartbeatLoop.INTERVAL_SECONDS)
    }
}
